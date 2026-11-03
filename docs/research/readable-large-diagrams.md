# Readable overviews of large container and component diagrams

## Question

How should an automatically generated container or component diagram of a large system stay readable without a fixed, arbitrary element cap, and still give the reader one overview of the whole?

## Depth and why

**High.** This changes a rule of the design: the cap of 15 elements and the order in which views are split. It also needs a way to measure readability. The question is open. No single authority settles it, so it needs empirical studies, C4 guidance and comparable tools. Pages read are listed at the end. Partial reads are marked.

## What the sources say

### 1. Readability measures and size limits

- **No validated size threshold exists.** A survey of 152 empirical graph-reading studies finds that the limits of node-link diagrams "are rarely addressed directly", and that "large" rests on tacit assumptions. https://ar5iv.labs.arxiv.org/html/1809.00270
  - It proposes size bands from the studies' own clustering: small ≤20 nodes, medium 21–50, large 51–200, very large >200.
  - It proposes density bands by edges per node: sparse 1–2, dense 2–4.
  - It reports a threshold of about 200 nodes and 10% density for detailed tasks. The authors say this rests on researcher intuition and needs a controlled study.
- **Thresholds from individual studies, as cited by that survey:**
  - Kobourov et al.: about 120 nodes at density 2.5 was the most at which tasks finished in under two minutes.
  - Huang et al.: errors rose at 25 nodes and 98 links.
  - Okoe et al.: error rates passed 50% at 900 nodes and 2,500 edges.
  - The median study used about 50 nodes and 73 edges, so most evidence is about graphs of the size Lantern draws.
- **Shortest-path tasks, 25–175 nodes (Yoghourdjian et al., InfoVis 2020).** Readers had "significant difficulty" above 50 nodes in high-density diagrams and above 100 nodes in low-density ones. Global layout features, overall size and crossings, affected difficulty more than features of the path itself. Cognitive load rose with difficulty and then fell, as readers gave up. Abstract only read. https://arxiv.org/abs/2008.07944
- **Crossings.** Huang & Huang (2010) drew 200 random graphs of 10–50 nodes with a force-directed (spring) layout. https://opus.lib.uts.edu.au/bitstream/10453/16557/1/2010001394OK.pdf
  - Each extra crossing added about 0.21 s to a path task.
  - Crossing count explained more of response time, effort and efficiency than crossing angle (β 0.52 vs 0.10 for time).
  - Together they explained 36% of the variance in time and 43% in mental effort.
  - The paper also restates two earlier findings. Purchase found crossings "by far the most important" aesthetic, followed by bends and symmetry. Ware et al. found that crossings on the path being followed matter more than the total count.
- **Crossing angle.** Huang's eye-tracking study found that small-angle crossings slow eye movement and cause back-and-forth scanning, while crossings near 90° are largely ignored. https://arxiv.org/pdf/0810.4431
  - The same study found a "geometric-path tendency": readers follow edges that point toward the target. Long detours confuse them.
  - It also suggests that for small graphs it may be worth asking whether removing crossings matters; for large graphs, crossing count comes first.
- **Practical readability metrics** are normalised to [0,1] (1 is best). They include edge crossings, crossing angle, angular resolution, edge-length deviation, edge orthogonality, aspect ratio, stress, neighbourhood preservation, node resolution and node uniformity. https://drops.dagstuhl.de/storage/00lipics/lipics-vol357-gd2025/html/LIPIcs.GD.2025.30/LIPIcs.GD.2025.30.html
  - The 2025 version of these definitions covers bends and curved edges, which matters for ELK's orthogonal routing.
  - Drawings in published graph-drawing papers have a median of 11 vertices; the generated benchmark graphs have 48.
  - The paper sets no thresholds.
- **greadability.js** computes four of these metrics and is used to tune and compare layouts, not to accept or reject them. https://github.com/rpgove/greadability

**Takeaway.** There is no measured element count for architecture diagrams. The evidence ranks crossings first, then crossing angle and path continuity. Size matters mostly through density and the crossings it causes. Sparse diagrams stay usable well past 15 nodes; the failure zone starts around 50 nodes when dense and around 100 when sparse.

### 2. What C4 and its tools do for large diagrams

- **C4 FAQ.** Real systems have "600 components rather than 6". https://c4model.com/faq
  - Split complex diagrams by "business area, functional area, functional grouping, bounded context", or by use case. Each split tells "a different part of the same overall story, at the same level of abstraction".
  - For microservices, one diagram per service with its inbound and outbound dependencies is offered. The FAQ admits this loses the big picture and suggests "alternative, less verbose visualisations" to recover it.
  - No element limit is given.
