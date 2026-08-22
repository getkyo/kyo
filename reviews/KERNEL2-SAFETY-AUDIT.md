# kyo-kernel2 held-out safety audit

Static analysis only; no code was executed. Independence note: this audit was produced without
reading any other document under `reviews/` or any design/handoff material.

**Anchor**: HEAD `600941e1e429bdd0992ae7fe83330a7e60156ff7` plus the uncommitted working-tree
modifications present at audit time. The tree was under concurrent modification during the audit
(the `drainFinalizers` signature changed from `Throwable | Null` to `Maybe[Throwable]` between the
first and second read of `Stack.scala`); every finding below was re-verified against the file
contents as of the anchor, and the cited line numbers are from that state.

**Scope**: `kyo-kernel2/shared/src/main/scala/kyo/Arrow.scala`, `kernel/*.scala`,
`kernel/internal/*.scala`; the JVM/jvm-native test sources; the shared test suite under
`kyo/kernel/`. The `kyo/proto/` namespace, `Arrow2.scala` (an explicitly non-working sketch), and
the top-level `Kyo.scala` combinators are excluded per the audit brief.

Severity meaning: HIGH = a concrete wrong outcome (lost failure, leaked resource, corrupted state,
lost preemption) reachable from ordinary kernel-facing code; MEDIUM = concrete wrong outcome
needing a rarer trigger; NOTE = protocol observation or design constraint without an independent
failure scenario. Findings with a failure scenario are ranked above notes, per the brief.

---

## 1. Findings

### F1 (HIGH) — A release that throws during unwind aborts the unwind, replaces the original failure, and skips the standing recoveries

Criterion 4 (exception and resource safety).

- `Stack.scala:333` — `case f: Finalizer[?, ?] => f.run(Result.panic(ex))` inside `unwind`, with no
  guard.
- `Eval.scala:601-614` — `stack.unwind(ex)` runs inside the `catch` clause of the eval's retry
  loop; an exception thrown *by the unwind itself* escapes the catch with `failure` still `Absent`.
- `Eval.scala:617-624` — the `finally` then calls `drainFinalizers(failure)` with `Absent`, so the
  remaining releases are told `Finalizer.Abandoned` instead of the real failure, and if one of them
  throws, `drainFinalizers`'s completing-path `throw first` replaces the in-flight exception again.

`Finalizer.run` executes the user's release as a nested `Eval` (`Finalizer.scala:41`). If that
release throws, the exception propagates out of `unwind` mid-walk. Consequences, in order:

1. The walk stops; every entry above the answering `Recover` that had not yet been popped is never
   consulted. A `Catching` that should have answered the original failure is skipped.
2. The original exception `ex` is replaced by the release's exception and is not suppressed onto
   anything; it is simply lost.
3. The drain then runs with `Absent` and reports `Abandoned` to the remaining releases, which is
   the wrong outcome (the eval is leaving on a failure).

This directly contradicts the kernel's own documented rule (`Stack.scala:368-373`): "A release
that throws never stops the ones after it." The drain honors that rule; the unwind does not.

**Failure scenario**:

```scala
Effect.catching {
  Effect.bracket(Effect.defer(1))((_, _) => throw new IllegalStateException("release")) { _ =>
    (throw new UnsupportedOperationException("body")): Int
  }
}(ex => -1)
```

Expected under the kernel's stated rules: the release runs, its failure is suppressed onto "body",
and the catching answers with `-1`. Actual: "release" escapes the eval, "body" is lost entirely,
the catching never runs.

The same hole exists for a *recovery* that throws: `Recover.panic` (`Eval.scala:60-70`) runs the
user's `recover(ex)` eagerly inside `unwind` (`Stack.scala:334`); a throwing recovery takes the
identical path (original failure lost, remaining scopes skipped, drain told `Absent`).

### F2 (HIGH) — The acquire-to-binding window is parkable, and an abandoned park taken there leaks the resource

Criteria 4 and 6 (resource safety, slice soundness).

- `Eval.scala:428` — the Defer-arm park guard: `armed && !v.isInstanceOf[Binding[?,?,?,?]] && stop()`.
- `Effect.scala:109` — `bracket` is `acquire.map { resource => new Binding ... }`.
- `Eval.scala:637-647` — `finalizeResources` releases only what `park.finalizers` holds.

