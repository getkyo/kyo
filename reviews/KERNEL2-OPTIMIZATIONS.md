# The three optimizations, presented for review

Each section: the problem with its evidence, the change as code, why it is sound, what it measured,
and what needs sign-off. The combined change set applies cleanly (`KERNEL2-ALL-CHANGES.diff`, 889
insertions across 9 files); raw per-candidate diffs and full exploration reports sit beside this file.

---

## 1. The stateful answer compiles at the call site (F1)

### The problem

`statefulAnswersPaySuccessor` ran at 597 µs against the old kernel's 145: the worst row on the board.
Four isolation experiments narrowed the mechanism to one boundary. Per answer, the eval called the
clause through the shared dispatch:

```scala
// Eval, before: one call site for every stateful handler in the program
val ran = h.run(s, kyo.input)      // h.run: virtual, bound by the JIT's type profile
ran match
    case r: Loop.Continue2[...] => stack.putState(pos, r._1); loop(r._2)
    ...
```

The clause's outcome — a `Continue2` carrying the boxed state — is allocated just to cross that call.
Escape analysis can only remove it while `h.run`'s call site sees a single receiver class:

- E1 kept the state in an eval-local register instead of the slot: **allocation byte-identical,
  row unmoved** — the escape is the outcome returning through the un-inlined virtual call, not the
  slot store.
