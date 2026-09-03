# A remainder handed out by `handleFirst` and the bracket it carries

Status: analysis, needs a ruling. Evidence: four red pins (`StreamCoreExtensionsTest` "a
resource-carrying remainder from a peel is consumable afterwards", "splitAt hands out a rest
stream that still owns its resource", "a Sync.ensure remainder from a peel is consumable
afterwards", "a Sync.ensure stream zipped with another releases once after both are consumed";
`ChoiceTest` "a bracket around the choice, streamed, releases once after every branch"), all
failing with `Closed` on the resumed remainder, against the kernel pin `BracketTest` "a
handleFirst clause runs before the release its remainder runs after" (ruling Q4, `b32f3b1318`,
2026-09-01).

## Why the First handlers exist

`handleFirst` is the coroutine step: pull one operation out of a push-style computation and get
the rest back as a value. Every consumer that combines two push-style computations one step at a
time is built on it, and none of them shows a peel to the user:

| surface | shape |
|---|---|
| `Stream.zip`, `Sink.zip` | each side pulled one emission at a time, remainder continued in the loop |
| `Stream.splitAt` (`Emit.runFirst`) | the rest handed to the caller as a stream |
| `Pipe`, `Poll` compositions | one side's `Emit` feeds the other's `Poll`, step by step |
| `Choice.runStream` | branches enumerated lazily, each remainder continued by the loop |
| `Actor` (`Poll.runFirst`) | the message loop |
| `IOTask.ensureInterrupt` (`dispatchFirst`) | reads the join a remainder stands at without running it |

`handleFirst`, `dispatchFirst`, `Emit.runFirst` and `Poll.runFirst` are already `private[kyo]`, so
visibility does not change the question: the exposure is the public combinators above.

`take` is not affected: it answers inside its own region (`Loop.done`), so the bracket completes
within its extent. Main left `Sync.ensure` under `take` ignored as "not yet specified"; it works
here and is pinned green now.

## Could the combinators avoid handing the remainder out?

Only by not being coroutines. Driving both sides as fibers over a channel makes every `Sync`
stream `Async`, allocates per element, and changes ordering semantics. Turning the loop itself
into the region (one `handleLoop` over one side whose clause pulls from the other) still needs
the other side's rest reified as a value, which is the same hand-out. A dedicated stepper object
whose region lifetime spans the steps is `handleFirst` plus custody of the dumped regions by the
continuation, which is the semantics question itself, not a way around it.

## What kernel2 did (`55a634b4ea`, the tree before the proto)

kernel2's `Finalizer` was one object in two places: an arrow spliced after the extent, so a
completing remainder released where its extent ended with the value in hand, and an entry on
the stack's finalizer lane, so an eval that threw or ended holding a continuation nobody resumed
still released it. An atomic flag made the two paths exclusive. At a region's completion it
drained what the region still owed as orphans, with one explicit exemption:

```scala
// kernel2 Eval, a region completing
// a completing region orphans what it still owes, unless the completion is
// handleFirst's token: that one carries the region's own continuation onward,
// so what it owes is not orphaned yet and stays for the extents that resume it
if stack.outstanding > stack.regionMark(-1) && !r.isInstanceOf[ArrowEffect.FirstSuspended] then
    stack.drainOrphans(stack.regionMark(-1), orphanOutcome(r))
```

and at the eval's end `stack.drainFinalizers(failure)` released whatever was still outstanding,
told `Finalizer.Abandoned`. So a remainder handed out and resumed in the same eval released once
through its arrow; one never resumed released at the eval's end; one re-entered after its
release was refused (`Spent`); one carried across a fiber boundary was drained at the first
fiber's end and refused by the second, which is the still-pending fiber lane, the same on the
proto. That is exactly-once, leak-free within an eval, and it is what the two stream pins were
written against on 2026-08-25.

## The proto has the machinery; Q4 is the one difference

Dumped regions are recorded as owed on the answering region's slot (`Stack.owe`, `oweBelow`,
`takeOwed`), a resumed park settles its debt by identity from whichever lane holds it (ruling
S4), and the eval's end drains what is still owed. When a clause's outcome is still pending the
done arm already passes the debt down rather than draining it:

```scala
case pending: Pending[...] =>
    stack.pop()
    if stack.owesAny then stack.oweBelow(idx, stack.takePopped())   // carried onward
case done =>
    if stack.owesAny then drainDiscarded(stack.takeOwed(idx))         // Q4: drained here
```

`handleFirst`'s token is a settled value that carries the continuation onward exactly as a
pending outcome does, and the eval can see it (`FirstSuspended`). The kernel2 rule in the proto
is that one arm: a `FirstSuspended` result owes below instead of draining. Everything else
follows from machinery already in place:

- resumed in the same eval: `installed` settles the debt, the bracket completes with the value,
  the release runs once with `Success`;
- never resumed: drained at the enclosing region's exit or the eval's end, as discarded;
- resumed twice: the second entry finds the cell completed and is refused;
- resumed inside a nested eval: the inner eval completes the cell; the outer lane's later drain
  is a no-op on it, the cell being the exactly-once guard;
- parked while resumed: the park carries the debt and `abandon` releases it;
- a plain `handleCont` clause that stashes its continuation returns a value that is not the
  token, so "a leaked capture resumed after its region completed is refused as closed" stands.

Cost: nothing on the settled path; the token check sits in the four done arms beside the
existing `owesAny` test.

What flips: the Q4 pin becomes "a handleFirst remainder carries the bracket it was handed and
releases it when it completes", and the five red pins above go green. The cross-fiber lane keeps
its pending pin.
