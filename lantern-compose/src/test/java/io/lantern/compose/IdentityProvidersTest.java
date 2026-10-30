package io.lantern.compose;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentityProvidersTest {

  @Test
  void wellKnownHostsHaveTheirNames() {
    assertThat(IdentityProviders.name("accounts.google.com")).contains("Google");
    assertThat(IdentityProviders.name("dev-123.okta.com")).contains("Okta");
    assertThat(IdentityProviders.name("cognito-idp.eu-west-1.amazonaws.com"))
        .contains("Amazon Cognito");
  }

  @Test
  void otherHostsKeepTheirHost() {
    assertThat(IdentityProviders.name("sso.example.com")).isEmpty();
  }
}
