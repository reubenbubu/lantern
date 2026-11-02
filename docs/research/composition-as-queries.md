# Composition as queries

This is the research for #696, the first issue of milestone 1G "Composer on the model".

**Depth: high.** The question is open: it decides how the composer is built and how the design's Composition section reads, and it needs tools, typed-access prior art and papers compared. **Pages read: 24 in full** (Structurizr: 5 documentation pages and 2 source files; LikeC4: 4; Ilograph: 3; IcePanel: 1; Backstage: 1; Glean: 2; jOOQ: 1; c4model.com: 2; Graphviz FAQ: 1; 2 research papers, read in full as PDFs). **Read in part: 5** (SPARQL 1.1, the property paths and ordering; RDF 1.1 Semantics, its entailment rules; an arXiv abstract page; the TU/e oblique-lifting abstract; the Wikipedia page on ISO/IEC 42010). ISO 42010's own site refused the connection and the ArchiMate viewpoints chapter needs a login, so neither carries any weight (Not read). What rests on a source read in part (lifting, the RDFS rules, SPARQL ordering) is only what its opened part says, and each point is also made by a source read in full (Structurizr, LikeC4).

## Question

How should a documentation generator compose diagrams and documents as queries over a stored, typed, read-only architecture model? Five parts:

1. How a view is defined over the model.
2. How lines are merged between elements, groups and parents.
3. How a view that is too big is split or folded.
4. How typed access works over an open vocabulary with inheritance, so a renderer falls back to a parent type's presentation.
5. How composition stays deterministic and testable.

## What we learned

### 1. Defining a view over one model

- **Structurizr: a view is a kind, a scope and include and exclude statements**: `container <software system>`, `component <container>`, `dynamic <scope>`, `deployment <scope> <environment>`. "Including elements also includes the relationships between them." What `*` means depends on the view kind: in a container view, the system's containers plus directly connected people and systems. The "reluctant wildcard" `*?` adds only relationships to or from the scoped element. <https://docs.structurizr.com/dsl/language>
- **Structurizr's expressions select by type, tag, parent, technology, property and group**: `element.type==Container`, `element.tag==`, `element.parent==`, `element.properties[name]==value`, `element.group==name`. `->x` adds what comes in, `x->` what goes out, `->x->` both; relationships by `relationship.tag==`, `relationship.source==`, `*->*`; combined with `&&` and `||`. Anything harder goes to "DSL scripts or plugins". <https://docs.structurizr.com/dsl/expressions>
- **Structurizr's filtered views derive from a base view by tag**, and it warns: "Automatically generated view keys are not guaranteed to be stable over time." <https://docs.structurizr.com/dsl/language>
- **LikeC4: "Views are projections of the model from various perspectives, scopes, and levels of detail."** `view of X` sets the scope; `extends` reuses a view, and "the predicates and style rules of extended views are applied after the ones from ancestors". <https://likec4.dev/dsl/views/>
- **LikeC4's predicates are ordered and incremental.** "Each included element brings in its relationships with already visible elements." `X.*` selects children; `X.**` descendants "IF they have a relationship with visible elements"; `X._` expands X. `where` filters by kind, tag or metadata, and relationships by their ends (`where kind is microservice`, `metadata.protocol is "grpc"`). "Order is significant; predicates are applied as defined within the view", and "excludes apply only to elements/relationships included earlier". <https://likec4.dev/dsl/views/predicates/>
- **LikeC4's dynamic views are steps written in the view**, their titles possibly "derived from the model"; the sequence variant "supports only connections with _leaf_ elements". <https://likec4.dev/dsl/views/dynamic/>
- **Ilograph keeps one resource tree and draws several "perspectives" of it.** Its "contexts" are other parent trees over the same resources: "the relations (arrows) remain the same but the layout of the diagram changes when different contexts are selected". <https://www.ilograph.com/docs/editing/perspectives/>, <https://www.ilograph.com/docs/editing/contexts/>, <https://www.ilograph.com/docs/editing/perspectives/relation-perspectives/>
- **IcePanel: diagrams are "a visual representation of a model"**; taking an object off a diagram leaves it in the model. <https://docs.icepanel.io/features/diagramming>
- **Orthographic Software Modeling (Atkinson, Stoll and Bostan, 2009) argues for one Single Underlying Model**, every view "generated dynamically from the SUM via the appropriate transformation": "no effort is needed to keep views consistent in a pairwise basis, because as long as each is consistent with the SUM, it is consistent with all the other views". For read-only views "it is only necessary to define a transformation in one direction". It keeps what a view shows apart from its "language" and "notation". <https://www.scitepress.org/Papers/2009/19532/19532.pdf>

