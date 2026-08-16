# Eval exploration report

Status: CLOSED with a final recommendation (last section). Charter and tracking:
`proto-eval-exploration.md`. Branch commits this session are the review trail; nothing here is a landing
decision, which stays yours. Closing state: suite 112/112, clean batch build green, tree clean.

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

Post-closure refinement (your morning review, landed 6cc0dc2ccd): the rebuild lambda inlined into the
Handle instance, tiers unified through cached empty spans (an Eval pushing zero entries is Bind
semantics), the captured continuation renamed `body` to dodge the Handle member shadow the lambda had
been shielding. Same-session A/B: streaming row -6.9% (82.5 vs 88.7 us), fused flat. The empty-span vals
stay hoisted by your ruling: Span.empty caches primitive tags only and allocates per call for reference
tags; adding ClassTag[AnyRef] to Span's cachedEmpty upstream is an optional kyo-data follow-up that would
remove two of the three.

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

Verdict: killed by the E9/E10 evidence plus a full site audit (E11).

The audit classified every remaining cast, `@unchecked`, and erased pattern in Eval (30 sites):

- **Representation assertions** (the `asInstanceOf[Any < S2]` family): all sit inside the two named
  adapters, `resume` and `outcome`, at currency positions. Load-bearing per the skill (they block the lift
  from corrupting); not a typing opportunity.
- **Erasure-forced**: the `stack(i)` storage cast and the `unnest` delivery. The storage boundary is the
  category's textbook case.
- **Erased handler-kind patterns**: the two dispatch ladders (the Suspend arm, the settle arm). These are
  the hot lines: E9/E10 measured that even an eight-line move adjacent to them seesaws the fused row by
  3%, and the only "typed" replacement is virtual handler dispatch, banned this session for megamorphic
  dispatch pathology.
- The cold logic the path targeted is already clean: `dump` is typed and castless, the adapters are named
  Transforms.

The path's target set is empty where it is safe and hot where it is not.

## Path 4: typed currency through the loop

Verdict: killed statically by its own kill criterion ("the opaque wall forces more assertions than it
removes").

Site-by-site (E12): none of Eval's existing casts is a widening that typing `cur` would absorb; they are
representation assertions and storage casts that remain either way. Conversely, `cur: Any < Any` would
require a new conversion at every arrow-valued assignment (`cur = c.a`, `cur = Chain(out, outcome(...))`,
the two `cur = p` continue arms, `cur = done` twice, `cur = h.v`, `cur = d`): roughly seven added casts
(or `fromArrow` calls, which change bytecode and fail the identical-bytecode gate) to remove zero. The
implicit lift firing silently at any of these positions is also exactly the defect class and the compiler
crash the night opened with.

## Path 5: integrated rewrite

Not attempted, by the charter's own terms: it composes the winners of 1 to 4, and the only winner is
path 1, already landed. This is the charter's anticipated acceptable outcome: "the paths' individual
landings stand and the full rewrite is declared not worth it."

The one piece of path 5 with standalone value is the conformance net: a shared program corpus run through
kyo-kernel and the proto asserting identical results across the hostile axes. That is a test asset, not a
rewrite, and I recommend it as a follow-up on its own merits.

## Size ledger (honest reading)

| checkpoint | proto lines | Eval | casts | @unchecked | erased lambdas | suite |
|---|---|---|---|---|---|---|
| baseline (b345a1412d) | 2275 | 236 | 37 | 42 | 14 | 110 |
| close (8e776a2d56) | 2298 | 259 | 41 | 43 | 12 | 112 |

Path 1 did NOT shrink Eval textually: +23 lines and +4 casts, against the charter's "slightly smaller"
expectation, which was wrong. The lines are the interior tiering (absent in rehandled, which always paid
the three-span copy) and the dispatcher's explicit arms; the four casts are representation assertions in
category, each at a currency position the map version handled with a silent lift. What shrank is the
mechanism count: the evaluator no longer contains `map`, the implicit lift, or any same-run macro summon,
and the erased-lambda count dropped. Whether that trade meets your bar is the review question; the
alternative (the map dispatch) is not on the table, since it does not survive a clean build.

## Final recommendation

1. **Review and keep the branch as it stands** (commits 81e73be81d, e2d2b6063d, d4639377bc, a2d599ca30,
   8e776a2d56): the dispatcher is simultaneously the clean-build fix, the removal of the silent lift, and
   path 1 complete with all gates green (suite 112, clean batch build, zero nest calls in Eval bytecode,
   boards flat in controlled A/B, rebuild row measured at ~85 ns/element).
2. **Do not land pushRegion** (patch preserved in the path 2 section): a consistent 3.3% fused-row cost
   buys a 5.7% streaming gain; my judgement is the fused tower wins, but the trade is documented for
   yours.
3. **Declare the exploration closed at path 1**: paths 2 to 4 are killed by evidence recorded in the
   charter, path 5 has nothing to integrate.
4. **Follow-ups worth their own sessions**: the kyo-kernel/proto conformance corpus; and if the streaming
   path becomes hot in practice, revisiting region establishment with the fused tower protected first.
5. The kernel skill now carries tonight's two durable lessons (single lift and the suspension
   equilibrium; dissolve before scaffolding), commit e2d2b6063d.
