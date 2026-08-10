# Evidence handlers for kyo-kernel2: design analysis

Scope: the maintainer's directive to make stop handlers halt and resume handlers
answer locally at the suspension, without building the continuation when the
handler is visible, wired as a Handlers parameter piped through execution, with
misses falling back to regular suspensions. All file references are to the
worktree at `.claude/worktrees/effervescent-painting-backus`.

Sources read in full: Kyo.scala, Arrow.scala, ArrowEffect.scala, Pending.scala,
Safepoint.scala (kyo-kernel2), kernel2-rotation-handlers-design.md,
kernel2-jit-morphism-report.md, kernel2-handler-encodings.md,
kernel-handler-sync-path-analysis.md, KernelBench.scala, ArrowEffectTest.scala.

## 0. Summary of the recommendation

1. Evidence is consulted at first-touch (position b), never at mint. The new
   kernel makes this natural: `Transform.apply` already receives possibly
   pending values, so every suspension meets evidence-holding machinery one
   ordinary return after it is minted. Mint-time consultation would require a
   thread-local and would change the meaning of computations as values. It is
   disqualified.
2. The wiring is one added EXPLICIT parameter (a plain value argument, never
   a `using` clause; implicit threading is rejected, section 4):
   `Transform.apply` (and the thin `Arrow` and `Step` apply entry points
   above it) gains a `Handlers` argument. Every mapLoop passes it through,
   the five trampolines extend it, rotation re-entries re-arm it,
   `eval`/`evalPartial` start it empty. Nothing else in the chain machinery
   changes shape.
3. All four region-installing kinds register an entry (resume, stop, handle,
   loop); only resume and stop are fast-path, handle and loop register barrier
   entries. Innermost-last scan plus barriers makes innermost-wins a property
   of the collection, so cross-kind shadowing is correct by construction.
   `partial` installs no region and registers nothing.
4. Stop is a `Halt` value: a `Kyo` whose `map` returns itself, carrying the
   owning entry (identity) and the raw input. Pending work discards itself by
   construction on the unwind; the owning trampoline applies the clause at the
   boundary. Every trampoline gets a pass-through arm; eval treats an
   unowned Halt as a defect.
5. Prerequisite discovered during this analysis: the current append-style
   rotation in the trampolines' foreign and Defer arms has a reachable
   soundness hole, independent of this design (section 2). The fix, wrap-style
   rotation (the rotation law applied verbatim), is also the exact mechanism
   that lets rotation re-entries re-supply the evidence parameter. It must
   land first, with its own reproducing pin.

## 1. The execution model fact everything else rests on

The new kernel has no central drive. Its one structural currency is:
`Transform.apply[C, S2](v: A < S2, next: Arrow[B, C, S2])` receives possibly
PENDING values. The fused walk (`step.head(f(res), step.tail)` in every
mapLoop settled arm, Pending.scala:38-39, ArrowEffect.scala:43-44) hands a
pending result directly to the next transform. Ordinary map transforms react
to a pending input by attaching themselves plus their downstream and returning
it up the stack (the kyo arm, Pending.scala:30-31); handler rotation wrappers
react by re-entering their trampoline with the pending value in hand
(ArrowEffect.scala:64-68 and analogues). A suspension minted anywhere inside a
user lambda therefore returns, by ordinary function returns, to the nearest
machinery frame within at most one attach event, and machinery frames are
exactly where a parameter can flow.

This is what makes position (b) below not merely viable but essentially free:
the machinery touches every suspension almost immediately after mint, on the
already-compiled per-site path.

## 2. Prerequisite finding: append-rotation loses the handler (live defect)

This is a soundness hole in the current kernel2 trampolines, found by static
trace while answering design question 6. It exists today, before any evidence
work.

The foreign arm rotates by appending: `kyo.map(rotated(next))`
(ArrowEffect.scala:76, 114, 151, 189). The rotation wrapper is placed AFTER
the suspension's existing continuation. The rotation law demands the opposite
nesting:

    handle(t1, suspend(t2, in, cont), f) == suspend(t2, in, x => handle(t1, cont(x), f))

On the right-hand side the handler wraps the APPLICATION of `cont`, so
anything arising during that application is inside the re-entered handler.
The appended encoding computes `handle(t1, _) compose cont` instead: `cont`
runs outside the region and only its settled value re-enters the loop.

