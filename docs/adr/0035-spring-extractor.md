# 0035: Spring / Jakarta extractor

Status: accepted (#127, part of #92), pending the user's review (#24)

## Context

`docs/design.md` (Static analysis and extractors) says the Spring / Jakarta extractor reads
annotations, including meta-annotations (a custom annotation carrying `@RestController` or
`@Service` counts as one), and bean definitions, and produces application main classes,
controllers and endpoints, beans by stereotype, configuration, `@Scheduled` jobs and `@Profile`
gates. Issue #127 asks for this through the Java extractor's javac and parser paths, with
placeholders resolved through the config extractor (ADR 0032). Later steps need these facts:
deployable units (#93) take the main classes, component diagrams (#95) the stereotypes, and
sequence flows (#96) start at entry points. The design leaves open how the extractor reads the
code, what its facts look like and how it handles inherited mappings, libraries and secrets.

## Decisions

- **One reading of the code.** `JavaExtractor.analyze(repos)` reads the snapshot's code once
  (javac or parser, as ADR 0007 decides) into a `JavaAnalysis`; the Java structure extractor and
  the Spring extractor both take it, so the analysis run compiles each module once. Both analyzers
  now also record the annotations written on types and members: each annotation's binary type
  name and its values as strings, constants folded (javac folds them itself; the parser folds
  literals, arithmetic, string concatenation and constants it can find in the snapshot's sources,
  and names enum constants and class literals). A container of repeated annotations
  (`@Schedules`) is read as the annotations in it, each where it is written. Facts of the Java
  structure extractor do not change.
- **Meta-annotations** come, in this order, from the annotation type's declaration in the analyzed
  sources, from the classpath as javac read it, or from a small list of well-known annotations
  (Spring stereotypes, Boot, request mappings, scheduling, Jakarta and javax REST) shipped with
  Lantern for code that was only parsed. The parser also uses that list to name an annotation
  imported by a wildcard import when no jar resolves it, and names a constant of an enum those
  annotations take (`RequestMethod.PUT`, `TimeUnit.SECONDS`) when no jar resolves it. A test checks that the javac and parser
  paths give the same Spring facts for the same code, and project-0 is checked both ways.
- **Facts**, all attributes with a string object, confidence that of the module's reading (1.0
  compiled, 0.7 parsed), evidence the annotation's lines:
  - `stereotype` on a class: `controller`, `service`, `repository`, `configuration` or
    `component`, the most specific its annotations carry, with `annotation` (the one written) and
    `via` (the meta-annotations leading to the stereotype). A controller whose responses are the
    body (`@ResponseBody`, so `@RestController`) has `rest: true`. A Jakarta REST resource (a class
    with `@Path`) is a `controller` too. Annotation types, interfaces, enums and records get none.
  - `main-class` on a class: `spring-boot` for a class carrying `@EnableAutoConfiguration` (so
    `@SpringBootApplication`), `java` for another class with a `main(String[])` method; `main`
    names the main method. Which ones make a deployable is #93's to decide.
  - `entry-point`, a parent type with two children now, so later extractors add theirs (message
    listeners, #129) and #96 finds every kind through the parent: `endpoint` and `scheduled`.
  - `endpoint` on a handler method: `GET /api/orders/{id}`, or the path alone when the mapping
    takes any HTTP method, with `httpMethod`, `path`, `controller` (the controller's binary name),
    `annotation` and `framework` (`spring-mvc` or `jakarta-rest`). A mapping carried by a custom
    annotation (`@ApiController` meta-annotated with `@RequestMapping("/api")`) takes its path from
    the nearest annotation along the chain that sets one; one written directly wins over one
    carried. Paths join the class and method
    mappings with one slash between, one fact per path and HTTP method. Spring controllers and
    Jakarta resources inherit mappings from the supertypes in the analyzed sources (an interface
    an OpenAPI generator wrote, an abstract base controller): the subject is the nearest
    declaration of the method, so an inherited method that is not overridden is the base type's
    method, and `controller` says which controller serves it. Evidence is where the mapping is
    written, in the repository and module that declare it, which may be another repository (an
    API library). A class whose Jakarta `@Path` is on a supertype is a resource too. Jakarta sub-resource locators
    (`@Path` without an HTTP method) and `@ApplicationPath` prefixes are left out.
  - `scheduled` on a method: the cron expression, delay or rate, with `trigger` (`cron`,
    `fixed-delay`, `fixed-rate`) and `timeUnit` when written.
  - `bean` on a `@Bean` method: the bean's name. Its type and what it is used for (a
    `RestTemplate`, a queue) are left to the extractors that need them (#128 to #130).
  - `profile-gate` on a type (not an annotation type) or `@Bean` method with `@Profile`, directly or through a custom
    annotation: the expression, several joined as `(a) | (b)` as Spring reads them, with
    `profiles` as written and `annotation` for a custom one. The gate is recorded where it is
    written; a reader applies a configuration class's gate to its `@Bean` methods and a class's
    gate to its endpoints and jobs. `@Conditional*` is not read.
- **Placeholders** in paths and schedules resolve through `ModuleConfig.resolve`, as for config
  values: the default view, plus a fact per profile whose value differs, marked `profile`, with
  `written` and `unresolved` as in ADR 0032. A module's code uses its own config; a module without
  any (a library) uses the config of the one module of its repository that has config, if there is
  exactly one, else placeholders stay unresolved (defaults in `${key:default}` still apply). Which
  deployable's config applies to a library from another repository is #93's to decide.
- **Secrets.** A value a secret was substituted into (a key `ConfigExtractor.isSecret` names) is
  recorded as written, placeholders kept, with `masked: true` and no other value attributes. Config
  values reach no other attribute.
- **New vocabulary**, applied by the app on startup after the other extractors', with source
  `spring-extractor`: the attribute types `stereotype`, `main-class`, `entry-point`, `endpoint`,
  `scheduled`, `bean` and `profile-gate`.
- **Not covered:** `@Value` following and message listeners (the HTTP client and messaging
  extractors, #128 and #129, since following a value one hop needs the call it reaches), CDI and
  EJB beans, functional (router function) endpoints, XML bean definitions, and Kotlin.
- **project-0.** `Project0SpringIT` checks the cases SPR-1 to SPR-4 (the gate), ENTRY-1 and VAL-3
  (`@Scheduled`), with the repositories built in the sandbox and read through javac, and parsed
  without builds, and checks both give the same facts. It runs when `lantern.test.project0` is set,
  with the same command as `Project0BuildIT` (see Commands in `CLAUDE.md`) but `-pl
  lantern-extract` and `-Dit.test=Project0SpringIT`.

## Amendment: JobRunr and Quartz jobs (#263)

Status: accepted, pending the user's review (#24). project-4 runs its task reminders as JobRunr
jobs, so `@Scheduled` alone left its reminder flow without an entry point. With extractor VERSION 2,
a new `entry-point` child `background-job` and optional attributes on `scheduled` mark them
(vocabulary changes are additive, ADR 0002, so `scheduled`'s `trigger` values stay as they were).
`framework` (`jobrunr` or `quartz`) is on both; it is absent on `@Scheduled`.

- **JobRunr request handler**: the `run(Request)` method of a class that implements
  `JobRequestHandler<Request>` (also through a supertype in the analyzed sources).
  `background-job`, value the `@Job(name)` or, without one, the request type; `kind:
  job-request`, `request`, and `scheduledBy`: the methods that pass `new Request(...)` to
  `BackgroundJobRequest` or `JobRequestScheduler` (`enqueue`, `schedule`, `scheduleRecurrently`,
  `create`), found in any module, with `cron` when `scheduleRecurrently` gets a literal one.
- **JobRunr `@Job` methods** that are not handlers: `background-job`, `kind: job`, value the name
  or the method. `@Recurring(cron)`: `scheduled`, `trigger: cron`, resolved like `@Scheduled`'s;
  `@Recurring(interval)`: `background-job`, `kind: interval`. Jobs queued by a lambda
  (`BackgroundJob.enqueue(() -> ...)`) are not followed to their target.
- **Quartz**: `execute(JobExecutionContext)` of a `Job`, `executeInternal` of a `QuartzJobBean`.
  With the cron of the `CronScheduleBuilder.cronSchedule(...)` in the same expression as the
  `JobBuilder.newJob(X.class)` (a scheduler call joins them), or the only unpaired one of a module
  that has exactly one such job: `scheduled`, `trigger: cron`, with `job` and `scheduledBy`; else
  `background-job`, `kind: quartz`, value the class. Simple, calendar and Spring
  `SimpleTriggerFactoryBean` triggers are not read.

`org.jobrunr.scheduling.` and `org.quartz.` join the tracked APIs. Flows start at both predicates
like at `@Scheduled` ones, so the call graph from the handler reaches what it publishes; documents
call them "JobRunr job" and "Quartz job".
