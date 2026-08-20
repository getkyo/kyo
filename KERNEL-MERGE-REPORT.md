# kyo-kernel2: proto adopted as the kernel, test corpora merged

Session report. Branch `worktree-effervescent-painting-backus`, commits `05cbc7eb12..HEAD`.

Starting state: you had moved the proto sources into `kyo/kernel` and `kyo/kernel/internal`, replacing the
previous kyo-kernel2 implementation. Main compiled. The test tree did not, and no suite had ever run against
this implementation.

Ending state: **776 tests pass, 0 fail**, on JVM. Clean batch build green. JS and Native test compiles green.

---

## 1. Kernel changes

Three, all narrow. No new concepts, no re-architecture.

### 1.1 `Loop` dispatched on the old node type (real bug, systematic)

Every `Loop` combinator detected "this value is pending" with `case arrow: Arrow[?, ?, ?]`. That was correct
when `Arrow` was an arm of the pending union. It is not one in this kernel, where a pending value is a `Kyo`.
So the suspended branch was **dead at all thirteen sites**:

```scala
@tailrec def loop(v: Outcome[A, O] < S): O < S =
    v match
        case next: Continue[A] @unchecked => loop(run(next._1))
        case arrow: Arrow[?, ?, ?]        => suspended(v)   // never matched
        case res                          => res.asInstanceOf[O < S]
```

`Loop.apply` returned a *suspended* outcome as if it were the loop's result, and `Loop.whileTrue` discarded a
suspended body and spun forever. Fixed to `case _: Kyo[?, ?] =>` at all thirteen sites.

Found by a hang: `LoopTest` "with suspended body" ran for 12 minutes before I took a thread dump. That test had
never run, because the suite had never compiled.

### 1.2 `Effect` lost its by-name `defer`

The move renamed `Effect.defer` to the deferral-node constructor and dropped the by-name form that both the
previous kyo-kernel2 and kyo-kernel carry, and that the stack above depends on. Restored over `Kyo.Defer`,
whose payload is a method, so the body runs when the evaluator reads it:

```scala
private[kyo] inline def deferInline[A, S](inline f: => A < S): A < S =
    new Kyo.Defer[A, A, A, S]:
        def value = f
        def contA = Arrow.id[A]
        def contB = Arrow.id[A]
```

It coexists with the two node constructors by arity.

### 1.3 `EffectTrace` ported

Per `proto-effecttrace-plan.md` and its review. New `kyo/kernel/internal/EffectTrace.scala`, plus attach sites in
the drive and one indexed accessor on `Stack`.

The load-bearing idea: the walk separates a **value position** from an **arrow position**. Six kernel
constructions mint a node that is its own continuation (`suspendWith`, the three `*With` handlers, the two
emitting-clause adapters). Reached in the arrow role, each emits one frame and stops; reached as a node, the walk
would re-enqueue itself forever, inside a catch, with an exception in flight. Termination is structural, not
guarded.

The drive attaches at every site that enters user code and the boundary only splices. The clean batch build
confirms the walk summons no lift evidence, so the macro-suspension equilibrium is intact.

---

## 2. Test merge, file by file

Driven by source file, merging the proto corpus, the previous kyo-kernel2 corpus, and kyo-kernel's.

| source | test | what happened |
|---|---|---|
| `kyo/Arrow.scala` | `ArrowTest` | rewritten for the current surface (`id`/`apply`/`recursive`/`chain`/`head`/`tail`) |
| `kyo/Kyo.scala` | `KyoTest`, `KyoForeachTest`, `KyoForeachCollTest` | **were 100% commented out**; restored against `handleCont` |
| `kyo/kernel/ArrowEffect.scala` | `ArrowEffectTest` | ascriptions; two partial-evaluation cases parked |
| `kyo/kernel/Effect.scala` | `EffectTest` | added coverage for the node constructors and the identity collapse |
| `kyo/kernel/Loop.scala` | `LoopTest` | already a superset of kyo-kernel's; unchanged |
| `kyo/kernel/Pending.scala` | `PendingTest` | took kyo-kernel's missing cases: for-comprehension, pure-function lifts at six arities, `handle` through arity ten, point-free `map` |
| `kyo/kernel/internal/CanLift.scala` | `CanLiftTest` | **new**, ported from kyo-kernel |
| `kyo/kernel/internal/Eval.scala` | `EvalTest`, `EvalCaptureTowerTest` | proto corpus merged with the previous kernel2's; continuation-replay counts re-expressed over `handleCont` captures |
| `kyo/kernel/internal/EffectTrace.scala` | `EffectTraceTest`, `EffectTracePhysicalTest` | **new**; physical-trace assertions split into `jvm-native` because they are not portable |
| `kyo/kernel/internal/Handler.scala` | `HandlerTest` | **new** |
| `kyo/kernel/internal/Nested.scala` | `NestedTest` | **new**; pins the representation contract |
| `kyo/kernel/internal/Safepoint.scala` | `SafepointTest` et al | one case parked on partial evaluation |
| `kyo/kernel/internal/Stack.scala` | `StackTest` | rewritten for the current surface |

