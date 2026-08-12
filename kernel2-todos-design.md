# kernel2 pending TODOs: diagnosis and designs

Worktree `/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus`,
branch `worktree-effervescent-painting-backus`. Written against HEAD
`d830408a4842c724aac93ba0fec352ff9d50a812` (`[kernel2] handleFirst and handleCatching pins`)
and re-anchored to HEAD `db363ae396` (`[kernel2] bracket design triple`). The branch is
receiving concurrent commits, so every line number below is anchored to `db363ae396`; the
three commits between the two hashes touched only tests, `kyo-kernel2/CONTRIBUTING.md`, and
the design documents, and every source citation below was re-verified against `db363ae396`.

Analysis only: no source file was edited, no build was run.

## Inventory

Four TODOs in `kyo-kernel2` main sources at HEAD, re-grepped rather than taken from the brief:

| # | site | text |
|---|---|---|
| 1 | `kyo-kernel2/shared/src/main/scala/kyo/kernel/Effect.scala:27` | "this seems an expensive workaround for something that should be handled in Eval or Arrow?" |
| 2 | `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Handlers.scala:8` | "can we encapsulate so the internal representaiotn is easier to evolve later?" |
| 3 | `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala:38` | "no, this is not acceptable, we need to fully review. Let's discuss" |
| 4 | `kyo-kernel2/shared/src/main/scala/kyo/kernel/Loop.scala:183` | "why call explicitly? also it can never be a nested computation? In fact, why is continue returnin ga computation? it seems it shouldn't" |

All three bracket design documents (`bracket-node-design.md`, `bracket-handler-design.md`,
`bracket-effect-design.md`) landed at `db363ae396`, after this document's first draft. Both
architectural candidates independently arrive at the same evaluator mechanism item 1
proposes, and the node design explicitly defers the guard question to this TODO
(`bracket-node-design.md:754-757`). Section 1.7 folds both in, and section 1.10 records one
argument in the handler design that item 1's fix invalidates.

The four items are not independent. Items 1, 2, and 3 all reduce to the same structural
question: how many places in the kernel enumerate the kinds of thing that can sit on the
handler spine or in the node ADT, and what happens to that count when the bracket, isolate,
and enrichment tracks each add one. Item 4 is independent and is the only one of the four
that is a live compile-level parity defect against `origin/main`.

---

## 1. `Effect.guarded`: failure recovery is a region, not an arrow rewrite

### 1.1 What the code does now

`Effect.catching` (`kyo-kernel2/.../kernel/Effect.scala:15-20`) has two halves. The outer
`try` covers construction of `v`. Everything that happens later is covered by `guarded`
(`Effect.scala:28-78`), which rewrites the value:

- A `Kyo.Suspend` is rebuilt with `cont = guard(k.cont)` (`Effect.scala:54-61`).
- A `Kyo.Defer` is rebuilt with `cont = guard(kyo.cont)` (`Effect.scala:62-63`).
- `Kyo.Handled`, `Kyo.HandledFirst`, and `Kyo.HandledState` are rebuilt with
  `exit = guard(kyo.exit)` (`Effect.scala:64-74`). Note that `value` is left alone: the
  region's interior is deliberately not rewritten.
- Anything else passes through (`Effect.scala:75-76`).

`guard` (`Effect.scala:29-52`) wraps one arrow in an `Arrow.Transform` whose `apply` runs one
continuation step inside a `try`, and then calls `guarded` again on whatever that step
produced, so the rewriting propagates forward.

### 1.2 The cost, stated concretely

Per resumed step under a `catching`, the current shape pays:

- one `Arrow.Transform` allocation for the guard (`Effect.scala:30-52`),
- one node re-allocation for whichever of the five node kinds came back
  (`Effect.scala:54-74`),
- one `cont.step` call (`Effect.scala:37`), which for an `Arrow.AndThen` runs the
  thread-local flatten-and-relink in `Arrow.AndThen.step` (`kyo-kernel2/.../kyo/Arrow.scala:112-138`),
- one five-way pattern match over the node ADT.

The recursion is per step, not per `catching`, because `guard`'s body calls `guarded` on the
step result (`Effect.scala:35-42`). So the mechanism's cost is proportional to the number of
steps executed inside the scope, and the allocations land on the same path the kernel
otherwise keeps allocation-free (`kyo-kernel2/CONTRIBUTING.md:167`).

That is the maintainer's "expensive". It is accurate.

### 1.3 The deeper problem: the mechanism is a port from a different model

`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/Effect.scala:39-61` is the same
shape: `catchingLoop` rewrites a `KyoSuspend` into a `KyoContinue` whose `apply` wraps the
next step in a `try`. In the old kernel that shape fits, because handling there is eager
rewriting and there is no region node to descend into.

In kernel2 handling is a value and a region is a cell on the spine
(`kyo-kernel2/CONTRIBUTING.md:83-102`). Porting the arrow rewrite onto that model produced two
consequences:

**Consequence A: the guard cannot cover a region's interior.** The rewrite reaches
`kyo.exit` but not `kyo.value`, and the comment at `Effect.scala:22-25` records this as the
contract: "A region node's internals evaluate at eval and are not covered; its exit steps
are". `ArrowEffect.handleCatching`'s scaladoc repeats it
(`kyo-kernel2/.../kernel/ArrowEffect.scala:253-255`), and there is now a test pinning it,
"a throw inside a region nested in the computation is not recovered"
(`kyo-kernel2/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:604-611`, added in the
handleFirst/handleCatching work). This is a hole a user cannot predict from the API: nothing
about `Effect.catching(v)(recover)` suggests that installing a handler somewhere inside `v`
removes the recovery from that part of `v`.

**Consequence B: every new node kind costs an arm.** `guarded` needed a `HandledFirst` arm
the moment `handleFirst` landed (`Effect.scala:66-67`). A bracket node adds a sixth. The
enumeration is the same one item 2 is about, appearing in a second file.

### 1.4 What `Effect.catching` requires that a plain evaluator `try` cannot give

The brief asks whether one evaluator-level mechanism subsumes the guard. The binding
constraint is a test, not a theory:

`EffectTest` "catching guards a stateful region across a park"
(`kyo-kernel2/shared/src/test/scala/kyo/kernel/EffectTest.scala:169-182`) evaluates a guarded
computation with `Eval.partial`, gets a residual back, and then evaluates that residual in a
**second, unrelated drive**, where the throw happens and must still be recovered.

A `try` region inside `Eval.evalLoop` cannot do that. The residual leaves `evalLoop` through
`rebuild` (`Eval.scala:50`, `Eval.scala:270-282`) and is re-entered later by a fresh
`Eval.apply`, whose spine starts at `Empty` (`Eval.scala:139`). Any scope knowledge held only
in the evaluator's Java frames is gone. The brief's drive-boundary observation is exactly
this, and it is decisive: **the catching scope has to be a value.**

The current design satisfies that by making the scope live in the arrows. There is exactly
one other structure in this kernel that is a value, survives a park, is reified by `rebuild`,
and re-enters on the next drive: a region cell plus its region node.

### 1.5 Design: `catching` becomes a region

Add a passive cell kind and its node. The recovery function rides the cell the way a handler
rides `Handlers.Node`.

```scala
// kyo-kernel2/.../internal/KyoInternal.scala, beside Kyo.Handled
// A scope whose failures are recovered. It is a region like any other: the
// value evaluates inside it, the exit applies to what the region produces,
// and recover produces the region's result when a failure unwinds to it.
trait Caught[A, +B, S, -S2] extends Kyo[B, S & S2]:
    def value: A < S
    def recover: Throwable => B < S2
    def exit: Arrow[A, B, S & S2]

    final def map[C, S3](f: Arrow[B, C, S3]): C < (S & S2 & S3) = ...  // as Handled.map
```

```scala
// kyo-kernel2/.../internal/Handlers.scala
final class CatchCell(
    val recover: Throwable => Any < Nothing,
    exit: Arrow[Any, Any, Any],
    prev: Handlers
) extends Handlers.Passive(exit, prev)
```