What this means for Lantern:
- The epic's view definition (scope, kinds shown, filters, split rule, template) is what every tool converges on: a scope, a selection of elements and of relationships, and a presentation, with no copied data.
- Selecting by kind must include subtypes: in Lantern a kind is a vocabulary type with parents.
- What a view shows (its definition) stays apart from how it is drawn (the template and the C4 presentation).
- Every view has an explicit, stable key.

### 2. Merging lines ("implied relationships")

- **Structurizr has three strategies**: none by default; "unless same exists", which causes "an explosion of relationships"; and "unless any exists", the DSL's default, to "show a summary relationship at higher levels in the model". <https://docs.structurizr.com/java/implied-relationships>
- **In its source, an implied relationship copies only the description and technology of the lower one** (`model.addRelationship(source, destination, relationship.getDescription(), relationship.getTechnology(), false)`), keeps a link back to it (`setLinkedRelationshipId`), refuses a pair whose ends are the same or one the other's parent, and walks every pair of ancestors. So the first lower relationship processed decides a merged line's label: it depends on order. <https://github.com/structurizr/java/blob/master/structurizr-core/src/main/java/com/structurizr/model/AbstractImpliedRelationshipsStrategy.java>, <https://github.com/structurizr/java/blob/master/structurizr-core/src/main/java/com/structurizr/model/CreateImpliedRelationshipsUnlessAnyRelationshipExistsStrategy.java>
- **LikeC4 merges lines when it draws a view and stores none.** "Connections represent merged relationships - direct between elements and/or those derived from their nested elements"; a merged one is titled `[...]`; `multiple true` draws "each relationship as a separate edge". <https://likec4.dev/dsl/views/predicates/>
- **IcePanel reuses lower connections at higher levels**, which "sync changes in the original connection". <https://docs.icepanel.io/features/diagramming>
- **Relation partition algebra calls this lifting** (from the abstract only, so it names the idea and carries no more weight than that): "Lifting transforms a relation at a certain level of the decomposition structure of a system" to a higher one; "oblique lifting" works when leaves sit at different depths; after lifting, "various kinds of weights (or multiplicities) may be associated with the interconnections". (Abstract only.) <https://research.tue.nl/en/publications/hiding-expressed-using-relation-algebra-with-multi-relations-obli/>
- **Structurizr's groups are boundaries, not elements**; nothing draws a relationship to a group. <https://docs.structurizr.com/dsl/cookbook/groups/>

What this means for Lantern:
- Lines are merged when a view is composed, as LikeC4 and lifting do, and never stored. The design already says so for groups (`Groups.lines`, Derivation).
- Lifting is oblique: a unit may stand in the system or in a domain, at different depths. When a view shows a domain instead of its units, each line end goes to the domain.
- A merged line keeps the facts behind it and their count, as Structurizr's link and lifting's weights do.
- Its label never comes from whichever relationship is first.

### 3. Splitting, folding and readability

