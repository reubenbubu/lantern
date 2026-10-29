# 0053: Removing what killed builds leave

Status: accepted (#174), amends ADR 0019, pending the user's review (#24)

## Context

ADR 0019 (#171) makes the egress proxy and the disk monitor end when the process running a build
is killed (a task timeout, Ctrl-C, a killed test fork), but such a process still leaves its build
or JDK-detection container, which runs Maven to its end and then stays exited, and the build's
internal network. A helper or cache hand-over container whose process is killed between its
`docker create` and `docker start` stays in state Created. All carry the sandbox label, but nothing
removes them. ADR 0019 ruled out a startup sweep because it could not tell a dead process's
leftovers from those of another Lantern process still building.

## Decisions

- **The build and detection containers stop with the process.** They run their command under the
  same standard-input lifeline as the helpers (`--interactive`, the shell wrapper, `docker start
  --attach --interactive` with the input held open while they run). When the process dies, the
  input closes, the wrapper ends Maven's shell and the container stops within seconds. They are not
  created with `--rm`: their outputs and exit state are read after they end.
- **Everything names the process that created it.** Each container and network the sandbox creates
  also carries `io.lantern.sandbox.owner=<boot ID>:<PID namespace>:<user ID>:<process ID>:<start
  time>`, read from Linux's `/proc` (the start time in clock ticks after boot, so a reused process
  ID is not mistaken for the owner). Where `/proc` cannot tell, as on macOS, the label is left out.
- **Each build first removes what ended processes left.** Before a build starts, the sandbox lists
  the containers and networks with an owner label and removes those whose owner ran on this
  machine's current boot, in the same PID namespace and as the same user, and is no longer there
  (or is another process with a reused ID). Containers go first, then networks. Anything else is
  left alone: a process on another machine sharing the Docker daemon, in another container, of
  another user (whose processes `/proc` may hide) or of an earlier boot cannot be told dead, and a
  process that cannot be read counts as alive. It is best effort: failures are ignored and the next
  build tries again.
- **Trade-offs.** Leftovers stay until the next build of any Lantern process of the same user on the
  same machine, and those of a process that ended in another PID namespace or before a reboot stay
  for good; they still carry the sandbox label, for removal by hand. Leftovers created before this
  change have no owner label and are never removed. Each build lists containers and networks once
  more. Tested with Docker only.
