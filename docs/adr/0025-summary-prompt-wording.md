# 0025: Summary prompt wording and length

Status: accepted (#71), pending the user's review (#24)

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

- **Purpose first, inferred, never hedged.** The system prompt asks for a first sentence saying
  what the unit is for in words a non-engineer understands, then how it does it, naming only the
  dependencies that matter. It asks the model to infer purpose from names, annotations (examples:
  `@RestController`, `@Entity`, `@KafkaListener`), Javadoc and code, or from the parts' summaries,
  and forbids sentences saying the input does not show something; what cannot be told is left
  out. Where the purpose is not clear, the first sentence says what the unit is and contains
  instead, so the model is not pushed to invent one.
- **Citation rules are unchanged.** The prompt still asks for references at the end of every
  sentence, and now also asks a sentence about purpose to cite what it is inferred from. As in ADR
  0010, the check enforces at least one valid citation per summary and no unknown references; a
  citation per sentence is requested, not enforced (#77).
- **Plain names, no IDs in the instructions.** The prompt names the unit and the contained units by
  kind and plain name: `type Order.Line in package com.example.shop`, `package com.example.shop`,
  `Maven module shop-core` (the artifact ID), `repository shop`. The system prompt asks for plain
  names and describes IDs (a kind and a colon) as what not to write, without examples to copy.
  Fact lines keep their entity IDs, since they identify the other end of a relationship; this is
  the one place the model still sees IDs.
- **No restating a single part.** A module or repository with exactly one contained unit is told not
  to restate that summary. A repository then gets two or three sentences on what it is for; a
  module is asked what it adds, such as its dependencies. A repository whose only module is a
  parent POM holding other modules is summarized as usual, since that module is not all there is
  to say. Packages with a single type get only the general rule: the system prompt asks every unit
  not to restate its parts' summaries sentence by sentence.
- **Repository summaries are written for the business reader**: role and main capabilities first,
  frameworks and libraries briefly if at all, also when its summary is merged from parts (ADR
  0024).
- **Each level says how much to write and what to cover**, in the user turn, with numbers kept in
  `SummaryLevel` next to the word limits (about 25 words a sentence, which a test checks):

  | Level | Sentences | Words | Covers |
  | --- | --- | --- | --- |
  | Type | 2-4 | 80 | its purpose, main responsibilities and the types it works with most |
  | Package | 3-5 | 120 | what it is for and its main parts |
  | Module | 3-6 | 150 | first its purpose in plain words for a non-engineer, then its main parts and the dependencies that matter |
  | Repository | 3-6 | 150 | the same as a module |

  The repository limit drops from 200 to 150 words (output limit 1,000 to 800 tokens), so six
  sentences fit it; a single-part repository is asked for two or three sentences instead.
- **`Prompt.VERSION` is 3**, so every cached summary is written again on the next run.

## Not verified

Whether real models follow the new wording needs real LLM calls, which tests do not make. The
golden file `lantern-compose/src/test/resources/golden/summary-prompts.txt` shows exactly what the
model receives for the fixture projects.
