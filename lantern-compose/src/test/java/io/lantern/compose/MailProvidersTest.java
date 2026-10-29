package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailProvidersTest {

  @Test
  void wellKnownHostsHaveTheirNames() {
    assertThat(MailProviders.name("smtp.gmail.com")).contains("Gmail SMTP");
    assertThat(MailProviders.name("SMTP.Gmail.com")).contains("Gmail SMTP");
    assertThat(MailProviders.name("email-smtp.eu-west-1.amazonaws.com"))
        .contains("Amazon SES SMTP");
  }

  @Test
  void otherHostsKeepTheirHost() {
    assertThat(MailProviders.name("mail.example.org")).isEmpty();
  }
}
