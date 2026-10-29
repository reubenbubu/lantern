# 0005: Build sandbox

Status: accepted (#7), pending the user's review (#24)

## Context

`docs/design.md` (Ingestion and build sandbox) says each repo is built for real, to `compile` with
tests skipped, in an isolated container per JDK with resource limits and a shared Maven cache, that
the build has no network access except to the artifact repository, and that a failed build leads to
degraded-mode analysis. Issue #7 implements this for JDK 17, 21 and 25. The builds run untrusted
code (plugins, annotation processors, Maven extensions), so isolation matters more than speed.

## Decisions

- **Docker through its command line tool**, as git is (0003), so no new dependency. The command is
  configurable, so Podman's compatible CLI should work, but only Docker is tested. Tests and
  Lantern need the `docker` CLI on the `PATH`.
- **Images** are the official `maven:3.9.16-eclipse-temurin-{17,21,25}` images, configurable per JDK.
  Builds always use the image's Maven, never the repo's `mvnw`, which would download a Maven
  distribution from outside the artifact repository.
- **Choosing the JDK.** The repo setting wins. Otherwise the top `pom.xml` at the commit is parsed
  (DTDs and external entities off): the toolchains plugin's JDK version, then the compiler release
  (property or plugin), then the compiler source, then Spring Boot's `java.version`. If the POM
  does not say, which is common when the version comes from a parent POM outside the repo (all
  OpenWMS services), Maven computes the top module's effective POM in a sandboxed container with
  the default JDK and the same rules are applied to that. If that does not say either, JDK 21 is
  used. The build runs on the oldest image at least as new as the wanted version, or the newest
  image there is. A setting naming a JDK without an image fails the build. A toolchains file
  offering the image's JDK is passed to Maven so builds using the toolchains plugin work.
- **Network isolation.** Each build gets its own Docker network created with `--internal`, which
  has no route out. The only other container on it is an egress proxy: a small Java class of
  Lantern's (`EgressProxy`, JDK classes only) run in its own limited container that is also on the
  default bridge network. It tunnels `CONNECT host:port` to an allowed list (by default only
  `repo.maven.apache.org:443`; the list comes from the configured Maven repositories, ADR 0023)
  and answers everything else with 403 or 405. Maven settings point
  both `http` and `https` at it. Code in the build that ignores the proxy settings has no route at
  all, and external names do not resolve (Docker 26 and later do not forward DNS from internal
  networks; older versions would leak DNS lookups). The proxy is also reachable from other
  containers on the default bridge network, which can use it to reach the same allowed hosts.
  Plain-HTTP repositories are not supported yet.
- **No host files in the container** but the shared Maven cache. The repo's files at the commit
  are streamed in with `git archive` (no `.git`, no uncommitted changes, no build output on the
  host) and `docker cp`. Only the Maven cache folder is bind-mounted, and the Docker socket is not
  available. Outputs come back as a tar
  stream from `docker cp`, parsed by Lantern's own extractor: only regular files and directories,
  every path confined to the target, symbolic and hard links skipped, at most 4 GiB and 2 million
  entries. A larger output fails the build. Only `work/`, `lib/` and Maven's exit code are taken
  from it, so the build cannot overwrite Lantern's log or result. This also means Lantern can itself run in a container, as long as the Maven cache folder and
  the builds directory have the same paths for Lantern and for Docker (a directory Lantern creates
  under the builds directory is mounted read only into the container that hands the cache over,
  ADR 0019).
- **Container limits** (all configurable): 2 CPUs, 4 GiB memory with swap off, 4096 processes,
  30 minutes per container (JDK detection and the build each get that), all capabilities dropped, `no-new-privileges`, and at most 4 builds at once (the
  design's sizing for the development VM). Builds run as a non-root user and have a disk limit;
  see ADR 0019.
- **Shared Maven cache** is one folder on the host, `lantern.sandbox.maven-cache-dir` (default
  `~/workspace/lantern-maven-cache`, created if missing), bind-mounted as the local repository of
  every build, with Maven's file locking (`file-lock`, `file-gav`) for concurrent builds, which
  works across containers since they share the host's filesystem. It replaced the Docker volume
  `lantern-maven-cache` (#108), so the cache survives Docker clean-ups, can be inspected and
  deleted on the host, and tests reuse the same folder instead of downloading everything again
  each run, which got Maven Central to answer 429 Too Many Requests. Only tests of the cache
  itself use a folder of their own, in their temporary directory. How the build user writes to it
  and the user running Lantern can still delete it is in ADR 0019. Trade-off: a malicious build
  can plant artifacts in the cache that later builds use. The design accepts a shared cache for
  speed; per-snapshot caches can be added if that matters. Moving to the folder starts with an
  empty cache; the old volume can be removed by hand.
- **The Maven run** is one invocation with `--fail-at-end`: `help:effective-pom` (all modules in
  one file), `dependency:tree`, `dependency:build-classpath` (compile scope, which includes
  `provided`) and `compile`, with `-DskipTests` and the repo's profiles and skipped modules. A
  skipped module is passed as a path if it has a `pom.xml` at the commit, else as an artifact ID.
  Plugin versions are pinned to Spring Boot 4.1.2's: help 3.5.2, dependency 3.10.0. A wrapper
  script then copies the outputs into `/out`.
- **Stored outputs** go in the design's workspace storage, not the database:
  `<buildsDirectory>/<repo>/<commit>/` holds `build.json` (the result), `build.log` (Lantern's
  notes, Maven's output, the proxy log; at most 16 MiB), `work/<module>/target/{classes,
  generated-sources,lantern/dependency-tree.txt}` mirroring the repo's layout,
  `work/target/lantern/effective-pom.xml`, and `lib/` with every classpath jar in Maven repository
  layout. Jars are copied per build so outputs never point into the shared cache. Classpaths in
  `build.json` are relative to the build directory. The module graph is each module's direct
  dependencies on other modules of the build, taken from the dependency trees. Building a
  `repo@commit` again replaces its outputs.
- **Failure.** A build that fails to compile or resolve, times out, runs out of memory, uses too
  much disk (ADR 0019), produces too much output, has no `pom.xml` or has no image for its JDK is stored as `FAILED` with a
  reason, keeping whatever outputs it left (a classpath often still resolves; a timed-out build,
  or one stopped for its disk use, leaves none, since outputs are gathered after Maven ends), and
  `degraded()` tells the analysis to use degraded mode. A build whose dependencies do not resolve
  still keeps its effective POM, which Maven writes first (added in #8). Problems of the sandbox
  itself, such as Docker being unavailable, or an interrupted build, throw `SandboxException` and store nothing;
  the build's containers and network are removed on every path.
- **No git metadata.** Builds get no `.git`, so plugins that read it fail. The
  `git-commit-id-maven-plugin`, used by the OpenWMS parent POM, is skipped
  (`-Dmaven.gitcommitid.skip=true`); the build information it records is not needed for analysis.
- **OpenWMS.** Which of the pinned repos build, and why the others fail into degraded mode, is in
  ADR 0020; `OpenWmsBuildIT` built the ones that do when the clones were present (removed in #100).
- **Not covered yet:** the mounted `settings.xml` for an internal artifact repository (#31),
  and images for JDK 8 and 11.
