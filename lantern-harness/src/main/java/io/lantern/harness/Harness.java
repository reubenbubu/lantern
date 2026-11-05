package io.lantern.harness;

import io.lantern.llm.LlmClient;
import io.lantern.llm.ModelConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
  private final Map<String, Integer> itemsPerRequest = new LinkedHashMap<>();
  private final Map<String, String> retries = new java.util.concurrent.ConcurrentHashMap<>();
  private final Set<String> retried = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
   * @param secrets the values masking marked as secret, which no accepted answer may hold; read
   *     live, so a set the masking adds to while it shows files to the roles counts in full (#645)
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
    this.secrets = Objects.requireNonNull(secrets, "secrets");
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

  /**
   * Asks the named role at most the given number of items a request instead of its spec's size
   * ({@code lantern.harness.items-per-request.<role>}; #529). Unlike a {@link Calibration} it does
   * not change the role's fingerprints: an answer does not depend on its request's neighbours
   * (knowledge-build epic, section 10), so the cache answers an item whatever size asked it. Set
   * before the role starts.
   */
  public synchronized Harness itemsPerRequest(String role, int items) {
    Objects.requireNonNull(role, "role");
    if (items < 1) {
      throw new IllegalArgumentException(
          "A request holds at least one item: " + role + " " + items);
    }
    if (sessions.containsKey(role)) {
      throw new IllegalStateException("The role " + role + " has already started");
    }
    itemsPerRequest.put(role, items);
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
    Integer items = itemsPerRequest.get(spec.name());
    if (items == null && spec.name().startsWith("support-judge-")) {
      // The support judge on a writer's model takes the judge's own size (#751).
      items = itemsPerRequest.get("support-judge");
    }
    if (items != null) {
      spec = spec.withPacketSize(items);
    }
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
    // A rejected answer may hold a masked value; what is kept of it never does (#645).
    rejections.add(
        new RejectedAnswer(
            rejection.role(),
            rejection.subject(),
            rejection.attempt(),
            redacted(rejection.answer()),
            rejection.reasons()));
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

  /**
   * The values masking marked as secret so far, but those too short to look for: what the Secrets
   * check keeps out of every answer and record, and no request may hold (#751).
   */
  public Set<String> secrets() {
    Set<String> kept = new TreeSet<>();
    for (String secret : List.copyOf(secrets)) {
      if (secret.length() >= SHORTEST_SECRET) {
        kept.add(secret);
      }
    }
    return kept;
  }

  /**
   * Marks the item whose answer was stored under the fingerprint to be asked again, once, told why
   * its answer was rejected (ADR 0101, the owner's decision on #751): the next time its role asks
   * it, the item shows the reason, which gives it a fingerprint of its own, and its earlier texts
   * are not carried forward.
   *
   * @param answer the fingerprint of the rejected answer, as its facts record it
   * @param reason what the support judge found, as the request shows it
   */
  public void retry(String answer, String reason) {
    retries.put(Objects.requireNonNull(answer, "answer"), Text.nonBlank("Reason", reason));
  }

  /** Why the item whose answer had the fingerprint is asked again, or null if it is not. */
  String retryReason(String answer) {
    return retries.get(answer);
  }

  /** Records the fingerprint an item marked for retry was asked again under. */
  void retriedAs(String fingerprint) {
    retried.add(fingerprint);
  }

  /** The fingerprints the items marked for retry were asked again under this run. */
  public Set<String> retried() {
    return Set.copyOf(retried);
  }

  /** A secret value the text holds, or null if it holds none. */
  String secretIn(String text) {
    for (String secret : secrets()) {
      if (holds(text, secret)) {
        return secret;
      }
    }
    return null;
  }

  /** What is written of a text, in the rejections file or the log: its secret values masked. */
  String redacted(String text) {
    if (text == null) {
      return null;
    }
    String result = text;
    for (String secret : secrets()) {
      result = result.replace(secret, MASK);
    }
    return result;
  }

  /** What a secret value is replaced by where a text holding one is written. */
  static final String MASK = "******";

  /**
   * Whether the text holds the secret as a whole word: not inside a longer run of letters and
   * digits, so a masked {@code postgres} does not reject an answer naming PostgreSQL (#645).
   */
  public static boolean holds(String text, String secret) {
    if (secret.isEmpty()) {
      return false;
    }
    for (int at = text.indexOf(secret); at >= 0; at = text.indexOf(secret, at + 1)) {
      int end = at + secret.length();
      boolean before = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
      boolean after = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
      if ((before || !Character.isLetterOrDigit(secret.charAt(0)))
          && (after || !Character.isLetterOrDigit(secret.charAt(secret.length() - 1)))) {
        return true;
      }
    }
    return false;
  }
}
