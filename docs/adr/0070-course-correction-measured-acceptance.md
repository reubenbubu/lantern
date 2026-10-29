# 0070: Course correction: measured against references, and the LLM may find

Status: accepted (#250), amends ADRs 0010, 0018, 0026, 0031, 0042, 0043, 0044, 0047 and 0050 and the
design's Grounding, pending the user's review (#24)

## Context

On 2026-10-29 the user compared Lantern's system report of project-4 (APA-CRM, eight
repositories), made with a real model, with a report written by hand from cited code facts
(file:line). The hand-made report and the comparison are kept outside the repository; #251 brings
the reference in. What the comparison found:

- **Missing elements.** None of the 4 data stores, no external system, no people, no risks (the
  reference has 13), no decisions (9), no glossary. Container-to-container connections: 16 found,
  all right, of 28 (57% recall); none of the 5 data store connections and not the SMTP one. The
  gateway's routes are missing (2 of its 7 components).
- **Mixed systems.** The report contained project-0's shop, analyzed earlier into the same
  database, because a snapshot carries forward every repository ever analyzed (ADR 0018): 21 of
  its 37 lines, 8 of its 26 pages, the context diagram's only line and the system's purpose. Its
  title was "System".
- **Composition.** main-service's component diagram had 45 elements; the flows were the wrong
  ones (three of the reference's six, partly, one duplicated, titled by endpoint), and drew a conditional
  email as always sent.
- **Root causes**, each now an issue: a datasource with a placeholder host gets no database
  (#255); Spring Cloud Gateway is not modelled (#257); a client's base URL set on a builder bean is
  not followed (#258); an in-set library under JitPack coordinates is not matched (#265); no
  extractor for Redis, mail, identity providers, WebSocket, JobRunr or aspects (#259 to #264);
  no system findings (#273); component selection admits any bean and never splits (#271); flow
  choice (#272); repositories without a `pom.xml` fail the run (#254); no deployment or frontend
  extractor (#266, #267).
- **Done well:** container descriptions, versions, the README's contradicted Boot version, pinned
  commits, messaging topology and routing keys, sequence conventions, honest quality gaps.

Sixty Phase 2 issues had closed with every check made on project-0, which is synthetic and was
written to match what Lantern already reads. Nothing measured Lantern against what a careful reader
of a real system finds, and static extraction alone, however extended, finds only the patterns
someone wrote an extractor for.

## Decisions

The user's decisions, recorded here; the design says what follows from them.

1. **The LLM may find, not only word.** A new role, Investigate (design, LLM integration), reads
   files and queries the graph through tools (#169) and proposes elements, connections and risks.
   Each proposal cites file:line ranges that Lantern verifies: the range exists at the snapshot's
   commit and holds the identifiers the proposal names; anything else is rejected. Accepted
   proposals are facts with `origin=llm` and a confidence, and documents mark them as found by the
   LLM. Static extraction stays the main source and wins on conflict. Built in #269.
2. **Real-model budget.** The real-model runs of 2D (recorded for replay) have a budget of $40 in
   all. Claude Haiku 5.5 serves the high-volume roles; Claude Sonnet 5.5 serves Investigate, the
   judge and the reasoning roles. The cost is reported to the user after the first round, and the
   user is asked before going on if Investigate costs more than about $5 a round. The scoring
   harness and the judge choose the model of every role per run, without code changes, and record
   it (#252, #277). The design's models for a deployment are unchanged.
3. **Acceptance criteria and test drops.** The user acts as the tester. The coordinator writes an
   "Accepted when" for every issue, stated as output on named test sets; every PR carries evidence
   (scores before and after, and an excerpt or screenshot of the generated document); reviewers
   check the evidence against the criteria. Each milestone ends with one test drop, outside the
   repository, that the user tests, tracked by a "Test drop <milestone>" issue; the user's verdict closes the milestone. How is in `CLAUDE.md`
   (How work is organized).
4. **Test sets** (design, Phasing). project-1 to project-4 are for development and PR evidence,
   each with a hand-made reference (#251). project-5, project-6 and project-7
   (booking-microservices, spring-petclinic-microservices, piggymetrics) are held out: nobody
   reads their code or analyzes them until 2D's acceptance. project-8 (FleetCare, #285), synthetic and
   larger than project-0, is a development set, like project-0; synthetic sets are never held out.
5. **Systems and repositories.** One analysis covers one named system (#253). Repositories without
   a Maven build are accepted (#254). Deployment descriptors and frontends move from Phase 5 into
   Phase 2 (#266, #267).
6. **Targets are provisional** until 2A's baseline: the 2A test drop shows the baseline
   scorecards and proposes 2B to 2D's targets, which the user confirms or changes.
7. **Documentation pages.** In the C4 report, decisions, risks, unknowns, the glossary, the
   catalog and the sources go on documentation pages, their own navigation group after the
   sequence diagrams (#249). C4 pages hold only C4 content. The report spec is the user's, so its
   change follows the user's preference recorded on #249.
8. **Phase 2 is split into milestones 2A to 2E** (measured on real systems; find the whole system;
   compose to the bar; real-model acceptance; foundations for Phase 3), with their goals and
   acceptance criteria on the milestones in GitHub, not copied here or in the design.

## What this amends or supersedes

- **Design, LLM integration, Grounding:** "The LLM never invents elements or relationships" is
  superseded. Boxes, lines and risks may now also come from verified Investigate proposals; the
  guard rails are in the design. Wording roles (Summarize, Infer intent, Compose) still only word.
- **ADR 0010** (amended): its rule that every LLM output cites the facts or summaries it used has
  one exception: Investigate's proposals cite file:line ranges, which Lantern verifies (design,
  Grounding). Summaries still cite as before.
- **ADR 0018** (amended): carrying forward is now within one named system. A snapshot belongs to a
  system and carries forward only that system's repositories; a repository can be removed from a
  system and is then not carried forward, replacing "Removing a repository from the system is not
  supported yet". Ending facts only for analyzed repositories and carrying forward only analyzed
  commits stand. Built in #253.
- **ADR 0026** (amended; one decision superseded): "The LLM words, it never invents" is superseded
  as above. "The rest are judged on the test sets each phase" becomes: measured against hand-made
  references, scored, and from 2D judged by an LLM (design, Quality bar). "Test sets are generic,
  the design names none" becomes: the design names the sets and their roles (development,
  held-out, synthetic), and still no single set shapes it. The rubric, the C4 definitions and the
  stitched sequence diagrams stand.
- **ADR 0031** (amended): content that is not at a C4 level no longer goes onto C4 pages. Decisions
  (until now a section after the container diagram, ADR 0044), risks, unknowns, the glossary, the
  catalog and the sources become documentation pages after the sequence diagrams once the spec has
  them (#249). Elements, lines and risks that Investigate found are marked as found by the LLM; the
  marker is C4 content, and the spec gains it with #249. `person` and `external` elements may now come from the code and Investigate,
  not only annotations. The renderer's mapping, IDs and checks stand.
- **ADR 0042** (amended): a datasource whose server or database is a placeholder no longer stays an
  `unresolved-link` of kind `database`; it becomes a database of its unit alone (keyed by unit and
  key, never shared), named from what is known, with the technology from the URL (#255). Shared
  databases between units still need the same resolved server and database.
- **ADR 0043** (amended): the README claim checks are frozen as they are. No new fixed readings of
  words are added in Phase 2 (fixing bugs in the existing checks is still allowed), whose effort goes to finding the system (#250). Claims none of them
  reads stay unjudged until #221 (LLM judgment), which stays in Later.
- **ADR 0044** (amended): the system is the analysis's named system, and its report is named after
  it, not after its repositories (#253). The container view is one view of the whole system, split
  by concern above 15 elements, with per-unit views only where still needed; an element links to
  its own page; pages run from the entry (frontend, gateway) inward (#270). This replaces the split
  into one view per unit. Components leave out mappers, configuration, DTO and util classes, are
  split above 15 by groups of entry points, and their lines carry one intent phrase instead of
  method lists (#271). The context page shows people and external systems the code shows (#268,
  #260, #261) instead of always noting that no people are known. The decisions table moves to the
  findings page (#273).
- **ADR 0047** (amended; its choosing of flows superseded): flows are chosen by cross-service reach
  and business weight, synchronous flows competing with asynchronous ones, with flows of the same
  shape shown once, self-deliveries kept, constructors not taken as alternatives, branches around
  sends drawn as `opt` frames, and each titled as a business scenario by the LLM (#272). Tracing
  and stitching stand.
- **ADR 0050** (amended): its premise that the LLM never invents elements or relationships now
  holds for the Describer only. The Describer still only words what is drawn; an element or line
  Investigate found is drawn from its fact, so the Describer may word it, citing that fact.

The ADRs pending the user's review (#24), these among them, stay the user's to review; this ADR
does not replace that review.

## Consequences

- Every issue's evidence is a score on real systems, not only project-0's tests.
- Investigate makes real calls in every real run, so a run costs more; its cost is measured in 2D
  against the budget above.
- The design's Phase 5 no longer holds Docker and Kubernetes extractors.

## Not verified

The targets are unmeasured until 2A's baseline, and whether Investigate's proposals reach the
references' recall within the budget is only known from 2D's runs.
