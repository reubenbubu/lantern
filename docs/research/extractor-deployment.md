# What deployment descriptors say about a Java system's containers, configuration and routing

Research for #397 (Review the Deployment extractor), milestone "Extractor revisit". It asks which
deployment descriptors Java (Maven, Spring Boot) systems ship, what each says about containers,
configuration (environment, active Spring profiles, command lines) and inbound routing, and what
the Deployment extractor of `docs/adr/0082-deployment-descriptors.md` should add or skip. It leads
to an amendment of that ADR.

**Depth: high.** Read in full, 28 sources:

- 6 Spring Boot and Spring Cloud Kubernetes reference pages;
- 4 Docker and Compose reference pages;
- 6 Kubernetes pages (Ingress, DNS, environment, command and arguments, ingress-nginx rewrite,
  the ingress-nginx retirement post) and 2 Gateway API pages;
- 5 pages on kustomize, Helm, Jib, Paketo and the `java` launcher, and the AWS ECS task
  definition reference;
- 4 papers (Code2DFD, a nine-tool comparison, ModARO, Baresi et al.).

Pages were read through a summarising fetch tool. Not confirmed on a page read: the pre-3.2
`JarLauncher` package name, `JAVA_TOOL_OPTIONS` being read by the JVM itself, the HTTPRoute
`backendRef` kind and namespace defaults, the pre-1.22 Ingress `serviceName` form, the
`helm create` scaffold keys, and everything about Terraform, Cloud Run, Container Apps, App
Engine, Procfile, fly.toml, Skaffold and Tilt. The research is generic: it does not read Lantern's
test sets.

## Findings

### How a container gets its Spring configuration

- **Precedence**, lowest first: config data files, OS environment, Java system properties (`-D`),
  `SPRING_APPLICATION_JSON`, command-line arguments (`--key=value`). With several active profiles
  the last wins. [Spring Boot external config]
- **Active profiles** come from `SPRING_PROFILES_ACTIVE`, `-Dspring.profiles.active` (before
  `-jar`), `--spring.profiles.active` or `spring.profiles.active` inside `SPRING_APPLICATION_JSON`;
  the precedence above decides between them. [Spring Boot properties how-to, external config]
- **Environment names bind loosely**: `.` becomes `_`, `-` is dropped, all uppercase
  (`SPRING_DATASOURCE_URL` is `spring.datasource.url`). The mapping cannot be reversed, so an
  environment name is matched against known property names, not un-mangled. [Spring Boot external
  config]
- **JVM option variables.** `JDK_JAVA_OPTIONS` is read by the `java` launcher and
  `JAVA_TOOL_OPTIONS` by the JVM, so a `-D` in either takes effect with any entrypoint. `JAVA_OPTS`
  is read by neither: it counts only where a shell-form command expands it. [java man page, Paketo]

### Dockerfile

- Only the **last stage** is the runtime image; it has the `ENV`, `EXPOSE`, `ENTRYPOINT` and `CMD`
  set from its `FROM` on, plus those of the earlier stage it is built `FROM`, if any. `ENV` has a `key=value` form and a legacy `key value` form. [Dockerfile
  reference]
- **ENTRYPOINT and CMD**: exec form (JSON) runs no shell, so `$JAVA_OPTS` stays literal; shell form
  runs under `/bin/sh -c`. An exec-form `ENTRYPOINT` takes `CMD` as arguments, and setting
  `ENTRYPOINT` resets an inherited `CMD`. [Dockerfile reference]
- Spring Boot's own recommended Dockerfile is multi-stage and ends in
  `ENTRYPOINT ["java","-jar","application.jar"]`, setting no profile. [Spring Boot Dockerfiles]

### Compose

- `env_file` paths are relative to the compose file; later files win and `environment` beats
  `env_file`. The project `.env` only fills `${VAR}` in the compose file itself. [Compose
  services, interpolation]
- `command` overrides the image's `CMD`, `entrypoint` its `ENTRYPOINT` (and drops `CMD`); neither
  runs through a shell. [Compose services]
- Several compose files merge by key for maps and concatenate ports; relative paths resolve
  against the first file. [Compose merge]

### Kubernetes

- `command` replaces the image's `ENTRYPOINT`, `args` its `CMD`; `args` alone is the usual way to
  pass `--spring.profiles.active=k8s` to a Jib or buildpack image. `$(VAR)` in them expands from
  the container's `env`. [K8s command and arguments, environment]
- **Ingress**: `rules[].host` is optional (all hosts without it); each path has `path`, `pathType`
  and a backend Service with a port number or name. The API is frozen and Gateway API is its
  successor; ingress-nginx was retired in March 2026, but its `rewrite-target` annotation, which
  makes the backend path differ from the external one, stays in repositories for years. [K8s
  Ingress, ingress-nginx rewrite, retirement post]
- **HTTPRoute** (Gateway API, GA): `parentRefs` name the Gateway, `hostnames` match first, a rule
  with no `matches` is PathPrefix `/`, and `URLRewrite` filters change the path; `backendRefs`
  carry name, port and weight. [Gateway API HTTPRoute, HTTP routing guide]

### Images built from `pom.xml` and other descriptors

- **Jib** (`<container>`: `environment`, `jvmFlags`, `args`, `ports`, `to.image`) and Spring
  Boot's **`build-image`** (`<image><name>`, default `docker.io/library/${artifactId}:${version}`;
  `BPE_APPEND_JAVA_TOOL_OPTIONS`) build images with no Dockerfile; the image name is what ties a
  compose or Kubernetes `image:` back to the module. Paketo's `BP_*` and `BPL_*` variables are
  build and JVM tuning, not architecture. [Jib README, build-image, Paketo]
