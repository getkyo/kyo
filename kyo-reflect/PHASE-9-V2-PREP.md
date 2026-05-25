# Phase 9 Prep: G5 Subtype Checking

**Plan reference**: execution-plan-v2.md lines 375-419
**Dependency state**: Phase 8 committed at `e08e70478539a0ff6f170a7b0b139f4f0f2dc7b3`

---

## Pre-existing State (Phase 8 delivered Phase 9 scaffolding)

Phase 8 already landed the Phase 9 production code. The following are present and do NOT need to be
written from scratch:

- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/Subtyping.scala` (330 lines) -- full
  implementation of `object Subtyping` with `isSubtype`, `isNamedSubNamed`, `checkParents`,
  `checkAppliedArgs`, `checkArgPairs`, `alphaEquiv`, `typeEquivAlpha`, `substituteRecThis`.

- `Reflect.scala` lines 833-856: `extension (t: Type)` block with `def isSubtypeOf(other: Type)(using cp: Classpath, Frame): Boolean < (Sync & Abort[ReflectError])` delegating to `Subtyping.isSubtype(..., budget = 64)`.

**Phase 9's only remaining work is writing the 9 tests in `SubtypeTest.scala`.**

The plan says `TypeOps.scala` should be extended with `isSubtype`. The actual implementation placed it in
a standalone `Subtyping.scala`. The plan also mentions `Type.<=:` in one sentence but the actual public
API name is `isSubtypeOf`. These are fine deviations; do not rename.

---

## Verbatim Signatures

### Type ADT (Reflect.scala lines 186-209)

```scala
enum Type:
    case Named(symbol: Symbol)
    case TermRef(prefix: Type, name: Name)
    case Applied(base: Type, args: Chunk[Type])
    case TypeLambda(params: Chunk[Symbol], body: Type)
    case Function(params: Chunk[Type], result: Type, isContext: Boolean)
    case Tuple(elements: Chunk[Type])
    case ByName(underlying: Type)
    case Repeated(elem: Type)
    case Array(elem: Type)
    case Refinement(parent: Type, name: Name, info: Type)
    case Rec(parent: Type)
    case RecThis(rec: Type)
    case AndType(left: Type, right: Type)
    case OrType(left: Type, right: Type)
    case Annotated(underlying: Type, annotation: Annotation)
    case ConstantType(value: Constant)
    case ThisType(cls: Symbol)
    case SuperType(self: Type, mixin: Type)
    case ParamRef(binder: Symbol, idx: Int)
    case Wildcard(lo: Type, hi: Type)
    case Skolem(underlying: Type)
    case MatchType(bound: Type, scrutinee: Type, cases: Chunk[Type])
    case FlexibleType(underlying: Type)
```

### Type.isSubtypeOf extension (Reflect.scala line 854)

```scala
extension (t: Type)
    def isSubtypeOf(other: Type)(using cp: Classpath, Frame): Boolean < (Sync & Abort[ReflectError]) =
        kyo.internal.reflect.type_.Subtyping.isSubtype(t, other, cp, budget = 64)
