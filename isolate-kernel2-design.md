# Isolate on kernel2: fork over the reified handler stack

Scope: whether kernel2's `Handlers` stack subsumes the old kernel's
`Isolate` plus `Context` machinery, and what a fork primitive built on it
looks like. Analysis and design only; no source changed, nothing built or
run.

Codebases referenced throughout:

- **worktree** = this tree at `6ee19e2ebe`, module `kyo-kernel2`, package
  `kyo.kernel`.
- **origin/main** = `2e9bb02d40`, modules `kyo-kernel`, `kyo-prelude`,
  `kyo-core`.

A note on the neighbouring documents. `kernel2-context-threading-design.md`,
`kernel2-boundary-api-design.md`, `kernel2-context-typemap-design.md`, and
`kernel2-finalizer-design.md` were written against an earlier kernel2
lineage: they cite `kyo-kernel2/shared/src/main/scala/kyo/kernel2/...`,
`Kyo.ContextSnapshot`, `Rotate`, and a threaded `Context` parameter, none of
which exist in this tree (`kyo-kernel2/shared/src/main/scala/kyo/kernel2` is
absent; there is no `Context.scala` in kernel2; `ContextEffect` is an
`ArrowEffect`, worktree `kyo-kernel2/shared/src/main/scala/kyo/kernel/ContextEffect.scala:17`).
They are treated here as history, not as binding inputs. The binding inputs
are the code, `kernel-parity-gaps.md`, and the rulings stated in the brief
(the Safepoint `Interceptor` is dead, `Kyo.Bracket` is arriving as a node
kind holding acquire/use/release, exception enrichment is a separate track).

---

## Executive summary and recommendation

The reified stack subsumes the *capture* and *isolate* halves of `Isolate`
and deletes the `Context` map outright. It does not subsume `restore`, and
it does not subsume the static evidence. Concretely:

1. **The context is the stack, and it is complete for handler-answered
   operations.** Everything an operation's answer depends on lives in the
   `Handlers` cells: handler, exit arrow, `prev`, and for a stateful region
   the current state (worktree `Handlers.scala:15-35`). Three things are
   contextual and are deliberately not in it: the Safepoint budget (thread
   state, saved and restored around a drive, worktree `Eval.scala:16-20`),
   the guards installed by `Effect.catching` (chain wiring, worktree
   `Effect.scala:28-76`), and, once it lands, `Kyo.Bracket`'s resource
   lifetime. Section 1.

2. **The fork primitive is `rebuild` with two edits.** `Eval.rebuild`
   (worktree `Eval.scala:241-251`) already turns a stack prefix into a
   value. A fork needs the same walk with the cells' exits replaced by the
   identity arrow and a per-cell policy consulted. What is missing today is
   only a way to reach `hs` from a user-level suspension site; the cheapest
   sound mechanism is a `Kyo.Detached` marker on a suspension, answered by
   the evaluator in the same arm that already answers `Kyo.Defaulted`
   (worktree `Eval.scala:43-47`). Section 2.

3. **Policy moves onto the handler, and it is two cases, not a matrix.**
   `Fork.Inherit` and `Fork.Skip`, plus `forkState(state): State` on the
   stateful kind. That covers `Var.isolate.discard`, `Emit.isolate.discard`,
   every `ContextEffect`, and `ContextEffect.Noninheritable` (which becomes
   `Fork.Skip`, replacing origin/main `Context.scala:30-36`'s filter and
   `45-51`'s flag entry). Section 3.

4. **Inheriting a cell is type-sound only for handlers that cannot
   short-circuit.** A cell's `done` produces the *region's* result type
   (worktree `Handler.scala:19, 23`, `Loop.scala:84, 95`), so a transplanted
   cell that dones injects a value of the parent region's type as the
   child's result. The handlers that never done are exactly the ones
   origin/main gave `Isolate` instances to; the handlers that can done are
   exactly the ones it deliberately refused (`Abort`, `Choice`, origin/main
   `Isolate.scala:70-71`). Recommendation: make that structural by adding an
   answering handler kind whose clause cannot done, and allow `Fork.Inherit`
   only there. Sections 3 and 4.

5. **Keep a static, macro-derived, per-effect witness.** The dynamic stack
   cannot tell the compiler which cells will be present at the fork (the row
   is static, the stack is dynamic, and worktree `EvalTest.scala:362-365`
   pins that they can diverge), and it cannot force a user to choose a
   write-back policy. Keep `Isolate.derive`'s fold and its error message;
   shrink the instance to `isolate` plus `restore`, dropping the `State`
   type member, the `capture` phase, and `andThen`'s state pairing (a fold
   of two phases instead of three, origin/main `Isolate.scala:189-201`).
   Section 4 and 8.

6. **Restore is joiner-side, and its payload must be boxed.** Every
   kyo-core consumer except one line already applies `restore` on the join
   side (origin/main `Async.scala:124-125, 226-227, 274-275, 384-386,
   412-416`); the exception is `Fiber.initUnscoped` (origin/main
   `Fiber.scala:174-179`). Under inheritance that line becomes wrong,
   because the child now has a handler for the restored effect and would
   answer its own write-back. The fix is `nest` semantics: box the restore
   computation so the child ships it as data (worktree
   `KyoInternal.scala:15-18`, pinned by worktree `EvalTest.scala:362-365`).
   Sections 2 and 8.

7. **The read cost is the one real regression risk.** Every suspension pays
   a tag-directed walk (worktree `Eval.scala:42` into `Handlers.scala:38-45`),
   and a `Tag <:<` miss is not free (a `Thread.currentThread()` plus a
   hashed cache probe, `kyo-data/.../Tag.scala:106-107, 173-184, 380-405`).
   Worse, `ContextEffect.handle`'s layered form raises a second suspension
   per read (worktree `ContextEffect.scala:74`), so N nested `Local.let`
   bindings cost N walks per read. There is a library-only fix that resolves
   the outer value once at region entry into the cell's state; it needs no
   kernel change. Section 5.

Recommendation in one line: build the fork as a transplant of the handler
stack with per-handler policy, keep a slimmed two-phase `Isolate` witness
for the write-back and for forcing the user's choice, and gate
`Fork.Inherit` on a new answering handler kind rather than on a declared
promise.

---

## 1. Context as a value

### 1.1 What constitutes the context in kernel2

At any point where `evalLoop` is about to dispatch an operation, the state
that determines the answer is exactly `hs: Handlers` (worktree
`Eval.scala:39`), a linked list of cells:

