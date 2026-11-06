package io.lantern.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.lantern.llm.LlmConversation;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.LlmTool;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.RecordReplayLlmProvider;
import io.lantern.llm.Role;
import io.lantern.llm.TokenUsage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Investigate test's outputs (#903) from a replayed run: {@code live.log}, the verbatim
 * conversation files and the verdicts against the answer key. The run is recorded from a scripted
 * model, then replayed through the live log as the test replays a recorded run; no model is called.
 */
class InvestigateLogsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PREFIX = "investigate-leads conversation: ";
  private static final String LEAD_A =
      "lead:contract-mismatch/frontend:fleetcare-web/staff-angular/GET%20/api/staff/operations/summary";
  private static final String LEAD_B =
      "lead:open-cors/type:io.fleetcare.parts.inventory.PublicAvailabilityController/cross-origin";
  private static final String SYSTEM = "You check one lead.\nRules ``` with a fence inside.";
  private static final String NOTE =
      "You have used most of what this task allows. Stop exploring and give your answer now.";
  private static final String REJECTED = "No repository named nope. The repositories: a, b.";
  private static final String SENT_BACK =
      "1 problem: \"/api/staff/**\" is not in edge-gateway/src/main/resources/application.yml:12-20";

  @TempDir Path dir;

  @Test
  void aReplayedRunWritesTheLiveLogTheVerbatimConversationsAndTheVerdictsAgainstTheKey()
      throws IOException {
    Path calls = dir.resolve("calls");
    List<LlmRequest> requests = new ArrayList<>();
    Scripted model = new Scripted();
    // Recorded: the calls a run makes, each request and response written to calls/.
    LlmProvider recording = RecordReplayLlmProvider.recordingEvery(model, calls);
    run(recording, requests);

    // Replayed through the live log, as the Investigate test replays a recorded run.
    Path liveFile = dir.resolve("live.log");
    InvestigateLiveLog live =
        new InvestigateLiveLog(
            RecordReplayLlmProvider.replaying(model.name(), calls),
            liveFile,
            PREFIX,
            Clock.fixed(Instant.parse("2026-11-06T10:04:11Z"), ZoneOffset.UTC));
    List<LlmResponse> replayed = new ArrayList<>();
    for (LlmRequest request : requests) {
      replayed.add(live.complete("haiku", request));
    }
    assertThat(replayed).hasSize(4);
    Optional<InvestigateKeyScore> key = InvestigateKeyScore.read(projectEightKey());
    assertThat(key).isPresent();
    Map<String, String> verdicts = new LinkedHashMap<>();
    verdicts.put(LEAD_A, "refuted");
    verdicts.put(LEAD_B, "refuted");
    Map<String, LlmConversation.Ending> endings = new LinkedHashMap<>();
    endings.put(PREFIX + LEAD_A, LlmConversation.Ending.ANSWERED);
    endings.put(PREFIX + LEAD_B, LlmConversation.Ending.CAPPED);
    live.finish(purpose -> InvestigateOpenRouterIT.ending(purpose, endings, verdicts, key));

    String log = Files.readString(liveFile);
    assertThat(log)
        .contains("[01] " + LEAD_A + "\nfirst message ")
        .contains("── [01] turn 1 · ")
        .contains("  → find_in_code  repo=edge-gateway text=/api/staff\n    ← ")
        .contains("      line 8\n      … 4 more lines\n")
        .contains("  → read_file  repo=nope path=a.yml\n    ✗ rejected: " + REJECTED)
        .contains("  note to the model:\n    " + NOTE)
        .contains("  → submit  I1 verdict confirmed, 2 cited\n    ← ")
        .contains("      " + SENT_BACK)
        .contains("  → submit  I1 verdict refuted, 1 cited")
        .contains("[01] END answered · refuted (key: confirmed ✗) · 3 turns")
        .contains("[02] END capped · refuted (key: refuted ✓) · 1 turn");
    // Each block is one write: no line of conversation 2 inside a block of conversation 1.
    List<String> blockOne = block(log, "── [01] turn 2");
    assertThat(blockOne).isNotEmpty().noneMatch(l -> l.contains("[02]"));

    // The verbatim files: every payload exactly as recorded, in order, nothing cut.
    Path conversations = dir.resolve("conversations");
    Map<String, Path> files =
        InvestigateTranscripts.write(calls, conversations, live.numbers(), PREFIX);
    assertThat(files).hasSize(2);
    Path first = files.get(PREFIX + LEAD_A);
    assertThat(first.getFileName().toString()).startsWith("01-contract-mismatch");
    String verbatim = Files.readString(first);
    JsonNode last = lastRecording(calls, PREFIX + LEAD_A);
    List<String> payloads = payloads(last);
    assertThat(payloads).hasSizeGreaterThan(10);
    int at = 0;
    for (String payload : payloads) {
      int found = verbatim.indexOf(payload, at);
      assertThat(found).as("in order, verbatim: %s", payload).isGreaterThanOrEqualTo(0);
      at = found + payload.length();
    }
    assertThat(verbatim)
        .contains("````text\n" + SYSTEM + "\n````")
        .contains("Tool result, rejected, for `call-2`")
        .contains("Text after the results:")
        .contains("The provider returned no reasoning in this conversation.")
        .contains("### 6. Model");
    assertThat(Files.readString(files.get(PREFIX + LEAD_B))).startsWith("# " + PREFIX + LEAD_B);

    // The verdicts against project-8's key.
    ObjectNode score = key.orElseThrow().score(verdicts);
    assertThat(score.path("total").path("right").asInt()).isEqualTo(1);
    assertThat(score.path("total").path("wrong").asInt()).isEqualTo(1);
    assertThat(score.path("byKind").path("contract-mismatch").path("wrong").asInt()).isEqualTo(1);
    assertThat(score.path("wrong").get(0).path("why").asString()).isNotBlank();
    Map<String, String> unanswered = new LinkedHashMap<>();
    unanswered.put(LEAD_A, null);
    assertThat(key.orElseThrow().score(unanswered).path("total").path("unanswered").asInt())
        .isEqualTo(1);
    assertThat(InvestigateKeyScore.markdown(score))
        .contains("| contract-mismatch | 0 | 1 | 0 |")
        .contains("| **all** | 1 | 1 | 0 |")
        .contains("| `" + LEAD_A + "` | refuted | confirmed | ");

    // The guards and the rejected tool calls by reason.
    ObjectNode guards = JSON.createObjectNode();
    InvestigateOpenRouterIT.guards(guards, endings, InvestigateTranscripts.read(calls));
    assertThat(guards.path("sizeGuard").asInt()).isEqualTo(1);
    assertThat(guards.path("costGuard").asInt()).isZero();
    assertThat(guards.path("safetyStop").asInt()).isZero();
    assertThat(guards.path("rejectedToolCalls").path("No repository named nope.").asInt())
        .isEqualTo(1);
  }

  /**
   * The calls of a run: lead A in three turns (a rejected call, a note, an answer sent back once),
   * lead B in one.
   */
  private static void run(LlmProvider provider, List<LlmRequest> requests) {
    List<LlmTool> tools =
        List.of(
            tool("find_in_code", "Finds text in a repository.\nOne match a line."),
            tool("read_file", "Reads lines of a file."),
            tool("submit", "Submits your answer."));
    List<LlmMessage> a = new ArrayList<>(List.of(LlmMessage.user("Check this lead: " + LEAD_A)));
    LlmResponse r1 = call(provider, requests, LEAD_A, a, tools);
    a.add(LlmMessage.assistant(r1));
    String found =
        IntStream.rangeClosed(1, 12).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
    a.add(
        LlmMessage.toolResults(
            List.of(
                LlmToolResult.of(r1.toolCalls().get(0), found),
                new LlmToolResult(r1.toolCalls().get(1).id(), REJECTED, true)),
            NOTE));
    LlmResponse r2 = call(provider, requests, LEAD_A, a, tools);
    a.add(LlmMessage.assistant(r2));
    a.add(
        LlmMessage.toolResults(
            List.of(LlmToolResult.of(r2.toolCalls().getFirst(), SENT_BACK)), ""));
    call(provider, requests, LEAD_A, a, tools);
    call(
        provider,
        requests,
        LEAD_B,
        new ArrayList<>(List.of(LlmMessage.user("Check this lead: " + LEAD_B))),
        tools);
  }

  private static LlmResponse call(
      LlmProvider provider,
      List<LlmRequest> requests,
      String lead,
      List<LlmMessage> messages,
      List<LlmTool> tools) {
    LlmRequest request =
        new LlmRequest(Role.INVESTIGATE, PREFIX + lead, SYSTEM, messages, 4000, true, tools);
    requests.add(request);
    return provider.complete("haiku", request);
  }

  /** The scripted model: the four answers of {@link #run}, in order. */
  private static final class Scripted implements LlmProvider {
    private final Deque<LlmResponse> answers =
        new ArrayDeque<>(
            List.of(
                tools(
                    "Let me look.",
                    new LlmToolCall(
                        "call-1",
                        "find_in_code",
                        JSON.readTree("{\"repo\":\"edge-gateway\",\"text\":\"/api/staff\"}")),
                    new LlmToolCall(
                        "call-2",
                        "read_file",
                        JSON.readTree("{\"repo\":\"nope\",\"path\":\"a.yml\"}"))),
                tools("", submit("confirmed", "[\"x:1-2\",\"y:3-4\"]", "call-3")),
                tools("", submit("refuted", "[\"x:1-2\"]", "call-4")),
                tools("", submit("refuted", "[\"z:5-5\"]", "call-5"))));

    @Override
    public String name() {
      return "scripted";
    }

    @Override
    public synchronized LlmResponse complete(String model, LlmRequest request) {
      return answers.removeFirst();
    }

    private static LlmResponse tools(String text, LlmToolCall... calls) {
      return new LlmResponse(
          text, LlmResponse.TOOL_USE, new TokenUsage(10, 200, 3000, 5000), List.of(calls));
    }

    private static LlmToolCall submit(String verdict, String cites, String id) {
      ObjectNode input = JSON.createObjectNode();
      input.put(
          "items",
          "{\"I1\": {\"verdict\":\"" + verdict + "\",\"finding\":\"F.\",\"cites\":" + cites + "}}");
      return new LlmToolCall(id, "submit", input);
    }
  }

  private static LlmTool tool(String name, String description) {
    return new LlmTool(
        name,
        description,
        JSON.readTree("{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"}}}"));
  }

  /** Every payload of a recorded call, in the order the verbatim file gives them. */
  private static List<String> payloads(JsonNode recorded) {
    List<String> out = new ArrayList<>();
    JsonNode request = recorded.path("request");
    out.add(request.path("system").asString());
    for (JsonNode tool : request.path("tools")) {
      out.add(tool.path("description").asString());
      out.add(JSON.writeValueAsString(tool.path("inputSchema")));
    }
    List<JsonNode> messages = new ArrayList<>();
    request.path("messages").forEach(messages::add);
    messages.add(recorded.path("response"));
    for (JsonNode m : messages) {
      m.path("toolResults").forEach(r -> out.add(r.path("content").asString()));
      if (!m.path("text").asString().isEmpty()) {
        out.add(m.path("text").asString());
      }
      m.path("toolCalls").forEach(c -> out.add(JSON.writeValueAsString(c.path("input"))));
    }
    return out;
  }

  private static JsonNode lastRecording(Path calls, String purpose) throws IOException {
    JsonNode last = null;
    try (Stream<Path> files = Files.list(calls)) {
      for (Path f : files.toList()) {
        JsonNode root = JSON.readTree(Files.readString(f));
        if (root.path("about").path("purpose").asString().equals(purpose)
            && (last == null
                || root.path("request").path("messages").size()
                    > last.path("request").path("messages").size())) {
          last = root;
        }
      }
    }
    return last;
  }

  private static List<String> block(String log, String header) {
    List<String> lines = List.of(log.split("\n"));
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith(header)) {
        start = i;
      }
    }
    List<String> out = new ArrayList<>();
    for (int i = start; start >= 0 && i < lines.size(); i++) {
      if (i > start && (lines.get(i).startsWith("── ") || lines.get(i).startsWith("["))) {
        break;
      }
      out.add(lines.get(i));
    }
    return out;
  }

  private static Path projectEightKey() {
    return Path.of("..", "testsets", "project-8", "answer-key.json").toAbsolutePath().normalize();
  }
}
