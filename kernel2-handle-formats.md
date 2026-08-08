# How resume, stop, and first handlers work

This document explains how `ArrowEffect.handleResume`, `handleStop`, and
`handleFirst` execute in kyo-kernel2, using the actual implementation code.
It starts from the two pieces every format shares: the shape of a suspended
computation, and the handle loop with its rotation step. Each format is then
one small variation on that skeleton.

## 1. The shape being handled

An operation suspends as a `Suspend` node. The node carries the operation
input, the effect tag, and the continuation from the operation's output to
the rest of the computation, already fused into one arrow:

```scala
abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, +B, -S] extends Kyo[B, S]:
    def input: I[A]
    def tag: Tag[E]
    /** The fused continuation from the operation's output; the identity arrow for a bare operation. */
    def cont: Arrow[O[A], B, S]
```

A bare operation's `cont` is the shared identity arrow, so suspending costs
one node. Mapping a suspended computation does not run anything; it extends
`cont`:

```scala
final private[kyo] def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
    Continue(origin, cont.map(f))
```

So by the time a handler sees a computation, it sees either a settled value,
a `Suspend` whose `cont` is the entire rest of the program, or one of the two
structural nodes (`Defer`, `Bracket`).

## 2. Handling is an eager fold

Every handle call folds the computation immediately, at the call site. There
is no interpreter that runs later: `handleStop(tag, v)(f)` walks `v` right
now and returns the handled computation. Every format's loop has the same
three arms:

```scala
def loop(v: A < (E & S & S2), context: Context, handlers: Handlers): A < (S & S2) =
    v match
        case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag =>
            // arm 1: an operation of THIS effect is at the surface: handle it
        case k: Kyo[A, E & S & S2] @unchecked =>
            // arm 2: something else is pending (a foreign suspension, a Defer, a Bracket):
            // rotate this handler into it and return
        case v =>
            // arm 3: settled: the handled effect is discharged, the value passes through
```

Arm 1 is where the formats differ. Arms 2 and 3 are the same everywhere: arm
3 completes, and arm 2 delegates to `rotate`, which implements the rotation law.

## 3. Rotation: crossing a foreign suspension

Suppose the computation surfaces an operation of a different effect, say an
`Async` suspension while we are handling `EffAsk`. The handler cannot answer
it, and it must not lose track of the rest of the computation either: after
the `Async` operation resumes, more `EffAsk` operations may appear, and this
handler must still be there. The law is:

```
handle(t1, suspend(t2, in, cont), f) == suspend(t2, in, x => handle(t1, cont(x), f))   where t1 != t2
```

The foreign suspension crosses the handler outward, and the handler rotates
into the suspension's continuation. `rotate` is that law as code:

```scala
private[kernel2] def rotate[A, B, S, S2](
    v: Kyo[A, S],
    rotated: [X] => Arrow[X, A, S] => Arrow[X, B, S2],
    loop: (A < S, Context, Handlers) => B < S2,
    context: Context,
    handlers: Handlers
): B < S2 =
    v match
        case s: Kyo.Suspend[?, ?, ?, ?, A, S] @unchecked =>
            s.continue(rotated(s.cont))
        ...
```

`rotated(s.cont)` wraps the suspension's continuation in a `Rotate` step: an
`Arrow.Transform` that, when the operation eventually resumes, applies the
original continuation and folds the result through the loop again:

```scala
def plain[X, M, S, A, S2](
    chain: Arrow[X, M, S],
    loop: (M < S, Context, Handlers) => A < S2,
    _frame: Frame
): Rotate[X, A, S2] =
    new Rotate[X, A, S2](chain):
        def frame = _frame
        def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
            cont(loop(chain(defaultLift(v), context, handlers), context, handlers), context, handlers)
```

Read `run` inside out: the resumption value `v` enters the contained chain
(the rest of the computation as it was at rotation time), and the chain's
result goes through `loop`, which is the same handle loop, re-entered. That
is what makes handlers deep without any registration: however and whenever
the continuation is invoked, even multiple times, the loop re-runs around
each invocation because it is physically wrapped inside the continuation.

This is the whole trick, shared by all formats. Now the formats.

## 4. handleStop: end the computation at each operation

The final ctl format. The handle function produces the result directly, and
the continuation from the operation to this handler never runs:

```scala
def handleStop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
    effectTag: Tag[E],
    v: A < (E & S)
)(
    handle: [C] => I[C] => A < (E & S & S2)
)(using frame: Frame): A < (S & S2) =
    def loop(v: A < (E & S & S2), context: Context, handlers: Handlers): A < (S & S2) =
        v match
            case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag =>
                // the continuation to this handler never runs: the operation input is all the handle function needs
                loop(handle[x](s.input), context, handlers)
            case k: Kyo[A, E & S & S2] @unchecked =>
                rotate(k, [X] => (chain: Arrow[X, A, E & S & S2]) => Rotate.stop(chain, entry, loop, frame), loop, context, handlers)
            case v =>
                v.asInstanceOf[A < (S & S2)]
        end match
    end loop
    traced(frame)(loop(v, Context.empty, Handlers.empty))
```

Arm 1 ignores `s.cont` entirely. Everything the program would have done after
the operation is discarded, and the handle function's result is folded again
by the direct `loop(...)` self-call, so operations of `E` inside the
replacement are also handled (the handler is deep).

Since the encodings round, discarding is not the whole story: the handler
also registers a per-call `Entry.Stop` in the threaded `Handlers` (via
`Rotate.stop` at every re-entry), and the bubble point recognizes it. An
operation of `E` surfacing anywhere in the region passes through bare: no
`Continue` or `AndThen` is stacked, no rotation installed, the suspension
travels outward as a plain return value, and this loop's arm 1 answers it on
arrival. The continuation that never runs is now never built either. For
effects whose output type is `Nothing`, the same skip applies statically,
before any handler exists: `suspend` mints a `NeverResumed` suspension and
every application returns it untouched, so `fail(x).map(f).map(g)` stays the
one suspension node.

Example. `EffAsk` is `ArrowEffect[Const[Unit], Const[Int]]`:

```scala
val program: Int < EffAsk = ask.map(a => ask.map(b => a + b))
val handled = ArrowEffect.handleStop(Tag[EffAsk], program)([C] => _ => -7)
handled.eval    // -7
```

The first `ask` is at the surface when `handleStop` folds `program`; arm 1
fires, the continuation `a => ask.map(b => a + b)` is dropped, and the whole
computation is `-7`. No continuation is built at any point.

Because the discarded continuation may carry bracket releases, dropping it is
not free-form: when a stop crosses a `Bracket` (section 8), the bracket is
discarded before anything is acquired, so there is nothing to release.

## 5. handleResume: answer each operation in place

The fun format. The handle function is `[C] => I[C] => O[C] < ...`: it
produces the operation's output and the kernel resumes the continuation with
it, exactly once. No continuation is ever exposed. This format has an extra
mechanism the others do not: the threaded `Handlers` parameter.

```scala
def handleResume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
    effectTag: Tag[E],
    v: A < (E & S)
)(
    handle: [C] => I[C] => O[C] < (E & S & S2)
)(using frame: Frame): A < (S & S2) =
    val h = new ResumeHandler[I, O, E, S & S2](effectTag, handle, frame)
    def loop(v: A < (E & S & S2), context: Context, handlers: Handlers): A < (S & S2) =
        v match
            case s: Kyo.Suspend[I, O, E, x, A, E & S & S2] @unchecked if effectTag.erased <:< s.erasedTag =>
                loop(s.cont(h.handle[x](s.input), context, handlers), context, handlers)
            case k: Kyo[A, E & S & S2] @unchecked =>
                rotate(k, [X] => (chain: Arrow[X, A, E & S & S2]) => Rotate.handler(chain, h, loop, frame),
                       (w, c, hs) => loop(w, c, hs, hs.add(new Handlers.Entry.Resume(h, c, hs))), context, handlers)
            case v =>
                v.asInstanceOf[A < (S & S2)]
        end match
    end loop
    traced(frame)(loop(v, Context.empty, Handlers.empty))
```

There are three distinct situations in which an operation of `E` can surface,
and they take different paths.

**Case 1: the operation is at the surface of the fold.** Arm 1 fires. The
answer is a direct call, `h.handle[x](s.input)`, and the continuation is
resumed immediately by applying `s.cont` to it. Nothing is captured, nothing
is looked up, no node is allocated for the answer itself.

**Case 2: the operation surfaces after a foreign suspension resumed.** This
is where the format differs from the others. The rotate step it installs is
`Rotate.handler`, not `Rotate.plain`:

```scala
def handler[X, M, S, A, S2](
    chain: Arrow[X, M, S],
    h: ResumeHandler[?, ?, ?, ?],
    loop: (M < S, Context, Handlers) => A < S2,
    _frame: Frame
): Rotate[X, A, S2] =
    new Rotate[X, A, S2](chain):
        def frame = _frame
        def run[C, S3](v: X, context: Context, handlers: Handlers, cont: Arrow[A, C, S3]): C < (S2 & S3) =
            val extended = handlers.add(new Handlers.Entry(h, context, handlers))
            cont(loop(chain(defaultLift(v), context, extended), context, handlers), context, handlers)
```

