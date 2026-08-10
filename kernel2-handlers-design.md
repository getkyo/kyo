# kernel2 design: handlers at the suspension site

Status: proposal, awaiting review, implemented step by step with each step
validated before the next. The one prerequisite already landed as a separate
bug fix: wrap-style rotation (commit 727e8e6742, five reproducing pins
written and confirmed red first), because the previous append-style rotation
lost handlers behind trailing transforms and because the re-entry guarantees
below depend on the wrap encoding. Backing analysis:
kernel2-evidence-handlers-analysis.md (historical; it proposes mechanisms
and vocabulary this design has since eliminated; where they disagree, this
document wins). Prior art: kernel2-rotation-handlers-design.md (E1/E2b
measurements, clause scope rule, the 11 reference programs),
kernel2-handler-encodings.md (registration and shadowing discipline).

## 1. What changes

resume and stop handlers act at the suspension site when their handler is
visible, without building the continuation. A resume-handled operation is
answered inside the per-site mapLoop; a stop-handled operation discards the
pending work by construction and carries its input to the boundary. A
missing handler produces a regular suspension handled by the trampolines
exactly as today. handle and loop keep their trampolines and their built
continuations.

Motivation, measured: the shared trampoline is the kernel's one megamorphic
surface (sharedHandlerPaysDispatch: 2.79x at 16 sites, identical allocation;
kernel2-jit-morphism-report.md), and the old kernel's round measured
in-place resume at ~15x time and ~100x allocation against park-and-dispatch
(E1/E2b in kernel2-rotation-handlers-design.md).

## 2. The mechanism

Composition, not machinery: the handlers ride as an argument of arrow
application, the same way a value does. No implicits anywhere, by explicit
constraint: a plain positional parameter named `handlers` on the internal
execution signatures; user code never sees it.

### 2.1 Handlers: a collection of handlers

Every handler method has a typed form saving exactly its method's clause at
its public types, named after the method (implemented, step 1):

    sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O]](val tag: Tag[E])

    object Handler:
        abstract class Handle[..., A, S](tag):
            def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)
        abstract class Resume[..., S](tag):
            def apply[X](input: I[X]): O[X] < S
        abstract class Stop[..., A, S](tag):
            def apply[X](input: I[X]): A < (E & S)
        abstract class Loop[..., A, S, State](tag):
            def apply[X](input: I[X], state: State, cont: O[X] => A < (E & S)): (State, A < (E & S))
        abstract class Partial[..., A, S](tag):
            def apply[X](input: I[X], cont: O[X] => A < (E & S)): Maybe[A < (E & S)]

Clauses are invoked at their declared types. The one erased boundary is the
tag-keyed recovery at the lookup site, justified by the tag match, the same
documented cast the trampolines' matched arms carry today. Each handler
method mints one handler value per call. Halt ownership compares the Stop
value by eq.

`Handlers` is an opaque type over `Chunk[Handler[?, ?, ?]]` (implemented,
step 1): `empty`, `add`, and an innermost-last `find` whose match relation
is the trampolines' matched-arm guard (suspension tag `<:<` handler tag).
A later step adds, with its own tests, a prefix view up to a given handler
for the clause scope rule. The scan reads `tag` as a val on the sealed
base: one monomorphic field load.

### 2.2 The parameter

`handlers: Handlers` is added to the internal application surface:

- `Arrow.apply(v)` and `Transform.apply(v, next)` gain the parameter; so do
  the `Step` factory's apply and the identity transform.
- The two inline mapLoops (Pending.map, ArrowEffect.suspendWith) thread it;
  the initial call at a `.map`/mint expression passes empty (with a pending
  self they attach; with a settled self the result returns to a caller that
  holds the parameter, so nothing is lost).
- The five trampolines take the parameter; each region-installing one
  (handle, resume, stop, loop) adds its own handler once per invocation;
  partial adds nothing (it installs no region and nothing executes under
  it). Interior walks (matched-arm continuations, Defer steps, rotation
  re-entry bodies) run under the extended collection; the settled arm exits
  under what the trampoline received.
- `eval`/`evalPartial` step Defers with empty. Fork boundaries fall out: a
  fiber inherits nothing; structure (the wrap-rotations in the chain)
  re-arms everything.

Untouched: the `<` union and lift, Nested/unnest, Kyo.map/Suspend.map/
Defer.map (attachment makes no handler decision), chain and the AndThen
normalization, Safepoint, every public signature.

### 2.3 Where the lookup happens

The lookup happens where a pending value first reaches machinery: the
mapLoop kyo arm. Never at mint: mint sites live inside user lambdas that
cannot receive the parameter, and mint-time answering would change what a
computation value means (a stored `ask.map(f)` must stay inert; the suite
pins it). The arm becomes:

    case kyo: Kyo[A, S3] @unchecked =>
        if handlers eq Handlers.empty then kyo.map(arrow)
        else Handlers.dispatch(kyo, arrow, handlers)

`dispatch` is a static outlined helper so the inline mapLoop body grows by
one eq branch and one static call, protecting the 325-byte inline budget the
continuation entries already exceed. Inside dispatch, by the innermost
handler matching the suspension's tag:

- no match: `kyo.map(arrow)`, byte for byte today's path. This is the
  maintainer's "a missing handler produces a regular suspension that will
  see the handler after the continuation is resumed".
- Resume: apply the clause in place (2.4).
- Stop: return a Halt (2.5); `arrow` is dropped, which is the point.
- Handle or Loop: `kyo.map(arrow)`, the structural path; these need the
  built continuation, and their presence in the collection is what makes an
  inner handle correctly shadow an outer resume of the same tag.
