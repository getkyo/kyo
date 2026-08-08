# kernel2: context as a threaded parameter (design track B)

Scope: replace kernel2's chain-walking context machinery (`Kyo.ContextRead`,
`Kyo.ContextSnapshot`, `resolveContext`, `snapshotContext`) with a design where the
context is a value handed to a continuation as a parameter, as the current kernel does.
Covers the node model, `ContextEffect.handle`, the fork boundary API (item #13),
`hasHandler`, the preemption interaction, benchmarks, and the file-by-file change list.

Verdict up front. The context cannot be a register that the drive carries and updates as
it goes: kernel2's fused chains cross binding delimiters without the drive ever seeing
them, and user code installs new binding regions in the middle of a drive. Both facts
are demonstrated with counterexamples in section 2. What works, and what this design
proposes, is that **the visible context becomes a summary field on `Arrow`, maintained
structurally at arrow-composition time, so the drive reads it in one field load and
hands it to the continuation as a parameter**. Reads stop walking chains,
`ContextSnapshot` disappears, `ContextRead` disappears, the two of them collapse into a
single `Defer` node whose resumption takes the context (the literal shape of the current
kernel's `KyoSuspend.apply(v, context)`), and the eager path pays nothing because no
`Arrow.Offset` or `Arrow.AndThen` is constructed on it.

---

## 1. What exists today, and what it costs

Three pieces implement context effects in kernel2:

1. `ContextEffect.handle` installs `Handler.Context(effectTag, transform, frame)` as an
   ordinary chain transform (`ContextEffect.scala:86`). On the value path the delimiter
   is a pass-through; its only job is to exist in the chain.
2. `Kyo.ContextRead` is a `Suspension`. The drive resolves it at boundary drives by
   `resolveContext` (`Pending.scala:748`), which walks the whole continuation chain with
   an explicit pending stack, collects every matching delimiter's `transform` into a
   `List`, then folds outermost to innermost.
3. `Kyo.ContextSnapshot` is a second `Suspension`, resolved by `snapshotContext`
   (`Pending.scala:616`), which performs the same walk, collects every binding of every
   tag, and folds per tag into a `Context` for `Isolate.internal.runDetached`.

Cost model, per operation:

| operation | today | note |
|---|---|---|
| one context read | O(chain length) walk, plus one `List` cell per matching delimiter and one `Maybe` per fold step | `resolveContext` |
| one fork snapshot | O(chain length) walk, plus a `List` of delimiters, plus one `Map` write per distinct tag | `snapshotContext` |
| one value step crossing a binding | one virtual call, no context work | `Handler.Context.run` |
| composing a chain | one `hasHandler` boolean per node | `Offset`, `AndThen` |

The asymptotic problem is real. The chain at a read is the whole remaining
continuation, so a read placed early in a long statically composed chain walks that
entire chain. The current kernel answers the same question with one `Map` lookup, at the
cost of one wrapper allocation and one `Context.set` per enclosing context handler per
suspension step. kernel2 today is better on the value path (zero context work) and worse
on reads (unbounded).

There is also an existing inconsistency worth naming: `resolveContext` matches
delimiters with `h.effectTag <:< readTag` (subtype tolerant), while a read performed
after a fork consults `Context`, a `Map` keyed by erased tags (exact match). The same
program therefore resolves differently before and after a fork boundary. Section 3.2
resolves this by picking one rule.

---

## 2. The invariant that constrains every design

**The set of bindings visible at a point is a function of the continuation chain at that
point.** A chain is executed inner to outer: `Offset(head, next)` runs `head` first, and
`ContextEffect.handle(tag, v)(computation)` appends its delimiter to the end of the
chain (`h(v)` is `v.map(h)`), so the delimiter marks where the region *ends*. Every
binding still ahead in the chain is therefore in scope, and a suspension's chain is
exactly its own remaining continuation, which is why per read resolution is correct
today.

Two threading designs suggest themselves. Both are unsound, and it is worth recording
why, because they are the designs the brief lists as candidates.

### 2.1 Rejected: seed a register once per drive entry

Proposal: the drive walks the chain once at entry, computes the visible context, keeps
it in a local, and answers every read from it.

Counterexample, region exit inside a drive:

```scala
ContextEffect.handle(Tag[Env], 7)(inner).map(_ => ContextEffect.suspend(Tag[Env], 0))
```

The chain is `[inner..., B(Env=7), readStep]`. The seed computed at drive entry contains
`Env = 7`. Execution then crosses `B` inside a fused `Offset.run` loop, which the drive
does not observe, and reaches a read that is outside the region. The seed still says 7;
the correct answer is the default. The register is stale in the direction of holding
bindings whose regions have ended.

### 2.2 Rejected: seed once, then pop when a delimiter is crossed

Proposal: fix the above by having the binding delimiter update the register in its own
`run`, so crossings are free on every other node.

Counterexample, region entry inside a drive:

```scala
op.map { _ =>
    ContextEffect.handle(Tag[Env], 7)(ContextEffect.suspend(Tag[Env]).map(_ + 1))
}
```

The region is constructed by user code in the middle of the drive, after the seed was
taken. The delimiter is at the end of the newly built chain, so the pop hook fires when
the region *ends*, and nothing fires when it begins. The register never learns about the
binding, and the inner read resolves against the stale seed.

The natural repair, pushing the binding onto the register inside `ContextEffect.handle`,
is unsound for a more fundamental reason: construction order is not execution order.

```scala
val a = ContextEffect.handle(Tag[Env], 1)(prog1)
val b = ContextEffect.handle(Tag[Env], 2)(prog2)
a.map(_ => b)
```

Both handles run at construction, so both pushes happen before either region is
entered, and the pops then fire in execution order against a register that was never in
a matching state. Building a computation must not touch runtime state; that is what
makes `handle` referentially transparent, and the test `"handling installs without
executing"` pins the property.

### 2.3 What follows

Any O(1) read must obtain the visible bindings from something that travels with the
chain and is maintained where chains are built, not where they are executed. That is a
summary field on `Arrow`, computed in the constructors of the two composite arrow nodes.
This also disposes of the "walk once per slice" variant in the brief: it is not merely a
weaker optimization, it is incorrect, because the chain a drive holds changes shape
during the drive.

---

## 3. The design

### 3.1 Context as an arrow-resident scope stack

Add one member to `Arrow`:

```scala
sealed abstract class Arrow[-A, +B, -S]:
    private[kyo] def hasHandler: Boolean
    /** The bindings in scope at the start of this arrow, innermost first. */
    private[kyo] def context: Context
```

with three definitions and no type tests anywhere:

```scala
abstract class Transform[-A, +B, -S] extends Arrow[A, B, S]:
    private[kyo] def hasHandler: Boolean = false
    private[kyo] def context: Context    = Context.empty      // a def: no field, no per-instance cost

final private[kyo] class ContextBinding(bindings: Context, val frame: Frame) extends Arrow.Transform[Any, Any, Any]:
    override private[kyo] def context: Context = bindings
    def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) = cont(Kyo.lift(v))

final private[kyo] class AndThen[-A, B, +C, -S](val a: Arrow[A, B, S], val b: Arrow[B, C, S]) extends Arrow[A, C, S]:
    override private[kyo] val hasHandler = a.hasHandler || b.hasHandler
    override private[kyo] val context    = Context.concat(a.context, b.context)   // a is inner, a wins

final class Offset[-A, X0, +B, -S] private[kyo] (val head: Transform[A, X0, S], val next: Arrow[X0, B, S])
    extends Transform[A, B, S], Step[A, B, S]:
    override private[kyo] val hasHandler = head.hasHandler || next.hasHandler
    override private[kyo] val context    = Context.concat(head.context, next.context)
```

`Context.concat(inner, outer)` returns `outer` when `inner` is empty and `inner` when
`outer` is empty, which is the case for every node whose head is a plain user transform.
The invariant is:

> For any arrow `f`, `f.context` is the set of bindings a computation observes at the
> moment `f` starts running.

It holds by construction for the four cases (identity, a plain transform, a binding, a
composition) and is preserved by every site that builds arrows: `Arrow.map`,
`optimize`'s `respine` and `unfold` (both build right to left, so `next.context` is
always available), the `segmentBoundary` splice (a plain transform), `dispatchControl`'s
`prefixArrow` and `compose`, and the `new Offset(h, fullRest)` re-installation of an
operation delimiter. None of these needs a change beyond inheriting the new field.

Two consequences worth stating plainly. First, the fused runner (`Offset.run`,
`Arrow.apply`, `guardedRun`) is untouched: no context parameter, no extra check, no new
branch. Second, the values are immutable and published through final fields, so a
continuation captured on one thread and resumed on another sees the same context, with
no synchronization.

### 3.2 Context representation

`Context` keeps its name and its role and changes representation, from
`Map[Tag[Any], AnyRef]` to an immutable frame list, innermost first:

```scala
private[kyo] opaque type Context = Context.Frame | Null            // Null is the empty context

private[kyo] object Context:
    sealed abstract class Frame:
        def tag: Tag[Any]
        def next: Context
    final class Const(val tag: Tag[Any], val value: Any, val next: Context)          extends Frame
    final class Derived(val tag: Tag[Any], val transform: Maybe[Any] => Any, val next: Context) extends Frame
```

Operations, all O(number of frames in scope):

- `get(tag)` / `getOrElse(tag, default)`: walk to the first frame whose tag matches. A
  `Const` answers directly. A `Derived` answers `transform(getMaybe(tag, frame.next))`,
  which is the same fold `resolveContext` performs today, over the bindings rather than
  over the chain.
- `set(tag, value)`: cons a `Const`. `bind(tag, transform)`: cons a `Derived`.
- `concat(inner, outer)`: copy `inner`'s frames onto `outer`, with the two empty fast
  paths. Typical cost is zero or one cons.
- `resolve`: fold to a `Const`-only context with one frame per distinct tag. Used only
  where a context leaves the computation (the fork boundary), so a context that crosses
  a boundary never carries an unresolved `Derived` frame.
- `inherit`: drop noninheritable tags. With a list this is a single pass, so the
  `NoninheritableFlag` sentinel entry that exists today purely to make the check O(1)
  goes away, along with the special comment in `snapshotContext` about maintaining it.

Why a list and not the current `Map`. The composition path needs cheap `concat` and
cheap "no bindings" (a reference copy); the read path needs to support `Derived` frames
whose value depends on the frames outside them, which no materialized map can hold
(section 7.2 shows why materializing at construction time is wrong). The frame count in
practice is the number of nested context regions, which downstream is small: kyo-prelude
uses one context effect for `Env`, one for `Local`, and kyo-core one for `Scope`.

This is not O(1) per read, it is O(bindings in scope). That is an honest downgrade from
the "one map lookup" the current kernel gets and a large upgrade from "walk the whole
chain". If a workload ever shows a deep binding stack, the follow-up is a resolved-map
cache keyed by arrow identity (sound, because an arrow's frame list is immutable), not a
change to this structure. The decision is recorded as a benchmark row in section 6.

**Tag matching.** Frames are matched by exact tag equality, as `Context` does today and
as the current kernel does. This drops `resolveContext`'s subtype-tolerant `<:<` match
and, with it, the pre-fork versus post-fork inconsistency described in section 1. No
kernel2 test binds one effect and reads a supertype of it. Recorded as open question
O-3 in case subtype tolerance was intended.

**Interaction with item #19 (`Context` to `TypeMap`).** That item assumes `Context`
stays a materialized keyed map. This design changes what `Context` is, so #19 should be
re-scoped after this lands: the candidate for a `TypeMap` is then the resolved snapshot
that crosses fork boundaries, not the in-flight scope stack. If the user prefers to
protect #19 as written, the fallback is two types (an in-flight `Bindings` list plus the
existing `Context` for boundaries) at the cost of one extra concept and one conversion
at every fork; the rest of this design is unchanged. Recorded as open question O-2.

### 3.3 The node model: one Defer, resumed with the context

`Kyo.ContextRead` and `Kyo.ContextSnapshot` are deleted. `Kyo.Suspension` loses its
second subclass and collapses into `Kyo.Suspend` (the arrow operation), so
`Kyo.Continue` carries a `Suspend` directly and the drive's suspension dispatch has one
case instead of three.

`Kyo.Defer` becomes the node the drive answers, in two forms:

```scala
sealed abstract class Defer[+A, -S] extends Kyo[A, S]:
    private[kyo] def cont: Arrow[?, A, S]

object Defer:
    /** A trampoline bounce: the value is already known. */
    final class Step[A, +B, -S](val value: A, val cont: Arrow[A, B, S]) extends Defer[B, S]

    /** A step resumed with the ambient context, the current kernel's `apply(v, context)` shape. */
    final class Read[+B, -S](val cont: Arrow[Context, B, S]) extends Defer[B, S]
```

`Step` is today's `Defer` under a name that says what it is; it carries the rescue path,
the segment boundary, and `Effect.defer`. `Read` is the only context-aware node in the
kernel, and both context operations are built from it:

```scala
// Kyo
private[kyo] inline def readContext[A, S](inline f: Context => A < S)(using inline _frame: Frame): A < S =
    Defer.Read(Arrow.of(new Arrow.Transform[Context, A, S]:
        def frame = _frame
        def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) = cont(f(v.asInstanceOf[Context]))
    ))

// ContextEffect
inline def suspend[V, E <: ContextEffect[V]](inline effectTag: Tag[E])(using inline _frame: Frame): V < E =
    Kyo.readContext(ctx => ctx.get(effectTag))

inline def suspend[V, E <: ContextEffect[V]](inline effectTag: Tag[E], inline default: => V)(using inline _frame: Frame): V < Any =
    Kyo.readContext(ctx => ctx.getOrElse(effectTag, default))

inline def suspendWith[V, E <: ContextEffect[V], B, S](inline effectTag: Tag[E])(inline f: V => B < S)(using inline _frame: Frame): B < (E & S) =
    Kyo.readContext(ctx => f(ctx.get(effectTag)))          // one node, not read-then-map

// Isolate.internal
private[kyo] def detach(using Frame): Detached < Any =
    Kyo.readContext(ctx => new Detached(Trace.empty, ctx.resolve.inherit))
```

This is the current kernel's shape, one level down: there, every continuation frame is
`apply(v, context)` and a context read is a `KyoDefer` that consults the parameter
(`ContextEffect.scala:103-109`); here, only the node that needs the context takes it,
and the drive supplies it from the arrow summary. The user's directive ("handled with
the context param threaded + Defer, not specific suspensions") is met literally at the
node boundary, and the eager path keeps its property of having no context parameter at
all.

If item #16 (single-allocation defer) lands, both forms become one anonymous class mint
with an abstract `resume` rather than a node plus a transform, and `Read`'s signature
`resume(context: Context): A < S` is then character for character the current kernel's
`KyoDefer.apply(v, context)`. The two items want the same node change, so #16 should be
sequenced with this one.

### 3.4 Where the drive answers, and the boundary rule

The drive gains one arm and loses two:

```scala
case defer: Kyo.Defer.Step[Any, Any, Any] @unchecked =>
    // unchanged: resume with the known value, on the existing preemption cadence
    loop(defer.cont(defer.value), ...)

case read: Kyo.Defer.Read[Any, Any] @unchecked if depth == 0 =>
    if boundary then loop(read.cont(read.cont.context), ...)
    else curr           // park: bindings installed outside this region are not in the chain yet
```

Three properties of this arm:

- **The answer is one field load.** `read.cont` is the remaining continuation, and
  `read.cont.context` is the summary maintained in section 3.1. No walk, no allocation.
- **The boundary rule is preserved exactly.** A non-boundary drive (`install`, that is
  `ArrowEffect.handle` evaluating a freshly installed region) parks the read, because
  the chain it holds is the region's chain and an enclosing `ContextEffect.handle` may
  still be applied to the whole thing afterwards. This is today's `if boundary` guard,
  unchanged in meaning, and the tests that depend on late installation
  (`"a binding installed after composition completes the read"`,
  `"bindings travel with parked computations"`,
  `"context reads inside arrow handler clauses resolve against the clause scope"`) keep
  passing for the same reason they pass today.
- **The `depth == 0` guard is preserved.** A read reached inside a nested trampoline (a
  bracket acquire or release) parks out to the drive's top level first, where the chain
  is complete. Section 3.7 makes that chain carry the right frames.

**One behavior does change.** Today, a read with no binding and no default returns
`Maybe.Absent` from `dispatch` even at a boundary drive, so the drive parks; `eval` then
reports `bug.failTag("Unexpected pending effect ...")`, and `handlePartial` returns a
computation that can never progress, which for a fiber is a silent hang. Under this
design the same situation calls `Context.get`, which reports
`bug("Missing value for context effect ...")` at the point of the read. This matches the
current kernel (`ContextEffect.suspendWith` uses `bug("Unexpected pending context
effect: " + tag.show)` as its default) and it is the correct call: a boundary drive is by
contract the outermost driver, so a read that cannot be answered there is a defect, and
a defect should be reported rather than parked into a hang. No existing test asserts the
old shape; a new test pins the new one (section 9).

### 3.5 `ContextEffect.handle`

Both overloads stay pure installation, with no drive and no evaluation:

```scala
def handle[V, E <: ContextEffect[V], A, S](effectTag: Tag[E], value: V)(v: A < (E & S))(using frame: Frame): A < S =
    install(Context.empty.set(effectTag.erased, value), frame, v)

def handle[V, E <: ContextEffect[V], A, S](effectTag: Tag[E], ifUndefined: => V, ifDefined: V => V)(v: A < (E & S))(using frame: Frame): A < S =
    val transform: Maybe[Any] => Any =
        case Maybe.Present(outer) => ifDefined(outer.asInstanceOf[V])
        case Maybe.Absent         => ifUndefined
    install(Context.empty.bind(effectTag.erased, transform), frame, v)

private def install[A, S](bindings: Context, frame: Frame, v: A < ?): A < S =
    new ContextBinding(bindings, frame).asInstanceOf[Arrow[Any, Any, Any]](v.asInstanceOf[Any < Any]).asInstanceOf[A < S]
```

What this preserves:

- **Installation is structural and lazy.** `handle` appends one node; nothing runs. The
  transform variant keeps its laziness too, because a `Derived` frame stores the
  function and applies it at read time against the frames outside it. This is why the
  design keeps `Derived` frames instead of resolving the transform when the region is
  entered: `Env.run` (`_.union(env)`) and `Local.let` (`_.updated(this, value)`) are
  transform-form handles on hot paths, and turning them into a deferred read plus a
  constant install would add a node and a drive bounce to each. Section 7.3 records that
  rejected variant.
- **Deep handling across parks needs no re-installation.** The binding node travels in
  the chain, so a parked computation resumed on another thread or in another slice
  carries its bindings with it, and the summary is recomputed by the arrow constructors
  that rebuild the chain. This is strictly less work than the current kernel, which
  re-wraps every suspension of the region on every step (`handleLoop` in
  `kyo-kernel/.../ContextEffect.scala:153-167`) and pays one `Context.set` per enclosing
  handler per step.
- **Shadowing and composition are positional.** An inner region's delimiter sits earlier
  in the chain than an outer one, `concat` gives the inner side priority, and a
  `Derived` frame resolves against exactly the frames after it. The oracle tests
  (`"ifDefined behavior"` expecting 200, `"effect order preservation"`,
  `"with transformation"`, `"nested effects"`) are folds of the same shape as today and
  produce the same values.

`ContextBinding` stops being a `Handler`. Nothing dispatches to it any more (the only
searches left look for operation delimiters), so it moves out of the `Handler` hierarchy
into its own internal class and reports `hasHandler = false`. That is C4's rename
(`Handler.Context` to `ContextBinding`) plus the structural fact that motivates it, and
it leaves `Handler` containing operation handlers only, which simplifies C3 and C4.

### 3.6 The fork boundary (item #13) and the IOTask handshake

The current callback shape:

```scala
private[kyo] def runDetached[A, S](f: (Trace, Context) => A < S)(using Frame): A < S
```

exists because the snapshot was a suspension that had to be resolved before the fork
site could see it. With `readContext` there is no reason for a callback: the boundary
state is an ordinary value.

```scala
/** The ambient state a forked computation inherits: the creator's trace and its resolved context. */
final private[kyo] class Detached private[kernel2] (val trace: Trace, private[kyo] val context: Context):

    /** Installs the inherited bindings on a detached computation. Outermost, so the child's own
      * handlers shadow them. */
    private[kyo] def attach[A, S](v: A < S): A < S =
        if context.isEmpty then v
        else new ContextBinding(context, Frame.internal).asInstanceOf[Arrow[Any, Any, Any]](v.asInstanceOf[Any < Any]).asInstanceOf[A < S]

// Isolate.internal
private[kyo] def detach(using Frame): Detached < Any =
    Kyo.readContext(ctx => new Detached(Trace.empty, ctx.resolve.inherit))
```

The fork site maps over one value, and every child gets the bindings by construction:

```scala
// kyo-core, Fiber.initUnscoped
Isolate.internal.detach.map { detached =>
    isolate.capture { state =>
        val io = isolate.isolate(state, v).map(r => isolate.restore(r))
        IOTask(detached.attach(io), detached.trace)
    }
}

// kyo-core, Fiber.internal.foreachIndexed and Race: many children, one Detached
val fiber = IOTask(detached.attach(workerLoop()), safepoint.copyTrace(detached.trace), parent)
```

What this buys on the consumer side, concretely:

- `IOTask.context` (`IOTask.scala:19`), the `override def context = ctx` subclass minted
  in `IOTask.apply` when the context is non-empty (`IOTask.scala:193-199`), and the
  `context` argument threaded into `ArrowEffect.handlePartial` (`IOTask.scala:70`) all
  disappear. The inherited context travels inside `curr`, which is the field the task
  already owns, saves, and restores across slices.
- The drive needs no context parameter of its own. A slice that resumes a parked
  computation gets the inherited frames from the same place it gets the local ones, the
  chain, so there is nothing to re-establish per slice and nothing to lose when a task
  migrates between workers.
- The pair stays a pair. Trace and context are both "ambient state captured at a fork",
  and future ambient state (the real `Trace` from the trace round, a fiber-local
  diagnostic) joins `Detached` without touching any signature.

`attach` is the only producer of an inherited frame and `detach` the only producer of a
resolved context, which is where the "a context that crosses a boundary carries no
`Derived` frame" invariant is enforced.

An alternative that keeps the current kernel's literal shape (the drive takes a
`context` parameter, `handlePartial(tag1, tag2, v, context)`, and `IOTask` keeps its
field) is fully workable and is recorded in section 7.4. It is rejected because it puts
the inherited context in a second place, which then has to be merged with the chain
summary at every read and re-supplied at every drive entry.

