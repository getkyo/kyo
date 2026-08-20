# Porting EffectTrace to the proto kernel

Proposal, written against `f329501024`. Nothing here is implemented. The source of truth for the
existing design is `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala` (265
lines, live in the kyo.kernel package) and its wiring, which is commented out in that package's
`Eval.scala` (7 call sites).

## What EffectTrace is

The effect-level frames of a failure, carried as a suppressed exception on the failure itself.
Nothing is recorded while a computation runs: at the boundary an exception crosses, the frames are
reconstructed from the failing value plus the drive stack the evaluator is already holding, so a
run that does not throw pays nothing. The carrier exists for three reasons: its presence among
`getSuppressed` marks an exception as already enriched, it accumulates the reconstruction of every
boundary the exception crosses, and `getMessage` renders the frames for a reader who would rather
not parse a stack trace. `splice` then rewrites the exception's own trace as synthesized frames
first, physical frames (minus kernel plumbing) after.

## What transfers unchanged

The carrier class, `MaxFrames = 64`, `reconstruct`, `carrierOf`, `find`, `splice`, `installInto`,
and the `Builder`'s `frame` / `region` / `push` / `drain` skeleton, including the cap discipline
(the worklist never exceeds the cap, emission stops at it, nothing recurses on the Java stack) and
the two error rules (a fatal error is returned unmodified; a non-fatal failure of the walk itself
is dropped, because an exception raised while describing a failure would replace the failure).

`Frame`'s API (`calleeName`, `className`, `callerName`, `position.fileName`,
`position.lineNumber`, `Frame.internal`) is `kyo-data` and unchanged.

## What changes, exhaustively

### 1. The walk's match: node kinds, not arrow kinds

The old kernel walks seven `Arrow` subclasses (`Chain`, `Suspend`, `Handle`, `Bind`, `Park`,
`Transform`, `Step`). The proto has three `Kyo` node kinds and two `Arrow` kinds, and its pending
things are `Kyo` nodes rather than `Arrow` subclasses, so the worklist element type becomes `Any`:

```scala
                work.removeHead() match
                    case h: Handler[?, ?, ?, ?] =>          // BEFORE Transform: Handler <: Arrow.Transform
                        region(h.tag)
                    case c: Arrow.Chain[?, ?, ?, ?] =>
                        push(c.b)
                        push(c.a)
                    case t: Arrow.Transform[?, ?, ?] =>
                        frame(t.frame)
                    case d: Kyo.Defer[?, ?, ?, ?] =>
                        push(d.contB)
                        push(d.contA)
                        value(d.value)
                    case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                        frame(s.frame)
                        push(s.cont)
                    case h: Kyo.Handle[?, ?, ?, ?, ?] =>
                        region(h.handler.tag)
                        push(h.cont)
                        value(h.value)
                    case _ => ()
```

Three facts drive this:

- `Handler` extends `Arrow.Transform` in the proto (`Handler.scala:7`). Matching `Transform` first
  would emit a source frame where a region label belongs, so the `Handler` arm must precede it.
- `Kyo.Defer` and `Kyo.Handle` carry no `Frame` (`KyoInternal.scala:13-31`); only `Kyo.Suspend` and
  every `Arrow` do. Node arms therefore contribute structure, and positions come from the
  `Transform`s reachable through them. A `Defer` chain with no user transform renders no frames.
- `Kyo` is `sealed abstract` and `Arrow` is `sealed`, so this match is exhaustiveness-checked from
  inside `kyo.proto`: a future node kind breaks the build here instead of silently emitting
  nothing. That is a reason to put the file in `kyo/proto/`, not a subpackage.

### 2. `entries`: the proto Stack has no `apply` and no `base`

Each `Eval` borrows its own `Stack` (`Stack.borrow()`), so the drive has no base index, and
`entries`/`head`/`mask` are private. One accessor is added:

```scala
// Stack.scala
    private[proto] def entry(i: Int): Arrow[?, ?, ?] = entries((head + i) & mask)
```

and the sweep loses its `base` parameter:

```scala
        def entries(stack: Stack): Unit =
            @tailrec def loop(i: Int): Unit =
                if i >= 0 then
                    if full then dropped += i + 1
                    else
                        value(stack.entry(i))
                        loop(i - 1)
            loop(stack.size - 1)
```

