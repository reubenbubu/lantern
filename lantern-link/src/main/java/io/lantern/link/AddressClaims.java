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
  private final Map<List<String>, List<Fact>> datasources = new HashMap<>();
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
      } else if (AddressResolution.BY_DATASOURCE.equals(a.by())) {
        datasources.computeIfAbsent(nameKey(a.from(), a.address()), k -> new ArrayList<>()).add(f);
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
    List<Fact> claims = names.get(nameKey(name, view));
    if (claims == null) {
      throw new IllegalStateException(
          "Address resolution did not look up the name " + name + " in view " + view);
    }
    List<Target> targets = new ArrayList<>();
    for (Fact f : claims) {
      if (f.object().entityId().equals(AddressResolution.UNKNOWN)) {
        continue; // asked, and no unit has the name
      }
      ResolvesToAttributes a = Claims.of(f, ResolvesToAttributes.class);
      targets.add(new Target(unit(f.object().entityId()), fact(a.fact()), matchedBy, factor));
    }
    // In the units' order, as the lookup gives them.
    targets.sort(Comparator.comparing(t -> t.unit().id(), LinkIndex.BY_TEXT));
    return targets;
  }

  @Override
  public List<Deployments.Placed> placed(Unit unit, Fact store, Deployments.Place place) {
    List<Fact> claims = datasources.get(nameKey(unit.id().toString(), store.object().value()));
    if (claims == null) {
      throw new IllegalStateException(
          "Address resolution did not place " + store.object().value() + " for " + unit);
    }
    return claims.stream()
        .filter(f -> Claims.of(f, ResolvesToAttributes.class).filledBy() != null) // none placing it
        .sorted(Comparator.comparing(f -> Claims.of(f, ResolvesToAttributes.class).order()))
        .map(
            f -> {
              ResolvesToAttributes a = Claims.of(f, ResolvesToAttributes.class);
              return new Deployments.Placed(
                  container(a.filledBy()),
                  a.fact() == null ? null : container(a.fact()),
                  a.matched(),
                  a.database(),
                  Boolean.TRUE.equals(a.guessed()));
            })
        .toList();
  }

  private Container container(String id) {
    Container c = containers.get(id);
    if (c == null) {
      throw new IllegalStateException("Address resolution names a container not read: " + id);
    }
    return c;
  }

  private Answer answer(Fact f) {
    ResolvesToAttributes a = Claims.of(f, ResolvesToAttributes.class);
    EntityId object = f.object().entityId();
    Container filledBy = a.filledBy() == null ? null : containers.get(a.filledBy());
    LinkText.Url url = LinkText.parse(a.matched());
    if (object.equals(AddressResolution.AMBIGUOUS)) {
      List<Target> candidates = new ArrayList<>();
      for (Claims.Candidate c : Claims.candidates(a.candidates())) {
        candidates.add(
            new Target(unit(EntityId.parse(c.unit())), fact(c.fact()), c.matchedBy(), c.factor()));
      }
      return new Answer(Kind.AMBIGUOUS, candidates, null, a.matched(), url, filledBy, false);
    }
    if (object.equals(AddressResolution.UNKNOWN)) {
      return new Answer(Kind.UNKNOWN, List.of(), null, a.matched(), url, filledBy, false);
    }
    if (object.type().equals(CrossRepoLinks.EXTERNAL_SYSTEM)) {
      return new Answer(Kind.EXTERNAL, List.of(), object, a.matched(), url, filledBy, false);
    }
    Target target =
        new Target(unit(object), fact(a.fact()), a.matchedBy(), a.factor().doubleValue());
    return new Answer(Kind.UNIT, List.of(target), null, a.matched(), url, filledBy, false);
  }

  /** The fact an answer names by ID, or null when it names none. */
  private Fact fact(String id) {
    if (id == null) {
      return null;
    }
    Fact fact = index.fact(id);
    if (fact == null) {
      throw new IllegalStateException("Address resolution names a fact not read: " + id);
    }
    return fact;
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
