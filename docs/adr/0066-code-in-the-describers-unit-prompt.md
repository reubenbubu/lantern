# 0066: Code in the Describer's unit prompt

Status: accepted (#208), amends ADR 0050 and ADR 0065, pending the user's review (#24)

## Context

The Describer's prompt about a deployable unit (ADR 0050) shows facts and README claims only. For a
component with a generic name, such as `OrderService` or `Handler`, its stereotype and entry points
say little about what it does, so the model has little to word its responsibility sentence from.
Issue #208, from the LLM strategy review (#204), asks the unit prompt to show enough of a
component's code for that, with secrets masked (ADR 0054), cited and cached as in ADR 0050. ADR
0065 left Javadoc to count as evidence once a Describer prompt shows it.

## Decisions

- **Every component shown gets the start of its main type's code**, not only those with generic
  names: a name cannot tell whether it says what the class does (`OrderService` names a noun and a
  role), and a list of generic words would be a guess. A unit with one component shows no
  components (ADR 0050), so no code. Data store, destination, flow and purpose prompts are
  unchanged.
- **The file is the one the type's `kind` fact was observed in** (ADR 0024), read from git at that
  fact's commit through `SourceFiles.shown`, which checks the file against the evidence's content
  hash and masks secrets with `SourceSecrets` (ADR 0054); the summarizer now reads through the same
  method. A type declared in two files shows the first, by repository and path, that can be read. A
  file that cannot be read shows nothing. The analyze run reads every registered repository,
  carried-forward ones included, so the prompt does not depend on which repositories a run names.
- **The start of the file, bounded.** The package declaration, imports and a licence comment
  (a leading `/*` comment that is not Javadoc) are left out, and runs of blank lines are folded
  into one. What is left is shown up to 4,000 characters per component (`Describer.MAX_CODE_CHARS`),
  cut after the last whole line that fits, and at most 40,000 characters per unit
  (`MAX_UNIT_CODE_CHARS`), given to components in the order shown (entry points first); a
  component whose first line no longer fits shows none. The start of a class holds its Javadoc,
  annotations, declaration, fields and first methods, which say most about what it does; the
  bound keeps a unit's prompt within some 10,000 tokens of code, since the Describer does not split
  prompts as the summarizer does (ADR 0024).
- **Cited as a fact**, as in ADR 0024: the code follows a line for the type's `kind` fact, which
  gets a reference such as `[F9]`, under the heading `Code of <path>, without its package and
  imports` (with `, the last N lines left out` when cut). Citing it cites that fact, whose evidence
  is the file. The system prompt says the code may follow and asks the model to cite the fact above
  it for what the code shows.
- **Cached by what the model sees** (ADR 0050, ADR 0055): the key hashes the user turn, which now
  holds the code as shown, without the commit or fact ID, so a new commit that leaves the shown code
  unchanged makes no call, and a change to it asks again. `WordingPrompt.VERSION` is 2.
- **Javadoc counts as evidence** (amending ADR 0065), as ADR 0059 does for summaries: a
  responsibility sentence that cites the `kind` fact of code shown with `/**` in it is evidenced,
  like one citing a README claim. Which facts those are follows from the prompt, which a cached
  answer shares, so it is worked out in every run (`Wordings.documented`) and not stored. The
  system's purpose still counts README claims only: its prompt shows the facts behind the units'
  sentences without their code (ADR 0060).

## Cost

The unit prompt grows by the code shown: at most 40,000 characters, some 10,000 tokens, per unit
with more than one component, typically a few thousand characters per component. Not measured on
a real system.

## Not verified

Whether real models word generically named components better from the start of their code needs
real calls, which tests do not make. The check of the evidenced mark is a bound, not proof: it
cannot tell that the cited Javadoc says what the sentence says (#191).
