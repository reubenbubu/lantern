# Extraction coverage

Research for #380, the first issue of the milestone "Extractor revisit". It leads to
`docs/adr/0089-extraction-coverage.md` and the extraction coverage check in `docs/design.md`,
Checking the quality bar.

## Question

How do architecture-recovery and code-intelligence tools decide which facts to extract from Java
systems, and how do they measure whether extraction is complete against a ground truth? For
Lantern: for each item of a test set's reference (container, data store, connection, each citing
code by file:line), does an extracted fact of the right kind have evidence on the cited code? That
splits the scorecards' losses into extraction losses and linking losses.

## What the authorities, tools and papers say

- **Code2DFD** (Schneider and Scandariato, JSS 2023) is the closest precedent. It recovers a data
  flow diagram of a Java microservice system with one extractor per technology:
  - services from build files and Docker Compose
  - infrastructure (Kafka, RabbitMQ and others) from well-known image names
  - names, ports and config-server flows from `bootstrap.yml` and properties files
  - flows from API calls such as `@FeignClient`, database connections and producer/consumer pairs

  Every item records traceability as "the containing file's path and the lines of code". Its
  evaluation scores true and false positives and negatives per kind (services, external entities,
  flows, annotations) against 17 hand-made diagrams: precision 0.93 and recall 0.85 on the
  validation set. Items are matched by identity; the traceability lines are published but not
  scored. Misses came from flows detected in the wrong direction, technologies with no extractor
  or a faulty one, and a database whose only evidence was an ambiguous line and a README comment.
  <https://arxiv.org/abs/2304.12769>
- **microSecEnD** (Schneider et al., MSR 2023) publishes those 17 diagrams "with full traceability
  from model to code". <https://github.com/tuhh-softsec/microSecEnD>
- **A comparison of nine static microservice-recovery tools** (Schneider, Bakhtin, Li, Soldani,
  Brogi, Cerny, Scandariato, Taibi, 2024) against the same ground truth reports precision, recall
  and F1 separately for components, connections and endpoints:
  - Code2DFD: components F1 0.98, connections F1 0.87
  - MicroDepGraph: connections recall 0.51
  - Prophet: components recall 0.15, connections recall 0.01

  Lessons: deployment files alone give high precision and low recall, and most misses are
  unsupported connection technologies or a narrow scope of files read.
  <https://arxiv.org/html/2412.08352v1>
- **Prophet** (Bushong, Das, Cerny, CLOSER 2022) finds endpoints and inter-service calls in Java
  code to draw communication diagrams and context maps. <https://www.scitepress.org/Papers/2022/110321/>
- **Lutellier et al.** (TSE 2017) vary the facts fed to recovery techniques: include versus symbol
  dependencies, granularity and dynamic-binding resolution. They find that "using accurate symbol
  dependencies has a major influence on recovery quality". This is the evidence that extraction
  errors carry into the recovered architecture, so extraction is worth measuring on its own.
  <https://www.cs.purdue.edu/homes/lintan/publications/archrec-tse17.pdf>
- **Garcia, Krka, Mattmann, Medvidovic** (ICSE 2013): without ground-truth architectures, recovery
  techniques "are difficult to evaluate". They build four ground truths with the systems'
  engineers. <https://2013.icse-conferences.org/content/obtaining-ground-truth-software-architectures.html>
  (seen in search results only). The companion comparison (Garcia, Ivkovic, Medvidovic, ASE 2013)
  finds even the best technique "has surprisingly low accuracy" against eight ground truths.
  <https://researchr.org/publication/GarciaIM13> (search results only)
- **jQAssistant** scans each artifact type with a plugin's scanner (Java classes, Maven POMs, XML,
  JSON, JUnit) into labelled graph nodes. Framework knowledge such as Spring is added as rules over
  the scanned graph. <https://jqassistant.github.io/jqassistant/current>
