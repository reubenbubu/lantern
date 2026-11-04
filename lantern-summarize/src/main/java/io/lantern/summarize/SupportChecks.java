package io.lantern.summarize;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.Origin;
import io.lantern.core.types.Types;
import io.lantern.harness.Harness;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.checks.SupportJudge;
import io.lantern.harness.render.FactLines;
import io.lantern.llm.Role;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The support judge over every role's stored texts (knowledge-build epic, section 11.7; ADR 0101,
 * amendment for #751). It runs once every role has stored its texts and the injection screen has
 * run: every Investigate risk is judged, every text that cites a range the screen flagged is
 * judged, and of the rest a deterministic 10% by fingerprint. Each role's texts go in requests of
 * their own. A verdict is stored on the text's observation ({@link LlmFacts#withSupport}); it never
 * drops a text.
 */
public final class SupportChecks {

  /**
   * The roles whose texts are judged, by the name of their facts' origin, each with the model role
   * whose model wrote them and so judges them (#751).
   */
  public static final Map<String, Role> ROLES =
      Map.of(
          "summarize",
          Role.SUMMARIZE,
          "summarize-type",
          Role.SUMMARIZE_TYPES,
          "describe-unit",
          Role.INFER_INTENT,
          "describe-element",
          Role.DESCRIBE_ELEMENTS,
          io.lantern.summarize.describe.FlowJudgements.NARRATIVE.name(),
          io.lantern.summarize.describe.FlowJudgements.NARRATIVE.modelRole(),
          "name-groups",
          Role.NAME_GROUPS,
          "purpose",
          Role.INFER_INTENT,
          "investigate",
          Role.INVESTIGATE);

  /** The most lines of a cited range a judge item shows. */
  static final int MAX_RANGE_LINES = 40;

  /** A cited range of a file, as roles cite them: {@code <repo>:<path>:<from>-<to>}. */
  private static final Pattern RANGE = Pattern.compile("^([^:]+):(.+):(\\d+)-(\\d+)$");

  /**
   * What the judge found.
   *
   * @param byOrigin for each origin name with a text judged, under one of its origins, every fact
   *     of that name that holds, whatever its version, the judged ones carrying their verdict: one
   *     recording a name, which ends the facts of that name it is not given
   * @param counts the verdicts per role, the stored ones of texts judged before included
   * @param judged how many texts this run judged
   */
  public record Result(Map<Origin, List<Fact>> byOrigin, Map<String, Counts> counts, int judged) {}

  /** How many texts of a role got each verdict. */
  public record Counts(int supported, int partly, int unsupported) {

    Counts add(String verdict) {
      return switch (verdict) {
        case "supported" -> new Counts(supported + 1, partly, unsupported);
        case "partly" -> new Counts(supported, partly + 1, unsupported);
        case "unsupported" -> new Counts(supported, partly, unsupported + 1);
        default -> this;
      };
    }
  }

  /** A file range: its repository, path and lines. */
  record Place(String repo, String path, int from, int to) {

    boolean overlaps(Place other) {
      return repo.equals(other.repo)
          && path.equals(other.path)
          && from <= other.to
          && other.from <= to;
    }
  }

  private final Harness harness;
  private final SourceFiles files;
  private final Map<String, String> commits;
  private boolean byWriter = true;

  /**
   * @param harness the run's harness
   * @param files where cited ranges are read from, secrets masked
   * @param commits the snapshot's commit of each repository
   */
  public SupportChecks(Harness harness, SourceFiles files, Map<String, String> commits) {
    this.harness = Objects.requireNonNull(harness, "harness");
    this.files = Objects.requireNonNull(files, "files");
    this.commits = Map.copyOf(commits);
  }

  /**
   * Whether each text is judged on the model of the role that wrote it (the default), or every text
   * on the support judge's own model.
   */
  public SupportChecks byWriter(boolean byWriter) {
    this.byWriter = byWriter;
    return this;
  }

