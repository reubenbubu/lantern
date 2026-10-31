# 0082: Deployment descriptors

Status: accepted (#266), amends ADR 0042 and ADR 0073, amended by ADR 0086 (#249), #397 and #516

## Context

The design's Deployment extractor (Static analysis, Initial extractors) reads Dockerfiles, compose
files, Helm charts, kustomize and Kubernetes manifests, and the Deployment view is built from its
facts. ADR 0070 moved it into Phase 2 (#266). Real systems leave what their config connects to
until deployment: project-4's services write `jdbc:postgresql://${auth.datasource.host}:${...port}/${...database}`
and their Deployment repository fills the placeholders, per environment, from compose files, a
kustomize overlay and a Helm chart. Until now such a datasource became a database of its unit
named after the unit ("auth-service database", #255), with no version, while the reference names
auth_db, main_db and file_db on PostgreSQL 17.5. The Redis extractor read compose files itself
for the Redis store's version (ADR 0073), which the Deployment extractor was to absorb.

## Decisions

- **The Deployment extractor (`deployment`, version 1)** reads every repository of the snapshot,
  with or without a build, from git at the commit; it runs and renders nothing with a tool. Files
  under `src/test/`, `node_modules/`, `target/` and test fixtures are skipped. Its facts are on
  the repository (`repo:<name>`, ADR 0073):
  - `dockerfile`, valued with the file's path: the last stage's base image (`from`, `ARG`
    defaults filled in, a stage built on an earlier stage taking that stage's base image), the product and version it names (`technology`) and the ports it exposes.
  - `deployment-container`, valued with the container's name, one per container an environment
    runs: its `platform` (`compose`, `kubernetes`, `helm`) and `environment`, `kind` (`service`
    or the workload kind), `image` and the `product`, `version` and `role` it names (a table of
    known images: databases, caches, brokers, identity providers, proxies, observability, mail,
    storage and runtimes; the version is the tag's leading number), a compose `build`, the
    `hosts` other containers reach it by, other `aliases` (app labels, container name), its
    environment variables (`env`), `masked` variables, `ports`, `dependsOn`, `composeProfiles`,
    the `databases` it creates and its `replicas`.
- **Environments.** A compose file is one (`docker-compose.local.yml` is `local`, a plain one
  `default`); variables come from a committed `.env` beside it, else their defaults
  (`${X:-d}`), else stay `${X}` so that two services sharing a variable can be tied. Each
  kustomization no other one builds on is one, named by its folder, with the resources (patches are not resources),
  `configMapGenerator` (`envs`, `env`, `literals`, `files`) and `images` of the kustomizations it
  builds on. A Helm chart is one per values file (`values.yaml`, then each `values-<env>.yaml`
  merged over it); its templates are read by filling value actions (`.Values`, `.Chart`,
  `.Release`, literals, `printf` and simple pipeline functions such as `default` and `quote`) and
  blanking control lines, both branches of an `if` kept and nothing repeated for a `range`, so
  line numbers stay the template's. Kubernetes manifests that no kustomization or chart uses are
  one environment, `default`. YAML is read as YAML 1.1 without base-60 numbers, so a port `22:22`
  stays as written. Two environments of one platform with the same name (two `prod` overlays) are
  not told apart in the table. A container's environment variables include the ConfigMaps it takes
  them from; Kubernetes Secrets are never read.
- **Secrets.** A variable whose name reads as a secret (password, secret, token, credential,
  private, API or access key, signing key) is listed in `masked` without its value, as is one
  taken from a Secret, one whose value writes a secret out (`-Dapp.password=x`,
  `?password=x`) and one holding a whole file from a ConfigMap; credentials in a URL are left out; `.env` variables named so are not
  substituted.
- **Databases a container creates**: the database an official image's variable names
  (`POSTGRES_DB`, `MYSQL_DATABASE`, `MARIADB_DATABASE`, `MONGO_INITDB_DATABASE`) when it is
  written out, and those `CREATE DATABASE` creates in its init scripts (a compose bind mount into
  `/docker-entrypoint-initdb.d`, a ConfigMap mounted there).
- **Containers and units.** The linker (`cross-repo-links`) ties a container to a deployable unit
  when one of the container's names (value, hosts, aliases, the image's name) is the unit's
  `spring.application.name` or artifact ID, compared without case and other characters than
  letters and digits, and no other unit matches; a container whose image is infrastructure (a
  database, cache, broker, identity provider, discovery, observability, mail or storage product)
  runs none, while one on a runtime or proxy image (`eclipse-temurin`, `node`, `nginx`) may. Each tie is a `deployed-in` fact on the unit, valued with the environment
  (`Docker Compose (local)`), naming the container and its fact.
- **Placeholders the environment fills.** For a datasource whose server or database is a
  placeholder no config resolves, each container that runs the unit fills the placeholders from
  its environment variables, as Spring Boot's relaxed binding reads them (`AUTH_DATASOURCE_HOST`
  sets `auth.datasource.host`). The database's server is the container of the same environment
  whose hosts include the filled host (a Kubernetes `*.svc` name by its first label), or, when the database is a compose variable, the container
  whose image variable (`POSTGRES_DB`...) is the same variable; only containers of the datasource's
  product (or of no known product) count, and only a single match is taken. The database's name is the filled
  one when written out, else the server's only database, else the one of the server's databases
  whose name shares a word with the unit's names or the placeholder's key (`auth_db` for
  `auth-service` and `auth.datasource.database`), generic words such as `db` and `service`
  ignored; that choice is `inferred` and has 0.8 of the confidence. The database stays the unit's
  own (ADR 0042 as amended by #255): its key does not change and it is never shared; its
  `display-name` is that name, its `technology` the URL's product with the versions of the server
  containers, and its `USES_DATABASE` link lists `deployments` (per environment: the unit's
  container, the server container, the address and the database) with their facts. Each server
  container is a `deployed-in` fact on the database. Values already set in config are not
  overridden by the environment, and a whole URL left to one placeholder is not filled.
- **Versions.** A store's technology is its product with the version its containers run, or the
  versions from oldest to newest when they differ (`Redis 7.4.4 / 8.4.0`), or the product alone
  when no tag names one. This replaces ADR 0073's rule of the product alone when versions differ.
- **Redis** takes its technology from the Deployment extractor's Redis, Valkey and Redis Stack
  containers (those of the unit's own repository, else any in the snapshot, as before), and its
  store gets a `deployed-in` fact for each. The Redis extractor (`redis`, version 2) no longer
  reads compose files, and its `redis-image` facts are gone, so images are read in one place.
- **The container view's Deployment table.** The C4 views (ADR 0044) add, after the container
  diagram, a table of the scope's deployment containers: environment, container, image (or what
  it is built from), technology, what runs in it (the units and data stores tied to it) and ports,
  each row citing its facts. It is the design's Deployment view in table form; a drawn deployment
  diagram with nodes waits for a page level for it in the report spec, which is the user's.

## Consequences

- project-4's three datasources are named auth_db, main_db and file_db, from the kustomize
  overlay's shared PostgreSQL whose init script creates them, with PostgreSQL 17.5; each compose
  file runs each in a container of its own, tied by the variable the service and the database
  container share. Its Redis is `Redis 7.4.4 / 8.4.0`, and the Deployment table lists the compose
  (local and dev), kustomize (dev) and Helm environments with their containers, RabbitMQ 3 in
  compose and 4 in Kubernetes among them.
- Brokers stay unlinked when their host is a placeholder (ADR 0042); their containers and
  versions show in the Deployment table only.
- `cross-repo-links` is raised to version 9 and `redis` to 2; `deployment` starts at 1.

## Not verified

Helm templates that build names or images with helpers, `range` or `include` are only partly
read; neither are `env_file`, compose `extends` and overrides across several compose files, nor
Kustomize patches. Only project-4 among the test sets fills datasource placeholders from its
deployments.

## Amendment (#397): each container's Spring settings, and inbound routes

The review of the extractor and its research note (`docs/research/extractor-deployment.md`) found
that deployments set a container's Spring configuration in more places than its `environment`, and
that Ingress and Gateway API routes, which say how traffic enters the system, were not read.
Extractor `deployment` version 2.

- **Environment.** A compose service's `env_file` (a path, a list, or entries with `path`, read
  relative to the compose file) is part of its environment; `environment` wins over it. A
  Dockerfile's `ENV` (both forms) counts for the final stage: a `FROM <stage>` inherits that stage's
  `ENV`, `EXPOSE`, `ENTRYPOINT` and `CMD`, while any other `FROM` starts afresh, so a build stage's
  settings are not the runtime image's.
- **Command lines.** A container's command is read from the Dockerfile's last `ENTRYPOINT` and `CMD`
  (exec or shell form; `ENTRYPOINT` resets an inherited `CMD`, not one set in its own stage, and a
  shell-form `ENTRYPOINT` ignores `CMD`), compose `entrypoint` and `command`, and Kubernetes
  `command` and `args` with `$(VAR)` filled from its `env`. An `sh -c` script is split into words.
  On a `java` command line, `-D` options count only before the main class or `-jar`'s file, and `--`
  arguments only after it, so `--add-opens` is not a property. The command counts only when it runs
  Java, or when it is bare arguments (its first word starts with `-`) to an image that is not a
  known product, so a Redis `--requirepass` is not a Spring setting.
- **Spring properties and profiles.** Each `deployment-container` and `dockerfile` fact gets
  `properties`: the Spring properties its deployment sets, in Spring's order, lowest first: `-D`
  options in `JAVA_TOOL_OPTIONS` and `JDK_JAVA_OPTIONS` (and in `JAVA_OPTS` only when the command
  expands it), the command's `-D` words, `SPRING_APPLICATION_JSON` flattened to keys, the command's
  `--` words. `activeProfiles` is `spring.profiles.active` from those properties, else
  `SPRING_PROFILES_ACTIVE`. Values with unresolved `${...}` or `$(...)` are kept as written, for the
  linker to fill; `activeProfiles` leaves them out. Secret keys and values are masked as for the
  environment, a JSON `"password": ...` included, and a known product's image (Kafka, Keycloak) gets
  no properties from its JVM option variables.
- **Routes.** A `deployment-route` fact is recorded for each path of an Ingress rule and its default
  backend (with the pre-1.22 `serviceName`/`servicePort` and `spec.backend` forms too) and each
  match of an HTTPRoute rule (a rule without matches, or a match without a path, is PathPrefix `/`).
  Its value is the host, or `*`, followed by the path; it records the platform, environment, kind
  and name, the ingress class or Gateway, host, path, path type, backend Service and port, and the
  rewrite set by `nginx.ingress.kubernetes.io/rewrite-target` or a `URLRewrite` filter. It cites the
  path's line.
- **Not yet linked.** Using a container's properties and profiles when layering its unit's config,
  and tying routes to units, is the linker's (#502, #503); images built by Jib or `build-image`
  (#504) and compose override files and `extends` (#505) follow too; this version records the facts
  only, so the report does not change yet.

## Amendment (#516): replicas from kustomize patches, and Helm `range`

project-8 (FleetCare) runs its prod services with 2 replicas through a kustomize patch, and builds
its Helm chart's Deployments with a `range` over the services in its values; Lantern recorded 1
replica for each and no Helm container at all.

- **Kustomize patches** of an overlay (`patches`, `patchesJson6902` and `patchesStrategicMerge`,
  inline or in a file) set `replicas` on the resources their `target` selects (kind, name and a
  `labelSelector` of `k=v` pairs), or on the resource a strategic merge patch names: a JSON patch's
  `replace`/`add` of `/spec/replicas`, or a merge patch's `spec.replicas`. Nothing else in a patch is
  applied.
- **Helm `range`.** A `range` at a template's top level over a map or list of the values
  (`range $name, $service := .Values.services`) is rendered once per entry, with its variables,
  `.` and `$` (`$.Values...`) bound, each rendering keeping the template's line numbers; the rest
  of the template is rendered once with the range blanked. A `range` inside another block, or over
  anything else, is still blanked with its body kept once.