```scala
// kyo-kernel2/.../kernel/Effect.scala
inline def catching[A, S, B >: A, S2](inline v: => A < S)(
    inline f: Throwable => B < S2
)(using inline _frame: Frame): B < (S & S2) =
    try
        (v: B < (S & S2)) match
            case kyo: Kyo[?, ?] =>
                // one allocation: the object is the recovery function and the
                // region node, matching the handle sites in ArrowEffect
                new Kyo.Caught[B, B, S & S2, Any]:
                    val value                 = kyo.asInstanceOf[B < (S & S2)]
                    def recover               = f
                    def exit                  = Arrow[B]
            case settled => settled
    catch case ex: Throwable if NonFatal(ex) => f(ex)
```

Evaluator changes, all in `Eval.evalLoop`:

1. **Entry**, beside the other region arms (`Eval.scala:109-128`):
   `case kyo: Kyo.Caught => loop(kyo.value, new CatchCell(kyo.recover, kyo.exit, hs))`.
2. **Exit** falls out of the settled-value pop for free once the pop is expressed over the
   cell layer (item 2, section 2.4): a catch cell pops through its exit like any other.
3. **Unwind**: the per-arm `try` regions the enrichment design already specifies
   (`exception-enrichment-design.md:506-523`) catch the throw and call one private helper:

```scala
// Eval.scala. Walks out to the innermost cell that answers a failure, running
// what the discarded cells owe on the way. Flat and tailrec over prev, so it
// names the same carrier rebuild and replace do.
@tailrec private def unwind(hs: Handlers, ex: Throwable): (Any < Nothing, Handlers) | Null = ...
```

The signature above is illustrative only; a pair return is forbidden here
(`kyo-kernel2/CONTRIBUTING.md:165`, the measured +24 B). The implementable shape is that
`unwind` finds the catch cell (a `@tailrec` walk returning `Handlers`), and the arm then
does `loop(walk(cell.exit, cell.recover(ex)), cell.prev)` with no tuple. Running what the
discarded cells owe is a second `@tailrec` walk from `hs` down to `cell`, which is where the
bracket track's releases go.

### 1.6 Why this is the correct fix and not merely a cheaper one

- **The property becomes structural.** Today "a resumed step is still guarded" is maintained
  by a code path that re-rewrites the value at every step, and that path has already been
  wrong once: task #53, "Fix Effect.guarded: guard must wrap region-node exit arrows, not
  follow them", and `kyo-kernel2/CONTRIBUTING.md:81` records the append encoding that "agreed
  with every existing test and still lost the handler behind one trailing transform"
  (`727e8e6742`). Under the region design there is no rewriting, so there is nothing to get
  wrong: the scope is on the spine or it is not.
- **The hot path stops paying.** Entering a catching region becomes one cell, the same as
  entering any handler region. No per-step allocation, no per-step `step` flatten, no per-step
  five-way match.
- **Consequence A disappears.** A region nested inside the catching scope sits above the
  catch cell on the same spine, so a throw in its interior unwinds to the catch cell. The
  boundary at `Effect.scala:22-25` and the caveat at `ArrowEffect.scala:253-255` are deleted
  rather than documented.
- **Consequence B disappears.** `guarded` and its five node arms are deleted outright. A new
  node kind no longer touches `Effect.scala`.
- **It is the precedent the guide tells you to look for.**
  `kyo-kernel2/CONTRIBUTING.md:148` says to "Read `Arrow.AndThen.step` and `Effect.catching`
  before designing any new evaluation mechanism, and ask whether your problem is one of them
  at a different scale". Applied to `Effect.catching` itself, the answer is that it is the
  region model at a different scale.

### 1.7 One mechanism, three consumers

The brief asks whether one evaluator-level mechanism subsumes guarding, enrichment, and
bracket unwind. It does, and the mechanism is the pair
(**per-arm `try` regions**, **a spine walk on the failure path**):

| consumer | what it needs from the try region | what it needs from the walk |
|---|---|---|
| `Effect.catching` | catch the throw at the arm | find the innermost catch cell, run `recover` at its `prev` |
| exception enrichment | `kyo` and `hs` live in the handler (`exception-enrichment-design.md:171-203`) | the same walk supplies the frames it reconstructs |
| bracket unwind | catch the throw at the arm | run releases on every bracket cell crossed (R-B2, `iotask-kernel2-integration-r2.md:1610-1612`) |
| `Loop.done` truncation | nothing | run releases on the truncated cells (R-B3, `iotask-kernel2-integration-r2.md:1613-1616`) |

The `Loop.done` row is the reason the walk must be separable from the throw path: `done`
truncates the spine at `Eval.scala:69, 86` with no exception in sight, and R-B3 requires the
same release walk there. So the walk is a function of `(from, to)` over the spine, and the
failure path is one caller of it.

The enrichment design's per-arm shape (`exception-enrichment-design.md:508-517`) is the exact
shape this needs, including the constraint that the `try` must not contain the tail call so
`@tailrec` survives. Six arms cover it.

**Both bracket candidates reached the same conclusion independently, which is the strongest
available evidence that the mechanism is right.** Neither was written with this item in view:

- `bracket-node-design.md:557-563` opens its unwind section with "`evalLoop` has no `try`
  today. The addition is a per-arm `try` around each arm's non-tail computation, excluding the
  tail call so `@tailrec` is preserved. This is the same structural constraint and the same
  solution the enrichment design specifies for the same arms".
- `bracket-handler-design.md:458-463` opens its unwind section with the same sentence and adds
  "the two features share one set of regions rather than each adding its own"
  (`:482-484`), enumerating the arms as `Eval.scala:52, 74, 93, 102, 66, 84, 108, 132-138`,
  identical to enrichment's sites 3 through 9.
- `bracket-node-design.md:754-757` closes its ordering section by deferring to this TODO by
  name: "the guard placement is already flagged in the tree as provisional (`Effect.scala:27`
  ...). If guards move into `Eval`, the bracket's unwind arm and the guard become the same
  per-arm `try`, and the two should be designed in one pass rather than stacked."

Three independent designs converging on one mechanism is exactly the signal
`kyo-kernel2/CONTRIBUTING.md:145` names: several failures sharing a mechanism means the
representation is the answer, not the patch count.

Two concrete facts from those documents sharpen item 1's case:

**The node design has to add a sixth arm to `guarded`**, precisely as predicted in 1.3's
Consequence B (`bracket-node-design.md:703-706`):

```scala
case kyo: Kyo.Bracket[Any, Any, B, S, Any] @unchecked =>
    Kyo.Bracket(kyo.value, kyo.acquired, guard(kyo.exit))
```

**The region-interior hole propagates into release suppression.**
`bracket-node-design.md:604-609` records that the unwind path suppresses a release failure by
wrapping the release in `Effect.catching`, and inherits the hole verbatim: "The known hole is
the one `catching` documents about itself: a throw from inside a *region* nested in the
release body is not covered". A release failure escaping unsuppressed instead of landing on
the primary is a correctness defect, not a documentation caveat, and it is caused by the
mechanism item 1 replaces.

### 1.8 Alternatives considered

**(a) Leave the guard in the arrows and only optimize it.** For example, stop calling
`guarded` recursively and instead have the guard re-wrap lazily. Rejected: the recursion
exists because a step can produce an arbitrary new node graph, so any lazy variant still has
to enumerate the node kinds at some point, and Consequence A is untouched.

**(b) Put it in `Arrow`.** A catching `Transform` is what the guard already is
(`Effect.scala:30`). Making it a first-class arrow kind means either every arrow application
tests a marker, or the chain still has to be rewritten to insert the catcher. Neither removes
the per-step rewriting, neither closes the region-interior hole, and it puts failure semantics
in the wiring layer, where `Arrow` has no notion of a scope. Rejected.

**(c) Per-arm `try` regions in `Eval` with the scope tracked in evaluator-local state.**
Rejected by section 1.4: the residual crosses the drive boundary and the state does not.

**(d) A handler kind rather than a passive cell.** `Handler.Catch` with a `tag`. Rejected:
the cell answers no operation, so a tag on it is a lie and `Handlers.find` would have to skip
it by value rather than by type. Passive is the honest classification, and item 2 gives it a
home.

### 1.9 Blast radius

