# Review: the sync bracket proposal, held out

A held-out read of `sync-bracket-proposal.md` against the landed code, with no context from
the design discussions. The dump-and-keep-pointer mechanism is the right shape: it closes the
red pin without discriminating regions, and the cell keeps exactly-once out of the kernel.
Three findings say the proposal's map of the territory is wrong in places the mechanism must
cover: the settle-to-install state is reachable today (a live leak, not future-proofing), the
LoopHandler short-circuit discards regions on a path no dump ever sees, and the `Arrow.Install`
enforcement as specified livelocks without a third point the proposal does not name. One
decision the rejected-designs record buried (dropping `Spent`) deserves reopening.

## 1. Does the kept pointer reach every cell?

The enumerated paths check out, with one uncovered edge.

Sound paths, verified against the code: completion claims through the exit map inside the
region (`Sync.scala:64-66`) before the settled arm pops it without release (`Eval.scala:315-324`).
Failure releases live entries in `recovered` (`Eval.scala:350-355`). Abandonment walks the
value spine (`Eval.scala:28-66`), and a crossing's `Kyo.Park` carries its entries
(`Eval.scala:156-159`), so a dropped value releases. The dropped capture is the red pin
(`SyncTest.scala:117`), and the kept pointer covers it: the one dump site is the crossing
(`Eval.scala:139`), the continuation is passed by value to `ContHandler.run` and
`ContOpHandler.run` (`Eval.scala:165,174`), so the dump always happens before a clause can
drop, and the eval-end drain reaches the unclaimed cell. A value never evaluated owes nothing:
the cell is minted inside the install lambda per application (`Sync.scala:58`). The isolate
fork installs `Cell.inert` (`Sync.scala:39-43`), and no claim path bypasses the CAS.

**The uncovered edge: LoopHandler short-circuit.** `LoopHandler.run` does not take the
continuation, and the non-top done branch never forces the `continuation` def that performs
the dump: it reads the handler's own continuation and calls `stack.truncate(idx)`
(`Eval.scala:245-256`), discarding every region above the handler with no release, no dump,
and no kept pointer. Concrete shape, entirely on the public surface (`Loop.done` is pinned at
`EvalTest.scala:191`):

    val body = Sync.acquireReleaseWith(Sync.defer(7))((_, _) => count += 1)(a => ask.map(a + _))
    val r    = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.done(-1), a => a)
    eval(Sync.Unsafe.run(r))    // -1, and count == 0

At the suspension the stack is [Ask loop handler, Finalize region]; `find` lands at idx 0,
the non-top arm runs, `Loop.done` takes the `case outcome` branch, and `truncate(0)` drops the
Finalize entry. The cell ends unreachable and unclaimed, exactly the class the proposal claims
the mechanism closes. The `Debugger.onRegionExit` calls at `Eval.scala:247-252` show the state
was known reachable. Fix: the done branch must run `released` on the ContextHandler entries
above idx, innermost first, before truncating (symmetric with `recovered`), or dump-and-keep
them. The pending branch is fine: forcing `reentry` dumps (`Eval.scala:225`). The proposal
must add this edge; it is a hole in the landed tree independent of the red pin.

## 2. Does at-least-once break any landed release user?

No landed pin breaks, but the landed contract changes observably one step away from the pins.
`ContextEffect.handle` takes an arbitrary `release` hook (`ContextEffect.scala:107,118`), and
the EvalTest pins use non-idempotent hooks (`log += state`, `EvalTest.scala:738`). Invariant
3's claim that "plain bindings are safe under at-least-once because their hook is the no-op"
covers only the default. The double-fire shape:

    val inner = ContextEffect.handle(Tag[Cfg])(_.getOrElse(7), fork, join,
                  release = (s, _) => log += s)(ask.map(_ => (throw Boom): Int))
    val outer = ArrowEffect.handleCont(Tag[Ask], inner)([C] => (_, cont) => cont(1), b => b)

The crossing dumps Cfg (kept pointer), the in-clause resume re-installs it via the Park arm,
`recovered` releases it from the live stack, and the eval-end drain invokes the same hook
again through the kept snapshot: `log == List(7, 7)` where today it is `List(7)`. Nothing
collapses this for a raw hook; only the cell does. Recommendation: accept it (the alternative
is the rejected registry), but document at-least-once on the `release` parameter and on
`ContextHandler.release` (`Handler.scala:87-89`), correct invariant 3's wording, and pin the
double-invocation shape so the contract is chosen rather than accidental.

## 3. Is the settle-to-install state unreachable today?

