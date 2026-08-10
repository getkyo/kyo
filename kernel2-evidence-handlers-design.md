# kernel2 design: evidence handlers

Status: proposal, awaiting review. Nothing here is implemented. The one
prerequisite already landed as a separate bug fix: wrap-style rotation
(commit 727e8e6742, five reproducing pins written and confirmed red first),
because the current append-style rotation lost handlers behind trailing
transforms and because every re-entry guarantee below depends on the wrap
encoding. Backing analysis with the full argument for each choice:
kernel2-evidence-handlers-analysis.md. Prior art inherited from the old
kernel's implemented-then-deleted evidence round:
kernel2-rotation-handlers-design.md (E1/E2b measurements, clause scope rule,
the 11 reference programs), kernel2-handler-encodings.md (registration and
shadowing discipline).

## 1. What changes

resume and stop handlers act at the suspension site when their handler is
visible, without building the continuation. A resume-handled operation
becomes a local call answered inside the per-site mapLoop; a stop-handled
operation discards the pending work by construction and carries its input to
the boundary. A missing handler produces a regular suspension handled by the
trampolines exactly as today. handle and loop keep their trampolines and
their built continuations; they participate only so shadowing stays correct.

Motivation, measured: the shared trampoline is the kernel's one megamorphic
surface (sharedHandlerPaysDispatch: 2.79x at 16 sites, identical allocation;
kernel2-jit-morphism-report.md), and the old kernel's evidence round measured
in-place resume at ~15x time and ~100x allocation against park-and-dispatch
(E1/E2b).

## 2. The mechanism

Composition, not machinery: evidence is an argument of arrow application, the
same way a value is. No implicits anywhere, by explicit constraint: the
collection rides only as a plain parameter of the internal execution
signatures; user code never sees it.

### 2.1 The collection

`Handlers` is a collection of handlers, nothing else: one final array-backed
class plus an empty singleton. Innermost-last linear scan, reference-first
tag comparison with the structural `<:<` fallback off the fast path (the
relation must match the trampolines' subtype guard). Copy-on-add at region
entry. The elements are the handlers themselves, typed:

    sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O]](val tag: Tag[E])

    object Handler:
        abstract class Resume[I[_], O[_], E <: ArrowEffect[I, O], S](
            tag: Tag[E],
            val scope: Handlers
        ) extends Handler[I, O, E](tag):
            def apply[X](input: I[X]): O[X] < S

        final class Stop[I[_], O[_], E <: ArrowEffect[I, O]](tag: Tag[E])
            extends Handler[I, O, E](tag)

        final class Shadow[I[_], O[_], E <: ArrowEffect[I, O]](tag: Tag[E])
            extends Handler[I, O, E](tag)

- Resume stores its clause at its public types as the typed `apply`;
  `ArrowEffect.resume` mints one per region entry and per rotation re-entry
  (the captured scope differs each time). The clause is invoked at its own
  types. The one erased boundary is the tag-keyed recovery at the dispatch
  site, justified by the tag match, the same documented cast the
  trampolines' matched arms carry today.
- Stop carries no clause: the clause runs at the boundary inside the stop
  trampoline, which already holds it at its types. The Stop value is the
  region's identity token (Halt ownership compares it by eq) plus the tag
  for the scan. One per stop call, reused by its re-entries.
- Shadow is the tag-only barrier for handle and loop regions. One per call.
- The scan reads `tag` as a val on the sealed base: one monomorphic field
  load regardless of which kinds populate the array; the kind dispatch after
  a hit is two class checks on a sealed hierarchy. Typing costs the scan
  nothing.

Rejected alternative: one final erased record (tag as `Tag[Any]`, an int
kind, the clause as `AnyRef`, nullable fields). It buys the scan nothing the
base-class val does not already give, and it violates the typing discipline
this kernel inherits from the previous round: clauses at public types,
invoked at their types, erasure only at documented tag-keyed boundaries.

### 2.2 The parameter

`ev: Handlers` is added to the internal application surface:

- `Arrow.apply(v)` and `Transform.apply(v, next)` gain the parameter; so do
  the `Step` factory's apply and the identity transform.
- The two inline mapLoops (Pending.map, ArrowEffect.suspendWith) thread it;
  the initial call at a `.map`/mint expression passes empty (with a pending
  self they attach; with a settled self the result returns to an
  evidence-holding caller, so nothing is lost).