### 3.7 Brackets and discard: a defect the threading fixes

`Kyo.Bracket.map` appends the composed arrow to `cont` only, leaving `acquire` and
`release` untouched (`Kyo.scala:102-110`). So for

```scala
ContextEffect.handle(Tag[Env], 5)(Effect.bracket(ContextEffect.suspend(Tag[Env]))(release)(use))
```

the binding lands in `bracket.cont`, and the read inside `acquire` never sees it: today
it walks a chain that does not contain the delimiter, resolves to `Absent`, parks, and
`eval` reports an unhandled effect. The same holds for the direct
`recur(bracket.release(resource), depth + 1)` path, and for `discardArrow`, which
evaluates a finalizer with `.eval`, that is, with no ambient context at all
(`Pending.scala:409`).

This is a live kernel2 defect, independent of this redesign, and the threading makes the
fix a two-line change because the bracket node's own scope is now readable from its
chain:

```scala
val local = bracket.cont.context                       // the bindings the bracket sits inside
recur(Kyo.withContext(local)(bracket.acquire), depth + 1)
...
recur(Kyo.withContext(local)(bracket.release(resource)), depth + 1)

// discardArrow, at each Finalize, with `rest` the remaining chain
Kyo.withContext(rest.context)(finalize.bracket.release(finalize.value)).eval
```

