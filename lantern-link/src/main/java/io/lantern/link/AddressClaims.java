package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.link.Addresses.Answer;
import io.lantern.link.Addresses.Kind;
import io.lantern.link.Addresses.Target;
import io.lantern.link.Deployments.Container;
import io.lantern.link.LinkIndex.Unit;
import io.lantern.link.attributes.ResolvesToAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where addresses lead, as address resolution's {@code RESOLVES_TO} claims say (#589): what a
 * matcher reads instead of deciding it ({@link AddressResolution}). Every address a matcher asks
 * about has its claims, since address resolution asks the same questions of the same facts; one
 * without them is a defect, not an address that leads nowhere.
 */
final class AddressClaims implements AddressAnswers {

  private final LinkIndex index;
  private final Map<List<String>, List<Fact>> answers = new HashMap<>();
  private final Map<List<String>, List<Fact>> names = new HashMap<>();
  private final Map<String, Container> containers = new HashMap<>();

  AddressClaims(LinkIndex index) {
    this.index = Objects.requireNonNull(index, "index");
    for (Fact f : index.of(ValueResolution.RESOLVES_TO)) {
      if (!f.origin().name().equals(AddressResolution.NAME)) {
        continue;
      }
      ResolvesToAttributes a = Claims.of(f, ResolvesToAttributes.class);
      if (AddressResolution.BY_NAME.equals(a.by())) {
        names.computeIfAbsent(nameKey(a.address(), a.profile()), k -> new ArrayList<>()).add(f);
      } else {
        answers
            .computeIfAbsent(
                key(a.from(), a.profile(), a.address(), a.written()), k -> new ArrayList<>())
            .add(f);
      }
    }
    new Deployments(index).all().forEach(c -> containers.put(c.fact().id(), c));
  }

  @Override
  public Answer resolve(Unit from, String view, String text, String written) {
    return Addresses.combined(answers(from, view, text, written));
  }

  @Override
  public List<Answer> answers(Unit from, String view, String text, String written) {
    List<Fact> claims =
        answers.get(key(from == null ? null : from.id().toString(), view, text, written));
    if (claims == null) {
      throw new IllegalStateException(
          "Address resolution did not answer " + text + " for " + from + " in view " + view);
    }
    return claims.stream()
        .sorted(Comparator.comparing(f -> Claims.of(f, ResolvesToAttributes.class).order()))
        .map(this::answer)
        .toList();
  }

  @Override
  public List<Target> byApplicationName(String name, String view, String matchedBy, double factor) {
    List<Target> targets = new ArrayList<>();
    for (Fact f : names.getOrDefault(nameKey(name, view), List.of())) {
      ResolvesToAttributes a = Claims.of(f, ResolvesToAttributes.class);
      targets.add(new Target(unit(f.object().entityId()), index.fact(a.fact()), matchedBy, factor));
    }
    // In the units' order, as the lookup gives them.
    targets.sort(Comparator.comparing(t -> t.unit().id(), LinkIndex.BY_TEXT));
    return targets;
  }

  private Answer answer(Fact f) {
    ResolvesToAttributes a = Claims.of(f, ResolvesToAttributes.class);
    EntityId object = f.object().entityId();
    Container filledBy = a.filledBy() == null ? null : containers.get(a.filledBy());
    boolean split = Boolean.TRUE.equals(a.split());
    LinkText.Url url = LinkText.parse(a.matched());
    if (object.equals(AddressResolution.AMBIGUOUS)) {
      List<Target> candidates = new ArrayList<>();
      for (ObjectNode c : a.candidates() == null ? List.<ObjectNode>of() : a.candidates()) {
        candidates.add(
            new Target(
                unit(EntityId.parse(c.path("unit").asString())),
                index.fact(c.path("fact").asString(null)),
                c.path("matchedBy").asString(),
                c.path("factor").asDouble()));
      }
      return new Answer(Kind.AMBIGUOUS, candidates, null, a.matched(), url, filledBy, split);
    }
    if (object.equals(AddressResolution.UNKNOWN)) {
      return new Answer(Kind.UNKNOWN, List.of(), null, a.matched(), url, filledBy, split);
    }
    if (object.type().equals(CrossRepoLinks.EXTERNAL_SYSTEM)) {
      return new Answer(Kind.EXTERNAL, List.of(), object, a.matched(), url, filledBy, split);
    }
    Target target =
        new Target(unit(object), index.fact(a.fact()), a.matchedBy(), a.factor().doubleValue());
    return new Answer(Kind.UNIT, List.of(target), null, a.matched(), url, filledBy, split);
  }

  private Unit unit(EntityId id) {
    Unit unit = index.units.get(id);
    if (unit == null) {
      throw new IllegalStateException("Address resolution names a unit not linked: " + id);
    }
    return unit;
  }

  private static List<String> key(String from, String view, String text, String written) {
    return java.util.Arrays.asList(from, view, text, written);
  }

  private static List<String> nameKey(String name, String view) {
    return java.util.Arrays.asList(name, view);
  }
}
