# kernel2 preemption integration (design track A)

Status: design only. No code in this document has been compiled or benchmarked.
Companion documents: `kernel2-preemption-analysis.md` (how the Safepoint state
machine was arrived at, with the measurement trail) and `kernel2-todo-analysis.md`
(the review items and rulings that opened this track).

## 1. What this design decides

The Safepoint delivery mechanism is built and tested; the drives do not consult it.
This design connects the two and removes the interim API that stood in for the
connection. Nine decisions:

1. **One constant.** `Safepoint.Period = 512` replaces `Arrow.Period` and the
   private `Safepoint.Limit`. It is the eager depth budget and the chain segment
   length, which are the same quantity: frames between two trampoline bounces. No
   API takes a period.
2. **Three drive modes replace the `preempt`/`period`/`boundary` parameters.**
   A drive is Preemptible (a slice boundary: stop on a request and hand back the
   remainder), Masked (non-preemptible: absorb requests and re-issue them on exit),
   or Cascade (an installed-handler drive: return the remainder without consuming,
   so the request reaches the enclosing slice).
3. **The poll site is the top of the drive loop**, not the Defer arm. Polling only
   at Defer bounces is not sufficient; section 5.2 shows a computation that
   dispatches without ever producing a Defer.
4. **Public surface.** `eval: A` keeps its signature and becomes Masked.
   `handlePartial` loses `preempt` and `period` and keeps everything else.
   A new `private[kyo] evalPartial: A < Any` is the clause-free Preemptible drive.
   Nothing user-facing mentions preemption, matching the current kernel.
5. **Overflow is deleted.** A thread that cannot claim a slot gets a detached
   Safepoint backed by its own cell, so it keeps the depth guard, keeps making
   progress, and keeps receiving preemption. `Preempted.restore` becomes
   non-optional and `Safepoint.thread` stops being a `Maybe`.
6. **`Parked` becomes `Preempted`**, and its `resume` field becomes `restore`.
7. **The slice deadline moves into the Safepoint.** JS and Wasm have no second
   thread to request preemption, so the running task must fire its own request.
   The slice owner arms a deadline at slice entry; the drive's poll converts an
   expired deadline into an ordinary request. On JVM and Native the arm is
   `Long.MaxValue` and the poll short-circuits on one compare.
8. **Consume-then-check stays, and the check moves to the scheduler.** The drive
   consumes the request and returns the remainder; the caller reads its own
   authoritative state (promise, deadline) after the drive returns. Program order
   on the consuming thread carries the ordering edge across that boundary.
9. **Bracket release runs masked** for as long as it runs inside a drive, mirroring
   the current kernel's `Ensure.apply` (which detaches the interceptor around a
   finalizer). Masking is stack-scoped only; section 7.2 states what that does not
   cover and routes the residue.

The hot path does not change. `Arrow.guardedRun` is untouched, `Safepoint.enter`
keeps its single memory word, and no new load appears in any per-frame position.

## 2. Inputs that bind the design

### 2.1 The measured budget

From `kernel2-preemption-analysis.md`: on eagerMap5 the current shape costs
5.79 ns/op; folding any second load into the depth check costs about 1.4 ns per
frame regardless of access mode or placement. Everything below therefore keeps
per-frame work at exactly what it is today: `Safepoint.get` (one volatile array
read plus an ownership compare) and `enter` (one plain field read, compare, write).

The one per-frame cost this design *removes*: `Safepoint.thread` is currently
`Maybe[Thread]` and `ownedBy` is `thread.exists(_ eq t)`. `Maybe.isEmpty` is
`isInstanceOf[Absent]`, so every `get` on the fast path performs a type test that a
plain `Thread` field does not need. Deleting Overflow makes the field total, so the
test goes away. Expect a small improvement on eagerMap5, to be confirmed.

### 2.2 The rulings

- The period is always a constant, not overridable through any API, and belongs to
  Safepoint (ruling 1).
- The interim preemption API (`eval(preempt, period)`, `handlePartial(preempt,
  period)`) is rejected and needs a replacement (ruling 2).
- A task that runs without preemption, interruption, or stack-depth services is
  better than a task that never progresses; the permanently parked Overflow is
  therefore wrong (ruling 3).
- `Parked` reads oddly for a safepoint; prefer preemption-flavored naming (ruling 4).
- `AtomicReferenceArray[Maybe[Safepoint]]` if and only if it is free (ruling 5).
- `clearPreempt` sufficiency must be argued case by case (ruling 6).

## 3. The Safepoint after this change

### 3.1 States

```scala
private[kyo] sealed abstract class Safepoint(private[kernel2] val thread: Thread):
    def enter(): Boolean
    def exit(): Unit
    def preempt(): Unit
    private[kyo] def openDrive(): Long
    private[kyo] def closeDrive(saved: Long): Unit
    private[kyo] def endSlice(): Unit
    private[kernel2] def ownedBy(t: Thread): Boolean = thread eq t
    private[kernel2] def alive: Boolean              = thread.isAlive
```

Two states, as today, with the Overflow degeneracy removed:

```scala
final private[kernel2] class Active private[Safepoint] (
    thread: Thread,
    private[Safepoint] val home: Home
) extends Safepoint(thread):

    private var depth    = 0L
    private var deadline = Long.MaxValue
    private var masked   = false

    def enter(): Boolean =
        val d = depth
        if d < Period then
            depth = d + 1
            true
        else false

    def exit(): Unit    = depth -= 1
    def preempt(): Unit = val _ = home.swap(this, new Preempted(this, thread))

    private[kyo] def openDrive(): Long             = val d = depth; depth = 0L; d
    private[kyo] def closeDrive(saved: Long): Unit = depth = saved
    private[kyo] def endSlice(): Unit              = deadline = Long.MaxValue
end Active

final private[kernel2] class Preempted private[Safepoint] (
    private[Safepoint] val restore: Active,
    thread: Thread
) extends Safepoint(thread):
    def enter(): Boolean = false
    def exit(): Unit     = ()
    def preempt(): Unit  = ()

    private[kyo] def openDrive(): Long             = restore.openDrive()
    private[kyo] def closeDrive(saved: Long): Unit = restore.closeDrive(saved)
    private[kyo] def endSlice(): Unit              = restore.endSlice()
end Preempted
```

