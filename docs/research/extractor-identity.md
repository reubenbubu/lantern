# Identity in Java systems: what the Identity extractor reads

Research for #395, the review of the Identity extractor in the milestone "Extractor revisit". It
leads to the amendment of `docs/adr/0085-identity-providers.md` for #395.

**Depth: high.** The note sets which patterns the extractor reads across stacks, which is a choice
between approaches, not only how one framework is used. About 20 pages and two codebases (Keycloak's
`ClientRepresentation`, Code2DFD) were read in full or in large part. Nine open-source projects
were cloned as evidence of what occurs. A first, medium pass that relied on search snippets was
discarded.

## Question

How do Java systems authenticate users and services, and what in code and config names the
identity provider and how tokens are checked? Which of it should the Identity extractor read, and
what did its plan miss?

## What we learned

How it was done: a research subagent read the pages below in full or in large part, plus the
source of Keycloak's `ClientRepresentation` and of Code2DFD. It also cloned nine open-source
Spring microservice projects and searched them for authentication code.

**Spring Security 6 and 7**

- **Spring Security 7.0** removed `and()` from the `HttpSecurity` DSL (only the lambda DSL is
  left), removed `authorizeRequests`, `AntPathRequestMatcher` and password-grant support, and
  merged **Spring Authorization Server into Spring Security**. 7.1 adds
  `RestClientOpaqueTokenIntrospector` and multi-factor conditions.
  <https://docs.spring.io/spring-security/reference/7.0/whats-new.html>,
  <https://docs.spring.io/spring-security/reference/whats-new.html>
- **New sign-in DSLs:**
  - `oauth2AuthorizationServer(...)` makes the unit the provider. Its clients are
    `RegisteredClient.withId(...)` in code, or
    `spring.security.oauth2.authorizationserver.client.<name>.registration.*` in config.
    <https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/getting-started.html>
  - `oneTimeTokenLogin()`.
    <https://docs.spring.io/spring-security/reference/servlet/authentication/onetimetoken.html>
  - `webAuthn(...)` (passkeys).
    <https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html>
  - `oidcLogout(...)`, alongside `oauth2Login`.
    <https://docs.spring.io/spring-security/reference/servlet/oauth2/login/logout.html>
- **Resource servers:**
  - Keys come from `issuer-uri`, `jwk-set-uri` or `public-key-location`, overridden in code by a
    `JwtDecoder` bean or by `jwt(j -> j.jwkSetUri(...)/decoder(...))`. Boot wraps discovery in a
    `SupplierJwtDecoder`.
    <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html>
  - Opaque tokens use `spring.security.oauth2.resourceserver.opaquetoken.introspection-uri` and
    introspector builders.
    <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/opaque-token.html>
  - Several issuers are trusted through `JwtIssuerAuthenticationManagerResolver`.
    <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/multitenancy.html>
- **jjwt has three API generations.** 0.11 added `parserBuilder()`. 0.12 removed it, renamed
  `parseClaimsJws` to `parseSignedClaims` and deprecated `SignatureAlgorithm`. Real code holds all
  three: `setSigningKey(secret)`, `signWith(SignatureAlgorithm.HS512, secret)` and
  `verifyWith(key)`. <https://github.com/jwtk/jjwt/blob/master/CHANGELOG.md>

**Keycloak**

- The Spring adapters were deprecated in 2022, so `keycloak.auth-server-url` is legacy. Modern
  services use Spring Security with the issuer `…/realms/<realm>`.
  <https://www.keycloak.org/2022/02/adapter-deprecation.html>
- A realm import reads `*.json` in `data/import` (`/opt/keycloak/data/import` in containers),
  named `<realm>-realm.json`, and its values may be `${ENV}` placeholders. JHipster mounts
  `realm-config` there. <https://www.keycloak.org/server/importExport>
