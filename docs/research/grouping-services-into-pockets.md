# Grouping services into pockets for container diagrams

> **Not built (6 Nov 2026).** Pockets were built on #764 and dropped before it merged: a pocket's name came from its central member, so one more unit could rename it. The owner chose a late LLM step that groups by meaning instead (#919). This note stays as the record of the research.

**Depth: high.** This question sets a rule for the design: how the container view is split into pages. It also involves choosing between several algorithm families and deciding how to measure stability, and the design rules count both of those as open questions. Fifteen pages were opened and read in full or in their relevant parts (listed under Sources). Four were PDFs read as extracted text.

## The question

A set of Java/Spring services may not be groupable by explicit signals: a shared database, a gateway path prefix, a shared repository or a Maven groupId. In that case, which algorithm should Lantern use to partition the service dependency graph into "pockets" for diagram pages?

- The graph has roughly 5–60 nodes. Its edges are directed and weighted: synchronous HTTP calls, messaging through topics or queues, and shared data stores.
- The partition must be deterministic and stable.
- Each pocket has at most about 8 members and at least 2.
- The map shows each pocket as one box and must stay at about 10 boxes or fewer.
- Pockets should be cohesive, with few edges between them.
- It should take a few hundred lines of plain Java.
- The note also covers how to weight the edge kinds, how to treat hub services, and how to name a pocket without inventing a business name.

## What authorities, tools and papers say

### How C4 and the diagram tools handle large container views

- **C4 says split the diagram; it does not say how.** The C4 FAQ says large diagrams carry too much cognitive load. Its advice is to split them into several simpler diagrams, each focused on "a business area, functional area, functional grouping, bounded context". For microservices it suggests one diagram per service, showing that service's nearest inbound and outbound dependencies. It admits that this loses "the big picture" and suggests a modelling tool to recover it. It gives no algorithm. (c4model.com/faq)
- **C4 models each service as a group of containers.** For a single team, each microservice is "a group of one or more containers" inside one software-system boundary, and API/database pairs are shown by colour coding or a box drawn around each pair. Where several teams own the services, each service is promoted to its own software system. (c4model.com/abstractions/microservices)
- **Structurizr only groups by hand.** Its `group` keyword draws a dashed boundary around elements, and groups can be nested through `structurizr.groupSeparator`. Grouping and splitting are never automatic. (docs.structurizr.com)
- **Conclusion:** no diagram tool I read derives groups automatically. Lantern's pocket map plus one page per pocket is a mechanised version of the FAQ's "functional grouping" split combined with its per-service view (a service plus its neighbours at the edge).

### Software clustering: stability, cluster sizes, hubs, naming

- **Stability is a first-class criterion, and randomised search is the least stable.** Wu, Hassan and Holt (ICSM 2005) ran six algorithms on about 70 monthly versions each of five systems.
  - Randomised hill climbing (Bunch) was rated **Low** on stability: it "does not perform in a deterministic way and it even produces different decompositions for the same input graph".
  - Bunch was also the best at avoiding "black holes" (one huge cluster) and "dust clouds" (mostly singletons).
  - Single linkage looked most stable only because it put 80–99% of files into one black hole.
  - Lesson: stability must be judged together with a cluster-size criterion. Their size measure, NED, is the share of nodes in clusters of acceptable size.
  - They measured stability with MoJo: the number of move and join operations needed to turn one partition into the other. Tzerpos's earlier definition counted a result as good when MoJo ≤ 1% of nodes.
- **SArF shows how to handle hubs without hand-removal, deterministically.**
  - SArF is Kobayashi et al., ICSM 2012. It scores each dependency A→B with a *Dedication* weight of 1/fan-in(B). A module used by many others adds little weight to any single edge, so "omnipresent" hubs stop gluing clusters together and need no manual removal step.
  - It then runs Newman's greedy agglomerative modularity maximisation on the weighted directed graph. Directed modularity is Q_D = (1/W) Σ_ij [A_ij − k_i^out·k_j^in / W] δ(c_i, c_j).
  - It describes itself this way: "Since the algorithm is deterministic, it has high stability."
  - Results: it had the best stability on 9 systems, though only slightly ahead of plain Newman. On Weka its MoJoFM was 72.9%, against 63.2% for Newman, 42.9% for ACDC and 44.1% for Bunch. Its cluster counts were close to the reference decompositions; Newman produced too few clusters and ACDC too many.
  - It defines stability as MoJoSim(C_{i−1}, C_i) between consecutive versions.
