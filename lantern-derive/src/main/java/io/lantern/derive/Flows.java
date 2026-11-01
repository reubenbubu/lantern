package io.lantern.derive;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.derive.FlowModel.Flow;
import io.lantern.derive.FlowModel.Operand;
import io.lantern.derive.FlowModel.Part;
import io.lantern.derive.FlowModel.Step;
import io.lantern.link.LinkRule;
import io.lantern.link.RuleInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every candidate flow of the architecture model, with its steps (#618; knowledge-build epic,
 * section 9): traced from each entry point whose code reaches outside its unit ({@link
 * FlowTracer}), with no cap on how many, and stored so documents and the flow roles read the stored
 * flows instead of tracing them again.
 *
 * <ul>
 *   <li>{@code flow:<entry point's claim>} {@code TRIGGERED_BY} the entry point's method, with how
 *       the flow starts (in a browser, at a gateway, or at the entry point);
 *   <li>its {@code flow-shape}: a hash of how it starts and the ordered (from, to, kind, link) of
 *       its messages, with its {@code content} (a hash of its steps' facts, so a judgement of the
 *       flow is stale when it differs), whether the 40-message bound {@code truncated} it, and the
 *       participants it reaches;
 *   <li>{@code SAME_SHAPE_AS} the flow of its shape whose entry point's claim sorts first;
 *   <li>each step, {@code flow-step:<flow's key>/<claim of the fact it follows>/<k>} {@code
 *       STEP_OF} its flow, k counting repeats of that fact in the flow, so a step inserted renames
 *       no other (#615); with its order, action, ends, kind and Spring profiles, and the frame
 *       operand it sits in.
 * </ul>
 *
 * Every fact cites the facts it rests on; a flow's all have the evidence of its entry point, so a
 * flow is shown whole by the documents of its entry point's repository.
 */
public final class Flows implements LinkRule.OverFacts {

  public static final String NAME = "flows";

  /** Raised whenever the flows it gives for the same facts change. */
  public static final String VERSION = "1";

  public static final String FLOW = "flow";
  public static final String FLOW_STEP = "flow-step";
  public static final String TRIGGERED_BY = "TRIGGERED_BY";
  public static final String FLOW_SHAPE = "flow-shape";
  public static final String SAME_SHAPE_AS = "SAME_SHAPE_AS";
  public static final String STEP_OF = "STEP_OF";

  /** The most messages one flow has before the trace stops. */
  public static final int MAX_MESSAGES = FlowTracer.MAX_MESSAGES;

  /** The most steps, notes included, one flow has before the trace stops. */
  public static final int MAX_STEPS = FlowTracer.MAX_STEPS;

  /** The most implementations a call is traced into, as the operands of one frame. */
  public static final int MAX_ALTERNATIVES = FlowTracer.MAX_ALTERNATIVES;

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  @Override
  public RuleInfo info() {
    return new RuleInfo(NAME, VERSION, Origin.Kind.STATIC);
  }

  @Override
  public Set<String> reads() {
    return Set.of(EXTRACTED, LINKED);
  }

  @Override
  public Set<String> writes() {
    return Set.of(TRIGGERED_BY, FLOW_SHAPE, SAME_SHAPE_AS, STEP_OF);
  }

  /** The vocabulary types of the flows' facts besides the built-in ones. */
  public static List<VocabularyType> vocabulary() {
    return Derivation.load("/vocabulary/flows-types.json");
  }

  /** A claim's ID, or, for a fact not stored yet, the fact's. */
  private static String claim(Fact fact) {
    return fact.claimId() == null ? fact.id() : fact.claimId();
  }

  /**
   * The flow traced from a unit's entry point: {@code flow:<unit>/<the claim of its trigger fact>}.
   * The unit is part of it, since a library's entry point runs in each unit that bundles it.
   */
  public static EntityId id(EntityId unit, Fact trigger) {
    return new EntityId(FLOW, unit + "/" + claim(trigger));
  }

  @Override
  public List<Fact> derive(List<Fact> facts, long snapshot) {
    Knowledge knowledge = new Knowledge(facts);
    List<Flow> flows = new FlowTracer(knowledge).trace(Flows::id);
    Validity validity = Validity.from(snapshot);
    // The flow of each shape whose entry point's claim sorts first stands for the others.
    Map<String, Flow> first = new TreeMap<>();
    Map<Flow, String> shapes = new HashMap<>();
    for (Flow flow : flows) {
      String shape = shape(flow);
      shapes.put(flow, shape);
      first.merge(shape, flow, (a, b) -> a.id().key().compareTo(b.id().key()) <= 0 ? a : b);
    }
    List<Fact> derived = new ArrayList<>();
    for (Flow flow : flows) {
      new Writer(flow, validity, derived).write(shapes.get(flow), first.get(shapes.get(flow)));
    }
    return derived;
  }

  /** Writes one flow's facts. */
  private static final class Writer {
    final Flow flow;
    final Validity validity;
    final List<Fact> out;
    final Map<String, Integer> repeats = new HashMap<>();
    int order;

    Writer(Flow flow, Validity validity, List<Fact> out) {
      this.flow = flow;
      this.validity = validity;
      this.out = out;
    }

    void write(String shape, Flow representative) {
      Fact trigger = flow.trigger();
      ObjectNode attributes = FactJson.newObject();
      attributes.put("unit", flow.unit().toString());
      attributes.put("trigger", trigger.id());
      List<Fact> cited = new ArrayList<>(List.of(trigger));
      if (flow.start() != null) {
        FlowModel.Start start = flow.start();
        attributes.put("start", "browser");
        if (start.person() != null) {
          attributes.put("person", start.person().toString());
        }
        attributes.put("frontend", start.frontend().toString());
        attributes.put("link", start.link().id());
        cited.add(start.link());
        if (start.via() != null) {
          attributes.put("through", start.via().toString());
        }
        if (start.route() != null) {
          attributes.put("route", start.route().id());
          cited.add(start.route());
        }
      } else if (flow.gateway() != null) {
        FlowModel.GatewayStart gateway = flow.gateway();
        attributes.put("start", "gateway");
        attributes.put("gateway", gateway.gateway().toString());
        attributes.put("routed", gateway.routed().id());
        cited.add(gateway.routed());
        if (gateway.route() != null) {
          attributes.put("route", gateway.route().id());
          cited.add(gateway.route());
        }
      }
      cite(attributes, cited);
      add(flow.id(), TRIGGERED_BY, FactObject.entity(flow.method()), attributes);

      ObjectNode described = FactJson.newObject();
      described.put("content", content(flow));
      described.put("truncated", flow.truncated());
      strings(described, "containers", flow.containers());
      strings(described, "asyncDestinations", flow.asyncDestinations());
      ArrayNode jobs = described.putArray("asyncJobs");
      flow.asyncJobs().forEach(j -> jobs.add(j.id()));
      strings(described, "reaches", flow.reaches());
      strings(described, "unresolved", flow.unresolved());
      described.put("pushes", flow.pushes());
      described.put("messages", flow.messages());
      cite(described, List.of(trigger));
      add(flow.id(), FLOW_SHAPE, FactObject.string(shape), described);

      if (representative != flow) {
        ObjectNode same = FactJson.newObject();
        cite(same, List.of(trigger, representative.trigger()));
        add(flow.id(), SAME_SHAPE_AS, FactObject.entity(representative.id()), same);
      }
      steps(flow.steps(), null, 0);
    }

    private void steps(List<Step> steps, EntityId frame, int operand) {
      for (Step step : steps) {
        EntityId id = stepId(step);
        ObjectNode attributes = FactJson.newObject();
        attributes.put("order", order++);
        attributes.put("action", step.action());
        attributes.put("kind", step.kind());
        if (step.from() != null) {
          attributes.put("from", step.from().toString());
        }
        if (step.to() != null) {
          attributes.put("to", step.to().toString());
        }
        if (!step.profiles().isEmpty()) {
          strings(attributes, "condition", step.profiles());
        }
        if (!step.over().isEmpty()) {
          strings(attributes, "over", step.over());
        }
        if (!step.detail().isEmpty()) {
          strings(attributes, "detail", step.detail());
        }
        if (!step.parts().isEmpty()) {
          ArrayNode parts = attributes.putArray("parts");
          for (Part part : step.parts()) {
            ObjectNode node = parts.addObject();
            node.put("verb", part.verb());
            strings(node, "what", part.what());
          }
        }
        if (frame != null) {
          attributes.put("fragment", frame.toString());
          attributes.put("operand", operand);
        }
        cite(attributes, step.facts());
        add(id, STEP_OF, FactObject.entity(flow.id()), attributes);
        for (int i = 0; i < step.operands().size(); i++) {
          steps(step.operands().get(i).steps(), id, i);
        }
      }
    }

    /** {@code flow-step:<flow's key>/<claim of the fact it follows, or none>/<k>}. */
    private EntityId stepId(Step step) {
      String follows = step.facts().isEmpty() ? "none" : claim(step.facts().getFirst());
      int k = repeats.merge(follows, 1, Integer::sum) - 1;
      return new EntityId(FLOW_STEP, flow.id().key() + "/" + follows + "/" + k);
    }

    private void add(EntityId subject, String predicate, FactObject object, ObjectNode attrs) {
      out.add(
          Fact.create(
              subject, predicate, object, attrs, ORIGIN, flow.trigger().evidence(), 1.0, validity));
    }
  }

  /**
   * A hash of how the flow starts (its trigger's kind, and a browser or gateway in front of it) and
   * the ordered (from, to, kind, link) of its messages, the link being the claim of the fact each
   * follows (#615): flows with the same participants but other endpoints or messages differ.
   */
  static String shape(Flow flow) {
    List<String> parts = new ArrayList<>();
    parts.add(
        flow.trigger().predicate()
            + (flow.start() != null ? " browser" : flow.gateway() != null ? " gateway" : ""));
    messages(flow.steps(), parts);
    return hash(parts);
  }

  private static void messages(List<Step> steps, List<String> parts) {
    for (Step step : steps) {
      if (step.message()) {
        parts.add(
            step.from()
                + " "
                + step.to()
                + " "
                + step.kind()
                + " "
                + (step.facts().isEmpty() ? "" : claim(step.facts().getFirst())));
      }
      for (Operand operand : step.operands()) {
        messages(operand.steps(), parts);
      }
    }
  }

  /** A hash of the flow's steps: each one's action and the claims of its facts, and truncation. */
  static String content(Flow flow) {
    List<String> parts = new ArrayList<>();
    content(flow.steps(), "", parts);
    parts.add("truncated " + flow.truncated());
    return hash(parts);
  }

  private static void content(List<Step> steps, String within, List<String> parts) {
    for (Step step : steps) {
      StringBuilder part = new StringBuilder(within).append(step.action());
      step.facts().forEach(f -> part.append(' ').append(claim(f)));
      step.parts().forEach(p -> part.append(' ').append(p.verb()).append(p.what()));
      part.append(' ').append(step.profiles());
      parts.add(part.toString());
      for (int i = 0; i < step.operands().size(); i++) {
        content(step.operands().get(i).steps(), within + i + ">", parts);
      }
    }
  }

  private static String hash(List<String> parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      // In base64url, so it is never read as a claim or fact ID, which are hex.
      return "sha256:"
          + java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  digest.digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void cite(ObjectNode attributes, Collection<Fact> facts) {
    ArrayNode ids = attributes.putArray("facts");
    facts.forEach(f -> ids.add(f.id()));
  }

  private static void strings(ObjectNode attributes, String name, Collection<?> values) {
    ArrayNode array = attributes.putArray(name);
    values.forEach(v -> array.add(v.toString()));
  }

  static EntityId parse(String id) {
    return FlowTracer.parse(id);
  }
}
