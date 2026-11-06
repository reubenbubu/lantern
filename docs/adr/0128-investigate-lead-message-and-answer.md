# 0128: A lead's first message in TOML, and its answer through one answer tool

Status: accepted (#860, #892)

## Context

A lead's conversation (Investigate, Mode A) opened with every fact of the lead's unit and a block of
code around each fact, a median of 29,500 characters, which was about three quarters of a lead's
cost; the owner found the facts repeated and written in internal names (6 Nov 2026). The model
answered through `submit`, a JSON schema shared with Modes B and C, in packets of leads, with a
lead it left out asked again alone in a second conversation. The owner decided on 6 Nov 2026 that
facts be "terse but still containing all the required details that the LLM needs", that #860 and
#892 land together, and that tool calls need not be JSON (`docs/design.md`, LLM integration,
Investigate's conversation: *The first message*, *The answer*, *Turns*).

## Decision

**The first message** (`InvestigateLeadMessage`) is TOML, built from the lead alone:

- `[task]`: `question`, written for the lead's kind from `prompts/investigate-questions.toml`
  (one table a kind, and `contract-mismatch-frontend` for a frontend's call through the gateway),
  its placeholders filled from the lead: the units, what its ID names (a header, a request, a
  setting, a type), the code it points at, what that code sends, the caller, the units'
  repositories, why static analysis flagged it; `verdicts`, what each verdict means for that
  kind; `repositories`, the lead's units' repositories and those its code is in.
- `[[evidence]]`, at most 6 pieces: where static analysis found the lead, the methods, types and
  fields it points at (never a whole unit or module, whose lines say nothing of it), then the
  lead's facts. A piece of code gives `what` it shows, `where` (`repo:path`) and `code`, its lines
  numbered as in the file: the Java declaration around it, at most 24 lines, else the lines and 2
  either side, each line cut at 160 characters. Pieces of a file within 24 lines of each other are
  one, their labels joined (at most 400 characters, then "and more facts on these lines"), and a
  label that only names the code gives way to a fact's sentence that says more. A fact without
  lines is a piece of its own, `fact = "..."`.
- `[start]`, when the kind has one: `hint`, where to look first.
- Facts are short sentences in plain words (`OrderController.place reads the request value
  X-User-Id.`), with short names: an entity by its last part, a method as `Type.method`, a class
  without its package, a Maven coordinate by its artifact, a deployment instance as "the compose
  deployment (default)"; no internal field names. A message over 6,000 characters drops its last
  pieces until it fits, or one piece is left (on the test sets the largest was 5,977).
- What the model judges for a kind lives only in the question file: the catalog `leads.json` keeps
  each kind's trigger, and its `judge` texts and `LeadRules.judges()` are gone. A changed question
  file changes Investigate's prompt version (its digest is part of it), so leads are asked anew.

**The instructions** are Mode A's own: `prompts/investigate-leads.system.txt` (550 characters) and
`prompts/investigate-leads.task.txt` (2,000 characters, the answer's form as a commented TOML
example), 2,550 characters in all, in place of the common system prompt, Mode A's task and the JSON
answer schema (5,176 characters before, and `submit`'s schema again among the tools). The
contract's phrases stay word for word.

**The answer** (`InvestigateLeadAnswer`, the harness's `SingleAnswer`): one `answer` tool whose input is one
string field, `toml`. The provider requires a JSON input schema per tool, so the wrapper is JSON;
nothing in it is. The TOML holds `verdict`, a confirmed lead's `title`, `finding`, `consequence`,
`impact` and `confidence`, an unclear one's optional `reason`, and up to 8 `[[evidence]]` pieces,
each `lines` (`repo:path:from-to`, or `repo:path:12` for one line), `quote` (one line of the range) and `shows` (the names in the
range its text uses).

- It is read into the entry the role's checks verify (its ranges as `cites`, every piece's names as
  `names`) and checked by the standard checks, the proposals check and `Quotes`: each quote is a
  line of its range character for character, spaces at either end aside, in the file as the model
  was shown it (masked), or the start of a line the message cut, at least 80 characters, with or
  without its "…"; a quote with no letter or digit shows nothing and is sent back; each name it shows is in its range or its path, compared as the other
  checks compare names (without case or anything but letters and digits), a dotted name
  (`RefundService.refund`, `spring.r2dbc.password`) part by part: the first panels, checking names
  exactly, sent back 65 answers for names such as a class cited from its own file. `Quotes` replaces, for Mode A, both the
  check that a cited line was shown and the coverage check (each declared name in some cited
  range), whose problems it only repeated. The names check, that every name the text uses is in
  its citations, stays.
- A passing answer is recorded and ends the conversation with no further turn (the tool box says
  so, `ToolBox.finished`). A failing one is answered in TOML: `recorded = false`, its `problems`,
  each "field: reason", and `answers_left`; the third failure ends it, the lead unclear. An answer
  written as text that reads as TOML with a `verdict` field counts as given; a turn with neither
  gets one reminder ("Give your answer through the answer tool.").

**One lead a conversation**, whatever size a setting or a calibration gives the role: each lead has
its own answer tool. A lead with no answer is not asked again alone; it is unclear.

**Limits** (`Investigator.LEAD_LIMITS`): no cap on tool turns; a guard of 60,000 input tokens on one
turn, well under the 100,000-token line above which Haiku 5.5's price rises; a cost guard of 400,000 fresh-token equivalents; a
safety stop at 100 turns. At three quarters of a guard the model is told once "Give your answer now
with what you have found." and from then only `answer` runs; another tool asked for is answered
with the same note. The estimate of the next turn counts the tool results about to go back.

**Caching.** The breakpoints stay at the end of the system prompt and on the last block of each
request. The first request is about 2,500 tokens with its tools; on Haiku 5.5 through OpenRouter its
second turn read 2,501 tokens from the cache, so the cached start is above the model's minimum.

## Evidence

On the Investigate test's panels (78 leads of project-1 to project-4 and project-8), Haiku 5.5
through OpenRouter, 6 Nov 2026, against the runs for #893 on the same leads (`main`):

| Measure | main | this ADR |
| --- | --- | --- |
| First turn on project-4, mean tokens (system, tools and message) | 21,074 | 2,650 |
| First message on the test sets, mean characters | 29,500 (median) | 1,200 to 2,800 by set, at most 6,000 |
| Leads with a verdict | 75 of 78 | 76 of 78 |
| Cost a lead | $0.0045 | $0.0036 (80%) |
| Turns a lead | 5.9 | 8.2 |
| Conversations ended on a guard or the safety stop | 14 | 0 |
| project-8 verdicts agreeing with its answer key | 23 of 54 | 43 of 54 |

Answers sent back: 27 on main, 57 here, most for a name a piece shows and its range does not hold.
A first panel that compared names exactly sent back 65 for names such as a class cited from its
own file, so names are compared as above; a line written as `repo:path:12` is read as `12-12`
since, which three answers on project-8 were sent back for. Turns rose because each tool answer is
cut at about 1,000 characters (ADR 0127; 1,500 since #915) and the model pages through a file in several calls.

## Consequences

- Investigate's prompt version is 10. Its recordings were made again from the scripted fake.
- Mode A no longer uses the common system prompt, the JSON answer schema, `submit` or the
  `ShownRanges` and coverage checks; Modes B and C still do until their answer moves to the same tool
  (#894).
- A calibration of `investigate-leads` by packet size (ADR 0112) no longer changes anything.
- Masking still hides a placeholder's variable name with its default (#912); a plaintext-secret
  lead cannot be refuted from such a line until that lands.

## Amendment (#894): refuted only with evidence of what stops the risk

A wrong "refuted" hides a real risk, since a refuted lead leaves the report. Mode A's instructions
now say: refuted means the code shows the risk cannot happen on any path, its evidence citing the
lines that stop it; for a risk in settings or the build (a secret, a baked-in address, CORS, the
schema, a single instance) look first at how the unit is built and deployed; not finding the risk
is unclear, not refuted.

A check (`InvestigateLeadAnswer.BuiltAndDeployed`) enforces what it can. A refuted answer to a lead
of `environment-baked`, `plaintext-secret`, `schema-unmanaged`, `open-cors` or `single-instance`
whose evidence cites neither a file that builds or deploys a unit (a Dockerfile, a compose file, a
CI workflow, Helm, Kustomize or Kubernetes files: `BUILD_OR_DEPLOY`) nor a file its first message
showed is sent back once, with the reason; a second such answer stands, as the rule cannot foresee
every correct refutation. The role keeps which leads it sent back, since a pipeline is built anew
for each check. A refuted answer with no evidence was sent back already. Investigate's prompt
version is 12.

On project-8's panel (Haiku 5.5 through OpenRouter, 6 Nov 2026) the rule gave 44 of 54 verdicts
agreeing with the key, against 43 before, at $0.0032 a lead; every lead the key refutes from a
build or deploy file was refuted citing one. On project-1 to project-4 every lead was answered at
$0.0031 a lead, as before. One tuning step after it, telling the model that deployment files may
be in another repository, gave 41 of 54 at $0.0037 and was dropped: runs of the same settings vary
by about four leads, so one run cannot show a step helps, and the owner chose to stop tuning there.
One lead the key confirms still comes out refuted: notification-service's staging profile, which
only a staging overlay in another repository turns on.

An absence is cited as the issue's comment asked: a contract-mismatch answer cites the caller's
line and the routes that do exist, and the names check passes it (the panels' `skils` lead).
