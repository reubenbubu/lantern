# Architecture model

This is the research for #615, the first issue of milestone 1D "Architecture model stored".

**Depth: high.** The question settles rules of the design: how the model is keyed, what its lineage holds, and how findings and leads keep their identity. Eighteen sources were read: fifteen in full, three in substantial part (jQAssistant about half, the Alibaba study about 70%, IRIS about 90%). Six more were looked for and not read, and they carry no weight here (see Not read).

## Question

How should an architecture model be derived from code facts once per snapshot and stored with lineage, so that documents and LLM roles only select from it and never recompute it? The model holds components, component interactions, candidate flows with ordered steps, findings, investigation leads and deployment instances. The epic's plan (sections 6, 8, 9 and 11.2) is checked here, not assumed.

## What we learned

**1. Derived facts, provenance and incrementality**

- **Glean stores a derived predicate once or computes it on demand.** A stored derived predicate is computed by a `derive` step before the database is finished. Dependent predicates run "in bottom-up dependency order". An on-demand predicate is computed at query time and is used to give clients a convenient shape. <https://glean.software/docs/derived/>
- **Glean gives a derived fact the AND of its inputs' owners.** A derived fact "should be visible if and only if all the facts that it was derived from are visible."
  - The scheme works because ownership sets stay few: about 10 to 100 times fewer sets than facts. Glean recommends coarse units, one per file or module.
  - It admits a gap. When a base fact gains a new owner in a stacked database, the facts derived from it do not, so the two can disagree. The fix ("incremental derivation on the stacked DB") is "not implemented yet".
  <https://glean.software/docs/implementation/incrementality/>
- **Soufflé does not store proof trees.** Per tuple it stores only the number of the rule that produced it and the height of its smallest proof. Trees are rebuilt lazily, "one level of a proof tree" at a time, by searching backwards over stored tuples. It cannot explain automatically why a tuple is absent. <https://souffle-lang.github.io/provenance>
- **W3C PROV records a derivation with many sources as one activity.** The activity *used* each source and *generated* the result, with an optional role on each use. A plan names the recipe the activity followed. A revision is "a new entity", never a change in place. <https://www.w3.org/TR/prov-primer/>
- **CodeQL's overlay analysis re-extracts only changed files** (found by comparing git object IDs). It reuses the base database's cached results when it evaluates queries. Some queries are tagged `exclude-from-incremental`. <https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/scan-from-the-command-line/incremental-analysis>
- **jQAssistant "concepts" add labels and relations to the graph,** ordered by `requiresConcept`. A concept that returns no rows raises a warning, since it probably does not match the code. <https://jqassistant.github.io/jqassistant/current/>

What this means for Lantern:
- Lineage should hold one level only: the direct inputs and the rule. This is Soufflé's rule number and PROV's activity with its uses. Explanations follow lineage recursively.
- Lineage is sound for knowing what to drop, not for knowing what to add. A new link can create a new flow step that no stored lineage mentions. So whole-rule recomputation, which the epic already plans (section 8, Incrementality), is the correct default. Invalidating by lineage alone would miss new paths.

**2. Components**

- **The C4 model defines a component** as "a grouping of related functionality encapsulated behind a well-defined interface". It is never deployable. How code is grouped "will vary from codebase to codebase". In C4's PetClinic example, each controller is its own component, and the services and repositories are combined into one. <https://c4model.com/abstractions/component>
- **Structurizr's component finder** works with "a pluggable and customisable set of matching and filtering rules". <https://docs.structurizr.com/java/component>
  - Its PetClinic workspace defines one strategy per technology: `@Controller` gives "Spring MVC Controller" components, and names ending in `Repository` give "Spring Data Repository" components. Each strategy also takes a description, a source URL, a group and the relationships to add for each component found. <https://raw.githubusercontent.com/structurizr/java/master/structurizr-dsl/src/test/resources/dsl/spring-petclinic/workspace.dsl>
  - So Structurizr makes repositories components of their own. Lantern's bar folds them into the component of the entry point that reaches them, which is a deliberate difference.
- **Spring Modulith derives modules from packages:** each direct subpackage of the main package is a module. A module's root package is its API and its subpackages are internal. Named interfaces expose more packages. `ApplicationModules` is an in-memory model listing each module's name, base package and beans, marked exposed or internal. A module's identity is its package name, which is stable. <https://docs.spring.io/spring-modulith/reference/fundamentals.html>
- **Cerny and Taibi's prototypes** found components from framework stereotypes (controllers, services, repositories, entities). They report precision and recall above 95% for component detection, and match calls between services on HTTP method, path and parameters. They note that static matching is "the best approximation", never exact. <https://arxiv.org/pdf/2205.08527>

