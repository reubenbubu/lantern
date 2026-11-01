package io.lantern.harness;

import io.lantern.harness.verify.CheckInputs;
import io.lantern.harness.verify.ItemAnswer;
import io.lantern.harness.verify.Pipeline;
import io.lantern.harness.verify.Rejection;
import io.lantern.llm.BudgetExceededException;
import io.lantern.llm.BudgetGuard;
import io.lantern.llm.Capability;
import io.lantern.llm.LlmClient;
import io.lantern.llm.LlmException;
import io.lantern.llm.LlmMessage;
import io.lantern.llm.LlmRequest;
import io.lantern.llm.LlmResponse;
import io.lantern.llm.ModelConfig;
import io.lantern.llm.TokenUsage;
import io.lantern.llm.Tokens;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * One role's work in a run (knowledge-build epic, section 10). For each item it looks the
 * fingerprint up in the answer cache, where a hit is reused once revalidated at no cost; it plans
 * the misses against the role's share of the run budget, so an item past the share is not asked; it
 * asks the rest through {@link Calls} and stores each accepted answer under its fingerprint. It
 * records all of it in the role's {@link RoleRecord}. Thread-safe: a role may ask items in
 * parallel.
 */
public final class RoleSession {

  private static final Logger LOG = LoggerFactory.getLogger(RoleSession.class);

  private final Harness harness;
  private final RoleSpec spec;
  private final ModelConfig model;
  private final LlmClient client;
  private final RoleRecord record;
  private final BigDecimal share;
  private BigDecimal planned = BigDecimal.ZERO;

  RoleSession(Harness harness, RoleSpec spec, LlmClient client, RoleRecord record) {
    this.harness = harness;
    this.spec = spec;
    this.model = client.model(spec.modelRole());
    this.share = harness.budget().share(spec.budgetGroup());
    LlmClient budgeted =
        share == null ? client : client.withBudget(new BudgetGuard(spec.name(), null, share));
    this.client = budgeted.alsoLogging(record);
    this.record = record;
  }

  /** The role. */
  public RoleSpec spec() {
    return spec;
  }

  /** The model the role uses. */
  public ModelConfig model() {
    return model;
  }

  Harness harness() {
    return harness;
  }

  /** The item's fingerprint for this role and model. */
  public String fingerprint(Item item) {
    return Fingerprints.of(spec, model, item);
  }

  /**
   * Plans items before any is asked: counts the cache's hits and estimates the tokens and dollars
   * of the misses, logs the estimate and adds it to the run record.
   *
   * @param items the items whose inputs are known now
   * @param predictedHits items not yet known that are expected to be cached, such as summaries of
   *     units none of whose parts needs asking
   * @param predictedMisses items not yet known that are expected to be asked, such as summaries of
   *     units whose parts are still to be summarized
   * @param predictedTokens the input tokens those items are expected to take, in all
   * @param prefixTokens the tokens of the prompt every request of the role starts with, written to
   *     the prompt cache once and read by the requests after the first
   */
  public Estimate plan(
      Collection<Item> items,
      int predictedHits,
      int predictedMisses,
      long predictedTokens,
      long prefixTokens) {
    int cached = predictedHits;
    long input = predictedTokens;
    for (Item item : items) {
      if (harness.answers().find(fingerprint(item)).isPresent()) {
        cached++;
      } else {
        input += Tokens.estimate(item.rendering());
      }
    }
    int toAsk = items.size() + predictedHits - cached + predictedMisses;
    long write = 0;
    long read = 0;
    if (toAsk > 0 && model.supports(Capability.PROMPT_CACHING) && prefixTokens > 0) {
      write = prefixTokens;
      read = prefixTokens * (toAsk - 1L);
      input = Math.max(0, input - prefixTokens * toAsk);
    }
    long output = (long) toAsk * spec.expectedOutputTokens();
    BigDecimal usd = model.pricing().cost(new TokenUsage(input, output, write, read));
    Estimate estimate =
        new Estimate(
            items.size() + predictedHits + predictedMisses,
            cached,
            toAsk,
            input,
            write,
            read,
            output,
            usd,
            share);
    record.planned(estimate);
    LOG.info("Plan {}", estimate.line(spec.name()));
    return estimate;
  }

