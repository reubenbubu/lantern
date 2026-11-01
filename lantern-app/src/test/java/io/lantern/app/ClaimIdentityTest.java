package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.AttributeSchema;
import io.lantern.core.vocabulary.AttributeSchema.Property;
import io.lantern.core.vocabulary.BuiltInTypes;
import io.lantern.core.vocabulary.C4Level;
import io.lantern.core.vocabulary.TypeKind;
import io.lantern.core.vocabulary.Vocabulary;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.Extractors;
import io.lantern.link.LinkVocabularies;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Claim identity across the whole vocabulary, as the app loads it (ADR 0090; epic {@code
 * docs/epics/001-knowledge-build.md}, section 6, Key attributes and Vocabulary fixes).
 */
class ClaimIdentityTest {

  /** Descriptive in every type (epic, Key attributes). */
  static final Set<String> DESCRIPTIVE_EVERYWHERE =
      Set.of(
          "written",
          "unresolved",
          "masked",
          "followed",
          "expressions",
          "alternates",
          "defaulted",
          "inferred",
          "facts",
          "link",
          "exceptProfiles",
          // Since #590, the views a default view's claim does not hold in.
          "absentIn",
          "cites",
          "summary",
          "description");

  /**
   * Descriptive beyond the global list, per type (epic, Key attributes, the table). Since #518
   * {@code config-value}'s {@code application} is key: a config server's repository holds a file
   * per application, and each one's setting is its own claim.
   */
  static final Map<String, Set<String>> DESCRIPTIVE_PER_TYPE =
      Map.ofEntries(
          Map.entry("endpoint", Set.of("controller", "annotation", "framework")),
          Map.entry("message-send", Set.of("template", "method", "routingKeyWritten", "binding")),
          Map.entry("message-listener", Set.of("annotation", "binding", "anonymous")),
          Map.entry("message-binding", Set.of("exchangeWritten", "routingKeyWritten", "anonymous")),
          // Since #597, on value resolution's relations: as on the message facts they replace.
          Map.entry("SENDS_TO", Set.of("routingKeyWritten", "binding")),
          Map.entry("LISTENS_ON", Set.of("binding", "anonymous")),
          Map.entry("BINDS", Set.of("exchangeWritten", "routingKeyWritten", "anonymous")),
          Map.entry(
              "message-destination",
              Set.of("exchangeType", "durable", "deadLetterExchange", "deadLetterRoutingKey")),
          Map.entry(
              "deployment-container",
              Set.of(
                  "image",
                  "product",
                  "version",
                  "build",
                  "hosts",
                  "aliases",
                  "env",
                  "ports",
                  "dependsOn",
                  "composeProfiles",
                  "replicas",
                  "properties",
                  "activeProfiles")),
          Map.entry(
              "dockerfile", Set.of("technology", "exposes", "env", "properties", "activeProfiles")),
          Map.entry(
              "frontend-app",
              Set.of("package", "version", "libraries", "image", "server", "ports")),
          Map.entry(
              "identity-client",
              Set.of("redirectUris", "webOrigins", "realm", "rootUrl", "baseUrl")),
          Map.entry("datasource", Set.of("technology", "server", "database")),
          Map.entry("data-store", Set.of("technology", "server", "database")),
          Map.entry("technology", Set.of("framework", "frameworkVersion", "frameworks", "java")),
          Map.entry("version-skew", Set.of("pins")),
          // Since #518: references to the facts a claim comes from are lineage, like facts.
          Map.entry("deployed-in", Set.of("fact")),
          Map.entry("claim-contradiction", Set.of("claim", "contradictedBy")),
          // Since #587: the text config resolved an expression to; URLs that differ only in their
          // path are one address.
          Map.entry(
              "RESOLVES_TO",
              // Since #589, address resolution's answers: what the unit was found by, and how.
              Set.of(
                  "resolved",
                  "exceptDeployments",
                  "matched",
                  "matchedBy",
                  "factor",
                  "fact",
                  "filledBy",
                  "order",
                  "candidates",
                  // Since #596, a datasource placed: the database the deployment names, and how.
                  "database",
                  "guessed")));