**3. Flows: enumerating, bounding, deduplicating, ranking**

- **Alibaba's production call graphs** were compared as the *set* of calls each holds (MinHash and Jaccard), not as an ordered structure. <https://arxiv.org/html/2504.13141v3>
  - Most services have one dominant graph: the median is 1 unique graph per service, and 59% of services have exactly one.
  - The rest diverge widely: 35% of non-dominant graphs share less than half their calls with the dominant one, and 15% share none.
  - The authors suggest clustering "tolerant to structural variability" rather than exact matching.
  - The lesson for Lantern: exact shape equality finds true duplicates. Near duplicates need a similarity measure, and the paper does not say they should be merged.
- **IRIS (LLM over CodeQL paths)** gives the model a candidate path with code around the source and the sink. Intermediate steps are given as file and line only. <https://arxiv.org/html/2405.17238v3>
  - A path longer than S = 10 steps is cut into 10 segments and one step is kept from each, preferring function calls.
  - The model writes its explanation before its verdict. A refutation names whether the source or the sink is the cause, so other paths that share it are pruned.
  - This bears on how a 40-step flow is shown to the flow-significance role.
- **SARIF thread flows** carry per-step attributes. <https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html> (§3.36–3.38)
  - `executionOrder` is separate from the step's position in the array.
  - `importance` is `essential`, `important` or `unimportant` ("not normally needed").
  - `nestingLevel` is "typically function call depth".
  - `kinds` name what a step is: `call`, `return`, `branch` and so on.
- **Structurizr's dynamic views** show "instances of relationships that are defined in the static model". They can give explicit ordering with `[order:]` and override a relationship's description for one scenario. <https://docs.structurizr.com/dsl/language>

**4. Findings with evidence and stable identity**

- **A SARIF result** has a `ruleId` that "has to be the same across analysis". It holds:
  - `locations`
  - `relatedLocations` ("relevant to understanding the result")
  - `codeFlows` for execution paths
  - `fingerprints` and `partialFingerprints`, each "contributes to the stable, unique identity"
  - `guid` and `correlationGuid` (the latter "shared by all results that are considered logically identical")
  - `baselineState`: `new`, `unchanged`, `updated` or `absent`

  <https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html> (§3.27)
- **GitHub code scanning** fingerprints a result by the rule, the file path and a hash of the line. It says that if the path changes, "a new alert will be created" and "the old one will be closed". <https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning>
- **jQAssistant** identifies result rows across runs by declared `keyColumns`; by default every column is a key. A baseline file suppresses known rows, and an entry is removed when its row disappears. <https://jqassistant.github.io/jqassistant/current/>

Identity by the right key columns is the common answer. Lantern's `finding:<kind>/<subject>` has one column, the subject. That is too few for kinds such as version skew, whose subject is a library but whose meaning is a pair of versions across units.

**5. Deployment model**

- **Structurizr nests deployment elements:** `deploymentEnvironment`, then nested `deploymentNode`s with `technology`, `instances` (a number or a range such as `1..3`) and `properties`, then `containerInstance`s.
  - `infrastructureNode` covers load balancers, DNS and firewalls.
  - Relationships are not written per instance: "all of the relationships between these elements are automatically replicated between all instances". `deploymentGroup`s limit that replication.
  - One container may have instances on several nodes in one environment.

  <https://docs.structurizr.com/dsl/language>
- **The C4 deployment diagram** covers "one or more software systems within a single deployment environment". Its nodes nest, and infrastructure nodes are supporting elements. <https://c4model.com/diagrams/deployment>

**6. Leads and their confirmation**

- **SARIF `kind`** separates `fail` (a problem) from `review` ("requires review by a human user to decide if it represents a problem") and `open` ("insufficient information to decide"). <https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html> (§3.27.9)
- **SARIF suppressions** record a decision about a result without deleting it. Each has a `status` (`accepted`, `underReview`, `rejected`), a `kind` (`inSource`, `external`) and a `justification`. <https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html> (§3.35)
- **A GitHub dismissal** carries a reason and an optional comment, applies on every branch, and holds in later runs: "the same code won't generate an alert". Copilot Autofix's validation is "best-effort". <https://docs.github.com/en/code-security/code-scanning/managing-code-scanning-alerts/resolving-code-scanning-alerts>
- **Semgrep's AI triage** never closes a finding itself: filtered findings are "hidden … and grouped in the platform for bulk triage". Its reusable triage notes ("memories") are scoped by project and rule. It reports 92 to 96% agreement with people. These figures are the vendor's own. <https://semgrep.dev/blog/2025/announcing-ai-noise-filtering-and-triage-memories>
- **IRIS** records a verdict with its explanation, and prunes paths that share a refuted cause (above).

