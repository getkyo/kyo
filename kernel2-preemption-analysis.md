# kernel2 preemption via Safepoint

## Implemented design: a sealed two-state machine per slot

Safepoint is a sealed hierarchy with two states, and a thread's slot in
the AtomicReferenceArray holds its current state. `Active` is the
claimed state doing the real work: single-writer plain depth accounting
in `enter` and `exit`, with no preemption code at all. `Parked` refuses
every frame; it carries `resume: Maybe[Active]`, where Present means "a
preemption request is pending, restore this on consume" and Absent is
the shared Overflow token (no slot could be claimed, permanently
parked). Ownership is checked by thread reference identity, never by
id, which the JVM may reuse after a thread dies.

A preemption request is the declared transition: the requester publishes
its condition (promise CAS, preempt state), then compare-and-swaps the
victim's slot from its Active to a Parked carrying it. The victim's
next `Safepoint.get`, a volatile read it performs on every frame anyway,
returns the parked state, so every subsequent frame refuses and bounces
onto the rescue trampoline; frames already entered keep exiting against
the Active they captured, unaffected. Consumption is the reverse
transition, performed by the boundary drive before consulting the
authoritative state; the exchange orders the requester's condition
writes before that check. `preempted` and `clearPreempt` live on the
companion and read the current thread's slot, so a stashed token cannot
be asked a question only the slot can answer.

Delivery cannot be lost: both transitions are CAS of the slot, which
the owner never writes outside claiming. Reclamation of a dead owner's
slot installs a fresh Active, so no stale request survives. One channel
serves time-slice preemption and interruption; the boundary drive
distinguishes them by reading the authoritative state. The
Task-precedent periodic authorities (coordinator tick, BlockingMonitor
scan) are still wanted, but only for retargeting a request whose fiber
migrated before the CAS landed, never for delivery.

An earlier iteration encoded refusal by pinning the wrapper's depth
counter at the limit inside a single final class. The sealed split
replaced it: refusal became a typed fact instead of a magic counter
value, the query surface moved to the companion, and the `enter`
callsite became bimorphic with overwhelming Active skew, which C2
devirtualizes speculatively; measured identical (5.79 vs 5.77 ns/op on
eagerMap5, all other rows unchanged).

### Why this shape: the measurement trail

Every candidate was benchmarked on eagerMap5 (5 eager maps + eval, the
purest hot path; baseline without any preemption 3.68 ns/op). The
decisive discovery: folding any second load into the depth read puts an
L1 load latency on the serial per-frame dependence chain, and it costs
the same whether the load is opaque, plain, same cache line, separate
array, or an object field, roughly 1.4 ns per frame:

| variant | eagerMap5 ns/op |
|---|---|
| baseline, no preemption (plain long array) | 3.68 |
| Safepoint class, no preemption (control) | 4.70 |
| implemented: sealed state machine, poll rides get's volatile load | 5.79 |
| same mechanism, single class with depth pinned at the limit | 5.77 |
| pending field checked as separate predicted branch | 7.4 |
| any variant folding a flag load into the depth read | 10.6 |
| ThreadLocal instead of the slot array, before any poll | 6.03 |

