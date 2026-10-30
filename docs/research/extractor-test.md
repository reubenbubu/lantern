# What a Java system's tests say about its architecture

Research for #398 (Review the Test extractor), milestone "Extractor revisit". It asks which test
evidence in a Java (Maven, Spring Boot) repository matters for the system's architecture, what it
shows about its structure, and what a static reader that parses test sources from git, without
compiling or running them, can take from it reliably. It leads to an amendment of
`docs/adr/0064-test-evidence.md`.

**Depth: high.** Read in full, 31 sources:

- the ArchUnit user guide, 4 Spring Modulith reference pages, the jMolecules and
  jMolecules-integrations READMEs, a jMolecules ArchUnit lab and the Taikai README;
- 3 Spring Cloud Contract pages, 4 Pact pages and the Pact specification v3;
- the Spring Boot Testcontainers and testing pages, the Testcontainers compose module, Spring
  Kafka's testing page and 2 WireMock pages;
- the Failsafe inclusion page, the JUnit 5 user guide, the Cucumber JUnit Platform engine, the
  Spock getting-started page and JetBrains' 2023 Java ecosystem page;
- one empirical study (Ayas et al., SEAA 2022), one literature review (Grinenko et al., 2026) and
  one practitioner report (lastminute.com).

Pages were read through a summarising fetch tool. Not confirmed on a page read: that ArchUnit reads
bytecode, ArchUnit's default freeze-store folder, the jMolecules onion and hexagonal annotations'
exact packages, the packages of `@ApplicationModuleTest`, `@EmbeddedKafka` and WireMock's DSL,
Spring Boot slices beyond `@WebMvcTest`, `@JsonTest` and `@DataJpaTest`, Spock's Maven setup, and
any prevalence figure for Spock, Testcontainers, ArchUnit, Pact or Spring Cloud Contract. The
research is generic: it does not read Lantern's test sets.

## Findings

### Architecture tests declare the intended structure

- **ArchUnit's library rules** state architecture directly, mostly with string literals: [ArchUnit
  user guide]
  - `layeredArchitecture().consideringAllDependencies().layer("Controller").definedBy("..controller..")`
    names layers and their packages, and `whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")`
    or `mayNotBeAccessedByAnyLayer()` the allowed access;
  - `onionArchitecture().domainModels(..).domainServices(..).applicationServices(..).adapter("cli", "..adapter.cli..")`
    names the rings and the system's adapters;
  - `slices().matching("..myapp.(*)..").should().beFreeOfCycles()` or `notDependOnEachOther()`
    makes each captured package a slice, often one per module or bounded context;
  - `adhereToPlantUmlDiagram(...)` checks the code against a `.puml` component diagram.
- **How they run.** `@AnalyzeClasses(packages = ...)` on the class, `@ArchTest` on rule fields or
  methods, `ArchTests.in(Rules.class)` to reuse a rules class, `@ArchIgnore` to skip one.
  `FreezingArchRule.freeze(rule)` records today's violations and reports only new ones: a frozen
  rule is intent, not fact. [ArchUnit user guide]
- **Spring Modulith** declares most of its model in main code: each direct sub-package of the
  application's package is a module, `@ApplicationModule(allowedDependencies = ...)` and
  `@NamedInterface` on `package-info.java` restrict and expose it, and a test calling
  `ApplicationModules.of(App.class).verify()` enforces it. Its `Documenter` writes C4 component
  diagrams from the same model. [Spring Modulith fundamentals, verification, testing,
  documentation]
- **jMolecules** marks layers, onion rings and hexagonal ports and adapters with annotations in
  main code (`org.jmolecules.architecture.*`); tests enforce them with
  `JMoleculesArchitectureRules` and `JMoleculesDddRules`. [jMolecules README, integrations
  README, DDD workshop lab]
- **Taikai** packs convention rules over ArchUnit (no layers or module boundaries): quality
  evidence, not structure. [Taikai README]

### Contract tests declare connections

- **Spring Cloud Contract** keeps the producer's contracts in `src/test/resources/contracts`, as
  Groovy, YAML, Java or Kotlin: request method and URL, or for messaging an `outputMessage.sentTo`
  destination. Per-consumer folders name the consumer; a consumer's
  `@AutoConfigureStubRunner(ids = "group:artifact")` names the producer. [SCC contract DSL,
  messaging, stubs per consumer]
