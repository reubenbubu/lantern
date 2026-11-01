package io.lantern.harness;

import io.lantern.llm.LlmTool;
import io.lantern.llm.ModelConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * An item's fingerprint (knowledge-build epic, section 10): the SHA-256 of the role, its prompt
 * version, the model (provider and identifier), the role's tool definitions, the item's fingerprint
 * inputs and its canonical rendering. Fact IDs, commits and timestamps are in none of them, so an
 * item whose inputs are unchanged keeps its fingerprint across commits.
 */
public final class Fingerprints {

  private Fingerprints() {}

  /** The fingerprint of an item of the role, for the model. */
  public static String of(RoleSpec role, ModelConfig model, Item item) {
    return of(role, role.promptVersion(), model.provider(), model.model(), item);
  }

  /**
   * The fingerprint the item would have had under another prompt version and model, such as those
   * an earlier answer was given under.
   */
  public static String of(
      RoleSpec role, String promptVersion, String provider, String model, Item item) {
    StringBuilder text = new StringBuilder("lantern-item 1\n");
    text.append("role ").append(role.name()).append('\n');
    text.append("prompt ").append(oneLine(promptVersion)).append('\n');
    text.append("provider ").append(oneLine(provider)).append('\n');
    text.append("model ").append(oneLine(model)).append('\n');
    text.append("tools ").append(tools(role.tools())).append('\n');
    for (FingerprintInput input : item.inputs()) {
      text.append("input ").append(input.line()).append('\n');
    }
    text.append("rendering ").append(sha256(item.rendering())).append('\n');
    return sha256(text.toString());
  }

  private static String tools(List<LlmTool> tools) {
    if (tools.isEmpty()) {
      return "none";
    }
    StringBuilder text = new StringBuilder();
    for (LlmTool tool : tools) {
      text.append(tool.name())
          .append('\n')
          .append(tool.description())
          .append('\n')
          .append(tool.inputSchema())
          .append('\n');
    }
    return sha256(text.toString());
  }

  private static String oneLine(String value) {
    return value.replace("\\", "\\\\").replace("\n", "\\n");
  }

  /** The SHA-256 of the text's UTF-8 bytes, in hex. */
  public static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** The git blob SHA-1 of a file's text, as {@code git hash-object} gives it. */
  public static String gitBlob(String text) {
    return gitBlob(text.getBytes(StandardCharsets.UTF_8));
  }

  /** The git blob SHA-1 of a file's bytes, as {@code git hash-object} gives it. */
  public static String gitBlob(byte[] content) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(("blob " + content.length + "\0").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(sha1.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 is not available", e);
    }
  }
}