- **Bunch removes hubs by thresholds.** Bunch treats as omnipresent:
  - *supplier*: in-degree ≥ 3× the average degree
  - *client*: out-degree ≥ 3× the average
  - *central*: both
  - *library*: out-degree 0
  
  It takes these modules out before clustering. Nakatsuka et al. found the 3× multiplier is a tunable heuristic, and that removing these modules roughly doubled average cluster cohesion compared with not removing them. (Osaka University, IPSJ 2007)
- **ACDC caps sizes, adopts orphans and names clusters.** ACDC (Tzerpos and Holt) aims for "bounded cluster cardinality" and "effective cluster names".
  - Modules with in-degree or out-degree above 20 are taken off the list of candidate cluster centres; high in-degree modules go to a "support" list.
  - Leftover "orphans" join the subsystem that depends on them most.
  - A cluster is named after its dominator module, a module with a large out-degree, a directory, or "support" for the utility cluster.
  - The wiki says ACDC is unsuitable below 100 files.
  - Wu et al. found that overused dominators produced huge clusters despite ACDC's internal size limit of 20.

### Community detection

- **Louvain** moves each node to the neighbouring community with the best modularity gain, then collapses communities into nodes and repeats.
  - It suffers from modularity's resolution limit, which merges small communities into larger ones.
  - It can produce badly connected or even disconnected communities.
  - It processes nodes sequentially, often in random order.
  
  (Wikipedia "Louvain method"; Leiden paper)
- **Leiden** (Traag, Waltman and van Eck, Sci. Rep. 2019) adds a refinement phase. In the authors' experiments, Louvain left up to 25% of communities badly connected and up to 16% disconnected. Leiden guarantees every community is γ-connected.
  - Leiden is randomised by design: nodes enter the move queue "in a random order", and the refinement step picks merge targets at random with parameter θ (0.01 in the paper).
  - The paper made its runs reproducible only by fixing the seed.
  - It recommends CPM (the Constant Potts Model, which treats γ as a density threshold) because CPM has no resolution limit.
- **Leiden with a size cap.** Silva, Aref, Traag and Fortunato (2026) argue that tuning γ only controls the *average* community size, not each community's size. Their constrained Leiden adds a sub-additive penalty for sizes outside [n_min, n_max].
  - The penalty weight φ starts at 10⁻⁴ and doubles until the partition is feasible, warm-starting each run from the previous partition.
  - With a range of [2, 8] it recovered a ring of cliques at default resolution, where unconstrained Leiden needed γ between 5 and 40.
  - On small instances it matched an exact integer-programming baseline.
  - The `leidenalg` package exposes `max_comm_size`, `initial_membership`, `is_membership_fixed` and `seed`; with no seed it uses a random one.
- **The Java Leiden library has limits.** CWTS `nl.cwts:networkanalysis` 1.3.0 (MIT licence, Java 8+, on Maven Central) implements Leiden and Louvain. It supports **only undirected** networks, its seed defaults to random, and its README does not document a size cap.
- **Label propagation** visits nodes in random order and breaks ties at random. It "produces no unique solution, but an aggregate of many solutions". (Wikipedia)
- **Infomap** is flow-based: it minimises the description length of a random walk. It searches by greedy moves in random order and recommends several trials with different seeds. A module forms where flow is retained: "A dense subgraph need not be a module." (mapequation.org)
- **Girvan–Newman** repeatedly removes the edge with the highest betweenness; the remaining components form a dendrogram. Service Cutter offers it as its deterministic option, which "finds a given number of clusters". Its other option, Leung's epidemic label propagation, is "non-deterministic". Service Cutter clusters a weighted undirected coupling graph. (servicecutter.github.io; Wikipedia)

### Keeping partitions stable across snapshots

From Hartmann, Kappes and Wagner's survey of clustering evolving networks (arXiv 1401.3516):

