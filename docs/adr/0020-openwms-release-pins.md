# 0020: Pin the OpenWMS test set to release tags

Status: accepted (#72), pending the user's review (#24)

## Context

ADR 0004 pinned each OpenWMS repository to the head of its default branch. Those heads depend on
unpublished `-SNAPSHOT` versions of each other and of `org.openwms.core.util`, so only six of the
15 repositories built in the sandbox (#30). Of the two options in #30, the user chose to pin the
test set to released code rather than build a snapshot's repositories in dependency order.
`core.util` is not missing after all: it is a module of the umbrella repository
`openwms/org.openwms`, which ADR 0004 left out.

## Decisions

- **Pins are release tags.** Each repository in `testsets/openwms/repos.txt` is pinned to the
  commit of its latest release tag on 28 Oct 2026, skipping milestones and release candidates
  (`M1`, `RC`). The file still holds full commit hashes; the tag is in a comment above each line,
  so a pin is moved by editing the hash and the comment.
- **Repositories without a release tag** keep their ADR 0004 pin, the head of the default branch
  on 28 Oct 2026, and say so in their comment: `wms.receiving` and `wms.receiving.lib`.
- **The umbrella repository** `org.openwms` joins the set at tag `4.0.0`, making 16 repositories.
  It is the parent of `org.openwms.core.util`, its only module.
- **What builds.** Ten of the 16 build in the sandbox: `ameba-lib`, `org.openwms.parent`,
  `common.service`, `common.service.lib`, `core.test.arch`, `core.uaa`, `core.uaa.lib`,
  `core.units`, `tms.transportation` and `wms.receiving.lib`; `OpenWmsBuildIT` builds them when
  the clones are present. The other six fail into degraded mode:
  - `org.openwms`: at tag `4.0.0`, `core.util`'s POM names its parent as `4.0.0-SNAPSHOT` while
    the umbrella POM is `4.0.0`, so Maven cannot read the project.
  - `core.process.execution`, and through it `tms.routing.lib`, `tms.routing.osip` and
    `tms.routing`: they need Activiti `8.8.0-alpha.7`, which is only on `artifacts.alfresco.com`,
    a host the sandbox's default allowlist does not reach.
  - `wms.receiving`: it has no release and needs `wms.receiving.lib:1.0.0-SNAPSHOT`, which is not
    published.

## Consequences

- Building a snapshot's repositories in dependency order (#30, option 1) is no longer needed for
  most of the test set; it would still fix `wms.receiving`.
- The released services are older than the default-branch heads, so analysis results describe
  the last released architecture, not work in progress.
