package io.lantern.link;

import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.vocabulary.VocabularyType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Links the deployable units of a snapshot at runtime, matching what one exposes with what another
 * uses: HTTP calls with endpoints, gateway routes with the units they send requests to, messages
 * sent with listeners, datasources with databases, config with brokers, infrastructure and identity
 * providers, and JWT secrets units share; and ties the containers deployment descriptors run to the
 * units and data stores in them. See {@code docs/adr/0042-cross-repo-links.md}.
 *
 * <p>Since #575 this class holds the step's vocabulary and constants, and the links are made by the
 * protocol rules of {@link ProtocolLinks}, which run after {@link DeployableUnits} and read its
 * facts with the extractors'. Each link is found in every view (the default one and each profile
 * the facts name, one profile active at a time) and recorded once if it holds in the default view,
 * else once per profile it holds under, marked with that {@code profile}. Every link names the
 * facts on both ends ({@code facts}), the code on each end where there is code ({@code source},
 * {@code target}), its protocol and a link ID that stays the same across snapshots ({@code link}).
 * What static matching cannot decide is recorded as {@code unresolved-link} on the unit, with its
 * candidates, for the LLM resolver.
 */
public final class CrossRepoLinks {

  /**
   * The step's name, the origin of its facts until #575 split it into the protocol rules ({@link
   * ProtocolLinks}).
   */
  public static final String NAME = "cross-repo-links";

  /** The source recorded on the vocabulary revision that adds this step's types. */
  public static final String VOCABULARY_SOURCE = "cross-repo-links";

  private static final String RESOURCE = "/vocabulary/cross-repo-links-types.json";

  /** The predicates of this step's vocabulary types, which the protocol rules write. */
  static Set<String> vocabularyPredicates() {
    return Rules.predicates(vocabulary());
  }

  /** The vocabulary types this step's facts use besides the built-in and earlier ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = CrossRepoLinks.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * The links of a snapshot's facts as the protocol rules ({@link ProtocolLinks}) give them
   * together, after address resolution, whose answers they read, for tests and callers that link
   * without the linker. Address resolution's own facts are not among those returned.
   *
   * @param facts the facts that hold in the snapshot, the deployable units' included
   * @param snapshot the snapshot number the facts hold from
   * @return the derived facts, ordered by subject, predicate, object and ID
   */
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    // Where the addresses lead first, which the matchers read (#589).
    AddressResolution addresses = new AddressResolution();
    ClaimSink answers = new ClaimSink(addresses.info(), addresses.writes());
    addresses.derive(
        new ClaimQuery(addresses.info(), addresses.reads(), Set.of(), facts, snapshot),
        answers,
        View.DEFAULT);
    List<Fact> input = new ArrayList<>(facts);
    input.addAll(answers.facts());
    List<Fact> derived = new ArrayList<>();
    for (ProtocolLinks rule : ProtocolLinks.withOpenLinks()) {
      derived.addAll(
          ViewMerge.derive(
              rule, new ClaimQuery(rule.info(), rule.reads(), Set.of(), input, snapshot)));
    }
    return derived.stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .toList();
  }

  /** Rounded, so a stored confidence reads as it was meant: 0.56, not 0.5599... */
  static double round(double confidence) {
    return Math.round(confidence * 100) / 100.0;
  }
}
