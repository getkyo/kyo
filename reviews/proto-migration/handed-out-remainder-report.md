# A bracket inside a remainder that leaves its answering region

Report for an independent design exploration. It states the problem, the mechanisms involved,
the rulings in force, what the two previous kernels did, and the constraints a solution has to
meet. It does not choose. Paths are relative to the repository root; the kernel is
`kyo-kernel/shared/src/main/scala/kyo/kernel`.

## 1. The problem in one scenario

```scala
val stream = Stream:
    Sync.ensure(released.incrementAndGet.unit):          // a bracket region around the emissions
        Emit.valueWith(Chunk(1))(Emit.value(Chunk(2)))
left.zip(Stream.init(Seq("a", "b"))).run                 // or splitAt, or Choice.runStream
```

`zip` pulls one emission at a time from each side. Each pull is a `handleFirst` region: its clause
receives the operation's input and the continuation, applies the continuation to build the rest,
and returns that rest as the region's value; the loop stores the rest and evaluates it on the
next iteration, after the region ended. The bracket sits between the answering region and the
`Emit` operation, so it is captured inside the continuation. Under the ruling in force (Q4,
below) the region's exit releases the bracket it still owes; the loop then resumes the rest,
which re-installs the bracket region, finds its cell already released, and is refused:

```
kyo.Closed: Bracket resource created at StreamCoreExtensionsTest.scala:1016 is closed.
```

Five pins fail this way, all with `Closed` at the second element or branch:

- `kyo-core/shared/src/test/scala/kyo/StreamCoreExtensionsTest.scala`, group "stream resource
  cleanup (#1398)": "a resource-carrying remainder from a peel is consumable afterwards",
  "splitAt hands out a rest stream that still owns its resource", "a Sync.ensure remainder from
  a peel is consumable afterwards", "a Sync.ensure stream zipped with another releases once
  after both are consumed".
- `kyo-prelude/shared/src/test/scala/kyo/ChoiceTest.scala`, group "brackets": "a bracket
  around the choice, streamed, releases once after every branch".

The kernel pin they contradict: `kyo-kernel/shared/src/test/scala/kyo/kernel/BracketTest.scala`
"a handleFirst clause runs before the release its remainder runs after", which asserts the
release at the region's end and `Closed` on the remainder.

`take` is unaffected: it answers inside its own region (`Loop.done`), and "Sync.ensure over an
unbounded stream releases once when take ends it" is green. The cross-fiber hand-out (a
remainder crossing a fiber boundary) is a separate, already pending lane: "a self-contained
stream's resource does not survive a fiber hand-out" is green on the current behavior and
"a resource-carrying remainder crosses a fiber boundary" is `pendingUntilFixed`.

## 2. Who depends on handing a remainder out

`ArrowEffect.handleFirst` and `dispatchFirst` (ArrowEffect.scala), `Emit.runFirst`
(kyo-prelude `Emit.scala`) and `Poll.runFirst` (`Poll.scala`) are all `private[kyo]`. Their
users, all in the repository:

| surface | how the remainder leaves the region |
|---|---|
| `Stream.zip`, `Stream.splitAt` (`Stream.scala` 667, 690) | loop state between iterations, or handed to the caller as a stream |
| `Sink.zip` (`Sink.scala` 44) | loop state |
| `Pipe` and `Poll` compositions (`Pipe.scala` 247, `Poll.scala` 170, 214) | one side's `Emit` feeds the other's `Poll`, step by step |
| `Choice.runStream` (`Choice.scala` 124) | every pending branch's remainder continued by the loop |
| `Actor` (`Actor.scala` 751, through `Poll.runFirst`) | the message loop |
| `IOTask.ensureInterrupt` (`scheduler/IOTask.scala` 333, `dispatchFirst`) | reads the join's input, never resumes |

All but the last are loops over unbounded sources: the region must end between steps, or one
region nests per element.

## 3. The mechanisms in the proto kernel

- A handler region answers an operation by dumping the regions between it and the operation
  into the continuation (`Arrow` with entries). The dumped regions are recorded as *owed* on the
  answering region's stack slot: `Stack.owe`, `oweBelow`, `takeOwed`, `takePopped`,
  `takeEvalOwed` (`internal/Stack.scala`). A resumed park or continuation re-installs its
  regions (`Eval.installed`) and *settles* the debt by identity from whichever lane holds it
  (`Stack.settle`; ruling S4).
- When a clause's outcome is a pending computation, the eval passes the debt below rather than
  draining it (`Eval.scala`, the `case pending` arms: `stack.oweBelow(idx, stack.takePopped())`).
  When the outcome is settled, the eval drains what the region owes with a
  `KyoException("remainder discarded")` (`drainDiscarded`, the four `case done` arms), and again
  at `contextExit`, `arrowExit`, and the eval's end.
