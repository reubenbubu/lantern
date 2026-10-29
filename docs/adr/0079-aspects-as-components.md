# 0079: Aspects as components

Status: accepted (#264), extends ADR 0035, pending the user's review (#24)

## Context

Access checks in Spring systems often live in AspectJ aspects, not in the code that handles a
request. In project-4 a shared `PermissionControlAspect` (for `@RequiresPermission`) and
Main-Service's `OrganizationMembershipControlAspect` decide who may call each endpoint, yet the
Spring extractor ignored `@Aspect`, so the components that enforce access were missing from the
component views, and nothing could state where authorization is decided.

## Decisions

- **Facts.** The Spring extractor (version 2) records, for each class with `@Aspect` and at least one
  advice method (`@Before`, `@After`, `@AfterReturning`, `@AfterThrowing`, `@Around`):
  - `aspect` on the type: the kinds of advice, the pointcut expressions as written and, when the
    pointcuts select by annotation, the annotations (`@annotation`, `@within`, `@target`).
  - `ADVISES` from the aspect type to each method of the analyzed code its advice selects, with the
    advice method, its kind and the pointcut. It is read across modules and repositories, since an
    aspect in a shared library advises code in a service.
- **Pointcut reading.** A small reader (`AspectPointcuts`) understands `@annotation`, `@within`,
  `@target`, `within` and `execution` (the declaring type and method name; return type, modifiers
  and parameters are ignored), named `@Pointcut` methods (own or `Type.name()`), and `&&`, `||`,
  `!` and parentheses. A binding such as `@annotation(requiresPermission)` is resolved through the
  advice method's parameter of that name. Any other designator (`args`, `this`, `bean`, ...) neither
  selects nor excludes unless nothing else in the term is known, which selects nothing. Only
  non-constructor methods of classes are candidates, and aspects are not advised.
- **Composition.** In a unit's component view (ADR 0044), an aspect that advises an entry point of
  the unit (a controller, listener or scheduled job) is a component of its own, "Spring AOP aspect",
  with a line "Advises @Annotation" to each component it advises. The beans only it uses join it,
  as for any entry point. An aspect declared outside the unit's code, such as a shared library the
  linker did not match to the unit (#265), counts only when its pointcuts select by annotation,
  since an aspect that selects by name pattern is not shown to run in the unit.
- **Not covered:** XML-declared aspects, `@DeclareParents`, annotations inherited from interfaces
  (`@annotation` matches only the method's own annotations, as AspectJ does), and whether Spring
  AOP can proxy the method: there is no visibility filter, so private, final, static or
  self-invoked methods are not told apart. An `execution` pattern names the declaring type, so a
  pattern on an interface does not select the methods its implementers declare. `@target` is read
  as `@within` (the annotation on the declaring class, not the runtime class). A malformed
  pointcut selects nothing.