- **Structurizr's component finder** finds components with one strategy per kind of evidence:
  `@Controller`, `@RestController`, `@Service`, `@Component`, `@Repository` and Spring Data
  repository types.
  <https://github.com/structurizr/java-extensions/blob/master/docs/spring-component-finder-strategies.md>
  (search results only)
- **CodeQL** keeps framework knowledge as models per library. Its tests write the expected result
  as an inline comment on the source line it concerns, so each expected fact is anchored to a
  location, like a reference citation.
  <https://codeql.github.com/codeql-standard-libraries/java/utils/test/InlineExpectationsTest.qll/module.InlineExpectationsTest.html>
  (search results only)
- **The Backstage catalog** uses the same element kinds as Lantern: components (service, website,
  library) with provided and consumed APIs, and resources such as databases and topics. It is
  declared by hand, not extracted.
  <https://backstage.io/docs/features/software-catalog/descriptor-format/>
- **Traceability recovery** (Hayes, Dekhtyar et al.) scores candidate links between artefacts and
  code with recall, precision and F1. <https://homepages.uc.edu/~niunn/courses/RE-refs/IR-Tracing-Hayes03.pdf>
  (search results only)

In short, tools choose what to extract per element kind and technology, one extractor or strategy
each, which is Lantern's design too. They measure completeness per kind with precision and recall
against a hand-made ground truth. None of the sources scores whether the extracted evidence lies on
the ground truth's cited lines. Code2DFD and microSecEnD record file:line traceability but match by
name. Measuring by evidence on cited lines is new, but rests on Code2DFD's traceability and
CodeQL's location-anchored expectations.

## Evidence from the test sets

The references of project-1 to project-4 already cite file:line for every item, in their
`facts/*.md`. The 2A scorecards' losses (connection recall 13–68%, data stores 0–25%) cannot say
whether a fact was never extracted or extracted and not linked. Lantern's facts carry
`evidence.repo`, `path` and `lines`, so both sides of an overlap check exist.

## Options

1. **Evidence line overlap.** An item is covered when any fact's evidence overlaps its citation.
   It is cheap and needs no model of fact kinds. But it is too lenient: a Java structure fact on
   every class "covers" a missed HTTP client call.
2. **A checklist of required facts per element kind,** matched by name as Code2DFD and the 2024
   comparison do. It follows the literature, but matching by name is fragile and it ignores the
   citations, the costliest part of a reference.
3. **End-to-end recall only.** Lantern has this already (the scorecard), and it cannot separate
   extraction from linking. Lutellier et al. show that the inputs matter.
4. **Overlap restricted to the expected area.** Each reference item names the extractor area or
   areas that should produce its facts and the code they should cite. A need is met when a fact of
   that area has evidence overlapping the citation. Each miss is reported as either wrong area
   (another extractor has a fact there, so a rule is missing) or uncovered (no fact there, so an
   extractor or a file type is missing). It uses the citations, isolates the losses and gives the
   diagnosis Code2DFD had to do by hand. The cost is one map per set, kept with its reference.

## Recommendation

Option 4. Each set's map lists, per item, the areas and citations it needs, or marks the item
linking only, with a reason, when no code location states it. Report coverage per kind beside the
scorecard's end-to-end recall: the gap between them is the linking loss. Treat it as a diagnostic,
not a target. As in Code2DFD, some reference evidence (README prose, an ambiguous line) is rightly
outside static extraction, so such items are marked, not silently dropped.

Lantern differs from the sources in two ways:

- **Areas are written per item in the map, not derived from a table of kinds.** A single item can
  need facts from several areas (a service needs Maven and Spring facts), and the map is checked
  against the reference by a test.
- **No widening to the enclosing declaration.** Citations are narrowed to the declaring lines when
  the map is written, so a plain overlap suffices.

## What it changes

- `docs/design.md`, Checking the quality bar: extraction coverage as a standing check beside
  scoring.
- `docs/adr/0089-extraction-coverage.md`: the map, the matching rule and the facts file `analyze`
  writes.
- `docs/quality-bar.md`: no rule changes. Coverage measures Lantern's extraction, not its output.
