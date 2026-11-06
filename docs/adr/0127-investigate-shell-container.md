# 0127: Investigate reads code with a shell in a sealed container

Status: accepted (#893)

## Context

Investigate's conversations read code through `read_file`, which fetched whole files 90% of the time, and every later turn sent them again (`~/workspace/lantern-launchpad/evidence/3-investigate/FINDINGS.md`, section 1). The owner's proof of concept gave the model a shell guarded by parsing each command, and its Haiku runs lost most "unclear" verdicts to commands refused for `cd`, a variable or `$(...)`. The owner decided on 6 Nov 2026 that a container Lantern controls is the guard, not parsing; that `cd` is allowed; no `git`; `nl` and `grep -n` for line numbers (`docs/design.md`, LLM integration, Investigate's conversation, *Tools*; research `docs/research/investigate-agent-design.md`, which chose a Docker container over bubblewrap).

## Decision

**Three tools.** Every Investigate mode reads code with `shell`, `find_in_code` and `outline` (`ShellTools`), in place of `read_file`, `facts_about` and `search_facts`. Resolve keeps the graph's tools, and its prompts and rejection texts do not change.
- `shell` runs one command with `sh -c` in `/work`, which holds one folder per repository.
- `find_in_code` is a fixed `grep -rnFI` in the same container. Each hit is given as `repo:path:line: text`, with the Java declaration around it.
- `outline` lists a Java file's declarations with their lines (`JavaOutline`, brought back from #900 with its tests).
- No answer is refused for its size. Each line is cut at 160 characters and the answer at about 1,000 characters, to whole lines (1,500 since #915, below). A footer names the exact call for the rest: the command with `| sed -n 'FROM,TOp'` (a command of several parts, a `;`, `&&`, `||` or a comment, run as `sh -c '…'` first, so the pipe takes its whole output), or `find_in_code` narrowed to the repository with the most hits. An outline may run to 24,000 characters.

**The container** (`InvestigateShell`, image `lantern-sandbox/src/main/resources/io/lantern/sandbox/investigate-shell/Dockerfile`):
- **Image.** Alpine pinned by digest, with coreutils, findutils, grep, ripgrep, sed, gawk, jq, libxml2-utils, tree, diffutils and file, plus mikefarah's yq v4.44.6 checked against its published SHA-256. It has no package manager and no git, and the busybox applets that reach a network or edit in place are unlinked; busybox itself stays, as it is the shell, so `busybox wget` still runs, and with no network it reaches nothing (tested). Lantern builds it the first time it is needed, tagged by a hash of the Dockerfile, and reuses it after.
- **Settings.** No network, a read-only root, user 65534, all capabilities dropped, no new privileges, 256 processes, 512 MB of memory and no swap, one CPU, and a 16 MB `/tmp` that runs nothing. The only mount is the repositories, read-only at `/work`. It has the sandbox's labels and owner label.
- **Life cycle.** It is started once per Investigate run and removed at the run's end, also on failure, by a `try` with resources. Like the build sandbox's helpers, it holds a lifeline on Lantern's process, so it also stops if Lantern dies.
- **Commands.** Each command runs as its own process group under `timeout` (20 s), and what is left of the group is killed when it ends, so a fork bomb or a background job cannot outlive it. Standard output and error are each read up to 256 KB; a command whose output passes that, or that Lantern stops waiting for, has its process group killed in the container before its turn is freed. Conversations running at the same time share the container, and at most 8 of their commands run at once, the rest waiting their turn: with 64 processes and no queue, project-8's parallel conversations ran out of processes 24 times ("can't fork").

**The copy is masked once.** Before the container starts, each repository is written at the analysed commit into a temporary folder: the snapshot's files, as Lantern reads them from git, so there is no `.git`, no history and no untracked file. The folder and everything in it can be read by any user, since the container's user is not Lantern's. Every file is written as the tools have always shown it: secrets masked (ADR 0054), values under JWT secret keys masked (#348), and binary files, keys and keystores left out. What masking takes out of a file goes to the harness's Secrets check (#645) once an answer shows that file, as with `read_file`. Sending every masked value of the snapshot up front made the check reject answers for ordinary words masked somewhere in the snapshot: two of project-2's four leads failed so. Every answer of every tool comes from these files, so none can show a secret, whatever the command. Masking each answer instead could not work: a pipe's output no longer says which file each line came from.

**What an answer showed is worked out from its text.** The check that an answer cites only lines it was shown (`ShownRanges`, #715) now reads which lines each tool answer showed:
- A `path:N:` line (grep -n), or a context line `path-N-`, is credited to its file and line when that line of the file holds its text, trying each folder the path may be read from.
- A numbered line (`nl -ba`, or `N:` from grep on one file) is credited to each file the command or the answer names whose line N holds that text.
- A path is read from the folder the command's `cd`s leave it in, inside a repository or not, `..` and `/work/...` included.
- Any other line of at least four characters, with a letter or digit, is credited when it is a line of a file the command or the answer names.
- A shown line matches a file's line when it is the same or, cut by the tool (`…`) or by the command (`cut -c1-120`), its first 30 characters or more.
- Within a cited range, a blank or brace-only line counts as shown when another line of the range was shown.
- The files so credited are the files read, with their blobs, for the answer cache. They are also the texts the injection screen reads (ADR 0101).
- A line of at least 20 characters that no file is credited with (`grep -rh`, `cat a/*`) still reached the model: the files that hold it (or, for a cut line, a line it starts), when three or fewer, go to the injection screen too, though the line cannot be cited. A file an `outline` lists goes to the screen as well, as its annotations are shown. Text a command rewrote (`sed s/…/…/`) matches no file and is not screened; it is the model's own rewriting of screened or unscreened lines, the residue of not parsing commands.

**Fallback.** An `Investigator` built without a shell keeps the graph's tools. Tests without Docker use this, and so does the Investigate test with `-Dlantern.test.investigate-tools=graph`.

## Consequences

- An analysis now needs Docker for Investigate as well as for builds, which it already needed. Each run writes the snapshot's text files to a temporary folder, a few megabytes on the test sets.
- The prompts named `read_file` in their citing rule. Their wording now says "tool results" and that a shell path's first folder is the repository. The rest of the prompts and the answer are unchanged until #860 and #892.
- A line shown only by its text can be credited to a file that merely holds the same line. That is the price of not parsing commands. The answer check (#892) moves to quoted evidence checked exactly.

Containment is tested against the real container (`InvestigateShellIT`).

## Amendment (#915): answers cut at 1,500 characters, and the model told so

Each tool answer was cut at about 1,000 characters, so the model read a file in several calls: with
the lean first message of ADR 0128, turns a lead rose from 5.9 to 8.2. A tool answer is now cut at
about 1,500 characters (`ShellTools.CHARS`), and Mode A's instructions say so: "A tool answer
longer than 1,500 characters is cut and you need another call for the rest, so ask for only the
lines you need: a line range, a grep with -n, a head." A test keeps the sentence and the number
the same.

Measured on the Investigate test's 78-lead panels (project-1 to project-4 and project-8), Haiku 5.5
through OpenRouter, 6 Nov 2026, after the masking change of #912:

| Cut | Cost a lead | Turns a lead | project-8 right per key | its plaintext-secret leads |
| --- | --- | --- | --- | --- |
| 1,000 (the runs for #914) | $0.0036 | 8.2 | 43 of 54 | 19 of 23 |
| 1,500, stated in the instructions | $0.0029 | 6.7 | 43 of 54 | 19 of 23 |
| 3,000, stated in the instructions | $0.0032 | 6.2 | 41 of 54 | 18 of 23 |

Before #912, larger cuts lost plaintext-secret leads (7 to 12 wrong against 4): the model met a
masked value sooner and answered unclear. A 3,000 cut saves turns but each turn carries more, so it
costs more than 1,500. Investigate's prompt version is 11.
