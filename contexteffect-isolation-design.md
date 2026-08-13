# ContextEffect isolation on kernel2

Scope: how the old kernel's "simple state copying" category, the generic
copying of `Context` map entries at a fork, is encoded on kernel2, where
there is no map and context values live as ordinary regions on the reified
handler stack. Analysis and design only; no source was changed, nothing was
built or run.

Codebases referenced throughout, always named:

- **worktree** = this tree, module `kyo-kernel2`, read at commit
  `86433c43fd83f78c60e16f813bdfc042057ab631` on branch `ci/fork-guards`
  (worktree `effervescent-painting-backus`).
  Two files carry uncommitted in-flight edits by the concurrent implementor
  landing backlog theme 7 (bare `Loop.Outcome` returns):
  `kyo-kernel2/shared/src/main/scala/kyo/kernel/Loop.scala` and
  `kyo-kernel2/shared/src/main/scala/kyo/kernel/ContextEffect.scala`, where
  the clause bodies gain an explicit `liftInternal` at the use site. That
  edit moves where the lift happens and does not change the region shape any
  encoding below depends on; line citations for `ContextEffect.scala` are to
  the committed state.
- **origin/main** = the shipped kernel, modules `kyo-kernel`, `kyo-prelude`,
  `kyo-core`, plus the downstream consumers `kyo-aeron`, `kyo-offheap`,
  `kyo-stm`.

Binding context this design is written against, taken as ruled and not
revisited: `Isolate` keeps the old kernel's design verbatim
(`Isolate[Remove, Keep, Restore]`, `type State`, `type Transform[_]`,
`capture`/`isolate`/`restore`/`nest`/`run`/`use`/`andThen`, per-effect policy
instances, `derive` for intersections, no instances for `Abort` and
`Choice`: origin/main `kyo-kernel/shared/src/main/scala/kyo/kernel/Isolate.scala:80-201`
and `:205-326`). The `Join`-effect substitution for `Transform[_]` is set
aside. Bracket is a separate track. The `Defaulted` redesign
(`kernel2-todos-design.md` section 3) is parked, so the body of this document
designs against the current `Defaulted` shape and section 6.4 states what
changes if that redesign lands.

---

## Executive summary

**Recommendation: candidate (a), boundary inheritance, narrowed to context
cells, plus a structural recognizer for those cells.** The fork transplants
exactly the regions that `ContextEffect.handle` installed, with neutral exits,
skipping the ones whose effect type mixes `ContextEffect.Noninheritable`. No
`Isolate` instance is involved for context members, and the `derive` macro's
existing filter (origin/main `Isolate.scala:268`) ports verbatim. This is the
only candidate that reproduces the old kernel's semantics, and the reason is
not a preference: candidate (b) is structurally incapable of carrying `Local`,
because `Local` never appears in any effect row (origin/main
`Local.scala:47`, `:125`), and a row-driven derivation can only see what the
row names.

Three findings decide it, each independently sufficient:

1. **`Local` is invisible to the row.** `Local.get` is `A < Any`
   (origin/main `Local.scala:47`), built from the defaulted suspend form
   (origin/main `Local.scala:125`, `ContextEffect.scala:97-102`), so
   `Local.internal.State` is never a member of `Remove`. Under (b) the derive
   macro emits nothing for it and every fork silently resets locals to their
   defaults, breaking the behavior pinned by origin/main
   `kyo-prelude/shared/src/test/scala/kyo/LocalTest.scala:114-129` and
   `:131-154`.
2. **Tag conventions are per effect, not mechanical.** `Env` installs and
   reads through an erased tag, `Tag[Env[Any]]` (origin/main `Env.scala:98`,
   `:130`, `:152`). A mechanically derived instance for row member
   `Env[Config]` that installed a cell tagged `Env[Config]` would be invisible
   to reads tagged `Env[Any]`, because `Handlers.find` tests
   `tag <:< handler.tag` (worktree `internal/Handlers.scala:47-55`) and
   `Env[Any]` is not a subtype of `Env[Config]`. The instance therefore cannot
   be derived from the row member alone; it must be written per effect.
3. **Downstream opaque aliases would each need an instance.** `Topic` is
   `opaque type Topic <: Env[AeronTransport]` (origin/main
   `kyo-aeron/.../Topic.scala:26`) and `Arena` is
   `opaque type Arena <: (Env[Arena.State] & Sync)` (origin/main
   `kyo-offheap/.../Memory.scala:178`). `Isolate` is invariant in `Remove`
   (origin/main `Isolate.scala:80`), so an `Env` instance does not serve a
   `Topic` row. Today both are covered by the `ContextEffect` filter and
   derive to `Identity` (origin/main `Topic.scala:508`, `Memory.scala:196`).

Candidate (b) is not worthless: its `capture` is exactly the shape
`Var.isolate` already uses (`capture` is a read, origin/main
`Var.scala:205`), and it has one real advantage, flattening a chain of N
nested bindings into a single cell for the child. Section 5 recommends
capturing that advantage inside candidate (a) instead, as a transplant-time
compaction, where it applies to every context effect rather than only to the
ones a row happens to name.

Answers to the five specific questions, in one line each:

- **Q3, the probe.** (a) needs no probe at the boundary: the layered chain is
  transplanted intact, so the child's per-read probes resolve exactly as the
  parent's did. (b) needs a definedness-aware capture, which the current
  `probe` cannot supply as a public shape (it is `private inline` and typed
  `Any < Any`, worktree `ContextEffect.scala:86-93`).
- **Q4, the state-aware region exit.** Neither candidate needs it for context
  members. A provision region is a stateless `Node` built through the
  stateless `handleLoop` overload with an identity exit (worktree
  `ContextEffect.scala:57-62`, `ArrowEffect.scala:111-129`), and nothing is
  read out of it at region exit. Stateful members (`Var.runTuple`,
  `Emit.run`, `Check.runChunk`) need it regardless of this design.
- **Q5, multi-shot and park.** (a)'s carrier is the immutable cell plus the
  region node, the same carrier `rebuild` already ships across parks and
  multi-shot re-entries (worktree `Eval.scala:270-282`, `:88-93`), pinned by
  worktree `EvalTest.scala:275-284`, `:286-296`, `:362-365`. (b)'s carrier is
  an ordinary suspension and an ordinary region, also safe.
- **Q1 parity bar.** Section 1; checked item by item in section 9.
- **Q6 recommendation.** Section 9, with the open value-forks in section 10.

---

## 1. The parity bar: what origin/main actually does

Every item here is what the kernel2 encoding has to reproduce. The numbering
(P1 to P11) is used again in section 9.

### 1.1 `runDetached` copies two things

```scala
// origin/main kyo-kernel/shared/src/main/scala/kyo/kernel/Isolate.scala:228-232
private[kyo] inline def runDetached[A, S](inline f: (Trace, Context) => A < S)(using inline _frame: Frame): A < S =
    new KyoDefer[A, S]:
        def frame = _frame
        def apply(v: Unit, context: Context)(using safepoint: Safepoint) =
            f(safepoint.saveTrace(), context.inherit)
```