No. The proposal's claim rests on "parks exist only at Defer dispatch and settled delivery is
strict", but arrow application manufactures Defers: `map`'s gate defers a settled value with
the pending arrow when `Safepoint.enter` fails (`Pending.scala:24-30`; same in
`Arrow.scala:70-72`), and a pending honored stop drains the armed slot's budget precisely so
the next `enter` fails (`Safepoint.scala:74-79,104-105` jvm-native). The trace: the acquire's
final thunk settles; a stop is pending (requested inside the thunk, or landed from another
thread via `Safepoint.stop` mid-slice, so the window is racy, not only self-inflicted); the
install map's `run` calls `Safepoint.get`, the resolve drains, `enter` fails, and a `DeferWith`
holding the settled resource with the install as its contA is returned; the Defer arm sees
`armed && stopped` and parks it (`Eval.scala:116-117`). The remainder strands a settled
resource with no region installed; `Eval.release` finds nothing:

    val v = Sync.acquireReleaseWith(Sync.defer { requestStop(); 7 })((_, _) => count += 1)(a => Sync.defer(a))
    val parked = Eval.partial(Sync.Unsafe.run(v))
    Eval.release(parked, Boom)    // count == 0: the resource exists and leaks

The landed pin at `SyncTest.scala:98-110` returns `Effect.defer(7)`, an unsettled tail, which
is correctly not owed; the settled variant is the leak. So `Arrow.Install` fixes a live bug
and the proposal should say so; the proposed pin (stop inside the final thunk) is the right
reproduction, but it must use a settled result.

**Enforcement completeness.** The two stated points are not sufficient; a third is required.
If the Install's own `apply` carries the budget gate, the Defer arm's decline re-enters the
loop, the settled arm re-applies the install, the drained gate defers again, and the eval
livelocks between decline and defer. The install's apply must skip the gate and apply
immediately on a settled input, which is exactly main's documented mechanism ("its apply skips
the budget gate so a pending stop cannot defer the bind against that refusal",
`kyo/kernel/Effect.scala:146-150`). With that, point 1 covers Defers built elsewhere with an
install ahead: a captured acquire tail resumed through the crossing re-emerges as
`Defer(settled, kc, ca.chain(cb))` and a stop at that dispatch would otherwise strand it. The
guard must check the headmost arrow of contA, not `contA` itself: `Effect.defer` destructures
only one Chain level (`Effect.scala:17-28`), so an install can sit at `contA.head`'s head. Use
the `head` spine walk. Point 2 (the park packer) guards only hypothetical future park sites;
today the Defer arm is the sole caller.

## 4. The rejected designs

