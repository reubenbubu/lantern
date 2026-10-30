# How Java systems send mail and notifications, and what names the provider

Research for #393 (Review the Mail extractor), milestone "Extractor revisit". It asks how Java systems send mail and other outbound notifications, and what in code or configuration names the external system reliably enough to cite. It leads to an amendment of `docs/adr/0076-mail-as-an-external-system.md`.

**Depth: high.** Read in full, 29 sources:

- 8 Spring and AWS reference pages;
- 12 library and SDK pages and repositories (SendGrid, Twilio, Firebase, Pushy, Mailgun, Brevo, Simple Java Mail, Commons Email, Quarkus, Micronaut, Angus Mail, Spring Cloud AWS);
- 5 test-server, webhook and SBOM pages;
- 1 paper with its code (Code2DFD).

Pages were read through a summarising fetch tool. Not confirmed on a page read: the AWS SDK v2 SES class names, the FCM, APNs and SendGrid API hosts, the Postmark and Mailjet Java SDKs, and the Mailgun and Postmark SMTP hosts. The research is generic: it does not read Lantern's test sets.

## Findings

### Mail libraries: the server is configuration

- **Spring Boot** sets up a `JavaMailSender` when `spring.mail.host` is set and the mail libraries are on the classpath. `spring.mail.jndi-name` overrides the host, and `spring.mail.properties.*` passes raw Jakarta Mail properties. Every send ends in `MailSender.send` or `JavaMailSender.send`. [Spring Boot email, properties appendix, MailProperties]
- **Spring without Boot** sets the host in code, as `new JavaMailSenderImpl().setHost("mail.example")` in a `@Bean`, or in XML. A reader of `spring.mail.host` alone misses it. [Spring Framework email]
- **Jakarta Mail** reads `mail.smtp.host` (and `mail.smtps.*`) from the `Session`'s properties, or a host given to `Transport.connect`. It sends with `Transport.send` or `sendMessage`. [Angus Mail SMTP package]
- **Apache Commons Email** (`org.apache.commons.mail`, and in 2.x `org.apache.commons.mail2.jakarta` and `.javax`) builds a `SimpleEmail`, `HtmlEmail`, `MultiPartEmail` or `ImageHtmlEmail` in a local variable. It calls `setHostName(...)`, then `send()`. [Commons Email user guide]
- **Simple Java Mail** builds its mailer with `MailerBuilder.withSMTPServer(host, port, ...)`, or from the `simplejavamail.smtp.host` property, and sends with `Mailer.sendMail`. Its logging-only mode sends nothing. [Simple Java Mail configuration]
- **Quarkus Mailer** sends with `Mailer.send` or `ReactiveMailer.send`, to `quarkus.mailer.host`. It mocks sending by default in dev and test. [Quarkus mailer reference]
- **Micronaut Email** sends with `EmailSender.send`. Its provider is named by the module on the classpath (`micronaut-email-sendgrid`, `-amazon-ses`, `-postmark`, `-mailjet`) or by `javamail.properties.mail.smtp.host`. [Micronaut Email guide]
- **The host names the provider.** Examples: `email-smtp.<region>.amazonaws.com` is Amazon SES, `smtp.sendgrid.net` (with the literal username `apikey`) is SendGrid, and `smtp.gmail.com` is Gmail. [AWS SES SMTP endpoints, SendGrid SMTP]

### Provider SDKs: the code names the provider

| SDK | Coordinates | Send call |
| --- | --- | --- |
| SendGrid | `com.sendgrid:sendgrid-java` | `SendGrid.api(Request)` with `mail/send` |
| Amazon SES | `com.amazonaws:aws-java-sdk-ses`, `software.amazon.awssdk:ses`/`sesv2` | `sendEmail`, `sendRawEmail` (v2 names unconfirmed) |
| Spring Cloud AWS SES | `io.awspring.cloud:spring-cloud-aws-starter-ses` | plain `MailSender`/`JavaMailSender`, backed by SES |
| Mailgun | `com.mailgun:mailgun-java` | `MailgunMessagesApi.sendMessage` |
| Brevo | `com.brevo:brevo` | `TransactionalEmailsApi.sendTransacEmail` |
| Twilio SMS | `com.twilio.sdk:twilio` | `Message.creator(...).create()` |
| Amazon SNS | SDK `sns`, Spring Cloud AWS | `SnsClient.publish`, `SnsSmsTemplate.send` |
| Firebase Cloud Messaging | `com.google.firebase:firebase-admin` | `FirebaseMessaging.send`, `sendEach`, `sendEachForMulticast` |
| Apple push (Pushy) | `com.eatthepath:pushy` | `ApnsClient.sendNotification` |
| Slack | none | POST to `hooks.slack.com/services/...`, a secret URL |

Spring Boot also auto-configures SendGrid from `spring.sendgrid.api-key`. [Spring Boot properties appendix, sendgrid-java, AWS SES SDK, Spring Cloud AWS, mailgun-java, brevo-java, twilio-java, Firebase Admin, Pushy, Slack webhooks]

### What hides the provider, and what is not one

- **Hiding patterns:**
  - Templates (FreeMarker, Qute) and `@Async` move the body or the thread, not the send call.
  - Queue-fed notification services send from the consumer, so the external system belongs to the consuming service.
  - Spring Cloud AWS SES looks exactly like SMTP mail in code. [Spring Framework email, Spring Cloud AWS, Code2DFD]