  /** Plans items whose inputs are all known now. */
  public Estimate plan(Collection<Item> items, long prefixTokens) {
    return plan(items, 0, 0, 0, prefixTokens);
  }

  /** Whether the cache holds the item's answer, revalidated. */
  public boolean cached(Item item) {
    Optional<StoredAnswer> stored = harness.answers().find(fingerprint(item));
    return stored.isPresent() && revalidated(stored.get());
  }

  /**
   * Answers an item: from the cache when it holds the item's fingerprint and every file the answer
   * read still has its blob; otherwise, within the role's budget share, by asking the model.
   */
  public Outcome ask(Item item, Asker asker) {
    return ask(item, asker, stored -> true);
  }

  /**
   * As {@link #ask(Item, Asker)}, reusing a cached answer only while the role's own check of it
   * holds too, such as that the facts a tool showed it still hold.
   */
  public Outcome ask(Item item, Asker asker, java.util.function.Predicate<StoredAnswer> holds) {
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(asker, "asker");
    String fingerprint = fingerprint(item);
    Optional<StoredAnswer> stored = harness.answers().find(fingerprint);
    if (stored.isPresent() && revalidated(stored.get()) && holds.test(stored.get())) {
      Outcome outcome =
          stored.get().omitted()
              ? Outcome.of(Outcome.Kind.CACHED, fingerprint, null, "omitted")
              : Outcome.of(Outcome.Kind.CACHED, fingerprint, stored.get().answer(), null);
      record.outcome(item.subject(), outcome, false);
      return outcome;
    }
    if (!withinShare(item)) {
      Outcome outcome =
          Outcome.of(
              Outcome.Kind.NOT_ASKED,
              fingerprint,
              null,
              "the " + spec.budgetGroup() + " share of the run budget is spent");
      LOG.warn("Not asked about {} for {}: {}", item.subject(), spec.name(), outcome.reason());
      record.outcome(item.subject(), outcome, false);
      return outcome;
    }
    Calls calls = new Calls(this, client);
    Outcome outcome;
    try {
      Answer answer = asker.ask(calls);
      save(item, answer.json(), answer.filesRead());
      outcome =
          answer.json() == null
              ? Outcome.of(Outcome.Kind.OMITTED, fingerprint, null, "omitted")
              : Outcome.of(Outcome.Kind.ANSWERED, fingerprint, answer.json(), null);
    } catch (ItemDropped e) {
      outcome = Outcome.of(Outcome.Kind.DROPPED, fingerprint, null, e.getMessage());
    } catch (BudgetExceededException e) {
      outcome = Outcome.of(Outcome.Kind.NOT_ASKED, fingerprint, null, e.getMessage());
      LOG.warn("Not asked about {} for {}: {}", item.subject(), spec.name(), e.getMessage());
    } catch (LlmException e) {
      outcome = Outcome.of(Outcome.Kind.FAILED, fingerprint, null, e.getMessage());
    }
    record.outcome(item.subject(), outcome, calls.repaired());
    return outcome;
  }

