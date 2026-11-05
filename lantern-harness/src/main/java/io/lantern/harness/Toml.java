package io.lantern.harness;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * TOML for what the model reads and writes (#872, {@code docs/research/toml-model-answers.md}): an
 * answer is read into the same tree a JSON answer gives, so the checks after it are unchanged, and
 * a request's data is written with strings the model reads as they are. A TOML answer costs fewer
 * output tokens than the JSON of the same sentences: no braces, and no quotes around keys.
 */
public final class Toml {

  private static final TomlMapper TOML = TomlMapper.builder().build();

  /**
   * A fenced answer, {@code ```toml ... ```}, wherever it starts: the fence and the text around it
   * are left out.
   */
  private static final Pattern FENCE =
      Pattern.compile(
          "^[ \\t]*```[A-Za-z]*[ \\t]*\\R(.*?)^[ \\t]*```", Pattern.DOTALL | Pattern.MULTILINE);

  /** The first line a TOML document can start with: a table header or a key. */
  private static final Pattern START =
      Pattern.compile("^[ \\t]*(?:\\[|[A-Za-z0-9_\"'-]+[ \\t]*=)", Pattern.MULTILINE);

  /** A key that needs no quotes. */
  private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

  private Toml() {}

  /**
   * An answer read, with the prose before its first table or key and after its last line, which are
   * left out.
   */
  public record Read(JsonNode root, String before, String after) {

    /** An answer with nothing after its document. */
    public Read(JsonNode root, String before) {
      this(root, before, "");
    }
  }

  /** How many lines of prose after the document are left out at most. */
  private static final int PROSE_AFTER = 5;

  /**
   * Whether a line can only be prose, not part of a TOML document: no table header, comment or key,
   * and not the end of a value.
   */
  private static boolean prose(String line) {
    String l = line.strip();
    return !l.startsWith("[")
        && !l.startsWith("#")
        && !l.contains("=")
        && !l.endsWith("'''")
        && !l.endsWith("\"\"\"")
        && !l.endsWith("]")
        && !l.endsWith("\"")
        && !l.endsWith("'")
        && !l.endsWith("}");
  }

  /**
   * The answer as a tree, as a JSON answer of the same content gives it; its fence and any prose
   * before its first table or key are left out.
   *
   * @throws Fault if it is not one TOML document, saying where and why in words the model can act
   *     on
   */
  public static Read read(String text) {
    String body = text == null ? "" : text;
    Matcher fence = FENCE.matcher(body);
    if (fence.find()) {
      body = fence.group(1);
    }
    Matcher start = START.matcher(body);
    if (!start.find()) {
      throw new Fault(
          body.isBlank()
              ? "the answer is empty"
              : "it has no TOML table or key; answer with the TOML document alone");
    }
    String before = body.substring(0, start.start()).strip();
    body = body.substring(start.start());
    try {
      return new Read(TOML.readTree(body), before);
    } catch (JacksonException e) {
      // A few lines of prose after the document, such as "Let me know if you need more.", are
      // left out, as text after a JSON answer's object is (#744).
      java.util.List<String> lines = new java.util.ArrayList<>(body.lines().toList());
      java.util.List<String> after = new java.util.ArrayList<>();
      int prose = 0;
      while (!lines.isEmpty() && prose < PROSE_AFTER) {
        String last = lines.getLast();
        if (!last.isBlank() && !prose(last)) {
          break;
        }
        after.addFirst(lines.removeLast());
        if (last.isBlank()) {
          continue;
        }
        prose++;
        try {
          return new Read(
              TOML.readTree(String.join("\n", lines) + "\n"),
              before,
              String.join("\n", after).strip());
        } catch (JacksonException again) {
          // Not yet: try leaving out another line of prose.
        }
      }
      String message = e.getOriginalMessage();
      String line = message == null ? "unreadable" : message.lines().findFirst().orElse(message);
      String where =
          e.getLocation() == null || e.getLocation().getLineNr() < 1
              ? ""
              : " at line " + (e.getLocation().getLineNr() + lines(text, body));
      throw new Fault("it is not valid TOML" + where + " (" + line + ")");
    }
  }

  /**
   * Whether the answer holds a readable document: a JSON object, or one TOML document (#872). An
   * answer with none is not stored as a drop, since a new call may give one (#744).
   */
  public static boolean readable(String text) {
    if (JsonFaults.first(text) != null) {
      return true;
    }
    try {
      read(text);
      return true;
    } catch (Fault e) {
      return false;
    }
  }

  /** The lines before the document's start in the text, so a fault's line is the answer's. */
  private static int lines(String text, String body) {
    int at = text == null ? -1 : text.indexOf(body);
    return at <= 0 ? 0 : (int) text.substring(0, at).lines().count() - 1;
  }

  /** What is wrong with an answer that is not one TOML document. */
  public static final class Fault extends RuntimeException {
    public Fault(String message) {
      super(message);
    }
  }

  /** The key as TOML writes it: bare when it can be, else a quoted string. */
  public static String key(String key) {
    return BARE_KEY.matcher(key).matches() ? key : basic(key);
  }

  /**
   * The text as a TOML string the model reads as written: a basic string on one line for a line of
   * text, else a multi-line literal string, which needs no escapes, unless the text holds what a
   * literal string cannot (three single quotes, or a control character other than a tab or a line
   * break), which a multi-line basic string escapes. So repository text can never end the string
   * early and pass for the request's own structure.
   */
  public static String string(String text) {
    if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
      return basic(text);
    }
    if (!text.contains("'''") && !hasControl(text) && !text.endsWith("'")) {
      // A line break right after the opening quotes is left out by TOML, so the text starts on its
      // own line.
      return "'''\n" + text + "'''";
    }
    return "\"\"\"\n" + escaped(text, true) + "\"\"\"";
  }

  /** The text as a basic string on one line, every quote, backslash and control escaped. */
  public static String basic(String text) {
    return "\"" + escaped(text, false) + "\"";
  }

  private static boolean hasControl(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ((c < 0x20 && c != '\t' && c != '\n') || c == 0x7f) {
        return true;
      }
    }
    return false;
  }

  private static String escaped(String text, boolean multiline) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append(multiline ? "\n" : "\\n");
        case '\t' -> out.append(multiline ? "\t" : "\\t");
        case '\r' -> out.append("\\r");
        default -> {
          if (c < 0x20 || c == 0x7f) {
            out.append(String.format("\\u%04X", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
