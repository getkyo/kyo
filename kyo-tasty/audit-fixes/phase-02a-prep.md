# Phase 02a prep

Phase name: Propagate AllowUnsafe through Symbol accessors
Files to produce: 0
Files to modify: 1
Tests: 5
Plan cites: ./05-plan.md §Phase 02a

## Verbatim API signatures

### AllowUnsafe proof token (kyo-config)

- `abstract class AllowUnsafe private ()`
  at kyo-config/shared/src/main/scala/kyo/AllowUnsafe.scala:22

- `implicit val danger: AllowUnsafe = instance`
  at kyo-config/shared/src/main/scala/kyo/AllowUnsafe.scala:27

Usage: callers obtain proof via `given AllowUnsafe = AllowUnsafe.embrace.danger` or
`import AllowUnsafe.embrace.danger`. The impl agent must add `(using AllowUnsafe)` to each
accessor signature so the proof propagates from the call site.

### OnceCell.get (kyo-tasty internal)

- `def get()(using AllowUnsafe): A`
  at kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala:32

### SingleAssign.get (kyo-tasty internal)

- `def get()(using AllowUnsafe): A`
  at kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/SingleAssign.scala:32

Both primitives already take `(using AllowUnsafe)`. After Phase 02a the accessor bodies no longer
need a local `import AllowUnsafe.embrace.danger`; the proof flows in through the `(using AllowUnsafe)`
parameter of the accessor itself.

### ClasspathRef.get (kyo-tasty internal)

The `companion` accessor calls `home.get()` twice (lines 688 and 699). ClasspathRef.get currently
uses a local `import AllowUnsafe.embrace.danger`:

- `def get(): Tasty.Classpath`
  at kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala:26

`ClasspathRef.get` does NOT yet take `(using AllowUnsafe)`. After Phase 02a adds
`(using AllowUnsafe)` to `companion`, calling `home.get()` inside its body still compiles because
ClasspathRef.get wraps its own `import danger` internally. No change to ClasspathRef is needed in
Phase 02a. See Edge case 1 below.

### Classpath.pureClass (kyo-tasty internal)

The `companion` body calls `home.get().pureClass(companionFqn)`. Current signature:

- `private[kyo] def pureClass(fqn: String): Maybe[Tasty.Symbol]`
  at kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala:68

`pureClass` hides its own `import AllowUnsafe.embrace.danger` internally (line 70). Phase 02b will
propagate AllowUnsafe through Classpath pure accessors. For Phase 02a the call
`home.get().pureClass(...)` compiles unchanged because pureClass has its own local import. No
change to pureClass is needed in Phase 02a.

### 9 Symbol accessors in Tasty.scala (BEFORE state)

1. `def fullName: Name`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:560
   Body: `import AllowUnsafe.embrace.danger` at line 562; calls `_fullNameOnce.get()`.

2. `def isPackageObject: Boolean`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:569
   Body: `import AllowUnsafe.embrace.danger` at line 571; calls `name.string.get() == "package"`.

3. `def scaladoc: Maybe[String]`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:583
   Body: `import AllowUnsafe.embrace.danger` at line 585; calls `_scaladoc.isSet` / `_scaladoc.get()`.

4. `def position: Maybe[Position]`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:597
   Body: `import AllowUnsafe.embrace.danger` at line 599; calls `_position.isSet` / `_position.get()`.

5. `def declaredType: Type`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:619
   Body: `import AllowUnsafe.embrace.danger` at line 625 (inside else branch); calls `_declaredType.get()`.

6. `def parents: Chunk[Type]`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:632
   Body: `import AllowUnsafe.embrace.danger` at line 635; calls `_parents.get()`.

7. `def typeParams: Chunk[Symbol]`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:643
   Body: `import AllowUnsafe.embrace.danger` at line 646; calls `_typeParams.get()`.

8. `def declarations: Chunk[Symbol]`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:654
   Body: `import AllowUnsafe.embrace.danger` at line 657; calls `_declarations.get()`.

9. `def companion: Maybe[Symbol]`
   at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:669
   Body: no top-level `import AllowUnsafe.embrace.danger`; instead uses `import Name.asString` at line 673,
   which currently resolves because `Name.asString` embraces danger internally. After Phase 02a,
   `companion` gains `(using AllowUnsafe)` and `Name.asString` gains `(using AllowUnsafe)` too,
   so `import Name.asString` inside the body still works because AllowUnsafe is in scope from the
   outer parameter. See Edge case 2.

