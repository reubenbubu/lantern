# Grouping a system's parts by meaning with an LLM

**Question.** How should an LLM be prompted, checked, evaluated and kept stable when it groups a
system's services and components into named domains and feature areas for architecture documents?

**Depth: high.** It chooses between approaches (one request or several, names or ids, how to score
against a reference, how to keep groups stable), and the bar sends a choice between approaches to
high depth. A research subagent (opus) read 21 pages in full or nearly in full on 6 Nov 2026 for
#919; two were read in part, as listed below.

## What the sources say

### Grouping guidance

- C4 splits a large diagram rather than shrinking it, each part focused on a "business area,
  functional area, functional grouping, bounded context"; it warns that one diagram per service
  loses the big picture, and sets no numeric box limit ([C4 FAQ](https://c4model.com/faq)).
  Whether services are grouped containers or systems of their own depends on team ownership
  ([C4 microservices](https://c4model.com/abstractions/microservices)).
- Structurizr's groups are named boundaries with no meaning in the model
  ([Structurizr groups](https://docs.structurizr.com/dsl/cookbook/groups/)).
- "There's no mechanical process that produces the correct design": group by business capability,
  not technical layer, from closely related functions and the dependency graph
  ([Microsoft, domain analysis](https://learn.microsoft.com/en-us/azure/architecture/microservices/model/domain-analysis)).
- Context Mapper's discovery uses explicit signals only (a Spring Boot application per bounded
  context, docker-compose for relations), needs a name-mapping step, and finds nothing where no
  strategy applies ([Context Mapper](https://contextmapper.org/docs/reverse-engineering/)). That is
  where Lantern's code-signal groups stand today.

### Prompting and input

- Clustering as classification: the LLM first proposes labels, then assigns items to them in
  batches; batches of about 15 worked best, and giving about 20% of the true labels as seeds helped
  (MTOP-I ARI 71.9 against 29.0 for an embedding method). Known labels play the part of Lantern's
  code-found groups ([Huang & He 2024](https://arxiv.org/html/2410.00927v2)).
- SemRef (ICSE 2026) refines an existing recovery with GPT-4o-mini at temperature 0: summaries of
  files, then modules, then split or merge decisions, each checked against dependency cohesion,
  with formatting into JSON as a prompt of its own; the top-down refinement gave the most
  ([SemRef](https://arxiv.org/pdf/2607.23774)).
- Names carry the architecture: clustering Java classes by their qualified names reached a2a 85.8
  against 70.7 to 74.3 for classic tools, and class bodies instead of names lost 5 points of a2a and
  29 of c-score ([ClassLAR](https://arxiv.org/html/2512.15980v1); first 100k of 120k characters
  read).
- Who calls whom helps: dependency context raised F1 by 0.07 to 0.11
  ([ArchAgent](https://arxiv.org/html/2601.13007v1)).
- Few-shot examples make decompositions finer; the prompt mattered more than the provider
  ([arXiv 2609.20308](https://arxiv.org/abs/2609.20308), abstract).
- Constraining the output to the known structure allows deterministic checks
  ([arXiv 2602.18644](https://arxiv.org/abs/2602.18644), abstract); off-the-shelf models handle
  structure but struggle with fine abstractions
  ([ECSA 2025](https://conf.researchr.org/details/ecsa-2025/ecsa-2025-call-for-research-papers/7/Automated-Software-Architecture-Design-Recovery-from-Source-Code-Using-LLMs),
  abstract).
- Longer inputs make every one of 18 tested models less reliable, and one distractor already
  lowers accuracy ([Chroma, Context Rot](https://www.trychroma.com/research/context-rot)).

### Checking the answer

- In JSON, a reason written after the answer loses the reasoning (GPT-3.5 put "answer" first in
  every response; one task fell from 56.7 to 25.2); structured output helps classification
  (Claude 3 Haiku 33.8 to 52.0 on DDXPlus) ([Let Me Speak Freely?](https://arxiv.org/html/2408.02442v3)).
- Simple schemas comply far more often; constrained decoding did not cost accuracy
  ([JSONSchemaBench](https://arxiv.org/html/2501.10868v3); 100k of 111k characters read).
- Weaker models broke the schema on long inputs (Llama 3.3 70B failed SemRef's two largest
  projects), and SemRef accepts a decision only if cohesion rises or the model confirms it again.
- Invented ids are a known failure; short aliases checked against a list are the practitioners'
  fix ([DEV](https://dev.to/nikhilverma/llms-as-unreliable-narrators-dealing-with-uuid-hallucination-151e),
  anecdotal). No controlled study of numeric ids against names in a prompt was found.

### Scoring against a reference

- MoJoFM makes splits dearer than merges; a2a stays above 0.7 even for poor results; c2c depends on
  module sizes and is unstable; report a gain as the share of the remaining distance it removes,
  (M'−M)/(1−M); match the number of groups to the reference before scoring and check other levels
  too ([SemRef](https://arxiv.org/pdf/2607.23774); [ClassLAR](https://arxiv.org/html/2512.15980v1)).
- One metric can be gamed by extreme groupings (many singletons, one huge group), so a bundle is
  reported ([Mo2oM](https://arxiv.org/html/2508.07486v1);
  [arXiv 2601.23141](https://arxiv.org/abs/2601.23141), abstract).

### Stability

- The most stable classic clusterers were stable because they put 80 to 98% of files in one
  cluster; stability is judged only together with closeness to a reference and group sizes,
  measured as MoJo between versions ([Wu, Hassan & Holt 2005](https://www.eecs.yorku.ca/course_archive/2009-10/W/6431/Wu.pdf)).
- Output varies between runs even at temperature 0 ([Ouyang et al.](https://arxiv.org/abs/2308.02828v1),
  abstract), so accepted groups must be stored, not recomputed.
- SemRef re-checks a decision only when its input changed.

## Evidence from the request variants

On 6 Nov 2026 four requests were built from project-8's analysis and each answered three times by
Claude Haiku standing in for the API (a subagent given the system prompt and the request alone),
scored by the same checks and by pair agreement with the reference's feature areas of
workorder-service and billing-service. Every element was listed once with a number.

| Variant | Input tokens (about) | workorder-service pair precision / recall, average (range) |
| --- | --- | --- |
| 1: what each part does, endpoint paths | 7,300 | 0.80 (0.76 to 0.83) / 0.86 (0.77 to 0.92) |
| 2: plus lines between parts | 9,100 | 0.77 (0.73 to 0.83) / 0.86 (0.77 to 0.92) |
| 3: plus flows, no lines | 9,100 | 0.83 (0.83) / 0.90 (0.86 to 0.92) |
| 4: plus lines and flows | 10,900 | 0.87 (0.83 to 0.89) / 0.88 (0.86 to 0.92) |

billing-service scored 1.00 / 1.00 in every answer, since the code already groups most of it.
Numeric ids caused no id error in 12 answers. Lines alone added tokens and no gain; flows helped a
little and varied least between runs; lines with flows gained 4 points of precision and lost 2 of
recall for 1,800 more tokens, within the spread of three runs. Variant 3 was kept (owner, 6 Nov). Domains varied between runs, which is why groups
are stored. The model often failed to split an area of 10 components when asked, so splitting is
left to code. Round 2 of the experiment, with full names and every detail, was about 36,500 tokens.
The requests, answers and checkers are kept outside the repository, in the main checkout's
`workspace/919-grouping-experiment/`.

## Options and trade-offs

- **Requests.** One request per system is simplest and lets the model balance sizes, but quality
  falls with length. Two stages (names, then assignment in batches) scale and turn assignment into
  classification, at more calls. Per level (domains per system, areas per oversized service) keeps
  each request small and matches C4's levels.
- **Names or ids.** Names alone repeat long strings on every line; ids alone lose the strongest
  signal. Both, each element listed once with its number, name and responsibility, then numbers
  everywhere else, keeps the meaning and makes the answer exactly checkable.
- **Detail.** Name and kind alone are too thin; flows, code and full endpoint lists risk
  distractors. Responsibility, a few endpoint paths and the lines is what the evidence supports.
- **Stability.** Re-running churns; storing groups and placing only new elements is stable but
  keeps early mistakes; re-opening a group on a defined trigger corrects them.
- **Scoring.** Pairwise precision and recall over "same group" pairs is easy to read; MoJoFM
  compares with the literature; extremity counts guard against gaming.

## Recommendation

1. One request per level: domains once per system, feature areas once per service with more
   components than a view holds. Code-found groups go in as fixed: the model adds members, never
   renames, splits or removes them, except that an area over the box limit is split into
   sub-areas. Above about 20,000 input tokens (a guess to measure), switch to names first, then
   assignment in batches of about 15.
2. Each element listed once as `id | name | group | responsibility | up to 5 endpoint paths`;
   flows and the answer use only ids, checked against the list. Ids are never sent without names.
3. Flows are included and lines are not: the comparison above found lines added nothing and flows
   a little. An area too large for one page is split by code, not by the model.
4. The answer gives each group's reason and evidence before its members. Temperature 0, one retry
   naming the failed checks, else the code-only grouping.
5. Accepted groups are stored and kept; later runs send only new elements. A group is re-opened
   only when it breaks the box limit, loses over half its members, or a member's lines now point
   mostly elsewhere.
6. Scored by pairwise precision and recall against the reference, with MoJoFM, the share of
   singleton groups, the largest group's share and the pages over the limit beside it.

## What it changes

`docs/design.md`, Groups: membership of what the code signals leave ungrouped comes from a late LLM
step (after Describe), stored and kept; the code-signal rules are unchanged. The LLM roles table
gains the grouping role. `docs/quality-bar.md`, Diagrams stay readable, names it. ADR 0129 records
it.

## Sources

Read: the C4 FAQ and microservices pages; Structurizr's groups cookbook; Microsoft's domain
analysis; Context Mapper's reverse engineering; SemRef (paper and ICSE page); ArchAgent; ClassLAR
(in part); Huang & He; Wu, Hassan & Holt; Mo2oM; arXiv 2609.20308, 2601.23141, 2602.18644 and the
ECSA 2025 paper (abstracts); Let Me Speak Freely?; JSONSchemaBench (in part); Chroma's Context Rot;
Ouyang et al. (abstract); the DEV post on id hallucination. Read and not relied on: arXiv
2401.14931 (ids recalled from memory, not given in a prompt); arXiv 2404.15604 (pages 1 to 8; it
did not hold a claim a search snippet gave it).

Not read, no weight: Tzerpos & Holt on clustering stability (2000) and orphan adoption (1997),
snippets only; arXiv 2109.09525; arXiv 2311.04643 (could not be extracted); Atil et al. 2408.04667;
"Lost in the Middle"; Wen & Tzerpos 2004 (MoJoFM); Sambu, Capuano & O'Dea, ICSA 2026.
