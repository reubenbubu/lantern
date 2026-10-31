package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.docs.DocsExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks what READMEs claim against the facts of the code and the links between units, and records
 * each claim the code contradicts. See {@code docs/adr/0043-readme-claim-checks.md}.
 *
 * <p>It runs after {@link CrossRepoLinks}, since most checks read links. A claim is checked only
 * where its words say something checkable: that the units make no synchronous calls (or only
 * asynchronous ones), never touch another service's tables or have a database of their own, that
 * they use a named database, message broker, Java release or Spring Boot version, or a port,
 * endpoint path, topic, queue or exchange, or call or never call a named unit (those in {@link
 * NamedClaimChecks}). Every other claim is left alone. A README speaks for the deployable units of
 * its repository, or, when it sits in a folder some of them are in, for those.
 */
public final class ClaimChecks implements LinkRule.OverFacts {

  /** The step's name, recorded as the origin of its facts. */
  public static final String NAME = "claim-checks";

  /** The step's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this step's types. */
  public static final String VOCABULARY_SOURCE = "claim-checks";

  /** Attribute: a claim of the subject README that the code contradicts. */
  public static final String CLAIM_CONTRADICTION = "claim-contradiction";

  /** The checks, the values of {@link #CLAIM_CONTRADICTION} facts. */
  public static final String SYNCHRONOUS_CALLS = "synchronous-calls";

  public static final String OTHER_SERVICES_DATA = "other-services-data";
  public static final String OWN_DATABASE = "own-database";
  public static final String DATABASE_TECHNOLOGY = "database-technology";
  public static final String BROKER_TECHNOLOGY = "broker-technology";
  public static final String JAVA_VERSION = "java-version";
  public static final String SPRING_BOOT_VERSION = "spring-boot-version";
  public static final String PORT = "port";
  public static final String ENDPOINT = "endpoint";
  public static final String DESTINATION = "destination";
  public static final String NAMED_SERVICES = "named-services";

  /** How much less certain a contradiction is than the code facts: it rests on reading words. */
  static final double WORDING_FACTOR = 0.8;

  private static final String RESOURCE = "/vocabulary/claim-checks-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  // ---------------------------------------------------------------------------------------------
  // Wording

  static final String NEGATION =
      "(?:never|not|no|none|nothing|without|doesn't|don't|isn't|aren't|won't|cannot|can't)";

  /** A negation at most three words before a word of the group. */
  private static Pattern negated(String words) {
    return Pattern.compile("\\b" + NEGATION + "\\b(?:\\W+[\\w'-]+){0,3}?\\W+(" + words + ")\\b");
  }

  /**
   * The unit as the caller, negated: "never calls", "does not call", "makes no ... calls", with
   * only qualifiers between (group 1), then the rest of the clause (group 2). Not "nothing calls"
   * or "no service calls", which are about calls coming in, and not "does not retry HTTP calls".
   */
  static final Pattern NO_CALLS =
      Pattern.compile(
          "\\b(?:never|not|doesn't|don't|won't|cannot|can't|makes? no|making no)"
              + "((?:\\s+(?:synchronous|synchronously|sync|blocking|http|https|rest-api|any|other"
              + "|outbound|direct|directly|remote))*)\\s+(?:call|calls|calling|invoke|invokes"
              + "|invoking)\\b([^,;:]*)");

  /** A qualifier that makes a call synchronous; REST only as the acronym (see sentences). */
  private static final Pattern SYNCHRONOUS =
      Pattern.compile("\\b(synchronous|synchronously|sync|blocking|http|https|rest-api)\\b");

  /** What the units call, said generally: not one named service. */
  private static final Pattern ANY_SERVICE =
      Pattern.compile(
          "^[\\s.!?]*$|\\b(another|other|any|anything|external|services|systems|apis|each other)\\b");

  private static final String READ = "read|reads|reading|query|queries|querying|select|selects";
  private static final String WRITE = "write|writes|writing|update|updates|updating|insert|inserts";
  private static final String ACCESS =
      "access|accesses|accessing|touch|touches|touching|use|uses|using|share|shares|sharing";