- A **trace snapshot**, `safepoint.saveTrace()` (origin/main
  `internal/Trace.scala:121`). kernel2 has no `Trace` and exception
  enrichment is a separate track, so this half has no counterpart and is out
  of scope here.
- The **inherited context**, `context.inherit` (origin/main
  `internal/Context.scala:30-36`).

Two properties of the shape matter and both survive into kernel2's fork
design. First, it is a `KyoDefer`, so the copy happens at the fork's
**evaluation** point, not at construction: the context it copies is the one
live where the fork is driven. Second, the copy is **generic**: it does not
know which effects are in the map, it copies whatever is there.

**P1.** The fork copies the standing context values, generically, with no
per-effect participation and no static knowledge of which effects are present.

**P2.** The copy happens at the fork's evaluation point.

### 1.2 `restoring` restores neither context nor state

```scala
// origin/main Isolate.scala:234-240
inline def restoring[Ctx, A, S](trace: Trace, interceptor: Safepoint.Interceptor)(inline v: => A < (Ctx & S))(
    using frame: Frame, safepoint: Safepoint
): A < (Ctx & S) =
    Safepoint.immediate(interceptor)(safepoint.withTrace(trace)(v))
```

It restores the trace into the running safepoint (origin/main
`internal/Trace.scala:148`) and installs the interceptor (origin/main
`internal/Safepoint.scala:108-123`). It does **not** restore context. The
context reaches the child by a different route: `IOTask` carries it as a
field and passes it to the driver, `ArrowEffect.handlePartial(..., context)`
(origin/main `kyo-core/.../scheduler/IOTask.scala:19`, `:69-70`), with the
field materialized as a conditional subclass only when the copied context is
non-empty (origin/main `IOTask.scala:208-214`).

**P3.** The child evaluates under the copied context for its entire lifetime,
including across every park and reschedule, because the context is a property
of the task rather than of one drive.

### 1.3 The inheritable/noninheritable split

```scala
// origin/main internal/Context.scala:30-36
def inherit: Context =
    if !contains(Tag[NoninheritableFlag]) then self
    else
        self.filterNot { (k, _) =>
            k <:< Tag[NoninheritableFlag] || k <:< Tag[ContextEffect.Noninheritable]
        }
```

The criterion is a tag subtype test against `ContextEffect.Noninheritable`
(origin/main `ContextEffect.scala:34-35`), applied per map entry. The
`NoninheritableFlag` sentinel entry (origin/main `Context.scala:45-51`,
`:56-57`) exists only to make the common case a single lookup instead of a
scan; it is an optimization of the filter, not part of the semantics.

At **restore** there is nothing to do: nothing travels back. A child's context
changes are its own, because `Context.set` returns a new map (origin/main
`Context.scala:45-52`) threaded forward through continuations only.

The observable effect of dropping a binding is that the child falls to the
effect's own default. For `Local` that is `Map.empty`, hence each local's
`default` (origin/main `Local.scala:125`, `:97`). Production consumers of the
noninheritable variant exist: origin/main `kyo-core/.../Meter.scala:358` and
`kyo-stm/.../STM.scala:59`.

**P4.** A binding whose effect type mixes `ContextEffect.Noninheritable` does
not cross the fork, and **every** binding for that effect is dropped, not only
the innermost: the filter is over all entries.

**P5.** Nothing about context travels back from child to parent.

### 1.4 `Env`: union on nest

```scala
// origin/main kyo-prelude/shared/src/main/scala/kyo/Env.scala:93-98
def runAll[R >: Nothing, A, S, VR](env: TypeMap[R])(v: A < (Env[R & VR] & S))(
    using reduce: Reducible[Env[VR]], frame: Frame
): A < (S & reduce.SReduced) =
    reduce(ContextEffect.handle(erasedTag[R], env, _.union(env))(v): A < (Env[VR] & S))
```

with `private def erasedTag[R] = Tag[Env[Any]].asInstanceOf[Tag[Env[R]]]`
(origin/main `Env.scala:152`) and reads through the same erased tag
(origin/main `Env.scala:130`, `:146`).

Three consequences. Every `Env` region, whatever its static `R`, is one
runtime tag, so nesting is resolved by innermost-wins on a single key. The
layered form means an inner `Env.run` **merges** the outer environment rather
than replacing it. And therefore the innermost binding's effective value is
already the full union of every enclosing binding, which is why a single read
at the fork point is a complete capture of the environment.

**P6.** An inner `Env.run` composes with the outer one by union, and the value
visible at any point is the union of all enclosing bindings.

### 1.5 `Local`: merge on nest, and invisible to the row

```scala
// origin/main kyo-prelude/shared/src/main/scala/kyo/Local.scala:124-138
def get(using Frame) =
    ContextEffect.suspendWith(tag, Map.empty)(_.getOrElse(this, default).asInstanceOf[A])
def let[B, S](value: A)(v: B < S)(using Frame) =
    ContextEffect.handle(tag, Map.empty[Local[?], AnyRef].updated(this, value), _.updated(this, value.asInstanceOf[AnyRef]))(v)
def update[B, S](f: A => A)(v: B < S)(using Frame) =
    ContextEffect.handle(tag, Map(this -> f(default)), map => map.updated(this, f(map.getOrElse(this, default).asInstanceOf[A]).asInstanceOf[AnyRef]))(v)
```

All locals of one variety share one tag, `Tag[Local.internal.State]` or
`Tag[Local.internal.NoninheritableState]` (origin/main `Local.scala:96`,
`:112`, `:117-118`), and the value is one `Map[Local[?], AnyRef]`. Like `Env`,
the layered form merges, so the innermost binding's effective value is the
merged map of every enclosing `let` and `update`.

The decisive property is the row. `Local.get: A < Any` (origin/main
`Local.scala:47`), because the defaulted suspend form erases the effect from
the row (origin/main `ContextEffect.scala:97-102`, which returns `B < S` with
no `E`). **No user computation ever carries `Local.internal.State` in its
effect row.** Isolation for locals is therefore invisible to any mechanism
driven by the row.

**P7.** Local bindings cross a fork (inheritable variety) or do not
(noninheritable variety), and this must hold for computations whose row does
not mention `Local` at all, which is all of them.

### 1.6 `Scope`: replace on nest, shared payload

```scala
// origin/main kyo-core/shared/src/main/scala/kyo/Scope.scala:132
ContextEffect.handle(Tag[Scope], finalizer, _ => finalizer)(v)
```

`Scope` is `sealed trait Scope extends ContextEffect[Scope.Finalizer]`
(origin/main `Scope.scala:37`) and reads are ordinary mandatory reads
(origin/main `Scope.scala:51`, `:67`). The `ifDefined` clause ignores the
outer value, so an inner `Scope.run` replaces rather than merges.