| cell | fields | worktree citation |
|---|---|---|
| `Handlers.Empty` | none | `Handlers.scala:13` |
| `Handlers.Node` | `handler` (a `Cont` or `Loop`), `exit: Arrow`, `prev` | `Handlers.scala:15-22` |
| `Handlers.StateNode` | `handler` (a `LoopState`), `exit: Arrow`, `state`, `prev` | `Handlers.scala:24-35` |

The handler carries the tag and the clause logic (worktree
`Handler.scala:9-24`); the cell carries the region's exit continuation and,
for a stateful region, the value this entry of the region currently holds.
`Handlers.find` resolves an operation innermost-first by `tag <:< handler.tag`
(worktree `Handlers.scala:38-45`), which is what makes shadowing a property
of walk order rather than of a mechanism.

Everything else that could be called contextual is elsewhere, and each
placement is deliberate:

- **The Safepoint slot.** The per-thread depth budget and armed bit live in
  a static array indexed by slot (worktree `Safepoint.scala:21-27, 40-63`).
  `Eval.apply` saves and restores it around a drive (worktree
  `Eval.scala:16-20`), `Eval.partial` arms it (worktree `Eval.scala:28`).
  This is thread state, not value state, and a fork must not copy it: the
  child gets a fresh budget from whatever thread runs it.
- **`Effect.catching` guards.** A guard is not a cell. `guarded` rewraps the
  continuation of a suspension, the `cont` of a `Defer`, and the *exit* of a
  region node (worktree `Effect.scala:53-75`), so the guard travels in the
  chain, not on the stack. Consequence: a `catching` around a fork
  expression does not cover the child, because the child is a separate
  value and not part of the parent's guarded chain. This matches origin/main,
  where a fiber's throws are caught by `IOTask`'s own `try` (origin/main
  `IOTask.scala:119-124`).
- **A `Defaulted` suspension's fallback.** Carried on the node and resolved
  through `root` so it survives `map` (worktree `KyoInternal.scala:39-41,
  51, 61-66`; consumed at `Eval.scala:44-47`).
- **`Kyo.Bracket`, once it lands.** A resource lifetime, not an answer
  source. Section 7.

So: **the kernel2 context is complete for the question "who answers this
operation, and with what state", and it is incomplete by design for
"what is the current thread's budget" and "what exception guard is in
force".** No ambient map, no interceptor, no trace exists to be forgotten
(`kernel-parity-gaps.md:25-27` records `Context.scala` and `Trace.scala` as
absent).

### 1.2 Contrast with origin/main

origin/main splits the same notion in two:

- **`ContextEffect` state** is a `Map[Tag[Any], AnyRef]` threaded as a
  parameter through every continuation application (origin/main
  `Context.scala:13`, `KyoInternal.scala:59`). It is a value, it is
  enumerable, and `Isolate.internal.runDetached` copies it wholesale
  (origin/main `Isolate.scala:228-232`).
- **`ArrowEffect` handlers** are not a value you can enumerate. While a
  handled computation is being driven, the handler is a live JVM frame:
  `handleLoop` calls `kyo(v, context)` and the user code down to the next
  suspension runs inside that frame (origin/main `ArrowEffect.scala:129-147`).
  Across a park, the handler survives as an opaque `KyoContinue` wrapper
  minted per crossing suspension (origin/main `ArrowEffect.scala:137-142`).
  You cannot ask "which handlers are installed", cannot filter them, and
  cannot reorder them.

That asymmetry is the whole reason `Isolate` has the shape it has. Simple
state copying works for context effects *because* their state is a value;
complex state management exists for arrow effects *because* theirs is not
(origin/main `Isolate.scala:32-41` states exactly this split). kernel2
erases the split: `ContextEffect[A] extends ArrowEffect[Const[Unit], Const[A]]`
(worktree `ContextEffect.scala:17`) and provision is an answering handler
layer (worktree `ContextEffect.scala:57-62`), so both halves live on one
enumerable stack. One transplant primitive can therefore replace both halves
of `Isolate`'s runtime work.

### 1.3 The one thing the stack is not

The stack is the *dynamic* set of entered regions, which is not the same
object as the static effect row. worktree `EvalTest.scala:362-365` pins the
divergence: a boxed computation passes out through the region that was
lexically around it and evaluates under a different, later region. So "the
row says `Var[V]` is pending" does not imply "a `Var[V]` cell is on the
stack here", in either direction. Section 4 is about what that costs.

---

## 2. Capture and restore: what the fork primitive is

### 2.1 `rebuild` is already the capture half

`Eval.rebuild(top, stop, acc)` walks cells from `top` down to `stop` and
wraps `acc` in one region node per cell, states included (worktree
`Eval.scala:241-251`). It is used in three places, all of which are exactly
"turn the handler context into a value":

- the residual of `Eval.partial` at an unhandled suspension:
  `rebuild(hs, Empty, v)` (worktree `Eval.scala:49`);
- the continuation handed to a `Cont` clause:
  `o => rebuild(hsAll, node, resume(kCont, ...))` (worktree
  `Eval.scala:90-91`), which is why capture is multi-shot by construction
  (worktree CONTRIBUTING.md:110, pinned by `EvalTest.scala:286-296`);
- the resumptions of clauses that suspended before deciding (worktree
  `Eval.scala:124-164`).

The values it produces re-enter cheaply: `RebuiltNode`/`RebuiltStateNode`
re-enter the original cell object by identity when they land where they
were built from (worktree `Eval.scala:99-112, 224-239`). Since a fresh
`Eval` starts at `Empty` (worktree `Eval.scala:118`) and a residual built
with `stop = Empty` bottoms out at `Empty`, a parked remainder re-driven on
another thread re-enters its original cells with no allocation. That is the
park path, and it is already cross-thread safe (section 6).

### 2.2 Why a fork cannot reuse `rebuild` unchanged

Two differences, and both are load-bearing.

**Difference 1: exits.** `rebuild` preserves each cell's `exit` arrow,
because a residual resumes the parent's own computation and must run the
parent's post-region continuations. A fork must not. With the `*With`
handling variants the exit *is* the code after the region: the object
returned by `handleWith`/`handleLoopWith` is simultaneously the handler,
the region node, and the exit arrow, and its `apply` runs `cont(res)`
(worktree `ArrowEffect.scala:89-104, 139-155, 199-217`). Transplanting such
a cell verbatim would make the child, on completing, run the parent's
continuation. So a fork's copy of a cell must carry a neutral exit
(`Arrow[A]`, the identity, worktree `Arrow.scala:31`), which also means it
cannot reuse the parent's cell object: one fresh cell per inherited layer,
per fork. That is a cold path and the cost is acceptable, but it is a real
difference from the park path.

