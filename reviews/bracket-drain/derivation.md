# Derivation: owed dumps and the bind step

The two live leaks from `reviews/proto-migration/sync-bracket-review.md`, closed with the
region scoping ruled during the design session. Rulings of record:

> "once the handling scope ends we must release no?" (2026-09-01, on when a dropped
> capture's obligations drain: at the answering region's exit, not at eval end)

> "Pleae please please use type safe code as much as possible. If you see an Any, take a
> step back and review. Minimize the change as much as possible." (2026-09-01, standing
> for this change)

## The equations

**Owed dumps.** A crossing into the region at `idx` dumps the regions above it; the dumped
snapshot rides the captured continuation, which is region-`idx` currency: it can only be
resumed from that region's state, body, or clauses. The region's exit is therefore the
delimiter of every possible resumption. In the combinators:

    handleCont(tag, v)(clause, done)  where the clause drops its continuation

must behave as if the drop were an abandonment of the dumped remainder at the moment the
`handleCont` region exits:

    Eval.release(remainder, discarded)   scoped to the region's exit

`Eval.release` is already the abandonment law (innermost first, suppression onto the
signal, claimed cells no-op). The eval realizes the scoping by making the answering entry
*owe* the snapshot its dump produced: the entry's exit, on every path the eval owns
(settled pop, recover, loop truncate, unwind), drains what it owes. "Owes" is the release
scaladoc's own word.

Two boundary cases keep the same equation:

- An entry that leaves the stack while its logical extent continues as values (the
  LoopHandler effectful-clause pop, where the region is re-created fresh by the outcome
  dispatch) re-homes what it owes to the enclosing entry: everything reachable from the
  dissolved extent is reachable only through the enclosing region's body (its continuation
  chains there), so the enclosing exit bounds the obligations. At depth 0 the enclosing
  extent is the eval itself: the owed list roots on the eval, drains at its end, and rides
  a safepoint park to the resuming eval.
- A dump packed by a park or by an enclosing dump travels with its entry, because the
  snapshot layout carries the owed slot alongside handler, state, and continuation. The
  abandonment walk drains owed slots wherever it already reaches entries.

**The bind step.** The settle-to-install leak exists because `acquireReleaseWith` installs
its region through `map`, and `map`'s budget gate can defer a *settled* acquire against the
install (`Pending.scala:24`, drained slot per `Safepoint.resolve`). The law is the one the
settled arm already obeys, extended over the gate: a settled value's next arrow applies
strictly. So the install becomes a step with no gate:

    acquireReleaseWith(acq)(rel)(use) = defer(acq).chain(bind)
    bind(a) = Handle(Finalize(cell(a)), use(a).map(complete))

