# 0124: Where every LLM fact came from

Status: accepted (#753)

## Context

`docs/design.md` (Design principles) says every claim has provenance and that documents can trace
any statement back to its evidence. The 1F and 1G review (3 Nov 2026) found that LLM facts fell
short of it:

- No LLM fact said which request it came from. Its origin named the role and prompt version and
  its attributes the model, but two runs with the same model could not be told apart, and the
  answer behind a fact could not be found in the answer cache (ADR 0100).
- Investigate and Resolve stored no `basis` (ADR 0103 gives every other role one).
- The file ranges Resolve's answers cite stayed in the answer cache; the link fact did not keep
  them.
- `cites` holds fact (observation) IDs, not claim IDs.

## Decision

1. **`answer`.** Every fact an LLM role stores from an answer records `answer`: the fingerprint of
   its item in the answer cache, the key of the `llm_answers` row that holds the answer (or the
   model's omission, or the rejected answer's drop, which the cache keeps too, #682). It names
   the request, or the conversation for roles that read files, that produced the fact. It is
   descriptive, not part of the claim: a new answer to the same question is the same claim.
   - Summarize, Describe, Name groups, the flow roles, Purpose and Investigate's verdicts, risks
     and connections take it from the item they answer. A risk that several leads' answers
     confirm keeps the answer of the first lead it is stored for.
   - Resolve's links and the injection screen's findings record theirs too, and Name groups' merge
     suggestions (findings of `finding-raised`) carry `model`, `answer` and `basis`.
   - Facts no request produced carry none: Investigate's `not-investigated` facts, and the
     verdict `unclear` of a lead that was not asked (its role's budget was spent, or the call
     failed), whose `reason` begins "not asked: ".
2. **`basis`.** Investigate's verdicts, risks and connections and Resolve's links are `inferred`:
   each is the model's judgement of code and facts, not something people wrote. A finding of the
   injection screen is `evidenced`: it quotes the file.
3. **`ranges`.** A link Resolve resolves keeps the file ranges its answer cites, as
   `repo:path:from-to`, in `ranges`; absent when it cites none.
4. **Cited claims.** The claims a fact cites are its `lineage`, which the store fills with the
   claim IDs of the facts `cites` (and `fact`, `facts`, `claim`) name (ADR 0091). `cites` keeps
   fact IDs: its readers look up the cited fact in the snapshot by them, sentence by sentence.
   Changing them to claim IDs would change the data model for every reader; the claim IDs are
   already recorded, so it is not done here.
5. **The check.** `Project0EndToEndIT` checks every `llm` fact of a fake project-0 run: role and
   prompt version, model, `answer` (stored in the cache, for the same model), `basis`, and a
   lineage of stored claims.

## Consequences

- Any LLM statement in a document can be traced to the stored answer, and through it to the
  prompt inputs and files read (`llm_answers.inputs`, `files_read`).
- `answer` changes whenever the inputs change, so an observation is stored anew on each re-ask,
  as its text already was.
- Real runs still have to show the same fields on stored facts; until then the evidence is the
  fake project-0 run.
