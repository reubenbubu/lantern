# 0032: Config extractor

Status: accepted (#126, part of #92), amended (#137, #143, #335, #338, #340, #333, #348, #384, #516)

## Context

`docs/design.md` (Static analysis and extractors) says the Config extractor reads `.properties`
and `.yml` files (`application*` and `bootstrap*`), Spring profiles and env-var placeholders, and
produces resolved and unresolved config values per profile, including `spring.application.name`.
Following values says extractors resolve `${...}` placeholders against config per profile, and a
value that can't be resolved keeps its placeholder key. The other runtime extractors of #92 (Spring,
HTTP clients, messaging, persistence) resolve their placeholders through this one, and later steps
need its values: container names (#93), base URLs and datasource URLs for the linker (#94), and a
config catalog for the views. The design leaves open which files count, how profiles are modelled,
what the facts look like and how a secret written in a config file is handled.

## Decisions

- **Spring Boot reads the files.** `ConfigExtractor` (`lantern-extract`) reads each file with
  Spring Boot's own `YamlPropertySourceLoader` and `PropertiesPropertySourceLoader`, so documents,
  YAML lists (`hosts[0]`), nested keys and `#---` separators come out exactly as Spring Boot reads
  them, with each value's line. This adds `spring-boot` and `snakeyaml` to `lantern-extract`; both
  are Spring Boot's (the design's framework), managed by its parent and already shipped in the app
  through `spring-boot-starter`. A file Spring Boot cannot read is left out; files over 1 MiB too.
- **Which files.** For each Maven module (found as the Java extractor finds them, ADR 0007), the
  files named `application`, `application-<profile>`, `bootstrap` or `bootstrap-<profile>` with
  extension `properties`, `yml` or `yaml`, directly in `src/main/resources` or
  `src/main/resources/config`, read from git at the commit. Test resources, other names
  (`@PropertySource` files) and `spring.config.import`ed files are not read; an import or config
  server URL is still recorded as a value. A config server's repository is read since #384
  (amendment below).