- E4 made the site bimorphic: **+24 B per answer in both JVM configs** (the `Continue2`
  re-materializes at two classes), +23% time even with boxing made free, and the monomorphic case is
  JIT-bimodal across forks (the row's ±160–316 error band).
- The old kernel is immune *by construction*: its `handleLoop` is a Scala `inline def`, so every call
  site gets its own drive with the clause statically bound — monomorphism as a compile-time property
  of each site, not a runtime property of the whole program:

```scala
// old kernel, after inline expansion at ONE call site — what C2 actually compiles:
def handleLoopLoop(state, v, context) =
    v match
        case kyo if effectTag <:< kyo.tag =>
            Loop.continue(state + 1, 1) match          // Continue2 born here...
                case r => handleLoopLoop(r._1, kyo(r._2), context)   // ...dies here: elided
```

- E2 tried copying that expansion into kernel2 and was refuted: it answered suspensions at
  *composition time*, before any eval exists, and kernel2's preemption lives in the eval —
  two slice pins broke. Its verdict named the sound home: per-site code invoked *by the eval's
  dispatch*.

### The change

The clause call and the outcome destructuring move into methods the `inline` expansion generates per
call site — so the outcome is born and matched inside one statically-bound compiled method — and the
eval invokes them through a narrow seam on the handler. The seam follows the kernel's universal
pattern: the eval never contains user code, it reaches it through per-site objects (`Transform.apply`
for map bodies, `HandlerCont.run` for cont clauses, `run` itself). These are `run` with its outcome
consumed on the side of the boundary where it is born:

```scala
// Handler.scala — the seam, with generic fallbacks so bespoke handlers keep working
abstract private[kyo] class HandlerLoopState[I[_], O[_], E, A, B, S, State] extends Handler[E, A, B, S]:
    def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B] < S   // unchanged

    /** Runs the clause and takes its outcome apart, in the class the call site generated. */
    def answer[X](state: State, input: I[X], out: Out): Any = ...   // generic body; expansion overrides

    /** Consecutive settled answers with the state in a local. The generic body answers once and
      * hands the continuation back unconsumed — the same protocol a bailing loop uses, so the
      * eval treats both alike. */
    def answers[X](state: State, input: I[X], k: Arrow[Any, Any, Any],
                   armed: Boolean, stop: () => Boolean, out: Out): Any =
        out.cont = k
        answer(state, input, out)
```

Two results must cross back — the new state and the answer — and returning them bundled is the very
allocation being removed. The bundle is split: the answer returns as the value, the state and branch
ride a per-stack cell:

```scala
// Stack.scala
// the out-parameter a stateful answer reports through, one per stack so it is pooled with it. Only
// the eval and the handler's generated answer method touch it, within one dispatch; nothing it holds
// survives past the dispatch that wrote it
private[kernel] val out = new Handler.Out

final private[kyo] class Out:
    private[kyo] var kind: Int  = 0        // 1 answered · 2 clause suspended · 3 finished
    private[kyo] var state: Any = null     // on its way into the state slot
    private[kyo] var cont: Arrow[Any, Any, Any] = null   // an entry handed back unconsumed
    private[kyo] var input: Any = null     // scratch lane for the decompose hook
```

`Out` is the out-parameter encoding of an immutable sum, and that sum is its specification — any
doubt about the cell resolves by asking what the pure form would do:

```scala
// the equation Out encodes (not compiled; the generic path is in effect its reference impl):
enum Answered[State, +A]:
    case Continue(state: State, value: A)
    case Bail(state: State, cont: Arrow[...], next: A)
    case Suspended(state: State, cont: Arrow[...], clause: Kyo[...])
    case Done(value: A)
```

The generated loop keeps the state in a local across consecutive settled answers — this is where the
per-answer box dies young — and the discipline is visible in the code: **every exit writes the cell
first**, and slicing is honored at the same cadence the eval honored it:

```scala
// the template (one copy, inline in the eval layer; expanded per call site with `handle` bound)
var s = state0; var in: Any = input0; var k = k0; var n = 128
while true do
    if armed && stop() then                        // the eval polled here per level before this
        out.kind = 1; out.state = s; out.cont = null      // loop existed: same cadence,
        return Effect.defer(resuspend(effectTag.erased, in, k), Arrow.id)  // and the eval parks
    val o =
        try handle[C](s, in)                       // the user's clause: sees (s, in), nothing else
        catch case ex: Throwable =>
            out.kind = 1; out.state = s; out.cont = k     // commit, then rethrow
            throw ex
    o match
        case kyo: Kyo[...] =>                      // clause suspended: bail to the general path
            out.kind = 2; out.state = s; out.cont = k
            return kyo
        case c: Loop.Continue2[...] =>
            s = c._1                               // state stays a LOCAL: no heap crossing
            ... apply k to the answer; decompose the next same-tag suspension via nextAnswer;
                every other shape: commit the cell, return a complete value ...
```

And the eval consumes the cell immediately, in the same dispatch, restoring the slot and any
unconsumed entry before anything else can observe the stack:

```scala
// Eval.dispatchLoopStateFast — the only reader
val ran =
    try h.answers(s, kyo.input, k, armed, stop, out)
    catch case ex: Throwable =>
        stack.putState(0, out.state.asInstanceOf[StateX])   // committed state lands first
        if (out.cont ne null) && !(out.cont eq Arrow.Id) then stack.push(out.cont)
        attachThrow(ex, kyo, Arrow.id[Any], stack)          // ...then the trace walk
out.kind match
    case 1 => stack.putState(0, out.state.asInstanceOf[StateX]); ...  // answered
    case 2 => ...push out.cont back; the existing clause-suspended path...
    case _ => stack.truncate(1); ...                                   // region finished
```

The eval keeps everything that is policy: it decides when the fast path is legal (handler on top,
at most one plain `Step` entry above — a `Region` entry forces the general path so the scans still
see it), passes its own slicing governance in, consumes every bail, owns the stack and the park.

Layering, per the review ruling: the template text lives once as inline `private[kyo]` members of
`object Handler`; `ArrowEffect`'s four expansions are wiring:

```scala
// ArrowEffect.handleLoopState — the expansion, back to being composition
new HandlerLoopState[I, O, E, A, B, S, State]:
    def run[X](st: State, input: I[X]) = handle[X](st, input)
    override def answer[X](st, input, out)               = Handler.answerStep(handle[X], st, input, out)
    override def answers[X](st, input, k, armed, stop, out) =
        Handler.answersLoop(handle[X], effectTag.erased, st, input, k, armed, stop, out)
```

### Why it is sound

- **Eval-time, not composition-time.** The loop runs from the eval's dispatch with `armed`/`stop` in
  hand; every bail (cap, stop, foreign shape, suspended clause) re-enters the eval through
  `Effect.defer`, where parks happen. The two slice pins E2 broke are green.
- **The complete-value rule holds.** Everything that leaves is a full value; the cell never rides a
  park or a capture (parks snapshot spans; the cell is per-stack); a nested eval borrows its own
  stack and cell. Five hostile-axis pins enforce this: multi-shot through the fast path, an
  adversarial argument-hoarding clause, a nested eval mid-loop, a throw between cell write and eval
  read with a recovery above, and a park resumed in a fresh eval.
- **Fast path specializes the law.** The generic `answers` body is the one-step form; the eval
  cannot tell a bespoke handler from a bailing fast one — one consumption path.

### Measured

| row | base | F1 | kyo-kernel | F1/k1 |
|---|---:|---:|---:|---:|
| statefulAnswersPaySuccessor | 597.77 ± 6.07 | **90.81 ± 1.12** | 145.28 | **0.63x** |
| statefulTwiceMono / Bi | 1,187 / 636 | **181 / 183** | 292 / 259 | **0.62x / 0.71x** |
| handleLoopAnswersInPlace | 154.34 | **86.93** | 129.86 | **0.67x** |
| handleLoopFusesContinuation (Proto) | 172.31 | **84.65** | n/a | |

Bi == mono to 1% — morphism-independence by construction. Bands ±160–316 → ±1–2 (the bimodal JIT
mode is gone). Allocation −15.5 B/answer. Compile fixtures flat. 981 tests; 36-row screen clean.
Two representation-contract kernel bugs found by the pins and fixed at root en route.

### Sign-off list

`Any`-typed answer return (the cell's kind disambiguates; alternative was three virtual methods per
answer); erasure-category casts, inventoried in the report; new `private[kyo]` surface
`Handler.Out` / `nextAnswer` / `resuspend`; the literal 128 cap. Open proposals, unimplemented:
collapse `answer`/`answers` to one seam method; put the `Answered` equation in `Out`'s scaladoc.

---

## 2. A fold applies by its own law (F2-L1)

### The problem

F2's attribution overturned the standing assumption about the suspension rows: the `AndThen` fold is
*absent* from `suspensionBaseline`'s allocation profile. What is present: `Effect$$anon$2` — the
two-argument `Defer` — at 38% of allocation sites. The source is `AndThen.apply`: every clause
resume `cont(v)` allocated a deferral node and made a full eval round trip (Defer arm, push, settled
delivery) before the first map step ran.

### The change

```scala
// Arrow.AndThen — before
def apply(v: A)                                    = Effect.defer(v, t, cont)
def apply[D, S2](v: A < S2, next: Arrow[C, D, S2]) = Effect.defer(v, t, cont.chain(next))

// after: the peeled step is applied directly rather than through a deferral. The eval's round
// trip for `Effect.defer(v, t, cont)` ends in exactly `t(v, cont)` (push cont, push t, settled
// delivery), so this is the same law without the node, and the step's own apply carries the
// budget check that makes it safe. The payload handling is unchanged: the same lift the
// deferring form applied to `v` fires on the same argument here
def apply(v: A)                                    = t(v, cont)
def apply[D, S2](v: A < S2, next: Arrow[C, D, S2]) = t(v, cont.chain(next))
```

The counterpart matters as much as the change, and is now pinned as law:

```scala
// Arrow.Chain — deliberately NOT changed
// a chain applies by deferring, never by running its head: the tail may carry a region (that is
// what makes it a Chain rather than an AndThen), and the eval's round trip installs the tail's
// entries on the stack before the head runs, so a failure in the head finds its scope. Running
// the head here would run it with the scope absent; EffectTest's "failure in map" pins exactly that
def apply(v: A) = Effect.defer(v, a, b)
```

This is the Step/Region hierarchy paying off a third time: the *type* is what makes it knowable that
an `AndThen` contains no region, so peeling its head early is sound there and only there. The
violation was not hypothesized — it was attempted on `Chain` and the existing pin caught it
immediately.

### Measured

| row | base | L1 | vs base |
|---|---:|---:|---:|
| trailingMapsStayLinear | 863.5 | **692.0** | **0.80x** |
| fusionAfterSuspensionRunOnly | 0.99 | **0.81** | **0.82x** |
| fusionAfterSuspension | 219.6 | **197.8** | **0.90x** |
| stateful (side effect, this branch alone) | — | — | 0.89x |

24 bytes per application, arithmetic exact against the alloc deltas. A 1.13x scare on
`suspensionFusesContinuation` was adjudicated by an `-f 3` bracket: fork drift, byte-identical
allocation, untouched path. 34-row both-board screen: nothing above 1.04x. L2 (a flat `push`) was
dropped honestly on measurement: it compiled 2.4x larger than the method it meant to shrink.

---

## 3. A settled eval stays in the caller (F3)

### The problem

`evalFixedOverhead` read 1.23x but at 0.01 µs carried no signal. A batched probe row
(`@OperationsPerInvocation(1000)`, added to both boards) made it readable: kernel2 12 ns and
**13.98 B/op** against the old kernel's 9 ns and 0 B. The 13.98 is exact arithmetic: 874 of 1001
seeds fall outside the `Integer` cache × 16 B — **one result box per settled eval**, escaping
through the deliberately non-inline `Eval.apply` boundary, which escape analysis cannot span (the
old kernel's inline eval elides the same box).

### The change

```scala
// Pending.scala, the .eval extension — before
Eval(self.asInstanceOf[A < Any]).asInstanceOf[A]

// after
// the settled arm stays in the caller's compilation on purpose: a value that never suspended has
// its result born in the caller's own map expansion, and delivering it through the non-inline
// interpreter boundary makes that box a real allocation (measured 14 B and 3ns per settled eval).
// Unnesting here instead lets escape analysis finish the job, and only a node graph pays the eval.
// Observationally the interpreter does exactly this for a settled value: unnest, empty stack, return
val v = self                       // bound once, for the reason evalNow binds once
v match
    case _: Kyo[?, ?] => Eval(v.asInstanceOf[A < Any]).asInstanceOf[A]
    case _            => Nested.unnest(v)
```

### Measured

2 ns / ~0 B per settled eval = **0.22x of the old kernel** on the probe. Controls (`userTypes`,
`uncached`, `fusionAllocatesNothing`) unchanged; 976 green on a clean batch build; the
outside-package expansion of the new arm is covered by `PendingExpansionSiteTest`.

### The finding delivered alongside, deliberately not fixed

`userTypes`' 1.12x — and ~18% of **every** settled row — is `Safepoint.get` resolving its slot
through a volatile `AtomicReferenceArray` read per map step (the old kernel pays a cheaper
ThreadLocal). The lever is specified: a plain read on the own-slot ownership check, VarHandle
ordering kept on the stop/CAS paths. It is a memory-ordering change that needs a precise
`Stop`-visibility argument and concurrency pins, which put it past this exploration's bar; it is
queued with expected value stated.

---

## Composition, remaining reds, sequence

The three apply cleanly together (`KERNEL2-ALL-CHANGES.diff`: Arrow +16, ArrowEffect +57,
Pending +12, Eval +308/−80, Handler +366, Stack +5, plus bench probes and 115 lines of new pins).
Composition is untested as a unit: adoption is one candidate at a time, full both-kernel boards at
`-f 3` on movers between steps.

Remaining reds afterward, each with a named mechanism: the cont cluster and much of `foreign`
(per-suspension dispatch + clause boxing → F1's design applied to `HandlerCont`, the direction both
F1 and F2 point to independently); `foreign`'s per-crossing Chain fold/walk; the Safepoint read
lever; `RunOnly` at the folded-continuation floor (1,240 B/op vs CPS's zero — likely a design ruling
rather than an optimization).

Proposed order: F3 → F2-L1 → F1.
