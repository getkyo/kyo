# Proposal: reliable bracketing for the proto kernel

The design for `Sync.acquireReleaseWith` in the proto kernel: what is landed, the one red
pin, the mechanism proposed to close it, and the invariants and rejected designs that a
reviewer must know were already litigated. Code references are to this working tree.

## The problem

A bracket acquires a resource, uses it, and must release exactly once on every way the
extent can end: completion, a failure unwinding past it, abandonment of a parked remainder,
and a captured continuation that a handler clause discards. The kernel's interruption model
frames everything: nothing interrupts inside a slice; the eval parks at safepoints
(currently only the Defer arm's dispatch check), and cancellation is a holder declining to
resume a parked remainder, reaching owed releases through `Eval.release`. There is no
asynchronous interrupt, which is why no masking primitive exists or is proposed: the
systems that mask acquire (cats-effect, ZIO, GHC) do so because their interrupts can land
on any instruction (see `bracket-survey.md`).

## What is landed

`Sync` (`kyo-kernel/shared/src/main/scala/kyo/proto/Sync.scala`) is a pure row marker plus
`defer`. A bracket opens a context region of a hidden `Sync.Finalize` effect at the instant
the acquire settles. The region's state is a `Cell`: an `AtomicBoolean` claim around the
release thunk. The resource lives in the thunk's closure, never in a field. The release
outcome is `Maybe[Throwable]`: `Absent` means the extent completed.

The edges ride the context release protocol already pinned in the kernel
(`ContextHandler.release`, invoked by the failure unwind in `recovered` and by the
abandonment walk in `Eval.release`):

- completion: the exit map inside the region claims and releases with `Absent`;
- failure: `recovered` pops the region and its release hook drains with the throwable;
- abandonment: `Eval.release` on a parked remainder reaches the region through the Park
  entries and drains;
- fork: the region's `fork` hook installs `Cell.inert` (pre-claimed), so a forked child's
  death cannot drain the parent's obligation; `join` keeps the parent.

Ten pins are green in `SyncTest.scala`: completion order and payload, exactly-once, failure
payload, abandonment, park-resume completing with `Absent`, LIFO nesting on unwind,
unguarded acquire before settle, in-clause resume completing the bracket, and both `ensure`
edges. One pin is red, and it is the subject of this proposal:

    "a discarded captured continuation still releases the bracket"

A suspension inside `use`, answered by a handler outside the bracket, makes
`stack.dump(idx + 1)` (`Eval.scala`, the crossing's `continuation`) pack the Finalize
region into the crossing Park inside the captured continuation. The clause drops the
continuation. The cell is garbage: never claimed, never drained. `Eval.release` would find
it, but nobody calls release on a silently discarded value.

## The invariants

These were converged on over many rounds; a review that re-opens one should argue against
its recorded rationale, not restate the alternative.

1. **Acquire is interruptible.** Every park inside acquire precedes the next step's thunk,
   so an abandonment mid-acquire owes nothing the bracket promised; a multi-step
   acquisition composes brackets internally. First-class masked acquire is explicitly not
   proposed (ZIO's variant pays by denying the release the resource; nothing interrupts
   inside our slices anyway).

2. **The settle-to-install edge is not interruptible.** The invariant: a park may never
   strand a settled value whose next applicable arrow is an install. Today the dangerous
   state is unreachable, because parks exist only at Defer dispatch and settled delivery is
   strict; the proposal makes the invariant explicit so no future safepoint placement can
   violate it silently.

3. **The kernel guarantees at-least-once reachability; exactly-once lives in the state.**
   A release hook may be invoked on more than one path (live stack and kept pointer, see
   below); the `Cell`'s CAS collapses this to exactly-once. Plain bindings are safe under
   at-least-once because their hook is the no-op. This is the division that dissolved every
   registry-shaped design: the kernel carries pointers, not uniqueness.

4. **Obligations live in the stack and the value graph, never in side stores.** A region
   entry is per-extent state the eval reaches on every edge it owns; Parks carry entries to
   holders; `Eval.release` walks values. The eval's only addition is pointers to snapshots
   it already produced (below), scoped to the eval, drained once.

5. **The parent's lifetime bounds obligations it created.** A stored continuation resumed
   after the creating eval drained finds the cell claimed and the release a no-op; entering
   a spent extent is documented, not modeled with a dedicated type.

6. **Late-registration outcome payloads are plain.** `Maybe[Throwable]`; no `Abandoned` or
   `Spent` case types; abandonment arrives as the holder's or the drain's throwable.

## The proposed mechanism: dump-and-keep-pointer

The fix for the red pin, in the framing that finally made it minimal: **share, don't
move.** `stack.dump` continues to pack and surrender the entries exactly as today, so
crossing semantics for bindings are untouched (crossed bindings still re-install and
resume at their captured values, as pinned). Additionally, the dump hands the produced
snapshot to the eval, which keeps the pointer:

- **Keep**: each eval keeps the snapshots its own dumps produce (a handful; eval-scoped;
  nested evals keep and drain their own).
- **Drain**: the eval's existing `try guarded(...) finally` walks kept snapshots and
  invokes each entry's release hook with the in-flight failure or a plain discard
  throwable. This single site covers both missing edges: the unwinding throw passes
  through the finally, and a dropped capture is simply an unclaimed cell still reachable
  through the kept pointer when the eval ends. Escaped extents that completed elsewhere
  drain as claimed no-ops. Suppression follows the landed policy: a throwing hook
  suppresses onto the signal, guarded against self-suppression.
- **Transfer**: a safepoint park moves the kept pointers into the Park alongside the
  snapshot of the stack; the resuming eval re-keeps them; `Eval.release` on an abandoned
  remainder drains them through the holder. Ownership follows the remainder.
- **Re-dumps chain**: entries of a resumed park dumped again produce a new snapshot kept by
  whichever eval dumped.

No flag discriminates regions (rejected: `owing`); no entries are retained or moved
(rejected: retention with sentinel continuations); no per-finalizer list exists (rejected:
outstanding buffers). The kept pointer is to a snapshot the dump already built.

## The settle-to-install enforcement

- `Arrow.Install` (name open): an empty, immutable marker subclass of the transform arrow.
  `acquireReleaseWith`'s install lambda (settled resource in, region-opening `Handle` node
  out) extends it. Immutability makes it a complete value: a replayed acquire tail mints a
  fresh cell and region per shot.
- Enforcement at both park sites: the Defer arm's guard declines to park a node whose
  `contA` is an install, and the `park()` packer applies any leading install on a settled
  value before packing.
- The pin: `requestStop()` inside acquire's final thunk (stop pending the instant the
  resource exists) parks the remainder with the region already installed, and
  `Eval.release` on that remainder fires the release. Together with the landed
  "acquire is not guarded before it settles" pin, both sides of the boundary are pinned.

