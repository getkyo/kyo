# Eval exploration report

Status: IN PROGRESS (overnight run; this file is filled in as results land and closes with the final
recommendation). Charter and tracking: `proto-eval-exploration.md`. Branch commits this session are the
review trail; nothing here is a landing decision, which stays yours.

## The night's arc

1. The exp-copy isolation machinery was abandoned and reverted on your instruction; experiments are
   working-tree probes on the prototype, checkpointed as branch commits.
2. A real defect surfaced and was fixed first: the branch's clean batch build had been broken since
   22c675072d (details below).
3. Path 1 (the named outcome dispatcher) both fixes that defect and completes the first charter path.
4. Paths 2 to 4 run as probes afterward; path 5 only if their results justify it.

## The clean-build defect (found, diagnosed, fixed)

- `sbt --batch 'kyo-kernel2JVM/clean' 'kyo-kernel2JVM/compile'` crashed with a dotty
  `StaleSymbolException` at every commit since 22c675072d, on 3.8.4 and unchanged on 3.9.0-RC5.
  Incremental builds masked it the whole session.
- Diagnosis (`-Xprint-suspension`): the CanLift evidence is a splice macro; a same-module summon suspends
  the summoning file to a retry run. The module compiles in an equilibrium where only Arrow.scala and
  Eval.scala suspend (the old kernel lives in the same equilibrium: Kyo.scala, Isolate.scala). The
  map-based clause dispatch (`rehandled`) put new summons inside Eval.scala, including the silent lift in
  its `done` arm, deepening the cascade past what dotty survives.
- Ruling applied: a single macro-backed lift for user code and kernel files alike; no internal lift
  variant, no explicit nest spellings. The fix is the evaluator not lifting at all, by construction.
- Fix: the path 1 dispatcher (commit 81e73be81d). Clean batch build green; suspension set back to the
  four-file equilibrium.

## Path 1: the named outcome dispatcher

Replaces `rehandled`'s `out.map { ... }` with a named Transform, sibling of `resume`:

- A pending outcome re-chains the dispatcher after itself (the old kernel's `v.map(self)` made literal).
- `Continue`/`Continue2` rebuild the region as a fresh `Handle` from the captured interior.
- `done` passes the union representation through untouched: the silent lift is gone.
- Interior tiering mirrors the handleCont arm: empty interior collapses to the resume adapter, an unmarked
  interior folds into a chain, only a marked interior copies the three spans.

Gates:

| gate | result |
|---|---|
| proto suite | 112/112 (two new tests below) |
| clean batch build | green (was the defect) |
| bytecode: nest calls in Eval classfiles | zero (only the three expected unnest delivery sites plus resume's) |
| same-tag pipeline test | green: clause suspends on its own effect per operation, outer same-tag region answers, region re-arms per element |
| multi-shot dispatch test | green: an outer handle clause applies its continuation twice; each application rebuilds an independent region |
| board v20 vs v19 | flat within noise; two flags investigated and cleared (below) |
| new row: emittingClausesPayRegionRebuild | 85.6 us for 1000 elements: ~85 ns per full region rebuild, about 10x an in-place answer (8.7 ns), the price confined to the suspended-clause path |

Flag investigations (same-session A/B, 3 forks per side, dispatcher vs rehandled classes):

- `nestedPayloadsUnwrapInMaps` +61% in v20: pure noise. The rerun scores 5.95 +- 0.08 (dispatcher) vs
  5.96 +- 0.03 (rehandled); v20's spikes were intra-fork perturbation, its clean fork matched v19 exactly.
- `trailingMapsStayLinear` +10.7% in v20: not a regression. Median forks equal (508 vs 507 us); the HEAD
  mean was inflated by one bad-JIT fork (578 us), and both versions run ~4% above the v19 record tonight,
  a session-level offset that hits both sides equally.

Path 1 verdict: complete. Commits 81e73be81d (dispatcher) and d4639377bc (pinning tests + bench row).

## Path 2: types to their owners

Verdict: killed by evidence, with one documented optional trade.

Most of the path's scope turned out to be dissolved by path 1 before it started: the HandleLoop cold tail is
already a named method (`outcome`), the hot settled arms are excluded by the charter's own scope statement
("hot fast arms remain inline branches") and by the session's standing ban on naked virtual handler
dispatch, and `Suspend.apply` already is the typed answer method the charter asked for.

The residual probe, `Stack.pushRegion` (the cont-marker-state pairing moves from Eval's Handle arm into
Stack, which owns the marker storage), was implemented and measured: suite 112, clean build green, and a
same-session A/B (3 forks per side) showed a REAL trade with no fork lottery:

| row | control | probe | delta |
|---|---|---|---|
| emittingClausesPayRegionRebuild | 87.28 | 82.29 | -5.7% |
| statefulAnswersPaySuccessor | 124.98 | 122.42 | -2.0% |
| handleLoopAnswersInPlace | 89.14 | 88.39 | -0.8% |
| handleLoopFusesContinuation | 85.99 | 88.81 | +3.3% |

The fused row pays consistently (every probe fork above every control fork), which fires the charter's kill
criterion: it does not land. The mechanism is the known cold-bytes effect: shrinking the loop method's
Handle arm shifts JIT decisions inside the fused tower. The patch is preserved below should you judge the
streaming gain worth the fused cost; my recommendation is no.

```diff
--- a/kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Eval.scala
+++ b/kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Eval.scala
@@
                     case h: Handle[Nothing, Any, Any, Any, Any] @unchecked =>
                         suspended = Maybe.Absent
-                        stack.push(h.cont)
-                        h.handler match
-                            case hls: Handler.HandleLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
-                                stack.push(h, h.handler.tag.erased, hls.initialState)
-                            case _ =>
-                                stack.push(h, h.handler.tag.erased)
-                        end match
+                        stack.pushRegion(h)
                         cur = h.v
--- a/kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Stack.scala
+++ b/kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Stack.scala
@@
     end push
+
+    def pushRegion(h: Arrow.Handle[?, ?, ?, ?, ?]): Unit =
+        push(h.cont)
+        h.handler match
+            case hls: Arrow.Handler.HandleLoopState[?, ?, ?, ?, ?, ?, ?] => push(h, h.handler.tag.erased, hls.initialState)
+            case _                                                       => push(h, h.handler.tag.erased)
+    end pushRegion
```

## Path 3: typed helpers for the unowned logic

PENDING

## Path 4: typed currency through the loop

PENDING

## Final recommendation

PENDING
