package io.lantern.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
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
 * provider, model, system prompt, prompt-caching flag, messages and output limit. The role and
 * purpose are not sent and so are not part of the key.
 *
 * <p>A {@linkplain #replaying replaying} provider only reads files and fails on a request it has no
 * recording for. A {@linkplain #recording recording} provider sends every request to the real
 * provider and writes the answer; recording costs money and needs the user's go-ahead.
 */
public final class RecordReplayLlmProvider implements LlmProvider {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
  private static final JsonMapper COMPACT = JsonMapper.builder().build();

  private final String name;
  private final Path directory;
  private final LlmProvider recordFrom;

  private RecordReplayLlmProvider(String name, Path directory, LlmProvider recordFrom) {
    this.name = ModelConfig.nonBlank("Provider name", name);
    this.directory = Objects.requireNonNull(directory, "directory");
    this.recordFrom = recordFrom;
  }

  /** Answers from the recordings in the directory, standing in for the provider with that name. */
  public static RecordReplayLlmProvider replaying(String name, Path directory) {
    return new RecordReplayLlmProvider(name, directory, null);
  }

  /**
   * Replays recorded requests and sends the others to the given provider, recording its answers in
   * the directory.
   */
  public static RecordReplayLlmProvider recording(LlmProvider provider, Path directory) {
    Objects.requireNonNull(provider, "provider");
    return new RecordReplayLlmProvider(provider.name(), directory, provider);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public LlmResponse complete(String model, LlmRequest request) {
    ObjectNode key = key(model, request);
    Path file = directory.resolve(hash(key) + ".json");
    if (Files.isRegularFile(file)) {
      return read(file);
    }
    if (recordFrom != null) {
      LlmResponse response = recordFrom.complete(model, request);
      write(file, key, response);
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
  private ObjectNode key(String model, LlmRequest request) {
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
      messages.addObject().put("author", message.author().name()).put("text", message.text());
    }
    key.put("maxOutputTokens", request.maxOutputTokens());
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

  private static void write(Path file, ObjectNode key, LlmResponse response) {
    ObjectNode root = MAPPER.createObjectNode();
    root.set("request", key);
    ObjectNode answer = root.putObject("response");
    answer.put("text", response.text());
    if (response.stopReason() == null) {
      answer.putNull("stopReason");
    } else {
      answer.put("stopReason", response.stopReason());
    }
    answer
        .putObject("usage")
        .put("input", response.usage().input())
        .put("output", response.usage().output())
        .put("cacheWrite", response.usage().cacheWrite())
        .put("cacheRead", response.usage().cacheRead());
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
    return new LlmResponse(
        answer.required("text").asString(),
        stopReason.isString() ? stopReason.asString() : null,
        new TokenUsage(
            usage.required("input").asLong(),
            usage.required("output").asLong(),
            usage.required("cacheWrite").asLong(),
            usage.required("cacheRead").asLong()));
  }
}
