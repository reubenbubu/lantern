# 0025: Summary prompt wording and length

Status: accepted (#71), amended by ADR 0059 (#193), pending the user's review (#24)

## Context

A DeepSeek run over OpenWMS (#17, 1,099 summaries) gave accurate, well-cited summaries with four
wording problems:
- 402 summaries said the input did not show what the unit was for, as the prompt asked: "Where they
  do not show what something is for, say what it contains rather than guessing".
- Repository summaries copied the entity ID from the prompt ("The repository repo:org.openwms...").
- Single-module repositories restated their module's summary sentence by sentence.
- Repository overviews listed packages and libraries instead of saying what the service does.

ADR 0024 added source files, so type prompts now show names, annotations, Javadoc and code;
package, module and repository prompts show facts and their parts' summaries. The design
asks for audiences up to executives (Composition, granularity table) and keeps every sentence cited
(LLM integration, Grounding).

## Decisions

- **Purpose first, never hedged.** The system prompt asks for a first sentence saying
  what the unit is for in words a non-engineer understands, then how it does it, naming only the
  dependencies that matter. It asks the model to infer purpose from names, annotations (examples:
  `@RestController`, `@Entity`, `@KafkaListener`), Javadoc and code, or from the parts' summaries,
  and forbids sentences saying the input does not show something; what cannot be told is left
  out. Where the purpose is not clear, the first sentence says what the unit is and contains
  instead, so the model is not pushed to invent one. Since ADR 0059 the model takes the purpose
  from README claims, Javadoc or an evidenced part's summary where the input has them, infers it
  only otherwise, and marks which it did; documents show an inferred purpose as such.
- **Citation rules are unchanged.** The prompt still asks for references at the end of every
  sentence, and now also asks a sentence about purpose to cite the facts, source files or
  summaries it is inferred from. As in ADR
  0010, the check enforces at least one valid citation per summary and no unknown references; a
  citation per sentence is requested, not enforced (#77).
- **Plain names, no IDs in the instructions.** The prompt names the unit and the contained units by
  kind and plain name: `type Order.Line in package com.example.shop`, `package com.example.shop`,
  `Maven module shop-core` (the artifact ID), `repository shop`. The system prompt asks for plain
  names and describes IDs (a kind and a colon) as what not to write, without examples to copy.
  Fact lines keep their entity IDs, since they identify the other end of a relationship; this is
  the one place the model still sees IDs.
- **No restating a single part.** A package, module or repository whose prompt shows a single
  contained unit's summary is asked for 1 to 3 sentences and at most 60 words on what it is for as
  a whole and what it adds, such as its dependencies, and told not to restate that summary. This
  includes a repository whose only module is a parent POM: its prompt shows nothing else. The
  system prompt also asks every unit not to restate its parts' summaries sentence by sentence.
- **Repository summaries are written for the business reader**: role and main capabilities first,
  frameworks and libraries briefly if at all, also when its summary is merged from parts (ADR
  0024). Since ADR 0059 a repository's prompt also shows its README's first claims.
- **Each level says how much to write and what to cover**, in the user turn, with the numbers
  kept in `SummaryLevel` next to the word limits (about 20 words a sentence, which a test checks).
  The system prompt's first-sentence rule applies at every level.

  | Level | Sentences | Words | Covers |
  | --- | --- | --- | --- |
  | Type | 2-4 | 80 | its purpose, main responsibilities and the types it works with most |
  | Package | 3-5 | 120 | what it is for and its main parts |
  | Module | 3-6 | 150 | what it is for, then its main parts and the dependencies that matter |
  | Repository | 3-6 | 150 | what it is for, then its main capabilities and the other modules or services it depends on |
  | One part only | 1-3 | 60 | see above |

  The repository limit drops from 200 to 150 words (output limit 1,000 to 800 tokens).
- **`Prompt.VERSION` is 3**, so every cached summary is written again on the next run.

## Not verified

Whether real models follow the new wording needs real LLM calls, which tests do not make. The
golden file `lantern-compose/src/test/resources/golden/summary-prompts.txt` shows exactly what the
model receives for the fixture projects.
