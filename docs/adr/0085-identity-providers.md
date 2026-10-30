# 0085: Identity providers and shared JWT secrets

Status: accepted (#261), amended for #395

## Context

Every development test set signs its users in somewhere, and Lantern drew none of it (ADR 0070).
project-1 runs Keycloak in compose: its BFF signs users in through it, profile-service gets
client-credentials tokens from it, keycloak-admin-service manages its users through the admin
API, and the gateway and services check their tokens with its keys. project-2's gateway and
services fetch auth-service's JWK set to check the tokens it issues. project-4's gateway and
auth-service sign and check their JWTs with one HMAC secret. Where the provider is lives in config
(`spring.security.oauth2.*`, or keys like them) or in the code that builds a client; a shared
secret is a finding, not a line. ADR 0076 set the pattern for outbound protocols: an extractor or a
config key, a linker and a relationship.

## Decisions

- **An identity extractor** (`lantern-extract`, `IdentityExtractor`, name `identity`, version 1)
  reads the code as the Java extractor read it and records two attributes on methods:
  - `jwt-secret-key`: the method makes an HMAC key for JWTs (jjwt's `Keys.hmacShaKeyFor`,
    `signWith`, `setSigningKey`, `verifyWith`; Nimbus's `MACSigner` and `MACVerifier`; Spring
    Security's `withSecretKey`; java-jwt's `Algorithm.HMAC256/384/512`) from a secret in config,
    followed from a `@Value` field or parameter through the calls made on it and one hop from a
    field a constructor sets, but not through what builds a key pair's key (`KeyFactory`, key
    specs, Nimbus JWKs) or from a field of another type than `String`, `byte[]` or `char[]`. The fact names the config keys (`keys`), the library, the call and
    what the type's code does with JWTs (`uses`: sign, verify). The secret's value is never
    recorded, and a secret written in the code is not recorded at all.
  - `identity-admin-client`: the method builds Keycloak's admin client (`KeycloakBuilder.serverUrl`,
    `Keycloak.getInstance`), valued with the server URL as written (a `@Value` text, or a literal
    without credentials).
  JWT libraries and Keycloak's admin client are tracked APIs.
