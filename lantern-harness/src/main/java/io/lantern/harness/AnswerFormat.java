package io.lantern.harness;

/**
 * The format a role's model answers in (#872): JSON for every role so far, TOML for those switched
 * to it, whose answers cost fewer output tokens ({@code docs/research/toml-model-answers.md}). It
 * fills the words of the common system prompt and the repair turn that name the format, so a JSON
 * role's prompts stay exactly as they were.
 */
public enum AnswerFormat {
  JSON(
      "{\"omitted\": true}",
      "JSON object that matches the schema of your task",
      "one JSON object in the same schema"),
  TOML(
      "a table with omitted = true (for example [I3] followed by omitted = true)",
      "TOML document in the format of your task",
      "one TOML document in the same format");

  private final String omitted;
  private final String answer;
  private final String again;

  AnswerFormat(String omitted, String answer, String again) {
    this.omitted = omitted;
    this.answer = answer;
    this.again = again;
  }

  /** How an answer says an item shows nothing to answer. */
  public String omitted() {
    return omitted;
  }

  /** What the answer is, as the common system prompt's last rule names it. */
  public String answer() {
    return answer;
  }

  /** What a repair turn asks for again. */
  public String again() {
    return again;
  }

  /** The text with the format's words in place of {@code ${omitted}} and {@code ${answer}}. */
  public String fill(String text) {
    return text.replace("${omitted}", omitted).replace("${answer}", answer);
  }
}