- `handleFirst` is a `handleCont` whose clause returns a `FirstSuspended` token (input plus
  continuation) as a settled value; the `done` lane unwraps it into the caller's `handle`
  (`ArrowEffect.scala` 264). At the eval's done arm the token is the region's `result`.
- `Bracket` (`Bracket.scala`) is a `ContextHandler` region whose state is a `Cell`
  (`AtomicBoolean`): `done(state, value)` completes it with `Success(value)`, `release(state, ex)`
  drains it with `Panic(ex)`, `reenter(state)` throws `Closed` when the cell is already set,
  `fork(parent)` hands an isolated child `Cell.inert`. The release signature is
  `(A, Result[Nothing, B]) => Unit`.
- Parks carry their entries and owed snapshots (`Pending.Park(value, entries, owed)`);
  `Eval.release(park, ex)` and `IOTask.abandon` release what a dropped park still holds.

## 4. Rulings in force that a solution must respect or explicitly revisit

- Q4 (`b32f3b1318`, 2026-09-01): "a bracket lives as long as the region that answers the
  suspensions inside it. A handleFirst clause still runs inside its region and sees the bracket
  open; the region exits when the clause's value is delivered, so the remainder that value runs
  is refused at its first branch." This is the ruling the five pins contradict.
- S3: forked and joined copies of a region are silent to `done` and `release`.
- S4: a resumed park settles its debt by identity from whichever lane holds it.
- Bracket not inheritable (this week): a bracket belongs to the computation that installed it and
  closes only with its own scope; an isolated child, a spawned fiber included, gets an inert copy.
- "a leaked capture resumed after its region completed is refused as closed" (BracketTest 262): a
  `handleCont` clause that stashes its continuation and returns a plain value has discarded the
  remainder; resuming the stash later is refused. Distinct from `handleFirst`, whose clause
  returns the continuation as the region's value on purpose.
- "a bracket outside the answering handler is not carried by an escaped continuation, so the
  remainder runs after the release" (BracketTest 654): a bracket below the handler is not part of
  any captured continuation.
- Release outcomes: `Success(value)` on completion, `Panic(throwable)` on unwind, the discard
  signal on abandonment; the bracket, not a handler, is the exactly-once guard.

## 5. What the two previous kernels did

- main (`origin/main`): `Sync.ensure` registered a finalizer with the fiber's `Safepoint`
  (`Safepoint.ensure`, `IOTask.finalizers`); a handed-out remainder kept its finalizer, which ran
  when the ensured computation completed or at the fiber's end. Custody with the continuation,
  fiber as backstop; a dropped remainder leaked until the fiber ended.
- kernel2 (`55a634b4ea`, `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Finalizer.scala`,
  `Eval.scala` 676-694, 760-775): a `Finalizer` was one object in two places, an arrow spliced
  after the extent (release with the value when the remainder completes) and an entry on the
  stack's finalizer lane (release on throw, or at the eval's end told `Abandoned`), with an
  atomic flag making the paths exclusive. A completing region drained what it still owed,
  *except when its completion was `handleFirst`'s token*, which "carries the region's own
  continuation onward, so what it owes is not orphaned yet and stays for the extents that resume
  it". The two stream pins were written against kernel2 and were green there.

## 6. Constraints on a solution

From `kyo-kernel/.claude/skills/kernel/SKILL.md` and CONTRIBUTING.md, binding here:

- Composition first: every evaluator behavior is the operational reading of an equation in the
  public combinators. Wanting a new node kind is the signal of being off the path.
- Correct by construction over correct by inspection: an invariant should be impossible to
  violate rather than hold because every site remembered to.
- Exactly-once release, no use after release, no leak that outlives the eval that could drain it,
  bounded stack for unbounded streams, no cost on the settled path.
- The cast ladder: no casts beyond the closed categories, no new types that do not earn
  themselves, no `Any` where a type can be stated.
- Kernel changes are the user's to rule on; this exploration is analysis only.

## 7. Directions named so far, none adopted

- Custody with the continuation: treat `FirstSuspended` like a pending outcome (owe below
  instead of draining), the eval's end as backstop. kernel2's rule; smallest change; the
  guarantee rests on the token check at the done arms.
- A law on users: keep Q4 and require a resource spanning emissions to be scoped outside the
  combinator (`Scope` in the row, `Scope.run` around the whole `zip` or peel). `Sync.ensure` has
  no row to lift, so an ensure around a multi-emission stream body would be refused the moment
  the stream is combined.
- `handleFirstWith`: the consumer runs inside the clause so the region spans the use. Sound for
  finite consumers (`ensureInterrupt`), nests one region per element for loops.
- A fiber per side with a channel: changes every `Sync` stream into `Async`, allocates per
  element.

The question for the exploration: is there a design that gives the guarantees by construction,
rather than by a check in the evaluator, and what does it cost against the constraints above?
