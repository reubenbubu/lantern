# 0076: Mail as an external system

Status: accepted (#260), pending the user's review (#24)

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