- **Clustering each snapshot from scratch is unstable.** Greedy agglomerative methods "tend to find different local optima in consecutive snapshots, depending on the order in which the vertices are chosen."
- **Evolutionary clustering** (Chakrabarti et al. 2006) optimises snapshot quality minus a weighted history cost, the distance from the previous partition. Görke et al.'s variant of Louvain optimises a linear mix of modularity and the Rand index against the previous partition.
- **Warm start.** Aynaud and Guillaume start Louvain from the previous partition instead of singletons. This "improves smoothness significantly" while modularity stays close to the static version. Optionally, a fixed share of nodes can be freed each step to allow necessary change.
- **Local updates.** QCA assigns an inserted node greedily to its best cluster, without re-clustering the rest.
- **Readable diagrams need it.** The survey cites "preservation of the mental map" in visualisation as a reason for temporal smoothness.
- **The orphan adoption problem** (Tzerpos and Holt, WCRE 1997, known only from secondary sources) is the same problem from software maintenance: place a new entity into an existing decomposition rather than recompute it.

### Microservice identification

A 2024 review (known only from a search snippet; not read) reports Louvain performing best among community-detection methods for identifying microservices. No head-to-head of Louvain and Leiden on service graphs was found.

## Options compared

| Option | Determinism | Stability under ±1 service | Size control (≤8, ≥2) | Cohesion | Cost in plain Java |
|---|---|---|---|---|---|
| Leiden (CPM or modularity) | Random by design; fixed seed only makes it reproducible, and a small input change shifts what the random generator produces | Medium; good with `initial_membership` warm start | Constrained variant exists, but only in Python/C++ `leidenalg`; the Java CWTS library has no documented cap and is undirected only | Best; guaranteed connected | High (refinement phase plus aggregation); or a new dependency without a size cap |
| Louvain | Random node order; deterministic only with sorted order | Low–medium; warm start helps | None built in; resolution changes only the average size | Good; can produce disconnected communities | Low (~150 lines) |
| Greedy agglomerative modularity (Newman/CNM) with size cap | **Deterministic** with a fixed tie-break | High (SArF best on 9 systems); warm start possible | **Natural**: refuse merges above the cap; singletons stay singletons | Good on small graphs | **Low** (~100–150 lines; O(n³) is trivial at n≤60) |
| Label propagation | Random | Low | None | Prone to one giant community | Very low |
| Infomap | Random; multiple trials | Medium | None | Flow-based; suits call and message flow | High; no Java implementation |
| Girvan–Newman | Deterministic (needs tie-break) | Medium; one betweenness change can reorder cuts | Indirect, via the number of clusters k | Good on small graphs, poorer on large | Medium; O(m²n) is fine at n≤60 |
| Hierarchical linkage (single/complete) | Deterministic | Single linkage is "stable" only through black holes | Cut height only | Single linkage poor; complete linkage gives many singletons | Low |
| Hub removal, then connected components | Deterministic | High | None; one component can exceed 8 | Weak; components are not communities | Very low; useful as a first step only |
| Bunch (MQ hill climbing) | Random | Low (Wu et al.) | Best size distribution | Good | Medium |
| ACDC (patterns) | Deterministic | High | Cap of 20, often overrun | Built for >100 files | Medium; patterns do not fit services |
| Evolutionary/warm start (a layer on top of any of the above) | Inherits from the base algorithm | **Raises** stability | Inherits | Small quality cost | Low |

## Recommendation

Use a **deterministic, size-capped greedy agglomerative modularity clustering**, as SArF does. Weight edges by Dedication to damp hubs. Leave infrastructure hubs out of the clustering. Then run one deterministic node-moving refinement and check that each pocket is connected (the Leiden lesson). When a previous partition exists, start from it (Aynaud–Guillaume) and accept a move only if it clearly improves the score (a hysteresis margin), so the result stays stable. No new dependency is needed.

### Steps

1. **Input.** Use only services not already grouped by an explicit signal.
2. **Leave out infrastructure hubs.** API gateways, config servers, service-discovery and admin servers, recognised by explicit role signals, do not take part in clustering. Each is drawn as its own box on the map and at the edge of every pocket page it touches. This is Bunch's omnipresent removal, but triggered by role, not by a degree threshold.
3. **Build directed weights.** Per ordered pair (A, B), add the weight of each edge kind *present* (presence, not the number of endpoints or calls, so one chatty pair cannot dominate):
   - shared data store: 3 (symmetric)
   - synchronous call A→B: 2
   - messaging, producer A → consumer B through a topic or queue: 1
   
   These weights are a starting choice; none of the sources read prescribes them. Calibrate them on the development test sets.