where `Kyo.withContext(ctx)(v)` is `attach`'s body: identity when `ctx` is empty,
otherwise one `ContextBinding` node. A parked read inside `acquire` then carries the
frames with it and resolves correctly when it reaches the drive's top level.

Per the repository's reproduce-before-you-fix rule, this lands as a failing test first
(section 9, T-5), then the fix. It is called out here rather than routed away because it
is in the surface this design touches.

---

## 4. `hasHandler` after the redesign

The field's consumers today are `resolveContext`, `snapshotContext`, and
`dispatchControl`. The first two are deleted, so its only remaining consumer is the
operation search, where it does two things: it short-circuits the whole search when the
chain has no operation delimiter (`if !chain.hasHandler then Maybe.Absent`), and it lets
`search` skip an entire nested spine (`case inner: Offset if inner.hasHandler`) instead
of descending into it.

The cost is one boolean field per `Offset` and per `AndThen`, written from two loads and
an `or` at construction. The benefit is the difference between O(nodes in the chain) and
O(spines in the chain) per dispatched operation, on a path that runs once per suspension.
For a program that handles one effect around a long handler-free chain, that is the
difference between a per-suspension walk of the whole chain and a walk of its top spine.
It pays for itself, and it should stay.

Two refinements come with this design:

1. `ContextBinding` is no longer a `Handler`, so `hasHandler` narrows to mean "an
   operation delimiter is in here". Chains that carry bindings but no operation handler
   now skip the search entirely, which they cannot do today. The `true` moves from
   `Handler` down to `Handler.Operation` (`ArrowHandler` after C4).