end extension
```

### Subtyping.isSubtype (Subtyping.scala line 55)

```scala
def isSubtype(sub: Reflect.Type, sup: Reflect.Type, cp: InternalClasspath, budget: Int)(using
    Frame
): Boolean < (Sync & Abort[ReflectError])
```

Where `InternalClasspath = kyo.internal.reflect.query.Classpath`. This is the internal concrete type;
the public extension uses the opaque `Reflect.Classpath` which is identical at runtime.

### Depth-budget pattern

```scala
if budget <= 0 then false
// ... case Reflect.Type.Rec(subParent) =>
//     isSubtype(subUnfolded, supUnfolded, cp, budget - 1)
```

Budget starts at 64 per top-level call. Decremented only on `Rec` unfolding, not on structural
recursion over other ADT cases. When it reaches 0, returns `false` conservatively.

---

## File:Line Anchors

| What | File | Line |
|---|---|---|
| `Subtyping.scala` -- production impl | `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/Subtyping.scala` | 1-330 |
| `isSubtypeOf` extension | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 833-856 |
| Type ADT | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 186-209 |
| `Flag.CoVariant` | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 111 |
| `Flag.ContraVariant` | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 112 |
| `Symbol._parents: SingleAssign[Chunk[Type]]` | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 341 |
| `Symbol._typeParams: SingleAssign[Chunk[Symbol]]` | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 342 |
| `Symbol.parents` (public accessor) | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 444-450 |
| `Symbol.typeParams` (public accessor) | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 453-459 |
| `Symbol.make` (private[kyo] test helper) | `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` | 619 |
| Where to add `SubtypeTest.scala` | `kyo-reflect/shared/src/test/scala/kyo/` | new file |
| Existing test fixture helper `makeNamedSym` pattern | `kyo-reflect/shared/src/test/scala/kyo/TypeOpsTest.scala` | 12-42 |

---

## Subtyping Rules per Phase 9 Plan

### Named (reflexivity + nominal parents)

`Named(A) <: Named(B)`:
- If `A eq B` (same symbol reference): `true` immediately (interning means reference equality is
  sufficient; no need to walk parents).
- If `A != B`: walk `A._parents` transitively. Each parent is checked as `Named(parentSym)` or
  `Applied(Named(parentSym), _)` -- the sym-equality check uses `eq` in both cases.
- If `_parents.isSet` is false (symbol from a not-yet-populated classpath): conservative `false`.
- `scala.Any` as the supertype: always `true` (checked before the main dispatch).
- `scala.Nothing` as the subtype: always `true`.

### Applied with variance

`Applied(C[subArgs]) <: Applied(C[supArgs])`:
1. `isSubtype(subBase, supBase)` must hold first.
2. `subArgs.length == supArgs.length` must hold.
3. For each arg pair `(subArg, supArg)` at index `i`:
   - Get the i-th type parameter symbol from `subBase._typeParams` (unsafe, via `AllowUnsafe`).
   - If that symbol has `Flag.CoVariant` (bit `1L << 33`): check `subArg <: supArg` (covariant).
   - If `Flag.ContraVariant` (bit `1L << 34`): check `supArg <: subArg` (reversed).
   - If neither (invariant, or variance info absent): check both `subArg <: supArg` AND `supArg <: subArg`.

### AndType / OrType distributivity

- `AndType(L, R) <: T` iff `L <: T` OR `R <: T`. (Either component suffices to establish subtyping.)
- `T <: OrType(L, R)` iff `T <: L` OR `T <: R`. (T fits into either side of the union.)
- Note: OrType on the SUB side (`OrType(L, R) <: T`) is NOT handled -- it would require `L <: T` AND
  `R <: T`. The current implementation returns `false` for that pattern, which is conservative.

### TypeLambda alpha-equivalence

Two `TypeLambda(params, body)` types are equivalent iff their bodies are structurally equal after
substituting each param symbol with its positional index. Implementation uses `alphaEquiv` which builds
`Map[Symbol, Int]` index maps and calls `typeEquivAlpha` recursively. Nested TypeLambda extends the maps
with `base + i` offsets (de Bruijn-style).

### Wildcard contravariant lo / covariant hi

`Wildcard(lo, hi) <: Wildcard(lo', hi')` iff `lo' <: lo` (lower bound is contravariant: the sub-type's
lower bound must be broader) AND `hi <: hi'` (upper bound is covariant: the sub-type's upper bound must
be narrower). Bug-prone direction: it is `supLo <: subLo` (not `subLo <: supLo`) for the lower bound.

### Rec unfold depth = 64

`Rec(parent) <: Rec(parent')`:
1. Substitute `RecThis` references in each parent with the `Rec` node itself (`substituteRecThis`).
2. Recursively call `isSubtype(subUnfolded, supUnfolded, cp, budget - 1)`.

`Rec(parent) <: T` (asymmetric):
1. Unfold sub Rec only, compare unfolded result against `T`.
2. Decrement budget.

Budget is a single counter threaded through the entire recursive descent -- it is NOT per-branch. Once
spent by any single Rec path, all subsequent Rec unfoldings in the same top-level call also fail.

---

## Variance: Where to Find It

TypeParam variance is stored as flags on each type parameter symbol:

```scala
// Reflect.scala lines 111-112
val CoVariant: Flag     = Flag(1L << 33, "CoVariant")
val ContraVariant: Flag = Flag(1L << 34, "ContraVariant")
```

