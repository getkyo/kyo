# kernel-conformance: reviews/bracket-drain, 3eb1c6991d..dc26bade37

verdict: BLOCKED

Two findings, both against claims the derivation makes that the tip does not satisfy. Neither
is a silent design substitution: every equation in `derivation.md` is realized in the code,
every piece maps to a value the kernel really has, and every touched file is inside the
declared surface. What fails is the derivation's own account of the change's footprint, which
is what the user reads as the authority during the live review.

## C1 The eval's arm count grew; the derivation says it did not

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:212-355`

derivation says:

> No new node kind. No eval arm added (one deleted, net). No registry: the owed chunks live
> in the stack and the snapshots the dump already builds, and uniqueness stays in the `Cell`.

code does: the `SuspendArrow` crossing is split into two tiers, each carrying its own full
`stack.handler(idx)` dispatch.

```
212  else if idx == stack.depth - 1 then
213      Debugger.onHandle(kyo, stack.handler(idx), stack.state(idx))
214      stack.handler(idx) match
215          case handler: Handler.ContHandler[IX, OX, EX, C, Y, S2] @unchecked =>
219          case handler: Handler.ContOpHandler[EX, C, Y, S2] @unchecked =>
228          case handler: Handler.LoopHandler[VX, IX, OX, EX, C, Y, S2] @unchecked =>
269          case handler =>
270              bug(s"unhandled: $handler")
272  else
...
300      stack.handler(idx) match
301          case handler: Handler.ContHandler[IX, OX, EX, C, Y, S2] @unchecked =>
305          case handler: Handler.ContOpHandler[EX, C, Y, S2] @unchecked =>
314          case handler: Handler.LoopHandler[VX, IX, OX, EX, C, Y, S2] @unchecked =>
353          case handler =>
354              bug(s"unhandled: $handler")
```

The base has exactly one such dispatch, with five arms
(`3eb1c6991d:.../Eval.scala:163-260`: `ContHandler`, `ContOpHandler`,
`LoopHandler if idx == stack.depth - 1`, `LoopHandler`, `bug`). The tip has eight arms across
two dispatches, plus the new `else if idx == stack.depth - 1` branch that separates them.

Under the other available reading of "eval arm", the top-level `loop` dispatch over node
kinds, the sentence fails on its other half: no top-level arm was deleted either. The claim
does not hold under either reading.

why it matters: the "Pieces and their counterparts" paragraph is where a reviewer reads what
the change costs the eval structurally, and it currently says the cost is negative. The
Surface section does declare the split ("the crossing arm split into its lazy top tier and
the eager non-top tier"), and `review.md` points the user at F34-F36 for the duplicated arms,
so the split itself is disclosed and derived. The defect is the contradicting claim standing
beside it: a reviewer who trusts the pieces paragraph will not go looking for the duplication
the Surface section admits. The claim was true at `54db7b2c66`, where the merged dispatch
went from five arms to four; `d76d5f6eb2`'s split superseded it and the sentence was not
updated.

## C2 `Stack.owedOf` is declared in the surface and has no caller

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:52`

derivation says:

> `Stack.scala`: always-present `owed` parallel array (chunks, empty-filled, no nulls)
> and `evalOwed`; `pop` hands back what the entry owes; `takeOwed`, `owe`, `oweBelow`,
> `takeEvalOwed`, `owedOf`; `dump` attaches its snapshot to the entry below; ...

code does:

```
52    def owedOf(i: Int): Chunk[Stack.Snapshot] = owed(i)
```

That line is the only occurrence of the identifier anywhere in the tree: no call site in
`kyo-kernel` main, test, or any platform source set, and none in the rest of the repository.
Every other member on that list is load-bearing (`takeOwed` at the loop-done arms and inside
`snapshot`/`dump`, `owe` at the Park install, `oweBelow` at the pending pops and the Park
arms, `takeEvalOwed` at the park transfer, the completion, and the unrecovered throw).

why it matters: the declared surface is the list a reviewer walks to check that each new
member earns its place. A non-private accessor that nothing reads is a surface item with no
realization; either the code needs a reader for it or the surface should not claim it.

## What was checked and holds

