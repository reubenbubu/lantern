# 0033: Docs extractor: README claims and decision records

Status: accepted (#114), amended for #385

## Context

`docs/design.md` lists a README extractor that reads Markdown in the repository and produces
low-trust "documented claims" (Static analysis and extractors; Annotations: knowledge layer,
READMEs and the wiki), and the Quality bar asks the Architecture Explorer for observed decisions
with their rationale quoted from ADRs or READMEs, or marked unknown. The report spec's decisions
section (`report-specs/c4-model-sequence-diagrams`) shows each decision with an id such as
`ADR-012`, a title, a body and a status. The design leaves open which files count, how much of
the Markdown is understood, what the facts look like and how much they are trusted. Checking
claims against the code and composing the decisions section come later (#97 and the follow-up
named below).

## Decisions

- **One extractor, two kinds of file.** `DocsExtractor` (`lantern-extract`, package
  `io.lantern.extract.docs`) reads READMEs and decision records from git at the snapshot's
  commit, never the working tree, and needs no build. Files over 1 MiB are left out, and so are
  files under `node_modules`, `target` or `src/test`.
- **Which files.** A *decision record* is a `.md` or `.markdown` file in a directory named `adr`,
  `adrs`, `decisions`, `decision-records`, `decision-log`, `architecture-decisions` or
  `architecture-decision-records` (any case, at any depth, so adr-tools' `doc/architecture/decisions`
  and MADR's `docs/decisions` count), or one named like `ADR-012...`. Indexes and templates
  (`README`, `index`, `toc`, `summary`, `_sidebar`, any name containing `template`) are not, and
  neither is a file with no status and no context, decision or consequences section. A *README* is
  a file named `README`, `README.md` or `README.markdown` (any case) outside those directories.
- **No Markdown library.** A small line-based reader in the package finds what the extractor
  needs: ATX and setext headings, paragraphs, list items, table rows, block quotes, fenced and
  indented code, HTML blocks and comments, and YAML front matter as top-level `key: value` lines.
  Inline Markdown is kept as written, since the report spec's fields are Markdown. It is not a
  CommonMark parser: nested lists are flattened and a list item's later paragraphs are dropped,
  which loses nothing the facts need.
- **Decision records.** Read in the common formats: Nygard's (adr-tools' `Date:` line and
  `## Status` section), MADR 3 and 4 (front matter), MADR 2 (`* Status:` list) and `Status:` or
  `**Status:**` lines. The title is the front matter's `title`, else the first level-1 heading,
  else a first heading that is not a section, else the file name; a leading number (`1.`,
  `0002:`, `ADR-012:`) is cut from it. The number comes from the file name (`0012-...`,
  `ADR-012...`), else from that cut; a date-named file (`2024-05-06-...`) gives a date, not a
  number. Status and date come from the front matter, else a `Status:`/`Date:` line, else the
  first block under a `Status`/`Date` heading, kept as written (`Superseded by [4. ...](...)`).
  Sections are matched by heading, ignoring case, numbering and a trailing colon: *context*
  (`Context`, `Context and Problem Statement`, `Problem Statement`, `Problem`, `Background`),
  *outcome* (`Decision`, `Decisions`, `The Decision`, `Decision Made`, `Decision Outcome`) and
  *consequences* (any heading containing "consequences", one fact each, so MADR 2's positive and
  negative ones stay apart). A section runs to the next heading of the same or a higher level, or
  the next section heading, and keeps its subheadings and Markdown, without comments.
- **Facts.** Entity types `decision` and `readme`, keyed `<repo>:<path>` with whitespace and `%`
  percent-encoded (entity keys allow no whitespace). A decision gets attribute facts `title`,
  `decision-number` (integer), `decision-status`, `decision-date`, `decision-context`,
  `decision-outcome` and `decision-consequences`, each only when found, each with the line range
  it was read from (none when it comes from the file name). A README gets one `claim` fact per
  paragraph, list item, table row and quote, with the heading it is under as attribute `section`;
  a table row reads `Header: cell; ...`, and lines with no words once links and images are left
  out (badges) are skipped. A claim's `key: value` and `KEY=value` pairs whose key names a secret, as
  the Config extractor judges keys (`ConfigExtractor.isSecret`, ADR 0032), have the value stored
  as `******` (a value that is only a `${...}` placeholder is kept), so a password written in a
  README never reaches a fact, the composer or the LLM. The status, date and section names carry a `decision-` prefix so they
  cannot clash with later, broader meanings of `status` or `context` (such as the design's
  Context annotations). No relationship facts: the composer finds a repository's decisions by
  the key or the evidence's repository, and linking claims to entities is the follow-up's job.
- **Provenance and trust.** Origin kind `readme` (the design's kind for documented sources) for
  both, extractor `docs` version 1. Evidence is the repository, commit, path, line range and the
  file's SHA-256. Confidence is 0.5 for README claims, which the design calls low-trust, and 0.8
  for what a decision record says: a deliberate record, but one that may be stale.
- **Wiring.** The analysis run (ADR 0016) runs the extractor with the others and stores its facts;
  the summarizer ignores them, since no module contains them (since ADR 0059 a repository's
  summary prompt shows its README's first claims). Composing the decisions section
  from them is #97.
- **Front matter and fences.** Front matter counts only when every line between the `---` lines
  is a `key: value` line, an indented (nested) line, a comment or blank; otherwise the first
  `---` is a thematic break. A backtick fence's info string has no backticks, so a line such as
  `` ```foo``` `` is inline code, not a fence. A title's leading number followed only by a space
  (`12 Factor config`) is cut only after `ADR` or when the file name gives the same number.
- **Not done here.** Checking README claims against code ("README says MySQL, code connects to
  Postgres", project-0 case DOC-2) needs the runtime facts of #92 and is #133. Decisions stated
  only in a README are kept as its claims, not as decisions. There is no cap on the number of
  claims per README yet; a very long README gives one fact per paragraph, which a later issue may
  limit if fact volume or LLM context becomes a problem.

## Amendment (#385): documents, AsciiDoc and diagrams

The review of the extractor and its research note (`docs/research/extractor-docs.md`) found
architecture written outside READMEs and decision records, in AsciiDoc, and in diagrams. The
development sets' references cite such documents, which had no facts. Extractor `docs` version 3.

- **Documents.** A Markdown or AsciiDoc file in a folder named `doc`, `docs`, `documentation` or
  `architecture` (any case, at any depth), or named `ARCHITECTURE`, is a *document*: entity type
  `document`, keyed like a README. It gets `claim` facts as a README does, at the same confidence,
  0.5.
- **What a document is not.** These are left out:
  - READMEs and decision records, which keep their own types;
  - anything in a hidden folder (`.github`) or a decision record folder, whose other pages index
    its records;
  - files about the project's process rather than its architecture: contributing, changelog,
    changes, history, release notes, code of conduct, security, license, notice, to-do, authors,
    maintainers, support and governance;
  - any name containing `template`.
- **AsciiDoc.** READMEs (`README.adoc`, `README.asciidoc`), documents and decision records in
  AsciiDoc are read into the same blocks as Markdown by a small line-based reader:
  - section titles (`=` to `======`);
  - paragraphs, list items, and description lists (`term:: text`, read as `term: text`);
  - tables (`|===`), with a header row when the first line is followed by a blank line or the
    table says `%header`;
  - listing, literal, passthrough and comment blocks.

  Attribute entries (`:status: Accepted`) stand in for front matter, so a decision record's
  status and date are read as in MADR 3. Includes are not followed.
- **Diagrams.** A fenced (Markdown) or delimited (AsciiDoc) block is a diagram, and becomes a
  claim holding its source as written, when either:
  - its language is a diagram language: `mermaid`, `plantuml` (`puml`, `c4plantuml`),
    `graphviz` (`dot`) or `ditaa`;
  - it names no language, or plain text, and draws with box characters (U+2500 to U+257F) or an
    ASCII box corner (`+---+`).

  The claim's attribute `diagram` gives the language, or `text` for a drawing. Other code blocks
  stay out.
- **Consumers.** The summarizer, the Explorer's "start here" link, the system's purpose prompt and
  the README claim checks (ADR 0043) use README claims only, as before. The purpose prompt's
  choice of README is now limited to the `readme` type, so that a root `ARCHITECTURE.md` is not
  taken for it. Document claims are stored with the other facts; how documents use them is the
  composer's and the roles' concern, not this extractor's.
- **Kept as is.** The decision record formats above already cover Nygard, adr-tools, MADR 2 to 4
  and log4brains' folders. Statuses stay as written: `Superceded by …`, adr-tools' spelling, is
  kept, and a missing status stays missing. Claims are not filtered by section.
- **Not covered:** Structurizr DSL (#488); following `.adr-dir`; reStructuredText.