  private static final Pattern NO_DATA_ACCESS = negated(READ + "|" + WRITE + "|" + ACCESS);

  private static final Pattern OTHERS_DATA =
      Pattern.compile(
          "\\b(another|other|any other)\\s+(service|services|application|applications|app|apps"
              + "|team|teams)(?:'s|s'|')?\\s+(table|tables|data|database|databases|schema"
              + "|schemas)\\b|\\b(table|tables|data|database|schema)\\s+of\\s+(another|other"
              + "|any other)\\s+(service|services|application|applications|app|apps)\\b");

  private static final Pattern OWN_DB =
      Pattern.compile(
          "\\b(its|their)\\s+own\\s+(database|db|schema)\\b"
              + "|\\b(database|db|schema)\\s+of\\s+(its|their)\\s+own\\b"
              + "|\\bdedicated\\s+(database|db)\\b");

  static final Pattern NEGATION_WORD = Pattern.compile("\\b" + NEGATION + "\\b");

  /** Sentences about tests or a developer's machine say nothing about what runs. */
  static final Pattern NOT_RUNTIME =
      Pattern.compile(
          "\\b(test|tests|testing|local|locally|development|dev|demo|example|migrated|migrate"
              + "|migrating|previously|formerly|instead of|replaced|replaces|plan|plans|planned"
              + "|will|future|used to|legacy|deprecated|roadmap)\\b");

  /** Database technologies by the words that name them, as the persistence extractor names them. */
  static final Map<String, Pattern> DATABASES = new LinkedHashMap<>();

  static {
    DATABASES.put("PostgreSQL", Pattern.compile("\\b(postgres|postgresql|pgsql)\\b"));
    DATABASES.put("MySQL", Pattern.compile("\\bmysql\\b"));
    DATABASES.put("MariaDB", Pattern.compile("\\bmariadb\\b"));
    DATABASES.put(
        "Oracle",
        Pattern.compile("\\boracle\\b(?!\\s+(jdk|java|cloud|linux|graalvm|weblogic|jre|openjdk))"));
    DATABASES.put("SQL Server", Pattern.compile("\\b(sql server|mssql)\\b"));
    DATABASES.put("H2", Pattern.compile("\\bh2\\b"));
    DATABASES.put("HSQLDB", Pattern.compile("\\bhsqldb\\b"));
    DATABASES.put("Derby", Pattern.compile("\\bderby\\b"));
    DATABASES.put("Db2", Pattern.compile("\\bdb2\\b"));
    DATABASES.put("SQLite", Pattern.compile("\\bsqlite\\b"));
  }

  /** Broker families by the words that name them, with the protocols and technologies of each. */
  record Broker(Pattern words, Set<String> found) {}

  static final Map<String, Broker> BROKERS = new LinkedHashMap<>();

  static {
    BROKERS.put(
        "Kafka", new Broker(Pattern.compile("\\bkafka\\b"), Set.of("kafka", "kafka streams")));
    BROKERS.put(
        "RabbitMQ",
        new Broker(Pattern.compile("\\b(rabbitmq|rabbit)\\b"), Set.of("amqp", "rabbitmq")));
    BROKERS.put(
        "ActiveMQ",
        new Broker(
            Pattern.compile("\\b(activemq|artemis)\\b"),
            Set.of("jms", "activemq", "activemq artemis")));
  }

  private static final String AT_LEAST = "(\\s*\\+|\\s+or\\s+(later|newer|higher|above|greater))?";

