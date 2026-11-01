# 0098: Investigate leads derived from the facts

Status: accepted (#524)

## Context

The knowledge-build epic (`docs/epics/001-knowledge-build.md`, section 11.2) splits Investigate
into three modes. The first is driven by leads: deterministic queries over the claims that point
the model at the code of one candidate risk, to confirm or refute. Of project-4's 16 reference
risks, at least 13 have such a trigger. The research for #615 (`docs/research/architecture-model.md`,
decision 8) settled that a lead is a stored claim of origin `derive` with a content hash, and that
its verdict is a separate `llm` claim. #520 added the facts the triggers need (ADR 0096). This record
says how the leads are stored and how each kind's trigger reads the facts, where the epic's table
leaves it open. The agent decided these points under the owner's standing go-ahead for 1D.

## Decisions

- **One derive rule per kind**, `lead-<kind>`, each reading the extracted and linked facts (and,
  for single instances, the stored deployment instances, #617), so each kind reruns on its own
  inputs and has its own version. The catalog is data, `lantern-derive/src/main/resources/leads.json`:
  each kind's trigger and what the model judges, for the Investigate prompt (#269); a test keeps it
  and the rules in step.
- **A lead** is the entity `lead:<kind>/<key>` with a `lead-trigger` fact whose value is the kind:
  `trigger` (why it fired, one sentence from the facts), `units`, `code` (the entity IDs of the code
  or descriptors to read), `content` and `facts`. All but `facts` are key, by the epic's rule. The
  key is the kind's own (below), percent-encoded where it holds whitespace.
- **`content`** is `sha256:` and a base64url SHA-256 of the cited claims and the content hashes (or
  blobs) of the files they were read from, and of the code entity IDs. A change to the code it
  points at changes the claim, so the verdict, keyed on the lead's claim, is asked again; nothing
  else does. It is base64url so no check reads it as a claim or fact ID.
- **Keys and triggers, per kind:**
  - `identity-from-request`: per unit and value (`<unit>/<source>:<name>`), citing every read of
    it in the unit's code. The model judges a unit's handling of a header, not each read. Names
    match after an optional `x`.
  - `send-before-commit`: per unit and sending method. The call graph is followed from every method
    of a read-write transaction (an interface's method reaches its implementations), stopping at a
    `@TransactionalEventListener`, an `afterCommit` method or a class whose name says outbox.
  - `contract-mismatch`: per caller and call. A call between units that carries a path and matched
    neither an endpoint nor a route, to a unit with endpoints that is not a gateway; and a
    frontend's call to a gateway that matches an endpoint no route reaches (`unrouted-endpoint`), or
    no route's path pattern and no endpoint of the gateway at all. A gateway is a unit with route
    links or route reach facts. A method the endpoint does not serve already leaves the call
    unmatched, so GET-for-POST is found the same way.
  - `weak-access-check`: per unit and endpoint method. A permission "ends in READ or VIEW" when a
    word of the check is READ or VIEW, or ends in `_READ` or `_VIEW`; a check on the method's class
    counts.
  - `shared-type-skew`: per library and type, when the units passing the type pin at least two
    versions of the library (`version-skew`'s pins).
  - `single-instance`: per entity the stored deployment instances run, when every container behind
    them has replicas 1 or unset and two or more units link to it; and per unit with a STOMP simple
    broker and no relay.
  - `plaintext-secret`: per masked config value, per shared signing secret, and per Kubernetes
    Secret with `stringData`.
  - `environment-baked`: per frontend and value, for a value from its default build's environment
    file or its code.
  - `open-cors`: per policy whose origins are `*`, also as a placeholder's default.
  - `schema-unmanaged`: per module and profile (not one whose name says test), when the module's
    schema has no Flyway or Liquibase `kind`.

## Consequences

- No document changes: nothing draws leads yet. Investigate (#269, milestone 1F) asks about them;
  the refuted ones stay stored.
- project-4 gets a lead for each triggerable reference risk; project-0, whose cases leave nothing
  to suspect, gets only the two single-instance services its Compose file runs once
  (`Project0EndToEndIT`).
- Gaps: a library's events passed through Spring Cloud Stream functions carry no `payload-type`
  (ADR 0096), so project-8's shared-library skew raises no lead; Compose credentials are not marked
  as secrets by the deployment facts, so they raise none; a parameter mismatch (the right path,
  another query parameter) is not a contract mismatch here.