- **Queues and topics.** Modelling the broker as one container is marked incorrect. Two notations are both correct: queues and topics as containers, or a "via" label on the relationship. https://c4model.com/abstractions/queues-and-topics
  - The "via" form applies where there is "genuinely … a point-to-point coupling via a queue".
  - "Neither version … is 'better'."
  - A dashed line or a colour can mark message-based relationships.
- **Microservices.** A microservice is "a combination of an API container … and a database schema container". https://c4model.com/abstractions/microservices
  - The pair can be linked by colour or by a box around it.
  - With separate teams owning services, each service is promoted to its own software system.
- **Notation.** Every diagram needs a title stating its type and scope, and a key explaining the notation. https://c4model.com/diagrams/notation
  - C4 says nothing about how to signal that elements were left out.
- **Structurizr.** It builds "implied relationships": a relationship between low-level elements is also shown between their parents. https://docs.structurizr.com/dsl/implied-relationships
  - This is the mechanism that lets a higher-level view summarise lower-level edges.
  - The strategy "unless any relationship exists" draws one summary line. (The tool's cookbook and a user discussion, seen only as search snippets, warn that this line's label then comes from just one underlying relationship.)
- **LikeC4.** Views are predicates over one model (`include`, `exclude`, `where tag/kind`). `X._` expands a parent to only the children that have relationships with what is already visible. https://likec4.dev/dsl/views/predicates/
  - Lines between hidden nested elements are drawn between their visible ancestors as derived relationships.
  - Views can extend a base view. https://likec4.dev/dsl/views/
- **Ilograph.** It splits a "master diagram" into perspectives, each with one focus (run time, deploy time, DNS, triggers). https://www.ilograph.com/blog/posts/breaking-up-the-master-diagram/
  - The overview is kept by lowering detail, for example showing services instead of individual functions and tables.
  - Splitting frees room for names and arrow labels.
- **IcePanel.** One model, many diagrams. Its hierarchy is Domain > System > App/Store > Component, with tags to hide or focus objects. https://docs.icepanel.io/features/modelling

### 3. Service maps of hundreds of services

- **Datadog.** https://docs.datadoghq.com/tracing/services/services_map/
  - Filtering by facets is "particularly useful … with hundreds or thousands of nodes".
  - It can group by team or application and scope the map to one environment.
  - Clicking a service "isolates the service", showing who calls it and what it calls, and lets the reader "pivot … one dependency at a time".
  - Services outside the filter collapse when filtered services connect only through them.
- **Kiali.** https://v2-24.kiali.io/docs/features/topology
  - Its graph types aggregate traffic by workload, app or service, so readers choose between "a high-level service topology" and "a low-level workload topology".
  - A double-click opens a per-node detail graph.
  - It ranks nodes (for example by inbound edges) and offers find and hide.
- **Netflix Vizceral.** It rolls traffic up into global, regional and service levels, with click-to-drill-down. https://github.com/Netflix/vizceral
- **Backstage catalog graph.** It filters by relation type and entity kind. https://github.com/backstage/backstage/blob/master/plugins/catalog-graph/README.md

**The common pattern:** aggregate to a coarser level from a label the system already has (environment, team, app label), and focus on one node's neighbours for detail. None of these tools invents groups.

### 4. Grouping without explicit signals

- **Recovery tools disagree with experts.** RELAX restates Garcia et al. (2013): of six recovery techniques, the two best were ACDC and ARC, and "even the best … has surprisingly low accuracy". https://arxiv.org/pdf/1903.06895
  - RELAX's own table of agreement with expert decompositions (MoJoFM) gives ACDC 36–88% and ARC 43–76% across eight systems, averaging 56% and 59%.
  - RELAX also notes that methods with "non-deterministic sampling" give inconsistent results across versions.
  - ARC's topics are "not … named automatically". ACDC names a cluster from the file names in it.
- **Louvain** can produce badly connected communities: up to 25% badly connected and 16% disconnected in the tests. Its result depends on node visit order. https://ar5iv.arxiv.org/html/1810.08473
- **Leiden** guarantees connected communities but uses random node order and a random refinement step, so results depend on the seed. Modularity also has a resolution limit; the CPM objective avoids it but needs a chosen resolution parameter.

**Verdict.** Automatic clustering is moderately accurate at best and unstable unless seeded. It produces groups without names. That breaks Lantern's rule that membership comes from explicit signals, and a reader cannot check why two services share a box. It is not suitable for membership in a document readers trust.

### 5. Folding detail in an overview

- C4 explicitly allows replacing point-to-point topics with "via" labels.
- C4 allows drawing a service's own schema as part of it, by colour or an enclosing box.
- Structurizr and LikeC4 roll edges up to visible ancestors.
- Ilograph and Kiali lower detail by aggregating to the parent.
- C4 requires a title stating scope and a key explaining the notation. Neither C4 nor the tools define a marker for "folded here", so Lantern must state it itself, in the key and on the folded element.

