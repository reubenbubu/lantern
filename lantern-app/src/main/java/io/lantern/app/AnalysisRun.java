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
import io.lantern.core.snapshot.Snapshot;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
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
 * <p>The snapshot describes the whole system: every repository an earlier snapshot analyzed that is
 * not named in this run is carried forward at its commit in the latest snapshot that analyzed it,
 * and its facts keep holding. Only the named repositories are built, summarized and composed, and
 * only their facts are recorded; the extractors see the whole snapshot (ADR 0018).
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
   * @param documentsDirectory where rendered documents are written, one directory per snapshot
   */
  public AnalysisRun(
      RepoRegistry registry,
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
   * @param snapshot the snapshot it analyzed, which also has the repositories carried forward
   * @param builds the build of each repository it analyzed, by name
   * @param reusedBuilds the names of the repositories whose stored build was reused
   * @param facts how many facts the extractors found, with the linker's facts about the analyzed
   *     repositories
   * @param newFacts how many facts did not hold in the previous snapshot, the linker's about any
   *     repository included
   * @param summariesGenerated how many summaries the LLM wrote
   * @param summariesReused how many summaries came from the cache
   * @param summariesRejected the units left without a summary because the LLM's answer was rejected
   *     twice
   * @param wordingsAsked how many deployable units, data stores, destinations and flows, and the
   *     system for its purpose, the LLM was asked to word (ADR 0050, ADR 0056, ADR 0058, ADR 0060)
   * @param wordingsReused how many of their wordings came from the cache
   * @param llm the LLM calls made during the run, whatever their outcome
   * @param documents the rendered documents, in the order written
   */
  public record Result(
      Snapshot snapshot,
      Map<String, BuildResult> builds,
      List<String> reusedBuilds,
      int facts,
      int newFacts,
      int summariesGenerated,
      int summariesReused,
      List<EntityId> summariesRejected,
      int wordingsAsked,
      int wordingsReused,
      JdbcLlmCallLog.Spending llm,
      List<Rendered> documents) {

    public Result {
      summariesRejected = List.copyOf(summariesRejected);
      builds = Collections.unmodifiableMap(new TreeMap<>(builds));
      reusedBuilds = List.copyOf(reusedBuilds);
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
   * Analyzes the repositories at their checked-out commits.
   *
   * @param repos the repositories, by name, each the top directory of a git working tree; a
   *     repository registered before keeps its settings
   * @throws LlmNotConfiguredException if the LLM is not configured; checked before anything else
   * @throws io.lantern.core.analysis.AnalysisAlreadyRunningException if another analysis is running
   * @throws IllegalArgumentException if a name or path is not a valid repository
   */
  public Result run(Map<String, Path> repos) {
    if (repos.isEmpty()) {
      throw new IllegalArgumentException("Name at least one repository to analyze");
    }
    Instant started = Instant.now();
    LlmClient client = llm.client();
    try (AnalysisLock.Held held = lock.acquire()) {
      Map<String, Repo> registered = new TreeMap<>();
      Map<String, String> revisions = new TreeMap<>();
      for (Map.Entry<String, Path> entry : new TreeMap<>(repos).entrySet()) {
        String name = entry.getKey();
        RepoSettings settings =
            registry.find(name).map(Repo::settings).orElse(RepoSettings.DEFAULT);
        registered.put(name, registry.register(name, entry.getValue(), settings));
        revisions.put(name, RepoRegistry.HEAD);
      }
      Snapshot snapshot = snapshots.createCarryingForward(revisions);
      LOG.info("Snapshot {}: {}", snapshot.number(), snapshot.commits());
      snapshot.commits().keySet().stream()
          .filter(name -> !registered.containsKey(name))
          .forEach(name -> LOG.info("Carried forward without analyzing: {}", name));

      List<String> reused = new ArrayList<>();
      List<RepoBuild> carried = carriedForward(snapshot, registered.keySet());
      Map<String, BuildResult> builds = build(registered, carried, snapshot, reused);
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
      // The code is read once, for the Java structure, Spring, persistence, HTTP client and
      // messaging extractors.
      JavaAnalysis code = JavaExtractor.analyze(repoBuilds);
      found.addAll(new JavaExtractor().extract(code, snapshot.number()));
      found.addAll(new ConfigExtractor().extract(repoBuilds, snapshot.number()));
      found.addAll(new DocsExtractor().extract(repoBuilds, snapshot.number()));
      found.addAll(new SqlExtractor().extract(repoBuilds, snapshot.number()));
      found.addAll(new SpringExtractor().extract(code, snapshot.number()));
      found.addAll(new PersistenceExtractor().extract(code, snapshot.number()));
      found.addAll(new HttpClientExtractor().extract(code, snapshot.number()));
      found.addAll(new MessagingExtractor().extract(code, snapshot.number()));
      found.removeIf(fact -> !registered.containsKey(fact.evidence().repo()));
      Stored stored = store(snapshot.number(), registered.keySet(), found, client);
      List<Fact> extracted = stored.facts();
      int newFacts = stored.added();
      LOG.info("Extracted {} facts, {} of them new", extracted.size(), newFacts);

      // Prompts show source excerpts read from git at the commits the facts were observed in.
      GitSourceFiles sources =
          new GitSourceFiles(name -> Optional.ofNullable(registered.get(name)).map(Repo::path));
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
      // forward repositories included (ADR 0050, ADR 0058).
      List<Fact> system = facts.findValidAt(snapshot.number());
      // Each repository's documents are composed from its extracted facts, so its flows are traced
      // from those.
      Describer.Result described = new Describer(client, wordings).describe(system, extracted);

      List<Rendered> documents = new ArrayList<>();
      Composer composer = new Composer(toolVersion);
      Path out = documentsDirectory.resolve(Long.toString(snapshot.number()));
      for (String name : registered.keySet()) {
        for (ViewDefinition view : views(name)) {
          Document document =
              composer.compose(
                  view, snapshot, extracted, summarized.summaries(), described.wordings());
          documents.add(new Rendered(document, write(out, view.id(), document)));
        }
      }
      // The system's report shows every repository of the snapshot, carried forward ones too.
      ViewDefinition systemView = view(ViewDefinition.c4System(SYSTEM_C4, "System", C4_REPORT));
      Document document =
          composer.compose(
              systemView, snapshot, system, summarized.summaries(), described.wordings());
      documents.add(new Rendered(document, write(out, systemView.id(), document)));
      documents.forEach(d -> LOG.info("Wrote {}", d.file()));
      return new Result(
          snapshot,
          builds,
          reused,
          extracted.size(),
          newFacts,
          summarized.generated(),
          summarized.reused(),
          summarized.rejected(),
          described.asked(),
          described.reused(),
          callLog.spentSince(started),
          documents);
    }
  }

  /**
   * The repositories the snapshot carries forward, for the extractors to see as context: each at
   * its commit with its stored build, if any, and none built again. One whose working tree no
   * longer has the commit is left out, with a warning.
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
              result.add(new RepoBuild(repo, commit, sandbox.find(name, commit).orElse(null)));
            });
    return result;
  }

  /**
   * Builds every repository at its snapshot commit, in parallel up to the sandbox's limit, each at
   * most once, and each after the repositories of the snapshot whose modules it uses, with their
   * successful builds' artifacts (ADR 0041). Repositories carried forward are not built; their
   * stored builds are used. A stored successful build of the same commit is reused if it was given
   * the same builds of other repositories, at the same commits; a stored failed one is built again,
   * since its failure may have been transient or fixed by a settings change (ADR 0016, #58). If the
   * sandbox cannot build it again, the stored failed build is used, as before #58.
   */
  private Map<String, BuildResult> build(
      Map<String, Repo> repos, List<RepoBuild> carried, Snapshot snapshot, List<String> reused) {
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
  private Stored store(long snapshot, Set<String> repos, List<Fact> found, LlmClient client) {
    return transaction.execute(
        status -> {
          Map<String, Fact> unique = new LinkedHashMap<>();
          found.forEach(fact -> unique.putIfAbsent(fact.id(), fact));
          // The LLM resolver is the linker's last step: it picks among the candidates of the links
          // the static steps leave open, with cached answers reused (ADR 0045).
          Linker linker = Linker.standard(facts, new LinkResolver(client, resolutions));
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
          // carried forward included, and records its facts for the whole snapshot (ADR 0039).
          Linker.Result linked = linker.link(snapshot);
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
          return new Stored(stored, added + linked.added());
        });
  }

  /** Facts as stored, and how many of them were new. */
  private record Stored(List<Fact> facts, int added) {}

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
