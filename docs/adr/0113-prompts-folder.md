# 0113: Prompts in one folder the owner edits

Status: accepted (#706)

## Context

The owner wants to tune the roles' prompts, and the wording judge's two questions above all
(#668), without asking an agent for each change. Before #706 the prompt files sat in three
modules' resources, and seven texts (the common system prompt, the support judge's, the injection
screen's and the wording judge's tasks and schemas) were Java text blocks. Changing any of them
meant a rebuild and raising a version number by hand, and trying a judge prompt meant a full run.

## Decisions

- **One folder.** Every prompt file sent to a model is in `prompts/` at the repository's root,
  beside `report-specs/`, and nowhere else in the source. The harness module packs the same files
  into the jar under `/prompts/` as the built-in copies.
- **Read at run time.** `Prompts` reads a file from the folder named by the system property
  `lantern.prompts.dir`, or from `prompts` in the working directory when that exists, and falls
  back to the built-in copy for a file the folder lacks or when there is no folder. The app takes
  `--lantern.prompts.dir=<folder>` from its command line before any role is built, and
  `scripts/launch.sh` always passes this repository's folder. Each file is read once per process,
  so a run never mixes two versions of a prompt. A run logs the folder it read and which files
  differ from their built-in copies.
- **Keyed by what was sent.** A role's prompt version is its version in the code while every file
  it uses (its own and `common.system.txt`) is as built, and that version plus a 12-character
  digest of the edited files otherwise. An edit therefore re-asks that role's items and nothing
  else, and an unedited folder keeps every answer stored before #706. A developer who changes a
  prompt for good still raises the role's version, as before, so the built-in copies and the folder
  agree again.
- **`judge-labels`.** The command judges `testsets/wording-labels/` (or `--labels=<folder>`) with
  the wording judge's current prompts, on the `tune` texts by default (`--split=test|all`,
  `--dimension=role|specific`), and prints, per dimension, how many of the texts the labels pass
  it passes and how many of those the labels fail it fails, then each text it got wrong with its
  reasoning. The counts are plain, not weighted by the draw's strata: they show whether an edit
  helps; validation stays the scorer's (ADR 0111). It uses the answer store, so a rerun with an
  unchanged prompt costs nothing. `scripts/launch.sh --judge-labels` runs it.

## Files

| File | Role |
| --- | --- |
| `common.system.txt` | Block 1 of every role |
| `summarize.task.txt`, `.schema.json`, `.closing.txt` | Summarize (types and above) |
| `resolve.task.txt`, `.schema.json` | Resolve |
| `investigate-leads.task.txt`, `investigate-explore.task.txt`, `investigate.schema.json` | Investigate's leads; units and system |
| `describe-units.*`, `describe-elements.*` | Describe |
| `purpose.*`, `name-groups.*`, `flow-significance.*`, `flow-narrative.*` | Purpose and key fact, group names, flows |
| `support-judge.*`, `injection-screen.*` | The model checks (ADR 0101) |
| `wording-judge-role.task.txt`, `wording-judge-specific.task.txt`, `wording-judge.schema.json` | The wording judge (ADR 0111) |

## Consequences

- The schemas are in the folder too, but each role's code reads the answers they describe: an
  edit to a schema that the code does not expect makes answers fail their checks.
- The fake model recognises a role by the first sentence of its task (`Task: ...`); with that
  sentence edited, a fake run no longer answers as that role.
- What the harness itself writes into a request (a repair after rejected answers, the note that a
  conversation is near its limit) stays in the code; it is not a role's prompt.
- Evidence (#706, fake model, project-0): a first run made 86 calls, a rerun 0; with one sentence
  added to `describe-elements.task.txt` the rerun made 5, all of them describe-element.