### 6. Measuring readability after layout

- The metrics in §1 are computable on ELK's output: crossings, crossing angles, bends, label overlap, edge-length spread, and aspect ratio or fit at a target width.
- Crossings on the path a reader follows matter most (Ware, via Huang), so crossings per edge is a better signal than a raw total.
- None of these sources gives pass or fail thresholds. They must be calibrated on Lantern's own diagrams against human judgment.

## Options

- **A. Status quo.** Cap at 15; split by concern, then one page per unit.
- **B. Fold, then measure; one overview.** Apply deterministic folding rules (below). Lay out with ELK. Accept the diagram if it passes the measured gates; otherwise go to the next fold step. Detail pages per unit hold the folded parts.
- **C. Automatic clustering.** Group units into domains by Leiden or Louvain on the call graph, named by the LLM.
- **D. Focus pages only.** No full overview: an index plus one neighbourhood page per unit (one hop), Datadog-style.
- **E. Explicit domains only.** Group by shared database, gateway prefix, repository or groupId; otherwise split as in A.

| | Readability | Faithful to code | Deterministic | Effort | Explicit signals only |
|---|---|---|---|---|---|
| A | Each page readable; no whole picture (17 pages in the hard case) | High | Yes | None | Yes |
| B | One overview within the size band studies show readable; detail one click away | High: nothing invented, folded items listed on the detail pages and in the key | Yes (fixed rules, ELK deterministic) | Medium | Yes |
| C | Possibly good | Low to medium; groups may not match the real architecture (MoJoFM ~56%) | Only with a fixed seed; unstable across versions | Medium | **No** |
| D | Each page good; no overview | High | Yes | Low | Yes |
| E | Good when signals exist; falls back to A otherwise | High | Yes | Planned | Yes |

## Recommendation

Adopt **B**, with E's domains as one of its fold steps, and replace the element cap with measured gates on the laid-out diagram.

**Fold steps, in a fixed order.** Stop at the first step whose layout passes the gates.

1. **Full view**, with parallel lines between the same two elements merged into one line. The merged label lists every underlying relationship, or gives a count ("3 calls: …"). This avoids Structurizr's one-label-wins problem.
2. **Topics and queues become "via" labels** where producer × consumer ≤ producer + consumer, that is, where the fold does not add lines. This covers every point-to-point topic. A topic with wide fan-out stays a node, because it is a real coupling point.
3. **Data stores used by only one unit are drawn inside or attached to that unit**, as C4's microservice pairing does: a store badge on the box. Shared stores stay as nodes, because they are coupling.
4. **Explicit domains, where signals exist.** Units collapse into their domain, and lines roll up to it as implied relationships.
5. **Only if still failing:** split by concern (requests, data, messaging). Every page keeps all units, so each page is still a whole-system overview.

Per-unit neighbourhood pages (one hop, as in Datadog and Kiali) always exist as detail pages. They are never the overview.

For the hard case, steps 2 and 3 remove up to 13 topic nodes and up to 6 store nodes. That leaves about 19–25 elements with merged lines. This is the survey's small-to-medium band, sparse, far below the densities where Yoghourdjian's readers failed. For a component diagram of 56 elements and 71 lines (1.3 lines per element, sparse), steps 1 and 4 apply. Here step 4 uses feature areas from explicit signals such as packages. Without those, the diagram falls to step 5's concern split.

**Gates that replace the 15 cap.** All are measured on ELK's output:

- **Fits without zooming:** at the report's target width, labels render at or above a minimum font size. This makes the bar's "without zooming" a measured check.
- **No overlap:** no label overlaps a label, node or line.
- **Crossings per line** is at or below a threshold.
- **No acute crossings:** the minimum crossing angle is at or above a threshold. ELK's orthogonal routing makes most crossings 90°, so this mainly guards against diagonal and spline routing.
- **Line length and bends:** total bends per line, and the longest line relative to the diagram width, stay at or below thresholds. This tracks "every line can be followed end to end".

**Calibration.** Compute these metrics for every container and component view on the development test sets, at every fold step. Have the judge or the user mark each view readable or not against the bar's three questions. Pick the thresholds that best separate the two, and record them and the data in an ADR. Until then, use provisional values, stated as provisional: crossings per line ≤ 0.2, minimum crossing angle ≥ 45°, and a minimum rendered font of about 11 px at the target width. Keep element and line counts as reported signals, never as gates.

**Do not use** automatic community detection for membership, nor LLM-named groups. At most, a seeded clustering may inform layout ordering, never what is drawn inside a box.

## What it changes in the quality bar or the design

