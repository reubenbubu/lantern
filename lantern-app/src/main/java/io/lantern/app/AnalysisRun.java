package io.lantern.app;

import io.lantern.compose.Composer;
import io.lantern.compose.ViewDefinition;
import io.lantern.compose.ViewDefinitionStore;
import io.lantern.core.analysis.AnalysisLock;
import io.lantern.core.document.Document;
import io.lantern.core.document.TemplateRef;
import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactRepository;
import io.lantern.core.fact.Origin;
import io.lantern.core.repo.Repo;
import io.lantern.core.repo.RepoRegistry;
import io.lantern.core.repo.RepoSettings;
import io.lantern.core.repo.SystemRegistry;
import io.lantern.core.repo.SystemTitle;
import io.lantern.core.snapshot.Snapshot;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.core.types.Types;
import io.lantern.derive.Derivation;
import io.lantern.derive.Findings;
import io.lantern.extract.ExtractionCache;
import io.lantern.extract.Extractors;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.identity.IdentityExtractor;
import io.lantern.harness.AnswerStore;
import io.lantern.harness.Fingerprints;
import io.lantern.harness.Harness;
import io.lantern.harness.RunBudget;
import io.lantern.harness.checks.InjectionScreen;
import io.lantern.link.GraphTools;
import io.lantern.link.LinkResolver;
import io.lantern.link.Linker;
import io.lantern.link.RuleRuns;
import io.lantern.link.ValueResolution;
import io.lantern.llm.LlmClient;
import io.lantern.sandbox.BuildOrder;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.SandboxException;
import io.lantern.sandbox.UpstreamBuild;
import io.lantern.summarize.GitSourceFiles;
import io.lantern.summarize.Summarizer;
import io.lantern.summarize.SummaryTree;
import io.lantern.summarize.describe.Describer;
import io.lantern.summarize.describe.NameGroups;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One analysis run over a set of local repositories, every stage in order: register the
 * repositories, take a snapshot of their checked-out commits, build them in the sandbox, extract
 * and store their facts, summarize them with the LLM, have it word the responsibilities of units
 * and components and the intent of their links (ADR 0050), compose an Architecture Explorer
 * document, an Executive Brief and a C4 report per repository and a C4 report of the whole system,
 * and render each as single-file HTML (ADR 0016, ADR 0031, ADR 0044).
 *
 * <p>A run analyzes one named system (ADR 0071): the repositories it names join that system, and
 * its snapshot describes the whole system: every repository of the system an earlier snapshot
 * analyzed that is not named in this run is carried forward at its commit in the latest snapshot
 * that analyzed it, and its facts keep holding. Repositories of other systems take no part. Only
 * the named repositories are built, summarized and composed, and only their facts are recorded; the
 * extractors see the whole snapshot (ADR 0018). Documents are written under the system's name and
 * titled by its title.
 *
 * <p>Everything that can be reused is: a stored successful build of a {@code repo@commit}, stored
 * facts, and cached summaries. A run over commits that an earlier run already analyzed therefore
 * makes no LLM calls.
 *
 * <p>Runs hold the analysis lock, so only one runs at a time.
 */
public final class AnalysisRun {

  /** The template of the per-repository Architecture Explorer document. */
  static final TemplateRef EXPLORER = new TemplateRef("architecture-explorer", "1");

  /** The template of the per-repository Executive Brief. */
  static final TemplateRef BRIEF = new TemplateRef("executive-brief", "1");

  /** The template of the C4 reports, the report spec of the same name (ADR 0031). */
  static final TemplateRef C4_REPORT = new TemplateRef(C4ReportRenderer.SPEC, "1");

  /**
   * The view of the system's C4 report. Its ID holds a colon, which repository names cannot, so it
   * is never a repository's view.
   */
  static final String SYSTEM_C4 = "system:c4";

  private static final Logger LOG = LoggerFactory.getLogger(AnalysisRun.class);

  /** The origin kinds of the extractors' facts, each recorded for the snapshot on its own. */
  private static final List<Origin.Kind> EXTRACTED_KINDS =
      List.of(Origin.Kind.STATIC, Origin.Kind.README);

  private final ExtractionCache extractionCache;
  private final RepoRegistry registry;
  private final SystemRegistry systems;
  private final SnapshotStore snapshots;
  private final AnalysisLock lock;
  private final BuildSandbox sandbox;
  private final FactRepository facts;
  private final TransactionTemplate transaction;
  private final LlmAccess llm;
  private final JdbcLlmCallLog callLog;
  private final AnswerStore answers;
  private final RunBudget runBudget;
  private volatile int batchAbove = Harness.BATCH_ABOVE;
  private volatile Map<String, Harness.Calibration> calibrations = Map.of();
  private volatile Map<String, Integer> itemsPerRequest = Map.of();
  private volatile boolean judgeWording;
  private volatile boolean supportByWriter = true;
  private final RuleRuns ruleRuns;
  private final ViewDefinitionStore views;
  private final HtmlRenderer renderer;
  private final C4ReportRenderer c4Renderer;
  private final String toolVersion;
  private final Path documentsDirectory;

