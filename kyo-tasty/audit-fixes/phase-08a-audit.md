# Phase 08a Audit — TypeArena depth-bound (B8)

**HEAD:** bb03b101f
**Path:** kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/TypeArena.scala

## Verdicts

**Depth threading: PASS.** Recount of arms in `recurse`: Applied(2), Function(2), Tuple(1), ByName(1), Repeated(1), Array(1), AndType(2), OrType(2), Refinement(2), Rec(1), Annotated(1), SuperType(2), Wildcard(2), Skolem(1), MatchType(3), FlexibleType(1), TermRef(1), TypeLambda(1) = 27 internRec call sites (commit message says "22 arms", which counts type constructors instead). All siblings pass `depth` unchanged; `internRec` increments by 1 before `recurse(t, depth + 1)`. Stack-depth semantics correct: increment tied to recursion chain depth, not branching factor. No arm leaks unincremented.

**MaxDepth=1024: PASS.** Per-frame footprint ~100-200B, so 1024 frames stays well under JVM default stack (512KB-1MB). Real Scala type nesting rarely exceeds ~50; 20x headroom is right.

**DepthExceededException: PASS.** `final class DepthExceededException(msg: String) extends RuntimeException(msg)` on `object TypeArena`. Publicly catchable, message embeds depth. Minor: commit message references `(depth: Int)` constructor; actual takes String. Doc-level only, no code defect.

**Boundary tests 6+7: PASS-WITH-NOTE.** ±1 around 1024 pinned (1023 succeeds, 1025 throws). The exact-boundary `depth == 1024` case (guard is `>= MaxDepth`) is bracketed but not directly asserted.

## NOTE for Phase 08b prep

Consider adding a test at exactly `MaxDepth` (1024 levels) to nail the `>=` boundary; current ±1 leaves the equality case implicit. Low priority — the guard inequality is unambiguous in source.

## Overall: READY