  /**
   * Answers items that share requests (knowledge-build epic, section 10): each from the cache when
   * it holds the item's fingerprint; the misses within the role's budget share packed into requests
   * of at most the role's packet size. Every item's entry goes through the standard pipeline on its
   * own, so one bad item keeps the others; all rejections of a request go back in one repair turn,
   * and what fails again is dropped. An item missing from an answer is asked again alone, once;
   * missing again, it is not asked.
   *
   * @return the outcome of each item, by subject, in the order given
   */
  public Map<String, Outcome> askPackets(List<Item> items, PacketRole role) {
    Map<String, Outcome> outcomes = new LinkedHashMap<>();
    items.forEach(item -> outcomes.put(item.subject(), null));
    List<Item> misses = new ArrayList<>();
    for (Item item : items) {
      String fingerprint = fingerprint(item);
      Optional<StoredAnswer> stored = harness.answers().find(fingerprint);
      if (stored.isPresent() && revalidated(stored.get())) {
        settle(
            outcomes,
            item,
            stored.get().omitted()
                ? Outcome.of(Outcome.Kind.CACHED, fingerprint, null, "omitted")
                : Outcome.of(Outcome.Kind.CACHED, fingerprint, stored.get().answer(), null),
            false);
      } else if (!withinShare(item)) {
        settle(
            outcomes,
            item,
            Outcome.of(
                Outcome.Kind.NOT_ASKED,
                fingerprint,
                null,
                "the " + spec.budgetGroup() + " share of the run budget is spent"),
            false);
      } else {
        misses.add(item);
      }
    }
    List<List<Item>> packets = Packer.pack(misses, spec.packetSize(), this::fingerprint);
    List<Item> missing = java.util.Collections.synchronizedList(new ArrayList<>());
    run(packets, packet -> missing.addAll(askPacket(packet, role, outcomes)));
    List<List<Item>> alone = missing.stream().map(List::of).toList();
    List<Item> missingTwice = java.util.Collections.synchronizedList(new ArrayList<>());
    run(alone, packet -> missingTwice.addAll(askPacket(packet, role, outcomes)));
    for (Item item : missingTwice) {
      settle(
          outcomes,
          item,
          Outcome.of(
              Outcome.Kind.NOT_ASKED, fingerprint(item), null, "missing from the answer twice"),
          false);
    }
    return outcomes;
  }

  /** Runs the packets: the first alone, the rest in parallel once it is done. */
  private void run(List<List<Item>> packets, java.util.function.Consumer<List<Item>> ask) {
    if (packets.isEmpty()) {
      return;
    }
    ask.accept(packets.getFirst());
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (List<Item> packet : packets.subList(1, packets.size())) {
        futures.add(executor.submit(() -> ask.accept(packet)));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Interrupted while asking " + spec.name(), e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    }
  }

  /** Asks one packet and settles its items; returns the items missing from the answer. */
  private List<Item> askPacket(List<Item> packet, PacketRole role, Map<String, Outcome> outcomes) {
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= packet.size(); i++) {
      ids.add("I" + i);
    }
    LlmRequest request =
        role.layout(packet, ids)
            .request(
                spec.modelRole(),
                spec.name() + " " + packet.size() + " items: " + packet.getFirst().subject(),
                role.maxOutputTokens(packet.size()),
                List.of());
    CheckInputs inputs = role.inputs(packet, ids).withSecrets(harness.secrets());
    List<Item> missing = new ArrayList<>();
    try {
      LlmResponse first = send(client, request);
      Map<String, ItemAnswer> accepted = new LinkedHashMap<>();
      Map<String, List<Rejection>> rejected = new LinkedHashMap<>();
      Optional<Map<String, JsonNode>> entries = Envelope.entries(first.text());
      for (int i = 0; i < packet.size(); i++) {
        String id = ids.get(i);
        if (entries.isEmpty()) {
          rejected.put(
              id,
              List.of(
                  new Rejection(
                      id,
                      "",
                      "Schema",
                      "The answer is not one JSON object with an entry per item.")));
          continue;
        }
        JsonNode entry = entries.get().get(id);
        if (entry == null) {
          missing.add(packet.get(i));
          continue;
        }
        check(id, packet.get(i), entry, role, inputs, accepted, rejected);
      }
      Map<String, Boolean> repairedIds = new LinkedHashMap<>();
      if (!rejected.isEmpty()) {
        List<Rejection> all = rejected.values().stream().flatMap(List::stream).toList();
        List<LlmMessage> turns = new ArrayList<>(request.messages());
        turns.add(LlmMessage.assistant(first.text().isBlank() ? "{}" : first.text()));
        turns.add(LlmMessage.user(Pipeline.repairTurn(all)));
        LlmResponse second =
            send(client, request.withMessages(turns).withPurpose(request.purpose() + " (repair)"));
        Map<String, JsonNode> corrected = Envelope.entries(second.text()).orElse(Map.of());
        for (String id : List.copyOf(rejected.keySet())) {
          Item item = packet.get(ids.indexOf(id));
          JsonNode entry = corrected.get(id);
          Map<String, List<Rejection>> again = new LinkedHashMap<>();
          if (entry != null) {
            check(id, item, entry, role, inputs, accepted, again);
          }
          if (entry == null || again.containsKey(id)) {
            settle(
                outcomes,
                item,
                Outcome.of(
                    Outcome.Kind.DROPPED,
                    fingerprint(item),
                    null,
                    entry == null ? "rejected and not corrected" : "rejected twice"),
                false);
          } else {
            repairedIds.put(id, true);
          }
        }
      }
      accepted.forEach(
          (id, answer) -> {
            Item item = packet.get(ids.indexOf(id));
            String json = answer.omitted() ? null : role.store(item, answer);
            save(item, json, List.of());
            String fingerprint = fingerprint(item);
            settle(
                outcomes,
                item,
                json == null
                    ? Outcome.of(Outcome.Kind.OMITTED, fingerprint, null, "omitted")
                    : Outcome.of(Outcome.Kind.ANSWERED, fingerprint, json, null),
                repairedIds.containsKey(id));
          });
    } catch (BudgetExceededException e) {
      packet.forEach(
          item ->
              settle(
                  outcomes,
                  item,
                  Outcome.of(Outcome.Kind.NOT_ASKED, fingerprint(item), null, e.getMessage()),
                  false));
      return List.of();
    } catch (LlmException e) {
      packet.forEach(
          item ->
              settle(
                  outcomes,
                  item,
                  Outcome.of(Outcome.Kind.FAILED, fingerprint(item), null, e.getMessage()),
                  false));
      return List.of();
    }
    return missing;
  }