`depth`, `deadline`, and `masked` are plain single-writer fields owned by the
thread. The state swap never touches them, so a request can never corrupt depth
accounting, and frames already entered keep exiting against the `Active` they
captured.

`openDrive` and `closeDrive` delegating through `Preempted` is a correctness fix,
not cosmetics. A drive entered while a request is pending must still reset the
depth budget on the `Active`, because a Masked drive will consume the request and
continue running on that Active; without the delegation it would inherit the
caller's exhausted budget and crawl one frame per bounce.

### 3.2 Home: where a safepoint's current state lives
how about Current
```scala
sealed private trait Home:
    def current: Safepoint
    def swap(expect: Safepoint, update: Safepoint): Boolean

final private class Slot(index: Int) extends Home:
    def current = slots.get(index)
    def swap(expect: Safepoint, update: Safepoint) = slots.compareAndSet(index, expect, update)

final private class Cell extends AtomicReference[Safepoint] with Home:
    def current = get()
    def swap(expect: Safepoint, update: Safepoint) = compareAndSet(expect, update)
```

`Slot` is the array element a claiming thread owns. `Cell` is the standalone
reference a detached thread owns. `Home` is consulted only on the cold paths
(request, consume, detached `get`); the hot `Safepoint.get` fast path still reads
`slots.get(i)` directly with no indirection.

One `Home` and one `Active` are allocated per thread, once.

### 3.3 Deleting Overflow: the detached fallback (ruling 3)

Today a thread that fails to claim a slot after 8 probes receives `Overflow`, a
permanently parked safepoint. That is not merely a loss of service: it is a
livelock. `guardedRun` refuses every frame and rescues into `Kyo.Defer(v, t)`; the
drive pops the Defer and re-applies `t`; `t` is a lone transform, so `Arrow.apply`
routes it back through `guardedRun`, which refuses identically. The drive spins
forever on a step that can never execute. The same shape is what makes the poll
mandatory in every drive mode (section 5.4).

The replacement: `slow` hands out a detached `Active` whose `Home` is a private
`Cell`, cached in a `ThreadLocal` so the same instance is returned for the life of
the thread.

```scala
@static private val detached = new ThreadLocal[Cell]

@static private def slow(self: Thread, tid: Long): Safepoint =
    val d = detached.get()
    if d ne null then d.current
    else
        probe(tid.toInt & Mask, Probes) match
            case Absent =>
                val cell   = new Cell
                val active = new Active(self, cell)
                cell.set(active)
                detached.set(cell)
                active
            case Present(claimed) => claimed
```

Properties:

- **Progress**: `enter` performs real depth accounting, so eager runs execute and
  the rescue happens only at the real budget. No livelock.
- **Stack safety**: preserved, because the instance is stable per thread. A fresh
  `Active` per `get` would reset depth to 0 on every frame and defeat the guard
  entirely, which is why the fallback must be cached rather than allocated inline.
- **Preemption and interruption**: preserved. `preempt` and `clearPreempt` go
  through `Home`, and a `Cell` is the same kind of synchronization location as an
  array element, so the whole protocol including the memory-ordering argument
  applies unchanged. A requester holding the safepoint the slice owner published
  can reach a detached thread exactly as it reaches a slotted one.
- **Cost**: `get` on a detached thread costs one array read plus an ownership
  compare (the failed fast path), one `ThreadLocal.get`, and one `AtomicReference`
  read per frame, roughly 4 to 6 ns instead of about 1 ns. This is the price of
  running on a machine with more live kyo threads than the array can seat, and it
  is paid only by those threads.
- **No upgrade**: the ThreadLocal is checked before probing, so a detached thread
  never migrates to a slot later. Probing 8 slots per frame for the lifetime of a
  thread that is detached because the array is full costs more than the detached
  path itself, and a migration would split depth accounting across two instances
  for the frames in flight. The tradeoff is deliberate and worth one comment in
  the source.

`Safepoint.owned` (used by tests) must report true for a detached thread as well,
since the fallback is fully functional. The `onClaimedThread` retry helper in
`SafepointTest` then has nothing to guard and is deleted.

### 3.4 The slice deadline

`Worker` on JVM and Native passes `Long.MaxValue` as the deadline and relies on the
coordinator to call `Task.doPreempt` from another thread. The JS and Wasm scheduler
has no other thread: `Scheduler.schedule` runs the task from a macrotask with
`deadline = now + timeSlice`, and today the only thing that stops a CPU-bound fiber
is `IOTask.eval`'s `stop` callback reading the clock. Removing the callback without
a replacement would let a fiber with no out-of-band suspension hold the event loop
until it completes. The current kernel checks the clock at every `Defer` step, which
is the same cadence a kernel2 drive poll has, so moving the check into the poll
preserves behavior exactly.

The deadline is per-thread state armed by the slice owner:

```scala
@static def beginSlice(deadlineMillis: Long): Safepoint  // = get, with the deadline armed on the Active
// Safepoint#endSlice(): Unit                            // disarms, on the stashed instance
```

The poll converts an expired deadline into an ordinary request once, and everything
downstream sees a single request representation:

```scala
@static def pollPreempt(): Boolean =
    get match
        case _: Preempted => true
        case a: Active =>
            if a.expired then
                a.endSlice()   // one shot: the next slice re-arms
                a.preempt()    // from here the wrapper is the request
                true
            else false
```

On JVM and Native `expired` is `deadline != Long.MaxValue && ...`, so an unarmed
thread pays one field read and one compare per poll, and never reads a clock. On JS
the clock read happens at most once per 512 frames, against the current kernel's
once per 128 `InternalClock` calls.

### 3.5 The companion API

