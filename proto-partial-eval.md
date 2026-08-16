# Reintroducing partial evaluation in the proto kernel

What the proto needs so `IOTask` can drive it in slices. Grounded in three sources: the entry protocol
that survives live in `kyo/kernel/internal/Eval.scala:12-28`, the full drive shape preserved in that
file's commented-out body, and the consumer contract in `iotask-kernel2-integration.md` (section 5).
This is analysis; nothing here is implemented.

## The contract partial evaluation must satisfy

From the IOTask design (doc 5.4): `Eval.partial` is the driver, `ArrowEffect.handlePartial` is the
resumer. Per slice, IOTask calls `Eval.partial(curr)`; the result is either settled (`evalNow` answers),
a park residual (a bare `Join` suspension the `handlePartial` clause answers or records), or a stop
residual (re-driven next slice). Preemption is delivered by `Safepoint.stop(thread)` from the scheduler;
the drive observes it and yields. The park protocol is not a mechanism: it falls out of the
effectful-clause semantics, which the proto landed this session (the `outcome` dispatcher).

## What the proto already has

| piece | where | status |
|---|---|---|
| preemption delivery and slot protocol | `Safepoint`: `arm`, `stop`, `consumeStopped`, `save`, `restore`, `reset` | ported, currently unconsumed; this is their consumer |
| the park protocol | the `outcome` dispatcher: a clause re-raising its own effect has its region taken apart and rebuilt around the resumption | landed and pinned (same-tag pipeline test) |
| region reification | the crossing machinery: `copyEntries/copyTags/copyStates` + `Arrow.Eval` restores markers and state on push | landed |
| fused-suspension continuations | `Suspend.chain` extends a suspension's own continuation, keeping it one node | landed |
| resumability of everything handed out | the complete-value rule; multi-shot pinned | landed |

## What is missing

1. **`Eval.partial[A](v: A < Any): A < Any`.** Entry parity with kernel2's live code: if
   `consumeStopped(slot)` already, return `v` untouched; else `save`, `arm`, drive, `restore` in
   finally. `Eval.apply` should gain the same `save`/`restore` bracket (kernel2's has it; the proto's
   does not), so a nested drive cannot consume an outer slice's budget state.

2. **A partial drive loop.** Two behavioral deltas against the full loop, at two points:
   - At node-step arms: poll `consumeStopped`; on stop, reify and yield. Kernel2 polled at the Defer
     arm and called `Safepoint.reset` per node on the non-stop path.
   - At an unhandled suspension: reify and yield instead of `bug`.
   Shape decision, forced by the E9 evidence (loop-body bytes move the fused row): a **separate loop
   copy** for the partial drive, the `*With` precedent, not a `partial: Boolean` branch inside the hot
   loop. The full loop keeps its bytes; the partial copy carries the polls.

3. **Two residual constructors**, both from existing machinery:
   - **Park residual** (unhandled suspension): fold the frames above `base` into the suspension via
     `Suspend.chain`. At the miss, those frames are the dispatcher and the clause's own transforms, all
     unmarked, because the region was truncated when its clause suspended; the fold therefore yields a
     **bare suspension**, which is the property the whole park design rests on (doc 5.1). If a marked
     frame is present the fold is not available and the residual wraps in `Arrow.Eval` instead.
   - **Stop residual** (preemption): `Arrow.Eval(copyEntries(base), copyTags(base), copyStates(base),
     cur)` and truncate to base. One node; markers and handler state restore on the next slice's push.
     This is where the proto is simpler than kernel2, whose commented `rebuild`/`renode` ladder
     re-nodes frame by frame: the proto's segment node already is the reification.

4. **`ArrowEffect.handlePartial(tag, v)(clause)`.** Head-only, no region installed, no walk:
   match `v` against a `Suspend` of `tag`; answer with `clause(s.input, s)` (the suspension is its own
   continuation, multi-shot by the complete-value rule), `Maybe.Absent` leaves it parked; anything
   else, including a stop residual whose head is an `Arrow.Eval`, returns unchanged. The bare-suspend
   property of the park residual is what keeps this head-only.

5. **Tests** (all cross-platform, shared): the park residual is a bare suspension of the boundary tag;
   a stop residual resumes across standing regions including `HandleLoopState` state; slicing is
   semantics-free (`partial` iterated to completion equals `apply`, driven over the acceptance corpus);
   `handlePartial` answers only the head and leaves stop residuals untouched; an armed slot yields at
   the next poll; interplay with the construction-time budget parks in `Pending`.

6. **Bench gates**: fused and suspension rows byte-identical by construction (separate loop); one new
   row for a sliced drive (park-resume per element is already covered by the emitting row).

## Explicitly out of scope here

- **Context / ContextEffect**: needed for the full IOTask integration (locals, fiber identity, doc
  5.6), not for partial evaluation itself. Separate deferred item.
- **Scheduler and kyo-core wiring** (`preemptOn` hook, boundary layers in `IOTask.apply`, the run
  loop): consumes this work, lives in kyo-core, follows the doc's section 5.
- **JS/Wasm deadline slicing** (doc Q1) and the finalizer ruling (doc Q3).

## Open questions

1. A genuinely unhandled suspension (no boundary anywhere) in partial mode: kernel2's shape reified it,
   which makes a driver that never answers it spin. Options: keep parity (reify; the boundary layers
   make it unreachable in practice), or keep `bug` for tags the residual protocol does not know. Parity
   is the default; needs a ruling only if the spin case is considered reachable.
2. Poll placement in the partial loop: kernel2 polled per Defer node. The proto's equivalent arms are
   Chain/Bind/Eval; per-arm polling is the latency bound of doc 3.3. Default: match kernel2 (poll at
   the deferred-node arms, reset on the non-stop path).