| file | change |
|---|---|
| `kyo-kernel2/.../kernel/Effect.scala` | `guarded` and `guard` deleted (51 lines); `catching` becomes the node-or-settled match |
| `kyo-kernel2/.../internal/KyoInternal.scala` | `Kyo.Caught` trait, companion, and `Impl` added |
| `kyo-kernel2/.../internal/Handlers.scala` | `CatchCell` added under the `Passive` layer from item 2 |
| `kyo-kernel2/.../internal/Eval.scala` | entry arm, per-arm `try` regions, `unwind` walk, `RebuiltCatchCell` |
| `kyo-kernel2/.../kernel/ArrowEffect.scala:251-265` | `handleCatching`'s scaladoc caveat deleted; the composition itself is unchanged |
| `kyo-kernel2/shared/src/test/.../ArrowEffectTest.scala:604-611` | the pinned "not recovered" expectation inverts |
| `kyo-kernel2/CONTRIBUTING.md:17, 81, 148` | the "guard rewraps continuations" description and the wrap-not-append note change |

Public signatures: none. `Effect.catching` keeps its signature exactly; `handleCatching` keeps
its signature exactly. This is a semantics widening plus an internal representation change.

Performance gate: this changes `Eval.scala` and the node shapes in `KyoInternal.scala`, so
`kyo-kernel2/CONTRIBUTING.md:191` applies verbatim (JMH A/B, frozen baseline, `-prof gc`).
Expected: answering rows allocation-flat (nothing new on a non-throwing path), and the
catching rows strictly better since the per-step allocations disappear. There is no catching
row in `KernelBench` today; one should be added before the A/B so the improvement is
measured rather than asserted.

### 1.10 What needs a maintainer ruling

- **R1.1** The semantics widening. A throw inside a region nested in a `catching` scope
  becomes recoverable, and `ArrowEffectTest.scala:604-611` inverts. The case for changing the
  test rather than preserving it: the expectation cannot be stated without naming the
  mechanism, which is precisely what `kyo-kernel2/CONTRIBUTING.md:159` says will not survive a
  representation change. The case against: it is a behavior change to a shipped surface, and
  `handleCatching`'s recovered value "carries no `E`, so it does not reach `f`"
  (`ArrowEffect.scala:255`), which needs re-checking under the wider scope.
- **R1.2** Whether the unwind runs the discarded cells' *exits* or only their releases. Today
  `done` truncation runs neither (`kyo-kernel2/CONTRIBUTING.md:98`). Failure unwinding should
  match `done` truncation, and the bracket track owns the release half. This must be ruled
  jointly with the bracket judgment, not before it.
- **R1.3** Sequencing against the bracket judgment. Both designs want the same `try` regions
  and the same walk. Landing catching-as-region first fixes the walk's shape and the bracket
  cell then plugs into it; landing bracket first does the same in reverse. They should not be
  designed in isolation and then merged. `bracket-node-design.md:754-757` says the same thing
  from the other side.
- **R1.4, an argument this fix invalidates.** `bracket-handler-design.md:486-492` justifies
  releasing every open bracket at the throw point with: "an `Effect.catching` guard that is on
  the stack is strictly inner: the guard's own `try` sits inside `Arrow.Transform.apply`
  (`Effect.scala:32-51`), which the loop arm's expression called. So the loop arm's handler
  runs only when no guard in this drive will recover". That reasoning is sound **only while
  the guard lives in the arrows**. Under item 1 the guard is a spine cell, so the loop arm's
  handler runs *before* it is known whether a catch cell will recover, and the unwind must
  find the innermost catch cell first and release only the brackets above it. This is not a
  defect in either design; it is a real ordering constraint that only exists once both land,
  and it is the concrete reason they cannot be stacked.
- **R1.5, an observable behavior change in the bracket design's own worked example.**
  `bracket-node-design.md:731-745` walks a program where a throw inside a bracket inside a
  handled region is deliberately **not** caught by an enclosing `Effect.catching`, "because
  the guard is on the chain after the region and that chain was never entered". Under item 1
  that program catches. The worked example's steps 8 and its "if the user wants the recovery
  to see it, the `catching` goes inside `use`" advice both change. Whoever rules R1.1 should
  read that example, since it is the clearest statement of what today's boundary costs a user.

---

## 2. `Handlers`: the cell kinds are enumerated in nine places

### 2.1 The complaint, quantified

`Handlers` is `sealed trait` plus `Empty`, `Node`, `StateNode`, and `FirstNode`
(`kyo-kernel2/.../internal/Handlers.scala:9-44`). `FirstNode` arrived at `90d1a4c285`. Every
walk over the spine enumerates them:

| # | site | arms |
|---|---|---|
| 1 | `Handlers.find` | `Handlers.scala:48-54`, 4 |
| 2 | `Eval.evalLoop` operation dispatch | `Eval.scala:43-102`, 4 (Empty plus 3 cells) |
| 3 | `Eval.evalLoop` settled-value pop | `Eval.scala:129-138`, 4 |
| 4 | `Eval.rebuild` | `Eval.scala:270-282`, 4 |
| 5 | `Eval.replace` / `count` | `Eval.scala:287-294`, 4 |
| 6 | `Eval.replace` / `fill` | `Eval.scala:297-305`, 4 |
| 7 | `Eval.replace` / `build` | `Eval.scala:307-314`, 4 |
| 8 | isolate `transplant` (proposed) | `isolate-kernel2-design.md:305-314`, 3 |
| 9 | bracket cell (proposed) | adds one arm to each of 1 through 8 |

Seven live sites, nine with the in-flight tracks. Adding the catch cell from item 1 and the
bracket cell from the bracket track is a fourteen-arm edit across four files. That is the
evolvability cost the maintainer is asking about, and it is real, not hypothetical: the
`FirstNode` addition at `90d1a4c285` had to touch sites 1 through 7 plus `Effect.guarded`.

### 2.2 What `Eval` actually needs from a cell

Read off the call sites:

| operation | needed by | cells that have it |
|---|---|---|
| `prev` | every walk (1 through 8) | all |
| `exit` | pop (3), `done` (`Eval.scala:69, 86`), First done (`Eval.scala:138`) | all |
| the handler's `tag` | `find` (1) | only cells an operation can resolve to |
| the handler, by kind | operation dispatch (2) | only answering cells, and the logic differs per kind |
| `withPrev` | `replace` / `build` (7) | all |
| reify to a node | `rebuild` (4), `transplant` (8) | all |
| `state`, `withState` | stateful arm (`Eval.scala:52-69`) | `StateNode` only |

Two facts fall out. First, `prev`, `exit`, and `withPrev` are needed by every cell and are
identical in meaning across kinds, so they belong to the cell layer, not to each kind.
Second, the only place where the kinds are *genuinely* different is the operation dispatch:
Cont, Loop, LoopState, and First have different clause protocols, different spine effects, and
different `loop(...)` targets.

### 2.3 A performance fact that changes the answer

`Handlers.find` (`Handlers.scala:48-54`) does, per cell walked, up to three `instanceof` tests
and then `l.handler.tag`. `Handler.tag` is abstract (`Handler.scala:10`) and is implemented by
a fresh anonymous class at every `ArrowEffect.handle*` expansion
(`ArrowEffect.scala:68, 118, 142, 172, 200, 237`), so that call site is megamorphic by
construction. `find` is on the hot path: one call per answered operation.

Hoisting the tag onto the cell as a `val` replaces the megamorphic call with a field read.
So the encapsulation is not a cost to be traded against evolvability. It is a hot-path
improvement that also collapses the enumeration.

### 2.4 Design: one cell layer, `Answering` and `Passive` under it

