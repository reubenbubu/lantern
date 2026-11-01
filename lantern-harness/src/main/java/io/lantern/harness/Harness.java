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
