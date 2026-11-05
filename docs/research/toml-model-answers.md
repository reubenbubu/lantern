# TOML for model answers: the library, and what models get wrong

5 Nov 2026, for #872.

**Depth: medium.** How a TOML library reads text into a tree, and what the TOML syntax allows, are
documented practice, not an open design question. Researched by a read-only `sonnet` subagent
(six pages read), then checked by tests of the chosen library on answer shapes models write
(`TomlTest`). Whether TOML pays for Lantern's answers is not settled here: it is measured on the
Summarize test (#871), since the only general source found says the opposite for generic data.

## Question

Lantern's Summarize role is to send its request data and take its answers in TOML instead of JSON,
to cut output tokens (the owner's decision, 5 Nov 2026). Which Java library should read a model's
TOML into the Jackson tree the checks already read, and write request data? Which mistakes do
models make in TOML that the reader and the prompt must handle?

## Sources

- Maven metadata of `tools.jackson.dataformat:jackson-dataformat-toml`,
  https://repo1.maven.org/maven2/tools/jackson/dataformat/jackson-dataformat-toml/maven-metadata.xml.
  Read. A Jackson 3 artifact exists in the 3.0, 3.1 and 3.2 lines.
- The same artifact on Maven Central,
  https://central.sonatype.com/artifact/tools.jackson.dataformat/jackson-dataformat-toml. Read.
  Apache-2.0, released every few weeks.
- The `toml` module of jackson-dataformats-text, branch 3.x,
  https://github.com/FasterXML/jackson-dataformats-text/tree/3.x/toml. Read. `TomlMapper` reads
  and writes at the streaming and tree levels. The README is stale: it still calls the module
  experimental and names no TOML version.
- Its release notes,
  https://github.com/FasterXML/jackson-dataformats-text/blob/3.x/release-notes/VERSION-2.x. Read.
  The TOML module keeps receiving fuzzing fixes, limits on nesting and length, and writer fixes.
- tomlj, https://github.com/tomlj/tomlj. Read. `org.tomlj:tomlj:2.2.0`, Apache-2.0, TOML 1.1 by
  default. It collects every error with its line and column instead of throwing. Its tree is its
  own type, so it needs a conversion to Jackson, and it has no writer.
- TOML v1.0.0, https://toml.io/en/v1.0.0. Read through a summary. Rules for basic and literal
  strings, duplicate keys and tables, and arrays of tables.
- "LLM data formats", https://nathom.github.io/llm-data-formats. Read. On random nested data, TOML
  took about 44% more tokens than JSON. Answers in TOML scored about as well as JSON, and better
  than YAML. One author, no exact figures.
- Not read, so no weight: web-search snippets on TOON and token efficiency (Medium, Cotera,
  ToolScan, Reddit); night-config and toml4j.

## Evidence from Lantern

The owner's local runs (outside the repository, 5 Nov 2026) asked a local model for 10 type
summaries in each format. The TOML answers took about 1,050 to 1,300 output tokens, against about
1,760 to 2,060 for JSON. The saving comes from the answer's shape: per sentence, JSON repeats
quoted keys, braces and commas inside nested arrays, and TOML writes `text =` and `cites =` once
each. That differs from the random data of the source above. The measure that counts is the
Summarize test (#871) on Haiku 5.5 through OpenRouter, before and after.

## Options

| | jackson-dataformat-toml 3.x | tomlj 2.2.0 |
| --- | --- | --- |
| Fits the checks | `readTree` gives the `JsonNode` the schema checks read | needs a `TomlTable` → `JsonNode` conversion |
| Writes TOML | yes | no |
| Versions | managed by the Jackson BOM Spring Boot already pins (3.1.7 here) | one more version to keep |
| Errors | an exception with line and column | every error with its position |
| License, upkeep | Apache-2.0, the Jackson team, hardened by fuzzing | Apache-2.0, quiet but kept |

## What models get wrong, and how Lantern handles it

1. **An unescaped quote or backslash in a basic string.** The read fails. The prompt says to write
   `\"` and `\\` inside a text. The repair turn names the fault and its line.
2. **Literal and multi-line strings.** They are valid, and the reader accepts all four forms.
3. **A table written twice.** It is invalid in TOML. The read fails and goes to the repair turn;
   it is never merged silently.
4. **A fence, or prose before or after the document.** The reader takes the fenced block. It
   leaves out prose before the first table or key, and up to five lines of prose after the
   document, and logs both, as JSON answers already do (#744).
5. **Item IDs as table names.** `[I1]` is a bare key and needs no quotes. Lantern controls the IDs.
6. **An inline table on one line** (`sentences = [{text = …, cites = […]}]`). It is valid, and is
   read.
7. **Numbers for cites.** The schema check rejects them. The prompt says cites are quoted strings.
8. **Repository text in the request.** A literal string cannot hold `'''`, and code may. Lantern
   writes such text as an escaped multi-line basic string, so no file content can end a string
   early and pass for the request's own structure. A test reads every kind of text back exactly.

## Recommendation

Use `jackson-dataformat-toml`, at the version the Jackson BOM gives. It reads straight into the
tree the checks already use and writes the request data, with no conversion code, and it is from
the same team and under the same license as the rest of Lantern's Jackson. Fall back to tomlj only
if its error messages prove too poor to repair from. They did not: a fault names its line, and the
repair turn passes it on.

A TOML answer counts as readable for a stored drop as a JSON object does (#744). A JSON answer is
still read, for a model that answers in JSON anyway.

## What it changes

`docs/design.md`, LLM integration: Summarize asks for and reads TOML. ADR 0100, amendment for #872:
the answer format per role, the common prompt's two format words, and what counts as readable.
