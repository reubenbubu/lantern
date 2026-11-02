package io.lantern.harness;

import io.lantern.llm.LlmClient;
import io.lantern.llm.ModelConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The harness of one run (knowledge-build epic, section 10; ADR 0100): every LLM role works through
 * a {@link RoleSession} of it, which shares the run's answer cache, budget and record. Thread-safe.
 */
public final class Harness {

  /** The shortest value the Secrets check looks for, so short common words never match. */
  static final int SHORTEST_SECRET = 6;

  private final LlmClient client;
  private final AnswerStore answers;
  private final RunBudget budget;
  private final BlobLookup blobs;
  private final Set<String> secrets;
  private final Map<String, RoleSession> sessions = new LinkedHashMap<>();
  private final Map<String, io.lantern.llm.BudgetGuard> guards = new LinkedHashMap<>();
  private final Map<String, ShareRounds> rounds = new LinkedHashMap<>();
  private final Map<String, Shown> shown = new LinkedHashMap<>();
  private final List<RejectedAnswer> rejections = new ArrayList<>();
  private volatile int batchAbove = BATCH_ABOVE;
  private final Map<String, Calibration> calibrations = new LinkedHashMap<>();

  /**
   * A role asked at another packet size for packet-size calibration (knowledge-build epic, section
   * 10; #529): its items are fingerprinted apart, by the size and the trial, so the role is asked
   * again at that size and its answers there never replace the ones of its usual size.
   *
   * @param packetSize the items per request
   * @param trial what tells two recordings at one size apart, such as "1" and "2"
   */
  public record Calibration(int packetSize, String trial) {

    public Calibration {
      if (packetSize < 1) {
        throw new IllegalArgumentException("A packet holds at least one item: " + packetSize);
      }
      trial = io.lantern.harness.Text.nonBlank("Calibration trial", trial);
    }

    /** What the role's fingerprints carry beside its prompt version. */
    String tag() {
      return "calibration packet " + packetSize + " trial " + trial;
    }
  }

  /**
   * How many cache misses a bulk role must have in a run, more than, for its requests to go through
   * the provider's batch API (knowledge-build epic, section 10, Run modes).
   */
  public static final int BATCH_ABOVE = 50;

  /**
   * @param client the LLM client, with the run's other budgets
   * @param answers the answer cache
   * @param budget the run's budget, split by role group
   * @param blobs the blobs of the snapshot's files, to revalidate answers that read files
   * @param secrets the values masking marked as secret, which no accepted answer may hold
   */
  public Harness(
      LlmClient client,
      AnswerStore answers,
      RunBudget budget,
      BlobLookup blobs,
      Set<String> secrets) {
    this.client = Objects.requireNonNull(client, "client");
    this.answers = Objects.requireNonNull(answers, "answers");
    this.budget = Objects.requireNonNull(budget, "budget");
    this.blobs = Objects.requireNonNull(blobs, "blobs");
    this.secrets =
        Set.copyOf(
            Objects.requireNonNull(secrets, "secrets").stream()
                .filter(s -> s.length() >= SHORTEST_SECRET)
                .toList());
  }

  /** A harness with an answer cache in memory and no run budget, for tests and one-off runs. */
  public static Harness inMemory(LlmClient client) {
    return new Harness(
        client, new InMemoryAnswerStore(), RunBudget.NONE, BlobLookup.NONE, Set.of());
  }

  /**
   * Sends a bulk role's requests through the batch API once it has more than the given number of
   * cache misses, instead of {@value #BATCH_ABOVE}; 0 batches every bulk role that misses at all,
   * as a check that batching changes no answer does. Set before any role starts.
   */
  public Harness batchingAbove(int misses) {
    if (misses < 0) {
      throw new IllegalArgumentException("Misses must not be negative: " + misses);
    }
    this.batchAbove = misses;
    return this;
  }

