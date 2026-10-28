# 0023: Configurable Maven repositories for sandbox builds

Status: accepted (#30), pending the user's review (#24)

## Context

ADR 0005 let builds reach only Maven Central, through the egress proxy. Four repositories of the
OpenWMS test set (ADR 0020) need Activiti `8.8.0-alpha.7`, which is only in Alfresco's repository,
so they could not build. The user decided that builds may download jars and POMs from Alfresco's
repository when they need them, that Maven Central stays the default and only repository unless
more are configured, and that downloaded code is used only to compile, never analyzed (#30).

## Decisions

- **One setting, `lantern.sandbox.maven-repositories`,** a list of `id` and `url` entries, drives
  both what the egress proxy allows and the repositories Maven sees in the build. Its default, in
  `application.properties`, is Maven Central alone (`central`,
  `https://repo.maven.apache.org/maven2`); a list set elsewhere replaces it whole. In the sandbox
  it is `SandboxSettings.mavenRepositories`, a list of `MavenRepository`, which replaces the
  former `allowedHosts` list; the allowed `host:port` targets are now derived from it.
- **Exact locations, never wildcards.** A URL must be `https`, with an exact host name, an
  optional port, a plain path, and no user information, query or fragment. IDs are distinct.
- **The proxy allows hosts, Maven gets the URLs.** The proxy only tunnels `CONNECT host:port`, so
  it cannot see paths: it allows the host and port of each configured repository. The URL prefix
  is what Maven uses. Code in a build that bypasses Maven can therefore fetch other paths on an
  allowed host, but no other host (#81).
- **Maven settings are generated per build** instead of copied from a fixed file. Besides the
  proxy settings of ADR 0005 they hold an always-active profile, `lantern-repositories`, listing
  every configured repository, in order, as a dependency repository, releases only (snapshots
  off). They are not plugin repositories: plugins still come from Maven Central only, since the
  user allowed extra repositories for build dependencies, not for build plugins. Repositories a POM names itself are still tried; they work only if their host
  is allowed. No mirrors and no server credentials; an internal repository with credentials is
  #31.
- **Third-party code is never analyzed.** Downloaded artifacts only go on the classpath, like
  those from Maven Central. Analysis covers the registered repositories' own sources and classes.
- **Test sets name their repositories in a `lantern.properties`.** `testsets/openwms/
  lantern.properties` lists Maven Central and Alfresco's Activiti repository, at the URL the OpenWMS
  POMs use (`https://artifacts.alfresco.com/nexus/content/repositories/activiti-releases`). It is
  passed to Lantern with `--spring.config.additional-location`, and the OpenWMS build tests read it
  with `MavenRepository.listFrom`.

## Consequences

- With it, 14 of the 16 OpenWMS repositories build. `org.openwms` still has a broken parent
  reference and `wms.receiving` still needs an unpublished snapshot (ADR 0020, #30).
- The test suite reaches `artifacts.alfresco.com` for one small Activiti jar, in the test that a
  configured repository works, so an outage of that host fails `./mvnw verify`.
- A stored build is reused for the same `repo@commit` whatever the repositories were when it ran
  (ADR 0016). Builds that failed before a repository was added stay failed until their directory
  under `builds/` is deleted; whether to rebuild stored failed builds is #58.
- Host names must have a dot and may not be IP addresses, so `localhost` and addresses such as
  the cloud metadata service cannot be configured.
