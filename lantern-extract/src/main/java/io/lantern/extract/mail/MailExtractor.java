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
 * Turns the code that sends mail into facts (ADR 0076): the methods that send through a mail
 * library (Spring's {@code JavaMailSender} or {@code MailSender}, Jakarta Mail's {@code Transport},
 * Apache Commons Email, Simple Java Mail, Quarkus's and Micronaut's mailers) or a mail provider's
 * SDK (SendGrid, Amazon SES, Mailgun), and the mail server hosts that code sets. A library's server
 * usually comes from the config, which the linker reads; an SDK names its provider.
 */
public final class MailExtractor {

  /** The extractor's name, recorded as the origin of its facts. */
  public static final String NAME = "mail";

  /** The extractor's version; change it whenever the facts it produces change. */
  public static final String VERSION = "2";

  /** The source recorded on the vocabulary revision that adds this extractor's types. */
  public static final String VOCABULARY_SOURCE = "mail-extractor";

  /** Attribute: the method sends mail. */
  public static final String MAIL_SEND = "mail-send";

  /** The packages of the senders whose server Spring Boot's {@code spring.mail.*} config sets. */
  private static final List<String> SPRING_CONFIGURED =
      List.of("org.springframework.mail.", "jakarta.mail.", "javax.mail.");

  /**
   * Whether a {@link #MAIL_SEND} fact sends through Spring's or Jakarta Mail's API, and so to the
   * server {@code spring.mail.host} names, rather than through another library or a provider's SDK.
   */
  public static boolean sendsThroughSpringMail(Fact send) {
    String template =
        send.attributes().has("template") ? send.attributes().get("template").stringValue() : "";
    return SPRING_CONFIGURED.stream().anyMatch(template::startsWith);
  }

  /** Attribute: the method sets the mail server's host. */
  public static final String MAIL_HOST = "mail-host";

  private static final String RESOURCE = "/vocabulary/mail-types.json";

  private static final Origin ORIGIN = new Origin(Origin.Kind.STATIC, NAME, VERSION);

  /**
   * What sends mail: the methods of a type that do, and the provider the type names, or null for a
   * library that sends to whatever server the config gives it.
   */
  private record Sender(Set<String> methods, String provider) {}

  private static final Set<String> SEND = Set.of("send");

  /** The types that send mail, by binary name. */
  private static final Map<String, Sender> SENDERS = senders();

  /** The types, and their methods that set the mail server's host from their first argument. */
  private static final Map<String, Set<String>> HOST_SETTERS = hostSetters();

  private static Map<String, Sender> senders() {
    Map<String, Sender> senders = new LinkedHashMap<>();
    Sender library = new Sender(SEND, null);
    for (String type :
        List.of(
            "org.springframework.mail.MailSender",
            "org.springframework.mail.javamail.JavaMailSender",
            "org.springframework.mail.javamail.JavaMailSenderImpl",
            "io.quarkus.mailer.Mailer",
            "io.quarkus.mailer.reactive.ReactiveMailer",
            "io.micronaut.email.EmailSender")) {
      senders.put(type, library);
    }
    senders.put("io.micronaut.email.AsyncEmailSender", new Sender(Set.of("sendAsync"), null));
    Sender transport = new Sender(Set.of("send", "sendMessage"), null);
    senders.put("jakarta.mail.Transport", transport);
    senders.put("javax.mail.Transport", transport);
    for (String type : commonsEmailTypes()) {
      senders.put(type, library);
    }
    senders.put("org.simplejavamail.api.mailer.Mailer", new Sender(Set.of("sendMail"), null));
    Sender sendGrid = new Sender(Set.of("api"), "SendGrid");
    senders.put("com.sendgrid.SendGrid", sendGrid);
    senders.put("com.sendgrid.SendGridAPI", sendGrid);
    Sender ses =
        new Sender(
            Set.of(
                "sendEmail",
                "sendRawEmail",
                "sendTemplatedEmail",
                "sendBulkTemplatedEmail",
                "sendBulkEmail",
                "sendEmailAsync",
                "sendRawEmailAsync",
                "sendTemplatedEmailAsync",
                "sendBulkTemplatedEmailAsync",
                "sendBulkEmailAsync"),
            "Amazon SES");
    for (String type :
        List.of(
            "com.amazonaws.services.simpleemail.AmazonSimpleEmailService",
            "com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceAsync",
            "com.amazonaws.services.simpleemailv2.AmazonSimpleEmailServiceV2",
            "com.amazonaws.services.simpleemailv2.AmazonSimpleEmailServiceV2Async",
            "software.amazon.awssdk.services.ses.SesClient",
            "software.amazon.awssdk.services.ses.SesAsyncClient",
            "software.amazon.awssdk.services.sesv2.SesV2Client",
            "software.amazon.awssdk.services.sesv2.SesV2AsyncClient")) {
      senders.put(type, ses);
    }
    senders.put(
        "com.mailgun.api.v3.MailgunMessagesApi",
        new Sender(Set.of("sendMessage", "sendMessageAsync", "sendMIMEMessage"), "Mailgun"));
    return Map.copyOf(senders);
  }

