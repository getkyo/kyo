# The clause-scope leak: design and verification plan

Held-out design review of `reviews/CLAUSE-SCOPE-LEAK.md`. Read-only: every claim below comes from
reading the working tree and git history; nothing was built or run. Where a build would settle
something, the prediction and the settling step are stated.

Paths are relative to `kyo-kernel2/shared/src/`. Line numbers are the working tree at `86999b71f3`.

## 0. Findings, ranked by consequence

1. **The correct contract is a genuine fork, and the pinned tests settle it against the type, the
   old kernel, and kyo-kernel.** The reproduced leak (a foreign-tag effect in an answer captured by
   the interior) has one fix under either reading. But *own-tag* effects inside a computation-valued
   answer split two ways: the pinned test at `EvalTest.scala:501` says the OUTER handler answers; the
   answer slot's declared row `O[C] < (E & S)` (`main/scala/kyo/Arrow.scala:178,183`), the old
   kernel's live drive (`cb1f2bcb8a~1:.../internal/Eval.scala`, quoted in §1), and kyo-kernel's
   `handleLoop` all say THIS region's handler answers. The design below is written for the pinned
   contract and isolates the fork to one line (park depth `i` vs `i+1`), so a ruling flips it
   without redesign. Under the pinned contract the answer row should be narrowed to `O[C] < S`, or
   `handleLoop(...): B < S` can throw "unhandled suspension" on a program typed `< Any`.
2. **There is a second leak site the report did not find.** `outcome`'s Transform delivers a
   computation-valued `Continue` payload into the rebuilt region: `region(h.handler, c._1)` at
   `internal/Eval.scala:96` builds `Identity(payload, body)` (line 86), and `Identity.apply` turns an
   `Arrow` payload into `Chain(payload, body)` (`kyo/Arrow.scala:80`), which the drive then runs
   *inside* the rebuilt interior. So `say("pre").map(_ => Loop.continue(say("c").map(_ => 41)))`
   leaks exactly like the sync case. §2's table marks the suspend-first path correct only because every
   suspend-first case it tried had a value payload. A fix that touches only `dispatchInline` leaves
   the class of bug in place.
3. **D5 is settled, and "restore the pre-regression semantics" is not an option.** At `fe9860a17e~1`
   the proto ran the answer computation in place, region present, exactly as today. The literal
   `case p: Arrow => Chain(p, whole)` was inlined at `fe9860a17e`, but the same decision lived in
   `Suspend.apply` before it. The proto never scoped the answer computation, and no proto test ever
   exercised `Loop.continue(<computation>)` under an interior handler. The only drive that scoped it
   correctly was the old kernel, and its semantics were the type-honest ones (finding 1).