- **The identity linker** (`lantern-link`, `IdentityLinker`, a step of `cross-repo-links`) links each
  unit, in each view, to the providers its config and code name, with `USES_IDENTITY_PROVIDER`
  (a child of `CALLS`), one fact per unit, provider and `purpose`:
  - `validates-tokens`: a resource server's `issuer-uri`, `jwk-set-uri` or `introspection-uri`, or
    any key whose last part reads like them (`platform.security.jwt.jwk-set-uri`), outside the
    client keys; a key ending in `issuer` without `-uri` is the unit's own issuer name and is not
    read;
  - `signs-in-users` and `gets-tokens`: a client provider (`spring.security.oauth2.client.provider.<p>`
    issuer, authorization, token, JWK set or user-info URI), by the grant types of the
    registrations that use it (`authorization_code`, the default, signs users in; others get
    tokens); a registration of a provider Spring Security knows by name (`google`, `github`,
    `facebook`) without a provider block reaches that provider's host;
  - `keycloak.auth-server-url` (Keycloak's adapter) signs users in, or validates tokens when
    `keycloak.bearer-only` is true;
  - `manages-users`: an `identity-admin-client`, its URL resolved through the unit's config.
  Protocols read `OIDC`, `JWKS`, `OAuth2` or `Keycloak Admin REST`, over HTTP or HTTPS.
- **Where a provider's URL leads**, in order: for this machine, the one unit whose `server.port`
  is the URL's port (project-2's `localhost:8081` is auth-service), else a container of an identity
  provider's image (ADR 0082's role `identity`) that publishes that port (project-1's
  `localhost:8080` is the compose Keycloak), else, for 8080, the one unit that sets no port when the URL's path reaches one of its
  endpoints (8080 is also Keycloak's own port);
  otherwise the unit whose `spring.application.name` is the host, else an identity provider
  container answering the host, else a host with a domain as `external-system:<host>`. A URL
  whose host is a placeholder is filled in from the environment of each container that runs the
  unit and tried again. A link to the unit itself is left out, and a URL that leads nowhere known
  makes no link: no provider is invented. A provider that is another unit is that unit, with the
  endpoint the URL's path reaches (`target`), so an issuer inside the system links to the unit.
- **An identity provider a deployment runs** is an external system keyed by its product
  (`external-system:keycloak`), named by it (`display-name` Keycloak), with a `deployed-in` fact
  for each container it was found by, so the Deployment table shows what runs in it. Following the
  acceptance criteria of #261, it is drawn as an external system even when the system's own
  compose file runs it; the reference of project-1 accepts either (its `verification.md`).
- **Shared secrets** (`shared-secret`, an attribute of each unit, for the findings page, #273):
  units whose `jwt-secret-key` secrets come from the same place share one, by, in order: the same
  placeholders nothing resolves (`basis` `placeholder`; the environment fills one value in for
  all, compared as relaxed binding reads them), the same value config gives (`value`; compared by
  digest inside the linker, never recorded), or, when a value is masked or unset, the same config
  key (`key`, at 0.7 of the confidence). The fact names the other units (`with`), what the unit does
  with JWTs and the placeholders, never the value.
- **Views.** `USES_IDENTITY_PROVIDER` is drawn on the context and container views as "Validates
  tokens with the keys of", "Signs users in through", "Gets tokens from" or "Manages users
  through", and is worded and listed in fact sheets like the other links. Well-known providers'
  hosts show their names (`IdentityProviders`: Google, GitHub, Facebook, Microsoft Entra ID, Okta,
  Auth0, Amazon Cognito), as mail servers do (ADR 0076).

## Consequences

- project-1 shows Keycloak as an external system with lines from bff, gateway, profile-service
  and keycloak-admin-service; project-2's six services that check tokens fetch auth-service's JWK
  set; project-4's gateway and auth-service record their shared HMAC secret.
- `cross-repo-links` moves to version 11, the summary prompt to 15; `identity` starts at 1.

## Amendment for #395: how tokens are checked, how callers sign in, who is registered

The review of the extractor (`docs/research/extractor-identity.md`) found that #380's coverage
report backed only 3 of 15 identity needs on project-1, project-2 and project-4. Research on the
web added legacy Spring Security OAuth, Spring Security 7's new sign-in calls and more of
Keycloak's client fields. The extractor (version 2) records four more facts. Each cites the line
that states it, and none records a secret's or key's value:

- `jwt-decoder`, on a method that builds Spring Security's JWT decoder: `NimbusJwtDecoder` or
  `NimbusReactiveJwtDecoder` with `withJwkSetUri`, `withIssuerLocation` or `withPublicKey`, and
  `JwtDecoders` or `ReactiveJwtDecoders` with `fromIssuerLocation` or `fromOidcIssuerLocation`.
  - The value says where the keys come from: `jwk-set-uri`, `issuer` or `public-key`.
  - When the URL can be followed, as `@Value` placeholders or a literal, the fact records it
    (`url`) and its config keys (`keys`).
  - A URL handed on from a properties class is not followed, but the decoder is still recorded.
- `http-security`, on what sets up how callers sign in, show a token or get one. The value is one
  of `oauth2-login`, `oauth2-resource-server`, `oauth2-client`, `oauth2-authorization-server`,
  `form-login`, `http-basic`, `saml2-login`, `x509`, `one-time-token-login` or `webauthn`.
  `via` names where it is set up:
  - `HttpSecurity` or `ServerHttpSecurity`, for a method's DSL call. The two DSLs' packages are
    tracked APIs.
  - `annotation`, for a type with legacy Spring Security OAuth's `@EnableAuthorizationServer`,
    `@EnableResourceServer`, `@EnableOAuth2Sso` or `@EnableOAuth2Client`. These are end of life
    but common in older systems.
- `jwt-key-pair`, on a method that signs or checks JWTs with a key pair's key: Nimbus's
  `RSASSASigner`, `RSASSAVerifier`, `ECDSASigner` or `ECDSAVerifier`.
  - The value is `RSA` or `EC`, and `use` is `sign` or `verify`.
  - `keys` holds the config keys when the key can be followed the way an HMAC secret is.
- `identity-client`, on the repository, for each client a Keycloak realm export
  (`*realm*.json` with a `clients` array) registers, Keycloak's own clients left out.
  - The value is the client ID.
  - The fact records the realm, whether the client is enabled and public, its protocol, whether
    it is bearer-only, its flows (`authorization-code` unless turned off, `implicit`, `password`,
    `client-credentials`), its PKCE method, its root and base URLs, its redirect URIs and its web
    origins, without credentials.
  - Its secret is never read.

The HMAC secrets of jjwt 0.9 to 0.11 (`setSigningKey(secret)`,
`signWith(SignatureAlgorithm.HS512, secret)`) were already read, and a test now covers them.

Left for later:
- Using these facts in the linker (registered apps as external systems, token checks whose key
  reads like neither, and a finding when a system has no authentication) is #407.
- Patterns no development set has are #406: Spring Authorization Server's `RegisteredClient`,
  secrets reached through helpers and `SecretKeySpec`, issuer resolvers, opaque-token
  introspectors, Sa-Token, Firebase and other stacks' config keys.
- Spring Cloud Gateway's `TokenRelay` belongs to the gateway extractor (#390).

## Not covered

Defaults a library adds to the environment in code (project-1's `EnvironmentPostProcessor` that
gives order-service and keycloak-admin-service their issuer), so those units have no
validates-tokens line; `@ConfigurationProperties` getters as a secret's source; issuers and JWK
sets under keys that read like neither; public keys and key stores (only shared HMAC secrets are
a finding); sequence steps for sign-in, which no flow traces yet.