The two encodings agree whenever an operation of the handled tag arises in the
LAST transform before the rotation wrapper, because the settled-arm handoff
passes the pending suspension directly into the wrapper, which re-enters the
loop and answers. Every existing suite pin and the foreignCrossingsPayRotation
bench have exactly that shape. They diverge when at least one ordinary
transform sits between the operation and the wrapper. Concrete failing
program:

    val prog: Int < (Say & Ask) = say("x").map(_ => ask).map(_ + 1)
    ArrowEffect.handle(Tag[Say],
      ArrowEffect.handle(Tag[Ask], prog)([X] => (_, cont) => cont(41))
    )([X] => (_, cont) => cont(())).eval

Trace: handle(Ask) sees the Say suspension first, appends rotA: cont is
[f: _ => ask, g: _ + 1, rotA]. handle(Say)'s clause resumes with (): the walk
runs f, which mints the Ask suspension; the next step is g, an ordinary
transform, whose kyo arm attaches [g, rotA] INTO the Ask suspension's own
continuation and returns it up. The Ask suspension unwinds to the Say loop
(foreign: appends rotS) and escapes both handlers, statically typed
`Int < Any`, with its only possible answerer (rotA) buried inside its own
future. `eval` throws "unhandled suspension". The handler is lost.

The fix is to encode the law directly. The foreign arm builds a new Suspend
whose continuation IS the re-entry (the remainder application happens inside
the re-entered loop), instead of appending a re-entry after the remainder:

    // wrap-style rotation, sketched at handle; identical shape for the others
    case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
        val k = kyo.asInstanceOf[Kyo.Suspend[I2, O2, E2, Any, A, E & S]]
        new Kyo.Suspend[I2, O2, E2, Any, C, S & S2]:
            override val root = k.root
            def tag           = root.tag
            def input         = root.input
            def frame         = root.frame
            val cont = new Arrow.Transform[O2[Any], C, S & S2]:
                def frame = _frame
                def apply[D, S3](o: O2[Any] < S3, next2: Arrow[C, D, S3], ev: Handlers) =
                    val step = k.cont.step
                    handleLoop(step.head(o, step.tail, ev.add(entry)), next.chain(next2), ev)

With this shape the remainder walk happens inside the loop frame: a pending
same-tag result returns into `handleLoop`, which answers it. The failing
program above becomes correct, "stays in force across resumptions" keeps its
meaning at every chain position rather than only at direct-handoff positions,
and the loop frame encloses the remainder for the whole of its execution.
Allocation is unchanged: one Suspend plus one Transform per crossing, exactly
what append-rotation pays today.

The same defect and the same fix apply to every trampoline's Defer arm
(budget exhaustion path, e.g. ArrowEffect.scala:77-88): a Defer that escapes
must carry a wrap-style re-entry, or an operation of the handled tag arising
while eval steps the deferred remainder escapes identically.

Two consequences for this design:

1. The evidence parameter REQUIRES wrap-style rotation: "rotation wrappers
   re-supply evidence on re-entry" is only possible if the wrapper delimits
   the remainder application. Under append-rotation the remainder has already
   run, under the wrong ambient, before the wrapper fires.
2. Wrap-style rotation gives the enclosure invariant (section 6) that makes
   Halt sound.

Order of work: reproduce (the pin above must fail on current code), fix
rotation in all five trampolines' foreign and Defer arms, then build evidence
on top. The fix is not extra scope; it is the load-bearing wall.

## 3. Design question 1: where evidence is consulted

### Position (a), mint-time: disqualified

`suspendWith` is inline and its expansion sits inside arbitrary user lambdas.
No lexical path exists from the calling machinery into that expansion: the
lambda is a plain `Function1` invoked as `f(res)` from a mapLoop settled arm.
The only carrier that reaches a mint site is a thread-local (a Safepoint-slot
sidecar is a thread-local with different clothes). That is disqualifying on
three grounds:

1. It is not structural. Fiber hops, stored continuations resumed on other
   threads, and forked evals all need manual set/clear discipline, the exact
   class of bug the parameter design exists to make impossible.
2. It changes what a computation value means. Construction-time answering
   makes `val v = ask.map(f)` observable: built inside a resume region and
   stored, it would be already answered. The suite pins the opposite ("a
   computation held as a value passes through a handler untouched",
   ArrowEffectTest.scala:307), and the Nested boxing mechanism exists
   precisely to keep stored computations inert. Handled-at-execution is the
   semantics of both kernels today.
3. The saving is only the mint node itself (24 bytes for suspendWith, which
   is one allocation regardless), because by section 1 the machinery touches
   the value one return later anyway. E1 established that the win lives in
   avoiding attachment, unwind, and dispatch, all of which (b) also avoids.

### Position (b), first-touch: recommended

