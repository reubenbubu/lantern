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
  private final Map<String, java.math.BigDecimal> planned = new LinkedHashMap<>();
  private final Map<String, Shown> shown = new LinkedHashMap<>();

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

  /** The role's session in this run; asking again for a role gives the same session. */
  public synchronized RoleSession session(RoleSpec spec) {
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
        group, g -> new io.lantern.llm.BudgetGuard(g, null, budget.share(g)));
  }

  /**
   * Adds an item's estimated cost to its group's plan if the plan, or what the group has spent if
   * more, stays within the group's share.
   */
  synchronized boolean planWithinShare(String group, java.math.BigDecimal cost) {
    java.math.BigDecimal share = budget.share(group);
    if (share == null) {
      return true;
    }
    java.math.BigDecimal sofar =
        planned.getOrDefault(group, java.math.BigDecimal.ZERO).max(guard(group).spentCostUsd());
    if (sofar.add(cost).compareTo(share) > 0) {
      return false;
    }
    planned.put(group, sofar.add(cost));
    return true;
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
