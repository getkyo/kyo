# Phase 3 v2 Prep

Addresses G21 (Symbol.parents), G22 (Symbol.typeParams), G23 (Symbol.declarations).
Depends on Phase 2 (G13 UnresolvedRef placeholder resolution).

---

## Verbatim API signatures

### Public stubs being replaced (Reflect.scala)

```scala
// line 252
def parents(using Frame): Chunk[Type] < (Sync & Abort[ReflectError]) = stub("Symbol.parents")

// line 260
def typeParams(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError]) = stub("Symbol.typeParams")

// line 268
def declarations(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError]) = stub("Symbol.declarations")
```

After Phase 3 the bodies replace `stub(...)` with `checkOpen.andThen(_parents.get())` etc.
The public signature (name, params, effect row) does not change.

### Internal Symbol class fields that need adding (Reflect.scala, inside the Symbol class body)

The plan requires three new `SingleAssign` fields. They must sit alongside the existing fields
on `Reflect.Symbol`. Current Symbol constructor is private (`private[Reflect]`); fields added
here are accessible package-wide within `kyo`.

```scala
// to be added alongside existing SingleAssign fields:
private[kyo] val _parents:      SingleAssign[Chunk[Type]]
private[kyo] val _typeParams:   SingleAssign[Chunk[Symbol]]
private[kyo] val _declarations: SingleAssign[Chunk[Symbol]]
```

The `SingleAssign` type already appears for other internal fields in this file (see the `Memo`
fields at lines 59-60 and 229-230 for the AllowUnsafe access pattern). No new import needed.

### ClassfileResult fields (ClassfileUnpickler.scala)

```scala
final case class ClassfileResult(
    classSymbol: Reflect.Symbol,
    parents:         Chunk[Reflect.Type],          // already present
    innerClassTable: Map[String, (String, String)], // already present
    symbols:         Chunk[Reflect.Symbol],         // already present (declarations source)
    typeParams:      Chunk[Reflect.Symbol],          // already present
    arena:           TypeArena                       // already present
)
```

All three fields that Phase 3 needs (`parents`, `symbols`, `typeParams`) already exist on
`ClassfileResult`. No change to `ClassfileUnpickler.scala` is needed for the classfile path.

### Pass1Result fields (AstUnpickler.scala)

```scala
final case class Pass1Result(
    symbols:      Chunk[Reflect.Symbol],
    addrMap:      Map[Int, Reflect.Symbol],
    placeholders: Chunk[UnresolvedRef],
    rootSymbol:   Reflect.Symbol
)
```

Phase 3 plan requires extending `Pass1Result` with:
```scala
parentsBySymbol:  Map[Reflect.Symbol, Chunk[Reflect.Type]]   // new
childrenByOwner:  Map[Reflect.Symbol, Chunk[Reflect.Symbol]] // new
```

These two maps are pre-indexed in AstUnpickler during the Pass 1 walk so `mergeResults` can
assign without re-walking.

---

## File:line anchors

| File | Line | Action |
|------|------|--------|
| `shared/src/main/scala/kyo/Reflect.scala` | 252 | Replace `stub("Symbol.parents")` body |
| `shared/src/main/scala/kyo/Reflect.scala` | 260 | Replace `stub("Symbol.typeParams")` body |
| `shared/src/main/scala/kyo/Reflect.scala` | 268 | Replace `stub("Symbol.declarations")` body |
| `shared/src/main/scala/kyo/Reflect.scala` | inside Symbol class | Add `_parents`, `_typeParams`, `_declarations` SingleAssign fields |
| `shared/src/main/scala/kyo/Reflect.scala` | 240, 254, 258, 262, 266 | Remove the 6 `@note Not implemented in v1...` scaladoc lines covering all 3 stubs (plan: "All 6 previous stub scaladoc @note Not implemented comments removed") |
| `shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala` | Pass1Result case class (line 49) | Add `parentsBySymbol` and `childrenByOwner` fields |
| `shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala` | `runPass1` body (line 101+) | Populate the two new maps during the walk |
| `shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` | `mergeResults` (after placeholder resolution) | Assign `_parents`, `_typeParams`, `_declarations` on each symbol |
| `shared/src/test/scala/kyo/QueryApiTest.scala` | end of file | Add 4 new QueryApiTest tests |
| `shared/src/test/scala/kyo/AstUnpicklerTest.scala` | end of file | Add 2 new AstUnpicklerTest tests |