The payload is a shared mutable handle (a queue plus a promise, origin/main
`Scope.scala:153-172`), so a child that inherits the `Scope` binding registers
finalizers into the **same** finalizer object as the parent, not a copy. That
is load-bearing for `Fiber.init`, which is
`Scope.acquireRelease(initUnscoped(v))(_.interrupt)` (origin/main
`Fiber.scala:138`).

**P8.** Inheritance is by reference for the value, not by deep copy.

### 1.7 What `derive` produces for a mixed row

```scala
// origin/main Isolate.scala:263-273
val keep = flatten(TypeRepr.of[Keep])
val isolates =
    flatten(TypeRepr.of[Remove])
        .filterNot(t => keep.exists(t =:= _))
        .filterNot(_ <:< TypeRepr.of[ContextEffect[Any]])
        .map { t => t.asType match { case '[tpe] => t -> Expr.summon[Isolate[tpe, Keep, Restore]] } }
```

then the missing-instance error (origin/main `Isolate.scala:275-318`) and the
fold (origin/main `Isolate.scala:320-322`) seeded with `Identity`, whose
`andThen` short-circuits on either side (origin/main `Isolate.scala:190-191`).

Worked cases:

| `Remove` | result | citation |
|---|---|---|
| `Env[Config]` alone | `Identity`: the only member is filtered, the fold returns its seed | `Isolate.scala:268`, `:320` |
| `Env[Config] & Var[Int]` | `Env` filtered; `Isolate[Var[Int], Keep, Restore]` summoned; `Var`'s instances are `def`s, not givens (origin/main `Var.scala:220`, `:237`, `:254`), so the summon fails and the teaching error fires. Pinned by origin/main `kyo-core/shared/src/test/scala/kyo/AsyncTest.scala:30-34` | `Isolate.scala:266-318` |
| `Env[Config] & Memo` | `Env` filtered; `Memo`'s given (origin/main `Memo.scala:66`) summoned; fold yields it unchanged through the `Identity` short-circuit | `Isolate.scala:190`, `:320` |
| `Topic` (opaque, `<: Env[AeronTransport]`) | filtered as a `ContextEffect`, so `Isolate.derive[Env[AeronTransport], Any, Any]` is `Identity` | `Topic.scala:26`, `:508` |

**P9.** A context effect in the row requires no instance and produces no
runtime work in the witness.

**P10.** A stateful effect in the row without an instance is a compile error
carrying the four-option teaching message.

### 1.8 One more parity item the encoding must not lose

`Context` reads are exact-key map lookups (origin/main `Context.scala:22-23`,
`:37-42`); kernel2 reads are subtype walks (worktree `Handlers.scala:47-55`).
This divergence exists already, without any fork, and it is inherited by
whatever the boundary does. It matters here only in one place, noted in
section 3.2.

**P11.** The set of bindings the child sees is the set the parent saw, minus
the noninheritable ones, with the same resolution order.

---

## 2. The ground kernel2 gives the encoding

### 2.1 A provision is a stateless region whose clause never dones

```scala
// worktree kyo-kernel2/shared/src/main/scala/kyo/kernel/ContextEffect.scala:57-62
inline def handle[A, E <: ContextEffect[A], B, S](inline effectTag: Tag[E], inline value: A)(v: B < (E & S))(
    using inline frame: Frame
): B < S =
    ArrowEffect.handleLoop(effectTag, v)([X] => _ => Loop.continue(value))
```

`ArrowEffect.handleLoop`'s stateless overload builds one object that is both a
`Handler.Loop` and a `Kyo.Handled` region node, with `exit = Arrow[A]`
(worktree `ArrowEffect.scala:111-129`, exit at `:123`). Entering it costs one
cell (worktree `Eval.scala:116`). Four things follow that the encodings rely
on:

- **The provided value lives in the handler closure, not in cell state.**
  There is no `StateNode` and no state to seed or read back.
- **The clause only ever continues.** `Loop.continue` is the only constructor
  in either form (worktree `ContextEffect.scala:61`, `:71-80`), so a provision
  region can never `done`, which is the exact soundness condition the fork
  design's section 4.1 identifies for transplanting a cell
  (`isolate-kernel2-design.md:481-510`).
- **The layered form resolves outward per read.** Its clause calls
  `probe(effectTag)`, a `Defaulted` suspension whose fallback is a private
  identity sentinel (worktree `ContextEffect.scala:66-80`, `:25`, `:86-93`).
  A clause that suspends before deciding is chained and evaluated at
  `node.prev`, outside its own region (worktree `Eval.scala:74-76`,
  CONTRIBUTING.md:99), which is what makes the probe resolve against the outer
  handlers instead of looping on itself.
- **A region's exit is not stable.** Even though `handleLoop` sets the
  identity exit, a `map` after the handle site fuses into it:
  `Handled.map` with an identity exit collapses to `Handled.Impl(v, h, f)`
  (worktree `internal/KyoInternal.scala:111-124`). So `ContextEffect.handle(tag, v)(c).map(g)`
  produces a cell whose exit is `g`. Any encoding that reuses a parent cell
  must neutralize the exit; this is true for context cells, not only for the
  `*With` variants.

### 2.2 What is missing to fork at all

`hs` is a local of `evalLoop` (worktree `Eval.scala:40`) and no user-level
operation can observe it. The transplant design already on disk supplies the
one missing piece: a `Kyo.Detached` marker on a suspension, answered in the
same cold find-miss arm that already answers `Kyo.Defaulted`
(`isolate-kernel2-design.md:266-284`, against worktree `Eval.scala:43-50`),
with the answer shipped through `Nested.lift` so the child crosses as data
(worktree `internal/KyoInternal.scala:15-18`, `:26`). This document takes that
mechanism as given and designs only what the fork does with `hs` for context
cells.

---

## 3. Candidate (a): boundary inheritance

### 3.1 Mechanism

The fork walks the stack once and rebuilds the context cells around the child,
with neutral exits:

```scala
// worktree kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala, beside rebuild
// the fork's copy of the standing context: the same provision handlers,
// neutral exits, and nothing that the child did not enter itself
@tailrec private def transplant(top: Handlers, acc: Any < Nothing): Any < Nothing =
    top match
        case Empty => acc
        case n: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] @unchecked =>
            n.handler match
                case p: ContextEffect.Provision if p.inheritable =>
                    transplant(n.prev, Kyo.Handled(acc, n.handler, Arrow[Any]))
                case _ =>
                    transplant(n.prev, acc)
        case n: StateNode[?, ?, ?, ?, ?, ?]    => transplant(n.prev, acc)
        case n: FirstNode[?, ?, ?, ?, ?, ?, ?] => transplant(n.prev, acc)
```

