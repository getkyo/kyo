# Custody of a bracket carried by a handed-out remainder

Held-out design review, analysis only. Everything below was established by reading; nothing was run. Line numbers are against the worktree at `5c695acd92` plus the uncommitted tree; kernel paths are relative to `kyo-kernel/shared/src/main/scala/kyo/kernel`. Claims I could not verify by reading are marked unverified.

## 1. Verdict in short

The kernel already has two exit laws, and `handleFirst` is filed under the wrong one.

- An exit whose outcome is settled drains what the region owes, because nothing can resume it (Eval.scala:116-123, 149-161, 178-185, 212-225, and the `ArrowHandler` settled exit at 267-272 through `arrowExit` 354-358).
- An exit whose outcome is still in flight passes the debt to the region below, because the outcome may re-enter through the crossing, and whoever re-installs it settles the debt by identity (Eval.scala:109-115, 139-148, 171-177, 202-211; `Stack.oweBelow` 59-63; `Stack.settle` 65-75; `Eval.installed` 318). That law is what `Stream.take` relies on today: its clause answers `Emit.valueWith(c)(...)`, a pending outcome, so the bracket it dumped is owed below and released at the enclosing exit, and "Sync.ensure over an unbounded stream releases once when take ends it" (StreamCoreExtensionsTest.scala:976-988) is green on it.

`handleFirst`'s clause is typed `(I[C], Arrow[O[C], A, E & S]) => B < (S & S2)` (ArrowEffect.scala:265): the continuation goes into a value at the outer row. By the skill's own rule ("signatures are semantics; read the rows as region geography", SKILL.md:274-280) that is an in-flight exit. It is realized today as a `handleCont` whose clause returns a settled token (ArrowEffect.scala:268-282), so the evaluator takes the settled-exit law, drains at the region's end, and the remainder is refused when it re-installs the bracket (Bracket.scala:70-72). That is ruling Q4.

Q4's recorded objection (backlog.md:156-167) is against custody by the token: "the token carrying its owed dumps until resumed or released cannot rule out a holder dropping the token and leaking the resource". The in-flight law does not put custody in the token. It puts it in the enclosing scope: the debt sits on the slot of the region below (or the eval lane), is settled if the continuation re-installs it (Stack.scala:65-88, `Eval.installed` 318), and otherwise is drained where that scope ends: `contextExit` (346-352), `arrowExit` (354-358), the unwind (384-386, 409-410, 426-428), the eval's end (440), or a park carried to `abandon` (298, IOTask.scala:467-474, Eval.scala:508-522). A holder that drops the value leaks nothing past the enclosing scope. This is the guarantee `take` has today and the one kernel2 had (its region completion drained orphans up to the enclosing region's mark, with the `FirstSuspended` exemption at kernel2 Eval.scala:681 and 689, and the eval's end drained the rest at 770).

Recommendation: revisit Q4 as an argued correction, and realize the in-flight exit for `handleFirst` by handler kind (candidate A, section 4), which deletes `FirstSuspended`, the `A | FirstSuspended` union, and the two casts at ArrowEffect.scala:274 and 281. The token check (candidate B) is the same law with a weaker realization and is the fallback.

One of the five red pins is not this question. ChoiceTest "a bracket around the choice, streamed, releases once after every branch" (ChoiceTest.scala:401-417) asks a multi-shot handler to run three branches against one bracket dumped into the continuation and release after the last. That contradicts the multi-shot law pinned at BracketTest.scala:360-372, 1227-1245 and 1247-1266, kernel2 refused it too (`Finalizer.Spent`, kernel2 Finalizer.scala:66-83), and no custody design can satisfy it, because no evaluator knows which shot is the last. Section 3 shows why and what the pin should say instead.

## 2. The mechanism, precisely

The scenario is `Stream(Sync.ensure(f)(Emit.valueWith(Chunk(1))(Emit.value(Chunk(2)))))` pulled by `zip` (Stream.scala:690-757). The trace, with today's evaluator:

1. `step1` installs a `ContHandler` region for `Emit` (ArrowEffect.scala:701-705 through `handleCont` 152-178). Inside it the bracket's `HandleContext` node pushes the `Finalize` region with the cell as state (Bracket.scala:73-77, Eval.scala:237-244).
2. The first `Emit` suspension finds `step1`'s region below the bracket, so it is not at the top (Eval.scala:64-70). The bracket region is dumped: `Stack.dump(idx + 1)` moves it into a snapshot and appends that snapshot to the answering region's lane, `owed(idx)` (Stack.scala:178-198, the append at 196). The continuation handed to the clause is `kyo.crossing(entries, ...)` (Eval.scala:78; PendingInternal.scala:47-63), whose application yields `Park(Effect.defer(v, kc, resume), entries)` over the same snapshot object.
3. The clause returns the `FirstSuspended` token, a settled value (ArrowEffect.scala:269-275). The evaluator loops it with the region still installed (Eval.scala:85), reaches the settled exit (256-273), calls `handler.done` (269), which is where the user's `handle` runs and builds `Maybe((vals, cont(())))` (276-281), and then `arrowExit()` pops and drains `owed(idx)` with "remainder discarded" (354-358, 468-472). The cell is now set with a `Panic`.
4. The loop later evaluates the `Park`: `installed` calls `reenter` on the bracket region (302-316), the cell is set, `Closed` (Bracket.scala:70-72).

Under the in-flight law, step 3 pops the region and passes `owed(idx)` to the region below instead of draining it; step 4's `installed` calls `stack.settle(entries)` (Eval.scala:318), which removes the snapshot from whichever lane holds it (Stack.scala:65-88, ruling S4), then pushes the bracket region back. The body completes, `contextExit` calls `done` (346-347), the cell completes with `Success`, the release runs once. The next `Emit` suspension dumps the region again, into a fresh snapshot owed on the new `step1` region, and the cycle repeats: at most one snapshot per side is outstanding at any time, so the lanes stay bounded for an unbounded stream.

Every consumer in the report's table has this shape. `Stream.zip` and `Stream.splitAt` (Stream.scala:690-757, 667-682), `Sink.zip` (Sink.scala:41-70), `Pipe.transform` (Pipe.scala:240-260), `Poll.runEmit` (Poll.scala:210-236), `Choice.runStream` (Choice.scala:114-131), the actor loop (Actor.scala:751-765 through `Poll.runFirst`, Poll.scala:163-180). Only `IOTask.ensureInterrupt` uses `dispatchFirst` (IOTask.scala:332-335), which pushes no region (ArrowEffect.scala:294-309) and is untouched by anything below.

Blast radius today, beyond the pins: `mapPar` builds its stream body as `Channel.use(...)`, which is `Sync.ensure(Channel.close(channel))` (Channel.scala:349-354), around `Meter.useSemaphore`, which is `Sync.acquireReleaseWith` (Meter.scala:179-180), around `Sync.ensure(channelOut.close)(...)` (StreamCoreExtensions.scala:450), and the emissions happen inside all of them (`Emit.value(chunk)` at 444). `mapParUnordered` and `mapChunkPar` have the same shape (510, 622). So under Q4 any peel of a `mapPar` stream (`zip`, `splitAt`, a `Pipe`, a `Sink.zip`) should be refused at its second chunk. Predicted from reading, not run: unverified, and no test in StreamCoreExtensionsTest combines `mapPar` with a peel.

## 3. The five pins, classified

Four are the hand-out question and go green under the in-flight law: StreamCoreExtensionsTest "a resource-carrying remainder from a peel is consumable afterwards" (1004-1018), "a Sync.ensure remainder from a peel is consumable afterwards" (1020-1037), "a Sync.ensure stream zipped with another releases once after both are consumed" (1039-1052), "splitAt hands out a rest stream that still owns its resource" (1054-1068). In each, every remainder is resumed exactly once inside the same eval, so the bracket completes with `done` and the eval's end finds nothing owed.