---

## Edge cases and gotchas

### TASTy parents: UnresolvedRef replacement vs direct Type

Phase 2 (G13) writes resolved `Type.Named(sym)` into each `UnresolvedRef.replaceSlot` via
`replaceSlot.set(...)`. After Phase 2, all UnresolvedRef slots in the type arena are resolved.

However, `parentsBySymbol` is built during Pass 1, which runs before Phase C (before G13
resolution). The parent types collected in `parentsBySymbol` may be in one of two forms:

- Same-file parents: the type was decoded inline by `TypeUnpickler.readType` and is already a
  complete `Type.Named(sym)` (the symbol exists in `addrMap`).
- Cross-file parents: `TypeUnpickler.readType` creates a new `UnresolvedRef(fqn, slot)` and
  returns `Type.Proxy(slot)` or similar. The slot is unset at Pass 1 time but will be set by
  Phase C (G13).

The correct approach is: `parentsBySymbol` must store the live `Type` references (which may be
wrapper types that contain the `SingleAssign` slot). When `mergeResults` reads from
`parentsBySymbol` and assigns `sym._parents.set(chunk)`, the chunk holds the live references.
By the time G13 has run and all slots are set, callers of `sym.parents` will read through the
already-resolved slot values. This works because `SingleAssign` stores a reference; the slot
value is the resolved type once Phase C writes it.

The implementation must not attempt to eagerly read or dereference the parent types during
`mergeResults` before G13 resolution; it must only copy the live references into the
`SingleAssign` field. If the parent type is a `Type.Proxy` containing the `SingleAssign` slot,
the caller of `parents` will unwrap it after Phase C has set the slot.

Verify: Phase 3 plan says "parents come from the resolved placeholder slots in the canonical
arena (re-walk Pass1Result stored per file or carry parent type IDs in FileResult)". This implies
the assignment of `_parents` must happen AFTER Phase C G13 resolution runs. The order in
`mergeResults` must be: (1) run G13 placeholder resolution, (2) then assign `_parents` from
`parentsBySymbol`. If `_parents` is assigned from `parentsBySymbol` before G13 runs, the parent
types for cross-file parents will hold un-set `SingleAssign` slots and callers of `parents` will
get proxy types that contain unresolved references.

Confirm the assignment order: in `ClasspathOrchestrator.mergeResults`, G13 placeholder resolution
iterates `fr.placeholders` and calls `replaceSlot.set(...)`. This must complete before the
`_parents` assignment loop.

### Java-origin symbols with no parents

`ClassfileResult.parents` can be `Chunk.empty` for `java.lang.Object` itself (it has no
superclass). The `_parents.set(Chunk.empty)` case must be handled: setting an empty chunk is
valid for `SingleAssign` and `sym.parents` returns `Chunk.empty` -- correct behavior.

For interface-only class files (all interfaces implicitly extend `java.lang.Object` in the JVM
model), the `parents` chunk from `ClassfileUnpickler` may or may not include `Object` depending
on how the unpickler handles the implicit superclass for interfaces. Test 5 (Java fixture) should
assert at least one parent for non-Object classes.

### typeParams for classfile path

`ClassfileResult.typeParams` comes from the class-level `Signature` attribute generic type
parameters. Non-generic classes have `typeParams = Chunk.empty`. The `_typeParams.set(Chunk.empty)`
case must be handled cleanly.

TypeParam symbols in `ClassfileResult.typeParams` are already allocated by `ClassfileUnpickler`.
Their `owner` is already set to the class symbol. No additional wiring is needed beyond assigning
the chunk.

### declarations scope: classfile vs TASTy

For classfile symbols: `ClassfileResult.symbols` contains all declared fields and methods. This
is the direct `_declarations` value. It does NOT include inner classes (those are in
`innerClassTable` and would require a separate lookup to resolve to symbols). The plan does not
require inner class inclusion in `declarations` for Phase 3.