- Defer and Halt inputs fall through to `kyo.map(arrow)` (for Halt that is
  itself, zero work).

### 2.4 Resume in place

Clause scope rule (inherited, validated by the old round's p1/p2/p9): a
clause runs OUTSIDE its own region, extended with the region itself (deep
semantics). Nothing needs to be saved to express this: at a hit on handler
`h` found at position i of the passed collection, the clause runs under the
prefix of that collection through i. Computed at use, never stored.

On a Resume hit: `w = h(input)`, with the clause's own suspensions
traveling under that prefix.

- w settled: feed it through the suspension's own continuation, then the
  site remainder, all under the full passed collection. Wrap rotation makes
  scope re-establishment automatic: if the suspension had crossed regions,
  its cont begins with their re-entries.
- w pending (effectful answer, or the clause parked): one anonymous
  Transform, the same wrapper shape as every rotation re-entry, that walks
  the rest of the clause under the prefix and then feeds the answer to the
  untouched remainder under whatever collection is passed at application.
  Allocated only on this shape, never on settled answers.

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

Soundness: under wrap rotation, a handler is passed to a walk exactly when
its trampoline frame encloses that walk, so a Halt unwinding by ordinary
returns always passes through its owner. What keeps that airtight is one
rule about the difference between the two kinds: a Resume handler is a
value (clause at its types, no live frame needed), a Stop handler is
positional (a Halt needs the owner's live frame). Therefore Stop handlers
are only ever passed, never stored: the two places the collection is stored
into a value (the pending-clause wrapper of 2.4, and handle's continuation
closure if it ever carries the parameter) keep Resume handlers only, using
a filter at the storage point. A stored walk that
raises a stop-handled operation travels structurally and reaches the live
stop trampoline by enclosure, today's semantics.

### 2.6 Who adds what

| method  | adds              | at the lookup     | continuation built |
|---------|-------------------|-------------------|--------------------|
| handle  | Handler.Handle    | structural travel | yes, by design     |
| resume  | Handler.Resume    | answered in place | never              |
| stop    | Handler.Stop      | Halt to boundary  | never              |
| loop    | Handler.Loop      | structural travel | yes, by design     |
| partial | nothing           | none              | n/a                |

Registration is uniform: every region-installing method adds its own typed
handler, so innermost-wins is scan order for every kind and no masking or
removal mechanism exists. A Handle or Loop hit takes the structural path
because those kinds need the built continuation; the hit still shadows any
outer same-tag handler, which is the correctness requirement.

loop stays on the trampoline path because its state forks per continuation
invocation (pinned); a mutable cell in a handler would share state across
replays, a semantics change. partial installs no region (no rotation,
result keeps E) and nothing executes under it, so it adds nothing.

## 3. Invariants

1. Scope: a clause runs under the prefix of the passed collection through
   its own handler; the remainder after an answer runs under the collection
   passed at the answer site.
2. Enclosure: a handler is passed to a walk iff its trampoline frame
   encloses that walk. Consequences: Halt always unwinds through its owner;
   rotation must stay wrap-style.
3. Stored collections carry Resume handlers only; Stop is positional.
4. Innermost-wins: resume and stop win by scan order; handle and loop win
   by subtraction.
5. Fallback: a miss is exactly today's structural suspension; the lookup
   path and the trampoline path agree on every program.
6. Values: Nested-boxed computations are never looked up, answered, or
   halted; the lookup acts on executing suspensions only.
7. Fork: the parameter starts empty at every eval entry.
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
handler cannot be helped), foreignBubbleUnderHandlers (scan-on-miss cost).

## 5. Acceptance

The 11 reference programs p1 through p11 with their old-kernel-validated
results (kernel2-rotation-handlers-design.md section 4a) where today's
kernel can express them (p3, p4, p5 need guard machinery kernel2 does not
have yet; they land with it), plus: the two rotation-law pins (landed with
the fix), dual-path equality for every terminating program, cross-kind
shadowing in both orders, Halt crossing foreign trampolines, the
stop-replacement-resuspends variant raised deep in a walk, the parked-clause
programs, a stored walk raising a stop-handled operation (invariant 3),
a stored computation answering under the later handler, subtype behavior on
both paths (askSub), and 100k in-place
recursion on JVM, JS, and Native.

## 6. Steps

1. `Handler` and `Handlers`, standalone, unit-tested. No execution code
   touched.
2. Thread the parameter, passing empty everywhere. No behavior change;
   fused-ladder A/B at parity.
3. Registration: the region-installing trampolines add; rotation re-entries
   re-arm. Still no lookup; behavior unchanged; handler-row A/B at parity.
4. Resume in place: the outlined dispatch, settled answers plus the
   pending-clause wrapper. Acceptance tests and the headline rows.
5. Stop as Halt: the class, the dispatch arm, pass-through arms, eval
   defect arm, invariant 3 at the two storage points. Stop programs and
   rows.
6. Sweep: remaining expressible reference programs, cross-kind pins, docs
   to implemented state.

## 7. Rulings needed

1. Approve the design for implementation, with the perf gates and
   acceptance programs as the definition of done.
2. Invariant 3 (stored collections carry Resume handlers only) trades
   in-place stop hits inside stored walks for soundness; the fallback is
   today's structural semantics. Recommendation: accept; the alternative is
   liveness tracking.
3. loop stays trampoline-only (state-forks-per-invocation is semantics).
   Recommendation: keep; revisit only on profile evidence.
4. partial adds nothing. Recommendation: confirm.
5. Names: `Handlers`, `Handler.Resume`, `Handler.Stop`, `Halt`, the
   parameter named `handlers`. Recommendation: as stated.
6. Row naming for the new benchmark rows. Recommendation: as listed in
   section 4.