## Open points and options

**A. Flow-step identity**

- *Position (`flow-step:<flow>/<n>`), the epic's plan.* It is simple. But one step inserted early renumbers every later step. LLM wordings that cite a step then point at the wrong step, and every later step becomes a new claim.
- *The link it follows plus an occurrence count (`flow-step:<flow>/<link claim>/<k>`), with `order` as a descriptive attribute.* An inserted step leaves the others' IDs alone, and a reorder is a new version of an observation. This is SARIF's split of identity from `executionOrder`, and the epic's own rule that identity is what is claimed, not where it was seen. It costs a longer key.
  - The design keeps only steps that cross a container (design.md, Sequence diagrams), so nearly every step has a link claim. A reply keys on its request's link with kind `reply`.

**B. Lineage of an aggregate (a flow of 30 steps)**

- *Every leaf claim in the flow's lineage.* The lists get large and repeat the steps' own lineage.
- *One level, Soufflé and PROV style.* Each step's lineage is its link claim and the call edges between steps. The flow's lineage is its entry-point claim and its step claim IDs. A deep explanation follows lineage downward.
- Either way, the flow's ID does not change when its steps change. An LLM judgement keyed on the flow ID alone would then stay attached to a flow that has changed. The flow needs a **content hash**: the ordered link claim IDs of its steps, as a descriptive attribute. Judgements record the hash they judged. This is the "hash of the facts a section draws from" rule that design.md already uses for prose.

**C. Same shape**

- *Ordered lifelines (the epic).* Two flows with the same participants but different endpoints or messages count as one shape. That may hide flows a reader needs.
- *Ordered (from, to, kind, channel) steps.* Stricter: true duplicates only.
- *Similarity (Jaccard over step sets).* Matches the Alibaba evidence. Storing it as links between every pair would grow as n²; storing it as a ranking feature does not.

**D. Finding identity across commits**

