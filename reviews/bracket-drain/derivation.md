# Derivation: owed dumps and the bind step

The two live leaks from `reviews/proto-migration/sync-bracket-review.md`, closed with the
region scoping ruled during the design session, plus the rulings that reshaped the work
mid-flight. Rulings of record, verbatim:

> "once the handling scope ends we must release no?" (2026-09-01, on when a dropped
> capture's obligations drain: at the answering region's exit, not at eval end)

> "Pleae please please use type safe code as much as possible. If you see an Any, take a
> step back and review. Minimize the change as much as possible." (2026-09-01, standing)

> "maybe we need a done function? for the hanlder" (2026-09-01, on the bracket's
> completion edge: eval-owned like release, not an exit map in the body)

> "why isn't it maintained automatically on dump?" (2026-09-01: the owe attachment lives
> inside `Stack.dump`, so no caller can produce an unowed dump)

> "can we use Chunk instead of List?" (2026-09-01: owed collections are `Chunk`,
> newest last)

> "why do we need Obligations? did you really introduce it just becuase of the cast!? ...
> this is HOT CODE" (2026-09-01: no carrier type for the release walk; the flat pair
> buffer with the one erasure-forced cast in `released` is the sanctioned shape)

> "remove Discarded, take the exception as a param" (2026-09-01: no dedicated signal
> class; drains take the throwable, discard sites mint a `KyoException` behind their
> empty-checks)

> "how about we move Sync.acquireReleaseWith to Effect.bracket? so we can keep all these
> tests in the kernel itself ... without a Sync prototype" (2026-09-01)

> "review the changes for possible simplifications. A main concern I have is the extra
> code in the eval loop degrading performance ... Note that just increasing the bytecode
> size of Eval is already risky" (2026-09-01: the hot-path pass; measured in review.md)

> "takeRoot sounds odd to me ... make sure naming of these methods is clear"
> (2026-09-01: the naming pass; evalOwed, takeEvalOwed, owe, oweBelow, rebound)

> "bracket must be bullet proof" (2026-09-01: the interaction and finalizer-failure pin
> sweeps)

> "no benchs for now please" (2026-08-31, the standing park during the design session)
> lifted by "how about you launch the benchamarks in parallel?" (2026-09-01): the A/B
> screening runs in review.md

## The equations

**Owed dumps.** A crossing into the region at `idx` dumps the regions above it; the dumped
snapshot rides the captured continuation, which is region-`idx` currency: it can only be
resumed from that region's state, body, or clauses. The region's exit is therefore the
delimiter of every possible resumption. A drop must behave as an abandonment of the dumped
remainder at the moment the answering region exits:

    Eval.release(remainder, discarded)   scoped to the region's exit

`Eval.release` is already the abandonment law (innermost first, suppression onto the
signal, claimed cells no-op). The eval realizes the scoping by making the answering entry
*owe* the snapshot its dump produced, attached inside `Stack.dump` itself: the entry's
exit, on every path the eval owns, drains what it owes. Two boundary cases keep the same
equation:

- An entry that leaves the stack while its logical extent continues as values (the
  LoopHandler effectful-clause pop, where the region is re-created fresh by the outcome
  dispatch, and a resumed park's remainder) re-homes what it owes below itself
  (`oweBelow`): everything reachable from the dissolved extent is reachable only through
  the extent below. Only when nothing is below does the obligation land on the eval
  itself (`evalOwed`, kept on the pooled stack so the nested eval functions do not lift a
  local into a per-eval box), draining at the eval's end and riding a safepoint park as
  `Kyo.Park.owed`.
- A dump packed by a park or by an enclosing dump travels with its entry, because the
  snapshot layout carries the owed slot beside handler, state, and continuation. The
  abandonment walk drains owed slots wherever it already reaches entries.

**The bind step.** The settle-to-install leak existed because the bracket installed its
region through `map`, and `map`'s budget gate can defer a *settled* acquire against the
install (`Pending.scala:24`, drained slot per `Safepoint.resolve`). The law is the one the
settled arm already obeys, extended over the gate: a settled value's next arrow applies
strictly. `Arrow.Bind` applies immediately on settled input and defers pending input,
exactly like every arrow, minus the `Safepoint.enter` check. With the bind chained
directly after the acquire, no gate exists between the resource-producing thunk and the
region install on any delivery path; the thunk's own gate fires *before* the thunk, so a
stop landing mid-acquire parks an extent that owes nothing yet. Main's `BindingStep` is
the precedent. No eval change; no guard, no packer, no livelock surface.

**The done edge.** The bracket's completion was an exit map in the region's body; safe
(the map defers inside the live region) but value-owned while the other three edges were
eval-owned, and one Transform plus a gate per bracket. `ContextHandler.done(state)` makes
completion the fourth eval edge: the settled context pop fires it in the same slice that
pops the entry, so nothing can park between the body settling and the completion, by
construction. The `ContextEffect.handle` settled fast path fires `done(derive(Absent))`
so a pure `use` cannot strand the obligation, and `Effect.bracket` guards the one window
the eval cannot own: a `use` that throws during application, before the region exists.

## The bug the pin sweep surfaced: stale context across crossings

Writing the contract pins exposed a third leak-class defect, confirmed red before the
fix: a crossing dumped the regions above `idx` but left their bindings in the context
map, so a clause reading a `ContextEffect` bound inside the body saw the inner value
where its row places it outside (the settled pop downdates carefully; the dump path did
not). The fix hoists the dump eagerly for every non-top crossing (all paths but the
loop-done arm already forced it) and downdates the context from the dumped entries,
mirroring the settled pop. That unification also made the non-top loop-done arm identical
to the top arm (the discarded regions are in the fresh dump, drained through the owed
slot), deleting `dropRegions` and the dead topness check.

## Pieces and their counterparts

| piece | counterpart already in the kernel |
|---|---|
| owed slot per region | the snapshot triple gains a fourth slot; `Stack.Snapshot` is the one home for the layout |
| owe at dump | inside `Stack.dump`: the entry below the dumped run keeps the pointer, structurally |
| drain | `released(handler, state, ex)` walked innermost first, the existing release law |
| discard signal | a `KyoException` minted per drain at the exit site, behind its empty-check |
| re-home on a dissolving extent | `oweBelow`: chunk concat onto the entry below, or `evalOwed` when nothing is below |
| eval-owed carriage across parks | `Kyo.Park.owed`, default empty; the park already carries the entries |
| bind | `Arrow.Step` minus the budget gate; the settled arm's strictness as an arrow |
| completion | `ContextHandler.done`, the settled context pop's edge, like release on the death edges |
| context rebind at the crossing | `rebound`: the settled pop's rebind, applied to the dumped run |

No new node kind. The crossing dispatch splits into a lazy top tier and an eager non-top
tier: eight handler arms across two dispatches where the base had five in one, with
`dropRegions` deleted; measured bytecode-neutral against the unsplit shape while the hot
top trace runs the pre-change lazy code (the loop table in review.md). No registry: the
owed chunks live in the stack and the snapshots the dump already builds, and uniqueness
stays in the `Cell`.

## Surface (as built)

- `Arrow.scala`: `Arrow.Bind` (private[kyo], gate-skipping apply).
- `Effect.scala`: `Cell`, `Finalize`, `bracket` on the companion; the `Sync` prototype
  deleted.
- `KyoInternal.scala`: `Kyo.Park` gains `owed: Chunk[Stack.Snapshot] = Chunk.empty`.
- `Stack.scala`: always-present `owed` parallel array (chunks, empty-filled, no nulls)
  and `evalOwed`; `pop` hands back what the entry owes; `takeOwed`, `owe`, `oweBelow`,
  `takeEvalOwed`, `owedOf`; `dump` attaches its snapshot to the entry below; snapshot
  stride 4 with an `owed` accessor and an `empty` constant; `contextual()` and the
  Builder emit empty owed slots (bindings fork, obligations do not).
- `Eval.scala`: the crossing arm split into its lazy top tier and the eager non-top tier
  (dump via `dumped`, context via `rebound`); drains at every entry exit through
  `drainDiscarded` and `drainOwed`; `oweBelow` at the pending pops and the Park arms
  (a resumed park's owed re-homes below the installed run); `takeEvalOwed` at the park
  transfer, the completion, and the unrecovered throw; `Eval.release` expands owed slots
  and the park's owed; shared expand helpers keep the in-eval drains and the abandonment
  walk on one order.
- `Handler.scala`: `ContextHandler.done`; the at-least-once wording on both hooks.
- `ContextEffect.scala`: `done` parameter (defaulted, beside `release`); the settled arm
  fires `done(derive(Absent))`; the contract scaladoc; the pair overload names its
  `release` argument so the new defaulted parameter cannot misbind.
- `EffectBracketTest.scala` (new, kernel package), `EvalTest.scala` additions;
  `Sync.scala` and `SyncTest.scala` deleted; a boundary comment on `Eval.released`.

Hot-path deltas, each named for the bench pass (parked by the user's standing
instruction, so all unverified):

- Defer arm, Handle arm, `answers` fast loop, `push`: untouched.
- settled arrow exit: one owed-slot read plus branch (`pop` returns the chunk).
- settled context exit: the same read plus one megamorphic `done` call (no-op default).
- loop-done exits: one owed-slot read plus branch; no allocation when nothing is owed.
- `ContextEffect.handle` settled fast path: `done(derive(Absent))` inline; dead-code
  elimination expected for the no-op default, required work for a bracket.
- non-top crossings: the dump is now eager on the loop-done path too, and the downdate
  walk runs per crossing (type tests only when no bindings were dumped).

## Pins (all green, 1546/1546 JVM at the tip)

Reproductions that were red first: the dropped capture, the settled-acquire strand (the
manufactured `DeferWith` shape observed in the failure), the loop-done discard, and the
stale-context read. Contract pins: pure-use settled fast path, use-throws-during-
application, park-after-crossing-resume owing through packed slots, effectful-clause
resume completing (no premature drain), effectful-clause done draining through the eval
root, sibling dumps draining newest first, the raw-hook at-least-once double-fire, and
drain promptness observable at the answering region's exit.

## Open, deliberately

- The `Spent` re-entry refusal (review section 5) is the user's open decision; the
  pass-through pins document the chosen-until-ruled semantics.
- Whether `release` on `ContextEffect.handle` stays user-facing: the reachability law now
  supports it (discussed 2026-09-01, leaning yes).
- Benches parked; the rows above are the gate list when they resume.