2. The two summaries can be folded into one reference field if the added `context` field
   shows up in the allocation rows: a small `Summary(context, hasHandler)` object,
   shared by reference in the common case where the head contributes neither. That
   trades one field for one allocation per handler-bearing node per respine, so it is a
   benchmark decision, not a design decision. Default: keep two fields, measure, fold
   only if the `suspension` and `stateMap10k` allocation rows regress beyond the bar in
   section 6.

---

## 5. Interaction with preemption (design track A)

Track A wires `Safepoint.preempted` and `clearPreempt` into the drives at `Defer`
cadence, with non-boundary drives cascading a park outward and the boundary drive
consuming the request. Three points of contact:

- **`Defer.Read` is a `Defer`, so it polls on the same cadence.** No new preemption point
  is introduced, and a read is a cheap step, so polling around it costs nothing. The arm
  in section 3.4 sits inside whatever cadence structure track A settles on; it needs no
  cadence of its own.
- **This design adds no thread-affine state, which is what makes it compose.** The
  visible context is derived from the parked value itself. A drive that is preempted
  mid-slice returns a `Kyo` node whose chain carries both the local bindings and, for a
  forked task, the inherited frame installed by `attach`. The next slice, on any worker,
  reconstructs the same context with the same field load. A mutable register on the
  `Safepoint` (the shape section 2.2 rejects on correctness grounds) would additionally
  have been wrong here: it is per thread, and a task does not resume on the thread it
  parked on.
