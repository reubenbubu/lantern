package io.lantern.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Investigate test's {@code conversations/} (#903): one file per conversation, verbatim and
 * complete, built from the recorded calls ({@code calls/}) the way they replay. A conversation's
 * last request holds every message sent before its last turn, and that turn's response is the
 * model's last message; the file gives the system prompt, the tools, then every message in order,
 * each payload exactly as sent or received. Nothing is summarised, cut or commented.
 */
final class InvestigateTranscripts {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern BACKTICKS = Pattern.compile("`+");

  private InvestigateTranscripts() {}

  /** One recorded call: the request as sent and the response as received. */
  record Recorded(String purpose, JsonNode request, JsonNode response, FileTime written) {}

  /** The recorded calls of a {@code calls/} folder, by purpose, each purpose's calls in order. */
  static Map<String, List<Recorded>> read(Path calls) throws IOException {
    List<Recorded> all = new ArrayList<>();
    try (Stream<Path> files = Files.list(calls)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
        JsonNode root = JSON.readTree(Files.readString(f));
        all.add(
            new Recorded(
                root.path("about").path("purpose").asString(),
                root.path("request"),
                root.path("response"),
                Files.getLastModifiedTime(f)));
      }
    }
    // A conversation's turns grow by messages; a repeat of the same request came later.
    all.sort(
        Comparator.comparing(Recorded::purpose)
            .thenComparingInt(r -> r.request().path("messages").size())
            .thenComparing(Recorded::written));
    Map<String, List<Recorded>> byPurpose = new LinkedHashMap<>();
    all.stream()
        .sorted(Comparator.comparing(Recorded::written))
        .forEach(r -> byPurpose.computeIfAbsent(r.purpose(), p -> new ArrayList<>()));
    all.forEach(r -> byPurpose.get(r.purpose()).add(r));
    return byPurpose;
  }

  /**
   * Writes one file per conversation to {@code folder}, named {@code <NN>-<lead>.md}: {@code NN}
   * the number {@code numbers} gives its purpose (the live log's), or the next free one.
   *
   * @return the files written, by purpose
   */
  static Map<String, Path> write(
      Path calls, Path folder, Map<String, Integer> numbers, String prefix) throws IOException {
    Files.createDirectories(folder);
    Map<String, Path> written = new LinkedHashMap<>();
    int next = numbers.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    for (Map.Entry<String, List<Recorded>> e : read(calls).entrySet()) {
      String purpose = e.getKey();
      Integer number = numbers.get(purpose);
      if (number == null) {
        number = ++next;
      }
      String lead = purpose.startsWith(prefix) ? purpose.substring(prefix.length()) : purpose;
      String name =
          "%02d-%s.md".formatted(number, SummarizeOpenRouterIT.slug(lead.replace("lead:", "")));
      Path file = folder.resolve(name.length() > 120 ? name.substring(0, 117) + ".md" : name);
      Files.writeString(file, verbatim(e.getValue().getLast()));
      written.put(purpose, file);
    }
    return written;
  }

  /**
   * The rejected tool calls of every conversation, counted once each from its last request, by
   * reason: a rejection's first line, its quoted names and numbers left out so like reasons meet.
   */
  static Map<String, Integer> rejected(Map<String, List<Recorded>> conversations) {
    Map<String, Integer> byReason = new java.util.TreeMap<>();
    for (List<Recorded> turns : conversations.values()) {
      for (JsonNode m : turns.getLast().request().path("messages")) {
        for (JsonNode r : m.path("toolResults")) {
          if (r.path("error").asBoolean()) {
            byReason.merge(reason(r.path("content").asString()), 1, Integer::sum);
          }
        }
      }
    }
    return byReason;
  }

  static String reason(String content) {
    String line = content.strip();
    int end = line.indexOf('\n');
    line = end < 0 ? line : line.substring(0, end);
    int sentence = line.indexOf(". ");
    line = sentence < 0 ? line : line.substring(0, sentence + 1);
    return line.replaceAll("\"[^\"]*\"", "\"…\"")
        .replaceAll("\\b[A-Za-z0-9_.:/-]*[:/.][A-Za-z0-9_.:/-]+\\b", "…")
        .replaceAll("\\d+", "N");
  }

  /** A conversation verbatim: its last recorded call's request, then that call's response. */
  static String verbatim(Recorded last) {
    JsonNode request = last.request();
    StringBuilder md = new StringBuilder("# ").append(last.purpose()).append("\n\n");
    md.append("Model ")
        .append(request.path("model").asString())
        .append(", at most ")
        .append(request.path("maxOutputTokens").asString())
        .append(" output tokens a turn.\n\n");
    boolean anyThinking = last.response().path("thinking").size() > 0;
    for (JsonNode m : request.path("messages")) {
      anyThinking |= m.path("thinking").size() > 0;
    }
    if (!anyThinking) {
      md.append("The provider returned no reasoning in this conversation.\n\n");
    }
    md.append("## System prompt\n\n");
    if (request.path("system").isNull()) {
      md.append("(none)\n\n");
    } else {
      fenced(md, request.path("system").asString());
    }
    md.append("## Tools\n\n");
    if (request.path("tools").isEmpty()) {
      md.append("(none)\n\n");
    }
    for (JsonNode tool : request.path("tools")) {
      md.append("### ").append(tool.path("name").asString()).append("\n\n");
      fenced(md, tool.path("description").asString());
      md.append("Schema: ");
      inline(md, JSON.writeValueAsString(tool.path("inputSchema")));
      md.append("\n\n");
    }
    md.append("## Messages\n\n");
    int n = 0;
    for (JsonNode m : request.path("messages")) {
      message(md, ++n, m.path("author").asString().equals("USER") ? "Lantern" : "Model", m);
    }
    message(md, ++n, "Model", last.response());
    JsonNode usage = last.response().path("usage");
    md.append("Last turn: stop reason ")
        .append(last.response().path("stopReason").asString("(none)"))
        .append(", ")
        .append(usage.path("input").asString())
        .append(" input, ")
        .append(usage.path("cacheRead").asString())
        .append(" cache read, ")
        .append(usage.path("cacheWrite").asString())
        .append(" cache write, ")
        .append(usage.path("output").asString())
        .append(" output tokens.\n");
    return md.toString();
  }

  private static void message(StringBuilder md, int n, String author, JsonNode m) {
    md.append("### ").append(n).append(". ").append(author).append("\n\n");
    for (JsonNode t : m.path("thinking")) {
      md.append(t.path("redacted").asBoolean() ? "Reasoning (redacted):\n\n" : "Reasoning:\n\n");
      fenced(md, t.path("text").asString());
    }
    for (JsonNode r : m.path("toolResults")) {
      md.append(r.path("error").asBoolean() ? "Tool result, rejected, for " : "Tool result for ");
      inline(md, r.path("callId").asString());
      md.append(":\n\n");
      fenced(md, r.path("content").asString());
    }
    String text = m.path("text").asString();
    if (!text.isEmpty()) {
      if (m.path("toolResults").size() > 0) {
        md.append("Text after the results:\n\n");
      }
      fenced(md, text);
    }
    for (JsonNode call : m.path("toolCalls")) {
      md.append("Tool call ");
      inline(md, call.path("id").asString());
      md.append(" ");
      inline(md, call.path("name").asString());
      md.append(": ");
      inline(md, JSON.writeValueAsString(call.path("input")));
      md.append("\n\n");
    }
  }

  /** A payload in a fence longer than any run of backticks inside it, so it shows as it is. */
  static void fenced(StringBuilder md, String text) {
    String fence = "`".repeat(Math.max(3, longestBackticks(text) + 1));
    md.append(fence).append("text\n").append(text);
    if (!text.endsWith("\n")) {
      md.append('\n');
    }
    md.append(fence).append("\n\n");
  }

  private static void inline(StringBuilder md, String text) {
    String ticks = "`".repeat(longestBackticks(text) + 1);
    String pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
    md.append(ticks).append(pad).append(text).append(pad).append(ticks);
  }

  private static int longestBackticks(String text) {
    int longest = 0;
    Matcher m = BACKTICKS.matcher(text);
    while (m.find()) {
      longest = Math.max(longest, m.group().length());
    }
    return longest;
  }
}