The wrapper swap wins because the poll is a load the hot path already
performs (`get`'s volatile slot read), so preemption adds zero
instructions to `enter`; the remaining 1.1 ns over the class control is
the Maybe sentinel checks and run variance, and the class control's 1 ns
over the raw-array baseline is the instance indirection. ThreadLocal
(the current kernel's carrier) was measured on request and rejected: it
costs more than the slot array before any preemption support at all.
The JIT-level explanation for the fold's cost: with a single memory
word, C2 collapses the whole enter/exit accounting of an inlined chain
into registers; a second dependent load makes it real memory traffic on
the critical path.

The sections below record the exploration that led here (the poison bit
in the depth word, the padded preempt word, their concurrency analyses)
and the still-valid protocol pieces that carry over: the two-level
authoritative-state anchoring, the cascade through non-boundary drives,
the consume-then-check ordering, the task-to-slot targeting, and the
Task re-assert precedent.

## The two mechanisms today

Current kernel: every map calls `safepoint.enter`, which checks a packed
Long (depth, threadId, hasInterceptor) and consults the installed
interceptor (IOTask: `!shouldPreempt()`). Refusal wraps the remainder in
`Effect.defer`, which cascades out through every handler loop as an
ordinary suspension. Granularity: any composition step.

kernel2: `guardedRun` checks only `Safepoint.increase(slot) >= Limit`
(stack safety). Preemption is consulted only at drive boundaries
(`eval(preempt, period)`, `handlePartial`, per stride and per Defer pop in
`driveLoop`). Granularity: dispatch step. Long eager nests (up to Limit
frames) and construction-time eager runs are unpreemptable windows.

## The poison design

### Encoding

`PoisonBit = 1L << 62`, ORed into the victim's cell. The bit preserves the
true depth in the low bits, which is what makes recovery trivial.
Poisoning by overwriting the cell with a value (say, `Limit`) is rejected:
it destroys the depth that in-flight `decrease` calls still need.

### Hot path: unchanged

`increase` loads the cell; a poisoned value is `>= Limit`, so `guardedRun`
takes the existing `rescue` branch: wrap the frame in `Kyo.Defer`. Note
that `increase` does not write when the value is at or past `Limit`, so a
poisoned cell is never stomped by `increase`, only read. `decrease` on a
poisoned cell decrements the low bits and preserves the bit, which is
exactly right: unwinding frames keep accurate depth accounting under the
poison.

`rescue` also stays unchanged: it does not need to distinguish poison from
genuine overflow. Both produce a one-frame Defer bounce. The
interpretation happens at the drive.

### Drive: observe, cascade, consume

Every `driveLoop` processes Defers and suspensions one step at a time.
Add one cheap read of the current thread's poison bit at those points:

- Non-boundary drives (`driveInstalled`, the eager handle drives, which
  run with `preempt = never` by design): if the bit is set, return the
  remainder suspended instead of continuing. They cannot decide the
  preemption (they have no authoritative signal), so they cascade the park
  outward, exactly like the current kernel's handler loops cascade an
  interceptor refusal. They never clear the bit.
- Boundary drives (`eval(preempt, period)`, `handlePartial`): on
  observing the bit, consult the authoritative `preempt()` (and interrupt
  state), clear the bit, and either park (return the remainder) or
  continue (the poison was stale). The authoritative check consumes the
  poison in both outcomes; that self-heals stale poisons.

So the poison's entire job is: force the nearest guardedRun onto the
trampoline, and force every drive between there and the boundary to hand
control outward, as fast as one frame per step. The boundary makes the
actual call. Preemption becomes "an early trampoline plus a cascade",
reusing the suspension channel end to end, the same shape as the current
kernel's `Effect.defer(mapLoop(value))`.

### Preempter side

The victim of a poison is a thread, not a fiber. The scheduler coordinator
already flags long-running workers, and a worker owns its thread, so the
thread id is known directly. Resolution tid to slot uses the existing
`owners` probe. New API sketch: `Safepoint.poison(threadId)`, CAS-setting
the bit through an array VarHandle. Rules:

- If the probe does not find the tid (thread unclaimed or on the shared
  overflow slot), skip: the overflow slot is pinned at `Limit` and already
  trampolines every frame, maximally preemptable by construction.
- Never write a `Transferring` slot; the CAS against the observed value
  fails naturally if a claim races.

Interrupts ride the same path: `IOPromise.interrupt` CAS-completes the
promise (authoritative, unchanged), then best-effort poisons the thread
currently running the task to cut the latency of the fiber noticing. The
poison is thread-addressed while today's preempt flag is task-addressed,
so the interrupt path needs a task-to-slot mapping: IOTask stashes its
slot index in a field at slice entry (`run`) and clears it at slice exit,
the mirror image of `Worker.currentTask`; `onComplete` reads it and
poisons. A stale or missed read is benign as always.

### Retriggering a stomped poison: the Task precedent

The owner's plain RMW can erase a just-set poison (the stomp race). The
scheduler's own `Task.State` shows how kyo already manages exactly this
class of signal: a bit-packed field mutated by non-atomic RMWs from three
parties (worker `addRuntime`, coordinator `doPreempt`, interrupter
`resetRuntime`), where lost writes are accepted by classification and the
signals that matter are re-asserted from authoritative sources rather
than protected by atomics. Task's own preempt bit is even cleared by the
owner as a matter of course (`addRuntime` drops bit 31), and the design
does not care, because both signal sources are periodic authorities, not
one-shot events:

- Time-slice preemption: the coordinator's periodic pass calls
  `Worker.checkStalling`, which calls `task.doPreempt()` on every tick
  while the task remains past its slice. Re-poisoning the worker's cell
  at that same site makes the poison level-triggered: a stomped bit is
  re-CASed one tick later.
- Interrupts: not one-shot either. The BlockingMonitor scans on a ~2ms
  cadence (immediate first scan via `wake()` on interrupt) and
  re-dispatches `Thread.interrupt()` for any running task whose
  `needsInterrupt()` holds, until the task observes the interrupt.
  Re-poisoning belongs in that same scan: while a running task needs
  interrupting, re-CAS its worker's poison each pass. `onComplete`
  provides the immediate first poison for latency; the monitor provides
  the guaranteed retrigger.

So every poison source has a standing re-assert authority in the existing
architecture, and the never-lossy anchor stays the promise's CAS state
(Task's comment: the preempt bit is redundant once interrupted, because
eval stops on the completed promise). The poison bit adds a faster
observation point, not a new correctness dependency, which is exactly the
role Task already assigns to its own lossy bits.

## The concurrency analysis

The cell goes from single-writer to multi-writer, and the design survives
because of one invariant: **owner operations recompute from their own
load, so a raced poison is dropped cleanly, never blended into the
depth.** Every race:

1. Poison CAS lands between the owner's `increase` load and store: the
   owner stores `depth + 1` computed from its pre-poison load. The poison
   is lost; the depth is exactly correct. No corruption, only a missed
   accelerator.
2. Same with `decrease`: owner stores `depth - 1` from its own load.
   Poison lost, depth correct.
3. Poison CAS while the cell already reads `>= Limit` (genuine deep
   nesting): `increase` is not writing in that regime, so the CAS
   succeeds and sticks; `decrease` stomps are the only loss window.
4. Owner-side clear (boundary drive) vs preempter re-poison: both are
   CAS, serialized by the hardware; no lost state, worst case one extra
   bounce.
5. Slot reclamation: `claim` resets the cell to 0, wiping any stale
   poison before a new thread uses the slot.
6. Wrong victim (the fiber migrated between the preempt decision and the
   poison write): some other fiber takes one spurious trampoline bounce,
   its boundary drive sees `preempt()` false, clears, continues. Benign
   by construction; this is why no precise fiber-to-thread tracking is
   needed.
7. Torn reads: aligned 64-bit accesses through a VarHandle in opaque mode
   are access-atomic; no tearing.
8. Visibility: plain loads could in principle be hoisted by the JIT out
   of a hot inlined chain and never observe the poison. Use opaque mode
   for the `increase` load (compiles to a normal load on x86 and ARM,
   but guarantees eventual visibility and blocks unbounded hoisting).
   Opaque's "eventually visible" is sufficient precisely because the
   poison is not the correctness carrier.

The losable-poison consequence is handled at the protocol level, the same
two-level split the current kernel has:

- Level 1 (authoritative, correct): the fiber and promise state
  (`shouldPreempt`, interrupt CAS), volatile, checked at every boundary
  drive stride. A lost poison delays preemption to the next stride check,
  never loses it.
- Level 2 (accelerator, best effort): the poison bit. The preempter may
  re-arm on its next tick (the coordinator ticks anyway), so even the
  stomp window is transient.

## What this replaces from the current kernel, and what it does not

- `interceptor.enter`'s preemption role: fully replaced, at zero hot-path
  cost instead of a per-map check. The production interceptor ignores
  `enter`'s `(frame, value)` arguments anyway (IOTask: `!shouldPreempt()`).
- The threadId check in `enter`: not needed in kernel2. The current
  kernel guards against a captured `given Safepoint` being entered from a
  foreign thread; kernel2 resolves `Safepoint.slot()` fresh from the
  current thread at every use, so it is thread-correct by construction.
- The finalizer registry (`addFinalizer`/`removeFinalizer`, `ensure`) and
  interceptor masking during cleanup: not carried by the bit. This is a
  separate design item for the preemption round: kernel2's bracket
  cleanup runs inside the drive as ordinary computation and can park
  correctly mid-cleanup, but the slice-level finalizer registry that
  IOTask keeps needs its own carrier.

## The preempt word (recommended)

The final shape, superseding the poison bit: keep the depth word strictly
single-writer and carry the signal in the adjacent long of the same cache
line, as a 0-or-1 flag the owner never writes. Two words per slot, not
one bit-packed word: packing depth and flag into one long is exactly the
poison variant, and the owner's depth RMW rewriting the whole word is
what created the stomp. The second word exists so the owner never writes
the signal's location.

- Placement: `cells(slot)` is depth, `cells(slot + 1)` is the preempt
  flag, in the padding of the existing cache line (each slot occupies 8
  longs with only index 0 used today). No new array: a separate
  AtomicLongArray would cost a second cache line on the poll and
  false-share neighboring slots' flags (8 per line), while the padding
  word is exclusive to the thread and rides the line `guardedRun` already
  loads.
- Requesters (coordinator tick, any interrupter): publish the condition
  first (the promise CAS, the preempt state), then `setVolatile(1)` on
  the flag through the array VarHandle. A plain volatile store suffices:
  the only writer of 0 is the consumer's exchange, so a 1 cannot be
  stomped, and concurrent requesters setting an idempotent 1 compose
  because the meaning of the request lives in the authoritative state,
  not in the word. (A request count was considered and rejected: the
  consumer takes the word wholesale either way, so a count of 2 and a
  flag of 1 are consumed identically; the count bought only telemetry
  plus a theoretical overflow question, and cost an RMW where a store
  suffices.)
- Poll: `guardedRun` adds one load and compare on the already-resident
  line: `if depth >= Limit || preempt != 0 then rescue`. The flag load is
  opaque mode (eventual visibility and access atomicity at plain-load
  cost; ordering is carried by the authoritative state, so acquire buys
  nothing here).
- Consume: only the boundary drive, and in this order: `getAndSet(0)`
  FIRST, then read the authoritative preempt and interrupt state, then
  park or continue. Consume-then-check is what makes delivery airtight:
  a requester stores its condition before its 1, so if the exchange
  swallowed that 1, the volatile-RMW read establishes happens-before
  with the requester and the condition is guaranteed visible to the
  check that follows; a 1 stored after the exchange stays pending and
  forces another bounce. This is also why consume is an exchange and not
  a plain store of 0: a blind 0-store racing a 1-store could erase a
  request whose condition the subsequent check does not yet see.
- Non-boundary drives treat `preempt != 0` exactly as the poison bit:
  return the remainder suspended, cascading the park outward; never
  consume.

The concurrency argument collapses to two sentences. The depth word is
single-writer plain, untouched by this design. The preempt word is only
ever written by volatile stores of the flag value (requesters) and one
volatile exchange to 0 (consumer), and read by the opaque poll, so no
interleaving loses a request or corrupts a value, and consume-then-check
ordering guarantees every consumed request's condition is visible to the
decision that follows.

### Encapsulation: the whole mechanism lives in Safepoint

Every access to both words stays inside Safepoint; no other file touches
the array or the VarHandle. The key enabler: the flag word stores `Limit`
rather than 1, and `increase` folds it into the depth it returns, so a
pending request reads as depth-at-limit and the caller's existing compare
is the poll. `Arrow.guardedRun` does not change at all.

    private[kyo] object Safepoint:
        inline def Limit = 512
        private inline def Flag = Limit   // flag word holds 0 or Limit

        // hot: previous depth with the preempt flag folded in; a pending
        // request reads as >= Limit, and the fold also suppresses the
        // depth store, so the rescue path stays write-free
        @static def increase(slot: Int): Long =
            val depth = cells(slot) | getOpaque(slot + 1)
            if depth < Limit then cells(slot) = depth + 1
            depth

        @static def decrease(slot: Int): Unit   // unchanged
        @static def slot(): Int                 // unchanged

        // requester: publish the condition first, then signal; no-op on
        // the shared overflow slot (it already rescues every frame)
        @static def preempt(slot: Int): Unit =
            if slot != OverflowSlot then setVolatile(slot + 1, Flag)

        // cascade check for non-boundary drives
        @static def preempted(slot: Int): Boolean =
            getOpaque(slot + 1) != 0L

        // boundary drives, consume-then-check; RMW only when set
        @static def clearPreempt(slot: Int): Boolean =
            preempted(slot) && getAndSet(slot + 1, 0L) != 0L

Call sites: `Arrow.guardedRun` unchanged; non-boundary drives call
`preempted` at their Defer-processing points and return the remainder;
boundary drives call `clearPreempt` at the stride check before consulting
the authoritative state; IOTask stashes `Safepoint.slot()` at slice entry
(and clears the stash at exit, with a defensive `clearPreempt` at slice
start); `onComplete`, the BlockingMonitor scan, and `Worker.checkStalling`
call `preempt(stashedSlot)`. The versus-two-compares accounting: folding
into `increase` costs one load and one OR on the resident line with no
added branch, strictly cheaper than a separate `preempt != 0` check, and
it keeps the poll invisible to the kernel's hot path. Safepoint's scaladoc
already names this as the intended trajectory (the successor of the
current kernel's Safepoint, carrying the runtime guard).

What this costs relative to the poison bit: the poll is no longer
literally free, it is one extra load, compare, and predicted branch per
eager transform, against the cache line the depth access just touched.
The JMH suite (eagerMap5, resumeFused, deepBind10k are the sensitive
rows) measures the real delta when implemented. What it buys: delivery
becomes guaranteed instead of probabilistic, the re-assert authorities
are needed only for retargeting (a request aimed at a thread the fiber
already left) rather than for delivery, and the safety argument requires
no reasoning about stomp windows at all.

Everything else in this document carries over unchanged: the cascade
semantics through non-boundary drives, the task-to-slot mapping for
interrupt targeting, the Task-precedent periodic re-assertion (now only
for stale targeting), the stride backstop, and the promise CAS as the
correctness anchor. One channel serves preemption and interruption
alike; the boundary drive decides which it was by reading the
authoritative state, never by decoding the word.

## Alternatives considered

- Plain flag in the padding cell (set 1, clear 0, no atomics): removes
  the owner stomp (the owner never writes the flag location) but leaves a
  set-versus-clear race between a requester and the consumer that can
  erase an unobserved request. Superseded by the preempt word, which
  keeps the same placement and poll cost and closes that last race with
  the volatile exchange on consume and consume-then-check ordering.
- Porting the interceptor as a per-thread reference consulted in
  `guardedRun`: a reference load on a separate cache line plus a
  megamorphic call per map. Rejected for the hot path; it also reintroduces
  the richer surface (enter with frame and value) that nothing uses.

## Access modes: the array stays plain

The backing storage remains a plain `Array[Long]`, not an
`AtomicLongArray`, and the owner hot path keeps zero fences and zero
atomics. Precisely:

- `increase`'s store and `decrease` stay plain array writes.
- `increase`'s load moves from plain to opaque mode through the
  VarHandle. Opaque is not volatile: it emits the same machine code as a
  plain load on x86 and ARM64 (no fence), and only constrains the
  compiler. That constraint is the point: under plain mode the JIT may
  legally promote the cell to a register across a hot fully-inlined loop
  (the owner is the only writer it can see) and never re-read memory, so
  a poison could go unobserved indefinitely. Opaque forbids that
  unbounded elimination and guarantees eventual visibility and access
  atomicity (no 64-bit tearing), at zero runtime cost.
- The poison set and the boundary-drive clear are CAS through
  `MethodHandles.arrayElementVarHandle(classOf[Array[Long]])` on the same
  plain array. Both are cold paths (coordinator tick, stride check).

So the memory layout is unchanged and the hot-path codegen is unchanged;
atomics exist only on the preempter and consume paths. If even the opaque
annotation on the depth load is unwanted, the padding-cell alternative
(above) keeps the depth cell 100 percent plain single-writer and moves
the opaque load to the flag cell on the same cache line, at the cost of a
second load and compare.

## Platform notes

- JVM: `MethodHandles.arrayElementVarHandle(classOf[Array[Long]])` hosted
  on the @static companion; owner ops plain or opaque, preempter and
  clear via CAS.
- JS: single-threaded, no preempter thread exists; the poison is never
  set and the code degrades to today's behavior. No split needed.
- Native: multithreaded; if VarHandle opaque or CAS support is
  incomplete, fall back to an AtomicLongArray for the cells on Native
  (volatile-cost owner ops there, acceptable) via a platform source
  split. To verify at implementation time.

## Open questions for the implementation round

1. Non-boundary drives observing the bit: per Defer pop only, or also per
   suspension dispatch? Per-pop is where the bounce manifests, likely
   sufficient.
2. Resolved above: re-arm rides the existing periodic authorities
   (coordinator tick via `checkStalling` for time slices, BlockingMonitor
   scan for interrupts), the Task re-assert pattern.
3. `handlePartial`'s last-resort clause runs host code (IOTask body);
   audit that a poison landing mid-clause only defers values the clause
   already treats as suspended remainders.
4. Whether the worker clears the bit defensively at slice start, in
   addition to the boundary-drive consume, to protect against a poison
   that landed after a park.
5. The finalizer-registry carrier (see above), designed together with
   this so the slice protocol lands in one piece.
