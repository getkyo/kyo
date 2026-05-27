# Phase 5 v2 Prep

**Phase**: G20 -- Wire `Symbol.declaredType` via eager Pass 1 member-type decode  
**Plan reference**: execution-plan-v2.md lines 184-229  
**IMPROVEMENT-ANALYSIS**: G20 section (lines 19-27)  
**Dependency**: Phase 3 complete (SingleAssign pattern established, Phase C arena stable)  
**Date**: 2026-05-25

---

## Verbatim API Signatures

### Public stub to replace

`kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` line 251:
```scala
def declaredType(using Frame): Type < (Sync & Abort[ReflectError]) = stub("Symbol.declaredType")
```

### New internal field (to add, does not exist yet)

Add to the `Symbol` private constructor block alongside `_parents`/`_typeParams`/`_declarations`
(currently at the block ending around line 78):
```scala
private[kyo] val _declaredType: kyo.internal.reflect.symbol.SingleAssign[Type] =
    new kyo.internal.reflect.symbol.SingleAssign
```

### TypeUnpickler.readTypeIntoSession (the session-sharing variant used by Pass 1)

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala` line 98:
```scala
private[kyo] def readTypeIntoSession(view: ByteView, session: DecodeSession): Reflect.Type
```
This is the correct call to use in Pass 1 because it shares the `DecodeSession`'s `liveAddrMap`,
`arena`, and `placeholders` accumulator with the ongoing walk. It is synchronous (throws on error)
and is already used by `decodeOneTypeIfPresent` and `decodeTemplateParents`.

### TypeUnpickler.readType (the public Kyo-effect variant, for reference)

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala` line 60:
```scala
def readType(
    view: ByteView,
    names: Array[Reflect.Name],
    addrMap: Map[Int, Reflect.Symbol],
    arena: TypeArena,
    home: ClasspathRef
)(using Frame): (Reflect.Type, Chunk[UnresolvedRef]) < (Sync & Abort[ReflectError])
```
Do NOT use this in Pass 1; it allocates a fresh `DecodeCtx` and loses the shared placeholder
accumulator. Use `readTypeIntoSession` exclusively inside `walkStats`.

### AstUnpickler.Pass1Result (current shape, after Phase 3)

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala` line 58:
```scala
final case class Pass1Result(
    symbols: Chunk[Reflect.Symbol],
    addrMap: Map[Int, Reflect.Symbol],
    placeholders: Chunk[UnresolvedRef],
    rootSymbol: Reflect.Symbol,
    parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]],
    childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]]
)
```

Phase 5 adds one field:
```scala
    typeBySymbol: Map[Reflect.Symbol, Reflect.Type]
```

### AstUnpickler.walkStats signature (current, after Phase 3)

`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala` line 154:
```scala
private def walkStats(
    view: ByteView,
    end: Int,
    names: Array[Reflect.Name],
    attrs: FileAttributes,
    home: ClasspathRef,
    addrMap: mutable.HashMap[Int, Reflect.Symbol],
    allSymbols: mutable.ArrayBuffer[Reflect.Symbol],
    ownerStack: mutable.ArrayDeque[Reflect.Symbol],
    typeSession: TypeUnpickler.DecodeSession,
    parentsBySymbol: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]]
): Unit
```

Phase 5 adds one parameter:
```scala
    typeBySymbol: mutable.HashMap[Reflect.Symbol, Reflect.Type]
