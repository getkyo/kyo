# kernel2 backlog

Living document; updated as agents finish and rulings land.
Last update: 2026-08-12, HEAD `90d1a4c285`.

## 1. In flight

| item | agent | lands as |
|---|---|---|
| handleFirst + handleCatching implementation | kernel2-impl | commits on this branch; kernel side landed at `90d1a4c285` (ArrowEffect, Handler, Handlers, Eval, KyoInternal, Effect), message says tests are the next commit; suite run pending |
| Bracket as a node: `Kyo.Bracket` similar to `Kyo.Handled` | bracket-node-design | `bracket-node-design.md` |
| Bracket as a handler kind on the Handlers stack | bracket-handler-design | `bracket-handler-design.md` |

The bracket pair is adversarial: each steelmans its architecture, ends with the
same comparison table, and the judgment happens in-session. Both carry the fixed
contract: use fully interruptible; no interruption between acquire finishing and
use starting (resource held implies obligation reachable); release always executes
on settle, unwind, and discard, failures suppressed onto the primary; release runs
to completion once started. The drive-era prototype is failure-modes-only reference.

## 2. Landed designs awaiting rulings

### 2.1 Exception enrichment (EffectTrace successor)
- Doc: `exception-enrichment-design.md` (committed `460ac993ee`)
- Recommendation: reconstruct-at-throw over the live chain (Transforms and
  Suspends already carry Frames; walk at kernel boundaries; zero cost unless
  a failure happens). Same walker implements IOTask.fiberTrace (five tests
  currently ignored in `kyo-core/shared/src/test/scala/kyo/scheduler/IOTaskTest.scala`).
- Rulings: section 9 (9.1 name, 9.2 pre-suspension history loss, 9.3
  NoStackTrace handling, 9.4 cap policy, 9.5 JS/Wasm addSuppressed proof,
  9.6 the shared rewriting combinator that keeps Debug.trace alive, 9.7
  which Eval arms get guards).

### 2.2 Isolate / fork over the reified handler stack
- Doc: `isolate-kernel2-design.md` (committed `fe06ac4df9`)
- Recommendation: fork as a transplant of the handler stack (rebuild with
  identity exits and per-cell policy), `Fork.Inherit`/`Fork.Skip` plus
  `forkState`, a slimmed two-phase `Isolate` witness kept for the write-back
  and the static row, `Fork.Inherit` gated on a new answering handler kind
  that cannot short-circuit. Deletes the old Context map. One regression
  risk: context-read tag walks (library-only mitigation in section 5.2).
- Rulings: section 10 (10.1 answering kind vs declared policy, 10.2
  Fiber.initUnscoped restore direction, 10.3 inherit-by-default or opt-in,
  10.4 naming, 10.5 state publication contract documented vs enforced).

### 2.3 IOTask integration r2
- Doc: `iotask-kernel2-integration-r2.md` (committed `0795446afc`),
  self-contained revision of `iotask-kernel2-integration.md` (r1), every
  delta marked "Changed from r1".
- Rulings: section 9. R1 Eval.partial deadline for JS/Wasm slicing; R2
  charge the settled-answer arm one budget step (JMH-gated); R3 abandoned
  releases run synchronously, Unit-returning entry (recommended a); R4
  context stays a def with the conditional-subclass factory; R5 add
  region-peeling dispatchFirst, landing together with handleFirst; R6
  Safepoint.stop cheap negative.
- Requirements it places on the bracket track, to be honored by the design
  judgment: R-B1 (Unit-returning synchronous discard entry), R-B2 (a throw
  escaping Eval.partial already ran the releases it unwound), R-B3 (a
  Loop.done that truncates the spine runs the discarded cells' releases).

## 3. Implementation queue (order by dependency, after rulings)

1. **Bracket primitive**: judgment over the design pair here, then
   implementation. Inputs: `bracket-node-design.md`,
   `bracket-handler-design.md`, R-B1/R-B2/R-B3 above,
   `kernel2-finalizer-design.md` (background). Task #73.
2. **dispatchFirst** (region-peeling, `private[kyo]`): spec in
   `iotask-kernel2-integration-r2.md` section 5.5; lands next to the
   handleFirst work from `90d1a4c285`. Ruling R5.
3. **Exception enrichment implementation**: per
   `exception-enrichment-design.md` after its rulings; also un-ignores the
   fiberTrace tests.
4. **Isolate implementation**: per `isolate-kernel2-design.md` after its
   rulings; largest single item (19 downstream files per
   `kernel-parity-gaps.md`).
5. **Eval.partial deadline** (R1) and **settled-answer budget charge** (R2,
   JMH-gated) and **Safepoint.stop early exit** (R6): small kernel changes
   from the IOTask rulings.
6. **IOTask port** itself: `iotask-kernel2-integration-r2.md` section 5.
   Consumes 1-5. Then task #9 (Sync.ensure/Scope tests) and task #11
   (green kyo-core suite).

## 4. Standing open items

| item | pointer |
|---|---|
| kyo-bench arena rows old vs new kernel (task #8) | `kyo-bench` module; kernel boards in `kyo-kernel2/jvm/src/jmh/.../KernelBench.scala` and `kyo-kernel/bench/...` |
| Defaulted (optional context) redesign, parked (task #31) | `kyo-kernel2/.../internal/KyoInternal.scala` (Kyo.Defaulted TODO) |
| Safepoint overflow one-shot report decision (task #57) | `kyo-kernel2/.../internal/Safepoint.scala` |
| Review TODOs: Loop.scala continue/Nested.lift, Handlers encapsulation, Effect.guarded placement (tasks #58, #59) | `kernel/Loop.scala:181` area, `internal/Handlers.scala:8` TODO, `kernel/Effect.scala` |
| Fold Implicits back into Pending.scala for old-kernel layout parity | `kernel-parity-gaps.md` section 2; user call |
| Remove stray empty dir `kyo-kernel2/kyo-kernel2/` | hygiene |
| JS/Native/Wasm compile check of kernel2 | `kernel-parity-gaps.md` section 4; shared Safepoint uses AtomicReferenceArray and Thread.threadId() |
| handleCatching consumer validation at the Abort port | `90d1a4c285` implements it by composition; Abort in kyo-prelude is the consumer |

## 5. Accepted or watched performance positions

- Boards and method: `compile-execution-analysis.md` (in-session A/B tables
  at the end), `node-fusion-report.md` (the mandate and the fusion-era
  boards).
- Compile time: new kernel beats old on all measured fixtures
  (MapChainDeep100 0.39x, ForCompDeep25 0.85x, ForComprehensions 0.86x).
- fusionAfterSuspension 1.84x time / 1.16x alloc: standing structural
  position (both kernels allocate two objects per map on an unanswered
  suspension; the delta is one reference per map plus resume-side costs
  the old kernel pays back); JFR attribution in
  `compile-execution-analysis.md` context and the session record.
  fusionAfterSuspensionRunOnly exempt by ruling.
- deepRecursionPaysRescuesOnly 1.05x time against 0.43x alloc: watch item;
  the rescue-path time moved with the candidate design; next optimization
  target if the campaign reopens.