A flat `@tailrec` loop over an immutable list, the same carrier `rebuild` and
`replace` already name (worktree `Eval.scala:270-282`, `:284-316`;
CONTRIBUTING.md:116-122). Walking from the innermost cell outward and wrapping
the accumulator preserves order: the outermost cell becomes the outermost
region node, so re-entry pushes it first and the innermost cell ends up on top,
exactly as `rebuild` behaves.

**The recognizer.** `ContextEffect.Provision` is a named trait mixed into the
handler that `ContextEffect.handle` installs. Today `ContextEffect.handle`
routes through the inline `ArrowEffect.handleLoop` (worktree
`ContextEffect.scala:61`, `:71`), so acquiring the mixin means
`ContextEffect.handle` constructs its own object instead, roughly ten lines
mirroring worktree `ArrowEffect.scala:117-123`:

```scala
// worktree kyo-kernel2/shared/src/main/scala/kyo/kernel/ContextEffect.scala
private[kyo] trait Provision:
    self: Handler.Loop[?, ?, ?, ?, ?] =>
    /** False when the effect type mixes Noninheritable, decided once at region construction. */
    def inheritable: Boolean
```

Two reasons to prefer this over testing `handler.tag <:< Tag[ContextEffect[Any]]`
at fork time. First, cost: an `instanceof` per cell instead of a `Tag` subtype
test, which is not free (a `fastPathEqual`, then a `checkTypes` that reads
`Thread.currentThread().hashCode` and does two dependent cache loads, kyo-data
`Tag.scala:106-107`, `:173-184`, `:380-405`). Second, and more important,
correctness: kernel2 made `ContextEffect` an `ArrowEffect` (worktree
`ContextEffect.scala:17`), so a user **can** now install a `handleLoop` over a
context effect's tag whose clause dones, which origin/main could not express
(`ContextEffect extends Effect` there, origin/main `ContextEffect.scala:25`,
and only `ContextEffect.handle` could install a provision). A tag test would
admit such a cell for transplant, and transplanting a cell that can `done`
injects a value of the parent region's result type as the child's result
(`isolate-kernel2-design.md:493-502`). The mixin admits only the object
`ContextEffect.handle` builds, whose clause is fixed in kernel source and
provably only continues. The property "this cell can be transplanted" is then
held by the shape of the thing being tested, which is what the module's
headline invariant asks for (worktree CONTRIBUTING.md:31-33).

**The consumer delta.** `Isolate`'s surface does not change. Only
`Fiber.initUnscoped` and the three `Fiber.internal` fork sites change
internally:

```scala
// origin/main kyo-core/shared/src/main/scala/kyo/Fiber.scala:174-179, target shape
Effect.detach(boundaryLayers(isolate.capture { state => isolate.isolate(state, v) }))
    .map(child => IOTask(child, parent))
```

with `Async.mask`, `_timeout`, `race`, `raceFirst`, `gather`, and `foreach`
(origin/main `Async.scala:124-125`, `:191-192`, `:226-227`, `:274-275`,
`:384-386`, `:412-416`) untouched: they already call `capture`, `isolate`, and
`restore` around the fork. `IOTask`'s `context` field, the `Context.empty`
default, and the conditional subclass in the factory (origin/main
`IOTask.scala:19`, `:208-214`) all disappear, because the context now travels
inside `curr` as region nodes. The fiber's own layers must wrap the child
before detaching, so they shadow anything inherited
(`isolate-kernel2-design.md:344-358`).

### 3.2 Noninheritable

The bit is decided once, at region construction, inside `ContextEffect.handle`,
by the same predicate origin/main uses:
`Tag[E] <:< Tag[ContextEffect.Noninheritable]` (origin/main
`Context.scala:34`, `:47`). It is stored on the provision handler as
`inheritable`, so the transplant reads a field rather than running a tag test
per cell per fork.

Placing the test at construction rather than at fork time costs one tag test
per `Local.let`, which is warmer than a fork. The alternative placement (test
at fork time) costs one tag test per provision cell per fork and keeps region
construction untouched. Both are correct; the choice is a measurement
question, listed as open value-fork V3 in section 10.

Two parity properties fall out for free:

- **P4's "every binding, not only the innermost"** is automatic, because the
  transplant visits every cell and asks each one. This replaces both pieces of
  origin/main machinery at once: the filter scan (origin/main
  `Context.scala:30-36`) and the `NoninheritableFlag` sentinel that existed to
  avoid it (origin/main `Context.scala:45-51`, `:56-57`). The sentinel has no
  counterpart because the walk is not additional traversal: the transplant has
  to visit every cell anyway.
- **P5** holds because the child's cells are fresh nodes over shared immutable
  handlers; nothing writes back.

One divergence to record, inherited from the port and not introduced here.
origin/main drops a noninheritable binding by exact key, and reads are exact
key lookups (P11 with `Context.scala:22-23`). kernel2 resolves reads by
subtype walk. If a program had a cell for `E0` and a cell for
`E1 <: E0` where only `E1` mixes `Noninheritable`, then in kernel2 a read
tagged `E1` would fall through to the `E0` cell in the child rather than to the
effect's default. No shipped effect has that shape: `Local`'s two varieties are
siblings under `ContextEffect[Map[Local[?], AnyRef]]`, not sub and super
(origin/main `Local.scala:117-118`), and `Env`, `Scope` have one tag each. It
is worth one test pinning the sibling case and a sentence in the scaladoc.

### 3.3 Union on nest survives exactly

The child receives copies of **all** `Env` cells in order. A read in the child
resolves to the innermost, whose clause probes outward, finds the next
transplanted cell, and applies `_.union(env)` (origin/main `Env.scala:98`),
exactly as it did in the parent. The result is identical because the same
handler objects run the same clause over the same chain.

A region the child installs itself nests inside the transplant, so its probe
resolves outward into the inherited chain and unions on top. Composition is
therefore preserved in both directions: parent bindings under child bindings,
and child bindings under parent bindings. The same argument holds verbatim for
`Local`'s merge (origin/main `Local.scala:131`, `:134-138`) and for `Scope`'s
replace (origin/main `Scope.scala:132`), since in each case the child runs the
parent's own clause.

### 3.4 What the derive macro emits

Nothing changes. `_ <:< TypeRepr.of[ContextEffect[Any]]` (origin/main
`Isolate.scala:268`) ports verbatim and remains a valid static discriminator:
kernel2's `ContextEffect[+A]` is covariant (worktree `ContextEffect.scala:17`),
so `Env[Config] <:< ContextEffect[Any]` holds, and kyo's `Tag` and the
compiler's `TypeRepr` both handle the variance (kyo-data
`TagTest.scala:108-111` cross-checks covariance with inheritance against izumi
`Tag`). All four worked cases in section 1.7 produce the same results as
today.

The one honest caveat is that the filter's **justification** weakened: in
origin/main a `ContextEffect` was structurally answer-only, in kernel2 it is an
`ArrowEffect` a user could handle with a `done`-capable clause. The
`ContextEffect.Provision` recognizer contains that: a user-installed
`handleLoop` over a context tag is simply not transplanted, so it is not
inherited and its effects stay in the child's row where the witness sees them.