```scala
// kyo-kernel2/.../internal/Handlers.scala
sealed trait Handlers derives CanEqual

object Handlers:

    case object Empty extends Handlers

    /** One entered region. Every cell links to the previous one and carries
      * that region's exit, so every walk over the spine reads two fields and
      * needs to know nothing about what kind of region it is.
      */
    sealed abstract class Cell(
        val exit: Arrow[Any, Any, Any],
        val prev: Handlers
    ) extends Handlers:
        def withPrev(prev: Handlers): Cell
        /** The region node this cell reconstitutes into, wrapped around a
          * value that is standing at this cell's position.
          */
        def rebuilt(value: Any < Nothing): Kyo[Any, Nothing]

    /** A cell an operation can resolve to. The tag is a field so find reads
      * it rather than calling through the handler.
      */
    sealed abstract class Answering(
        val tag: Tag[?],
        exit: Arrow[Any, Any, Any],
        prev: Handlers
    ) extends Cell(exit, prev)

    /** A cell the walk crosses without resolving: it answers no operation.
      * Failure recovery and resource release are regions of this kind.
      */
    sealed abstract class Passive(
        exit: Arrow[Any, Any, Any],
        prev: Handlers
    ) extends Cell(exit, prev)

    final class Node[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        val handler: Handler.Cont[I, O, E, A, S] | Handler.Loop[I, O, E, A, S],
        exit: Arrow[Any, Any, Any],
        prev: Handlers
    ) extends Answering(handler.tag, exit, prev):
        def withPrev(prev: Handlers)      = new Node(handler, exit, prev)
        def rebuilt(value: Any < Nothing) = new Eval.RebuiltNode(value, this)

    // StateNode, FirstNode: the same shape, plus state / withState on StateNode
```

`find` becomes three arms, constant in the number of cell kinds and with no call through the
handler:

```scala
extension (self: Handlers)
    def find[E2](tag: Tag[E2]): Handlers =
        @tailrec def loop(l: Handlers): Handlers =
            l match
                case Empty        => Empty
                case c: Answering => if tag <:< c.tag then c else loop(c.prev)
                case c: Cell      => loop(c.prev)
        loop(self)
```

`rebuild` becomes one arm:

```scala
@tailrec private def rebuild(top: Handlers, stop: Handlers, acc: Any < Nothing): Any < Nothing =
    if top eq stop then acc
    else
        top match
            case c: Cell => rebuild(c.prev, stop, c.rebuilt(acc))
            case Empty   => acc
```

`replace`'s three loops become one arm each, reading `c.prev` and calling `c.withPrev`.
`transplant` (`isolate-kernel2-design.md:305-314`) becomes one arm plus the per-kind policy
read the isolate design defines. The settled-value pop collapses to one virtual call:

```scala
case v =>
    hs match
        case Empty   => v
        case c: Cell => loop(c.settled(v), c.prev)
```