The consultation point is the mapLoop kyo arm, the one place every pending
value funnels through (Pending.scala:30-31, ArrowEffect.scala:35-36, and the
walk handoffs that land there). The arm becomes:

    case kyo: Kyo[A, S3] @unchecked =>
        if ev eq Handlers.empty then kyo.map(arrow)
        else Handlers.dispatch(kyo, arrow, ev)      // static, outlined

`dispatch` is a static helper (section 8 for why it is outlined): Defer and
Halt fall through to `kyo.map(arrow)` (for Halt that returns the Halt itself,
zero code); for a Suspend it scans the evidence innermost-last with the
suspension's tag:

- no entry: `kyo.map(arrow)`, the structural path, byte for byte today's
  behavior. This is the maintainer's "a missing handler produces a regular
  suspension that will see the handler after the continuation is resumed".
- resume entry: answer in place (section 5).
- stop entry: return a Halt (section 6). The local continuation is never
  attached; `arrow` is dropped on the floor, which is the entire point.
- barrier entry (handle/loop innermost for this tag): structural path, so the
  built continuation reaches the trampoline that needs it.

Semantics: (b) answers at execution first-touch. A suspension stored as a
value (Nested-boxed) is not a `Kyo` at the match and passes through untouched;
a pre-built suspension handed to a handler is answered by the trampoline's
matched arm at the handle call (eager, as today); a suspension executing under
a visible handler is answered at its site. All three meanings are the current
ones; evidence only removes the travel.

## 4. Design question 2: the wiring surface

### Signatures that change

All internal (`Arrow` and friends are `private[kernel]`-adjacent surface;
public `ArrowEffect` and `<` entry points do not move):

1. `Arrow.apply(v: A): B < S` becomes `apply(v: A, ev: Handlers): B < S`.
   Implementors: `AndThen.apply`, `Step.apply` (the anon in `Step.apply`
   factory: `head(v, tail, ev)`), `Transform`'s final one-arg bridge
   (`apply(v, Arrow[B], ev)`).
2. `Arrow.Transform.apply[C, S2](v: A < S2, next: Arrow[B, C, S2])` gains
   `ev: Handlers`. Implementors: the identity transform (pass-through), the
   per-site map arrows in `Pending.map` and `suspendWith` (pass `ev` into
   their mapLoop), the suspendWith mint itself (its `apply` is the mapLoop
   entry), and the rotation wrappers (which become wrap-style, section 2).
3. The two inline mapLoops (`Pending.map`, `ArrowEffect.suspendWith`) gain an
   `ev` parameter. The initial calls at the `.map`/mint expression pass
   `Handlers.empty` (they have nothing else lexically, and by section 1 they
   never need more: with `self` pending they attach, with `self` settled the
   result returns to an evidence-holding caller).
4. The five trampolines gain an ambient parameter:
   `handleLoop[C, S2](v: A < (E & S), next: Arrow[A, C, S2], ambient: Handlers)`.
   Each computes `extended = ambient.add(entry)` once per invocation.
   Interior applications (matched-arm continuation walks, Defer stepping,
   wrap-rotation bodies) use `extended`; the settled arm exits with
   `ambient` (`step.head(v, step.tail, ambient)`): the post-region chain runs
   outside the region. Initial calls pass `Handlers.empty` as ambient.
   handle's continuation closure becomes `o => anchored.cont(o, captured)`
   with `captured = extended.downgradeStops` (section 6).
5. `eval` and `evalPartial` step Defers with `Handlers.empty`
   (Pending.scala:63, 85). Evidence starts empty per eval entry, which is the
   fork-boundary rule falling out of the parameter: forked fibers inherit
   nothing; structure (wrap-rotations in the chain) re-arms everything.

### What stays untouched

The `<` union and lift, `Nested`/unnest, `Kyo.map`/`Suspend.map`/`Defer.map`
(pure attachment, no evidence decision is ever made there), `Arrow.chain` and
the `AndThen.step` normalization with its scratch buffer, `Safepoint`
entirely, and every public signature of `ArrowEffect` and `Pending`.

### New pieces

- `Handlers`: one final array-backed class plus an `empty` singleton
  (inherited E2b result: innermost-last scan, `eq`-first tag comparison with
  the structural `<:<` fallback off the fast path, copy-on-add). The scan
  must use the trampolines' relation (`suspTag <:< entry.tag`) so subtype
  effects (ArrowEffectTest.scala:103) resolve identically on both paths.
