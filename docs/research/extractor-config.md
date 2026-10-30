# Config in Java systems: what the Config extractor reads

Research for #384, the review of the Config extractor in the milestone "Extractor revisit". It
leads to the amendment of `docs/adr/0032-config-extractor.md` for #384.

**Depth: high.** It decides where a service's databases, brokers and peers come from in a system
that splits its config across repositories, and a wrong rule misplaces them in the report. 23
pages were read in full, through the web-fetch tool, which hands the reader each page's full text
to work from.

## Question

Where do Spring Boot systems keep the config that names their databases, brokers, peer services
and URLs? How does Spring resolve the value that applies? Which of these places should a static
extractor read, and how should it tie each value to the service it configures?

## What we learned

**Spring Boot's own order**

- Sources rank from lowest to highest: default properties, `@PropertySource`, config files, OS
  environment variables, Java system properties, `SPRING_APPLICATION_JSON`, then command-line
  arguments.
  <https://docs.spring.io/spring-boot/reference/features/external-config.html>
- Among config files, from lowest: `application`, then `application-{profile}`, both in the jar,
  then the same two outside it. On the classpath, `/config` ranks above the root, and
  `.properties` above YAML in the same place. With several profiles active, the last one wins.
  (same page)
- **Imports.** A file brought in by `spring.config.import` acts as a document just below the file
  that imports it, and wins over that file. Prefixes:
  - `optional:` skips a missing file.
  - `configtree:` reads a directory of files, one key per file (Kubernetes volumes, Docker
    secrets).
  - `env:` reads a whole file from one variable.

  (same page)
- **Environment variables** bind by a fixed rule: `.` becomes `_`, dashes are dropped, and the
  name is uppercased. So `SPRING_DATASOURCE_URL` sets `spring.datasource.url`. (same page)
- **Profiles.** `spring.profiles.active`, `include` and `group` are allowed only in documents
  that are not profile-specific. Spring Boot 2.4 renamed the document key `spring.profiles` to
  `spring.config.activate.on-profile`.
  <https://docs.spring.io/spring-boot/reference/features/profiles.html>,
  <https://spring.io/blog/2020/08/14/config-file-processing-in-spring-boot-2-4>

**Spring Cloud Config Server**

- The server serves an `{application}/{profile}/{label}` triple.
  - Files apply from lowest: the shared `application.yml`, then `application-{profile}`, then
    `{app}.yml`, then `{app}-{profile}`.
  - A client names itself with `spring.application.name`, or overrides the name with
    `spring.cloud.config.name`, which may list several names (`${spring.application.name},cassandra`).

  <https://docs.spring.io/spring-cloud-config/reference/server/environment-repository.html>,
  <https://docs.spring.io/spring-cloud-config/reference/client.html>
- **Git backend.**
  - The URI may contain `{application}`.
  - `search-paths: '{application}'` adds a folder per app as well as the top level.
  - `repos` with patterns route apps to different repositories.
  - The default label is `main`, falling back to `master`.

  <https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/git-backend.html>
- The native backend reads `search-locations`. The server's own `application.*` is not served to
  clients.
  <https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/file-system-backend.html>
- `{cipher}…` values are decrypted on the server.
  <https://docs.spring.io/spring-cloud-config/reference/server/encryption-and-decryption.html>
- **Remote values win over local ones.** In bootstrap mode they "cannot be overridden locally"
  unless the remote side allows it (`allowOverride`).
  <https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html>
- **Overrides and placeholders.** Server-wide `overrides` apply to every client. Placeholders in
  remote values are resolved by the client.
  <https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/property-overrides.html>,
  <https://docs.spring.io/spring-cloud-config/reference/server/serving-alternative-formats.html>
- The Vault and JDBC backends keep values outside any repository.
  <https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/vault-backend.html>,
  <https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/jdbc-backend.html>

**Other places**

- **Compose**, from highest: `run -e`, then an interpolated `environment` or `env_file` value,
  then a literal `environment` value, then a literal `env_file` value, then the Dockerfile's
  `ENV`. A `.env` file reaches the container only through the compose file.
  <https://docs.docker.com/compose/how-tos/environment-variables/envvars-precedence/>
- **Spring Cloud Kubernetes** (`spring.config.import: kubernetes:`) reads ConfigMaps named after
  the app. More common is `envFrom`, which turns a ConfigMap into environment variables.
  <https://docs.spring.io/spring-cloud-kubernetes/reference/property-source-config.html>,
  <https://docs.spring.io/spring-cloud-kubernetes/reference/property-source-config/configmap-propertysource.html>
- **Consul** layers `config/{name},{profile}/` over `config/application/`.
  <https://docs.spring.io/spring-cloud-consul/reference/config.html>
- **Azure App Configuration** and AWS Parameter Store keep values in the cloud. Only the import
  line is in the repository.
  <https://learn.microsoft.com/en-us/azure/azure-app-configuration/quickstart-java-spring-app>

**Other tools**

- **Code2DFD** (`technology_specific_extractors/spring_config/cnf_entry.py`, read in source):
  - It finds a config server by `@EnableConfigServer`.
  - It ties a file to a service when the file name equals the service name, or merely contains it.
  - It ignores the shared `application.yml`, profiles and precedence.

  <https://github.com/tuhh-softsec/code2DFD>
