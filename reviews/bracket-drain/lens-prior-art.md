# Lens: the landed bracket against the prior art

A held-out semantics review of the landed `Effect.bracket` against the research this project
compiled: `reviews/proto-migration/bracket-survey.md` (the cross-library survey),
`state-on-failure-survey.md`, and `sync-bracket-review.md` (the held-out review of the
earlier iteration). Ground truth for the landed semantics is the pins
(`kyo-kernel/shared/src/test/scala/kyo/proto/kernel/EffectBracketTest.scala`, 28 of them,
plus the owed-dumps block at `internal/EvalTest.scala:978-1052`), then the code
(`Effect.scala`, `internal/{Eval,Stack,Handler}.scala`, `Arrow.scala`,
`ContextEffect.scala`), with `reviews/bracket-drain/{derivation,review}.md` as the design
record. Every property below states what the surveyed systems do, what landed, whether the
divergence is recorded, and whether anything needs a ruling.

Verdict in one line: the landed semantics is prior-art-sound on every closed property; the
two genuine concerns are exactly the two the design record already holds open (the silent
discard-drain swallow and the spent-extent pass-through), and one divergence from the
masked-acquire consensus (compound acquires) is pinned but not narrated anywhere a user
would read it.

## 1. Acquire interruptibility and the no-mask design

**Surveyed.** All four masking systems (cats-effect 3, ZIO 2, Effect-TS, GHC) make the
acquire uncancelable by default because an interrupt can land mid-acquire; ZIO alone offers
an interruptible-acquire tier, paying by denying the release the resource. The survey's
implication (a) frames the incumbent's alternative: refuse the park instead of masking, and
the open question becomes whether an acquire spanning a park should be abandonable.

**Landed.** No mask, and the acquire is abandonable before it settles. The pin "the acquire
is not guarded before it settles" (`EffectBracketTest.scala:142-154`) runs a two-slice
acquire (`Effect.defer { requestStop(); Effect.defer(7) }`), parks it mid-acquire, abandons
the park, and asserts the release does not fire (`count == 0`); resuming completes and
releases once. The kernel's bookkeeping is consistent: the resource does not exist until
the acquire's final thunk settles, and settle-to-install is one slice by construction
(section 2), so a parked mid-acquire extent genuinely owes nothing
(`derivation.md`, "a stop landing mid-acquire parks an extent that owes nothing yet").

**Judgment: sound under this interruption model, with one narrated gap.** Nothing can
interrupt inside a slice, so a single-thunk acquire (the common shape) is atomic with its
install; the state masking exists to protect (resource exists, finalizer not installed) is
unreachable rather than guarded. That is a stronger property than the mask gives, inside
this model. The residual divergence is the compound acquire: in all four masked systems a
multi-step `before` cannot be pierced by cancellation at all, while here a multi-deferral
acquire can be abandoned between its slices, leaking whatever side effects the earlier
slices performed. The kernel-correct answer is nesting brackets per step, which is
compositional, and CE3's own unmasked tier documents the identical stance ("the acquire
action should know how to cleanup after itself in case it gets canceled",
`Resource.makeFull`). But the landed record carries this only as the pin: neither
`Effect.bracket`'s scaladoc (`Effect.scala:41-46`) nor the design docs state that a
multi-slice acquire is abandonable without release. See concern 3.

## 2. The settle-to-install edge

