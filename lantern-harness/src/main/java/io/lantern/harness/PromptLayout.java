package io.lantern.harness;

import io.lantern.llm.CacheBreak;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmTool;
import io.lantern.llm.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A request laid out so its longest stable part comes first (knowledge-build epic, section 10,
 * Prompt layout for caching), with cache breakpoints at block boundaries: blocks 1 and 2 are the
 * system prompt, with a breakpoint after block 2; blocks 3 to 5 are the first user turn, with a
 * breakpoint after block 3 and, when several requests share it, after block 4. That leaves one of
 * the provider's four breakpoints for an agent conversation's latest turn.
 *
 * @param system block 1: Lantern's common system prompt
 * @param role block 2: the role's instructions and output schema
 * @param shared block 3: the shared context, such as the system map; empty for none
 * @param packet block 4: the packet's context, such as a unit's facts; empty for none
 * @param packetShared whether several requests share block 4, so it gets a breakpoint
 * @param items block 5: the items
 */
public record PromptLayout(
    String system, String role, String shared, String packet, boolean packetShared, String items) {

  /** What separates two blocks. */
  static final String GAP = "\n\n";

  public PromptLayout {
    Text.nonBlank("Block 1", system);
    Text.nonBlank("Block 2", role);
    shared = shared == null ? "" : shared;
    packet = packet == null ? "" : packet;
    Text.nonBlank("Block 5", items);
  }

  /** The request this layout makes, with its cache breakpoints. */
  public LlmRequest request(
      Role modelRole, String purpose, int maxOutputTokens, List<LlmTool> tools) {
    Objects.requireNonNull(tools, "tools");
    String systemText = system + GAP + role;
    List<CacheBreak> breaks = new ArrayList<>();
    breaks.add(CacheBreak.inSystem(systemText.length()));
    StringBuilder user = new StringBuilder();
    if (!shared.isEmpty()) {
      user.append(shared);
      breaks.add(new CacheBreak(0, user.length()));
      user.append(GAP);
    }
    if (!packet.isEmpty()) {
      user.append(packet);
      if (packetShared) {
        breaks.add(new CacheBreak(0, user.length()));
      }
      user.append(GAP);
    }
    user.append(items);
    return new LlmRequest(
        modelRole,
        purpose,
        systemText,
        List.of(LlmMessage.user(user.toString())),
        maxOutputTokens,
        false,
        tools,
        breaks);
  }
}