- The five trampolines take an ambient parameter, compute
  `extended = ambient.add(handler)` once per invocation, run interior walks
  (matched-arm continuations, Defer steps, rotation re-entry bodies) under
  extended, and exit their settled arm under ambient.
- `eval`/`evalPartial` step Defers with empty evidence. Fork boundaries fall
  out: a fiber inherits nothing, structure re-arms everything.

Untouched: the `<` union and lift, Nested/unnest, Kyo.map/Suspend.map/
Defer.map (attachment makes no evidence decision), chain and the AndThen
normalization, Safepoint, every public signature.

### 2.3 First-touch dispatch

Evidence is consulted where a pending value first meets machinery: the
mapLoop kyo arm. Never at mint: mint sites live inside user lambdas that
cannot receive the parameter, and mint-time answering would change what a
computation value means (a stored `ask.map(f)` must stay inert; the suite
pins it). The arm becomes:

    case kyo: Kyo[A, S3] @unchecked =>
        if ev eq Handlers.empty then kyo.map(arrow)
        else Handlers.dispatch(kyo, arrow, ev)

`dispatch` is a static outlined helper so the inline mapLoop body grows by
one eq branch and one static call, protecting the 325-byte inline budget the
continuation entries already exceed. Inside dispatch, by the innermost
matching handler for the suspension's tag:

- absent: `kyo.map(arrow)`, byte for byte today's path.
- Shadow: same structural path; the built continuation reaches the
  trampoline that needs it. Shadows are what make innermost-wins structural
  across kinds.
- Resume: apply the clause in place (2.4).
- Stop: return a Halt (2.5); `arrow` is dropped, which is the point.
- Defer and Halt inputs fall through to `kyo.map(arrow)` (for Halt that is
  itself, zero work).

### 2.4 Resume in place