- **The nine-tool comparison** finds that deployment files give high precision but miss things.
  Config servers are not studied. <https://arxiv.org/html/2412.08352v1>
- **ModARO** merges multiple repositories by name. **jQAssistant's Spring plugin** reads
  annotations only. <https://arxiv.org/html/2602.08166v1>,
  <https://github.com/jqassistant-plugin/jqassistant-spring-plugin>
- **Spring Boot's configuration metadata** lists every known key, with its type.
  <https://docs.spring.io/spring-boot/specification/configuration-metadata/format.html>

## Evidence from the test sets

- **project-0.** Module files only, no config server.
- **project-1 and project-2.** Services use placeholders, such as
  `${DATABASE_URL:jdbc:postgresql://localhost:5433/profile_db}`, which `compose.yaml` fills with
  the real host. ADR 0082 already fills datasource placeholders from compose.
- **project-3.** `mediahub-config-repo` holds `application.yml`, `user-service.yml`,
  `media-service.yml`, `subscription-service.yml` and `api-gateway.yml` at its top, with no pom.
  - The config server names a git URI other than the pinned clone. Compose runs it with the
    native backend, mounting the repository as `/config`.
  - The clients use bootstrap mode with profile `dev`, and there are no `*-dev` files.
  - user-service's own file defaults its datasource to `localhost:5432/user_db`. The remote file
    says `mediahub_user` on `${DB_HOST:user-db}`. Remote wins.
  - The shared `application.yml` holds the Eureka URL and the actuator settings.
- **project-4.** Values arrive as environment variables from a shared Kubernetes ConfigMap
  (`envFrom`), with Helm and compose too.

Before this change the config area met 57 of 67 needs. All 10 misses were in project-3's config
repository.

## What the plan missed

The plan was to read the config repository's files and tie them to services by file name. It
missed these:

1. **The shared `application.yml`.** It applies to every client. Code2DFD drops it too.
2. **Precedence.** Remote values outrank the service's own files, and in bootstrap mode its
   environment variables too.
3. **Placeholders resolve in the client.** A remote value is resolved in the service's merged
   view.
4. **Finding the repository.** The server's URI may not match the clone, and the native backend
   reaches it through a mount. `search-paths` and patterns change which files apply.
5. **The label.** A server serves a branch, while Lantern reads the pinned commit.
6. **Matching by substring** (Code2DFD) gives false matches.

## Options

- **A. Read the config repository's files by exact name, in the extractor.** Cheap, and it backs
  project-3's items. On its own it leaves the service with two datasources until linking layers
  them.
- **B. A, plus Spring's layering per unit, in the linker.** Right for project-1 to project-4.
  Needs the merge step in `LinkIndex`.
- **C. B, plus environment variables for every address-like key.** Covers project-4's
  `RABBIT_HOST` and project-2's Kafka servers.
- **D. External stores** (Vault, Consul, AWS, Azure) recorded as a named source with no value.

## Recommendation

A in the extractor now (#384). B and C belong to linking, in #321, which already owns joining
the config repository to its services. D waits until a test set has an external store.

The repository is recognised by shape, not by the server's URI, because project-3's URI does not
match its clone. Shape means a repository with no Maven module in a snapshot whose modules set up
a config server, with files named `application` or exactly a known application name.

**Rule:** a service's config is everything Spring would layer for it, not only its own module's
files. Lantern reads a Spring Cloud Config repository's files, tied to applications by exact name
(the shared `application` files to every one), with their file and line. It never reads a
`{cipher}` value. Linking layers them per unit, lowest first:

1. its code defaults;
2. its own files;
3. the deployment's environment variables;
4. the config repository's shared files;
5. the files of each of its config names, each with its profile files on top.

## What it changes

- `docs/adr/0032-config-extractor.md`, amended for #384.
- `docs/design.md`, the Config row of the extractor table.
- `docs/quality-bar.md`: nothing. Its sentence on config served from a central server is what this
  starts to meet.

## Sources read in full

- Spring Boot, externalized configuration; profiles; config file processing in 2.4; configuration
  metadata format
- Spring Cloud Config:
  - environment repository; git, file-system, Vault and JDBC backends
  - property overrides; alternative formats; encryption and decryption
  - client
- Spring Cloud Commons, application context services
- Spring Cloud Kubernetes, property sources and ConfigMap property source
- Spring Cloud Consul, config
- Docker, environment variable precedence
- Microsoft, Azure App Configuration quickstart for Spring
- arXiv 2412.08352 (the nine-tool comparison); arXiv 2602.08166 (ModARO)
- jQAssistant Spring plugin README
- Code2DFD source: `cnf_entry.py` and `environment_variables.py`

## Sources not read or read only in part (carry no weight)

- Code2DFD's paper, arXiv 2304.12769: the last part not read.
- Spring Cloud AWS reference: sections 10 and 11 only.
- Spring Boot's configuration metadata index: a navigation page only.
- CLAIM (EASE 2024), MicroART, Bushong and Das: search snippets only.
