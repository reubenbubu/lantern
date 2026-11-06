package io.lantern.app;

import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmProvider;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.LlmToolCall;
import io.lantern.llm.LlmToolResult;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Tokens;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Investigate test's {@code live.log} (#903): one block per turn of every conversation, written
 * as the run goes, each line tagged with the conversation's number so conversations running at the
 * same time can be told apart. A turn's block is written once its tool calls' results are known, in
 * the conversation's next request, or at the end of the run ({@link #finish}); each block is one
 * write, so blocks of parallel conversations never interleave.
 */
final class InvestigateLiveLog implements LlmProvider {

  /** How many lines of a tool result or note the log shows. */
  static final int LINES = 8;

  /** How many characters of each such line the log shows. */
  static final int WIDTH = 160;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String RULE = "═".repeat(60);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final LlmProvider provider;
  private final Path file;
  private final String prefix;
  private final Clock clock;
  private final Map<String, Conversation> conversations = new LinkedHashMap<>();

  /**
   * @param provider the provider that makes the calls
   * @param file where the log goes
   * @param prefix the part of a conversation's purpose before its lead, left out of the log
   */
  InvestigateLiveLog(LlmProvider provider, Path file, String prefix, Clock clock) {
    this.provider = provider;
    this.file = file;
    this.prefix = prefix;
    this.clock = clock;
  }

  /** One conversation: its number, its turns so far, and the turn whose results are awaited. */
  private static final class Conversation {
    final int number;
    int turns;
    long sent;
    BigDecimal cost = BigDecimal.ZERO;
    Duration took = Duration.ZERO;
    Turn pending;

    Conversation(int number) {
      this.number = number;
    }
  }

  /** A turn the model has answered, waiting for its tool calls' results. */
  private record Turn(
      int number, LocalTime at, LlmResponse response, BigDecimal cost, Duration took) {}

  @Override
  public String name() {
    return provider.name();
  }

  @Override
  public LlmResponse complete(String model, LlmRequest request) {
    return logged(null, request, () -> provider.complete(model, request));
  }

  @Override
  public LlmResponse complete(ModelConfig model, LlmRequest request) {
    return logged(model, request, () -> provider.complete(model, request));
  }

  private LlmResponse logged(ModelConfig model, LlmRequest request, Supplier<LlmResponse> call) {
    started(request);
    LocalTime at = LocalTime.now(clock.withZone(ZoneId.systemDefault()));
    long begun = System.nanoTime();
    LlmResponse response = call.get();
    Duration took = Duration.ofNanos(System.nanoTime() - begun);
    BigDecimal cost = model == null ? BigDecimal.ZERO : model.pricing().cost(response.usage());
    synchronized (this) {
      Conversation c = conversations.get(request.purpose());
      c.turns++;
      c.sent += sent(response);
      c.cost = c.cost.add(cost);
      c.took = c.took.plus(took);
      c.pending = new Turn(c.turns, at, response, cost, took);
    }
    return response;
  }

  /** Opens a conversation's block on its first request; otherwise writes its previous turn. */
  private void started(LlmRequest request) {
    String block;
    synchronized (this) {
      Conversation c = conversations.get(request.purpose());
      if (c == null) {
        c = new Conversation(conversations.size() + 1);
        conversations.put(request.purpose(), c);
        String first = request.messages().getFirst().text();
        block =
            RULE
                + "\n"
                + tag(c)
                + " "
                + lead(request.purpose())
                + "\nfirst message "
                + "%,d".formatted(first.length())
                + " characters, "
                + "%,d".formatted(Tokens.estimate(first.length()))
                + " tokens\n"
                + RULE
                + "\n";
      } else {
        Turn turn = c.pending;
        c.pending = null;
        block = turn == null ? "" : turn(c, turn, request.messages().getLast());
      }
    }
    write(block);
  }

  /** The conversations' numbers by purpose, in the order they started. */
  synchronized Map<String, Integer> numbers() {
    Map<String, Integer> numbers = new LinkedHashMap<>();
    conversations.forEach((purpose, c) -> numbers.put(purpose, c.number));
    return numbers;
  }

  /**
   * Ends the log: each conversation's last turn, and its end line as {@code ending} words it from
   * its purpose and its totals.
   */
  void finish(Ending ending) {
    StringBuilder out = new StringBuilder();
    synchronized (this) {
      for (Map.Entry<String, Conversation> e : conversations.entrySet()) {
        Conversation c = e.getValue();
        if (c.pending != null) {
          out.append(turn(c, c.pending, null));
          c.pending = null;
        }
        out.append(tag(c))
            .append(" END ")
            .append(ending.words(e.getKey()))
            .append(" · ")
            .append(c.turns)
            .append(c.turns == 1 ? " turn" : " turns")
            .append(" · sent ")
            .append("%,d".formatted(c.sent))
            .append(" tokens · $")
            .append(c.cost.setScale(4, RoundingMode.HALF_UP).toPlainString())
            .append(" · ")
            .append(Math.round(c.took.toMillis() / 1000.0))
            .append(" s\n");
      }
    }
    write(out.toString());
  }