- **Precedence**, lowest first, as Spring Boot (and Spring Cloud for bootstrap) applies it:
  bootstrap before application config; within each, files without a profile before profile files;
  `src/main/resources` before `src/main/resources/config`; `yaml`, `yml`, then `properties`; and
  the documents of a file in the order written. A later value overrides an earlier one. So a name
  set only in `bootstrap.yml` holds, and one in `application.yml` wins over it. A list a later
  document sets (`routes[0].id`, ...) replaces the earlier list whole, as Spring binds lists, rather
  than being merged with it item by item (#516).
- **One profile at a time.** The default view is what Spring Boot sees with no profile set: the
  `default` profile is active (`application-default.yml` and documents limited to `default`
  apply). A profile view has that one profile active instead. Documents limited by
  `spring.config.activate.on-profile`, or by the older `spring.profiles`, apply when their profile
  expressions match (Spring's `Profiles`, so `!prod` and `prod & cloud` work); documents limited to
  a cloud platform, or with an expression Spring would reject, never apply. The profiles are those
  named in file names and in activations. Combinations of profiles,
  `spring.profiles.active`/`include`/`group` and env vars that would override a value at deploy time
  are not modelled; they are recorded as values only.
- **Placeholders** are resolved as Spring resolves them (a backslash before `${` makes it text): `${key}` takes the key's value from the
  same view, itself resolved; `${key:default}` takes the default when the key is not set; keys,
  defaults and values may nest. A placeholder nothing resolves (an environment variable no file
  sets, or a value that leads back to itself) is kept as written, `${TAX_SERVICE_URL}`, and its key
  is reported. Env vars are not read from anywhere: the files are what the code ships with.
- **Facts.** One attribute type, `config-value` (object kind `string`), with the module
  (`module:<groupId>:<artifactId>`) as subject: the module is where the file is, and it is the
  entity the Maven extractor already links to its service or library. The object is the resolved
  value. Attributes: `key`, the flattened key; `profile` for a profile view; `written`, the value as
  written, when it differs (it had placeholders); `unresolved`, the placeholder keys kept; and
  `masked` for secrets (below). Evidence is the file, line and content hash of the definition that
  wins. Every key of the default view is a fact; a profile view adds a fact only for a key whose
  value differs from the default view (including keys only the profile sets), so a reader takes the
  default facts and overlays a profile's. Confidence is 1.0.
- **Secrets are masked.** Documents are read by people and summaries are sent to the LLM, so a
  password written in a config file must not be copied into the knowledge store. A key names a
  secret when its last part, without list indexes, `-` and `_`, contains `password`, `passwd`,
  `secret` or `credential`, or ends in `pwd`, `token`, `apikey`, `accesskey`, `privatekey`,
  `signingkey` or `encryptionkey` (or their plurals), or when it is Spring Cloud's `encrypt.key`.
  Its value is recorded as `******` with `masked: true`, and neither `written` nor `unresolved`,
  unless it is only placeholders that nothing resolves (`${SHOP_DB_PASSWORD}`), which say where the
  secret comes from. Any value, under any key, that a secret was substituted into through a
  placeholder is masked the same way (`db.url=...?password=${spring.datasource.password}`). Keys
  merely ending in `key` (a routing key) are not secrets. Credentials written inside a URL are
  removed, and so are secret options inside other values, see the amendments below; other literal
  secrets under other keys are not detected.
- **For other extractors**, `ConfigExtractor.read(repo)` gives each module's `ModuleConfig`:
  its profiles, its values per view, and `resolve(profile, text)` for a placeholder in code, such
  as a `@Value` or a listener's destination. These are not masked; each value and resolution lists
  the keys it used, and a caller must not record a value that used a secret
  (`ConfigExtractor.isSecret`). Which module's config applies to code in a library
  (the deployable that packages it) is for those extractors to decide.
- **New vocabulary**, applied by the app on startup after the Java extractor's, with source
  `config-extractor`: the attribute type `config-value`.
- **project-0.** `Project0ConfigIT` checks the cases CFG-1, CFG-2, CFG-3 and VAL-5, and the values
  MSG-1, MSG-4, ENTRY-1, HTTP-3, HTTP-4 and DB-5 resolve through. It reads the repositories from
  git without building them, and runs when `lantern.test.project0` is set (see Commands in
  `CLAUDE.md`).

## Amendment: credentials inside URLs (#137)

- **Every URL in a value loses its credentials, the rest is kept.** A literal credential inside a
  URL under a key that is not a secret (`spring.datasource.url=jdbc:postgresql://user:pass@db/shop`)
  used to be recorded as written. Now the value and its `written` form go through
  `UrlCredentials`, shared with the persistence extractor (ADR 0036):
  - A URL starts with `jdbc:`, `r2dbc:` or any `scheme://` (`amqp://`, `redis://`,
    `mongodb+srv://`, `https://`, ...); a placeholder may be the scheme or a subprotocol
    (`${PROTO:https}://`, `jdbc:${DB_VENDOR:postgresql}://`), and a URL inside a placeholder's
    default (`${DB_URL:jdbc:...}`) is cleaned too. It ends at whitespace, or at a comma before
    another URL or before `user:password@host`, so a value may hold several.
  - It loses its user info: there is some if an `@` comes before the first `/ ? #` of the
    authority, or if the authority starts with `user:` and what follows the colon up to the first
    `/ ? #` is not a port (so `amqp://u:p#ss@rabbit` loses it, `https://medium.com/@team` and
    `http://host:8080/@team` keep their path). It ends at the last `@` before the first
    `/ ? # ,` after its first `@`, so a password holding `@` goes too, and Mongo's further hosts
    stay. Oracle's `thin:user/password@` goes too.
  - It loses every `?`, `&` or `;` property naming a user (`user`, `username`, `uid`, ...) or a
    secret, any case, `-` and `_` ignored: a name a config key would be secret by, one holding
    `password`, `passwd`, `secret`, `credential`, `signature` or `token`, or one of `pass`, `pw`,
    `sig`, `key`, `auth`, `authorization`, `passphrase` (so `api_key`, `access_key`, Azure's
    `sig`, `X-Amz-Signature`). Other properties, separators and a `#fragment` are kept as
    written; only when the property after `?` goes does the next `&` become `?`.
  - Outside URLs, a `user:password@host` token (`spring.rabbitmq.addresses=guest:guest@r1,...`)
    loses its user info.
- Host, port, path and other properties stay, as the linker needs them. A value a placeholder
  resolves into such a URL is cleaned the same way. A fact that lost a credential carries
  `masked: true`; a value without one is recorded byte for byte. A value with a URL that still
  looks like it holds a credential (`password=`, `pass=`, `sig=`, `key=` ... in a form not
  understood) is recorded as `******`, masked. The extractor's version becomes 2.
- **Not covered:** secrets in other forms, such as Kafka's
  `sasl.jaas.config=... password="x";`, are covered by the next amendment (#143).

## Amendment: secret options inside values (#143)

- **An option whose name is a secret's is removed, the rest is kept.** Kafka's
  `sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required
  username="app" password="x";` is not a URL and its key is not a secret, so the password used to
  be recorded. Now `UrlCredentials` also removes, outside URLs, every `name=value` option whose name
  a config key would be secret by (`ConfigExtractor.isSecret`, the one rule): `password`,
  `clientSecret`, `oauth.token`, `api-key`, `-Dtrust.store.password`, `${PREFIX}_password`, ...
  The value is double or single quoted, a backslash escaping the next character, when the closing
  quote ends it; otherwise it runs up to whitespace or `;`, or to a `,` or `&` followed by another
  option, so a quote or `)` inside it goes too (removing too much is the safe side); whitespace may
  surround the `=`. The option goes with one `,` or `&` separator next to it,
  or else with the whitespace before it, so several login modules, their `;` and their other
  options (`username`, `debug`) and the login module class stay:
  `...PlainLoginModule required username="app";`.
- An option whose value is only placeholders without defaults that nothing resolves
  (`password="${KAFKA_PASSWORD}"`) is kept, as a secret key's value is, since it says where the
  secret comes from. Secret options are removed from the written value before its placeholders
  are resolved, so a secret substituted through a placeholder (`password=${kafka.pw}`,
  `kafka.pw=my secret`) goes whole, whatever it holds, from both the value and `written`. A fact
  that lost an option carries `masked: true`; a value without one is recorded byte for byte. The
  config extractor's version becomes 3; the HTTP client and persistence extractors, which clean
  their URLs with `UrlCredentials`, become version 2.

## Amendment: keys the code uses as a JWT secret (#335)

- **A key that a `jwt-secret-key` fact names is a secret too.** The key rule above leaves out keys
  ending in plain `key`, so `jwt.key=<secret>` was recorded as a plain value. The identity
  extractor (ADR 0085) names the config keys the code makes an HMAC key from. The app now runs it
  before the config extractor and passes those keys to `ConfigExtractor.extract(repos, snapshot,
  secretKeys)` (`ConfigExtractor.jwtSecretKeys` reads them from the facts). A value under such a
  key, or one a value of such a key was substituted into, is recorded as `******` with `masked:
  true`, as a secret's, unless it is only placeholders nothing resolves. Keys compare without list
  indexes, `-`, `_` and case, as Spring's relaxed binding does. `extract(repos, snapshot)` alone
  masks by key name only. The config extractor's version becomes 4.

## Amendment: the other readers of config (#338)

- **The known JWT secret keys reach every extractor that records a config value.** The docs
  extractor (a README line such as `jwt.key: <value>`), the persistence extractor (datasource URLs,
  and the values substituted into them) and the Spring Cloud Stream settings of the messaging
  extractor take the same keys as `ConfigExtractor.extract(repos, snapshot, secretKeys)`, through
  an `extract` overload with a `secretKeys` argument, and judge a key with
  `ConfigExtractor.isSecret(key, ConfigExtractor.normalizedKeys(secretKeys))`. Without the keys
  they mask by key name only, as before. The docs extractor becomes version 2, the persistence
  extractor 3 and the messaging extractor 4.

## Amendment: the Spring, HTTP client and gateway extractors (#340)

- **The extractors that substitute config into code values take the known JWT secret keys too.**
  The Spring, HTTP client and gateway extractors take the keys through an `extract(code, snapshot,
  secretKeys)` overload, as the extractors of #338 do; `CodeConfig` is built with them and judges
  both a key (`isSecret`) and a resolved value (`value`) by name or by the known keys. A value
  substituted from such a key into an annotation value, a URL or a route is kept as written and
  marked masked. Without the keys they mask by key name only. The Spring extractor becomes version
  4, the HTTP client extractor 5 and the gateway extractor 2. The Redis extractor records config
  keys, never values, and the other extractors read no config.

## Amendment: defaults a library adds in code (#333)

- **An `EnvironmentPostProcessor`'s literal keys and values are config defaults.** A library such
  as project-1's `common-security` gives every service that bundles it its resource-server issuer
  from a post-processor, not from a file. The extractor now reads the classes listed under
  `org.springframework.boot.env.EnvironmentPostProcessor` in a module's `META-INF/spring.factories`,
  and those listed in a `META-INF/spring/*.imports` file that implement `EnvironmentPostProcessor`,
  from the module's `src/main/java`. In such a class it takes every pair of string literals (a
  literal, a concatenation of literals, a text block, or a `static final String` constant of the
  class) written with `Map.of`, `Map.entry`, `singletonMap`, `put`, `putIfAbsent` or
  `setProperty`, whose key looks like a property key. Computed keys or values are not read.
- **Facts.** The same `config-value` fact for the module, default view only, with the attribute
  `source: environment-post-processor` and the Java file and the line of the key as evidence.
  Placeholders resolve against the defaults themselves (`${KEYCLOAK_ISSUER_URI:http://...}` takes
  its default). The module's own `read` (for `${...}` in code, ADR 0035) does not see them, so the
  rule "a library uses the config of the one module of its repository that has config" is
  unchanged.
- **Secrets are masked as any value.** A key that names a secret, or one of the known JWT secret
  keys, or a value a secret was substituted into, is recorded as `******`, masked, whatever the
  literal holds.
- **For a unit that bundles the library** (ADR 0039), the linker treats these facts as the lowest
  precedence, below the unit's `application` and `bootstrap` files: a key the unit's own files set
  wins, and each bundled library adds its own defaults (the nearest first). They feed the
  identity-provider links of ADR 0085, so a service gets its "Validates tokens" line from a
  bundled library's issuer. The config extractor becomes version 5 and the cross-repository link
  step 13.

## Amendment: source shown to the model (#348)

- **`SourceSecrets.scrub(source, secretKeys)` also masks values under the known JWT secret
  keys.** A placeholder default (`${jwt.key:x}`) and a literal following such a key
  (`put("jwt.key", "x")`) are masked as if the key's name said secret, comparing keys as
  `ConfigExtractor.isSecret(key, ConfigExtractor.normalizedKeys(secretKeys))` does. Every path that
  puts file content in front of a model passes the snapshot's keys: `SourceFiles.secretKeys()`
  (set with `GitSourceFiles.withSecretKeys`) for the summarizer's and describer's excerpts, and the
  keys of the `jwt-secret-key` facts given to `GraphTools.Graph` for `read_file` (ADR 0081), which
  also masks `key=value` and `key: value` lines and XML under them in non-code files.
  `scrub(source)` alone masks by name only. Prompt text is unchanged.

## Amendment: a Spring Cloud Config repository (#384)

project-3 keeps its services' datasources, Eureka URL and actuator settings in a Spring Cloud
Config repository with no Maven module, which the extractor did not read. The research is in
`docs/research/extractor-config.md`.

- **Which repository.** A repository of the snapshot with no Maven module is read as a config
  repository when a module of the snapshot sets up a config server (a key under
  `spring.cloud.config.server.`, in any profile). Finding it through the server's git URI or a
  compose volume is not needed: project-3's server names a URI other than its clone, and the
  native backend reaches it through a mount. Any repository without a Maven module qualifies (a
  Gradle or frontend repository too), but only files named after an application are read from it.
  Since #516 a folder of the server's own repository is one too, when its native backend names it
  with a relative `file:` location (`search-locations: file:./config-repo`, taken from the
  server module's folder); its files are read as a repository's top files are.
- **Which files.** The files at its top named `application`, or exactly one of the names the
  snapshot's modules give themselves (`spring.application.name`, and each name of
  `spring.cloud.config.name`), each optionally followed by `-<profile>`, with extension
  `properties`, `yml` or `yaml`. A file is a profile file when its name is a known name, the
  longest that fits, followed by `-`. Names match exactly, never as substrings: Code2DFD's
  substring match gives false matches. Other files (`docker-compose.yml`, a README) are not read.
- **Facts.** Each file set an application gets is read as a module's files are, with the same
  precedence, profile views, placeholder resolution and masking. The subject is the repository
  (`repo:<name>`), not a module. The `config-value` fact records `source: config-repository` and
  `application`, which is the name the file is named after, or `application` for the files every
  application gets.
  - Placeholders resolve within that file set only. A placeholder the client would fill, such as
    `${spring.application.name}` in the shared file, stays unresolved.
  - No linker reads these facts yet, since config is looked up per module.
- **`{cipher}` values** are masked wherever they are found, also when a placeholder brings one in. The server decrypts them; the
  repository holds only ciphertext.
- **Not covered:**
  - `{application}` folders (`search-paths`) and repositories chosen by pattern
  - a native backend that serves from the server's own classpath (`search-locations:
    classpath:/shared`); with `classpath:/config`, those files are read as the server's own config
  - the label: the pinned commit stands in for the branch a server serves
  - Vault, JDBC, Consul, AWS and Azure stores, whose values are not in any repository
  - environment variables a deployment sets
- **For linking (#321).** A unit's effective config layers, lowest first:
  - its code defaults
  - its own files
  - the deployment's environment variables
  - the config repository's shared `application` files
  - the files of each of its config names, each with its profile files on top

  Config-server values outrank the unit's own files, and in bootstrap mode its environment too.

## Amendment for #558: the text as written of a value masked for its placeholders

Linking now resolves code values and reads gateway routes from the config facts (ADR 0094). It
needs the text as written of a value that is masked only because a placeholder in it brings in a
secret, such as `uri: http://edge/${app.secret-token}`. So a masked value keeps `written` when all of
these hold:
- its own key is not a secret;
- the text holds no credential in a URL and no `{cipher}`;
- no placeholder in it has a default, which may itself be a secret (`${api.token:abc123}`).

Its value stays `******`. A secret's own value, and any other masked value, keeps neither, as before.
The extractor's version becomes 9.