### 3.5 Evidence for a mixed row

There is no `Async.run`; the family that takes a witness is `mask`, `timeout`,
`timeoutWithError`, `race`, `raceFirst`, `gather`, and `foreach`, all with
`using isolate: Isolate[S, Abort[E] & Async, S]` (origin/main
`Async.scala:120`, `:166`, `:182`, `:221`, `:269`, `:380`, `:401`). For
`S = Env[Config] & Var[Int]`:

```scala
Var.isolate.update[Int].use {
    Async.foreach(parallelism)(tasks)   // tasks: A < (Abort[E] & Async & Env[Config] & Var[Int])
}
```

The witness is `Isolate[Var[Int], Any, Var[Int]]` widened to the required
shape; `Env[Config]` contributes nothing to it and is carried by the boundary.
Identical to today. Without the explicit `use`, derivation fires and the
teaching error names `Var[Int]` only, which is the origin/main behavior pinned
at `AsyncTest.scala:30-34`.

### 3.6 Cost model

**Per fork:** one walk of the whole stack; one `instanceof` per cell; one
`Kyo.Handled` allocation per inherited provision cell; one `Node` allocation
per inherited cell when the child is first driven (worktree `Eval.scala:116`).
The `RebuiltNode` identity re-entry (worktree `Eval.scala:111-114`) does not
apply, because `node.prev eq hs` cannot hold in a child whose stack starts at
`Empty`. All of this is on the fork path, which already allocates an `IOTask`
and schedules it.

**Cells on the child's find walk:** the number of provision cells standing at
the fork. The fiber's own layers wrap inside the transplant (section 3.1), so
they are innermost and operations that the fiber itself answers (`Abort`,
`Async.Join`) do not walk past inherited cells. What does walk past them: any
unhandled suspension (the miss path, already cold) and reads of the inherited
context effects themselves, at the same distance they had in the parent.

**Per read in the child:** identical to the parent, including the N probe
suspensions for N nested same-tag bindings (worktree `ContextEffect.scala:74`,
analyzed at `isolate-kernel2-design.md:606-636`). Section 5 removes that
factor.

**Depth across fork generations:** constant, not accumulating. A child forked
from a child transplants the cells it holds, which are copies rather than a
new layer, so a fiber that forks in a loop does not grow its stack.

**One place where kernel2 is structurally more expensive than origin/main:**
N nested same-tag bindings are N cells here and a single map entry there,
because `Context.set` overwrites the key with the merged value (origin/main
`Context.scala:45-52`). That is a property of the ContextEffect port, visible
without any fork; the transplant copies N cells where origin/main copied one
entry. Section 5 addresses it.

---

## 4. Candidate (b): derived per-effect instances over the as-is interface

### 4.1 Mechanism

For `E <: ContextEffect[A]`, an instance in the ruled three-phase shape:

```scala
// the shape, written out against origin/main Isolate.scala:80-125
final class ContextIsolate[A, E <: ContextEffect[A]](tag: Tag[E]) extends Isolate[E, Any, Any]:
    type State        = A
    type Transform[X] = X
    def capture[B, S](f: State => B < S)(using Frame): B < (E & S) =
        ContextEffect.suspendWith(tag)(f)                       // worktree ContextEffect.scala:30-35
    def isolate[B, S](state: State, v: B < (S & E))(using Frame): B < S =
        ContextEffect.handle(tag, state)(v)                     // worktree ContextEffect.scala:57-62
    def restore[B, S](v: B < S)(using Frame): B < S = v
```

This is not an alien shape. `Var.isolate.Base` has exactly it with a different
payload: `capture` is a read, `Var.use(f)` (origin/main `Var.scala:205`), and
`isolate` installs a fresh region seeded from the captured value (origin/main
`Var.scala:207-208`).

The derive macro drops the filter at origin/main `Isolate.scala:268` and
summons an instance per context member like any other member. The consumer
sites do not change at all: `capture`, `isolate`, and `restore` are already
called by `Async.mask` and siblings (origin/main `Async.scala:124-125` and the
list in section 3.5), and by `Fiber.initUnscoped` (origin/main
`Fiber.scala:174-179`).

### 4.2 Noninheritable

Expressed by **not** deriving an instance, or by deriving `Identity`
(origin/main `Isolate.scala:246-252`). The child then has no cell for the tag,
so a read resolves at `Empty`: the `Defaulted` fallback answers for
`Local`-style reads (worktree `Eval.scala:46-47`), and a mandatory read throws
`IllegalStateException` (worktree `Eval.scala:49`), which is the counterpart of
origin/main's `bug(...)` (origin/main `Context.scala:41-42`,
`ContextEffect.scala:64`). Correct in principle. In practice it never fires,
because the only noninheritable effects shipped are `Local` varieties, which
candidate (b) cannot see at all (section 4.5, B1).

### 4.3 Union on nest survives, by a different route

`capture` is a read, so it returns the **fully merged** value: the innermost
`Env` cell's clause probes outward and unions all the way down (section 1.4,
P6). `isolate` then installs a `ContextEffect.handle(tag, value)` const region
in the child, which answers that merged value directly and never probes. A
child-installed `Env.run(c)` probes outward, finds the const cell, and unions
on top. The composed result is the same value, and the chain is flattened to
one cell for the child, which is candidate (b)'s single real advantage.

### 4.4 What the derive macro emits, and the evidence

For `Remove = Env[Config] & Var[Int]`, `Keep = Abort[E] & Async`: both members
survive the `Keep` filter, `Env[Config]` now needs an instance, and the fold
produces `envIsolate.andThen(varIsolate)` (origin/main `Isolate.scala:189-201`),
pairing states as `(TypeMap[Config], Int)` and nesting transforms as
`Id[(Int, A)]`. The user-visible evidence at `Async.foreach` is unchanged in
type but changed in what it demands: if `Env` ships a given, nothing changes at
the call site; if it does not, the teaching error now names `Env[Config]`,
which is a regression against origin/main's deliberate silence about context
effects (P9).

The ordering hazard the doc warns about for `Abort` and `Choice` (origin/main
`Isolate.scala:70-71`) now applies weakly to context members too: the fold
order is the macro's flattening of the type intersection (origin/main
`Isolate.scala:257-273`), which need not match the program's nesting order. For
context effects this is benign, because `capture` for one tag does not disturb
another tag's chain, but it is one more thing that is true by argument instead
of by construction.

### 4.5 Where it breaks

**B1, `Local` is invisible to the row (fatal).** `Local.get: A < Any`
(origin/main `Local.scala:47`, `:125`), so `Local.internal.State` is never a
member of `Remove`, the macro emits nothing, and the child gets no bindings.
Every `Local.let` would stop crossing every fork. Directly contradicts
origin/main `LocalTest.scala:114-129` and `:131-154`, which assert that the
inheritable local's binding is present in the detached context and the
noninheritable one is not. There is no repair inside (b): a mechanism that
reads the row cannot carry an effect the row never names.