TASTy encodes variance via modifier tags `COVARIANT` / `CONTRAVARIANT` on TypeParam definitions
(`Flags.scala` lines 47-48 maps those to the bits above).

To check variance for the i-th argument of `Applied(base, args)`:
1. Get `base._typeParams` (type: `SingleAssign[Chunk[Symbol]]`, `private[kyo]`).
2. If `isSet` is false (not loaded): default to invariant.
3. `tps(i).flags.contains(Flag.CoVariant)` -> covariant.
4. `tps(i).flags.contains(Flag.ContraVariant)` -> contravariant.
5. Neither -> invariant.

`Flag.CoVariant` and `Flag.ContraVariant` are absent = invariant (no dedicated invariant flag).

---

## Test-Data Suggestions

### Synthetic symbol construction (from TypeOpsTest pattern)

Use `Symbol.make(kind, flags, name, owner, home, origin, javaMetadata)` (private[kyo], available in
test scope). The `TypeOpsTest.makeNamedSym` helper at `TypeOpsTest.scala:12` shows the full pattern.

For covariant type-param tests, create a symbol with `Reflect.Flags(Flag.CoVariant.bit)`:
```scala
val covariantParam = Reflect.Symbol.make(
    Reflect.SymbolKind.TypeParam,
    new Reflect.Flags(Reflect.Flag.CoVariant.bit),
    Reflect.Name("A"),
    owner,
    new ClasspathRef,
    Reflect.Symbol.TastyOrigin.empty,
    Absent
)
```
Then `import AllowUnsafe.embrace.danger; listSym._typeParams.set(Chunk(covariantParam))`.

### Using existing fixtures (ClasspathOrchestrator)

Tests 1-3 (Named nominal subtyping) benefit from real symbols from the fixture TASTy files (e.g.,
`baseClassTasty` which contains a class that extends another). After opening a classpath via
`ClasspathOrchestrator.openInto`, `sym.parents` will be populated. The `runPass1` pattern from
`TreeUnpicklerTest.scala` (line 57-62) is reusable.

For Tests 4-9, purely synthetic types (no classpath) are sufficient because the logic is structural
and does not require `parents` lookups (except Test 2 which requires parents).

### Test 9 (Rec no infinite recursion)

Build a `Rec` type manually:
```scala
val recType = Reflect.Type.Rec(Reflect.Type.RecThis(/* placeholder: will be the Rec itself */))
```
Note: `RecThis(rec)` points to the `rec` field which will be the outer `Rec`. Use a lazy val or
two-step construction to wire the cycle. Call `recType.isSubtypeOf(recType)(using cp)` and verify it
terminates (returns either `true` or `false` without timeout/overflow).

---

## Edge Cases

### Named eq Named (interning)

After Phase C, structurally identical `Named(sym)` values have `sym eq sym` because symbols are
canonical. The reflexivity check `subSym eq supSym` at `Subtyping.scala:74` is correct and sufficient
for the same-symbol case. Do not add structural equality as a fallback -- it would be wrong (two
symbols with the same FQN but different identities are not interchangeable in kyo-reflect).

### AnyRef as a common ancestor

Tests asserting `String <: Object` must go through the parent chain. `String`'s `_parents` will
include `Serializable`, `Comparable[String]`, and `Object` (or `java.lang.Object`). The FQN check
in `isNamedSubNamed` uses `sym eq supSym` (reference equality), NOT FQN string matching, so the
parent Symbol for `Object` must be the same object as the `objectSym` used on the right-hand side.
For purely synthetic tests this means both sides must use the same `Symbol.make` call result (or the
parent-wiring must use the exact same instance).

### Type aliases unfolding

The current implementation does NOT unfold type aliases. `Named(opaqueAlias) <: Named(underlying)` will
return `false` unless the alias symbol appears in the parent chain. This is a known limitation per
IMPROVEMENT-ANALYSIS.md G5; it is correct behavior for Phase 9. Do not add alias unfolding.

### Wildcard contravariance bug-prone direction