  /**
   * A Java release after words about what the units run on or are built for ("runs on Java 17",
   * "requires JDK 21", "Java version: 17"), not one naming a feature ("Java 8 streams").
   */
  private static final Pattern JAVA =
      Pattern.compile(
          "\\b(?:(?:runs? on|running on|requires?|needs?|built (?:with|for|on)|builds? with"
              + "|compiled (?:with|for)|targets?)\\s+(?:at least\\s+)?(?:java|jdk|openjdk|jre)"
              + "\\s*(?:se\\s*)?|(?:java|jdk) version\\s*:?\\s*(?:is\\s+)?)(1\\.)?(\\d{1,2})\\b"
              + AT_LEAST
              + "(?!\\s+(?:streams?|httpclient|http|records?|apis?|features?|syntax|style|time"
              + "|date|lambdas?|modules?|virtual|text|switch|optional))");

  private static final Pattern BOOT =
      Pattern.compile("\\bspring[\\s-]*boot\\s*v?(\\d+(?:\\.\\d+){0,2})(?:\\.x)?" + AT_LEAST);

  private static final Pattern MINIMUM = Pattern.compile("\\b(at least|minimum|min\\.?)\\s*$");

  @Override
  public RuleInfo info() {
    return RuleInfo.of(ORIGIN);
  }

  @Override
  public Set<String> reads() {
    return Rules.union(
        Set.of(EXTRACTED),
        ValueViews.WRITES,
        DeployableUnits.vocabularyPredicates(),
        CrossRepoLinks.vocabularyPredicates());
  }

  @Override
  public Set<String> writes() {
    return Set.of(CLAIM_CONTRADICTION);
  }

