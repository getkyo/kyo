# Phase 15 Decisions

## Callsite migration

**Total callsites migrated**: 2 source callsites (Tasty.scala extension method + Subtyping.scala
internal implementation). Tests were updated separately (9 existing + 4 new).

### Tasty.scala `isSubtypeOf` extension (line ~1154)

Changed return type from `Boolean` to `Tasty.SubtypeVerdict`. The single line delegates to
`Subtyping.isSubtype`, so the change is mechanical.

### Subtyping.scala `isSubtype` (full rewrite)

All Boolean returns replaced with SubtypeVerdict values:
- `true` -> `Sub`
- `false` -> `NotSub` (for structural mismatches)
- `false` (budget exhaustion) -> `Unknown`
- `false` (parents not set) -> `Unknown`

**OrType (T <: A | B)**: Sub if either side is Sub. NotSub only if both sides are NotSub. Otherwise
Unknown. This ensures Unknown propagates rather than being silently treated as NotSub.

**AndType (A & B <: T)**: Sub if either side is Sub (A <: T or B <: T). NotSub only if both sides
are NotSub. Otherwise Unknown. Same lattice shape as OrType since the semantics are dual.

**Applied base mismatch**: When the base subtype check returns Unknown (not Sub, not NotSub), the
Applied check returns Unknown rather than NotSub to avoid masking a partial-classpath situation.

**checkArgPairs Unknown propagation**: If an arg check returns Unknown but no arg returns NotSub,
the result is Unknown (remaining pairs are still checked to detect definitive NotSub).

**checkParents**: Returns Unknown when any transitive parent chain lookup returns Unknown and no Sub
was found. The previous `false` for unknown parents in `isNamedSubNamed` is now `Unknown`.

## Test 3 fallback (budget exhaustion)

Building 66 real Rec unfolding levels in a unit test requires constructing a deeply recursive type
graph, which is complex to wire correctly in synthetic test infrastructure. Instead, test 12 calls
`Subtyping.isSubtype` directly with `budget = 0`, which immediately returns `Unknown`. This
exercises the same code path that a genuinely budget-exhausted Rec traversal hits. Decision
documented: "Use budget=0 shortcut rather than constructing 66-deep Rec to test the Unknown path."

## cp.hasFullParentChain method

No such method was found in the Classpath internals. The existing implementation uses
`Symbol._parents.isSet` (a SingleAssign slot) to detect whether parents have been populated.
When `_parents.isSet` is false, the verdict is now `Unknown` (was `false`/NotSub). This is the
operative partial-classpath detection mechanism.

## Test 4 (missing parent chain)

Builds a symbol `Foo` without calling `_parents.set(...)`. The `_parents` SingleAssign slot is
unset, so `isNamedSubNamed` returns `Unknown`. The test asserts `fooType.isSubtypeOf(barType) ==
SubtypeVerdict.Unknown`.

## Existing test assertions

All 9 existing tests were updated from `assert(x.isSubtypeOf(y))` / `assert(!x.isSubtypeOf(y))`
to `assert(x.isSubtypeOf(y) == Tasty.SubtypeVerdict.Sub)` / `... == Tasty.SubtypeVerdict.NotSub`.
Test 9 (Rec safety) was updated to accept all three verdict values.
