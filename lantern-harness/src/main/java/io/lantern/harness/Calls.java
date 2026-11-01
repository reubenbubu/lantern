package io.lantern.harness;

import io.lantern.llm.Conversation;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ToolBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The calls one item may make, all through its role's session: every request counts against the
 * role's budget share and is recorded in its run record, and every answer goes through the
 * verification pipeline with at most one repair turn.
 */
public final class Calls {

  private static final Logger LOG = LoggerFactory.getLogger(Calls.class);

  /** At most this many characters of a rejected answer are logged. */
  private static final int LOGGED_ANSWER = 1_000;

  private final RoleSession session;
  private final LlmClient client;
  private boolean repaired;

  Calls(RoleSession session, LlmClient client) {
    this.session = Objects.requireNonNull(session, "session");
    this.client = Objects.requireNonNull(client, "client");
  }

  /** Sends one request. */
  public LlmResponse call(LlmRequest request) {
    return session.send(client, request, session.spec().expectedOutputTokens());
  }

  /**
   * Starts a conversation with the role's tools and caps (knowledge-build epic, section 10, Agent
   * loop); the request's tools are replaced by the tool box's.
   */
  public Conversation converse(LlmRequest request, ToolBox tools) {
    return client.converse(request, tools, session.spec().limits());
  }

  /**
   * Sends the request and checks the answer: the harness's own checks (Secrets) and then the
   * role's. A rejected answer goes back once, with every reason, in the repair turn; the second
   * answer is accepted or the item is dropped.
   *
   * @throws ItemDropped if the second answer is rejected too
   */
  public <T> T answer(LlmRequest request, Verifier<T> verifier, Repair repair) throws ItemDropped {
    LlmResponse first = call(request);
    try {
      return verify(first, verifier);
    } catch (Rejected rejected) {
      logRejected(request.purpose(), rejected, first);
      List<LlmMessage> turns = repair.turns(new ArrayList<>(request.messages()), first, rejected);
      LlmResponse second =
          call(request.withMessages(turns).withPurpose(request.purpose() + " (retry)"));
      try {
        T accepted = verify(second, verifier);
        repaired = true;
        return accepted;
      } catch (Rejected again) {
        logRejected(request.purpose() + " (retry)", again, second);
        throw new ItemDropped("Rejected twice, the second time because " + again.getMessage());
      }
    }
  }

  /**
   * Checks an answer the way {@link #answer} does, for a role that repairs within its own
   * conversation: the harness's own checks, then the role's.
   */
  public <T> T verify(LlmResponse answer, Verifier<T> verifier) throws Rejected {
    String secret = session.harness().secretIn(answer.text());
    if (secret != null) {
      throw new Rejected("The answer holds a value marked as secret; leave it out.");
    }
    return verifier.verify(answer);
  }

  /** Whether an answer was accepted only after a repair turn. */
  boolean repaired() {
    return repaired;
  }

  /** Marks the item's answer as accepted only after a repair turn. */
  public void repairedAnswer() {
    repaired = true;
  }

  private static void logRejected(String purpose, Rejected reason, LlmResponse answer) {
    String text = answer.text();
    if (text.length() > LOGGED_ANSWER) {
      text = text.substring(0, LOGGED_ANSWER) + "... (" + text.length() + " characters)";
    }
    LOG.warn(
        "Rejected the answer to {}: {} The answer: {}",
        purpose,
        reason.getMessage(),
        text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n"));
  }
}