### `Loop.continue` now needs an ascription

You removed the `< Any` hack from `Loop.continue` (the one your TODO cursed). The consequence is that every call
site in a handler clause needs the payload ascribed:

```scala
ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)
```

Every call site in the corpus and the benchmarks now carries it. Worth knowing this is the user-visible cost of
the non-hacked signature; I did not touch the signature.

---

## 3. The five failures from the first full run

The suite ran for the first time at 742/747. Each failure and its resolution:

1. **`StackTest` "a nested chain is flattened fully"** — my expectation was wrong. `push` walks the right spine
   only, so a left-nested chain lands as one entry. Not a gap: the drive's done branch matches a `Chain` entry and
   re-defers it, which flattens it on the next turn. Both shapes now have their own case.

2. **`EvalTest` "a clause's re-raise of its own tag"** — the previous kernel2 corpus encoded the *opposite law*
   from the proto corpus, and both were in the merged file. The signature settles it: a clause **suspending** sits
   at row `S`, outside the region, so the successor answers; a clause's **answer** carries row `E & S`, which is
   region currency, so this handler answers it. The implementation agrees (`dump(pos)` alone for an answer,
   `dump(pos)` **and** `pop()` for a clause suspension). The answer shape was already pinned by the proto corpus;
   I rewrote the case to pin the suspension shape and dropped the stale law.

3. **`EffectTraceTest` emitting-clause pin** — my new pin expected the region body's frames. Accepted limit,
   recorded in the test: the clause's answer is settled, so the drive resumes the capture strictly inside `map`
   rather than through a delivery site, and those frames are consumed before any attach site sees them.

4. **`ImplicitsTest` Render** — no `Render` instance for the pending type in this kernel. Parked.

5. **`PendingBytecodeTest` "lift of a concrete class"** — see below, this one needs you.

---

## 4. Things that need you

### 4.1 The concrete-class lift moved from 2 bytes to 5

`PendingBytecodeTest` pinned the lift of a final class at 2 bytes, a bare cast. It is now 5. The previous
kernel2's emission analyzed the type and proved a final class admits no nested payload; this lift has two arms, a
primitive fast path and `Nested.lift`, with no such analysis, so a concrete class takes the same static call a
generic value takes.

The cost is one union `instanceof` on the most common boundary in the library. Restoring the elision means an
emission macro that reads the type, which is machinery this lift deliberately does not have.

**I updated the pin and labelled it MOVED rather than silently re-baselining it.** Your call whether the elision
comes back.

### 4.2 Parked surface, complete inventory

Each is kept as commented code in the file that would implement it:

| surface | parked in |
|---|---|
| partial evaluation (`Eval.partial`, `handlePartial`) | `EvalTest`, `ArrowEffectTest`, `SafepointConcurrencyTest` |
| `Effect.catching` | `EffectTest`, `EffectTraceTest` (your exploration TODO stands) |
| `Render` for the pending type | `PendingTest`, `ImplicitsTest` |
| `toString` on computations and arrows, `Arrow.step` | `KyoTest`, `ArrowTest` |
| `ContextEffect`, `Effect.detach` | `EffectTest`, `PendingTest` |
| `ArrowEffect.dispatchFirst` | `ArrowEffectTest` |
| the deferred block's own frame in a trace | `EffectTraceTest` (known limit, guarding it would put a try region on the drive's hottest arm) |

### 4.3 Bracket and Park

`reviews/BRACKET-PARK-DESIGN.md` is written, and `reviews/BRACKET-PARK-REVIEW.md` is the held-out review of it.

**Verdict: REWORK**, 7 blocking findings, 14 non-blocking.

The reviewer confirmed the central reduction: neither `Bracket` nor `Park` needs to be a new `Kyo` node kind,
because `Kyo.Defer` already reifies both. What does not hold is exactly-once. Four of the seven blocking findings
share one root cause: the design never fixed *where a finalizer's identity lives*. The abandonment walk mints a
fresh `Finalizer` from an `Acquire`, so a resume and an abandonment do not share a CAS and both release.

Both documents are in the tree for you to read.

---

## 5. Benchmarks

`ProtoKernelBench`, whole class, `-f 2`, 5x1s warmup, 5x1s measurement. 17 of 17 rows returned. Raw JSON in
`bench-results/kernel-merge-0820/`.

`YetAnotherProtoBench` pointed at the removed `kyo.proto` package and duplicated `ProtoKernelBench` row for row,
so it is gone and `ProtoKernelBench` is the kernel's benchmark class. It gained the two rescue-boundary rows it
was missing, so the row set matches the last proto board.

### 5.1 The board

`pre-merge` is `bench-results/threeway-0819/proto-nolower-f3.json`, the last proto board before this session.
`best k2` is the previous kyo-kernel2's own board, which only overlaps on some rows.

