package io.lantern.link;

import io.lantern.harness.FileRead;
import io.lantern.harness.Harness;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Investigate's tools over the code (#893): {@code shell}, a command in a sealed container over a
 * read-only copy of the repositories; {@code find_in_code}, the lines that hold a text, each with
 * the Java declaration around it; and {@code outline}, a Java file's declarations with their lines.
 * No tool reads a whole file: every answer is cut to whole lines of at most {@value #WIDTH}
 * characters and about {@value #CHARS} characters in all, with a footer naming the exact narrower
 * call that gives the rest, and is never refused for its size. The code they read was masked when
 * it was written ({@link ShellWorkspace}). One instance serves one conversation: it records which
 * lines of which files its answers showed, for the check that an answer cites only lines it was
 * shown, the files an answer rests on, and the texts the injection screen reads.
 */
final class ShellTools implements CodeTools {

  static final String SHELL = "shell";
  static final String FIND_IN_CODE = GraphTools.FIND_IN_CODE;
  static final String OUTLINE = "outline";

  /** The longest line an answer shows; a longer one is cut and ends with {@code …}. */
  static final int WIDTH = 160;

  /** About how many characters an answer shows, to whole lines, before its footer. */
  static final int CHARS = 1_000;

  /** The most characters an outline shows. */
  static final int OUTLINE_CHARS = 24_000;

  /** The most hits {@code find_in_code} gives. */
  static final int HITS = 30;

  /** How long a command may run, as its description and its stop note say. */
  static final int SECONDS = 20;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static final String SHELL_DESCRIPTION =
      "Runs a shell command over a read-only copy of the repositories (one folder each in the"
          + " working folder). Use pipes and && freely; cd works. Tools: grep, rg, find, ls, cat,"
          + " head, tail, sed, awk, jq, yq, xmllint, sort, uniq, wc, cut, tr, nl, tree, diff, xargs"
          + " and the usual text tools; no git, no network, nothing can be written. Ask for"
          + " numbered lines: grep -rn, or nl -ba FILE | sed -n 'FROM,TOp'. Keep answers small:"
          + " end with | head, | cut -c1-120, or count first with | wc -l. For Spring YAML, yq"
          + " -o=props FILE gives each setting's full key.";

  static final String FIND_DESCRIPTION =
      "The lines of the repositories' files that hold a text exactly, case kept, up to "
          + HITS
          + ", each as repo:path:line: text, and in a Java file the declaration around it. Name a"
          + " repo to search only it.";

  static final String OUTLINE_DESCRIPTION =
      "A Java file's types, constructors and methods, each as from-to: its annotations and"
          + " signature, in order: a guide to which lines to read, never something to cite.";

  private static final Pattern GREP_LINE = Pattern.compile("^(?:\\./)?([^:\\s]+):(\\d+)[:-](.*)$");
  private static final Pattern NL_LINE = Pattern.compile("^\\s*(\\d+)(?:\\t|[:-])(.*)$");
  private static final Pattern CD =
      Pattern.compile("(?:^|[;&|(]\\s*)cd\\s+(?:/work/)?([^\\s;&|)/]+)");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_./@-]+");

  private final ShellWorkspace workspace;
  private final GraphTools.Graph graph;
  private final GraphTools excerpts;
  private final Map<String, BitSet> shown = new LinkedHashMap<>();
  private final Map<String, FileRead> read = new LinkedHashMap<>();

  ShellTools(ShellWorkspace workspace, GraphTools.Graph graph) {
    this.workspace = workspace;
    this.graph = graph;
    // Excerpts of the request count as shown, as with the graph's tools (#715).
    this.excerpts = graph.tools();
  }

  @Override
  public List<LlmTool> tools() {
    return List.of(
        new LlmTool(
            SHELL,
            SHELL_DESCRIPTION,
            schema(
                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\","
                    + "\"description\":\"The command, run with sh -c in the working folder.\"}},"
                    + "\"required\":[\"command\"]}")),
        new LlmTool(
            FIND_IN_CODE,
            FIND_DESCRIPTION,
            schema(
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                    + "\"description\":\"The text to look for, as written.\"},"
                    + "\"repo\":{\"type\":\"string\",\"description\":\"Only this repository.\"}},"
                    + "\"required\":[\"text\"]}")),
        new LlmTool(
            OUTLINE,
            OUTLINE_DESCRIPTION,
            schema(
                "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},"
                    + "\"path\":{\"type\":\"string\",\"description\":\"The Java file's path in"
                    + " the repository.\"}},\"required\":[\"repo\",\"path\"]}")));
  }

  @Override
  public List<LlmTool> leadTools() {
    return tools();
  }

  @Override
  public List<LlmTool> exploreTools() {
    return tools();
  }

  private static JsonNode schema(String json) {
    return JSON.readTree(json);
  }

  @Override
  public LlmToolResult run(LlmToolCall call) {
    JsonNode in = call.input();
    return switch (call.name()) {
      case SHELL -> {
        String command = in.path("command").asString("");
        yield command.isBlank()
            ? LlmToolResult.error(call, "Give the command to run as command.")
            : LlmToolResult.of(call, shell(command));
      }
      case FIND_IN_CODE -> {
        String text = in.path("text").asString("");
        String repo = in.path("repo").asString("");
        if (text.isBlank()) {
          yield LlmToolResult.error(call, "Give the text to look for as text.");
        }
        if (!repo.isBlank() && !workspace.repos().contains(repo)) {
          yield LlmToolResult.error(call, noRepo(repo));
        }
        yield LlmToolResult.of(call, find(text, repo.isBlank() ? null : repo));
      }
      case OUTLINE -> {
        String repo = in.path("repo").asString("");
        String path = in.path("path").asString("");
        if (!workspace.repos().contains(repo)) {
          yield LlmToolResult.error(call, noRepo(repo));
        }
        yield outline(call, repo, path);
      }
      default -> LlmToolResult.error(call, "This task offers no tool " + call.name() + ".");
    };
  }

  private String noRepo(String repo) {
    return "No repository named "
        + repo
        + ". The repositories: "
        + String.join(", ", workspace.repos())
        + ".";
  }

  // ---------------------------------------------------------------------------------------------
  // shell

  String shell(String command) {
    ShellWorkspace.Run run = workspace.run(command);
    String output = !run.out().isEmpty() ? run.out() : run.err();
    List<String> lines = output.isEmpty() ? List.of() : List.of(output.split("\n", -1));
    if (!lines.isEmpty() && lines.getLast().isEmpty()) {
      lines = lines.subList(0, lines.size() - 1);
    }
    Cut cut = cut(lines, CHARS);
    StringBuilder answer = new StringBuilder(String.join("\n", cut.shown()));
    if (lines.isEmpty()) {
      answer.append("(no output)");
    }
    if (cut.shown().size() < lines.size()) {
      answer
          .append("\n[showing the first ")
          .append(cut.shown().size())
          .append(" of ")
          .append(lines.size())
          .append(" lines; the rest: ")
          .append(command.strip())
          .append(" | sed -n '")
          .append(cut.shown().size() + 1)
          .append(',')
          .append(lines.size())
          .append("p'")
          .append(cut.lineCut() ? " | cut -c1-" + WIDTH : "")
          .append(']');
    } else if (cut.lineCut()) {
      answer.append("\n[long lines cut at ").append(WIDTH).append(" characters]");
    }
    if (run.overCeiling()) {
      answer.append("\n[the output was longer than Lantern reads; narrow the command]");
    }
    if (run.timedOut()) {
      answer.append("\n[stopped after ").append(SECONDS).append(" s]");
    } else if (run.exitCode() != 0) {
      answer.append("\n[exit status ").append(run.exitCode()).append(']');
    }
    attribute(command, cut.shown());
    return answer.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // find_in_code

  String find(String text, String repo) {
    String command =
        "grep -rnFI -e "
            + quote(text)
            + " -- "
            + (repo == null ? "*" : quote(repo))
            + " 2>/dev/null";
    ShellWorkspace.Run run = workspace.run(command);
    List<String[]> hits = new ArrayList<>();
    Map<String, Integer> byRepo = new TreeMap<>();
    for (String line : run.out().split("\n")) {
      Matcher m = GREP_LINE.matcher(line);
      if (!m.matches()) {
        continue;
      }
      String where = m.group(1);
      int slash = where.indexOf('/');
      if (slash < 0) {
        continue;
      }
      String r = where.substring(0, slash);
      byRepo.merge(r, 1, Integer::sum);
      hits.add(new String[] {r, where.substring(slash + 1), m.group(2), m.group(3)});
    }
    if (hits.isEmpty()) {
      return "No line of the files holds \"" + text + "\".";
    }
    int total = hits.size();
    List<String> lines = new ArrayList<>();
    for (String[] h : hits.subList(0, Math.min(HITS, hits.size()))) {
      String line = h[0] + ":" + h[1] + ":" + h[2] + ": " + h[3].strip();
      String around = enclosing(h[0], h[1], Integer.parseInt(h[2]));
      lines.add(around == null ? line : line + " " + around);
    }
    Cut cut = cut(lines, CHARS);
    StringBuilder answer = new StringBuilder(String.join("\n", cut.shown()));
    for (int i = 0; i < cut.shown().size(); i++) {
      String[] h = hits.get(i);
      mark(h[0], h[1], Integer.parseInt(h[2]));
    }
    if (cut.shown().size() < total || run.overCeiling()) {
      answer
          .append("\n[showing ")
          .append(cut.shown().size())
          .append(" of ")
          .append(run.overCeiling() ? "more than " + total : total)
          .append(" hits; per repository: ");
      List<String> counts = new ArrayList<>();
      byRepo.forEach((r, n) -> counts.add(r + " " + n));
      answer.append(String.join(", ", counts));
      if (repo == null && byRepo.size() > 1) {
        String biggest =
            byRepo.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        answer
            .append("; narrower: find_in_code(text=\"")
            .append(text)
            .append("\", repo=\"")
            .append(biggest)
            .append("\")");
      } else {
        answer.append("; narrower: a longer text, or shell with grep -rn and | sed -n");
      }
      answer.append(']');
    } else if (cut.lineCut()) {
      answer.append("\n[long lines cut at ").append(WIDTH).append(" characters]");
    }
    return answer.toString();
  }

  /** The Java declaration around a line, as {@code (in name, lines A-B)}; null if none. */
  private String enclosing(String repo, String path, int line) {
    if (!path.endsWith(".java")) {
      return null;
    }
    return workspace
        .lines(repo, path)
        .map(JavaOutline::of)
        .map(d -> JavaOutline.enclosing(d, line))
        .map(d -> "(in " + d.name() + ", lines " + d.from() + "-" + d.to() + ")")
        .orElse(null);
  }

  static String quote(String text) {
    return "'" + text.replace("'", "'\\''") + "'";
  }

  // ---------------------------------------------------------------------------------------------
  // outline

  private LlmToolResult outline(LlmToolCall call, String repo, String path) {
    if (!path.endsWith(".java")) {
      return LlmToolResult.error(
          call, "outline reads Java files; for " + path + " use shell, for example grep -n.");
    }
    Optional<List<String>> lines = workspace.lines(repo, path);
    if (lines.isEmpty()) {
      return LlmToolResult.error(call, "No file " + path + " in " + repo + ".");
    }
    List<String> out = new ArrayList<>();
    for (JavaOutline.Declaration d : JavaOutline.of(lines.get())) {
      out.add(d.from() + "-" + d.to() + ": " + d.text());
    }
    if (out.isEmpty()) {
      return LlmToolResult.of(call, "No declarations found in " + repo + ":" + path + ".");
    }
    Cut cut = cut(out, OUTLINE_CHARS);
    StringBuilder answer =
        new StringBuilder(repo + ":" + path + ", " + lines.get().size() + " lines\n")
            .append(String.join("\n", cut.shown()));
    if (cut.shown().size() < out.size()) {
      answer
          .append("\n[showing ")
          .append(cut.shown().size())
          .append(" of ")
          .append(out.size())
          .append(" declarations; the rest: shell with grep -n on the file]");
    }
    return LlmToolResult.of(call, answer.toString());
  }

  // ---------------------------------------------------------------------------------------------
  // Cutting

  /** The lines an answer shows, each at most {@link #WIDTH} characters; whether any was cut. */
  record Cut(List<String> shown, boolean lineCut) {}

  /** The first whole lines that fit in about {@code chars} characters, at least one. */
  static Cut cut(List<String> lines, int chars) {
    List<String> shown = new ArrayList<>();
    boolean lineCut = false;
    int used = 0;
    for (String line : lines) {
      String l = line;
      if (l.length() > WIDTH) {
        l = l.substring(0, WIDTH - 1) + "…";
        lineCut = true;
      }
      if (!shown.isEmpty() && used + l.length() + 1 > chars) {
        break;
      }
      shown.add(l);
      used += l.length() + 1;
    }
    return new Cut(shown, lineCut);
  }

  // ---------------------------------------------------------------------------------------------
  // What the answers showed

  /**
   * Records the lines of files an answer showed: a line given with its file and number ({@code
   * path:12:...}), a numbered line ({@code nl}) of the one file the command names, or a line whose
   * text is a line of a file the command or the answer names.
   */
  private synchronized void attribute(String command, List<String> answer) {
    String cd = null;
    Matcher m = CD.matcher(command);
    while (m.find()) {
      cd = m.group(1);
    }
    Set<String[]> files = new LinkedHashSet<>();
    collect(command, cd, files);
    for (String line : answer) {
      collect(line, cd, files);
    }
    List<String[]> named = new ArrayList<>(files);
    for (String line : answer) {
      Matcher grep = GREP_LINE.matcher(line);
      if (grep.matches()) {
        String[] file = resolve(grep.group(1), cd);
        if (file != null) {
          mark(file[0], file[1], Integer.parseInt(grep.group(2)));
          continue;
        }
      }
      Matcher nl = NL_LINE.matcher(line);
      if (nl.matches() && named.size() == 1) {
        String[] file = named.getFirst();
        int n = Integer.parseInt(nl.group(1));
        Optional<List<String>> lines = workspace.lines(file[0], file[1]);
        if (lines.isPresent()
            && n >= 1
            && n <= lines.get().size()
            && same(lines.get().get(n - 1), nl.group(2))) {
          mark(file[0], file[1], n);
          continue;
        }
      }
      String text = line.strip();
      if (!meaningful(text)) {
        continue;
      }
      for (String[] file : named) {
        List<String> lines = workspace.lines(file[0], file[1]).orElse(List.of());
        for (int i = 0; i < lines.size(); i++) {
          if (lines.get(i).strip().equals(text)
              || (text.endsWith("…")
                  && lines.get(i).strip().startsWith(text.substring(0, text.length() - 1)))) {
            mark(file[0], file[1], i + 1);
          }
        }
      }
    }
  }

  private void collect(String text, String cd, Set<String[]> files) {
    Matcher token = TOKEN.matcher(text);
    while (token.find() && files.size() < 20) {
      String t = token.group();
      if (!t.contains("/") && !t.contains(".")) {
        continue;
      }
      String[] file = resolve(t.replaceFirst(":\\d+.*$", ""), cd);
      if (file != null
          && files.stream().noneMatch(f -> f[0].equals(file[0]) && f[1].equals(file[1]))) {
        files.add(file);
      }
    }
  }

  /** A path as a command or answer writes it, as a repository and a path in it; or null. */
  private String[] resolve(String written, String cd) {
    String p = written.replaceFirst("^/work/", "").replaceFirst("^\\./", "");
    int slash = p.indexOf('/');
    if (slash > 0 && workspace.has(p.substring(0, slash), p.substring(slash + 1))) {
      return new String[] {p.substring(0, slash), p.substring(slash + 1)};
    }
    if (cd != null && workspace.has(cd, p)) {
      return new String[] {cd, p};
    }
    int colon = p.indexOf(':');
    if (colon > 0 && workspace.has(p.substring(0, colon), p.substring(colon + 1))) {
      return new String[] {p.substring(0, colon), p.substring(colon + 1)};
    }
    return null;
  }

  private static boolean same(String fileLine, String shownLine) {
    String a = fileLine.strip();
    String b = shownLine.strip();
    return a.equals(b) || (b.endsWith("…") && a.startsWith(b.substring(0, b.length() - 1)));
  }

  /**
   * A line long enough to tell where it comes from: four characters, a letter or digit among them.
   */
  private static boolean meaningful(String text) {
    return text.length() >= 4 && text.chars().anyMatch(Character::isLetterOrDigit);
  }

  private synchronized void mark(String repo, String path, int line) {
    shown.computeIfAbsent(repo + '\u0000' + path, k -> new BitSet()).set(line);
    String key = repo + '\u0000' + path;
    if (!read.containsKey(key)) {
      graph.fileRead(repo, path).ifPresent(f -> read.put(key, f));
    }
  }

  @Override
  public void supplied(String request) {
    excerpts.supplied(request);
  }

  /**
   * Whether every line of a range was shown: by a tool's answer or an excerpt of the request. A
   * line too short to tell apart (blank, a brace) counts as shown when a line of the range was.
   */
  @Override
  public synchronized boolean showed(String repo, String path, int from, int to) {
    if (excerpts.showed(repo, path, from, to)) {
      return true;
    }
    BitSet lines = shown.get(repo + '\u0000' + path);
    if (lines == null || from < 1 || to < from) {
      return false;
    }
    List<String> text = workspace.lines(repo, path).orElse(List.of());
    boolean any = false;
    for (int n = from; n <= to; n++) {
      if (lines.get(n) || excerpts.showed(repo, path, n, n)) {
        any = true;
      } else if (n > text.size() || meaningful(text.get(n - 1).strip())) {
        return false;
      }
    }
    return any;
  }

  @Override
  public synchronized List<FileRead> filesRead() {
    Map<String, FileRead> all = new LinkedHashMap<>(read);
    excerpts.filesRead().forEach(f -> all.putIfAbsent(f.repo() + '\u0000' + f.path(), f));
    return List.copyOf(all.values());
  }

  @Override
  public synchronized List<Harness.Shown> textsShown() {
    List<Harness.Shown> texts = new ArrayList<>(excerpts.textsShown());
    for (FileRead f : read.values()) {
      workspace
          .lines(f.repo(), f.path())
          .ifPresent(
              lines ->
                  texts.add(
                      new Harness.Shown(f.repo(), f.path(), f.blob(), String.join("\n", lines))));
    }
    return texts;
  }
}