  /**
   * The key attributes of link claims: the channel attributes, the protocol (#518: a different
   * protocol is a different line) and the view, which link types called {@code profile} before
   * #590.
   */
  static final Set<String> LINK_KEYS =
      Set.of(
          "endpoint",
          "destination",
          "routingKey",
          "bindingKey",
          "table",
          "host",
          "key",
          "protocol",
          "view");

  /** The runtime relationships: {@code INTERACTS_WITH} and everything under it. */
  static final Set<String> RUNTIME =
      Set.of(
          BuiltInTypes.INTERACTS_WITH,
          BuiltInTypes.CALLS,
          "CALLS_HTTP",
          "SENDS_MAIL",
          "USES_IDENTITY_PROVIDER",
          "PUBLISHES",
          "CONSUMES",
          "ROUTES_TO",
          "SENDS_MESSAGES_TO",
          "USES_DATABASE",
          "ACCESSES_DATA_OF",
          "USES_BROKER",
          "USES_INFRASTRUCTURE");

  /** The build relationships: {@code DEPENDS_ON} and everything under it. */
  static final Set<String> BUILD = Set.of(BuiltInTypes.DEPENDS_ON, "BUILD_DEPENDS_ON", "PACKAGES");

  /**
   * Every type the app loads: the built-in ones, then the extractors', the linker's and
   * derivation's.
   */
  static Vocabulary vocabulary() {
    List<VocabularyType> types = new ArrayList<>(BuiltInTypes.definitions());
    Extractors.vocabularies().values().forEach(types::addAll);
    LinkVocabularies.bySource().values().forEach(types::addAll);
    io.lantern.derive.Derivation.vocabularies().values().forEach(types::addAll);
    return new Vocabulary(1, types);
  }

  static Set<String> linkSourceTypes() {
    Set<String> names = new TreeSet<>();
    LinkVocabularies.bySource().values().forEach(l -> l.forEach(t -> names.add(t.name())));
    return names;
  }

  static Stream<String> predicates() {
    return vocabulary().types().stream().filter(t -> t.kind().isPredicate()).map(t -> t.name());
  }

  /** The key attributes the epic's rules give a type, from its declared attributes. */
  static Set<String> expectedKeys(Vocabulary vocabulary, String type) {
    Set<String> declared = vocabulary.attributeSchema(type).properties().keySet();
    Set<String> keys = new TreeSet<>();
    if (vocabulary.isA(type, BuiltInTypes.INTERACTS_WITH)) {
      declared.stream().filter(LINK_KEYS::contains).forEach(keys::add);
      return keys;
    }
    Set<String> descriptive = new TreeSet<>(DESCRIPTIVE_EVERYWHERE);
    for (VocabularyType t : vocabulary.lineage(type)) {
      descriptive.addAll(DESCRIPTIVE_PER_TYPE.getOrDefault(t.name(), Set.of()));
    }
    for (String name : declared) {
      // An extracted fact's profile is descriptive, as profiles are views (#590: a derived fact's
      // view is the key attribute view, and profile is kept only for facts stored before).
      boolean profile = name.equals("profile") && !type.equals("config-value");
      if (!descriptive.contains(name) && !profile) {
        keys.add(name);
      }
    }
    return keys;
  }

  @ParameterizedTest
  @MethodSource("predicates")
  void everyTypeHasTheKeyAttributesTheEpicsRulesGive(String type) {
    Vocabulary vocabulary = vocabulary();

    assertThat(vocabulary.keyAttributes(type)).isEqualTo(expectedKeys(vocabulary, type));
  }

