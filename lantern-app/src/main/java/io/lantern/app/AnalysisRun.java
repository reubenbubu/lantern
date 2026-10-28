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
import io.lantern.core.snapshot.Snapshot;
import io.lantern.core.snapshot.SnapshotStore;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.llm.LlmClient;
import io.lantern.sandbox.BuildResult;
import io.lantern.sandbox.BuildSandbox;
import io.lantern.sandbox.BuildStatus;
import io.lantern.sandbox.SandboxException;
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
 * and store their facts, summarize them with the LLM, compose an Architecture Explorer document and
 * an Executive Brief per repository, and render both as single-file HTML (ADR 0016).
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

  private static final Logger LOG = LoggerFactory.getLogger(AnalysisRun.class);

  private final RepoRegistry registry;
  private final SnapshotStore snapshots;
  private final AnalysisLock lock;
  private final BuildSandbox sandbox;
  private final FactRepository facts;
  private final TransactionTemplate transaction;
  private final LlmAccess llm;
  private final JdbcLlmCallLog callLog;
  private final SummaryStore summaries;
  private final ViewDefinitionStore views;
  private final HtmlRenderer renderer;
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
      ViewDefinitionStore views,
      HtmlRenderer renderer,
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
    this.views = Objects.requireNonNull(views, "views");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.toolVersion = Objects.requireNonNull(toolVersion, "toolVersion");
    this.documentsDirectory = Objects.requireNonNull(documentsDirectory, "documentsDirectory");
  }

  /**
   * What a run did.
   *
   * @param snapshot the snapshot it analyzed, which also has the repositories carried forward
   * @param builds the build of each repository it analyzed, by name
   * @param reusedBuilds the names of the repositories whose stored build was reused
   * @param facts how many facts the extractors found
   * @param newFacts how many of them did not hold in the previous snapshot
   * @param summariesGenerated how many summaries the LLM wrote
   * @param summariesReused how many summaries came from the cache
   * @param summariesRejected the units left without a summary because the LLM's answer was rejected
   *     twice
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
      Map<String, BuildResult> builds = build(registered, snapshot, reused);
      List<RepoBuild> repoBuilds = new ArrayList<>();
      registered.forEach(
          (name, repo) ->
              repoBuilds.add(new RepoBuild(repo, snapshot.commits().get(name), builds.get(name))));
      repoBuilds.addAll(carriedForward(snapshot, registered.keySet()));

      // The extractors see the whole snapshot, so whether a dependency or call reaches another
      // repository does not depend on which repositories this run names. Only the named
      // repositories' facts are recorded.
      List<Fact> found =
          new ArrayList<>(new MavenExtractor().extract(repoBuilds, snapshot.number()));
      found.addAll(new JavaExtractor().extract(repoBuilds, snapshot.number()));
      found.removeIf(fact -> !registered.containsKey(fact.evidence().repo()));
      Stored stored = store(snapshot.number(), registered.keySet(), found);
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

      List<Rendered> documents = new ArrayList<>();
      Composer composer = new Composer(toolVersion);
      Path out = documentsDirectory.resolve(Long.toString(snapshot.number()));
      for (String name : registered.keySet()) {
        for (ViewDefinition view : views(name)) {
          Document document = composer.compose(view, snapshot, extracted, summarized.summaries());
          documents.add(new Rendered(document, write(out, view.id(), document)));
        }
      }
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
   * most once. A stored successful build of the same commit is reused; a stored failed one is built
   * again, since its failure may have been transient or fixed by a settings change (ADR 0016, #58).
   * If the sandbox cannot build it again, the stored failed build is used, as before #58.
   */
  private Map<String, BuildResult> build(
      Map<String, Repo> repos, Snapshot snapshot, List<String> reused) {
    Map<String, Future<BuildResult>> running = new LinkedHashMap<>();
    Map<String, BuildResult> storedBuilds = new HashMap<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (Map.Entry<String, Repo> entry : repos.entrySet()) {
        String name = entry.getKey();
        String commit = snapshot.commits().get(name);
        BuildResult stored = sandbox.find(name, commit).orElse(null);
        if (stored == null) {
          LOG.info("Building {}@{}", name, commit);
          running.put(name, executor.submit(() -> sandbox.build(entry.getValue(), commit)));
          continue;
        }
        storedBuilds.put(name, stored);
        if (stored.status() == BuildStatus.SUCCEEDED) {
          running.put(name, CompletableFuture.completedFuture(stored));
        } else {
          LOG.info("Building {}@{} again: its stored build failed", name, commit);
          running.put(name, executor.submit(() -> rebuild(entry.getValue(), commit, stored)));
        }
      }
      Map<String, BuildResult> builds = new TreeMap<>();
      for (Map.Entry<String, Future<BuildResult>> entry : running.entrySet()) {
        BuildResult result = entry.getValue().get();
        if (result == storedBuilds.get(entry.getKey())) {
          reused.add(entry.getKey());
        }
        LOG.info(
            "Build of {}: {}{}",
            entry.getKey(),
            result.status(),
            result.failure() == null ? "" : " (" + result.failure() + "), degraded mode");
        builds.put(entry.getKey(), result);
      }
      return builds;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while building", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("A build failed", e.getCause());
    }
  }

  /**
   * Builds a repository whose stored build failed again, or uses the stored build if the sandbox
   * cannot run the build: a stored result is better than none, and the run went ahead with it
   * before #58.
   */
  private BuildResult rebuild(Repo repo, String commit, BuildResult failed) {
    try {
      return sandbox.build(repo, commit);
    } catch (SandboxException e) {
      LOG.warn(
          "Could not build {}@{} again, so its stored failed build is used: {}",
          repo.name(),
          commit,
          e.getMessage());
      return failed;
    }
  }

  /**
   * Records the facts found in the snapshot in one transaction, each ID once (ADR 0017). A fact
   * that already held keeps the start of its validity, and stored static facts of the analyzed
   * repositories that were not found stop holding; other repositories' facts are untouched. In the
   * same transaction the analyzed repositories are marked as such in the snapshot, so later
   * snapshots carry them forward at their commits here, and a run that fails before this point
   * leaves its commits out of later snapshots (ADR 0018).
   */
  private Stored store(long snapshot, Set<String> repos, List<Fact> found) {
    return transaction.execute(
        status -> {
          Map<String, Fact> unique = new LinkedHashMap<>();
          found.forEach(fact -> unique.putIfAbsent(fact.id(), fact));
          FactRepository.SnapshotUpdate update =
              facts.recordSnapshot(snapshot, Origin.Kind.STATIC, repos, unique.values());
          LOG.info(
              "Facts: {} changed confidence, {} no longer hold", update.changed(), update.closed());
          snapshots.markAnalyzed(snapshot, repos);
          return new Stored(update.facts(), update.added());
        });
  }

  /** Facts as stored, and how many of them were new. */
  private record Stored(List<Fact> facts, int added) {}

  /**
   * The repository's two views, the Architecture Explorer document {@code <repo>} and the Executive
   * Brief {@code <repo>.brief}. A view stored before is used as stored; a missing one is stored
   * with the default sections.
   */
  private List<ViewDefinition> views(String repo) {
    List<ViewDefinition> result = new ArrayList<>();
    for (ViewDefinition fallback :
        List.of(
            ViewDefinition.perRepo(repo, repo, EXPLORER),
            ViewDefinition.executiveBrief(repo + ".brief", repo, BRIEF))) {
      result.add(
          views
              .find(fallback.id())
              .orElseGet(
                  () -> {
                    views.save(fallback);
                    return fallback;
                  }));
    }
    return result;
  }

  private Path write(Path directory, String viewId, Document document) {
    try {
      Files.createDirectories(directory);
      Path file = directory.resolve(viewId + ".html");
      Files.writeString(file, renderer.render(document));
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the document " + viewId, e);
    }
  }
}