- **Test servers are never external systems:** GreenMail (port 3025, `greenmail/standalone`), Mailpit and MailHog (1025 and 8025), Quarkus's mock mode, and Simple Java Mail's logging-only mode. [GreenMail, Mailpit]
- **Prior art.** Code2DFD (Schneider and Scandariato 2023) extracts dataflow diagrams from Java microservices with technology-specific extractors. It draws a "mail server" external entity, with file and line evidence, attached to the service that sends. Its rule is that a component with no deployment descriptor of its own is external. [Code2DFD]

## Options

1. **Configuration hosts only.** Precise, but misses SDK senders, hosts set in code, and Spring Cloud AWS SES.
2. **Dependency coordinates only.** Names SDK providers, but an unused or test-scoped dependency is not a send, and the citation is a POM line, not the sending code.
3. **The send call, plus the strongest evidence of the provider.** The extractor records every send call (with the provider when an SDK names it) and every host that code sets. The linker joins them with configuration and dependencies, and draws a line only from a unit whose code sends. This is the only option that cites the sending code and draws the line from the right container.

## Decision

**Option 3.** The extractor's half is in ADR 0076's amendment for #393:

- **More senders.** Record `mail-send` for:
  - Apache Commons Email `send()`;
  - Simple Java Mail `Mailer.sendMail`;
  - Quarkus `Mailer.send` and `ReactiveMailer.send`;
  - Micronaut `EmailSender.send`;
  - the SendGrid (`SendGrid.api`), Amazon SES (`sendEmail` and its kin, SDK v1 and v2, `ses` and `sesv2`) and Mailgun (`MailgunMessagesApi.sendMessage`) SDKs, with the provider they name.

  Each sink is resolved by its receiver's type, never by a method name alone.
- **Hosts set in code** become `mail-host` facts: a string constant given to `JavaMailSenderImpl.setHost`, Commons Email's `setHostName` or `MailerBuilder.withSMTPServer`. A computed host is not recorded.
- **Calls on an object made in the same method**, such as `new HtmlEmail()` in a local, count. Commons Email is always used this way.
- **Left to issues:**
  - The linker's half is #499: SDK providers, `mail-host`, the other libraries' host properties, Spring Cloud AWS SES, and the test-server filter.
  - SMS, push and chat (Twilio, SNS, Firebase Cloud Messaging, Pushy, Slack) are a new extractor, #500.
  - Brevo's package was not confirmed and is left out.

### Evidence from the development sets

The coverage report's mail area is 3/3 on project-4, its only set with mail needs, before and after:
- project-4's notification-service sends through Spring's `JavaMailSender` to Gmail SMTP;
- project-0 sets `spring.mail.host` from an environment variable;
- project-2's email and SMS adapters are local stand-ins, and its reference rightly draws no provider.

No set uses the libraries or SDKs added here.

## Sources

1. Spring Boot, Email: https://docs.spring.io/spring-boot/reference/io/email.html
2. Spring Boot, Common application properties (Mail, SendGrid): https://docs.spring.io/spring-boot/appendix/application-properties/index.html
3. Spring Boot, `MailProperties`: https://docs.spring.io/spring-boot/api/java/org/springframework/boot/mail/autoconfigure/MailProperties.html
4. Spring Framework, Email: https://docs.spring.io/spring-framework/reference/integration/email.html
5. Amazon SES, SMTP endpoints: https://docs.aws.amazon.com/ses/latest/dg/smtp-connect.html
6. AWS, SES endpoints and quotas: https://docs.aws.amazon.com/general/latest/gr/ses.html
7. Amazon SES, sending with an AWS SDK: https://docs.aws.amazon.com/ses/latest/dg/send-an-email-using-sdk-programmatically.html
8. Spring Cloud AWS reference (SES, SNS): https://docs.awspring.io/spring-cloud-aws/docs/3.4.0/reference/html/index.html
9. sendgrid-java: https://github.com/sendgrid/sendgrid-java
10. SendGrid, SMTP API: https://www.twilio.com/docs/sendgrid/for-developers/sending-email/integrating-with-the-smtp-api
11. twilio-java: https://github.com/twilio/twilio-java
12. Twilio, Messaging quickstart for Java: https://www.twilio.com/docs/messaging/quickstart/java
13. Firebase, Admin SDK sending: https://firebase.google.com/docs/cloud-messaging/send/admin-sdk
14. firebase-admin-java: https://github.com/firebase/firebase-admin-java
15. Pushy: https://github.com/jchambers/pushy
16. mailgun-java: https://github.com/mailgun/mailgun-java
17. brevo-java: https://github.com/getbrevo/brevo-java
18. Simple Java Mail configuration: https://www.simplejavamail.org/configuration.html
19. Apache Commons Email user guide: https://commons.apache.org/proper/commons-email/userguide.html
20. Quarkus mailer reference: https://quarkus.io/guides/mailer-reference
21. Micronaut Email guide: https://micronaut-projects.github.io/micronaut-email/latest/guide/
22. Angus Mail SMTP provider: https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html
23. GreenMail: https://greenmail-mail-test.github.io/greenmail/
24. Mailpit with Docker: https://mailpit.axllent.org/docs/install/docker/
25. Slack incoming webhooks: https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks
26. Microsoft Teams incoming webhooks: https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook
27. Schneider and Scandariato, Code2DFD (2023): https://arxiv.org/abs/2304.12769
28. Code2DFD source: https://github.com/tuhh-softsec/code2DFD
29. CycloneDX SaaSBOM: https://cyclonedx.org/capabilities/saasbom/
