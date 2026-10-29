# 0051: README claims the code contradicts, shown in documents

Status: accepted (#159), amends ADR 0043 and ADR 0046, pending the user's review (#24)

## Context

The claim checks record a `claim-contradiction` fact on a README entity for each claim the code
contradicts: the claim, the check, what was stated and found, and the facts on the code's side with
where they are written (ADR 0043). No document showed them. `docs/design.md` (Annotations:
knowledge layer, READMEs and the wiki) says such claims are flagged, and the Quality bar asks for
unknowns and conflicts to be stated plainly. Issue #159 asks for each contradicted claim to be shown
where the README's units appear, in the Architecture Explorer and in the quality report block (ADR
0028), quoting the claim and linking both sides.

## Decisions

- **Which documents.** A document shows the contradictions recorded on the READMEs of the
  repositories in its scope (the fact's evidence, which is the claim's): one repository for a
  per-repository view, all of them for a system view. The units a README speaks for are in its own
  repository (ADR 0043), so that is where its units appear.
- **A warning callout in the containers section**, one per contradicted claim, after the section's
  other blocks, titled "Claim the code contradicts". It says where the claim is written (the file
  as code, the line and the repository), quotes the claim as written with its whitespace folded,
  and cites the claim fact; for a technology or version check it adds what the claim states and
  what the code shows; then it tells each fact on the code's side ("shop-app uses shop on db",
  "shop-app's technology is Spring Boot 3.5.0 / Java 21", with the Spring profile when it holds only under one),
  each entity a cross-reference, each with where it is written and a citation of that fact. The
  callout cites the contradiction, the claim and the code's facts. The containers section is in
  every Lantern view (Explorer, Brief, C4 reports), so every one of them shows it.
- **A Quality bar check, "README claims agree with the code".** The Quality bar's "Every
  document" row gains it, so the quality report names it as the Quality bar words it (ADR 0028,
  ADR 0046). The composer reports one failure per contradicted claim, after the other checks'
  failures. The failure points at the claim's callout (the C4 report puts it on the containers
  page, ADR 0046) or, in a document without a containers section, at nothing; its detail says where
  the claim is written, quotes it (at most 160 characters) and how many facts of the code
  contradict it; it cites the same facts as the callout.
- **The quote is the document's text.** A quoted claim goes through the other checks like any
  text, so an acronym in it ("REST API") is one the glossary must explain.
- **Both sides must hold.** A contradiction whose claim or code facts are not in the snapshot is
  not shown, since what it would cite is not there.
- **No new section kind and no document model change.** Callouts, citations, cross-references and
  quality report failures already carry all of it, and every template already renders them.
- **Checks.** `ClaimContradictionsTest` covers the callout's wording and citations, the failure and
  its target, a document without containers, the scope, a contradiction whose code side does not
  hold, and a second claim read from a call under a profile, an unlinked call and a long quote. The
  system C4 report's golden files (`runtime.c4.json` and the renderer's `runtime.json`) now hold a
  README claim the code contradicts.

## Not in this issue

Claims no check understands, judged by the LLM (#160).
