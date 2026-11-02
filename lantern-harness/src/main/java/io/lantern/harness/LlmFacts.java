package io.lantern.harness;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the LLM roles write, as claims (knowledge-build epic, section 6, Derived and LLM facts):
 * ordinary facts of origin {@code llm}, named by the role and versioned by its prompt, whose
 * evidence is where the facts they cite were observed, whose lineage is the claims they cite, and
 * whose attributes hold the text, the facts it cites, its basis and the model that wrote it.
 */
public final class LlmFacts {

  /** The vocabulary source of the roles' types. */
  public static final String SOURCE = "llm";

  /** A responsibility sentence: {@code (element, responsibility, "c4")}. */
  public static final String RESPONSIBILITY = "responsibility";

  /** An intent phrase: {@code (source, intent, "<interaction claim ID>")}. */
  public static final String INTENT = "intent";

  /** A unit's summary: {@code (unit, summary, "<level>")}. */
  public static final String SUMMARY = "summary";

  /** The document kind texts are worded for. */
  public static final String C4 = "c4";

  /** How certain a text the checks accepted is, before the support judge's verdict. */
  public static final double CONFIDENCE = 0.6;

  /** Whether a text rests on what people wrote. */
  public enum Basis {
    /** It cites a README or document claim, Javadoc, or an evidenced summary. */
    EVIDENCED,
    /** It rests on code and configuration alone. */
    INFERRED;

    /** The lowercase name used in attributes. */
    public String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private LlmFacts() {}

  /** The roles' vocabulary types. */
  public static List<VocabularyType> vocabulary() {
    String resource = "/vocabulary/llm-types.json";
    try (InputStream in = LlmFacts.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + resource);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      array.forEach(node -> types.add(VocabularyType.fromJson(node)));
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + resource, e);
    }
  }

  /** The origin of a role's facts. */
  public static Origin origin(RoleSpec role) {
    return new Origin(Origin.Kind.LLM, role.name(), role.promptVersion());
  }

  /**
   * A text a role wrote, as a fact.
   *
   * @param role the role that wrote it
   * @param model the model that wrote it, as {@code provider/model}
   * @param subject what it is about
   * @param predicate its predicate, such as {@link #RESPONSIBILITY}
   * @param object its value, such as {@link #C4} or an interaction's claim ID
   * @param text the text, without citations
   * @param cited the facts it cites, at least one
   * @param basis whether it rests on what people wrote
   * @param snapshot the snapshot it holds from
   */
  public static Fact text(
      RoleSpec role,
      String model,
      EntityId subject,
      String predicate,
      String object,
      String text,
      List<Fact> cited,
      Basis basis,
      long snapshot) {
    Objects.requireNonNull(text, "text");
    if (cited.isEmpty()) {
      throw new IllegalArgumentException("A text of " + subject + " cites no fact");
    }
    ObjectNode attributes = FactJson.newObject();
    attributes.put("text", text);
    ArrayNode cites = attributes.putArray("cites");
    Set<String> ids = new LinkedHashSet<>();
    cited.forEach(f -> ids.add(f.id()));
    ids.forEach(cites::add);
    attributes.put("basis", basis.jsonName());
    attributes.put("model", model);
    List<String> lineage = new ArrayList<>();
    cited.forEach(
        f -> {
          if (f.claimId() != null && !lineage.contains(f.claimId())) {
            lineage.add(f.claimId());
          }
        });
    Fact fact =
        Fact.create(
            subject,
            predicate,
            FactObject.string(object),
            attributes,
            origin(role),
            evidence(cited),
            CONFIDENCE,
            Validity.from(snapshot));
    return lineage.isEmpty() ? fact : fact.withLineage(lineage);
  }

  /**
   * One sentence of a summary.
   *
   * @param text the sentence, without citations
   * @param cites the facts it cites, its contained summaries' resolved to the facts they cite
   */
  public record Sentence(String text, List<Fact> cites) {
    public Sentence {
      Objects.requireNonNull(text, "text");
      cites = List.copyOf(cites);
    }
  }

  /**
   * A unit's summary as a fact (knowledge-build epic, section 11.3, Stored): {@code (unit, summary,
   * "<level>")}, its attributes holding the sentences with the facts each cites, the basis of the
   * first and the model, its confidence from the share of sentences the checks kept.
   *
   * @param level the unit's level, such as {@code type}
   * @param sentences the kept sentences, at least one citing a fact
   * @param confidence above 0 and at most 1
   */
  public static Fact summary(
      RoleSpec role,
      String model,
      EntityId subject,
      String level,
      List<Sentence> sentences,
      Basis basis,
      double confidence,
      long snapshot) {
    List<Fact> cited = new ArrayList<>();
    sentences.forEach(sentence -> cited.addAll(sentence.cites()));
    if (cited.isEmpty()) {
      throw new IllegalArgumentException("A summary of " + subject + " cites no fact");
    }
    ObjectNode attributes = FactJson.newObject();
    ArrayNode texts = attributes.putArray("sentences");
    for (Sentence sentence : sentences) {
      ObjectNode one = texts.addObject();
      one.put("text", sentence.text());
      ArrayNode cites = one.putArray("cites");
      Set<String> ids = new LinkedHashSet<>();
      sentence.cites().forEach(f -> ids.add(f.id()));
      ids.forEach(cites::add);
    }
    attributes.put("basis", basis.jsonName());
    attributes.put("model", model);
    List<String> lineage = new ArrayList<>();
    cited.forEach(
        f -> {
          if (f.claimId() != null && !lineage.contains(f.claimId())) {
            lineage.add(f.claimId());
          }
        });
    Fact fact =
        Fact.create(
            subject,
            SUMMARY,
            FactObject.string(level),
            attributes,
            origin(role),
            evidence(cited),
            confidence,
            Validity.from(snapshot));
    return lineage.isEmpty() ? fact : fact.withLineage(lineage);
  }

  /** Where the cited facts were observed, each place once, as cited evidence. */
  public static Evidence evidence(List<Fact> cited) {
    Set<Evidence.Location> locations = new LinkedHashSet<>();
    for (Fact fact : cited) {
      for (Evidence.Location location : fact.evidence().locations()) {
        locations.add(
            new Evidence.Location(
                location.repo(),
                location.path(),
                location.lines(),
                location.blob(),
                location.contentHash(),
                Evidence.Role.CITED));
      }
    }
    return new Evidence(List.copyOf(locations));
  }
}