For TASTy symbols: `childrenByOwner` collects all non-root symbols whose `owner eq parentSym`.
This includes type parameters, parameters, nested classes, methods, and vals. The question is
whether type parameters should appear in `declarations` or only in `typeParams`. The plan says
"all non-root children" for `childrenByOwner`. Type parameters are `SymbolKind.TypeParam`; they
will appear in `childrenByOwner`. Implementors should confirm whether the public `declarations`
contract (per DESIGN.md) includes type parameters or excludes them. If excluded, the
`_declarations` assignment must filter by `sym.kind != SymbolKind.TypeParam`.

### SingleAssign set-once semantics

`SingleAssign` is write-once (set panics if called twice). `mergeResults` iterates all
`FileResult` entries; if a symbol could appear in two `FileResult` entries (which should not
happen since each file produces distinct symbols), double-set would panic. Ensure each symbol
appears in exactly one `FileResult` before calling `_parents.set`. This invariant is guaranteed
by the one-symbol-per-file design but worth a defensive comment.

---

## Test-data suggestions

### Test 1 (parents, non-empty)

Fixture: `PlainClass.tasty` (package `kyo.fixtures`, file committed as embedded bytes).
`PlainClass` is a plain Scala class with no explicit superclass, so its TASTy TEMPLATE parent
is `java.lang.Object` (via `scala.AnyRef`). After Phase 3, `sym.parents` should return a
non-empty chunk containing the `AnyRef`/`Object` parent type.

Use `SymbolResolutionTest.fixtureSource()` pattern (load `PlainClass.tasty` from
`kyo.fixtures.Embedded.plainClassTasty`).

### Test 2 (typeParams, non-empty)

Fixture: `GenericBox.tasty` (`Embedded.genericBoxTasty`). `GenericBox[A]` is a single
type-parameter class. `AstUnpicklerTest` already exercises this fixture for Pass 1 typeParam
detection (test at line 224). After Phase 3, `sym.typeParams` for `GenericBox` should return
a `Chunk` of length 1 with `typeParams(0).name.asString == "A"`. The plan uses
`class GenFoo[T, U]` (length 2); the existing `GenericBox` fixture covers length-1. Either
create a new two-param fixture or use `GenericBox` for length-1 and use a different existing
fixture for the plan test 2 assertion. `SomeCaseClass.tasty` is a case class and likely has no
type params. Use `GenericBox` with adjusted assertion.

### Test 3 (declarations, non-empty)

Fixture: `PlainClass.tasty` (has field `x: Int` and possibly constructor method). `sym.declarations`
should include at least `x` and `<init>`. Assert by name set: `assert(syms.map(_.name.asString).toSet.contains("x"))`.

For nested class declarations, `Outer.tasty` (`Embedded.outerTasty`) contains outer/inner
nested structure. `Outer.declarations` should include the inner class symbol.

### Test 4 (parents after close)

No fixture needed. Use `Scope.run` and call `cp.findClass(...)` then `Scope.run` exits to close,
then call `sym.parents`. The `checkOpen` in the stub replacement returns
`Abort.fail(ReflectError.ClasspathClosed)`.

### Test 5 (Java fixture)

`ArrayRecord.class` (`Embedded.arrayRecordClass`) is a Java record. Use `ClassfileReaderTest`'s
fixture-loading pattern. After Phase 3, `sym.parents` should include `java.lang.Record` (records
extend Record). `sym.typeParams` should be empty (no generic params). `sym.declarations` should
be non-empty (the `values` field and record accessor).

### Tests 6 and 7 (AstUnpicklerTest)

Test 6 (`Pass1Result.parentsBySymbol`): load `PlainClass.tasty` or `SomeCaseClass.tasty` via
`AstUnpickler.readPass1`. Assert `result.parentsBySymbol.contains(classSymbol)` and
`result.parentsBySymbol(classSymbol).nonEmpty`.

Test 7 (`Pass1Result.childrenByOwner`): load `PlainClass.tasty`. Assert
`result.childrenByOwner.contains(classSymbol)` and the mapped chunk contains at least the `x`
field symbol and the `<init>` method symbol.

---

## Anti-flakiness deltas

### Declaration order is not guaranteed

