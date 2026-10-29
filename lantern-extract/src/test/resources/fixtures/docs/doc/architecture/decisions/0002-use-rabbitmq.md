# 2. Use RabbitMQ for order events

Date: 2024-02-01

## Status

Superseded by [4. Use Kafka for order events](0004-use-kafka-for-order-events.md)

## Context

Other services need to know when an order is placed.

### Volumes

A few hundred orders a day.

## Decision

Orders publishes an event per order to the exchange `orders`.

## Consequences

Orders does not need to know who listens.