- **Cascade behavior is unchanged.** A `Read` parked by a non-boundary drive and a
  `Read` parked by preemption are the same node in the same position; the boundary drive
  resolves it when it re-enters. There is no state that must be re-established at
  re-entry, so there is no ordering constraint between `clearPreempt` and context
  resolution.

The one coordination item: track A is replacing the `eval(preempt, period)` and
`handlePartial(preempt, period)` surface. This design also changes `handlePartial`, by
removing the `context` parameter the current kernel's version carries (section 3.6). The
two changes touch the same signature, so whichever lands second updates the other's call
sites, and the IOTask adaptation at swap time sees one final shape.

---

## 6. Benchmarks

Per the user's ruling on this item, the rows land **before** the implementation, against
the current chain-walking code, so the baseline is measured rather than asserted. All
rows go in `kyo-kernel2/jvm/src/jmh/scala/kyo/kernel2/bench/KernelBench.scala` next to
the existing ones, same harness settings, run with `-prof gc`.

New rows:

| row | workload | what it isolates |
|---|---|---|
| `contextReadShallow` | `handle(tag, 1)(read.map(_ + 1)).eval` | the floor cost of one read |
| `contextReadDeep10` / `100` / `1000` | `handle(tag, 1)(read.map(_ + 1) chained N times).eval`, the read first | read cost as a function of chain length: the slope is the defect |
| `contextReadRepeated` | 100 reads spread through one region | per read cost when the chain shortens as execution proceeds |
| `contextNested1` / `4` | one read under 1 and under 4 nested transform-form handles | cost as a function of bindings in scope |
| `contextHandleConst` | `handle(tag, 1)(v)` install only, no eval | that installation stays structural |
| `contextHandleTransform` | `handle(tag, 1, _ + 1)(v)` install only | that the transform form stays structural (this is `Env.run` and `Local.let`) |
| `forkSnapshot1` / `4` | `detach.map(_.context)` under 1 and 4 bindings | the snapshot path that replaces `ContextSnapshot` |
| `forkedRead` | a read inside `detached.attach(child)`, no local binding | the inherited-frame path, which has no equivalent today |

