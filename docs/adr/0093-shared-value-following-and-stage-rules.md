# 0093: One value follower, and architecture rules for the order of the stages

Status: accepted (#283)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 7, "Values are emitted as
written") keeps constant folding and the one-hop `@Value` follow in extraction, as facts about the
code. Five extractors each carried their own copy of that follow: HTTP client, messaging, gateway,
identity and WebSocket. The copies differed in small ways: when a field counted as given one value
(the same expression everywhere, or the same text everywhere), which values a field could be
followed to, and whether the gateway followed fields at all. Section 5 orders the stages: extract,
link, derive, the LLM roles, compose, render. The only architecture rule was that extractors do not
use the LLM.

## Decisions

- **One follower.** `io.lantern.extract.java.Values`, made once per code model
  (`JavaAnalysis.values()`), follows an expression in the code it is in:
  - a constant is written as it is (the analyzers already fold constants and concatenations);
  - a field or parameter with `@Value`, directly or through a meta-annotation, gives the
    annotation's text as written, placeholders and all;
  - with the hop, a field without `@Value` gives what its type's constructors and initializers
    give it, followed without a further hop, when every value given to it follows to the same
    parts (so a constant and a `@Value` with the same text do not agree);
  - anything else stays open, as its expression.

  The result is the value's parts in order, the fields and code it was read through, and the code
  whose parameters stayed open. Each extractor keeps only how it writes the parts: the HTTP client
  and gateway write an open part as its source in braces, messaging and identity take a value only
  when nothing stayed open (identity also only a URL). The gateway follows without the hop, as
  before. The follower also gives the members, parameters and field assignments of the code, and
  a field's one assignment when every constructor gives it the same expression (`single`), which
  the extractors that follow values used to index and copy for themselves. The gateway keeps its
  own ordered member list for finding filters and beans, which is not value following.
- **Placeholders are not resolved here.** That stays with config for now and moves to the linker's
  value resolution (#519, #522).
- **The identity extractor's config keys** (`keys()`) walk calls as well, such as
  `Keys.hmacShaKeyFor(secret.getBytes())`, to find the `@Value` placeholders a secret is read
  from; that walk stays its own, and follows a field through the follower's `single`.
- **WebSocket destinations** keep their own reading: they follow formatted strings and fields up to
  three hops with no `@Value`, which is a pattern of destinations, not the value follow; they use
  the follower's field assignments.
- **Stage order.** An ArchUnit rule (`lantern-archtest`) fails when a class of a stage depends on a
  class of a later stage. The stages are packages: extract `io.lantern.extract`, link
  `io.lantern.link`, derive `io.lantern.derive`, the LLM roles `io.lantern.summarize` and
  `io.lantern.investigate`, compose `io.lantern.compose`; render is the renderers. A stage not built
  yet has no classes and passes.
- **Renderers read no facts.** A renderer is an app class whose name, or its enclosing class's,
  ends in `Renderer`. It may not depend on the fact store (`FactRepository`) or on a stage that
  makes facts (extract, link, derive, the LLM roles, the LLM harness). The facts the document model
  carries as citations are part of the document and stay readable.
- `ArchitectureRulesTest` shows each rule failing on a deliberately bad fixture.

## Consequences

- Where the copies disagreed, the shared rule applies. A field given the same expression in two
  constructors whose parameters carry different `@Value`s no longer takes the first one's; fields
  given different expressions that read the same are now followed; HTTP and messaging can follow a
  field to another `@Value` field or a concatenation; WebSocket's relay settings now also find a
  `@Value` through a meta-annotation, as the others did; the HTTP client reads a URL again at the
  calls of the code making it also when a field's disagreeing values left that code's parameter
  open. None of this changes the fake-model
  scorecards on project-0 to project-4 and project-8 (#283's PR).
- A new extractor that follows values uses `Values` rather than its own copy.