| Member | Who calls it | Meaning |
|---|---|---|
| `get: Safepoint` | `guardedRun`, drives | current state of this thread |
| `pollPreempt(): Boolean` | every drive, at the top of the loop | is a request pending (firing an expired deadline) |
| `clearPreempt(): Boolean` | Preemptible drive, at exit | consume the request, true if one was pending |
| `maskPreempt(): Unit` | Masked drive, at each poll | consume and record for re-issue |
| `unmaskPreempt(): Unit` | Masked drive, at exit | re-issue anything recorded |
| `beginSlice(millis): Safepoint` | slice owner (IOTask) | arm the deadline, return the instance to publish |
| `Safepoint#preempt(): Unit` | requester (scheduler), masked exit | request preemption of this safepoint |
| `Safepoint#endSlice(): Unit` | slice owner | disarm |
| `owned: Boolean` | tests | does this thread have a safepoint of its own |

`maskPreempt` and `unmaskPreempt` compose under nesting without any stack of their
own, because the recorded flag means "a request was consumed and has not been put
back", not "this particular region consumed one":

```scala
@static def maskPreempt(): Unit =
    if clearPreempt() then markMasked()

@static def unmaskPreempt(): Unit =
    if takeMasked() then get.preempt()
```

An inner masked region that consumes the flag an outer region set re-issues the
request; the outer region's next poll absorbs it again and sets the flag again. If
the outer region has no further poll, the request is already back in the slot, which
is where it needs to be. In every interleaving the request survives to the outermost
masked region's exit, and from there to the slice.

### 3.6 `Maybe[Safepoint]` slots (ruling 5)

Representationally the change is free: `Maybe` is opaque over `Absent | Present[A]`,
`Present[A]` for a non-null `AnyRef` that is not itself a `Maybe` is the value
itself, and `Absent` is a singleton. The erased array is the same
`AtomicReferenceArray[Object]`, the identity compare-and-set still works because the
stored reference is the `Safepoint` itself, and pre-filling is one
`new AtomicReferenceArray(Array.fill[Maybe[Safepoint]](Slots)(Absent))` at class
initialization rather than 256 volatile stores.

The cost is not in the representation, it is in the API, on the hottest path in the
kernel:

- `isEmpty` is `self.isInstanceOf[Absent]`, an `instanceof` against an abstract
  class, where the null-empty version has a null compare that the JIT usually folds
  into the surrounding dependent load.
- `get` on a `Present` runs `case _: Absent => throw; case self: PresentAbsent =>
  unnest; case v: A => v`, so reaching the `Safepoint` costs two type tests plus a
  checkcast that the typed array does not need.
- A `Present(s)` pattern match compiles to the same `isEmpty` plus `get` pair, so it
  does not avoid them.

Per `Safepoint.get` that is one type test on the empty branch and up to three on the
occupied branch, at a call site executed once per guarded frame. These are cheaper
than the dependent load that cost 1.4 ns per frame (they run on a value already in a
register and predict perfectly), but they are not free.

Recommendation: implement it as a separate commit and A/B it on eagerMap5 against
the shape it replaces, with the shape that measures faster kept. If the delta is
inside run-to-run noise, keep the `Maybe` version: the slot array is private to
`Safepoint` with three access sites, so the win is documentation of intent, and that
is worth having when it is genuinely free. If it costs, keep the null-empty array
with a comment naming this measurement so the question does not get re-opened.
Note that the two Maybe questions in `Safepoint` point in opposite directions: the
slots array gains a `Maybe` only if free, while `thread` and `Preempted.restore`
both *lose* theirs unconditionally because deleting Overflow makes them total.

### 3.7 Naming (ruling 4)

`Parked` becomes `Preempted`. The reading the user objected to disappears with
Overflow: there is no longer a safepoint that is parked with nothing to resume and
nobody having asked. Every instance of `Preempted` now means exactly one thing, a
pending request, so the class name is the fact.

`resume: Maybe[Active]` becomes `restore: Active`. The field is a state to put back,
not an action to perform, and `home.swap(p, p.restore)` reads correctly at the one
site that uses it. `Maybe` disappears with the Overflow case.

The drive-side poll is `pollPreempt()` rather than `preempted`, because an armed
deadline makes it fire a request rather than merely observe one, and a predicate
name would hide that. `clearPreempt` keeps its name; it is an exchange, and
`consumePreempt` was considered and judged not worth the churn.

## 4. Public API

### 4.1 Before

```scala
// Pending.scala
def eval: A
def eval(preempt: () => Boolean, period: Int): A < Any
private[kyo] def neverPreempt: () => Boolean

// ArrowEffect.scala
def handlePartial[...](effectTag: Tag[E], v: A < (E & S),
                       preempt: () => Boolean = `<`.neverPreempt,
                       period: Int = Arrow.Period)(clause: ...)(using Frame): A < (E & S)
```

### 4.2 After

```scala
// Pending.scala
extension [A](self: A < Any)
    /** Evaluates the computation to its value. */
    def eval: A

    /** Evaluates until the computation completes or a preemption request is consumed,
      * returning the remainder. */
    private[kyo] def evalPartial: A < Any

// ArrowEffect.scala
private[kyo] def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](
    effectTag: Tag[E],
    v: A < (E & S)
)(
    clause: [C] => (I[C], Arrow[O[C], A, E & S]) => Maybe[A < (E & S)]
)(using frame: Frame): A < (E & S)
```

`eval` is unchanged in signature and conforms to the current kernel. `evalPartial`
and `handlePartial` become `private[kyo]`, which is where the current kernel keeps
`handlePartial`; user code has no reason to drive a slice, and leaving them public
invites exactly the leakage this track exists to remove. The visibility change
overlaps with the pending C20 visibility audit and should be recorded there rather
than decided twice.

Nothing in the public surface names preemption, a period, or a callback.

### 4.3 How a caller runs a computation preemptibly

The return value is the whole protocol: a value means the computation completed, a
pending computation means it did not, and the caller resumes it later by driving it
again. This is what the current kernel does (`partialLoop` returns `v` both when
`stop` fires and when a suspension does not match), and what `IOTask.run` already
consumes through `next.evalNow`.

The drive does not tell the caller *why* it stopped, and does not need to:

- **Completed**: `evalNow` is `Present`.
- **Preempted**: the drive consumed the request and returns the remainder. The
  caller re-schedules it. There is no in-line retry loop and therefore no need to
  distinguish a stale request from a live one; a request aimed at a fiber that has
  already moved on costs one park and one re-schedule, which is the same work a
  legitimate preemption costs.
- **Parked out of band**: the boundary clause returned `Absent` after capturing the
  continuation, which is the clause's own action, so the caller that installed the
  clause already knows (`IOTask` sets `curr` and returns a null result from the
  clause today).

A stop-reason return channel was designed and rejected; see section 9.

## 5. The drive protocol

### 5.1 Three modes

```scala
private inline def Preemptible = 0   // slice boundary: stop on a request, consume at exit
private inline def Masked      = 1   // non-preemptible boundary: absorb and re-issue at exit
private inline def Cascade     = 2   // installed-handler drive: return the remainder, never consume

private def evalLoop(v0: Any < Any, mode: Int, last: Maybe[BoundaryClause]): Any < Any
```

| Call site | Mode | Rationale |
|---|---|---|
| `eval` | Masked | it must return `A`, so it cannot hand back a remainder; mirrors the current kernel's `Safepoint.eval`, which detaches the interceptor for the duration |
| `evalPartial`, `handlePartial` | Preemptible | these are the slice boundary |
| `install` (every `ArrowEffect.handle*`) | Cascade | it cannot decide a preemption, so it hands the park outward, exactly as the current kernel's handler loops cascade an interceptor refusal |
| bracket release sub-drive | Masked | section 7.2 |

The mode also replaces the existing `boundary: Boolean` parameter, since context
reads and their defaults resolve at Preemptible and Masked drives and park at
Cascade drives, which is precisely `mode != Cascade`. That coupling is temporary:
design track B removes `ContextRead` and `ContextSnapshot` altogether.

### 5.2 Where the poll goes, and why Defer bounces are not enough

The brief's hypothesis is that a `Defer` bounce arrives at least every 512 frames by
construction, so polling in the Defer arm suffices. The first half is true and the
second is not.

**The 512-frame bound holds.** Every path that executes transforms without returning
to the drive is bounded:

- Guarded eager execution (`Arrow.apply` on a lone transform to `guardedRun`)
  increments `depth` per nested frame and rescues into `Kyo.Defer` at
  `Safepoint.Period`.
