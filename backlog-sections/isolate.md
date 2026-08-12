## Isolate: fork as a transplant of the handler stack

The stack subsumes `capture` and `isolate` and deletes `Context`. It does not subsume `restore`
(joiner-side by nature) or the static witness (the row is not the stack).

### Mechanism

A fork is `rebuild` with the exits neutralised. Reaching `hs` from a user site needs one marker,
answered in the arm that already answers `Defaulted`: no hot-dispatch cost, `root` delegation free.

```scala
// KyoInternal.scala, beside Defaulted (:39-41)
private[kyo] trait Detached:
    self: Suspend[?, ?, ?, ?, ?, ?] =>
    def child: Any
// Eval.scala, the find-miss arm (:43-49), third case unchanged
case Empty =>
    kyo.root match
        case d: Kyo.Defaulted => loop(resume(kyo.cont, Nested.lift(d.default)), hs)
        case d: Kyo.Detached  => loop(resume(kyo.cont, Nested.lift(transplant(hs, d.child))), hs)
```

`Nested.lift` is the point: `Kyo <: Boxed` (`KyoInternal.scala:26`), so the fork site gets the
onion-wearing child as data, not as something this drive continues into.

```scala
// Eval.scala, beside rebuild (:241-251)
@tailrec private def transplant(top: Handlers, acc: Any < Nothing): Any < Nothing =
    top match
        case Empty => acc
        case n: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] @unchecked =>
            if n.handler.fork == Fork.Skip then transplant(n.prev, acc)
            else transplant(n.prev, Kyo.Handled(acc, n.handler, Arrow[Any]))
        case n: StateNode[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
            if n.handler.fork == Fork.Skip then transplant(n.prev, acc)
            else transplant(n.prev, Kyo.HandledState(acc, n.handler, Arrow[Any], n.handler.forkState(n.state)))
```

`Arrow[Any]` instead of `n.exit` is load-bearing: under the `*With` variants the exit **is** the parent's
post-region continuation (`ArrowEffect.scala:89-104, 139-155, 199-217`), so a verbatim copy has the child
running the rest of the parent's program on completion. Price: `RebuiltNode`'s identity re-entry
(`Eval.scala:99-112`) stops applying, one cell per inherited layer per fork. As a pair, **`rebuild`
preserves exits and bracket cells, `transplant` neutralises exits and drops brackets**, so double-release
is unrepresentable. Fiber-owned layers wrap inside the detach: inherited cells land outermost.

```scala
// Handler.scala
sealed trait Handler[I[_], O[_], E <: ArrowEffect[I, O], A, -S]:
    def tag: Tag[E]
    def fork: Handler.Fork = Handler.Fork.Inherit   // Inherit | Skip only
trait LoopState[...] extends Handler[...]:
    def forkState(state: State): State = state      // Emit/Check return their empty
```

Two cases, not three: "snapshot" is already the representation (immutable cells, `withState`
successors), and a "bar" is a compile error in the witness, not a runtime throw. `Noninheritable`
becomes `Skip` from one tag test at region construction, replacing both `Context.inherit`'s filter
scan and the `NoninheritableFlag` sentinel (origin/main `Context.scala:30-36, 45-51`).

```scala
abstract class Isolate[Remove, -Keep, -Restore]:
    type Transform[_]
    def isolate[A, S](v: A < (Remove & S))(using Frame): Transform[A] < (Remove & Keep & S)
    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)
def update[V](using Tag[Var[V]]): Isolate[Var[V], Any, Var[V]] =   // Var.isolate.update
    new Isolate[Var[V], Any, Var[V]]:
        type Transform[A] = (V, A)
        def isolate[A, S](v: A < (Var[V] & S))(using Frame) = Var.use[V](s => Var.runTuple(s)(v))
        def restore[A, S](v: (V, A) < S)(using Frame)       = v.map(Var.setWith(_)(_))
```

`State`, `capture`, and `andThen`'s state pairing are gone: the `Var.use` runs *in the child* against
the inherited cell, so the phase disappears rather than moves. Capture ordering becomes the runtime
nesting order, not `deriveImpl`'s intersection flattening order, which is why the Abort/Choice
ordering hazard (origin/main `Isolate.scala:70-71`) does not reproduce here.

### Load-bearing findings

**`done` gates `Fork.Inherit`.** A cell's `done` produces the *region's* result type (`Handler.scala:19,23`,
`Loop.scala:84,95`) and, with a neutral exit, becomes the child's result: a transplanted `Abort.run` cell
hands a `Result[E, A_parent]` where the child's `A2` was expected. The handlers that never `done` are
exactly the effects origin/main gave instances to; the ones that can are exactly the ones it refused.
Recommend an answering handler kind (no done branch in the clause type) over a declared `fork = Inherit`
that is a standing per-edit obligation. That kind needs the state-aware region exit kernel2 lacks:
origin/main's stateful `handleLoop` takes `done: (State, A) => B` (`ArrowEffect.scala:530-541`), worktree
exits with `Arrow[A]` (`ArrowEffect.scala:176`). That gap blocks `Var.runTuple`, `Emit.run`, and
`Check.runChunk` independently of this design and is not in the parity gaps.

**Restore must ship boxed.** A restore mapped into the fiber stays pending today only because the fiber
has no handler for it; under inheritance it has one, answers its own write-back, and drops it. Every
kyo-core consumer already restores joiner-side (`Async.scala:124-125, 226-227, 274-275, 384-386,
412-416`); the counterexample is `Fiber.initUnscoped` (`Fiber.scala:174-179`), reached with an identity
isolate by those consumers, so it has not bitten. Fix: `nest` semantics, box it (`EvalTest.scala:352-371`).

**Inheriting a cell shares the handler closure**, which origin/main never did (its instances always ran a
fresh handler in the child). Everything else shipped is immutable: kernel2 main has one `var`
(`Eval.Suspended.rest`, thread-confined) and `depths` is owner-thread-only.

**Context reads.** Every suspension pays `find` (`Eval.scala:42`), and a `Tag <:<` miss costs a
`Thread.currentThread()` plus a hashed cache probe per cell (`Tag.scala:173-184, 380-405`). Worse,
the layered `ContextEffect.handle` raises a `probe` per read (`ContextEffect.scala:74`), so N nested
`Local.let` cost N walks per read. Library-only fix: resolve the outer value once at region entry
and hold it as the cell's state, making a read one `find`. `ifDefined` is pure at every call site so
per-entry versus per-read is unobservable; parks and multi-shot are pinned already. No JMH run.

### Rulings

1. **Answering handler kind, or declared `fork` policy?** Structural soundness at the cost of two
   handler kinds plus rewriting Var/Emit/Check/Memo/ContextEffect, versus smaller now and the
   obligation forever. Recommend the kind.
2. **Does `Fiber.initUnscoped` keep an inward restore?** All-restores-joiner-side (one line) versus
   boxed tunnelling with the payload type documenting it. Both sound; recommend joiner-side.
3. **Inherit by default, or opt in per fork site?** Inherit makes the `discard` policies free
   instances; skip matches origin/main's arrow-effect behaviour. Recommend inherit.
4. **Naming** under the no-new-terminology rule: `detach`, `transplant`, `Fork.Inherit`/`Skip`,
   `forkState`, the answering kind, and whether `Isolate` keeps its name. No recommendation.
5. **State-value publication: documented or enforced?** No effectively-immutable evidence exists in
   the tree and origin/main documented it. Recommend documented, on the forkable kind.

Status: design only, nothing implemented, no benchmark run. Full analysis in `isolate-kernel2-design.md`.
