package io.lantern.app;

import io.lantern.compose.Composer;
import io.lantern.compose.Describer;
import io.lantern.compose.ViewDefinition;
import io.lantern.compose.ViewDefinitionStore;
import io.lantern.compose.WordingStore;
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
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.frontend.FrontendExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.identity.IdentityExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.mail.MailExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.people.PeopleExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.redis.RedisExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.extract.test.TestExtractor;
import io.lantern.extract.websocket.WebSocketExtractor;
import io.lantern.link.GraphTools;
import io.lantern.link.LinkResolver;
import io.lantern.link.Linker;
import io.lantern.link.ResolutionStore;
import io.lantern.llm.LlmClient;
import io.lantern.sandbox.BuildOrder;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.SandboxException;
import io.lantern.sandbox.UpstreamBuild;
import io.lantern.summarize.GitSourceFiles;
import io.lantern.summarize.Summarizer;
import io.lantern.summarize.SummaryStore;
import io.lantern.summarize.SummaryTree;
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

  private final RepoRegistry registry;
  private final SystemRegistry systems;
  private final SnapshotStore snapshots;
  private final AnalysisLock lock;
  private final BuildSandbox sandbox;
  private final FactRepository facts;
  private final TransactionTemplate transaction;
  private final LlmAccess llm;
  private final JdbcLlmCallLog callLog;
  private final SummaryStore summaries;
  private final ResolutionStore resolutions;
  private final WordingStore wordings;
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
      SummaryStore summaries,
      ResolutionStore resolutions,
      WordingStore wordings,
      ViewDefinitionStore views,
      HtmlRenderer renderer,
      C4ReportRenderer c4Renderer,
      String toolVersion,
      Path documentsDirectory) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.systems = Objects.requireNonNull(systems, "systems");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.lock = Objects.requireNonNull(lock, "lock");
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    this.facts = Objects.requireNonNull(facts, "facts");
    this.transaction = Objects.requireNonNull(transaction, "transaction");
    this.llm = Objects.requireNonNull(llm, "llm");
    this.callLog = Objects.requireNonNull(callLog, "callLog");
    this.summaries = Objects.requireNonNull(summaries, "summaries");
    this.resolutions = Objects.requireNonNull(resolutions, "resolutions");
    this.wordings = Objects.requireNonNull(wordings, "wordings");
    this.views = Objects.requireNonNull(views, "views");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.c4Renderer = Objects.requireNonNull(c4Renderer, "c4Renderer");
    this.toolVersion = Objects.requireNonNull(toolVersion, "toolVersion");
    this.documentsDirectory = Objects.requireNonNull(documentsDirectory, "documentsDirectory");
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
      List<Rendered> documents,
      Path runRecord) {

    public Result {
      summariesRejected = List.copyOf(summariesRejected);
      builds = Collections.unmodifiableMap(new TreeMap<>(builds));
      reusedBuilds = List.copyOf(reusedBuilds);
      withoutBuild = List.copyOf(withoutBuild);
      Map<String, List<String>> code = new TreeMap<>();
      codeNotAnalyzed.forEach((repo, kinds) -> code.put(repo, List.copyOf(kinds)));
      codeNotAnalyzed = Collections.unmodifiableMap(code);
      documents = List.copyOf(documents);
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
      // repositories' facts are recorded.
      List<Fact> found =
          new ArrayList<>(new MavenExtractor().extract(repoBuilds, snapshot.number()));
      // The code is read once, for the Java structure, Spring, persistence, HTTP client,
      // messaging, WebSocket, mail, Redis, people and gateway extractors.
      JavaAnalysis code = JavaExtractor.analyze(repoBuilds);
      found.addAll(new JavaExtractor().extract(code, snapshot.number()));
      // A config key the code uses as a JWT secret is masked whatever it is called (#335), so the
      // identity extractor runs before the config extractor.
      List<Fact> identity = new IdentityExtractor().extract(code, snapshot.number());
      Set<String> secretKeys = ConfigExtractor.jwtSecretKeys(identity);
      found.addAll(new ConfigExtractor().extract(repoBuilds, snapshot.number(), secretKeys));
      found.addAll(new DocsExtractor().extract(repoBuilds, snapshot.number(), secretKeys));
      found.addAll(new SqlExtractor().extract(repoBuilds, snapshot.number()));
      found.addAll(new TestExtractor().extract(repoBuilds, snapshot.number()));
      found.addAll(new SpringExtractor().extract(code, snapshot.number(), secretKeys));
      found.addAll(new PersistenceExtractor().extract(code, snapshot.number(), secretKeys));
      found.addAll(new HttpClientExtractor().extract(code, snapshot.number(), secretKeys));
      found.addAll(new MessagingExtractor().extract(code, snapshot.number(), secretKeys));
      found.addAll(new WebSocketExtractor().extract(code, snapshot.number()));
      found.addAll(new MailExtractor().extract(code, snapshot.number()));
      found.addAll(identity);
      found.addAll(new RedisExtractor().extract(code, repoBuilds, snapshot.number()));
      found.addAll(new PeopleExtractor().extract(code, repoBuilds, snapshot.number()));
      found.addAll(new DeploymentExtractor().extract(repoBuilds, snapshot.number()));
      found.addAll(new FrontendExtractor().extract(repoBuilds, snapshot.number()));
      found.addAll(new GatewayExtractor().extract(code, snapshot.number(), secretKeys));
      found.removeIf(fact -> !registered.containsKey(fact.evidence().repo()));
      // The resolver's tools read files of every repository of the snapshot at its commit there,
      // carried forward ones included (ADR 0081).
      GitSourceFiles snapshotSources =
          new GitSourceFiles(
              name ->
                  Optional.ofNullable(registered.get(name))
                      .or(() -> registry.find(name))
                      .map(Repo::path));
      GraphTools.RepoFiles snapshotFiles =
          (repo, path) ->
              Optional.ofNullable(snapshot.commits().get(repo))
                  .flatMap(commit -> snapshotSources.read(repo, commit, path));
      Stored stored =
          store(
              snapshot.number(),
              registered.keySet(),
              snapshot.commits().keySet(),
              found,
              client,
              snapshotFiles);
      List<Fact> extracted = stored.facts();
      int newFacts = stored.added();
      LOG.info("Extracted {} facts, {} of them new", extracted.size(), newFacts);

      // Prompts show source excerpts read from git at the commits the facts were observed in.
      GitSourceFiles sources =
          new GitSourceFiles(name -> Optional.ofNullable(registered.get(name)).map(Repo::path))
              .withSecretKeys(secretKeys);
      Summarizer.Result summarized =
          new Summarizer(client, summaries, sources).summarize(SummaryTree.fromFacts(extracted));
      LOG.info(
          "Summaries: {} written by the LLM, {} reused",
          summarized.generated(),
          summarized.reused());
      if (!summarized.rejected().isEmpty()) {
        LOG.warn(
            "Units without a summary, their answer rejected twice: {}",
            summarized.rejected().size());
      }

      // The LLM words the responsibilities of the snapshot's units and components and the intent
      // of their links, and narrates the main flows, from every fact that holds in it, carried
      // forward repositories included (ADR 0050, ADR 0058), and of no other system (ADR 0071).
      List<Fact> systemFacts = facts.findValidAt(snapshot.number(), snapshot.commits().keySet());
      // Each repository's documents are composed from its extracted facts, so its flows are traced
      // from those.
      // Their prompts show the code of components, read from every registered repository, carried
      // forward ones included, so a run gives the same prompts whichever repositories it names
      // (ADR 0069).
      GitSourceFiles allSources =
          new GitSourceFiles(
                  name ->
                      Optional.ofNullable(registered.get(name))
                          .or(() -> registry.find(name))
                          .map(Repo::path))
              .withSecretKeys(secretKeys);
      Describer.Result described =
          new Describer(client, wordings, allSources).describe(systemFacts, extracted);

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
      Composer composer = new Composer(toolVersion);
      Path out = documentsDirectory.resolve(system).resolve(Long.toString(snapshot.number()));
      for (String name : registered.keySet()) {
        for (ViewDefinition view : views(name)) {
          Document document =
              composer.compose(
                  view,
                  snapshot,
                  extracted,
                  summarized.summaries(),
                  described.wordings(),
                  systemTitle);
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
      Document document =
          composer.compose(
              systemView,
              snapshot,
              systemFacts,
              summarized.summaries(),
              described.wordings(),
              systemTitle);
      documents.add(new Rendered(document, write(out, systemView.id(), document)));
      documents.forEach(d -> LOG.info("Wrote {}", d.file()));
      Path runRecord =
          RunRecord.write(
              out,
              RunRecord.of(
                  system,
                  snapshot.number(),
                  llm.models(),
                  callLog.spentByModelSince(started),
                  callLog.conversationsSince(started)));
      LOG.info("Wrote {}", runRecord);
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
          summarized.generated(),
          summarized.reused(),
          summarized.rejected(),
          described.asked(),
          described.reused(),
          callLog.spentSince(started),
          documents,
          runRecord);
    }
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
      LlmClient client,
      GraphTools.RepoFiles files) {
    return transaction.execute(
        status -> {
          Map<String, Fact> unique = new LinkedHashMap<>();
          found.forEach(fact -> unique.putIfAbsent(fact.id(), fact));
          // The LLM resolver is the linker's last step: it picks among the candidates of the links
          // the static steps leave open, with cached answers reused (ADR 0045).
          LinkResolver resolver = new LinkResolver(client, resolutions, files);
          Linker linker = Linker.standard(facts, resolver);
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
              "Linked facts: {} new, {} changed confidence, {} no longer hold",
              linked.added(),
              linked.changed(),
              linked.closed());
          snapshots.markAnalyzed(snapshot, repos);
          // The documents of the analyzed repositories show the linked facts about them.
          linked.facts().stream()
              .filter(f -> repos.contains(f.evidence().repo()))
              .forEach(stored::add);
          return new Stored(stored, added + linked.added(), resolver.asked(), resolver.reused());
        });
  }

  /**
   * Facts as stored, how many of them were new, and how many open links the LLM was asked about and
   * how many of its answers were reused.
   */
  private record Stored(List<Fact> facts, int added, int linksAsked, int linksReused) {}

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