- `Handlers.Entry`: ONE final class, not a hierarchy, for monomorphic reads:
  `tag: Tag[Any]`, `kind: Int` (RESUME, STOP, BARRIER), `clause: AnyRef`
  (the erased poly-function, null for stop and barrier entries), `scope:
  Handlers` (resume only, null otherwise; section 5). Stop and barrier
  entries are created once per handle call and reused (stable identity, which
  Halt ownership needs); resume entries are created per region entry and per
  rotation re-entry because their scope differs each time.
- `Kyo.Halt` (section 6).
- The static `Handlers.dispatch` helper and the clause bracket transform
  (section 5).

### Smaller-surface alternatives, assessed

- Implicit threading (`using Handlers` / given instances): rejected, and now
  also excluded by the maintainer's constraint. On the merits it fails
  twice. First, givens resolve lexically at compile time, but the evidence
  is a runtime value of the EXECUTION, different at every re-entry of the
  same code (rotation re-entries, multi-shot replays, fibers): a
  compile-time resolution mechanism cannot carry a value that only exists
  dynamically, so the given would have to be summoned at machinery
  boundaries and passed along anyway, reducing to the explicit parameter
  with extra resolution machinery and invisible plumbing on signatures.
  Second, an implicit `Handlers` in scope at user-code level would leak the
  kernel's execution state into inference and into user signatures, exactly
  the kind of surface contamination the kernel avoids (Frame is the one
  deliberate exception and it is compile-time data). Everything in this
  design is an explicit value parameter: `ev: Handlers` on the machinery
  applies, an ordinary argument on the trampolines. No `using` clause
  appears anywhere, kernel or user-facing.
- Thread-local or Safepoint-carried ambient: rejected above; not structural.
- Evidence as a chain node (a marker arrow the walk inspects): every chain
  operation and the normalization would have to know about it, and lookups
  become chain walks. Strictly worse than a parameter.
- Evidence as a field on transforms: transforms are shared immutable values
  executed under different handlers at different times (accumulatedChain in
  the bench is one arrow value run repeatedly); execution state cannot live
  on them.

