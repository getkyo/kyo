# Phase 08a Decisions

## Finding addressed: B8

`TypeArena.internRec` (lines 81-96 of TypeArena.scala) was recursive with no depth bound, making it vulnerable to stack overflow on pathologically nested `Applied(Applied(...))` type structures.

## Approach chosen: depth-bound (throw on too-deep)

The plan offered three options: depth-bound throw, iterative trampoline, or worklist-based. The depth-bound approach was selected because:
- It matches the plan's explicit BEFORE/AFTER contract.
- It is the simplest change that converts `StackOverflowError` (uncatchable in some contexts) to a structured `DepthExceededException` that callers can intercept.
- A worklist or trampoline would require a larger structural rewrite of the local `recurse`/`internRec` closure pair without meaningful benefit; the depth bound makes the precondition explicit and auditable.

## Implementation

Both local defs inside `merge` were updated:

- `recurse(t: Tasty.Type, depth: Int)` threads the current depth through all recursive arms. Every `internRec(x)` call becomes `internRec(x, depth)` (same depth, not incremented, because `recurse` does not itself count as an extra frame; depth is counted at the `internRec` entry point).
- `internRec(t: Tasty.Type, depth: Int)` checks `depth >= TypeArena.MaxDepth` first and throws `DepthExceededException` if exceeded. It passes `depth + 1` when calling `recurse(t, depth + 1)`.
- The top-level entry point `for (_, t) <- map do internRec(t, 0)` passes 0.

`TypeArena.MaxDepth = 1024` and `TypeArena.DepthExceededException` were added to the companion object.

No default parameter was added to `internRec` or `recurse` (per the no-default-params-internal rule); all callers pass the depth explicitly.

## Tests added (TypeArenaTest.scala)

Test 6 (B8/INV-019): builds a 2000-level deep `Applied` chain, interns it, and asserts `merge` throws `TypeArena.DepthExceededException` with message containing `"depth 1024 exceeded"`.

Test 7 (B8 boundary): builds a 1023-level deep chain (MaxDepth - 1) and asserts `merge` succeeds with `canon.values.nonEmpty`.

## Produced invariant

INV-019: `TypeArena.internRec` enforces a recursion-depth cap (1024) and throws a structured `DepthExceededException` instead of a `StackOverflowError` on pathologically nested types.