**Difference 2: policy.** Some cells must not be copied at all
(`Noninheritable` context effects, bracket regions), and some stateful cells
must start the child from a different state (`Emit`'s accumulator starts
empty, origin/main `Emit.scala:225-236`). Section 3.

### 2.3 What is missing to capture at a user-level suspension site

`hs` exists only as a local of `evalLoop` (worktree `Eval.scala:39`). No
user-reachable operation can observe it, and no ordinary `ArrowEffect` can:
a clause is given the operation's input and a continuation, never the stack
(worktree `Handler.scala:14-24`), and by construction a clause runs outside
its own region (worktree CONTRIBUTING.md:99, `Eval.scala:75`,
`EvalTest.scala:72-81`). Parking the fork as an unhandled suspension does
not help either: `Eval.partial` wraps the *whole* suspension including its
continuation in the onion (worktree `Eval.scala:49`), and there is no way to
re-associate that onion onto the child alone.

So exactly one thing is missing: an evaluator arm that reads `hs`. The
minimal shape reuses the precedent already in the tree.

```scala
// worktree KyoInternal.scala, beside Kyo.Defaulted (KyoInternal.scala:39-41)
private[kyo] trait Detached:
    self: Suspend[?, ?, ?, ?, ?, ?] =>
    def child: Any
```

```scala
// worktree Eval.scala, in the existing find-miss arm (Eval.scala:43-49)
case Empty =>
    kyo.root match
        case d: Kyo.Defaulted =>
            loop(resume(kyo.cont, Nested.lift(d.default)), hs)
        case d: Kyo.Detached =>
            loop(resume(kyo.cont, Nested.lift(transplant(hs, d.child))), hs)
        case _ =>
            if !partial then throw new IllegalStateException(s"unhandled suspension: $kyo")
            else rebuild(hs, Empty, v)
```

Three properties make this the right mechanism rather than a new node kind:

- It adds nothing to the hot dispatch. The `Empty` arm is already the cold
  miss path; the settled-value and `Defer` arms are untouched.
- `root` delegation gives it the same survives-a-`map` property `Defaulted`
  relies on (worktree `KyoInternal.scala:51, 56-77`).
- `Nested.lift` is exactly right: `Kyo` extends `Boxed` (worktree
  `KyoInternal.scala:26`), so lifting a computation boxes it (worktree
  `KyoInternal.scala:15-18`) and the fork site receives the transplanted
  child **as data**, not as something the current drive continues into. This
  is the currency discipline the kernel already documents (worktree
  CONTRIBUTING.md:125-138) and the semantics `EvalTest.scala:362-365` pins.

The transplant itself:

```scala
// worktree Eval.scala, beside rebuild
// A fork's copy of the region stack: the same handlers, neutral exits, and
// each handler's own answer to what its region contributes to a child.
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

It is a flat `@tailrec` loop over an immutable list, so it names its
stack-safe carrier the same way `rebuild` and `replace` do (worktree
CONTRIBUTING.md:115-121). The erased cell patterns sit at the boundary
`rebuild` already documents (worktree CONTRIBUTING.md:173).

The user-facing suspension is one object, shaped like the `Defaulted`
constructor (worktree `ContextEffect.scala:38-47`):

```scala
private[kyo] inline def detach[A, S](v: A < S)(using inline _frame: Frame): (A < S) < Any =
    new Kyo.Suspend[Const[Unit], Const[Any], DetachEffect, Any, A < S, Any] with Kyo.Detached:
        def tag   = Tag[DetachEffect]
        def input = ()
        def frame = _frame
        def cont  = Arrow[A < S]
        def child = v
```

and the fork site is one `map`:

```scala
Effect.detach(child).map(detached => IOTask(detached))
```

`map`'s `Kyo.unnest` (worktree `Pending.scala:39`) unboxes it for the
callback, so `detached` is the child wearing the parent's handler onion,
ready to be driven by a fresh `Eval` on the fiber's thread.

### 2.4 Ordering: the fork's own layers go inside the transplant

`transplant(hs, X)` puts the parent's cells *outside* `X`, and `find` picks
innermost-first (worktree `Handlers.scala:38-45`). A fiber that owns an
effect (its `Abort` completion layer, its `Join` park layer, per
`iotask-kernel2-integration.md:652-670`) must therefore wrap the child
*before* detaching, so those layers shadow anything inherited:

```scala
Effect.detach(boundaryLayers(child)).map(IOTask(_))
```

With the reverse nesting an inherited `Abort.run` cell from the parent would
shadow the fiber's own completion layer, which is both semantically wrong
and, per section 4.1, a type lie.

### 2.5 Restore, and why the child must ship it boxed

Inheriting handlers changes where a write-back lands. In origin/main, a
`restore` mapped into the fiber's computation stays pending because the
fiber has no handler for the restored effect; it travels in the promise
payload, which is itself a pending computation
(`opaque type Fiber[+A, -S] = IOPromiseBase[Any, A < (Async & S)]`,
origin/main `Fiber.scala:44`), and executes on whoever joins
(`kernel-isolate-analysis.md:57-75`). Under inheritance the child *does*
have a handler for it (the inherited cell), so the write-back would be
answered inside the child and silently dropped.

Two facts make this small in practice. First, every kyo-core consumer that
picks a real isolate already applies `restore` on the joiner side:
`Async.mask` (origin/main `Async.scala:124-125`), `race`
(`Async.scala:226-227`), `raceFirst` (`Async.scala:274-275`), `gather`
(`Async.scala:384-386`), `foreach` (`Async.scala:412-416`). Second, the one
site that maps it inward, `Fiber.initUnscoped` (origin/main
`Fiber.scala:174-179`), is reached by those consumers with an identity
isolate, because they pre-apply `isolate.isolate` and hand the result to
`Fiber.initUnscoped`.

The rule for the new design, stated once: **restore is joiner-side, and a
tunnelled restore is boxed.** That is `nest` semantics rather than `run`
semantics (origin/main `Isolate.scala:140-143` already lifts:
`isolate(state, v).map(r => Kyo.lift(restore(r)))`), and kernel2 pins the
boxed-crossing behaviour it depends on (worktree `EvalTest.scala:352-371`).

---

## 3. Per-handler fork policy

### 3.1 The shape

```scala
// worktree Handler.scala
sealed trait Handler[I[_], O[_], E <: ArrowEffect[I, O], A, -S]:
    def tag: Tag[E]
    /** What a fork crossing this region inherits from it. */
    def fork: Handler.Fork = Handler.Fork.Inherit