- **Pact JVM**: consumer tests carry `@Pact(provider, consumer)` and `@PactTestFor(providerName)`
  with the request path and method in the DSL; providers carry `@Provider` with `@PactFolder` or
  `@PactBroker`. Pact files land in `target/pacts`, usually not committed, so the test source is
  the evidence. The Pact Broker draws a network diagram from them. [Pact consumer, provider,
  broker, specification]
- **Reliability.** An edge present in a contract is strong evidence of intent; an edge absent
  says nothing: few of a small sample's tests targeted interactions, and contracts drift when CI
  does not enforce them. Participant names are free strings. [Ayas et al., lastminute.com]

### Tests name the infrastructure they need

- **Testcontainers** containers are declared as `@Container` fields (with `@Testcontainers`),
  `@Bean` methods or `@ImportTestcontainers` interfaces, e.g.
  `new PostgreSQLContainer("postgres:16")`. Spring Boot's `@ServiceConnection` maps container
  classes to technologies: `PostgreSQLContainer`, `MySQLContainer`, `MariaDBContainer`,
  `MSSQLServerContainer`, `OracleContainer` to JDBC; `KafkaContainer`, `ConfluentKafkaContainer`,
  `RedpandaContainer` to Kafka; `RabbitMQContainer`; `MongoDBContainer`; `RedisContainer` or the
  `redis` image; and others (Cassandra, Elasticsearch, Neo4j, Pulsar, ActiveMQ). A
  `GenericContainer` is known by its image name. `@DynamicPropertySource` names the Spring
  properties the container stands in for. [Spring Boot Testcontainers]
- `@EmbeddedKafka(topics = ...)` names Kafka topics; WireMock's `stubFor(get(urlEqualTo(...)))`
  names outbound HTTP paths but not the system; Testcontainers' `ComposeContainer` points at a
  compose file. [Spring Kafka testing, WireMock, Testcontainers compose]
- **What it shows.** The data stores, brokers and external systems the team treats as real
  enough to emulate, with likely versions from the image tags. It corroborates what config and
  code show; it does not prove production topology.

### Layout, slices and counting

- Failsafe runs `IT*`, `*IT` and `*ITCase` by default; Spring Boot slices (`@WebMvcTest`,
  `@DataJpaTest`, `@SpringBootTest`) show how a module is tested, not how it is structured.
  [Failsafe, Spring Boot testing]
- **JUnit 5 composed annotations**: a method "directly annotated or meta-annotated" with `@Test`
  and its kin is a test, so a project's own `@FastTest` carrying `@Test` makes tests that an
  import-only matcher misses. [JUnit 5 user guide]
- Spock specs need a Groovy toolchain; Cucumber features describe behaviour, not structure. No
  prevalence figure was found for either. [Spock, Cucumber, JetBrains]

### Prior art

Tools use tests as architecture evidence only inside their own ecosystems: the Pact Broker's
network diagram, Spring Modulith's Documenter and ArchUnit's PlantUML rules. A 2026 literature
review of microservice reconstruction lists source, annotations, build and deployment files as
static evidence, not tests; no study mining ArchUnit rules or Testcontainers declarations was
found. [Pact Broker, Spring Modulith documentation, Grinenko et al.]

## Options

1. **Counts only** (as now): exact and cheap, but blind to declared rules, contracts and
   infrastructure.
2. **Declared architecture rules** from ArchUnit's library rules, with frozen and ignored flags;
   Modulith and jMolecules declarations from main code.
3. **Contracts** as declared connections: SCC contract files, Pact test sources and committed
   pact files.
4. **Test infrastructure**: Testcontainers classes and images, `@ServiceConnection`, embedded
   Kafka topics, WireMock paths; labelled as seen in tests.
5. **Counting fixes**: composed annotations, Failsafe includes, Spock and Cucumber.
6. **Semantic recovery** from slices, mocked beans and Modulith scenarios: little beyond what the
   main-code extractors give, at high cost.

## Decision

