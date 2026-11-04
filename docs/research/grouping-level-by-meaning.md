# Choosing a view's grouping level by meaning

Depth: **high**. This is an open question that sets a rule of the quality bar and the design. It covers how to choose a level, what size target to use and how to measure fit. That needs authorities, tools and papers compared on their trade-offs.

## The question

Lantern can draw a container or component view at several grouping levels that the code proves. How should it pick one level per view by meaning, with size only a soft signal? And what size is a defensible target?

## What the sources say

### Authorities: C4 and Structurizr

- **There is no official number.** The C4 site gives no element cap for container or component diagrams ([container](https://c4model.com/diagrams/container), [component](https://c4model.com/diagrams/component), [microservices](https://c4model.com/abstractions/microservices)). Its review checklist does not mention size either ([checklist](https://c4model.com/diagrams/checklist)).
- **Simon Brown's own number is soft.** "Any diagram with 20+ elements (perhaps fewer) starts to get complicated quickly." He adds: "A complicated architecture will lead to a complicated diagram." ([Brown, DEV](https://dev.to/simonbrown/diagramming-distributed-architectures-with-the-c4-model-51cm))
- **The C4 fix for a big diagram is to split it, not to enlarge it.**
  - The FAQ says large diagrams are "hard to interpret and comprehend because the cognitive load is too high". It advises: "split that single complex diagram into a larger number of simpler diagrams". Each split has a focus, such as a business area or a functional grouping. Each "tells a different part of the same overall story, at the same level of abstraction" ([FAQ](https://c4model.com/faq)).
  - Brown lists the ways to scope a split view: "a single domain, a bounded context, a business capability, a feature", or "a single service plus its direct afferent/efferent couplings". He states the cost plainly: "We do lose some of the 'big picture'." ([Brown, DEV](https://dev.to/simonbrown/diagramming-distributed-architectures-with-the-c4-model-51cm))
- **Component diagrams are optional in C4.** The component page says "only create component diagrams if you feel they add value". It also says to "consider automating their creation for long-lived documentation" ([component](https://c4model.com/diagrams/component)).
- **Structurizr groups are boundaries, not merged boxes.**
  - "Groups can only be used to group elements of the same type (i.e. the same level of abstraction)." Groups can be nested.
  - Views are narrowed with `include`/`exclude`, and filtered views are made by tag ([Structurizr DSL](https://docs.structurizr.com/dsl/language)).
- **Structurizr shows each line once by default.** When elements are rolled up into their parents, the DSL creates "implied relationships" between the parents. Its default strategy skips a pair where "any relationship already exists between them" ([implied relationships](https://docs.structurizr.com/dsl/implied-relationships)).
- **IcePanel also gives no number.**
  - Its advice is to break complex diagrams "into multiple diagrams that exist in parallel", each with "a specific message".
  - It also says to "use the right level of complexity at each level (Level 1 should have fewer objects than Level 2)" ([IcePanel docs](https://docs.icepanel.io/core-features/diagramming)).

### Tools that derive structure

- **Spring Modulith** writes one C4 component diagram of all modules. It also writes one diagram per module, holding "the individual module and the ones they directly depend on" ([Spring Modulith docs](https://docs.spring.io/spring-modulith/reference/documentation.html)). The pattern is an overview plus one view per group with its neighbours.
- **Bunch** clusters a dependency graph into a hierarchy. Its levels are bounded by O(log₂ N). Its authors present "the median clustering level" as "a good tradeoff between cluster size and number of clusters". Runs from random starts "converged to a consistent 'family' of solutions" ([Bunch slides](https://slidetodoc.com/the-search-landscape-of-graph-partitioning-problems-using/)).
- **ACDC** builds clusters from recognisable patterns, such as directory structure, a subgraph dominator or a support library. It names every cluster and bounds cluster size with a threshold of 20 ([ACDC wiki](https://wiki.eecs.yorku.ca/project/cluster/protected:acdc)).
- **Automatic clustering is not accurate enough to stand alone** ([Lutellier et al., TSE 2017](https://www.cs.purdue.edu/homes/lintan/publications/archrec-tse17.pdf)).
  - Across nine recovery techniques, "more accurate recovery techniques are needed".
  - The best technique changed with the input.
  - "There is not necessarily a unique, correct architecture."
  - Developers saw their system as nested, while the tools recover only flat groupings.
  - Lesson: build groups from structure the code proves, not from a clustering score alone.
- **Leiden and Louvain community detection.**
  - Louvain can produce badly connected or even disconnected communities: up to 25% and 16% in tests. Leiden guarantees connected ones ([Traag et al.](https://arxiv.org/abs/1810.08473)).
  - Any "natural" resolution "is often quite arbitrary". A resolution profile returns only the partitions that are best over a range of resolutions, so wide ranges mark stable levels ([leidenalg docs](https://leidenalg.readthedocs.io/en/stable/advanced.html)).
- **Structure101** judges complexity separately at each level of the hierarchy. A level is "Fat" when its child items are tied by too many dependencies (default 120 for a package). The docs say "there is no industry norm for this value". Its fix is to subdivide into "cohesive clusters" ([Fat](https://www.sonarsource.com/structure101/docs/java/studio/content/restructure101/fat), [measuring complexity](https://www.sonarsource.com/structure101/docs/java/studio/content/xs/measuring-complexity)).

### Studies of reading diagrams

- **Moody's "Physics of Notations"**, section 4.4 ([transcript](https://speakerdeck.com/rhazkoomar/take-and-grant-model)).
  - Diagram complexity is the number of elements.
  - Modularising diagrams improved understanding and verification "by more than 50 percent".
  - Hierarchy comes from top-down splitting, or from bottom-up grouping, where higher levels "need to be explicitly created by a process of summarization".
  - The paper's only number is the data-flow-diagram convention of "7 ± 2 bubbles per diagram". It is not a measured limit for architecture diagrams.
- **The "7 ± 2" rule is not a box cap** ([Cowan 2001](https://memory.psych.missouri.edu/assets/doc/articles/2001/cowan-bbs-2001.pdf)).
  - Miller's seven was "a rough estimate and a rhetorical device"; the real limit is about 3 to 5 chunks.
  - Miller's central point was that grouping ("chunking") raises how much people can hold.
  - So working memory does not cap the box count of a diagram the reader can look back at. What it supports is meaningful groups, because a group the reader understands acts as one chunk.
- **Störrle 2016 is the best direct evidence on diagram size** ([PDF](https://backend.orbit.dtu.dk/ws/files/132539081/a31_storrle.pdf)). The study had 156 participants and 60 UML diagrams of five types.
  - **Size measure:** size counts every shape, line and movable label.
  - **Larger diagrams were harder:** they correlated with lower understanding (r ≈ −0.27).
  - **Layout flaws hurt more:** flaws such as crossings, bends, overlaps and touching elements correlated more strongly (r ≈ −0.42).
  - **Recommended limits:**
    - about 44 to 65 diagram elements, centred near 50;
    - no more than 15 to 20 flaws;
    - a flaw rate below 0.5 per element;
    - "Improving diagrams should prioritize reducing the number of flaws over reducing the number of elements."
- **Crossings matter most for layout** ([Purchase 1997 abstract](https://eprints.gla.ac.uk/35804)).
  - Reducing edge crossings "is by far the most important aesthetic".
  - For UML diagrams the order of priority was "arc crossings, orthogonality, information flow, arc bends…". Abstract-graph results did not carry over directly to diagrams with meaning ([Purchase et al. 2002](https://jgaa.info/index.php/jgaa/article/download/paper54/2911)).
  - Crossings at sharp angles slow readers more than crossings near 90 degrees. On larger graphs, the effect of crossings builds up ([Huang 2008](https://arxiv.org/pdf/0810.4431)).
- **Graph size limits are far above a good architecture view.**
  - Readers struggled with dense node-link diagrams above about 50 nodes, and sparse ones above about 100. Global features such as size and crossings affected difficulty more than local ones ([Yoghourdjian et al. 2020](https://arxiv.org/abs/2008.07944)).
  - The survey found experimental graphs had a median of about 50 nodes and 73 edges. One study saw overload "even with 25 nodes and 98 links". Size alone is "an important, but incomplete indicator", and thresholds in the field are mostly intuition ([Yoghourdjian et al. 2018](https://arxiv.org/html/1809.00270v1)).
  - These are task limits for path-finding, not targets for a diagram meant to be read for meaning.
- **Stability helps readers only a little.**
  - Keeping the layout stable as data changes gave no significant gain in speed or errors.
  - Users still felt it made the diagram easier to remember ([Archambault & Purchase 2012](https://eprints.gla.ac.uk/66915)).
  - So stability is worth having, but it cannot outweigh a clearly better level.

## Options and trade-offs

### How levels are proposed

| Option | Gains | Costs |
| --- | --- | --- |
| **A. Code-proven levels only** (current plan: packages, feature areas, domains, merged small groups) | Every group has a name and a reason a reader can check. This is what ACDC does with its named patterns. | A few fixed levels; there may be no level between two of them. |
| **B. Graph clustering** (Bunch, Leiden with a resolution profile) | Any level of detail; stable ranges show natural levels. | Groups without names or meaning. Accuracy is low (Lutellier). Louvain can split communities. |
| **C. A plus B as a check** | A cohesion score per proposed group points out groups that mean little. | One more computation. |

### How one level is chosen

| Option | Gains | Costs |
| --- | --- | --- |
| **Fixed cap** (rejected by the owner) | Simple and predictable. | Ignores meaning. No source supports a hard cap. |
| **Fixed rule** (Bunch's "median level") | Deterministic. | Blind to what the view is for. |
| **Score function** (fewer boxes, more cohesion, fewer lines; like Bunch's modularization quality) | Deterministic and explainable. | Weights are arbitrary, and the view's question is hard to encode. |
| **LLM judgement among derived levels only** | Can weigh what the view is for and whether group names mean something. | Can vary between runs, costs money, and is hard to test. It needs a rubric, a cache and fixed tie-breaks. |
| **Filter, then judge, then layout gate** (recommended) | Rules handle what can be measured; the judgement handles only meaning. | Three steps to build and explain. |

### The soft target

| Option | Gains | Costs |
| --- | --- | --- |
| **7 ± 2** | Well known. | It misreads Miller, and Cowan's chunking argument cuts against it. It is a data-flow-diagram convention, not a measured limit. |
| **About 20 boxes** (Brown) | Comes from the C4 author. | Rough ("perhaps fewer"). |
| **About 50 diagram elements, boxes, lines and labels together** (Störrle) | Measured, and counts lines too. | Comes from UML diagrams read by students. |
| **Graph-study limits** (50 to 100 nodes) | Measured. | These are where readers fail, not a target. |

### The after-layout gate

| Option | Gains | Costs |
| --- | --- | --- |
| **Crossings only** | Matches Purchase's strongest result. | Misses overlaps and bends. |
| **Störrle flaw count and rate** (crossings, bends, overlaps, touching) | The best-validated measure, and it adjusts for size. | Each kind of flaw must be measured. |
| **Fits a page without zooming** (smallest box text still readable at page width) | Matches how the report is read. | Depends on the medium, so the page size must be fixed. |

## Recommendation

**1. Propose (option A, checked by C).** Only code-proven levels are candidates. A level is admissible only if both hold:
- every group has a name from the code, such as a package, feature or domain;
- the view still answers its question. A container view still shows each deployable unit as its own box. A component view still shows every part that has a line leaving its container.

A group whose internal ties are weaker than its outside ties is flagged as low meaning. This is ACDC's "named pattern" rule and Lutellier's warning about clustering applied together.

**2. Choose.** Use the **fewest boxes that still answer the view's question, with groups that mean something**:
- Order the admissible levels from coarse to fine. A judgement picks one. It may be an LLM given a fixed rubric and only the derived levels; it never adds or moves members.
- The judgement weighs, in this order:
  1. whether the view's question can still be answered;
  2. whether each group's name and members mean something (cohesion, the flag above);
  3. box count against the soft target;
  4. distinct lines, counting each line between two boxes once, as Structurizr's implied relationships do;
  5. expected crossings.
- **Ties go to the coarser level.**
- **Stability:**
  - Store the choice under a key made from its inputs: the candidate levels, their counts, the view's question and the rubric version. The same inputs give the same choice without asking the judgement again.
  - When inputs change, keep the previous level unless it is no longer admissible, or another level is clearly better on criterion 1 or 2.
  - Archambault & Purchase show stability helps memory a little, but not enough to keep a worse level (abstract only, so low weight).

**3. Soft size targets.** These are warnings in the quality report, never failures.
- **Overview (container or system view): about 10 to 20 boxes, warn above 25. Distinct lines: warn above about 30.** Brown says 20+ elements "starts to get complicated". Störrle's best size is about 50 elements counting lines and labels, and 20 boxes with about 25 labelled lines comes to roughly that. Warn below 5 boxes when a finer admissible level exists: a view that coarse says little (Bunch's trade-off between cluster size and cluster count, from a secondary source, so a judgement). 30 lines rounds up the 25 of that estimate, so 25 boxes and 30 lines is a warning, not the best size.
- **Component view: about 8 to 20 boxes, warn above 25.** Same evidence for the upper side; the lower bound of 8 is a judgement no source gives. C4 treats component views as optional, and Spring Modulith draws one per module with its neighbours, so the cure for a large one is a view per group, not a bigger view.
- Do not use 7 ± 2 as a target (Cowan, Moody).

**4. After-layout check (final word).** Measure on the laid-out diagram. Crossings and flaws are warnings; only fit moves a view:
- **Crossings:** target 0, warn above about 5. Crossings are Purchase's strongest aesthetic, and Huang shows sharp-angled ones cost most.
- **Layout flaws:** total flaws (crossings, overlaps, touching, bends) at most 15 to 20, and fewer than 0.5 per element (Störrle). Not adopted yet: Lantern measures crossings and fit first (#366); the other flaws need their own measures, and can be added as warnings later.
- **Fit:** the smallest box text is readable at page width without zooming.

If the view does not fit, step to the next coarser admissible level. If the coarsest level still fails, apply these in order:
1. Show each line once between two boxes, with a combined label.
2. Fold detail, keeping groups as boundaries with the parts inside them listed rather than drawn.
3. **Split by question.** Keep the coarse overview, and add one view per group that shows the group's parts plus their direct neighbours (Brown, the C4 FAQ, Spring Modulith). Record the loss of "the big picture" in the quality report.

A group drawn as a single box is a change of abstraction level. It must say so (a group marker and a count) and link to its own view, because C4 asks each view to stay at one level of abstraction.

## Open issues it bears on

- **Grouping by coupling (#537).** Leiden with a resolution profile is option B. It gives groups without names, so it is not a source of levels. It can serve as option C's check: a cohesion score that flags a proposed group whose members are tied more to the outside than to each other.
- **Feature areas by nested resource (#634).** A deeper resource path, such as `work-orders/warranty-claims`, is a code-proven, named grouping, so it fits option A as one more candidate level between "feature areas" and "none". It adds a level; it does not change how a level is chosen.

## What it changes in the quality bar

The size rule becomes a soft target with a warning:
- overview about 10 to 20 boxes, warn above 25;
- component view about 8 to 20 boxes, warn above 25;
- each line between two boxes drawn once.

Layout is measured after drawing: crossings warn above about 5, and a view whose smallest text is not readable at page width moves to a coarser level. Störrle's flaw limits are not adopted yet. Each rule links to this note.

## What it changes in the design

The composer proposes only code-proven, named levels. One judgement step, limited to those levels and run by a rubric, picks one per view and is cached by its inputs. The after-layout gate can move a view to a coarser level, or split it into an overview plus one view per group. No fixed box cap is applied anywhere.

## Sources

- C4 FAQ, https://c4model.com/faq: **read**
- Simon Brown, "Diagramming distributed architectures with the C4 model", https://dev.to/simonbrown/diagramming-distributed-architectures-with-the-c4-model-51cm: **read**
- C4 microservices, https://c4model.com/abstractions/microservices: **read**
- C4 component diagram, https://c4model.com/diagrams/component: **read**
- C4 container diagram, https://c4model.com/diagrams/container: **read**
- C4 review checklist, https://c4model.com/diagrams/checklist: **read** (short page)
- Structurizr DSL language reference, https://docs.structurizr.com/dsl/language: **read**
- Structurizr implied relationships, https://docs.structurizr.com/dsl/implied-relationships: **read**
- IcePanel diagramming docs, https://docs.icepanel.io/core-features/diagramming: **read**
- Spring Modulith documentation, https://docs.spring.io/spring-modulith/reference/documentation.html: **read**
- Moody 2009, "The Physics of Notations", section 4.4 (transcript), https://speakerdeck.com/rhazkoomar/take-and-grant-model: **read in part** (section 4.4 and the first 100k characters, not the original IEEE PDF)
- Cowan 2001, "The magical number 4", https://memory.psych.missouri.edu/assets/doc/articles/2001/cowan-bbs-2001.pdf: **read in part** (abstract and introduction)
- Störrle 2016, "Diagram Size vs. Layout Flaws", https://backend.orbit.dtu.dk/ws/files/132539081/a31_storrle.pdf: **read** (pages 1 to 10: method, results, guidelines)
- Purchase 1997, "Which aesthetic has the greatest effect on human understanding?", https://eprints.gla.ac.uk/35804: **abstract only** (full text not available)
- Purchase, Allder, Carrington 2002, "Graph Layout Aesthetics in UML Diagrams", https://jgaa.info/index.php/jgaa/article/download/paper54/2911: **read in part** (abstract, introduction, conclusions, discussion)
- Huang 2008, "An Eye Tracking Study into the Effects of Graph Layout", https://arxiv.org/pdf/0810.4431: **read** (pages 1 to 6)
- Yoghourdjian et al. 2018, "Exploring the Limits of Complexity" (survey), https://arxiv.org/html/1809.00270v1: **read**
- Yoghourdjian et al. 2020, "Scalability of Network Visualisation from a Cognitive Load Perspective", https://arxiv.org/abs/2008.07944: **abstract only**
- Archambault & Purchase 2012, mental map and memorability, https://eprints.gla.ac.uk/66915: **abstract only**
- Traag, Waltman, van Eck, "From Louvain to Leiden", https://arxiv.org/abs/1810.08473: **abstract only**
- leidenalg advanced docs (resolution profile), https://leidenalg.readthedocs.io/en/stable/advanced.html: **read**
- ACDC (Tzerpos), York wiki, https://wiki.eecs.yorku.ca/project/cluster/protected:acdc: **read**
- Bunch (Mitchell and Mancoridis) slides, https://slidetodoc.com/the-search-landscape-of-graph-partitioning-problems-using/: **read** (secondary copy of the Drexel slides)
- Lutellier et al., TSE 2017, https://www.cs.purdue.edu/homes/lintan/publications/archrec-tse17.pdf: **read in part** (abstract, introduction, threats, conclusions)
- Structure101 "Fat", https://www.sonarsource.com/structure101/docs/java/studio/content/restructure101/fat: **read**
- Structure101 "Measuring complexity", https://www.sonarsource.com/structure101/docs/java/studio/content/xs/measuring-complexity: **read**
- Garcia, Ivkovic, Medvidovic 2013, comparative analysis of recovery techniques, https://researchr.org/publication/GarciaIM13: **not read** (search snippet only)
- Holten 2006, hierarchical edge bundles: **not read**
- CodeScene architectural analyses docs: **not read** (site unreachable)
- Mitchell and Mancoridis 2006, Bunch in IEEE TSE (full paper): **not read** (fetch returned empty)
- Moody 2009, original IEEE PDF: **not read** (paywalled; the transcript above stands in)