object Handler:
    enum Fork derives CanEqual:
        case Inherit   // the child gets a cell with this handler
        case Skip      // the cell is not copied; the child resolves outward

    trait LoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, State] extends Handler[I, O, E, A, S]:
        def apply[X](input: I[X], state: State): Outcome2[State, O[X] < (E & S), A] < S
        /** The state the child's copy of this region starts from. */
        def forkState(state: State): State = state
```

Two cases, not a share/snapshot/bar triple, because the third case is not a
runtime decision:

- **`Inherit` already is "snapshot", for state.** Cells are immutable and
  updates allocate a successor (worktree `Handlers.scala:30-31`,
  `Eval.scala:57-60`), so the child's updates never reach the parent's cell.
  Copy-on-write is the representation, not a policy.
- **A "bar" (refuse to fork across this handler) belongs in the type
  system**, not in a runtime check that throws. Section 4. Adding a `Deny`
  case would be a mechanism guarding a property the witness already holds
  (worktree CONTRIBUTING.md:142-146).

### 3.2 Mapping origin/main's consumers

| origin/main instance | citation | policy |
|---|---|---|
| `Var.isolate.discard` | `Var.scala:254-257` | `Inherit`. The child reads the inherited value, updates its private successor cells, and nothing propagates. Observably identical to today's capture-then-`runTuple`-then-drop, with the capture and the extra region deleted. |
| `Var.isolate.update` | `Var.scala:220-223` | `Inherit` plus a joiner-side `restore` (`Var.setWith`). The witness's `isolate` installs a fresh `Var.runTuple` region inside the child so the final value can be read out; see 8.2. |
| `Var.isolate.merge` | `Var.scala:237-242` | as `update`, with the user's combine on the joiner side. |
| `Emit.isolate.discard` | `Emit.scala:258-272` | `Inherit`. The child appends into its private successor cells, which nobody reads. Observably identical to `Emit.runDiscard`. |
| `Emit.isolate.merge` | `Emit.scala:225-246` | `Inherit` with `forkState(_) = Chunk.empty`, plus the joiner-side re-emit that already exists. |
| `Memo` | `Memo.scala:66` | it *is* `Var.isolate.merge[Cache]`, so it inherits Var's answer with no code of its own. |
| `Check` | `Check.scala:116-132` | as `Emit.merge`: `forkState(_) = Chunk.empty` and the joiner-side re-`require`. |
| `ContextEffect` (Env, Local, Scope) | `Env.scala:39`, `Local.scala:117`, `Scope.scala:37` | `Inherit`, no instance, matching origin/main's macro filter (`Isolate.scala:268`). |
| `ContextEffect.Noninheritable` | `ContextEffect.scala:34`, `Local.scala:118` | `Skip`, decided by one tag test at region construction inside `ContextEffect.handle`. |
| `Abort`, `Choice` | `Isolate.scala:70-71` (the deliberate non-instances) | not `Inherit`; see 4.1. Fiber-owned effects are additionally shadowed by the boundary layers per 2.4. |

`Noninheritable` deserves one precision. origin/main removes the *binding*
from a flat map, so the child falls to the effect's default (origin/main
`Context.scala:30-36`; `Local.get` defaults to `Map.empty`, origin/main
`Local.scala:125`, which yields the `Local`'s own default). On a stack, the
same behaviour requires skipping **every** cell for that tag, not just the
innermost, or an outer `let` would leak into the child. The `transplant`
walk visits every cell and asks each one, so this is automatic, and it
replaces two pieces of origin/main machinery at once: the `NoninheritableFlag`
sentinel entry (`Context.scala:45-51, 56-57`) and the fork-time filter scan
(`Context.scala:30-36`).

### 3.3 What `Isolate.andThen` becomes

origin/main's `andThen` (`Isolate.scala:189-201`) does three jobs: it pairs
the two `State`s, it nests the two `Transform`s, and it sequences
capture/isolate/restore in a fixed order. Under the transplant:

- **State pairing disappears.** There is no `State` type member left to
  pair; each region's state rides its own cell.
- **Capture ordering disappears, and improves.** origin/main folds instances
  in the order the derive macro flattens the *type* intersection
  (`Isolate.scala:263-273`), which need not match the order the handlers are
  actually nested in the program. The transplant's order is the runtime
  nesting order, by construction. This is also why `Abort` and `Choice`'s
  documented hazard (order-dependent results depending on which handler runs
  first, origin/main `Isolate.scala:70-71`) is not reproducible for the
  capture half: there is one order and it is the program's.
- **`Transform` nesting survives**, because it is about the joiner side.
  `andThen` stays, reduced to composing `isolate` and `restore`:

```scala
final def andThen[RM2, KP2, RS2](next: Isolate[RM2, KP2, RS2]): Isolate[Remove & RM2, Keep & KP2, Restore & RS2] =
    new Isolate[Remove & RM2, Keep & KP2, Restore & RS2]:
        type Transform[A] = self.Transform[next.Transform[A]]
        def isolate[A, S](v: A < ((Remove & RM2) & S))(using Frame) = self.isolate(next.isolate(v))
        def restore[A, S](v: Transform[A] < S)(using Frame)         = next.restore(self.restore(v))
