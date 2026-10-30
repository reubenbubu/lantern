# 0087: One intent per line, frameworks in the technology line, exchange types

Status: accepted (#276, part of 2C), amends ADR 0039, ADR 0044 and ADR 0050, pending the user's
review (#24)

## Context

A container line was labelled with up to two of its links' intent phrases and "and N more"
("Creates a new organization role; deletes ... and 5 more"), which reads as a list and not as one
thing the line does. A gateway's technology said only "Spring Boot 3.5 / Java 21", though its
framework is Spring Cloud Gateway, and an exchange was drawn as "RabbitMQ exchange" although the
messaging facts know whether it is a topic, direct or fanout exchange. The hand-made references
(#251) say all three.

## Decisions

- **One intent per line.** When every link of a line has an intent phrase, the line's label is one
  phrase: the one most of its links say, the first alphabetically on a tie. The other phrases are
  not repeated in the label; they stay reachable through the facts the line cites, whose intent
  phrases the document keeps. A line with a link that has no phrase keeps its static label (what
  the links record, with up to two details and a count of the rest, such as paths). This amends
  ADR 0050.
- **Frameworks in a unit's technology.** The deployable-units step (version 3) adds the frameworks
  the unit's modules depend on to its technology fact, after Spring Boot and before Java: Spring
  Cloud Gateway, Eureka Server, Spring Cloud Config Server, WebFlux (left out when the gateway is
  there, which is built on it) and WebSocket, each with the version the dependency states, if any (not for Spring Boot's own starters, which are at its version)
  ("Spring Boot 3.5.14 / Spring Cloud Gateway 4.3.0 / Java 21"). The fact's `frameworks`
  attribute lists them. This amends ADR 0039. Frameworks are found from dependencies, so a unit
  without a build shows them only when its POM states them.
- **Exchange types.** An exchange's technology line is the broker, the exchange type its bindings
  state and "exchange" ("RabbitMQ topic exchange"); an exchange no binding is known for stays
  "RabbitMQ exchange". This amends ADR 0044.