**B2, tag conventions are per effect (fatal for a mechanical derivation).**
`Env` installs and reads through `Tag[Env[Any]]` (origin/main `Env.scala:98`,
`:130`, `:152`). A mechanically generated `ContextIsolate[TypeMap[Config], Env[Config]]`
using `Tag[Env[Config]]` would install a cell that reads tagged `Env[Any]`
cannot find, because `find` tests `tag <:< handler.tag` (worktree
`Handlers.scala:47-55`) and `Env[Any] <:< Env[Config]` is false. So the
instance must be hand written per effect, with the effect's own tag. That is a
new obligation on every context effect author, exactly the category of
per-edit proof obligation the module guide argues against (worktree
CONTRIBUTING.md:31-33).

**B3, opaque downstream aliases (fatal without per-alias instances).**
`opaque type Topic <: Env[AeronTransport]` (origin/main `Topic.scala:26`),
whose runtime provision is `Env.run(transport)(v)` (origin/main
`Topic.scala:120`), and `opaque type Arena <: (Env[Arena.State] & Sync)`
(origin/main `Memory.scala:178`). `Isolate` is invariant in `Remove`
(origin/main `Isolate.scala:80`), so an `Env` instance does not satisfy an
`Isolate[Topic, ...]` summon, and a per-alias instance would still have to
know that the runtime tag is `Env[Any]`. Both aliases derive to `Identity`
today (origin/main `Topic.scala:508`, `Memory.scala:196`) and would need
bespoke instances under (b).

**B4, capture forces a read that may be undefined.** `capture` is eager: it
reads at the fork whether or not the child ever reads. A computation whose row
carries `Env[Config]` while no `Env` handler is installed at the fork (a
widened value, a branch that never reads) succeeds on origin/main, where the
map copy is generic and a missing entry only matters at read time, and throws
at the fork under (b). Repairing it requires a definedness-aware capture,
which the current `probe` cannot supply: it is `private inline`, typed
`Any < Any`, and compares a private sentinel by identity (worktree
`ContextEffect.scala:86-93`, `:25`, `:76`). Section 6.4 shows this becomes
clean if the parked `Maybe` redesign lands, which makes (b) partly blocked on a
parked decision.

**B5, it changes the compile-time surface.** P9 says a context effect in the
row costs the user nothing. Under (b) it costs a given per effect and, when one
is missing, a teaching error that origin/main deliberately never emitted for
context effects.

**B6, it does not cover a fork whose row over-approximates.** The row is
static, the stack is dynamic, and worktree `EvalTest.scala:362-365` pins that
they diverge (a boxed computation evaluates under a region other than the one
that lexically enclosed it). A row-driven capture carries what the row names;
the boundary-driven walk carries what is actually installed. For context
effects, "what is actually installed" is the definition of the parity bar.

---

## 5. Candidate (c): (a) plus the two library companions that recover (b)'s advantage

Candidate (b)'s single advantage is real: it hands the child one cell per
effect instead of one cell per binding, and one read instead of a probe chain.
That advantage is available inside candidate (a), and once it is, it applies to
the parent too rather than only to forked children.

**C1, resolve the layered value at region entry.** The layered
`ContextEffect.handle` can probe once, when the region is entered, and store
the merged value, instead of probing per read
(`isolate-kernel2-design.md:638-669`):

```scala
// worktree kyo-kernel2/shared/src/main/scala/kyo/kernel/ContextEffect.scala:66-80, target shape
probe(effectTag).map { outer =>
    val bound = if outer.asInstanceOf[AnyRef] eq undefined then ifUndefined else ifDefined(outer.asInstanceOf[A])
    ArrowEffect.handleLoop(effectTag, bound, v)([X] => (_, s) => Loop.continue(s, s))
}
```

The probe still resolves outward because the region node is not entered when
the `map` runs. `ifDefined` is a pure `A => A` at all four shipped call sites
(origin/main `Local.scala:131`, `:134-138`, `Env.scala:98`, `Scope.scala:132`),
so moving it from per read to per entry is not observable for them. Note the
region becomes a `StateNode` (worktree `ArrowEffect.scala:164-184`), which
changes nothing for the transplant: the child inherits the same resolved value,
`forkState` would be the identity, and still nothing is read out at region
exit, so Q4's answer is unchanged.

**C2, compact by exact tag during the transplant.** Once C1 lands, an outer
cell with the same tag as an inner cell is dead for reads, because the inner
cell answers from its own state without probing. The transplant may then keep
only the innermost cell per exact tag, which reproduces origin/main's
one-entry-per-tag boundary cost precisely. Compaction must be by tag
**equality**, not subtyping: an outer cell for a supertype can still be the
answer for a read of a different subtype.

C2 is sound only with C1. Without C1 the inner clause probes outward at read
time and dropping the outer cell changes the answer.

C1 carries one semantic delta that C2 inherits, and it is a genuine value fork,
not a detail: with lazy probing, a region node re-entered under a **different**
outer stack re-resolves against that stack; with eager resolution it keeps the
value it resolved at its first entry. worktree `EvalTest.scala:362-365` pins
that re-entry under a different stack is expressible. origin/main is on the
lazy side of this: its layered `handle` recomputes `context.set(tag, ifDefined(context.get(tag)))`
per crossing suspension against whatever context is threaded in (origin/main
`ContextEffect.scala:152-167`). Listed as open value-fork V1 in section 10, with
a pin required either way.

---

## 6. The definedness probe

### 6.1 What the probe is today

The layered `handle`'s clause raises `probe(effectTag)` per read: a
`Kyo.Suspend` mixing `Kyo.Defaulted` whose `default` is a private
`undefined = new AnyRef {}` compared by identity before any cast (worktree
`ContextEffect.scala:86-93`, `:25`, `:76`). It resolves against the outer
handlers because a pending clause outcome is evaluated at `node.prev`
(worktree `Eval.scala:74-76`), and it falls back to the sentinel through the
find-miss arm (worktree `Eval.scala:43-47`). The payload is typed `Any` on the
node (worktree `internal/KyoInternal.scala:38-41`), which is what the parked
redesign is about.

### 6.2 Candidate (a) does not need a probe at the boundary

The transplant copies cells, so the child's chain is the parent's chain and
each read's probe walks the same cells to the same answer. Where the chain
bottoms out at the child's `Empty`, the probe answers `undefined` and the
clause applies `ifUndefined`, which is the same answer it would have given in
the parent if the parent's chain also bottomed out. Since the transplant copies
every provision cell, the two chains are identical, so this is an equality, not
an approximation.

The one case where the chains differ is a `Noninheritable` skip, and there the
whole chain for that tag is removed together (the criterion is a property of the
effect type, so all cells for one tag agree), leaving the child with no cell for
that tag. A `Defaulted` read answers its fallback; a mandatory read throws. Both
match origin/main (section 4.2).