```

---

## 4. Static typing

### 4.1 The soundness limit of inheriting a cell

A cell is typed `Node[I, O, E, A, S]` / `StateNode[I, O, E, A, S, State]`
where `A` is the *region's* result type (worktree `Handlers.scala:15, 24`).
Three uses of `A` matter when the cell is placed around a foreign child of
type `A2`:

1. **The answering path is safe.** A `continue` answers `O[X]`, which
   depends only on the effect signature `E` (worktree `Handler.scala:19, 23`),
   never on `A`. This is the path every read and every state update takes.
2. **The exit path is handled by 2.2.** Replacing the cell's exit with the
   identity arrow removes the `Arrow[A, B, S]` obligation.
3. **The `done` path is unsound.** `Loop.Outcome[A, O] = O | Continue[A]`
   (worktree `Loop.scala:84`, `Outcome2` at `:95`), and `Loop.done(v)` is
   just the region result lifted (worktree `Loop.scala:213-229`). The
   evaluator feeds it to the cell's exit and continues at `prev` (worktree
   `Eval.scala:67-68, 84-85`). With a neutral exit, a `done` from an
   inherited cell becomes the *child's* result, typed as the parent region's
   `A`. For `Abort.run` that is a `Result[E, A_parent]` delivered where the
   child's `A2` was expected: a `ClassCastException` arbitrarily far from
   the cause, which is precisely the failure class the kernel's own history
   is written against (worktree CONTRIBUTING.md:49).

The convergence worth noticing: **the handlers that never `done` are exactly
the effects origin/main gave `Isolate` instances to** (`Var`, `Emit`,
`Memo`, `Check`, and every `ContextEffect`, all of which only ever
`continue`: origin/main `Var.scala:148-157`, `Emit.scala:95-96`,
`Check.scala:125-126`, worktree `ContextEffect.scala:61, 71-80`), **and the
handlers that can `done` are exactly the ones it deliberately refused**
(origin/main `Isolate.scala:70-71`).

Two ways to hold that property:

- **(a) Declared.** `override def fork = Fork.Inherit` on handlers whose
  author asserts they never done. Cheap, and a proof obligation discharged
  by hand at every edit, forever.
- **(b) Structural.** Add a handler kind whose clause type has no done
  branch, and make `Fork.Inherit` available only on it:

```scala
trait Answer[I[_], O[_], E <: ArrowEffect[I, O], A, S] extends Handler[I, O, E, A, S]:
    def apply[X](input: I[X]): O[X] < (E & S)

trait AnswerState[I[_], O[_], E <: ArrowEffect[I, O], A, S, State] extends Handler[I, O, E, A, S, State]:
    def apply[X](input: I[X], state: State): (State, O[X] < (E & S)) < S
    def forkState(state: State): State = state
```

The module's headline invariant answers this: "when you design a change to
this kernel, the question to ask of every property you rely on is 'what
keeps this true?'. If the answer is a code path, you are adding an
obligation" (worktree CONTRIBUTING.md:33). Recommendation: **(b)**, with the
vocabulary taken from terms the guide already uses ("answering", worktree
CONTRIBUTING.md:16, 96-97, 166) rather than invented, and the final naming
left to the maintainer per worktree CONTRIBUTING.md:172.

Two notes on (b)'s cost. It is a genuine addition to the handling family
(five kinds instead of three), so it is not free. And it needs the
state-aware region exit that kernel2 currently lacks: origin/main's stateful
`handleLoop` takes `done: (State, A) => B` alongside the clause (origin/main
`ArrowEffect.scala:530-541`), which is how `Var.runTuple` gets the final
state (origin/main `Var.scala:194-195` through `runWith`'s
`done = f`, `Var.scala:148-159`); worktree kernel2 has no counterpart, since
its stateful `handleLoop` exits with `Arrow[A]` (worktree
`ArrowEffect.scala:176`) and `handleLoopWith`'s continuation does not see
the state (worktree `ArrowEffect.scala:199-217`). That gap is not listed in
`kernel-parity-gaps.md:55-63` and it blocks `Var.runTuple`, `Emit.run`, and
`Check.runChunk` independently of anything in this document.

### 4.2 What still has to be in the types

The child's row must stay sound, and the stack cannot make it so.

- **`Restore` stays.** A write-back is a computation that runs against the
  *joiner's* handlers. Its effects are in the joiner's row, and no dynamic
  knowledge at the fork can produce that type.
- **`Keep` stays**, unchanged in meaning: the row the fork's own runtime
  serves during detached execution (`Sync` for `Fiber.init`,
  `Abort[E] & Async` for `Async.foreach`, origin/main `Fiber.scala:130`,
  `Async.scala:120`). It was never about isolation.
- **`Remove` stays, and keeps its real job**, which is not "these effects
  get handled" but "the user has been made to choose what happens to these
  effects at the boundary". `Isolate.deriveImpl` requires an instance per
  non-`Keep`, non-`ContextEffect` component and otherwise emits the
  four-option teaching error (origin/main `Isolate.scala:263-320`); the pin
  for that behaviour is origin/main
  `kyo-core/shared/src/test/scala/kyo/AsyncTest.scala:31`
  (`typeCheckFailure("Fiber.initUnscoped(Var.get[Int])")`). Nothing about a
  runtime stack reproduces that.

The derivation's filter should change its criterion. Today it is
`_ <:< TypeRepr.of[ContextEffect[Any]]` (origin/main `Isolate.scala:268`),
which is still a valid *static* discriminator in kernel2 (a `ContextEffect`
subtype is recognisable in the macro). It is now a weaker *justification*
than it was: in origin/main a `ContextEffect` could only be handled by
`ContextEffect.handle` (it extended `Effect`, not `ArrowEffect`, origin/main
`ContextEffect.scala:25`), so it was structurally answer-only. In kernel2 it
extends `ArrowEffect` (worktree `ContextEffect.scala:17`), so a user can
handle a context effect with a `done`-capable `handleLoop` and the subtype
test no longer implies forkability. The clean replacement is to filter on
fork-transparency (a declared marker, or "all its handlers are of the
answering kind" under 4.1(b)) rather than on `ContextEffect` subtyping.

### 4.3 What the dynamic stack knowledge does not buy

Stated plainly, because the temptation is to over-claim:

1. **It does not tell the compiler which cells will be present.** The row is
   static, the stack is dynamic, and worktree `EvalTest.scala:362-365` pins
   that a computation can be evaluated under regions other than the ones
   that lexically enclosed it. A fiber whose effects are handled only by
   handlers installed after the fork still needs a static story.
2. **It does not type `done`.** Section 4.1.
3. **It does not make anyone choose.** Silent inherit-and-discard for `Var`
   would compile where origin/main refuses.
4. **It does not check the handler closure.** Section 6.
5. **It does not eliminate `restore`.** Section 2.5.

What it does buy, and this is not small: the correct capture ordering for
free, deletion of the `State` type member and the `capture` phase from every
instance, deletion of the `Context` map and its inherit filter, and a fork
whose per-effect runtime content is zero for the common case.

---

## 5. Read cost for context effects

### 5.1 The cost, precisely

Every suspension resolves through `hs.find(kyo.tag)` (worktree
`Eval.scala:42`), a `@tailrec` walk testing `tag <:< l.handler.tag` per cell
(worktree `Handlers.scala:38-45`). Per non-matching cell that test is:
`fastPathEqual` (a reference identity check, then a `String.hashCode`
comparison for two static tags, `kyo-data/shared/src/main/scala/kyo/Tag.scala:173-184`),
then `checkTypes`, which mixes both hash codes, reads
`Thread.currentThread().hashCode`, and does two dependent array loads into
the per-thread subtype cache (`Tag.scala:380-405`). Cached, so no type
decoding, but not free, and the `Thread.currentThread()` is per cell.

On top of that, `ContextEffect.handle`'s layered form raises a second
suspension **per read**: the clause calls `probe(effectTag)`, a `Defaulted`
suspension that resolves against the outer handlers because a clause runs
outside its own region (worktree `ContextEffect.scala:66-80, 86-93`). So a
read under N nested same-tag bindings costs N `find` walks plus N clause
invocations, and each walk is O(distance to its answering cell). `Local.let`
and `Env.runAll` both use that form (origin/main `Local.scala:130-138`,
`Env.scala:98`), and `Local.get` is an ordinary read on the same path
(origin/main `Local.scala:124-128`).

origin/main's profile is the mirror image: a read is a lookup in a
`Map[Tag[Any], AnyRef]` with a handful of entries (origin/main
`Context.scala:13, 37-42`), but every binding re-wraps **every crossing
suspension** in a fresh `KyoContinue` that recomputes `context.set`
(origin/main `ContextEffect.scala:153-167`), so the cost is one allocation
per crossing per enclosing binding. Neither kernel is free; kernel2 moved
the cost from crossings to reads and from allocation to walking.

### 5.2 The mitigation that needs no kernel change

The per-read `probe` is removable in the library. `ContextEffect.handle`'s
layered form can resolve the outer value **once, at region entry**, and
store the merged value in the region's state cell; a read is then one `find`
returning the cell's state.

```scala
inline def handle[A, E <: ContextEffect[A], B, S](
    inline effectTag: Tag[E],
    inline ifUndefined: A,
    inline ifDefined: A => A
)(v: B < (E & S))(using inline frame: Frame): B < S =
    probe(effectTag).map { outer =>
        val bound =
            if outer.asInstanceOf[AnyRef] eq undefined then ifUndefined
            else ifDefined(outer.asInstanceOf[A])
        ArrowEffect.handleLoop(effectTag, bound, v)([X] => (_, s) => Loop.continue(s, s))
    }
