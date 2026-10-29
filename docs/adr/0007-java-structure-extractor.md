# 0007: Java structure extractor

Status: accepted (#9), amended by ADR 0049 (#176), pending the user's review (#24)

## Context

`docs/design.md` (Static analysis and extractors) says the Java structure extractor reads code
through the javac Tree API with annotation processing and the sandbox classpath, falls back to
JavaParser when a build failed, and produces packages, types, members, inheritance and a resolved
call graph, deterministically. It leaves open how these are named, which annotation processors run
where, which calls are kept and how the fallback relates to the compiler.

## Decisions

- **One pass over the snapshot**, like the Maven extractor (0006): `JavaExtractor` in
  `lantern-extract` takes all repositories of a snapshot, because a call is kept only when it
  reaches a method declared in the analyzed sources, which may be in another repository. A later
  extractor cache must key call facts on that set as well as on the files.
- **Which sources.** Modules are found as the Maven extractor finds them (following `<modules>`
  from the top `pom.xml`); each module's `src/main/java` at the commit is read from git (never the
  working tree), plus the generated sources its sandbox build left. Test sources,
  `module-info.java`, symbolic links and submodules are not read. The sources, the generated
  sources and the repository's `lombok.config` files are copied to a temporary directory for the
  run (with a `lombok.config` above them that stops Lombok looking further up) and deleted
  afterwards. A module's ID comes from its
  build when the build has it, else from its POM as written, so it matches the Maven extractor's.
- **Compiler path.** A module whose repository built successfully is compiled in Lantern's own
  process with javac (`JavacTask`, parse and analyze only, no class files written) against the
  module's classpath from the build, with `--release` set to the build's JDK when Lantern's JDK
  can compile for it; if that gives errors it is tried once more without `--release`, since
  `--release` hides JDK-internal APIs the build may use. Facts have confidence 1.0. If javac
  still reports an error, or fails outright, the module falls back to parsing.
- **Annotation processing.** Only Lombok runs, and it is Lantern's own Lombok (the version Spring
  Boot manages), not the copy on the build's classpath. Builds are untrusted (0005): any jar on a
  build's classpath could declare an annotation processor, and the shared Maven cache can be
  poisoned, so running the build's processors would run untrusted code in Lantern's process.
  Other processors (MapStruct, OpenAPI and Protobuf generators) need not run: what they generate is
  in the build's generated sources, which are compiled with the module's own. Lombok is the one
  common processor that changes classes in place rather than generating files. Trade-off: a repo
  whose Lombok version behaves differently from Lantern's may see slightly different members, and
  other in-place processors are not supported.
- **Fallback path (degraded mode).** A module without a successful build, without outputs in its
  build, or that javac reports errors for, is parsed with JavaParser and its symbol solver.
  Types resolve against the JDK Lantern runs on, the main sources of every repository in the
  snapshot (its own first) and whatever classpath jars the failed build left for the module.
  Facts have confidence 0.7. Lombok's members are missing, calls that do not resolve are left out,
  and a parameter or supertype that does not resolve is named from a single-type import, or else
  as if it were in the same package; a type variable is named as its first bound, or
  `java.lang.Object`. A class with a Lombok annotation that adds constructors (`@Value`, `@Data`,
  `@Builder`, `@SuperBuilder`, `@AllArgsConstructor`, `@RequiredArgsConstructor`) gets no implicit
  default constructor. A file the parser cannot handle is left out.
- **Same IDs both ways.** The fallback adds the members javac declares implicitly (default
  constructors; a record's fields, accessors, canonical constructor, `equals`, `hashCode` and
  `toString`), and both leave out an enum's implicit `values()` and `valueOf(String)` and the
  `super(...)` calls javac writes itself. A test checks that both paths give the same facts for
  code that needs neither Lombok nor a classpath, so a module's entities do not change when its
  build is fixed.
- **Entity IDs.** `package:<groupId>:<artifactId>:<package>` for a package of a module, so a
  package split across modules is one package per module and the hierarchy (module, package,
  type) stays a tree; `type:<binary name>` (`com.example.Outer$Inner`); `method:<type>#<name>(<erased
  parameter types>)` with `<init>` for constructors and binary names for parameter types
  (`method:com.example.Order#total(java.lang.String,int[])`); `field:<type>#<name>` for fields
  and enum constants. Types and members are keyed by name alone, not by module, so they keep their
  ID when moved between modules; two modules declaring the same class share one entity. Only
  top-level and member types are entities. Local and anonymous classes and lambdas are not; their
  code counts for the member that encloses them.
- **Facts.** `module CONTAINS package`, `package CONTAINS type` (`module CONTAINS type` for the
  unnamed package), `type CONTAINS type` for member types, `type CONTAINS method|field`,
  `type kind <class|interface|enum|record|annotation>`, `type EXTENDS type` for a declared
  superclass (not `Object`, `Enum` or `Record`) or an interface's superinterfaces,
  `type IMPLEMENTS type` for a class's interfaces (supertypes may be third-party types), and
  `method CALLS method` for method calls, constructor calls (`new`, `this(...)`, `super(...)`) and
  method references whose target is declared in the analyzed sources (or, since ADR 0049, is
  inherited from third-party code by a type the sources declare that the call is written on).
  Calls in field initializers and initializer blocks have the type as subject. Calls into the JDK
  and third-party code are left out, to keep the graph to the system's own code. There is one `CALLS` fact per caller and
  callee, with the first call as evidence. Evidence is the source file with its content hash and
  the declaration's or call's lines; members Lombok generates have the annotation's line. A
  package's evidence is its directory, and a generated source's path is relative to the build's
  work directory (for example `core/target/generated-sources/annotations/...`).
- **New vocabulary**, applied by the app on startup after the Maven extractor's, with source
  `java-extractor`: the entity types `method` and `field` (C4 level `code`) and the attribute type
  `kind`.
- **Not covered yet:** sources outside `src/main/java` (build-helper source roots), test code,
  Gradle builds, and repositories whose code needs a newer JDK than the one Lantern runs on (javac
  reports errors and the module is parsed instead).