The guard blocks a park only when the deferral's *payload* is already the `Binding` node, i.e.
after the bracket's map body has run. But the map defers *before* running its body whenever the
budget is drained or a stop landed (`Pending.scala` map: `shouldDefer` on `!Safepoint.enter`),
producing `Defer(value = settledResource, contA = theStepThatBuildsTheBinding)`. At the Defer arm
the payload is the settled resource, not a `Binding`, so the guard passes and the slice parks.
That park's `finalizers` span is empty: the acquire has completed (the resource exists, captured in
the step's closure) but no `Finalizer` was ever registered. A holder that abandons the park via
`finalizeResources` releases nothing.

This contradicts the invariant claimed twice in the sources: `Effect.scala:102-103` and
`Eval.scala:424-426` ("no slice can end between the resource existing and the scope that owes it
being installed"). The eval never stops *in front of a Binding node*; it can absolutely stop in
front of the *step that builds one*, one instruction earlier in effect.

**Failure scenario**: armed slice (`Eval.partial`); a cross-thread `Safepoint.stop` lands while the
eval is between the acquire settling and the bracket's map step running (any budget-drain at that
boundary produces the shape); the slice parks; the holder decides not to resume and calls
`finalizeResources`. The acquired resource (an open file, a connection) is never released.

The existing test `EffectTest` "a slice stopping at every step releases once, at the end" walks
straight through this window but never abandons, so it cannot see the leak (it asserts
`released == 0` while parked, which is the correct half of the property).

### F3 (HIGH) — The Out cell is not cleared between dispatches, and `nextAnswer` runs user code outside the cell-maintenance try; a throw there commits stale state and resurrects stale continuations

Criteria 1 and 6 (no mutable evaluator state may leak into control decisions; throw-mid-loop must
leave the region coherent).

- `Handler.scala:70` — `val v0 = d.value` in `nextAnswer` forces a deferral's by-name payload,
  which is user code for any `Effect.defer { ... }` node.
- `Handler.scala:186, 296, 366` — `nextAnswer` is called from all three answers loops *outside*
  the try blocks that maintain the cell on throw.
- `Handler.scala:144, 247, 346` — `armed && stop()` runs before any cell write in the dispatch;
  `stop` is caller-supplied and nothing stops it from throwing.
- `Eval.scala:188-190, 231-233, 324-328` — the fast-dispatch catch handlers then trust the cell
  unconditionally: `dispatchLoopStateFast` commits `out.state` into slot 0
  (`stack.putState(0, out.state...)`, `Eval.scala:326`) and all three push `out.cont` onto the
  stack for the unwind.

The cell (`Handler.Out`) is per-stack and reused across dispatches; nothing nulls `state`/`cont`
after a dispatch completes (several bail paths deliberately leave `cont` set, e.g. the kind-2
suspended-clause path, and the generic `HandlerCont.answers` used by every `handleCatching` region
leaves `cont = k` on every successful answer). So on the first throw of a dispatch that happens
*before that dispatch's first cell write*, the catch reads another dispatch's values:

- `out.state` may belong to a **different handler** installed earlier on the same stack; committing
  it into slot 0 puts foreign state into the current region's slot. If a recovery below answers
  the failure while this handler is still installed (the pushed stale continuation can itself
  contain the `Catching` that answers), the region resumes with another region's state: a
  `ClassCastException` at best, a silently wrong answer at worst.
- `out.cont` may be a continuation from an earlier dispatch. Pushing it re-installs entries whose
  scopes then participate in the unwind: a stale `Catching` can answer a failure that is not its
  own; a stale not-yet-run `Finalizer` is run early with a panic outcome (the AtomicBoolean then
  makes the *legitimate* later run a no-op, so the extent completes against a released resource).

**Failure scenario** (payload-throw variant, no throwing `stop` needed): a stateful region's
continuation step returns an `Effect.defer { throw Boom() }` node; the answers loop's
`k(Nested.unnest(ans))` succeeds, `nextAnswer` forces the payload at `Handler.scala:70`, `Boom`
escapes `h.answers` with the cell untouched this dispatch; `Eval.scala:326` commits whatever
`out.state` last held (a previous dispatch's state, possibly another handler's) and `Eval.scala:327`
pushes whatever `out.cont` last held.