- A client (`ClientRepresentation`) has `clientId`, `enabled`, `publicClient`, `bearerOnly`,
  `protocol` (openid-connect or saml), `rootUrl`, `baseUrl`, `redirectUris`, `webOrigins`, the
  flow switches (`standardFlowEnabled`, `implicitFlowEnabled`, `directAccessGrantsEnabled`,
  `serviceAccountsEnabled`), `secret` and `attributes` (`pkce.code.challenge.method`).
  <https://github.com/keycloak/keycloak/blob/main/core/src/main/java/org/keycloak/representations/idm/ClientRepresentation.java>

**Other stacks**

- **Okta's starter** uses `okta.oauth2.issuer`, `client-id` and `audience`.
  <https://github.com/okta/okta-spring-boot>
- **Microsoft Entra ID** uses `spring.cloud.azure.active-directory.*`.
  <https://learn.microsoft.com/en-us/azure/developer/java/spring-framework/configuration-properties-azure-active-directory>
- **MicroProfile JWT** uses `mp.jwt.verify.publickey.location` and `mp.jwt.verify.issuer`, with
  `@LoginConfig(authMethod="MP-JWT")`. Quarkus uses `quarkus.oidc.*`, and Micronaut uses
  `micronaut.security.*`.
  <https://download.eclipse.org/microprofile/microprofile-jwt-auth-2.1/microprofile-jwt-auth-spec-2.1.html>
- **Firebase** checks tokens with `FirebaseAuth.verifyIdToken`.
  <https://firebase.google.com/docs/auth/admin/verify-id-tokens>
- **Sa-Token** is common in Chinese open-source microservices (`StpUtil`, `SaInterceptor`, a
  `sa-token:` config block). <https://github.com/dromara/Sa-Token>
- **Legacy Spring Security OAuth** (`@EnableAuthorizationServer`, `@EnableResourceServer`,
  `@EnableOAuth2Sso`, `security.oauth2.*` keys) is end of life but common in older systems.
  <https://github.com/spring-attic/spring-security-oauth>

**What real projects do.** The nine cloned projects:

| Project | Authentication |
| --- | --- |
| spring-petclinic-microservices, piomin/sample-spring-microservices-new, eventuate customers-and-orders, ftgo-application | none |
| piggymetrics, piomin/sample-spring-oauth2-microservices | legacy Spring Security OAuth (annotations; config in a Config Server repository) |
| jhipster-sample-app-microservice | HMAC secret through `@Value`, a helper, `SecretKeySpec`, then `NimbusJwtDecoder.withSecretKey` |
| mall-swarm | Sa-Token |
| Packt's Spring Cloud book (3rd ed.) | Spring Authorization Server with `RegisteredClient`, resource servers by `issuer-uri` |

**How other tools model it.** Code2DFD (read in its source) marks `authorization_server`,
`resource_server`, `token_server` (from `security.oauth2.client.accessTokenUri`),
`pre_authorized_endpoints`, `basic_authentication`, `csrf_disabled` and
`authentication_scope_all_requests`, each with file and line. It reads only legacy Spring
Security OAuth and `HttpSecurity` lines. CodeQL's Java queries flag JWTs parsed without a
signature check, disabled CSRF and Basic auth over HTTP.
<https://codeql.github.com/codeql-query-help/java/java-missing-jwt-signature-check/>
Code2DFD's paper (Schneider and Scandariato, "Automatic Extraction of Security-Rich Dataflow
Diagrams for Microservice Applications written in Java", Journal of Systems and Software; arXiv
2304.12769) finds annotations such as `@EnableAuthorizationServer` by direct keyword search and
follows identifiers such as a password encoder by iterative search. It names authentication and
authorization as the hardest to detect, because they "require detailed configurations at numerous
places in the code". <https://arxiv.org/html/2304.12769>

## Evidence from the test sets

What project-0 to project-4 use (their main code; held-out sets not read):

| Pattern | Sets |
| --- | --- |
| `oauth2Login` | project-1 (bff), project-2 (auth-service) |
| `oauth2ResourceServer` | project-1, project-2 |
| `NimbusJwtDecoder.withJwkSetUri` and the reactive one | project-2 (gateway and five services) |
| `withPublicKey` | project-2 |
| Nimbus `RSASSASigner` and `RSASSAVerifier` | project-1 (bff session cookie) |
| Keycloak realm export | project-1 (`keycloak/realm-config/realm-export.json`) |
| HMAC JWT secret (already read) | project-4 |
| Keycloak admin client (already read) | project-1 |
| legacy Spring Security OAuth, authorization server, Sa-Token, vendor starters | none |

