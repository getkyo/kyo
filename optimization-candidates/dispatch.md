# Optimization candidates: dispatch, control flow, algorithmic structure

Family: dispatch, control flow, and algorithmic structure. Analysis only. Nothing was edited, compiled,
or run; no sbt, no JMH, no benchmark.

Evidence used, all read-only:

- the proto sources at HEAD (`kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/*.scala`)
- `git show 36b41336fb:...` for the pre-SuspendWith design, and `git diff 36b41336fb d85ee6821f` for the
  arc that produced the regression
- **`javap -c -p` on the already-built class files** at
  `kyo-kernel2/jvm/target/scala-3.8.4/classes/kyo/kernel/proto/` (timestamped 2026-08-16 22:34, i.e.
  after the last source edit at 22:32, so they match HEAD). Reading a class file is not a build.
- `kernel-backlog.md:12-24` and `:176-211` (the defect and the 15-row table)
- `kernel2-jit-morphism-report.md:69-76` (prior inline-verdict experiments on this exact row, in the
  sibling kernel2 impl, not the proto; provenance flagged wherever cited)
- `qa-artifacts/e2e2.log` (the harness's own comparison output and JIT cost table)
- `.claude/skills/kernel/SKILL.md`

Every bytecode number below is from `javap` on those class files and is reproducible without a build.

---

## Part 1: what the structure says about `continuationBodiesFuse`, before any candidate

### 1.1 The one structural fact that changed for this row, and only for rows like it

`continuationBodiesFuse` builds `ask.map { a => <ten fused maps> }` (`ProtoKernelBench.scala:168-183`).
`ask` is a bare `Suspend` (`ProtoKernelBench.scala:195` into `ArrowEffect.scala:17-27`), and `.map` over
an `Arrow` takes the composition arm `v.chain(arrow.chain(next))` (`Pending.scala:49-50`), which lands on
`Suspend.chain`.

Under the **old** design (`36b41336fb`, `Arrow.scala:113-119` at that commit) `Suspend.chain` minted
another `Suspend`:

```scala
new Suspend[I, O, E, A, C, S & S2]:
    def frame         = self.frame
    def tag           = self.tag
    def input         = self.input
    def cont(v: O[A]) = Arrow[B](self.cont(v), f)
```

Under the **current** design (`Arrow.scala:114-116`) it mints a different class:

```scala
override def chain[C, S2](f: Arrow[B, C, S2]): Arrow[Any, C, E & S & S2] =
    if f eq Identity then this.asInstanceOf[Arrow[Any, C, E & S & S2]]
    else SuspendWith(this, f)
```

That single change moves this row from one arm of the drive's dispatch to the other, and those two arms
are **not symmetric**:

```scala
case s: Suspend[...] @unchecked =>
    cur = dispatchInline(s, s.asInstanceOf[Arrow[Any, Any, Any]])      // Eval.scala:201-202
case m: SuspendWith[...] @unchecked =>
    cur = dispatch(m.susp, m.asInstanceOf[Arrow[Any, Any, Any]])       // Eval.scala:203-204
```

`dispatchInline` is `inline` (`Eval.scala:124`) and `dispatch` is a plain wrapper that calls it
(`Eval.scala:184-185`). So the `Suspend` arm gets the whole dispatch body expanded into the loop, and the
`SuspendWith` arm gets a call.

`javap` on `Eval$.class` measures the asymmetry exactly:

| method | bytecode size |
|---|---:|
| `Eval$::loop(Object, boolean, Function0)` | **1582 B** |
| `Eval$::dispatch$1(Stack, int, Arrow$Suspend, Arrow)` | **607 B** |
| `Eval$::outcome$1` | 197 B |
| `Eval$::dump$1` | 92 B |
| `Eval$::reify$1` | 51 B |

and inside `loop` the arm layout is:

```
  63: instanceof  Arrow$Chain          ifeq 96
  98: instanceof  Arrow$Bind           ifeq 131
 133: instanceof  Arrow$Suspend        ifeq 791     <- 650-byte inlined dispatch body follows
 793: instanceof  Arrow$SuspendWith    ifeq 829
 821: invokespecial dispatch$1                       <- the call
 831: instanceof  Arrow$Handle         ifeq 939
 941: instanceof  Arrow$Eval           ifeq 984
 986: instanceof  Arrow                ifeq 1095
1095:  (settled arm)
```

**So: before the arc, `continuationBodiesFuse` executed the inlined dispatch on every one of its 1000
iterations per op and never made a call. After the arc, it executes the out-of-line `dispatch$1` on every
one of those iterations and never touches the inlined copy.** That is the change, stated structurally,
and it is invisible to allocation counting, to a 450-sample CPU profile, and to a receiver profile.

### 1.2 Which of the 15 rows sit on which arm

Derived from each row's source shape, not guessed:

| loop-head shape | rows |
|---|---|
| `SuspendWith` every iteration (out-of-line `dispatch$1`) | `suspensionBaseline`, `handleLoopAnswersInPlace`, `handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, `trailingMapsStayLinear`, `emittingClausesPayRegionRebuild`, **`continuationBodiesFuse`** |
| bare `Suspend` (inlined dispatch) | `suspensionFusesContinuation` (uses `askWith` at `ProtoKernelBench.scala:201-202`, so the continuation is fused into the `Suspend` itself and `chain` is never called) |
| never reaches a suspension arm; `Bind` and `settled` alternate | `evalFixedOverhead`, `fusionAllocatesNothing`, `fusionPastBudgetPaysRescuesOnly`, `uncachedValuesPayBoxingOnly`, `deepRecursionPaysRescuesOnly`, `nestedPayloadsUnwrapInMaps`, `idleHandlerAddsNothing` |

Six other rows moved to the out-of-line arm and got faster or stayed flat
(`kernel-backlog.md:186-199`). So the frame alone is not fatal; the question is what makes this row unable
to repay it. Section 1.4 answers that.

### 1.3 Path-length accounting says the new design does strictly *less* work per iteration on this row

Per suspension, current design:

`loop` -> `dispatch$1` -> `hc.run` (the clause) -> `SuspendWith.apply` (56 B) -> `Suspend.apply` (6 B) ->
`ask.cont` -> `Step::head` / `Step::tail` -> the big `Transform::apply`.

Per suspension, old design:

`loop` (dispatch inlined) -> `hc.run` -> wrapper `Suspend.apply` (6 B) -> wrapper `cont` -> `Identity$::apply`
(**92 B**, two type tests: `instanceof Arrow` then `instanceof Defer`, `Arrow.scala:66-74`) -> `Step::head` /
`Step::tail` -> the same big `Transform::apply`.

The old design additionally paid, per iteration: a second virtual hop for `tag` and for `input` (the wrapper
delegated to `self`), and a `suspended = Maybe(s)` / `Maybe.Absent` write on every arm of the loop (deleted
by the arc, `git diff 36b41336fb d85ee6821f`).

So the new design removes one 92-byte method with two failing type tests, two virtual hops, and a per-arm
`Maybe` write, and adds one non-inlinable call plus one `instanceof Arrow$Chain` in
`SuspendWith.apply` (bci 6, fails on every delivery on this row because `cont` is a bare `Transform`; the
`Chain` shape only appears after a continuation has been chained twice, `Arrow.scala:132-135`).

**Conclusion: path length is lower and allocation is byte-identical, yet the row is 4.3% slower. The cause is
therefore code shape, not work done.** That is what pushes the search onto compilation-unit boundaries and
frame structure, which is exactly where candidates DIS-1 and DIS-2 live.

### 1.4 The differentiator: this is the only row whose continuation body cannot be inlined

Every other out-of-line-arm row has a small continuation body (`loop(i + a)`, one node construction), so the
whole chain below `dispatch$1` folds into `dispatch$1` and the frame is repaid by a smaller, cleaner
compilation root. `continuationBodiesFuse` is the only row whose clause body is ten fused `map`
expansions plus the recursion (`ProtoKernelBench.scala:173-180`), all inlined by the Scala compiler into one
anonymous `Transform::apply`.

`kernel2-jit-morphism-report.md:73` records, for this exact benchmark row (**in the kernel2 impl at commit
`df6a68fc93`, not the proto; treat as shape evidence, not as a proto measurement**):

> `hot method too big` | continuation-entry mapLoop (377B) called from handle path | E2: FreqInlineSize=600
> recovers ~8% on continuationBodiesFuse (31.4 to 28.9 us)

`hot method too big` is refused on the **callee** size against `FreqInlineSize` (325), so if it fires it fires
in both designs. The consequence is what matters: on this row the delivery is a hard call boundary no matter
what, so the extra `dispatch$1` frame cannot be amortized by downstream inlining the way it is on
`suspensionBaseline`. That asymmetry is the hypothesis DIS-1 tests, and the residual possibility that the
verdict itself moved is the rival DIS-2 tests.

Arithmetic sanity check on scale (arithmetic on the reported numbers, not a measurement): 28.695 minus
27.522 us/op over 1000 iterations is **1.17 ns per iteration**, roughly 4 cycles at a typical clock. One
non-inlined 4-argument call with prologue and epilogue is in that band. One extra `instanceof` is not (see
1.5).

### 1.5 Two hypotheses the existing numbers already kill, with the calibration that kills them

**Branch count at the loop head is not the mechanism.** The arc added exactly one `instanceof` to the
`settled` path (six arms before `settled` in the old design, seven now, bci 63/98/133/793/831/941/986 above).
The five rows whose hot path alternates `Bind` and `settled` therefore each pay one extra type test per
delivered answer, and they measured +0.5%, +0.8%, +0.2%, -1.0%, -2.7% (`kernel-backlog.md:189-197`), all
inside the 3-4% drift band. So one extra type test on this machine is worth under about 1%. The
`continuationBodiesFuse` path pays exactly one extra test at the loop head (`SuspendWith` after `Suspend`).
It cannot be 4.3%.

**The secondary-supertype cache is not in play in the drive.** From `javap`, the hierarchy is
`Object` -> `scala.runtime.AbstractFunction1` -> `Arrow` -> `Arrow$Step` -> {`Arrow$Transform`, `Arrow$Defer`}
-> {`Identity`, `Suspend`, `SuspendWith`, `Chain`, `Bind`, `Eval`, `Handle`}. Every type tested in
`Eval.loop` is a **class** at depth 5 or less, well inside HotSpot's 8-deep primary supertype display, so each
test is one klass load plus one compare against a display slot. None of them touches the secondary super
array or its cache. The only interface test in the kernel's hot machinery is `instanceof Boxed` in
`Nested.nest` (`Pending.scala:22-25`; `javap` shows `instanceof kyo/kernel/proto/Boxed` at bci 3, and
`Boxed` is `public interface` from `Pending.scala:9`). That test is genuinely on the interface path, but it is
**not on this row**: an `Int` lift takes `CanLift`'s bare-cast arm (`CanLift.scala:54` `isValue`, applied at
`:63`), so `nest` is never called from `continuationBodiesFuse`. It is called from
`nestedPayloadsUnwrapInMaps`, which is flat.

### 1.6 The owner's `Identity` TODO, analyzed and not acted on

`Arrow.scala:16-21` currently reads:

```scala
def chain[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
    if f.isInstanceOf[Arrow.Identity]
    then // TODO I've made Identity a class to use here. Check if this helps perf, convert other uses and measure
        this.asInstanceOf[Arrow[A, C, S & S2]]
    else
        Arrow.Chain(this, f)
```

while `Transform.chain` (`Arrow.scala:47-49`) and `Suspend.chain` (`Arrow.scala:115`) still use `f eq Identity`.

Verdict on the suspected cliff: **there is no cliff here.** `Arrow$Identity` is an abstract class at depth 5
(`Object`/`AbstractFunction1`/`Arrow`/`Step`/`Transform`/`Identity`), so `instanceof Arrow$Identity` compiles
to the same one-load-one-compare display check as every other test in the drive, and `javap` confirms both
spellings are 19-byte and 19-byte methods respectively (`Arrow::chain` 19 B, `Arrow$Transform::chain` 19 B).
The `eq` form is `getstatic MODULE$` plus `if_acmpne`; the `instanceof` form is a klass load plus a compare.
They are the same cost class. Where they could differ is whether C2 folds one and not the other, and the
evidence that would show that is a per-site receiver profile at `Arrow::chain`, which this log cannot supply
(12 of 5093 sites carry one). **So the TODO is not answerable from the current evidence, and the only way to
answer it is a single-variable A/B over all 15 rows, which the backlog already queues behind the regression
(`kernel-backlog.md:86-96`). No candidate below proposes changing it.**

### 1.7 Two constraints on any candidate in this family

- **JMH forks per benchmark method** (`@Fork(value = 2)`, `ProtoKernelBench.scala:14`), so each row's JVM sees
  only its own node shapes. Cross-row profile pollution is impossible by construction. Any pollution
  hypothesis has to be within-row, which sharply limits it: `continuationBodiesFuse` produces exactly one
  suspension class, one handler class, and eleven `Transform` classes with per-site bytecode.
- **Receiver-profile evidence does not exist in this log.** No candidate below rests on one, and I explicitly
  do not propose `-XX:TypeProfileWidth` or `-XX:-UseTypeSpeculation` as a falsifier for anything, because both
  produce exactly the evidence class the constraint rules out.

---

## Part 2: candidates, ranked by expected value

Ranking: DIS-1 and DIS-2 first because they target the one open defect and are cheap and decisive; DIS-3 and
DIS-4 next as structural wins with predicted movement on named rows; DIS-5 last as a shape fix whose
benchmark movement is expected to be small.

---

### DIS-1. Split dispatch into an inlined degenerate tier and an out-of-line general tier, so both node kinds take the same frame-free fast path

**Hypothesis.** `continuationBodiesFuse` regressed because the arc moved its entire per-iteration path from
`Eval$::loop`'s compilation unit into the 607-byte `dispatch$1`, adding one non-inlinable frame per
suspension, and it is the only suspending row whose continuation body is itself too big to inline, so it is
the only one that cannot repay that frame with downstream inlining.

**Mechanism.** Today `dispatchInline` (`Eval.scala:124-182`) carries three handler kinds, the region-mark
scan (`Eval.scala:132-134`), the k-fold (`Eval.scala:139-143`), the region copy (`Eval.scala:147-151`), the
two `Loop.Continue` arms (`Eval.scala:153-175`) and the `EffectTrace` catch (`Eval.scala:177-180`). All of
that is expanded into `loop` at the `Suspend` arm (`Eval.scala:202`, bytecode bci 146 to 790) and compiled
again standalone as `dispatch$1` for the `SuspendWith` arm (`Eval.scala:204`, call at bci 821).

The change: keep only the degenerate tier in the shared, inlinable body, and push everything else behind one
call. The degenerate tier is exactly the shape both benchmark handlers hit, `HandleCont` with an empty
region, which is already its own branch at `Eval.scala:135-136`:

```scala
if j == top then
    if i + 1 == top then hc.run(s.input, whole)
```

so the fast body becomes roughly: `stack.find`, the `Handle` read, `h.handler match { case hc: HandleCont if
i + 1 == stack.size => hc.run(s.input, whole); case _ => dispatchGeneral(s, whole, h, i) }`. Estimated 60 to
90 bytes.

Do this **without** the `inline` keyword: at 60 to 90 bytes a plain private `def` clears `MaxInlineSize` only
when hot, but it is far under `FreqInlineSize` (325), so C2 inlines it into `loop` at both arms once the site
is hot, and C1 refuses it during warmup, which costs warmup and not score. Both arms then read
`cur = dispatchFast(...)` and neither carries a special case.

This is not the recorded ablation that inlined more. That one grew `loop`; this one shrinks it, from 1582
bytes to roughly 950 to 1050, while removing the frame from the fast path.

**Predicted signal.**

- `javap -c -p Eval$.class`: `Eval$::loop` falls from **1582 B to roughly 950-1050 B**; `dispatch$1`
  (607 B) is replaced by a `dispatchFast$1` of 60-90 B and a `dispatchGeneral$1` of roughly 520-560 B. This
  is deterministic and is the first thing to check.
- Harness `Inlining changed:`, C2 tier: a new `Eval$::dispatchFast$1: ~75B inline (hot)` entry at both the
  `Suspend` and `SuspendWith` arms, and the disappearance of the `Eval$::dispatch$1: 607B` refusal.
- JMH score: `continuationBodiesFuse` **falls** toward 27.5 us/op. Direction is the claim; magnitude is the
  measurement.
- `gc.alloc.rate.norm`: **unchanged** on every row. Any B/op movement means the restructure changed
  semantics and the run is void.
- JIT cost table (`qa-artifacts/e2e2.log` format): `compilation tasks` and `compiling total` fall slightly;
  `deoptimizations` holds or falls, since a smaller root has fewer never-taken arms to trap through.

**Falsifier.** Dies if `javap` shows `loop` still above about 1200 B (the split did not land where I read
it). Dies as an explanation if the isolation experiment below shows the old design with a frame added is
still at 27.5 us/op. Dies as a fix if `continuationBodiesFuse` does not move beyond drift while the byte
counts moved as predicted, which would mean the frame is real but not priced.

**Target rows.** Must move (all sit on the out-of-line arm today): `continuationBodiesFuse` (the target),
`suspensionBaseline`, `handleLoopAnswersInPlace`, `handleLoopFusesContinuation`,
`statefulAnswersPaySuccessor`, `trailingMapsStayLinear`, `emittingClausesPayRegionRebuild`. May move a little:
`suspensionFusesContinuation` (already on the inlined arm; it gains a smaller `loop` and loses nothing).
Must **not** move: `evalFixedOverhead`, `fusionAllocatesNothing`, `fusionPastBudgetPaysRescuesOnly`,
`uncachedValuesPayBoxingOnly`, `deepRecursionPaysRescuesOnly`, `nestedPayloadsUnwrapInMaps`,
`idleHandlerAddsNothing`. If any of those seven moves beyond drift, the attribution is wrong and the run
needs re-diagnosis before anything is concluded.

**Cost and risk.** One method split inside `Eval.loop`, no change to the node algebra, no change to any
signature outside `Eval`. Risk: the general tier now takes `h` and `i` as parameters, so the two scans must
not be duplicated across the boundary; getting that wrong changes behavior, and the proto suite (126/126 per
`kernel-backlog.md:7-8`) is the guard. Second risk: `hc.run` is a virtual call in the fast body, so the fast
body contains a call that C2 may or may not inline; that is true today as well and does not change.

**Gated constructs.** This candidate **removes** a gated construct: the `inline` on `Eval.dispatchInline`
(`Eval.scala:124`), which `SKILL.md` records as an unapproved use standing as an open question. It adds no
`inline`, no cast, no `asInstanceOf` beyond the two `@unchecked` patterns already at `Eval.scala:201-204`,
and no public API change. Owner should be told the experiment doubles as the ruling's evidence.

**The isolation experiment (one measurement, and it settles the attribution).**

Reverse the implementation change onto the old design, which is the cheap direction the skill prescribes:

1. `git worktree add --detach <tmp> 36b41336fb`.
2. One edit, and only this edit: in that tree's `Eval.loop`, lift the body of `case s: Suspend =>` verbatim
   into a private `def dispatchOut(s, whole): Any` and replace the arm with `cur = dispatchOut(s, s)`. The
   node algebra, the delivery chain, the allocation, and every other line stay identical.
3. Run `continuationBodiesFuse` and `suspensionBaseline` at `-f 3`, same session, back to back, against the
   unmodified `36b41336fb` control.

Outcomes:

- old-plus-frame lands at about **28.7** while old lands at about **27.5**: the frame is the whole delta.
  DIS-1 is the fix and DIS-2 is unnecessary.
- old-plus-frame stays at about **27.5**: the frame is not the mechanism. DIS-1 dies as an explanation (it
  may still be worth doing for the 607 bytes) and DIS-2 becomes the lead.
- old-plus-frame lands **beyond 28.7**: the frame costs more than the observed delta, meaning the new design
  also recovered something else on this row; that is two mechanisms and both need naming before anything
  ships.

**JVM-flag falsifier, no source edit, on the current design.** Add
`-jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=inline,kyo/kernel/proto/Eval\$::dispatch\$1"`
to a `-f 3` run of `continuationBodiesFuse`.

- Row returns to about 27.5: frame confirmed, DIS-1 is the right shape.
- Row does not move: the frame is inert, DIS-1's explanation is dead.
- Row gets **worse**: forcing a 607-byte callee into an already-1582-byte root is net negative, which is the
  same direction as the recorded "inlines more was slowest" ablation, and it says the fix must shrink what is
  inlined rather than inline more, which is precisely DIS-1's shape rather than a blunt force-inline.

Sanity check that the command took effect, since a mistyped `CompileCommand` is silently ignored: the same
run with `-XX:+PrintInlining` must show `dispatch$1` as `force inline by CompileCommand`. Companion control:
`-XX:CompileCommand=dontinline,kyo/kernel/proto/Eval\$::dispatch\$1` must be a no-op, because the callee is
already refused; if that one moves the score, the harness is measuring noise and no conclusion is available.

---

### DIS-2. Deliver `Transform`-first in `SuspendWith.apply`, collapsing the `step` / `head` / `tail` triple and taking the delivery entry under `MaxInlineSize`

**Hypothesis.** The regression is the JIT's inline verdict at the delivery site, not the frame: the arc moved
the call into the un-inlinable continuation body from `Identity$::apply` (92 B, invoked from inside `loop`)
to `SuspendWith::apply` (56 B, invoked from inside `dispatch$1`), and the frequency-relative gating that
decides whether a callee gets the 325-byte `FreqInlineSize` budget rather than the 35-byte `MaxInlineSize` one
takes its inputs from the call site's count relative to its caller's invocation count. `loop` is entered once
per op; `dispatch$1` is entered a thousand times per op. Same callee, different ratio, and the verdict can
flip.

**Mechanism.** `SuspendWith::apply` (`Arrow.scala:124-138`) currently reads, and `javap` confirms the shape at
56 bytes:

```scala
final override def apply(v: Any) =
    cont match
        case c: Chain[X, Any, B, S2] @unchecked =>
            applyFolded(v, c)
        case cont =>
            val st = cont.step
            st.head(susp(v), st.tail)
```

bytecode: `instanceof Arrow$Chain` (bci 6), then `Arrow.step()` (bci 29), `Arrow$Step.head()` (bci 36),
`Arrow$Suspend.apply` (bci 44), `Arrow$Step.tail()` (bci 49), `Arrow$Transform.apply` (bci 52).

On every row whose `cont` is a bare `Transform`, which is every `ask.map(f)` row, the `Chain` test fails and
the `step` / `head` / `tail` triple is three virtual calls that provably return `this`, `this` and
`Arrow[B]` (`Arrow.scala:32`, `:38`, `:39`). Dispatch `Transform` first and they all disappear:

```scala
final override def apply(v: Any) =
    cont match
        case t: Transform[X, B, S2] @unchecked => t(susp(v), Arrow[X])
        case c                                 => applyGeneral(v, c)
```

with `applyGeneral` holding today's `Chain` arm and today's general `step` arm. This is observationally
equivalent by `Transform.head = this` and `Transform.tail = Arrow[B]`, which is the "fast paths specialize
laws" rule, not a new law. Predicted `apply` size after the split: roughly 30 to 40 bytes, i.e. at or under
`MaxInlineSize` (35), so the delivery entry is inlined unconditionally rather than on a frequency judgement,
and the call into the big continuation body then originates one frame higher.

**Predicted signal.**

- `javap -c -p Arrow$SuspendWith.class`: `apply` falls from **56 B to about 30-40 B**; a new
  `applyGeneral` appears at roughly 45-55 B; `applyFolded` (35 B) is unchanged or absorbed.
- Harness `Inlining changed:`, C2 tier: `Arrow$SuspendWith::apply` moves from a frequency-gated verdict to a
  size-gated one, and the three `Arrow$Step::head` / `::tail` / `Arrow::step` entries under it disappear from
  the site list.
- JMH score: `continuationBodiesFuse` **falls**. Also expect small falls on `suspensionBaseline`,
  `handleLoopAnswersInPlace`, `handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`.
- `gc.alloc.rate.norm`: **byte-identical on every row**. This candidate touches no constructor and no node
  layout, so any B/op movement at all falsifies the implementation.

**Falsifier.** Dies if `javap` shows `apply` above about 45 B after the split, since the whole point is
clearing the size gate. Dies as an explanation if the flag experiment below shows the two designs converging
under `-XX:FreqInlineSize=600` by the same amount with the 4.3% gap intact. Dies as a fix if the row does not
move while the byte counts did.

**Target rows.** Must move or be measured flat: `continuationBodiesFuse`, `suspensionBaseline`,
`handleLoopAnswersInPlace`, `handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`,
`emittingClausesPayRegionRebuild`. Expected flat because their `cont` is not a bare `Transform`:
`trailingMapsStayLinear` (two maps compose through `Transform.chain` at `Arrow.scala:47-56` into an anonymous
`Step`, so it takes the general arm exactly as today). Must not move: `evalFixedOverhead`,
`fusionAllocatesNothing`, `fusionPastBudgetPaysRescuesOnly`, `uncachedValuesPayBoxingOnly`,
`deepRecursionPaysRescuesOnly`, `suspensionFusesContinuation` (no `SuspendWith` is ever built),
`nestedPayloadsUnwrapInMaps`, `idleHandlerAddsNothing`.

**Cost and risk.** One `match` reordering plus one private method in `Arrow.scala`. Risk is low and local.
The real risk is interpretive: if it moves the row, the reason could be either the shorter delivery chain or
the changed inline verdict, and those are two mechanisms. The flag experiment below separates them **before**
the edit, which is why it is written as the isolation step rather than as a post-hoc explanation.

**Gated constructs.** Adds one typed pattern with `@unchecked` (`case t: Transform[X, B, S2] @unchecked`),
which is ladder tier 2 in `SKILL.md`, the same construct already used at `Arrow.scala:126`. No new
`asInstanceOf`, no `inline`, no public API change (`SuspendWith` is `private[proto]`, `Arrow.scala:119`).
Owner approval needed for the `@unchecked` pattern only.

**The isolation experiment (one measurement, no source edit, and it decides between DIS-1 and DIS-2).**

Run `continuationBodiesFuse` at `-f 3` on **both** designs in one session, once with default flags and once
with `-jvmArgsAppend "-XX:FreqInlineSize=600"`. Four numbers, one table. Prior art for the flag choice is
`kernel2-jit-morphism-report.md:73`, which recovered about 8% on this row with exactly this value in the
sibling kernel.

Outcomes:

- both legs improve and the gap closes to inside the drift band: the delta **is** the inline verdict on the
  continuation body. DIS-2 is the fix and DIS-1's frame is a side issue.
- both legs improve by a similar amount and the gap stays at about 4%: the verdict is not the mechanism.
  DIS-2 dies and DIS-1 owns the defect.
- only the current leg improves: the site relocation is what flipped the verdict. DIS-2 confirmed in its
  strongest form, and the fix must be the site shape, not the flag.
- neither leg improves: the proto's continuation body is not hitting the size gate at all, unlike the sibling
  kernel's 377-byte one. That kills DIS-2 outright and is worth knowing, because it also retires the whole
  `hot method too big` story for this row.

Second flag, to separate "the entry is a call boundary" from "the body is a call boundary", run only if the
first is ambiguous:
`-XX:CompileCommand=dontinline,kyo/kernel/proto/Arrow\$SuspendWith::apply` on the current design. A large
regression means the 56-byte entry's inlining is load-bearing and shrinking it is worth doing; no movement
means the entry was already effectively a call boundary and DIS-2's size argument is void.

Not used, deliberately: `-XX:TypeProfileWidth` and `-XX:-UseTypeSpeculation`. Both would answer a question
about receiver profiles, and the constraint stands that this log has receiver profiles at 12 of 5093 sites.
Also not used: `-XX:-EliminateAllocations`, because `gc.alloc.rate.norm` is already byte-identical between
the designs and there is nothing for it to decide.

---

### DIS-3. One suspension arm: unify `Suspend` and `SuspendWith` under a `Suspension` supertype

**Hypothesis.** The drive tests two classes and carries two copies of the dispatch body for what is one
concept, a suspension plus the continuation to resume it into; collapsing them to one arm deletes 607 bytes
from the module, one type test from every non-suspension path, and the asymmetry that DIS-1 is fixing by
other means.

**Mechanism.** Introduce `sealed abstract class Suspension[...] extends Defer[...]` above both, exposing
`def susp: Suspend[...]`. `Suspend` (`Arrow.scala:105-117`) implements `susp = this`; `SuspendWith`
(`Arrow.scala:119-143`) returns its field (`Arrow.scala:120`). The drive's two arms
(`Eval.scala:201-204`) collapse to one:

```scala
case s: Suspension[...] @unchecked =>
    cur = dispatch(s.susp, s.asInstanceOf[Arrow[Any, Any, Any]])
```

and the popped-entry arms (`Eval.scala:257-270`), which today duplicate the same `Nested.unnest` delivery
twice, collapse to one as well. Depth check: `Suspension` inserts one level, so `Suspend` and `SuspendWith`
move from display depth 5 to 6, still inside the 8-deep primary display, so no test changes cost class
(section 1.5).

This is not a new node kind in the sense `SKILL.md` warns about; it adds no combinator and reifies nothing
new. It names a shape the evaluator already treats uniformly.

**Predicted signal.**

- `javap`: `Eval$::loop` falls from **1582 B to roughly 950-1000 B**; `dispatch$1` stays at about 607 B and
  becomes the single dispatch body; the `instanceof Arrow$SuspendWith` at bci 793 disappears.
- A new bimorphic `Arrow$Suspension::susp` site appears in the harness site list, 2 B and 5 B accessors,
  expected `inline (hot)`.
- JMH score: flat to slightly better on the seven `Bind`-and-`settled` rows (one fewer type test on the
  settled path, calibrated at under 1% in section 1.5); flat on the suspending rows unless combined with
  DIS-1.
- `gc.alloc.rate.norm`: unchanged everywhere. `Suspension` adds no field.

**Falsifier.** Dies if `loop` does not fall below about 1100 B, meaning the duplicate was not what the bci
range 146-790 holds. Dies as a *ship* candidate if any row regresses beyond drift, most plausibly through the
new `susp` accessor at a site that today is a field read on a final class.

**Target rows.** Small movement expected on `fusionPastBudgetPaysRescuesOnly`,
`uncachedValuesPayBoxingOnly`, `deepRecursionPaysRescuesOnly`, `nestedPayloadsUnwrapInMaps`,
`idleHandlerAddsNothing` (they lose one failing test per settled delivery). Flat expected on
`suspensionBaseline`, `handleLoopAnswersInPlace`, `handleLoopFusesContinuation`,
`statefulAnswersPaySuccessor`, `trailingMapsStayLinear`, `emittingClausesPayRegionRebuild`,
`continuationBodiesFuse`, `suspensionFusesContinuation`. Must not move: `evalFixedOverhead`,
`fusionAllocatesNothing`.

**Cost and risk.** One new abstract class, two arms merged in `Eval`, two arms merged in the popped-entry
match, and the `EffectTrace` sites that name `Suspend` and `SuspendWith` separately
(`Eval.scala:261`, `:268`) collapse to one. Risk: `Suspend.susp = this` returns a `Suspend` from a
`Suspension`-typed receiver, which the evaluator then passes where a `Suspend` is expected; the types work but
the variance on `E & S` needs checking at the `Handle` construction sites in `ArrowEffect.scala:56-63` and
`:102-109`. Verification must include the **clean batch build**, since a new class in `Arrow.scala` touches
the macro suspension equilibrium `SKILL.md` documents.

**Gated constructs.** Public API change: `Suspend` is public (`Arrow.scala:105`) and users obtain anonymous
subclasses of it through `ArrowEffect.suspend` / `suspendWith`; inserting a public supertype changes the
published hierarchy. Owner approval required on that ground alone. One `@unchecked` typed pattern replaces
two. No `inline`, no new `asInstanceOf`.

---

### DIS-4. Constant-time kind dispatch at the drive head, replacing the seven-deep subtype chain

**Hypothesis.** Every settled answer delivered through the drive pays seven failing subtype tests before
reaching the arm that handles it, and on the five rows whose hot cycle is `Bind` then `settled`, roughly half
of all loop iterations pay all seven.

**Mechanism.** From the `javap` layout in section 1.1, the arms are tested in source order
(`Eval.scala:194-226`): `Chain` (bci 63), `Bind` (98), `Suspend` (133), `SuspendWith` (793), `Handle` (831),
`Arrow.Eval` (941), `Arrow` (986), `settled` (1095). The popped-entry match (`Eval.scala:231-282`) has the
same shape with five arms.

Give `Arrow` a `private[proto] final val kind: Int` assigned in each intermediate base's constructor
(`Step`, `Defer`, `Transform` pass a constant down; `Chain`, `Bind`, `Eval`, `Suspend`, `SuspendWith`,
`Handle`, `Identity` each get their own), and rewrite the head as one type test plus a `tableswitch`:

```scala
cur match
    case a: Arrow[Any, Any, Any] @unchecked =>
        (a.kind: @switch) match
            case Kind.Chain       => ...
            case Kind.Bind        => ...
            ...
    case settled => ...
```

The settled path then costs **one** failing test instead of seven; every arrow path costs one test plus an
indexed jump instead of between one and seven tests. `kind` must be a field, never an abstract `def`, or the
read becomes a virtual call at the most polymorphic site in the kernel and the candidate inverts.

**Predicted signal.**

- `javap`: the seven `instanceof` opcodes in `loop` collapse to one `instanceof Arrow` plus one
  `tableswitch`; `loop` falls by roughly 60-100 B on the dispatch skeleton alone (independent of DIS-1 or
  DIS-3).
- JMH score: **falls** on `fusionPastBudgetPaysRescuesOnly`, `uncachedValuesPayBoxingOnly`,
  `deepRecursionPaysRescuesOnly`, `nestedPayloadsUnwrapInMaps`, `idleHandlerAddsNothing`. Magnitude is
  bounded by the calibration in section 1.5: one type test measured under 1% on those rows, so six of them
  is worth low single digits, not more. Predicting more than that would be overselling.
- `gc.alloc.rate.norm`: **this is the number that decides the candidate.** A 4-byte field on a 24-byte
  two-reference node pads to 32, i.e. +8 B per `Chain`, `Bind`, `SuspendWith`. If B/op rises on the fused and
  suspending rows, the field is being paid for per allocation and the win has to clear that cost explicitly.
- Deopt counts: expect a fall. Seven never-taken `instanceof` branches per row are seven uncommon traps in
  the compiled `loop`; one switch with a profiled default is fewer.

**Falsifier.** Dies if `gc.alloc.rate.norm` rises on any allocating row and the score does not clear the
allocation cost. Dies if the compiler emits a `lookupswitch` rather than a `tableswitch` (non-contiguous kind
values), since the win is the indexed jump. Dies if the score does not move beyond drift on the five named
rows, which would mean C2 was already folding the chain.

**Target rows.** Should move: `fusionPastBudgetPaysRescuesOnly`, `uncachedValuesPayBoxingOnly`,
`deepRecursionPaysRescuesOnly`, `nestedPayloadsUnwrapInMaps`, `idleHandlerAddsNothing`,
`trailingMapsStayLinear`. May move slightly: `suspensionBaseline`, `handleLoopAnswersInPlace`,
`handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, `emittingClausesPayRegionRebuild`,
`continuationBodiesFuse`, `suspensionFusesContinuation`. Should not move: `evalFixedOverhead`,
`fusionAllocatesNothing` (both stay inside the safepoint budget and barely enter the drive).

**Cost and risk.** The largest candidate here. It touches the node algebra, every constructor in
`Arrow.scala`, and both matches in `Eval.scala`. Risk one is the allocation cost above. Risk two is that
`Transform` is subclassed by every user `map` expansion (`Pending.scala:42-45` and siblings), so the constant
must be carried by `Transform` itself and not required of subclasses, or every user site breaks. Risk three
is that `kind` is a second source of truth alongside the class, so a new node kind can be added with a wrong
or duplicate constant; that needs a pinning test that asserts one constant per class.

**Gated constructs.** Data-structure change to the public sealed `Arrow` hierarchy, and a `@switch`
annotation. `Arrow` is `sealed` (`Arrow.scala:10`) so no external subclass exists and no external code
breaks, but the change is a design decision and needs owner sign-off. One `@unchecked` typed pattern at the
head, replacing seven. No `inline`.

---

### DIS-5. Fold the three per-suspension stack scans into `Stack`, and let the degenerate tier skip all of them

**Hypothesis.** Each suspension walks the stack up to three times for information one walk could produce, and
the walks are inline in the drive, which is why the drive is 1582 bytes; the owner's own TODOs at
`Eval.scala:126` and `:138` already name this.

**Mechanism.** Per suspension today:

1. `stack.find(s.tag.erased, base)` walks top-down testing `t <:< tag` per marked frame
   (`Stack.scala:85-93`, compiled as a 41-byte `loop$1` tailrec plus a 13-byte entry).
2. `dispatchInline` then walks up from `i + 1` looking for the next mark (`Eval.scala:132-134`).
3. If the region is non-empty it walks again folding entries (`Eval.scala:139-143`), and `outcome$1`
   (`Eval.scala:57-59`, 197 B) repeats scan 2 for the loop handlers.

Replace with `Stack.handlerAt(tag, base)` returning the index (the owner's `Eval.scala:126` TODO removes the
`asInstanceOf[Handle]` at `Eval.scala:129` at the same time, moving the storage cast to the storage boundary
where `Stack.apply` and `pop` already live) and `Stack.regionTop(i)` returning the next marked index, both
computed in one downward walk, plus `Stack.foldFrom(i)` for the k-fold that appears three times with small
variations (`Eval.scala:139-143`, `Eval.scala:68-73`, `Eval.scala:44-45`).

**Predicted signal.**

- `javap`: `Eval$::loop` and `dispatch$1` both fall (the scan bodies leave), `outcome$1` falls from 197 B;
  new `Stack` methods appear at 40-70 B each, all under `FreqInlineSize`.
- Harness `Inlining changed:`: new `Stack::handlerAt`, `Stack::regionTop`, `Stack::foldFrom` entries, C2
  verdict `inline (hot)`.
- JMH score: flat to slightly better on the suspending rows. Honest prediction: the benchmark handler stacks
  are two frames deep (one `Handle`, one `cont`), so the scans are already short and the win is code size
  rather than cycles. The row where a real win is possible is `emittingClausesPayRegionRebuild`, the only row
  with two nested regions.
- `gc.alloc.rate.norm`: unchanged.

**Falsifier.** Dies as a performance candidate if no row moves beyond drift, which is the likely outcome and
should be stated up front rather than discovered. It survives as a size and clarity candidate in that case,
and as the enabler that makes DIS-1's fast tier small enough to be worth inlining.

**Target rows.** May move: `emittingClausesPayRegionRebuild`, `trailingMapsStayLinear`,
`statefulAnswersPaySuccessor`. Expected flat: everything else. Must not move: the seven non-suspending rows.

**Cost and risk.** Mechanical, well-scoped, and pre-approved in direction by the owner TODOs. Risk: the
three fold sites differ in their seed (`Arrow[Any]` in two, `resume` in one) and in whether they truncate; a
single `foldFrom` that papers over that difference changes behavior. Each site must keep its own truncation.

**Gated constructs.** None. No `inline`, no new cast (it removes one at `Eval.scala:129`), no public API
change (`Stack` is `private[proto]`, `Stack.scala:9`).

---

## Part 3: ranking, and the order to run them in

| rank | candidate | targets the open defect | cost | decisive measurement |
|---|---|---|---|---|
| 1 | DIS-1 dispatch tier split | yes | low | isolation: add the frame to `36b41336fb` |
| 2 | DIS-2 `Transform`-first delivery | yes | low | flag: `-XX:FreqInlineSize=600` on both designs |
| 3 | DIS-3 one suspension arm | no | low-medium | `javap` size of `loop`, then the 15-row sweep |
| 4 | DIS-4 kind dispatch | no | high | `gc.alloc.rate.norm` first, then the 15-row sweep |
| 5 | DIS-5 stack scan consolidation | no | low | `javap` sizes; expect flat scores |

**Run order.** The two isolation experiments first, and both before any edit lands, because they are cheap,
they need no source change on the current tree, and between them they tell you which of DIS-1 and DIS-2 owns
the defect. Running them in the other order, or after an edit, reproduces the exact failure `SKILL.md`
records: a mechanism stated confidently and contradicted by the next measurement.

DIS-1 and DIS-3 overlap in what they touch and should not be measured in the same bracket; DIS-3 subsumes
DIS-1's byte saving but leaves the frame on the hot row's path, so it does not fix the defect and must not be
credited with fixing it if the row happens to move.

Whatever lands, the claim is the whole class on both legs, same session, back to back, per
`SKILL.md`. A four-row subset is what let this regression through in the first place
(`kernel-backlog.md:21-24`).

---

## Part 4: what this family cannot address, handed off

- **Boxing.** Roughly 29% of top-frame samples on `nestedPayloadsUnwrapInMaps` are
  `BoxesRunTime.boxToInteger`, and `SKILL.md` puts it near 44% on the suspension rows. No dispatch change
  touches it; it belongs to the benchmark's own `Int` threading through an erased union.
- **Method size as such.** Covered by the sibling report `optimization-candidates/inlining.md`, whose IN-2
  proposes deleting `inline` from `dispatchInline` outright. DIS-1 is a different edit with a different
  prediction (a small inlined fast tier, not a single large out-of-line body) and the two should be measured
  separately, never bundled, since a bundled measurement attributes nothing.
- **The `Identity`-as-class question** (`Arrow.scala:18`). Analyzed in section 1.6: not a secondary-super
  cliff, not answerable from this log, and left alone.
- **The shared-handler megamorphism** measured at 2.79x in `kernel2-jit-morphism-report.md:82-106`. It is
  real, it is a dispatch problem, and none of the 15 rows exposes it, so no candidate here can be validated
  against it. It needs its own row before it can be worked on.
- **The harness's tier reporting.** `optimization-candidates/inlining.md` documents that the harness's
  `Inlining changed:` table has no tier column and its C2 task counter reports zero on a log that contains C2
  tasks. Every "verdict flip" prediction in this document is unreadable until that is fixed, because a C1 flip
  and a C2 flip mean opposite things about the score. That repair is a prerequisite for DIS-1, DIS-2 and
  DIS-3, not a candidate.