Note the contrast with every other throw site in the loops: `handle` and `k(...)` throws all write
`kind/state/cont` before rethrowing, and the code comments ("the loop committed before rethrowing;
make the slot and the entry current", `Eval.scala:325-326`) show the catch handlers *assume* that
protocol. `nextAnswer`'s payload forcing and the `stop()` call are the two paths that break the
assumption.

### F4 (HIGH) — Clause-throw scope semantics diverge between handler families and between the fast and general HandlerCont paths

Criteria 4 and 6.

When a handler clause throws, which scopes standing *between the suspension and the handler* get to
answer the failure differs by dispatch path:

- `dispatchLoop` / `dispatchLoopState` general paths (`Eval.scala:154-157, 284-287`): the clause
  runs with the interior entries still on the stack (dump happens only after a successful answer),
  so the unwind pops the interior: its finalizers release with the failure, and an interior
  `Catching` **answers the clause's throw**.
- Fast paths (`Eval.scala:188-190, 231-233, 324-328` catching the throw paths at
  `Handler.scala:154-156, 175-178, 257-261, 283-287, 355-358`): the unconsumed continuation is
  re-pushed from `out.cont`, restoring the same behavior: interior scopes participate.
- **`HandlerCont` general path (`Eval.scala:455-460`)**: `stack.dump(pos)` folds the interior into
  `k` *before* `h.run(kyo.input, k)`; the catch only attaches trace (`attachThrow(ex, kyo, k,
  stack)`) and does **not** re-push `k`. An interior `Catching` folded into `k` is skipped: the
  clause's throw escapes the region (or reaches an outer recovery). Interior finalizers survive
  only via the side array and are released later, at eval end, with `Abandoned` instead of the
  failure.

So the same program shape recovers on one path and fails on the other:

```scala
val body = Effect.catching(ask.map(_ + 1))(_ => -1)   // Catching between suspension and handler
// stack at suspension: [map-step, Catching, Handler] → pos = 2 → general path for both families
ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => throw Boom(), a => a)      // evaluates to -1
ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a) // Boom escapes
```

Both cannot be right. There is also a design tension worth deciding explicitly: the clause-scope
law pinned in `EvalTest` ("clause scope" section) says a clause's *effects* are answered outside
the region it serves; by that symmetry a clause's *throw* should also unwind outside, which is what
the `HandlerCont` general path implements and every other path does not. Whichever semantics is
chosen, one family of paths currently implements the opposite, the difference is observable from
ordinary code (`pos <= 1` versus `pos > 1` is just "was the suspension bare or mapped"), and no
test pins either behavior.

### F5 (MEDIUM-HIGH) — A cross-thread stop consumed by the answers-loop bail is lost; the slice does not park

Criteria 5 and 6 (concurrency, slice soundness).

- `Handler.scala:144-148, 247-252, 346-350` — the answers loops poll `armed && stop()` at the top
  of each iteration and bail with `Effect.defer(resuspend(...))` when it fires.
- `Eval.scala:112-116` — `Eval.partial` composes `() => Safepoint.consumeStopped(slot) || stop()`;
  `consumeStopped` is one-shot (`Safepoint.scala:179-185`).
- `Eval.scala:428` — the only park point re-polls the same composed function.

When the top-of-iteration check is the first observer of a pending `Stop`, it *consumes* the
sentinel and bails with a resuspension. The eval's Defer arm then re-polls: `consumeStopped` is now
false (consumed), the caller's own `stop` is false, so the eval does **not** park; it pushes the
conts and re-dispatches the resuspended operation, and the slice runs on. The budget is not even
drained on this path (drain happens in `Safepoint.resolve`, which only runs if a `Safepoint.get()`
observed the Stop before the check consumed it), so the slice continues at full speed. The
stopper's contract (`Safepoint.stop` returning true means the target will yield) is broken.

The existing partial-evaluation tests pass through a *different* mechanism: their stops are placed
by the clause itself, so the continuation's next `Safepoint.get()` observes the pending Stop first,
drains the budget, the continuation defers *without consuming*, and the Defer arm both consumes and
parks. The top-of-loop consume path fires only when the Stop lands in the window between the
continuation's last `Safepoint.get()` and the next iteration's check, which requires a genuinely
cross-thread stop with racy timing. Exactly that case is untested: the only cross-thread
stop-during-eval test is commented out (`SafepointConcurrencyTest.scala:77-106`) with a stale
"waiting on partial evaluation" note, although `Eval.partial` has since landed and is exercised
elsewhere in the suite.

Consequence: lost preemption. A scheduler relying on `Safepoint.stop` to bound a slice gets, with
some probability per stop, a slice that runs to completion instead.

### F6 (MEDIUM) — The eval's finally block: a throwing release on the completing path skips `Safepoint.restore`, corrupting the caller's slice state

Criterion 4.

`Eval.scala:617-624`: the finally is three sequential statements: `stack.drainFinalizers(failure)`,
`Stack.release(stack)`, `Safepoint.restore(slot, saved)`. `drainFinalizers` deliberately throws on
the completing path when a release failed (`Stack.scala:399`, correct per its contract), but that
throw skips the other two statements.

Skipping `Stack.release` costs only a pooled stack (GC reclaims it). Skipping `Safepoint.restore`
is worse: the slot keeps the *inner eval's* state (fresh budget, armed bit cleared by `save`)
instead of the caller's. For a full eval nested inside an armed slice (a clause calling `.eval`,
which the suite pins as legitimate: "a clause can run a full eval of its own mid-loop"), the outer
slice silently loses its armed bit: subsequent stops stop draining the budget, the Defer-arm poll
loses its acceleration, and preemption of the outer slice degrades or is lost. The same skip also
occurs when F1 fires (unwind throw → drain throws again).

