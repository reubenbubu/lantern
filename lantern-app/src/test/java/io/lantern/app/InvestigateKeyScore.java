package io.lantern.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Investigate's verdicts scored against a test set's answer key (#903): its {@code investigate}
 * section (#891), one keyed verdict per lead. A verdict is right when it is the key's, wrong when
 * it is another, and unanswered when Investigate stored none; a lead the key marks {@code "scored":
 * false} counts in none of these.
 */
final class InvestigateKeyScore {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One keyed lead: its kind, verdict and why. */
  record Keyed(String kind, String verdict, String why, boolean scored) {}

  private final Path file;
  private final Map<String, Keyed> leads;

  private InvestigateKeyScore(Path file, Map<String, Keyed> leads) {
    this.file = file;
    this.leads = leads;
  }

  /** The key in {@code file}, or none when it has no {@code investigate} section. */
  static Optional<InvestigateKeyScore> read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    JsonNode investigate = JSON.readTree(Files.readString(file)).path("investigate");
    if (!investigate.isArray() || investigate.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Keyed> leads = new LinkedHashMap<>();
    for (JsonNode e : investigate) {
      leads.put(
          e.path("lead").asString(),
          new Keyed(
              e.path("kind").asString(),
              e.path("verdict").asString(),
              e.path("why").asString(),
              e.path("scored").asBoolean(true)));
    }
    return Optional.of(new InvestigateKeyScore(file, leads));
  }

  /** Whether the key holds every one of these leads, so it is the panel's key. */
  boolean covers(List<String> chosen) {
    return leads.keySet().containsAll(chosen);
  }

  /** The key's verdict of a lead, if it holds it. */
  Optional<String> verdict(String lead) {
    return Optional.ofNullable(leads.get(lead)).map(Keyed::verdict);
  }

  /**
   * The score of these verdicts, a lead with none mapped to {@code null}: per lead, per kind and in
   * all.
   */
  ObjectNode score(Map<String, String> verdicts) {
    ObjectNode out = JSON.createObjectNode();
    out.put("key", file.toString());
    Map<String, int[]> byKind = new TreeMap<>();
    int[] total = new int[3];
    ArrayNode byLead = out.putArray("byLead");
    ArrayNode wrong = out.putArray("wrong");
    for (Map.Entry<String, String> e : verdicts.entrySet()) {
      Keyed k = leads.get(e.getKey());
      ObjectNode n = byLead.addObject().put("lead", e.getKey());
      n.put("verdict", e.getValue());
      if (k == null) {
        n.put("outcome", "not in the key");
        continue;
      }
      n.put("kind", k.kind()).put("key", k.verdict());
      if (!k.scored()) {
        n.put("outcome", "not scored");
        continue;
      }
      int outcome = e.getValue() == null ? 2 : e.getValue().equals(k.verdict()) ? 0 : 1;
      n.put("outcome", List.of("right", "wrong", "unanswered").get(outcome));
      byKind.computeIfAbsent(k.kind(), x -> new int[3])[outcome]++;
      total[outcome]++;
      if (outcome != 0) {
        wrong
            .addObject()
            .put("lead", e.getKey())
            .put("kind", k.kind())
            .put("verdict", e.getValue() == null ? "(none)" : e.getValue())
            .put("key", k.verdict())
            .put("why", k.why());
      }
    }
    counts(out.putObject("total"), total);
    ObjectNode kinds = out.putObject("byKind");
    byKind.forEach((kind, c) -> counts(kinds.putObject(kind), c));
    return out;
  }

  private static void counts(ObjectNode n, int[] c) {
    n.put("right", c[0]).put("wrong", c[1]).put("unanswered", c[2]);
  }

  /** The score as Markdown: in all and per kind, then the leads it got wrong with the key's why. */
  static String markdown(JsonNode score) {
    StringBuilder md =
        new StringBuilder("\n## Against the answer key\n\nKey: `")
            .append(score.path("key").asString())
            .append(
                "`.\n\n| Lead kind | Right | Wrong | Unanswered |\n| --- | --- | --- | --- |\n");
    score.path("byKind").properties().forEach(e -> row(md, e.getKey(), e.getValue()));
    row(md, "**all**", score.path("total"));
    if (!score.path("wrong").isEmpty()) {
      md.append("\n### Wrong or unanswered\n\n| Lead | Verdict | Key | Key's why |\n")
          .append("| --- | --- | --- | --- |\n");
      for (JsonNode w : score.path("wrong")) {
        md.append("| `")
            .append(w.path("lead").asString())
            .append("` | ")
            .append(w.path("verdict").asString())
            .append(" | ")
            .append(w.path("key").asString())
            .append(" | ")
            .append(w.path("why").asString().replace("|", "\\|").replace("\n", " "))
            .append(" |\n");
      }
    }
    return md.toString();
  }

  private static void row(StringBuilder md, String name, JsonNode c) {
    md.append("| ")
        .append(name)
        .append(" | ")
        .append(c.path("right").asString())
        .append(" | ")
        .append(c.path("wrong").asString())
        .append(" | ")
        .append(c.path("unanswered").asString())
        .append(" |\n");
  }
}