The fifth is different. `Choice.runStream` applies the continuation to every branch value (`Chunk.from(input).map(cont(_))`, Choice.scala:125), producing one `Park` per branch over the same snapshot. `evalNow` is not an evaluation (Pending.scala:352-355 returns `Absent` for any `Pending`), so each `Park` is evaluated by the next iteration's `handleFirst` region. Branch 1 re-installs the bracket, completes it, the release runs (Bracket.scala:68, Cell.complete 35). Branch 2 re-installs the same cell and is refused (70-72). The pin expects "branch1, branch2, branch3, release". Making that true means deferring `done` for a replayed region until the last replay, which no evaluator can know; kernel2 did not do it either (`Spent`, kernel2 Finalizer.scala:66-83). The pin was born red in `e1c7ff7c5a` and has never been green anywhere.

The intent the pin reaches for has a spelling that works: put the bracket outside the loop that replays, `Stream(Bracket(acquire)(_ => Choice.runStream(v).emit)(release))`. There the bracket is below the answering `Choice` region, so it is not part of any branch's continuation (the law pinned at BracketTest.scala:654-678), every branch runs against the live resource, and the release runs once when the stream body completes. That stream, once zipped or split, is exactly the hand-out case of section 2. So the pin should become two: the bracket-inside spelling asserting refusal at branch 2 and one release, matching BracketTest 1227-1245; and the bracket-outside spelling asserting "branch1, branch2, branch3, release". Owner's call (open question 4).

## 4. Candidates

Each candidate states the equation it is the operational reading of, what it guarantees and cannot, what changes, the hot-path cost, and the failure it introduces.

### A. Custody with the enclosing scope, decided by the handler kind (recommended)

Equation. For a body `R[op ▷ rest]` where `R` are the regions between the answering handler and the first `E` operation:

```
handleFirst(tag, R[op ▷ rest])(handle, done)  =  handle(input(op), k)      k = o => R[rest(o)]
```

evaluated at the outer row, with `R`'s obligations belonging to the scope enclosing the `handleFirst` until `k` re-establishes them or that scope ends. Compare `handleCont`, `done(h(input, k))` evaluated inside the region with `R` owed to the region, and `handleLoop`'s pending exit, whose clause outcome is evaluated at the outer row with `R` owed below and re-entered by `Continue` through `clauseDispatch` (Handler.scala:76-93). `handleFirst` is the loop form with the outcome `Loop.done(handle(input, k))` and `k` in hand; the only thing the loop family lacks is the continuation in its clause, which D2 hid so `answersLoop` can fuse (Handler.scala:204-278).

Realization. A `Handler.FirstHandler[I, O, E, A, B, S] extends ArrowHandler[Unit, E, A, B, S]` in Handler.scala (it must live there, `Handler` and `ArrowHandler` are sealed, 20 and 28) with `run[X](input: I[X], cont: Arrow[O[X], A, E & S]): B < S` and an `answering` that attaches the trace on a throw like `ContHandler.answering` (37-42). One arm in the `SuspendArrow` dispatch (Eval.scala:71-229), placed after the existing arms so nothing on their paths changes:

```
case handler: Handler.FirstHandler[IX, OX, EX, C, Y, S2] @unchecked =>
    val entries      = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
    val ctx2         = if atTop then ctx else rebound(entries, ctx)
    val continuation = if atTop then kyo.cont.chain(contA.chain(contB)) else kyo.crossing(entries, contA.chain(contB))
    val result       = handler.answering(kyo.input, continuation, kyo, stack)
    val next         = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
    Debugger.onRegionExit(handler, result)
    stack.pop()
    if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
    if armed && Safepoint.stopped(slot) then park(result, next, Arrow.id) else loop(result, next, Arrow.id, ctx2)
```

