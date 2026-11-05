package io.lantern.harness.checks;

import io.lantern.harness.Prompts;

/**
 * Block 1 of every request the harness lays out itself (knowledge-build epic, section 11.0): the
 * common system prompt, identical for every role, so it is cached once across roles.
 */
public final class CommonPrompt {

  /** The common system prompt, for a role that answers in JSON. */
  public static final String SYSTEM = system(io.lantern.harness.AnswerFormat.JSON);

  /** The common system prompt for a role that answers in the given format (#872). */
  public static String system(io.lantern.harness.AnswerFormat format) {
    return format.fill(Prompts.text("common.system.txt"));
  }

  private CommonPrompt() {}

  /** The answer envelope's schema around a per-item schema, as block 2 shows it. */
  static String envelope(String itemSchema) {
    return "{\"type\": \"object\", \"required\": [\"items\"], \"properties\": {\"items\": {\"type\":"
        + " \"object\", \"additionalProperties\": {\"oneOf\": ["
        + itemSchema
        + ", {\"type\": \"object\", \"required\": [\"omitted\"], \"properties\": {\"omitted\":"
        + " {\"const\": true}}, \"additionalProperties\": false}]}}}}";
  }
}
