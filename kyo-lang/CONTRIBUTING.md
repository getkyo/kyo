# Contributing to kyo-lang

This guide complements the root [CONTRIBUTING.md](../CONTRIBUTING.md), which covers the global Kyo conventions. Read it first; this file covers only what is specific to `kyo-lang`. [DESIGN.md](DESIGN.md) is the language specification and the implementation sequence; a change to what the language accepts, rejects, or generates is a change to that document as well.

## Module layout

`kyo-lang` ships as two artifacts from one module directory:

- `kyo-lang/` (sbt `kyo-lang`, cross-platform): the compile-time library a `.kyo` compilation needs on its classpath. `kyo.lang.Checked` is the metadata attached to compiled definitions; `kyo.lang.internal.Markers` and `kyo.lang.internal.Body` are the marker overloads (the pending one is kyo-direct's `.now`) and the typed body macro the plugin inserts, which validates and then lowers through kyo-direct; `kyo.lang.internal.Validate` is the API admission check. The library is cross-built because the compiled output of a `.kyo` file runs wherever its enclosing project runs, and the compile classpath of a JS or Native project carries that platform's artifact.
- `kyo-lang/plugin/` (sbt `kyo-lang-plugin`, JVM only): the scalac plugin. `KyoLangPlugin` registers two phases: `Preparation` runs after the parser and before the typer on `.kyo` units only, and `CheckMetadata` runs after the typer on every unit. The plugin depends on `scala3-compiler` as `Provided`: the compiler loading it supplies itself.

The plugin never references the library's classes directly; it builds untyped trees naming `kyo.lang.internal.Markers.force`, `kyo.lang.internal.Body.apply`, `kyo.lang.internal.Body.field`, and `kyo.lang.Checked` by qualified path. Those paths are the contract between the two artifacts. Renaming one side without the other compiles and fails every `.kyo` compilation.

## The pipeline

```text
.kyo source
  -> Preparation (untyped): markers around expressions, Body around definition bodies, Checked on definitions,
     rejection of unsupported declarations
  -> normal Scala typing: marker overload resolution leaves a .now on each sequenced computation; Body expands as a transparent inline macro
  -> Body: Validate every resolved call and reference, then kyo-direct's restrictions and lowering (kyo.internal.Validate and Lower)
  -> CheckMetadata (typed): a .scala unit declaring Checked is an error
  -> normal compilation
```

Lowering runs inside typing because the body's inferred result type must be the lowered `A < S`. Moving it into a separate phase is not a goal; the design adds a phase only if a demonstrated typing requirement needs one.

## Tests

The module's test configuration compiles `.kyo` sources through the plugin, the same way a user's build does: `shared/src/test/kyo` holds the positive fixtures (`kyolang.Programs`, `kyolang.More`, a top-level definition), and `build.sbt` adds that directory and the `*.kyo` filter to `Test / unmanagedSources` with `-Xplugin` pointing at `kyo-lang-plugin`'s packaged jar. The fixtures live outside package `kyo` on purpose: generated composition derives a `Frame` at the definition's position, and Kyo refuses to derive one inside its own package, exactly as it does for any user code placed there.

- `shared/src/test/scala/kyo/lang/BodyTest.scala` runs on every platform. It checks each exported signature for exact type equivalence through the `Signatures.exact` macro (not assignment compatibility, which could insert a conversion or widen the type) and runs every fixture through ordinary handlers with concrete expected values.
- `jvm/src/test/scala/kyo/lang/` drives `dotty.tools.dotc.Main` in-process through the `Compilation` helper, which does its file work with kyo-system's `Path` under `PathWrite` (a scoped `Path.tempDir` per compilation, `write`, `read`, `mkDir`) and reads its configuration through `System.property`; the only Java API it touches is the compiler's own. The build passes the plugin jar as the `kyo.lang.plugin` system property and the fixture directory as `kyo.lang.sources`, and `kyo-system` is a test dependency for this reason. These suites stay under `jvm/` because their subject, scalac, exists only on the JVM; the fixtures and `BodyTest` they share are cross-platform. `ValidateTest` and `PreparationTest` hold the rejection programs as inline strings next to the exact message each must produce. `CheckedTest` compiles the fixtures into their own package, compiles the second file against the first's output, compiles an ordinary Scala consumer without the plugin against both, runs it through a class loader, and checks that a Scala caller cannot weaken a compiled contract. `KyoLangPluginTest` loads the plugin into the presentation compiler and asks for completions on a `.kyo` buffer.

Every rejection test asserts on the message text. When a diagnostic changes, the test changes with it, and the wording stays specific enough that a reader knows which rule fired.

```sh
scripts/sbt.sh test-jvm 'kyo-langJVM/test'
```

## Adding a construct

The construct inventory in DESIGN.md assigns every Scala construct a policy and an ID. Supporting a new row means, in order: a lowering rule with its evaluation-order and effect-row semantics written down in the CPS rules section; positive fixtures in `shared/src/test/kyo` with runtime assertions and exact signatures in `BodyTest`; the removal of the rejection the construct used to produce and, where the row keeps a restriction, a rejection test for it. A construct stays rejected until all three are in place. Never admit a construct through a generic fallback because a tree scan found no marker in it.

## Admission

`Validate` currently holds a fixed table of admitted symbols: the operations the fixtures use plus the evidence derivations their inline expansions insert. Admission compares resolved symbols; an inline call is checked at the call and its trusted expansion is not re-validated, which is why `Frame.derive` and `Tag.derive` appear in the table. The design replaces the table with the package rule (`kyo.*` without an `AllowUnsafe` requirement) plus an exact external allowlist. Extending the table is appropriate only to let a new conformance fixture compile; the replacement is step 4 of the implementation sequence.