  /**
   * @param documentsDirectory where rendered documents are written, one directory per system and in
   *     it one per snapshot
   */
  public AnalysisRun(
      RepoRegistry registry,
      SystemRegistry systems,
      SnapshotStore snapshots,
      AnalysisLock lock,
      BuildSandbox sandbox,
      FactRepository facts,
      TransactionTemplate transaction,
      LlmAccess llm,
      JdbcLlmCallLog callLog,
      AnswerStore answers,
      RunBudget runBudget,
      ViewDefinitionStore views,
      HtmlRenderer renderer,
      C4ReportRenderer c4Renderer,
      ExtractionCache extractionCache,
      RuleRuns ruleRuns,
      String toolVersion,
      Path documentsDirectory) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.systems = Objects.requireNonNull(systems, "systems");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.lock = Objects.requireNonNull(lock, "lock");
    this.extractionCache = Objects.requireNonNull(extractionCache, "extractionCache");
    this.ruleRuns = Objects.requireNonNull(ruleRuns, "ruleRuns");
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    this.facts = Objects.requireNonNull(facts, "facts");
    this.transaction = Objects.requireNonNull(transaction, "transaction");
    this.llm = Objects.requireNonNull(llm, "llm");
    this.callLog = Objects.requireNonNull(callLog, "callLog");
    this.answers = Objects.requireNonNull(answers, "answers");
    this.runBudget = Objects.requireNonNull(runBudget, "runBudget");
    this.views = Objects.requireNonNull(views, "views");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.c4Renderer = Objects.requireNonNull(c4Renderer, "c4Renderer");
    this.toolVersion = Objects.requireNonNull(toolVersion, "toolVersion");
    this.documentsDirectory = Objects.requireNonNull(documentsDirectory, "documentsDirectory");
  }

  /**
   * Asks the named roles at other packet sizes for packet-size calibration ({@code
   * lantern.harness.calibrate}; #529), each fingerprinted apart by its size and trial.
   */
  AnalysisRun calibrating(Map<String, Harness.Calibration> calibrations) {
    this.calibrations = Map.copyOf(calibrations);
    return this;
  }

  /**
   * How many items each named role asks a request ({@code
   * lantern.harness.items-per-request.<role>}; #529, ADR 0112), instead of its spec's size.
   */
  AnalysisRun itemsPerRequest(Map<String, Integer> itemsPerRequest) {
    this.itemsPerRequest = Map.copyOf(itemsPerRequest);
    return this;
  }

  /**
   * Whether the run ends by judging the roles' texts with the wording judge ({@code
   * lantern.judge.wording}, off unless set; ADR 0111), which measures them for scoring.
   */
  AnalysisRun judgingWording(boolean judge) {
    this.judgeWording = judge;
    return this;
  }

  /**
   * Who checks a text's support ({@code lantern.harness.support-judge.model}): {@code writer}, the
   * default, the model that wrote it; {@code judge}, the support judge's own model ({@code
   * lantern.llm.roles.judge}) for every text (ADR 0101, amendment for #751).
   */
  AnalysisRun supportJudgedBy(String by) {
    this.supportByWriter =
        switch (by == null ? "" : by.strip()) {
          case "", "writer" -> true;
          case "judge" -> false;
          default ->
              throw new IllegalStateException(
                  "lantern.harness.support-judge.model is writer or judge: " + by);
        };
    return this;
  }

  /**
   * Sends a bulk role's requests through the batch API once it has more than the given number of
   * cache misses in a run ({@code lantern.llm.batch-above}, {@value Harness#BATCH_ABOVE} unless
   * set).
   */
  AnalysisRun batchingAbove(int misses) {
    if (misses < 0) {
      throw new IllegalArgumentException("lantern.llm.batch-above must not be negative: " + misses);
    }
    this.batchAbove = misses;
    return this;
  }

  /**
   * What a run did.
   *
   * @param system the name of the system it analyzed
   * @param title the system's title, which its documents carry (ADR 0071)
   * @param snapshot the snapshot it analyzed, which also has the repositories carried forward
   * @param builds the build of each repository it analyzed that has a Maven build, by name
   * @param reusedBuilds the names of the repositories whose stored build was reused
   * @param withoutBuild the names of the repositories it analyzed without a build, because they
   *     have no Maven build (no top {@code pom.xml}), such as a frontend or deployment files; that
   *     is not degraded mode
   * @param codeNotAnalyzed of those, the ones that have what only a Maven build would let Lantern
   *     analyze (Java sources, Gradle build files, POMs below the top), with what they have; their
   *     code is not analyzed
   * @param facts how many facts the extractors found, with the linker's facts about the analyzed
   *     repositories
   * @param newFacts how many facts did not hold in the previous snapshot, the linker's about any
   *     repository included
   * @param linksAsked how many links static matching left open the LLM was asked about (ADR 0045)
   * @param linksReused how many of their answers came from the cache
   * @param summariesGenerated how many summaries the LLM wrote
   * @param summariesReused how many summaries came from the cache
   * @param summariesRejected the units left without a summary because the LLM's answer was rejected
   *     twice
   * @param wordingsAsked how many deployable units, data stores, destinations and flows, and the
   *     system for its purpose, the LLM was asked to word (ADR 0050, ADR 0056, ADR 0058, ADR 0060)
   * @param wordingsReused how many of their wordings came from the cache
   * @param llm the LLM calls made during the run, whatever their outcome, by role
   * @param roles what each role of the harness planned, estimated, asked and spent (ADR 0100)
   * @param documents the rendered documents, in the order written
   * @param runRecord the file that records each role's model and the run's cost, next to the
   *     documents
   */
  public record Result(
      String system,
      String title,
      Snapshot snapshot,
      Map<String, BuildResult> builds,
      List<String> reusedBuilds,
      List<String> withoutBuild,
      Map<String, List<String>> codeNotAnalyzed,
      int facts,
      int newFacts,
      int linksAsked,
      int linksReused,
      int summariesGenerated,
      int summariesReused,
      List<EntityId> summariesRejected,
      int wordingsAsked,
      int wordingsReused,
      JdbcLlmCallLog.Spending llm,
      List<io.lantern.harness.RoleRecord> roles,
      List<Rendered> documents,
      Path runRecord,
      Set<String> secrets) {

    public Result {
      summariesRejected = List.copyOf(summariesRejected);
      builds = Collections.unmodifiableMap(new TreeMap<>(builds));
      reusedBuilds = List.copyOf(reusedBuilds);
      withoutBuild = List.copyOf(withoutBuild);
      Map<String, List<String>> code = new TreeMap<>();
      codeNotAnalyzed.forEach((repo, kinds) -> code.put(repo, List.copyOf(kinds)));
      codeNotAnalyzed = Collections.unmodifiableMap(code);
      documents = List.copyOf(documents);
      secrets = Set.copyOf(secrets);
    }

    /** The run in brief, the secret values counted, never shown, so logging it leaks none. */
    @Override
    public String toString() {
      return "Result[system="
          + system
          + ", snapshot="
          + snapshot.number()
          + ", facts="
          + facts
          + ", documents="
          + documents.size()
          + ", secrets="
          + secrets.size()
          + " values]";
    }
  }

  /**
   * A composed and rendered document.
   *
   * @param document the document model
   * @param file the HTML file it was rendered to
   */
  public record Rendered(Document document, Path file) {}

  /**
   * Analyzes repositories of a named system at their checked-out commits.
   *
   * @param system the system's name; it is created if it does not exist
   * @param title the system's title to store: {@code null} keeps the stored one, blank clears it;
   *     without a stored title the documents carry the one {@link SystemTitle} derives
   * @param repos the repositories, by name, each the top directory of a git working tree; a
   *     repository registered before keeps its settings
   * @throws LlmNotConfiguredException if the LLM is not configured; checked before anything else
   * @throws io.lantern.core.analysis.AnalysisAlreadyRunningException if another analysis is running
   * @throws IllegalArgumentException if a name or path is not a valid repository, the system name
   *     is not valid, or a repository belongs to another system
   */
  public Result run(String system, String title, Map<String, Path> repos) {
    SystemRegistry.checkName(system);
    if (repos.isEmpty()) {
      throw new IllegalArgumentException("Name at least one repository to analyze");
    }
    Instant started = Instant.now();
    LlmClient client = llm.client();
    try (AnalysisLock.Held held = lock.acquire()) {
      // Refused before anything is stored, so a repository of another system keeps its path and
      // settings, and no system is created.
      systems.checkCanJoin(system, repos.keySet());
      Map<String, Repo> registered = new TreeMap<>();
      Map<String, String> revisions = new TreeMap<>();
      for (Map.Entry<String, Path> entry : new TreeMap<>(repos).entrySet()) {
        String name = entry.getKey();
        RepoSettings settings =
            registry.find(name).map(Repo::settings).orElse(RepoSettings.DEFAULT);
        registered.put(name, registry.register(name, entry.getValue(), settings));
        revisions.put(name, RepoRegistry.HEAD);
      }
      systems.define(system, title);
      systems.join(system, registered.keySet());
      Snapshot snapshot = snapshots.createCarryingForward(system, revisions);
      LOG.info("Snapshot {} of {}: {}", snapshot.number(), system, snapshot.commits());
      snapshot.commits().keySet().stream()
          .filter(name -> !registered.containsKey(name))
          .forEach(name -> LOG.info("Carried forward without analyzing: {}", name));

      List<String> reused = new ArrayList<>();
      List<String> withoutBuild = new ArrayList<>();
      Map<String, List<String>> codeNotAnalyzed = new TreeMap<>();
      List<RepoBuild> carried = carriedForward(snapshot, registered.keySet());
      Map<String, BuildResult> builds =
          build(registered, carried, snapshot, reused, withoutBuild, codeNotAnalyzed);
      List<RepoBuild> repoBuilds = new ArrayList<>();
      registered.forEach(
          (name, repo) ->
              repoBuilds.add(new RepoBuild(repo, snapshot.commits().get(name), builds.get(name))));
      repoBuilds.addAll(carried);

      // The extractors see the whole snapshot, so whether a dependency or call reaches another
      // repository does not depend on which repositories this run names. Only the named
      // repositories' facts are recorded. Each extractor's units come from the cache when nothing
      // they depend on changed (ADR 0092).
      Extractors.Result extraction =
          new Extractors(Extractors.load(), extractionCache).run(snapshot.number(), repoBuilds);
      LOG.info(
          "Extraction: {} units extracted, {} read from the cache",
          extraction.extracted(),
          extraction.cached());
      int pruned = extractionCache.prune();
      if (pruned > 0) {
        LOG.info("Deleted {} extraction cache entries no run used for 30 days", pruned);
      }
      List<Fact> found = new ArrayList<>(extraction.facts());
      // Config keys the code uses as a JWT secret are masked wherever they appear (#335).
      Set<String> secretKeys =
          ConfigExtractor.jwtSecretKeys(extraction.facts(IdentityExtractor.NAME));
      found.removeIf(fact -> !registered.containsKey(fact.evidence().repo()));
      // The resolver's tools read files of every repository of the snapshot at its commit there,
      // carried forward ones included (ADR 0081).
      GitSourceFiles snapshotSources =
          new GitSourceFiles(
              name ->
                  Optional.ofNullable(registered.get(name))
                      .or(() -> registry.find(name))
                      .map(Repo::path));
      // What masking takes out of the files shown to the roles, which no accepted answer may hold;
      // filled as the files are shown, and read live by the harness's Secrets check (#645).
      Set<String> secrets = java.util.concurrent.ConcurrentHashMap.newKeySet();
      GraphTools.RepoFiles snapshotFiles =
          new GraphTools.RepoFiles() {
            @Override
            public void masked(String value) {
              secrets.add(value);
            }

            @Override
            public Optional<byte[]> read(String repo, String path) {
              return Optional.ofNullable(snapshot.commits().get(repo))
                  .flatMap(commit -> snapshotSources.read(repo, commit, path));
            }

            // find_in_code searches each repository at the snapshot's commit (ADR 0105).
            @Override
            public List<GraphTools.Hit> find(String repo, String text, int max) {
              String commit = snapshot.commits().get(repo);
              return commit == null
                  ? List.of()
                  : snapshotSources.grep(repo, commit, text, max).stream()
                      .map(match -> new GraphTools.Hit(match.path(), match.line()))
                      .toList();
            }
          };
      // Every LLM role of the run goes through one harness: one answer cache, one budget, one
      // record (ADR 0100). A cached answer that read files holds while they keep their blobs.
      Harness harness =
          new Harness(
                  client,
                  answers,
                  runBudget,
                  (repo, path) -> snapshotFiles.read(repo, path).map(Fingerprints::gitBlob),
                  secrets)
              .batchingAbove(batchAbove);
      itemsPerRequest.forEach(harness::itemsPerRequest);
      calibrations.forEach(harness::calibrating);
      calibrations.forEach(
          (role, c) ->
              LOG.info(
                  "Calibration: {} asks {} items a request, trial {}",
                  role,
                  c.packetSize(),
                  c.trial()));
      Stored stored =
          store(
              snapshot.number(),
              registered.keySet(),
              snapshot.commits().keySet(),
              found,
              harness,
              snapshotFiles);
      List<Fact> extracted = stored.facts();
      int newFacts = stored.added();
      LOG.info("Extracted {} facts, {} of them new", extracted.size(), newFacts);

      // Prompts show source excerpts read from git at the commits the facts were observed in.
      GitSourceFiles sources =
          new GitSourceFiles(name -> Optional.ofNullable(registered.get(name)).map(Repo::path))
              .withSecretKeys(secretKeys)
              .withMaskedValues(secrets::add);
      // The summaries read the facts the extractors recorded before they kept values as written:
      // value resolution's facts per profile (ADR 0094), derived here as linking does; its views
      // of deployments need the units, which linking derives later, and summaries do not read them.
      List<Fact> resolved = new ValueResolution().derive(extracted, snapshot.number());
      List<Fact> summarizable = new ArrayList<>(extracted);
      summarizable.addAll(resolved);
      // Only the types the architecture model needs are summarized: its entry points, component
      // anchors and members, which leave out domain, DTO, mapper, configuration and util classes
      // (knowledge-build epic, section 11.3).
      List<io.lantern.summarize.SummaryNode> units =
          SummaryTree.fromFacts(summarizable, modelTypes(stored.linkerFacts()));
      LOG.info(
          "Summarize: units per level {}, of {} the facts hold",
          levels(units),
          levels(SummaryTree.fromFacts(summarizable)));
      // Their prompts show the code of components, read from every registered repository, carried
      // forward ones included, so a run gives the same prompts whichever repositories it names
      // (ADR 0069).
      GitSourceFiles allSources =
          new GitSourceFiles(
                  name ->
                      Optional.ofNullable(registered.get(name))
                          .or(() -> registry.find(name))
                          .map(Repo::path))
              .withSecretKeys(secretKeys)
              .withMaskedValues(secrets::add);
      // The roles write their texts, the screen reads what they were shown, and the support judge
      // checks what they wrote. A text it rejects is asked again once, told why, in a further pass
      // that reuses every other answer from the cache; a text first rejected in that pass, such as
      // a group name worded anew from a retried sentence, gets its one retry in the next; what is
      // rejected again is left to the plain wording (ADR 0101, the owner's decision of 4 Nov 2026
      // on #751).
      io.lantern.summarize.SupportChecks.Result support;
      Summarizer.Result summarizedFirst = null;
      Describer.Result describedFirst = null;
      List<io.lantern.summarize.SupportChecks.Rejected> retriedTexts = new ArrayList<>();
      Set<String> marked = new java.util.HashSet<>();
      for (int pass = 1; ; pass++) {
        Summarizer.Result summarized = new Summarizer(harness, sources).summarize(units);
        summarizedFirst = summarizedFirst == null ? summarized : summarizedFirst;
        LOG.info(
            "Summaries: {} written by the LLM, {} reused",
            summarized.generated(),
            summarized.reused());
        // Stored as claims of origin llm, which later roles read (knowledge-build epic, section 6).
        Map<Origin, List<Fact>> summaryFacts =
            Summarizer.facts(summarized, summarizable, snapshot.number());
        // A run summarizes only the repositories it analyzes, so it ends only their summaries;
        // those
        // of repositories carried forward keep holding.
        recordLlmFacts(snapshot, summaryFacts, registered.keySet());
        LOG.info(
            "Summarize stored {} summaries as claims of {} summaries",
            summaryFacts.values().stream().mapToInt(List::size).sum(),
            summarized.summaries().size());
        if (!summarized.rejected().isEmpty()) {
          LOG.warn(
              "Units without a summary, their answer rejected twice: {}",
              summarized.rejected().size());
        }
        if (!summarized.notAsked().isEmpty()) {
          LOG.warn(
              "Units without a summary, not asked because a budget refused the call: {}",
              summarized.notAsked().size());
        }

        // Investigate checks the leads derivation stored, then explores each unit and the system,
        // every finding cited to code Lantern verifies; what it finds is stored as claims of origin
        // llm before anything is described or composed (knowledge-build epic, section 11.2; ADR
        // 0110). It reads the facts made before it: static facts, summaries and resolved links.
        io.lantern.link.Investigator.Result investigated =
            new io.lantern.link.Investigator(
                    harness, snapshotFiles, io.lantern.derive.LeadRules.judges())
                .investigate(
                    facts.findValidAt(snapshot.number(), snapshot.commits().keySet()),
                    snapshot.number());
        recordLlmFacts(snapshot, investigated.byOrigin());
        LOG.info(
            "Investigate: {} asked, {} reused, {} leads confirmed, {} facts; not investigated: {}",
            investigated.asked(),
            investigated.reused(),
            investigated.confirmed(),
            investigated.facts().size(),
            investigated.notInvestigated());

        // The LLM words the responsibilities of the snapshot's units and components and the intent
        // of their links, and narrates the main flows, from every fact that holds in it, carried
        // forward repositories included (ADR 0050, ADR 0058), and of no other system (ADR 0071).
        // The texts an earlier run's roles stored are left out: this run words them again, and the
        // documents take the words of this run (ADR 0103).
        List<Fact> systemFacts =
            Describer.withoutTexts(
                facts.findValidAt(snapshot.number(), snapshot.commits().keySet()));
        // Each repository's documents are composed from its extracted facts, so its flows are
        // traced
        // from those.
        Describer.Result described =
            new Describer(harness, allSources)
                .judgedLater()
                .describe(systemFacts, extracted, summarized.summaries());
        describedFirst = describedFirst == null ? described : describedFirst;
        // What Describe wrote is stored as claims of origin llm before anything is composed
        // (knowledge-build epic, section 6, Derived and LLM facts).
        Map<Origin, List<Fact>> llmFacts = described.facts(snapshot.number());
        recordLlmFacts(snapshot, llmFacts);
        Map<String, Long> storedTexts =
            llmFacts.values().stream()
                .flatMap(List::stream)
                .collect(
                    java.util.stream.Collectors.groupingBy(
                        Fact::predicate,
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
        LOG.info(
            "Describe stored {}; left {} elements, components and interactions unknown: {}",
            storedTexts,
            described.unknown().size(),
            described.unknown());
        // Name groups words each group the derive stage formed from what Describe wrote of its
        // members, and stores its names, responsibilities and intents, and its merge suggestions as
        // findings (knowledge-build epic, section 11.8; ADR 0107).
        List<Fact> withTexts = new ArrayList<>(systemFacts);
        llmFacts.values().forEach(withTexts::addAll);
        NameGroups.Result named = new NameGroups(harness).name(withTexts, snapshot.number());
        recordLlmFacts(snapshot, named.byOrigin());
        if (!named.unknown().isEmpty()) {
          LOG.info("Groups left unnamed: {}", named.unknown());
        }
        // The injection screen reads every file the run's prompts showed, each blob once; what it
        // flags is a finding of origin llm, shown as a risk, never a reason to stop (ADR 0101).
        List<Fact> flagged = screen(harness, snapshot);
        // Then the support judge checks what every role stored: each Investigate risk, each text
        // resting on a range the screen flagged, and a 10% sample of the rest (knowledge-build
        // epic,
        // section 11.7; ADR 0101, amendment for #751). A verdict marks a text, never drops it.
        support =
            new io.lantern.summarize.SupportChecks(harness, allSources, snapshot.commits())
                .byWriter(supportByWriter)
                .retried(harness.retried())
                .judge(
                    facts.findValidAt(snapshot.number(), snapshot.commits().keySet()),
                    flagged,
                    snapshot.number());
        recordLlmFacts(snapshot, support.byOrigin());
        LOG.info(
            "Support judge: {} texts judged; verdicts by role {}",
            support.judged(),
            support.counts());
        Map<String, String> fresh = toRetry(pass, support.retries(), marked, harness.retried());
        if (fresh.isEmpty()) {
          break;
        }
        fresh.forEach(harness::retry);
        marked.addAll(fresh.keySet());
        support.rejected().stream()
            .filter(r -> fresh.containsKey(io.lantern.summarize.SupportChecks.answer(r.text())))
            .forEach(retriedTexts::add);
        LOG.info("Support judge: {} answers rejected; each is asked again once", fresh.size());
        harness.retryPass(true);
      }
      harness.retryPass(false);
      List<Fact> judgedFacts = facts.findValidAt(snapshot.number(), snapshot.commits().keySet());
      Map<String, Integer> retried = retried(retriedTexts);
      Map<String, Integer> fellBack = fellBack(judgedFacts, retriedTexts);
      if (!fellBack.isEmpty()) {
        LOG.info("Support judge: texts left to the plain wording, by role {}", fellBack);
      }
      // Every item a role did not ask about, or got no answer for, is stored, so the unknowns page
      // lists it (#741); the next run asks again and ends the fact.
      recordLlmFacts(snapshot, Map.of(NOT_ASKED_ORIGIN, notAsked(harness.records(), snapshot)));
      // Knowledge is complete before composition (knowledge-build epic, section 12): the documents
      // are composed from what the store holds now, the roles' texts included, and from nothing
      // the run kept in memory.
      List<Fact> complete = facts.findValidAt(snapshot.number(), snapshot.commits().keySet());
      List<Fact> composed = repositoryFacts(complete, registered.keySet());

      String systemTitle =
          systems
              .title(system)
              .orElseGet(
                  () ->
                      SystemTitle.derive(
                          system,
                          snapshot.commits().keySet(),
                          readmeHeadings(snapshot, registered)));
      List<Rendered> documents = new ArrayList<>();
      // Composed with the diagram limits the report is rendered with, so the template and the
      // scorer judge its views by the same ones (#836).
      Composer composer =
          new Composer(toolVersion, io.lantern.derive.Vocabularies.standard(), c4Renderer.limits());
      Path out = documentsDirectory.resolve(system).resolve(Long.toString(snapshot.number()));
      for (String name : registered.keySet()) {
        for (ViewDefinition view : views(name)) {
          Document document = composer.compose(view, snapshot, composed, systemTitle);
          documents.add(new Rendered(document, write(out, view.id(), document)));
        }
      }
      // The system's report shows every repository of the snapshot, carried forward ones too, and
      // is titled by the system's title, a stored view's title included.
      ViewDefinition storedView = view(ViewDefinition.c4System(SYSTEM_C4, systemTitle, C4_REPORT));
      ViewDefinition systemView =
          new ViewDefinition(
              storedView.id(),
              systemTitle,
              storedView.scope(),
              storedView.audience(),
              storedView.template(),
              storedView.sections());
      Document document = composer.compose(systemView, snapshot, complete, systemTitle);
      documents.add(new Rendered(document, write(out, systemView.id(), document)));
      documents.forEach(d -> LOG.info("Wrote {}", d.file()));
      // Every fact of origin llm that holds in the snapshot, which the scorer's rows per role read
      // (ADR 0072): resolved links, texts, judgements, verdicts and risks.
      List<Fact> heldLlmFacts =
          facts.findValidAt(snapshot.number(), snapshot.commits().keySet()).stream()
              .filter(f -> f.origin().kind() == Origin.Kind.LLM)
              .toList();
      if (judgeWording) {
        io.lantern.llm.ModelConfig judge = llm.models().get(io.lantern.llm.Role.WORDING_JUDGE);
        LOG.info(
            "Wrote {}",
            WordingJudgements.judgeAndWrite(
                harness, heldLlmFacts, judge.provider() + "/" + judge.model(), out));
      } else {
        // An earlier run's verdicts on this snapshot are not this run's.
        try {
          java.nio.file.Files.deleteIfExists(out.resolve(WordingJudgements.FILE));
        } catch (java.io.IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      }
      tools.jackson.databind.node.ObjectNode record =
          RunRecord.of(
              system,
              snapshot.number(),
              llm.models(),
              callLog.spentByModelSince(started),
              callLog.conversationsSince(started),
              harness.records());
      RunRecord.withSupport(record, support.counts(), retried, fellBack);
      Path runRecord = RunRecord.write(out, record);
      LOG.info("Wrote {}", runRecord);
      // The model's and the rank's choice of flows on the same candidates, side by side (#743).
      LOG.info(
          "Wrote {}",
          FlowSelectionFile.write(
              out, io.lantern.derive.FlowSelection.of(complete, composed, systemTitle)));
      LOG.info("Wrote {}", Rejections.write(out, harness.rejections()));
      // The values the Secrets check looked for, counted, never written (#645).
      LOG.info(
          "Masking took {} values out of the files shown to the roles; no answer kept holds one",
          secrets.size());
      // With the first link step's facts, the facts extractors recorded before they kept values as
      // written, which extraction coverage counts as theirs (ADR 0094).
      List<Fact> written = new ArrayList<>(found);
      written.addAll(resolved);
      LOG.info("Wrote {}", ExtractedFacts.write(out, written));
      // Every fact the linker derived for the snapshot, not only those about the repositories
      // analyzed in this run, so a link to a repository carried forward is there too.
      LOG.info(
          "Wrote {}", ExtractedFacts.write(out, ExtractedFacts.LINKED_FILE, stored.linkerFacts()));
      LOG.info("Wrote {}", ExtractedFacts.write(out, ExtractedFacts.LLM_FILE, heldLlmFacts));
      return new Result(
          system,
          systemTitle,
          snapshot,
          builds,
          reused,
          withoutBuild,
          codeNotAnalyzed,
          extracted.size(),
          newFacts,
          stored.linksAsked(),
          stored.linksReused(),
          summarizedFirst.generated(),
          summarizedFirst.reused(),
          summarizedFirst.rejected(),
          describedFirst.asked(),
          describedFirst.reused(),
          callLog.spentSince(started),
          checked(harness.records()),
          documents,
          runRecord,
          harness.secrets());
    }
  }

  /**
   * The most times the roles run in one analysis: once, then once more for the texts the judge
   * rejected, then once more for texts first rejected in that pass (#751).
   */
  static final int RETRY_PASSES = 3;

  /**
   * The answers to ask again after a pass (#751): those rejected for the first time, not marked in
   * an earlier pass and not themselves the answer of an item asked again, each with why; none after
   * the last pass.
   *
   * @param retries why each rejected answer would be asked again, by its fingerprint
   * @param marked the answers marked in earlier passes
   * @param retriedAnswers the fingerprints the marked items were asked again under
   */
  static Map<String, String> toRetry(
      int pass, Map<String, String> retries, Set<String> marked, Set<String> retriedAnswers) {
    Map<String, String> fresh = new java.util.TreeMap<>(retries);
    if (pass >= RETRY_PASSES) {
      return Map.of();
    }
    fresh.keySet().removeAll(marked);
    fresh.keySet().removeAll(retriedAnswers);
    return fresh;
  }

  /** How many texts of each role the support judge rejected and had asked again (#751). */
  static Map<String, Integer> retried(List<io.lantern.summarize.SupportChecks.Rejected> rejected) {
    Map<String, Integer> counts = new java.util.TreeMap<>();
    rejected.forEach(r -> counts.merge(r.text().origin().name(), 1, Integer::sum));
    return counts;
  }

  /**
   * How many texts of each role the documents show in Lantern's plain wording because the support
   * judge rejected them (#751): each text that holds with an unsupported verdict, and each text
   * rejected first whose role gave no new text for its subject when asked again.
   *
   * @param held every fact that holds once the judge is done
   * @param rejectedFirst the texts the judge rejected and had asked again, in any pass
   */
  static Map<String, Integer> fellBack(
      List<Fact> held, List<io.lantern.summarize.SupportChecks.Rejected> rejectedFirst) {
    Map<String, Integer> counts = new java.util.TreeMap<>();
    Set<String> holding = new java.util.HashSet<>();
    for (Fact fact : held) {
      if (fact.origin().kind() != Origin.Kind.LLM) {
        continue;
      }
      if ("unsupported"
          .equals(fact.attributes().path(io.lantern.harness.LlmFacts.SUPPORT).asString(""))) {
        counts.merge(fact.origin().name(), 1, Integer::sum);
      }
      holding.add(fact.origin().name() + "\u0000" + fact.subject() + "\u0000" + fact.predicate());
    }
    for (io.lantern.summarize.SupportChecks.Rejected r : rejectedFirst) {
      Fact text = r.text();
      if (!holding.contains(
          text.origin().name() + "\u0000" + text.subject() + "\u0000" + text.predicate())) {
        counts.merge(text.origin().name(), 1, Integer::sum);
      }
    }
    return counts;
  }

  /** The origin of the facts that say a role did not ask about an item (#741). */
  static final Origin NOT_ASKED_ORIGIN = new Origin(Origin.Kind.LLM, Types.NOT_ASKED, "1");

  /**
   * A {@code not-asked} fact for every item a role did not ask about or got no answer for (#741),
   * with why in words that read the same from run to run.
   */
  static List<Fact> notAsked(List<io.lantern.harness.RoleRecord> roles, Snapshot snapshot) {
    List<Fact> result = new ArrayList<>();
    String repo = new java.util.TreeSet<>(snapshot.commits().keySet()).first();
    for (io.lantern.harness.RoleRecord role : roles) {
      // Investigate stores what it did not look at itself (not-investigated, ADR 0110); a link
      // Resolve was not asked about is listed as not linked; a judge's sample writes nothing the
      // documents show.
      if (role.role().startsWith("investigate")
          || role.role().equals("resolve")
          || role.role().contains("judge")) {
        continue;
      }
      // The injection screen's items are parts of files, named as such, not entities.
      boolean files = role.role().equals("injection-screen");
      new java.util.TreeMap<>(role.withoutAnswer())
          .forEach(
              (subject, reason) -> {
                tools.jackson.databind.node.ObjectNode attributes =
                    io.lantern.core.fact.FactJson.newObject();
                attributes.put("reason", stableReason(reason));
                EntityId entity;
                try {
                  if (files) {
                    throw new IllegalArgumentException("a part of a file");
                  }
                  entity = EntityId.parse(subject);
                } catch (IllegalArgumentException e) {
                  entity = io.lantern.derive.ArchitectureModel.SYSTEM;
                  attributes.put("item", subject);
                }
                result.add(
                    Fact.create(
                        entity,
                        Types.NOT_ASKED,
                        io.lantern.core.fact.FactObject.string(role.role()),
                        attributes,
                        NOT_ASKED_ORIGIN,
                        io.lantern.core.fact.Evidence.of(repo, ".", null, null),
                        io.lantern.harness.LlmFacts.CONFIDENCE,
                        io.lantern.core.fact.Validity.from(snapshot.number())));
              });
    }
    return result;
  }

  /** Why an item was not asked, without the amounts and errors that change from run to run. */
  static String stableReason(String reason) {
    String lower = reason.toLowerCase(java.util.Locale.ROOT);
    if (lower.startsWith("the request failed")) {
      return "the request failed";
    }
    if (lower.contains("budget") || lower.contains("share")) {
      return "a budget refused it";
    }
    if (lower.contains("missing from the answer")) {
      return "missing from the answer twice";
    }
    int colon = reason.indexOf(':');
    return colon > 0 ? reason.substring(0, colon) : reason;
  }

  /**
   * Records the facts the LLM roles wrote, each role's by its origin, so that afterwards exactly
   * these hold in the snapshot: a text the role did not write this time, such as one not asked
   * under a budget, stops holding and is unknown.
   */
  private void recordLlmFacts(Snapshot snapshot, Map<Origin, List<Fact>> byOrigin) {
    recordLlmFacts(snapshot, byOrigin, snapshot.commits().keySet());
  }

  /** As above, ending only the facts of those origins whose evidence names one of {@code repos}. */
  private void recordLlmFacts(
      Snapshot snapshot, Map<Origin, List<Fact>> byOrigin, Set<String> repos) {
    transaction.executeWithoutResult(
        status ->
            byOrigin.forEach(
                (origin, found) -> facts.recordDerived(snapshot.number(), origin, repos, found)));
  }

  /** How many units of each level the trees hold, each unit once. */
  private static Map<io.lantern.summarize.SummaryLevel, Integer> levels(
      List<io.lantern.summarize.SummaryNode> roots) {
    Map<io.lantern.summarize.SummaryLevel, Integer> counts =
        new java.util.EnumMap<>(io.lantern.summarize.SummaryLevel.class);
    Set<EntityId> seen = new java.util.HashSet<>();
    java.util.ArrayDeque<io.lantern.summarize.SummaryNode> todo = new java.util.ArrayDeque<>(roots);
    while (!todo.isEmpty()) {
      io.lantern.summarize.SummaryNode node = todo.pop();
      if (seen.add(node.entity())) {
        counts.merge(node.level(), 1, Integer::sum);
        todo.addAll(node.children());
      }
    }
    return counts;
  }

  /**
   * The stored facts the analyzed repositories' documents are composed from: those observed in
   * them, the linker's about them included, the texts the LLM roles stored, and Investigate's and
   * the injection screen's findings.
   *
   * @param held the facts that hold in the snapshot, once every role has run
   * @param analyzed the repositories the run analyzed
   */
  static List<Fact> repositoryFacts(Collection<Fact> held, Set<String> analyzed) {
    return held.stream()
        .filter(
            f ->
                analyzed.contains(f.evidence().repo())
                    || io.lantern.harness.LlmFacts.isText(f)
                    || f.origin().equals(io.lantern.derive.Findings.SCREEN_ORIGIN)
                    || f.origin().kind() == Origin.Kind.LLM
                        && f.origin().name().equals(io.lantern.link.Investigator.NAME))
        .toList();
  }

  /** The types the architecture model's components are anchored on or hold as members. */
  static Set<EntityId> modelTypes(Collection<Fact> linkerFacts) {
    Set<EntityId> types = new java.util.HashSet<>();
    for (Fact fact : linkerFacts) {
      if ((fact.predicate().equals(Types.ANCHORED_ON) || fact.predicate().equals(Types.HAS_MEMBER))
          && fact.object().kind() == io.lantern.core.fact.FactObject.Kind.ENTITY) {
        EntityId type = EntityId.parse(fact.object().value());
        if (type.type().equals(Types.TYPE)) {
          types.add(type);
        }
      }
    }
    return types;
  }

  /** The records, after warning of a role whose items did not each land in one count (#654). */
  private static List<io.lantern.harness.RoleRecord> checked(
      List<io.lantern.harness.RoleRecord> roles) {
    for (io.lantern.harness.RoleRecord role : roles) {
      if (role.settled() != role.planned()) {
        LOG.warn(
            "{} planned {} items but counted {} outcomes",
            role.role(),
            role.planned(),
            role.settled());
      }
    }
    return roles;
  }

  /**
   * Screens the files the run's prompts showed and records what it flags as findings of the
   * snapshot, replacing those of the last screen only when every chunk was answered (ADR 0101).
   *
   * @return the findings that hold in the snapshot
   */
  private List<Fact> screen(Harness harness, Snapshot snapshot) {
    InjectionScreen.Result result =
        new InjectionScreen(harness).screen(InjectionScreen.chunks(harness.shown()));
    List<Fact> found = new ArrayList<>();
    for (InjectionScreen.Suspected suspected : result.suspected()) {
      found.add(
          Findings.injectionSuspected(
              suspected.chunk().repo(),
              suspected.chunk().path(),
              suspected.chunk().blob(),
              suspected.from(),
              suspected.to(),
              suspected.quote(),
              suspected.model(),
              suspected.answer(),
              snapshot.number()));
    }
    // One finding per file: the first flagged chunk.
    Map<String, Fact> byFile = new LinkedHashMap<>();
    found.forEach(f -> byFile.putIfAbsent(f.subject().toString(), f));
    // A screen speaks only for the files it read this run: an earlier finding on a file no prompt
    // showed this time, such as one only a cached Resolve answer read, still holds.
    Set<String> screenedFiles = new java.util.HashSet<>();
    harness.shown().forEach(f -> screenedFiles.add(f.repo() + "\u0000" + f.path()));
    stillHolding(
            facts.findValidAt(snapshot.number(), snapshot.commits().keySet()),
            screenedFiles,
            snapshot.number())
        .forEach(f -> byFile.putIfAbsent(f.subject().toString(), f));
    if (!result.suspected().isEmpty()) {
      LOG.warn("Injection screen: text addressed to an AI in {} files", byFile.size());
    }
    if (!result.complete()) {
      LOG.warn("Injection screen: not every chunk was answered; earlier findings are kept");
      return flaggedRanges(found, byFile.values());
    }
    transaction.executeWithoutResult(
        status ->
            facts.recordDerived(
                snapshot.number(),
                Findings.SCREEN_ORIGIN,
                snapshot.commits().keySet(),
                byFile.values()));
    return flaggedRanges(found, byFile.values());
  }

  /**
   * Every range the screen flagged this run, not only the first of each file that is stored, and
   * the findings that hold: what the support judge always checks texts against (#751).
   */
  private static List<Fact> flaggedRanges(List<Fact> found, Collection<Fact> holding) {
    List<Fact> all = new ArrayList<>(found);
    all.addAll(holding);
    return List.copyOf(all);
  }

  /**
   * The injection screen's earlier findings on files the run's screen did not read, as findings of
   * the snapshot: a screen speaks only for the files it read (ADR 0101).
   *
   * @param earlier facts that hold in the snapshot
   * @param screened the files screened this run, as repository and path joined by a NUL
   */
  static List<Fact> stillHolding(Collection<Fact> earlier, Set<String> screened, long snapshot) {
    List<Fact> kept = new ArrayList<>();
    for (Fact fact : earlier) {
      if (fact.origin().kind() != Findings.SCREEN_ORIGIN.kind()
          || !fact.origin().name().equals(Findings.SCREEN_ORIGIN.name())
          || screened.contains(fact.evidence().repo() + "\u0000" + fact.evidence().path())) {
        continue;
      }
      String[] lines = Findings.attribute(fact, "lines").split("-");
      kept.add(
          Findings.injectionSuspected(
              fact.evidence().repo(),
              fact.evidence().path(),
              fact.evidence().blob(),
              Integer.parseInt(lines[0]),
              Integer.parseInt(lines[1]),
              Findings.attribute(fact, "quote"),
              Findings.attribute(fact, "model"),
              Findings.attribute(fact, "answer"),
              snapshot));
    }
    return kept;
  }

  /**
   * The first heading of each snapshot repository's root README at its commit, for those that have
   * one, which the system's title may come from (ADR 0071).
   */
  private Map<String, String> readmeHeadings(Snapshot snapshot, Map<String, Repo> registered) {
    Map<String, String> headings = new TreeMap<>();
    snapshot
        .commits()
        .forEach(
            (name, commit) ->
                Optional.ofNullable(registered.get(name))
                    .or(() -> registry.find(name))
                    .flatMap(repo -> SystemTitle.readmeHeading(repo.path(), commit))
                    .ifPresent(heading -> headings.put(name, heading)));
    return headings;
  }

  /**
   * Removes repositories from a system, so its next snapshot does not carry them forward (ADR
   * 0071). Their stored facts, builds and documents stay. Holds the analysis lock, so no run is
   * taking a snapshot meanwhile.
   *
   * @throws IllegalArgumentException if a repository does not belong to the system
   * @throws io.lantern.core.analysis.AnalysisAlreadyRunningException if an analysis is running
   */
  public void remove(String system, Collection<String> repos) {
    if (repos.isEmpty()) {
      throw new IllegalArgumentException("Name at least one repository to remove");
    }
    try (AnalysisLock.Held held = lock.acquire()) {
      systems.remove(system, repos);
    }
  }

  /**
   * The repositories the snapshot carries forward, for the extractors to see as context: each at
   * its commit with its stored build, if any, and none built again; one without a Maven build with
   * none, even if an earlier run stored a failed build of it. One whose working tree no longer has
   * the commit is left out, with a warning.
   */
  private List<RepoBuild> carriedForward(Snapshot snapshot, Set<String> analyzed) {
    List<RepoBuild> result = new ArrayList<>();
    snapshot
        .commits()
        .forEach(
            (name, commit) -> {
              if (analyzed.contains(name)) {
                return;
              }
              Repo repo = registry.find(name).orElse(null);
              try {
                if (repo == null || !registry.resolve(name, commit).equals(commit)) {
                  throw new IllegalArgumentException("Commit " + commit + " not found");
                }
              } catch (IllegalArgumentException e) {
                LOG.warn(
                    "Carried forward {}@{}, but cannot read it, so the extractors do not see it: {}",
                    name,
                    commit,
                    e.getMessage());
                return;
              }
              result.add(new RepoBuild(repo, commit, storedBuild(sandbox, repo, commit)));
            });
    return result;
  }

  /**
   * The stored build of a repository at a commit, or null if there is none or the repository has no
   * Maven build there: a failed build stored for it before #254 would put it in degraded mode.
   */
  static BuildResult storedBuild(BuildSandbox sandbox, Repo repo, String commit) {
    return BuildSandbox.hasMavenBuild(repo, commit)
        ? sandbox.find(repo.name(), commit).orElse(null)
        : null;
  }

  /**
   * Builds every repository at its snapshot commit, in parallel up to the sandbox's limit, each at
   * most once, and each after the repositories of the snapshot whose modules it uses, with their
   * successful builds' artifacts (ADR 0041). Repositories carried forward are not built; their
   * stored builds are used. A stored successful build of the same commit is reused if it was given
   * the same builds of other repositories, at the same commits; a stored failed one is built again,
   * since its failure may have been transient or fixed by a settings change (ADR 0016, #58). If the
   * sandbox cannot build it again, the stored failed build is used, as before #58. A repository
   * without a Maven build is not built, nor given a stored build, and is added to {@code
   * withoutBuild} instead of the builds returned, and to {@code codeNotAnalyzed}, with a warning,
   * if it has code only a build would let the extractors read.
   */
  private Map<String, BuildResult> build(
      Map<String, Repo> repos,
      List<RepoBuild> carried,
      Snapshot snapshot,
      List<String> reused,
      List<String> withoutBuild,
      Map<String, List<String>> codeNotAnalyzed) {
    Map<String, Repo> all = new TreeMap<>(repos);
    carried.forEach(c -> all.put(c.repo().name(), c.repo()));
    BuildOrder order = BuildOrder.of(all, snapshot.commits());
    for (List<String> cycle : order.cycles()) {
      LOG.warn(
          "Repositories {} use each other's modules, so each is built without the others'"
              + " artifacts",
          cycle);
    }
    Map<String, Future<BuildResult>> running = new LinkedHashMap<>();
    Map<String, BuildResult> storedBuilds = new HashMap<>();
    carried.forEach(
        c -> running.put(c.repo().name(), CompletableFuture.completedFuture(c.build())));
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String name : order.order()) {
        Repo repo = repos.get(name);
        if (repo == null) {
          continue;
        }
        String commit = snapshot.commits().get(name);
        if (!BuildSandbox.hasMavenBuild(repo, commit)) {
          // Nothing builds what it would provide, so no build waits for it.
          List<String> code = BuildSandbox.codeWithoutMavenBuild(repo, commit);
          if (code.isEmpty()) {
            LOG.info("{}@{} has no pom.xml, so it is analyzed without a build", name, commit);
          } else {
            LOG.warn(
                "{}@{} has {} but no top-level pom.xml, so it is not built and its code is not"
                    + " analyzed",
                name,
                commit,
                String.join(", ", code));
            codeNotAnalyzed.put(name, code);
          }
          running.put(name, CompletableFuture.completedFuture(null));
          continue;
        }
        List<Future<BuildResult>> before = order.upstream(name).stream().map(running::get).toList();
        List<String> upstreamNames = List.copyOf(order.upstream(name));
        BuildResult stored = sandbox.find(name, commit).orElse(null);
        if (stored != null) {
          storedBuilds.put(name, stored);
        }
        running.put(
            name,
            executor.submit(
                () -> {
                  List<UpstreamBuild> upstream = new ArrayList<>();
                  Set<String> given = new TreeSet<>();
                  for (int i = 0; i < before.size(); i++) {
                    BuildResult build = before.get(i).get();
                    if (build != null) {
                      upstream.add(new UpstreamBuild(all.get(upstreamNames.get(i)), build));
                      if (!build.degraded()) {
                        given.add(build.repo() + "@" + build.commit());
                      }
                    }
                  }
                  // A stored successful build is reused only if it had the same builds of other
                  // repositories, so it never compiled against classes the snapshot no longer has.
                  if (stored != null
                      && stored.status() == BuildStatus.SUCCEEDED
                      && stored.upstream().equals(given)) {
                    return stored;
                  }
                  if (stored == null) {
                    LOG.info("Building {}@{}", name, commit);
                    return sandbox.build(repo, commit, upstream);
                  }
                  if (stored.status() == BuildStatus.SUCCEEDED) {
                    LOG.info(
                        "Building {}@{} again: it was built with {}, now {}",
                        name,
                        commit,
                        stored.upstream(),
                        given);
                    return rebuild(repo, commit, upstream, stored);
                  }
                  LOG.info("Building {}@{} again: its stored build failed", name, commit);
                  return rebuild(repo, commit, upstream, stored);
                }));
      }
      Map<String, BuildResult> builds = new TreeMap<>();
      for (Map.Entry<String, Future<BuildResult>> entry : running.entrySet()) {
        String name = entry.getKey();
        if (!repos.containsKey(name)) {
          continue;
        }
        BuildResult result = entry.getValue().get();
        if (result == null) {
          withoutBuild.add(name);
          continue;
        }
        if (result == storedBuilds.get(name)) {
          reused.add(name);
        }
        LOG.info(
            "Build of {}: {}{}",
            name,
            result.status(),
            result.failure() == null ? "" : " (" + result.failure() + "), degraded mode");
        builds.put(name, result);
      }
      return builds;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while building", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      // A build that waited for a failed one fails with that one's failure.
      while (cause instanceof ExecutionException nested && nested.getCause() != null) {
        cause = nested.getCause();
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("A build failed", cause);
    }
  }

  /**
   * Builds a repository with a stored build again, because it failed or was built with other
   * repositories' builds than now, or uses the stored build if the sandbox cannot run the build: a
   * stored result is better than none, and the run went ahead with it before #58.
   */
  private BuildResult rebuild(
      Repo repo, String commit, List<UpstreamBuild> upstream, BuildResult stored) {
    try {
      return sandbox.build(repo, commit, upstream);
    } catch (SandboxException e) {
      LOG.warn(
          "Could not build {}@{} again, so its stored build is used: {}",
          repo.name(),
          commit,
          e.getMessage());
      return stored;
    }
  }

  /**
   * Records the facts found in the snapshot in one transaction, each ID once (ADR 0017). A fact
   * that already held keeps the start of its validity, and stored facts of the analyzed
   * repositories that were not found stop holding, each origin kind the extractors give (static,
   * and readme for the docs extractor's) recorded on its own; other repositories' facts are
   * untouched. In the same transaction the analyzed repositories are marked as such in the
   * snapshot, so later snapshots carry them forward at their commits here, and a run that fails
   * before this point leaves its commits out of later snapshots (ADR 0018).
   */
  private Stored store(
      long snapshot,
      Set<String> repos,
      Set<String> snapshotRepos,
      List<Fact> found,
      Harness harness,
      GraphTools.RepoFiles files) {
    return transaction.execute(
        status -> {
          Map<String, Fact> unique = new LinkedHashMap<>();
          found.forEach(fact -> unique.putIfAbsent(fact.id(), fact));
          // The LLM resolver is the linker's last step: it picks among the candidates of the links
          // the static steps leave open, with cached answers reused (ADR 0045).
          LinkResolver resolver = new LinkResolver(harness, files);
          // A rule whose input and version are those of its last run is not run again (ADR 0095).
          // The derive rules follow the link rules whose facts they read, as rules of the same
          // linker, so the architecture model is stored with the links (#523).
          Linker linker = Linker.standard(facts, resolver, ruleRuns, Derivation.rules());
          // Each origin kind is recorded on its own, so a static analysis closes only the facts of
          // its kind; the docs extractor's facts are of kind readme (ADR 0033).
          Map<Origin.Kind, List<Fact>> byKind = new LinkedHashMap<>();
          EXTRACTED_KINDS.forEach(kind -> byKind.put(kind, new ArrayList<>()));
          for (Fact fact : unique.values()) {
            List<Fact> ofKind = byKind.get(fact.origin().kind());
            if (ofKind == null) {
              throw new IllegalArgumentException(
                  "Fact "
                      + fact.id()
                      + " has origin kind "
                      + fact.origin().kind()
                      + ", which no extractor gives");
            }
            ofKind.add(fact);
          }
          List<Fact> stored = new ArrayList<>();
          int added = 0;
          int changed = 0;
          int closed = 0;
          for (Map.Entry<Origin.Kind, List<Fact>> entry : byKind.entrySet()) {
            FactRepository.SnapshotUpdate update =
                facts.recordSnapshot(
                    snapshot, entry.getKey(), repos, linker.origins(), entry.getValue());
            stored.addAll(update.facts());
            added += update.added();
            changed += update.changed();
            closed += update.closed();
          }
          LOG.info("Facts: {} changed confidence, {} no longer hold", changed, closed);
          // The linker derives from every fact that holds in the snapshot, those of repositories
          // carried forward included, and records its facts for the whole snapshot (ADR 0039); not
          // from other systems' facts, nor ending theirs (ADR 0071).
          Linker.Result linked = linker.link(snapshot, snapshotRepos);
          LOG.info(
              "Linked facts: {} new, {} changed confidence, {} no longer hold; rules run: {}",
              linked.added(),
              linked.changed(),
              linked.closed(),
              linked.ran());
          snapshots.markAnalyzed(snapshot, repos);
          // The documents of the analyzed repositories show the linked facts about them.
          linked.facts().stream()
              .filter(f -> repos.contains(f.evidence().repo()))
              .forEach(stored::add);
          return new Stored(
              stored, linked.facts(), added + linked.added(), resolver.asked(), resolver.reused());
        });
  }

  /**
   * Facts as stored, every fact the linker derived for the snapshot, how many facts were new, and
   * how many open links the LLM was asked about and how many of its answers were reused.
   */
  private record Stored(
      List<Fact> facts, List<Fact> linkerFacts, int added, int linksAsked, int linksReused) {}

  /**
   * The repository's three views, the Architecture Explorer document {@code <repo>}, the Executive
   * Brief {@code <repo>.brief} and the C4 report {@code <repo>.c4}: a stored view if one was saved
   * to override the default, else the default (ADR 0047).
   */
  private List<ViewDefinition> views(String repo) {
    List<ViewDefinition> result = new ArrayList<>();
    for (ViewDefinition fallback :
        List.of(
            ViewDefinition.perRepo(repo, repo, EXPLORER),
            ViewDefinition.executiveBrief(repo + ".brief", repo, BRIEF),
            ViewDefinition.c4Report(repo + ".c4", repo, C4_REPORT))) {
      result.add(view(fallback));
    }
    return result;
  }

  /**
   * The view stored to override the default, else the default. Defaults are not stored, so a new
   * default section reaches every workspace (ADR 0047).
   */
  private ViewDefinition view(ViewDefinition fallback) {
    return views.find(fallback.id()).orElse(fallback);
  }

  private Path write(Path directory, String viewId, Document document) {
    try {
      Files.createDirectories(directory);
      // A view of no single repository: its colon becomes a dot, after an underscore no repository
      // name starts with.
      Path file =
          directory.resolve(
              (viewId.contains(":") ? "_" + viewId.replace(':', '.') : viewId) + ".html");
      String html =
          document.template().name().equals(C4ReportRenderer.SPEC)
              ? c4Renderer.render(document)
              : renderer.render(document);
      Files.writeString(file, html);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the document " + viewId, e);
    }
  }
}
