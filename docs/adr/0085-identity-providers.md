# 0085: Identity providers and shared JWT secrets

Status: accepted (#261), pending the user's review (#24)

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
    field a constructor sets. The fact names the config keys (`keys`), the library, the call and
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
  `localhost:8080` is the compose Keycloak), else, for 8080, the one unit that sets no port;
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

## Not covered

Defaults a library adds to the environment in code (project-1's `EnvironmentPostProcessor` that
gives order-service and keycloak-admin-service their issuer), so those units have no
validates-tokens line; `@ConfigurationProperties` getters as a secret's source; issuers and JWK
sets under keys that read like neither; public keys and key stores (only shared HMAC secrets are
a finding); sequence steps for sign-in, which no flow traces yet.
