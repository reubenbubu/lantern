package io.lantern.compose;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.harness.LlmFacts;
import io.lantern.harness.RoleSpec;
import io.lantern.harness.checks.SupportJudge;
import io.lantern.summarize.Summary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the summaries and wordings tests used to hand the composer as the text facts the LLM roles
 * store, which is all the composer reads (knowledge-build epic, section 12).
 */
final class TestTexts {

  private static final RoleSpec ROLE = DescribeRole.UNITS;
  private static final Pattern MARKER = Pattern.compile("\\[(fact|summary):([^\\]\\s]+)\\]");

  private TestTexts() {}

  /** The facts with the summaries as stored summary facts. */
  static List<Fact> with(Collection<Fact> facts, Map<EntityId, Summary> summaries) {
    return with(facts, summaries, Wordings.NONE);
  }

  /** The facts with the wordings as stored text facts. */
  static List<Fact> with(Collection<Fact> facts, Wordings wordings) {
    return with(facts, Map.of(), wordings);
  }

  /** The facts with the summaries and the wordings as stored text facts. */
  static List<Fact> with(
      Collection<Fact> facts, Map<EntityId, Summary> summaries, Wordings wordings) {
    Map<String, Fact> byId = new HashMap<>();
    facts.forEach(f -> byId.putIfAbsent(f.id(), f));
    List<Fact> all = new ArrayList<>(facts);
    all.addAll(summaries(summaries, byId));
    all.addAll(wordings(wordings, byId));
    return all;
  }

  /**
   * The summaries as the summarizer stores them ({@link io.lantern.summarize.Summarizer#facts}),
   * given to it as it gives them: every summary after the summaries it cites.
   */
  private static List<Fact> summaries(Map<EntityId, Summary> summaries, Map<String, Fact> byId) {
    Map<String, Summary> byEntity = new java.util.TreeMap<>();
    summaries.values().forEach(sm -> byEntity.put(sm.entity().toString(), sm));
    Map<EntityId, Summary> ordered = new java.util.LinkedHashMap<>();
    byEntity.keySet().forEach(e -> after(e, byEntity, ordered, new java.util.HashSet<>()));
    io.lantern.summarize.Summarizer.Result result =
        new io.lantern.summarize.Summarizer.Result(
            ordered, ordered.size(), 0, List.of(), List.of(), List.of());
    List<Fact> out = new ArrayList<>();
    io.lantern.summarize.Summarizer.facts(result, byId.values(), 1).values().forEach(out::addAll);
    return out;
  }

  private static void after(
      String entity,
      Map<String, Summary> byEntity,
      Map<EntityId, Summary> ordered,
      Set<String> visiting) {
    Summary summary = byEntity.get(entity);
    if (summary == null || ordered.containsKey(summary.entity()) || !visiting.add(entity)) {
      return;
    }
    Matcher marker = MARKER.matcher(summary.text());
    while (marker.find()) {
      if (marker.group(1).equals("summary")) {
        after(marker.group(2), byEntity, ordered, visiting);
      }
    }
    ordered.put(summary.entity(), summary);
  }

