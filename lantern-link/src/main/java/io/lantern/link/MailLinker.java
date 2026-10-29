package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.extract.mail.MailExtractor;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links units to the mail server their config names (ADR 0076): the {@code spring.mail.host} a unit
 * sees in a view is an external system, keyed by the host, and each method of the unit that sends
 * mail {@code SENDS_MAIL} through it over SMTP. A unit whose config names a host but whose code
 * sends no mail the extractor recognises has one link without a source, since Spring Boot only sets
 * up a {@code JavaMailSender} for a unit that means to use it.
 */
final class MailLinker {

  static final String HOST = "spring.mail.host";
  private static final String PORT = "spring.mail.port";
  private static final String PROTOCOL = "spring.mail.protocol";

  private final LinkIndex index;
  private final FoundLinks out;

  MailLinker(LinkIndex index, FoundLinks out) {
    this.index = index;
    this.out = out;
  }

  void link() {
    List<Fact> sends = index.of(MailExtractor.MAIL_SEND);
    for (String view : index.views()) {
      for (Unit unit : index.units.values()) {
        link(unit, view, sends);
      }
    }
  }

  private void link(Unit unit, String view, List<Fact> allSends) {
    List<Fact> hosts = DataLinker.values(index, unit, view, HOST);
    if (hosts.isEmpty()) {
      return;
    }
    Fact host = hosts.getFirst();
    String name = host.object().value().trim().toLowerCase(Locale.ROOT);
    // A name without a domain is a compose or Kubernetes service or this machine, not a system.
    if (!name.contains(".") || name.contains("${")) {
      return;
    }
    List<Fact> ports = DataLinker.values(index, unit, view, PORT);
    List<Fact> protocols = DataLinker.values(index, unit, view, PROTOCOL);
    String port = ports.isEmpty() ? null : ports.getFirst().object().value().trim();
    boolean secure =
        "465".equals(port)
            || (!protocols.isEmpty()
                && protocols.getFirst().object().value().trim().equalsIgnoreCase("smtps"));
    EntityId system = new EntityId(CrossRepoLinks.EXTERNAL_SYSTEM, name);

    List<Fact> sends = new ArrayList<>();
    for (Fact send : allSends) {
      if (index.unitsOf(send.subject()).contains(unit) && index.active(send.subject(), view)) {
        sends.add(send);
      }
    }
    Set<String> config = new TreeSet<>();
    config.add(host.id());
    ports.stream().limit(1).forEach(p -> config.add(p.id()));
    protocols.stream().limit(1).forEach(p -> config.add(p.id()));
    if (sends.isEmpty()) {
      add(unit, view, system, null, host, name, port, secure, config, host);
    }
    for (Fact send : sends) {
      Set<String> facts = new TreeSet<>(config);
      facts.add(send.id());
      add(unit, view, system, send, send, name, port, secure, facts, host);
    }
  }

  private void add(
      Unit unit,
      String view,
      EntityId system,
      Fact send,
      Fact evidence,
      String name,
      String port,
      boolean secure,
      Set<String> facts,
      Fact host) {
    ObjectNode attributes = FactJson.newObject();
    attributes.put("protocol", secure ? "SMTPS" : "SMTP");
    if (send != null) {
      attributes.put("source", send.subject().toString());
    }
    attributes.put("host", name);
    if (port != null) {
      attributes.put("port", port);
    }
    attributes.put("key", HOST);
    double confidence = Math.min(evidence.confidence(), host.confidence());
    out.add(
        view,
        new Found(
            CrossRepoLinks.SENDS_MAIL
                + "|"
                + unit.id()
                + "|"
                + system
                + "|"
                + (send == null ? "" : send.subject()),
            unit.id(),
            CrossRepoLinks.SENDS_MAIL,
            FactObject.entity(system),
            attributes,
            evidence.evidence(),
            CrossRepoLinks.round(confidence * unit.fact().confidence()),
            facts));
  }
}