Before running the contained chain, it registers the handler in the threaded
`Handlers` parameter. `Handlers` travels beside `Context` through every arrow
application; it is never stored in a node. While the chain runs, any
operation of `E` that surfaces is caught at the single point where
suspensions bubble, in `Arrow`'s application on a pending value:

```scala
else if v.isInstanceOf[Kyo[?, ?]] then
    val kyo = v.asInstanceOf[Kyo[Any, Any]]
    if handlers.isEmpty then kyo.asInstanceOf[Kyo[A, S2]].map(self)
    else
        val answered = ArrowEffect.answerNow(kyo, context, handlers)
        if answered.asInstanceOf[AnyRef] eq null then kyo.asInstanceOf[Kyo[A, S2]].map(self)
        else self(answered.asInstanceOf[A < S2], context, handlers)
```

`answerNow` resolves the suspension's tag against the registered entries and,
on a hit, answers in place:

```scala
private[kyo] def answerNow(kyo: Kyo[Any, Any], context: Context, handlers: Handlers): Any < Any =
    kyo match
        case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
            val entry = handlers.resolve(s.erasedTag)
            if entry eq null then null.asInstanceOf[Any < Any]
            else
                val w = entry.handler.answer(s.input)
                val k = s.cont.asInstanceOf[Arrow[Any, Any, Any]]
                if w.isInstanceOf[Kyo[?, ?]] then
                    k(scoped[Any, Any, Any](entry)(w), context, handlers)
                else
                    k(defaultLift(w), context, handlers)
```

This is the point of the format: the operation is answered exactly where it
surfaced, and its continuation keeps running forward. The suspension does not
travel outward, so no chain of `Continue` nodes is stacked on it on the way
to a handler. Compare with what the `handlers.isEmpty` branch does for every
other format: `kyo.map(self)` pushes the current arrow onto the suspension
and lets it bubble.

Two details of `answerNow`:

- The `Entry` carries `entryContext` and `entryHandlers`, the parameters
  captured where the handler was installed. If the handle function's answer
  is itself effectful (a pending computation), it must run at the handler's
  scope, not at the operation site's: a context read inside the handle
  function resolves against the bindings outside the handler, not whatever
  bindings enclose the operation. `scoped` enforces this by folding the
  answer with `Rotate.at`, a rotate step that applies the remainder at the
  entry's captured parameters across any suspensions inside the answer.
- The `null` protocol is the miss path: no registered handler matched, so the
  caller falls back to structural bubbling and the operation reaches its
  handler through arm 1 of that handler's loop.

