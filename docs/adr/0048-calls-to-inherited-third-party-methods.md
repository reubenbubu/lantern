# 0048: Calls to methods a type inherits from third-party code

Status: accepted (#176), pending the user's review (#24)

## Context

ADR 0007 keeps a `CALLS` fact only when the method called is declared in the analyzed sources. A
Spring Data repository declared in the code (`OrderRepository extends JpaRepository`) inherits
`save` and `findById` from Spring Data, so `orders.save(order)` left no fact: nothing linked the
calling code to the repository, and the sequence flows (ADR 0047) and component views (ADR 0044)
missed the database steps the code takes through these methods. Issue #176 asks for those calls.

## Decisions

- **Recorded against the receiver's type.** A call to a method declared outside the sources is kept
  when it is written on a type the sources declare: a method call or method reference whose
  receiver's static type is such a type (`orders.save(o)`, `this.clear()`, `orders::save`, or a
  type name, `Ticks.systemUTC()`). The callee is the method as a member of that type, with the
  parameters as declared, erased (`method:com.acme.OrderRepository#save(java.lang.Object)`), so the
  existing `CALLS` predicate and method ID scheme carry it; no vocabulary changes. The type gets no
  `CONTAINS` fact for it, since the code does not declare it.
- **Not kept:** methods of `Object`, `Enum` and `Record`, which every class, enum or record
  inherits (`toString`, `name`, `getClass`), as ADR 0007 leaves those supertypes out; calls without
  a written receiver (`size()` in a subclass of `ArrayList`), whose receiver both paths would have
  to work out from the enclosing classes; and calls on a type the sources do not declare.
- **Both paths.** The compiler and the parsing fallback both record the receiver's type, so they
  still give the same facts (`JavaExtractorTest`). Parsing names it when it resolves, as for any
  call.
- **Composition.** A method no type declares belongs to the type its ID names
  (`PackageDependencies.ownerType`), so a call to `OrderRepository#save` is a call to a Spring Data
  repository (`REPOSITORY_FOR`) for the sequence flows, and an edge to the repository's component.

## Consequences

project-0's FLOW-1 gains catalog's and storefront's database steps, `GET /api/orders/{id}` becomes
a flow of its own, and catalog's `ProductRepository`, now reached only from `ProductController`,
is part of its component, so catalog has one component and no component page
(`Project0EndToEndIT`).