  /** Words a conversation's end from its purpose: how it ended and its verdict. */
  @FunctionalInterface
  interface Ending {
    String words(String purpose);
  }

  private String turn(Conversation c, Turn turn, LlmMessage next) {
    LlmResponse r = turn.response();
    StringBuilder b =
        new StringBuilder("── ")
            .append(tag(c))
            .append(" turn ")
            .append(turn.number())
            .append(" · ")
            .append(TIME.format(turn.at()))
            .append(" · sent ")
            .append("%,d".formatted(sent(r)))
            .append(" tokens (cached ")
            .append("%,d".formatted(r.usage().cacheRead()))
            .append(", new ")
            .append("%,d".formatted(sent(r) - r.usage().cacheRead()))
            .append(") · wrote ")
            .append("%,d".formatted(r.usage().output()))
            .append(" · $")
            .append(turn.cost().setScale(4, RoundingMode.HALF_UP).toPlainString())
            .append(" ──\n");
    if (!r.text().isBlank()) {
      b.append("  text\n");
      lines(b, r.text(), "    ");
    }
    Map<String, LlmToolResult> results = new LinkedHashMap<>();
    if (next != null) {
      next.toolResults().forEach(result -> results.put(result.callId(), result));
    }
    for (LlmToolCall call : r.toolCalls()) {
      b.append("  → ").append(call.name()).append("  ").append(cut(input(call))).append('\n');
      LlmToolResult result = results.get(call.id());
      if (result == null) {
        continue;
      }
      if (result.error()) {
        b.append("    ✗ rejected: ").append(cut(firstLine(result.content()))).append('\n');
      } else {
        b.append("    ← ")
            .append("%,d".formatted(result.content().length()))
            .append(" characters\n");
        lines(b, result.content(), "      ");
      }
    }
    if (next != null && !next.toolResults().isEmpty() && !next.text().isBlank()) {
      b.append("  note to the model:\n");
      lines(b, next.text(), "    ");
    }
    return b.toString();
  }

  /** What a turn sent: its fresh input, cache writes and cache reads. */
  private static long sent(LlmResponse r) {
    return r.usage().input()
        + r.usage().cacheWrite()
        + r.usage().cacheWriteLong()
        + r.usage().cacheRead();
  }

  /**
   * A tool call on one line: an answer by its verdicts and how many references it cites, any other
   * call by its input's values.
   */
  static String input(LlmToolCall call) {
    JsonNode in = call.input();
    if (in.has("items")) {
      JsonNode items = in.get("items");
      if (items.isString()) {
        try {
          items = JSON.readTree(items.asString());
        } catch (RuntimeException e) {
          return "answer that is not JSON: " + items.asString();
        }
      }
      List<String> parts = new ArrayList<>();
      for (var item : items.properties()) {
        JsonNode v = item.getValue();
        String verdict = v.path("verdict").asString("no verdict");
        int cites = 0;
        for (String field : List.of("cites", "evidence", "quotes")) {
          if (v.path(field).isArray()) {
            cites += v.path(field).size();
          }
        }
        parts.add(item.getKey() + " verdict " + verdict + ", " + cites + " cited");
      }
      return parts.isEmpty() ? "answer with no items" : String.join("; ", parts);
    }
    List<String> values = new ArrayList<>();
    for (var field : in.properties()) {
      JsonNode v = field.getValue();
      values.add(field.getKey() + "=" + (v.isString() ? v.asString() : v.toString()));
    }
    return String.join(" ", values);
  }

  private static void lines(StringBuilder b, String text, String indent) {
    String[] lines = text.strip().split("\n", -1);
    int shown = Math.min(LINES, lines.length);
    for (int i = 0; i < shown; i++) {
      b.append(indent).append(cut(lines[i])).append('\n');
    }
    if (lines.length > shown) {
      b.append(indent).append("… ").append(lines.length - shown).append(" more lines\n");
    }
  }

  private static String firstLine(String text) {
    String stripped = text.strip();
    int end = stripped.indexOf('\n');
    return end < 0 ? stripped : stripped.substring(0, end) + " …";
  }

  static String cut(String line) {
    return line.length() <= WIDTH ? line : line.substring(0, WIDTH - 1) + "…";
  }

  private static String tag(Conversation c) {
    return "[%02d]".formatted(c.number);
  }

  private String lead(String purpose) {
    return purpose.startsWith(prefix) ? purpose.substring(prefix.length()) : purpose;
  }

  private synchronized void write(String block) {
    if (block.isEmpty()) {
      return;
    }
    try {
      Files.writeString(
          file,
          block,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      // The calls are made and paid for: a log that cannot be written must not fail them.
      System.err.println("Cannot write " + file + ": " + new UncheckedIOException(e).getMessage());
    }
  }
}