`Arrow.Bind` applies immediately on settled input and defers pending input, exactly like
every arrow, minus the `Safepoint.enter` check. With the bind chained directly after the
acquire, no gate exists between the resource-producing thunk and the region install on any
delivery path: the fused `cont.head(f(v), cont.tail)` sites hand the settled result
straight to the bind, the eval's settled arm applies `contA` strictly, a crossing's
captured tail re-applies the bind on resume, and a replayed tail mints a fresh cell per
shot because the bind is an immutable complete value. The thunk's own gate fires *before*
the thunk, so a stop landing mid-acquire parks an extent that owes nothing yet. Main's
`BindingStep` is the precedent ("its apply skips the budget gate so a pending stop cannot
defer the bind against that refusal"). No eval change; the reviewer's decline/defer
livelock cannot arise because no site declines.

## Pieces and their counterparts

| piece | counterpart already in the kernel |
|---|---|
| owed slot per region | the snapshot triple gains a fourth slot; `Stack.Snapshot` is the one home for the layout |
| owe at dump | `stack.dump(idx + 1)` already produces the snapshot; the entry at `idx` keeps the pointer |
| drain | `released(handler, state, ex)` walked innermost first, the existing release law |
| discard signal | a throwable minted per drain, the abandonment signal for a drop (`Eval.release`'s `ex` position) |
| re-home on pending pop | list concat onto the entry below, or the eval-local root for depth 0 |
| root carriage across parks | a `Kyo.Park` field, default empty; the park already carries the entries |
| bind | `Arrow.Step` minus the budget gate; the settled arm's strictness as an arrow |

No new node kind. No eval arm added. No registry: the owed lists live in the stack and the
snapshots the dump already builds, and uniqueness stays in the `Cell`.

## Surface

Changes:

- `Arrow.scala`: `Arrow.Bind` (private[kyo], ~12 lines).
- `Sync.scala`: `acquireReleaseWith` builds the install as a `Bind` and chains it; scaladoc.
- `KyoInternal.scala`: `Kyo.Park` gains `owed: List[Stack.Snapshot] = Nil`.
- `Stack.scala`: lazily allocated parallel `owed` array; `owe`, `oweAll`, `takeOwed`,
  `owed(i)`; snapshot stride 3 to 4 with an `owed` accessor and an empty constant;
  `snapshot()`/`dump()` pack the slots, `clear()`/`truncate()` null them, `grow()` carries
  them; `Snapshot.Builder` and `contextual()` emit empty slots (bindings fork, obligations
  do not).
- `Eval.scala`: owe at the crossing dump; drains at every entry-exit site (settled arrow
  pop, the three recovered pops, the post-recover pop, both loop truncates including the
  released-before-truncate fix for the `Loop.done` leak); re-home at the two pending pops;
  eval-local root list, transferred by `park()`, drained at completion and at the
  unrecovered throw; both Park arms re-root and install owed slots; `Eval.release` expands
  owed slots and the park's root list; the `Discarded` signal class; shared collect/drain
  helpers so the in-eval drain and the abandonment walk produce the same order.
- `Handler.scala` + `ContextEffect.scala`: release scaladoc states the at-least-once
  contract (the kernel guarantees reachability; exactly-once lives in the state).
- `SyncTest.scala`, `EvalTest.scala`: pins below.

Must not change: the Defer arm, `Stack.push`'s signature and hot path, the handler
protocols, the `ContextEffect` API surface, `Isolate.scala`, the suspension arms' shapes.

## Pins

Reproductions (red before the fix):

1. landed red pin: a discarded captured continuation still releases the bracket.
2. a stop landing as the acquire settles still installs the region (the review's section 3
   program: settled acquire result, `Eval.release` on the remainder fires the release once,
   resume does not double-fire).
3. a loop clause answering `Loop.done` over an open bracket releases it (the review's
   section 1 program).

Contract pins (the new semantics, chosen not accidental):

4. a raw `ContextEffect.handle` release hook fires on both the live-stack unwind and the
   owed drain: at-least-once is observable for raw hooks, exactly-once stays with the cell.
5. the drop drain fires at the answering region's exit, not later: observable from the
   continuation after `handleCont` completes, inside the same eval.
6. owed drains run innermost first across nested brackets in one dump.
7. a loop handler's effectful clause storing the capture and resuming it a later iteration
   completes the bracket: re-homing does not drain a live extent.
8. an outermost loop handler's effectful clause dropping the reentry drains at eval end
   through the root list.
9. a park mid-use after a crossing resume still owes the bracket through the transferred
   entries (release on the park fires it).

## Forks

None blocking. Recorded for the review:

- The `Spent` re-entry refusal (review section 5) is the user's open decision; this change
  pins the pass-through so the semantics are chosen, and does not add the hook.
- The name `Bind` (main's `BindingStep`; proto vocabulary calls context regions bindings).
  Alternatives recorded: `Open`, `Install` (rejected: "install" is the Park arm's verb).
- Benches are parked by the user's standing instruction ("no benchs for now please"); the
  rows the change reaches are named in the package as unverified.
