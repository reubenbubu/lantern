# 0076: Mail as an external system

Status: accepted (#260), amended for #393

## Context

Only an HTTP host with a domain became an external system (ADR 0042). Project-4's
notification-service sends all its mail through Gmail SMTP (`spring.mail.host=smtp.gmail.com`,
`JavaMailSender.send`), and the hand-made reference puts "Gmail SMTP" on the context and container
views and ends the invitation and password-reset flows in an email step (ADR 0070). Lantern drew
neither.

## Decisions

- **A mail extractor** (`lantern-extract`, `MailExtractor`, version 1) records an attribute
  `mail-send` on each method that calls `send` on Spring's `JavaMailSender`, `JavaMailSenderImpl`
  or `MailSender`, or `send` or `sendMessage` on Jakarta (or javax) Mail's `Transport`. It names no
  host: the server is configuration. `TrackedApis` records calls on `org.springframework.mail`,
  `jakarta.mail` and `javax.mail` for it.
- **A mail linker** (`lantern-link`, in `CrossRepoLinks`) reads `spring.mail.host` as a unit sees
  it in each view (profiles and bundled libraries as ADR 0042 sets out). A host with a domain that
  is not an unresolved placeholder becomes `external-system:<host>`, the same entity an HTTP call to
  that host would reach. A host without a domain (`mailhog`, `localhost`) is a local test server or
  a service name and is left out, as for HTTP.
- **`SENDS_MAIL`** (a child of `CALLS`) links the unit to the system, one link for each method of
  the unit that sends mail and runs in the view (`@Profile` gates apply), with `source` the
  method, `protocol` `SMTP` (`SMTPS` for `spring.mail.protocol=smtps` or port 465), `host`, `port`
  and `key`. A unit whose config names a host but whose code sends no mail the extractor
  recognises gets one link without `source`, since Boot sets up a mail sender only for a unit that
  means to send. Confidence is the lowest of the ends' times the unit's.
- **Views.** `SENDS_MAIL` is drawn as a line "Sends mail through" with its protocol on the context,
  container and component views, and as a step "Sends an email" in a sequence flow that reaches the
  sending method, so invitation and password-reset flows end in an email step.
- **Names.** An external system shows the name people know a well-known mail server by: Gmail,
  Microsoft 365, SendGrid, Mailgun, Postmark, Mailtrap, Yahoo, Zoho and Amazon SES hosts
  (`MailProviders`, "Gmail SMTP" for `smtp.gmail.com`). Others show their host. This is a table of
  names, not a reading of anything the repositories say.
- **Other outbound protocols** (an OIDC issuer, #261; Redis, #259; WebSocket, #262) follow the same
  pattern, each in its own issue: an extractor or a config key, a linker, a relationship and a
  step in the flows.

## Consequences

- Project-4's notification-service shows `SENDS_MAIL` to Gmail SMTP (`EmailService.java:35`), and
  the sign-up, invitation and password-reset flows end in an email step.
- A mail host set only by an environment variable the repositories do not define stays unknown: no
  system is invented.

## Not verified

Mail sent through `Transport.send(...)` (a static call), and the javac reading of `JavaMailSender`
calls, are covered only by the parsed reading in tests; project-4 is read without a build.

## Amendment (#393): more senders, provider SDKs and hosts set in code

The review of the extractor and its research note (`docs/research/extractor-mail.md`) found mail
sent through other common libraries and providers' SDKs, and mail servers set in code. Extractor
`mail` version 2.

- **More senders.** `mail-send` is also recorded for these calls, each on a receiver of the named
  type, never matched by method name alone:
  - Apache Commons Email's `send()` (`Email`, `SimpleEmail`, `HtmlEmail`, `MultiPartEmail`,
    `ImageHtmlEmail`, in `org.apache.commons.mail` and 2.x's `mail2.jakarta` and `mail2.javax`);
  - Simple Java Mail's `Mailer.sendMail`;
  - Quarkus's `Mailer.send` and `ReactiveMailer.send`;
  - Micronaut's `EmailSender.send` and `AsyncEmailSender.sendAsync`.
- **Provider SDKs.** A send through a provider's SDK records `mail-send` with the attribute
  `provider`, the provider the SDK names:
  - SendGrid: `SendGrid.api`, whatever endpoint it calls, since nearly all SendGrid calls send
    mail;
  - Amazon SES: `sendEmail`, `sendRawEmail`, `sendTemplatedEmail`, `sendBulkTemplatedEmail` and
    `sendBulkEmail` in SDK v1 and v2 (`ses` and `sesv2`), and SDK v1's `...Async` forms;
  - Mailgun: `MailgunMessagesApi.sendMessage`, `sendMessageAsync` and `sendMIMEMessage`.
- **Hosts set in code.** A string constant given to `JavaMailSenderImpl.setHost`, Commons Email's
  `setHostName` or Simple Java Mail's `MailerBuilder.withSMTPServer` or `withSMTPServerHost`
  becomes a `mail-host` fact
  on the method, holding the host. A host the code computes is not recorded.
- **Objects made in the method.** A call on an object the same method makes, such as `new
  HtmlEmail()` held in a local variable, counts as a call on its type. This covers Commons Email,
  which is always used this way, and a `JavaMailSenderImpl` made in a `@Bean` method.
- **`TrackedApis`** records calls on those libraries' and SDKs' packages for the extractor.
- **The linker** links only sends through Spring's or Jakarta Mail's API to the server
  `spring.mail.host` names (`MailExtractor.sendsThroughSpringMail`); a send through another library
  or a provider's SDK is not mail to that server.
- **One call per chain.** As for Spring's sender, the first call of a chain is the one read (after
  a constructor), so `new HtmlEmail().setFrom(...).send()` written as one chain is not recorded.
- **Not here:**
  - Linking: drawing a provider or a `mail-host` host as an external system, other libraries' host
    properties, Spring Cloud AWS SES, and leaving test servers out are #499. Until then these
    facts draw nothing.
  - SMS, push and chat (Twilio, Amazon SNS, Firebase Cloud Messaging, Pushy, Slack) are a new
    extractor, #500.
  - Brevo's SDK package was not confirmed and is left out.