The clause runs before the pop, with the debt on the region's slot, so a throw in it unwinds the region as today ("a clause that throws after capturing releases the bracket with the failure", BracketTest.scala:249-260; `recovered` 384-386). Then the region is gone and the debt is below. There is no state in which the region is installed while its continuation is out in a value, which is the state that Q4 had to rule on. `handleFirst` in ArrowEffect.scala:264-282 becomes a `HandleArrow` over a `FirstHandler` with `done` straight from the user's `done`; `FirstSuspended` (242-246), the union `A | FirstSuspended[...]` (268), the `@unchecked` typed pattern (278), and the casts at 274 and 281 are deleted. The settled exit at 256-273 needs nothing: a `FirstHandler` region that completes without its operation is an `ArrowHandler` and takes `done` there; its slot cannot owe anything, since it answers at most once and pops in the same arm. `dispatchFirst` is untouched.

The continuation's row. Today the public clause receives `Arrow[O[C], A, E & S]` and the cast at 274 narrows the region's `E & S & S2` to it; `Arrow` is contravariant in the row (Arrow.scala:14), so the narrowing is not a subtyping step. With `FirstHandler[.., S & S2]` the continuation the eval builds is at `E & S & S2`. Either keep the public row and one categorized cast (a representation assertion: the body never raises `S2`), or widen the public row to `E & S & S2` as `handleCont` already does (ArrowEffect.scala:132). Every caller in the repository has `S2 = Any` or `S2 = E` (Emit.runFirst, Poll.runFirst, zip, splitAt, runStream, Pipe, Poll.runEmit return settled values from the clause; Sink.zip's clause raises the same `Poll` it handles), so the widening changes no call site. Widening is the cast-free spelling and the one I would take (open question 3).

Guarantees. Exactly-once by the cell (Bracket.scala:34-39), reachable on every path: completion through `contextExit`, unwind through `released`, discard through the enclosing scope's drain or the eval's end, abandonment through `Eval.release`. No use after release: `reenter` at every re-install (Eval.scala:306-316). No leak past the eval: every dumped snapshot is in exactly one lane, one park, or installed, from `dump` to `settle` or drain, and the eval's end drains its lane (440). Bounded lanes for unbounded streams, by the loop shape (section 2). Nothing on the settled path changes. The cast count goes down by two.

What it cannot do. A remainder dropped without being resumed holds its resource until the enclosing scope ends, not until the drop. For `zip` that is at most one remainder per side over the stream's life (the `Loop.done(())` arms, Stream.scala:731 and 741), for `splitAt` one per call, for `Pipe.transform` one at the end; `Poll.runEmit` runs the emitter's remainder to completion instead (`Emit.runDiscard`, Poll.scala:228), `Sink.zip` runs both sides, `Choice.runStream` runs every branch. A user who calls `splitAt` in a loop and drops every rest accumulates one snapshot per drop on the enclosing lane until it exits; `Scope.run` bounds it, since its bracket region sits below and drains at its `contextExit`. Custody cannot cross an eval: a remainder handed to another fiber is drained at the peeling eval's end (440) and refused by the other, which is today's behavior and the green pin at StreamCoreExtensionsTest.scala:1075-1097. The report names a `pendingUntilFixed` pin for the cross-fiber lane; I could not find it in this tree (grep for the name and for `pendingUntilFixed` in StreamCoreExtensionsTest finds nothing): unverified.

Pins that change. BracketTest "a handleFirst clause runs before the release its remainder runs after" (1288-1313) becomes: `closedAtClause` false, first shot runs (`seen == List("branch 10")`), release once, `Closed` at the second shot, which is the multi-shot law applied to a handed-out remainder. ContextEffectTest "a handleFirst remainder re-enters a raw region the region's end already released" (507-519) becomes `List("clause", "done cfg 1")`: the raw region is no longer released and then revived, which is the "one of done or release per instance" direction open-issues.md §2 names for S9. The four stream pins go green. Nothing else in BracketTest exercises a `handleFirst` exit; the `handleFirst` groups in ArrowEffectTest (134-241, 2116-2295) carry no bracket and every one of them evaluates the clause's value after the region ended already, so "an operation raised by the clause reaches the outer handler" (2222-2237) and "the innermost handleFirst wins" (2271-2281) are unchanged. IsolateTest's `continuationOf` (453-468) stashes a continuation from a `handleFirst` clause and resumes it in later evals; its raw region is released at the first eval's end and revived by each resume, exactly as today.

Cost. On the settled path, none. On a suspension, `FirstHandler` regions pay the failed class tests of the arms before theirs; regions of other kinds pay nothing new. The `loop` method grows by the arm, which the skill treats as a design property to measure (SKILL.md:493-510), and every `ProtoBench` row must be run on both tips per open-issues.md §5 (`-f 1` then `-f 3`, `-prof gc`), the crossing rows in particular. Per stream element, versus today: one `Chunk.concat` in `oweBelow` (Stack.scala:59-63) and one lane rebuild in `settle` (77-88) replace one `drainDiscarded`; `settle` already walks every lane on every crossing resume today, it just finds nothing. Unverified until measured; if `loop`'s size regresses, the TODO at Eval.scala:73 and 101 (move the at-top branching out of line) is the lever, not dropping the arm.

Failure introduced. None found. The one semantic change beyond the pins is that the clause's effects and throws are attached to the region while it is still installed, and its value is evaluated once it is gone, which is what `handleCont`'s `done` already does (271-272) and what the signature says.

### B. The same law, decided by the token at the settled exit

Equation: identical to A. Realization: the note `handle-first-custody.md` proposes. In the `ArrowHandler` settled exit (Eval.scala:267-272), `arrowExit()` takes `res` and, only when `stack.owesAny`, passes the popped lane below when `res` is a `FirstSuspended` and drains otherwise. One site: the loop family's done arms cannot produce the token, since loop clauses never hold `k`. Guarded by `owesAny`, so the class test runs only for a region that dumped something.

What it guarantees: everything A does, with the same pins flipping. What it cannot: it keeps the region installed while the user's `handle` runs inside `done` (269), keeps `FirstSuspended`, the union, and the two casts, and the rule "a settled value that holds the continuation" is knowable only from the value's class. A future exit site (a new handler family's settled arm) has to remember it. That is correctness by inspection, in the one place the skill says to avoid it. Cost: one class test under `owesAny`. Failure introduced: none, same reasoning as A.

If the owner wants the smallest diff first, B is it, but it is not a stepping stone to A: A replaces it entirely, so landing B first is paying twice.

### C. No region drains at a settled exit; every debt flows to the enclosing scope

Equation: every exit is the in-flight exit. Rejected on a correctness argument, not scope. `Abort.run`'s clause is `(input, _) => input` (Abort.scala:224): it drops the continuation, and a bracket inside an aborted body is released by the discard drain at `Abort.run`'s own exit. Under C that release moves to the enclosing scope, so `Abort.run(Bracket(acq)(_ => Abort.fail(e)))` inside a long-lived loop holds its resource for the loop's life. The pins that hold the line: BracketTest "a discarded continuation releases when its region completes, before the handler's continuation" (1114-1129) and "a region installed inside the use does not intercept the release" (1097-1112). This is also main's weakness, custody with the fiber (main Safepoint.scala:157-166, 169-192), which the report already calls a leak.

### D. The debt travels in the value

Equation: the First exit returns `Park(handle(i, k), Snapshot.empty, owed)`, the node the evaluator already builds for a park with nothing installed (Eval.scala:290) and consumes at 246-248 by owing to the current top. Within an eval this is A's `oweBelow` with an allocation and a round trip through the loop; the value is consumed one step later. Across evals it could only differ if an eval returned the `Park` unconsumed as its result, past the drain at 440, and then a dropped value is a leak nobody can drain. That is the constraint "no leak that outlives the eval able to drain it" stated as a prohibition. Not a design; recorded because it looks compositional and is ceremony.

### E. A consumer-held stepper or close token

The region cannot stay installed while the consumer runs (the consumer is the region's `next`), so a stepper is a detached pair: resume and close. The kernel already has both: the crossing arrow is resume (PendingInternal.scala:47-63) and `Eval.release(v, ex)` is close (Eval.scala:493-528), the pair `IOTask.abandon` uses (IOTask.scala:467-474). A stepper type adds a name, not a guarantee; the guarantee is still the enclosing scope's drain. What a close does add is promptness for the drops in section 4.A's "cannot": `zip` and `Pipe.transform` could release a dropped remainder at the drop. That is a prelude refinement on top of A, spelled with `Eval.release` (reachable from package `kyo`) or a public discard whose equation is the abandonment law already visible through fiber abandonment. Not required for correctness; open question 6.

### F. Ownership in the types

Scala has no linear types, so "resume exactly once or close" cannot be a type. A one-shot flag on the arrow is a runtime guard weaker than the one that exists (the cell refuses at re-install, per region, Bracket.scala:70-72, which also covers a replay inside the body). A marker effect in the handed-out continuation's row (an `Owed` the consumer must discharge) forces a discharge site, but the discharge handler is where exactly-once would be implemented, so it is the same accounting with more surface, and every stream combinator's row changes. The type that does carry the semantics is `handleFirst`'s signature itself; A is what makes the evaluator honor it, and the row widening in A is the one type-level improvement available.

### G. A law on users: keep Q4, resources spanning emissions live outside the combinator

Equation: no bracket is ever between an answering handler and its operation, so no bracket ever enters a continuation. Sound by construction, and the only candidate that is: with `Scope` in the row the `Scope.run` region sits below every peel (the green pin at StreamCoreExtensionsTest.scala:1099-1119; `StreamCompression` already works this way with `Scope.acquireRelease` and `Emit.runFirst`, StreamCompression.scala:156, 166, 240, 277, 359, 363).

What it costs. `Sync.ensure` has no row to lift, so `Stream(Sync.ensure(close)(loop emitting))` is refused by every peel. The repository's own `mapPar`, `mapParUnordered` and `mapChunkPar` are that spelling (section 2): under G they must change their public rows to carry `Scope`, or hold their channel elsewhere, which for a stream that owns its channel does not exist. The four stream pins invert to assert `Closed` or are rewritten with `Scope` in the row. Q4 stays. Zero kernel change. Ranked below A and B because it forbids the spelling the repository already uses and moves the cost onto every parallel stream combinator's API.

### H. Restructure the combinators

`handleFirstWith` (the consumer runs inside the clause) nests one region per element: the `Stack` grows on the heap (Stack.scala:28-38, 200-219), so no stack overflow, but unbounded growth and an `O(depth)` `settle` walk per resume (65-75). A fiber per side over a channel makes every `Sync` stream `Async` and allocates per element. One loop region over one side whose clause pulls from the other still reifies the other side's rest as a value, the same hand-out. Agreed with `handle-first-custody.md`: the coroutine step is the combinator, and the hand-out is its meaning, not an accident of the encoding.

## 5. Interactions with rulings and open lanes

- Q4 is revisited, with the loop family's pending exit as the precedent and Q4's own objection answered by custody with the scope rather than the token. Q3, S3, S4 and "bracket not inheritable" are untouched: forks still get `Cell.inert` (Bracket.scala:66), `settle` is the same identity search, and the spawn shape's pins (BracketTest.scala:600-652) do not involve a `handleFirst` exit.
- "A leaked capture resumed after its region completed is refused as closed" (BracketTest.scala:262-282) stands: a `handleCont` clause that stashes and returns a plain value is a settled exit and drains. The distinction the report draws between that pin and `handleFirst` becomes the distinction between handler kinds under A, and between a plain value and the token under B.
- The multi-shot law stands and is what the flipped Q4 pin asserts.
- S9 (open-issues.md §1 and §2). The raw-region pin flips to "done only" for the same-eval case, which is the direction §2 names. The cross-eval double fire (done in a nested eval, release at the outer lane's drain) is unchanged, and so is the multi-shot re-owe of inner snapshots at `installed` (333, 340). One constraint for whoever rules S9: IsolateTest 453-476 pins that a raw region stashed out of a `handleFirst` and resumed in later evals, twice, revives each time; a released mark that refuses re-entry would invert those kernel pins.
- The cross-fiber lane stays as the report describes. Nothing here changes what the peeling eval's end drains.
- EffectTrace: `FirstHandler.answering` attaches with the region installed, as `ContHandler.answering` does. Debugger: `onRegionExit` moves to the pop in the arm.

## 6. What changes, by file

Under A: Handler.scala (the family, beside `ContHandler` at 34-43); Eval.scala (the arm in the `SuspendArrow` dispatch; nothing at the settled exit); ArrowEffect.scala (264-282 rebuilt over the family, 242-246 deleted, the scaladoc at 248-262 stating custody); Bracket.scala scaladoc (12-25, add the hand-out sentence); BracketTest.scala 1288-1313 and ContextEffectTest.scala 507-519 restated; StreamCoreExtensionsTest 1004-1068 unchanged and green; ChoiceTest 401-417 restated per section 3; backlog.md Q4 entry and open-issues.md §2 updated. Emit.scala, Poll.scala, Stream.scala, Sink.scala, Pipe.scala, Choice.scala, Actor.scala: no change unless the row is widened, and even then their inferred `S2` makes the widening invisible.

Under B: Eval.scala 267-272 and 354-358 only, plus the same pin and document changes.

## 7. Ranked recommendation

1. A. The in-flight exit by handler kind. It is the operational reading of `handleFirst`'s signature, it reuses the exit law the loop family already has, it removes a type, a union and two casts, and it leaves no state for a future site to get wrong.
2. B. The same law by token check. Smallest diff; keeps the encoding A removes; the rule lives in a value's class.
3. G. Keep Q4 and make `Scope` in the row the law. Sound by construction, at the price of the repository's own parallel stream combinators and of `Sync.ensure` inside any stream body.
4. E, as a prelude follow-up to A: prompt release of the remainders `zip` and `Pipe.transform` drop.
5. C, D, F, H: rejected for the reasons given.

## 8. Open questions for the owner

1. Revisit Q4 as argued: is `handleFirst`'s exit the in-flight exit, with custody in the enclosing scope, the loop family's pending exit being the precedent?
2. A or B: the handler family plus one arm, or the token check at the settled exit.
3. Under A, widen the public continuation row to `E & S & S2` (no cast, no call site changes) or keep `E & S` with one categorized cast.
4. ChoiceTest 401-417: restate to the multi-shot law and add the bracket-outside spelling, or hold the pin red as a marker for a law that has no evaluator.
5. ContextEffectTest 507-519 flips to `("clause", "done cfg 1")`: accept now as the S9 direction, or hold the flip until S9 is ruled as a whole.
6. Whether `zip` and `Pipe.transform` should release a dropped remainder at the drop, and if so through `Eval.release` or a public discard.
7. The measurement plan: the `ProtoBench` rows on both tips, and a stream benchmark for the per-element `oweBelow` and `settle` cost, since none exists in `ProtoBench` that I could see (unverified).
8. Confirm the location of the cross-fiber `pendingUntilFixed` pin the report names; I could not find it, and A does not touch that lane either way.