```

---

## File:Line Anchors

| Item | File | Line | Action |
|---|---|---|---|
| `declaredType` stub (replacement site) | `shared/src/main/scala/kyo/Reflect.scala` | 251 | Replace `stub("Symbol.declaredType")` with `SingleAssign` read |
| New `_declaredType` field (addition site) | `shared/src/main/scala/kyo/Reflect.scala` | ~78 (after `_declarations`) | Add `private[kyo] val _declaredType: SingleAssign[Type]` |
| `Pass1Result` (addition site) | `shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala` | 64 (after `childrenByOwner`) | Add `typeBySymbol: Map[Reflect.Symbol, Reflect.Type]` |
| VALDEF type decode (existing anchor) | `AstUnpickler.scala` line ~201 | Add: capture return of `decodeOneTypeIfPresent`/call `readTypeIntoSession`, store in `typeBySymbol` |
| DEFDEF type decode (existing anchor) | `AstUnpickler.scala` line ~219 | Add eager type read before `scanForwardAndCollectFlags` |
| TYPEDEF type-level branch (existing anchor) | `AstUnpickler.scala` line ~289 | Add eager type read after consuming tag, before `readModifiers` |
| TYPEPARAM type decode (existing anchor) | `AstUnpickler.scala` line ~310 | Add capture of decoded bounds type into `typeBySymbol` |
| PARAM type decode (existing anchor) | `AstUnpickler.scala` line ~325 | Add capture of decoded param type into `typeBySymbol` |
| `mergeResults` population site (TASTy path) | `shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` | ~122 (after `_declarations` loop) | Add loop over `fr.typeBySymbol` assigning `sym._declaredType.set(t)` |
| Classfile `_declaredType` population | `ClasspathOrchestrator.scala` | after existing member symbol loop | Wire type from per-symbol classfile data |
| SnapshotReader fallback | `shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotReader.scala` | ~79 (existing pattern) | Add `if !sym._declaredType.isSet then sym._declaredType.set(???)`  -- see Concerns |

---

## Eager Member-Type Decode Strategy in Pass 1

### The existing pattern

`decodeOneTypeIfPresent` (line 354) already reads one type node from `view` via
`TypeUnpickler.readTypeIntoSession` and discards the result. The return value is lost. Phase 5
captures it instead of discarding:

```scala
// Current (Phase 3):
decodeOneTypeIfPresent(view, payloadEnd, typeSession)

// Phase 5: capture the decoded type
val declTpe: Option[Reflect.Type] = decodeOneTypeIfPresentCapturing(view, payloadEnd, typeSession)
declTpe.foreach(t => typeBySymbol(sym) = t)
```

Alternatively, rename `decodeOneTypeIfPresent` to return `Option[Reflect.Type]` and update all
call sites. This is the cleaner approach.

### Per-node decode strategy

**VALDEF** (line 195): The type annotation appears immediately after NameRef, before modifiers.
`decodeOneTypeIfPresent` is already called here; change it to capture the return.

**DEFDEF** (line 213): No type is decoded today; the call to `scanForwardAndCollectFlags` skips
everything. For DEFDEF the declared type is a function type, not directly encoded as a single type
node -- it is encoded as zero or more TYPEPARAM and PARAM children interleaved with the body sub-
tree. Reading it eagerly requires scanning the sub-tree for each param's type. **The most robust
approach**: for DEFDEF, store a sentinel or leave `_declaredType` unset in Pass 1 and reconstruct
the Function type in `mergeResults` from the DEFDEF's children (PARAMs and the return type VALDEF).
The return type appears as the last type node before the body. This is the same pattern that dotty
uses.

Alternatively, store the raw return-type byte range and decode it lazily. But since Phase 8 (full
tree body decode) is not yet implemented, the safest Phase 5 scope is:
- Wire `_declaredType` for VALDEF, TYPEPARAM, PARAM, and TYPEDEF (type-level) nodes directly.
- For DEFDEF: reconstruct from `childrenByOwner` in `mergeResults`: params are PARAM/TYPEPARAM
  children, return type is recoverable from the type annotation of the last child that is a return-
  position VALDEF, OR by a targeted re-parse of the DEFDEF byte slice.
- The plan's Test 2 (method type as `Type.Function`) requires DEFDEF support; the agent must
  implement DEFDEF reconstruction.

**TYPEDEF (class-like)** (line 244): For class-like TYPEDEFs, `declaredType` is `Type.Named(sym)`
(the class's own type, i.e., the type of the class symbol itself). No byte decode needed; set in
`mergeResults` after the symbol exists.

**TYPEDEF (type-level)** (line 289): The first byte after NameRef is the type body tag (already
peeked). Phase 5 calls `readTypeIntoSession` here to decode the type annotation (alias body or
bounds). The existing code consumes the tag byte and calls `skipTreeBody`; replace the skip with
a decode call.

**TYPEPARAM** (line 304): `decodeOneTypeIfPresent` is already called for bounds. Capture the
return.

**PARAM** (line 319): `decodeOneTypeIfPresent` is already called for param type. Capture the
return.

**PACKAGE** (line 170): No declared type; `declaredType` for package symbols should return an
error or a well-known sentinel. Leave `_declaredType` unset in Pass 1; in `mergeResults`, skip
Package symbols or assign a sentinel. The public accessor with `home.isAssigned` guard will throw
`stub("Symbol.declaredType")` if the field is never set -- or use `Chunk.empty` / `Absent` pattern.
The plan does not include a test for Package symbols; leave `_declaredType` unset for Package and
let the fallback-zeroing loop handle it (it must not assign a `Chunk.empty`-style sentinel for a
`Type` slot since there is no null-safe "no type" value; consider a dedicated `Type.NoType` variant
or simply leave the stub for Package symbols).

**Note**: the existing `decodeOneTypeIfPresent` silently swallows decode errors by jumping to
`end`. This is intentional for robustness. Keep the same swallow behavior when capturing; on error,
do NOT insert into `typeBySymbol`.

---

## Pass1Result Extension

Add to `runPass1`:
```scala
val typeBySymbol = new mutable.HashMap[Reflect.Symbol, Reflect.Type]()
// ... walkStats call gains typeBySymbol parameter ...
Pass1Result(
    ...,
    typeBySymbol = typeBySymbol.view.mapValues(identity).toMap
)
```

Add to `FileResult` analogously (like `parentsBySymbol` / `childrenByOwner` were added in Phase 3).

---

## mergeResults Population

After the existing `_parents`/`_typeParams`/`_declarations` loops, add:

```scala
// TASTy path: assign _declaredType from typeBySymbol.
for (sym, t) <- fr.typeBySymbol do
    sym._declaredType.set(t)

