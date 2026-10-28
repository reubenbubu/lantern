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
  as root, with no network and only the `CHOWN` and `DAC_OVERRIDE` capabilities, and hands the
  cache volume to the build user once: unless the marker file `.lantern-owner-10001` is in the
  cache, it changes the owner of everything in it (for caches filled by earlier root builds) and
  then writes the marker. Only Lantern's own command runs in it, never build code.
- **Disk limit by a watchdog** (both values configurable): every 5 seconds while a build or
  JDK-detection container runs, the sandbox reads how much the container has written to its own
  filesystem (`docker container inspect --size`, which covers `/work`, `/out` and `/tmp`, not the
  cache volume) and the free space of Docker's storage, measured with `df` in the egress proxy,
  which mounts the cache volume read only. A container that has written more than 8 GiB, or that
  is running when Docker's storage has less than 2 GiB free (which also catches a build filling
  the shared cache), is killed. The build is stored as `FAILED` with the reason, keeping no outputs
  (like a timed-out build), and analysis goes to degraded mode. A measurement that fails is
  skipped.
- **Trade-offs.** Polling lets a build overshoot by what it can write in 5 seconds. The free-space
  guard cannot tell which build is filling the disk, so with several builds at once the others can
  be stopped too; it assumes the cache volume is on the same filesystem as the rest of Docker's
  storage, as with Docker's default local volumes.