**Case 3: the operation surfaces while arm 1 resumes the continuation.**
Since the encodings round the loop threads a precomputed extension (the
ambient plus this invocation's entry), and arm 1 resumes under it:
`loop(s.cont(h.handle[x](s.input), context, extended), context, handlers,
extended)`. An operation of `E` surfacing during the resumption is answered
where it surfaces, exactly like case 2, and an outer entry of the same tag
can no longer capture it. The extension is computed once per loop invocation,
so the arm allocates nothing per operation.

Example:

```scala
val program: Int < (EffAsk & Sleep) =
    ask.map(a => sleep.map(_ => ask.map(b => a + b)))

val handled = ArrowEffect.handleResume(Tag[EffAsk], program)([C] => _ => 10)
```

The first `ask` is answered by arm 1 directly (case 1). The `sleep`
suspension is foreign: `rotate` sends it outward with `Rotate.handler`
wrapped around `_ => ask.map(b => 10 + b)`. When `sleep` resumes, the rotate
step registers the handler and runs the chain; the second `ask` surfaces
inside that application and `answerNow` answers it in place (case 2). Result:
`20 < Sleep`, with the second operation never traveling.

## 6. handleFirst: intercept one operation, shallowly

`handleFirst` handles only the first operation of `E` and does not
reinstall itself. It is the primitive for park and step protocols: take the
first operation, get the continuation as a value, decide later.

```scala
def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
    effectTag: Tag[E],
    v: A < (E & S)
)(
    handle: [C] => (I[C], O[C] => A < (E & S)) => B < S2,
    done: A => B < S2
)(using frame: Frame): B < (S & S2) =
    def loop(v: A < (E & S), context: Context, handlers: Handlers): B < (S & S2) =
        v match
            case s: Kyo.Suspend[I, O, E, x, A, E & S] @unchecked if effectTag.erased <:< s.erasedTag =>
                val k      = s.cont
                val resume = (o: O[x]) => k(defaultLift(o), context, handlers)
                // shallow: the handler leaves, so the result is not looped
                handle[x](s.input, resume)
            case k: Kyo[A, E & S] @unchecked =>
                rotate(k, [X] => (chain: Arrow[X, A, E & S]) => Rotate.masked(chain, shadow, loop, frame),
                       (w, c, hs) => loop(w, c, hs, if hs.resolve(shadow.tag).isEmpty then hs else hs.add(shadow)), context, handlers)
            case v =>
                done(Kyo.unnest(v).asInstanceOf[A])
        end match
    end loop
    traced(frame)(loop(v, Context.empty, Handlers.empty))
```

Three differences from the deep formats, all visible in the code:

- Arm 1 does not call `loop` on the handle function's result. The handler is
  gone after the first operation: if the handle function invokes `resume`,
  later operations of `E` in the resumed computation are not handled by this
  call, and its `A < (E & S)` result type says so.
- The continuation is handed to the handle function as a plain value,
  `resume`. Invoking it applies the suspension's fused chain; not invoking it
  abandons the rest of the computation.
- Completion goes through `done`. If the computation settles without any
  operation of `E`, arm 3 maps the value into the caller's result type.

The rotate step is still `Rotate.plain(chain, loop, frame)`: if a foreign
suspension crosses first, the shallow handler stays wrapped and still catches
the first `E` operation after the resumption. Shallow means not reinstalled
after handling, not gone before handling.

Example, a one-step driver:

```scala
val program: Int < EffAsk = ask.map(a => ask.map(b => a + b))

val stepped: Maybe[Int < EffAsk] < Any =
    ArrowEffect.handleFirst(Tag[EffAsk], program)(
        [C] => (_, resume) => Maybe(resume(1)),
        a => Maybe.empty
    )
```

The first `ask` is intercepted, `resume(1)` produces `ask.map(b => 1 + b)`,
and that remainder still requires `EffAsk`: the second operation was not
handled, as the types show.

## 7. Where the ctl format fits

`handle` (the ctl format) is arm 1 of `handleStop` plus the continuation of
`handleFirst` plus deep re-entry: the handle function receives both the input
and `resume`, decides whether and how often to invoke it, and its result is
folded by the loop again. `handleResume` and `handleStop` are the two
degenerate cases worth their own formats because their arm 1 needs no
continuation value at all: resume always resumes once, so the kernel can do
it; stop never resumes, so nobody does.

## 8. Brackets

`rotate` has two more arms. A `Defer` carries the rotate step into its
continuation. A `Bracket` distinguishes whether its acquire is still pending:

```scala
case b: Kyo.Bracket[r, A, S] @unchecked =>
    if b.acquire.isInstanceOf[Kyo[?, ?]] then
        // fold acquire as the head of the computation and rebuild the bracket
        // around the settled resource
        ...
        loop(rebuild(b.acquire, context, handlers), context, handlers)
    else
        new Kyo.Bracket[r, B, S2]:
            def acquire       = b.acquire.asInstanceOf[r < S2]
            def release(x: r) = loop(b.release(x).asInstanceOf[A < S], context, handlers).asInstanceOf[Unit < S2]
            def cont          = rotated(b.cont)
            def frame         = b.frame
```

A pending acquire is folded before the bracket exists, and the bracket is
rebuilt around the settled resource. The continuation an operation inside
acquire presents therefore spans the rest of acquire, the bracket, and its
use. For the three formats this means:

- `handleStop` during acquire discards the bracket: nothing acquired, release
  never runs, the stop value is the result.
- `handleResume` during acquire answers and acquisition continues; the
  bracket is then rebuilt and use and release run normally.
- `handleFirst` during acquire hands out a continuation that, when resumed,
  finishes acquiring and runs the bracket; when dropped, abandons it before
  anything was acquired.

## 9. Cost summary

| situation | resume | stop | first |
|---|---|---|---|
| operation at the fold's surface | direct call, resume in place | direct call, rest discarded | continuation exposed once |
| operation after a foreign resumption | answered where it surfaces via Handlers, no nodes stacked | passes through bare via the stop entry, nothing stacked, the loop answers on arrival | travels structurally under the shadow, intercepted by the loop |
| operation while arm 1 resumes | answered where it surfaces under the precomputed extension | not applicable, nothing resumes | not applicable, handler already left (the resume closure deliberately unshadowed) |
| foreign suspension | one Rotate.handler node | one Rotate.stop node | one Rotate.masked node |
| settled value | passes through | passes through | mapped by done |
| Const[Nothing] operation | passes through untouched from construction, any format | same | same |
