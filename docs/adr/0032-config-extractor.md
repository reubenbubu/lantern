# 0032: Config extractor

Status: accepted (#126, part of #92), pending the user's review (#24)

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
  (`@PropertySource` files), `spring.config.import`ed files and config servers are not read; an
  import or config server URL is still recorded as a value.
- **Precedence**, lowest first, as Spring Boot (and Spring Cloud for bootstrap) applies it:
  bootstrap before application config; within each, files without a profile before profile files;
  `src/main/resources` before `src/main/resources/config`; `yaml`, `yml`, then `properties`; and
  the documents of a file in the order written. A later value overrides an earlier one. So a name
  set only in `bootstrap.yml` holds, and one in `application.yml` wins over it.
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
  merely ending in `key` (a routing key) are not secrets. Literal secrets under other keys, such as
  a password written inside a URL, are not detected.
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