**The probe is unchanged and untouched by candidate (a).**

### 6.3 Candidate (b) needs a definedness-aware capture

`capture` must distinguish "no binding at the fork" from "a binding whose value
happens to be the default", because the first must install nothing in the child
and the second must install the value. The mandatory read
(`ContextEffect.suspendWith(tag)`) cannot express the first: it throws. The
defaulted read (`ContextEffect.suspend(tag, default)`) cannot either: it maps
both cases to the same answer, so `isolate` would install a cell where the
parent had none, which changes what a child-installed layered region merges
with (P6). The only tool that expresses it is `probe`, which is private and
typed `Any < Any`. So (b) either exposes a sentinel-shaped definedness API on
`ContextEffect` or waits for the redesign.

### 6.4 If the parked `Maybe` redesign lands

Under `kernel2-todos-design.md` section 3, `ContextEffect[+A]` becomes
`ArrowEffect[Const[Unit], Const[Maybe[A]]]`, `Kyo.Defaulted` is replaced by a
parameterized `Kyo.Unhandled` whose payload is typed at `O[X]`, and `probe`
returns `Maybe[A] < Any` (`kernel2-todos-design.md:686-764`).

- **Candidate (a) is unaffected in substance.** The transplant copies cells and
  does not read values, so a change in what a cell answers is invisible to it.
  Two mechanical consequences: the `ContextEffect.Provision` mixin sits on
  whatever handler the rewritten `ContextEffect.handle` builds, and the miss
  arm gains a second marker beside `Unhandled`, which
  `kernel2-todos-design.md:812-827` already anticipates and recommends ruling
  jointly (the `Detached` marker computes its answer from `hs` and so genuinely
  cannot reuse `Unhandled`).
- **Candidate (b) gets its missing piece.** `State = Maybe[A]`,
  `capture = probe(tag)`, and `isolate` becomes
  `state.fold(v)(a => ContextEffect.handle(tag, a)(v))`, which is clean and
  removes B4 entirely. B1, B2, B3, B5, and B6 are untouched, so the
  recommendation does not change.
- **C1 becomes simpler**: the sentinel comparison and the two casts around it
  disappear from the entry resolution (`kernel2-todos-design.md:766-769`).

---

## 7. The state-aware region exit

The missing kernel piece is `done: (State, A) => B` on the stateful handling
form: origin/main has it (`ArrowEffect.scala:530-541`, consumed by
`Var.runTuple` through `runWith`, origin/main `Var.scala:194-195`), kernel2
does not: its stateful `handleLoop` exits with `Arrow[A]` (worktree
`ArrowEffect.scala:176`) and `handleLoopWith`'s continuation receives only `A`
(worktree `ArrowEffect.scala:209-216`).

**Neither candidate needs it for context members.** The reason is structural:
a provision region carries its value in the handler closure, not in cell state
(section 2.1), and no phase of either encoding reads a value out at region
exit. Under (a), `restore` for a context member does not exist at all
(no instance). Under (b), `Transform[X] = X` and `restore` is the identity, so
the region's exit has nothing to carry.

It stays a prerequisite for the **stateful** members of the same rows
(`Var.runTuple`, `Emit.run`, `Check.runChunk`, so `Var.isolate.*`,
`Emit.isolate.*`, `Memo`, `Check`: origin/main `Var.scala:220-257`,
`Emit.scala:225-272`, `Memo.scala:66`, `Check.scala:116-132`), independently of
anything decided here. The practical consequence is scheduling: the
ContextEffect encoding can land before the state-aware exit, and is not
blocked by it.

If C1 lands and provision regions become `StateNode`s, this answer does not
change: C1 stores the resolved value so reads can find it, and still nothing
reads it out at exit.

---

## 8. Multi-shot and park or resume

### 8.1 Candidate (a)

**Park.** If the fork suspension itself lands in an `Eval.partial` residual,
the transplant has not run: `rebuild(hs, Empty, v)` reifies the standing cells
around the unanswered suspension (worktree `Eval.scala:50`), and when the
residual is driven again the cells re-enter, by identity when the position
matches (`RebuiltNode`, worktree `Eval.scala:111-114`, `:245-268`). The
transplant then sees the same cells and produces the same child. Pinned by
worktree `EvalTest.scala:262-269` (a residual is resumable) and `:275-284`,
`:315-326` (a residual carries the advanced state of a region it crossed
rather than resetting).

If the transplant has already run, the child is a value that carries its own
regions and is not part of the parent's residual at all, so a park of the
parent cannot disturb it.

**Multi-shot.** A `Cont` clause receives a continuation that rebuilds the
crossed cells per call (worktree `Eval.scala:88-93`, CONTRIBUTING.md:110),
pinned by worktree `EvalTest.scala:286-296`: each shot resumes from the
capture-time state. A fork inside such a continuation runs the transplant once
per shot over the capture-time cells, so each child gets the capture-time
context and no shot leaks into another. Because the cells are immutable
(`val` fields, successors allocated on update: worktree `Handlers.scala:15-35`,
`Eval.scala:57-61`), the shots share cell objects with no interference.

**The crossing.** The child must leave the parent's drive as data, not as
something the parent continues into. That is `Nested.lift` at the fork answer
(worktree `internal/KyoInternal.scala:15-18`, `:26`), and the semantics it
relies on are pinned by worktree `EvalTest.scala:362-365`: a boxed computation
passes out through its region and evaluates under a later one, carrying the
regions it was built with. That pin is exactly the property "the child carries
the transplanted onion and does not acquire the joiner's stack".

**Concurrency.** The child's cells hold the parent's handler objects by
reference. For a provision handler that is the provided value (const form) or
the `ifUndefined` and `ifDefined` closures (layered form). All four shipped call
sites are pure functions over immutable values (origin/main `Local.scala:131`,
`:134-138`, `Env.scala:98`, `Scope.scala:132`), and the `Scope` payload is a
concurrent handle shared on purpose (section 1.6, P8). The obligation to state
in scaladoc: a provided value must be safe to publish and read concurrently.
origin/main carried the same obligation implicitly, since `Var.isolate.capture`
hands the raw value across (origin/main `Var.scala:205`).

### 8.2 Candidate (b)

Both phases are ordinary values: `capture` is a suspension, `isolate` is a
region node. A park across `capture` reifies it like any other suspension
(worktree `Eval.scala:50`); a multi-shot re-entry re-reads at that shot's
evaluation point, which worktree `EvalTest.scala:352-360` pins as the correct
reading point for a boxed computation. No new carrier, nothing unsafe.

The one behavioral difference is a consequence of eagerness rather than of the
carrier: under (a) the child sees the bindings that stood at the fork's
evaluation point because the walk happens there; under (b) it sees the values
`capture` read at the same point. Same point, same values. The candidates
diverge on **which** bindings are captured (section 4.5), not on when.

---

## 9. Recommendation, with the parity bar checked

