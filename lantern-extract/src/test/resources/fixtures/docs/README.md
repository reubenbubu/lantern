[![Build](https://ci.example.com/badge.svg)](https://ci.example.com) [![Licence](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)

Orders
======

Orders keeps the orders of the shop
and publishes an event for each one.

<!-- Maintainers: keep this list in sync with the modules. -->

## Modules

- `orders-api`: the REST API
- `orders-service`: the deployable
  application, packaging `orders-api`

| Module | Database |
| --- | --- |
| `orders-service` | PostgreSQL |

## Building

```bash
# not a heading
./mvnw verify
```

> Orders never calls another service synchronously.

---

See [the decisions](docs/adr/).
