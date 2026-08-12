# Bracket as a handler kind: `Handler.Bracket` over the Handlers spine

Read at worktree HEAD `fe06ac4df95f5e3f1247660ed7286e173952c5e9` (branch
`worktree-effervescent-painting-backus`). Every citation of the form `file:line` without a
repository prefix is the worktree copy of kyo-kernel2 at that commit; citations prefixed
`origin/main:` are the old kernel and its consumers. This is one half of an adversarial pair: a
sibling design places the bracket as a dedicated node type `Kyo.Bracket`. Section 13 states
where that shape probably wins.

---

## 1. Executive summary

The bracket is a region on the existing `Handlers` spine, expressed as a fourth handler kind and
two new cell shapes.

1. **`Handler.Bracket` is `Handler.LoopState` plus `release`.** It answers an effect whose
   operation registers an obligation, its state accumulates the obligations, and the kernel calls
   `release(state, outcome)` when the region ends (`Handler.scala:22-23` is the shape it extends).
   Because the kind is generic in `I`, `O`, `E`, any effect can be a bracket effect: kyo-core's
   `Scope` can be an instance without a kernel-owned effect, and the kernel ships one internal
   effect only for the `Effect.bracket` sugar.

2. **The acquire gap closes with a mask that is a cell, not a counter.** The seed proposed a
   drive-local counter set at handler dispatch and cleared when the dispatch's `Continue2` lands.
   Verified against `Eval.scala`, that counter is lost whenever the acquire parks voluntarily
   (`Eval.scala:50`, the unhandled suspension residual) and is reinstated by nothing when the
   remainder is resumed by a later drive, so the gap reopens on exactly the async acquires that
   need it most. The repair: a mask region (`Kyo.Masked` node, `MaskNode` cell) whose extent is a
   cell on the spine. `rebuild` already preserves cells across parks (`Eval.scala:270-282`), so
   the mask level is a corollary of the spine's shape and the loop only has to count pushes and
   pops. It survives a park, a resume in a different drive, a rebuild, and a truncation, with
   nothing whose job it is to keep it true.

3. **The mask makes `Eval.partial` skip the stop check at `Eval.scala:104`, never consume it.**
   `Safepoint.consumeStopped` CASes the `Stop` wrapper back to a plain thread
   (`Safepoint.scala:174-181`), so consuming inside the window would discard the scheduler's
   request outright. Skipping leaves it pending for the first unmasked `Kyo.Defer` arm, which is
   the first step of `use`.

4. **Release has exactly one implementation, the settle arm.** A bracket cell pops the way every
   other cell pops (`Eval.scala:129-138`), except that it sequences `release` before the exit
   arrow, inside a mask region so the release runs to completion. The unwind path and the discard
   path are then not new machinery: unwind is a per arm `try` region that walks the spine at the
   throw point (the same regions `exception-enrichment-design.md:506-523` already proposes), and
   discard is the evaluator itself, driving the parked remainder with its body replaced by `()`
   and its exits neutralized, so the ordinary settle arms run the releases innermost first.

5. **The `done` truncation is the fourth path and it is provably safe to release eagerly.** When
   a `Loop`/`LoopState` clause dones, the cells between the operation's spine and the handler's
   cell are discarded and no closure holds them (`Eval.scala:68-69`, `Eval.scala:85-86`), so the
   arm releases their brackets before applying the exit. This is `iotask-kernel2-integration-r2.md:897-909`'s
   requirement R-B3, met by the design rather than worked around by IOTask.

The weakest points, stated up front: every operation raised inside a bracket region pays extra
iterations in `Handlers.find` (`Handlers.scala:46-56`), each resource costs a suspension round
trip plus a `replace` path copy (`Eval.scala:284-316`), and the design adds two cell classes and
two node classes with arms in `find`, `replace`, `rebuild`, `Effect.guarded`, and five evaluator
arms. Section 13 prices these against the node shape.

---

## 2. Verifying the seed against the current sources

The brief's seed presumes five mechanisms. Three hold, two do not.

### 2.1 Holds: parks are two sites, and only one of them is stop driven

`evalLoop` leaves with a residual at exactly two places: the unhandled suspension arm under
`partial` (`Eval.scala:49-50`) and the `Kyo.Defer` arm when a stop is pending
(`Eval.scala:103-105`). Normal completion is `case Empty => v` (`Eval.scala:131`). `Eval.partial`
additionally returns its input untouched when a stop is already pending at entry
(`Eval.scala:26`). So the seed's "parks happen only at `Kyo.Defer` arms via `consumeStopped`" is
correct for stop driven parks, and incomplete for parks in general: the unhandled suspension arm
parks without consulting the stop signal at all. That second site is the one IOTask uses for a
fiber awaiting a promise (`iotask-kernel2-integration-r2.md:768-781`, the residual trichotomy),
and it is what breaks the drive-local counter.

### 2.2 Holds: budget Defers inside a non partial eval never escape

The `Defer` arm's stop test is guarded by `partial` (`Eval.scala:104`), so `Eval.apply` always
resets the budget and steps (`Eval.scala:106-108`). A nested `Eval.apply` therefore cannot park,
which is what makes it usable as the synchronous driver for unwind and discard releases
(section 8).

### 2.3 Holds: `statePending` re-entry rebuilds the region and re-pushes a cell

`statePending` chains the decision after the pending clause outcome and runs it at `node.prev`
(`Eval.scala:53-54`), and its `Continue2` branch builds a fresh `Kyo.HandledState` around
`rebuild(hsAll, node, walk(kCont, c._2))` (`Eval.scala:151-158`). The crossed inner cells are
restored; the region cell is re-entered when the loop consumes that node
(`Eval.scala:117-122`).

### 2.4 Does not hold: "the dispatch's `Continue2` lands" is not a point the evaluator observes

In the pending clause path the `Continue2` is consumed inside the closure at `Eval.scala:152-158`,
which is arrow application, not a loop arm. Worse, that closure calls `walk(kCont, c._2)`
(`Eval.scala:154`), and `walk` on a settled value calls `resume` (`Eval.scala:192-196`), which
runs the continuation chain immediately. So at the moment the seed wants to close the window,
arbitrary user code from the continuation has already executed, with the region cell not yet
back on the spine. Closing the mask there runs the head of `use` masked (breaking contract point
2), and closing it later runs the head of `use` before the obligation is in any cell, so a throw
in that head leaks the resource.

The repair is to stop routing the acquire through a pending clause outcome at all. The register
operation is dispatched with a settled `Continue2`, and the interval that needs masking is
expressed as its own region (section 5).

### 2.5 Does not hold: a drive-local counter cannot survive a park