Clause scope rule (inherited, validated by the old round's p1/p2/p9): a
clause runs OUTSIDE its own region, under the evidence captured at region
entry, extended with the region itself (deep semantics). `h.scope` stores
the collection at push, excluding the handler; use sites add it back.

On a Resume hit: `w = h(input)` under `h.scope.add(h)`.

- w settled: feed it through the suspension's own continuation, then the
  site remainder, all under the flowing site evidence. Wrap rotation makes
  the scope re-establishment automatic: if the suspension had crossed
  regions, its cont begins with their re-entries.
- w pending (effectful answer, clause parked): one bracket, which is just a
  rotation-style re-entry whose region is the clause and whose evidence is
  `h.scope.add(h)` with stops downgraded (2.5). Allocated only on this
  shape, never on settled answers.

Stack safety: in-place answering nests real frames, bounded by the Safepoint
budget; at Period the walk mints a Defer and the trampoline or eval restores
a flat stack. Pinned at depth well past Period on all three platforms.

### 2.5 Stop as Halt

    final private[kernel] class Halt[I[_], X](
        val owner: Handler.Stop[I, ?, ?],  // compared by eq
        val input: I[X],
        val frame: Frame
    ) extends Kyo[Nothing, Any]:
        def map[B, S2](f: Arrow[Nothing, B, S2]): B < S2 = this

`map` returning itself makes every attachment site discard pending work by
construction: the unwind allocates nothing after the one Halt. The owning
stop trampoline matches on owner identity and applies its clause AT the
boundary (so the clause's scope is trivially the boundary's own, and a
clause that raises the same effect again is re-dispatched by the same loop,
as the suite already pins). Every trampoline gets a pass-through arm before
its settled arm; eval treats an unowned Halt as a defect.

Soundness rests on the enclosure invariant (2.6). The one violation class is
snapshots: evidence captured into values that outlive the capturing frames
(handle's continuation closure, a resume handler's scope). The rule that
closes it: flowing evidence may carry stop entries; snapshotted evidence
replaces Stop handlers with Shadow at capture. A downgraded stop falls back to
structural travel, today's semantics for an escaped continuation.
`downgradeStops` copies only when a Stop handler is present.

Alternative considered: the old kernel round chose bare pass-through (return
the suspension unchanged, no new node) and rejected propagating an applied
clause result. Halt carries the raw input, not a result, so that rejection
does not apply, and in this kernel Halt is the more minimal runtime: the
pass-through re-resolves evidence at every crossed frame while Halt pays one
allocation per stop event and nothing per frame, and the snapshot hazard
that silently loses work under pass-through becomes an explicit defect or a
sound downgrade under Halt.

### 2.6 Registration and shadowing

| method  | handler pushed                    | at first-touch     | continuation built |
|---------|-----------------------------------|--------------------|--------------------|
| resume  | Handler.Resume, per entry/re-entry | answered in place | never              |
| stop    | Handler.Stop, one per call        | Halt to boundary   | never              |
| handle  | Handler.Shadow, one per call      | structural travel  | yes, by design     |
| loop    | Handler.Shadow, one per call      | structural travel  | yes, by design     |
| partial | none                              | none               | n/a                |

loop stays on the trampoline path because its state forks per continuation
invocation (pinned); a mutable cell in a handler would share state across
replays, a semantics change. partial installs no region (no rotation, result
keeps E), so there is no scope for an entry to describe.

## 3. Invariants

1. Scope: a clause runs outside its own region under the evidence captured
   at region entry plus itself; the remainder after an answer runs under
   site evidence.
2. Enclosure: a handler is in flowing evidence iff its trampoline frame
   encloses the current walk. Halt therefore always unwinds through its
   owner; rotation must stay wrap-style.
3. Snapshot: flowing evidence may carry Stop handlers; snapshots replace
   them with Shadow.
4. Shadowing: the innermost matching handler decides; shadows make every
   region visible to the decision.
5. Fallback: a miss is exactly today's structural suspension; evidence and
   trampoline paths agree on every program.
6. Values: Nested-boxed computations are never consulted, answered, or
   halted; evidence acts on executing suspensions only.
7. Fork: evidence starts empty at every eval entry.
8. Budget: in-place depth is bounded by the Safepoint Period.

## 4. Performance gates

Unchanged, gating the parameter and the outlining: evalFixedOverhead,
fusionAllocatesNothing, fusionPastBudgetPaysRescuesOnly,
deepRecursionPaysRescuesOnly, continuationBodiesFuse, trailingMapsStayLinear,
suspensionBaseline, suspensionFusesContinuation, idleHandlerAddsNothing,
foreignCrossingsPayRotation.

Expected to move: resumeAnswersInPlace toward the fused rate (old-kernel
ceiling ~15x time, ~100x allocation). sharedHandlerPaysDispatch stays as the
handle-kind limit pin; new row sharedResumeAnswersLocally (the same 16 sites
under resume) is the direct megamorphism claim, expected near 1x of the
single-site row. New rows deepStopHaltsWithoutContinuation (O(N) attachment
collapse), stopPreBuiltChainUnchanged (honesty: construction before the
handler cannot be helped), foreignBubbleUnderEvidence (scan-on-miss cost).

## 5. Acceptance

The 11 reference programs p1 through p11 with their old-kernel-validated
results (kernel2-rotation-handlers-design.md section 4a), plus: the two
rotation-law pins (landed with the fix), dual-path equality for every
terminating program, cross-kind shadowing in both orders, Halt crossing
foreign trampolines, the stop-replacement-resuspends evidence variant, the
parked-clause bracket programs, escaped continuation with an outer stop
region (downgrade rule), snapshot honesty (stored computation answers under
the later handler), subtype dual-path (askSub via evidence), and 100k
in-place recursion on JVM, JS, and Native.

## 6. Rulings needed

1. Approve the design above for implementation. Recommendation: yes, with
   the perf gates and acceptance programs as the definition of done.
2. The snapshot downgrade rule trades in-place stop hits inside escaped
   resumptions for soundness. Recommendation: accept; the alternative is
   liveness tracking, machinery this design should not grow.
3. loop stays trampoline-only (state-forks-per-invocation is semantics).
   Recommendation: keep; revisit only on profile evidence.
4. partial registers nothing. Recommendation: confirm.
5. Names: `Handlers` for the collection, `Handler` with `Resume`, `Stop`,
   and `Shadow` for the hierarchy, `Halt` for the stop value, all
   `private[kernel]`. Recommendation: as stated.
6. Row naming for the new benchmark rows. Recommendation: as listed in
   section 4.