  /** The vocabulary types this step's facts use besides the built-in and earlier ones. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = ClaimChecks.class.getResourceAsStream(RESOURCE)) {
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
   * Checks the README claims of a snapshot against its other facts.
   *
   * @param facts the facts that hold in the snapshot, the links between units included
   * @param snapshot the snapshot number the facts hold from
   * @return one fact per claim and check the code contradicts, ordered by subject and ID
   */
  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Objects.requireNonNull(facts, "facts");
    Index index = new Index(facts);
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> derived = new TreeMap<>();
    for (Fact claim : index.claims) {
      Set<EntityId> units = index.unitsFor(claim);
      if (units.isEmpty()) {
        continue;
      }
      for (Contradiction c : check(claim, units, index)) {
        Fact fact = c.fact(claim, units, validity);
        derived.putIfAbsent(fact.id(), fact);
      }
    }
    return derived.values().stream()
        .sorted(Comparator.comparing((Fact f) -> f.subject().toString()).thenComparing(Fact::id))
        .toList();
  }

  // ---------------------------------------------------------------------------------------------
  // The checks

  /** A check the code fails: what the claim states, what was found, and the facts that show it. */
  record Contradiction(String check, String stated, Set<String> found, List<Fact> contradictedBy) {

    Fact fact(Fact claim, Set<EntityId> units, Validity validity) {
      ObjectNode attributes = FactJson.newObject();
      attributes.put("claim", claim.id());
      if (stated != null) {
        attributes.put("stated", stated);
      }
      if (!found.isEmpty()) {
        ArrayNode array = attributes.putArray("found");
        found.forEach(array::add);
      }
      ArrayNode unitArray = attributes.putArray("units");
      units.forEach(u -> unitArray.add(u.toString()));
      ArrayNode ids = attributes.putArray("facts");
      ids.add(claim.id());
      ArrayNode by = attributes.putArray("contradictedBy");
      double confidence = 1.0;
      for (Fact f : contradictedBy) {
        ids.add(f.id());
        by.add(entry(f));
        confidence = Math.min(confidence, f.confidence());
      }
      return Fact.create(
          claim.subject(),
          CLAIM_CONTRADICTION,
          FactObject.string(check),
          attributes,
          ORIGIN,
          claim.evidence(),
          CrossRepoLinks.round(confidence * WORDING_FACTOR),
          validity);
    }

    /** A fact on the code's side, with where it is written. */
    private static ObjectNode entry(Fact f) {
      ObjectNode entry = FactJson.newObject();
      entry.put("fact", f.id());
      entry.put("subject", f.subject().toString());
      entry.put("predicate", f.predicate());
      entry.put("object", f.object().value());
      String profile = Claims.profile(f);
      if (profile != null) {
        entry.put("profile", profile);
      }
      entry.put("repo", f.evidence().repo());
      if (f.evidence().path() != null) {
        entry.put("path", f.evidence().path());
      }
      if (f.evidence().lines() != null) {
        entry.put("line", f.evidence().lines().start());
      }
      return entry;
    }
  }

  private static List<Contradiction> check(Fact claim, Set<EntityId> units, Index index) {
    List<String> sentences = sentences(claim.object().value());
    List<Contradiction> found = new ArrayList<>();
    NamedClaimChecks named = new NamedClaimChecks(sentences, units, index);
    synchronousCalls(sentences, units, index, named, found);
    othersData(sentences, units, index, found);
    ownDatabase(sentences, units, index, found);
    databaseTechnology(sentences, units, index, found);
    brokerTechnology(sentences, units, index, found);
    javaVersion(sentences, units, index, found);
    springBootVersion(sentences, units, index, found);
    named.port(found);
    named.endpoints(found);
    named.destinations(found);
    named.services(found);
    return found;
  }

  /** "Never calls another service synchronously": any HTTP call the units make. */
  private static void synchronousCalls(
      List<String> sentences,
      Set<EntityId> units,
      Index index,
      NamedClaimChecks named,
      List<Contradiction> out) {
    if (sentences.stream().noneMatch(ClaimChecks::saysNoSynchronousCalls)
        && !named.saysOnlyAsynchronous()) {
      return;
    }
    List<Fact> calls = new ArrayList<>(index.from(CrossRepoLinks.CALLS_HTTP, units));
    for (Fact f : index.from(CrossRepoLinks.UNRESOLVED_LINK, units)) {
      if ("http".equals(UntypedReads.text(f, "kind"))) {
        calls.add(f);
      }
    }
    if (!calls.isEmpty()) {
      out.add(new Contradiction(SYNCHRONOUS_CALLS, null, Set.of(), sorted(calls)));
    }
  }

  /** "Never reads another service's tables": the units access data of another unit. */
  private static void othersData(
      List<String> sentences, Set<EntityId> units, Index index, List<Contradiction> out) {
    boolean reads = false;
    boolean writes = false;
    for (String s : sentences) {
      Matcher verb = NO_DATA_ACCESS.matcher(s);
      if (!OTHERS_DATA.matcher(s).find()) {
        continue;
      }
      while (verb.find()) {
        String word = verb.group(1);
        boolean read = word.matches(READ);
        boolean write = word.matches(WRITE);
        reads |= read || !write;
        writes |= write || !read;
      }
    }
    if (!reads && !writes) {
      return;
    }
    boolean anyRead = reads;
    boolean anyWrite = writes;
    List<Fact> access =
        index.from(CrossRepoLinks.ACCESSES_DATA_OF, units).stream()
            .filter(
                f ->
                    (anyRead && !UntypedReads.texts(f, "reads").isEmpty())
                        || (anyWrite && !UntypedReads.texts(f, "writes").isEmpty()))
            .toList();
    if (!access.isEmpty()) {
      out.add(new Contradiction(OTHER_SERVICES_DATA, null, Set.of(), sorted(access)));
    }
  }

  /**
   * "Has a database of its own": a unit outside the README's units uses the same database, in the
   * default view. Sharing only under a profile (a local database on localhost) is not flagged.
   */
  private static void ownDatabase(
      List<String> sentences, Set<EntityId> units, Index index, List<Contradiction> out) {
    if (sentences.stream().noneMatch(s -> affirms(s, OWN_DB))) {
      return;
    }
    List<Fact> own = defaultView(index.from(CrossRepoLinks.USES_DATABASE, units));
    Set<String> databases = new TreeSet<>();
    own.forEach(f -> databases.add(f.object().value()));
    List<Fact> shared = new ArrayList<>();
    Set<String> others = new TreeSet<>();
    for (Fact f : defaultView(index.of(CrossRepoLinks.USES_DATABASE))) {
      if (!units.contains(f.subject()) && databases.contains(f.object().value())) {
        shared.add(f);
        others.add(f.subject().toString());
      }
    }
    if (shared.isEmpty()) {
      return;
    }
    Set<String> sharedDatabases = new TreeSet<>();
    shared.forEach(f -> sharedDatabases.add(f.object().value()));
    List<Fact> facts = new ArrayList<>(shared);
    own.stream().filter(f -> sharedDatabases.contains(f.object().value())).forEach(facts::add);
    out.add(new Contradiction(OWN_DATABASE, null, others, sorted(facts)));
  }

  /** "Stores orders in MySQL": the units connect to databases, none of them of that kind. */
  private static void databaseTechnology(
      List<String> sentences, Set<EntityId> units, Index index, List<Contradiction> out) {
    String stated = named(sentences, DATABASES);
    if (stated == null) {
      return;
    }
    List<Fact> uses = index.from(CrossRepoLinks.USES_DATABASE, units);
    Set<String> found = new TreeSet<>();
    for (Fact f : uses) {
      String technology = UntypedReads.text(f, "technology");
      if (technology == null || technology.equalsIgnoreCase(stated)) {
        return;
      }
      found.add(technology);
    }
    if (!uses.isEmpty()) {
      out.add(new Contradiction(DATABASE_TECHNOLOGY, stated, found, sorted(uses)));
    }
  }

  /** "Publishes to Kafka": the units send, listen or connect to brokers, none of that kind. */
  private static void brokerTechnology(
      List<String> sentences, Set<EntityId> units, Index index, List<Contradiction> out) {
    Map<String, Pattern> words = new LinkedHashMap<>();
    BROKERS.forEach((name, broker) -> words.put(name, broker.words()));
    String stated = named(sentences, words);
    if (stated == null) {
      return;
    }
    Set<String> accepted = BROKERS.get(stated).found();
    List<Fact> messaging = new ArrayList<>();
    Set<String> found = new TreeSet<>();
    for (String predicate :
        List.of(CrossRepoLinks.PUBLISHES, CrossRepoLinks.CONSUMES, CrossRepoLinks.USES_BROKER)) {
      for (Fact f : index.from(predicate, units)) {
        String kind =
            predicate.equals(CrossRepoLinks.USES_BROKER)
                ? UntypedReads.text(f, "technology")
                : UntypedReads.text(f, "protocol");
        if (kind == null || accepted.contains(kind.toLowerCase(Locale.ROOT))) {
          return;
        }
        messaging.add(f);
        found.add(kind);
      }
    }
    if (!messaging.isEmpty()) {
      out.add(new Contradiction(BROKER_TECHNOLOGY, stated, found, sorted(messaging)));
    }
  }

  /** "Runs on Java 17": the units are built for another Java release. */
  private static void javaVersion(
      List<String> sentences, Set<EntityId> units, Index index, List<Contradiction> out) {
    Version stated = version(sentences, JAVA, 2);
    if (stated == null) {
      return;
    }
    version(
        JAVA_VERSION,
        "Java " + stated.text(),
        stated,
        "java",
        "Java ",
        units,
        index,
        out,
        v -> v.startsWith("1.") ? v.substring(2) : v);
  }

  /** "Built on Spring Boot 2.7": the units use another Spring Boot version. */
  private static void springBootVersion(
      List<String> sentences, Set<EntityId> units, Index index, List<Contradiction> out) {
    Version stated = version(sentences, BOOT, 1);
    if (stated == null) {
      return;
    }
    version(
        SPRING_BOOT_VERSION,
        "Spring Boot " + stated.text(),
        stated,
        "frameworkVersion",
        "Spring Boot ",
        units,
        index,
        out,
        v -> v);
  }

  private static void version(
      String check,
      String statedText,
      Version stated,
      String attribute,
      String prefix,
      Set<EntityId> units,
      Index index,
      List<Contradiction> out,
      UnaryOperator<String> normalize) {
    List<Fact> technologies = index.from(DeployableUnits.TECHNOLOGY, units);
    List<Fact> contradicting = new ArrayList<>();
    Set<String> found = new TreeSet<>();
    for (Fact f : technologies) {
      String value = UntypedReads.text(f, attribute);
      if (value == null) {
        continue;
      }
      if (stated.holdsFor(normalize.apply(value))) {
        return;
      }
      contradicting.add(f);
      found.add(prefix + value);
    }
    if (!contradicting.isEmpty()) {
      out.add(new Contradiction(check, statedText, found, sorted(contradicting)));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Reading the words

  /**
   * The claim in lowercase sentences, without emphasis and with plain apostrophes, the acronym REST
   * written as {@code rest-api}.
   */
  static List<String> sentences(String claim) {
    String text =
        claim
            // REST only as the acronym: "the rest is event-driven" is not about calls.
            .replaceAll("\\bREST\\b", "rest-api")
            .toLowerCase(Locale.ROOT)
            .replace('’', '\'')
            .replace("*", "")
            .replace("`", "")
            .replaceAll("\\s+", " ");
    List<String> sentences = new ArrayList<>();
    for (String s : text.split("(?<=[.!?;])\\s+")) {
      if (!s.isBlank()) {
        sentences.add(s.strip());
      }
    }
    return sentences;
  }

  /** Whether a sentence says what the pattern matches, without a negation before it. */
  static boolean affirms(String sentence, Pattern pattern) {
    Matcher m = pattern.matcher(sentence);
    return m.find() && !NEGATION_WORD.matcher(sentence.substring(0, m.start())).find();
  }

  /**
   * The one name the claim's sentences about what runs give, of those the patterns know, said
   * without a negation; null when they name none, or several.
   */
  private static String named(List<String> sentences, Map<String, Pattern> names) {
    Set<String> named = new LinkedHashSet<>();
    for (String s : sentences) {
      if (NOT_RUNTIME.matcher(s).find()) {
        continue;
      }
      names.forEach(
          (name, words) -> {
            if (words.matcher(s).find()) {
              named.add(name);
            }
          });
      if (names.values().stream().anyMatch(w -> w.matcher(s).find() && !affirms(s, w))) {
        return null;
      }
    }
    return named.size() == 1 ? named.iterator().next() : null;
  }

  /** A version a claim states, its parts as written, and whether it is a minimum. */
  record Version(List<Integer> parts, boolean atLeast) {

    String text() {
      return String.join(".", parts.stream().map(String::valueOf).toList()) + (atLeast ? "+" : "");
    }

    /** Whether a version found in the code is this one, or at least this one. */
    boolean holdsFor(String found) {
      List<Integer> actual = new ArrayList<>();
      for (String part : found.split("[.\\-]")) {
        if (!part.matches("\\d+")) {
          break;
        }
        actual.add(Integer.parseInt(part));
      }
      if (actual.isEmpty()) {
        return true;
      }
      for (int i = 0; i < parts.size(); i++) {
        int a = i < actual.size() ? actual.get(i) : 0;
        if (a != parts.get(i)) {
          return atLeast && a > parts.get(i);
        }
      }
      return true;
    }
  }

  /** The one version the claim's sentences state with the pattern; null when none, or several. */
  private static Version version(List<String> sentences, Pattern pattern, int numberGroup) {
    Set<Version> stated = new LinkedHashSet<>();
    for (String s : sentences) {
      if (NOT_RUNTIME.matcher(s).find()) {
        continue;
      }
      Matcher m = pattern.matcher(s);
      while (m.find()) {
        if (NEGATION_WORD.matcher(s.substring(0, m.start())).find()) {
          return null;
        }
        List<Integer> parts = new ArrayList<>();
        for (String part : m.group(numberGroup).split("\\.")) {
          parts.add(Integer.parseInt(part));
        }
        boolean atLeast =
            m.group(numberGroup + 1) != null
                || m.group().contains("at least")
                || MINIMUM.matcher(s.substring(0, m.start())).find();
        stated.add(new Version(List.copyOf(parts), atLeast));
      }
    }
    return stated.size() == 1 ? stated.iterator().next() : null;
  }

  /** The links that hold in the default view: those without a profile. */
  private static List<Fact> defaultView(List<Fact> links) {
    return links.stream().filter(f -> Claims.profile(f) == null).toList();
  }

  /** Whether a sentence says the units make no synchronous calls to other services. */
  private static boolean saysNoSynchronousCalls(String sentence) {
    Matcher m = NO_CALLS.matcher(sentence);
    while (m.find()) {
      String rest = m.group(2);
      if ((SYNCHRONOUS.matcher(m.group(1)).find() || SYNCHRONOUS.matcher(rest).find())
          && ANY_SERVICE.matcher(rest).find()) {
        return true;
      }
    }
    return false;
  }

  static List<Fact> sorted(List<Fact> facts) {
    List<Fact> sorted = new ArrayList<>(facts);
    sorted.sort(LinkIndex.BY_ID);
    return sorted;
  }

  // ---------------------------------------------------------------------------------------------
  // The facts it reads

  static final class Index {

    /** The README claims, in ID order. */
    final List<Fact> claims = new ArrayList<>();

    /** The same facts as the cross-repo linker reads them: units, config, code and endpoints. */
    final LinkIndex links;

    /** Each name a unit goes by, lowercase, mapped to the unit. */
    final Map<String, EntityId> unitNames;

    /** Each repository's units, with the path of the fact that makes each a unit. */
    private final Map<String, Map<EntityId, String>> unitsByRepo = new HashMap<>();

    private final Map<String, List<Fact>> byPredicate = new HashMap<>();

    Index(List<Fact> facts) {
      links = new LinkIndex(facts);
      unitNames = NamedClaimChecks.unitNames(links);
      List<Fact> sorted = new ArrayList<>(facts);
      sorted.sort(LinkIndex.BY_ID);
      for (Fact f : sorted) {
        byPredicate.computeIfAbsent(f.predicate(), p -> new ArrayList<>()).add(f);
        if (f.predicate().equals(DocsExtractor.CLAIM)
            && f.subject().type().equals(DocsExtractor.README)
            && f.object().kind() == FactObject.Kind.STRING) {
          claims.add(f);
        } else if (f.predicate().equals(DeployableUnits.DEPLOYABLE_UNIT)) {
          unitsByRepo
              .computeIfAbsent(f.evidence().repo(), r -> new TreeMap<>(LinkIndex.BY_TEXT))
              .putIfAbsent(f.subject(), f.evidence().path() == null ? "" : f.evidence().path());
        }
      }
    }

    List<Fact> of(String predicate) {
      return byPredicate.getOrDefault(predicate, List.of());
    }

    /** The facts of a predicate about the units. */
    List<Fact> from(String predicate, Set<EntityId> units) {
      return of(predicate).stream().filter(f -> units.contains(f.subject())).toList();
    }

    /**
     * The units a README speaks for: all of its repository's for one at the root, else those in its
     * folder or below; none for a README in a folder without units (docs/, a library's folder).
     */
    Set<EntityId> unitsFor(Fact claim) {
      Map<EntityId, String> units = unitsByRepo.getOrDefault(claim.evidence().repo(), Map.of());
      String path = claim.evidence().path() == null ? "" : claim.evidence().path();
      String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
      Predicate<String> inFolder = p -> !folder.isEmpty() && p.startsWith(folder);
      Set<EntityId> within = new TreeSet<>(LinkIndex.BY_TEXT);
      units.forEach(
          (unit, where) -> {
            if (inFolder.test(where)) {
              within.add(unit);
            }
          });
      if (!folder.isEmpty()) {
        return within;
      }
      Set<EntityId> all = new TreeSet<>(LinkIndex.BY_TEXT);
      all.addAll(units.keySet());
      return all;
    }
  }
}
