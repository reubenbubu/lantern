package io.lantern.link;

import io.lantern.core.fact.EntityId;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.mail.MailExtractor;
import io.lantern.link.Addresses.Answer;
import io.lantern.link.Addresses.Kind;
import io.lantern.link.FoundLinks.Found;
import io.lantern.link.LinkIndex.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links units to the mail server their config names (ADR 0076): the {@code spring.mail.host} a unit
 * sees in a view, filled in by the environment of a deployment container that runs it where config
 * leaves it to one, is an external system keyed by the host when it is a public domain ({@link
 * Addresses}), and each method of the unit that sends mail {@code SENDS_MAIL} through it over SMTP.
 * A unit whose config names a host but whose code sends no mail the extractor recognises has one
 * link without a source, since Spring Boot only sets up a {@code JavaMailSender} for a unit that
 * means to use it.
 */
final class MailLinker {

  static final String HOST = "spring.mail.host";
  private static final String PORT = "spring.mail.port";
  private static final String PROTOCOL = "spring.mail.protocol";

  private final LinkIndex index;
  private final FoundLinks out;
  private final Addresses addresses;

  MailLinker(LinkIndex index, FoundLinks out, Addresses addresses) {
    this.index = index;
    this.out = out;
    this.addresses = addresses;
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
    Fact host = host(unit, view);
    if (host == null) {
      return;
    }
    String written = LinkIndex.text(host, "written");
    List<Answer> answers =
        addresses.answers(
            unit,
            view,
            "smtp://" + host.object().value().trim(),
            written == null ? null : "smtp://" + written.trim());
    // Where a deployment sets the host, the config's own value is not what runs there: every
    // deployment must name the same system outside.
    List<Answer> filled = answers.stream().filter(a -> a.filledBy() != null).toList();
    List<Answer> deciding = filled.isEmpty() ? answers : filled;
    Answer answer = deciding.getFirst();
    if (deciding.stream()
        .anyMatch(
            a ->
                a.kind() != Kind.EXTERNAL
                    || !a.external().equals(deciding.getFirst().external()))) {
      return;
    }
    String name = answer.url().host();
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
      // Another library or a provider's SDK does not send to the server spring.mail.host names.
      if (MailExtractor.sendsThroughSpringMail(send)
          && index.unitsOf(send.subject()).contains(unit)
          && index.active(send.subject(), view)) {
        sends.add(send);
      }
    }
    Set<String> config = new TreeSet<>();
    config.add(host.id());
    if (answer.filledBy() != null) {
      config.add(answer.filledBy().fact().id());
    }
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

  /** The unit's {@code spring.mail.host} in the view, a placeholder nothing resolves kept. */
  private Fact host(Unit unit, String view) {
    String wanted = DataLinker.relaxed(HOST);
    for (String k : index.configKeys(unit, ConfigExtractor.CONFIG_VALUE)) {
      if (DataLinker.relaxed(k).equals(wanted)) {
        Fact f = index.config(unit, view, ConfigExtractor.CONFIG_VALUE, k);
        if (f != null && !LinkIndex.flag(f, "masked") && !f.object().value().isBlank()) {
          return f;
        }
      }
    }
    return null;
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
