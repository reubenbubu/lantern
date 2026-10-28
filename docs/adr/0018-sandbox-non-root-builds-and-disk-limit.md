# 0018: Sandbox: non-root builds and disk limit

Status: accepted (#33), pending the user's review (#24)

## Context

ADR 0005 ran builds as the image's root user, which is only harmless under rootless Docker, and
put no limit on the disk a build may use, so a hostile build could fill Docker's storage through
its container's filesystem or the shared Maven cache. Issue #33 asks for non-root builds (or at
least refusing rootful Docker) and a disk limit or a guard. Docker offers a per-container disk
quota (`--storage-opt size`) only on some storage drivers and filesystems, not on the development
VM's rootless Docker.

## Decisions

- **Builds run as a fixed non-root user, `10001:10001`,** on rootless and rootful Docker alike,
  rather than checking which kind of Docker runs them. The user is not defined in the images, so
  `HOME` and `user.home` point at `/tmp`. The JDK-detection container and the egress proxy run as
  the same user. The repository is streamed in with `git archive` under `tar.umask=0`, so `/work`
  is writable by everyone, and `/out` is copied in as an empty directory writable by everyone.
  The user is a constant, not a setting.
- **The shared Maven cache belongs to the build user.** Before each build a short container runs
  as root, with no network and only the `CHOWN` and `DAC_READ_SEARCH` capabilities, and gives the
  build user everything in the cache volume that belongs to someone else (`find ... -exec chown
  -h`, following no links): the top directory of a new volume, and caches filled by earlier root
  builds. Once handed over this only walks the cache. Only Lantern's own command runs in it, never
  build code. If it fails or takes longer than the build timeout, the build goes ahead and fails on
  what it cannot write.
- **Disk limit by a watchdog** (both values configurable): every 5 seconds while a build or
  JDK-detection container runs, and once after it ends, the sandbox reads how much the container
  has written to its own filesystem (`docker container inspect --size`). That covers `/work` (the
  repository's files too), `/out` (including the classpath jars copied there from the cache) and
  `/tmp`, but not the cache volume. While the container runs it also reads the free space of
  Docker's storage, with `df` in the egress proxy, which mounts the cache volume read only. A
  container that has written more than 8 GiB, or that is running when Docker's storage has less
  than 2 GiB free (which also catches a build filling the shared cache), is stopped. So is one
  whose disk use could not be measured six checks in a row; each measurement may take 30 seconds.
  The build is stored as `FAILED` with the reason, keeping no outputs (like a timed-out build), and
  analysis goes to degraded mode.
- **Trade-offs.** Polling lets a build overshoot by what it can write between two checks. The
  free-space guard cannot tell which build is filling the disk, so with several builds at once the
  others can be stopped too; it assumes the cache volume is on the same filesystem as the rest of
  Docker's storage, as with Docker's default local volumes. The build timeout can be overrun by
  the time of one check.
