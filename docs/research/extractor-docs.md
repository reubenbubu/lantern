# What READMEs, documents and decision records state reliably enough to cite

Research for #385 (Review the README and ADR extractor), milestone "Extractor revisit". It asks which decision record formats and README conventions occur in real repositories, and what in them states architecture (purpose, containers, people, external systems, decisions and their status) reliably enough to cite. It leads to an amendment of `docs/adr/0033-docs-extractor.md` and the README and ADR row of `docs/design.md` (Static analysis and extractors).

**Depth: high.** Read in full, 27 sources:

- 13 format specifications, templates and tool sources (Nygard, MADR 2–4, adr-tools, log4brains, Y-statements, arc42, Structurizr);
- 4 README and documentation conventions (Standard Readme, GitHub, GitHub markup, matklad's ARCHITECTURE.md);
- 4 papers (README content, stale docs, ADRs in practice, LLM architecture recovery from docs);
- 2 project and abstract pages (ArDoCo, ADR adoption study).

The full text of the ADR adoption study (Buchgeher et al. 2023) was not reachable; only its abstract carries weight. The research is generic: it does not read Lantern's test sets. The test-set evidence below was gathered afterwards, to check the decision.

## Findings

### Decision records

- **Nygard (2011)** set the shape every later format follows: title, context, decision, status, consequences. Status is proposed or accepted, and a later record marks an earlier one deprecated or superseded rather than deleting it. [Nygard]
- **adr-tools** writes `# N. Title`, a `Date:` line, a `## Status` section with the status on its own line, then Context, Decision and Consequences. It keeps the records in `doc/adr` unless a `.adr-dir` file names another folder, and names them `0001-slug.md`. Superseding writes `Superceded by [title](file)` (the tool's own spelling) into the old record's Status section, and `Supercedes` into the new one; custom link types such as `Amends` go there too. [adr-tools template, adr-new, _adr_add_link, _adr_dir]
- **MADR** changes where its metadata lives:
  - 2.x: a `* Status: …` bullet list under the title, and positive and negative consequences as separate sections. [MADR 2.1.2]
  - 3.0: YAML front matter (`status`, `date`, `deciders`), one Consequences section. [MADR 3.0.0]
  - 4.0 (2024): `decision-makers` in front matter, the decision as `Chosen option: "X", because …` under `## Decision Outcome`, records in `docs/decisions`, numbers unique per folder. [MADR 4.0.0, adr.github.io/madr]
- **log4brains** uses MADR, allows date-prefixed names (`20200101-…`) and per-package `packages/<pkg>/docs/adr` folders. [log4brains]
- **Y-statements** are one sentence ("In the context of …, facing …, we decided for …") with no status. They live inside other documents, not as files. [DPR Y-form]
- **arc42** section 9 holds decisions as ADRs or a table. Its other sections map onto C4: 1 goals (purpose), 3 context (people, external systems), 5 building blocks, 7 deployment. [arc42 section 9]
- **Structurizr's importers** show how little robust parsing does, and one trap:
  - The adr-tools importer takes the first word under `## Status` and maps "Superceded".
  - The MADR importer reads status only from front matter and defaults a missing status to "accepted", which makes MADR 2 records look accepted. [Structurizr importers]
  - **Never default a status.**
- **Adoption is low but growing.** About half of the repositories with ADRs hold 1 to 5 records, and Nygard's template dominates. [Buchgeher 2023, abstract] In a microservice company, where records were stored decided whether they were used, and decisions on shared components stayed undocumented. [Ahmeti 2024]

### READMEs and other documents

- **README content** was studied by Prana et al. (2018) over 4,226 sections of 393 READMEs:
  - 97% of READMEs say *what* the project is, but only 17% of sections do;
  - 58% of sections are *how* (install, run, configure), and 20% are references;
  - *why* appears in a quarter of READMEs. [Prana 2018]
- **Standard Readme** puts the short description first and has no architecture section; architecture goes under extra sections. [Standard Readme]
- **Formats.** GitHub renders READMEs in `.md`, `.adoc`, `.rst` and more, and looks for them in `.github/`, the root and `docs/`. [GitHub about READMEs, github/markup] AsciiDoc is the documentation format of the Spring projects, so Java repositories use it more than most; reStructuredText is rare outside Python.
- **ARCHITECTURE.md** (matklad) gives a bird's-eye view, a code map of coarse modules, invariants and boundaries. It holds only what rarely changes and is revisited a few times a year: high in signal, expected to lag the code. [matklad]
- **arc42 and other design documents** sit in `docs/architecture`, `docs/arc42` or plain `docs`. Structurizr's `!docs` imports every Markdown and AsciiDoc file of a folder. [Structurizr docs]
- **Process files carry no architecture:** CONTRIBUTING, CHANGELOG, CODE_OF_CONDUCT, SECURITY, LICENSE. Prana's "who" and "contribution" categories are entirely such content.

### Trust

- **Stale documentation is the norm** (Tan, Wagner and Treude 2023):
  - 28.9% of top projects hold at least one outdated code reference in their docs now, and 82.3% did at some point;
  - outdated references were fixed by changing the code nearly as often as the doc. [Tan 2023]
- **Docs give names and intent; line citations must come from the extractor.** In ExArch, LLM-based component extraction from architecture documentation alone reached F1 0.86, about as good as hand-made models and better than from code. But:
  - the LLM returned unreliable line numbers;
  - it invented components;
  - it confused synonyms. [ExArch/ArTEMiS 2025]
- **ArDoCo detects inconsistencies** between docs and models in both directions: model elements the text never mentions, and documented elements the model lacks. The second direction is the harder case. [ArDoCo]

## Options

1. **Keep reading only READMEs and decision records in Markdown.** Simple, but it misses AsciiDoc, the Spring ecosystem's format, and design documents beside the README, where architecture prose often lives.
2. **Read every Markdown and AsciiDoc file in the repository.** Complete, but it pulls in changelogs, contribution guides, templates and generated or vendored docs, which are noise.
3. **Read READMEs, decision records, and documents in a documentation folder or named ARCHITECTURE, in Markdown and AsciiDoc, as cited blocks; skip process files.**
   - This is deterministic, so every claim has its exact lines.
   - The LLM decides what a claim means, and code facts decide what exists.
4. **Parse structured architecture sources (Structurizr DSL, arc42 section numbers) into typed facts.** Precise where present, but rare, and Structurizr DSL is a model language that needs its own extractor.

## Decision

**Option 3, with diagrams kept, and option 4's Structurizr DSL as its own later extractor.** ADR 0033's amendment for #385 records the rule:

- **Documents.** A Markdown or AsciiDoc file in a `doc`, `docs`, `documentation` or `architecture` folder, or named `ARCHITECTURE`, is a *document*. Its claims are read like a README's, as low-trust documented claims.
  - Process files (contributing, changelog, license, code of conduct, security, to-do and the like), templates, hidden folders and decision record folders are left out.
  - Code facts still decide what exists. A document claim never makes a container.
- **AsciiDoc** READMEs, documents and decision records are read into the same blocks as Markdown. Attribute entries (`:status:`) stand in for front matter.
- **Diagrams.** A fenced or delimited block in a diagram language (Mermaid, PlantUML, Graphviz, ditaa), or a plain one drawn with box characters, is a claim holding the diagram's source. Authors often draw their containers and ports this way instead of writing prose.
- **Kept as is:**
  - The decision record formats ADR 0033 already reads cover Nygard, adr-tools, MADR 2–4 and log4brains folders.
  - Status is kept as written, so `Superceded by …` survives, and a missing status stays missing.
  - Claims are not filtered by section. What a claim is about is the LLM's call, and the README claim checks (ADR 0043) work on any claim.
- **Not done:**
  - Structurizr DSL is a new extractor (#488, milestone Later).
  - Following `.adr-dir`, and reStructuredText, are rare in Java repositories.
  - Reading supersession links as relations between decisions belongs to the composer's decisions section, if it needs them.

### Evidence from the development sets

Before this change, the hand-made references of project-1 to project-4 cited 29 lines of documentation. All README lines had their claims, except one ASCII diagram (project-1, `README.md:75-78`). Nine citations had no facts at all, because the files were neither READMEs nor decision records:

- project-1: `docs/bff_comparison_analysis.md`, `dual_session_strategy.md`, `defense_in_depth_vs_perimeter.md`, `proactive_token_refresh.md`, `user_registration_flow.md` and `PRODUCTION_CHECKLIST.md`;
- project-2: `docs/architecture/system-context.md`.

project-2's nine decision records were all read before. No set holds AsciiDoc, ARCHITECTURE files or diagram sources.

## Sources

- Nygard, "Documenting Architecture Decisions" (2011): https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions
- adr-tools: https://github.com/npryce/adr-tools; template https://raw.githubusercontent.com/npryce/adr-tools/master/src/template.md; `adr-new` https://raw.githubusercontent.com/npryce/adr-tools/master/src/adr-new; `_adr_add_link` https://raw.githubusercontent.com/npryce/adr-tools/master/src/_adr_add_link; `_adr_dir` https://raw.githubusercontent.com/npryce/adr-tools/master/src/_adr_dir
- MADR: https://adr.github.io/madr/; 2.1.2 https://raw.githubusercontent.com/adr/madr/2.1.2/template/template.md; 3.0.0 https://raw.githubusercontent.com/adr/madr/3.0.0/template/adr-template.md; 4.0.0 https://raw.githubusercontent.com/adr/madr/4.0.0/template/adr-template.md
- log4brains: https://github.com/thomvaill/log4brains
- Y-statements (Design Practice Repository): https://socadk.github.io/design-practice-repository/artifact-templates/DPR-ArchitecturalDecisionRecordYForm.html
- arc42 section 9: https://docs.arc42.org/section-9/
- Structurizr `!adrs` https://docs.structurizr.com/dsl/adrs and `!docs` https://docs.structurizr.com/dsl/docs; importers https://raw.githubusercontent.com/structurizr/java/master/structurizr-import/src/main/java/com/structurizr/importer/documentation/AdrToolsDecisionImporter.java and https://raw.githubusercontent.com/structurizr/java/master/structurizr-import/src/main/java/com/structurizr/importer/documentation/MadrDecisionImporter.java
- matklad, "ARCHITECTURE.md" (2021): https://matklad.github.io/2021/02/06/ARCHITECTURE.md.html
- Standard Readme: https://github.com/RichardLitt/standard-readme/blob/main/spec.md
- GitHub, "About READMEs": https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes; github/markup: https://github.com/github/markup
- Prana et al., "Categorizing the Content of GitHub README Files", EMSE 2018: https://arxiv.org/abs/1802.06997
- Tan, Wagner, Treude, "Detecting Outdated Code Element References in Software Repository Documentation", EMSE 2023: https://arxiv.org/abs/2212.01479
- Ahmeti et al., ADRs in practice, ECSA 2024: https://rebekkaa.github.io/files/2024_ECSA.pdf
- Buchgeher et al., ADRs in open source projects, IEEE Access 2023 (abstract only): https://research.jku.at/en/publications/using-architecture-decision-records-in-open-source-projects-an-ms/
- ArDoCo: https://ardoco.de/
- Fuchß et al., ExArch and ArTEMiS (2025): https://arxiv.org/html/2511.02434v1