The natural reading "lower bound check" may lead to writing `subLo <: supLo` (wrong). The correct
direction is `supLo <: subLo`: the sub-type's lower bound must be at least as permissive (contravariant
position). Implementation at `Subtyping.scala:129`: `isSubtype(supLo, subLo, cp, budget)`. Verify tests
explicitly check this direction.

### Annotated / FlexibleType / Skolem

None of the 9 plan tests cover these cases. The current implementation returns `false` for unmatched
cases at the bottom of the match (`case _ => false`). This is conservative but correct. Do not add
tests for these unless they emerge from fixture data.

---

## Anti-Flakiness

### Depth budget: single counter, not per-branch

The budget is threaded as a `val` parameter, not a mutable counter. Each recursive call uses the same
`budget` value for all its children (not decremented by structural recursion -- only by `Rec` unfolding).
This means two branches of an `AndType` check can each spend up to `budget` Rec-unfolding steps. This is
intentional (conservative: total Rec unfolds across one chain, not total across all paths).

For Test 9: the budget starts at 64 at the top-level `isSubtypeOf` call. A self-referential `Rec` type
can unfold at most 64 times before termination. Verify the test completes without timeout (use the
`run { ... }` pattern from `Test.scala` which has built-in timeout).

### Structural equality vs reference equality for TypeLambda after alpha-renaming

`alphaEquiv` uses `typeEquivAlpha` which checks `s1 eq s2` for `Named` symbols outside the param index
map (line 273: `case (None, None) => s1 eq s2`). After Phase C interning, canonical types reuse the
same symbol references, so `eq` is correct. Do not switch to structural equality -- it would cause
false positives for distinct types that happen to have the same name string.

The `TypeLambda` structural equality in `TypeKey.structuralEquals` (TypeArena.scala line 200) checks
`ps1.length == ps2.length && structuralEquals(body1, body2)` -- it does NOT check param symbols for
equality. This is body-only equality and is different from alpha-equivalence. Do not confuse the two.

---

## Concerns

1. **OrType on the sub side is missing**: `OrType(A, B) <: T` requires `A <: T` AND `B <: T`. The
   current implementation falls through to `case _ => false`. This is a soundness gap (it reports
   non-subtype for things that are subtypes), not a correctness hole (no false positives). The plan
   does not list this case in the 9 tests, so it is acceptable for Phase 9. Consider a follow-up or a
   NOTE comment in Subtyping.scala.

2. **Parent-chain traversal is not memoized**: `isNamedSubNamed` re-traverses `_parents` on every
   call. For deep class hierarchies (e.g., `java.io.FileInputStream` has 5+ levels) with many repeated
   sub-checks, this is O(depth^2). Not a correctness issue; the design document does not require
   memoization at this phase. Mention in Subtyping.scala scaladoc if not already noted.

3. **Test 2 (String <: Object) requires populated parents**: Test 2 in the plan uses real class symbols
   (`stringSym`, `objectSym`) with a populated parent chain. Purely synthetic symbols have `_parents`
   not set (`isSet == false`), so `isNamedSubNamed` returns `false` conservatively. The test must
   either: (a) use actual classpath-loaded symbols, or (b) manually `import AllowUnsafe.embrace.danger`
   and call `sym._parents.set(Chunk(Reflect.Type.Named(objectSym)))`. Both approaches are viable. The
   classpath approach is more realistic but requires the fixture setup from `TreeUnpicklerTest`.

4. **`Classpath` type in extension vs `InternalClasspath` in `Subtyping`**: The extension in
   `Reflect.scala` takes `cp: Classpath` (the opaque public type). `Subtyping.isSubtype` takes
   `cp: InternalClasspath` (the concrete internal class). At runtime they are the same object. The
   delegation `kyo.internal.reflect.type_.Subtyping.isSubtype(t, other, cp, budget = 64)` works because
   `Classpath` is transparent inside `object Reflect` (see `Reflect.scala` line 786 comment). Do not
   attempt to change this -- the transparency is by design.

5. **Plan says "extend TypeOps.scala"**: The actual production code is in a standalone `Subtyping.scala`.
   The plan's supervisor check says "`TypeOps.isSubtype` present" -- this check will fail literally.
   Interpret it as "`kyo.internal.reflect.type_.Subtyping.isSubtype` present" for Phase 9 verification.