Acceptance bars:

1. **`eagerMap5` does not regress.** Current official baseline 5.78 ns/op after the
   Safepoint work (the todo doc quotes 5.67 from a later run; either way the bar is the
   run's own confidence band). The design's claim is that it cannot regress, because the
   eager path constructs no `Offset` and no `AndThen`: verify, do not assume.
2. **`eagerMap5` and `resumeFused` stay at 0 B/op.**
3. **`contextReadDeep` becomes flat.** The 10, 100, and 1000 variants must agree within
   noise. Today they must not, and the before-run is what proves the defect was real.
4. **Every `contextRead*` and `forkSnapshot*` row beats its own before-number.** A read
   should also beat the current kernel's read; add the equivalent old-kernel rows if a
   cross-kernel comparison is wanted, per the precedent set for item #17.
5. **`suspension`, `suspensionStep`, `narrowIter`, `state10`, `stateMap10k`,
   `deepBind10k` within noise on time.** Allocation on the suspension-heavy rows may rise
   by the new field (8 B per `Offset` and per `AndThen`); the bar is that the rise is
   accounted for by node count and does not exceed 5% on `stateMap10k`. Beyond that, take
   the folded-summary variant from section 4.
6. **`contextHandleTransform` allocates no more than `contextHandleConst` plus one
   frame.** This is the guard on the section 7.3 rejection: if the transform form ever
   grows a node, `Env.run` and `Local.let` pay for it.

---

## 7. Alternatives considered and rejected

### 7.1 Context threaded through every transform (`Transform.run(v, cont, context)`)

The literal transcription of the current kernel, whose `KyoSuspend.apply(v, context)`
puts the context in every continuation frame. Rejected: it puts a register through the
hottest path in the kernel for the benefit of the small fraction of programs that read
context in a tight loop, and `eagerMap5` is the row that would pay. The measurement
trail in `kernel2-preemption-analysis.md` shows this path is sensitive at the level of a
single extra load. The design achieves the same semantics with the parameter present at
exactly one node kind.

### 7.2 A materialized `Context` (map) as the arrow summary

Attractive because reads become one map lookup. It fails on transform-form bindings: a
`Derived` binding's value depends on the bindings outside it, and "outside" grows every
time the computation is composed into a larger one, so a value computed at construction
time goes stale.

```scala
val inner = ContextEffect.handle(Tag[Env], 100, _ * 2)(program)   // resolves against nothing: 100
val outer = ContextEffect.handle(Tag[Env], 100, _ * 2)(inner)     // inner should now be 200
```

Recomputing on append is O(chain) per composition, and memoizing per node is unsound
under multi-shot continuations, which re-run a captured prefix under a different
enclosing context (`"a multi shot continuation re-resolves reads consistently"` is
exactly that program). The frame list keeps the laziness that makes the fold correct.

### 7.3 Resolve the transform at region entry, keep constant bindings only

Make `handle(tag, ifUndefined, ifDefined)` desugar to "read the outer binding, then
install a constant", which would make 7.2 work. Semantically sound (walked against every
oracle test, all values agree), and rejected on cost and shape: `Env.run` and
`Local.let` are transform-form handles, so every one of them would gain a `Defer.Read`
node plus a transform, and would additionally park at non-boundary drives, turning
today's structural install into a deferred step inside every `ArrowEffect.handle` region.
It also changes `handle(tag, ifU, ifD)(pureValue)` from a value into a suspension, which
`evalNow` can observe.