  /**
   * Two sightings of one thing, at another commit, lines, file and extractor version, with every
   * descriptive attribute different, are one claim; changing any key attribute, the subject or the
   * object makes two.
   */
  @ParameterizedTest
  @MethodSource("predicates")
  void twoSightingsOfOneThingAreOneClaimAndTwoThingsAreTwo(String type) {
    Vocabulary vocabulary = vocabulary();
    Map<String, Property> properties = vocabulary.attributeSchema(type).properties();
    Set<String> keys = vocabulary.keyAttributes(type);
    ObjectNode attributes = FactJson.newObject();
    properties.forEach((name, property) -> attributes.set(name, sample(property, 0)));
    Fact fact = fact(vocabulary, type, "a", attributes, 1);

    ObjectNode otherSighting = attributes.deepCopy();
    properties.forEach(
        (name, property) -> {
          if (!keys.contains(name)) {
            otherSighting.set(name, sample(property, 1));
          }
        });
    Fact again = fact(vocabulary, type, "a", otherSighting, 2);
    assertThat(vocabulary.claimId(again))
        .as("another sighting")
        .isEqualTo(vocabulary.claimId(fact));

    for (String key : keys) {
      JsonNode other = sample(properties.get(key), 1);
      if (other.equals(attributes.get(key))) {
        continue; // an enum of one value cannot differ
      }
      ObjectNode changed = attributes.deepCopy();
      changed.set(key, other);
      assertThat(vocabulary.claimId(fact(vocabulary, type, "a", changed, 1)))
          .as("another " + key)
          .isNotEqualTo(vocabulary.claimId(fact));
      ObjectNode without = attributes.deepCopy();
      without.remove(key);
      assertThat(vocabulary.claimId(fact(vocabulary, type, "a", without, 1)))
          .as("no " + key)
          .isNotEqualTo(vocabulary.claimId(fact));
    }
    Fact otherSubject = fact(vocabulary, type, "b", attributes, 1);
    Fact otherObject = fact(vocabulary, type, "a", attributes, 1);
    otherSubject =
        Fact.create(
            otherSubject.subject(),
            type,
            fact.object(),
            attributes,
            fact.origin(),
            fact.evidence(),
            1.0,
            fact.validity());
    otherObject =
        Fact.create(
            fact.subject(),
            type,
            fact(vocabulary, type, "b", attributes, 1).object(),
            attributes,
            fact.origin(),
            fact.evidence(),
            1.0,
            fact.validity());
    assertThat(vocabulary.claimId(otherSubject))
        .as("another subject")
        .isNotEqualTo(vocabulary.claimId(fact));
    assertThat(vocabulary.claimId(otherObject))
        .as("another object")
        .isNotEqualTo(vocabulary.claimId(fact));
  }

  @Test
  void runtimeAndBuildRelationshipsHaveSeparateParents() {
    Vocabulary vocabulary = vocabulary();
    Set<String> runtime = new TreeSet<>();
    Set<String> build = new TreeSet<>();
    for (VocabularyType type : vocabulary.types()) {
      if (vocabulary.isA(type.name(), BuiltInTypes.INTERACTS_WITH)) {
        runtime.add(type.name());
      }
      if (vocabulary.isA(type.name(), BuiltInTypes.DEPENDS_ON)) {
        build.add(type.name());
      }
    }

    assertThat(runtime).isEqualTo(RUNTIME);
    assertThat(build).isEqualTo(BUILD);
  }

  /**
   * A renderer that knows nothing of a relationship type draws it with the shape it inherits: a
   * build relationship always as a dashed build arrow, a runtime one always as a runtime arrow.
   */
  @Test
  void aRendererFallingBackToAParentNeverDrawsABuildEdgeAsARuntimeLine() {
    Vocabulary vocabulary = vocabulary();
    String runtimeLine = vocabulary.shape(BuiltInTypes.INTERACTS_WITH).orElseThrow();
    String buildLine = vocabulary.shape(BuiltInTypes.DEPENDS_ON).orElseThrow();

    assertThat(runtimeLine).isNotEqualTo(buildLine);
    for (String type : BUILD) {
      assertThat(vocabulary.shape(type)).as(type).contains(buildLine);
    }
    for (String type : RUNTIME) {
      assertThat(vocabulary.shape(type)).as(type).contains(runtimeLine);
    }
  }