  /** Apache Commons Email's message types, in 1.x and in 2.x for Jakarta and javax Mail. */
  private static List<String> commonsEmailTypes() {
    List<String> types = new ArrayList<>();
    for (String pkg :
        List.of(
            "org.apache.commons.mail.",
            "org.apache.commons.mail2.jakarta.",
            "org.apache.commons.mail2.javax.")) {
      for (String name :
          List.of("Email", "SimpleEmail", "HtmlEmail", "MultiPartEmail", "ImageHtmlEmail")) {
        types.add(pkg + name);
      }
    }
    return types;
  }

  private static Map<String, Set<String>> hostSetters() {
    Map<String, Set<String>> setters = new LinkedHashMap<>();
    setters.put("org.springframework.mail.javamail.JavaMailSenderImpl", Set.of("setHost"));
    for (String type : commonsEmailTypes()) {
      setters.put(type, Set.of("setHostName"));
    }
    setters.put(
        "org.simplejavamail.mailer.MailerBuilder", Set.of("withSMTPServer", "withSMTPServerHost"));
    return Map.copyOf(setters);
  }

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
        List<Expression.Invoke> chain = i.expression().chain();
        Expression.Invoke call = chain.getFirst();
        if (call.method().equals("<init>") && chain.size() > 1) {
          call = chain.get(1); // the first call on an object made here, such as a local's
        }
        if (type == null || call.receiver() == null) {
          continue;
        }
        Sender sender = SENDERS.get(type);
        boolean sends = sender != null && sender.methods().contains(call.method());
        String host =
            HOST_SETTERS.getOrDefault(type, Set.of()).contains(call.method())
                    && !call.arguments().isEmpty()
                ? host(call.arguments().getFirst())
                : null;
        if (!sends && host == null) {
          continue;
        }
        Location where = i.where();
        RepoBuild repo = module.repo();
        Evidence evidence =
            Evidence.of(
                repo.repo().name(),
                where.file().path(),
                where.start() > 0 ? new Evidence.LineRange(where.start(), where.end()) : null,
                where.file().contentHash());
        ObjectNode attributes = FactJson.newObject();
        attributes.put("template", type);
        attributes.put("method", call.method());
        if (sends && sender.provider() != null) {
          attributes.put("provider", sender.provider());
        }
        Fact fact =
            Fact.create(
                i.caller(),
                sends ? MAIL_SEND : MAIL_HOST,
                FactObject.string(sends ? call.method() : host),
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

  /** A host written as a string constant, or null when code computes it or leaves it blank. */
  private static String host(Expression argument) {
    return argument instanceof Expression.Literal(String value) && !value.isBlank()
        ? value.strip()
        : null;
  }
}