  /**
   * Asks the named role at the given packet size, fingerprinted apart (see {@link Calibration}).
   * Set before the role starts.
   */
  public synchronized Harness calibrating(String role, Calibration calibration) {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(calibration, "calibration");
    if (sessions.containsKey(role)) {
      throw new IllegalStateException("The role " + role + " has already started");
    }
    calibrations.put(role, calibration);
    return this;
  }

  /** The calibration the role runs under, if any. */
  synchronized java.util.Optional<Calibration> calibration(String role) {
    return java.util.Optional.ofNullable(calibrations.get(role));
  }

  /** How many cache misses a bulk role must have, more than, to batch. */
  int batchAbove() {
    return batchAbove;
  }

  /** The role's session in this run; asking again for a role gives the same session. */
  public synchronized RoleSession session(RoleSpec spec) {
    Calibration calibration = calibrations.get(spec.name());
    if (calibration != null) {
      spec = spec.withPacketSize(calibration.packetSize());
    }
    RoleSession session = sessions.get(spec.name());
    if (session != null) {
      if (!session.spec().equals(spec)) {
        throw new IllegalArgumentException(
            "The role " + spec.name() + " is already running as " + session.spec());
      }
      return session;
    }
    ModelConfig model = client.model(spec.modelRole());
    session =
        new RoleSession(
            this, spec, client, new RoleRecord(spec.name(), model.provider(), model.model()));
    sessions.put(spec.name(), session);
    return session;
  }

  /**
   * A file whose text entered a prompt, as the prompt showed it (secrets masked), for the injection
   * screen (ADR 0101).
   *
   * @param repo the repository
   * @param path the file's path in it
   * @param blob the file's git blob
   * @param text the file's text as shown
   */
  public record Shown(String repo, String path, String blob, String text) {}

  /** Notes that a prompt showed the file's text; the first showing of a blob is kept. */
  public synchronized void showed(String repo, String path, String blob, String text) {
    if (repo != null && path != null && blob != null && text != null) {
      shown.putIfAbsent(
          repo + "\u0000" + path + "\u0000" + blob, new Shown(repo, path, blob, text));
    }
  }

  /** The files whose text entered a prompt in this run, in the order first shown. */
  public synchronized List<Shown> shown() {
    return List.copyOf(shown.values());
  }

  /** Notes an answer the checks rejected, or an item dropped (#682). */
  synchronized void rejected(RejectedAnswer rejection) {
    rejections.add(rejection);
  }

  /** Every answer rejected and every item dropped in this run, in the order they happened. */
  public synchronized List<RejectedAnswer> rejections() {
    return List.copyOf(rejections);
  }

  /** The model configured for the role. */
  public ModelConfig model(io.lantern.llm.Role role) {
    return client.model(role);
  }

  /** The records of the roles that ran, in the order they started. */
  public synchronized List<RoleRecord> records() {
    List<RoleRecord> records = new ArrayList<>();
    sessions.values().forEach(session -> records.add(session.record()));
    return records;
  }

  /** The guard of a role group's share of the run budget, shared by the group's roles. */
  synchronized io.lantern.llm.BudgetGuard guard(String group) {
    return guards.computeIfAbsent(
        group, g -> io.lantern.llm.BudgetGuard.stopOnceSpent(g, budget.share(g)));
  }

  /**
   * The rounds that decide which items of a role group fit its share, shared by the group's roles;
   * {@code null} for a group without a share.
   */
  synchronized ShareRounds rounds(String group) {
    java.math.BigDecimal share = budget.share(group);
    if (share == null) {
      return null;
    }
    return rounds.computeIfAbsent(group, g -> new ShareRounds(g, share, guard(g)));
  }

  AnswerStore answers() {
    return answers;
  }

  RunBudget budget() {
    return budget;
  }

  BlobLookup blobs() {
    return blobs;
  }

  /** The values masking marked as secret. */
  Set<String> secrets() {
    return secrets;
  }

  /** A secret value the text holds, or null if it holds none. */
  String secretIn(String text) {
    for (String secret : secrets) {
      if (text.contains(secret)) {
        return secret;
      }
    }
    return null;
  }
}
