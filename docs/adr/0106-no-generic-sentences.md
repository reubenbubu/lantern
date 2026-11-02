# 0106: No generic sentences, no fragments

Status: accepted (#275); amends ADRs 0100 (a ninth check) and 0104 (when a summary is asked again)

## Context

`docs/quality-bar.md` (Words say what the diagram cannot) asks that a responsibility sentence not
restate the name or kind and that no sentence be true of any Spring application. The last
real-model reports (milestone 2A's baseline) broke both: on project-4, 16 of 96 descriptions held
sentences such as "It is a Spring service.", "OrganizationService is a Spring service class in the
package …" or "It works mainly with Organization, OrganizationRequest and OrganizationResponse.",
and several descriptions opened with "It" or "Its", because the summary they fall back on had lost
its first sentence to a check and kept the ones that followed it. The Form check (epic, section
10) catches only a responsibility that restates its subject's name and a broken word limit.

## Decisions

- **A Generic check** joins the pipeline after Form, for every role. It rejects any text but an
  intent phrase that matches one of the patterns in
  `lantern-harness/src/main/resources/checks/generic-sentences.txt`: framework mechanics (being a Spring bean, being injected, being managed), generated members
  (Lombok, getters and setters, inherited CRUD operations), starting the application, following a
  standard pattern, and the members list "works mainly with …". The patterns are data, one Java
  regular expression a line, matched case-insensitively anywhere in the sentence, so a pattern is
  added without code.
- **Sentences shown alone.** A responsibility, and the first sentence of a summary (a new text kind,
  `LEAD`, since a part without a responsibility shows its summary as its description), may also not
  open with one of the patterns in `checks/weak-openings.txt`: a pronoun ("It", "Its", "This",
  "They" …), or the kind of part said again ("The class is …", "A Spring service that …", "This
  controller …"). A later sentence of a summary may still open with "It", since it follows the
  first. The lead may name its unit, unlike a responsibility, which the Form check holds to its
  60% rule.
- **Asking again instead of a fragment.** A summary answer whose first sentence is left out while
  others pass is sent back once, with the reasons and a note to write the whole summary again
  opening with what the unit is for. The answer to that repair turn is taken as it is, its first
  sentence left out or not, so a unit is never left without a summary for the levels above it
  that cite it.
- **No new prompt version.** The prompts already ask for this (no boilerplate, no restated names);
  the checks hold answers to it. Cached answers accepted before stay until their inputs change.

## Consequences

- Run on the descriptions of the 2A baseline's real-model reports, the patterns flag 16 of 96 on
  project-4, 1 of 87 on project-2 and none on project-1 and project-3.
- A summary that loses its first sentence costs one more call. Fake runs, whose sentences open with
  a placeholder, are unchanged.