- **Yoghourdjian et al. (IEEE TVCG, 2020) tested graphs of 25 to 175 nodes at 2, 4 and 6 edges per node on a shortest-path task.** People were wrong or unsure more than half the time from 100 nodes at density 2, from 75 at density 4 and from 50 at density 6; accuracy was already about 90% at 25 nodes and about 75% at 50, even at density 2. Node and edge counts and crossings predicted difficulty best; the authors propose size limits and "overview-and-detail techniques". <https://arxiv.org/pdf/2008.07944>
- **IcePanel**: "We recommend breaking complex diagrams into multiple diagrams that exist in parallel"; "avoid overlapping connections". <https://docs.icepanel.io/features/diagramming>
- **C4**: a microservice is "a group of one or more containers", shown with grouping boxes; services owned by separate teams become systems of their own. <https://c4model.com/abstractions/microservices>, <https://c4model.com/diagrams/faq>
- **LikeC4 folds directly**: `X._` keeps only children with lines to what is shown, `X.**` only connected descendants. <https://likec4.dev/dsl/views/predicates/>

What this means for Lantern:
- The evidence supports a threshold well below 50 elements, not exactly 15: 15 stays a setting (`docs/design.md`, Readable diagrams; #366 measures it), and lines count as well as elements.
- The epic's order (one overview; then domains instead of units, a view per domain; then split by concern) is overview-and-detail with Ilograph's other parent tree.
- A domain's view shows its units and their neighbours outside it, lifted to what the view shows (Structurizr's `*?`, LikeC4's `->X->`).

### 4. Typed access over an open vocabulary

- **RDFS entailment**: rdfs9 says "xxx rdfs:subClassOf yyy" and "zzz rdf:type xxx" entail "zzz rdf:type yyy"; rdfs11 makes subClassOf transitive, rdfs5 and rdfs7 the same for properties. So "every container" includes every `batch-job`, and every `CALLS` is an `INTERACTS_WITH`. <https://www.w3.org/TR/rdf11-mt/>
- **SPARQL**: `?x rdf:type/rdfs:subClassOf* ?type` finds "all resources and all their inferred types" without duplicates or endless loops, but "query patterns generate an unordered collection of solutions": order must be imposed. <https://www.w3.org/TR/sparql11-query/>
- **Glean**: each predicate is the type of its facts; "derived predicates" are defined by a query, stored or computed on demand, and `codemarkup` is "a language-neutral abstraction over language-specific schemas" that clients read. <https://glean.software/docs/schema/basic/>, <https://glean.software/docs/derived/>
- **jOOQ's generated code** catches schema changes at compile time ("Removing columns will lead to compilation errors"); it suits a schema fixed at build time. <https://www.jooq.org/doc/latest/manual/code-generation/>
- **Backstage**: "Adding a kind has a very large impact", because "there will be many places where code checks `if (kind === 'X')`"; adding a type "takes relatively little effort"; relations "may be dangling". <https://backstage.io/docs/features/software-catalog/extending-the-model>
- **Structurizr's presentation cascades like CSS**: every element carries default tags that "cannot be removed" (`Element`, then its type's tags), and "as with CSS, styles cascade according to the order in which they are added". **LikeC4** gives each element kind a default style that relationships inherit "unless the relationship overrides them". <https://docs.structurizr.com/server/diagrams/notation>, <https://likec4.dev/dsl/specification/>

What this means for Lantern:
- The composer's and renderer's type checks on leaf types are Backstage's problem: each new type is a code change. Decisions ask by ancestor (is it a container? is this relationship an `INTERACTS_WITH`?), through the closure of parents.
- A constant per type suits only types fixed at build time. Lantern's built-in types are; runtime-added ones are not and keep the generic API (`docs/design.md`, Knowledge model, Typed access).
- Presentation walks up a type's parents to the first that has one, ending at its C4 level's default.

### 5. Determinism and testability

- **Order is not given by default**: SPARQL's results are unordered, Structurizr's merged labels depend on processing order and its generated keys are unstable, and Graphviz's neato layouts "are heavily influenced by the initial position of the nodes". <https://www.w3.org/TR/sparql11-query/>, <https://docs.structurizr.com/dsl/language>, <https://graphviz.org/faq/>
- **LikeC4 says which rule wins**: for elements the first, for relationship customization the last. <https://likec4.dev/dsl/views/predicates/>
- **OSM**: views stay consistent because each is a one-way function of one model. <https://www.scitepress.org/Papers/2009/19532/19532.pdf>

