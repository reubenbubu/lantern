# Which Maven modules are deployable units

Research for #382 (Review the Maven extractor), milestone "Extractor revisit". It asks what in a multi-module Maven build defines what is deployed and how, and which signals decide whether a module is a deployable unit rather than a library. It leads to an amendment of `docs/adr/0006-maven-extractor.md` and the Maven row of `docs/design.md` (Static analysis and extractors).

**Depth: high.** Which modules are drawn as containers rests on it. Read in full, 23 sources:

- 13 official Maven and Maven plugin pages;
- 4 Spring Boot sources;
- 4 container and build tool pages (Jib, Paketo, Skaffold);
- 2 papers (Code2DFD with its Maven extractor's source, Claim).

Partly read and carrying no weight: Quarkus Maven tooling, Eclipse JKube, the Micronaut plugin index and Spotify's dockerfile-maven. The research is generic: it does not read Lantern's test sets.

## Question

In real multi-module Maven builds, what defines what is deployed and how? Which signals decide whether a module is a deployable unit (drawn as a C4 container) rather than a library?

## What we learned

**Packaging is necessary but never sufficient.**
- Core packaging values are `pom`, `jar`, `maven-plugin`, `ejb`, `war`, `ear`, `rar`; with none declared, Maven assumes `jar`. `pom` is required for parents and aggregators (https://maven.apache.org/pom.html).
- `pom` packaging binds goals only to `install` and `deploy`, so it never yields a runnable artifact (https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html).
- `war` and `ear` are deployable by construction.
- `jar` is the ambiguous case: libraries, Spring Boot apps, Quarkus apps and CLIs all use it.
- Packaging can also be a framework type or a property:
  - Quarkus asks application modules to declare `<packaging>quarkus</packaging>`, and says not to use it in library modules (https://quarkus.io/guides/maven-tooling).
  - Micronaut's recommended POM writes `<packaging>${packaging}</packaging>` with a `packaging` property (default `jar`), so the real type is known only after interpolation (https://micronaut-projects.github.io/micronaut-maven-plugin/latest/index.html, partly read).
- A reader that matches only the literal `jar|war|ear` text therefore misclassifies whole frameworks.

**Spring Boot: repackage is the strongest signal, and it is opt-in per module.**
- `spring-boot-starter-parent` puts `spring-boot-maven-plugin` with a `repackage` execution under `pluginManagement`. It also puts there `maven-jar-plugin`/`maven-war-plugin` with `mainClass=${start-class}`, a shade configuration, git-commit-id, CycloneDX `makeAggregateBom`, and `java.version=17` with `maven.compiler.release=${java.version}` (https://github.com/spring-projects/spring-boot/blob/main/starter/spring-boot-starter-parent/build.gradle; https://docs.spring.io/spring-boot/maven-plugin/using.html).
- `pluginManagement` "only configures plugins that are actually referenced within the `plugins` element in the children or in the current POM" (https://maven.apache.org/pom.html).
- So a module that inherits the starter parent becomes an executable jar only if it, or an intermediate parent, lists `spring-boot-maven-plugin` under `<build><plugins>`.
- The reverse risk: a corporate parent that lists the plugin under `<build><plugins>` (not `pluginManagement`) makes every child repackage, libraries included, unless they opt out.
- Importing `spring-boot-dependencies` as a BOM gives "dependency management (but not the plugin management)". Without the parent you must declare the `repackage` execution yourself (https://docs.spring.io/spring-boot/maven-plugin/using.html).
- A Spring Boot BOM or `spring-boot-starter-*` dependencies say nothing about deployability.
- Options of `repackage` (https://docs.spring.io/spring-boot/maven-plugin/packaging.html):
  - `skip` (user property `spring-boot.repackage.skip`, default `false`) turns it off.
  - With a `classifier` (such as `exec`), the original jar stays the main artifact and the executable one is attached beside it. The docs recommend this for modules "consumed as a dependency", because classes in `BOOT-INF/classes` cannot be loaded by dependents. A classifier therefore marks a module that is both an app and a library.
  - `attach=false` builds the executable jar locally but neither installs nor deploys it.
  - `mainClass` defaults to "the first compiled class containing a `main` method".
  - `layout` can be `NONE`, which bundles dependencies and is "not executable".
  - Layers (`layers.idx`) are on by default and aimed at container images.
- `build-image` / `build-image-no-fork` builds an OCI image with buildpacks. The image name defaults to `docker.io/library/${project.artifactId}:${project.version}`, the skip property is `spring-boot.build-image.skip`, and the Java version comes from the compiler configuration (https://docs.spring.io/spring-boot/maven-plugin/build-image.html).

**Other fat-jar and main-class builders.**
- Shade: `ManifestResourceTransformer` writes `Main-Class` into the shaded jar (https://maven.apache.org/plugins/maven-shade-plugin/examples/executable-jar.html).
- Assembly: `jar-with-dependencies` plus `<archive><manifest><mainClass>` gives an executable jar with classifier `jar-with-dependencies`, bound with `single` at `package` (https://maven.apache.org/plugins/maven-assembly-plugin/usage.html).
- Neither plugin is proof on its own. The starter parent preconfigures shade in `pluginManagement`, and shade is also used to relocate library dependencies with no main class at all.
- Quarkus: `quarkus-maven-plugin` `build` produces `target/quarkus-app/quarkus-run.jar` (`quarkus.package.jar.type` is `fast-jar` by default; `uber-jar`, `mutable-jar`, `legacy-jar` and `aot-jar` also exist), or a native executable through a `native` profile (`-Dnative`). Library modules get only a `jandex-maven-plugin` index (https://quarkus.io/guides/maven-tooling).

**Image builders say "this module ships as a container".**
- Jib (https://github.com/GoogleContainerTools/jib/blob/master/jib-maven-plugin/README.md):
  - `build`, `dockerBuild` and `buildTar` need no Dockerfile.
  - `mainClass` is inferred from the jar plugin configuration or by scanning.
  - A `war` defaults to a Jetty base image.
  - `jib.skip` exists explicitly "for multi-module projects", which shows the plugin is commonly declared in a parent and skipped in libraries.
- Skaffold builds each image from the root context and selects the module with a `jib.project` artifactId. It requires Jib to be "configured already" (https://skaffold.dev/docs/builders/builder-types/jib/).
- Eclipse JKube's generators work as a ready-made deployable classifier (https://eclipse.dev/jkube/docs/kubernetes-maven-plugin/, partly read):
  - `spring-boot` activates when `spring-boot-maven-plugin` is in the POM.
  - `webapps` activates for WAR builds.
  - `micronaut`, `karaf` and Open Liberty activate on their plugins.
  - `vertx` activates on its plugin, or on `vertx-core` plus shade.
  - `dockerfile-simple` activates on a `Dockerfile` in the module base directory.
  - `java-exec` looks first for a jar whose manifest has `Main-Class` (the largest if several), then scans `target/classes` for exactly one `main` method. With zero or several it "does nothing".
  - `pom`-packaged projects are skipped unless they hold image configuration.
- Spotify dockerfile-maven (archived, maintenance only) runs `docker build` on the module's Dockerfile and passes `JAR_FILE=${project.build.finalName}.jar` as a build argument (https://github.com/spotify/dockerfile-maven, partly read).

**Buildpacks decide from the built artifact, not the POM.**
- Paketo builds with `-Dmaven.test.skip=true package`, then takes `target/*.[jw]ar` of the root module, or of `BP_MAVEN_BUILT_MODULE` in a multi-module build. Profiles come from `BP_MAVEN_ACTIVE_PROFILES` (https://paketo.io/docs/howto/java/).
- The executable-jar buildpack passes detection only if `META-INF/MANIFEST.MF` has a `Main-Class` entry, or some `**/*.jar` does. Tomcat handles WARs (https://github.com/paketo-buildpacks/executable-jar/blob/main/README.md).
- A manifest `Main-Class` in the packaged artifact is the most tool-neutral definition of "runnable jar".

**Inheritance, interpolation and profiles: why POMs as written mislead.**
- Inherited: `groupId`, `version`, `properties`, `dependencyManagement`, `dependencies`, `build` (including "plugin executions with matching ids" and plugin configuration).
- Not inherited: `artifactId`, `name`, `prerequisites` and `profiles`, "but the effects of active profiles from parent POMs are" (https://maven.apache.org/pom.html).
- Interpolation runs after inheritance, so a child's property overrides the parent's even where the parent uses it (https://maven.apache.org/guides/introduction/introduction-to-the-pom.html).
- Executions with the same id are overwritten by the child; different ids are merged and run parent first (https://maven.apache.org/guides/mini/guide-configuring-plugins.html).
- Plugin `inherited=false` and execution `inherited=false` stop propagation. `combine.children="append"` and `combine.self="override"` change how configuration merges (https://maven.apache.org/pom.html).
- Goals bound by packaging get implied execution ids (`default-jar`, `default-compile`) that a POM can configure (https://maven.apache.org/guides/mini/guide-default-execution-ids.html).
- Profiles can change `<plugins>`, `<pluginManagement>`, `<modules>`, `<properties>`, `<finalName>` and dependencies. They activate by JDK, OS, property, file exists/missing, `activeByDefault`, and, since Maven 3.9.0, packaging. `activeByDefault` profiles are switched off as soon as any other profile in the POM activates (https://maven.apache.org/guides/introduction/introduction-to-profiles.html).
- `help:effective-pom` prints the merged model "with the active profiles factored in"; `-Dverbose` annotates where each line came from (https://maven.apache.org/plugins/maven-help-plugin/effective-pom-mojo.html).

**Java release level.**
- `maven.compiler.release` (or `<release>`) checks against the target release's API, unlike `source`/`target`. It is JEP 223 style (`8`, not `1.8`). Compiler plugin 3.13.0+ turns it into source/target on JDK 8 (https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html).
- The Boot parent derives `maven.compiler.release` from `java.version`. Projects that need `--add-exports` unset it and use `source`/`target=${java.version}` (https://docs.spring.io/spring-boot/maven-plugin/using.html).
- Toolchains pick which JDK compiles; they set no language level (https://maven.apache.org/guides/mini/guide-using-toolchains.html).
- Effective order: plugin `<release>`, then `maven.compiler.release`, then `<source>/<target>`, then `maven.compiler.source/target`, then `java.version` through the parent.

**What other tools do.**
- CycloneDX's Maven plugin (https://cyclonedx.github.io/cyclonedx-maven-plugin/makeAggregateBom-mojo.html):
  - `projectType` defaults to `library`; it does not infer deployability.
  - `skipNotDeployed=true` drops modules that skip deploy, a "not shipped" proxy.
  - `excludeTestProject` drops modules whose artifactId contains "test".
  - `makePackageBom` targets only `war`/`ear` modules.
- Code2DFD (Schneider et al., JSS 2023, https://arxiv.org/pdf/2304.12769; source https://github.com/tuhh-softsec/code2DFD):
  - Finds services "in build files (like Maven) or in Infrastructure as Code files (like Docker and Docker Compose)".
  - Its Maven extractor treats every `pom.xml` without `<modules>` as a microservice.
  - The service is named from `spring.application.name` in the module's properties or YAML, else `build/finalName`, else `artifactId`. Paths containing "test" are skipped.
  - It reports no false negatives for services on its 17 apps. Under its rule, though, a leaf library module would be a service, and the sample apps hide that.
  - A compared tool produced "many false positive services" named after directories. Another, which parsed Docker Compose, had 0.99 precision and 0.58 recall.
- Claim (Maggi et al., EASE 2024, https://leonardoscommegna.github.io/papers/EASE-2024.pdf):
  - Picks the compose file (dropping `infra`/`override` files), matches each Docker service to a Dockerfile, and counts it as a microservice only if the Dockerfile copies non-configuration source into the image.
  - It drops Dockerfiles under `vendor`, `external`, `example` and `demo` paths.
  - Results: 82% accuracy, 94.5% recall, 73.8% precision. False positives were "mostly due to custom implementations of infrastructural elements (e.g., gateways)", meaning in-house gateways are real deployables that a classifier must not drop.
  - Limitation: services only appear once they are added to compose.

## Pitfalls for static analysis

1. **`pluginManagement` is not `plugins`.** Counting `spring-boot-maven-plugin` anywhere in the parent chain marks every library as deployable. Only a declaration in an effective `<build><plugins>` runs.
2. **Plugins declared in a parent's `<plugins>` run in every child.** A company parent that repackages everything makes shared "common" modules look like apps. Look for `skip`, `spring-boot.repackage.skip`, `jib.skip` or `jkube.skip`, or `<phase>none</phase>` overrides in the child.
3. **Default lifecycle plugins take `pluginManagement` configuration without being declared.** This is how `maven-jar-plugin` `mainClass=${start-class}` from the Boot parent reaches every jar module. It rests on a community answer (https://stackoverflow.com/questions/61516447), not the official pages read. A `Main-Class` there with `start-class` unset is not a signal.
4. **A classifier (`exec`) or `attach=false`** means the main artifact is a library jar while an executable also exists. The module is both, and other units may package it.
5. **Aggregator versus parent.** `<modules>` and `<parent>` are independent. An aggregator need not be a parent, and a parent need not aggregate. Directory structure is not inheritance.
6. **Profiles.** A `native`, `docker` or `release` profile can add the image or repackage plugin, or change `<modules>`. Without the effective model for the profiles used in production, deployables can be missed. `activeByDefault` profiles vanish when another profile activates.
7. **Interpolated packaging and properties** (`<packaging>${packaging}</packaging>`, `${start-class}`, `<skip>${skipImage}</skip>`). The values are unknown without interpolation, and a child can override a property its parent uses.
8. **Test, example and sample modules.** Modules named `*-it`, `*-tests`, `examples`, `samples` or `demo` often have main classes or Boot plugins. CycloneDX and Claim both fall back to name keywords, which are crude.
9. **Deployables defined outside the POM.** A Dockerfile that `COPY`s `target/*.jar` (or another module's jar), compose files, Kubernetes manifests and Skaffold or Paketo `BP_MAVEN_BUILT_MODULE` settings decide what ships. One module can be built into several images (Jib with different `to.image` per profile or execution, JKube image configurations). An image can also build from the root context.
10. **One `main` method is not a deployable.** Tools, migration runners and code generators have one too. JKube refuses to guess with several, and Spring Boot picks "the first".
11. **`maven.deploy.skip` / `skipNotDeployed`** says "not published to a repository", not "not run". Services are often not deployed to Maven repositories, and libraries are.
12. **Java release.** Reading only `maven.compiler.source` misses `release`, the plugin `<release>`, and `java.version` from the Boot parent. Toolchains name the JDK used to build, not the language level.

## Options

**A. Rules over POMs as written.** Packaging, plus the Boot plugin in `<plugins>`, plus a main class.
- Cheap, needs no build.
- Wrong on pitfalls 1–3, 6 and 7, which are the common corporate-parent cases.

**B. Rules over the effective POM (default profiles) plus the built artifacts.**
- Classify by effective `<build><plugins>` executions that are not skipped (Spring Boot `repackage`, `quarkus:build`, Jib, JKube, shade/assembly with a main class, dockerfile plugins), by `war`/`ear`, and by the packaged jar's manifest `Main-Class` / `Start-Class` or `quarkus-run.jar`.
- Matches how Paketo and JKube decide.
- Costs a sandbox build, which Lantern already runs. Reflects default profiles only.

**C. B plus deployment descriptors.** Link Dockerfiles that copy a module's jar, compose services (Claim's approach), Kubernetes or Skaffold artifacts, and `BP_MAVEN_BUILT_MODULE` to modules.
- Best recall for "what ships", and catches several images per module.
- More parsing, with matching heuristics (paths, image names).

**D. B with named profiles too** (`native`, `docker`, `prod`, or `-P` named in CI or Dockerfiles), by computing effective POMs per profile.
- Catches profile-only deployables.
- Several effective POMs per module; the profile list must come from somewhere.

**E. LLM or heuristic fallback by names** (`*-service`, `*-app`, `example`, `test`).
- Handles odd builds.
- Low precision. Use only as a tie-breaker with evidence attached.

## Recommendation (the researcher's)

Order: how common the pattern is, then how much it changes which boxes are drawn.

**Now**
1. Classify from the effective POM. Spring Boot `repackage` counts only if it is an effective `<build><plugins>` execution that is not skipped (`skip`, `spring-boot.repackage.skip`, `<phase>none</phase>`). Never count `pluginManagement`. This is the most common case and decides most boxes.
2. Confirm with the built artifact. A packaged jar with `Start-Class` or Boot layout, `Main-Class` from shade, assembly or the jar plugin with the class actually present, `quarkus-app/quarkus-run.jar`, or `war`/`ear`. Treat `<packaging>quarkus</packaging>` and an interpolated `${packaging}` as their resolved value. `pom` is never deployable.
3. Treat a repackage `classifier` or `attach=false` as "deployable and also a library". Draw it as a container, but let units that depend on its main artifact fold in only its library classes. Do not draw two boxes.
4. Count image plugins (Jib, JKube, Spring Boot `build-image(-no-fork)`, Spotify dockerfile, Micronaut `docker*` packaging) as deployable evidence, honouring `jib.skip` and `jkube.skip`.
5. Read the Java release in effective order: compiler `<release>`, then `maven.compiler.release`, then `source`/`target`, then `java.version`. Record toolchains separately as the build JDK.
6. Exclude test, integration-test and example modules only on structural evidence: test-scoped use, not depended on by any deployable, under an `examples`/`samples` path, or no deployment descriptor. Never on a name match alone.

**Later**
7. Dockerfiles, compose services and Kubernetes or Skaffold artifacts, mapped back to modules (Claim-style matching, plus a COPY of a module's `target/*.jar`). This catches modules deployed by Dockerfile only and one module shipped as several images.
8. Effective POMs for named deployment profiles (`native`, `docker`, `prod`) when CI or Dockerfiles invoke them.
9. A single-`main` jar with no other signal: a candidate, resolved by the LLM stage with cited evidence. Do not draw it by default.

## Decision

The extractor already reads the effective POM where the sandbox left one, so `pluginManagement` never counts there (pitfall 1), and it already honours `<skip>` and `spring-boot.repackage.skip`. What changes now, in order of how common each pattern is:

1. **More ways a module ships** (recommendations 2 and 4): `quarkus` packaging and the Quarkus plugin's `build` goal; Spring Boot's `build-image` goals; Jib, Eclipse JKube and the Spotify and fabric8 Docker plugins, unless skipped (`jib.skip`, `jkube.skip`, `dockerfile.skip`, `docker.skip`, or `<skip>`); and a shade or assembly jar whose main class resolves. A shade without a main class (relocating a library's dependencies), and a main class still `${start-class}`, do not count. `pom` packaging never ships.
2. **An execution moved to `<phase>none</phase>`** does not count (pitfall 2's opt-out).
3. **The reason is a fact**, `deployable-by`, pointing at the plugin or packaging element that decides, so a container cites its deployable evidence.
4. **The Java release in Maven's order** (recommendation 5): the compiler plugin's `release`, then `maven.compiler.release`, then the plugin's `source`, then `maven.compiler.source`, then `java.version`.

The artifact's manifest (recommendation 2's confirmation) is not read: the sandbox keeps no built jars, and the plugin rules above already say what the build packages. Issues, where they belong: an app that is also a library (#484), deployables only named profiles build (#485), test, example and sample modules drawn as containers (#486). Dockerfiles, compose and Kubernetes manifests mapped to modules (recommendation 7) are the Deployment extractor's (#397). A single `main` with no other signal stays undrawn (recommendation 9), as now.

## What it changes in the bar and the design

- **Design, Static analysis and extractors:** the Maven row names what makes a module deployable. ADR 0006 is amended.
- **Quality bar:** nothing new. The Container row already leaves libraries out, and `deployable-by` is the evidence a container cites.

## Researcher's notes on the bar and the design


- **Design, Maven extractor row.** "Deployable vs. library" should name its rule: effective-POM plugin executions (not `pluginManagement`), skip properties and phase `none`, resolved packaging (`quarkus`, `${packaging}`), artifact manifest evidence, and classifier/attach. Java release resolves in the order above. The extractor already prefers the effective POM, so this is mainly specifying it.
- **Design, Deployable units and the Container view's "Deployable evidence".** Add image plugins and the classifier case ("an app that is also a library is one box; dependents fold in its library jar"). Mark Dockerfile, compose and Kubernetes mapping, and named profiles, as later.
- **Bar, Container row.** No change to the rule ("no library modules"). Optionally add that a module counts as a container only with cited deployable evidence, and that test or sample modules are not containers.
- **Test sets (generic note).** Check scores for modules wrongly drawn because a parent declared the Boot plugin in `<plugins>`, and for Dockerfile-only deployables that were missed.

## Sources read in full

- https://maven.apache.org/pom.html
- https://maven.apache.org/guides/introduction/introduction-to-the-pom.html
- https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html
- https://maven.apache.org/guides/mini/guide-configuring-plugins.html
- https://maven.apache.org/guides/mini/guide-default-execution-ids.html
- https://maven.apache.org/guides/introduction/introduction-to-profiles.html
- https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html
- https://maven.apache.org/guides/mini/guide-using-toolchains.html
- https://maven.apache.org/plugins/maven-help-plugin/effective-pom-mojo.html
- https://maven.apache.org/plugins/maven-shade-plugin/examples/executable-jar.html
- https://maven.apache.org/plugins/maven-assembly-plugin/usage.html
- https://docs.spring.io/spring-boot/maven-plugin/packaging.html
- https://docs.spring.io/spring-boot/maven-plugin/using.html
- https://docs.spring.io/spring-boot/maven-plugin/build-image.html
- https://github.com/spring-projects/spring-boot/blob/main/starter/spring-boot-starter-parent/build.gradle
- https://github.com/GoogleContainerTools/jib/blob/master/jib-maven-plugin/README.md
- https://paketo.io/docs/howto/java/
- https://github.com/paketo-buildpacks/executable-jar/blob/main/README.md
- https://skaffold.dev/docs/builders/builder-types/jib/
- https://cyclonedx.github.io/cyclonedx-maven-plugin/ and https://cyclonedx.github.io/cyclonedx-maven-plugin/makeAggregateBom-mojo.html
- Schneider et al., "Automatic Extraction of Security-Rich Dataflow Diagrams for Microservice Applications written in Java" (Code2DFD), JSS 2023: https://arxiv.org/pdf/2304.12769, with `technology_specific_extractors/maven/mvn_entry.py` from https://github.com/tuhh-softsec/code2DFD
- Maggi, Verdecchia, Scommegna, Vicario, "Claim: a Lightweight Approach to Identify Microservices in Dockerized Environments", EASE 2024: https://leonardoscommegna.github.io/papers/EASE-2024.pdf

## Sources not read in full (carry no weight)

- https://quarkus.io/guides/maven-tooling (first 100k of 112k characters)
- https://eclipse.dev/jkube/docs/kubernetes-maven-plugin/ (300k of 345k characters; generator sections read)
- https://micronaut-projects.github.io/micronaut-maven-plugin/latest/index.html (index only; the packaging-types page was not read)
- https://github.com/spotify/dockerfile-maven (README summary)
- https://stackoverflow.com/questions/61516447 (via search snippet only; the source for pitfall 3)
- https://maven.apache.org/ref/current/maven-core/default-bindings.html (search snippet)
- Bushong et al., "Using Static Analysis to Address Microservice Architecture Reconstruction" (par.nsf.gov), and ModARO (arXiv 2602.08181): search results only
- Not consulted: Helidon, appassembler and exec-maven-plugin docs, Dependabot/Renovate, Backstage, Tilt
