# kernel2 preemption via the depth cell

Question: can kernel2 reuse the Safepoint depth counter as the preemption
poll, the way the JVM folds safepoint polls into an existing memory
access? Answer: yes. A preempter sets a high bit in the victim thread's
depth cell; the existing `depth >= Limit` compare in `guardedRun` becomes
the poll, so the eager path pays zero additional cost. The concurrency
works out because the design never asks the cell to be reliable: the cell
is a latency accelerator, and correctness stays with the authoritative
preempt state the drive already checks.

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

## Alternatives considered

- Flag in the padding cell (`cells(slot + 1)`, same cache line, currently
  unused padding): restores strict single-writer-per-cell (preempter
  writes the flag, owner only reads and clears it), removing races 1-3
  entirely. Costs one extra load and compare in `guardedRun`. The load
  hits the already-loaded cache line, so it is nearly free, but it is not
  zero, and the poison-bit races were shown benign. Keep as the fallback
  if the bit packing complicates something unforeseen.
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
