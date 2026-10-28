package io.lantern.summarize;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Summarizes units bottom-up: a unit's summary is written from its facts and the summaries of the
 * units it contains, so each level builds on the one below.
 *
 * <p>Every summary is cached under a key that hashes its entity and level, the IDs of its input
 * facts, the text of its contained units' summaries, the prompt version and the model (provider and
 * identifier). A contained unit's summary counts with both its key and its text. A unit whose key
 * is already in the store is not sent to the model again. When a unit's facts change its key
 * changes, so it is summarized again; its new text then changes its parent's key, and so on up to
 * the repository. Units whose inputs did not change are reused.
 *
 * <p>Every summary must cite the facts or contained summaries it used. An answer that cites
 * nothing, or cites a reference that was not in the prompt, is sent back once with the reason; if
 * the second answer is no better the unit fails with {@link SummaryRejectedException} and nothing
 * is stored for it.
 *
 * <p>Not thread-safe; use one summarizer per run.
 */
public final class Summarizer {

  /** The stop reason of an answer cut off at its output limit, as Anthropic reports it. */
  private static final String MAX_TOKENS = "max_tokens";

  private final LlmClient client;
  private final SummaryStore store;

  /**
   * @param client the LLM client, with the run's budgets
   * @param store where summaries are cached
   */
  public Summarizer(LlmClient client, SummaryStore store) {
    this.client = Objects.requireNonNull(client, "client");
    this.store = Objects.requireNonNull(store, "store");
  }

  /**
   * What a run produced.
   *
   * @param summaries the summary of every unit that has one, by entity; a unit without facts and
   *     without summarized parts has none
   * @param generated how many summaries the model wrote in this run
   * @param reused how many came from the store
   */
  public record Result(Map<EntityId, Summary> summaries, int generated, int reused) {
    public Result {
      summaries = Collections.unmodifiableMap(new LinkedHashMap<>(summaries));
    }
  }

  /**
   * Summarizes the given units and everything they contain, for example the trees from {@link
   * SummaryTree#fromFacts}.
   *
   * @throws SummaryRejectedException if the model's summary of a unit is rejected twice
   * @throws io.lantern.llm.LlmException if a call fails or is refused, for example over budget
   */
  public Result summarize(Collection<SummaryNode> roots) {
    Run run = new Run(client.model(Role.SUMMARIZE));
    for (SummaryNode root : roots) {
      run.visit(root, new HashSet<>());
    }
    Map<EntityId, Summary> summaries = new LinkedHashMap<>();
    run.done.forEach((entity, summary) -> summary.ifPresent(s -> summaries.put(entity, s)));
    return new Result(summaries, run.generated, run.reused);
  }

  /** The cache key of a unit with the given contained summaries, for the given model. */
  static String key(SummaryNode node, List<Summary> children, ModelConfig model) {
    StringBuilder text = new StringBuilder();
    text.append("lantern-summary\n");
    text.append("prompt ").append(Prompt.VERSION).append('\n');
    text.append("provider ").append(oneLine(model.provider())).append('\n');
    text.append("model ").append(oneLine(model.model())).append('\n');
    text.append("level ").append(node.level()).append('\n');
    text.append("entity ").append(node.entity()).append('\n');
    for (Fact fact : node.facts()) {
      text.append("fact ").append(fact.id()).append('\n');
    }
    for (Summary child : children) {
      text.append("child ")
          .append(child.entity())
          .append(' ')
          .append(child.key())
          .append(' ')
          .append(sha256(child.text()))
          .append('\n');
    }
    return sha256(text.toString());
  }

  private static String oneLine(String value) {
    return value.replace("\\", "\\\\").replace("\n", "\\n");
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private final class Run {
    private final ModelConfig model;
    private final Map<EntityId, Optional<Summary>> done = new LinkedHashMap<>();
    private int generated;
    private int reused;

    Run(ModelConfig model) {
      this.model = model;
    }

    Optional<Summary> visit(SummaryNode node, Set<EntityId> visiting) {
      Optional<Summary> known = done.get(node.entity());
      if (known != null) {
        return known;
      }
      if (!visiting.add(node.entity())) {
        throw new IllegalArgumentException("Units contain each other: " + node.entity());
      }
      List<Summary> children = new ArrayList<>();
      for (SummaryNode child : node.children()) {
        visit(child, visiting).ifPresent(children::add);
      }
      visiting.remove(node.entity());
      Optional<Summary> summary =
          node.facts().isEmpty() && children.isEmpty()
              ? Optional.empty()
              : Optional.of(summary(node, children));
      done.put(node.entity(), summary);
      return summary;
    }

    private Summary summary(SummaryNode node, List<Summary> children) {
      String key = key(node, children, model);
      Optional<Summary> cached = store.find(key);
      if (cached.isPresent()) {
        reused++;
        return cached.get();
      }
      Summary summary = generate(key, node, children);
      store.save(summary);
      generated++;
      return summary;
    }

    private Summary generate(String key, SummaryNode node, List<Summary> children) {
      List<String> factIds = node.facts().stream().map(Fact::id).toList();
      String purpose =
          "summarize " + node.level().name().toLowerCase(Locale.ROOT) + " " + node.entity();
      List<LlmMessage> messages = new ArrayList<>();
      messages.add(LlmMessage.user(Prompt.user(node, children)));
      LlmResponse first = ask(purpose, node, messages);
      Citations.Checked checked;
      try {
        checked = check(first, factIds, children);
      } catch (Citations.Rejected rejected) {
        if (first.text().isBlank()) {
          // A conversation cannot hold an empty turn, so the reason goes with the prompt again.
          messages.set(
              0,
              LlmMessage.user(
                  messages.getFirst().text()
                      + "\n"
                      + Prompt.RETRY.formatted(rejected.getMessage())));
        } else {
          messages.add(LlmMessage.assistant(first.text()));
          messages.add(LlmMessage.user(Prompt.RETRY.formatted(rejected.getMessage())));
        }
        LlmResponse second = ask(purpose + " (retry)", node, messages);
        try {
          checked = check(second, factIds, children);
        } catch (Citations.Rejected again) {
          throw new SummaryRejectedException(
              "The summary of "
                  + node.entity()
                  + " was rejected twice, the second time because "
                  + again.getMessage());
        }
      }
      return new Summary(
          key,
          node.entity(),
          node.level(),
          checked.text(),
          checked.facts(),
          checked.summaries(),
          factIds,
          children.stream().map(Summary::key).toList(),
          Prompt.VERSION,
          model.provider(),
          model.model());
    }

    private Citations.Checked check(
        LlmResponse response, List<String> factIds, List<Summary> children) {
      if (MAX_TOKENS.equals(response.stopReason())) {
        throw new Citations.Rejected("it was cut off at the length limit; write a shorter one.");
      }
      return Citations.check(response.text(), factIds, children);
    }

    private LlmResponse ask(String purpose, SummaryNode node, List<LlmMessage> messages) {
      return client.call(
          new LlmRequest(
              Role.SUMMARIZE,
              purpose,
              Prompt.SYSTEM,
              messages,
              node.level().maxOutputTokens(),
              false));
    }
  }
}
