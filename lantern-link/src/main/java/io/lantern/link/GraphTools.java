package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactObject;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.config.SourceSecrets;
import io.lantern.extract.config.UrlCredentials;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.ToolBox;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tools that let the model look further than its prompt (ADR 0081): look up the facts about an
 * entity, search the facts for a text, and read a range of lines of a file of the snapshot's
 * repositories at the snapshot's commit. The Resolve role uses them, and the Investigate role
 * (#269) builds on them.
 *
 * <p>Every fact a tool shows gets a reference ({@code T1}, {@code T2}, ...), kept for the whole
 * conversation, which an answer may cite like a prompt's references; {@link #shown()} gives what
 * they stand for. Facts are written as the resolver's prompt writes them, without fact IDs or line
 * numbers, and listed in a fixed order, so the same snapshot gives the same results and recorded
 * conversations replay. A file is shown with its secrets masked: Java source as summaries show it
 * (ADR 0054), any other file with the value of each secret key masked and credentials removed from
 * URLs.
 *
 * <p>One instance serves one conversation ({@link Graph#tools()}); not thread-safe.
 */
public final class GraphTools implements ToolBox {

  /** The files of the snapshot's repositories, at the snapshot's commit of each. */
  @FunctionalInterface
  public interface RepoFiles {

    /** Reads no file: the {@code read_file} tool is not offered. */
    RepoFiles NONE = (repo, path) -> Optional.empty();

    /**
     * The content of a file, or empty if it cannot be read.
     *
     * @param repo the repository name, as in the facts' evidence
     * @param path the file path relative to the repository root
     */
    Optional<byte[]> read(String repo, String path);
  }

  static final String FACTS_ABOUT = "facts_about";
  static final String SEARCH_FACTS = "search_facts";
  static final String READ_FILE = "read_file";

  /** The most facts one call shows. */
  static final int MAX_FACTS = 40;

  /** The most lines one read shows. */
  static final int MAX_LINES = 200;

  /** The most characters one result holds, so one call cannot fill the context window. */
  static final int MAX_CHARS = 24_000;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A key and its value on one line of a config file: {@code key: value} or {@code key=value}. */
  private static final Pattern KEY_VALUE =
      Pattern.compile("^(\\s*(?:-\\s+)?[\"']?)([A-Za-z0-9_.\\-\\[\\]]+)([\"']?\\s*[:=]\\s*)(.+)$");

  private static final Pattern ONLY_PLACEHOLDERS = Pattern.compile("[\"']?(\\$\\{[^}:]+})+[\"']?");

  /**
   * A snapshot's facts and files, prepared once for the tools of many conversations. Thread-safe.
   */
  public static final class Graph {

    private final List<Fact> facts;
    private final Map<String, String> rendered = new HashMap<>();
    private final Set<String> repos = new TreeSet<>();
    private final RepoFiles files;

    /**
     * @param facts the facts that hold in the snapshot
     * @param files the snapshot's files; {@link RepoFiles#NONE} offers no {@code read_file}
     */
    public Graph(List<Fact> facts, RepoFiles files) {
      this.files = Objects.requireNonNull(files, "files");
      List<Fact> sorted = new ArrayList<>();
      for (Fact fact : Objects.requireNonNull(facts, "facts")) {
        if (rendered.putIfAbsent(fact.id(), ResolvePrompt.fact(fact)) == null) {
          sorted.add(fact);
        }
        repos.add(fact.evidence().repo());
      }
      // A fixed order, whatever order the facts came in, so results do not change between runs.
      sorted.sort(Comparator.comparing((Fact f) -> rendered.get(f.id())).thenComparing(Fact::id));
      this.facts = List.copyOf(sorted);
    }

    /** New tools for one conversation, with references of their own. */
    public GraphTools tools() {
      return new GraphTools(this);
    }
  }

  private final List<Fact> facts;
  private final Map<String, String> rendered;
  private final Set<String> repos;
  private final RepoFiles files;
  private final Map<String, Fact> shown = new LinkedHashMap<>();
  private final Map<String, String> refs = new HashMap<>();

  private GraphTools(Graph graph) {
    this.facts = graph.facts;
    this.rendered = graph.rendered;
    this.repos = graph.repos;
    this.files = graph.files;
  }

  /** The facts the tools have shown so far, by reference, in the order shown. */
  public Map<String, Fact> shown() {
    return Map.copyOf(shown);
  }

  @Override
  public List<LlmTool> tools() {
    List<LlmTool> tools = new ArrayList<>();
    ObjectNode about = schema();
    property(about, "entity", "string", "An entity ID as the facts write it, such as service:g:a");
    property(about, "predicate", "string", "Only facts with this predicate, such as endpoint");
    about.putArray("required").add("entity");
    tools.add(
        new LlmTool(
            FACTS_ABOUT,
            "Lists the facts static analysis extracted about one entity: those it is the subject"
                + " of, then those that point to it. Each fact gets a reference such as [T1] that"
                + " an answer may cite.",
            about));
    ObjectNode search = schema();
    property(search, "text", "string", "Text the fact holds, matched ignoring case");
    property(search, "predicate", "string", "Only facts with this predicate, such as endpoint");
    search.putArray("required").add("text");
    tools.add(
        new LlmTool(
            SEARCH_FACTS,
            "Finds the facts whose subject, predicate, value, attributes or file hold a text, such"
                + " as a class, property, path, topic or host name. Each fact gets a reference"
                + " such as [T1] that an answer may cite.",
            search));
    if (files != RepoFiles.NONE) {
      ObjectNode read = schema();
      property(read, "repo", "string", "The repository, as a fact's file names it before the :");
      property(read, "path", "string", "The file's path in the repository");
      property(read, "from", "integer", "The first line, from 1; default 1");
      property(read, "to", "integer", "The last line; at most " + MAX_LINES + " lines a read");
      read.putArray("required").add("repo").add("path");
      tools.add(
          new LlmTool(
              READ_FILE,
              "Reads lines of a file of the analyzed repositories at the analyzed commit, with"
                  + " line numbers and secrets masked. A file is not a fact and cannot be cited;"
                  + " cite the facts about what it shows.",
              read));
    }
    return List.copyOf(tools);
  }

  private static ObjectNode schema() {
    ObjectNode schema = JSON.createObjectNode().put("type", "object");
    schema.putObject("properties");
    return schema;
  }

  private static void property(ObjectNode schema, String name, String type, String description) {
    ((ObjectNode) schema.get("properties"))
        .putObject(name)
        .put("type", type)
        .put("description", description);
  }

  @Override
  public LlmToolResult run(LlmToolCall call) {
    JsonNode input = call.input();
    return switch (call.name()) {
      case FACTS_ABOUT -> {
        String entity = text(input, "entity");
        if (entity == null) {
          yield LlmToolResult.error(call, "Give the entity, an entity ID such as service:g:a.");
        }
        yield LlmToolResult.of(call, factsAbout(entity.strip(), text(input, "predicate")));
      }
      case SEARCH_FACTS -> {
        String text = text(input, "text");
        if (text == null || text.isBlank()) {
          yield LlmToolResult.error(call, "Give the text to search for.");
        }
        yield LlmToolResult.of(call, search(text.strip(), text(input, "predicate")));
      }
      case READ_FILE -> {
        if (files == RepoFiles.NONE) {
          yield LlmToolResult.error(call, "No files can be read.");
        }
        yield read(call, input);
      }
      default -> LlmToolResult.error(call, "There is no tool named " + call.name() + ".");
    };
  }

  private static String text(JsonNode input, String field) {
    JsonNode value = input.get(field);
    return value == null || !value.isString() ? null : value.stringValue();
  }

  private String factsAbout(String entity, String predicate) {
    EntityId id;
    try {
      id = EntityId.parse(entity);
    } catch (RuntimeException e) {
      return "Not an entity ID: " + entity + ". Use " + SEARCH_FACTS + " to find one.";
    }
    List<Fact> subject = new ArrayList<>();
    List<Fact> object = new ArrayList<>();
    for (Fact fact : facts) {
      if (predicate != null && !fact.predicate().equals(predicate)) {
        continue;
      }
      if (fact.subject().equals(id)) {
        subject.add(fact);
      } else if (fact.object().kind() == FactObject.Kind.ENTITY
          && fact.object().value().equals(id.toString())) {
        object.add(fact);
      }
    }
    if (subject.isEmpty() && object.isEmpty()) {
      return "No facts about "
          + id
          + (predicate == null ? "" : " with the predicate " + predicate)
          + ". Use "
          + SEARCH_FACTS
          + " to find the entity's ID.";
    }
    List<Fact> all = new ArrayList<>(subject);
    all.addAll(object);
    return list(all, "Facts about " + id);
  }

  private String search(String text, String predicate) {
    String needle = text.toLowerCase(Locale.ROOT);
    List<Fact> found = new ArrayList<>();
    for (Fact fact : facts) {
      if ((predicate == null || fact.predicate().equals(predicate))
          && rendered.get(fact.id()).toLowerCase(Locale.ROOT).contains(needle)) {
        found.add(fact);
      }
    }
    if (found.isEmpty()) {
      return "No facts hold \"" + text + "\".";
    }
    return list(found, "Facts that hold \"" + text + "\"");
  }

  /** The facts with their references, at most {@link #MAX_FACTS} and {@link #MAX_CHARS}. */
  private String list(List<Fact> found, String title) {
    StringBuilder out = new StringBuilder(title).append(":\n");
    int listed = 0;
    for (Fact fact : found) {
      if (listed == MAX_FACTS) {
        break;
      }
      String line = "[" + ref(fact) + "] " + rendered.get(fact.id()) + "\n";
      if (out.length() + line.length() > MAX_CHARS) {
        break;
      }
      out.append(line);
      listed++;
    }
    if (listed < found.size()) {
      out.append("... and ")
          .append(found.size() - listed)
          .append(" more; narrow the call, for example with a predicate.\n");
    }
    return out.toString();
  }

  private String ref(Fact fact) {
    return refs.computeIfAbsent(
        fact.id(),
        id -> {
          String ref = "T" + (shown.size() + 1);
          shown.put(ref, fact);
          return ref;
        });
  }

  private LlmToolResult read(LlmToolCall call, JsonNode input) {
    String repo = text(input, "repo");
    String path = text(input, "path");
    if (repo == null || path == null || path.isBlank()) {
      return LlmToolResult.error(call, "Give the repo and the path.");
    }
    if (!repos.contains(repo)) {
      return LlmToolResult.error(
          call, "No repository named " + repo + ". The repositories: " + String.join(", ", repos));
    }
    path = path.strip();
    while (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (path.isEmpty() || path.contains("..") || path.contains("\\")) {
      return LlmToolResult.error(call, "Not a path in the repository: " + text(input, "path"));
    }
    Optional<byte[]> content = files.read(repo, path);
    if (content.isEmpty()) {
      return LlmToolResult.error(call, "No file " + path + " in " + repo + ".");
    }
    String text = masked(path, new String(content.get(), StandardCharsets.UTF_8));
    List<String> lines = text.lines().toList();
    int from = Math.max(1, input.path("from").asInt(1));
    int to = input.path("to").isNumber() ? input.path("to").asInt() : from + MAX_LINES - 1;
    to = Math.min(Math.min(to, from + MAX_LINES - 1), lines.size());
    if (from > lines.size()) {
      return LlmToolResult.error(
          call, path + " in " + repo + " has " + lines.size() + " lines; start at most there.");
    }
    if (to < from) {
      return LlmToolResult.error(call, "The last line must not be before the first.");
    }
    StringBuilder out =
        new StringBuilder()
            .append(repo)
            .append(':')
            .append(path)
            .append(", lines ")
            .append(from)
            .append(" to ")
            .append(to)
            .append(" of ")
            .append(lines.size())
            .append(":\n");
    for (int i = from; i <= to; i++) {
      String line = i + ": " + lines.get(i - 1) + "\n";
      if (out.length() + line.length() > MAX_CHARS) {
        out.append("... cut off; read from line ").append(i).append(".\n");
        break;
      }
      out.append(line);
    }
    return LlmToolResult.of(call, out.toString());
  }

  /** A file's text as the model may see it: its secrets masked. */
  static String masked(String path, String text) {
    if (path.endsWith(".java")) {
      return SourceSecrets.scrub(text);
    }
    StringBuilder out = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      Matcher kv = KEY_VALUE.matcher(line);
      String shown = line;
      if (kv.matches()
          && ConfigExtractor.isSecret(kv.group(2))
          && !ONLY_PLACEHOLDERS.matcher(kv.group(4).strip()).matches()) {
        shown = kv.group(1) + kv.group(2) + kv.group(3) + "******";
      } else {
        shown = UrlCredentials.withoutCredentials(line);
      }
      out.append(shown).append('\n');
    }
    out.setLength(out.length() - 1);
    return out.toString();
  }
}