## Rejected designs, with the reasons of record

- **A `Kyo.Bracket` node or hidden bracket effect answered by the eval**: unnecessary; the
  region machinery plus kept pointers covers every edge, and node kinds are reifications of
  combinators, not evaluator patches.
- **A `GuardHandler`/finalizer handler family**: superseded by using the context region
  protocol directly; the hidden `Finalize` ContextEffect is the same idea with no new
  family.
- **Row-restricted release ("resources handled last", enforced by `v: B < E`)**: sound but
  rejected because `use` must keep its open row; the kept-pointer mechanism achieves the
  guarantee without the restriction.
- **Retention-by-moving with an `owing` discriminator**: moving entries out of dumps
  requires distinguishing brackets from bindings and changes crossing semantics for
  whatever is retained; sharing needs no discrimination.
- **An eval-held finalizer list / outstanding registry**: registry-shaped state was
  rejected repeatedly; the kept pointer differs by construction: it points at snapshots the
  dump already produced, it is eval-scoped, and uniqueness stays in the cell.
- **Finalizer holding the resource, or fused step/finalizer objects**: continuation entries
  are complete values and must be immutable; per-run mutable state (the claim) must be a
  separate object minted per application. Resource capture belongs in the release thunk's
  closure.
- **`Abandoned`/`Spent` case types**: not introduced; their semantics survive as plain
  throwables and the claimed no-op.
- **`Maybe[Error[Nothing]]` payloads**: collapsed to `Maybe[Throwable]`, which is all it
  could carry.

## Test plan beyond the red pin

- the settle pin above;
- scoping: nested evals drain their own kept snapshots; a re-dumped resumed park chains;
  in-place clause resume completes the cell and the finally no-ops (no double release
  observable through the cell);
- Scope, later, is a plain registry binding whose `run` drains through one
  `acquireReleaseWith`, with hierarchical child queues installed by its `fork`/`join`
  functions; it needs nothing further from the kernel.

## Open questions for review

1. Placement details of the kept pointers (field beside the borrowed stack vs on the Stack
   object itself) and their Park carriage (a Park field vs an entry-shaped encoding).
2. Drain order among multiple kept snapshots and within one (current intent: LIFO both).
3. The discard throwable's identity and message; whether the finally reuses the in-flight
   failure when unwinding.
4. Whether `Arrow.Install` should live on `Arrow` or in the kernel internals.
5. Interaction with the contextual isolate: `contextual()` collects `Finalize` regions into
   forked snapshots; the inert-cell fork hook neutralizes child drains; is any case missed
   (restore-merge paths, replayed isolates)?
6. Bench exposure: the Defer arm gains one `isInstanceOf` on the armed path only; dump
   gains a pointer append; the finally gains a usually-empty loop. Rows to gate:
   suspension and handleLoop families, emitting (crossing path).