### Name.asString extension (BEFORE state)

- `def asString: String`
  at kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:67
  Body: `import AllowUnsafe.embrace.danger` at line 69; calls `n.string.get()`.

This is the 10th accessor modified by Phase 02a (plan §Files to modify notes "Name.asString extension
(lines 58-64)"). It becomes `def asString(using AllowUnsafe): String`.

## File anchors

- kyo-tasty/shared/src/main/scala/kyo/Tasty.scala
  - Lines 65-71: `Name.asString` extension; remove `import AllowUnsafe.embrace.danger` (line 69),
    add `(using AllowUnsafe)` to the def.
  - Lines 560-564: `def fullName`; remove `import AllowUnsafe.embrace.danger` (line 562),
    add `(using AllowUnsafe)` to the def.
  - Lines 569-573: `def isPackageObject`; remove `import AllowUnsafe.embrace.danger` (line 571),
    add `(using AllowUnsafe)` to the def.
  - Lines 583-588: `def scaladoc`; remove `import AllowUnsafe.embrace.danger` (line 585),
    add `(using AllowUnsafe)` to the def.
  - Lines 597-602: `def position`; remove `import AllowUnsafe.embrace.danger` (line 599),
    add `(using AllowUnsafe)` to the def.
  - Lines 619-627: `def declaredType`; remove `import AllowUnsafe.embrace.danger` (line 625),
    add `(using AllowUnsafe)` to the def.
  - Lines 632-637: `def parents`; remove `import AllowUnsafe.embrace.danger` (line 635),
    add `(using AllowUnsafe)` to the def.
  - Lines 643-648: `def typeParams`; remove `import AllowUnsafe.embrace.danger` (line 646),
    add `(using AllowUnsafe)` to the def.
  - Lines 654-659: `def declarations`; remove `import AllowUnsafe.embrace.danger` (line 657),
    add `(using AllowUnsafe)` to the def.
  - Lines 669-703: `def companion`; no import to remove (body uses `import Name.asString`);
    add `(using AllowUnsafe)` to the def. Body calls to `fullName`, `name.asString`, `owner.fullName`
    automatically satisfy AllowUnsafe because the outer parameter is in scope.

## Edge cases and gotchas

1. **companion calls home.get() which has its own internal import.**
   `ClasspathRef.get()` (kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathRef.scala:26)
   still uses an internal `import AllowUnsafe.embrace.danger` (line 28). After Phase 02a adds
   `(using AllowUnsafe)` to `companion`, calling `home.get()` inside the body still compiles
   without any change to ClasspathRef. ClasspathRef.get() will be updated in a later phase once the
   plan covers it. No action needed in Phase 02a.

2. **companion body uses `import Name.asString` (a Scala 3 extension import).**
   At Tasty.scala:673 the body has `import Name.asString`. This imports the extension method `asString`
   into scope. After Phase 02a, `asString` itself requires `(using AllowUnsafe)`, which is satisfied
   by the outer `companion(using AllowUnsafe)` parameter. The import line stays; the compiler sees
   the AllowUnsafe witness from the enclosing scope and forwards it automatically.

3. **companion calls fullName and owner.fullName inside its body.**
   Lines 686-698 call `fullName.asString`, `owner.fullName.asString`, `name.asString`. After
   Phase 02a, `fullName` and `asString` require AllowUnsafe; the outer parameter satisfies all
   of these transitively. No special annotation needed inside the body.

4. **isPackageObject calls name.string.get() directly, not through asString.**
   At Tasty.scala:572: `flags.contains(Flag.Module) && name.string.get() == "package"`. The
   `string` field is a `OnceCell[String]`; `get()` requires AllowUnsafe. After adding
   `(using AllowUnsafe)` to `isPackageObject`, the OnceCell.get() call at line 572 receives proof
   from the outer parameter.

5. **Test call sites: run helper provides AllowUnsafe.**
   All test call sites that use `in run { ... }` obtain AllowUnsafe from
   `kyo-core/shared/src/main/scala/kyo/internal/BaseKyoCoreTest.scala:8`
   (`import AllowUnsafe.embrace.danger` inside `def run`). Tests in AstUnpicklerTest (line 279:
   `inner.fullName.asString`), InternerTest (lines 55/64: `name.asString`), and
   CommentsUnpicklerTest (lines 154/158/162: `sym.scaladoc`) all live inside `in run { ... }`
   and will compile correctly after the accessor signatures add `(using AllowUnsafe)`.

6. **TastySymbolTest.scala does not exist yet.**
   The plan test scenarios 3-5 specify `TastySymbolTest.scala` as target. That file does not
   currently exist in kyo-tasty/shared/src/test/scala/kyo/. Tests 3-5 are NEW test scenarios
   that Phase 02a must add to that new file (plan §Files to produce is empty, but the test
   scenarios reference this file). Cross-checking: plan YAML `tests.files` lists both
   `TastyTest.scala` (existing) and `TastySymbolTest.scala` (must be created). Test scenarios
   1-2 are source-text grep tests that belong in TastyTest.scala; scenarios 3-5 are runtime
   classpath tests that belong in the new TastySymbolTest.scala.

7. **Scala2PickleTest line 229 calls .parents on a raw Symbol.**
   `kyo-tasty/shared/src/test/scala/kyo/Scala2PickleTest.scala:229` calls `result.parents`.
   That test file must be checked for AllowUnsafe context. If it does not have
   `import AllowUnsafe.embrace.danger` or a `given AllowUnsafe` in scope it will fail to compile
   after Phase 02a. The `run` helper provides `danger` implicitly for any call inside
   `in run { ... }` blocks, so if the `result.parents` call is inside such a block it is safe.
   The impl agent must verify the surrounding context before completing the phase.

## Test-data suggestions

- `scala.Predef` from the JVM classpath: a stable top-level class with a well-known FQN; suitable
  for `fullName.asString == "scala.Predef"` assertion.
- `scala.Int` from the JVM classpath: an AnyVal subtype; `parents` contains `scala.AnyVal`.
- `scala.Option` from the JVM classpath: has a companion object `Option$`; suitable for
  companion round-trip test.

## Anti-flakiness deltas

- AllowUnsafe is a pure token with no concurrency surface; no timing or ordering concerns.
- The `OnceCell` and `SingleAssign` reads are idempotent after `open` returns; no flake vector.
- TastySymbolTest must call its tests inside `in run { ... }` blocks so `AllowUnsafe.embrace.danger`
  is in scope from the `run` helper (BaseKyoCoreTest.scala:8). Calling accessors outside `run`
  blocks requires an explicit `given AllowUnsafe` at the test scope.

## Cross-platform notes

- platforms: jvm, js, native
- Change is confined to a single `kyo-tasty/shared/` file. No platform-specific source. The same
  edit applies uniformly on JVM, JS, and Native.
- The verification command in the plan runs `kyo-tastyJVM/testOnly`; JS and Native equivalents
  (`kyo-tastyJS/testOnly`, `kyo-tastyNative/testOnly`) use the same shared test source and are
  expected to compile and pass identically.

## Concerns

1. **`files_produced: []` in plan YAML but TastySymbolTest.scala does not exist.**
   The plan YAML marks `files_produced: []` but scenarios 3-5 require `TastySymbolTest.scala`
   which is a new file. The YAML and the test-files list are inconsistent. Recommended resolution:
   the impl agent creates `TastySymbolTest.scala` as part of Phase 02a; the supervisor may want
   to update the YAML `files_produced` entry before the verify gate runs. This prep does NOT
   modify the plan; surfacing for supervisor decision.

2. **Scala2PickleTest.scala:229 calls `.parents` outside of an explicit AllowUnsafe context.**
   Line 229 reads `result.parents` where `result` is a decoded symbol. If this call is not inside
   a `run` block or does not have `import AllowUnsafe.embrace.danger` in scope, Phase 02a will
   cause a compile error in an existing test. The impl agent must inspect the surrounding context
   and add an AllowUnsafe scope if needed. This is a real compile-break risk.

3. **QueryApiTest.scala uses `.name.asString` extensively (lines 238-662) inside `run` blocks.**
   After Phase 02a `asString` requires AllowUnsafe. All these call sites are inside `in run { ... }`
   so they are covered by `BaseKyoCoreTest.run`'s import. No change needed, but the impl agent
   should confirm by spot-checking one site.

4. **Plan names 10 modified accessors (9 Symbol + Name.asString) but phase title says "9 Symbol
   accessors."** The phase modifies 10 signatures total (9 Symbol + 1 Name extension). The plan
   prose is consistent; the phase title is a short label, not a count. No remediation needed;
   noting for clarity.