`ClassfileResult.symbols` iterates the classfile field/method tables in bytecode order, which is
deterministic for a given classfile. However, tests that assert on `declarations` by index
(e.g., `declarations(0).name.asString == "x"`) are fragile: compiler changes, field ordering
changes, or synthetic members added can shift indices.

Always assert by name set lookup, not by index:
```scala
assert(decls.map(_.name.asString).toSet.contains("x"))
```
or use a specific named-lookup:
```scala
val xSym = decls.find(_.name.asString == "x").get
assert(xSym.kind == SymbolKind.Field)
```

### typeParams order

TypeParam order in TASTy is the declaration order (left-to-right). For the `GenericBox[A]` single-
param test, order does not matter. For a multi-param test using a future fixture (`Foo[T, U]`),
assert position-by-name: `assert(typeParams.map(_.name.asString) == Chunk("T", "U"))` only if
the fixture's declaration order is known and stable. Otherwise assert by set membership.

### parents order

For TASTy: parent order matches the TEMPLATE parent list in the TASTy file. For a class with a
single parent (`AnyRef`), order is trivially stable. For multi-parent tests (trait mix-ins),
assert by set membership or by checking the first entry is the superclass, as Scala's TASTy
encodes superclass first.

### Cross-platform: SingleAssign is not thread-safe for concurrent set

`SingleAssign.set` must be called exactly once per symbol, from a single-threaded `mergeResults`
context. The Phase C orchestration is single-threaded (STEERING.md confirms this). No lock needed.
Do not introduce any concurrent `_parents.set` calls.

---

## Concerns

1. **Assignment timing relative to G13**: The most critical implementation risk. `_parents.set`
   must happen after G13 placeholder resolution to ensure cross-file parents are resolved `Type`
   references, not proxies with unset slots. The Phase 3 plan says "after placeholder resolution"
   but the impl agent must verify the literal code ordering in `mergeResults`. A test that checks
   `sym.parents` returns a `Type.Named(sym)` where `sym.fullName.asString` equals a cross-file
   class FQN (not a proxy type) would catch this.

2. **No new fixture for 2-param typeParams**: The plan test 2 asks for a 2-typeParam fixture. The
   only current fixture with type params is `GenericBox[A]` (one param). The impl agent must
   either create a new fixture (adding to `Embedded.scala`) or adjust the test to use `GenericBox`
   for 1-param and accept a weaker test. Creating a new fixture is preferred; a new
   `GenericBox2.tasty` with `class GenericBox2[T, U](t: T, u: U)` would close this gap cleanly.

3. **`checkOpen` in parents/typeParams/declarations**: Each method body must call `home.checkOpen`
   before reading the `SingleAssign`. For `checkOpen` to work, the `home: ClasspathRef` field
   must be reachable from Symbol. Verify the internal Symbol class exposes `home` in a way the
   stub-replacing body can call: `home.checkOpen.andThen(_parents.get())`. This pattern matches
   the existing `declaredType` stub shape.

4. **`SingleAssign.get` panics if unset**: If `mergeResults` fails to call `_parents.set` for any
   symbol (e.g., a symbol created by `ClassfileUnpickler` for a synthetic inner proxy), the
   subsequent `parents` call would panic on `get`. The impl must ensure `_parents.set` is called
   for every symbol in `allSymbols`, with `Chunk.empty` as the fallback for symbols that have no
   parents in the source (e.g., package symbols, type parameters themselves).

5. **`childrenByOwner` memory cost**: For a large classpath with thousands of symbols, building
   `childrenByOwner: Map[Symbol, Chunk[Symbol]]` holds all symbols twice (once in `allSymbols`,
   once in the map). This is acceptable for correctness; performance is not a concern in Phase 3.

6. **Plan supervisor check: "All 6 previous stub scaladoc @note Not implemented comments removed"**:
   Both `Symbol.parents`, `Symbol.typeParams`, and `Symbol.declarations` each have 2 scaladoc
   `@note` lines (the `Not implemented in v1` message and the `Deferred per DESIGN.md` part,
   combined as a single `@note` block at Reflect.scala lines 247-251, 255-259, 263-267). The
   impl agent must remove all of them; leaving stub scaladoc alongside a working implementation
   would be misleading.