```

The probe still resolves outward, because the region node is not entered
when the `map` runs. `ifDefined` is a pure `A => A` at every call site
(origin/main `Local.scala:131, 134-138`, `Env.scala:98`,
`Scope.scala:132`), so moving it from per-read to per-entry is not
observable. The state cell carries the bound value across parks and
multi-shot re-entry for free, both already pinned (worktree
`EvalTest.scala:275-284, 432-443`).

This removes the N-nested-bindings factor. It does not remove the one walk
per read, and nothing can without a cache, which would be a mechanism
guarding a property the walk already has.

### 5.3 What to measure

Per worktree CONTRIBUTING.md:168 any such change carries a JMH A/B against a
frozen baseline with `-prof gc`. Rows this design needs and the tree does
not have:

- `Local.get` and `Env.get` under D unrelated intervening regions, D in
  {1, 4, 16, 64}, measuring the walk's slope.
- the same under N nested same-tag bindings, N in {1, 2, 4}, before and
  after 5.2.
- old kernel versus kernel2 on an `Env`-heavy and a `Local`-heavy program,
  which is the only comparison that answers "did the map-to-walk trade cost
  us".

Unverified: no benchmark was run for this document.

---

## 6. Cross-fiber sharing

### 6.1 Audit

The kernel2 main sources contain exactly one `var`, two `ThreadLocal`s, and
one atomic array:

| site | worktree citation | verdict |
|---|---|---|
| `Handlers.Node` / `StateNode` fields | `Handlers.scala:15-19, 24-29` | all `val`, therefore final: safe to publish, safe to share. Updates allocate successors (`withState`, `withPrev`, `:30-34`). |
| `Kyo` nodes | `KyoInternal.scala:43-189` | `Suspend`, `Defer`, `Handled`, `HandledState` expose `def`/`val` members and are built once; `map` allocates a new node (`:56-77, 84-91, 111-124, 152-167`). |
| `Arrow` | `Arrow.scala:11-141` | immutable. `AndThen.step` uses a per-thread scratch `ArrayDeque` (`:42-44, 113-114`) and returns a freshly linked chain, so concurrent `step` on a shared arrow is safe and uncached (it re-flattens per call). |
| `Eval.Suspended.rest` | `Eval.scala:169` | the only `var`. Created at a suspension inside `evalChain` and consumed by the same call; the comment at `:166-168` states it never escapes. |
| `Safepoint.depths` | `Safepoint.scala:23-27` | a plain array written only by the slot's owning thread; no cross-thread reader. |
| `Safepoint.slots` | `Safepoint.scala:28` | `AtomicReferenceArray`; the cross-thread channel is `stop`'s CAS (`:158-172`) read by `consumeStopped` (`:174-180`). Slot reuse after thread death is guarded by `isAlive` plus CAS (`:84-93`). |
| `Safepoint.local` | `Safepoint.scala:29` | thread-local slot cache. |

So the value graph a fork ships (cells, handlers, arrows, nodes) has no
mutable kernel state in it, and the park path already relies on this: a
residual is driven on another thread by `IOTask` today.

### 6.2 The mutation contract a fork must require

`StateNode.state` is never mutated; a state update replaces the cell
(worktree `Handlers.scala:30-31`, `Eval.scala:57-60`), and interior updates
path-copy through `replace` (worktree `Eval.scala:253-282`). The contract is
therefore not about the field, it is about the value:

1. **State values must be safe to publish and to read concurrently.** The
   parent keeps its cell with the same state object while the child's cell
   holds the same reference. `Chunk`, `TypeMap`, and immutable `Map` are
   fine; a state holding an array or a mutable builder is not. origin/main
   carried the same requirement implicitly (`Var.isolate`'s `capture` hands
   the raw value across, origin/main `Var.scala:205`), so this is a
   documentation obligation, not a new hazard.
2. **Handler closures become shared, and this is new.** The inline handling
   sites compile the user's clause into an anonymous handler object
   (worktree `ArrowEffect.scala:67-72, 117-123, 171-178`), so the handler
   closes over whatever the clause captured. worktree `EvalTest.scala:28-34`
   is a handler closing over a `ListBuffer`. Under origin/main's `Isolate`
   the child always ran a *fresh* handler (`Var.runTuple` inside
   `isolate.isolate`, origin/main `Var.scala:207-208`), so a captured
   mutable object was never concurrently shared by this route. Under
   inheritance it is. The design must say so: **`Fork.Inherit` asserts the
   handler's captured state is safe for concurrent use**, and that is a
   second obligation on the same declaration as 4.1, which is a further
   argument for making the forkable kind a distinct type where the
   obligation can be documented in one place.
3. **The exit arrow is not shared**, by construction: 2.2 replaces it.

---

## 7. Bracket interaction

Given the ruling that `Kyo.Bracket` arrives as a node kind holding acquire,
use, and release, and by analogy with `Kyo.Handled` it will push a cell that
the evaluator pops by running release before continuing at `prev`:

1. **A bracket cell is never transplanted.** It is a lifetime, not an answer
   source: it has no tag, so `find` never selects it (worktree
   `Handlers.scala:38-45` matches only `Node` and `StateNode`), and copying
   it would give the child a second cell holding the same release over the
   same resource. Two releases of one acquisition violates the
   exactly-once contract recorded in `kernel2-finalizer-design.md:22-24`.
   Make it structural rather than a policy: if bracket cells are a distinct
   cell type, `transplant`'s match has no arm that copies one, so double
   release is unrepresentable. This is the same test `rebuild` will need in
   the opposite direction (a *park* must preserve bracket cells, since the
   remainder resumes inside the region), which makes the pair the cleanest
   statement of the difference: **`rebuild` preserves exits and brackets;
   `transplant` neutralises exits and drops brackets.**

2. **Parent release ordering is untouched.** `transplant` reads the stack
   and writes nothing; the parent's cells, their order, and their `prev`
   links are unchanged, so innermost-first release still follows `prev`
   exactly as before the fork.

3. **The child cannot release what it did not acquire**, which is the
   double-release property restated. Brackets the child opens itself become
   cells in the child's own evaluation and release at the child's own region
   exits.

4. **The lifetime relationship stays explicit and stays in kyo-core.** A
   child that closes over a resource acquired by the parent can outlive the
   parent's release. That hazard exists on origin/main too and is answered
   there by construction at the call site: `Fiber.init` is
   `Scope.acquireRelease(initUnscoped(v))(_.interrupt)` (origin/main
   `Fiber.scala:138`), and `Scope` itself is a `ContextEffect[Scope.Finalizer]`
   (origin/main `Scope.scala:37`) whose value is a shared finalizer object,
   so a child that inherits the `Scope` cell registers into the *same*
   finalizer rather than into a copy. Inheritance preserves that, which is
   the right answer: `Scope` is inherited (it is a context effect and its
   payload is a shared handle), while the bracket cell it may sit above is
   not.

5. **A fork inside `acquire` or inside `release`** detaches a child that
   outlives the region either way; the rules above are unchanged, and this
   is worth one pin each.

---

## 8. Surface sketch and the consumer list

### 8.1 The kernel surface

Three additions, all `private[kyo]` except the policy on `Handler`:

```scala
// worktree kyo/kernel/internal/Handler.scala
sealed trait Handler[I[_], O[_], E <: ArrowEffect[I, O], A, -S]:
    def tag: Tag[E]
    def fork: Handler.Fork = Handler.Fork.Inherit   // see 4.1 on which kinds may say Inherit

