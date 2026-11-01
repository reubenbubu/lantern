package io.lantern.harness;

import io.lantern.llm.BudgetGuard;
import io.lantern.llm.LlmException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which planned items of one role group fit the group's share of the run budget, in rounds
 * (ADR 0100, #659). The group's items queue in a fixed order: each role's own order, roles in the
 * order they plan. A round admits items from the head of the queue while their estimates fit what
 * is left of the share; once every item of the round is settled, the next round admits from what
 * the group has really spent, until the first item that does not fit even then: it and every item
 * after it are not asked. A round is decided only when nothing of the group is in flight, so what
 * fits never depends on thread timing, and two runs at the same budget ask the same items.
 *
 * <p>A claim is admitted at once, outside the queue, if its estimate fits: the item a group gives
 * first claim on its share (the system's purpose), and an item no plan knew. Rounds do not wait for
 * claims; a claim counts at its estimate until it is settled. Thread-safe.
 */
final class ShareRounds {

  private static final Logger LOG = LoggerFactory.getLogger(ShareRounds.class);

  /**
   * A planned item of one of the group's roles, under the key its role keeps it by.
   *
   * @param item the item, or {@code null} for one whose input is not known yet
   * @param cost what it is expected to cost alone
   */
  record Entry(RoleSession session, String key, String subject, Item item, BigDecimal cost) {
    private String id() {
      return session.spec().name() + '\u0000' + key;
    }
  }

  private final String group;
  private final BigDecimal share;
  private final BudgetGuard guard;
  private final List<Entry> queue = new ArrayList<>();
  private int next;
  private final Map<String, Entry> round = new LinkedHashMap<>();
  private final Map<String, Entry> claims = new LinkedHashMap<>();
  private boolean started;
  private int rounds;

  ShareRounds(String group, BigDecimal share, BudgetGuard guard) {
    this.group = group;
    this.share = share;
    this.guard = guard;
  }

  /**
   * Queues planned items. While nothing of the group has been asked, or no round is open, the next
   * round takes them at once if they fit.
   */
  synchronized void queue(List<Entry> entries) {
    queue.addAll(entries);
    if (!started || round.isEmpty()) {
      decide();
    }
  }

  /**
   * Admits the item at once if its estimate fits what is left of the share.
   *
   * @return whether it is admitted
   */
  synchronized boolean claim(Entry entry) {
    if (base().add(roundCost()).add(entry.cost()).compareTo(share) > 0) {
      return false;
    }
    claims.put(entry.id(), entry);
    return true;
  }

  /** Notes that an item of the group may be in flight: rounds are no longer decided on planning. */
  synchronized void started() {
    started = true;
  }

  /**
   * Notes that an item has an outcome, or ended without one. Once every item of the round has, the
   * next round is decided. Settling an item twice, or one never admitted, changes nothing.
   */
  synchronized void settle(RoleSession session, String key) {
    String id = session.spec().name() + '\u0000' + key;
    claims.remove(id);
    if (round.remove(id) != null && round.isEmpty()) {
      decide();
    }
  }

  /** Waits until the item kept under the key is decided. */
  synchronized void await(RoleSession session, String key) {
    while (session.undecided(key)) {
      try {
        wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new LlmException("Interrupted while waiting for the " + group + " share", e);
      }
    }
  }

  /** What the group has spent, and expects its claims not settled yet to cost. */
  private BigDecimal base() {
    BigDecimal base = guard.spentCostUsd();
    for (Entry entry : claims.values()) {
      base = base.add(entry.cost());
    }
    return base;
  }

  /** What the round's items are expected to cost, each role's priced as it will ask them. */
  private BigDecimal roundCost() {
    BigDecimal cost = BigDecimal.ZERO;
    for (Map.Entry<RoleSession, List<Entry>> byRole : byRole().entrySet()) {
      cost = cost.add(byRole.getKey().roundCost(byRole.getValue()));
    }
    return cost;
  }

  private Map<RoleSession, List<Entry>> byRole() {
    Map<RoleSession, List<Entry>> byRole = new LinkedHashMap<>();
    round.values().forEach(e -> byRole.computeIfAbsent(e.session(), s -> new ArrayList<>()).add(e));
    return byRole;
  }

  /**
   * Admits items from the head of the queue while they fit. If none is admitted and none is out,
   * nothing will free room: the rest are not asked.
   */
  private void decide() {
    BigDecimal base = base();
    Map<RoleSession, List<Entry>> byRole = byRole();
    Map<RoleSession, BigDecimal> costs = new LinkedHashMap<>();
    BigDecimal committed = base;
    for (Map.Entry<RoleSession, List<Entry>> role : byRole.entrySet()) {
      BigDecimal cost = role.getKey().roundCost(role.getValue());
      costs.put(role.getKey(), cost);
      committed = committed.add(cost);
    }
    int admitted = 0;
    while (next < queue.size()) {
      Entry entry = queue.get(next);
      List<Entry> mine = new ArrayList<>(byRole.getOrDefault(entry.session(), List.of()));
      mine.add(entry);
      BigDecimal cost = entry.session().roundCost(mine);
      BigDecimal after =
          committed.subtract(costs.getOrDefault(entry.session(), BigDecimal.ZERO)).add(cost);
      if (after.compareTo(share) > 0) {
        break;
      }
      committed = after;
      byRole.put(entry.session(), mine);
      costs.put(entry.session(), cost);
      round.put(entry.id(), entry);
      next++;
      admitted++;
      entry.session().decided(entry, true);
    }
    if (admitted > 0) {
      rounds++;
      LOG.info(
          "Budget round {} of {}: {} items admitted, ${} of ${} spent or expected",
          rounds,
          group,
          admitted,
          committed.toPlainString(),
          share.toPlainString());
    }
    if (round.isEmpty() && next < queue.size()) {
      LOG.info(
          "The {} share is spent: {} items not asked, ${} of ${} spent",
          group,
          queue.size() - next,
          guard.spentCostUsd().toPlainString(),
          share.toPlainString());
      while (next < queue.size()) {
        Entry entry = queue.get(next++);
        entry.session().decided(entry, false);
      }
    }
    notifyAll();
  }
}