- *Kind plus subject (the epic).* It collides for findings about pairs, and moves when the subject is renamed.
- *Per-kind key attributes,* like jQAssistant's key columns and SARIF's partial fingerprints. This reuses Lantern's `keyAttributes` mechanism.
- Whether a finding is new, unchanged, updated or gone (SARIF's `baselineState`) then follows from validity ranges at no extra cost.

**E. Leads**

- *A lead as a stored claim with origin `derive`, plus a separate verdict claim of origin `llm` with lineage to the lead.* This is SARIF's `review` result plus a suppression record. It is auditable, and a refuted lead stays visible.
- *A lead as a transient query result.* Cheaper, but nothing remains to say why a risk is absent, and the "not asked again" rule has nothing to hang on.

**F. Deployment instance key**

- *`deployment-instance:<env>/<container>`.* It collides when one container runs on two nodes of one environment, and cannot hold nesting or replicas.
- *Add a node path:* `deployment-node:<env>/<path>` claims, and instances keyed `deployment-instance:<env>/<node path>/<container>`.

**G. Stored or on demand**

- Glean shows both kinds of derived fact are legitimate.
- Lines aggregated between groups, and lines between deployment instances (Structurizr replicates them from the container links), can be computed when a view needs them, since both follow fully from stored claims. Storing them as well makes a second store of the same facts.

## Recommendation

1. **Keep a Java rule framework that recomputes a whole rule's output; do not invalidate by lineage.** Lineage cannot predict new derivations: Glean admits a gap on its stacked databases, and Soufflé cannot explain absent tuples. Stable claim IDs keep the diff small. (Glean incrementality; Soufflé; CodeQL overlay.)
2. **Lineage is one level.** Each derived observation lists only its direct input claims. The observation's origin names the rule and its version, as PROV's activity and plan do. An aggregate lists its parts, not their leaves. (Soufflé; PROV primer.)
3. **Key flow steps on the link they follow:** `flow-step:<flow>/<link claim>/<k>`, where k counts repeats of that link in the flow. Hold `order`, `nestingLevel` and `importance` as descriptive attributes:
   - `essential` for steps that cross a system, the entry and async hand-offs
   - `important` for the other steps between containers
   - `unimportant` for reads repeated in a loop

   (SARIF §3.38; Structurizr dynamic views.)
4. **Give every flow a `content` hash** of its ordered step link IDs and its `truncated` flag. Flow-significance and narrative claims record the hash they judged, and are stale when it differs. (design.md cache keys; Glean AND semantics.)
5. **Define shape as start kind plus ordered (from, to, kind) steps,** not lifelines alone. Link exact matches with `SAME_SHAPE_AS` as planned. Store `similarity-to-representative` (Jaccard over step sets) as a feature for the significance role, not as links. (Alibaba study.)
6. **When a flow goes to the LLM,** show every `essential` step in full. Sample the rest IRIS-style, at most 10 steps, preferring cross-system ones. The stored flow stays complete. (IRIS §3.5, A.3.)
7. **Findings declare their key attributes per kind**, as Lantern types already do. The finding ID is the claim ID. A finding holds:
   - lineage to the claims behind it, like `relatedLocations`
   - a `path` (ordered claim IDs) when its trigger is a path, such as send-before-commit, like `codeFlows`
   - a `ruleId`, the derive rule's name, that stays fixed between versions

   Whether a finding is new or gone is computed from validity, never stored. (SARIF §3.27; GitHub fingerprints; jQAssistant key columns.)
8. **Leads are stored claims** (`lead:<kind>/<key>`, origin `derive`, kind `review`), each with its evidence and a content hash of what it points at.
   - A verdict is a separate `llm` claim with lineage to the lead: `confirmed`, `refuted` or `unclear`, with a justification and citations.
   - A confirmed lead produces a `finding` with lineage to both.
   - A lead is asked again only when its content hash changes, not merely when its claim reappears.
   - Refuted leads are hidden from the reader's risk list but listed in the quality report, as Semgrep hides findings rather than closing them.
   - A later human decision (Phase 3) is a suppression-like claim with `status` and `justification`.

   (SARIF `kind` and suppressions; GitHub dismissal; Semgrep; IRIS.)
9. **A refutation may name the claim that refutes it,** such as a filter that strips a header. Other leads of the same unit whose lineage includes that claim can then be shown that refutation as context in the same packet. (IRIS pruning on a shared source or sink.)
10. **Deployment gets nodes:**
    - `deployment-node:<env>/<path>` claims, nested with `PART_OF`, holding `technology`, `replicas` and `properties`
    - instances keyed `deployment-instance:<env>/<node path>/<container>`
    - `infrastructure-node` claims for ingresses and load balancers

    Lines between instances are computed when a view needs them, from the environment view's links, not stored. (Structurizr DSL; C4 deployment diagram.)
11. **Components:**
    - Key a component on its anchor *type* (`component:<unit>/<anchor class>`), not on entry-point methods. Adding an endpoint to a controller must not rename it.
    - Store membership as one `CONTAINS` claim per class.
    - Key a shared component on its representative class: the shared class reached from the most entry points, ties by name. The key then comes from a class the code names, not from the set of members. Spring Modulith keys modules on a package for the same reason.
    - Warn, as jQAssistant does for an empty concept, when a unit with entry points gets no components.

    (C4 component; Structurizr strategies; Spring Modulith.)
12. **Compute lines between groups and between instances when a view needs them.** Store only what needs judgement or is expensive. (Glean on-demand predicates.)

## What it changes in docs/design.md and the epic

The agent's proposed changes, as written; "Decisions taken" below supersedes this list where they differ.

- **Epic §9, What is derived:**
  - Flow step row: replace `flow-step:<flow>/<n>` with `flow-step:<flow>/<link claim>/<k>`, and add `nestingLevel` and `importance`.
  - Flow row: add `content` (hash of its ordered step links) and `similarity-to-representative`, and define `shape` as start kind plus ordered (from, to, kind) steps.
  - Component row: anchor is the anchor type; shared components are keyed on their representative class.
  - Finding row: "`finding:<kind>/<key attributes>`; each kind declares its key attributes; carries `ruleId` and, when path-triggered, `path`".
  - Deployment row: add `deployment-node` and `infrastructure-node`, and change the instance key to include the node path.
- **Epic §9, Rules for flows change:** add "LLM judgements of a flow record its `content` hash and are stale when it changes."
- **Epic §9, Grouping, Lines:** change "one `INTERACTS_WITH` claim per ordered pair" to "computed when a view needs it from member lines". Or keep it stored and say why.
- **Epic §6, Derived and LLM facts:** add "Lineage is one level: the direct inputs of the rule that wrote it; aggregates list their parts, not their leaves."
- **Epic §11.2, Mode A:**
  - "A lead is a stored claim of origin `derive` with a content hash; a verdict is an `llm` claim with lineage to the lead; a lead is asked again only when its hash changes; refuted leads are listed in the quality report, never deleted."
  - Add the `refuted-by` claim of recommendation 9.
- **design.md, Versioning and incremental computation, Cache keys:** add rows "Derived model: rule name and version plus the predicates it reads; recomputed whole" and "LLM judgements of a flow or lead: its content hash plus prompt version plus model".
- **design.md, Composition, Diagrams table, Deployment row:** "Nodes (nested), infrastructure nodes and container instances in one environment; lines between instances follow the environment's links".

## Decisions taken (#615)

The chat judged the recommendations and took these into the epic and `docs/design.md` in the same
PR. The agent decided them under the owner's standing go-ahead for 1D.

- **Taken now:** whole-rule recomputation (1); one-level lineage (2); flow steps keyed on the link
  they follow (3, with `order` descriptive); a flow `content` hash that judgements record (4);
  shape as start kind plus ordered (from, to, kind, channel) steps, the channel being the endpoint or destination the step's link follows, so two different endpoints between the same units are two shapes (5, option C's stricter form); findings keyed per kind with a fixed
  rule name, lineage and a `path` when a path triggers them (7); leads stored as `lead:<kind>/<key>` with per-kind key attributes, verdicts as separate
  claims (8); components keyed on their anchor class, a class several entry points reach being a component of its own keyed on that class, membership one `CONTAINS` claim per class (11; the warning for a unit with entry points and no components is left to #523's checks); lines between groups computed on demand
  (12).
- **Taken with a narrower form:** deployment instances are keyed on the name the environment gives
  them (a compose service, a Kubernetes workload) rather than on a node path: today's documents draw
  deployment as a table, and nodes (10) come when it is drawn as a diagram.
- **Left to the roles that use them (1F):** step `importance` and `nestingLevel` (3), the
  similarity feature (5), sampling long flows for the model (6) and `refuted-by` context (9). They
  shape what a model is shown, not what is stored.
- **Scope of 1D's issues is unchanged:** #523 stores the model with these keys, #524 the leads as
  stored claims (the verdicts are Investigate's, 1F), #271 and #535 as written.

## Sources read (in full, or in substantial part where marked)

- <https://glean.software/docs/derived/>: stored and on-demand derived predicates, and the derivation order.
- <https://glean.software/docs/implementation/incrementality/>: AND ownership of derived facts, and the stacked-database gap.
- <https://souffle-lang.github.io/provenance>: one-level provenance annotations and lazy proof trees.
- <https://www.w3.org/TR/prov-primer/>: activity, used and generated, plans and revisions.
- <https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/scan-from-the-command-line/incremental-analysis>: CodeQL overlay reuse.
- <https://jqassistant.github.io/jqassistant/current/> (first half): concepts, key columns, baselines.
- <https://c4model.com/abstractions/component>: what a component is, and the PetClinic grouping.
- <https://raw.githubusercontent.com/structurizr/java/master/structurizr-dsl/src/test/resources/dsl/spring-petclinic/workspace.dsl>: the component finder's strategies in use.
- <https://docs.spring.io/spring-modulith/reference/fundamentals.html>: modules from packages, API and internal types, named interfaces.
- <https://arxiv.org/pdf/2205.08527>: component detection from stereotypes and matching HTTP calls between services.
- <https://arxiv.org/html/2504.13141v3> (about 70%): dominant and divergent call graphs, and comparison by sets of calls.
- <https://arxiv.org/html/2405.17238v3> (about 90%): how an LLM checks a path, sampling long paths, verdicts first explained.
- <https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html> (§3.19–3.39): result kind, fingerprints, baselineState, suppressions, thread flows.
- <https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning>: fingerprints across runs, and a new alert when a path changes.
- <https://docs.github.com/en/code-security/code-scanning/managing-code-scanning-alerts/resolving-code-scanning-alerts>: dismissals and best-effort Autofix.
- <https://docs.structurizr.com/dsl/language>: deployment elements, replicated relationships, dynamic views.
- <https://c4model.com/diagrams/deployment>: deployment diagram scope and nodes.
- <https://semgrep.dev/blog/2025/announcing-ai-noise-filtering-and-triage-memories>: AI triage hides findings rather than closing them; scoped memories (vendor figures).

## Not read

- <https://docs.structurizr.com/java/component>: only the introduction is public. The strategy reference (supporting types, duplicate handling) is behind early access.
- <https://deepwiki.com/structurizr/java/4.1-component-finder-strategies>: HTTP 429.
- AppMap sequence diagram documentation: the page returned 404. Blog posts covered only notation.
- <https://arxiv.org/abs/2203.16684> (DBSP): abstract only.
- SARIF Appendix B (how a result management system matches results): not in the chunks read.
- The ICS 2025 and TraceMesh trace-clustering papers: found in search only.