// worktree kyo/kernel/internal/Eval.scala
private[kernel] def transplant(top: Handlers, child: Any < Nothing): Any < Nothing

// worktree kyo/kernel/Effect.scala (or a sibling)
private[kyo] inline def detach[A, S](v: A < S)(using inline frame: Frame): (A < S) < Any
```

`detach` is the whole user-invisible mechanism: it yields the child wearing
the parent's handler onion, boxed, as an ordinary value.

### 8.2 The library surface: `Isolate`, two phases

```scala
abstract class Isolate[Remove, -Keep, -Restore]:
    self =>
    type Transform[_]

    /** Runs the computation under the fork's own interpretation of Remove,
      * seeded from what the fork inherited. */
    def isolate[A, S](v: A < (Remove & S))(using Frame): Transform[A] < (Remove & Keep & S)

    /** Applies the isolated run's effect on the joiner's handlers. */
    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)

    final def use[A](f: this.type ?=> A): A = f(using this)
    final def andThen[RM2, KP2, RS2](next: Isolate[RM2, KP2, RS2]): Isolate[Remove & RM2, Keep & KP2, Restore & RS2] = ...
```

Gone relative to origin/main `Isolate.scala:80-201`: the `State` type
member, `capture`, the `state` parameter of `isolate`, and `andThen`'s state
pairing. `nest` and `run` survive as conveniences over the two phases.
`Identity` (origin/main `Isolate.scala:246-251`) becomes the instance for
every effect that only needs inheritance.

`Var.isolate.update` under this shape:

```scala
def update[V](using Tag[Var[V]]): Isolate[Var[V], Any, Var[V]] =
    new Isolate[Var[V], Any, Var[V]]:
        type Transform[A] = (V, A)
        def isolate[A, S](v: A < (Var[V] & S))(using Frame) =
            Var.use[V](s => Var.runTuple(s)(v))          // the read runs in the child, under the inherited cell
        def restore[A, S](v: (V, A) < S)(using Frame) =
            v.map(Var.setWith(_)(_))                     // unchanged from origin/main Var.scala:222-223
