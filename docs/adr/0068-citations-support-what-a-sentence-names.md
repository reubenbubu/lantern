# 0068: Citations support what a sentence names

Status: accepted (#191), amends ADRs 0010, 0050, 0061, 0065 and 0066, pending the user's review (#24)

## Context

The checks of ADRs 0010 and 0050 look at whether citations exist: since ADR 0061 every summary
sentence must cite something, and each Describer text must cite facts of its prompt. Neither
looks at what is cited, so a sentence naming a class, service or technology that none of its
cited inputs mention still passes: "Orders are cached in Redis [F3]" where F3 is a REST endpoint.
Issue #191 asks for a deterministic check, with no extra LLM call, that every code-like name or
technology in a sentence appears in the text of what it cites, for summaries and Describer
answers, with failing sentences dropped or the answer retried.

## Decisions

- **What a text names** (`Grounding`, `lantern-summarize`, which the composer already uses): a
  span in backticks; a word with a capital after a lower-case letter (`OrderService`, `orderId`,
  `PostgreSQL`), with a dot between identifiers (`com.example.shop`, `order.created`, not `e.g`),
  with an underscore inside (`order_items`), ending in `()` or starting with a slash and a letter
  (`/api/orders`); and the technologies of a fixed list of some 50 databases, brokers, frameworks
  and platforms (Kafka, Redis, Spring, AWS, ...), matched case-sensitively as the list writes
  them. Plain words, words in capitals such as `HTTP`, and hyphenated words such as `shop-core`
  are not checked: they are too often plain English, and a false match would drop good text.
  Citations are not part of the text.
- **What supports a name**: the text of each input the sentence cites, as its prompt writes it
  (a fact's line, a source file, a part's summary with its name), and what the text is about: the
  unit's name for a summary (`type Order in package com.example`), the unit's or element's
  heading for its sentence, the component's or relationship's line for theirs, and, for a flow's
  texts and the system's purpose, everything the prompt shows besides fact lines (the flow's
  title, trigger and steps; the units' names and sentences and the READMEs' paths). A name is
  found ignoring case and every character that is not a letter or digit, so `OrderService`
  appears in `type:com.example.OrderService`; a trailing `'s` is ignored and a plural `s` may be
  missing; a technology also appears under its other names (`postgres` for PostgreSQL, `rabbit`
  or `amqp` for RabbitMQ). It is a bound, not proof: a cited fact that mentions Redis does not
  show that the sentence says something true about it.
- **Dropped, as in ADR 0061.** A summary sentence naming what its citations do not support is
  left out like one that cites nothing, after the whole answer passes the checks of ADR 0010, and
  logged at info level with the names. A Describer text (a unit's, component's, data store's or
  destination's sentence, an intent phrase, a flow's walkthrough or conditions, the system's
  purpose) is left out as if the model had left it out: the element keeps its fallback (ADRs
  0050, 0056, 0058, 0060), and its answer is cached without it. Each text of a Describer answer
  comes with its own citations, so the text is the unit that is checked; a walkthrough is not
  split into sentences. No second call is made for what is left out.
- **A retry only when nothing is left**, as in ADR 0061: a summary none of whose sentences is
  kept, or a Describer answer all of whose texts are left out, is sent back once, naming what was
  not supported. An answer that leaves everything out itself (`{}`) is still accepted.
- **A purpose whose first sentence is left out is inferred**, whichever check left it out (ADR
  0061, ADR 0059).
- **The prompts say so.** Each system prompt adds that a text naming a class, method, table, topic,
  path or technology that none of the references it cites mention is left out. `Prompt.VERSION` is
  11 and each of `WordingPrompt`'s four versions is 3 (ADR 0067 had raised them to 10 and 2), so
  every cached summary and wording, made without the check, is made again once.
- **A summary sentence left out by this check lowers its confidence** like one that cites
  nothing: it counts among the sentences left out (ADR 0066).

## Not verified

How often real models name what they do not cite, and so how much text is left out, needs real
calls, which tests do not make. The fake models of the tests name only what they cite, so the
golden documents do not change; the summary prompt golden file does.
