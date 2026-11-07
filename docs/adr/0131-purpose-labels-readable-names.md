# 0131: Every line says what it is for, every box reads as a name

Status: accepted (#933), amended for #944 (lines into one box with one label) and #946 (readable names written by Describe)

## Context

The owner reviewed project-8's C4 report on 6 Nov 2026 (milestone 1I). Of its 406 line labels,
17 were route lists ("Calls GET /api/collections/cases, GET … and 13 more") and 7 a bare "Uses";
labels repeated what the line's ends show ("Stores work orders in PostgreSQL" into a box that says
PostgreSQL); four boxes had no description; services showed the build's names ("edge-gateway")
beside readable ones ("Repair work"); a database was named
"workorder (billing.datasource.workorders.url)".

The four blank boxes had one cause: Describe's sentence for each was rejected twice by the support
judge, and ADR 0101 (amendment for #751) then left the box without a sentence.

## Decision

1. **A line's label is its purpose.** ADR 0088's label keeps its order (the group's intent, the
   phrase most of its links state, the line's own intent), and then the phrase its links state
   most often, never the list of what they record. With no phrase at all it says what the
   records are about, never the list: routes as "Works with collections and cases" (each route's
   first path segment after `api` and versions), a gateway's as "Routes reports and jobs
   requests", screens as "Opens the registration screens", methods as the verbs their names start
   with ("Approves, completes and finds"), message keys by what they are about ("Delivers order
   and refund messages"); at most three, the rest "and others", never "and N more". Path
   segments that say nothing (`api`, versions, `internal`, `rest`, `public`) are skipped. The
   routes, queues and methods stay on the line's facts and in the tables. A line merging several
   requests or method calls with different phrases and no majority keeps ADR 0103's line intent
   (amendment for #810) or, failing that, this summary; other lines the phrase most links state.
2. **A person's line reads what they do.** With no intent for a frontend, it is the first clause
   of Describe's sentence about the person, on the container and context diagrams. Over eight
   words it ends before the first word that opens a qualifier ("submitted by …"), never mid-phrase;
   with none such it falls back to the static label.
3. **A label does not name the box it points to.** A closing phrase naming the end (its name, the
   name with dashes, dots or underscores as spaces, a kind noun the name ends with such as
   "gateway", or a product word of its technology such as PostgreSQL or Keycloak) is dropped when at
   least two words are left: "Keeps invoices in PostgreSQL" into PostgreSQL is "Keeps invoices".
   Into a topic, queue or exchange, a closing "to the custody exchange" goes too.
   This is done in the composer, deterministically, not by a new LLM check with raised prompt
   versions as #933 first asked: the composer sees both ends, and a prompt change would re-ask
   every stored answer for a rule a regular expression keeps.
4. **No blank box.** A box whose sentence was rejected, or that has none, shows Lantern's plain
   wording: a domain or area "Holds a, b, c and 4 others.", a unit its module's summary when that
   was not rejected, else "A deployable unit that calls X, uses Y, sends to Z and receives from W."
   from its links (at most three of each, units named in words), else "A deployable unit; what it
   does is not known.", each citing its facts. This amends ADR 0101's "a box without a sentence".
5. **Readable names.** The C4 report draws a service or frontend whose name is a build's name
   (`edge-gateway`, `customer-portal-bff`, `billing`) in words ("Edge gateway", "Customer portal
   BFF", "Billing": known acronyms in capitals), its page titled the same, and writes the
   build's name as the element's `technicalName` (an optional property of the report spec), shown
   small on the technology line and in the tables. Title-casing is the fallback; a readable name
   written with the description replaces it when a role provides one. A unit's database named by
   a datasource key keeps only the key's telling words: "workorder (billing workorders)". Since
   #946 a name Describe wrote comes first (amendment below).
6. **The report's checks fail on each.** On the C4 report the quality report fails a line labelled
   with routes or "and N more", a label naming the box it points to, a service or frontend whose
   name stays a build's name once made readable, and a page or box name over 45 characters
   ("about 40": a flow title's five words, `prompts/flow-narrative.task.txt`, fit). A bare "Uses"
   already fails the intent check; a box with no sentence the responsibility check.

## Amendment for #944: lines into one box with one label

Lines from several boxes into one target with the same label say what each does there:

- A component's data access into a relational store (its technology names SQL, PostgreSQL, Oracle,
  MariaDB, H2, DB2, Derby, HSQL, CockroachDB or JDBC; never a cache or a NoSQL store, whose lines
  keep the unit's phrase) names the tables it reads or writes, or the types its repositories are
  for, as many: "Reads orders" and "Reads unpaid invoices", not "Keeps shop data" twice. This
  replaces the unit's intent Describe stored for the store on those lines only; at most two
  names, then "and others", within 45 characters.
- A line of method calls names what only it calls: one method in words ("requestDiagnosticSnapshot"
  is "Requests diagnostic snapshot", "findByVehicleVinOrderByCreatedAtDesc" "Finds by vehicle
  vin", acronyms kept, at most five words, no ordering, locking or trailing condition words),
  several by their verbs; a line calling nothing the others do not keeps its label.

A line of one method reads as that method in words whether or not another shares its label. Lines
nothing tells apart (one method called from two places) keep their label, and on a component
diagram the C4 report's quality report fails on them: "No two lines into one box share a label".
Container views are not checked: several units may well publish to one broker with one purpose.

## Consequences

Labels and names change on every C4 report; the scorer matches names lower-cased and dashed, so
its matching is unchanged. No prompt changed, so no stored answer is asked again.

## Amendment for #946: readable names written by Describe

Title-casing cannot know a better name ("telematics-ingest" reads "Telematics ingest"). Describe's
unit request now asks, for a unit whose name is a build's name of words (`edge-gateway`), its name
in words, 1 to 4 plain words, keeping the build's own words where they already say what it is; the
item says so ("Its name is a build's name: also write its name."), and the answer's `name` field
is checked against its contract like every text and stored as the unit's `display-name`, citing
what it cites. The unit role's prompt version is 9, so every unit is asked again once.

The composer keeps the unit's name as the code gives it (scoring, links and every other text read
it) and carries the written name as the document entity's optional `readableName`, unless the
support judge rejected it. The C4 report draws it on the box and the page, the build's name small
beneath as `technicalName`; with none it title-cases as before. The scorer matches a box by its
`technicalName` when it has one, so a name in words changes no match.