```

The `Var.use` that replaces `capture` executes inside the child against the
inherited cell, which is what makes the phase disappear rather than move.
The fresh `Var.runTuple` region shadows the inherited cell for the child's
body, so the child's writes are captured by a region whose final state can
be read out, and the inherited cell is only ever read.

### 8.3 Every consumer, one line each

origin/main, kyo-prelude:

| consumer | citation | mapping |
|---|---|---|
| `Var.isolate.update` | `Var.scala:220-223` | two-phase instance as 8.2; `restore` unchanged. |
| `Var.isolate.merge` | `Var.scala:237-242` | same, user combine in `restore`. |
| `Var.isolate.discard` | `Var.scala:254-257` | becomes `Identity`: inheritance already discards. |
| `Emit.isolate.merge` | `Emit.scala:225-246` | `isolate = Emit.run`, `forkState(_) = Chunk.empty` on the handler, `restore` unchanged. |
| `Emit.isolate.discard` | `Emit.scala:258-272` | becomes `Identity`. |
| `Memo` | `Memo.scala:66` | unchanged text: it delegates to `Var.isolate.merge[Cache]`. |
| `Check` | `Check.scala:116-132` | `isolate = Check.runChunk`, `forkState(_) = Chunk.empty`, `restore` unchanged. |
| `Env`, `Local`, `Scope` | `Env.scala:39`, `Local.scala:117-118`, `Scope.scala:37` | no instance; inherited. `Local.initNoninheritable`'s tag yields `Fork.Skip`. |

origin/main, kyo-core:

| consumer | citation | mapping |
|---|---|---|
| `Fiber.initUnscoped` | `Fiber.scala:165-179` | `Isolate.internal.runDetached` deleted; body becomes `Effect.detach(boundaryLayers(isolate.isolate(v))).map(IOTask(_))`, with `restore` moved to the joiner or boxed per 2.5. |
| `Fiber.init` / `Fiber.use` | `Fiber.scala:129-156` | unchanged; they delegate to `initUnscoped`. |
| `Fiber.internal.foreachIndexed` and siblings | `Fiber.scala:750, 793, 905` | the three further `runDetached` sites map the same way, and can share one `detach` per batch. |
| `Async.mask`, `race`, `raceFirst`, `gather`, `foreach` | `Async.scala:124-125, 226-227, 274-275, 384-386, 412-416` | drop the `isolate.capture` wrapper; keep `isolate.isolate` per child and `isolate.restore` on the join side, where they already are. |
| `Clock` | `Clock.scala:442-686` | witness passed through only; signatures unchanged. |
| `KyoApp` | `KyoApp.scala:30` | unchanged. |
| `Stat` | `Stat.scala:184-191` | no `Isolate` use (the grep hits are unrelated wording); nothing to map. |
| `StreamCoreExtensions` | `StreamCoreExtensions.scala:80-1079` | witness passed through only; the explicit `Isolate[Any, Any, Any]` at `:1079` becomes `Isolate.Identity`. |
| `IOTask` | `IOTask.scala:19, 69-70, 203-214` | `context: Context` field and the `Context.empty` default disappear (the onion travels inside `curr`); `Isolate.internal.restoring` disappears with the `Interceptor`; the conditional context subclass in the factory is unnecessary. |

`kernel-parity-gaps.md:68-78` counts 19 downstream files for `Isolate`; the
table above is the main-source set. The witness type keeps its name and its
three parameters, so most of those files change in their bodies, not their
signatures.

---

## 9. Rejected alternatives

**Reintroduce a threaded `Context` parameter alongside the stack.** This is
the design in `kernel2-context-threading-design.md`, written for the earlier
lineage. Rejected: it restores the old kernel's two-notions-of-context split
that `ContextEffect extends ArrowEffect` (worktree `ContextEffect.scala:17`)
removed, it adds a parameter to every arrow application on the hot path, and
it needs a re-arming wrapper per binding per crossing (the cost origin/main
pays at `ContextEffect.scala:153-167`). The stack already answers context
reads; a second answer source is a mechanism guarding a property the first
one has (worktree CONTRIBUTING.md:142-146). The trade it wins is section 5's
read cost, which 5.2 addresses without a second representation.

**A dedicated `Kyo.Fork` node kind with its own evaluator arm.** Rejected in
favour of the `Detached` marker on a suspension (2.3): a sixth arm adds a
type test to the settled-value path of the hot match (worktree
`Eval.scala:41-117`), while the marker lives inside the already-cold
find-miss arm and inherits `root` delegation for free. The trade is one
wasted `find` walk per fork, which is a cold path.

**Copy cells verbatim, exits included.** Rejected on correctness: with the
`*With` variants the exit is the parent's post-region continuation (worktree
`ArrowEffect.scala:89-104, 139-155, 199-217`), so the child would run the
rest of the parent's program on completion. The trade the neutral exit costs
is losing `RebuiltNode`'s identity re-entry (worktree `Eval.scala:99-112`),
that is, one allocation per inherited layer per fork.

**Add a `Fork.Deny` case that throws at transplant time.** Rejected: the
refusal belongs to the witness, where it is a compile error with the
teaching message (origin/main `Isolate.scala:275-320`), not to a runtime
guard. A runtime `Deny` would be structure protecting structure.

**Declare forkability with `override def fork = Fork.Inherit` and no new
handler kind (4.1(a)).** Rejected as the recommendation, kept as the honest
interim if the answering kind is ruled too large. The trade: it is smaller
today, and it converts a type-level property into a per-edit obligation on
every handler author, with the failure mode (a `done` from an inherited cell
becoming the child's result at the wrong type) surfacing far from its cause.

**Let the child re-raise its effects to the parent across the fiber
boundary instead of inheriting handlers.** Rejected: it makes the parent a
coordinator for every child operation, serialising what the fork exists to
parallelise, and it has no answer for a parent that has already exited its
region.

**Drop the static witness entirely and rely on inheritance.** Rejected: it
deletes the property origin/main's `AsyncTest.scala:31` pins (forking with a
`Var` in the row does not compile until the user picks a policy) and
replaces it with silent discard.

---

## 10. Open questions requiring a maintainer ruling

Only value-forks; everything else in this document is either cited or is
work.

1. **Answering handler kind, or declared policy?** Section 4.1. Option (b)
   makes `Fork.Inherit`'s soundness structural at the cost of two new
   handler kinds and a rewrite of `Var`, `Emit`, `Check`, `Memo`, and
   `ContextEffect.handle` onto them. Option (a) ships sooner and leaves a
   standing proof obligation. The guide's headline invariant points at (b);
   the size of (b) is a scope call, not a correctness one.

2. **Does `Fiber.initUnscoped` keep an inward `restore` at all?** Section
   2.5. Either every restore becomes joiner-side (simplest rule, changes
   the one line at origin/main `Fiber.scala:176`), or tunnelled restores are
   boxed and the fiber payload's type documents that. Both are sound; they
   differ in what `Fiber[A, S2]` means to a reader.

3. **Should inheritance be the default, or opt-in per fork site?** Under
   this design a fork inherits every non-skipped cell. That is the old
   `ContextEffect` behaviour generalised to all effects, and it is more than
   origin/main did for arrow effects (which inherited nothing unless an
   instance said so). Defaulting to inherit is what makes the `discard`
   policies free; defaulting to skip would be closer to origin/main's
   behaviour for `Var` and `Emit` and would make every inheritance explicit.

4. **Naming.** `detach`, `transplant`, `Fork.Inherit`, `Fork.Skip`,
   `forkState`, and any answering-kind name are new nouns in a module whose
   rule is that names are maintainer-approved (worktree
   CONTRIBUTING.md:172). `Isolate` itself may or may not keep its name once
   `capture` and `State` are gone.

5. **Is the state-value publication contract (6.2) a documented obligation
   or an enforced one?** There is no evidence type in the tree for
   "effectively immutable". Documenting it matches how origin/main handled
   the same requirement; enforcing it would be a new constraint on every
   stateful handler's `State`.