### 7.4 The drive carries the inherited context as a parameter

`driveLoop(v, context, ...)`, `handlePartial(tag1, tag2, v, context)`, `IOTask.context`,
with reads resolving as "local frames, then the parameter". This is the current kernel's
arrangement and it works. Rejected as the default because it splits the context into two
places that every read has to consult and every drive entry has to re-supply, while
`attach` puts the inherited frames in the one place that already survives parks,
migrations, captured continuations, and nested drives. Kept on the table as a fallback
if the swap round finds a reason IOTask must own the context explicitly; the only piece
that changes is section 3.6.

### 7.5 Keep `ContextSnapshot`, make it cheap

Rejected by ruling: the snapshot must not exist as a node kind. It is also unnecessary
once `readContext` exists, since a snapshot is just the read whose function is
`ctx => ctx.resolve.inherit`.

### 7.6 Rope-shaped summaries (`Concat(left, right)` in O(1))

Avoids the copying in `Context.concat`. Rejected for now: it needs a walk stack in every
lookup, which reintroduces the per read allocation this design removes, to save a cons
count that is bounded by the nesting depth. Revisit only if a benchmark shows `concat`
copying on a real chain shape.

---

## 8. Code changes, file by file

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/internal/Context.scala`**
- Replace the `Map[Tag[Any], AnyRef]` representation with the frame list (3.2).
- Add `bind`, `concat`, `resolve`, `isEmpty` (exact, not `eq`-based), keep `get`,
  `getOrElse`, `contains`, `set`, `inherit`.
- Delete `NoninheritableFlag` and the flag maintenance in `set`; `inherit` filters in one
  pass.

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/Arrow.scala`**
- Add `private[kyo] def context: Context` to `Arrow`; `Context.empty` on `Transform`, a
  `val` on `Offset` and `AndThen` computed with `Context.concat`.
