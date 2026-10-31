# 0002: Vocabulary registry details

Status: accepted (#4), amended by ADR 0026 (#86), by #90, by #156 and by ADR 0090 (#518: key attributes, `INTERACTS_WITH`, `actor`)

## Context

`docs/design.md` (Knowledge model and schema evolution) says each vocabulary type has a parent
type, an attribute schema, a C4 level and default presentation, that fact attributes are validated
against the vocabulary, and that every change is versioned. It leaves the exact shape open. Issue
#4 implements the registry, so the shape has to be fixed now. The authoritative form of a type is
`lantern-core/src/main/resources/schema/vocabulary-type.schema.json`; this record explains the
choices.

## Decisions

- **Kinds.** A type is an `entity`, `relationship` or `attribute` type, the three the design names.
  Entity type names have the type form of an entity ID (`batch-job`); relationship and attribute
  type names have the predicate form (`CALLS`, `owner`). All kinds share one namespace. A parent
  must be of the same kind, and parents cannot form a cycle.
- **C4 level** is one of `context`, `container`, `component`, `code`, or absent. **Presentation**
  is a default `shape` and `icon`, each a lowercase token (`box`, `dashed-arrow`) whose meaning the
  renderer defines later. C4 level, shape and icon are inherited from the nearest ancestor that
  sets them, which is how a new `batch-job` extending `container` renders as a container at once.
- **Attribute schema** is a small subset of JSON Schema: `type: "object"`, `properties`, `required`
  and `additionalProperties` (true when left out, as in JSON Schema). Each property has a `type`
  (`string`, `integer`, `number`, `boolean`, `array`, `object`) and optionally a `description`,
  string `enum` or array `items`. Lantern checks this subset itself, so no JSON Schema validator
  dependency is needed; unsupported keywords are rejected rather than ignored. A type's effective
  schema is its own merged with its ancestors': declared and required attributes are combined,
  undeclared attributes are refused if any type in the line refuses them, and an attribute may
  not be declared differently from an ancestor.
- **Validation on write.** `FactRepository.save` checks every fact against the latest vocabulary
  revision: the predicate must be a relationship or attribute type, and the attributes must match
  its effective schema. Unknown predicates are refused. Entity types and object values are also
  checked since #26; see the amendment below.
- **Revisions.** Revision 0 is the empty vocabulary; each change that alters anything creates the
  next revision (1, 2, 3, ...) with its source (`built-in`, a plugin, a user) and time. A revision
  stores the new definition of each type it changed, so the vocabulary as of any revision can be
  read back. A change that alters nothing creates no revision. Writers take a table lock, so
  revisions have no gaps or races.
- **Changes are additive, except that the parent may change.** The design's versioning rules
  allow additive changes: an existing type keeps its kind, keeps every declared attribute
  unchanged, gains no required attributes and does not start refusing undeclared ones. It may
  declare new optional attributes, and descriptions, C4 level and presentation may change.
  Declaring a new attribute on a type that allows undeclared ones constrains values that were
  accepted before (and so for its open descendants); this is still treated as additive, because
  declaring attributes is how the vocabulary grows. Since #90 an existing type may also get another
  parent, or none, as a normal new revision (it was refused before). The type keeps its name, so no
  alias is needed. The new parent must exist, be of the same kind and not create a cycle, and no
  type, the ones below it included, may accept less than before through the new parents, so stored
  facts stay valid. Older revisions still read back with the old parent. Removing and renaming
  types (deprecation with an alias) are not implemented yet.
- **Built-in types** are defined in `lantern-core/src/main/resources/vocabulary/built-in-types.json`
  and applied when the app starts, before anything can save facts; unchanged built-ins create no
  revision. Besides the entity types the issue names (system, service, library, module, package,
  type) they include the C4 parents `container` and `component`. Only `service` extends
  `container`. Since #90 `library`, `module` and `package` have no parent and so no C4 level, as in
  the design (a library is not a container, a module or package is not a component); they set
  their own shape. A database that holds the earlier built-ins gets the change as one new revision
  at startup. Relationship types are `CONTAINS`, `DEPENDS_ON`, `CALLS` (extends `DEPENDS_ON`),
  `EXTENDS` and `IMPLEMENTS`. Built-ins declare no attributes and refuse undeclared ones. Since
  #156 only the built-in source may change them, so extractors define types of their own (see the
  amendment below).
- **Java types** are hand-written records in `io.lantern.core.vocabulary`, with name constants for
  the built-in types in `BuiltInTypes`, kept in line with the schema and the built-in file by
  tests, as for the fact envelope (0001).

## Amendment (#26): entity types and object values are checked

- **Entity types.** Saving a fact also checks that its subject's type, and an entity object's
  type, is an entity type of the latest revision. A fact about an entity of an unknown type is
  refused, like one with an unknown predicate.
- **Object by predicate kind.** A relationship fact's object must be an entity, and an attribute
  fact's object a value (string, integer, decimal or boolean), as the kinds' definitions say.
- **Object kind.** An attribute type may set `objectKind` (`string`, `integer`, `decimal` or
  `boolean`, the value kinds of the fact envelope) to fix the kind of its facts' objects; it is
  inherited like the C4 level, and a type may not set one that differs from the one it inherits.
  Without one, any value is allowed. Kinds must match exactly: an `integer` object is not accepted
  where `decimal` is set, since the envelope keeps them apart. Entity and relationship types have
  no object kind. The extractors' attribute types (`version`, `packaging`, `kind`) set `string`.
- **Object kind changes.** Setting an object kind on a type that has none (of its own or
  inherited) is additive in the same sense as declaring a new attribute on an open type: it
  constrains new facts only. Once a type sets one, or inherits one, it may not change or drop it,
  including through a new parent.
- **One lookup per batch.** `FactValidator.validateAll` checks a batch against one vocabulary
  revision, so `FactRepository.recordSnapshot` reads the latest revision number once per batch
  instead of once per fact. A single `save` still reads it once.

## Amendment (#156): a type belongs to the source that first defined it

- **Context.** Type names are global, so one source could change a type another source defined: a
  test's `topic` silently replaced the linker's description, and an additive change by any source
  passed the version rules unnoticed.
- **Owner.** A type belongs to the source of the earliest revision that holds it
  (`VocabularyRegistry.owner`). It is read from the existing `vocabulary_revisions` and
  `vocabulary_types` tables, so nothing is stored or migrated and type names are unchanged.
- **Only the owner changes a type.** Applying a definition that differs from the current one of a
  type another source owns is refused, even if the change is additive, with an error naming the
  type, its owner, the other source and both definitions; nothing in that change is stored.
  Another source may apply the identical definition, which changes nothing and creates no
  revision, but sources must not repeat another source's types even unchanged: once the owner
  changes the type, the copy differs and the other source is refused. The owner's changes
  still follow the rules above. Built-in types belong to `built-in`.
- **Clashes fail at startup.** The app applies the built-in types and then each extractor's and
  the linker's types when it starts, so two sources defining the same name differently stop the
  app with that error instead of one silently winning. Namespacing names per source was not chosen:
  it would rename stored types.