If the acquire suspends on an effect that no cell in this drive answers (an `Async` await under
IOTask's boundary layer), `Eval.scala:50` returns `rebuild(hs, Empty, v)` and the drive ends. A
counter held in a local, in a loop parameter, or in the thread's `Safepoint` slot is gone. When
the boundary resumes that remainder in a later drive, the acquire's tail runs unmasked, and its
next budget `Defer` can park with the resource already created and no obligation registered.
Contract point 3 is violated on precisely the async acquires it exists for. Section 5.3 shows the
cell based repair, which needs no save and restore because `rebuild` already preserves cells and
the loop re-counts them as it re-enters them.

---

## 3. The kind, the effect, and the cell (design question 1)

### 3.1 The kind

```scala
// internal/Handler.scala, alongside Cont, Loop, LoopState, First
trait Bracket[I[_], O[_], E <: ArrowEffect[I, O], A, S, State] extends Handler[I, O, E, A, S]:
    def apply[X](input: I[X], state: State): Outcome2[State, O[X] < (E & S), A] < S
    def release(state: State, outcome: Maybe[Throwable]): Any < S
```

The `apply` is `Handler.LoopState.apply` verbatim (`Handler.scala:22-23`), so the dispatch arm is
the stateful arm with one behavioral difference (section 6.1) and the clause vocabulary users
already know applies unchanged. `release` is the only new member. `Maybe` is already a kernel
dependency (`ArrowEffect.scala:5`).

Three deliberate choices in that signature:

- **`outcome` is `Maybe[Throwable]`, not a kyo `Result.Error`.** The kernel has no `Result`, and
  more importantly the settle path must not try to read failure out of the region's settled
  value. `kernel2-finalizer-design.md:234-235` records the bill for the alternative: "outcomeOf
  reads any settled `Result.Error` as the region's failure, misreporting a `Result.Error`-valued
  success". Error awareness is layered above: origin/main's `Scope.run` already computes the
  error itself after `Abort.run` and passes it to `finalizer.close(result.error)`
  (`origin/main:kyo-core/shared/src/main/scala/kyo/Scope.scala:129-140`), so the port keeps
  working exactly as it does today.
- **`release` returns `Any < S`, in the region's own outer row.** It can suspend. What drives it
  differs by path, and only one of the three paths can honor a suspension; section 8 states the
  contract.
- **`Outcome2`'s `done` is kept.** A bracket clause may short circuit (a closed scope refusing a
  registration). Section 6.4 specifies that a `done` at a bracket cell releases before it applies
  the exit, so the escape hatch cannot skip the obligation.

### 3.2 The effect it answers

The kind is generic in `I`, `O`, `E` exactly like the other four kinds, so the bracket region's
effect is the user's. Two consequences.

- **`Scope` can be a direct instance.** `Scope` would become an
  `ArrowEffect[Const[Maybe[Error[Any]] => Any < (Async & Abort[Throwable])], Const[Unit]]` whose
  `ensure` is `ArrowEffect.suspend` and whose `run` is one bracket region with `Chunk` state.
  Section 12.2 argues that the port should nonetheless keep `Scope`'s shared `Finalizer` payload
  for one specific reason (cross fiber sharing), and use the bracket region only for the close.
- **The sugar needs one internal effect.** `Effect.bracket` opens a region and immediately raises
  one operation into it, so it declares its own:

```scala
// internal, raised only by Effect.bracket's expansion
private[kyo] sealed trait Ensure extends ArrowEffect[Const[Any], Const[Unit]]
```

The input is erased to `Const[Any]` because the resource type varies per region and the clause
that reads it is generated by the same inline expansion that raised it, so the cast is kernel
authored and sits at the documented erased boundary (`CONTRIBUTING.md:173`). The name reuses
kyo's existing word for registering a finalizer (`origin/main:kyo-core/.../Sync.scala:108`,
`origin/main:kyo-core/.../Scope.scala:66`) rather than inventing one.

### 3.3 The cells: dedicated, not `StateNode`

```scala
// internal/Handlers.scala
final class BracketNode[I[_], O[_], E <: ArrowEffect[I, O], A, S, State](
    val handler: Handler.Bracket[I, O, E, A, S, State],
    val exit: Arrow[Any, Any, Any],
    val state: State,
    val prev: Handlers
) extends Handlers:
    def withState(state: State): BracketNode[I, O, E, A, S, State] = ...
    def withPrev(prev: Handlers): BracketNode[I, O, E, A, S, State] = ...

final class MaskNode(val exit: Arrow[Any, Any, Any], val prev: Handlers) extends Handlers:
    def withPrev(prev: Handlers): MaskNode = new MaskNode(exit, prev)
```

Reusing `StateNode` (`Handlers.scala:24-35`) was considered and rejected on three grounds, each
structural rather than stylistic:

1. **The settle arm must not ask a question.** With a dedicated class the arm at
   `Eval.scala:129-138` gains one `case` and the existing `StateNode` arm stays byte identical.
   With reuse, every ordinary stateful region exit would pay an `isInstanceOf` on the handler to
   find out whether it owes a release. That is a check whose job is to keep a property true,
   which the module guide names as the flag (`CONTRIBUTING.md:185`).
2. **`find` must skip a mask cell and must not skip a bracket cell.** A `MaskNode` has no handler
   and no tag, so it cannot be a `Node`; the walk at `Handlers.scala:46-56` gets one arm that
   follows `prev` unconditionally.
3. **The fork story becomes unrepresentable rather than policed.** `isolate-kernel2-design.md:747-759`
   asks for exactly this: "if bracket cells are a distinct cell type, `transplant`'s match has no
   arm that copies one, so double release is unrepresentable". The same holds for `MaskNode`: a
   child must not inherit its parent's uninterruptible window.

Node shapes mirror the cells, following the existing pattern where absence of state is node shape
rather than a sentinel (`KyoInternal.scala:106-144` versus `190-233`):

```scala
// internal/KyoInternal.scala
trait HandledBracket[I[_], O[_], E <: ArrowEffect[I, O], A, +B, S, -S2, State] extends Kyo[B, S & S2]
trait Masked[A, +B, -S] extends Kyo[B, S]     // value plus exit, no handler
```