- No change to `Offset.run`, `apply`, `guardedRun`, `optimize`, `step`.

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/internal/Handler.scala`**
- Remove `Handler.Context`; move `hasHandler = true` from `Handler` to the operation
  layer.

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/internal/ContextBinding.scala`** (new)
- `ContextBinding(bindings: Context, frame: Frame) extends Arrow.Transform`, overriding
  `context`, pass-through `run`.

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/Kyo.scala`**
- Delete `ContextRead` and `ContextSnapshot`; collapse `Suspension` into `Suspend`;
  `Continue.suspend` takes a `Suspend`.
- Split `Defer` into `Defer.Step` (today's) and `Defer.Read`, with `map` and `prepend`
  on each.
- Add `private[kyo] readContext` and `private[kyo] withContext(ctx)(v)`.

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/Pending.scala`**
- Delete `resolveContext` and `snapshotContext`.
- `dispatch` loses the two context arms and keeps the operation arm; `boundary` now
  gates only the `Defer.Read` arm and `dispatchLast`.
- `driveLoop`: add the `Defer.Read` arm (3.4); rename the `Defer` arm to `Defer.Step`;
  materialize the bracket's scope for `acquire` and `release` (3.7).
- `discardArrow` and `discardChain`: evaluate finalizers under the remaining chain's
  context (3.7).

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/ContextEffect.scala`**
- `suspend`, `suspend(default)`, `suspendWith` become `readContext` mints.
- Both `handle` overloads install a `ContextBinding`; the constant form conses a `Const`
  frame, the transform form a `Derived` frame. Neither delegates to the other any more.

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/Isolate.scala`**
- Replace `runDetached` with `Detached` and `detach` (3.6).

**`kyo-kernel2/shared/src/main/scala/kyo/kernel2/ArrowEffect.scala`**
- No change required by this item. `handlePartial` is touched only if section 7.4 is
  chosen, and by track A for the preemption parameters.

**`kyo-kernel2/jvm/src/jmh/scala/kyo/kernel2/bench/KernelBench.scala`**
- The rows in section 6, added first, against the current implementation.

**kyo-core (swap round, not now)**
- `IOTask`: drop `context`, the `override def context` subclass, and the `context`
  argument to the partial drive. `Fiber`: four `runDetached` sites become `detach` plus
  `attach`. Listed so the swap round inherits a written-down handshake, not so this item
  changes kyo-core.

---

## 9. Test impact

`ContextEffectTest` (`shared/src/test/scala/kyo/kernel2/ContextEffectTest.scala`): every
existing case was walked against the design and keeps its current expected value,
including the two that pin the semantics most tightly, `"ifDefined behavior"` (200, an
inner `Derived` frame folding over an outer one) and `"a multi shot continuation
re-resolves reads consistently"` (the captured prefix carries the `ContextBinding`, so
`k(10)` and `k(20)` resolve `Env` to 5 both times). New cases:

- T-1: a read at the head of a long composed chain returns the same value as a read at
  the tail, with the chain long enough that a walk-based implementation would be visibly
  different in the benchmark (behavioral guard for the summary invariant).
- T-2: an unresolvable read at a boundary reports a defect rather than parking (3.4).
- T-3: a computation under `attach` reads an inherited binding; a local binding of the
  same tag shadows it.
- T-4: a noninheritable binding is absent under `attach`, present locally (moves the
  `IsolateTest` "context inheritance" assertion onto the new API).
- T-5: reproduction and fix for the bracket defect: a read in `acquire`, in `release`,
  and in a finalizer run by the discard path, all under a `ContextEffect.handle` (3.7).

`IsolateTest`: the ten `runDetached { (trace, context) => ... }` sites become
`detach.map { detached => ... }`, with `detached.context` where the test inspects the
snapshot. Mechanical, no assertion changes except the shape of the lambda.

`PendingTest`, `PendingInternalTest`, `KyoTest`, `EffectTest`, `ArrowEffectTest`,
`LoopTest`: no references to the removed node kinds, no expected impact beyond
compilation.

kyo-prelude's `LocalTest` uses the current kernel's `runDetached` and is untouched until
the swap round.

---

## 10. Open questions that need the user

- **O-1 (naming).** `Defer.Step` / `Defer.Read` for the two defer forms,
  `ContextBinding` for the chain node (C4 asked for this name, and the node is no longer
  a handler), `Detached` / `detach` / `attach` for the fork boundary. All are proposals;
  the mechanism does not depend on them.
- **O-2 (`Context` representation and item #19).** This design makes `Context` a frame
  list, which makes the approved-in-direction `TypeMap` migration moot as written. Ruling
  needed: re-scope #19 to the resolved fork snapshot, or keep `Context` a keyed map and
  introduce a second in-flight type (section 3.2, fallback).
- **O-3 (tag matching).** Bindings are matched by exact tag, as the current kernel and
  `Context` already do, dropping `resolveContext`'s subtype-tolerant match and the
  pre-fork versus post-fork inconsistency it creates. Confirm that no downstream effect
  intends to bind a subtype and read a supertype.
- **O-4 (sequencing with #16).** Both items want `Defer` to carry an abstract resumption
  method. Landing #16 first makes this item's node change a one-line signature addition;
  landing this first means #16 revisits two classes instead of one. Recommend #16 first,
  which needs its pending ruling.
- **O-5 (the bracket defect).** It is a pre-existing kernel2 bug found while designing
  this item, and the fix belongs here because the fix is "read the bracket's own scope",
  which only exists after this change. Confirm it lands with this item rather than as a
  separate one.