**Failure scenario**: `Eval.partial` (armed) evaluates a computation whose clause runs a nested
`Eval(...)`; inside the nested eval a clause drops a continuation whose bracket release throws; the
nested drain throws on the completing path; `Safepoint.restore` is skipped; the outer slice
continues unarmed and no longer honors `Safepoint.stop`.

---

## 2. Notes (no independent failure scenario; ranked below findings per the brief)

**N1 — Multi-shot versus run-once releases is a real semantic edge, pinned for continuations but
not for parks.** A continuation held past the end of its eval gets its finalizer drained with
`Abandoned` at eval end; applying it later runs the use against a released resource, and the
arrow-path release is a no-op (`EffectTest:588` pins exactly this as intended: `count == 1`). The
same interplay for a `Park` resumed twice (span-shared `Finalizer` objects, first resume's
completion releases, second resume runs on a released resource) is unpinned: no test resumes one
park twice, resumes a park on another thread, or races resume against `finalizeResources`.

**N2 — `EffectTrace.attach` mutates shared exception instances.** A cached or singleton exception
thrown through evals repeatedly accumulates one `EffectTrace` carrier via `addSuppressed`
(synchronized, safe) whose fields are then written without synchronization
(`EffectTrace.scala:319-325` `installInto`; `splice`'s `physical` memoization). Growth is capped
(the budget is `MaxFrames - carrier.elements.length`, so a full carrier appends nothing), and the
races are last-writer-wins on a whole array reference, not torn state. Net effect: stale or mixed
frames on shared exceptions, misleading but not unsound. `Finalizer.Abandoned` is `NoStackTrace`
(splice skips it) but `attach` does not check `NoStackTrace`, so it would still gain a carrier if
it ever crossed an attach site.

**N3 — The overflow slot is shared mutable state across threads.** All threads past 65536 claimed
cells share `depths(Slots)` with plain unsynchronized read-modify-write (`enter`/`exit`) and a
shared armed bit. The test suite pins that overflow threads miss preemption
(`SafepointConcurrencyTest:210`), but not the cross-thread interference on the shared depth counter
(two overflow threads can interleave `enter`/`exit` so the guard bit is misread, letting a fused
chain exceed the depth budget; bounded by the enclosing eval but in principle a StackOverflow
hazard). Requires full table saturation; noting for completeness.

**N4 — `Loop.done` does not nest, so `Outcome`'s union overlaps the pending union at exotic
instantiations.** `done(v) = v` raw (`Loop.scala:211`); if a region's result type `B` is itself a
pending type and the done value is a `Kyo` node, `answerStep`'s first match arm
(`Handler.scala:118-120`) reads it as "the clause suspended". Under current kernel usage `B` is
never a pending type, and the eventual behavior is arguably equivalent, but this is the one lift
boundary in scope that inserts a value into a union without `Nested.nest` discipline. Criterion 2
observation.

**N5 — Cast inventory (criterion 3).** The `asInstanceOf` population in `Eval`, `Stack`, `Handler`,
`Nested`, `Pending` is erasure-forced or protocol-backed, and the protocol-backed ones (the
`out.kind`-discriminated `ran` casts, `stack.state`'s slot reads, `lookup`'s `Maybe[v]`) are each
locally documented; no cast was found whose justification is absent or circular. Two mechanical
flags: `Arrow.id[Any].asInstanceOf[Arrow[Any, Any, Any]]` (`Eval.scala:446, 465, 486`) is vacuous
(`Id[Any]` already has that type; a typed spelling exists), and `Handler.Out.Answered/Suspended/
Finished` are defined (`Handler.scala:385-391`) while the dispatches match raw `1/2/3` literals, a
drift hazard rather than a bug. `Safepoint.reset` has no production caller (test-only).

**N6 — A throw through a map/step skips `Safepoint.exit`,** leaking one budget unit per
throw-and-recover inside a single eval (the cross-eval variant is fixed and pinned by
`EvalTest:922`). Self-corrects at eval end via `restore`; within one eval, many recovered throws
bias the budget toward early defers. Performance-only.

**N7 — `flatten`'s worklist leaves stale entry references in free ring cells**
(`Stack.scala:104-121` writes `entries(...)` for tree nodes and never nulls the worklist region).
Object retention until cell reuse; the `states` array is untouched so no state pollution. Memory
note only.

---

## 3. What was verified and found sound

So that absence of findings is distinguishable from absence of looking:

- **Union representation (criterion 2).** Nesting happens exactly once, at `Implicits.lift` via
  `Nested.nest` (primitive/String shapes bypass it and can never be `Kyo`/`Nested`); unnesting
  happens exactly once at each delivery point (`Eval`'s settled arm, the answers loops'
  `k(Nested.unnest(ans))`, every settled branch of every arrow `apply`, the `handleX` settled
  shortcuts). `Arrow.Id` deliberately preserves the union. Suspension continuations receive raw
  payloads everywhere I traced (map/flatMap/andThen unnest before user code; `clauseSuspended*`'s
  step unnests before its typed apply). `NestedTest` pins one-level stripping, and the
  "nested box" sections of `ArrowEffectTest`/`EvalTest` pin double-boxing round trips.
- **Complete values (criterion 1).** Dumped continuations are immutable chains; `dump` nulls the
  ring cells it consumes and re-wraps a live stateful handler into a fresh
  `HandlerLoopState(h, state)` holding the state as `initialState`, so captures replay from
  capture-time state; the `Cont` spine is sealed to `AndThen | Id | Step`, so a normalized run can
  contain only plain steps and a `Chain` never partially executes (its `apply` always defers),
  which together mean no escaped value can hold a half-applied region. Park snapshots copy into
  fresh `Span`s; `restore` copies out without aliasing and re-resolves bindings against the
  resuming stack. The `Out` cell is per-stack, never captured by any wrapper or continuation. The
  hostile-axes suite ("the answer fast path", "the cont answer fast path", "a captured
  continuation is a value", `EvalCaptureTowerTest`) pins multi-shot, cross-thread replay,
  post-eval replay, and capture-time state well.
- **Region visibility invariants.** `Handler`, `Catching`, `Binding`, `Finalizer` are `Region`,
  excluded from `AndThen` by type; `push` flattens every `Chain` so ring entries are never chains
  and `find`/`lookup`/the drain always see their targets; the pos ≤ 1 fast-path gates exclude
  `Region` entries explicitly. `dump()`'s boundary stops at handlers and recoveries so a fold
  cannot take a live scope off the stack, and folded bindings are only ever invisible while
  nothing executes (chain re-installation precedes the head's execution).
- **Finalizer exactly-once discipline.** The AtomicBoolean gates all three paths (arrow, unwind,
  drain); the side array is deliberately never trimmed by `dump`, so folds cannot bury a release;
  `pushFinalizer` prunes only trailing already-ran entries; drains run innermost-first; `clear()`
  precedes pooling so no release crosses to the next borrower; park carries the side array and the
  parking eval's own drain is skipped. Broadly and well pinned in `EffectTest` "bracket" and
  `StackTest` "finalizers", including multi-throw suppression ordering.
- **Safepoint slot protocol (criterion 5).** All cross-thread communication goes through the
  `AtomicReferenceArray`; `depths` is owner-written only; `stop`'s null-cell probe termination is
  sound because a claimed cell never returns to null and a claimer retries the same index on CAS
  failure (so it never settles beyond a cell that was null when probed); one cell per thread via
  the thread-local cache; dead-thread reuse, displaced threads, unstarted threads, and racing
  stops are pinned in `SafepointConcurrencyTest`. No ABA: `stop`'s CAS requires the exact owner
  thread identity.
- **Budget mechanism.** `enter`/`exit` form a recursion-depth meter, not fuel; exits from the
  unwinding recursion replenish headroom, so no explicit reset is needed in the loop. `save`/
  `restore` bracket nested evals correctly on non-throwing paths (the throwing path is F6).
- **Suspended-clause re-entry.** `clauseSuspended`/`clauseSuspendedLoop` carry the state in the
  outcome (`Continue2._1`) and re-enter through a fresh `HandlerLoopState(h, state)` wrapper that
  preserves the per-site `answer`/`answers` overrides; the folded continuation is dropped on
  `done` with its finalizers still recoverable through the side array. Consistent with the general
  dispatches.
- **Clause-scope law for effects.** A clause's own suspensions are answered outside its region on
  every path (popped handler for the loop families' suspended clauses; region currency for
  answers), thoroughly pinned in `EvalTest` "clause scope".
- **Trace machinery.** Reconstruction is fully guarded (`reconstruct` swallows non-fatal walk
  failures; fatal errors pass untouched), bounded (cap, worklist bound, loop-not-recursion), and
  structurally terminating (arrow-position walk of self-referential slots). `splice` skips
  `NoStackTrace`. Pinned across `EffectTraceTest` including cap, fatal, and walk-failure cases.

---

## 4. Test-coverage improvements

Hostile axes first. Each entry names the test, sketches it, and states exactly what wrong behavior
it evidences or what gap it closes. Sketches use the suite's existing `Ask`/`Say` fixtures.

### T1 (pins F1) — `"a release that throws during unwind does not lose the failure or the recovery"`

```scala
var seen = List.empty[String]
val v = Effect.catching {
  Effect.bracket(Effect.defer(1))((_, _) => throw new IllegalStateException("release")) { _ =>
    (throw new UnsupportedOperationException("body")): Int
  }
}(ex => { seen :+= ex.getMessage; -1 })
assert(Eval(v) == -1)            // fails today: "release" escapes, the catching never runs
assert(seen == List("body"))     // the recovery saw the body's failure, with "release" suppressed on it
```

Evidences: unwind aborted by a throwing release; original failure replaced unsuppressed; standing
recovery skipped. Companion case: the same shape with the *recovery* throwing, asserting the outer
scope sees the recovery's failure with the original suppressed, and that outer releases are told
the failure rather than `Abandoned`.

### T2 (pins F2) — `"an abandoned park cannot strand a resource the acquire produced"`

```scala
var acquired = 0; var released = 0
val v: Int < Any =
  Effect.bracket(Effect.defer { acquired += 1; 1 })(_ => released += 1)(r => Effect.defer(r + 1))
// step the slice one poll at a time (the sliceOnce driver from EffectTest), and at EVERY
// intermediate park abandon a COPY of the run: park, finalize, and check the invariant
@tailrec def probe(v0: Int < Any): Unit =
  if v0.evalNow.isEmpty then
    val parked = sliceOnce(v0)
    parked.finalizeResources
    assert(acquired == released, s"acquired=$acquired released=$released") // fails at the pre-binding park
    ... restart from a fresh v for the next park position ...
```

Evidences: the acquire-settled-but-binding-not-installed park position leaks on abandonment. The
existing "slice stopping at every step" test walks these positions but never abandons; this closes
the other half of the property it gestures at.

### T3 (pins F3) — `"a deferred payload that throws mid answer loop cannot commit another dispatch's state or continuation"`

```scala
case class Boom() extends RuntimeException
// H1 commits an Int state; H2 (a different tag) holds a String state; H2's continuation forces
// a by-name defer that throws, with a recovery below both
val inner: String < (Ask & Say) =
  say("s").map(_ => ask.map(_ => Effect.defer { (throw Boom()): String }))
val h2 = ArrowEffect.handleLoopState(Tag[Ask], "B0", Effect.catching(inner)(_ => "recovered"))(
  [C] => (s, _) => Loop.continue(s + "+", (0: Int < Any).map(_.toString)), (s, a) => s + "/" + a)
val h1 = ArrowEffect.handleLoopState(Tag[Say], 999, h2)(
  [C] => (n, _) => Loop.continue(n + 1, (): Unit < Any), (_, a) => a)
assert(Eval(h1) == "B0+/recovered")   // any ClassCastException or foreign state here is the bug
```

Evidences: after the payload-throw at `Handler.scala:70` escapes, `Eval.scala:326` must not write a
previous dispatch's `out.state` into the live handler's slot, and `Eval.scala:327` must not push a
previous dispatch's continuation. Companion case: `Eval.partial` with a `stop` function that throws
after N polls, asserting the failure propagates without re-entering any scope (a sentinel
`Catching` inside a *previous* dispatch's continuation must not answer it).

### T4 (pins F4) — `"a clause throw meets the same scopes on every dispatch path"`

```scala
case class Boom() extends RuntimeException
val body: Int < Ask = Effect.catching(ask.map(_ + 1))(_ => -1) // Catching inside the region body
def viaLoop = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => throw Boom(), a => a)
def viaCont = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a)
// today: viaLoop evaluates to -1 (interior Catching answers the clause throw),
// viaCont lets Boom escape (the dumped continuation is not reattached). Pin ONE semantics:
assert(Eval(Effect.catching(viaLoop)(_ => -2)) == Eval(Effect.catching(viaCont)(_ => -2)))
```

Plus the pos-sensitivity variant: the same `handleCont` program with the suspension bare (`ask`,
fast path) versus mapped (`ask.map(f)`, general path), asserting identical outcomes. Evidences the
fast/general and family divergence; forces the clause-throw scope law to be decided and pinned.

### T5 (pins F5) — re-enable and strengthen the cross-thread preemption test

Un-comment `SafepointConcurrencyTest` "an evaluation yields to a stop requested from another
thread" (`SafepointConcurrencyTest.scala:81-106`); its blocking reason ("waiting on partial
evaluation") is stale, `Eval.partial` exists. Then add the answers-loop-specific variant, looped to
make the racy window reliable:

```scala
"a stop delivered during a fast answer loop ends the slice" in {
  // clause answers settled values so the loop iterates via nextAnswer case 1;
  // a second thread stops the target mid-loop; the slice MUST come back pending
  def countdown(i: Int): Int < Ask = if i == 0 then 0 else ask.map(a => countdown(i - a))
  val region = ArrowEffect.handleLoop(Tag[Ask], countdown(5_000_000))(
    [C] => _ => Loop.continue(1: Int < Any), a => a)
  // repeat many times: target thread runs Eval.partial(region); stopper spins Safepoint.stop(target)
  // once per run at a random small delay; assert every run either completed before the stop CAS
  // succeeded or came back pending. A run where stop() returned true AND the slice completed is
  // the lost-preemption bug.
}
```

Evidences: the top-of-loop `consumeStopped` bail must lead to a park, not to the slice continuing.

### T6 (pins F6) — `"a throwing release on the completing path leaves the caller's safepoint state intact"`

```scala
val v = Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release")) { _ =>
  ask.map(_ + 1)
}
val dropped = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
val slot = Safepoint.get()
val before = { val d = Safepoint.save(slot); Safepoint.restore(slot, d); d }
intercept[IllegalStateException](discard(Eval(dropped)))
val after = { val d = Safepoint.save(slot); Safepoint.restore(slot, d); d }
assert(after.equals(before))   // fails today: Safepoint.restore was skipped by the drain throw
```

Evidences: the finally-ordering hole; the armed-outer-slice variant (nested eval inside
`Eval.partial`, then assert a stop still parks the outer slice) pins the user-visible consequence.

### T7 (closes the N1 park gap) — `"a park is resumable more than once, and its releases run exactly once"`

```scala
var released = 0
val v: Int < Any = Effect.bracket(Effect.defer(1))(_ => released += 1)(r => burn(Period * 2).map(_ => r))
val parked = /* drive with a one-shot caller stop to a park holding the finalizer */
assert(parked.evalNow.isEmpty)
assert(Eval(parked) == 1); assert(Eval(parked) == 1)  // second resume: pin the intended semantics
assert(released == 1)                                  // run-once, as EffectTest:588 pins for continuations
```

Plus the cross-thread variant (resume on another thread; race `finalizeResources` on thread A
against a resume on thread B, assert `released == 1` always). Closes: no test today resumes one
park twice, resumes one on another thread, or exercises `Finalizer`'s CAS under real concurrency.

### T8 (closes an N2 gap) — `"concurrent attach on a shared exception instance stays bounded and non-throwing"`

Two threads loop 10k times each throwing the *same* pre-allocated exception through separate evals
with distinct region stacks; assert no exception ever escapes the trace machinery itself, and that
`ex.getSuppressed` contains exactly one `EffectTrace` whose rendered message stays within the
64-frame cap. Evidences: the shared-carrier races are last-writer-wins, never torn or unbounded.

### T9 (closes a criterion-2 gap) — `"a boxed answer crosses the answers loop unopened"`

The nested-box suite covers `handleCont` delivery but not the answers-loop settled-answer lane
(`k(Nested.unnest(ans))` at `Handler.scala:173/281`):

```scala
val payload: Int < Say = say("p").map(_ => 7)
val r: (Int < Say) < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ => box(payload)))(
  [C] => _ => Loop.continue(box(payload): (Int < Say) < Any), a => box(a))
// assert the region result IS the boxed payload (eq), then unbox and run it: exactly one unwrap
```

Evidences: the loop's unnest-then-apply must strip exactly the lift's one level, never the
payload's own pending structure.

### T10 (closes a binding gap) — `"a park holding a binding resumes on another thread against that thread's enclosing scope"`

`ContextEffectTest` pins same-thread resume and capture-time re-resolution; add: park a computation
holding a merging binding, restore it on a second thread under a *different* enclosing binding of
the same tag, assert the merge sees the resume-site enclosure (the documented `restore` →
`resolveFrom` semantics, `Stack.scala:307-310`), and that two concurrent resumes under different
enclosures do not observe each other's resolution (the ring-buffer states are per-stack; this pins
it).

### T11 — restore the two commented-out `EffectTraceTest` cases (`EffectTraceTest.scala:333, 340`)

"carried through a catching guard" and "a second crossing rewrites the spliced trace rather than
duplicating it": splice idempotence across two boundary crossings is currently unpinned; a
regression would silently double the synthesized frames on every re-throw.

### Weak-assertion audit of the existing suite

- `EvalTest` "a preemption stop reifies and resumes with handler state" asserts only
  `evalNow == Absent` then completion. It cannot distinguish the budget-drain park path (which
  works) from the answers-loop consume path (F5, which loses the stop); it also cannot see *where*
  the slice stopped. Strengthen: assert the clause-run count at the moment of parking is within
  one of the stop point.
- `SafepointConcurrencyTest` "racing stop requests never lose the slot ownership" asserts
  `consumed <= requests` and both positive, which pins the CAS bookkeeping but not the actual
  contract (a delivered stop leads to an observed yield). The only test of the contract is the
  commented-out one (T5).
- `EffectTest` "a slice stopping at every step releases once, at the end" asserts `released == 0`
  at every park but never abandons a park, so it is weaker than the resource-safety property its
  comment claims ("the eval never stops in front of a binding"); T2 is the missing half, and F2
  shows the claim itself has a hole.
- `HandlerTest` exercises the generic `HandlerCont`/`HandlerLoopState` bodies only through settled
  applications; the generic `HandlerCont.answers` (`Handler.scala:400-404`), which every
  `handleCatching` region uses on the fast path, has no test of its cell protocol at all
  (its `out.cont = k`-before-`run` ordering is load-bearing for the exception lane).
- `StackTest` "the pool" pins same-thread reuse only; nothing asserts the pool is thread-local
  (a stack migrating threads would break the `Out`-cell and ring ownership story). One-line pin:
  borrow on two threads, assert distinct instances even after both release.

---

## 5. Summary table

| id | severity | criterion | where | one-line |
|----|----------|-----------|-------|----------|
| F1 | HIGH | 4 | `Stack.scala:333`, `Eval.scala:601-624` | release/recovery throw during unwind loses the original failure, skips recoveries, mislabels remaining releases |
| F2 | HIGH | 4, 6 | `Eval.scala:428`, `Effect.scala:109` | acquire-to-binding window is parkable; abandoned park leaks the resource |
| F3 | HIGH | 1, 6 | `Handler.scala:70,186,296,366`, `Eval.scala:326-327` | Out cell never cleared; `nextAnswer`/`stop` throw commits stale state and resurrects stale continuations |
| F4 | HIGH | 4, 6 | `Eval.scala:455-460` vs `154-157/284-287` and fast catches | clause-throw scope semantics diverge across dispatch paths and handler families |
| F5 | MED-HIGH | 5, 6 | `Handler.scala:144-148,247-252,346-350`, `Eval.scala:115,428` | cross-thread stop consumed by the answers-loop bail is lost; slice fails to park |
| F6 | MEDIUM | 4 | `Eval.scala:617-624` | drain throw on the completing path skips `Safepoint.restore` (and `Stack.release`) |
| N1-N7 | NOTE | — | see section 2 | design constraints and protocol observations without independent failure scenarios |

Proposed tests: 11 new test entries (T1-T11, several with companion variants) plus 5
weak-assertion strengthenings of existing tests.