project-0 and project-3 have no authentication. Before this review, the identity area met
project-1 2/6, project-2 0/8 and project-4 1/1 of its needs (#380's maps as corrected).

## What the plan missed

The plan before this research had only the decoders, the DSL's sign-in calls, the RSA and EC
signers and the realm clients' flows and redirect URIs. Ranked by how common the miss is:

1. **No authentication at all is common** (four of nine projects, and project-0 and project-3).
   The report should say so instead of staying silent. This is a finding for linking (#407).
2. **Legacy Spring Security OAuth annotations.** These are cheap: one annotation each.
3. **Older jjwt calls** (`setSigningKey`, `signWith(SignatureAlgorithm, secret)`). The
   extractor already read them; a test now proves it.
4. **Secrets reached through helpers and `SecretKeySpec`** (JHipster) (#406).
5. **The in-system authorization server, the new DSLs and other stacks.**
   `oauth2AuthorizationServer`, `oneTimeTokenLogin` and `webAuthn` are cheap and done here.
   `RegisteredClient`, `TokenRelay` (#390;
   <https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/gatewayfilter-factories/tokenrelay-factory.html>),
   Sa-Token, Firebase and the other stacks' config keys
   go to #406.
6. **More realm client fields:** `enabled`, `protocol`, `bearerOnly`, `rootUrl`, `baseUrl` and
   `webOrigins`.

One claim of the research was wrong for Lantern: it said the linker reads
`jwt.introspection-uri` instead of `opaquetoken.introspection-uri`. The linker matches any key
ending in `introspection-uri` (ADR 0085), so both are read.

## Options

1. **Read only what the development sets use.** Smallest, but it misses legacy OAuth and the new
   DSLs, which cost one map entry each.
2. **Model what is stated, not the API.** One fact per kind of statement: how tokens are checked,
   how callers sign in (including issuing tokens), which keys sign JWTs, and which clients a
   provider registers. Each pattern is a table entry with its line. This is Code2DFD's approach,
   extended to current Spring Security.
3. **Option 2 for every stack the research lists.** Complete, but no development set would test
   most of it.

## Recommendation

Option 2, reading every pattern that is either in the development sets or costs one entry beside
them. Each has a unit test. The rest waits in #406 until a test set brings it, and linking the
new facts, with the no-authentication finding, is #407.

**Rule:** the Identity extractor records, with the line that states it, every place a system's
code or provider configuration says how callers prove who they are:

- the key or provider that tokens are checked with
- how callers sign in or receive tokens, including a unit that issues tokens itself
- the keys JWTs are signed with
- the clients a provider registers

It never records a secret's or key's value.

## What it changes

- `docs/adr/0085-identity-providers.md`, amended for #395.
- `docs/design.md`, the Identity row of the extractor table.
- `docs/quality-bar.md`: nothing.

## Sources read in full or in large part

The Spring Security 7.0 and 7.1 "What's New" pages and reference pages linked above; the Spring
Cloud Gateway TokenRelay page; Keycloak's import/export guide, adapter deprecation post and
`ClientRepresentation` source; jjwt's changelog; the Okta starter README; Microsoft's Entra
configuration reference; the MicroProfile JWT 2.1 specification; Firebase's token verification
page; the Sa-Token README; the spring-security-oauth README; CodeQL's Java query help; Code2DFD's
source; the first part of Code2DFD's paper (its method); nine open-source projects. Not read, or only in part: Spring Security 6.4's "What's New"
(missing), the end of Keycloak's upgrade guide, Quarkus's and Micronaut's full references, and
the Auth0, Cognito, Shiro and pac4j docs, and the second part of Code2DFD's paper, including
its precision and recall per kind of item. None of these carries weight in the recommendation.
