# Phase 22b Decisions

## MaxDepth value

`TypeArena.MaxDepth = 1024` (confirmed in `TypeArena.scala` line 116).

The depth counter increments in `internRec` before calling `recurse`: a type at nesting level N calls `internRec` at depth N. The guard fires at `depth >= MaxDepth`, so depth 1023 is the last safe call. With `MaxDepth - 1 = 1023` Rec wrappings around a Named leaf, the deepest `internRec` call for the leaf is at depth 1023 (safe). One additional level (1024 wrappings) would call `internRec(leaf, 1024)` and throw.

## Rec/RecThis fixture construction approach

True circular ADT values (`rec = Rec(RecThis(rec))`) are not constructible with immutable case classes. The production decoder uses mutable placeholder slots. For test purposes, the fixture instead builds `Rec(RecThis(inner))` where `inner` is a separate `Rec(Named)`. This exercises:

- The `internRec` leaf treatment for `RecThis` (returns type as-is without recursing into the `rec` field).
- The `inProgress` cycle-break in merge (the outer Rec is registered as in-progress before its child is merged).
- Canonical map reference equality: `canon.intern(sameType)` called twice returns the same ref.

The `PlatformHashingState` thread-local prevents infinite hash recursion over structural cycles in `TypeKey.computeHash`.

## Real-recursion vs budget=0 differentiation

Phase 15 Test 12 calls `Subtyping.isSubtype(stringType, intType, cp, budget = 0)` directly, which returns `Unknown` immediately at the `if budget <= 0 then Unknown` guard. This does not exercise any recursive Rec unfolding.

Phase 22b Test 14 builds a 66-level deep `Rec(Rec(Rec(...Named...)))` chain and calls `t.isSubtypeOf(t)` which starts with `budget = 64`. Each Rec unfolding decrements the budget by 1 via `isSubtype(subUnfolded, supUnfolded, cp, budget - 1)`. After 64 unfolds the budget reaches 0 and `Unknown` is returned mid-chain (at the 65th Rec level, before reaching the Named leaf). This confirms that the budget mechanism terminates real unbounded Rec traversals, not just the trivial zero-budget case.
