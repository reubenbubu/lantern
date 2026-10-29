---
status: "accepted"
date: 2024-06-12
deciders: Ann, Bob
---
# Use Kafka for order events

## Context and Problem Statement

Order volumes grew and consumers need to replay events. Which broker should carry order events?

<!-- This is an optional element. Feel free to remove. -->
## Decision Drivers

* Replay
* Throughput

## Considered Options

* RabbitMQ
* Kafka

## Decision Outcome

Chosen option: "Kafka", because it keeps events for replay.

### Consequences

* Good, because consumers can replay.
* Bad, because we run a Kafka cluster.