The explicit parameter is the minimal structural carrier. The maintainer's
instinct ("the only way to wire that is a Handlers param piped in the
execution") is correct.

## 5. Design questions 3 and 4: kinds, shadowing, and clause scope

### Registration table

| kind    | entry pushed             | fast path at first-touch | continuation built |
|---------|--------------------------|--------------------------|--------------------|
| resume  | RESUME, per entry/re-entry | answer in place        | no                 |
| stop    | STOP, one per handle call  | Halt to the boundary   | no                 |
| handle  | BARRIER, one per call      | none (structural)      | yes, by design     |
| loop    | BARRIER, one per call      | none (structural)      | yes, by design     |
| partial | none                       | none                   | n/a                |

Shadowing: the innermost-last scan returns the innermost matching entry
regardless of kind. An inner `handle` for tag T inside an outer `resume` for
T yields a BARRIER hit at sites inside the inner region, forcing structural
travel to the inner trampoline: innermost-wins across kinds is structural, no
special rule. This is the "registration of ALL kinds with fast-path flags"
position, and I endorse it; the barrier IS the cleaner rule. Any scheme that
registers only fast-path kinds has to reintroduce the same information as a
side table to avoid the outer-entry capture bug that
kernel2-handler-encodings.md section 1.5 documented on the previous kernel.

Why `loop` stays on the trampoline path: its state is a loop parameter, and
the suite pins that state forks per continuation invocation
(ArrowEffectTest.scala:396). A mutable cell in an entry would share state
across multi-shot replays and across the fork boundary, breaking that pin's
semantics. In-place stateful answering is possible only by giving up the
fork-on-replay property, which is a semantics change, not an optimization.
Keep loop general; its barrier entry costs one object per handle call.

Why `partial` registers nothing: it installs no region (no rotation on
foreign suspensions, `A < (E & S)` result, ArrowEffect.scala:211-237); it
acts only on suspensions that have already bubbled to it. There is no scope
for an entry to describe.

### The resume fast path

On a RESUME hit for suspension `kyo` with entry `e` (clause `f`, at the site
ambient `ev`):

1. `val w = f(input)` (two casts under the tag match, the same discipline as
   the trampolines' matched arms).
2. If `w` is settled: feed it through the suspension's own continuation and
   then the site's remainder: `arrow(kyo.cont-walked(w, ev))`, all under the
   flowing `ev`. Wrap-style rotation makes this scope-correct automatically:
   if `kyo.cont` begins with rotation wrappers (the suspension had crossed
   regions before reaching this site), applying it re-enters those regions
   and re-extends the evidence before any interior code runs. No bookkeeping.
3. If `w` is pending (effectful answer, or the clause parked): the clause is
   mid-flight and the rest of it must run at clause scope, then the site
   remainder at site scope. Mechanism below.

Clause scope rule (inherited, still correct here): a clause runs OUTSIDE its
own region, under the environment captured at region entry, extended with the
region itself (deep). Representation: `e.scope` is the evidence array as it
existed at the entry's push, excluding `e` itself; users add `e` back at use
(`e.scope.add(e)`) so the entry needs no self-referential field.

The pending-clause mechanism is one more wrap-style re-entry, the same shape
as a rotation wrapper whose region is the clause:

    // dispatch, pending-answer arm: w is the clause's pending computation
    val wk = w.asInstanceOf[Kyo.Suspend[...]]
    new Kyo.Suspend[..., C, ...]:
        override val root = wk.root
        ...
        val cont = new Arrow.Transform[O2[Any], C, ...]:
            def apply[D, S3](o, next2, ambient) =
                val clauseEv = e.scope.add(e).downgradeStops   // section 6
                val r = wk.cont-walked(o, clauseEv)            // rest of the clause, clause scope
                // r settled: exit the bracket, site remainder under the flowing ambient
                arrowSite(kyo.cont-walked-under(ambient)... )  // feed as in step 2
                // r pending: rebuild this bracket around it (same re-wrap as any region)

Allocated only when a clause's answer suspends (the p9 shape), never on the
settled fast path. It is not a new abstraction: it is the rotation wrapper
pattern with `e.scope` as the re-entry evidence. p9's pinned behavior (a
resume clause parks mid-clause; its post-resume read resolves at clause
scope; the remainder after the answer resolves at site scope) falls out of
exactly these two evidence choices.

Stack safety of in-place answering: each in-place answer nests the dispatch,
cont walk, and user continuation as real stack frames instead of trampoline
iterations. The bound is the existing Safepoint budget: every settled
application enters the slot, and at Period (512) the walk mints a Defer that
unwinds the nested frames to the owning trampoline or eval, which steps it
fresh. In-place depth is therefore bounded at Period times a small constant
per answer. This must be pinned (deep in-place recursion at depth well past
Period, all three platforms; the JS frame budget is the tight one).

## 6. Design question 5: stop as Halt

    final private[kernel] class Halt(
        val entry: Handlers.Entry,   // owning stop entry, compared by eq
        val input: Any,              // the raw operation input
        val frame: Frame
    ) extends Kyo[Nothing, Any]:
        def map[B, S2](f: Arrow[Nothing, B, S2]): B < S2 = this

Assessment of the encoding, piece by piece:

- `Kyo[Nothing, Any]` makes `Halt` assignable to every `B < S` with no cast,
  and `map` returning `this` is well-typed under the variance. Every
  `kyo.map(arrow)` site in every mapLoop then discards pending work by
  construction, zero allocation, zero new code in the fused paths. This is
  the whole argument for Halt-as-Kyo and it holds.
- Carrying the INPUT, not the applied clause result, is right for three
  independent reasons: the clause's result type is the region's type, which
  no local frame can consume; the clause may itself raise `E` again and must
  be re-dispatched by the owning loop (pinned: "a stop replacement may
  suspend on the same effect and stops again", ArrowEffectTest.scala:445);
  and running the clause at the boundary makes stop's clause scope trivially
  the boundary's own scope, no bracket needed.
- Arms: the stop trampoline adds
  `case h: Halt if h.entry eq entry => stopLoop(f(h.input), next, ambient)`.
  All five trampolines add the pass-through arm `case h: Halt => h` BEFORE
  the settled arm (a foreign region crossed by a Halt is discarded wholesale;
  its `next` must not run). `eval` and `evalPartial` add
  `case h: Halt => throw new IllegalStateException(...)`: an unowned Halt at
  the boundary is a defect, and with the invariant below it is unreachable
  except through the snapshot hazard, which the downgrade rule closes.
- Defer interplay: a Halt is never wrapped in a Defer (the budget arm guards
  settled applications; Halt takes the kyo arm, whose `map` is identity). A
  Defer whose deferred walk produces a Halt returns it into the stepping
  loop's dispatch, where the ownership arms apply. No special case.

The enclosure invariant (what makes Halt sound): under wrap-style rotation,
an entry is present in the FLOWING evidence if and only if its trampoline's
frame encloses the current walk on the call stack. Initial trampoline calls
extend for walks made inside the call; wrap-rotation re-entries re-enter the
loop around the remainder walk; nested re-entries nest their frames in region
order. A Halt unwinding by ordinary returns therefore always passes through
its owning loop's frame. This invariant is why the ownership check can be a
plain `eq` and why no search or targeting machinery is needed.

The one violation class: SNAPSHOTS. Evidence captured into a value that can
be applied after the capturing frames are gone (handle's continuation closure
handed to the clause, which may escape as a first-class multi-shot value; a
resume entry's `scope` replayed by a clause bracket inside an escaped
replay). A stop entry in a snapshot can mint a Halt whose owner frame no
longer exists, and the continuation it discarded cannot be reconstructed, so
there is no fallback. The rule that closes it:

    Flowing evidence may carry stop entries. Snapshotted evidence may not:
    every capture (cont closures, entry.scope) downgrades STOP entries to
    BARRIER at snapshot time.

A downgraded stop falls back to structural travel, which is exactly today's
semantics for an escaped continuation: the suspension bubbles to whatever
live handler exists at replay time. Resume entries are self-contained (clause
plus scope, no live frame needed) and barrier entries are inert, so both
survive snapshots unchanged. `downgradeStops` copies only when a stop entry
is present, so the common capture is a reference pass.

## 7. Design questions 6 and 7: fallback composition and interactions

The trampolines remain the general mechanism; evidence is an optimization
layer for resume and stop plus a correctness layer for cross-kind shadowing.
Composition audit, path by path (all under wrap-style rotation; several of
these are exactly where append-rotation fails):

- Pre-built suspension at handle entry: the trampoline's matched arm answers
  it, evidence uninvolved. Unchanged.
- Evidence hit during a walk: answered or halted at the site, continuing in
  the same fused mapLoop.
- Miss during a walk: one attach event captures the remaining chain into the
  suspension, which unwinds to the enclosing loop frames; foreign loops
  wrap-rotate; the matching loop answers. Identical to today.
- Answer feeding after an outer hit: the suspension's continuation begins
  with the wrap-rotations of every region between its origin and the current
  site; feeding the answer re-enters them innermost-first, re-extending
  evidence before interior code runs. Interior scope is re-established
  structurally, with no captured environment.
- Defer bounces: each trampoline's Defer arm steps the deferred continuation
  under its own extended evidence; escaping Defers carry wrap-style
  re-entries; eval steps with empty evidence and the chain re-arms itself.
  The mapLoop attachments between trampolines are pure arrows carrying no
  scope, and that is sound precisely because ALL scope is carried by the
  re-entry wrappers (under append-rotation it is not sound, section 2).
- Fiber boundaries: evidence starts empty at every eval entry because it is
  a parameter. A parked computation resumed on another thread or fiber
  re-arms from its own structure. Nothing to clear, nothing to leak.
- Multi-shot: a replayed continuation re-runs its wrap-rotations, each replay
  re-extending from its own ambient (p6, and the existing multi-shot pin at
  ArrowEffectTest.scala:283). The snapshot downgrade rule covers the stop
  entries it captured.
- Pre-built chains run later under different handlers: the value carries no
  evidence; each execution's parameter supplies its own. Correct by
  construction, and the honesty benchmark row pins that pre-handler
  construction cost is unchanged.

Hole search result: the one genuine hole found is the append-rotation defect
itself, which the fallback path inherits today with or without evidence.
After the rotation fix I could not construct a program where the two paths
disagree; the dual-path acceptance programs (section 10) exist to keep it
that way.

## 8. Design question 8: cost model and gates

What the fused rows pay:

- One parameter through `Transform.apply` and the mapLoops: register
  traffic, expected noise. Gate: evalFixedOverhead, fusionAllocatesNothing,
  fusionPastBudgetPaysRescuesOnly, deepRecursionPaysRescuesOnly unchanged.
- The kyo arm grows by one `eq`-against-empty branch, and the non-empty case
  is OUTLINED into the static `Handlers.dispatch` helper, so the inline
  mapLoop body stays at today's size (one static call replacing one virtual
  `kyo.map` call in that arm). This is the FreqInlineSize answer: the
  continuation-entry mapLoops already sit at 377 bytes against the 325
  budget (kernel2-jit-morphism-report.md); the evidence path must not add
  inline bytes, and outlining achieves that. Gate: continuationBodiesFuse,
  trailingMapsStayLinear unchanged.
- Programs under handle-kind regions now run with non-empty evidence
  (barrier entries), so every bubble event pays one scan: `eq`-first,
  approximately 2ns per depth at realistic depths (E2b), on a path that
  already allocates an attachment. Gate: suspensionBaseline,
  suspensionFusesContinuation, idleHandlerAddsNothing within noise;
  foreignCrossingsPayRotation at parity (wrap-rotation allocates the same
  two objects per crossing as append-rotation).

What the suspension rows gain:

- resumeAnswersInPlace (currently ~85us, ~640KB per iteration): every `ask`
  is answered inside the per-site mapLoop with no attachment, no unwind, no
  trampoline dispatch. Expected direction: time toward the fused-chain rate
  and allocation toward the Defer-rescue floor (the Safepoint bounces plus
  boxing). E1's measured ceiling for this exact shape was ~15x time and two
  orders of magnitude allocation.
- sharedHandlerPaysDispatch (2.79x at 16 sites): UNCHANGED, deliberately. It
  pins the handle-kind trampoline limit, which remains real. The claim the
  new design makes is a new row: sharedResumeAnswersLocally, the same 16-site
  program under `ArrowEffect.resume`, expected at approximately 1x of the
  single-site row because each site answers in its own bytecode with its own
  receiver profile. This row is the direct test of the megamorphism thesis.
- New stop rows: deepStopHaltsWithoutContinuation (a stop operation raised
  under N frames of live walk: allocation collapses from O(N) attachments to
  approximately zero) and stopPreBuiltChainUnchanged (the honesty row: a
  chain built before the handler exists pays today's cost, evidence cannot
  reach construction).
- Miss-cost row: foreignBubbleUnderEvidence (a foreign operation bubbling
  through frames inside registered regions), gating the scan-on-miss cost
  and the `<:<` fallback frequency.

## 9. Verdicts on the tentative positions

1. First-touch (b) over mint-time (a): AGREE, strengthened. (a) is not just
   awkward, it is disqualified (thread-local, value-semantics change,
   near-zero residual benefit). (b)'s "cannot lexically receive the
   parameter" concern dissolves once you see that the pending-value handoff
   delivers every suspension to parameterized machinery within one return.
2. Register ALL kinds, fast-path flags on resume/stop only: AGREE. The
   barrier entries are the shadowing rule; nothing cleaner exists that keeps
   innermost-wins structural.
3. Stop as Halt-with-identity-map carrying the raw input, clause at the
   boundary: AGREE, with one addition the position did not name: the
   snapshot downgrade rule (section 6). Without it, escaped continuations
   and clause-bracket replays can mint orphaned Halts, and there is no
   recovery from a discarded continuation. With it, the encoding is sound
   across all loops, Defer interplay, and (future) guards.
4. Evidence representation from E2b (one final array-backed class,
   innermost-last, eq-first tags): AGREE, inherited unchanged. One addition:
   the entry is one final class with an int kind, not a sealed hierarchy,
   so the scan and the kind dispatch stay monomorphic.
5. Five trampolines stay; evidence consulted in an outlined helper from the
   mapLoop kyo arm: AGREE, with the correction that the trampolines' foreign
   and Defer arms must first move to wrap-style rotation (section 2), which
   changes their bodies but not their number, their names, or their public
   signatures.

## 10. Invariants (the semantic rules, stated for the test suite)

1. Scope: a clause runs outside its own region, under the evidence captured
   at region entry extended with the region itself; the remainder after an
   answer runs under the evidence flowing at the answer site.
2. Enclosure: an entry is in the flowing evidence iff its trampoline frame
   encloses the current walk. (Consequence: Halt unwinds always meet their
   owner; consequence: rotation must be wrap-style.)
3. Snapshot: flowing evidence may carry stop entries; snapshotted evidence
   (continuation closures, entry scopes) carries them downgraded to barrier.
4. Shadowing: the innermost matching entry decides, and barrier entries make
   every non-fast-path region visible to that decision.
5. Fallback: a miss produces exactly today's structural suspension; the
   evidence and trampoline paths compute the same results for every program
   (dual-path property).
6. Values: a Nested-boxed computation is never consulted, answered, or
   halted; evidence acts on executing suspensions only.
7. Fork: evidence starts empty at every eval entry; only structure crosses.
8. Budget: in-place answering depth is bounded by the Safepoint Period; the
   Defer unwind restores flat stack.

## 11. Hazards, ranked

1. Append-rotation soundness hole (live today): lost handler, unhandled
   suspension throw at eval in deep-crossing programs (section 2 program).
   Fix first, reproduce-first.
2. Orphaned Halt via snapshot evidence: IllegalState at eval or a halt
   consumed by the wrong region in multi-shot / escaped-continuation
   programs. Closed by the downgrade rule; needs its own pins.
3. Inline-budget regression: fattening the mapLoop kyo arm inline would
   regress every fused row. Closed by outlining; gated by the unchanged-row
   list.
4. Clause-bracket scope errors: p9-analog reds (post-park clause reads
   resolving at site scope, or the post-answer remainder resolving at clause
   scope). Closed by e.scope in the bracket; pinned by the p9 program.
5. Stack overflow from in-place answering on JS at deep recursion: bounded
   by Period but the constant matters; pin at depth well past Period on all
   platforms.
6. Scan-relation drift: evidence matching by `eq` only would break subtype
   effects that the trampolines answer via `<:<` (askSub). The scan must be
   eq-first with the structural fallback, and a dual-path subtype pin must
   exist.
7. Megamorphism migrating into the dispatch helper (its cont-application and
   clause-application sites see every mint class): expected to be one
   virtual call per answered operation, against the many the trampoline path
   pays; gated by sharedResumeAnswersLocally.
8. Allocation creep from per-re-entry resume entries: same order as the
   rotation allocation it rides on; gated by foreignCrossingsPayRotation and
   the state rows.

## 12. Acceptance programs

The 11 reference programs p1 through p11 from
kernel2-rotation-handlers-design.md section 4a, as suite tests with their
old-kernel-validated results (p1 7, p2 200042, p3 -1, p4 escape, p5 -7,
p6 (6,15,25), p7 1110, p8 16, p9 8003, p10 105, p11 (8,641)), plus:

12. The rotation-law pin: `say.map(_ => ask).map(_ + 1)` under nested
    handlers (section 2). Must fail on current code, pass after the fix.
13. The Defer-arm analog: the same shape where the crossing is a budget
    Defer instead of a foreign suspension (drive past Period inside the
    region, then raise the handled tag behind a trailing transform).
14. Dual-path equality: for each of p1 to p11 that terminates, the same
    program under handle-kind (trampoline path) and under resume/stop
    evidence where the kinds permit, asserting identical results.
15. Cross-kind shadowing: inner `handle` for tag T inside outer `resume` for
    T, operation raised inside the inner region after a foreign park and
    resume; the inner handler must answer. And the mirror with `stop` outer.
16. Halt crossing foreign trampolines: stop region containing two foreign
    regions and interleaved maps; raise the stop op innermost; assert the
    outer regions' remainders and `next` chains never ran and the stop
    clause result is the value.
17. Stop replacement re-suspends: exists (ArrowEffectTest.scala:445); add
    the evidence-path variant (op raised deep in a walk, not at the fold
    surface).
18. Clause parks mid-clause under the new encoding: p9 restated with the
    bracket, plus the variant where the clause's post-park segment raises
    the region's own effect (deep self-extension through the bracket).
19. Escaped continuation with an outer stop region: capture via a handle
    clause, apply after the handlers returned; ops of the stop-handled tag
    in the replay must travel structurally (no Halt), reaching whatever
    live handler wraps the replay, or eval's unhandled-suspension defect.
20. Snapshot honesty: a computation built inside a resume region, stored
    (Nested), and evaluated later under a different resume handler answers
    with the LATER handler.
21. Subtype dual-path: askSub answered by a resume evidence entry registered
    with Tag[Ask].
22. Deep in-place recursion at 100k on JVM, JS, and Native (the Period-bound
    stack pin).

## 13. Open questions for the maintainer (each with a recommendation)

1. Land the wrap-rotation fix as its own change before the evidence work?
   Recommendation: yes. It is a live soundness defect with a one-program
   reproduction, its fix is allocation-neutral, and the evidence design
   depends on the invariant it establishes.
2. Name and visibility of the parameter and collection: `Handlers` as you
   named it, `private[kernel]`, one final array class, entry as one final
   class with an int kind. Recommendation as stated; the only real choice is
   the name.
3. The snapshot downgrade rule trades away in-place stop hits inside
   handle-clause resumptions (they fall back to structural travel) for
   soundness of escaped continuations. I see no sound alternative short of
   liveness tracking, which is machinery this design should not grow.
   Recommendation: accept the rule.
4. loop-kind stays trampoline-only (no stateful fast path), because the
   state-forks-per-invocation pin is semantics, not implementation.
   Recommendation: keep; revisit only if a profile shows loop-kind travel
   dominating a real workload.
5. `partial` registers nothing. Recommendation: confirm; it follows from
   partial not delimiting a region, but it is a semantic reading worth your
   sign-off.
6. Whether `Halt` is the right name (alternatives: `Stopped`, `Discard`).
   Recommendation: `Halt`; it names the execution behavior, not the handler
   format.