  @Test
  void peopleAreActorsAndBrokersAndInfrastructureAreContainers() {
    Vocabulary vocabulary = vocabulary();

    assertThat(vocabulary.find("person").orElseThrow().parent()).isEqualTo(BuiltInTypes.ACTOR);
    assertThat(vocabulary.isA("person", BuiltInTypes.SYSTEM)).isFalse();
    assertThat(vocabulary.c4Level("person")).contains(C4Level.CONTEXT);
    assertThat(vocabulary.find("message-broker").orElseThrow().parent())
        .isEqualTo(BuiltInTypes.CONTAINER);
    assertThat(vocabulary.find("infrastructure").orElseThrow().parent())
        .isEqualTo(BuiltInTypes.CONTAINER);
    assertThat(BuiltInTypes.definitions())
        .extracting(VocabularyType::name)
        .contains(BuiltInTypes.REPO);
    assertThat(Extractors.vocabularies().values().stream().flatMap(List::stream))
        .extracting(VocabularyType::name)
        .doesNotContain(BuiltInTypes.REPO);
  }

  private static Fact fact(
      Vocabulary vocabulary, String type, String which, ObjectNode attributes, int sighting) {
    VocabularyType predicate = vocabulary.find(type).orElseThrow();
    FactObject object =
        predicate.kind() == TypeKind.RELATIONSHIP
            ? FactObject.entity(new EntityId("type", "com.acme.Target" + which))
            : switch (vocabulary.objectKind(type).orElse(FactObject.Kind.STRING)) {
              case INTEGER -> FactObject.integer(which.equals("a") ? 1 : 2);
              case DECIMAL -> FactObject.decimal(new BigDecimal(which.equals("a") ? "1.5" : "2.5"));
              case BOOLEAN -> FactObject.bool(which.equals("a"));
              default -> FactObject.string("value-" + which);
            };
    String commit = sighting == 1 ? "1".repeat(40) : "2".repeat(40);
    return Fact.create(
        new EntityId("type", "com.acme.Subject" + which),
        type,
        object,
        attributes,
        new Origin(Origin.Kind.STATIC, "test", String.valueOf(sighting)),
        Evidence.of(
            "repo-" + sighting,
            "File" + sighting + ".java",
            new Evidence.LineRange(sighting, sighting + 3),
            null),
        sighting == 1 ? 1.0 : 0.5,
        Validity.from(sighting));
  }

  /** A value of the property's type; {@code which} 0 and 1 give two different ones. */
  private static JsonNode sample(Property property, int which) {
    ObjectNode node = FactJson.newObject();
    switch (property.type()) {
      case STRING -> {
        List<String> allowed = property.allowed();
        node.put("v", allowed.isEmpty() ? "s" + which : allowed.get(which % allowed.size()));
      }
      case INTEGER -> node.put("v", 10 + which);
      case NUMBER -> node.put("v", new BigDecimal("1." + which));
      case BOOLEAN -> node.put("v", which == 0);
      case OBJECT -> node.putObject("v").put("k", "o" + which);
      case ARRAY ->
          node.putArray("v")
              .add(
                  property.items() == null
                      ? FactJson.newObject().stringNode("e" + which)
                      : sample(property.items(), which));
    }
    return node.get("v");
  }

  /** The schema the app checks attributes against is unchanged by key attributes. */
  @Test
  void keyAttributesAreDeclaredAttributes() {
    Vocabulary vocabulary = vocabulary();
    for (VocabularyType type : vocabulary.types()) {
      if (type.kind().isPredicate()) {
        AttributeSchema schema = vocabulary.attributeSchema(type.name());
        assertThat(schema.properties().keySet())
            .as(type.name())
            .containsAll(vocabulary.keyAttributes(type.name()));
      }
    }
  }
}