**The equations.** Owed dumps: `Stack.dump` attaches the snapshot to `owed(from - 1)`
(`Stack.scala:180`), so no caller can produce an unowed dump. Every site where an entry
leaves the stack either drains or re-homes what it owes, with no exception: the eight
`stack.pop()` sites (`Eval.scala:258, 342, 422, 432, 458, 469, 481, 498`) and the two
`truncate` sites (`Eval.scala:264-266, 348-350`, each preceded by `takeOwed(idx)`, and at
both the stack depth is `idx + 1` so `truncate` clears nothing undrained). The two
effectful-clause pops and both Park arms re-home through `oweBelow`; the settled pops and
the loop-done truncates drain through `drainDiscarded`; the four recovery pops drain through
`drainOwed` with the failure, which the "drains take the throwable" ruling authorizes.
`evalOwed` lives on the pooled stack as derived, is taken at exactly the three named sites,
and rides a park as `Kyo.Park.owed`.

The bind step: `Arrow.Bind` (`Arrow.scala:97-105`) is `Arrow.Step`'s body with the
`Safepoint.get`/`Safepoint.enter`/`Safepoint.exit` triple removed and nothing else changed,
matching "`Arrow.Step` minus the budget gate" exactly. `Effect.bracket` chains it directly
onto the acquire (`defer(acquire).chain(open)`), and `deferInline`'s own gate fires before
the thunk, as the derivation states. No eval code participates.

The done edge: `hc.done(stack.state(top))` fires immediately before `drainDiscarded(stack.pop())`
at the settled context pop (`Eval.scala:421-422`), in the same slice. The settled fast path in
`ContextEffect.handle` fires `done(derive(Maybe.empty))`, which is `done(derive(Absent))`.
`Effect.bracket` catches a `use` that throws during application and drains the cell before
rethrowing.

The stale-context fix: the non-top crossing dumps eagerly through `dumped` and downdates
through `rebound`, which is the settled pop's rebind (`find` the tag, `update` to the live
state below or `remove`) applied per dumped region. The two loop-done arms are now
identical bar `ctx` versus `ctx2`. `dropRegions` really did exist inside the range
(introduced at `54db7b2c66`, removed at `d76d5f6eb2`) and really is gone, so that clause of
the derivation is accurate.

**Every piece maps to an existing value.** All nine rows of the counterparts table check out
against the sources. No carrier type was introduced for the release walk: `expandOwed` and
`releaseCollected` share the flat `ArrayBuffer[AnyRef]` pair buffer with the single
erasure-forced cast in `released`, per the "why do we need Obligations" ruling. No dedicated
discard signal class: `drainDiscarded` mints a `kyo.KyoException` behind its empty-check, per
"remove Discarded, take the exception as a param". Owed collections are `Chunk`, appended
newest last. The two new types, `Effect.Finalize` and `Effect.Cell`, are both named in the
Surface; `Cell.inert` is a constant of an authorized type, forced by `ContextEffect.handle`'s
mandatory `fork` hook. `Stack.Snapshot` remains the one home for the layout: the stride
change from 3 to 4 reaches `Isolate.scala` and `EffectTrace.scala` only through the
accessors, and both still compile against `regions`/`handler`/`state` unchanged.

**The surface holds.** Every file in the diff is declared: `Arrow.scala`, `Effect.scala`,
`ContextEffect.scala`, `Handler.scala`, `KyoInternal.scala`, `Stack.scala`, `Eval.scala`,
the `Sync.scala` deletion, and the two test files (`SyncTest.scala`'s deletion follows the
prototype's). No unrequested improvement was found outside the declared items. Two changes
sit inside a declared file without their own surface bullet, and both are forced by a
declared item rather than volunteered: the `release = release` named argument in the
`ContextEffect.handle` delegation, which the inserted `done` parameter makes a compile
requirement, and `stack.owe(stack.depth - 1, entries.owed(i))` at the Park install, which the
equation's second boundary case ("a dump packed by a park travels with its entry") requires
for the obligation to land back on the live stack.

**Nothing was quietly dropped.** All nine counterpart rows are realized. Every pin the
derivation names exists: the four red-first reproductions (dropped capture at
`EffectBracketTest.scala:158`, settle strand at `:116`, loop-done discard at `:130`, stale
context at `EvalTest.scala` "a clause reads the outer binding, not a dumped one") and all
eight contract pins, four of them in the `EvalTest` "owed dumps" block.