  /**
   * Judges the texts the roles stored.
   *
   * @param held every fact that holds in the snapshot
   * @param flagged the injection screen's findings that hold, each citing its flagged lines
   * @param snapshot the snapshot the facts handed back are recorded for
   */
  public Result judge(List<Fact> held, List<Fact> flagged, long snapshot) {
    Map<String, Fact> byId = new HashMap<>();
    held.forEach(f -> byId.putIfAbsent(f.id(), f));
    List<Place> flaggedPlaces = new ArrayList<>();
    flagged.forEach(f -> flaggedPlaces.addAll(places(f.evidence())));
    Map<String, List<SupportJudge.Text>> byRole = new TreeMap<>();
    Map<String, Fact> byKey = new LinkedHashMap<>();
    Set<String> always = new LinkedHashSet<>();
    List<Fact> texts =
        held.stream()
            .filter(SupportChecks::judgeable)
            .sorted(Comparator.comparing(Fact::id))
            .toList();
    for (Fact fact : texts) {
      if (fact.attributes().hasNonNull(LlmFacts.SUPPORT)) {
        continue;
      }
      String text = text(fact);
      List<String> cited = new ArrayList<>();
      List<Place> places = new ArrayList<>();
      for (String cite : cites(fact)) {
        Matcher range = RANGE.matcher(cite);
        if (range.matches()) {
          Place place =
              new Place(
                  range.group(1),
                  range.group(2),
                  Integer.parseInt(range.group(3)),
                  Integer.parseInt(range.group(4)));
          places.add(place);
          cited.add(rendered(cite, place));
        } else {
          Fact cites = byId.get(cite);
          if (cites != null) {
            places.addAll(places(cites.evidence()));
            cited.add("[" + cite + "] " + FactLines.line(cites));
          }
        }
      }
      if (text.isBlank() || cited.isEmpty()) {
        continue;
      }
      // Named by its claim, which is the same whichever model wrote or judges it.
      String key = fact.claimId() != null ? fact.claimId() : fact.id();
      if (byKey.containsKey(key)) {
        continue;
      }
      byKey.put(key, fact);
      byRole
          .computeIfAbsent(fact.origin().name(), r -> new ArrayList<>())
          .add(new SupportJudge.Text(key, text, cited));
      // Every risk; every text resting on what the screen flagged, whatever the sample.
      if (fact.predicate().equals(Types.INVESTIGATED_RISK)
          || places.stream().anyMatch(p -> flaggedPlaces.stream().anyMatch(p::overlaps))) {
        always.add(key);
      }
    }
    Map<String, SupportJudge.Support> verdicts =
        byRole.isEmpty()
            ? Map.of()
            : new SupportJudge(harness).judge(byRole, always, byWriter ? ROLES : Map.of());
    Map<String, Fact> judgedFacts = new HashMap<>();
    verdicts.forEach(
        (key, verdict) ->
            judgedFacts.put(byKey.get(key).id(), LlmFacts.withSupport(byKey.get(key), verdict)));
    Map<String, Origin> changed = new LinkedHashMap<>();
    verdicts
        .keySet()
        .forEach(
            key -> changed.putIfAbsent(byKey.get(key).origin().name(), byKey.get(key).origin()));
    Map<Origin, List<Fact>> byOrigin = new LinkedHashMap<>();
    for (Fact fact : held) {
      Origin under = changed.get(fact.origin().name());
      if (under != null && fact.origin().kind() == under.kind()) {
        byOrigin
            .computeIfAbsent(under, o -> new ArrayList<>())
            .add(restated(judgedFacts.getOrDefault(fact.id(), fact), snapshot));
      }
    }
    Map<String, Counts> counts = new TreeMap<>();
    Set<String> counted = new java.util.HashSet<>();
    for (Fact fact : texts) {
      // Each claim once, however many observations hold it.
      if (!counted.add(fact.claimId() != null ? fact.claimId() : fact.id())) {
        continue;
      }
      Fact now = judgedFacts.getOrDefault(fact.id(), fact);
      JsonNode support = now.attributes().get(LlmFacts.SUPPORT);
      if (support != null && support.isString()) {
        counts.merge(
            fact.origin().name(),
            new Counts(0, 0, 0).add(support.asString()),
            (a, b) ->
                new Counts(
                    a.supported() + b.supported(),
                    a.partly() + b.partly(),
                    a.unsupported() + b.unsupported()));
      }
    }
    return new Result(byOrigin, counts, verdicts.size());
  }

