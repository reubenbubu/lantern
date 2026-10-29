# Version the API in the path

* Status: proposed
* Date: 2024-07-01

Technical Story: ORD-42

## Context and Problem Statement

Clients break when the API changes.

## Decision Outcome

Chosen option: "/v1 in the path", because it is visible in every log line.

### Positive Consequences

* Easy to route.

### Negative Consequences

* URLs change between versions.