// Class-like TYPEDEFs get Type.Named(sym) as their declaredType.
// These are not in typeBySymbol (no byte-level type decode for class nodes).
for sym <- fr.fqns.map(_._2) do
    if (sym.kind == SymbolKind.Class || sym.kind == SymbolKind.Trait || sym.kind == SymbolKind.Object)
        && !sym._declaredType.isSet then
        sym._declaredType.set(Reflect.Type.Named(sym))

// Zero-fill unset symbols (Package, root, etc.).
for sym <- fr.allSymbols do
    // No good sentinel for "has no type"; skip for now. Package symbols leave _declaredType unset.
    ()
```

The classfile path: `buildOneMemberSymbol` (ClassfileUnpickler.scala line 1048) creates each member
symbol but does NOT currently carry the decoded type back to the symbol. Two options:

1. Add a `_declaredType` assignment directly in `ClassfileUnpickler.readFrom` alongside the
   existing `_parents`/`_typeParams`/`_declarations` block (preferred, consistent with Phase 3
   classfile pattern).
2. Carry the type in a new `ClassfileResult.typeBySymbol: Map[Reflect.Symbol, Reflect.Type]` field.

Option 1 requires `buildOneMemberSymbol` to return the type alongside the symbol. Currently
`buildOneMemberSymbol` does not surface the decoded type. The type is decoded inside
`buildMemberSymbols` from the field/method descriptor via `parseErasedDescriptorType` or the
`Signature` attribute parse; it is used only to set the JavaMetadata (it is not stored on the
symbol). Phase 5 needs this type wired to `_declaredType`. Change: thread the decoded type out of
`buildOneMemberSymbol` as a tuple `(Symbol, Type)`, then assign `sym._declaredType.set(t)` in
`readFrom`.

---

## Edge Cases

### ValDef (simple field)

`val x: Int` in PlainClass: `declaredType` returns `Type.Named(sym)` where `sym` is the symbol for
`scala.Int`. The TASTy byte stream for this VALDEF has the type annotation as the first sub-tree
after NameRef: a `TYPEREF` or `TERMREFpkg` node encoding `scala.Int`. `readTypeIntoSession` decodes
it directly. If `Int` is in another file (it always is), the result is a `Type.Named(unresolvedSym)`
with an `UnresolvedRef` placeholder; Phase C resolves it to the canonical `scala.Int` symbol. The
`_declaredType` field is set to the placeholder type initially; after Phase C the placeholder
slot's `SingleAssign` is populated, so reading `_declaredType.get()` retrieves a type whose
`Named.symbol` has been resolved. This is correct because `UnresolvedRef.replaceSlot` is the
same `SingleAssign[Type]` that `Type.Named` wraps; the resolve is in-place.

### DefDef (method)

`def add(x: Int, y: Int): Int` should produce `Type.Function(params=[Int, Int], result=Int)`.
This cannot be decoded from a single type node in Pass 1 because DEFDEF's TASTy layout interleaves
TYPEPARAM, PARAM, and body nodes. Reconstruction from `childrenByOwner` in `mergeResults` is
required. The children of the DEFDEF symbol include PARAM symbols with their types in `typeBySymbol`.
The return type is trickier: it is encoded as the last type annotation before the body RHS in the
DEFDEF payload. Phase 5 should eagerly decode the return type from the DEFDEF payload byte slice
(the `origin.TastyOrigin.bodyStart` to `bodyEnd` range), OR walk the DEFDEF payload explicitly
in Pass 1 to capture both param types and the return type.

Preferred approach: read the DEFDEF's return type explicitly before recursing into `walkStats`
on the inner view. In the DEFDEF case (line 213), after reading NameRef, the payload layout is:
`TYPEPARAM* PARAM* returnType RHS? modifier*`. Read the return type via `decodeOneTypeIfPresent`
after the params walk, before `scanForwardAndCollectFlags`. Reconstruct the `Function` type in
`mergeResults` from `typeBySymbol` param entries and the stored return-type entry.

### TypeDef (type alias)

`type Alias = String`: the declared type is the alias body (`Type.Named(stringSym)`). For type-
level TYPEDEFs, the first byte after NameRef is the alias body tag. Phase 5 must read this type
before calling `readModifiers`.

### TypeDef (class)

`class Foo` or `trait Foo`: the declared type is `Type.Named(sym)` (the class's own type). No byte
decode needed; `mergeResults` assigns this directly.

### TypeParam

`[A]`: the declared type is the type parameter's bounds encoded as a `TYPEBOUNDS` node. The
existing `decodeOneTypeIfPresent` call decodes the bounds; Phase 5 captures the return. Typically
`Type.Wildcard(lo, hi)` or a concrete type reference.

### PackageDef

Packages have no meaningful declared type. `_declaredType` is not set for Package symbols in Pass 1.
The `mergeResults` zero-fill loop should skip Package symbols. The public accessor relies on
`home.isAssigned` guard; if the `SingleAssign` is never set, `_declaredType.get()` will fail with
`SingleAssign not set` (under `AllowUnsafe`). Two options: (a) assign a sentinel type (e.g.,
`Type.Named(sym)` where `sym` is the package symbol itself), or (b) add a `kind == Package` guard
before the `_declaredType.get()` call and return `Abort.fail(ReflectError.NotImplemented)` for
packages. Option (b) is cleaner and does not pollute the type with a meaningless value.

### Java symbols (classfile path)

Each field symbol has its erased type decoded from the JVM field descriptor by
`parseErasedDescriptorType` (ClassfileUnpickler.scala line 963). Each method symbol has its erased
type from `parseErasedDescriptorType` on the method descriptor. The Signature attribute (if present)
provides generic types. Currently these types are decoded inside `buildMemberSymbols` but never
stored on the symbol. Phase 5 threads them back via `buildOneMemberSymbol` returning `(Symbol, Type)`.

Key: `ClassfileUnpickler` already has `resolveThrowsTypes` and `parseErasedDescriptorType`; the
decoded type is available at the point where the symbol is created. No new decode logic is needed,
only wiring.

---

## Test-Data Suggestions

### Test 1: ValDef Int (plainClassTasty)

`PlainClass(val x: Int)` from `kyo.fixtures.Embedded.plainClassTasty` (PlainClass.tasty). The
symbol for `x` has `declaredType` that resolves to `Type.Named(scalaDotIntSym)`. Assert:
```scala
sym.declaredType.map {
    case Type.Named(s) => s.fullName.asString == "scala.Int"
    case other         => fail(s"Expected Named(scala.Int) but got $other")
}
```

### Test 2: Method with params (baseClassTasty or fixture method)

`SomeTrait.compute: Int` from FixtureClasses. Or add a two-param method to the fixtures if none
exists. The fixture file has `def compute: Int` in `SomeTrait` (no params, returns Int). For a
two-param method, use `SomeCaseClass` (which has a synthesized `copy` method) or add
`class BaseClass { def add(x: Int, y: Int): Int = x + y }` to FixtureClasses. The plan calls for
`Type.Function([Int, Int], Int)`.

If adding a fixture is needed, add it to
`kyo-reflect-fixtures/shared/src/main/scala/kyo/fixtures/FixtureClasses.scala`.

### Test 3: TypeDef alias (plainClassTasty or new fixture)

`type StringList = List[String]` is already in FixtureClasses.scala. Load `FixtureClasses$package.tasty`
(or the top-level file); find the `StringList` TYPEDEF symbol; assert `declaredType` is
`Type.Applied(Type.Named(listSym), Chunk(Type.Named(stringSym)))` or similar.

### Test 4: Java field (ArrayRecord.class -- existing jvmOnly fixture)

Already used in Phase 3 Test 5. Find a field symbol on the Java classfile fixture and assert its
`declaredType` is the expected primitive or reference type.

### Test 5: ClasspathClosed guard

Same pattern as Phase 3 Test 4: capture symbol inside scope, let scope close, then call
`declaredType` and assert `ClasspathClosed`.

### AstUnpicklerTest Test 6: typeBySymbol for `def foo: String`

Need a `String`-returning method in fixtures. `SomeTrait.compute: Int` works for Int, or add
`def foo: String`. Assert `r.typeBySymbol.contains(fooSym)` and the type is
`Type.Named(s) && s.name.asString == "String"`.

### AstUnpicklerTest Test 7: typeBySymbol for `class Foo[T](val x: T)`

`GenericBox[A](val content: A)` from FixtureClasses. Load `GenericBox.tasty`; find the `content`
PARAM/VALDEF symbol; assert `r.typeBySymbol(contentSym)` is `Type.Named(aSym)` where `aSym` is
the type-param symbol for `A`.

---

## Anti-Flakiness Deltas

1. `decodeOneTypeIfPresent` silently swallows decode errors. Keep this behavior when the variant
   returns `Option[Reflect.Type]`: on exception, return `None` and do NOT insert into `typeBySymbol`.
   If a symbol ends up with no entry, the zero-fill loop in `mergeResults` should leave it unset
   (or assign a safe fallback) rather than crashing.

2. The `typeBySymbol` map is populated eagerly in Pass 1 but the type references may contain
   `UnresolvedRef` proxy types. After Phase C resolves all placeholders, the proxy's `SingleAssign`
   slots are populated. Tests that check `declaredType` must be run AFTER `mergeResults` (i.e., via
   the `Classpath` API, not raw `AstUnpickler.readPass1`). The AstUnpicklerTest Tests 6-7 test the
   `typeBySymbol` map content directly; they may see proxy types instead of resolved types. This is
   correct and expected for that layer; tests should assert on the structure (e.g.,
   `r.typeBySymbol(sym)` is `Type.Named(_)`) rather than on full FQN resolution.

3. For the `GenericBox` test (Test 7), `content`'s type is a reference to the type parameter `A`
   which is a locally-defined symbol. Because `DecodeSession.liveAddrMap` is live during the walk,
   by the time `content`'s type is decoded, `A`'s symbol is already in `liveAddrMap`. The decoded
   type is `Type.Named(aSym)` directly (not an UnresolvedRef). No Phase C resolution needed for
   intra-file type param references. Tests can assert on the direct symbol reference.

4. `isSet` (added in Phase 3) requires `AllowUnsafe`. All call sites in `mergeResults`,
   `ClassfileUnpickler.readFrom`, and `SnapshotReader` already have `AllowUnsafe.embrace.danger`
   in scope. No new `AllowUnsafe` import sites needed beyond the existing blocks.

---

## Concerns

### Cross-Phase C placeholder resolution for declaredType

When Pass 1 decodes a type annotation for `val x: Int`, the `Int` reference is a cross-file
`TERMREFpkg` or `TYPEREF` node pointing to a symbol in `scala.tasty`. `TypeUnpickler.readTypeIntoSession`
produces an `UnresolvedRef` placeholder and returns a `Type.Named(proxySymbol)` where `proxySymbol`
is a synthetic unresolved symbol whose `name = "scala.Int"`. This proxy is what gets stored in
`typeBySymbol(sym)`. Phase C then calls `placeholder.replaceSlot.set(Type.Named(resolvedSym))`.

The critical point: the type stored in `_declaredType` is `Type.Named(proxySymbol)`, NOT
`Type.Named(resolvedSym)`. The proxy type is a live reference that gets updated in-place via the
`SingleAssign` slot that `Type.Named` wraps -- but this requires the consumer to call
`resolvedSlot.get()` to retrieve the final value.

**This is identical to how parent types work (Phase 3) and it is correct** because the proxy
`Type.Named(sym)` where `sym` is a proxy symbol points to a `Symbol` whose own fields (fullName,
kind, etc.) are populated by Phase C's `fqnIndex` lookup. After Phase C, reading
`_declaredType.get().asNamed.symbol.fullName` gives the real FQN.

However: if `_declaredType` is assigned the proxy `Type.Named(proxySymbol)` BEFORE Phase C resolves
the placeholder, the pointer is to the proxy symbol, not the canonical symbol. Phase C resolves the
`UnresolvedRef.replaceSlot` to point to the canonical symbol, but the `_declaredType` slot already
holds a reference to `Type.Named(proxySymbol)`. The proxy symbol and the canonical symbol are
DIFFERENT objects; Phase C updates `replaceSlot`, not the proxy symbol itself.

**Risk**: If `_declaredType.set(proxyType)` happens in Pass 1 before Phase C, and Phase C replaces
`replaceSlot` with a different `Type.Named`, then `_declaredType` holds a stale reference to the
proxy type, not the resolved type.

**Resolution**: Follow the same pattern as `parentsBySymbol` in Phase 3. Do NOT assign
`_declaredType` in `walkStats`. Instead, carry `typeBySymbol` in `Pass1Result` and let `mergeResults`
assign `_declaredType` AFTER Phase C placeholder resolution completes (line ~210 of
ClasspathOrchestrator). At that point, all `UnresolvedRef` slots are resolved, and
`typeBySymbol(sym)` values that reference proxy types have their proxy `symbol`'s own data
populated. Reading `Named.symbol.fullName` after Phase C gives the canonical FQN. This is safe.

**Important**: The `typeBySymbol` map values ARE proxy types before Phase C. After Phase C they
remain proxy types (the map is immutable), but the proxy symbols' own fields have been populated
by Phase C. So the test assertion `declaredType.map(case Type.Named(s) => s.fullName.asString == "scala.Int")`
is correct only if the test is run after `mergeResults` (i.e., via the public `Classpath` API).
AstUnpicklerTest Tests 6-7 that check `typeBySymbol` directly will see proxy symbols; tests should
NOT assert on resolved FQN from the raw `Pass1Result` -- assert on symbol presence and type
constructor only.

### Package symbols (no declared type)

Packages do not have a declared type. Leave `_declaredType` unset for Package-kind symbols. Add a
`kind == SymbolKind.Package` guard in the `declaredType` accessor before reading `_declaredType`:
return `Abort.fail(ReflectError.NotImplemented)` with a message indicating packages have no
declared type. This prevents the `SingleAssign` "not set" panic for package symbols.

### SnapshotReader

SnapshotReader restores symbols from disk without re-running Pass 1. It cannot reconstruct
`_declaredType` without the original TASTy bytes. Leave `_declaredType` unset for snapshot-restored
symbols and return `Abort.fail(ReflectError.NotImplemented)` (same as the Package guard above, or
use a dedicated `ReflectError.NotYetRestored` variant). The zero-fill pattern used for
`_parents`/`_typeParams`/`_declarations` does not apply here because there is no null-safe sentinel
for `Type`. The plan states "empty Chunks acceptable for now" for Phase 3 because `Chunk.empty` is
a valid (if empty) `Chunk[Type]`; there is no equivalent for a missing `Type`.

### ClassDef declaredType

For class-like TYPEDEF symbols, `declaredType` should return `Type.Named(sym)` (the type of the
class itself as a type constructor, without args). This is the agreed convention per
IMPROVEMENT-ANALYSIS.md G20 ("ClassDef does NOT [have a type annotation]; its declaredType is the
class type itself"). This is assigned in `mergeResults` directly without any byte decode.

### DEFDEF reconstruction complexity

Reconstructing `Type.Function` for DEFDEF in `mergeResults` requires knowing which children of the
DEFDEF symbol are PARAM symbols, which are TYPEPARAM symbols, and what the return type is. After
Phase 3, `childrenByOwner` provides the children. The return type can be captured as an additional
entry in `typeBySymbol` keyed by the DEFDEF symbol itself (reading the last type node in the DEFDEF
payload before the body RHS). The agent must implement this carefully; the TASTy layout for DEFDEF
is:
```
DEFDEF Length NameRef TYPEPARAM* PARAM* returnType Expr? modifier*
```
The `returnType` is the last type node before the optional body `Expr` and modifiers. The existing
`walkStats` recursion for DEFDEF descends via `innerView` to pick up TYPEPARAM and PARAM children;
the return type is between the params and the body RHS and must be read explicitly. The simplest
approach: after creating the DEFDEF symbol and before calling `walkStats` on `innerView`, scan the
innerView for the return type position. This requires either a "skip all params then read one type"
helper or a targeted peek.