4. **Damp hubs (Dedication).** Multiply each directed edge A→B by 1/fan-in(B), where fan-in counts distinct services that use B, with kinds folded together. Then make the graph symmetric: w(A,B) = w(A→B) + w(B→A). A shared notification service then still joins the pocket that depends on it most, but can no longer glue all pockets together (SArF).
5. **Mark statistical hubs.** A service whose weighted degree is at least 3× the average (Bunch's supplier/client rule) is clustered normally. Afterwards, if no single pocket holds at least half of its edge weight, it is pulled out and drawn as its own box.
6. **Merge greedily with a size cap.** Start from singletons. For a merge of pockets i and j, ΔQ = 2(e_ij − γ·a_i·a_j), where:
   - e_ij is the share of total weight on edges between i and j
   - a_i is the share of total weight touching pocket i
   - γ = 1
   
   Repeatedly merge the connected pair with the largest ΔQ > 0 whose combined size is ≤ the cap (8). Stop when no allowed merge has a positive gain.
7. **Refine.** Visit nodes in sorted id order. Move a node to the neighbouring pocket with the largest strictly positive gain, if the cap allows. Repeat until no move is made, with at most n passes. Then split any pocket that is no longer connected in the weighted graph into its components; Leiden guarantees the same property.
8. **Minimum size.** Pockets of 1 are not pockets; the service is drawn as itself.
9. **Map size.** Count boxes: pockets, standalone services and hubs.
   - If there are more than 10, adopt orphans as ACDC does: each singleton joins the pocket it has the most weight to, if the cap allows. Order by descending weight, then by id.
   - If there are still more than 10, merge the pair of pockets with the highest weight between them, even at a negative gain, within the cap.
   - If it still does not fit, fall back to the C4 FAQ's one-page-per-service view, and the quality check reports that the map does not fit. Never silently exceed 10.
10. **Make it deterministic.** All iteration runs over services sorted by a stable id (the repository and module coordinate). Ties in gain are broken by the lexicographically smallest sorted member list. Exact equality of ΔQ is compared with a tolerance of 1e-12. Doubles are summed in sorted order. There is no randomness anywhere.
11. **Keep it stable across snapshots.**
    - When a stored partition from the previous snapshot exists, start step 7 from it instead of from step 6's output. Drop services that have gone, add new services as singletons, then run the merge of step 6 on that start.
    - Accept a move or merge only if its gain exceeds a margin ε (for example 0.01·Q), so near-ties do not flip-flop.
    - Keep each pocket's identity by matching new pockets to old ones by greatest overlap (Jaccard).
    - Test it: on synthetic graphs, adding or removing one service must give MoJo ≤ 1 + (size of the pocket it joins or leaves) and must leave every other pocket unchanged. Report MoJoSim between consecutive runs, as SArF and Wu et al. do.
    - Without a stored partition, determinism alone gives SArF-level stability.
12. **Name a pocket.** Name it after its member with the highest *internal* weighted degree, the sum of its edge weights inside the pocket, with ties broken by external degree and then by id. Write it as "<service> and N others" (or "<service> pocket"). Never invent a business name. This follows ACDC's naming after the dominator or high-degree module. Internal degree is chosen over betweenness or PageRank because it is cheap, deterministic and easy to explain. Once a pocket is matched to its previous identity, it keeps its previous name if that service is still a member.

**Library choice.** Not needed. CWTS networkanalysis would add a dependency that is undirected only, random by default and has no documented size cap. The constrained Leiden exists only in Python and C++. At 60 nodes, the hand-written greedy-plus-refine approach costs about 200–300 lines.

## What it would change in the design

In the design's container-view section, add a "pockets" rule. When explicit grouping signals do not bring the container view within the box limit, Lantern partitions the remaining services with deterministic, size-capped greedy modularity clustering:

- Dedication-weighted edges: shared store 3, sync call 2, messaging 1, each scaled by 1/fan-in.
- Infrastructure hubs left out, and drawn as their own boxes.
- Pockets of 2 to 8 members; at most 10 boxes on the map.
- Pockets must be connected.
- Each pocket is named after its member with the highest internal degree.
- The run starts from the previous snapshot's pockets when they exist, and a test measures stability with MoJo.

The view becomes one map page plus one page per pocket, with outside neighbours shown at the edge. The detail belongs in an ADR that links to this note.

## What Lantern took (#764, 6 Nov 2026)

The chat that wrote this note judged it and took its recommendation with these changes:

- **Where pockets live.** Pockets are stored by the derive stage like domains (`Groups`, signal 5, basis `coupling`), not worked out while drawing, so the naming role names and describes them like any group (owner, 6 Nov 2026: "sure try"). The same rule groups a unit's components the feature-area rules leave, from the calls between them.
- **Size cap 4, a setting.** `lantern.diagrams.pocket-size`, 4 by default: a pocket's page then holds its members and their neighbours within the diagram limit of 8 plus 2. The note's cap of 8 left no room on a pocket's page for what it talks to.
- **Hubs.** Gateways (units with a gateway route) join no pocket. The statistical hub step (5) and the map-size steps (9) are not built: Dedication damps hubs, the composer's fallback (merge lines, outside off, split) handles a map still too large, and both wait until a test set shows the need.
- **No warm start.** Pockets are recomputed from each snapshot's facts, deterministic by sorted order and fixed tie-breaks; a test shows the order of the facts changes nothing. The warm start (step 11) waits until the test sets show pockets moving between snapshots.
- **Name.** The signal names the central member (most weight inside the pocket, then degree, then ID); the naming role gives the pocket its name.

## Sources (read)

- C4 model FAQ — https://c4model.com/faq
- C4 model, Microservices — https://c4model.com/abstractions/microservices
- Structurizr DSL cookbook, Groups — https://docs.structurizr.com/dsl/cookbook/groups/
- Traag, Waltman, van Eck, "From Louvain to Leiden: guaranteeing well-connected communities", Sci. Rep. 2019 (full text) — https://pmc.ncbi.nlm.nih.gov/articles/PMC6435756/ (abstract also at https://arxiv.org/abs/1810.08473)
- Wikipedia, Louvain method — https://en.wikipedia.org/wiki/Louvain_method
- Wikipedia, Label propagation algorithm — https://en.wikipedia.org/wiki/Label_propagation_algorithm
- Wikipedia, Girvan–Newman algorithm — https://en.wikipedia.org/wiki/Girvan%E2%80%93Newman_algorithm
- Infomap, How it works — https://www.mapequation.org/infomap/how-it-works/ (and the overview, https://www.mapequation.org/infomap/)
- Wu, Hassan, Holt, "Comparison of Clustering Algorithms in the Context of Software Evolution", ICSM 2005 — https://www.eecs.yorku.ca/course_archive/2009-10/W/6431/Wu.pdf
- Kobayashi et al., "Feature-Gathering Dependency-Based Software Clustering Using Dedication and Modularity" (SArF), ICSM 2012 — https://arxiv.org/pdf/1306.2096
- Hartmann, Kappes, Wagner, "Clustering Evolving Networks" (survey) — https://arxiv.org/pdf/1401.3516
- Silva, Aref, Traag, Fortunato, "Heuristic and exact modularity optimization with size-constrained communities" (2026) — https://www.alphaxiv.org/abs/2605.25248.md
- leidenalg reference documentation — https://leidenalg.readthedocs.io/en/stable/reference.html
- CWTS networkanalysis (Java Leiden/Louvain) — https://github.com/CWTSLeiden/networkanalysis
- Service Cutter tool page — https://servicecutter.github.io
- ACDC algorithm wiki, York University — https://wiki.eecs.yorku.ca/project/cluster/protected:acdc
- Nakatsuka, Matsushita, Inoue, "Improving understandability of software clustering results with Component Rank" (on Bunch's omnipresent rules), IPSJ 2007 — https://ir.library.osaka-u.ac.jp/repo/ouka/all/50208/ipij-jnl_48_9_3281.pdf

## Not read (leads only, no weight)

- Gysel et al., Service Cutter paper (ESOCC 2016), full text: how its coupling criteria map to edge weights is unverified.
- Mitchell and Mancoridis, Bunch, IEEE TSE 2006, full text.
- Tzerpos and Holt, "The Orphan Adoption Problem" (WCRE 1997) and "On the Stability of Software Clustering Algorithms" (IWPC 2000).
- Chakrabarti, Kumar, Tomkins, "Evolutionary clustering" (KDD 2006), original: known only through the survey above.
- Aynaud and Guillaume, the warm-start Louvain paper: known only through the survey.
- The microservice reengineering review (arXiv 2407.13915), the Aalto thesis and the ICSA 2023 Louvain paper: snippets only.
- CNM-ES and RELISON "Balanced FastGreedy": snippets only.
- IcePanel, Backstage and Ilograph documentation on grouping: not opened.
- JGraphT clustering algorithms: not checked.
