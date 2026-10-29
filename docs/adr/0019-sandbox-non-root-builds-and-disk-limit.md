# 0019: Sandbox: non-root builds and disk limit

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
- **The shared Maven cache belongs to the build user, in the group of the user running
  Lantern.** The cache is a host folder (ADR 0005, #108). Before each build, and after each
  build that was not interrupted, a short container runs as root, with no network and only the `CHOWN`, `DAC_READ_SEARCH` and `FOWNER`
  capabilities. It reads which group the user running Lantern has as the container sees it, from
  a directory Lantern created and mounts read only (under rootless Docker that is group 0; if the
  group is not mapped, the build user's group is used). It gives the build user, in that group,
  everything in the cache that belongs to someone else (`find ... -exec chown -h`, following no
  links): the top directory of a new folder, files left by earlier root builds or by hand, and
  what builds added in the build user's own group. It makes every directory group-writable
  (`chmod g+rwx`, no set-ID bits, so a directory a concurrent build swaps for a link to one of its
  files gains nothing). Builds and JDK detection run with umask `0002`, so the directories Maven
  adds are group-writable as they are made. Under rootless Docker the build user is a subordinate
  user of the host, not the user running Lantern, so this is what lets that user delete the cache
  (`rm -rf`) without root; the run after each build undoes whatever owners and modes a build set.
  Files that other builds remove during the walk are no error. It only adds to what the build user
  and the group may do, so it is safe while other builds use the cache. It walks the whole cache
  twice per build, which takes longer as the cache grows. Only Lantern's own command
  runs in it, never build code. If it fails or takes longer than the build timeout, the build goes
  ahead and fails on what it cannot write.
- **Disk limit by a watchdog** (both values configurable): every 5 seconds while a build or
  JDK-detection container runs, and once after it ends, the sandbox reads how much the container
  has written to its own filesystem (`docker container inspect --size`). That covers `/work` (the
  repository's files too), `/out` (including the classpath jars copied there from the cache) and
  `/tmp`, but not the cache folder. A container that has written more than 8 GiB is stopped, as is
  one whose disk use could not be measured six checks in a row (each measurement may take 30
  seconds) or once more after it ended. The build is stored as `FAILED` with the reason, keeping
  no outputs (like a timed-out build), and analysis goes to degraded mode. Build containers log
  with Docker's `none` driver, since their output is read through `docker start --attach`, so
  Docker keeps no unbounded copy of it on disk.
- **Free-space guard.** The same checks read the free space and free inodes of Docker's storage
  with `df`, in a per-build monitor container that mounts the cache folder read only, has no
  network and so cannot be reached by the build. It measures both Docker's storage (its own
  filesystem) and the cache folder's filesystem, which may differ. A build does not start, or is
  stopped, when either has less than 2 GiB (configurable) or 100,000 inodes free (where the
  filesystem counts inodes). This catches a build filling the shared cache, which the size check
  does not count. Since it cannot tell which build used the space, it is treated as a problem of
  the host, not of the build: `SandboxException`, nothing stored, so no repository is recorded as
  failed for it.
- **Cleanup waits for creates (#144, #98).** Every Docker call that creates a container or
  network runs to its end even when the build is interrupted or times out, up to the 5-minute
  Docker timeout, because a killed `docker create` or `docker run` can still be completed by the
  daemon after cleanup ran. The hand-over is a `docker create` followed by `docker start
  --attach`, and only the start is bound by the build timeout. Cleanup removes the build's
  containers and network by name and then, for any a create killed at the Docker timeout left
  behind, by the build's sandbox label.
- **Helpers end with their build (#171).** The egress proxy and the disk monitor are removed as
  soon as the build container ends, before the outputs are copied out. They are created with
  `--rm` and `--interactive` and run their command under a small shell wrapper that ends it when
  the container's standard input ends. Lantern starts them with `docker start --attach
  --interactive` and keeps that process's input open while the build runs, so if Lantern's process
  is killed and its cleanup never runs, the input closes, the helpers stop within seconds and Docker
  removes them. The cache hand-over container is created with `--rm` too. The proxy's output is
  read through its attached process into the build log, since Docker removes the container, and
  its logs, when it ends; it logs with Docker's `none` driver. If a helper's attached process ends
  while the build runs, the build is stopped with a `SandboxException` naming the helper, not
  recorded as a failed build. Nothing removes all that is labelled: it could not tell a dead build's
  leftovers from those of another Lantern process still building. What a killed process still
  leaves, the stopped build or detector container, the build's network and a helper killed between
  its `docker create` and `docker start`, is removed by a later build once its owner process is
  known to have ended (ADR 0053). Tested with Docker only; Podman's handling of the attached input
  is untested.
- **Trade-offs.** Polling lets a build overshoot by what it can write between two checks. With
  several builds at once, the free-space guard stops them all. What a build wrote to the shared
  cache stays there; cleaning the cache is manual, and until then every analysis stops at its
  first build (#65). A build can fill the cache folder's filesystem,
  which may also hold the user's other files, down to the free-space floor. The
  build timeout can be overrun by the time of one check. On a very busy host an honest build with
  very many files could be stopped because its size could not be measured in time.
