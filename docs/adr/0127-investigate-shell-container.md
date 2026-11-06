# 0127: Investigate reads code with a shell in a sealed container

Status: accepted (#893)

## Context

Investigate's conversations read code through `read_file`, which fetched whole files 90% of the time, and every later turn sent them again (`~/workspace/lantern-launchpad/evidence/3-investigate/FINDINGS.md`, section 1). The owner's proof of concept gave the model a shell guarded by parsing each command, and its Haiku runs lost most "unclear" verdicts to commands refused for `cd`, a variable or `$(...)`. The owner decided on 6 Nov 2026 that a container Lantern controls is the guard, not parsing; that `cd` is allowed; no `git`; `nl` and `grep -n` for line numbers (`docs/design.md`, LLM integration, Investigate's conversation, *Tools*; research `docs/research/investigate-agent-design.md`, which chose a Docker container over bubblewrap).

## Decision

**Three tools.** Every Investigate mode reads code with `shell`, `find_in_code` and `outline` (`ShellTools`), in place of `read_file`, `facts_about` and `search_facts`. Resolve keeps the graph's tools, and its prompts and rejection texts do not change.
- `shell` runs one command with `sh -c` in `/work`, which holds one folder per repository.
- `find_in_code` is a fixed `grep -rnFI` in the same container. Each hit is given as `repo:path:line: text`, with the Java declaration around it.
- `outline` lists a Java file's declarations with their lines (`JavaOutline`, brought back from #900 with its tests).
- No answer is refused for its size. Each line is cut at 160 characters and the answer at about 1,000 characters, to whole lines. A footer names the exact call for the rest: the command with `| sed -n 'FROM,TOp'`, or `find_in_code` narrowed to the repository with the most hits. An outline may run to 24,000 characters.

**The container** (`InvestigateShell`, image `lantern-sandbox/src/main/resources/io/lantern/sandbox/investigate-shell/Dockerfile`):
- **Image.** Alpine pinned by digest, with coreutils, findutils, grep, ripgrep, sed, gawk, jq, libxml2-utils, tree, diffutils and file, plus mikefarah's yq v4.44.6 checked against its published SHA-256. It has no package manager, no git, and no busybox applet that reaches a network or edits in place. Lantern builds it the first time it is needed, tagged by a hash of the Dockerfile, and reuses it after.
- **Settings.** No network, a read-only root, user 65534, all capabilities dropped, no new privileges, 64 processes, 512 MB of memory and no swap, one CPU, and a 16 MB `/tmp` that runs nothing. The only mount is the repositories, read-only at `/work`. It has the sandbox's labels and owner label.
- **Life cycle.** It is started once per Investigate run and removed at the run's end, also on failure, by a `try` with resources. Like the build sandbox's helpers, it holds a lifeline on Lantern's process, so it also stops if Lantern dies.
- **Commands.** Each command runs as its own process group under `timeout` (20 s), and what is left of the group is killed when it ends, so a fork bomb or a background job cannot outlive it. Standard output and error are each read up to 256 KB. Conversations running at the same time share the container.

**The copy is masked once.** Before the container starts, each repository is written at the analysed commit into a temporary folder: the snapshot's files, as Lantern reads them from git, so there is no `.git`, no history and no untracked file. Every file is written as the tools have always shown it: secrets masked (ADR 0054), values under JWT secret keys masked (#348), and binary files, keys and keystores left out. What masking takes out goes to the harness's Secrets check (#645) at that point. Every answer of every tool comes from these files, so none can show a secret, whatever the command. Masking each answer instead could not work: a pipe's output no longer says which file each line came from.

**What an answer showed is worked out from its text.** The check that an answer cites only lines it was shown (`ShownRanges`, #715) now reads which lines each tool answer showed:
- A `path:N:` line (grep -n) is credited to its file and line.
- A numbered line (`nl -ba`, or `N:` from grep on one file) is credited when the command names exactly one file and the text matches that line.
- Any other line of at least four characters, with a letter or digit, is credited when it is a line of a file the command or the answer names.
- Within a cited range, a blank or brace-only line counts as shown when another line of the range was shown.
- The files so credited are the files read, with their blobs, for the answer cache. They are also the texts the injection screen reads (ADR 0101).

**Fallback.** An `Investigator` built without a shell keeps the graph's tools. Tests without Docker use this, and so does the Investigate test with `-Dlantern.test.investigate-tools=graph`.

## Consequences

- An analysis now needs Docker for Investigate as well as for builds, which it already needed. Each run writes the snapshot's text files to a temporary folder, a few megabytes on the test sets.
- The prompts named `read_file` in their citing rule. Their wording now says "tool results" and that a shell path's first folder is the repository. The rest of the prompts and the answer are unchanged until #860 and #892.
- A line shown only by its text can be credited to a file that merely holds the same line. That is the price of not parsing commands. The answer check (#892) moves to quoted evidence checked exactly.

Containment is tested against the real container (`InvestigateShellIT`).
