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
- **Two ranges per size rule** (amended when #760 was reopened; the first version gave each rule only a maximum).
  - `requested` is the range the prompt states, `[min, max]`, with the prompt's own words in `stated`, such as "one plain sentence of 10 to 22 words". `accepted` is the range the check enforces.
  - Unless the rule sets its own `accepted`, each bound of the requested range moves out by 15% of itself, rounded, and at least one unit (`Contract.accepted`): 10 to 22 words accepts 8 to 25. The minimum never falls below one word, or below none for a count. Per bound, so every accepted maximum the checks enforced before stays as it was. The issue's example, 30 to 40, gives 25 to 46 by this rule.
  - A text below the accepted minimum is rejected like one above the maximum. The rejection names the range and the side ("Has 2 words; the accepted range is 8 to 25 words, so say more."), and the repair turn carries it.
  - A sentence count has both ranges too, and is measured, not enforced: splitting sentences is a guess, and a wrong guess would send good answers back.
  - A rule marked `"enforced": false` is measured only. This applies to the injection screen's quote, which must never hide a suspected injection.
  - Counts (Investigate's proposals, the support judge's claims) have both ranges as well. "At most 10 things" states the range 0 to 10.
- **The checks read the contracts.** Each role's word range is `Contract.of(role).acceptedWords(field)`, so a contract edit changes what the checks accept. This covers Describe, Name groups, Flow significance and narrative, Purpose, Resolve, Investigate, Summarize and the wording judge's longest text.
  - Summarize states its requested range in each request from the contract (`Prompt.length`, "2 to 4 sentences of 10 to 30 words each"). The other prompts state theirs in their own text, which the test below holds to the contract.
  - Counts the checks keep read it too (`Contract.count`, Investigate's proposals).
- **A contract is not part of a prompt version.**
  - A contract changes what is accepted, not what a model is asked, so editing one asks nothing again.
  - Stored answers are checked again when reused.
  - Contracts are pinned in `prompts.sha256`, like every committed prompt file.
- **The margins decided before contracts are kept**, as the contract's own `accepted` maximum:
  - Flow significance's reason: 18 asked, 20 accepted (ADR 0108).
  - The walkthrough: 75 and 80 (ADR 0108).
  - Name groups' merge reason: 18 and 20 (ADR 0107).
  - Investigate's finding and consequence: 70 (ADR 0110).
  - Investigate's connection description: 15.

  Every other maximum takes the default, which reproduces each earlier margin except three that had none. Purpose's purpose now accepts 29 instead of 25, its key fact 35 instead of 30, and Resolve's reason 35 instead of 30. The owner's 15% default now covers them too.
- **The minimums come from the 1F recordings** (#760, reopened). The requested minimum is the 5th percentile of the rule's lengths in the 1F recordings (DeepSeek, project-1 to project-4 and project-8; `llm.jsonl.gz`), lowered until the accepted minimum keeps at least 99% of those texts. A model that wrote what the prompts asked before is then almost never sent back, while a stub (a 2-word responsibility, a 1-word title) is. A rule with fewer than 20 recorded texts takes the minimum of a sibling rule of the same kind instead: Purpose's two sentences that of a one-sentence description (10 words, as Describe's unit responsibility), Resolve's reason and Name groups' merge reason that of Flow significance's reason (7 words), the other reason a role gives for a judgement. The injection screen's quote has no recorded text and asks for 3 words, as a quote shorter than that cannot show an instruction. The wording judge's judged text is not an answer: its range, 1 to 60, is the texts it judges.

  | Rule | 1F texts | Fewest | 5th percentile | Median | Most | Requested | Accepted | 1F texts below the accepted minimum |
  | --- | --- | --- | --- | --- | --- | --- | --- | --- |
  | describe-elements responsibility | 73 | 5 | 8 | 13 | 18 | 6 to 22 | 5 to 25 | 0 (0.0%) |
  | describe-units responsibility | 306 | 7 | 10 | 14 | 21 | 10 to 22 | 8 to 25 | 1 (0.3%) |
  | describe-units intent | 1054 | 3 | 4 | 6 | 8 | 4 to 7 | 3 to 8 | 0 (0.0%) |
  | flow-narrative title | 116 | 3 | 3 | 4 | 5 | 3 to 5 | 2 to 6 | 0 (0.0%) |
  | flow-narrative walkthrough | 116 | 25 | 30 | 48 | 75 | 30 to 75 | 25 to 80 | 0 (0.0%) |
  | flow-narrative preconditions | 116 | 5 | 6 | 11 | 21 | 6 to 22 | 5 to 25 | 0 (0.0%) |
  | flow-narrative postconditions | 110 | 9 | 10 | 16 | 22 | 10 to 22 | 8 to 25 | 0 (0.0%) |
  | flow-significance reason | 393 | 5 | 7 | 11 | 19 | 7 to 18 | 6 to 20 | 2 (0.5%) |
  | injection-screen quote | 0 | - | - | - | - | 3 to 15 | 2 to 17 | - |
  | investigate title | 241 | 5 | 5 | 6 | 8 | 5 to 7 | 4 to 8 | 0 (0.0%) |
  | investigate finding | 361 | 17 | 22 | 39 | 66 | 20 to 60 | 17 to 70 | 0 (0.0%) |
  | investigate consequence | 241 | 13 | 22 | 31 | 54 | 22 to 60 | 19 to 70 | 2 (0.8%) |
  | investigate description | 29 | 3 | 3 | 6 | 10 | 3 to 12 | 2 to 15 | 0 (0.0%) |
  | name-groups name | 29 | 1 | 1 | 2 | 4 | 1 to 3 | 1 to 4 | 0 (0.0%) |
  | name-groups responsibility | 29 | 11 | 13 | 17 | 22 | 13 to 22 | 11 to 25 | 0 (0.0%) |
  | name-groups intent | 28 | 5 | 5 | 7 | 8 | 5 to 7 | 4 to 8 | 0 (0.0%) |
  | name-groups mergeReason | 6 | 10 | 10 | 13 | 16 | 7 to 18 | 6 to 20 | 0 (0.0%) |
  | purpose purpose | 4 | 19 | 19 | 20 | 20 | 10 to 25 | 8 to 29 | 0 (0.0%) |
  | purpose keyFact | 4 | 18 | 18 | 22 | 26 | 10 to 30 | 8 to 35 | 0 (0.0%) |
  | resolve reason | 3 (from the recorded calls) | 19 | 19 | 19 | 20 | 7 to 30 | 6 to 35 | 0 (0.0%) |
  | summarize sentence | 2799 | 3 | 11 | 19 | 33 | 10 to 30 | 8 to 35 | 18 (0.6%) |
  | wording-judge judgedText | 0 | - | - | - | - | 1 to 60 | 1 to 60 | - |

  Resolve's reason is stored in no fact; its three lengths are the reasons in the recorded calls. Under these minimums 23 of the about 6,000 recorded texts would have been sent back for being too short, most of them Summarize sentences of 3 to 7 words such as "Targets Java 21."
- **A test ties prompts to contracts.**
  - `ContractTest` fails if a prompt does not state a contract's requested range in the contract's words, or states another range ("N to M"; "at most N" only for a count, whose least is none). Each stated phrase must occur once in the role's prompts; a size the prompts state again elsewhere is listed under `restated`; and any other "N to M", "at most N" or sentence count in the prompts fails, so a size changed or added on another line is caught.
  - It also holds every rule to a requested and an accepted range, the accepted one holding the requested one, and runs the Form check on a text at each accepted edge (accepted) and one word outside each (rejected).
  - `PromptTest` checks the same for Summarize's request.
- **The scorecard reads them** (`lantern-web/scripts/contracts.mjs`). Its "Response contracts" section splits each size rule over a run's stored texts (`llm.jsonl.gz`) and its rejections (`rejections.jsonl.gz`), too short and too long apart: of the stored texts, the share within the requested range, accepted outside it (short, long), and outside even the accepted range (short, long; only a rule that is measured, or a run before the rule, keeps those); and the answers rejected for their length, short and long, each counted apart as first answers and repairs. Counted apart because a rejected answer that is repaired is stored too, and a rule such as Resolve's reason stores no texts at all. Sentence rules are listed too, measured only.
- **The quality score** (`prompts/quality.json`; research in `docs/research/quality-score.md`).
  - Per kind of text (responsibility, intent, name, prose), weighted yes/no checks sum to 10.
  - The judged checks are the wording judge's two questions. "Says what it does" weighs 3 and is a gate: a text that fails it scores at most 4. "Specific to this system" weighs 2, or 3 for a name.
  - The deterministic checks cover the length within the requested range, the requested sentences, an opening that is not a pronoun or a restated kind, an intent that opens with a verb and names no protocol, and no filler phrases.
  - The scorecard gives per role the mean over the texts judged on both questions, how many the gate capped, and the points lost per check, most first.
  - A difference under 0.7 is within the paired rule's tolerance of 7 points in 100.
  - The owner's other two judged checks, "uses the code's own terms" and "no padding", are written as questions under `pending` in `prompts/quality.json`. #757 labels them blind, adds them to the wording judge and gives them weights; until then the score leaves them out and the filler list stands in for padding.

## Consequences

- A rule changes in one file. The prompt's stated number must change with it, or `ContractTest` fails, and a prompt change raises the role's prompt version as before.
- Every word range a role answers to is now stated in its prompt (#760): Investigate's title, finding and consequence in both its prompts (leads and explore, which check the same fields), and its connection description in the explore prompt. Flow narrative's closing check restates its four ranges, which `ContractTest` also catches when written as a text's name and a number ("title 5"). Two rules stay contract notes, to be stated when those prompts next change:
  - the 40-line range in the leads prompt;
  - Investigate's schema allowing 12 proposals where the check keeps 10.
- Stating ranges changed the prompts of Describe (units and elements), Flow significance and narrative, Name groups, Purpose, Resolve, Investigate, Summarize, the injection screen and the support judge, so each role's prompt version was raised and their answers are asked again. A recording made before (#759's replay) no longer answers those roles. The support judge's 10% sample is taken by the fingerprint of its item, which holds its prompt version, so it now samples other texts than before.
- The fake model's placeholders are long enough for the contracts (`FakeLlmConfiguration`): a fake run still stores a text for everything it is asked about.
- `WordingPrompt`'s old flow sizes (8, 100 and 30 words) are not a contract; removing them is #775.
- The quality score is reasoned, not fitted. CheckEval found fitted weights gained nothing for most judges. The weights change only through an issue.
- The epic's section 11 is a closed record; its limits now live in the contracts.

## Re-check on the 1H baseline (#756)

The minimums re-checked by #760's method on the texts of the 1H baseline (#756): `complete` run, the five sets, Haiku 5.5 and Sonnet 5.5. Every rule's texts stay above its accepted minimum, so no check changes. The requested minimums and the accepted minimums would move as below. Nothing changes in the prompts or contracts here: a requested range is stated in the prompt, so moving one raises the role's prompt version and asks the model again. The owner decides whether to move them, in its own issue.

| Rule | Texts | Requested min now -> 5th percentile | Accepted min now -> 99