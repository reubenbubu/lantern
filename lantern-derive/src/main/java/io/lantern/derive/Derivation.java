package io.lantern.derive;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.Lineage;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.link.LinkRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import tools.jackson.databind.JsonNode;

/**
 * The derive stage (knowledge-build epic, section 9; #523): the rules that compute the architecture
 * model from the linked facts, once per snapshot. They have the shape of link rules and run in the
 * linker after the rules whose facts they read, so they run again only when those facts changed,
 * and their facts are stored with lineage.
 */
public final class Derivation {

  /** The vocabulary source of the components' types. */
  public static final String COMPONENTS_SOURCE = "derive-components";

  private Derivation() {}

  /** The derive rules of this version of Lantern. */
  public static List<LinkRule> rules() {
    return List.of(new Components());
  }

  /** The derive rules' vocabularies by source, applied after the linker's. */
  public static SequencedMap<String, List<VocabularyType>> vocabularies() {
    SequencedMap<String, List<VocabularyType>> sources = new LinkedHashMap<>();
    sources.put(COMPONENTS_SOURCE, Components.vocabulary());
    return sources;
  }

  /**
   * The facts with the model the derive rules give for them, as the linker would store it, with
   * lineage: for composing facts that were never stored, as tests do.
   *
   * @param facts the linked facts of one snapshot; a model among them is replaced
   * @param snapshot the snapshot they hold in
   */
  public static List<Fact> withModel(List<Fact> facts, long snapshot) {
    // A model derived before is derived again, from the facts as they are now.
    java.util.Set<String> origins = new java.util.HashSet<>();
    rules().forEach(r -> origins.add(r.info().name()));
    List<Fact> all = new ArrayList<>();
    facts.stream().filter(f -> !origins.contains(f.origin().name())).forEach(all::add);
    Map<String, String> claims = new HashMap<>();
    all.forEach(f -> claims.put(f.id(), f.claimId()));
    for (LinkRule rule : rules()) {
      List<Fact> derived = ((LinkRule.OverFacts) rule).derive(List.copyOf(all), snapshot);
      for (Fact f : derived) {
        Fact stored = f.withLineage(Lineage.of(f.attributes().deepCopy(), claims::get));
        all.add(stored);
      }
    }
    return all;
  }

  /** The types of a vocabulary resource of {@code lantern-derive}, in the order written. */
  static List<VocabularyType> load(String resource) {
    try (InputStream in = Derivation.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + resource);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + resource, e);
    }
  }
}