**Adopt candidate (a): boundary inheritance narrowed to provision cells, with
`ContextEffect.Provision` as the structural recognizer and the
`Noninheritable` bit decided at region construction.** Keep the `derive`
macro's `ContextEffect` filter verbatim (origin/main `Isolate.scala:268`), so
context members require no instance and cost the user nothing, which is the
old kernel's contract. Adopt C1 and C2 (section 5) as the cost answer, subject
to value-fork V1.

Parity bar, item by item:

| item | statement | candidate (a) verdict |
|---|---|---|
| **P1** | fork copies standing context generically, no per-effect participation | **met.** The transplant walk copies whatever provision cells stand, with no per-effect code and no static knowledge. |
| **P2** | copy happens at the fork's evaluation point | **met.** The `Detached` marker is answered by the evaluator with the live `hs` (worktree `Eval.scala:43-50`), the same evaluation-time property origin/main's `KyoDefer` had (origin/main `Isolate.scala:228-232`). |
| **P3** | child runs under the copied context for its whole lifetime, across parks | **met, and by a better carrier.** The context is region nodes inside `curr`, so a park reifies it with the rest of the computation (worktree `Eval.scala:50`, `:111-114`); origin/main needed an `IOTask` field plus a conditional subclass (origin/main `IOTask.scala:19`, `:208-214`), both of which disappear. |
| **P4** | noninheritable bindings dropped, all of them, not only the innermost | **met.** The walk asks every cell. Replaces both the filter scan and the `NoninheritableFlag` sentinel (origin/main `Context.scala:30-36`, `:45-51`). One divergence recorded in section 3.2 for a sibling subtype shape no shipped effect has. |
| **P5** | nothing travels back | **met.** Fresh cells over shared immutable handlers; no write-back path exists. |
| **P6** | `Env`'s union on nest composes, in both directions | **met exactly.** The child runs the parent's own clause over the transplanted chain (section 3.3), and a child-installed region probes outward into it. |
| **P7** | `Local` bindings cross, for computations whose row never names `Local` | **met.** The walk is row-independent. This is the item candidate (b) cannot meet. |
| **P8** | inheritance is by reference | **met.** The cell holds the same handler object, which holds the same payload; `Scope`'s shared finalizer keeps working, which `Fiber.init` depends on (origin/main `Fiber.scala:138`). |
| **P9** | a context effect in the row costs the user nothing | **met.** The filter is unchanged, so no given, no error, no witness content. |
| **P10** | a stateful effect without an instance is a compile error with the teaching message | **met.** `deriveImpl` is unchanged (origin/main `Isolate.scala:275-318`), pinned by origin/main `AsyncTest.scala:30-34`. |
| **P11** | the child's binding set and resolution order equal the parent's, minus noninheritable | **met.** The walk preserves order (section 3.1); the exact-key versus subtype-walk divergence is pre-existing and noted. |

Candidate (b) fails P7 outright, fails P9, and needs per-effect authorship to
meet P6 and P11 (B2, B3). Candidate (c) is candidate (a) plus two library
changes and meets the same bar, with the read cost brought to origin/main's
profile and one semantic question to rule.

What must be built, in dependency order:

1. `ContextEffect.Provision` with `inheritable`, and `ContextEffect.handle`
   constructing its own region object instead of routing through
   `ArrowEffect.handleLoop` (worktree `ContextEffect.scala:57-80`, mirroring
   `ArrowEffect.scala:117-123`).
2. `Eval.transplant` and the `Detached` marker (the mechanism from
   `isolate-kernel2-design.md:266-332`), with the `Nested.lift` crossing.
3. `Fiber.initUnscoped` and the three `Fiber.internal` fork sites moved off
   `Isolate.internal.runDetached` (origin/main `Fiber.scala:174`, `:750`,
   `:793`, `:905`); `IOTask`'s context field and conditional subclass deleted
   (origin/main `IOTask.scala:19`, `:208-214`).
4. C1 and C2 (section 5), gated on V1 and on a JMH row for context reads,
   which does not exist today (worktree CONTRIBUTING.md:163-169 puts the burden
   of proof on the change).

Tests the change owes, all statable through the public surface as the module
requires (worktree CONTRIBUTING.md:160): an inheritable binding crosses a fork;
a noninheritable one does not and the child sees the default; an outer
noninheritable binding does not leak when an inner inheritable one crosses (the
"every cell, not only the innermost" property, mirroring origin/main
`LocalTest.scala:131-154`); an inner `Env.run` in the child unions over the
inherited environment; a child registering into an inherited `Scope` reaches
the parent's finalizer; a fork inside a multi-shot continuation gives each shot
the capture-time bindings; a fork whose parent parks before the fork resolves
produces the same child on resume.

---

## 10. Open value-forks for the maintainer

Only genuine forks; everything else above is either cited or is work.

**V1. Is the layered context value resolved at region entry or at each read?**
(section 5, C1.) Entry resolution deletes the N-probes-per-read factor and
enables C2's compaction, bringing the boundary cost to origin/main's
one-entry-per-tag. It differs observably from today in one case: a region node
re-entered under a different outer stack keeps its entry-time resolution
instead of re-resolving. origin/main is on the lazy side (origin/main
`ContextEffect.scala:152-167` recomputes per crossing). Either answer needs a
pin; the fork is which behavior is the contract.

**V2. Does the recognizer become a named provision handler, or a tag test?**
(section 3.1.) The named handler is cheaper on the walk and makes
transplantability structural, at the cost of `ContextEffect.handle` building
its own region object instead of reusing `ArrowEffect.handleLoop`. The tag test
(`handler.tag <:< Tag[ContextEffect[Any]]`) is smaller today and admits a
user-installed `done`-capable handler over a context tag, which kernel2 made
expressible and origin/main did not.

**V3. Where is the `Noninheritable` bit decided?** (section 3.2.) At region
construction, costing one tag test per `Local.let` and storing a field, or at
fork time, costing one tag test per provision cell per fork and keeping region
construction untouched. A measurement question with no correctness content.

**V4. Should `transplant` copy anything other than provision cells?**
This document narrows it to provision cells, which keeps the old kernel's split
intact (simple state copying at the boundary, complex state management in the
witness) and makes the `done`-soundness question moot, since provision clauses
provably only continue. `isolate-kernel2-design.md:390-448` proposes the wider
reading, where every non-skipped cell is inherited and `Var.isolate.discard`
becomes free. The wider reading needs the answering handler kind of that
document's section 4.1 to be sound, and it changes behavior for effects
origin/main deliberately did not inherit. It is a separate ruling and does not
block this one.

**V5. Joint ruling with the parked `Defaulted` redesign on the miss arm.**
(section 6.4, `kernel2-todos-design.md:812-827`.) The arm ends with two
markers: one whose node carries its own answer, one whose answer the evaluator
computes from `hs`. Both designs recommend two markers rather than one trait
with an ignored parameter. The ruling belongs to whichever track lands second.
