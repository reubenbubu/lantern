package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.extract.EntityKeys;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.link.Addresses.Answer;
import io.lantern.link.Addresses.Target;
import io.lantern.link.LinkIndex.Unit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Address resolution, the link rule after value resolution (epic {@code
 * docs/epics/001-knowledge-build.md}, section 8, rule 3; ADR 0042, amendment for #589): where each
 * address a matcher asks about leads, decided as {@link Addresses} decides it (filled in from the
 * deployments by value resolution, classified, matched in one order), written as {@code
 * RESOLVES_TO} claims the matchers read back ({@link AddressClaims}) instead of deciding it
 * themselves.
 *
 * <ul>
 *   <li>For an address, keyed by the unit that uses it ({@code from}), the view ({@code profile}),
 *       the address and the address as written: one claim for the config's answer and one for each
 *       deployment whose environment fills the address in ({@code deployment}), in order, to the
 *       unit it leads to, the external system, {@code outcome:ambiguous} with its candidates, or
 *       {@code outcome:unknown}.
 *   <li>For a service name ({@code by: name}): one claim to each unit whose {@code
 *       spring.application.name} in the view it is.
 * </ul>
 *
 * Each claim cites the fact the unit was found by and the container that filled the address in. The
 * matchers whose addresses it answers so far: HTTP calls (#589), gateway routes and frontends
 * (#594); identity providers and mail hosts, and data stores follow (#595, #596).
 */
public final class AddressResolution implements LinkRule {

  /** The rule's name, recorded as the origin of its facts. */
  public static final String NAME = "addresses";

  /** The rule's version. */
  public static final String VERSION = "1";

  /** The entity type of an answer that is no unit or external system. */
  static final String OUTCOME = "outcome";

  static final EntityId AMBIGUOUS = new EntityId(OUTCOME, "ambiguous");
  static final EntityId UNKNOWN = new EntityId(OUTCOME, "unknown");

  /** The value of {@code by} on a service name's claims. */
  static final String BY_NAME = "name";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  private static final Set<String> READS =
      Rules.union(
          Set.of(EXTRACTED),
          ValueResolution.WRITES,
          Set.of(
              DeployableUnits.DEPLOYABLE_UNIT,
              DeployableUnits.BUNDLES,
              DeployableUnits.APPLICATION_NAME));

  @Override
  public RuleInfo info() {
    return RuleInfo.of(ORIGIN);
  }

  @Override
  public Set<String> reads() {
    return READS;
  }

  @Override
  public Set<String> writes() {
    return Set.of(ValueResolution.RESOLVES_TO);
  }

  /** The questions the matchers ask, each once, in the order asked. */
  static final class Questions {

    record Address(Unit from, String view, String text, String written) {}

    record Name(String name, String view) {}

    /** Where a question was first asked: the fact asking it, and the config facts it used. */
    record Asked(Fact source, List<Fact> used) {}

    final Map<Address, Asked> addresses = new LinkedHashMap<>();
    final Map<Name, Asked> names = new LinkedHashMap<>();

    void address(
        Unit from, String view, String text, String written, Fact source, List<Fact> used) {
      addresses.putIfAbsent(new Address(from, view, text, written), new Asked(source, used));
    }

    void name(String name, String view, Fact source) {
      names.putIfAbsent(new Name(name, view), new Asked(source, List.of()));
    }
  }

  /** Answers as {@link Addresses} does, recording each question asked with the fact asking it. */
  private record Recorder(Addresses addresses, Questions questions) implements AddressAnswers {

    @Override
    public Answer resolve(Unit from, String view, String text, String written) {
      throw new IllegalStateException("A matcher asked about " + text + " for no fact");
    }

    @Override
    public List<Answer> answers(Unit from, String view, String text, String written) {
      throw new IllegalStateException("A matcher asked about " + text + " for no fact");
    }

    @Override
    public List<Target> byApplicationName(
        String name, String view, String matchedBy, double factor) {
      throw new IllegalStateException("A matcher looked up " + name + " for no fact");
    }

    @Override
    public Answer resolve(Fact asking, Unit from, String view, String text, String written) {
      questions.address(from, view, text, written, asking, List.of());
      return addresses.resolve(from, view, text, written);
    }

    @Override
    public List<Target> byApplicationName(
        Fact asking, String name, String view, String matchedBy, double factor) {
      questions.name(name, view, asking);
      return addresses.byApplicationName(name, view, matchedBy, factor);
    }
  }

  /** The questions the matchers ask of the facts an index holds, each with the fact asking it. */
  static Questions questions(LinkIndex index, Addresses addresses) {
    Questions questions = new Questions();
    HttpLinker.queries(index, questions);
    // Gateway routes and frontends ask as they link, a question on an earlier one's answer: run as
    // the matchers run them, recording what they ask (#594).
    Recorder recorder = new Recorder(addresses, questions);
    FoundLinks scratch = new FoundLinks();
    HttpLinker http = new HttpLinker(index, scratch, addresses);
    RouteLinker routes = new RouteLinker(index, scratch, recorder);
    routes.link();
    new FrontendLinker(index, scratch, recorder, new Deployments(index), http, routes)
        .link(ORIGIN, Validity.from(0));
    return questions;
  }

  @Override
  public void derive(ClaimQuery in, ClaimSink out, View view) {
    LinkIndex index = new LinkIndex(in.facts());
    Validity validity = Validity.from(in.snapshot());
    Addresses addresses = new Addresses(index, new Deployments(index));
    Questions questions = questions(index, addresses);
    Map<String, Fact> facts = new LinkedHashMap<>();
    questions.addresses.forEach(
        (q, asked) -> {
          List<Answer> answers = addresses.answers(q.from(), q.view(), q.text(), q.written());
          for (int i = 0; i < answers.size(); i++) {
            Fact f = answer(q, asked, answers.get(i), i, validity);
            facts.putIfAbsent(f.id(), f);
          }
        });
    questions.names.forEach(
        (q, asked) -> {
          List<Target> targets =
              addresses.byApplicationName(
                  q.name(), q.view(), "service-name", Addresses.BY_SERVICE_NAME);
          if (targets.isEmpty()) {
            // Asked, and no unit has the name: recorded, so a matcher tells it from not asked.
            Fact f = name(q, asked, null, validity);
            facts.putIfAbsent(f.id(), f);
          }
          for (Target t : targets) {
            Fact f = name(q, asked, t, validity);
            facts.putIfAbsent(f.id(), f);
          }
        });
    facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(f -> f.object().value())
                .thenComparing(Fact::id))
        .forEach(out::add);
  }

  /** The claim of one answer an address has. */
  private static Fact answer(
      Questions.Address q, Questions.Asked asked, Answer a, int order, Validity validity) {
    ObjectNode attributes = FactJson.newObject();
    if (q.from() != null) {
      attributes.put("from", q.from().id().toString());
    }
    if (q.view() != null) {
      attributes.put("profile", q.view());
    }
    attributes.put("address", q.text());
    if (q.written() != null) {
      attributes.put("written", q.written());
    }
    if (a.filledBy() != null) {
      attributes.put("deployment", Literals.view(a.filledBy()));
      attributes.put("filledBy", a.filledBy().fact().id());
    }
    attributes.put("order", order);
    attributes.put("matched", a.address());
    // Lineage: the call asking, the config that resolved its address, what the unit was found by
    // and the container that filled the address in.
    List<Fact> cited = new ArrayList<>();
    cited.add(asked.source());
    cited.addAll(asked.used());
    EntityId object;
    switch (a.kind()) {
      case UNIT -> {
        Target t = a.target();
        object = t.unit().id();
        attributes.put("matchedBy", t.matchedBy());
        attributes.put("factor", t.factor());
        if (t.fact() != null) {
          attributes.put("fact", t.fact().id());
          cited.add(t.fact());
        }
      }
      case EXTERNAL -> object = a.external();
      case AMBIGUOUS -> {
        object = AMBIGUOUS;
        ArrayNode candidates = attributes.putArray("candidates");
        for (Target t : a.targets()) {
          ObjectNode c = candidates.addObject();
          c.put("unit", t.unit().id().toString());
          if (t.fact() != null) {
            c.put("fact", t.fact().id());
            cited.add(t.fact());
          }
          c.put("matchedBy", t.matchedBy());
          c.put("factor", t.factor());
        }
      }
      default -> object = UNKNOWN;
    }
    if (a.filledBy() != null) {
      cited.add(a.filledBy().fact());
    }
    ArrayNode lineage = attributes.putArray("facts");
    cited.stream().map(Fact::id).distinct().sorted().forEach(lineage::add);
    // Seen where the address is used.
    Evidence evidence = asked.source().evidence();
    return Fact.create(
        subject(q.from(), q.text()),
        ValueResolution.RESOLVES_TO,
        FactObject.entity(object),
        attributes,
        ORIGIN,
        evidence,
        1.0,
        validity);
  }

  /** The claim of one unit a service name names, or of none ({@code t} null). */
  private static Fact name(Questions.Name q, Questions.Asked asked, Target t, Validity validity) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("by", BY_NAME);
    attributes.put("address", q.name());
    if (q.view() != null) {
      attributes.put("profile", q.view());
    }
    ArrayNode lineage = attributes.putArray("facts");
    lineage.add(asked.source().id());
    if (t != null) {
      attributes.put("fact", t.fact().id());
      lineage.add(t.fact().id());
    }
    return Fact.create(
        EntityKeys.of(HttpClientExtractor.ADDRESS, q.name().toLowerCase(Locale.ROOT)),
        ValueResolution.RESOLVES_TO,
        FactObject.entity(t == null ? UNKNOWN : t.unit().id()),
        attributes,
        ORIGIN,
        asked.source().evidence(),
        1.0,
        validity);
  }

  /**
   * The address entity a question is about: {@code address:<host>} for an address whose host is
   * written out, as the HTTP client extractor keys one, else the address as the unit using it sees
   * it, keyed by that unit. The latter is the extractor's entity only where the unit's key is its
   * module's and the URL is its own base; the claim's {@code from} and {@code address} say which
   * question it answers either way.
   */
  static EntityId subject(Unit from, String text) {
    LinkText.Url url = LinkText.parse(text);
    String host = url.host();
    if (host != null && !host.contains("$") && !host.contains("{")) {
      String authority = url.port() == null ? host : host + ":" + url.port();
      return EntityKeys.of(HttpClientExtractor.ADDRESS, authority.toLowerCase(Locale.ROOT));
    }
    String scope = from == null ? "frontend" : from.id().key();
    return EntityKeys.of(HttpClientExtractor.ADDRESS, scope + ":" + text);
  }
}