### 3. `isPlumbing`: the proto's own package

```scala
    private def isPlumbing(e: StackTraceElement): Boolean =
        val cls = e.getClassName
        cls.startsWith("kyo.proto.Eval") || cls.startsWith("kyo.proto.Arrow") ||
        cls.startsWith("kyo.proto.ArrowEffect") || cls.startsWith("kyo.proto.Stack") ||
        cls.startsWith("kyo.proto.Handler") || cls.startsWith("kyo.proto.Effect") ||
        cls.startsWith("kyo.proto.Nested") || cls.startsWith("kyo.proto.Pending")
```

The per-site `Arrow.Transform` a user's `map` mints is an anonymous class in the user's own
compilation unit carrying the user's line numbers, so it is the most informative physical frame
present and is never filtered.

## The wiring, and the constraint that shapes it

The proto's drive is `@tailrec def loop(curr: Any < Nothing)`. **A `try` around a `loop(...)` call
makes it a non-tail call and the drive stops being stack-safe**, which the deep-recursion and
long-map-tower pins would catch. The old kernel did not face this: its drive was a `while` loop.

Two consequences:

**(a) The boundary catch is free.** It wraps the initial call, outside the recursion:

```scala
        try loop(v.asInstanceOf[Any < Nothing]).asInstanceOf[A]
        catch
            case ex: Throwable =>
                EffectTrace.splice(ex)
                throw ex
        finally
            Stack.release(stack)
            Safepoint.restore(slot, saved)
```

**(b) Any per-application attach must compute first and recurse after**, so the `try` does not
contain the tail call:

```scala
                            case head =>
                                val tail = stack.dump()
                                val next =
                                    try head.asInstanceOf[Arrow[Any, ?, EX & S]](curr, tail)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, tail, stack)
                                            throw ex
                                loop(next)
```

## Proposed scope, and why it is staged

**Stage 1 (this proposal):** `Stack.entry`, `EffectTrace.scala` in `kyo.proto`, wiring at the
boundary only (a), and the ported `EffectTraceTest`. The boundary attach still walks the whole
drive stack, which is where most frames come from; what it loses relative to the kernel is the
*failing value* at the moment of the throw (the kernel passes `v` and `next` from the site).

Open question for stage 1: whether `attach` should be called at the boundary too (it has the stack
but not the failing value), or whether `splice` alone suffices there. As written above, the
boundary only splices, which renders nothing unless an inner site attached; so stage 1 without any
inner site produces *no frames*. That makes stage 1 as stated incomplete: either the boundary must
also `attach(ex, stack)` (a new two-argument overload without a value), or at least one inner site
is required. **Recommended: add the boundary overload**

```scala
    def attach(ex: Throwable, stack: Stack): Unit =
        reconstruct(ex)(_.entries(stack))
```

and call it in the boundary catch before `splice`. The stack there is still live: `Stack.release`
runs in the `finally`, which executes after the catch body.

**Stage 2 (measured, not assumed):** per-application attaches at the delivery site and the clause
site, each in the (b) shape. Each adds a `try` region inside the hot loop; the guard rows are
`evalFixedOverhead` and `fusionAllocatesNothing` (currently ~0.011 us and ~0.57 us, zero
allocation), plus `deepRecursionPaysRescuesOnly` for stack safety and `handleLoopAnswersInPlace`
for the handler path. A stage-2 site lands only if its pin demands it and the fusion rows do not
move beyond the drift band.

## Cost claim

Zero when nothing throws, provided the `try` regions do not inhibit inlining of the drive. That is
a measurement, not an argument: the two fusion rows above are the test.

## Test port

`EffectTraceTest` (300 lines) ports by package, by effect definitions (`kyo.proto.ArrowEffect`),
and by handler constructors (`handleCont` / `handleLoop`). Its assertions are on rendered frames
and are design-independent, except where they assume the kernel's node kinds produce a frame the
proto's do not (see the `Defer`/`Handle` note above): those cases need their expectations
re-derived from the proto's shapes, not copied.

## What is NOT touched

`ArrowEffect`, `Loop`, `Safepoint`, `Nested`, `Effect`, the benches, and the existing
`kyo.kernel.internal.EffectTrace`, which stays where it is; the two coexist in different packages.