- **ECS task definitions** carry what compose does per container; Terraform alone gives versions
  of managed databases, but is HCL with variables, usually in a separate repository. [ECS task
  definition parameters]

### Prior art

- Code2DFD and MicroDepGraph read compose and Kubernetes files for services and classify data
  stores by known images; deployment files are "simple, unambiguous documents" but "contain
  limited information", so source analysis is needed for recall. None of nine compared tools
  recovers environment, profiles or ingress. [Code2DFD, nine-tool comparison]
- ModARO could not express service hosts set in another repository's environment and asks for
  link values "resolved retroactively". [ModARO]
- No work found resolves active profiles or Spring's property precedence per container: Lantern
  would go beyond the prior art there.

## Options

1. **Deepen what is already read** (Dockerfile, compose, Kubernetes) with configuration and
   routing. The parsers exist; the risk is in precedence, which the sources settle.
2. **Images from `pom.xml`** (Jib, `build-image`): ties image names to modules without a
   Dockerfile. Belongs to the Maven extractor's facts and the linker.
3. **Gateway API HTTPRoute** next to Ingress: a small schema, the stated successor.
4. **Cloud descriptors** (ECS, Cloud Run, Terraform): one parser each, low prevalence, often in
   other repositories.
5. **Render Helm and kustomize**: exact, but needs their binaries and a choice of values, against
   ADR 0082's read-without-rendering rule.

## Decision

Take options 1 and 3 now, in the Deployment extractor; the rest goes to issues in `Later`.

- **Each container's Spring settings**: the effective environment (compose `env_file` included),
  the Spring properties set by its command line, its JVM option variables and
  `SPRING_APPLICATION_JSON`, in Spring's precedence order, and its active profiles. Command lines
  come from the Dockerfile's final stage, compose `command`/`entrypoint` and Kubernetes
  `command`/`args` (with `$(VAR)`), read only when they run Java or are bare arguments to the
  image. Secrets stay masked as before.
- **Inbound routes**: one fact per Ingress path and HTTPRoute match, with host, path, path type,
  backend Service and port, the class or Gateway, and a rewrite where nginx's annotation or a
  `URLRewrite` filter sets one.
- Linking these (profiles and properties into config layering, routes to units) is the linker's,
  and is left to follow-up issues so each change shows its own effect on the report.

### Evidence from the development sets

- project-3's `mediahub-infra/docker-compose.yml` sets `SPRING_PROFILES_ACTIVE=native` on its
  config server: now an active profile of that container.
- project-4's kustomize `base/ingress.yml` routes `www.aappaa.com/` to `gateway-service`: now a
  route fact. Its Helm chart's Ingress renders `cors-allow-origin: *` unquoted, which is not valid
  YAML (Helm would reject it too), so it gives none.
- No development set uses `env_file`, HTTPRoute, a Java command line with `-D` or `--` settings, or
  Jib; the fixture covers them.

## Sources

- Spring Boot, Externalized Configuration: https://docs.spring.io/spring-boot/reference/features/external-config.html
- Spring Boot, Properties and Configuration how-to: https://docs.spring.io/spring-boot/how-to/properties-and-configuration.html
- Spring Boot, Dockerfiles: https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html
- Spring Boot, Launching executable jars: https://docs.spring.io/spring-boot/specification/executable-jar/launching.html
- Spring Boot Maven plugin, build-image: https://docs.spring.io/spring-boot/maven-plugin/build-image.html
- Spring Cloud Kubernetes, ConfigMap property source: https://docs.spring.io/spring-cloud-kubernetes/reference/property-source-config/configmap-propertysource.html
- Dockerfile reference: https://docs.docker.com/reference/dockerfile/
- Compose services: https://docs.docker.com/reference/compose-file/services/
- Compose merge: https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/
- Compose interpolation: https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/
- Kubernetes Ingress: https://kubernetes.io/docs/concepts/services-networking/ingress/
- Kubernetes DNS for Services and Pods: https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/
- Kubernetes, environment variables: https://kubernetes.io/docs/tasks/inject-data-application/define-environment-variable-container/
- Kubernetes, command and arguments: https://kubernetes.io/docs/tasks/inject-data-application/define-command-argument-container/
- Ingress NGINX retirement: https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/
- ingress-nginx rewrite: https://kubernetes.github.io/ingress-nginx/examples/rewrite/
- Gateway API HTTPRoute: https://gateway-api.sigs.k8s.io/reference/api-types/httproute/
- Gateway API HTTP routing: https://gateway-api.sigs.k8s.io/guides/http-routing/
- kustomize configMapGenerator: https://kubectl.docs.kubernetes.io/references/kustomize/kustomization/configmapgenerator/
- Helm values best practices: https://helm.sh/docs/chart_best_practices/values/
- Jib Maven plugin: https://github.com/GoogleContainerTools/jib/blob/master/jib-maven-plugin/README.md
- Paketo Java how-to: https://paketo.io/docs/howto/java/
- `java` launcher (JDK 21): https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html
- AWS ECS task definition parameters: https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html
- Schneider and Scandariato, Code2DFD: https://arxiv.org/pdf/2304.12769
- Comparison of nine architecture recovery tools: https://arxiv.org/html/2412.08352
- Manglaras et al., ModARO: https://arxiv.org/html/2602.08181
- Baresi et al., Docker configuration files in microservice repositories: https://arxiv.org/pdf/2212.03107