Take 4, 2 (ArchUnit's part) and 5 (composed annotations) in the Test extractor now, since they
are common, read from literals, and give facts the report has no other source for: the
infrastructure tests run against, and the layers or slices a team declares. The rest goes to
issues in `Later`:

- Modulith and jMolecules declarations, which are main code and belong to the Spring or Java
  extractor;
- contracts as declared connections, which need participant names resolved by the linker;
- using the new facts in the brief and the C4 report, so each shows its own effect on the
  output.

Of option 4, only the containers are taken now: WireMock paths do not name the system they stub,
and `@DynamicPropertySource` keys add little beyond the container. Skip Taikai's rule contents,
slices, Failsafe classification, Spock, Cucumber, Kotlin and
`target/` output (generated pacts and Modulith documents), which are not committed. Lantern
never claims a declared rule holds: it does not run the tests. Infrastructure seen only in tests
is labelled as such wherever it is shown.

### Evidence from the development sets

- project-1 and project-4 start PostgreSQL 16, RabbitMQ 4.1 and Redis 8.4 with Testcontainers,
  some with `@ServiceConnection`, mostly in abstract base classes that the extractor did not read
  as test classes.
- No development set has ArchUnit, Modulith, jMolecules, contract tests, Spock or composed test
  annotations; unit tests cover them.

## Sources

- ArchUnit User Guide: https://www.archunit.org/userguide/html/000_Index.html
- Spring Modulith, Verification: https://docs.spring.io/spring-modulith/reference/verification.html
- Spring Modulith, Fundamentals: https://docs.spring.io/spring-modulith/reference/fundamentals.html
- Spring Modulith, Testing: https://docs.spring.io/spring-modulith/reference/testing.html
- Spring Modulith, Documentation: https://docs.spring.io/spring-modulith/reference/documentation.html
- jMolecules: https://github.com/xmolecules/jmolecules
- jMolecules integrations: https://github.com/xmolecules/jmolecules-integrations
- DDD workshop, jMolecules ArchUnit lab: https://o-s-expert.github.io/ddd-workshop/06_04_lab
- Taikai: https://github.com/enofex/taikai
- Spring Cloud Contract, Contract DSL: https://docs.spring.io/spring-cloud-contract/reference/project-features-contract.html
- Spring Cloud Contract, Messaging: https://docs.spring.io/spring-cloud-contract/reference/project-features-messaging.html
- Spring Cloud Contract, Stubs per consumer: https://docs.spring.io/spring-cloud-contract/reference/project-features-stubrunner/stub-runner-stubs-per-consumer.html
- Pact JVM consumer, JUnit 5: https://docs.pact.io/implementation_guides/jvm/consumer/junit5
- Pact JVM provider, JUnit 5: https://docs.pact.io/implementation_guides/jvm/provider/junit5
- How Pact works: https://docs.pact.io/getting_started/how_pact_works
- Pact Broker: https://docs.pact.io/pact_broker
- Pact specification v3: https://github.com/pact-foundation/pact-specification/tree/version-3
- Spring Boot, Testcontainers: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Spring Boot, Testing Spring Boot applications: https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html
- Testcontainers, Docker Compose module: https://java.testcontainers.org/modules/docker_compose/
- Spring for Apache Kafka, Testing: https://docs.spring.io/spring-kafka/reference/testing.html
- WireMock, JUnit Jupiter: https://wiremock.org/docs/junit-jupiter/
- WireMock, Stubbing: https://wiremock.org/docs/stubbing/
- Maven Failsafe, inclusions and exclusions: https://maven.apache.org/surefire/maven-failsafe-plugin/examples/inclusion-exclusion.html
- JUnit 5.9.2 User Guide: https://docs.junit.org/5.9.2/user-guide/
- Cucumber JUnit Platform engine: https://github.com/cucumber/cucumber-jvm/tree/main/cucumber-junit-platform-engine
- Spock 2.3, Getting Started: https://spockframework.org/spock/docs/2.3/getting_started.html
- JetBrains Developer Ecosystem 2023, Java: https://www.jetbrains.com/lp/devecosystem-2023/java/
- Ayas et al., consumer-driven contract testing in microservices (SEAA 2022): https://www.slideshare.net/slideshow/an-empirical-analysis-of-microservices-systems-using-consumerdriven-contract-testing/252773917
- Grinenko et al., methods for reconstructing microservice architectures (CEUR-WS Vol-4278): https://ceur-ws.org/Vol-4278/paper10.pdf
- lastminute.com, Impacts of contract tests: https://technology.lastminute.com/impacts-of-contract-tests-in-a-microservice-architecture/
