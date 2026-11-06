package io.lantern.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records real responses once and replays them in tests, so tests see real model output without
 * calling the model.
 *
 * <p>Each response is a JSON file in a directory, named by a hash of what is sent to the model: the
 * provider, model, system prompt, prompt-caching flag, messages, output limit (with any thinking
 * headroom) and, only when set, the request to turn thinking off, the tools offered and each turn's
 * tool calls and results (ADR 0081). The role and purpose are not sent and so are not part of the
 * key. A recorded answer that asks for tool calls holds them, so a tool loop replays turn by turn.
 *
 * <p>A {@linkplain #replaying replaying} provider only reads files and fails on a request it has no
 * recording for; a request it is sent again reads the request's next recording, as {@linkplain
 * #recordingEvery recording every call} numbered them, and the last once there are no more. A
 * {@linkplain #recording recording} provider sends every request to the real provider and writes
 * the answer; recording costs money and needs the user's go-ahead. A provider {@linkplain
 * #recordingEvery recording every call} replays nothing: it sends every request, so a run behaves
 * exactly as without it, and writes each to a file of its own, a repeat of one request beside the
 * first, for a person to read the run's conversations and a test to replay them. Each file also
 * says which role sent the request and why, outside the key.
 */
public final class RecordReplayLlmProvider implements LlmProvider {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
  private static final JsonMapper COMPACT = JsonMapper.builder().build();

  private final String name;
  private final Path directory;
  private final LlmProvider recordFrom;
  private static final Logger LOG = LoggerFactory.getLogger(RecordReplayLlmProvider.class);

  private final boolean every;

  /** How many times a replay has answered each key, so a repeat reads the repeat (#903). */
  private final java.util.Map<String, Integer> served = new java.util.HashMap<>();

  private RecordReplayLlmProvider(
      String name, Path directory, LlmProvider recordFrom, boolean every) {
    this.name = ModelConfig.nonBlank("Provider name", name);
    this.directory = Objects.requireNonNull(directory, "directory");
    this.recordFrom = recordFrom;
    this.every = every;
  }

  /** Answers from the recordings in the directory, standing in for the provider with that name. */
  public static RecordReplayLlmProvider replaying(String name, Path directory) {
    return new RecordReplayLlmProvider(name, directory, null, false);
  }

  /**
   * Replays recorded requests and sends the others to the given provider, recording its answers in
   * the directory.
   */
  public static RecordReplayLlmProvider recording(LlmProvider provider, Path directory) {
    Objects.requireNonNull(provider, "provider");
    return new RecordReplayLlmProvider(provider.name(), directory, provider, false);
  }

  /**
   * Sends every request to the given provider and records each call in the directory, replaying
   * nothing (#529): the first call of a request under its key's name, a repeat beside it with a
   * number ({@code <key>-2.json}).
   */
  public static RecordReplayLlmProvider recordingEvery(LlmProvider provider, Path directory) {
    Objects.requireNonNull(provider, "provider");
    return new RecordReplayLlmProvider(provider.name(), directory, provider, true);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public LlmResponse complete(String model, LlmRequest request) {
    return complete(model, request, Thinking.DEFAULT, () -> recordFrom.complete(model, request));
  }

  @Override
  public LlmResponse complete(ModelConfig model, LlmRequest request) {
    return complete(
        model.model(), request, model.thinking(), () -> recordFrom.complete(model, request));
  }

  private LlmResponse complete(
      String model, LlmRequest request, Thinking thinking, Supplier<LlmResponse> record) {
    ObjectNode key = key(model, request, thinking);
    String hash = hash(key);
    Path file = directory.resolve(hash + ".json");
    if (every) {
      LlmResponse response = record.get();
      Path claimed = free(hash);
      try {
        write(claimed, key, request, response);
      } catch (UncheckedIOException e) {
        // The call is made and paid for: a recording that cannot be written does not fail it.
        LOG.warn("Could not record a call in {}: {}", claimed, e.getMessage());
        try {
          Files.deleteIfExists(claimed);
        } catch (IOException ignored) {
          // Nothing more to do; the empty file is left.
        }
      }
      return response;
    }
    if (recordFrom == null) {
      Path repeat = repeat(hash);
      if (repeat != null) {
        return read(repeat);
      }
    } else if (Files.isRegularFile(file)) {
      return read(file);
    }
    if (recordFrom != null) {
      LlmResponse response = record.get();
      write(file, key, request, response);
      return response;
    }
    throw new LlmException(
        "No recording for this request in "
            + directory
            + " (expected "
            + file.getFileName()
            + "). Record it with a recording provider, which calls the real model.");
  }

  /** What identifies a request: everything sent to the model, in a fixed order. */
  private ObjectNode key(String model, LlmRequest request, Thinking thinking) {
    ObjectNode key = COMPACT.createObjectNode();
    key.put("provider", name);
    key.put("model", model);
    if (request.system() == null) {
      key.putNull("system");
    } else {
      key.put("system", request.system());
    }
    key.put("cacheSystem", request.cacheSystem());
    ArrayNode messages = key.putArray("messages");
    for (LlmMessage message : request.messages()) {
      ObjectNode turn =
          messages.addObject().put("author", message.author().name()).put("text", message.text());
      // Only when there are any, so that recordings made before tools existed keep their names.
      if (!message.toolCalls().isEmpty()) {
        turn.set("toolCalls", toolCalls(message.toolCalls()));
      }
      if (!message.thinking().isEmpty()) {
        turn.set("thinking", thinking(message.thinking()));
      }
      if (!message.toolResults().isEmpty()) {
        ArrayNode results = turn.putArray("toolResults");
        for (LlmToolResult result : message.toolResults()) {
          results
              .addObject()
              .put("callId", result.callId())
              .put("content", result.content())
              .put("error", result.error());
        }
      }
    }
    key.put("maxOutputTokens", request.maxOutputTokens());
    if (!request.cacheBreaks().isEmpty()) {
      // Only when set, so recordings made before breakpoints existed keep their keys.
      ArrayNode breaks = key.putArray("cacheBreaks");
      for (CacheBreak at : request.cacheBreaks()) {
        breaks
            .addObject()
            .put("message", at.message())
            .put("offset", at.offset())
            .put("longLived", at.longLived());
      }
    }
    if (thinking.disabled()) {
      // Only when set, so that recordings made before this setting existed keep their names.
      key.put("thinkingDisabled", true);
      // Likewise only for an off switch other than the one that existed first.
      if (thinking.offSwitch() != Thinking.OffSwitch.DISABLED) {
        key.put("thinkingOffSwitch", thinking.offSwitch().name());
      }
    }
    if (!request.tools().isEmpty()) {
      ArrayNode tools = key.putArray("tools");
      for (LlmTool tool : request.tools()) {
        tools
            .addObject()
            .put("name", tool.name())
            .put("description", tool.description())
            .set("inputSchema", tool.inputSchema());
      }
    }
    return key;
  }

  private static String hash(ObjectNode key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(COMPACT.writeValueAsString(key).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ArrayNode toolCalls(List<LlmToolCall> calls) {
    ArrayNode array = COMPACT.createArrayNode();
    for (LlmToolCall call : calls) {
      array.addObject().put("id", call.id()).put("name", call.name()).set("input", call.input());
    }
    return array;
  }

  private static ArrayNode thinking(List<LlmThinking> blocks) {
    ArrayNode array = COMPACT.createArrayNode();
    for (LlmThinking block : blocks) {
      ObjectNode node =
          array.addObject().put("text", block.text()).put("redacted", block.redacted());
      if (block.signature() != null) {
        node.put("signature", block.signature());
      }
    }
    return array;
  }

  /**
   * The recording a replay answers this call of the key with: its n-th call reads the n-th
   * recording ({@code <key>-n.json}, the first {@code <key>.json}), as a run recording every call
   * wrote them, and a call past the last recording reads the last again; {@code null} when there is
   * none.
   */
  private synchronized Path repeat(String hash) {
    int n = served.merge(hash, 1, Integer::sum);
    for (int k = n; k >= 1; k--) {
      Path file = directory.resolve(k == 1 ? hash + ".json" : hash + "-" + k + ".json");
      if (Files.isRegularFile(file)) {
        return file;
      }
    }
    served.merge(hash, -1, Integer::sum);
    return null;
  }

  /** The first file name of the key that holds no recording yet, claimed for this call. */
  private synchronized Path free(String hash) {
    try {
      Files.createDirectories(directory);
      for (int n = 1; ; n++) {
        Path file = directory.resolve(n == 1 ? hash + ".json" : hash + "-" + n + ".json");
        try {
          Files.createFile(file);
          return file;
        } catch (java.nio.file.FileAlreadyExistsException taken) {
          // A call of the same request is recorded there; try the next number.
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot record in " + directory, e);
    }
  }

  private static void write(Path file, ObjectNode key, LlmRequest request, LlmResponse response) {
    ObjectNode root = MAPPER.createObjectNode();
    // Who sent it and why, for a person reading the recording; not part of the key.
    root.putObject("about")
        .put("role", request.role() == null ? null : request.role().name())
        .put("purpose", request.purpose());
    root.set("request", key);
    ObjectNode answer = root.putObject("response");
    answer.put("text", response.text());
    if (response.stopReason() == null) {
      answer.putNull("stopReason");
    } else {
      answer.put("stopReason", response.stopReason());
    }
    ObjectNode usage =
        answer
            .putObject("usage")
            .put("input", response.usage().input())
            .put("output", response.usage().output())
            .put("cacheWrite", response.usage().cacheWrite())
            .put("cacheRead", response.usage().cacheRead());
    if (response.usage().cacheWriteLong() > 0) {
      usage.put("cacheWriteLong", response.usage().cacheWriteLong());
    }
    if (!response.toolCalls().isEmpty()) {
      answer.set("toolCalls", toolCalls(response.toolCalls()));
    }
    if (!response.thinking().isEmpty()) {
      answer.set("thinking", thinking(response.thinking()));
    }
    try {
      Files.createDirectories(file.getParent());
      // Written to a temporary file and moved, so that concurrent readers never see half a file.
      Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
      try {
        Files.writeString(temp, MAPPER.writeValueAsString(root) + "\n");
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write recording " + file, e);
    }
  }

  private static LlmResponse read(Path file) {
    JsonNode answer;
    try {
      answer = MAPPER.readTree(Files.readString(file)).required("response");
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read recording " + file, e);
    }
    JsonNode usage = answer.required("usage");
    JsonNode stopReason = answer.path("stopReason");
    List<LlmToolCall> calls = new ArrayList<>();
    for (JsonNode call : answer.path("toolCalls")) {
      calls.add(
          new LlmToolCall(
              call.required("id").asString(),
              call.required("name").asString(),
              call.required("input")));
    }
    List<LlmThinking> thought = new ArrayList<>();
    for (JsonNode block : answer.path("thinking")) {
      JsonNode signature = block.path("signature");
      thought.add(
          new LlmThinking(
              block.required("text").asString(),
              signature.isString() ? signature.asString() : null,
              block.path("redacted").asBoolean(false)));
    }
    return new LlmResponse(
        answer.required("text").asString(),
        stopReason.isString() ? stopReason.asString() : null,
        new TokenUsage(
            usage.required("input").asLong(),
            usage.required("output").asLong(),
            usage.required("cacheWrite").asLong(),
            usage.required("cacheRead").asLong(),
            usage.path("cacheWriteLong").asLong(0)),
        calls,
        thought);
  }
}