What this means for Lantern: composing is a pure function of the model, the view definition and the template; every selection sorts by a stable key and every tie-break is written down; checks over the model and the composed views need no LLM.

## Options

**(a) How a view definition is expressed**

| Option | For | Against |
| --- | --- | --- |
| A Java class per diagram kind (today) | Full power | Logic hides in code; hard to list, compare or test |
| A declarative record (key, scope, kinds, filters, split rule, template), built in Java | Printable, comparable, testable; order explicit; no parser | Needs a small vocabulary of selectors; odd cases need code |
| A text DSL (Structurizr, LikeC4) | Users could write views | Lantern's views are its own; a grammar for no user |

**(b) How lines are merged across groups**

| Option | For | Against |
| --- | --- | --- |
| Store implied relationships (Structurizr) | Simple to query | Non-facts in the model; order-dependent labels; explosion |
| Lift when composing, to the element the view shows (LikeC4, lifting) | Model stays as derived; works for units, domains and areas; keeps weights | Needs a label rule |
| Draw every relationship | Exact | Unreadable at scale |

**(c) Splitting and folding**

| Option | For | Against |
| --- | --- | --- |
| Split by count into pages | Simple | Cuts across meaning |
| Overview, then stored groups instead of their members, a view per group | Overview and detail; each part has a theme | Only as good as the groups |
| Fold away unconnected children | Cheap | May hide real parts |
| Split by concern last | Handles what is still too big | Less natural to read |

**(d) The typed access layer**