4. **The report's central representation claim is wrong, and D1/D2 built on it would break the
   kernel.** `Transform[Any, B, S]` is a legitimate computation (the kernel's thunk):
   `Effect.deferInline` (`kernel/Effect.scala:16-20`) returns one as `A < S`, the drive applies it
   to `()` at `internal/Eval.scala:231`, and `EvalTest.scala:119-124` builds one as `Int < Any`. The
   line the representation draws is input type `Any` (already enforced by contravariance in
   `Arrow[Any, A, S]`), not Defer vs Transform. Independently, this bug is not a value-vs-computation
   confusion: both leak sites correctly identify the payload as a computation and correctly evaluate
   it; they evaluate it on the wrong stack. `Nested` marks an arrow as data-not-to-run; nesting the
   answer (D1) says the opposite of the truth and reproduces attempt 2's crash.
5. **The design**: treat a computation-valued answer as unfinished clause work. At both sites,
   `Loop.continue(p)` with `p` an `Arrow` is rewritten to `p` followed by a re-wrap Transform that
   yields `Loop.continue(<settled value>)`, and that chain is dispatched through the existing
   `outcome` park (sync site) or re-entered through the same Transform (async site). One park
   mechanism, one dispatcher, and `region(...)` asserts it never receives an `Arrow`. About 25 lines
   in `internal/Eval.scala`, zero cost on the value-answer hot path.

## 1. D5: what the pre-regression drive did (settled by reading)

`git show fe9860a17e~1:kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Eval.scala`, HandleLoop arm:

```scala
case c: Loop.Continue[?] =>
    val st = whole.step
    st.head(c._1.asInstanceOf[Any < Any], st.tail)
```

`whole` there is the `Suspend` itself (drive arm `case s: Suspend => dispatchInline(s, s)`) or a
`Step` whose head is the `Suspend` (drive arm `case a: Arrow => s0.head match case sus: Suspend =>
dispatch(sus, a)`). Either way `st.head` IS the `Suspend`, which at that commit was a `Transform`
(`git show fe9860a17e~1:.../proto/Arrow.scala`):

```scala
abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Transform[Any, B, E & S]:
    ...
    final def apply[C, S2](v: Any < S2, next: Arrow[B, C, S2]): C < (E & S & S2) =
        v match
            case p: Arrow[Any, Any, S2] @unchecked =>
                Chain(p, this.chain(next))
            case o =>
                val step = next.step
                step.head(cont(Nested.unnest[O[A]](o)), step.tail)
```

So `st.head(c._1, st.tail)` with a `Defer` payload returned `Chain(p, suspend.chain(next))` to the
drive, with the stack untouched. The drive pushed the suspension and ran `p` on top of the region's
interior. That is byte-for-byte the semantics of today's `Chain(p, whole)`: the report's §4 statement
that the fast path "first appears 2026-08-16, commit fe9860a17e" is true of the source text and false
of the behaviour. The proto suite at that commit ("Proto suite 118/118") had no case with a
computation-valued `Continue` payload under an interior handler (I listed every case in
`proto/EvalTest.scala` and `proto/ArrowEffectTest.scala` at `fe9860a17e~1`; the closest,
"a clause suspension resolves outside the region", is suspend-first with a value payload).

The proto's own design record confirms the placement was deliberate, and wrong for this shape.
`git show 28a645aa8e:proto-effectful-clauses.md` gives the equation the drive implements:

```
handle(H)(op andThen k)  =  clause(op).map {
    continue(answer) => handle(H)(answer into (op andThen k))
    done(result)     => result
}
```

`answer` sits inside `handle(H)(...)`; if `answer` is a computation, this equation runs it inside the
region. The correct equation evaluates `answer` as part of `clause(op)` and rebuilds the region
around its value.

The old kernel had it right. `git show cb1f2bcb8a~1:kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala`
(the last live version before the proto replaced it and stubbed `run` to `???`):

```scala
h.run(kyo.input) match
    case p: Kyo[Any, Nothing] @unchecked =>          // clause suspended before its outcome
        val seg = stack.copyFrom(i)                     // handler AND interior
        stack.truncate(i)
        stack.push(interpret(seg))
        p
    case v =>
        Nested.unnest[Loop.Outcome[Any < Nothing, Any]](v) match
            case c: Loop.Continue[Any < Nothing] @unchecked =>
                c._1 match
                    case p: Kyo[?, ?] if i + 1 < stack.size =>
                        // an effectful answer sees the region, whose successor
                        // answers a re-raise, but not the region's interior:
                        // that is the operation's continuation and receives
                        // the answer's result
                        val seg = stack.copyFrom(i + 1)  // interior only, handler stays
                        stack.truncate(i + 1)
                        stack.push(resume(seg, 0))
                        c._1
                    case _ =>
                        c._1
```

Two park depths: the clause's own suspension parks handler and interior (`truncate(i)`); a
computation-valued answer parks the interior only (`truncate(i + 1)`), so its own-tag effect is
answered by this region's handler ("whose successor answers a re-raise") and every other effect goes
outward past the interior. That is what "the old semantics" means, and it is finding 1's fork.

Consequence for the design: D1's "deliver by value" cannot be sufficient in any lineage, because in
every lineage the payload was *evaluated* before delivery; the difference between right and wrong was
never the delivery protocol, it was which stack the evaluation ran on. Both attempts in §5 changed the
delivery and left the stack alone.

## 2. The report's central claim: Defer-only in computation position

Report §6: "So **the only arrows that belong in the computation arm are `Defer`s**, and the opaque
type's `Arrow[Any, A, S]` was always saying so."

Wrong. The opaque type says input `Any`, and a `Transform[Any, B, S]` has input `Any`:

- `kernel/Effect.scala:14-20`: `deferInline` returns `new Transform[Any, A, S] { def apply(v, next) =
  step.head(f, step.tail) }` as `A < S` through `fromArrow`. This is the kernel's thunk.
- `internal/Eval.scala:228-231`: the drive's computation arm applies a `Transform` to unit,
  `s0.head((), next)`. That arm exists for exactly this.
- `EvalTest.scala:119-124` and `139-145` build `new Transform[Any, Int, Any]` as `Int < Any`;
  `EvalTest.scala:282-287` returns `Effect.defer(Loop.continue(1))` from a clause and expects it to
  run as the clause's outcome computation.

So the correct line is: **an `Arrow` is a computation iff its input type is `Any`; a `Transform[Any,
B, S]` is a computation applied to `()`, a `Defer` is a computation with its own structure; an arrow
of any input type becomes data only under `Nested`.** `fromArrow`'s parameter type `Arrow[Any, A, S]`
(`kernel/Pending.scala:19`) already encodes this by contravariance and is not the hole. `Identity`
(`Transform[Any, Any, Any]`) type-checks in computation position and would evaluate to `()`; nothing
puts it there, and a rule forbidding `Transform` would forbid `Effect.defer` first.

Consequences:

- **D2 rejected.** Bounding `fromArrow` to `Defer` breaks `Effect.defer` and the fixtures above, or
  forces `defer` to allocate a `Bind` per call. It closes nothing here (`SuspendWith` is a `Defer`).
- **D1 rejected.** `Nested.nest(p)` asserts "this arrow is a value, do not run it". The answer
  computation must be run; its *result* is the answer. Marking it `Nested` and delivering through
  `whole(unnest(o))` hands the raw arrow to the user's continuation as if it were the answer, which is
  attempt 2's `Effect$$anon$1 cannot be cast to Integer`. It also does nothing at the second site.
- The report's own reading of `whole(v)` (raw hand-off, `kyo/Arrow.scala:127` and `139-145`) is
  correct, and it is why "deliver as value" and "evaluate as computation" were never the choice: the
  drive already evaluates a computation payload; the choice is *where*.
- The report's §6(b), that `Loop.continue` stores raw and `Continue2._1` is state, is correct
  (`kernel/Loop.scala:145-149, 160-166`) and is why nesting at `Loop.continue` would be wrong in the
  other direction.

Where the value-vs-computation axis IS live in this drive: not `fromArrow` but the erasing casts that
put arbitrary runtime shapes into `Any < Any` slots (`internal/Eval.scala:86, 96, 110, 116, 287`) and
`Loop.continue`'s `< Any` return type (`kernel/Loop.scala:145`, cursed by the TODO at `:204`), which
types a `Continue` holding a computation as a *settled* outcome. Those are inherent to an erased drive
and mean the drive must inspect a payload's runtime shape at delivery. The safety lever is therefore
to have exactly one sanctioned way to evaluate a clause-produced computation and to assert at the
rebuild that nothing else got through. That is what §4 does.

## 3. The two leak sites, traced

**Site A, synchronous.** `internal/Eval.scala:166-169` (HandleLoop) and `177-181` (HandleLoopState):

```scala
case c: Loop.Continue[?] =>
    c._1 match
        case p: Arrow[Any, Any, Any] @unchecked => Chain(p, whole)        // stack untouched
        case o                                  => whole(Nested.unnest[Any](o))
```

`p` runs above the region's interior; `stack.find(tag, base)` (`:136`) finds the innermost handler,
which is the interior's. Own tag: finds this handler, whose clause returns the same shape, forever.

**Site B, after a suspended clause.** `outcome` (`:60-118`) parks the region (`copyEntries(i+1)`,
`truncate(i)`) and returns Transform `T`. When the clause's computation settles to `Continue(p)`,
`T.apply` (`:95-96`) does `Identity(region(h.handler, c._1), next)`; `region` (`:84-88`) builds
`Handle { v = Arrow.Eval(entries, tags, states, Identity(payload, body)) }`; the drive rebuilds the
handler (`:216-224`), replays the interior (`:225-227`), and evaluates `Identity(p, body)`, which for
an `Arrow` payload is `Chain(p, body)` (`kyo/Arrow.scala:80`). `p` runs with the interior back on the
stack: same leak, same livelock for own tag. `Continue2` (`:97-112`) is identical with `c._2`.

Prediction (needs a build to confirm): with sayInner inside and sayOuter outside,
`[C] => _ => say("pre").map(_ => Loop.continue(say("c").map(_ => 41)))` logs
`List("inner", "outer", "inner")` today; the contract requires `List("inner", "outer", "outer")`.

Both sites share the correct delivery ("evaluate `p`, then hand its value to `whole`") and the same
wrong context. Fixing site A alone leaves site B, and the next fast path anyone writes at either has
the same trap. Hence a design that leaves one mechanism.

## 4. Design

### 4.1 Contract, stated so it can be checked

Under the pinned tests (S1): **everything a clause produces, its outcome computation and any
computation it returns as an answer, is evaluated with the region (handler and interior) parked, on
the stack the clause was dispatched on; a region is rebuilt only around a settled answer.**

Under the type-honest alternative (T, finding 1): the same for the outcome computation; a
computation-valued answer is evaluated with the interior parked and the handler installed (state
updated in place), so its own-tag effect is answered by this region and everything else goes outward.
The old kernel implemented T; kyo-kernel's `handleLoop` (`kyo-kernel/.../ArrowEffect.scala:454-457`,
`continue._1 match case kyo: KyoSuspend if effectTag <:< kyo.tag => handleLoopLoop(handle(...))`)
answers own-tag effects in the `Continue` payload with the same handler; the answer slot's row `E & S`
means the same. Only one pinned test distinguishes S1 from T: `EvalTest.scala:501` ("a clause's
re-raise of its own tag is answered by the successor, not by itself"), which under T livelocks by
construction for a stateless handler (a stateless region's successor is itself; the test's own
counter would fire). Under T that test would become the old kernel's stateful form
(`fe9860a17e~1:.../internal/EvalTest.scala:169`, "a continue answer raising the effect is answered by
the successor, which may done": phase 0 answers `ask.map(a => a + 100)`, phase 1 returns
`Loop.done(-2)`, result -2).

I design for S1 because that is the ruling, and I recommend the lead re-confirm it with finding 1 in
hand. Practical stakes are low either way: every `handleLoop` clause in kyo-core and kyo-prelude
puts effectful work *before* `Loop.continue` (`f(state, input).map(a => Loop.continue(a, cont(())))`
in `kyo-prelude/.../Emit.scala:121`, `Var.scala:152-157`, and so on); none re-raises its own tag
inside the payload. The type-honesty stake is real: under S1 the answer row must narrow (§4.5) or the
row lies.

### 4.2 The one mechanism: `Loop.continue(p)` with `p` a computation is `p` then `Loop.continue(_)`

Introduce, at `Eval` object level (no closure over the drive), a re-wrap Transform that turns a
settled answer value back into the outcome the dispatcher expects, **without unnesting** (a
`Nested(arrow)` answer is a value and must reach `whole(unnest(o))` still boxed):

```scala
// after a computation-valued answer settles, re-box its value as the outcome the region
// dispatcher takes, so both answer shapes travel one path. Never unnests: a boxed arrow
// answer is a value until the suspension delivers it
private object ContinueAnswer extends Transform[Any, Any, Any]:
    def frame = kyo.Frame.internal
    def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
        v match
            case v: Arrow[Any, Any, S2] @unchecked => Chain(v, this.chain(next))
            case v                                 => Identity(Loop.continue(v).asInstanceOf[Any < S2], next)

final private class Continue2Answer(st: Any) extends Transform[Any, Any, Any]:
    def frame = kyo.Frame.internal
    def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
        v match
            case v: Arrow[Any, Any, S2] @unchecked => Chain(v, this.chain(next))
            case v                                 => Identity(Loop.continue(st, v).asInstanceOf[Any < S2], next)
```

`p.chain(ContinueAnswer)` costs one `SuspendWith`/`Chain`/`Step` per computation-valued answer;
`ContinueAnswer` itself allocates nothing. (Using `(p: Any < Any).map(x => Loop.continue(x))` would
also work but `map` unnests before `f`, so a `Nested(arrow)` answer would be re-raised as a
computation; the explicit Transform is the correct one.)

### 4.3 Site A: `internal/Eval.scala:163-172` and `174-184`

```scala
case hl: Handler.HandleLoop[...] @unchecked =>
    hl.run(s.input) match
        case out: Arrow[Any, Any, Any] @unchecked =>
            Chain(out, outcome(whole, h, i))
        case c: Loop.Continue[?] =>
            c._1 match
                case p: Arrow[Any, Any, Any] @unchecked =>
                    // a computation-valued answer is unfinished clause work: it settles outside
                    // the region, on the suspended-clause path, and only its value comes back in
                    Chain(p.chain(ContinueAnswer), outcome(whole, h, i))
                case o => whole(Nested.unnest[Any](o))
        case done =>
            stack.truncate(i)
            done

case hls: Handler.HandleLoopState[...] @unchecked =>
    hls.run(stack.state(i), s.input) match
        case out: Arrow[Any, Any, Any] @unchecked =>
            Chain(out, outcome(whole, h, i))
        case c: Loop.Continue2[?, ?] =>
            c._2 match
                case p: Arrow[Any, Any, Any] @unchecked =>
                    Chain(p.chain(new Continue2Answer(c._1)), outcome(whole, h, i))
                case o =>
                    stack.setState(i, c._1)
                    whole(Nested.unnest[Any](o))
        case done =>
            stack.truncate(i)
            done
```

`stack.setState(i, c._1)` moves into the value branch: on the park branch `outcome` truncates entry
`i` and the state travels in the `Continue2` that `T` turns into a successor handler (existing code
at `:97-112`). The value branches, which are every benchmarked row, are byte-identical to today.

The sync computation-valued answer now literally *is* the suspend-first shape: `hl.run` returning
`p.chain(ContinueAnswer)` and returning `Continue(p)` produce the same `Chain(_, outcome(...))`.

### 4.4 Site B: `outcome`'s Transform, `internal/Eval.scala:89-116`

```scala
def apply[C2, S2](v: Any < S2, next: Arrow[Any, C2, S2]): C2 < S2 =
    v match
        case p: Arrow[Any, Any, S2] @unchecked =>
            Chain(p, this.chain(next))
        case c: Loop.Continue[?] =>
            c._1 match
                case p: Arrow[Any, Any, Any] @unchecked =>
                    // the region is parked and stays parked: settle the answer here, then re-enter
                    Chain(p.chain(ContinueAnswer), this.chain(next))
                case a =>
                    Identity(region(h.handler, a).asInstanceOf[Any < S2], next)
        case c: Loop.Continue2[?, ?] =>
            c._2 match
                case p: Arrow[Any, Any, Any] @unchecked =>
                    Chain(p.chain(new Continue2Answer(c._1)), this.chain(next))
                case a =>
                    (existing successor-handler `region(...)` code, payload `a`)
        case done =>
            Identity(done.asInstanceOf[Any < S2], next)
```

`T` is popped only after the clause's computation settled, on the stack the region was parked from;
`this.chain(next)` re-enters `T` after `p` settles, with the region still parked. `T` is a pure
Transform over immutable spans, so this is replay-safe and stop-safe like the existing first branch.

### 4.5 The guard: `region` refuses an unsettled payload, `internal/Eval.scala:84-88`

```scala
def region(regionHandler: Handler[Nothing, Any, Any, Any], payload: Any): Arrow[Any, Any, Any] =
    if payload.isInstanceOf[Arrow[?, ?, ?]] then bug(s"unsettled answer delivered into a region: $payload")
    new Handle[Nothing, Any, Any, Any, Any]:
        def v       = Arrow.Eval(entries, tags, states, Identity(payload.asInstanceOf[Any < Any], body))
        ...
```

One `instanceof` on the rebuild path. This is what makes the invariant enforced rather than
documented: after 4.3 and 4.4 the `Chain(payload, body)` arm of `Identity.apply` is unreachable from
`region`, and any future branch that hands a computation to `region` fails loudly on the first test
that reaches it instead of leaking silently.

### 4.6 The type-honest completion under S1 (separate step, lead's call)

Narrow the answer row from `O[C] < (E & S)` to `O[C] < S` at `kyo/Arrow.scala:178`
(`HandleLoop.run`), `:183` (`HandleLoopState.run`), and `kernel/ArrowEffect.scala:123, 147, 194, 220`
(the four clause parameter types). Then a clause that answers with its own effect must have `E` in
`S`, so `handleLoop`'s result carries `E` and an outer handler is type-required, matching what S1 does
at runtime. Every `Loop.continue(value)` compiles unchanged; every effectful answer whose effects are
already in `S` compiles unchanged (all current tests, by reading: `Say`-answers under a `S = Say`
region, `Ask`-answers under a `S = Ask` region, `Effect.defer(7)` under `< Any`). The `handle*`
methods keep their shape; one row on the clause type narrows. Without this step, under S1,
`handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(ask.map(_ + 100)), a => a): Int < Any` is a
program the type accepts and `Eval` rejects with "unhandled suspension". If the ruling is T instead,
the current row is already honest and this step is dropped.

### 4.7 If the ruling flips to T

Site A: keep the handler, park the interior at `i + 1` (the shape `dispatchInline` already uses for
`handleCont` at `:155-160`: `copyEntries(i + 1)`, `truncate(i + 1)`, and a resume that rebuilds via
`Arrow.Eval(entries, tags, states, whole(o))`), keep `stack.setState(i, c._1)` before running `p`,
and keep `Chain(p, whole)` when `i + 1 == top` (nothing to park; the old kernel's guard). Site B:
`Identity(regionOf(h.handler, Chain(p, deliverInterior)), next)` where `region` takes an inner arrow
instead of a payload and `deliverInterior(x) = Arrow.Eval(entries, tags, states, Identity(x, body))`.
The guard in 4.5 stays. Test at `EvalTest.scala:501` becomes the stateful successor form. Everything
else in this document is unchanged.

### 4.8 What is not in the fix, and why

- D1 and D2: rejected, §2.
- D3 (explicit `fromArrow`): orthogonal hygiene, changes no semantics, would triple the diff and hide
  the fix; if wanted, a separate change after this one is green.
- `Loop.continue`'s `< Any` return (`kernel/Loop.scala:145, 204`): unrelated to the leak; leave for
  its own TODO.
- `Identity.apply`'s `case v: Arrow => Chain(v, next)` (`kyo/Arrow.scala:80`): general Transform
  protocol for an unsettled input; stays. Only its use from `region` becomes unreachable.

### 4.9 Cost

- `Loop.continue(value)`, sync: unchanged; the `instanceof Arrow` on `_1` exists today.
- Suspend-first with a value payload: one extra `instanceof` on `_1` inside `T`, on a path already
  copying three spans and allocating a `Handle`, an `Arrow.Eval`, and a `Bind`.
- Region rebuild: one `instanceof` (the guard).
- Computation-valued answer: from one wrong `Chain` to the suspend-first path's cost (park, `T`,
  `Handle`, `Arrow.Eval`, `Bind`, plus one `SuspendWith`/`Chain` for the re-wrap and, stateful, one
  `Continue2Answer`). No JMH row exercises this shape (`ProtoKernelBench` rows use value payloads or
  the suspend-first `tick.map(t => Loop.continue(t))`), and no kyo-core/prelude handler does either.

## 5. The invariant, in one sentence

**A clause-produced computation, whether the clause's outcome or an `Arrow` inside its `Continue`
payload, is only ever evaluated after `outcome` has parked the region and before `region` rebuilds
it, and `region` never receives an `Arrow` payload.**

Check for any new drive branch: does an `Arrow` taken from `Continue._1` / `Continue2._2` reach
`whole(...)`, `Identity(..., body)`, or `region(...)` without first passing through `p.chain(...Answer)`
under `outcome`/`T`? If yes, it is this bug. The `bug(...)` guard in `region` catches the case where
the check was skipped.

## 6. Verification plan

Every step on a **clean** build (`sbt clean` before compile; two earlier bugs in this campaign hid
behind stale classes), JVM first, then JS and Native since every file is shared.

**Step 1: tests before code** (Reproduce Before You Fix). Add to `EvalTest.scala` under
`"clause scope"`; run; each must fail for the stated reason; commit as known red.

| test | shape | expected | today (predicted) |
|---|---|---|---|
| a stateful clause's effectful answer is answered outside its scope | `handleLoopState(Ask, 0, sayInner)([C] => (n, _) => Loop.continue(n + 1, say("c").map(_ => 41)), ...)` under sayOuter | 42, `List("inner", "outer")` | `List("inner", "inner")` |
| a clause that suspends and then answers effectfully is answered outside its scope | `say("pre").map(_ => Loop.continue(say("c").map(_ => 41)))` under sayOuter | 42, `List("inner", "outer", "outer")` | `List("inner", "outer", "inner")` (site B) |
| stateful variant of the above | `(n, _) => say("pre").map(_ => Loop.continue(n + 1, say("c").map(_ => 41)))` | 42, `List("inner", "outer", "outer")` | leaks (site B, Continue2) |
| an effectful answer's own-tag re-raise after a suspension is answered outside | `say("pre").map(_ => Loop.continue(ask.map(_ + 100)))` under recordSay and outerAsk, counter-bounded | 106, clause runs once | "clause answered its own re-raise" (site B livelock) |
| an effectful answer's own-tag effect is not answered by its own region | `handleLoop(Ask, ask.map(_ + 1))(_ => Loop.continue(ask.map(_ + 100)))` with NO outer, counter-bounded, `Eval(x.asInstanceOf[Int < Any])` | throws "unhandled suspension" | livelock guard fires |
| a nested arrow value in an effectful answer stays a value | effect `Give extends ArrowEffect[Const[Unit], Const[Int < Ask]]`; body `answerAskIn(1)(give.flatten).map(_ + 1)`; clause `Loop.continue(Effect.defer(box(ask.map(_ + 1))))`; whole under `answerAsk(41)` | 3 (the arrow value is flattened inside `answerAskIn(1)`) | 3 today by accident of the leak; 43 if a re-wrap unnests (this pins 4.2's no-unnest rule) |
| a stateful effectful answer threads its state through the park | `handleLoopState(Ask, 0, ask.map(a => ask.map(b => a * 10 + b)))((n, _) => Loop.continue(n + 1, Effect.defer(n)))` | 1 | passes today; must stay green through the successor path |

Under T, rows 4 and 5 become the old kernel's stateful successor forms
(`fe9860a17e~1:kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:169` sync,
`:438` after a suspension: phase 0 answers `ask.map(a => a + 100)`, phase 1 returns `Loop.done(-2)`,
result -2), and `EvalTest.scala:501` changes the same way.

**Step 2: the drive.** Apply 4.2 through 4.5. Must go green: the five red today
(`EvalTest.scala:315, 438, 490, 501, 535`) plus the step-1 rows. Must stay green (the paths the
change touches, all in `EvalTest.scala` unless noted): `:277` "an effectful answer on the settled
outcome path" (sync `Effect.defer(7)`, no interior; walks the new park with `marked = false`), `:282`
"a pending clause outcome resolves before the region continues" (a `Transform` outcome), `:307` "an
effectful answer resolves through the outer scope", `:388, :396` suspend before outcome/done, `:410,
:423` done climbing during a settling outcome, `:371` stateful state and done, `:456, :468, :480,
:522` the four suspend-first scope cases, and in `kyo/kernel/ArrowEffectTest.scala` the `handleLoop`
block ("a clause answers effectfully", "every clause suspension re-arms the region", "a clause
suspension resolves outside the region", "a clause answer survives its own deep evaluation", "a
clause suspends on its own effect per operation", "a suspended clause dispatch is multi-shot", "a
stateful clause answers effectfully"). Then the full `kyo-kernel2` suite on JVM, JS, Native. Commit.

**Step 3 (S1 only): the row.** Apply 4.6. Full suite green; add one compile-negative pin that
`handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(ask), a => a)` no longer types as `Int < Any`
(the module's existing typeCheck helpers). Commit separately so a lead who declines the API narrowing
can drop one commit.

**Bench (optional, cheap):** `ProtoKernelBench` rows `handleLoopAnswersInPlace`,
`statefulAnswersPaySuccessor`, `handleLoopFusesContinuation`, `emittingClausesPayRegionRebuild`
before and after; expected inside the 3-4% A/A drift the WIP commits record, since the value-answer
branches are unchanged.

**What a build settles that reading cannot:** the exact log order in the site-B rows (I predict the
third element is `"inner"` today), and that `Chain(p.chain(ContinueAnswer), outcome(...))` on the
`marked = false` park (`:277`) delivers 8 (I trace it as `Bind(7, whole)` then `whole(7)`). If either
prediction is wrong, the trace in §3, not the design, is what to revisit.
