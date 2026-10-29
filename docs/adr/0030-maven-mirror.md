# 0030: One Maven mirror for every sandbox download

Status: accepted (#117), amended (#212), pending the user's review (#24)

## Context

Maven Central rate-limits this machine (HTTP 429), so sandbox builds and the tests that run them
fail for reasons that have nothing to do with the code. The user runs a caching Maven proxy on the
local network and wants every build download to go through it (#117). ADR 0023 refused such a
proxy: repository URLs must be `https` with a host name, never an IP address, and plugins come
only from Maven Central. The egress proxy of ADR 0005 only tunnelled `CONNECT`, which Maven uses
for `https` but not for `http`.

## Decisions

- **One optional setting, `lantern.sandbox.maven-mirror`,** with an `id` and an `url`. Unset by
  default, and then nothing changes. In the sandbox it is `SandboxSettings.mavenMirror`, a
  `MavenMirror`, null for none.
- **The mirror stands in for every repository.** The generated Maven settings (ADR 0023) get a
  `<mirror>` of `external:*` with the mirror's URL, so dependencies and plugins alike, from the
  configured repositories, from Maven Central and from repositories a POM names itself, are
  downloaded through it. The repositories stay in the settings, all mirrored. `file:` and
  `localhost` repositories, such as a repository inside the project, are not mirrored and keep
  working as before. Maven merges the global settings of the
  build image after the generated ones, so the image's `maven-default-http-blocker` mirror
  (`external:http:*`) never matches first and does not block an `http` mirror.
- **The egress proxy then allows only the mirror's host and port** (port 80 or 443 if the URL
  names none). Maven Central and the configured repositories are no longer reachable.
- **For the mirror only, `http` and private-network addresses.** Its URL may be `http` or `https`,
  and its host may be a host name, as for repositories, or an IPv4 address in 10.0.0.0/8,
  172.16.0.0/12 or 192.168.0.0/16, written as four plain decimal numbers. Loopback, link-local
  addresses such as the cloud metadata service, IPv6 and every other address are refused, as are
  user information, a query, a fragment and a path with dot segments. Repository URLs keep every
  rule of ADR 0023.
- **The egress proxy forwards plain HTTP `GET` and `HEAD` requests.** Maven sends an `http`
  request through an HTTP proxy as `GET http://host:port/path`, not as `CONNECT`. The proxy checks
  that `host:port` (port 80 by default) is allowed, exactly as for `CONNECT`, connects to it, and
  sends the request on with the path alone, without the proxy and connection headers, and with
  `Connection: close`, so each connection carries one request to the checked target. Other methods
  are refused with 405 and other targets with 403. Whatever a client sends after that request goes
  to the same checked target, as through a `CONNECT` tunnel. This holds with or without a mirror:
  without one, a plain request to a host that is not allowed now gets 403 instead of 405, and one
  to an allowed repository's host and port is forwarded to that same target, which `CONNECT`
  reaches anyway. Nothing else changes when no mirror is set.
- **The address stays out of the repository.** It is the host's own setting, in a properties file
  outside the repository, passed to Lantern with `--spring.config.additional-location`. The
  integration tests that run sandbox builds (`BuildSandboxIT`, `Project0BuildIT`,
  `MavenExtractorIT`, `JavaExtractorIT`, `EndToEndIT`) read the mirror from the Lantern properties
  file named, by absolute path, by the system property `lantern.test.properties`, or else the
  environment variable `LANTERN_TEST_PROPERTIES`, and use Maven Central without one.

## Consequences

- With a mirror, a build reaches one host, which can be plain `http` on the local network: the
  mirror sees, and could alter, everything builds download. That is the user's trade, for a proxy
  they run.
- A private-network address is the one way a build can reach the local network, and only the
  mirror's host and port. Code in a build that bypasses Maven can fetch any path from the mirror,
  as ADR 0023 allows for a repository's host. The mirror must be a Maven repository manager (such
  as Nexus, Artifactory or Reposilite), not a general forward proxy: builds can send it any
  request, so a forward proxy would let them reach whatever it reaches.
- Only IP literals are checked against address ranges. A host name, for the mirror or a
  repository, is not checked after it resolves, as under ADR 0023.
- The shared Maven cache records which repository each file came from. Files downloaded from
  Maven Central are fetched again through the mirror, and the other way round, the first time a
  build needs them after the setting changes.
- `BuildSandboxIT` builds through the mirror when one is set, and then checks that even Maven
  Central is refused and, on a cache of its own, that the `lombok` fixture's dependency and the
  compiler and dependency plugins came through the mirror, and nothing from anywhere else. The
  tests of configured repositories (ADR 0023) clear the mirror and still use Maven Central.
- The mirror's URL rules are checked by `MavenMirrorTest`, the generated mirror by
  `MavenSettingsTest`, the allowed host by `SandboxSettingsTest`, the binding by
  `SandboxPropertiesTest`, and the forwarding by `EgressProxyTest`.

## Amendment (#212): nothing from Maven Central where a proxy is set up

Parallel tasks on the development machine were still rate-limited by Maven Central (HTTP 429),
because a full `./mvnw verify` reached it in three places: the Maven wrapper's download of Maven,
the two `BuildSandboxIT` tests of configured repositories (ADR 0023), which clear the mirror and
build against Maven Central and `repo1.maven.org` on purpose, and every sandbox build when no test
properties file was named. Host Maven already goes through the machine's own `~/.m2/settings.xml`.

- **One local proxy, set up per machine and never in the repository.** A machine with a Maven
  repository manager that proxies Maven Central mirrors `*` to it in `~/.m2/settings.xml`, exports
  `MVNW_REPOURL` with its URL (the wrapper then downloads Maven from it instead of the
  `distributionUrl` host), and exports `LANTERN_TEST_PROPERTIES` naming a properties file that sets
  the mirror above. The sandbox's mirror cannot be `localhost`, which build containers do not
  reach and which stays refused, so it is the host's private-network IPv4 address and the proxy
  must listen on it. CLAUDE.md's Commands section says how. A machine without a proxy sets none
  of this, and everything uses Maven Central as before.
- **The tests of configured repositories are skipped when a test mirror is set.** They exist to
  show that builds reach Maven Central and `repo1.maven.org`, which a mirrored machine must not
  contact, and a configured repository cannot be the mirror (it must be `https` with a host
  name). They still run on machines without a mirror; the generated settings and the allowed hosts
  stay covered there and by `MavenSettingsTest` and `SandboxSettingsTest` everywhere.