**Surveyed.** One mask spanning acquire and finalizer installation, with the interrupt
check gated on the mask (CE3 `masks == 0`, ZIO `uninterruptible` around
`acquire.tap(addFinalizerExit)`, Effect-TS, GHC). Kotlinx has no guarantee here (issue
#1936's lost-resource hazard). Main-branch kyo refuses the park via `BindingStep`.

**Landed.** `Arrow.Bind` (`Arrow.scala:90-105`): applies immediately on settled input with
no budget gate, defers pending input like every arrow. `Effect.bracket` chains it directly
after the acquire (`Effect.scala:70`), and the region install (`ContextEffect.handle`
wrapping `use(a)`) happens inside `Bind.apply`, in the same slice the acquire settles. The
pin "a stop landing as the acquire settles still installs the region"
(`EffectBracketTest.scala:116-128`) is the exact reproduction the earlier held-out review
demanded (its section 3, "the settled variant is the leak"): release fires exactly once on
abandonment, and a resumed remainder does not double-fire.

**Judgment: matches the strongest surveyed guarantee, by the incumbent's own mechanism.**
This is main's `BindingStep` precedent carried over, and the earlier review's livelock
concern is structurally impossible here because no site declines: the Bind never defers a
settled value, so there is no decline/re-defer cycle. I verified the reachability argument
in the landed code: every constructor of a `Defer` node with a settled value puts a gated
step, never the Bind, at its head (Bind defers only pending input,
`Arrow.scala:100-102`), and a `Defer` parked with the Handle node in its value spine is
still reached by `Eval.release`'s spine walk (`Eval.scala:33-44`), so a region that exists
only as a value is still drained on abandonment. No divergence; recorded
(`derivation.md`, "The bind step").

## 3. The completion edge

**Surveyed.** CE3's mask spans the `flatMap` into `guaranteeCase`, so no cancel can land
between the body settling and the completion finalizer being wired; ZIO's scoped form puts
the `ReleaseMap` transition inside the same uninterruptible region.

**Landed.** `ContextHandler.done` (`Handler.scala:86-89`) fires at the settled context pop
in the same slice as the pop (`Eval.scala:416-427`); the `ContextEffect.handle` settled
fast path fires `done(derive(Absent))` so a pure `use` still completes
(`ContextEffect.scala:152-156`, pinned at `EffectBracketTest.scala:41-46`); and `use`
throwing during application, before the region exists, drains at the one window the eval
cannot own (`Effect.scala:55-61`, pinned at `:48-56`).

**Judgment: the same window the masks close, closed by slice atomicity.** Recorded
(`derivation.md`, "The done edge", from the user's "maybe we need a done function?"
ruling). No divergence.

## 4. Release exactly-once and its division

**Surveyed.** CE3 deregisters by popping a fiber-owned stack; ZIO and Effect-TS CAS a
region state machine; main-branch kyo puts an `AtomicBoolean` on the kernel's `Finalizer`.
Every surveyed user-facing finalizer is exactly-once.

**Landed.** The kernel guarantees at-least-once reachability per edge and hooks may fire
more than once (a shared dump, a fork's state, a merged shadow region); exactly-once
belongs to the state, which for `Effect.bracket` is the `Cell`'s CAS
(`Effect.scala:26-29`). The contract is stated on `ContextEffect.handle`'s scaladoc
(`ContextEffect.scala:111-118`) and on `ContextHandler.release`
(`Handler.scala:91-97`), and the raw-hook double-fire is pinned
(`EvalTest.scala:1024-1038`, `log == List(7, 7)`), exactly as the earlier held-out review
recommended (its section 2: accept, document, pin). Exactly-once through the cell is
pinned at `EffectBracketTest.scala:58-63` and across two parks at `:350-372`.

**Judgment: ZIO's shape with the CAS relocated from the runtime to the state; recorded and
chosen.** The one caveat rides open question 3 of `review.md`: if `release`/`done` on
`ContextEffect.handle` become user-facing, an at-least-once finalizer hook has no surveyed
precedent (every system's user tier is exactly-once), so the documentation carrying the
contract becomes load-bearing. See concern 5.

## 5. Release-failure semantics, per edge

**Surveyed.** Body error wins with release suppressed onto it: Java convention and
main-branch kyo (with an identity guard). Both aggregated into one cause: ZIO, Effect-TS.
Side-channel report: CE3 (the survey's implication (c) calls this "no constituency", it
loses the error to a logger). Silent replacement: GHC base (same verdict). The survey's
recommendation was suppression-onto-the-body's-error.

**Landed, per edge:**

- **Completion**: a throwing release fails the computation and the outer bracket releases
  with that failure (`EffectBracketTest.scala:280-289`; the throw from `done` propagates
  into `recovered`). Matches the incumbent's completing-path behavior and ZIO's
  release-only-cause surfacing.
- **Unwind**: suppressed onto the in-flight failure with the identity guard
  (`Eval.scala:148-153`, `case t if NonFatal(t) && (t ne ex) => ex.addSuppressed(t)`;
  pinned at `:291-300`). Exactly the survey's recommendation and the JVM convention.
- **Abandonment**: suppressed onto the holder's signal (`EffectBracketTest.scala:302-315`).
  A consistent extension of the same convention; no surveyed system has this edge.
- **Discard drain**: suppressed onto a per-drain internal `KyoException("remainder
  discarded")` which is then dropped, so the failure is swallowed silently
  (`Eval.scala:120-124`; pinned only as "does not starve the ones after it",
  `EffectBracketTest.scala:264-276`, where `Bad` vanishes and the outer release still
  runs). The completion-path drain of leftover `evalOwed` (`Eval.scala:516-521`) uses the
  same silent signal.

**Judgment: three edges match the convention the survey recommends; the fourth is the
project's one choice with no prior-art constituency at all.** Every surveyed system
surfaces a finalizer failure on a non-failing path somewhere: CE3 to the EC reporter, ZIO
and Effect-TS into the exit `Cause` the joiner sees, Java onto the propagating throwable
(and on Java's non-failing path the release failure simply propagates). The landed discard
drain surfaces it nowhere. `review.md` open question 2 already holds this open; the
prior-art weight says the ruling is needed. See concern 1.

## 6. The outcome payload

**Surveyed.** Nothing (GHC bracket, Eio); a three-way ADT (CE3 `Outcome`, ZIO/Effect-TS
`Exit`/`Cause` with interruption as a constructor); a nullable cause (kotlinx
`Throwable?`). The incumbent: `Result` with a distinguished `Finalizer.Abandoned`, argued
in its source as "a case, not a failure". The survey notes no surveyed system passes a
full outcome to a *pure* hook.

**Landed.** `release: (A, Maybe[Throwable]) => Unit` (`Effect.scala:47-49`): `Absent` for
completion, `Present(ex)` for all three deaths, where `ex` is the unwind failure, the
holder's arbitrary abandonment signal (`Eval.release(v, ex)`), or the minted
`KyoException` on discard. This is the kotlinx shape, not the incumbent's.

**Judgment: recorded and blessed, with a forward-looking note.** The payload collapse was
in the earlier design's rejected-designs record and the held-out review called it sound
(its section 4); the "remove Discarded, take the exception as a param" ruling is verbatim
in `derivation.md`. So this is justified-by-recorded-design. The note: because the
abandonment signal is caller-chosen, there is no canonical marker for "this was
abandonment" the way ZIO's `Cause.Interrupt` or kotlinx's `CancellationException` is
canonical. A release hook that wants to distinguish cancellation from failure has nothing
reliable to match on at the kernel tier. That is fine for the kernel (main's `Sync.ensure`
narrows to `Maybe[Error]` publicly anyway), but the concurrency layer above will need to
standardize its signal throwable to recover the discrimination every ADT-camp system
provides. See concern 4.

## 7. Discarded and leaked continuations

**Surveyed.** Unrepresentable in CE3/ZIO/Effect-TS/GHC/kotlinx (no first-class captured
continuations). Main-branch kyo is the only precedent for the exact situation and it
*refuses*: `Spent` guards re-entry into a released scope, throwing at install. The earlier
held-out review (section 5) called dropping `Spent` "the unsound entry": pass-through is a
silent use-after-release, race-equal to main under concurrency (the CAS makes the release
unique but not ordered before a concurrent use) and strictly weaker sequentially.

**Landed.** Discard itself is fully closed: a dropped capture's regions release at the
answering region's exit (`EffectBracketTest.scala:158-168`; promptness pinned at
`EvalTest.scala:979-996`, the release observably precedes the next `map`), through the
owed-dump machinery (`Stack.scala:160-182` attaches every dump to the entry below;
`Eval.scala` drains at every exit the eval owns). The loop-done truncate leak from the
earlier review is fixed and pinned (`EffectBracketTest.scala:130-140, 213-225`). But a
*leaked* capture resumed after its region completed enters the spent extent and completes
silently: the pin at `:240-262` asserts `eval(leaked.get(1)) == 8` with the release still
at one fire, meaning `use`'s tail ran against a released resource and produced a
normal-looking value.

**Judgment: the reachability half is at or above prior art; the pass-through half is the
open soundness decision, correctly held open.** `review.md` open question 1 and
`derivation.md` "Open, deliberately" both record pass-through as chosen-until-ruled, with
the earlier review's cheap refusal (a re-entry hook on the Park install arm, Finalize
overriding to throw on a claimed cell) still on the table. Prior art offers exactly one
data point, main's refusal, and it is on the refusal side. See concern 2.

## 8. Multi-shot continuations over a bracket

**Surveyed.** None of the bracket-survey systems has multi-shot continuations; the
effect-handler languages in the state-on-failure survey do, but that survey covers state,
not resources, and OCaml's continuations are one-shot besides. There is no prior-art
answer to "a continuation captured inside an open region, resumed twice."

**Landed.** Release at first completion: the pin at `EffectBracketTest.scala:336-348`
resumes twice, gets `809`, and `outcomes == List(Maybe.empty)`, one completion. Both shots
share the cell because the capture was taken inside the open region; a replay of the whole
bracket would mint a fresh cell per application (the cell is created inside `Bind.apply`,
`Effect.scala:53`), which the earlier review endorsed as "the better shape under replay."

**Judgment: coherent with the cell-per-application design and deliberately pinned; no
prior art to diverge from.** The least-surprise note: the second shot's `use` tail runs
after the release, which is the same spent-extent surface as section 7 and inherits
whatever ruling concern 2 gets. An alternative with no surveyed constituency either
(release at last completion, i.e. refcounting) would change this; nothing in the record
suggests it was wanted.

## 9. Fork and join

**Surveyed.** The convergent structured-concurrency rule (survey section 9): the forked
child owns its own region, the enclosing region owns only the right to interrupt it; no
surveyed system lets a child's death run the parent's finalizer. Fork policy lives on the
value, declared by its author (ZIO/Effect-TS/Java/kotlinx/incumbent, unanimous).

**Landed.** `Finalize`'s author-declared policy: `fork = _ => Cell.inert` (a pre-claimed
cell, `Effect.scala:31-39`), `join = (parent, _, _) => parent`, so a child's death drains
a no-op and the merge's reference check skips installing anything
(`Isolate.scala:135-167`). The contextual snapshot leaves owed slots behind ("bindings
fork, obligations do not", `Stack.scala:109-131`). Pinned: the isolate inside a bracket
forks an inert obligation and the parent completes once
(`EffectBracketTest.scala:374-381`).

**Judgment: matches the convergent rule exactly, expressed through the kernel's own
per-value fork mechanism rather than wiring at the fork site.** The one thing the kernel
deliberately does not do, matching the incumbent: detect a child *using* the resource
after the parent's extent released it. The incumbent detects that at the `Scope` layer
("finalizer already closed"), not the kernel, and that layering is the survey's own
reading; nothing to rule here, but the Scope-equivalent layer above should keep the
detection when it is built.

## 10. Ordering

**Surveyed.** LIFO everywhere, by five mechanisms; parallel release is always a region
opt-in, never on the primitive.

**Landed.** Innermost-first on unwind (`EffectBracketTest.scala:104-114`, inner then
outer); newest-first sibling dumps (`EvalTest.scala:998-1022`, `List("b", "a")`);
obligations before their owner (`Eval.scala:67-90`, the expand order comment, verified:
each region appends before what it owes and the walk reverses); a bracket and a binding
dumped together release inner first (`EffectBracketTest.scala:319-334`). The primitive is
sequential; parallelism stays a region concern above, matching the field.

**Judgment: matches all eight surveyed systems. No divergence.**

## 11. Finalizer execution context

**Surveyed.** Effects, masked while running (CE3/ZIO/Effect-TS); the incumbent's kernel
primitive alone is effect-free (`Any < Any` run through a bare `Eval`), re-admitting
`Sync & Abort` at the `Sync.acquireReleaseWith` boundary above. Fork/join hooks are pure
in every surveyed system.

**Landed.** Stricter than the incumbent: `release` and `done` are pure `Unit` functions
run strictly in the eval (`Handler.scala:81-98`), because they must run where nothing is
installed to answer for them, including the abandonment walk over a dead value spine. The
`Sync` prototype that would have re-admitted effects was deleted by the user's own ruling
("move Sync.acquireReleaseWith to Effect.bracket ... without a Sync prototype",
`derivation.md`).

**Judgment: justified-by-recorded-design at this layer; the effectful tier is an owed
follow-up, not a defect.** Every surveyed system gives users effectful finalizers
somewhere; today no landed layer does. When the `Sync`-equivalent layer is rebuilt above
this kernel, the incumbent's discharge-at-the-boundary pattern
(`Abort.runWith(release(resource))(_.getOrThrow)`) is the precedent to follow. Flagged
only so it is not lost. See concern 6.

## 12. State through the bracket (state-on-failure survey)

The bracket introduces no interaction with the exit law's failed-extent lane: the
`Finalize` region's state is a claim (the cell), not user state, and the release hook on
the unwind path reads the entry's state before the pop (`Eval.scala:453-460`), so the
rollback-on-failure semantics the survey endorses for the proto's threaded context is
untouched. The region-exit drain scoping (the user's "once the handling scope ends we must
release" ruling) matches ZIO's `Scope`-closes-at-region-exit and CE3's
`Resource`-releases-at-use-scope-exit. No divergence.

## Ranked concerns

1. **Release failures on the discard drain are swallowed silently** (`Eval.scala:120-124`,
   and the completion-path `evalOwed` drain at `:516-521`). Zero surveyed systems choose
   silence on a non-failing path: CE3 side-channels to the EC reporter, ZIO and Effect-TS
   fold the failure into the visible `Cause`, Java lets it propagate, and the two
   behaviors the survey calls "no constituency" (CE3's logger, GHC base's replacement) are
   still louder than this. Already open as `review.md` question 2; the prior-art weight
   says it needs the ruling, and the pinned shape (`Bad` vanishing at
   `EffectBracketTest.scala:264-276`) should be re-pinned to whatever surfacing is chosen.
2. **Spent-extent pass-through is a silent use-after-release** (pinned at
   `EffectBracketTest.scala:240-262`; the multi-shot second shot at `:336-348` shares the
   surface). The only prior art for the exact situation is main-branch kyo, and it
   refuses (`Spent`); the landed design is race-equal to main but strictly weaker
   sequentially. Already open as `review.md` question 1, with the earlier review's cheap
   refusal hook still available. Needs the ruling.
3. **A compound (multi-slice) acquire is abandonable between slices with no release**
   (pinned at `EffectBracketTest.scala:142-154`). All four masking systems make the whole
   acquire atomic against cancellation; here only the final thunk-to-install edge is. The
   semantics is defensible (nest brackets per step; CE3's unmasked tier documents the
   identical user obligation), and it is the survey's implication (a) answered on the
   abandonable side, but the answer lives only in a pin: `Effect.bracket`'s scaladoc
   should state that the acquire is unguarded before it settles and that multi-step
   acquisition composes by nesting. A doc line or an explicit ruling, not a code change.
4. **No canonical abandonment marker in the payload.** The collapse from the incumbent's
   `Result` + `Abandoned` to `Maybe[Throwable]` is recorded and was reviewed sound, but it
   leaves the holder's signal arbitrary where ZIO/Effect-TS/kotlinx all have a canonical
   cancellation representation. The concurrency layer above should standardize its
   abandonment throwable so release hooks (and any future `ensure` narrowing) can
   discriminate; worth a line in whatever design doc opens that layer.
5. **If `release`/`done` on `ContextEffect.handle` go user-facing** (`review.md` open
   question 3, leaning yes): an at-least-once user finalizer hook has no surveyed
   precedent; every system's user tier is exactly-once. The contract is documented and
   pinned today; if exposed, the scaladoc's at-least-once sentence is the load-bearing
   defense and should survive any wording pass.
6. **No effectful release tier exists yet above the kernel.** Recorded consequence of the
   delete-the-prototype ruling, and correct at this layer; listed so the survey-backed
   expectation (every surveyed user tier admits effectful finalizers; the incumbent's
   boundary-discharge is the precedent) travels to the layer that will meet it.

## Explicit confirmations

- The no-mask design is sound under the park-only interruption model: the state masking
  protects (resource exists, no finalizer installed) is unreachable by construction, via
  `Arrow.Bind`'s gate skip, and I verified no constructor produces a parkable node with a
  settled resource ahead of its install; a region existing only in a value spine is still
  reached by `Eval.release`'s walk.
- The settle-to-install fix matches main's `BindingStep` precedent, and the earlier
  review's livelock scenario is structurally impossible (no decline site exists).
- The completion edge (`done` in the pop's slice, the settled fast path, the
  use-throws-during-application guard) closes the same window the surveyed masks close.
- The exactly-once division (kernel at-least-once reachability, state-side CAS) is ZIO's
  region-state-machine shape relocated, with the raw-hook double-fire pinned so the
  contract is chosen, exactly as the earlier held-out review recommended.
- Release-failure handling on the completion, unwind, and abandonment edges matches the
  JVM/incumbent convention the survey recommends, identity guard included.
- Ordering is LIFO at both levels, obligations before their owner, newest sibling first:
  in line with every surveyed system.
- `Cell.inert` realizes the convergent structured-concurrency boundary rule through the
  kernel's own author-declared fork policy; the child-escape detection correctly remains a
  Scope-layer concern per the incumbent's layering.
- Multi-shot release-at-first-completion is internally coherent, deliberately pinned, and
  has no surveyed system to diverge from.
- Drain promptness at the answering region's exit matches ZIO/CE3 region-exit semantics
  and the recorded user ruling.
- The three fixes since the earlier held-out review (loop-done discard, settle strand,
  at-least-once documentation and pin) each landed as that review specified or stronger.