| row | now (us/op) | error | pre-merge | delta | best k2 |
|---|---:|---:|---:|---:|---:|
| evalFixedOverhead | 0.01 | 0.00 | 0.01 | - | 0.00 |
| fusionAllocatesNothing | 0.55 | 0.00 | 0.55 | -0.4% | 0.55 |
| deepRecursionNoRescue | 1.94 | 0.02 | 1.95 | -0.6% | - |
| deepRecursionOneRescue | 2.79 | 0.02 | 2.81 | -0.4% | - |
| nestedPayloadsUnwrapInMaps | 6.09 | 0.05 | 6.08 | +0.2% | - |
| fusionPastBudgetPaysRescuesOnly | 33.87 | 0.73 | 34.24 | -1.1% | 31.77 |
| uncachedValuesPayBoxingOnly | 33.92 | 0.36 | 34.32 | -1.2% | 33.46 |
| idleHandlerAddsNothing | 34.03 | 0.39 | 34.12 | -0.3% | 31.78 |
| continuationBodiesFuse | 35.02 | 0.23 | 34.96 | +0.2% | 26.07 |
| deepRecursionPaysRescuesOnly | 51.71 | 0.26 | 49.40 | **+4.7%** | 49.48 |
| suspensionFusesContinuation | 97.09 | 0.78 | 96.72 | +0.4% | 28.38 |
| handleLoopAnswersInPlace | 151.69 | 1.21 | 152.69 | -0.7% | 78.64 |
| emittingClausesPayRegionRebuild | 157.00 | 1.36 | 161.60 | -2.9% | - |
| handleLoopFusesContinuation | 163.79 | 1.33 | 165.55 | -1.1% | - |
| suspensionBaseline | 185.73 | 1.19 | 169.41 | **+9.6%** | 79.34 |
| statefulAnswersPaySuccessor | 546.35 | 90.09 | 557.46 | -2.0% | 106.93 |
| trailingMapsStayLinear | 1403.98 | 111.11 | 1703.60 | -17.6% | 718.63 |

### 5.2 What the EffectTrace wiring costs, isolated

The wiring adds eight `try` regions and eight hoisted `val`s to a drive that is `inline`, so it multiplies by
every `Eval(...)` site. One variable, same session: the same class with the pre-EffectTrace `Eval.scala` swapped
in, everything else identical. Two traced runs bracket it, so the within-session spread is visible.

| row | traced #1 | traced #2 | no trace | trace cost |
|---|---:|---:|---:|---:|
| deepRecursionPaysRescuesOnly | 51.71 | 51.79 | 52.04 | -0.6% |
| handleLoopAnswersInPlace | 151.69 | 151.78 | 150.59 | +0.8% |
| idleHandlerAddsNothing | 34.03 | 34.11 | 33.80 | +0.8% |
| suspensionBaseline | 185.73 | 185.66 | 180.58 | **+2.8%** |
| trailingMapsStayLinear | 1403.98 | 1486.91 | 1599.46 | -9.6% |

The two traced runs agree to within 0.2% on every stable row, so 2.8% on `suspensionBaseline` is a real cost and
the sub-1% numbers are not.

**EffectTrace costs 2.8% on `suspensionBaseline` and nothing measurable on the other rows.** `trailingMapsStayLinear`
spans 1404 / 1487 / 1599 across three runs and cannot carry an attribution at all; it needs more forks before
anything is claimed about it.

### 5.3 Two rows are red against the pre-merge board and I have not closed them

`deepRecursionPaysRescuesOnly` +4.7% and `suspensionBaseline` +9.6%.

The isolation above accounts for **2.8 points of the 9.6** on `suspensionBaseline` and **none** of the 4.7 on
`deepRecursionPaysRescuesOnly`, where the trace measures as free. So the residual is not the EffectTrace wiring.

Nothing else I changed touches those paths: the by-name `Effect.defer` adds an overload the benchmarks never
call, and the `Loop` node-type fix is in `Loop.apply` and friends, which these rows do not use. That leaves the
pre-merge board being cross-session as the untested explanation, and cross-session is exactly the comparison
that cannot support a claim at this magnitude.

**This is open, not accepted.** The experiment that closes it is a same-session A/B: check out `b3d7579dfe` in a
second worktree, run the same two rows there and here back to back, and read the delta. I did not run it because
you said to leave regressions for now, and it needs the machine quiet.

---

## 6. Verification summary

| check | result |
|---|---|
| `kyo-kernel2JVM/test` | **776 passed, 0 failed**, 1 ignored |
| `kyo-kernel2JVM/clean` then `compile` | green, so the macro-suspension equilibrium holds with EffectTrace added |
| `kyo-kernel2JS/Test/compile` | green |
| `kyo-kernel2Native/Test/compile` | green |
| `kyo-kernel2JVM/Jmh/compile` | green |
| benchmark rows returned | 17 of 17 |

Not run: JS and Native test *execution* (compile only), and Wasm.