`Kyo.Masked.map` and `Kyo.HandledBracket.map` chain onto the exit exactly as
`KyoInternal.scala:111-124` does, so composition after a mask region or a bracket region fuses
into the exit arrow and never lands inside the region. That is the property whose absence caused
the first defect class in the prototype (`kernel2-finalizer-design.md:47-51`: "extension landed
inside the region: downstream ran before release"), and here it is inherited from `Handled` for
free.

---

## 4. The region constructor and the sugar (design question 2)

### 4.1 The handler-kind entry

Following the naming of the existing family (`handle`, `handleLoop`, `handleFirst`,
`handlePartial`), the entry is `ArrowEffect.handleBracket`, built in the same shape as the
stateful `handleLoop` at `ArrowEffect.scala:163-184`: one anonymous object that is the handler
and the region node, no function value allocated, a settled input passing through with no node
at all.

```scala
@nowarn("msg=anonymous")
inline def handleBracket[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
    inline _tag: Tag[E],
    state: State,
    v: A < (E & S)
)(
    inline f: [X] => (I[X], State) => Loop.Outcome2[State, O[X] < (E & S & S2), A] < S2
)(
    inline r: (State, Maybe[Throwable]) => Any < (S & S2)
)(using inline _frame: Frame): A < (S & S2) =
    v match
        case kyo: Kyo[?, ?] =>
            val state0 = state
            new Handler.Bracket[I, O, E, A, S & S2, State]
                with Kyo.HandledBracket[I, O, E, A, A, S & S2, Any, State]:
                def tag                                             = _tag
                def apply[X](input: I[X], state: State)             = f(input, state)
                def release(state: State, o: Maybe[Throwable])      = r(state, o)
                val value                                           = v
                def handler                                         = this
                def exit                                            = Arrow[A]
                val state                                           = state0
        case v =>
            // settled: the effect cannot occur and nothing was registered, so the
            // value passes through with no region node and no release
            v.asInstanceOf[A < (S & S2)]
```

The settled arm is worth one sentence of contract: a bracket region over a settled value releases
nothing, because a region that never ran cannot have registered anything. That matches how the
other four kinds treat a settled input (`ArrowEffect.scala:73-77`, `124-128`, `179-183`).

A `handleBracketWith` variant that fuses the caller's continuation as the region's exit follows
the same construction as `ArrowEffect.scala:186-221` and is worth having for the same reason: one
object serving as handler, node, and exit.

### 4.2 The mask region

```scala
// kernel/Effect.scala, beside catching and defer
private[kyo] inline def masked[A, S](inline v: A < S)(using inline _frame: Frame): A < S =
    v match
        case kyo: Kyo[?, ?] => new Kyo.Masked.Impl[A, A, S](v, Arrow[A])
        case v              => v
```

The mask region has independent value beyond the bracket: it is the primitive `Async.mask` and
`Sync.uninterruptible` will want at port time. It is `private[kyo]` until a ruling says otherwise,
because an unbounded uninterruptible region is a capability the maintainer should choose to
publish.

### 4.3 The sugar

```scala
// kernel/Effect.scala
inline def bracket[A, S, B, S2](inline acquire: A < S)(
    inline release: (A, Maybe[Throwable]) => Any < S
)(
    inline use: A => B < S2
)(using inline _frame: Frame): B < (S & S2) =
    ArrowEffect.handleBracket[Const[Any], Const[Unit], Ensure, B, S, S2, Maybe[A]](
        Tag[Ensure],
        Maybe.Absent,
        Effect.masked(
            acquire.map(a => ArrowEffect.suspendWith[Any](Tag[Ensure], a)(_ => a))
        ).map(use)
    )(
        [X] => (input, _) => Loop.continue(Maybe(input.asInstanceOf[A]), ())
    )(
        (state, outcome) => state.fold(())(a => release(a, outcome))
    )
```

Reading it from the outside in: the bracket region opens first, so its cell is under everything;
inside it the mask region opens; inside that the acquire runs and its result is handed to one
`Ensure` operation, which the bracket cell answers by putting `Present(a)` in its state; the
answer settles, the mask region's value settles, the mask cell pops, and its exit is `use`.

Three properties fall out of that nesting rather than out of a rule:

- **`use` is outside the mask and inside the bracket**, because the mask cell was pushed after the
  bracket cell and pops before `use` starts.
- **The obligation is in a cell before `use` runs**, because the answer to the `Ensure` operation
  cannot reach `use` without crossing the mask cell's exit, and the cell is updated before the
  answer resumes (`Eval.scala:58-66`).
- **The user-visible row stays `S & S2`.** `Ensure` is handled by the region, `Kyo.Masked` carries
  no effect, and the tag is `private[kyo]`, so the user never names either.

A resource with no acquire (kyo's `Sync.ensure` shape) is the same region with the state supplied
at construction and no operation ever raised; section 12.3.

---

## 5. The acquire gap (design question 3)

### 5.1 The exact sequence, with every park site named

`Effect.bracket(acq)(rel)(use)` evaluated by `Eval.partial`, starting at spine `hs0`:

| step | value | arm | spine after | mask |
|---|---|---|---|---|
| 1 | `Kyo.HandledBracket` | new arm, mirrors `Eval.scala:117-122` | `BracketNode(prev = hs0)` | 0 |
| 2 | `Kyo.Masked` | new arm | `MaskNode(prev = BracketNode)` | 1 |
| 3 | `acq`'s suspensions | `Eval.scala:42-102` unchanged | unchanged | 1 |
| 4 | `acq`'s budget rescues | `Eval.scala:103-108`, stop test skipped | unchanged | 1 |
| 5 | `Kyo.Suspend(Ensure, a)` | `Eval.scala:42`, `find` skips `MaskNode`, matches `BracketNode` | cell replaced, state `Present(a)` | 1 |
| 6 | answer `()` then `a` | `Eval.scala:66` resume | unchanged | 1 |
| 7 | settled `a` at `MaskNode` | new settle arm | `BracketNode(prev = hs0)` | 0 |
| 8 | `use(a)`'s first `Defer` | `Eval.scala:103-105` | park: `rebuild(hs, Empty, v)` | 0 |

The resource exists from some instant inside step 3 or 4 (the kernel cannot know which, since it
is created by user code inside `acq`) to step 7. In that whole interval the only site that can
turn a stop into a park is the `Defer` arm at `Eval.scala:104`, and it is masked. Step 5's
dispatch and step 6's resume contain no stop test at all: reading the arms, `Eval.scala:51-70`
and `Eval.scala:199-205` never touch `Safepoint`. Step 7 pops the mask with the obligation
already in the cell that step 8's `rebuild` will reconstruct. That is contract point 3, proved by
enumerating the arms rather than by asserting an invariant.

Contract point 2 is the complement: from step 8 onward the mask is 0, so every `Defer` inside
`use` consumes the stop normally and parks with the `BracketNode` in the residual.

### 5.2 Skip, not consume, and why

The masked `Defer` arm becomes:

```scala
case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
    if partial && mask == 0 && Safepoint.consumeStopped(slot) then
        rebuild(hs, Empty, v)
    else
        Safepoint.reset(slot)
        loop(walk(kyo.cont, kyo.value), hs, mask, open)
```

`Safepoint.consumeStopped` writes the plain thread back over the `Stop` wrapper
(`Safepoint.scala:174-181`). Calling it inside the window and ignoring the answer would delete
the scheduler's request: `Safepoint.stop` installs the wrapper once and returns `true` if one is
already pending (`Safepoint.scala:158-172`), so nothing re-issues it. An interrupt would be lost
until some later, unrelated stop request, which for an interrupted fiber may never arrive.
Skipping the call leaves the wrapper in place, so the request fires at the first unmasked arm,
which is inside `use`. The added cost on unmasked code is one integer comparison placed before
the array read that `consumeStopped` performs, so the masked path also becomes cheaper than the
unmasked one rather than more expensive.

### 5.3 Where the mask level lives, and its nesting

It is a parameter of `evalLoop`'s `loop`, incremented when a `MaskNode` is pushed and decremented
when one pops. It is not a field, not thread state, and not a `Safepoint` bit, for one reason
that matters and one that is doctrine.

The reason that matters: a value can leave one drive and be resumed by another
(`EvalTest.scala:262-268` pins that a residual is resumable and answerable by a handler installed
later, per `iotask-kernel2-integration-r2.md:761-766`). Any storage outside the value loses the
mask at that boundary. Cells are inside the value: `rebuild` wraps every open cell around the
standing computation (`Eval.scala:270-282`) and the loop re-enters them one at a time
(`Eval.scala:109-128`), so drive B re-derives the same mask level from the same cells drive A
had. Nesting is the count of `MaskNode` cells on the spine, so an acquire that itself performs a
bracket nests correctly with no special case.

The doctrine: `CONTRIBUTING.md:175` rules out implicit plumbing and `CONTRIBUTING.md:31-33` asks
what keeps a property true. Here the answer is the shape of the spine, and the loop parameter is
a cache of it that the loop maintains at the two places the spine changes.

Two more sites must adjust the parameter, and both are cold:

- `Eval.scala:68-69` and `85-86` (`done` truncation) discard the cells between `hs` and `node`;
  if any is a `MaskNode` the level drops by that count.
- `Eval.scala:88-93` and `94-102` (`Cont` and `First` clauses) run the clause at a shallower
  spine; the same recount applies, and if the clause later calls its continuation the rebuild
  re-enters the cells and the loop re-increments. A masked window that a `Cont` handler discards
  therefore ends, which is correct: that computation is not running any more.

Both recounts are skipped entirely when `mask == 0 && open == 0`, so a program with no bracket in
scope executes the same arms it executes today.

---

## 6. The release paths (design question 4)

### 6.1 Dispatch: the one behavioral difference from `StateNode`

The bracket dispatch arm is the stateful arm (`Eval.scala:51-70`) with the cell class changed and
one addition: nothing. The clause is called, a settled `Continue2` replaces the cell and resumes,
a pending `Continue2` goes through a `bracketPending` that mirrors `Eval.scala:145-161` and
builds a `Kyo.HandledBracket` instead of a `Kyo.HandledState`, and a `done` takes the path in
6.4. The seed's per-dispatch mask window is not needed at this arm at all once the mask is a
region, which is the simplification the repair buys: the arm that answers is untouched hot-path
code.

### 6.2 Settle: release before exit

```scala
case n: BracketNode[?, ?, ?, ?, ?, ?] =>
    // the release is sequenced, not evaluated: it runs under n.prev inside a mask
    // region, and the region's exit applies to the saved result afterwards
    val released = n.handler.release(n.state, Maybe.Absent)
    loop(Effect.masked(released).andThen(walk(n.exit, v))(using Frame.internal), n.prev, mask, open - 1)
```

Reading it against the existing arm (`Eval.scala:133`, `loop(resume(n.exit, v), n.prev)`): the
exit application is unchanged, it is only deferred behind a computation. `andThen` takes its
argument by name (`Pending.scala:83-107`), so `walk(n.exit, v)` runs after the release settles,
under the popped spine, which is where the exit ran before. Two properties follow:

- **Ambient handlers are the enclosing ones.** The release runs at `n.prev`, so a handler inside
  the region cannot answer it (it has already exited) and an enclosing one can. That is the
  contract `kernel2-finalizer-design.md:32-33` pins ("locals bound around the ensure reach the
  finalizer").
- **The release cannot register into the region it is closing**, because the cell is off the
  spine before the release runs, so `find` resolves an `Ensure` raised by a release outward. This
  is the same property the kernel already gives clauses (`CONTRIBUTING.md:99`, a clause runs
  outside its own region), obtained the same way.

The release runs inside a mask region, so contract point 4's "release runs to completion once
started" holds against interruption, and a release that suspends parks inside its own mask cell
and resumes there.

### 6.3 Unwind: per arm `try` regions, no new register

`evalLoop` has no `try` today. The shape that adds one without breaking `@tailrec` is already
worked out for exception enrichment: per arm regions that exclude the tail call
(`exception-enrichment-design.md:506-523`), which keeps `hs` readable in the handler and costs no
instructions on the JVM when nothing throws.

```scala
val next =
    try resume(kyo.cont, answer)
    catch case ex: Throwable if NonFatal(ex) => releaseOpen(hs, ex, open); throw ex
loop(next, hs, mask, open)
```

`releaseOpen` returns immediately when `open == 0`. Otherwise it walks `hs` through `prev`,
innermost first, and for each `BracketNode` runs `release(state, Present(ex))` through a nested
`Eval.apply` over the cell's enclosing spine with the exits neutralized (section 8.2), catching
each release's own failure and calling `ex.addSuppressed` on it. That is contract point 4's
suppression rule and it matches the old kernel's `Ensure` behavior on the throw path
(`origin/main:kyo-kernel/.../Safepoint.scala:158-166`, `ensuring`).

The arms that need a region are the ones that can run user code: the three clause invocations
(`Eval.scala:52`, `74`, `93`, and the `First` clause at `102`), the two answered resumes
(`Eval.scala:66`, `84`), the `Defer` step (`Eval.scala:108`), and the settle exits
(`Eval.scala:132-138`). That is the same set `exception-enrichment-design.md:258-272` enumerates
as sites 3 through 9, so the two features share one set of regions rather than each adding its
own.

**Why releasing everything open at the throw point is correct.** The Java stack decides who sees
the exception first, and an `Effect.catching` guard that is on the stack is strictly inner: the
guard's own `try` sits inside `Arrow.Transform.apply` (`Effect.scala:32-51`), which the loop
arm's expression called. So the loop arm's handler runs only when no guard in this drive will
recover, which means the exception is leaving the drive and every open region is abandoned.
Nested drives are consistent: an inner drive releases its own open cells and rethrows, and the
outer drive's arm then releases its own.

**Happy-path cost.** Nothing is allocated and no branch is taken on a non-throwing path; the cost
is that `hs` and `open` must stay live into the handler, which can cost a register in the
compiled loop. The module's gate applies verbatim: this is a change to `Eval.scala`, so a JMH A/B
with `-prof gc` against a frozen baseline decides it, allocation read first
(`CONTRIBUTING.md:168`).

### 6.4 The `done` truncation, the fourth path

`done` feeds the handler's own exit and continues at `node.prev`, and the discarded cells' exits
never run (`CONTRIBUTING.md:98`, implemented at `Eval.scala:68-69` and `85-86`). If a bracket
cell is among the discarded cells, its release must run first. Unlike the `Cont` case, this is
provably safe to do eagerly: the `done` branches capture nothing (`Eval.scala:69`,
`Eval.scala:160`, `Eval.scala:184` all discard `kCont`), so no value in the program can bring
those cells back.

```scala
case done =>
    val exit = resume(node.exit, Nested.lift(done))
    if (open | mask) == 0 then loop(exit, node.prev, mask, open)
    else loop(releaseSpan(hs, node, Maybe.Absent, exit), node.prev, mask - m, open - k)
```

`releaseSpan` walks `hs` down to `node`, and for each `BracketNode` it finds sequences
`Effect.masked(release(state, Absent))` before the accumulated value, innermost first, so the
result is one computation the loop drives. This is `iotask-kernel2-integration-r2.md:897-909`'s
R-B3 met inside the kernel: an unhandled `Abort` in a fiber holding an open bracket releases it.

The outcome passed is `Absent`, because a `done` is a handler's ordinary decision, not a failure.
A handler that wants the region to see its error passes it through its own state or its own
effect, which is what `Scope.run` does today (`origin/main:kyo-core/.../Scope.scala:129-140`).

### 6.5 Discard: the evaluator, not a walk

```scala
private[kyo] def discard(v: Any < Any, error: Maybe[Throwable]): Unit
```

The scheduler calls this for a remainder that will never be evaluated again (IOTask's three
discard paths, `iotask-kernel2-integration-r2.md:863-869`). The implementation is one
transformation plus one ordinary drive:

1. `neutralize(v)` peels the head onion of rebuilt region nodes (`Eval.scala:245-268` are the
   classes `rebuild` produces), rebuilding each with `Arrow[Any]` as its exit and replacing the
   innermost standing value with `()`. It is an iterative walk with a local buffer, the same
   carrier `replace` uses (`Eval.scala:295-306`), so it is stack safe by the module's rule
   (`CONTRIBUTING.md:115-121`).
2. `evalLoop(neutralized, slot, partial = false, outcome = Present(error))`.

The loop then pushes every cell in nesting order and lets `()` settle, so the ordinary settle arm
of section 6.2 fires at each `BracketNode`, innermost first, under its own enclosing cells, with
`outcome` taken from the drive parameter instead of `Absent`. Neutralized exits mean no user
continuation runs; the neutralized mask cells mean an abandoned acquire's `use` never starts.

Three things this buys over a bespoke walk:

- **The order, the ambient handlers, and the release-before-exit rule are the settle arm's**, so
  there is no second implementation of finalization that can disagree with the first. The
  prototype's `finalizeArrow` was the second implementation, and it had to know `AndThen`,
  `Offset`, and the rotation node (deleted `Finalize.scala:68-85` at `cc446cf27b`).
- **A brand new region node in the remainder is handled with no special case:** its state is
  whatever it was constructed with, and the loop runs its release if it has one.
- **A cell caught mid acquire is visible.** Its state still says `Absent` (the sugar) or holds
  only the obligations registered so far (`Scope`), so nothing is released for a resource whose
  acquire never completed, which is the only honest answer available to any design.

`discard` needs no `mask` bookkeeping of its own: a non partial drive never parks
(`Eval.scala:104`), so it is uninterruptible by construction.

### 6.6 The path that is not in the contract, stated honestly

A `Handler.Cont` or `Handler.First` clause that returns without invoking its continuation
discards the inner cells (`Eval.scala:88-93`, `94-102`), and unlike `done` those cells are
reachable: the continuation closure captures `hsAll` and `rebuild` reinstates them on every call
(`Eval.scala:91-92`). The kernel cannot decide at the discard point whether the region is dead,
because deciding requires knowing whether user code will ever call a value it holds.

The rule this design takes, stated as a contract rather than left implicit: **a bracket cell
inside a region that a `Cont` or `First` clause abandons travels with the continuation.** If the
continuation is resumed, the region resumes and releases at its own exit. If the continuation is
dropped, the release does not run. The maintainer's contract point 4 names three paths (settle,
unwind, discard of a parked remainder) and this is a fourth, so it is a place where the contract
needs a ruling rather than a gap in the mechanism. Two notes for that ruling:

- The sibling node design faces the identical case, for the identical reason, and
  `iotask-kernel2-integration-r2.md:904-906` already records that a `Handler.Cont` "does not
  help".
- kyo's real consumer does not hit it. `Scope`'s obligations live in the cell of `Scope.run`,
  which is outside any handler nested in the scoped computation, so a discarded `Choice` branch
  cannot drop them; they release at `Scope.run`'s exit, which is exactly origin/main's behavior
  (`origin/main:kyo-core/.../Scope.scala:129-140`).

Related: exactly-once under a multi-shot continuation is not a kernel property under any design,
because two resumptions of one continuation reach the same immutable cell holding the same
obligation. The kernel's guarantee is per region exit; idempotence of an obligation is the
handler's, and the old kernel already pays for it the same way, with `Ensure extends
AtomicBoolean` and a `compareAndSet` (`origin/main:kyo-kernel/.../Safepoint.scala:142-155`).
`Effect.bracket`'s expansion therefore wraps the user's release in the same guard, since the
sugar promises exactly-once.

---

## 7. Ordering (design question 5)

Two rules, both derived rather than added.

- **Against the enclosing regions:** the release is sequenced before the cell's exit arrow
  (section 6.2), and the exit arrow is where composition after the region lands, because
  `Kyo.HandledBracket.map` chains onto the exit (`KyoInternal.scala:111-124`'s pattern). So
  release precedes every step composed after the region, at any nesting depth, on the settle
  path, the `done` path, and the discard path. On the unwind path nothing composed after the
  region can run at all, because the value never reaches the exit.
- **Against `Effect.catching`:** the innermost enclosing Java `try` wins, and a guard's `try`
  lives inside `Arrow.Transform.apply` (`Effect.scala:32-51`), so a guard on the stack recovers
  before the loop arm sees the throw, and one that is not on the stack does not run at all. A
  guard is on the stack exactly when the throwing step is part of a chain application it wraps,
  which is what the scaladoc means by "a region nested inside the computation evaluates on its
  own, so its internals are not covered" (`ArrowEffect.scala:253-255`).

### 7.1 Worked example

```scala
Effect.catching(
    Abort.run(
        Effect.bracket(acquire)(release)(a => useThatThrows(a))
    ).map(g)
)(recover)
```

Spine at the throw, innermost first: whatever `use` opened, `BracketNode`, the `Abort.run` region
cell, then the caller's. The mask cell popped at step 7 of section 5.1.

1. `useThatThrows` throws inside a loop arm's expression. `Abort.run`'s guard wraps that region
   node's exit (`Effect.scala:64-65`), not its internals, so no guard frame is on the stack.
2. The arm's handler runs `releaseOpen(hs, ex, open)`: `release(Present(a), Present(ex))` under
   the `BracketNode`'s enclosing spine, any release failure suppressed onto `ex`.
3. `ex` is rethrown, `Abort.run`'s exit never applies, `g` never runs, `recover` runs only if some
   guard's frame is on the stack, which for this shape it is not, so the exception surfaces from
   `eval`.

Change one thing, put the recovery inside the region:

```scala
Effect.bracket(acquire)(release)(a => Effect.catching(useThatThrows(a))(recover).map(h))
```

Now the guard is in the chain being applied when the throw happens, so it recovers first, the
bracket region settles normally with `h`'s result, and the release runs at the settle arm with
`Absent`, still before anything composed after the region. Both orders are the same rule: release
happens at the region's exit, and the region exits when its value settles or when the value
stops existing.

---

## 8. The release effect row (design question 6)

`release(state, outcome): Any < S`, in the region's outer row. What drives it differs:

### 8.1 Settle and `done`

The loop drives it, because the release is sequenced into the value (sections 6.2, 6.4). It can
suspend on anything the enclosing cells answer, it can park inside its mask cell and resume, and
its failures propagate as the region's failure, which is the correct reading of a release that
fails on the success path.

### 8.2 Unwind and discard

Both run inside a `catch` handler or inside `Eval.discard`, where returning a computation is not
possible, so both use a nested `Eval.apply`, which never parks (`Eval.scala:104` gates the stop
test on `partial`). The release runs under its own ambient handlers by rebuilding the cell's
enclosing spine around it with the exits neutralized, the same construction `neutralize` uses in
6.5, so a suspension the enclosing cells can answer is answered and a suspension they cannot is
`IllegalStateException("unhandled suspension: ...")` (`Eval.scala:49`), suppressed onto the
primary failure.

**This is the honest contract: a release that must park cannot run on the unwind or discard
path.** It is the baseline's contract, arrived at from the same direction twice already:
origin/main lowers every `Sync.ensure` finalizer through `Sync.Unsafe.evalOrThrow`
(`origin/main:kyo-core/.../Sync.scala:111`), and `kernel2-finalizer-design.md:233-234` states
"parking releases on discard-walk paths remain synchronous-eval only, stated as a restriction".
`iotask-kernel2-integration-r2.md:870-882` recommends keeping it (ruling R3), and this design
agrees, with the alternative noted: `discard` could return `Unit < Any` for the boundary to
drive, at the cost of a second termination mode in IOTask's run loop.

### 8.3 Masking during release

Settle and `done` releases run inside a mask region, so no interrupt cuts them. Unwind and
discard releases run in a non partial drive, which cannot park at all, so masking is automatic.
The one residual hole: an async release that parks voluntarily during a settle can be cut if the
boundary then discards the remainder, because the rest of the release chain lives in the
discarded body. It is unreachable in the baseline (its releases are synchronous) and it is worth
one pin and one line of documentation.

### 8.4 Enrichment attach point

`exception-enrichment-design.md:239-254` reserves bracket release failure as attach point 12, and
notes the distinguishing requirement: the release exception is a secondary, so it must be
enriched before it is suppressed. Under this design there are exactly three call sites to hook,
all of them kernel code with the bracket handler and the cell in scope: the settle sequencing
(6.2), `releaseOpen` (6.3), and the discard drive's settle arm (6.5).

---

## 9. State semantics and fork policy (design question 7)

**Single resource and accumulated obligations are the same cell with different state types.**
`Effect.bracket` uses `Maybe[A]`: `Absent` before the register, `Present(a)` after, and `release`
folds over it. `Scope` uses `Chunk[Finalizer]` and appends per registration. Nothing in the
kernel knows which; the state type is the handler's, exactly as for `Handler.LoopState`
(`Handler.scala:22-23`).

This is the design's clearest advantage over one node per resource: N resources in a scope are N
appends into one cell's state, not N nested nodes, and the release order is the state's order,
under the handler's control.

**State updates are cell replacement**, the mechanism that already exists: the arm computes the
successor cell and `replace` path-copies the cells above it (`Eval.scala:56-61`,
`Eval.scala:284-316`). Two consequences worth naming: registering from inside K nested regions
costs K cell allocations (section 10), and the path copy invalidates the identity of the copied
cells, so a rebuilt node captured earlier that names one of them falls back to allocating a fresh
cell at re-entry (`Eval.scala:111-114`), which is a cost, not a correctness issue.

**Fork policy under `isolate-kernel2-design.md:392-410`'s scheme: `Fork.Skip` for both new cell
kinds, and made structural rather than declared.** `isolate-kernel2-design.md:747-759` already
argues the case for the bracket cell: a copied cell means two releases of one acquisition. Since
`transplant` matches on cell class, omitting arms for `BracketNode` and `MaskNode` makes copying
unrepresentable rather than policed. `MaskNode` is skipped for a second reason: an
uninterruptible window is a property of the parent's execution, and a child that inherited it
would start life unpreemptable.

The interplay with the proposed answering kind (`isolate-kernel2-design.md:62-71`,
`Fork.Inherit` only for kinds that cannot short circuit) is empty: the bracket kind can `done`,
and it is `Fork.Skip` regardless. The pairing `isolate-kernel2-design.md:758-759` states holds
verbatim under this design: `rebuild` preserves exits and brackets, `transplant` neutralizes
exits and drops brackets.

One consequence to record for the port: a forked child cannot register into a parent's bracket
cell, because the cell is not in the child's spine and `find` will miss. For `Scope` that changes
today's cross-fiber semantics, which is why section 12.2 recommends keeping `Scope`'s shared
`Finalizer` payload.

---

## 10. Costs (design question 8)

| cost | when | size |
|---|---|---|
| bracket cell push | per region entry | one `BracketNode`, one `Kyo.HandledBracket` (fused into the handler object by the inline constructor) |
| mask cell push | per acquire window and per settle release | one `MaskNode` plus one `Kyo.Masked` |
| `find` walk | every operation raised inside the region | one extra iteration per bracket or mask cell crossed, each iteration a `prev` load plus the type tests that precede its arm in `Handlers.scala:48-53` |
| register round trip | per resource | one `Kyo.Suspend` (`ArrowEffect.scala:29-58`), one `find` walk, one clause call, one successor cell, plus `replace`'s path copy of K cells above it |
| mask test at the `Defer` arm | per budget rescue, roughly one per 512 transforms (`Safepoint.scala:31`) | one integer comparison, placed before `consumeStopped`'s array read |
| truncation recount | per `done`, `Cont`, `First` dispatch, only when `open` or `mask` is nonzero | a walk of the same span `find` just walked |
| unwind `try` regions | never on the happy path | zero instructions; `hs` and `open` live into the handler |
| loop parameters | every iteration | two integers, register resident |
| non-bracket code | always | the new arms sit last in every match (`find`, `replace`, `rebuild`, the settle arm, the node arms), so a `Node` or `StateNode` reaches its arm after the same tests as today |

The two that deserve emphasis, because they are steady state rather than per region:

- **The `find` tax.** A program wrapped in one `Scope.run` pays one extra cell in every
  suspension's tag walk, for the whole program. `isolate-kernel2-design.md:94-102` already flags
  the read cost of the walk as the one real regression risk for the fork design, and this adds to
  the same line. The node design does not pay it, because a node holds no tag and never enters
  the spine.
- **The register path copy.** Acquiring a resource at nesting depth K allocates K cells. For the
  `Effect.bracket` sugar K is 1 (the mask cell), which is the common case. For `Scope.ensure`
  called from deep inside handlers, K is the live handler depth.

The gate is not optional: `CONTRIBUTING.md:190` requires a JMH A/B against a frozen baseline with
`-prof gc` for any change to `Eval.scala`, allocation read first. The expected result on the
answering rows is byte identical, since nothing new is allocated when no bracket is open.

---

## 11. Prototype failure modes this design does not reintroduce

Read only, from `cc446cf27b` and its parent, to enumerate what to avoid.

| prototype mechanism | citation | why it is gone here |
|---|---|---|
| Java stack recursion per bracket, with a `BracketDepth = 512` cap | deleted `Eval.scala:34` (`recur(..., depth + 1)` guarded by the cap), `Finalize.scala:60` | a bracket is a cell; entry is `loop(kyo.value, new BracketNode(...))`, and nothing recurses, so there is nothing to cap. `CONTRIBUTING.md:121`: "when you find yourself sizing a cap, the question is not how big" |
| three drive modes (`Preemptible`, `Masked`, `Cascade`) | deleted `Eval.scala:12-18` | masking is a bounded region on the spine, not a mode of the whole drive, so a masked computation can still park at its unmasked steps |
| consume-and-reissue at the poll site (`maskPreempt` at the poll, `unmaskPreempt` at exit) | deleted `Eval.scala:29-30, 87-88, 95` | the masked arm skips the check and never touches the `Stop` wrapper, so nothing has to be reissued |
| `reacquire`, a node rebuilt with the resource inlined as its acquire | deleted `Finalize.scala:36-50` | the resource lives in the cell state, so a resumed acquire has no second representation to rebuild |
| `Finalize` as a transform in the chain, found by `finalizeArrow` walking `AndThen`, `Offset`, and rotation nodes | deleted `Finalize.scala:9-14, 62-85` | obligations live in cells; discard drives the remainder rather than parsing arrows, so no walk needs to know arrow internals |
| releases run by `.eval` inside that walk | deleted `Finalize.scala:54, 72` | settle releases are sequenced into the loop; only unwind and discard force a nested drive, and that restriction is stated rather than hidden |
| composition as a node field (`after`), the `Sequenced` node, activation tokens | `kernel2-finalizer-design.md:74-109` | composition after a region lands on the exit arrow, inherited from `Kyo.Handled.map` (`KyoInternal.scala:111-124`) |

---

## 12. Surface and the kyo-core mapping (design question 9)

### 12.1 Where things live

| item | file | visibility |
|---|---|---|
| `Handler.Bracket` | `internal/Handler.scala` | as the other kinds |
| `Handlers.BracketNode`, `Handlers.MaskNode` | `internal/Handlers.scala` | as the other cells |
| `Kyo.HandledBracket`, `Kyo.Masked` | `internal/KyoInternal.scala` | as the other nodes |
| `ArrowEffect.handleBracket`, `handleBracketWith` | `kernel/ArrowEffect.scala` | public, with the family |
| `Effect.bracket`, `Effect.masked`, `Ensure` | `kernel/Effect.scala` | `bracket` public, `masked` and `Ensure` `private[kyo]` |
| `Eval.discard` | `internal/Eval.scala` | `private[kyo]`, called by IOTask |

Inline discipline follows `CONTRIBUTING.md:81`: the constructors are inline so each region site
generates one anonymous object that is handler, node, and (in the `With` form) exit, with the
clause and the release compiled into its body and no function value allocated. The handling paths
(the kind's `apply` and `release`) are abstract methods called from `Eval`, never inline.

### 12.2 `Scope`

Two mappings, and the recommendation is the conservative one for the port.

**(a) `Scope` as a direct bracket instance.** `Scope` becomes an `ArrowEffect` whose input is a
finalizer, `Scope.ensure` becomes `ArrowEffect.suspend`, and `Scope.run(closeParallelism)` becomes
one `handleBracket` with `Chunk[Finalizer]` state whose `release` runs today's parallel close.
`Scope.Finalizer`, its `Awaitable` machinery, and the `ContextEffect` indirection collapse into
handler state.

**(b) `Scope` keeps its `Finalizer` payload and uses one bracket region for the close.** `Scope`
stays `ContextEffect[Scope.Finalizer]` (`origin/main:kyo-core/.../Scope.scala:37`), and
`Scope.run` wraps its `ContextEffect.handle` in a bracket region whose state is the finalizer and
whose release is `finalizer.close`, replacing the `Sync.ensure(finalizer.close)` at
`origin/main:kyo-core/.../Scope.scala:134`.

(b) is the recommendation for one reason that is not about effort. Under (a) the obligations live
in a cell, and cells are `Fork.Skip`, so a forked child cannot register into its parent's scope.
Under (b) the payload is a shared handle inherited as a context value, which is the behavior
`isolate-kernel2-design.md:771-782` relies on when it explains why `Fiber.init` composed with
`Scope.acquireRelease` works: "a child that inherits the `Scope` cell registers into the same
finalizer rather than into a copy". Moving to (a) is a semantic change to cross-fiber scopes and
belongs to its own ruling, not to the bracket landing.

`Scope.acquireRelease` (`origin/main:kyo-core/.../Scope.scala:80-85`) maps to
`Effect.masked(acquire.map(r => Scope.ensure(_ => release(r)).andThen(r)))` under either mapping.
Note what that closes: the baseline's `acquire.map { resource => ensure(...) }` has an unmasked
gap between the acquire settling and the registration landing, since the old kernel's preemption
check is `interceptor.enter` inside `Safepoint.enter`
(`origin/main:kyo-kernel/.../Safepoint.scala:30-45`) and a `map` step sits in that window. The
mask region is not only a kernel2 requirement, it repairs a hole the baseline has.

### 12.3 `Sync.ensure`

`origin/main:kyo-core/.../Sync.scala:108-111` is a bracket with no acquire and one obligation
known at construction, so it is a region whose state is supplied at construction and whose effect
is never raised:

```scala
inline def ensure[A, S](f: Maybe[Error[Any]] => Any < (Sync & Abort[Throwable]))(v: => A < S) =
    ArrowEffect.handleBracket(Tag[Ensure], f, v)(
        [X] => (input, state) => Loop.continue(state, ())    // unreachable: nothing raises Ensure here
    )((state, outcome) => state(outcome.map(Panic(_))))
```

The cell exists purely as a lifetime, which is `isolate-kernel2-design.md:747`'s framing. The
`Safepoint.ensure` machinery it replaces (the interceptor list, `addFinalizer`, `removeFinalizer`,
the per-step `ensuring` wrapper at `origin/main:kyo-kernel/.../Safepoint.scala:158-166`) has no
counterpart here, and neither does `Finalizers` in the scheduler
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/Finalizers.scala`), because IOTask's
outstanding releases are the cells inside its remainder
(`iotask-kernel2-integration-r2.md:851-857`).

`Sync.acquireReleaseWith` (`origin/main:kyo-core/.../Sync.scala:77-82`) becomes `Effect.bracket`
directly.

### 12.4 IOTask

The three requirements at `iotask-kernel2-integration-r2.md:863-909` map one to one:

- **R-B1, an abandonment entry:** `Eval.discard(remainder, Present(error))` (6.5), returning
  `Unit`, releases innermost first with the fiber's error.
- **R-B2, a throw escaping `Eval.partial` has already released:** the per arm regions (6.3) run
  the releases before the exception leaves `evalLoop`, so IOTask's fatal arm completes the promise
  and has nothing to drain.
- **R-B3, a `done` that truncates the spine releases the discarded cells:** the truncation arm
  (6.4).

---

## 13. Where the dedicated node shape likely wins

Stated as the sibling would state it, without hedging.

1. **No spine tax.** A `Kyo.Bracket` node holds no tag and never enters `Handlers`, so
   `Handlers.find` (`Handlers.scala:46-56`) is untouched and every operation raised inside a
   bracket region costs exactly what it costs today. This design taxes the walk for the lifetime
   of every open bracket region, which for a program-wide `Scope` is every suspension in the
   program. This is the single biggest steady-state difference and it favors the node.
2. **No registration round trip.** A node registers by existing. This design pays a suspension, a
   `find` walk, a clause call, and a `replace` path copy per resource (section 10). For a scope
   acquiring many resources deep inside handlers, that is a real allocation difference.
3. **Fewer shapes in the evaluator's data structures.** The node design adds one node class and
   one arm. This design adds two node classes, two cell classes, and arms in `find`, `replace`,
   `rebuild`, `Effect.guarded` (`Effect.scala:53-77`, where a missing arm silently drops the
   guard, the exact bug class of task #53), plus five evaluator arms. More shapes is more surface
   for a future change to get wrong, and the module's guide is explicit that a mechanism is a
   habitat for bugs (`CONTRIBUTING.md:33`).
4. **The extent is a node field.** With acquire, use, and release in one node, "release after
   use" is the node's own shape, and no ordering rule has to be maintained at an arm. This design
   gets the same property from the exit arrow, which is equally structural but arrives one
   indirection later.
5. **The bracket needs no state machine when there is exactly one resource.** The sugar's
   `Maybe[A]` state, the `Ensure` effect, and the erased input cast all exist only because the
   handler-kind formulation routes the resource through an operation. A node holds it directly.
6. **The mask may be cheaper to express.** If the node design accepts a drive-local counter and
   the accompanying limitation on async acquires (section 2.5), it avoids the `MaskNode` entirely.
   That is a real simplification bought with a real semantic concession, and the maintainer may
   judge the concession acceptable.

Where this design wins, for symmetry: the mask level and the outstanding obligations are
properties of the spine, so they survive parks, cross-drive resumes, rebuilds, and truncations
with nothing maintaining them; accumulated obligations are one cell rather than N nodes, which is
what `Scope` actually needs; release-before-exit is the settle arm the kernel already runs for
every cell; discard is the evaluator rather than a second finalization implementation; and any
effect can have a lifetime handler without a kernel-owned effect.

---

## 14. Open rulings

1. **Naming.** `Handler.Bracket`, `Handlers.BracketNode`, `Handlers.MaskNode`, `Kyo.HandledBracket`,
   `Kyo.Masked`, `ArrowEffect.handleBracket`, `Effect.bracket`, `Effect.masked`, `Eval.discard`,
   `Ensure`. `CONTRIBUTING.md:172` requires a ruling for every new noun; `mask` and `bracket` come
   from the maintainer's own framing, `discard` and `Ensure` do not.
2. **The `Cont`/`First` abandonment path** (6.6): is "the release travels with the continuation"
   the contract, or must the kernel do something else?
3. **May a bracket clause `done`** (6.4), or should the kind be restricted to an answering shape
   that cannot short circuit, aligning it with `isolate-kernel2-design.md:62-71`?
4. **`Eval.discard` returns `Unit`** (8.2), matching the baseline, or returns a computation for
   the boundary to drive?
5. **`Effect.masked` visibility:** `private[kyo]` now, or public as the uninterruptible-region
   primitive kyo-core will want for `Async.mask`?
6. **`Scope` mapping (a) or (b)** (12.2). Recommendation: (b) for the port, (a) as a separate
   ruling on cross-fiber scope semantics.
7. **Restoring the `Safepoint` slot on the throw path.** `Eval.apply` restores only on the normal
   path (`Eval.scala:19-21`), so an exception leaves the thread's budget drained today. Adding the
   unwind regions is the moment to fix it, with a `finally`.

---

## 15. Summary table

| dimension | this design (handler kind) |
|---|---|
| contract 1: holds acquire, use, release | acquire is the masked prefix of the region's value, use is the rest of it, release is a member of the handler object; one allocation per region carries handler, node, release, and optionally the exit |
| contract 2: use fully interruptible | the mask cell pops before the exit that starts `use`, so every `Defer` inside `use` consumes stops normally (`Eval.scala:103-105`) |
| contract 3: no interruption in the acquire gap | proved by arm enumeration in 5.1: the only stop-driven park is the `Defer` arm and it is masked from region entry to the pop that starts `use` |
| contract 4: release always runs | settle (6.2), `done` truncation (6.4), unwind (6.3), discard (6.5); failures suppressed onto the primary; releases run inside a mask or inside a non-partial drive |
| release path: settle | bracket cell's arm in `Eval.scala:129-138` sequences `Effect.masked(release)` before the exit; suspending releases supported |
| release path: unwind | per arm `try` regions excluding the tail call, sharing the set `exception-enrichment-design.md:506-523` proposes; walks `hs` innermost first; synchronous releases only |
| release path: discard | `Eval.discard` neutralizes the onion's exits, replaces the body with `()`, and drives it non-partially, so the settle arms do the work in the right order; synchronous releases only |
| acquire-gap mechanism | a mask region whose extent is a `MaskNode` cell; the loop counts pushes and pops; `Eval.partial` skips (never consumes) the stop test while the count is nonzero |
| allocations | per region: one fused handler/node object plus one `BracketNode`; per acquire window: one `Kyo.Masked` plus one `MaskNode`; per resource: one `Kyo.Suspend`, one successor cell, plus `replace`'s path copy; per release: one mask node pair; nothing on non-bracket paths |
| eval deltas | two node arms, one dispatch arm, two settle arms, a truncation walk on `done`/`Cont`/`First` gated by `(open \| mask) != 0`, one comparison at the `Defer` arm, per arm `try` regions, two loop parameters, one drive parameter for the discard outcome, arms in `find`, `replace`, `rebuild`, and `Effect.guarded` |
| fork story | `Fork.Skip` for both cells, made structural by omitting `transplant` arms; `rebuild` preserves them, `transplant` cannot copy them (`isolate-kernel2-design.md:747-759`) |
| Scope mapping | recommended: `Scope` keeps its shared `Finalizer` payload and `Scope.run` becomes one bracket region whose release is `finalizer.close`, replacing `Sync.ensure` at `origin/main:kyo-core/.../Scope.scala:134`; `Scope.acquireRelease` gains `Effect.masked`, closing a gap the baseline has; `Scope` as a direct bracket instance is possible and changes cross-fiber semantics |
| weakest point | the `find` tax on every operation inside an open region, plus the per-resource register round trip; then the number of new shapes the evaluator must match |