- Fused chain execution (`Arrow.Offset.run`'s internal loop, and the `Offset`
  fast path inside `map`'s transform) runs unguarded, but `Arrow.optimize`'s
  `unfold` splices a `segmentBoundary` transform every `Period` elements, and
  `segmentBoundary.run` returns a `Kyo.Defer`. Chains of 32 elements or fewer are
  respined without a splice and terminate on their own.
- Every chain is optimized before it is executed (`applySlow` optimizes an
  `AndThen` before applying it; `dispatch` optimizes `c.cont`), and `prefixArrow`
  rebuilds prefixes from transforms collected out of an already spliced chain, so
  boundaries are preserved through dispatch.

A tight `Loop` over immediate values does produce bounces, through the first
mechanism rather than the second. `Loop.apply` is `loop(v) = v.map { o => ... loop(run(next._1)) }`;
with an immediate value and an immediate outcome, `map` builds a lone transform and
`Arrow.apply` routes it to `guardedRun`, and the recursive `loop` call happens
*inside* the transform's `run`, so each iteration is a nested guarded frame. Depth
reaches 512 after 512 iterations, `enter` refuses, and the `Kyo.Defer` returns
through the nested `run` frames (each of which has nothing left to do, since its
continuation is the empty arrow) to the drive.

**But dispatch steps need no Defer.** `evalLoop`'s `Kyo.Continue` arm dispatches a
suspension and continues with the result. A resuming handler whose clause suspends
again produces another `Continue` with no guarded frame and no chain segment in
between:

```scala
def program(i: Int): Int < Echo = if i == 0 then 0 else echo(i).map(_ => program(i - 1))
```

driven by `handlePartial` with `[C] => (in, cont) => Maybe(cont(in))` dispatches
10000 times. This is exactly the shape `ArrowEffectTest`'s "handlePartial polls
preemption across dispatches" pins, and it is why the current code consults
`preempt()` in the dispatch arms and not only in the Defer arm.

**Resolution**: poll once at the top of `loop`, before the match. That covers Defer
pops, suspension dispatches, and the bare-suspension arm with one site instead of
three, and it also means a drive entered with a request already pending returns
immediately having done no work, instead of performing one dispatch first.

Latency bound, stated as a property to test: from the instant a request is installed,
the drive regains control within one segment, that is at most `Safepoint.Period`
transform executions plus the in-flight fused chain, and returns at the following
poll.

Cost: one `Safepoint.get` and one type test per loop iteration. That replaces one
indirect call through the `preempt: () => Boolean` closure per iteration, so the
dispatch-heavy rows should not regress. The alternative of polling through the
drive's captured `Active` (`home.current ne this`, avoiding `Thread.currentThread`)
is available if a benchmark asks for it, but it needs a re-read after any consume
because the captured reference goes stale, and it is not worth that hazard until a
measurement demands it.

### 5.3 The consume rule

The poll observes; it does not consume. Consumption happens once, at drive exit, and
only in Preemptible mode:

```scala
val safepoint = Safepoint.get
val saved     = safepoint.openDrive()
try
    val result = recur(v0, 0)
    if mode == Preemptible && Safepoint.pollPreempt() then val _ = Safepoint.clearPreempt()
    result
catch ...
finally
    if mode == Masked then Safepoint.unmaskPreempt()
    safepoint.closeDrive(saved)
```

Consuming at the poll site instead would break the bracket cascade: the bracket arm
decides whether to keep driving after an acquire or a use by asking whether the
nested `recur` stopped for a request, and if the request were already consumed the
answer would be no and the drive would continue past a park it had already decided
to take. With consumption at exit, the state is still `Preempted` while the stack
unwinds, so the cascade check is simply `Safepoint.pollPreempt()`.

That also lets the private helper `preempted(v: Any < Any) = v.isInstanceOf[Kyo.Defer[?, ?, ?]]`
in `Pending.scala` be deleted. It is a proxy for "the nested drive stopped because of
preemption" that works only because `recur` cannot return a `Defer` for any other
reason, and it collides by name with `Safepoint.preempted`. The direct question
replaces it.

Masked mode consumes at every poll instead, because absorbing is the only way it can
make progress: while the state is `Preempted` every frame refuses, and a drive that
neither returns nor consumes spins on the rescue Defer (section 5.4). It records
each consume and re-issues once on exit, so the enclosing slice still sees the
request.

Cascade mode neither consumes nor re-issues. It returns the remainder, and the
handler it installed travels in that remainder by construction (`dispatch` keeps the
`Handler` node in the chain it rebuilds, and the drive returns `curr` with the whole
chain intact), so the enclosing drive resumes into the same handler region.

### 5.4 Why every mode must poll

The Overflow livelock generalizes: **a drive that ignores a pending request cannot
make progress through a lone-transform Defer.** The drive pops `Defer(v, t)`, applies
`t`, `guardedRun` refuses because the state is `Preempted`, and the identical Defer
comes back. Cascade drives are therefore not exempt from polling; they are exempt
from consuming. With the trichotomy in place every mode terminates: Cascade and
Preemptible return, Masked consumes and the state becomes `Active` again, so frames
enter and the computation advances.

### 5.5 Nested drives

`openDrive` and `closeDrive` are unchanged in role: a drive is a fresh trampoline, so
it resets the depth budget and restores it on the way out. Three interactions:

- Entering with a request pending: `Safepoint.get` returns the `Preempted` wrapper,
  whose `openDrive` and `closeDrive` delegate to `restore` (section 3.1). Without the
  delegation a Masked drive would consume the request and then run on the caller's
  exhausted budget.
- A request landing mid-drive: the captured reference is the `Active`, and the
  wrapper installed by the requester carries that same `Active` as `restore`, so a
  later consume puts back the object whose `depth` the drive has been accounting on,
  with the value it has. `closeDrive` on the captured reference is correct whether or
  not a request landed.
- A Preemptible drive nested inside another slice is not a supported configuration.
  Its exit-consume would take the outer slice's request. Nothing in the runtime does
  this today (a slice's boundary drive is the outermost drive of that slice), and the
  rule belongs in `handlePartial`'s scaladoc.

## 6. Memory ordering, and whether consume-then-check is enough (ruling 6)

### 6.1 The protocol

- The `Home` cell (array element or standalone reference) is the single
  synchronization location for a thread's safepoint.
- A requester publishes its condition first (the promise compare-and-set, the
  scheduler state write), then compare-and-sets the cell from the `Active` it holds
  to a `Preempted` carrying it.
- The victim observes through `Safepoint.get`, a volatile read it performs on every
  frame anyway. No fence and no additional read appear on the frame path.
- The consumer compare-and-sets the cell back to the `Active`, and only then reads
  its authoritative state.

Delivery cannot be lost: both transitions are compare-and-set on the cell, and the
owner never writes the cell outside claiming, which happens before any requester can
name the `Active`. Slot reclamation installs a fresh `Active`, so no state of a dead
owner, including a pending request, survives into a new one.

Visibility: the consumer's compare-and-set reads the value the requester's
compare-and-set wrote, so the two synchronize with each other. Everything the
requester did before its write, including publishing its condition, happens-before
everything the consumer does after its read, including the authoritative check. The
check runs in the scheduler after the drive returns, which is the same thread in
program order, so the edge extends across the drive-to-caller boundary. This is why
consume must be an exchange rather than a plain store: a blind restore racing a
request would erase a request whose condition the subsequent check has not yet seen.

### 6.2 The cases

**A second request arriving mid-slice, after a consume.** The cell holds `Active`,
so the requester's compare-and-set succeeds and the request is pending. The drive's
next poll observes it. Nothing special.

**A second request arriving between the consume and the drive returning.** The drive
has already decided to park and returns the remainder; the new request stays pending
in the cell. If it was aimed at this fiber, the next slice of this fiber parks at its
first poll, having done no work, and its owner consumes it: correct, at the cost of
one round trip. If the worker picks up a different task first, that task takes one
spurious park and re-schedule, which is the wrong-victim case below.

**Multiple requesters.** Two threads both hold the same `Active` and both
compare-and-set. One wins; the loser's compare-and-set fails and is a no-op. The
loser's condition is still observed, and the argument is precise: the loser's failed
compare-and-set is a synchronization action on the same location that read the
winner's value, so it is ordered before the consumer's exchange in that location's
total order, and the loser's condition write precedes it in program order. Therefore
the loser's condition happens-before the consumer's post-consume check.

This holds only because the request carries no payload and the consumer re-reads its
*complete* authoritative state (promise state, deadline, scheduler flags) rather than
something attached to the wrapper. Keeping the wrapper payload-free is a correctness
requirement of this protocol, not an economy.

**Interruption on the same channel.** `IOPromise.interrupt` completes the promise
(the authoritative, never-lossy record) and then requests preemption for latency. The
drive parks; the caller reads `needsInterrupt()` after the consume and sees the
completed promise by the argument above. A request that is lost or aimed at the wrong
thread delays the observation to the next re-assert, and never loses the interrupt,
because the promise is the anchor. This is the two-level split the analysis document
established, unchanged.

**A masked region.** `maskPreempt` consumes and records; `unmaskPreempt` puts the
request back with a fresh compare-and-set on the current `Active`. A request landing
during the masked region succeeds normally and is absorbed at the next poll. A
request landing after the region's last poll but before its exit stays pending and is
seen by the enclosing drive. In every case at most one request is pending at a time,
which is all the protocol represents.

**The wrong victim.** A request delivered to a thread whose fiber has moved on causes
one park and one re-schedule for whatever runs there next. This is more expensive
than the old shape, where the drive consulted `preempt()` and continued in place, and
it is the deliberate price of removing the callback. The frequency is bounded by the
coordinator's tick rate per worker, so it is at most one wasted re-schedule per tick
per worker, and it is self-healing: the consume clears the state.

**A stale request at slice start.** The slice owner does not consume defensively at
slice start (the open question the analysis document left). It cannot distinguish a
request aimed at the previous fiber from an interrupt aimed at this one that landed
between scheduling and running, and consuming blind would drop the latter. Not
consuming costs at most one park and one re-schedule; dropping an interrupt costs a
whole slice of an interrupted fiber. The drive's first poll handles it correctly
either way.

### 6.3 Per drive kind

| Mode | On observing a request | Consumes | Re-issues |
|---|---|---|---|
| Cascade | returns the remainder immediately | no | no |
| Preemptible | returns the remainder | yes, once at exit | no |
| Masked | absorbs and continues | yes, at every poll | yes, once at exit |

## 7. Interactions

### 7.1 `Effect.catching`

No change. `Catching.run` calls `cont(...)` and, if the result is a `Kyo`, prepends
itself so later steps stay intercepted. A preemption-induced park is a `Kyo.Defer`
produced by `guardedRun`'s rescue, indistinguishable from any other park, and
`Defer.prepend` puts `Catching` in front of the deferred continuation, so the
re-armed interception covers the resumed steps. The same holds for `Observe` and for
the `Finalize` node a bracket leaves in a parked continuation. Worth one test, not
one line of code.

### 7.2 Bracket

**Acquire is preemptible.** A request landing during an acquire parks it, and the
drive wraps the remainder with `reacquire(bracket)`, so the resumed computation
continues the acquire it started and rebuilds the bracket around the result. Nothing
is re-run and nothing leaks.

**Release runs masked**, mirroring the current kernel. There, `Safepoint.Ensure.apply`
sets the interceptor to null around a finalizer, so `enter` cannot refuse and neither
a time-slice preemption nor an interrupt can cut it. The kernel2 equivalent is Masked
mode around the two places a release executes:

- `evalLoop`'s bracket arm, which drives `bracket.release(resource)` in a nested
  `recur`; that nested drive runs Masked.
- `Finalize.run`, which evaluates `bracket.release(value)` eagerly on the drive's
  stack before splicing the result into the chain. The evaluation is wrapped by
  `maskPreempt` and `unmaskPreempt`.

**What masking does not cover, stated plainly.** Masking is stack-scoped: it consumes
requests that are pending when the region starts and at each of its poll points, and
it re-issues them on exit. A request that lands *during* an eager release, between two
of its frames, still makes the next frame refuse and parks the release halfway. Making
that window airtight would need either a per-frame mask check (rejected: it is a
second load on the path measured at 1.4 ns per frame) or additional `Safepoint`
states at the shared `enter` call site (rejected: the design depends on that site
staying bimorphic with overwhelming `Active` skew, and a third and fourth klass risks
the speculative devirtualization that makes the whole mechanism free). A chain-level
mask marker was also rejected: a mask that travels in a continuation outlives the
thread that set it and would leak onto whatever thread resumes the remainder.

**The residue, routed.** If a release parks halfway and the remainder is then
abandoned, `discard`/`finalizeBracket` walks the chain for `Finalize` nodes and finds
none for that bracket, because the `Finalize` node already ran and spliced the
release in. The rest of the release never runs. This is a property of the current
Bracket and abandonment design, not something preemption introduces (any effect
suspension inside a release has it), but preemption makes it easier to hit, since an
interrupt uses the same channel. It belongs to the bracket track, and the shape of a
fix is visible from here: `Finalize` should re-prepend itself around the spliced
release the way `Catching` re-arms, so an abandoned remainder still carries a node
that knows how to finish the release. Recorded here so it is not lost; not designed
here.

### 7.3 `handlePartial`'s boundary clause

The clause runs host code (the `IOTask` body) inside the drive. A request landing
mid-clause makes the eager evaluation inside `cont(...)` rescue into a `Defer`, so the
clause returns `Maybe(defer)`; the drive treats it as the next computation, polls,
sees the request, and parks with it as the remainder. Every value the clause returns
is a computation, so a Defer in that position is ordinary. The out-of-band path
(`input.onComplete { r => curr = Sync.defer(cont(r)); schedule }`) builds its
continuation lazily and runs on another thread's safepoint, so nothing there is
affected.

## 8. IOTask adaptation sketch

This is the consumer the design is validated against. It is not part of this track's
implementation (kernel2 is not wired to the scheduler yet), but the protocol has to
close end to end.

```scala
// IOTask
@volatile private var running: Safepoint | Null = null

final override def doPreempt(): Unit =
    super.doPreempt()                      // scheduler-level state, published first
    val sp = running
    if sp ne null then sp.preempt()        // delivery; no-op if already requested

final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
    val safepoint = Safepoint.beginSlice(deadline)
    running = safepoint
    try
        val next = ArrowEffect.handlePartial(erasedAbortTag, Tag[Async.Join], curr)(
            [C] => (input, cont) => { completeDiscard(input); Absent },
            [C] => (joinInput, cont) => { ... as today ... }
        )
        if !isPending() then
            // interrupted: finalize the remainder and finish
            ...
            Task.Done
        else
            next.evalNow match
                case Present(a) => completeDiscard(Result.succeed(a)); Task.Done
                case Absent     => curr = next; Task.Preempted
    finally
        running = null
        safepoint.endSlice()
```

What changed relative to today:

- The `stop` callback is gone. Time-slice preemption arrives through `doPreempt`,
  interruption arrives through `onComplete` (which already calls `doPreempt`), and
  the deadline is armed on the safepoint instead of read in a callback.
- `shouldPreempt()` is no longer read by the drive. `Task.State`'s preempt bit
  becomes a scheduler-internal signal only. It should not be deleted as part of this
  work: `Task` is a published, standalone contract with its own tests, and
  `doPreempt` remains the scheduler-level entry point. Worth a note in the scheduler,
  not a change.
- A request delivered to a task that is not running is dropped (`running` is null)
  rather than setting a bit that makes the task's next slice yield immediately. That
  is an improvement: no slice is wasted.
- The re-assert authorities are unchanged in role. `Worker.checkStalling` calls
  `doPreempt` on every tick while a task is past its slice, which now re-delivers the
  request, so a request aimed at a thread the fiber has left is re-aimed one tick
  later. For interrupts, `onComplete` fires once for latency; the BlockingMonitor
  scan is the natural place to re-assert `doPreempt` for tasks whose
  `needsInterrupt()` still holds, and that hook should land with the migration.

**One gap this sketch exposes.** `IOTask` needs two effects handled at the boundary
(`Abort[E]` and `Async.Join`), and kernel2's `LastResort` carries a single tag and
clause. The current kernel's `handlePartial` takes two tags for exactly this reason.
The boundary clause carrier therefore has to hold a pair (or a short list) of
tag-and-clause entries, which the approved C14 rework of `LastResort` into a typed
`Maybe`-threaded node should account for. Flagged here because it is a hard
requirement of the real consumer, not a nicety.

## 9. Alternatives considered and rejected

**Keep a `stop: () => Boolean` callback, drop only `period`.** This is the smallest
change and it is what the current kernel does. Rejected by ruling 2, and independently:
the callback is one indirect call per drive-loop iteration, it makes the drive depend
on scheduler state, and it is exactly the leakage of runtime internals into the
kernel's API that the user objected to. Everything it buys (a stale request continuing
in place instead of costing a re-schedule) is measurable in re-scheduled tasks per
coordinator tick and is small.

**Return a stop reason (`Done | Parked | Preempted`) from the partial drives.**
Rejected. It allocates per slice, it widens the API, and the caller does not need it:
the clause's own bookkeeping already distinguishes an out-of-band park, and a
preempted remainder and a clause-parked remainder are both simply re-scheduled or
handed to a callback. The current kernel returns the remainder for both cases and
`IOTask` copes.

**Let the caller consume (`clearPreempt` called by IOTask instead of the drive).**
This is attractive because the consume and the authoritative check would sit in the
same method, and the boundary drive would become identical to a cascade drive.
Rejected because it pushes the protocol onto every caller: a caller that re-drives
without consuming spins, since the state is still `Preempted`. Keeping the consume in
the drive means `evalPartial` has a self-contained contract, and the ordering
argument survives the boundary through program order (section 6.1).

**Consume at the poll site rather than at drive exit.** Rejected: the bracket cascade
needs to ask, after a nested `recur` returns, whether it stopped for a request. See
section 5.3.

**An unregistered `Active` with no cell for the Overflow replacement** (the candidate
named in the ruling). It restores progress and the depth guard but loses preemption
and interruption delivery for that thread, which means a fiber running on a detached
worker cannot be interrupted at all until it reaches a suspension. The `Home`
indirection that avoids this costs one field on `Active`, one sealed trait with two
tiny implementations, and a virtual call on paths that run once per request. Since
the ruling permits the weaker version, choosing the stronger one is safe, and it
removes an "except when the array is full" clause from the mechanism's contract.

**A `ConcurrentHashMap[Thread, Safepoint]` as the overflow home.** Delivery would work
(`replace(k, old, new)` is a compare-and-set), but entries keyed by `Thread` outlive
the threads and grow without bound, so it needs its own reclamation. A `ThreadLocal`
gets that for free.

**Growing the slot array or making it resizable.** `Mask` would become a field load on
the `get` fast path. Rejected on the measured budget.

**An array of cells (`Array[AtomicReference[Safepoint]]`) so slotted and detached
homes are uniform.** `get` would become an array read plus a field read, the second
dependent on the first. That is the shape that cost 1.4 ns per frame. Rejected.

**Airtight masking via extra `Safepoint` states** (a `Masked` state whose `enter`
succeeds and a `MaskedPreempted` that records a request). It would make release
uncuttable even by a request that lands mid-release. Rejected because it puts a third
and fourth klass at the `enter` call site shared by every guarded frame in the
system, and the measured design depends on that site staying bimorphic.

**A chain-level mask marker** (transforms that mask and unmask around the spliced
release). Rejected: the region can park between them on a real effect, and the mask
would then survive into another thread's safepoint. Masks must be stack-scoped.

**A work-quantum instead of a deadline for JS time slicing** (park every N bounces).
No clock in the kernel, but the mapping from bounces to wall time varies by orders of
magnitude with frame cost, so the scheduler would have to run a feedback loop to hit
a latency target. A clock read once per 512 frames is cheaper and honest.

**A `deadline: Long` parameter on `handlePartial`.** Functionally identical to the
Safepoint-armed deadline, but it re-introduces a per-call preemption parameter, which
is what ruling 2 removed, and it does not compose with nested drives (a nested drive
would silently run without the enclosing slice's deadline).

## 10. Code changes, file by file

### `kyo-kernel2/shared/src/main/scala/kyo/kernel2/internal/Safepoint.scala`

1. `private[kyo] inline def Period = 512` replaces `Limit`, exported to the kernel
   package so `Arrow.optimize` can use it. `Slots`, `Mask`, `Probes` unchanged.
2. `Safepoint(thread: Thread)` replaces `Safepoint(thread: Maybe[Thread])`;
   `ownedBy` becomes `thread eq t`; `alive` becomes `thread.isAlive`.
3. `Parked` renamed `Preempted`; `resume: Maybe[Active]` becomes `restore: Active`;
   `openDrive`/`closeDrive`/`endSlice` delegate to `restore`.
4. New sealed `Home` with `Slot(index)` and `Cell extends AtomicReference[Safepoint]`;
   `Active` carries `home` instead of `index`; `preempt` goes through `home.swap`.
5. `Overflow` deleted. `slow` checks the detached `ThreadLocal` first, then probes,
   then installs a detached `Cell` and `Active`.
6. `Active` gains `deadline: Long` and `masked: Boolean` (plain, owner-written) with
   `expired`, `endSlice`, `markMasked`, `takeMasked`.
7. Companion: `pollPreempt()`, `clearPreempt()`, `maskPreempt()`, `unmaskPreempt()`,
   `beginSlice(millis)`; `preempted` removed in favor of `pollPreempt`; `owned`
   accounts for the detached case.
8. Scaladoc rewritten: the Overflow paragraph goes, the detached fallback and the
   three drive modes go in, and the memory-ordering argument from section 6 is
   condensed into the class comment as it is today.

### `kyo-kernel2/shared/src/main/scala/kyo/kernel2/Arrow.scala`

9. `Arrow.Period` deleted; `optimize`'s `unfold` splices every `Safepoint.Period`.
10. `guardedRun` unchanged, and it should stay byte-identical; it is the measured path.

### `kyo-kernel2/shared/src/main/scala/kyo/kernel2/Pending.scala`

11. `driveLoop` becomes `evalLoop(v0, mode, last)`: `preempt`, `stride`, and `boundary`
    parameters deleted, `boundary` derived as `mode != Cascade`.
12. The poll moves to the top of `loop`; the three `if preempt() then ...` sites in the
    Defer, Continue, and Suspension arms collapse into it.
13. Exit handling per section 5.3, in the existing `try`/`finally` around `recur`.
14. Bracket arm: `preempted(wrapped)` replaced by `Safepoint.pollPreempt()`; the
    private `preempted(v: Any < Any)` helper deleted; the release sub-drive runs Masked.
15. `Finalize.run` masks the eager evaluation of `bracket.release(value)`.
16. `eval` drives Masked; `eval(preempt, period)` deleted; `evalPartial` added as
    `private[kyo]`.
17. `never` and `neverPreempt` deleted. `driveInstalled` and `drivePartial` forwarders
    deleted (already approved as C2); `install` and `handlePartial` call `evalLoop`
    with their mode.

### `kyo-kernel2/shared/src/main/scala/kyo/kernel2/ArrowEffect.scala`

18. `handlePartial` loses `preempt` and `period` and becomes `private[kyo]`; its
    scaladoc states the poll cadence, that the returned remainder is the resumption
    point, and that nesting one inside another slice is not supported.

### Tests

19. `SafepointTest`: the `onClaimedThread` helper and the Overflow test are deleted;
    new tests cover a detached thread (it evaluates eagerly, its depth guard fires at
    512, and a request from another thread reaches it), `restore` identity across a
    consume, and mask/unmask round-tripping including the nested case.
20. `PendingTest`: "preemption yields and the remainder resumes" is rewritten against
    `Safepoint.get.preempt()` plus `evalPartial`; "period controls poll cadence" is
    deleted with the parameter; a new test asserts `eval` masks (a request pending
    before an `eval` does not prevent it from returning a value, and the request is
    still pending afterwards).
21. `ArrowEffectTest`: "respects the preempt condition" and "handlePartial polls
    preemption across dispatches" are rewritten against `Safepoint.preempt`; a new
    test asserts an installed handle drive cascades (returns a pending computation
    without consuming) while the enclosing partial drive parks and consumes.
22. New: a tight `Loop` over immediate values with a request installed parks within
    `Safepoint.Period` iterations; a lone-transform Defer under a pending request does
    not spin (the livelock regression guard, which is the test the old Overflow would
    have failed).
23. New: a bracket whose release is running when a request lands completes the release
    in the same slice (the masking guarantee), and the residue in section 7.2 gets an
    ignored-or-pending test only if the bracket track picks it up; otherwise it stays a
    documented routed issue and not a silently passing test.

### Scheduler side (not this track's code, listed so the migration is scoped)

24. `IOTask`: stash the safepoint per slice, override `doPreempt` to deliver, arm and
    disarm the deadline, drop the `stop` argument.
25. `handlePartial` needs a two-effect form for `Abort` and `Async.Join` (section 8).
26. BlockingMonitor re-assert for interrupts.

## 11. What must be benchmarked

Before and after, on the existing JMH suite, with the analysis document's numbers as
the baseline:

| Row | What it guards | Expectation |
|---|---|---|
| `eagerMap5` | the per-frame path | no regression against 5.79 ns/op; a small improvement from dropping `Maybe[Thread]` |
| a dispatch-heavy row (`resumeFused`) | the new per-iteration poll replacing the closure call | flat or better |
| `deepBind10k` | segment splicing against `Safepoint.Period` | unchanged |
| a drive-entry-heavy row (many small `eval`s) | the exit poll and the mask bookkeeping in `eval` | flat |
| the Loop rows C17 asks for | the 512-iteration bounce cadence | unchanged, and now measured |

Separately, and gating ruling 5: `eagerMap5` with the slots array typed
`AtomicReferenceArray[Maybe[Safepoint]]` against the same array typed
`AtomicReferenceArray[Safepoint]`, everything else identical, adopted only if the
delta is inside noise.

One measurement that is not a JMH row but should exist as a test: preemption latency,
counted in transform executions between `preempt()` and the drive returning. The
design claims at most one segment; the test pins it.

## 12. Open questions that need the user

1. **The slice deadline in the kernel.** JS and Wasm have no thread that can request
   preemption of a running task, so either the Safepoint carries an armed deadline
   (this design) or JS loses time slicing for fibers that never suspend out of band.
   The design picks the deadline and confines it to a plain field plus one compare per
   poll. Confirm that adding a clock read to the kernel, on that path only, is
   acceptable, or name a different owner for JS time slicing.
2. **The mid-release residue** (section 7.2). An interrupt landing between two frames
   of an eager release can leave a partially-run release in a remainder that
   abandonment cannot finish. The fix belongs to the Bracket and `finalizeBracket`
   design (make `Finalize` re-arm across the spliced release the way `Catching` does).
   Should that land inside this track, since interruption is what makes it reachable,
   or as its own item?
3. **Visibility.** This design makes `evalPartial` and `handlePartial` `private[kyo]`
   to match the current kernel's surface. That overlaps C20, which is still awaiting a
   ruling. Confirm the direction here or defer both to C20.
