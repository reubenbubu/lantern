# 0118: A response contract for every role

Status: accepted (#760)

## Context

The rules for what each LLM role writes lived in five places, and they had drifted apart:
- the epic (section 11);
- the prompts;
- constants in the checks;
- the schemas;
- the role ADRs.

Six roles asked for one size and accepted another without a written reason. For example, Describe asked for 22 words and accepted 25, and Flow narrative's title asked 5 and accepted 6. A user tuning their own model could not see which rule a model broke. On 3 Nov 2026 the owner decided:
- each size rule has a requested range, which the prompt states, and an acceptable range, which the check enforces, with about 15% tolerance by default;
- the quality score is 0 to 10, built from weighted yes/no checks, never asked of a judge directly.

## Decisions

- **One contract per role**, `prompts/<role>.contract.json`, beside its prompt. Each lists:
  - the role's harness names;
  - the prompt files that state its sizes;
  - per text field:
    - its kind;
    - where its texts are stored (fact predicate, attribute and subject);
    - which answer fields its rejections name;
    - its size rules;
  - counts (Investigate's proposals, the support judge's claims);
  - strict rules (no tolerance: a valid answer, citations that exist, names backed by what is cited, citable reference kinds, repository text as data, no secrets);
  - its judged questions;
  - when not answering is right.
- **Two ranges per size rule.**
  - `requested` is what the prompt states, with its own words in `stated`. `accepted` is what the check enforces.
  - Unless the contract sets it, `accepted` is the requested maximum plus 15% of it, rounded, and at least one unit (`Contract.accepted`).
  - A sentence count is requested only and measured, not enforced.
  - A rule marked `"enforced": false` is measured only. This applies to the injection screen's quote, which must never hide a suspected injection.
- **The checks read the contracts.** Each role's word limit is `Contract.of(role).acceptedWords(field)`, so a contract edit changes what the checks accept. This covers Describe, Name groups, Flow significance and narrative, Purpose, Resolve, Investigate, Summarize and the wording judge's longest text.
  - Summarize states its requested size in each request from the contract (`Prompt.length`).
- **A contract is not part of a prompt version.**
  - A contract changes what is accepted, not what a model is asked, so editing one asks nothing again.
  - Stored answers are checked again when reused.
  - Contracts are pinned in `prompts.sha256`, like every committed prompt file.
- **The margins decided before contracts are kept**, as the contract's own `accepted`:
  - Flow significance's reason: 18 asked, 20 accepted (ADR 0108).
  - The walkthrough: 75 and 80 (ADR 0108).
  - Name groups' merge reason: 18 and 20 (ADR 0107).
  - Investigate's finding and consequence: 70 (ADR 0110).
  - Investigate's connection description: 15.

  Every other rule takes the default, which reproduces each earlier margin except three that had none. Purpose's purpose now accepts 29 instead of 25, its key fact 35 instead of 30, and Resolve's reason 35 instead of 30. The owner's 15% default now covers them too.
- **A test ties prompts to contracts.**
  - `ContractTest` fails if a prompt does not state a contract's requested size in the contract's words, or states another number.
  - `PromptTest` checks the same for Summarize's request.
- **The scorecard reads them** (`lantern-web/scripts/contracts.mjs`). Its "Response contracts" section reports each size rule three ways over a run's stored texts (`llm.jsonl.gz`) and its rejections (`rejections.jsonl.gz`): within the requested size, accepted beyond it, and rejected for its length.
- **The quality score** (`prompts/quality.json`; research in `docs/research/quality-score.md`).
  - Per kind of text (responsibility, intent, name, prose), weighted yes/no checks sum to 10.
  - The judged checks are the wording judge's two questions. "Says what it does" weighs 3 and is a gate: a text that fails it scores at most 4. "Specific to this system" weighs 2, or 3 for a name.
  - The deterministic checks cover the length within the requested size, the requested sentences, an opening that is not a pronoun or a restated kind, an intent that opens with a verb and names no protocol, and no filler phrases.
  - The scorecard gives per role the mean over the texts judged on both questions, how many the gate capped, and the points lost per check, most first.
  - A difference under 0.7 is within the paired rule's tolerance of 7 points in 100.

## Consequences

- A rule changes in one file. The prompt's stated number must change with it, or `ContractTest` fails, and a prompt change raises the role's prompt version as before.
- Rules the prompts do not state yet stay as contract notes, to be stated when those prompts next change (#743):
  - Investigate's finding, consequence and description sizes;
  - the 40-line range in the leads prompt;
  - Investigate's schema allowing 12 proposals where the check keeps 10.
- `WordingPrompt`'s old flow sizes (8, 100 and 30 words) are not a contract; removing them is #775.
- The quality score is reasoned, not fitted. CheckEval found fitted weights gained nothing for most judges. The weights change only through an issue.
- The epic's section 11 is a closed record; its limits now live in the contracts.
