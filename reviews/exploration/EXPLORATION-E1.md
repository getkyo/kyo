# Exploration E1: stateful state as an eval register — FALSIFIED

Branch `exp-e1-register`, commit `ed39151070`, on top of `26f14ecdd4`. 979 tests green (976 prior plus three
new pins). The design was implemented completely, measured cleanly, and does not work. This report records
why, so the next attempt starts from the mechanism this one exposed.

## The design

Per answer, `handleLoopState` wrote the state box into the stack's `states` array (`putState`), and an array
store is an escape scalar replacement cannot undo. The old kernel keeps state in a local of its
inline-expanded drive and escape analysis eliminates the per-answer box. Supporting evidence going in:
`-XX:AutoBoxCacheMax=20000` (making every state box a cache hit, so no allocation) takes kernel2's
`statefulAnswersPaySuccessor` from 612 to 235 us while the old kernel only moves 144 to 136.

Implemented: the state rides the eval loop as tail-recursive parameters (`hs`: the stateful entry whose slot
is stale, `hstate`: the true state); the slot becomes the spill target; `dispatchLoopState` folded back into
the loop so the register stays a local.

## The numbers (same session, -f 2 -wi 5 -i 5, gc profiler)

| row | base us | register us | old kernel us | vs k1 | base B/op | register B/op | k1 B/op |
|---|---:|---:|---:|---:|---:|---:|---:|
| `statefulAnswersPaySuccessor` | 543.01 | **540.89** | 146.09 | 3.70x | 798,124 | **798,124** | 1,040,140 |
| `suspensionBaseline` | 180.77 | **193.16** | 124.04 | 1.56x | 640,137 | 640,137 | 560,080 |
| `handleLoopAnswersInPlace` | 157.11 | **168.33** | 128.66 | 1.31x | 640,137 | 640,137 | 960,135 |
| `handleLoopFusesContinuation` (Proto) | 174.72 | **183.64** | n/a | | 640,153 | 640,153 | |
| `fusionAfterSuspensionRunOnly` | 0.91 | 0.87 | 0.27 | 3.22x | 1,264 | 1,264 | 0 |

Verdict by the numbers: the target row is unchanged (-0.4%, noise), allocation is byte-identical, and three
non-target rows regressed 5 to 7 percent from the added spill calls and try-wraps on hot arms.

## Why it failed: the register removed the wrong escape

The state box has two escapes:

1. It is born inside the clause as `Continue2(state + 1, answer)` and **returned through `h.run`**, a virtual
   call the JIT does not reliably inline (`HandlerLoopState::run ... no static binding` in the logs).
   Crossing that un-inlined boundary makes both the `Continue2` and the box real heap allocations, before the
   eval touches them.
2. It was then stored into the `states` array by `putState`.

The register eliminates only escape 2. Escape 1 is where the allocation actually happens, so B/op did not
move by a single byte, and the AutoBoxCache gain was never reachable this way. The array store was an escape,
but not the load-bearing one; the session's framing over-weighted it.

The corollary is the real finding: **the old kernel's win is the statically bound clause call**, not the
local state per se. Its inline-expanded drive makes the clause call site monomorphic by construction, the
clause inlines, `Continue2` and the box are scalar-replaced together. Any design that keeps the clause behind
the shared eval's virtual dispatch keeps the allocation.

## What this points at instead

- **Option B from the session (call-site-specialized settled drive)**: `handleLoopState` is already `inline`;
  a small per-call-site loop that runs the settled suspend-answer cycle with statically bound clause and
  local state, bailing to the shared eval for anything else, reproduces the old kernel's mechanism exactly.
  Costs per-call-site code size (the compile-time trade kernel2 made the other way).
- Or making `h.run` reliably devirtualize/inline in the eval; but that is profile-dependent and megamorphic
  in any real application with more than two stateful handlers, so it cannot be the load-bearing mechanism.

## Spill-site inventory (as implemented, all tested green)

| spill site | covered by |
|---|---|
| fold past a foreign region (HandlerCont/HandlerLoop dispatch) | new pin: "survives a foreign crossing that folds the region" |
| park between answers | new pin: "survives a park between answers" |
| throw reaching the unwind (Defer payload, Catching body, Binding resume) | new pin: "survives a failure a scope below the region recovers" |
| delivery into the entry itself | existing handleLoopState suite |
| dispatch of a different stateful region | existing "state survives an inner handler's exit" |
| Park restore | existing park suite |

## Open risks (moot for adoption, relevant for the record)

- The monomorphic-vs-megamorphic question was never reached: the design failed before morphism mattered.
- The 5-7% regressions localize to the try-wraps and spill calls added to the Defer/Catching/Binding arms
  and the register threading; they are the cost floor of any register protocol in the shared loop.

## Verdict

Do not adopt. The mechanism is now precisely located: the per-answer allocation survives because the clause
call is virtual; only a statically bound clause (per-call-site expansion, Option B) or eliminating the
per-answer `Continue2`+box currency entirely can reach the old kernel's number on this row.