- **Design:** remove "split any view above 15 elements". Add the fold steps above, the merged-line rule, the "via" rule with its fan-out test, the rule for stores used by one unit, the post-layout gates, and the rule that per-unit pages are detail pages, not the overview.
- **Design, notation:** every folded diagram's key states what was folded, for example "topics shown as 'via' labels" and "stores used by one service shown inside it". Each folded element links to the page showing it in full.
- **Quality bar:** "readable" gets measurable proxies (fits at target width, no overlap, crossings per line, minimum crossing angle), each linked to this note. The rule that no element count alone decides stays. The bar adds that a system has one overview page, and that what an overview omits is stated on it.
- **ADR:** the calibrated thresholds and the data behind them.

## Judgement (#699)

Adopted (ADR 0117):
- The order of fold steps.
- The rejection of automatic clustering.
- The "via" fold, narrowed to one sender and one receiver: the rule above would also fold one
  sender to many receivers, which is the fan-out it means to keep.

Not adopted:
- Folding a store used by one unit into that unit. The bar's Container row keeps each service's
  schema as its own data store.

The measured gates on the laid-out diagram wait for #366, since the layout runs only in the
report's browser code today. Until then, element and line counts are used as provisional gates,
not only as signals:
- at most 50 elements, the least size at which the studies above saw readers struggle with dense
  lines;
- at most 2 lines per element, the top of the sparse band.

This departs from the recommendation to keep counts as signals only. It is stated as provisional
in the ADR and the design, and #366 replaces it.

## Sources read

- https://ar5iv.labs.arxiv.org/html/1809.00270: Yoghourdjian et al., survey of 152 graph-reading studies; sizes, density bands, cited thresholds. First ~100k of 132k characters read.
- https://arxiv.org/abs/2008.07944: Yoghourdjian et al. 2020, cognitive load and graph size. Abstract page only.
- https://arxiv.org/pdf/0810.4431: Huang, eye-tracking study of crossings and crossing angles. Read in full.
- https://opus.lib.uts.edu.au/bitstream/10453/16557/1/2010001394OK.pdf: Huang & Huang 2010, crossing number vs crossing angle, regression results. Pages 1–6.
- https://c4model.com/faq: C4 advice on large diagrams and splitting.
- https://c4model.com/abstractions/queues-and-topics: explicit topics vs "via" notation.
- https://c4model.com/abstractions/microservices: a service as API plus schema, grouping.
- https://c4model.com/diagrams/notation: title, key and labelling rules.
- https://docs.structurizr.com/dsl/implied-relationships: rolling relationships up to parent elements.
- https://likec4.dev/dsl/views/: views as projections, extending views.
- https://likec4.dev/dsl/views/predicates/: include and exclude, expand, derived relationships.
- https://www.ilograph.com/blog/posts/breaking-up-the-master-diagram/: perspectives, overview by lower detail.
- https://docs.icepanel.io/features/modelling: model hierarchy, domains, tags.
- https://docs.datadoghq.com/tracing/services/services_map/: filtering, grouping, isolate and pivot, collapsing.
- https://v2-24.kiali.io/docs/features/topology: aggregation by graph type, detail graph, find and hide.
- https://github.com/Netflix/vizceral: drill-down levels.
- https://github.com/backstage/backstage/blob/master/plugins/catalog-graph/README.md: relation and kind filters.
- https://ar5iv.arxiv.org/html/1810.08473: Traag et al., Louvain vs Leiden, connectivity and randomness. First ~100k of 140k characters.
- https://arxiv.org/pdf/1903.06895: RELAX; accuracy of recovery techniques (MoJoFM), determinism, naming. Pages 1–4 and 8–11.
- https://github.com/rpgove/greadability: readability metric definitions.
- https://drops.dagstuhl.de/storage/00lipics/lipics-vol357-gd2025/html/LIPIcs.GD.2025.30/LIPIcs.GD.2025.30.html: Mooney et al. 2025, ten normalised quality metrics.

## Not read

These carry no weight. Where a claim from them appears above, it is cited only as restated by a source I did read.

- Purchase 1997 and 2002 (metrics paper: Calgary host unreachable, Springer behind login).
- Ware et al. 2002.
- Huang, Eades & Hong 2014, "Larger crossing angles make graphs easier to read" (embargoed).
- The full text of Yoghourdjian et al. 2020.
- Garcia et al. 2013 (only as cited by RELAX).
- Bunch and ACDC original papers.
- van Ham & Perer 2009 on degree of interest (connection refused).
- Kobourov's 2024 metrics survey (403).
- Dunne et al. 2015.
- Structurizr's automatic-layout page (404) and filtered-view pages.
- Structurizr's implied-relationships cookbook and the related GitHub discussion (search snippets only).
- IcePanel's groups pages.
- Ilograph's perspectives docs.
- Dynatrace Smartscape and AWS X-Ray docs.
- Backstage README-alpha.
