# Reply to API-DOCS-HANDOFF.md

## Status

All 5 asks closed. The branch is in a complete-and-correct state awaiting merge.

## Per ask

### Ask 1: scaladoc text contract

Commit `f5680a0a6`. The contract is documented in the `sealed trait Symbol` scaladoc
at `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:2859-2866` and mirrored in
`kyo-tasty/README.md` (Phase 05). Verbatim text in the "Scaladoc contract on
Symbol.scaladoc" section below.

### Ask 2: extension-method representation

Commits `8811b9775` and `66cba4970`. Both halves of the contract now hold:

- `Symbol.Method` with `isExtension == true` is owned by the enclosing object and
  appears in `members(companion, Declared)`. Regression-pinned by
  `CollectionInvariantsTest` and `ParamListIdsPopulationTest`.
- `paramListIds.head` is a non-empty `Chunk[SymbolId]` on real cold-loaded and
  snapshot-loaded extension methods. `paramListIds.head.head` resolves via
  `Tasty.paramLists` to the receiver `Symbol.Parameter`. The receiver's
  `declaredType` is `Type.Applied(Type.Named(maybeId), ...)` for generic opaque types;
  strip the `Applied` wrapper before calling `Tasty.typeSymbol`. Confirmed by
  `KyoMaybeSmokeTest` against real `kyo.Maybe` from a live classpath.
- Same applies to `implicit final class Ops[A](maybe: Maybe[A])`: the primary
  constructor `<init>` has the `maybe` receiver as `paramListIds.head.head`.

### Ask 3: kyo-data Maybe smoke test

Commits `e0fbbae66` + `8811b9775`.

- `kyo-tasty/shared/src/test/scala/kyo/ParamListIdsPopulationTest.scala`:
  cross-platform (JVM / JS / Native), uses embedded `kyo.fixtures.Meters`
  (structurally identical to `kyo.Maybe`). Asserts: OpaqueType found, companion
  symmetric, extension has non-empty paramListIds, receiver resolves to Meters.
- `kyo-tasty/jvm/src/test/scala/kyo/KyoMaybeSmokeTest.scala`: JVM only, loads real
  `kyo.Maybe` from `TestClasspaths.standard`. Asserts the full chain including
  `scaladoc` Present on extension methods.

All leaves green across all three platforms.

### Ask 4: surface stability + paramLists helper

Commit `f3d438d14`. Every name in the API audit remains stable. `Tasty.paramLists`
is a new addition, not a rename. The `KyoTastyDoctestVerifyTest` compile-time probe
confirms the exact type:

```
Tasty.paramLists(null: Tasty.Symbol.Method)
// type: Chunk[Chunk[Symbol.Parameter]] < Sync
```

`kyo-tasty/shared/src/test/scala/kyo/KyoTastyDoctestVerifyTest.scala` leaf
`paramLists_signature_probe` (added in Phase 04) is the regression guard.

### Ask 5: TASTy version

No commit needed. `Tasty.supportedTastyVersion = Version(28, 8, 0)` at
`kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1295`. `scala3Version = "3.8.3"` at
`build.sbt:10`. Green test runs against `TestClasspaths.standard` (which includes the
3.8.3 scala-library jar) are the standing proof of no `TastyError.UnsupportedVersion`.

## Wire format

Snapshot format bumped from minor 11 to minor 12 (commit `66cba4970`). Cached minor-11
snapshots return `TastyError.SnapshotVersionMismatch` on first access; the caller
re-decodes from cold TASTy. New reads produce minor-12 snapshots carrying the
`PLISTS__` section. If the website build uses `Tasty.withClasspath(roots,
Present(cacheDir))`, any pre-existing `.krfl` files in `cacheDir` will be regenerated
on first run after upgrading to this branch. No other migration step is needed.

## New public surface

```
// kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:797
def paramLists(method: Symbol.Method)(using Frame): Chunk[Chunk[Symbol.Parameter]] < Sync
```

Example:

```
Tasty.withClasspath(roots) {
    Tasty.requireClass("kyo.Maybe").flatMap: maybeClass =>
        Tasty.companion(maybeClass).flatMap: comp =>
            Tasty.members(comp).flatMap: members =>
                val ext = members.collect {
                    case m: Tasty.Symbol.Method if m.isExtension && m.name.asString == "get" => m
                }.head
                Tasty.paramLists(ext).map: lists =>
                    lists.head.head
                    // the receiver: Symbol.Parameter for `self: Maybe[A]`
}
```

## Scaladoc contract on Symbol.scaladoc

Verbatim from `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:2859-2866`:

> `scaladoc` returns the RAW TASTy comment text, including the `/**` opening
> delimiter, the `*/` closing delimiter, and any inner `*` margin characters dotty
> recorded at compile time. The string is the UTF-8 bytes copied verbatim from the
> Comments TASTy section; no stripping, trimming, or reformatting is performed.
> Callers that need cleaned prose, `@param` / `@return` extraction, or markdown
> rendering must perform those steps themselves. Returns `Maybe.Absent` when no
> scaladoc was recorded for the symbol. `Symbol.TypeParam`, `Symbol.Parameter`, and
> `Symbol.Package` always return `Maybe.Absent`; every other symbol kind carries
> whatever comment the compiler recorded.

## Outstanding

**Multi-parameter-list fixture gap** (documented in
`kyo-tasty/handoff-fixes/phases/phase-03/decisions.md` Leaf 5):
`SnapshotParamListsRoundTripTest.multi_list_method_roundtrip` is `.ignore`'d because
no curried method exists in the embedded fixture set. To activate: add
`def curried(a: Int)(b: Int): Int = a + b` to
`kyo-tasty-fixtures/shared/src/main/scala/kyo/fixtures/FixtureClasses.scala`,
re-embed the TASTy bytes, and remove the `.ignore`. This does not affect the
contract delivered to the website agent; `paramListIds` shape for multi-list methods
is verified in `ParamListsHelperTest` using synthetic symbols.

No other outstanding items.