  /** The fact as it holds from the snapshot, as recording the snapshot's facts needs. */
  static Fact restated(Fact fact, long snapshot) {
    Fact now =
        Fact.create(
            fact.subject(),
            fact.predicate(),
            fact.object(),
            fact.attributes(),
            fact.origin(),
            fact.evidence(),
            fact.confidence(),
            io.lantern.core.fact.Validity.from(snapshot));
    return fact.lineage().isEmpty() ? now : now.withLineage(fact.lineage());
  }

  /** Whether a fact is a text a role whose texts are judged wrote. */
  static boolean judgeable(Fact fact) {
    return fact.origin().kind() == Origin.Kind.LLM
        && ROLES.containsKey(fact.origin().name())
        && TEXTS.contains(fact.predicate());
  }

  /**
   * The kinds of text a verdict is stored on: those whose type declares {@code support} ({@code
   * llm-types.json}). A lead's verdict or a group's display name is not judged.
   */
  static final Set<String> TEXTS =
      Set.of(
          Types.RESPONSIBILITY,
          Types.INTENT,
          Types.SUMMARY,
          Types.FLOW_NARRATIVE,
          Types.PURPOSE,
          Types.KEY_FACT,
          Types.INVESTIGATED_RISK,
          Types.FINDING_WORDING);

  /**
   * The words of a text: its sentence, or its title and finding, or its narrative's title and
   * walkthrough, or its sentences, as the reader sees them.
   */
  static String text(Fact fact) {
    JsonNode a = fact.attributes();
    List<String> parts = new ArrayList<>();
    for (String name :
        List.of("text", "title", "finding", "consequence", "preconditions", "postconditions")) {
      strings(a.get(name), parts);
    }
    strings(a.get("walkthrough"), parts);
    JsonNode sentences = a.get("sentences");
    if (sentences != null && sentences.isArray()) {
      sentences.forEach(s -> strings(s.get("text"), parts));
    }
    return String.join(" ", parts).strip();
  }

  /** The references a text cites: fact IDs and file ranges, each once, in order. */
  static List<String> cites(Fact fact) {
    JsonNode a = fact.attributes();
    Set<String> cites = new LinkedHashSet<>();
    List<String> found = new ArrayList<>();
    strings(a.get("cites"), found);
    JsonNode sentences = a.get("sentences");
    if (sentences != null && sentences.isArray()) {
      sentences.forEach(s -> strings(s.get("cites"), found));
    }
    JsonNode fieldCites = a.get("fieldCites");
    if (fieldCites != null && fieldCites.isObject()) {
      fieldCites.forEach(v -> strings(v, found));
    }
    cites.addAll(found);
    return List.copyOf(cites);
  }

  private static void strings(JsonNode node, List<String> into) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isString()) {
      if (!node.asString().isBlank()) {
        into.add(node.asString().strip());
      }
    } else if (node.isArray()) {
      node.forEach(n -> strings(n, into));
    } else if (node.isObject() && node.has("text")) {
      strings(node.get("text"), into);
    }
  }

  private static List<Place> places(Evidence evidence) {
    List<Place> places = new ArrayList<>();
    if (evidence == null) {
      return places;
    }
    for (Evidence.Location location : evidence.locations()) {
      if (location.path() != null && location.lines() != null) {
        places.add(
            new Place(
                location.repo(),
                location.path(),
                location.lines().start(),
                location.lines().end()));
      }
    }
    return places;
  }

  /** A cited range as the judge sees it: its reference, then its lines as the file holds them. */
  private String rendered(String cite, Place place) {
    StringBuilder text = new StringBuilder("[").append(cite).append(']');
    Optional<byte[]> content =
        Optional.ofNullable(commits.get(place.repo()))
            .flatMap(commit -> files.read(place.repo(), commit, place.path()));
    if (content.isPresent()) {
      String[] lines = new String(content.get(), StandardCharsets.UTF_8).split("\n", -1);
      int last = Math.min(lines.length, Math.min(place.to(), place.from() + MAX_RANGE_LINES - 1));
      for (int i = Math.max(1, place.from()); i <= last; i++) {
        text.append('\n').append(i).append(": ").append(lines[i - 1]);
      }
    }
    return text.toString();
  }
}