where `settled` is `resume(exit, v)` on every cell except `FirstNode`, which overrides it
with `walk(exit, handler(Nested.unnest(v)))` (today's `Eval.scala:134-138`).

**The operation dispatch stays an explicit match.** Virtualizing it would require each kind's
`apply` to return a descriptor of what the loop should do next (a value plus the next spine),
which is the pair return whose +24 B the guide records
(`kyo-kernel2/CONTRIBUTING.md:165`). Here the match is the encapsulation, and the honest
statement is that the kinds are different exactly where their protocols are different.

### 2.5 What this costs

Enumeration sites after the change: **two**. The operation dispatch (`Eval.scala:43-102`) and
`FirstNode`'s override of `settled`. Adding the catch cell from item 1 or a bracket cell
touches neither: they are `Passive`, they inherit `prev`, `exit`, `withPrev`, `rebuilt`, and
`settled`, and `find` skips them by the `case c: Cell` arm that already exists.

Runtime cost changes:

- `find`: fewer branches, and a field read replacing a megamorphic interface call. Better.
- pop, `rebuild`, `replace`, `transplant`: one virtual call per crossed cell where today there
  is an `instanceof` chain plus a direct field read. All four are cold
  (`kyo-kernel2/CONTRIBUTING.md:114`) or once-per-region.
- `prev` and `exit` become concrete fields on the abstract class, so they stay direct field
  reads with no dispatch. This is the reason for the constructor-parameter shape above rather
  than abstract members.

Allocation: unchanged. No cell gains or loses a field; `tag` on `Answering` is a copy of a
reference the handler already holds.

### 2.6 Is "leave it, the match is the encapsulation" the right answer?

No, and the reason is countable. The argument for leaving it is that a sealed hierarchy
matched exhaustively is already a closed, checkable enumeration, and that the compiler will
flag every site when a kind is added. That argument holds where the sites are few. At seven
going on nine, with two in-flight tracks each adding a cell kind, the exhaustiveness check
becomes a list of nine mechanical edits per addition, six of which (`rebuild`, the three
`replace` loops, the pop, `transplant`) contain no per-kind logic at all. Those six are pure
duplication, and duplication that the compiler forces you to write is still duplication.

Where "the match is the encapsulation" **is** the right answer is the operation dispatch, and
the design above says so explicitly rather than virtualizing it.

The minimal version, if the full change is not wanted now: hoist `tag` to a `val` on the cells
and add the `Cell` layer with concrete `prev`/`exit`, without `rebuilt` or `settled`. That
alone collapses sites 1, 5, 6, and 7 and delivers the `find` improvement. It is not the
recommendation, because the remaining sites are the ones the bracket and catch cells would
have to touch.

### 2.7 Blast radius

| file | change |
|---|---|
| `kyo-kernel2/.../internal/Handlers.scala` | rewritten (58 lines); `Cell`/`Answering`/`Passive` layers, `find` to three arms |
| `kyo-kernel2/.../internal/Eval.scala` | pop, `rebuild`, `replace` collapse; `Rebuilt*` classes referenced from `rebuilt` |
| `kyo-kernel2/shared/src/test/.../HandlersTest.scala` | constructs cells directly (`:38-42, 94, 106`); constructor shapes change |
| `kyo-kernel2/CONTRIBUTING.md:23, 83-114` | the spine description gains the two-layer split |

Public signatures: none. `Handlers` is `kyo.kernel.internal` and is used only by `Eval` and by
tests in the internal test package, which
`kyo-kernel2/CONTRIBUTING.md:160` explicitly sanctions.

Performance gate: `Eval.scala` changes, so the JMH A/B applies. The rows to read are
`sharedHandlerPaysDispatch` and `suspensionBaseline`
(`kyo-kernel2/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala:273, 238`), which are the
ones that exercise `find` depth.

### 2.8 What needs a maintainer ruling

- **R2.1** The names. `Cell`, `Answering`, `Passive` are new nouns, and
  `kyo-kernel2/CONTRIBUTING.md:173` requires a ruling before any new terminology lands. `Cell`
  is already the word the guide uses in prose ("one immutable cell per region",
  `CONTRIBUTING.md:23`), so it is a promotion rather than an invention; `Answering` and
  `Passive` are genuinely new.
- **R2.2** Whether `Passive` should exist before there is a passive cell. If item 1 and the
  bracket track both slip, `Passive` has no implementors and is speculative generality, which
  `AGENTS.md` forbids. The honest sequencing is that this item lands *with* the first passive
  cell, not before it.

---

## 3. `Kyo.Defaulted`: the row lies and the payload is untyped

### 3.1 What it is

```scala
// kyo-kernel2/.../internal/KyoInternal.scala:38-41
// TODO no, this is not acceptable, we need to fully review. Let's discuss
private[kyo] trait Defaulted:
    self: Suspend[?, ?, ?, ?, ?, ?] =>
    def default: Any
```

Answered by the evaluator's find-miss arm (`Eval.scala:44-50`):

```scala
case Empty =>
    kyo.root match
        case d: Kyo.Defaulted =>
            loop(resume(kyo.cont, Nested.lift(d.default)), hs)
        case _ =>
            if !partial then throw new IllegalStateException(s"unhandled suspension: $kyo")
            else rebuild(hs, Empty, v)
```

Two constructors, both in `ContextEffect`:

- `ContextEffect.suspend(tag, default): A < Any` (`ContextEffect.scala:37-47`), the
  user-facing optional context.
- `ContextEffect.probe(tag): Any < Any` (`ContextEffect.scala:86-93`), whose `default` is a
  private `undefined = new AnyRef {}` sentinel (`ContextEffect.scala:25`) compared by identity
  at `ContextEffect.scala:76`. It exists so the layered
  `ContextEffect.handle(tag, ifUndefined, ifDefined)` (`ContextEffect.scala:66-80`) can ask
  whether an outer handler provides a value.

### 3.2 Diagnosis: five distinct problems, in severity order

**D1: the effect row is false.** `ContextEffect.suspend(tag, default)` returns `A < Any`
(`ContextEffect.scala:41`) while the value it returns is a live `Kyo.Suspend` carrying
`Tag[E]` (`ContextEffect.scala:42-47`). A type that says "this computation needs nothing" is
attached to a value that any enclosing `E` handler will intercept and answer differently. The
test suite documents this by having to cast:
`ContextEffect.handle(Tag[TestRuntimeEffect1], 42)(effect.asInstanceOf[Int < TestRuntimeEffect1])`
(`kyo-kernel2/shared/src/test/.../ContextEffectTest.scala:159`). The cast is there because the
type is wrong and the test needs the true behavior. This is the "not acceptable" core: it is
not a hole in a corner, it is the declared type of a public API disagreeing with the value.

**D2: the payload is `Any`.** `def default: Any` (`KyoInternal.scala:41`) is fed to
`kyo.cont`, whose input is `O[X]`. Nothing checks the relation. It is already being violated
on purpose: `probe`'s default is an `AnyRef` sentinel while its `O[X]` is `A`
(`ContextEffect.scala:88, 93`). The mechanism is untyped because one of its two users cannot
be typed.

**D3: the answer bypasses the handler protocol.** The miss arm resumes the continuation
directly (`Eval.scala:47`). No handler kind participates, so the evaluator has two answering
paths and only one of them is expressible as a `Handler`. Every future capability that
handlers gain (the isolate design's per-cell fork policy,
`isolate-kernel2-design.md:392-423`; a bracket cell's release) is absent from this path by
construction.

**D4: it interacts with the eager drivers.** `ArrowEffect.handlePartial` matches on
`kyo.tag <:< _tag` (`ArrowEffect.scala:272`) with no knowledge of `Defaulted`, so it will feed
an optional-context suspension to a user clause. The planned `dispatchFirst`
(`iotask-kernel2-integration-r2.md:1624-1633`) will do the same. Whether that is correct is
undecided because the mechanism never stated a contract.

**D5: the miss arm is becoming a dispatch table.** The isolate design adds a second marker
with the same shape, `Kyo.Detached` with `def child: Any`, in the same arm
(`isolate-kernel2-design.md:266-284`). Two untyped markers on one cold arm is the pattern
`kyo-kernel2/CONTRIBUTING.md:143-150` names as the module's failure mode.

### 3.3 A structural constraint that eliminates most of the design space

The fallback **must** be typed at `O[X]`, the operation's output, not at `A`, the node's
result.

Proof: the miss arm resumes `kyo.cont` (`Eval.scala:47`), and `kyo` is whatever the value is
*after* any number of `map`s. `Suspend.map` builds a new node that delegates `tag`, `input`,
and `frame` to `root` and chains onto `cont` (`KyoInternal.scala:56-77`), and the evaluator
recovers the marker through `root` (`Eval.scala:45`). So the marker lives on the original
object while the continuation being resumed is the mapped one. A fallback typed at the
original node's *result* would be a value that has already passed through the root's own
continuation, and there is no way to apply "the mapped chain minus the root's continuation":
the mapped node presents `cont = this`, an `Arrow.AndThen` (`KyoInternal.scala:70-75`), and an
`AndThen` cannot be subtracted from.

This kills the shape where the node carries `unhandled: A < S`. It is also why the current
design puts the default before the continuation, and that part of the current design is right.

### 3.4 The two needs, separated

- **N1, optional context.** "The value of `E` here, or this fallback if no handler provides
  one." Sole production consumer: `Local.get` and `Local.use`
  (`kyo-prelude/shared/src/main/scala/kyo/Local.scala:125, 128`), which pass `Map.empty` so a
  local is readable with no `Local.let` in scope.
- **N2, definedness.** "Is there an outer handler, and what does it say?" Sole consumer: the
  layered `ContextEffect.handle` (`ContextEffect.scala:66-80`), which downstream is
  `Env.run` (`kyo-prelude/.../Env.scala:98`, unions with the outer env), `Local.let` and
  `Local.update` (`Local.scala:131, 134-138`, merge into the outer map), and `Scope.run`
  (`kyo-core/.../Scope.scala:132`, ignores the outer).

N2 has no analogue in the old kernel's mechanism: `origin/main:.../ContextEffect.scala:152-155`
answers it with `context.contains(tag)` against the threaded `Context` map. kernel2 deleted
that map, so N2 became a probe, and the probe is what forced `default: Any`. Naming this is
important: `Defaulted`'s untypedness is a consequence of removing the context map, not of
optional context itself.

### 3.5 Design: `Maybe`-shaped context answers plus one typed fallback

The single change that makes both needs expressible with a typed fallback at `O[X]` is to make
a context effect's answer carry its own definedness.

```scala
// kyo-kernel2/.../kernel/ContextEffect.scala
abstract class ContextEffect[+A] extends ArrowEffect[Const[Unit], Const[Maybe[A]]]
```

```scala
// kyo-kernel2/.../internal/KyoInternal.scala, replacing Kyo.Defaulted
/** A suspension that carries its own answer for the case where no handler
  * is installed for its effect. The answer is typed as the operation's
  * output because the evaluator feeds it to the same continuation a
  * handler's answer would reach.
  */
private[kyo] trait Unhandled[I[_], O[_], E <: ArrowEffect[I, O], X]:
    self: Suspend[I, O, E, X, ?, ?] =>
    def unhandled: O[X]
```

```scala
// Eval.scala, the find-miss arm
case Empty =>
    kyo.root match
        case u: Kyo.Unhandled[[Z] =>> Any, [Z] =>> Any, Nothing, Any] @unchecked =>
            loop(resume(kyo.cont, Nested.lift(u.unhandled)), hs)
        case _ => ...
```

The evaluator's pattern stays erased, which is a sanctioned boundary
(`kyo-kernel2/CONTRIBUTING.md:174`). The gain is at the **construction** sites, which are now
checked, and that is what forces the two consumers into shape:

```scala
// N1: optional context. The miss answers Absent; the continuation applies
// the caller's default. The row is honest: see the ruling in 3.8.
inline def suspend[A, E <: ContextEffect[A]](
    inline effectTag: Tag[E],
    inline _default: => A
)(using inline _frame: Frame): A < E =
    new Arrow.Transform[Maybe[A], A, Any]
        with Kyo.Suspend[Const[Unit], Const[Maybe[A]], E, Any, A, E]
        with Kyo.Unhandled[Const[Unit], Const[Maybe[A]], E, Any]:
        def tag       = effectTag
        def input     = ()
        def frame     = _frame
        def cont      = this
        def unhandled = Maybe.Absent
        def apply[C, S2](v: Maybe[A] < S2, next: Arrow[A, C, S2]) = ...  // getOrElse(_default)

// N2: definedness. No sentinel, no identity comparison, no Any.
inline def handle[A, E <: ContextEffect[A], B, S](
    inline effectTag: Tag[E],
    inline ifUndefined: A,
    inline ifDefined: A => A
)(v: B < (E & S))(using inline frame: Frame): B < S =
    ArrowEffect.handleLoop(effectTag, v)(
        [X] =>
            _ =>
                probe(effectTag).map {
                    case Present(outer) => Loop.continue(Present(ifDefined(outer)))
                    case Absent         => Loop.continue(Present(ifUndefined))
                }
    )

// probe: the identity continuation, so the answer is the Maybe itself
private inline def probe[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(
    using inline _frame: Frame
): Maybe[A] < Any =
    new Kyo.Suspend[Const[Unit], Const[Maybe[A]], E, Any, Maybe[A], Any]
        with Kyo.Unhandled[Const[Unit], Const[Maybe[A]], E, Any]:
        def tag       = effectTag
        def input     = ()
        def frame     = _frame
        def cont      = Arrow[Maybe[A]]
        def unhandled = Maybe.Absent
```

What this deletes: `default: Any` (`KyoInternal.scala:41`), the `undefined` sentinel
(`ContextEffect.scala:25`), the identity comparison and the two `asInstanceOf` casts around it
(`ContextEffect.scala:76-77`), and the `Any < Any` return type on `probe`
(`ContextEffect.scala:87`).

Cost: `Maybe[A]` is `opaque type Maybe[+A] >: (Absent | Present[A]) = Absent | Present[A]`
(`kyo-data/shared/src/main/scala/kyo/Maybe.scala:12`), so `Present(v)` for a non-`Maybe` `v` is
`v` itself with no allocation, and the unwrap is one reference comparison against `Absent`
(`Maybe.scala:133, 193`). The per-read cost is that comparison, on a path that today reads the
value directly. This is the one measurable regression in the design and it lands on `Env.use`
and `Local.get`, which are hot in application code.

### 3.6 Alternatives considered

**(a) A dedicated node type**, a sibling of `Suspend` in the `Kyo` ADT. Rejected: the value
must still be found by tag when a handler *is* installed, so it has to be a `Suspend` for
`Handlers.find` and for the operation dispatch. Making it a sibling adds an arm to the
evaluator's hot node dispatch (`Eval.scala:41-138`, ordered by expected frequency per the
comment at `Eval.scala:37-39`) to serve a cold case. A `Suspend` refinement is the correct
placement; the defect was never the placement, it was the typing.

**(b) An input-carried default**: `I[X]` carries the fallback so the handler protocol stays
uniform. Rejected as a solution to the miss: inputs flow to handlers, and the miss case is
precisely the case with no handler to receive them. It does not address N1 at all. It is worth
recording that it does not address N2 either, because N2 asks about the *outer* stack and
inputs flow inward.

**(c) A handler-side default**, a cell kind or handler capability that answers misses.
Rejected on reachability: the miss arm has the suspension's `Tag[E]` and nothing else. A
`Tag` is not a value, so the effect instance cannot be consulted, and no cell exists at
`Empty` by definition. A variant where `Eval` installs a synthetic root cell requires
`Eval.apply` to know each effect's default, which it cannot. This is also the shape
`kyo-kernel2/CONTRIBUTING.md:146` calls machinery guarding machinery.

**(d) Suspend-time eager resolution.** Rejected as impossible: the suspension is a value
constructed arbitrarily long before any drive, and "is a handler installed" is a property of
the spine during a drive. `ContextEffectTest.scala:157-161` pins exactly the case that breaks
it: the same value resolves to the default in one context and to 42 in another.

**(e) Make the row honest by requiring a handler**, dropping optional context entirely so
`Local.get` carries `Local.State` in its row. This is the only design in which the miss arm
disappears. Rejected on user-visible semantics: `origin/main` deliberately makes locals
readable with no handler (`Local.scala:125`), and requiring `Local.State` at every call site
that reads a local is a large behavior change to a shipped API. Recorded because it is the
honest endpoint of D1 and the maintainer may want it.

### 3.7 Interaction with the isolate track

`isolate-kernel2-design.md:266-284` adds `Kyo.Detached` with `def child: Any` in the same arm.
Under this design it cannot reuse `Unhandled`, because its answer is *computed from the
spine* (`transplant(hs, d.child)`, `isolate-kernel2-design.md:280`) rather than carried by the
node. That is a genuine second kind, and the miss arm ends with two markers:

- `Unhandled`: the node carries its own answer, typed at `O[X]`.
- `Detached`: the evaluator computes the answer from `hs`.

A single trait `def unhandled(hs: Handlers): O[X]` would cover both, at the price of handing
the internal spine to every implementor and moving the transplant walk out of `Eval` into a
node method. The recommendation is two markers, because collapsing them makes one implementor
ignore its parameter, which is the flattened encoding
`kyo-kernel2/CONTRIBUTING.md:145-147` warns about. This needs to be ruled jointly with the
isolate design rather than by either track alone.

`Detached` should adopt the same typing discipline regardless: its `child` is typed by the
`DetachEffect`'s own `O`, which the fork site chooses
(`isolate-kernel2-design.md:325-332`), so `def child: A < S` is expressible there and `Any` is
not needed.

### 3.8 Blast radius

| file | change |
|---|---|
| `kyo-kernel2/.../internal/KyoInternal.scala:38-41` | `Defaulted` replaced by parameterized `Unhandled` |
| `kyo-kernel2/.../internal/Eval.scala:44-50` | miss arm pattern changes |
| `kyo-kernel2/.../kernel/ContextEffect.scala` | `ContextEffect[A]`'s parent changes; all four suspend forms, both handle forms, and `probe` rewritten; `undefined` deleted |
| `kyo-prelude/.../Env.scala:39, 98, 130, 146` | `Env[+R] extends ContextEffect[TypeMap[R]]` unchanged; the `suspendWith` bodies see `Maybe[TypeMap[R]]` only if they use the raw form, which they do not |
| `kyo-prelude/.../Local.scala:117-118, 125-138` | same |
| `kyo-core/.../Scope.scala:37, 51, 67, 132` | same |
| `kyo-core/.../scheduler/IOTask.scala:216` | `CurrentFiber extends ContextEffect[IOPromise[?, ?]]`, same |
| `kyo-kernel2/shared/src/test/.../ContextEffectTest.scala:159` | the `asInstanceOf` becomes unnecessary if the row is fixed |

Public signatures: `ContextEffect`'s supertype changes, which is `private`-adjacent in
practice (no downstream code writes a raw `ArrowEffect` handler for a context effect today,
verified by grep: every installer goes through `ContextEffect.handle`). The four
`ContextEffect.suspend`/`suspendWith` signatures keep their shapes; only
`suspend(tag, default)`'s **return row** changes if R3.1 below is ruled that way.

### 3.9 What needs a maintainer ruling

- **R3.1, the one that matters.** Does `ContextEffect.suspend(tag, default)` keep returning
  `A < Any` (D1 stands, the row stays false, no downstream change) or does it return `A < E`
  (the row becomes true, and every `Local.get` call site gains `Local.State` in its row unless
  `Local` handles it)? This is the actual "not acceptable" question. Everything else in this
  section is settled by analysis; this one is a value judgment about the user-facing contract.
  A middle position exists and should be considered: keep `A < Any` but document it as a
  deliberate erasure with the interception behavior stated, and add a test asserting the
  interception rather than casting around it.
- **R3.2** Whether a handler answering `Absent` is a bug to be made unrepresentable. Under the
  `Maybe`-shaped protocol, a hand-written `ArrowEffect.handleLoop` over a context effect's tag
  could answer `Absent`, which the mandatory `suspend(tag)` would then have to treat as a bug.
  Today the equivalent state is unrepresentable. The mitigation is that `ContextEffect.handle`
  is the only installer shipped, and it always answers `Present`.
- **R3.3** The `Maybe` unwrap cost on `Env.use` and `Local.get`. It is one reference
  comparison per context read on a path that today reads the value directly. Cheap, but it is
  on a hot application path and `kyo-kernel2/CONTRIBUTING.md:191` puts the burden of proof on
  the change. There is no context-read row in `KernelBench` today; one is needed.
- **R3.4** One marker or two on the miss arm, ruled jointly with `isolate-kernel2-design.md`
  section 10.
- **R3.5** What `handlePartial` (`ArrowEffect.scala:272`) and the planned `dispatchFirst` do
  when they meet an `Unhandled` suspension. This has no answer today and needs one either way.

---

## 4. `Loop.continue` and `Loop.done`: the pending return type is an inference workaround that broke signature parity

### 4.1 The three sub-claims in the TODO, adjudicated

**"why call explicitly?"** Because the declared return type is `Outcome[...] < S`
(`Loop.scala:143, 151, 165, 182, 202` for `continue`; `:213, 221, 229, 237, 245` for `done`),
so something has to produce currency. The explicit `Nested.lift` rather than the implicit
conversion was chosen at `e19661ee6f`, whose message states the reason: "Loop's outcome
constructors switch to the runtime `Nested.lift` directly since their opaque expected types
must never skip the boxing check". That reason is now stale: `LiftMacro.liftMacro` keeps
opaque types on the runtime test and says so in its own comment, naming this very type
("an opaque's underlying can admit computations (Loop.Outcome carries them)",
`kyo-kernel2/.../internal/LiftMacro.scala:22-26`). So the implicit path and the explicit path
emit the same thing today.

**"it can never be a nested computation?"** Correct for `continue`, and provable.
`Continue`, `Continue2`, `Continue3`, and `Continue4` are `sealed abstract class ... extends
Serializable` (`Loop.scala:30, 40, 53, 70`) and extend nothing else. `Nested.lift`'s boxing
arm is `case boxed: Boxed` (`KyoInternal.scala:17`), and `Boxed` is extended only by `Nested`
and `Kyo` (`KyoInternal.scala:9, 11, 26`). So the boxing arm is dead code for every value
`continue` constructs. The payload can be a `Kyo` (`Eval.scala:63, 80` match `c._1` against
`Kyo`), but the payload is not what is lifted: the `Continue` object is.

**Not correct for `done`.** `done(v)` takes an arbitrary `O`, which may be a `Kyo` held as
data or an already-`Nested` value, and the evaluator relies on that box: it unnests the
outcome (`Eval.scala:56, 78`), discriminates, and re-lifts the done payload
(`Eval.scala:69, 86, 160, 184`). Without the box a `Kyo`-valued done payload is
indistinguishable from a clause that suspended, and `Loop.apply`'s `case kyo: Kyo[?, ?]` arm
(`Loop.scala:268`) would loop on it. So the lift in `done` is load-bearing; only the lift in
`continue` is dead.

**"why is continue returning a computation? it seems it shouldn't"** Correct, and this is the
substantive claim. The pending return type exists to make inference work at the clause sites,
whose expected type is `Loop.Outcome[...] < S2` (`Handler.scala:19, 23`). It was introduced at
`341d0d9b98`, whose message says "The constructors return the lifted type because this
kernel's `<` lower bounds only Kyo, so a bare Outcome would not unify with an expected
`Outcome < S`". At that commit kernel2's currency was
`opaque type <[+A, -S] >: Kyo[A, S] = A | Kyo[A, S]`
(`git show 341d0d9b98:kyo-kernel2/.../kernel/Pending.scala:8`). The lower bound was removed by
task #45; the current definition is `opaque type <[+A, -S] = A | Kyo[A, S]`
(`Pending.scala:10`), which is **byte-identical to `origin/main`'s**
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala:42`).

### 4.2 The live defect this caused

Carrying the pending type forced an extra `S` type parameter onto every constructor. Compare:

| constructor | `origin/main` | kernel2 HEAD |
|---|---|---|
| `continue` (no state) | `continue[A]: Outcome[Unit, A]` | `continue[A, S]: Outcome[Unit, A] < S` |
| `continue` (1 state) | `continue[A, O, S](v: A): Outcome[A, O]` | `continue[A, O, S](v: A): Outcome[A, O] < S` |
| `done` (no value) | `done[A]: Outcome[A, Unit]` | `done[A, S]: Outcome[A, Unit] < S` |
| `done` (1 value) | `done[A, O](v: O): Outcome[A, O]` | `done[A, O, S](v: O): Outcome[A, O] < S` |

(`origin/main:kyo-kernel/.../Loop.scala` for the left column; `Loop.scala:143, 151, 213, 221`
for the right.)

Twenty-four call sites in this repository apply these constructors with explicit type
arguments at the `origin/main` arity, and every one of them fails against kernel2's arity.
Examples: `Loop.done[GunzipState, Unit](())`
(`kyo-core/jvm/src/main/scala/kyo/StreamCompression.scala:540, 545`),
`kyo.Loop.continue[Unit]` (`kyo-http/shared/src/test/scala/kyo/HttpServerTest.scala:1952,
2109`), `Loop.done[Int]`
(`kyo-ui/shared/src/test/scala/kyo/internal/ReactiveUITeardownTest.scala:134`),
`Loop.done[Unit, Maybe[Pub]](Maybe(pub))` (`kyo-aeron/shared/src/main/scala/kyo/Topic.scala:361, 375`).

So the maintainer's instinct is not only about type honesty. The pending return type is a
source-compatibility break against the kernel it replaces, on an API with 1,750 call sites in
this repository alone, and it has not been noticed because nothing outside kyo-kernel2
compiles against kernel2 yet.

### 4.3 Design: bare `Outcome` returns, matching `origin/main` exactly

```scala
inline def continue[A]: Outcome[Unit, A] = _continueUnit

@nowarn("msg=anonymous")
inline def continue[A, O, S](inline v: A): Outcome[A, O] =
    new Continue[A]:
        def _1 = v

@targetName("done1")
def done[A, O](v: O): Outcome[A, O] = v
```

and the same for the 2, 3, and 4 arities. Note that `origin/main`'s `done` family is a plain
`def` with a by-value parameter (`origin/main:kyo-kernel/.../Loop.scala:198, 206, 214, 222, 230`)
while `continue` is `inline` (`:136, 144, 156, 171, 189`); kernel2 made both `inline`. Keeping
`done` inline is fine and is not part of the parity question, which is about type-parameter
arity. The lift moves to the use sites, where the implicit
conversion already fires today for any other value entering a `< S` position: inside
kyo-kernel2 that is `Implicits.liftInternal` (`Implicits.scala:69-75`, imported by
`Loop.scala:5`, `ContextEffect.scala:8`, `ArrowEffect.scala` and `Eval.scala`), and outside it
is `Implicits.lift` (`Implicits.scala:15-20`) through `LiftMacro`, which routes an opaque type
to the runtime `Nested.lift` (`LiftMacro.scala:22-26, 55`). The emitted runtime behavior is
identical to today for both `continue` and `done`.

`Loop`'s own runners need one adjustment each: `loop(Loop.continue(input))`
(`Loop.scala:272, 302, 334, 368, 393, 419, 449, 481, 515, 539`) passes into a parameter typed
`Outcome[...] < S`, so the conversion fires there. That is 10 sites, all inside `Loop.scala`.

### 4.4 Why the bare shape is known to type-check

The strongest evidence is not an argument, it is `origin/main`. It has:

- the identical currency definition, `opaque type <[+A, -S] = A | Kyo[A, S]`
  (`origin/main:kyo-kernel/.../Pending.scala:42`),
- bare `Outcome` returns on every constructor,
- pending-wrapped clause types, `Loop.Outcome[A < (E & S), A] < S2`
  (`origin/main:kyo-kernel/.../ArrowEffect.scala:443`), identical in shape to kernel2's
  (`Handler.scala:19, 23`),
- an implicit lift that is *more* restrictive than kernel2's, since it carries a `CanLift`
  evidence gate (`origin/main:kyo-kernel/.../Pending.scala:439`) that kernel2 deliberately
  dropped ("The conversion carries no evidence gate", `Implicits.scala:13-14`),
- and 1,750 call sites across 60-plus files that compile.

So the inference concern recorded at `341d0d9b98` was a property of the then-current lower
bound, not of the bare shape. This is a claim that a compile settles cheaply and that must be
settled by a compile before the change lands; it is not asserted as verified here.

### 4.5 Alternatives considered

**(a) Keep the pending return, replace `Nested.lift` with a cast in `continue` only.**

```scala
// Continue values are never Boxed, so the nesting box cannot apply
inline def continue[A, O, S](inline v: A): Outcome[A, O] < S =
    (new Continue[A] { def _1 = v }).asInstanceOf[Outcome[A, O] < S]
```

This answers sub-claims one and two and leaves three unanswered, and it does not fix the arity
break in 4.2. It is strictly the smaller fix and it is listed only so the record shows it was
weighed.

**(b) Wrap `done` in its own case, `Outcome[A, O] = Continue[A] | Done[O]`.** Every outcome
becomes provably non-`Boxed`, so no constructor ever lifts, and a latent hazard closes: today
`Loop.done(v)` where `v` happens to *be* a `Continue` is misread as a continuation by
`Eval.scala:57, 79` and `Loop.scala:266, 296, 328, 362`. Rejected as the primary design, for
three reasons: it allocates once per region exit and once per loop completion where today it
allocates nothing; the box does not actually disappear, it relocates to the loop runner and to
`Eval`'s done arms, which must still produce currency from `d.value`; and it is a
representation change to a type with 1,750 call sites in service of a hazard that
`origin/main` has carried without a reported defect. It should be recorded as the fix if that
hazard is ever observed.

**(c) Make `Outcome` and `Continue` covariant** so `Outcome[A, Nothing]` widens, removing the
inference pressure at the source. Interesting but orthogonal: it does not change what the
constructor returns, and it weakens the union's discrimination in the same direction as the
hazard in (b). Not recommended without a concrete inference failure to point at.

### 4.6 A second, smaller finding in the same code

`continue`'s state accessors are `def`s over inlined expressions:
`new Continue[A] { def _1 = v }` with `inline v: A` (`Loop.scala:151-155` and the three
higher arities). The inlined expression is substituted into `_1`'s body, so it is evaluated on
every read rather than at construction. Every current reader reads once
(`Eval.scala:59, 80`; `Loop.scala:267, 297, 329, 363`), so nothing observes it, but
`Loop.continue(sideEffecting())` does not do what it reads as. `origin/main` has the same
shape, so this is inherited rather than introduced. Worth fixing to `val` in the same pass;
it costs nothing and removes a latent surprise.

### 4.7 Blast radius

| file | change |
|---|---|
| `kyo-kernel2/.../kernel/Loop.scala:125-245` | 10 constructors lose the pending return and the `S` parameter; the comment at `:129-133` is deleted |
| `kyo-kernel2/.../kernel/Loop.scala:272-539` | 10 runner call sites now rely on the implicit conversion |
| `kyo-kernel2/CONTRIBUTING.md:139` | currency-discipline rule 4 cites these constructors as its positive example and must be rewritten |
| `kyo-kernel2/shared/src/test/.../LoopTest.scala` | 106 call sites; expected to compile unchanged, since they use inference |
| `kyo-kernel2/.../kyo/Kyo.scala` | 198 call sites; same expectation |
| `kyo-kernel2/.../kernel/ContextEffect.scala:61, 76-79` | clause bodies now rely on the conversion |

Public signatures: `Loop.continue` and `Loop.done`, all ten overloads. The change *restores*
`origin/main`'s signatures rather than introducing new ones, so the 1,750 downstream call
sites move from broken-or-inference-dependent to identical-to-today, and the 24 explicit
type-argument sites go from broken to working.

Handler clause types (`Handler.Loop.apply`, `Handler.LoopState.apply`, `Handler.scala:19, 23`)
and the `handleLoop` / `handleLoopWith` public signatures
(`ArrowEffect.scala:112, 133, 165, 192`) are **unchanged**: they keep returning
`Outcome[...] < S`, which is correct, because a clause genuinely may suspend before deciding
(`Eval.scala:53-54, 75-76` handle exactly that). The cascade the brief asks to scope honestly
turns out to stop at the constructors.

Performance gate: `Loop.scala` is not on the `Eval`/`Kyo`/`Arrow` gate list
(`kyo-kernel2/CONTRIBUTING.md:191`), and the emitted runtime code is unchanged. The risk to
measure is **compile time**, not run time: the implicit conversion plus a `LiftMacro`
expansion now happens at each of the 1,750 use sites instead of being pre-applied in the
constructor. `origin/main` pays exactly this and kernel2 currently beats it on every measured
fixture (`kernel2-backlog.md:101-103`), so the expected delta is small, but
`compile-bench-plan.md`'s harness should measure it rather than assume it.

### 4.8 What needs a maintainer ruling

Nothing in this item is genuinely underdetermined. The arity break in 4.2 is a defect, the
bare shape is `origin/main`'s proven shape, and the clause protocol does not move. Two items
need confirmation rather than a ruling:

- **R4.1** Confirmation that the module accepts moving the lift back to the use sites, since
  `kyo-kernel2/CONTRIBUTING.md:139` currently blesses the opposite and will need rewriting.
- **R4.2** Whether alternative (b), the `Done` wrapper, should be taken now rather than
  recorded. It closes a real misclassification hazard at the price of one allocation per
  region exit.

---

## 5. Documentation drift found while reading

Not TODOs, but they touch the sections above and will mislead the next reader.

| claim | site | reality at HEAD |
|---|---|---|
| "A macro-based lift with per-type static mode selection was tried and reverted" | `kyo-kernel2/CONTRIBUTING.md:126` | the macro is back (`Implicits.scala:15-20`, `LiftMacro.scala:12`), with `liftInternal` as the same-module escape (`Implicits.scala:62-75`); commit `afada84394` |
| "The lift requires `CanLift` evidence" | `kyo-kernel2/CONTRIBUTING.md:126` | there is no evidence gate (`Implicits.scala:13-14`) and no `CanLift.scala` in kyo-kernel2; the file exists only in `kyo-kernel` |
| `CanLift.scala` listed among kernel2's internal files | `kyo-kernel2/CONTRIBUTING.md:26` | the file does not exist in this module |
| rule 3's example cites `rebuildFrom` at `Eval.scala:211-214` | `kyo-kernel2/CONTRIBUTING.md:130-137` | the function is `rebuild` at `Eval.scala:270-282`; `rebuildFrom` was renamed by task #40 |
| rule 4 cites `Loop.scala:126-130` | `kyo-kernel2/CONTRIBUTING.md:139` | the comment is at `Loop.scala:129-133`, and item 4 above proposes deleting it |
| `new Node(kyo.handler, kyo.cont, hs)` quoted twice | `kyo-kernel2/CONTRIBUTING.md:55, 85` | the field is `exit`, not `cont`: `new Node(kyo.handler, kyo.exit, hs)` (`Eval.scala:116`) |
| "optional context is a `Defaulted` suspension" | `kyo-kernel2/CONTRIBUTING.md:16` | accurate today; item 3 replaces it, so this line moves with that change |

---

## 6. Dependency-ordered fix plan

The ordering is by technical dependency only.

**Step 0. Rulings, batched.** R3.1 (the context row) is the only one that gates a public
contract and should be taken first, since it is task #31's parked discussion and the rest of
item 3 is settled without it. R1.1 (the catching semantics widening) and R2.1 (naming) can be
taken together with the bracket judgment, which is already scheduled
(`kernel2-backlog.md:63-66`). R4.1 and R4.2 are confirmations.

**Step 1. Item 4, `Loop` constructors.** Independent of everything else, and the only item
that is a live parity break. Order: change the ten constructors, fix the ten runner sites,
run `kyo-kernel2` JVM tests, then compile one downstream module that uses explicit type
arguments (`kyo-aeron`'s `Topic.scala` or `kyo-core`'s `StreamCompression.scala`) against
kernel2 to prove 4.4 rather than assert it. Rewrite `CONTRIBUTING.md:139`.

**Step 2. Item 2, the cell layer, but only the part with an implementor today.** Hoist `tag`
onto the cells, introduce `Cell` with concrete `prev` and `exit` and abstract `withPrev` and
`rebuilt`, collapse `find`, `rebuild`, `replace`'s three loops, and the settled pop. Do **not**
add `Passive` yet (R2.2). JMH A/B with `-prof gc`; `find`'s improvement should show on
`sharedHandlerPaysDispatch`.

**Step 3. The bracket judgment.** Task #73, with all three candidate documents now on disk
(`bracket-node-design.md`, `bracket-handler-design.md`, `bracket-effect-design.md`, all at
`db363ae396`). Its outcome fixes whether there is a passive cell kind and what the unwind walk
must run. Section 1.7's table is the input: the walk is shared by catching, enrichment, bracket
unwind, and `Loop.done` truncation, so the judgment should rule on the walk, not only on the
bracket. Two items from this document belong in that judgment rather than after it: R1.4 (the
handler design's release-everything-at-the-throw-point argument stops holding once the guard
is a cell) and R1.5 (the node design's worked example changes outcome).

**Step 4. Item 1, catching as a region, together with the enrichment per-arm `try` regions.**
These two share the same six `try` regions in `Eval.evalLoop`
(`exception-enrichment-design.md:506-523`), so landing them separately means writing those
regions twice and benchmarking them twice. Add `Passive` here, with the catch cell as its
first implementor. Delete `guarded`, `guard`, and the caveat at `ArrowEffect.scala:253-255`.
Invert `ArrowEffectTest.scala:604-611` per R1.1. JMH A/B, with a catching row added to
`KernelBench` first.

**Step 5. Item 3, the context redesign.** Depends on step 2 only in that both touch `Eval`,
and on the isolate track for R3.4. Add a context-read row to `KernelBench` before the change so
R3.3's cost is measured.

**Step 6. Isolate.** `Detached` adopts the typing discipline from 3.7 and the single-arm
`transplant` from 2.4, both of which are cheaper after steps 2 and 5.

Two items that must not be split across steps: catching and enrichment (step 4, shared `try`
regions), and the bracket judgment and the unwind walk (steps 3 and 4, shared walk).
