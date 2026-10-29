package io.lantern.extract.mail;

import io.lantern.core.fact.Evidence;
import io.lantern.core.fact.Fact;
import io.lantern.core.fact.FactJson;
import io.lantern.core.fact.FactObject;
import io.lantern.core.fact.Origin;
import io.lantern.core.fact.Validity;
import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.RepoBuild;
import io.lantern.extract.java.Expression;
import io.lantern.extract.java.JavaAnalysis;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.java.ModuleStructure.Invocation;
import io.lantern.extract.java.ModuleStructure.Location;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns the code that sends mail into facts: the methods that call {@code send} on a Spring {@code
 * JavaMailSender} or {@code MailSender}, or on Jakarta Mail's {@code Transport} (ADR 0076). The
 * mail server comes from the config, which the linker reads, so a fact names no host.
 */
public final class MailExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "mail";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "1";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "mail-extractor";

  /** Attribute: the method sends mail. */
  public static final String MAIL_SEND = "mail-send";

  private static final String RESOURCE = "/vocabulary/mail-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  /** The types whose {@code send} sends mail. */
  private static final Set<String> SENDERS =
      Set.of(
          "org.springframework.mail.MailSender",
          "org.springframework.mail.javamail.JavaMailSender",
          "org.springframework.mail.javamail.JavaMailSenderImpl",
          "jakarta.mail.Transport",
          "javax.mail.Transport");

  private static final Set<String> METHODS = Set.of("send", "sendMessage");

  /** The vocabulary types this extractor's facts use. */
  public static List<VocabularyType> vocabulary() {
    try (InputStream in = MailExtractor.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE);
      }
      JsonNode array = FactJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      List<VocabularyType> types = new ArrayList<>();
      for (JsonNode node : array) {
        types.add(VocabularyType.fromJson(node));
      }
      return List.copyOf(types);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }

  /**
   * Extracts the facts of a snapshot's repositories, reading their code first.
   *
   * @param repos the repositories of the snapshot, each at its commit with its build, if any
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate and ID
   */
  public List<Fact> extract(List<RepoBuild> repos, long snapshot) {
    return extract(JavaExtractor.analyze(repos), snapshot);
  }

  /**
   * Extracts the facts of a snapshot's code already read by {@link JavaExtractor#analyze}.
   *
   * @param code the code of the snapshot's repositories
   * @param snapshot the snapshot number the facts hold from
   * @return the facts, without duplicates, ordered by subject, predicate and ID
   */
  public List<Fact> extract(JavaAnalysis code, long snapshot) {
    Objects.requireNonNull(code, "code");
    Validity validity = Validity.from(snapshot);
    Map<String, Fact> facts = new LinkedHashMap<>();
    for (JavaAnalysis.Module module : code.modules()) {
      for (Invocation i : module.structure().invocations()) {
        String type = i.rootType();
        Expression.Invoke call = i.expression().chain().getFirst();
        if (type == null
            || !SENDERS.contains(type)
            || !METHODS.contains(call.method())
            || call.receiver() == null) {
          continue;
        }
        Location where = i.where();
        RepoBuild repo = module.repo();
        Evidence evidence =
            new Evidence(
                repo.repo().name(),
                repo.commit(),
                where.file().path(),
                where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
                where.file().contentHash());
        ObjectNode attributes = FactJson.newObject();
        attributes.put("template", type);
        attributes.put("method", call.method());
        Fact fact =
            Fact.create(
                i.caller(),
                MAIL_SEND,
                FactObject.string(call.method()),
                attributes,
                ORIGIN,
                evidence,
                module.confidence(),
                validity);
        facts.putIfAbsent(fact.id(), fact);
      }
    }
    return facts.values().stream()
        .sorted(
            Comparator.comparing((Fact f) -> f.subject().toString())
                .thenComparing(Fact::predicate)
                .thenComparing(Fact::id))
        .toList();
  }
}