| Option | For | Against |
| --- | --- | --- |
| A generated constant or record per type (jOOQ) | Compile-time checks | Only for types known at build time |
| A generic API on strings | Open | Copied strings everywhere (about 111 today, as #280 counts) |
| Typed records of the model's shapes, a type reference that knows its parents, constants for the built-in types in one layer, decisions by ancestor | Open and safe; strings in one place; parent fallback built in | Constants must match the vocabulary (a test checks) |

## Recommendation

**The model.** `ArchitectureModel` is read-only and typed: elements with their type, C4 level, name, technology and responsibility; interactions with their ends, type, protocol, intent, profiles and the facts behind them; components and their links; groups and their members and names; flows with their steps, significance per scope and narrative; findings, deployment instances, purpose and key fact. A type is a reference resolved from the vocabulary that knows its parents and its C4 level; queries take a type and include its subtypes. The model is the one place that reads predicate strings and attribute names; the composer and the renderer see none.

**View definitions are records**, not code and not a DSL: a stable key, scope, the kinds shown (by type, subtypes included), filters, a split rule and a template, applied in their written order.

**Merging lines** happens when a view is composed: each end goes to the nearest element the view shows (a unit, or the domain or feature area that stands for it), a line whose ends become one element or one the other's parent is dropped, and the rest merge into one line per ordered pair of ends and protocol, keeping the facts behind it, sorted. *Lantern's judgement on the label, against the note's sources:* the label follows ADR 0088, which already settles it without depending on order: one phrase, the one most of the line's links say, the first alphabetically on a tie, else the static label. A line between groups takes the intent Name groups stored for it (`(group, intent, "<other end> <protocol>")`, ADR 0107), else the same rule over its member lines. A count such as "N interactions" is not a label: it is no intent phrase (`docs/quality-bar.md`, Rubric).

**Splitting**: the overview first; above the threshold, domains instead of units (units in no domain drawn as themselves) with one view per domain (its units and their lifted neighbours); what is still above it split by concern. Components do the same with feature areas, and an area still above it is split by groups of entry points (epic, section 12). Thresholds are settings.

**Typed access and fallback.** *Lantern's judgement:* the note's sources argue against a constant per type for an open vocabulary, but Lantern's built-in types are fixed at build time and already have generated attribute records (#518, ADR 0090). So predicate and type names of built-in types become constants in one typed layer in core (#280), and every decision of what a type is drawn as (C4 level, kind, shape) asks the vocabulary by ancestor, so a runtime type that extends `container` is drawn as one with no code. The renderer receives resolved kinds and maps no type.

**Determinism and tests**: every list sorted by a stable key; a test that composes one model, view and template twice and gets the same document; tests that every drawn line traces to facts of the model and every interaction in scope is on some view, lifted or not; quality checks as queries over the model and the composed document.

## What it changes in the bar or the design

The bar: none. The design's Composition section gains a paragraph, "Composition reads the model", stating the rule above and linking here: the composer reads only `ArchitectureModel`; a view is a record; lines are lifted to what the view shows and merged per ordered pair and protocol when composing, never stored, labelled by ADR 0088 and Name groups' intents; the split order; C4 kind and presentation from the nearest ancestor type. #530 and #280 build it and make it true.

## Sources

Read in full:
- <https://docs.structurizr.com/dsl/language>
- <https://docs.structurizr.com/dsl/expressions>
- <https://docs.structurizr.com/java/implied-relationships>
- <https://docs.structurizr.com/server/diagrams/notation>
- <https://docs.structurizr.com/dsl/cookbook/groups/>
- <https://github.com/structurizr/java/blob/master/structurizr-core/src/main/java/com/structurizr/model/AbstractImpliedRelationshipsStrategy.java>
- <https://github.com/structurizr/java/blob/master/structurizr-core/src/main/java/com/structurizr/model/CreateImpliedRelationshipsUnlessAnyRelationshipExistsStrategy.java>
- <https://likec4.dev/dsl/views/>
- <https://likec4.dev/dsl/views/predicates/>
- <https://likec4.dev/dsl/specification/>
- <https://likec4.dev/dsl/views/dynamic/>
- <https://www.ilograph.com/docs/editing/perspectives/>
- <https://www.ilograph.com/docs/editing/perspectives/relation-perspectives/>
- <https://www.ilograph.com/docs/editing/contexts/>
- <https://docs.icepanel.io/features/diagramming>
- <https://backstage.io/docs/features/software-catalog/extending-the-model>
- <https://glean.software/docs/schema/basic/>
- <https://glean.software/docs/derived/>
- <https://www.jooq.org/doc/latest/manual/code-generation/>
- <https://c4model.com/diagrams/faq>
- <https://c4model.com/abstractions/microservices>
- <https://graphviz.org/faq/>
- Yoghourdjian, Yang, Dwyer, Lawrence, Wybrow, Marriott, "Scalability of Network Visualisation from a Cognitive Load Perspective", IEEE TVCG 2020, <https://arxiv.org/pdf/2008.07944>
- Atkinson, Stoll, Bostan, "Supporting View-Based Development through Orthographic Software Modeling", ENASE 2009, <https://www.scitepress.org/Papers/2009/19532/19532.pdf>

Read in part:
- <https://www.w3.org/TR/sparql11-query/> (property paths and ordering)
- <https://www.w3.org/TR/rdf11-mt/> (the RDFS entailment rules)
- <https://arxiv.org/abs/2008.07944> (abstract page)
- <https://research.tue.nl/en/publications/hiding-expressed-using-relation-algebra-with-multi-relations-obli/> (abstract of Bril, Feijs, Glas, Krikhaar and Winter on oblique lifting)
- <https://en.wikipedia.org/wiki/ISO/IEC_42010> (no definitions there)

## Not read

These carry no weight:
- ISO/IEC/IEEE 42010 conceptual model, <http://www.iso-architecture.org/42010/cm/> (connection refused)
- ArchiMate 3 viewpoints chapter, <https://pubs.opengroup.org/architecture/archimate3-doc/chap13.html> (needs a login)
- Moody, "The Physics of Notations", IEEE TSE 2009 (no open full text)
- Feijs and van Ommering, "Relation partition algebra", 1999 (abstract only)
- Atkinson et al., later OSM papers
- <https://docs.icepanel.io/core-features/modelling>
- <https://docs.structurizr.com/ui/diagrams/notation> (404)
- Kythe's schema documentation
- LikeC4's deployment views page
