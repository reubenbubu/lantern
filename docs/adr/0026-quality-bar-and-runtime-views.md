# 0026: A quality bar, and diagrams of runtime units instead of build units

Status: accepted (#86), pending the user's review (#24)

## Context

The user judged the phase 1 diagrams useless: boxes were Maven modules and Java packages, every
line was a build-time "depends on", and nothing said which part is responsible for what. The
cause was the input, not the wording. Phase 1 had only Maven and Java structure facts, so the
composer (ADR 0012) drew what it had. Research into what makes good C4 diagrams, sequence
diagrams and architecture documents (`docs/research/architecture-documentation-quality-bar.md`)
produced a rubric. The user asked for the design to change so Lantern can reach it, keeping the
existing architecture (extractors, fact store with envelope, vocabulary and a C4 level per type,
cross-repo linker, composer, renderer), and for the design to stop being shaped by the one test
set it had been checked on.

## Decisions

- **Adopt the rubric as the design's Quality bar** (`docs/design.md`, Quality bar). Checks are
  yes or no, so a document can be shown to be right rather than plausible; the composer makes the
  ones it can on every document, and the rest are judged on the test sets each phase.
  `docs/research/` becomes the place for research and evidence (`CLAUDE.md`, One source of truth).
- **C4 levels as c4model.com defines them** (design, Composition, Diagrams). Containers are
  runtime units, not modules or jars, because a reader needs to know what runs and who is
  responsible for what, and build structure answers neither. This replaces ADR 0012's C4 mapping
  and changes the C4 levels of ADR 0002's built-in types (#90).
- **Code diagrams are not drawn.** The research says "on request, for one key component"; Lantern
  links types to their source instead, since IDEs show code structure better and nobody has asked
  for one. This can be revisited without changing the model.
- **Sequence diagrams from stitched traces** (design, Composition, Sequence diagrams). Only
  boundary-crossing steps are kept, because generated diagrams that show every call become
  unreadable, and stitching across repos is what a single-repo tool cannot do. Flows are chosen
  by a deterministic ranking so the same ones return on later runs.
- **The LLM words, it never invents** (design, LLM integration, Grounding), because confident
  invented edges are the main failure of generated architecture docs.
- **Extraction and linking gaps go into the existing tables** (design, Static analysis and
  extractors; Cross-repo linking), not new stages: the architecture already has the right places.
- **Executive Brief additions** (design, Quality bar). Change-history measures need git history
  beyond the snapshot and are left for later. Where end-of-life data comes from is an open
  question in the design.
- **Phase reorder.** System view comes before the remaining Phase 1 fixes, because per-repo
  documents from Maven and Java facts alone can't meet the bar.
- **Test sets are generic** (design, Phasing). No single test set should shape the design, so
  the design names none; the OpenWMS details moved to ADR 0004. Choosing more is #88.

## Consequences

- Phase 1 documents keep working but do not pass the bar. Follow-up issues in the Phase 2
  milestone build it: vocabulary C4 levels (#90), document model additions (#91), runtime
  extractors (#92), deployable units (#93), the linker (#94), the new views with the bar's checks
  (#95), sequence flows (#96), and the Explorer sections and summarizer wording the bar
  requires (#97).
- The document model gains a `sequence` diagram kind and a quality report block, a schema change
  per ADR 0011 (#91).
- The Executive Brief (ADR 0015) changes with the new views: its context and container diagrams
  are redrawn by the new definitions, and its package-cycle risk, which reads the old
  package-as-component diagrams, has to be rebased on packages directly or replaced.
- #49 and #53, about package-level component diagrams, no longer apply and are to be closed:
  components are now found by role, and diagrams are capped at 15 elements.
