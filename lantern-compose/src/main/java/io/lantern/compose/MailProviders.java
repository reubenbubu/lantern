package io.lantern.compose;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The names people know well-known mail servers by (ADR 0076), so the external system for {@code
 * smtp.gmail.com} reads as "Gmail SMTP". A host not listed keeps its host as its name.
 */
final class MailProviders {

  private static final Map<String, String> BY_HOST =
      Map.ofEntries(
          Map.entry("smtp.gmail.com", "Gmail SMTP"),
          Map.entry("smtp-relay.gmail.com", "Gmail SMTP relay"),
          Map.entry("smtp.office365.com", "Microsoft 365 SMTP"),
          Map.entry("smtp-mail.outlook.com", "Outlook SMTP"),
          Map.entry("smtp.sendgrid.net", "SendGrid SMTP"),
          Map.entry("smtp.mailgun.org", "Mailgun SMTP"),
          Map.entry("smtp.postmarkapp.com", "Postmark SMTP"),
          Map.entry("smtp.mailtrap.io", "Mailtrap SMTP"),
          Map.entry("sandbox.smtp.mailtrap.io", "Mailtrap SMTP"),
          Map.entry("smtp.mail.yahoo.com", "Yahoo Mail SMTP"),
          Map.entry("smtp.zoho.com", "Zoho Mail SMTP"));

  private MailProviders() {}

  /** The name of a well-known mail server by its host, if it is one. */
  static Optional<String> name(String host) {
    String lower = host.toLowerCase(Locale.ROOT);
    String known = BY_HOST.get(lower);
    if (known != null) {
      return Optional.of(known);
    }
    if (lower.startsWith("email-smtp.") && lower.endsWith(".amazonaws.com")) {
      return Optional.of("Amazon SES SMTP");
    }
    return Optional.empty();
  }
}