  private static List<Fact> wordings(Wordings wordings, Map<String, Fact> byId) {
    List<Fact> out = new ArrayList<>();
    wordings
        .responsibilities()
        .forEach(
            (element, marked) -> {
              List<Fact> cited = cited(marked, byId);
              if (cited.isEmpty()) {
                return;
              }
              boolean evidenced =
                  cited.stream().anyMatch(f -> wordings.documented().contains(f.id()));
              Fact fact =
                  element.equals(RepoSections.SYSTEM)
                      ? system(LlmFacts.PURPOSE, text(marked), cited, evidenced)
                      : LlmFacts.text(
                          ROLE,
                          "test/test",
                          element,
                          LlmFacts.RESPONSIBILITY,
                          LlmFacts.C4,
                          text(marked),
                          cited,
                          evidenced ? LlmFacts.Basis.EVIDENCED : LlmFacts.Basis.INFERRED,
                          1);
              if (wordings.unsupported().contains(element)) {
                fact = LlmFacts.withSupport(fact, SupportJudge.Support.UNSUPPORTED);
              }
              out.add(fact);
            });
    if (wordings.keyFact() != null) {
      List<Fact> cited = cited(wordings.keyFact(), byId);
      if (!cited.isEmpty()) {
        out.add(system(LlmFacts.KEY_FACT, text(wordings.keyFact()), cited, false));
      }
    }
    wordings
        .phrases()
        .forEach(
            (linkId, phrase) -> {
              Fact link = byId.get(linkId);
              if (link == null) {
                return;
              }
              List<Fact> cited = new ArrayList<>();
              phrase
                  .facts()
                  .forEach(id -> java.util.Optional.ofNullable(byId.get(id)).ifPresent(cited::add));
              if (cited.isEmpty()) {
                cited.add(link);
              }
              out.add(
                  LlmFacts.text(
                      ROLE,
                      "test/test",
                      link.subject(),
                      LlmFacts.INTENT,
                      link.claimId() != null ? link.claimId() : link.id(),
                      phrase.text(),
                      cited,
                      LlmFacts.Basis.INFERRED,
                      1));
            });
    wordings
        .chosen()
        .forEach(
            (scope, flows) -> {
              for (int i = 0; i < flows.size(); i++) {
                ObjectNode attributes = FactJson.newObject();
                attributes.put("significant", true);
                attributes.put("category", "other");
                attributes.put("rank", i + 1);
                attributes.putArray("cites");
                attributes.put("basis", "inferred");
                out.add(
                    Fact.create(
                        EntityId.parse(flows.get(i)),
                        LlmFacts.FLOW_SIGNIFICANCE,
                        FactObject.string(scope),
                        attributes,
                        LlmFacts.origin(ROLE),
                        anyEvidence(byId),
                        LlmFacts.CONFIDENCE,
                        Validity.from(1)));
              }
            });
    return out;
  }

  /** A stored narrative of the flow, as the flow narrative role writes it. */
  static Fact narrative(EntityId flow, Wordings.Flow wording, Collection<Fact> facts) {
    Map<String, Fact> byId = new HashMap<>();
    facts.forEach(f -> byId.putIfAbsent(f.id(), f));
    ObjectNode attributes = FactJson.newObject();
    ObjectNode fieldCites = FactJson.newObject();
    Set<String> all = new LinkedHashSet<>();
    String[][] fields = {
      {"walkthrough", wording.walkthrough()},
      {"preconditions", wording.preconditions()},
      {"postconditions", wording.postconditions()}
    };
    for (String[] field : fields) {
      if (field[1] != null) {
        attributes.put(field[0], text(field[1]));
        ArrayNode own = fieldCites.putArray(field[0]);
        for (Fact fact : cited(field[1], byId)) {
          own.add(fact.id());
          all.add(fact.id());
        }
      }
    }
    if (wording.title() != null) {
      attributes.put("title", wording.title());
    }
    ArrayNode cites = attributes.putArray("cites");
    all.forEach(cites::add);
    attributes.set("fieldCites", fieldCites);
    attributes.put("basis", "inferred");
    return Fact.create(
        flow,
        LlmFacts.FLOW_NARRATIVE,
        FactObject.string(LlmFacts.C4),
        attributes,
        LlmFacts.origin(ROLE),
        anyEvidence(byId),
        LlmFacts.CONFIDENCE,
        Validity.from(1));
  }

  private static Fact system(String predicate, String text, List<Fact> cited, boolean evidenced) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("text", text);
    ArrayNode cites = attributes.putArray("cites");
    cited.forEach(f -> cites.add(f.id()));
    attributes.put("basis", evidenced ? "evidenced" : "inferred");
    return Fact.create(
        RepoSections.SYSTEM,
        predicate,
        FactObject.string(LlmFacts.C4),
        attributes,
        new Origin(Origin.Kind.LLM, ROLE.name(), ROLE.promptVersion()),
        LlmFacts.evidence(cited),
        LlmFacts.CONFIDENCE,
        Validity.from(1));
  }

  private static io.lantern.core.fact.Evidence anyEvidence(Map<String, Fact> byId) {
    return byId.values().stream()
        .sorted(java.util.Comparator.comparing(Fact::id))
        .findFirst()
        .map(f -> LlmFacts.evidence(List.of(f)))
        .orElseThrow();
  }

  /** The text without its markers. */
  private static String text(String marked) {
    return MARKER.matcher(marked).replaceAll("").replaceAll("\\s+", " ").strip();
  }

  private static List<Fact> cited(String marked, Map<String, Fact> byId) {
    List<Fact> cited = new ArrayList<>();
    Matcher marker = MARKER.matcher(marked);
    while (marker.find()) {
      Fact fact = byId.get(marker.group(2));
      if (fact != null && !cited.contains(fact)) {
        cited.add(fact);
      }
    }
    return cited;
  }
}
