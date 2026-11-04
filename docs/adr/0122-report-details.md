# 0122: Report details: people's sentences, the stored basis, the overview and flow titles

Status: accepted (#767)

## Context

The 1F and 1G review (3 Nov 2026) found four small defects in the C4 report, each against
`docs/quality-bar.md` (Words say what the diagram cannot; the C4 report row):

- A person was described by the linker's own note ("Inferred: The code has invitations…") even
  when Describe had written a sentence for them.
- The system's overview section held a list of its repositories and the units each runs. The C4
  report never shows it: under its title it shows only that section's paragraphs and callouts
  (ADR 0048, amendment for #681). The quality check "Every specific claim links to a fact or
  source" still checked it, and failed on project-3, project-4 and project-8 for repositories
  with no deployable unit.
- Whether a responsibility sentence or the system's purpose rests on what people wrote was
  derived again in the composer from its citations, though the role that wrote it had stored
  that as the fact's `basis` (ADR 0103).
- A flow page's title was its scenario followed by its trigger, "(POST /api/… on main-service)",
  which made long titles.

## Decisions

- **People.** A person is described by Describe's sentence when there is one, marked inferred as
  every person is (ADR 0074); the linker's note describes a person only when Describe wrote none.
- **The overview lists no repositories.** The system's overview section holds its purpose and key
  fact only. The report names each repository, with its pinned commit, under its title already.
  Dropping the list, rather than teaching the check which blocks a template shows, keeps the
  composer free of one template's layout and leaves no text that is checked but never read.
- **The stored basis decides.** A responsibility sentence and the system's purpose are shown as
  written when their fact's stored `basis` is `evidenced`, and opened with "Inferred:" or
  "Purpose inferred from the code:" when it is `inferred`. Citations no longer decide. The roles
  set `evidenced` when a sentence cites a README claim or, for an element's sentence, code with
  Javadoc (ADR 0059, ADR 0065, ADR 0069). That is not quite what the composer derived: it also
  followed a sentence's citations of a unit's answer to the claims behind it, while Describe
  stores an element's sentence as inferred even when the unit sentence it cites rests on a README.
  Such a sentence now shows "Inferred:", which is the truer mark, since it is derived from another
  sentence. A sentence stored without a `basis` reads as inferred.
- **Flow titles are the scenario.** A flow page is titled by its scenario alone: the LLM's title
  (ADR 0108), else its entry point's name. Its Overview table's Trigger names the request,
  message or schedule, where it runs and its handler: "POST /orders on front (Api.place)",
  "The schedule cron 0 0 2 * * * in ship (Nightly.run)"; a request a frontend sends or a gateway
  routes names both ends: "POST /api/auth/sign-in from crm-frontend to auth-service
  (AuthController.signIn)", "GET /api/ship/status to edge, routed to ship (Status.get)". The
  first flow of a scope with a scenario keeps the bare title; a later one with the same scenario
  gets its unit's name, then its handler's, then a number, in brackets.

## Consequences

- Report titles are short; the trigger moved into the table that was already there.
- Golden files changed: the overview's repository list is gone, flow titles lost their bracket
  and triggers name where they run.
- What the model sees is unchanged: the flow prompts use the heading's title and its "starts"
  clause, not the trigger.