Sound and well-recorded: the bracket node (a node kind would still need the kept pointer for
discarded captures), the GuardHandler family, retention-by-moving, the outstanding registry
(the kept pointer's difference is real: dump-produced, eval-scoped, no uniqueness duty), the
fused finalizer (main fused resource into the entry, `Finalizer.scala:28-32`; the proto's
separate per-shot cell is the better shape under replay), and the payload collapse.

Two qualifications. Row-restricted release was rightly rejected for the API, but it should be
recorded that it carried a guarantee the chosen design gives up: with resources handled last
no outer clause exists to capture and outlive the extent, so spent-extent entry was
unrepresentable. The chosen design pays for the open row with invariant 5.

**Dropping `Spent` is the unsound entry.** The record says Abandoned's and Spent's "semantics
survive as plain throwables and the claimed no-op". True for Abandoned, false for Spent: Spent
was never an outcome payload, it is a guard on re-entering a released scope
(`Finalizer.scala:66-83`, refused at install, main `Stack.scala:43-53`). The claimed no-op
reproduces the release's idempotence, not the entry's refusal. See section 5.

## 5. The invariants, and the 3-with-5 interaction

Invariants 1, 4, and 6 hold as stated and are pinned or structural. Invariant 2's
"unreachable today" is false (section 3). Invariant 3 needs its wording fixed (section 2).

**Invariant 5 is a silent use-after-release, and the proposal should say so.** A clause stores
the continuation; the eval ends; the eval-end drain claims the cell and runs the release. The
stored continuation applied later re-installs the region and runs `use`'s tail against a
released resource, silently: nothing refuses, nothing throws, the completion is a claimed
no-op. Sequentially this is observable through the public surface. Under concurrency the drain
on one thread races a resumed extent mid-use on another; the CAS makes the invocation unique
but not ordered before the use. Main has the same race (its boundary drain vs a concurrently
resumed eval) but refuses the sequential case with `Spent`. So the proposal is race-equal to
main and strictly weaker sequentially. Recommendation: treat this as the one open soundness
decision, not a documentation line. The cheap refusal: an optional `ContextHandler` re-entry
hook consulted by the Park install arm (`Eval.scala:290-295`), default no-op, overridden by
Finalize to throw when the cell is already claimed. It costs one virtual call per region on
the resume path only, and it catches both a replayed region value (the shared-cell replay,
where `derive` returns the same closure cell, `Sync.scala:59-60`) and a post-drain resume. If
pass-through is kept instead, pin the sequential shape so the semantics are chosen.

## 6. The open questions

1. **Placement and carriage.** An eval-local var (lazily allocated, nearly always null), not a
   field on the pooled `Stack`: a stale kept list surviving `clear()` into the next borrow
   would drain another eval's cells, and pool hygiene is a bug class the local avoids by
   construction. Park carriage as a field on `Kyo.Park` with a shared empty default so
   isolate-built parks (`Isolate.scala:85,104`) pay one reference; entry-shaped encoding would
   corrupt `entries.regions` and the install arm. Two traps to spec explicitly: the transfer
   must clear the kept set before the finally runs (else raw hooks double-fire on the same
   eval), and the empty-stack park path returns a bare value today (`Eval.scala:99-101`), so
   when kept pointers exist the Park wrapper must be forced anyway or the finally will drain
   obligations that should ride the remainder.
2. **Drain order.** LIFO on both levels, matching the innermost-first pins
   (`EvalTest.scala:655`, `SyncTest.scala:86`) and `Eval.release`'s backward walk
   (`Eval.scala:61-65`): newest snapshot first, region `regions - 1` down to 0 within one.
3. **The discard throwable.** A class minted per drain, not a shared object: the drain
   suppresses hook failures onto the signal (`Eval.scala:73`), and a singleton would
   accumulate suppressed throwables for the life of the process, the exact reason main's
   `Spent` is a class (`Finalizer.scala:76-78`). Reuse the in-flight failure when unwinding:
   yes, it is the JVM suppression convention and main's policy (survey section 7), and it
   requires a catch-and-rethrow shape rather than a bare finally, since a `finally` block does
   not hold the throwable.
4. **Where `Arrow.Install` lives.** Kernel internals. It is an eval contract, not a user
   combinator; on the public `Arrow` surface a user extension would silently acquire
   park-refusal semantics. `Sync` already reaches internals (`Cell` is `private[kyo]`). Main's
   `BindingStep` is the precedent. Also rename: "install" is already the Park arm's verb
   (`Eval.scala:283`); one word for two mechanisms will confuse the next reader. `Arrow.Bind`
   or `Arrow.Open` reads better.
5. **The contextual isolate.** No missed case found for Finalize itself: `contextual()` does
   not consume (`Stack.scala:68-87`), fork maps every crossing to inert, join returns the
   parent so `merge`'s reference check skips it and restore never installs a Finalize binding
   (`Isolate.scala:152-163`), a child abandonment drains inert plus whatever the child's own
   spine owes, and a re-forked inert forks to inert. The genuine gap in this area is not the
   isolate but section 1's truncate edge.
6. **Bench exposure.** The gated rows are right, and the Defer arm's added check sits behind
   `armed && stopped`, off the hot path entirely. Two costs the list omits: the `Kyo.Park`
   field on every park allocation (shared empty default makes it one reference), and the
   headmost walk on the armed-and-stopped path only. If the kept list ever lands on `Stack`
   despite recommendation 1, `clear()` gains a store.

## 7. Smaller findings

- Missing pins beyond the plan: the settled-acquire abandonment leak (section 3's program,
  asserting the release fires on `Eval.release` of the remainder and does not double-fire on
  resume); the LoopHandler done-over-a-bracket shape (section 1); the raw-hook
  double-invocation shape (section 2); the stored-continuation-after-eval-end shape
  (section 5), whichever way that decision goes.
- Doc gaps: `ContextEffect.handle`'s `release` parameter and `ContextHandler.release` need the
  at-least-once contract stated; `Sync.acquireReleaseWith`'s "exactly once" scaladoc stays
  correct because the combinator's hook is cell-guarded.
- The proposal's Drain bullet should state that the drain site is the outer finally at
  `Eval.scala:408-412`, after all recoveries: kept pointers must survive a recovered failure,
  since a stored continuation can still be applied inside the recovered computation, and the
  current wording does not rule out draining at the recovery.