  private static void check(
      String id,
      Item item,
      JsonNode entry,
      PacketRole role,
      CheckInputs inputs,
      Map<String, ItemAnswer> accepted,
      Map<String, List<Rejection>> rejected) {
    ItemAnswer answer = role.read(id, item, entry);
    List<Rejection> found = answer.omitted() ? List.of() : Pipeline.STANDARD.check(answer, inputs);
    if (found.isEmpty()) {
      accepted.put(id, answer);
    } else {
      rejected.put(id, found);
    }
  }

  /**
   * Answers items in one agent conversation (knowledge-build epic, section 10, Agent loop): the
   * model explores with the role's read-only tools and submits through the {@code submit} tool,
   * which verifies each item at once. The conversation keeps the role's caps; at a cap it ends and
   * what was submitted and accepted is kept. An item the model never submitted counts as omitted,
   * so a warm rerun does not ask it again; one it submitted wrongly twice is dropped. Items the
   * cache holds are not asked.
   *
   * @param tools the role's read-only tools
   * @param filesRead the files the tools read during the conversation, with their blobs, recorded
   *     with each answer
   */
  public Map<String, Outcome> converse(
      List<Item> items,
      PacketRole role,
      io.lantern.llm.ToolBox tools,
      java.util.function.Supplier<List<FileRead>> filesRead) {
    Map<String, Outcome> outcomes = new LinkedHashMap<>();
    List<Item> packet = new ArrayList<>();
    for (Item item : items) {
      String fingerprint = fingerprint(item);
      Optional<StoredAnswer> stored = harness.answers().find(fingerprint);
      if (stored.isPresent() && revalidated(stored.get())) {
        settle(
            outcomes,
            item,
            stored.get().omitted()
                ? Outcome.of(Outcome.Kind.CACHED, fingerprint, null, "omitted")
                : Outcome.of(Outcome.Kind.CACHED, fingerprint, stored.get().answer(), null),
            false);
      } else if (!withinShare(item)) {
        settle(
            outcomes,
            item,
            Outcome.of(
                Outcome.Kind.NOT_ASKED,
                fingerprint,
                null,
                "the " + spec.budgetGroup() + " share of the run budget is spent"),
            false);
      } else {
        packet.add(item);
      }
    }
    if (packet.isEmpty()) {
      return outcomes;
    }
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= packet.size(); i++) {
      ids.add("I" + i);
    }
    SubmitTool submit =
        new SubmitTool(
            tools, packet, ids, role, role.inputs(packet, ids).withSecrets(harness.secrets()));
    LlmRequest request =
        role.layout(packet, ids)
            .request(
                spec.modelRole(),
                spec.name() + " conversation: " + packet.getFirst().subject(),
                role.maxOutputTokens(packet.size()),
                submit.tools());
    io.lantern.llm.Conversation conversation = client.converse(request, submit, spec.limits());
    LlmException failure = null;
    try {
      conversation.answer();
      conversation.end(io.lantern.llm.LlmConversation.Ending.ANSWERED);
    } catch (io.lantern.llm.ConversationCappedException e) {
      LOG.info("{} kept what was submitted before: {}", request.purpose(), e.getMessage());
    } catch (BudgetExceededException e) {
      failure = e;
    } catch (LlmException e) {
      failure = e;
    }
    Map<String, ItemAnswer> accepted = submit.accepted();
    List<FileRead> read = filesRead.get();
    for (int i = 0; i < packet.size(); i++) {
      Item item = packet.get(i);
      String id = ids.get(i);
      String fingerprint = fingerprint(item);
      ItemAnswer answer = accepted.get(id);
      if (answer != null) {
        String json = answer.omitted() ? null : role.store(item, answer);
        save(item, json, read);
        settle(
            outcomes,
            item,
            json == null
                ? Outcome.of(Outcome.Kind.OMITTED, fingerprint, null, "omitted")
                : Outcome.of(Outcome.Kind.ANSWERED, fingerprint, json, null),
            submit.repaired(id));
      } else if (submit.dropped().contains(id)) {
        settle(
            outcomes,
            item,
            Outcome.of(Outcome.Kind.DROPPED, fingerprint, null, "rejected twice"),
            false);
      } else if (failure instanceof BudgetExceededException) {
        settle(
            outcomes,
            item,
            Outcome.of(Outcome.Kind.NOT_ASKED, fingerprint, null, failure.getMessage()),
            false);
      } else if (failure != null) {
        settle(
            outcomes,
            item,
            Outcome.of(Outcome.Kind.FAILED, fingerprint, null, failure.getMessage()),
            false);
      } else {
        // A conversation that ends without submitting an item submitted nothing for it.
        save(item, null, read);
        settle(
            outcomes,
            item,
            Outcome.of(Outcome.Kind.OMITTED, fingerprint, null, "not submitted"),
            false);
      }
    }
    return outcomes;
  }

  /** Stores an accepted answer under the item's fingerprint. */
  void save(Item item, String json, List<FileRead> filesRead) {
    harness
        .answers()
        .save(
            new StoredAnswer(
                fingerprint(item),
                spec.name(),
                item.subject(),
                spec.promptVersion(),
                model.provider(),
                model.model(),
                json == null,
                json,
                filesRead,
                item.inputs()));
  }

  private synchronized void settle(
      Map<String, Outcome> outcomes, Item item, Outcome outcome, boolean repaired) {
    outcomes.put(item.subject(), outcome);
    record.outcome(item.subject(), outcome, repaired);
  }

  /** The answer stored last for the subject by this role, whatever its fingerprint. */
  public Optional<StoredAnswer> latest(String subject) {
    return harness.answers().latest(spec.name(), subject);
  }

  /** Whether every file the answer's tools read still has the blob it read. */
  private boolean revalidated(StoredAnswer answer) {
    for (FileRead file : answer.filesRead()) {
      Optional<String> now = harness.blobs().blob(file.repo(), file.path());
      if (now.isEmpty() || !now.get().equals(file.blob())) {
        return false;
      }
    }
    return true;
  }

  /** Adds the item's estimated cost to the plan if it fits the role's share. */
  private synchronized boolean withinShare(Item item) {
    if (share == null) {
      return true;
    }
    BigDecimal cost =
        model
            .pricing()
            .cost(
                new TokenUsage(
                    Tokens.estimate(item.rendering()), spec.expectedOutputTokens(), 0, 0));
    if (planned.add(cost).compareTo(share) > 0) {
      return false;
    }
    planned = planned.add(cost);
    return true;
  }

  /** Sends a request of one of the role's items. */
  LlmResponse send(LlmClient client, LlmRequest request) {
    return client.call(request);
  }

  /** The role's run record. */
  public RoleRecord record() {
    return record;
  }
}
