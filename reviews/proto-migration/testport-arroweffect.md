# ArrowEffectTest: old kernel vs proto coverage gap

Compared:

- OLD `kyo-kernel/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala` (168 test cases)
- PROTO `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/ArrowEffectTest.scala` (110 test cases)

Judgement is on behavior, not names. A proto test with a different name asserting the same
behavior counts as covered. Where a behavior is absent from the proto `ArrowEffectTest` but
present elsewhere in the proto test tree (`internal/EvalTest.scala`, `PendingTest.scala`,
`EffectBracketTest.scala`), that is called out per entry: those are low-priority ports.

## Counts

| Bucket | Count |
|---|---|
| Already covered in the proto file | 100 |
| PORTABLE, missing from the proto file | 64 |
| DIVERGENT (old-kernel-only machinery or contradicted by proto design) | 4 |
| Old total | 168 |

## Porting cheat sheet (applies to every body quoted below)

The bodies below are quoted verbatim from the old file. Every one of them needs the following
mechanical rewrites, which are already the house style of the proto file:

1. `Eval(x)` becomes `eval(x)` (the proto file's local helper `private def eval[A](v: A < Any): A = v.eval`).
   `Eval.partial(x)` stays as-is; `kyo.proto.kernel.internal.Eval` is already imported.
2. `Loop.continue(answer)` becomes `Loop.continue((), answer: T < Any)`. The proto clause returns
   `Loop.Outcome2[Unit, O[C] < ..., B < ...]`, so the stateless loop carries a `Unit` state and the
   answer is ascribed to the pending type. `Loop.continue(state, answer)` becomes
   `Loop.continue(state, answer: T < Any)`. `Loop.done(x)` is unchanged.
3. `ArrowEffect.handleCatching(tag, v)(handle)(recover)` becomes
   `ArrowEffect.handleCont(tag, v)(handle, a => a, ex => Maybe(recover(ex)))`; the four-clause form
   `handleCatching(tag, v)(handle, done)(recover)` becomes `handleCont(tag, v)(handle, done, recover)`
   with the recovery returning `Maybe[B < ...]`. The same third-clause overload exists on
   `handleLoop` and `handleLoopState`. There is no `handleCatching` in the proto.
4. `Effect.catching(v)(f)` has no proto equivalent. Where the old test wraps the whole region
   (outside the handler), use `try eval(region) catch case ex if NonFatal(ex) => f(ex)`. Where the
   old test wraps a slice of the computation *inside* the region, there is no substitute other than
   installing an inner recovering region on a scratch effect tag; those cases are listed DIVERGENT.
5. `internal.SafepointStop.request()` becomes the proto's park request, spelled as in the proto
   file's `dispatchFirst` / "sees through a parked slice" test:
   ```scala
   kyo.discard(Safepoint.stop(Thread.currentThread()))
   Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
   ```
6. `internal.Safepoint.get()/enter/exit/save/restore` becomes `Safepoint.*` from
   `kyo.proto.kernel.internal.Safepoint`, already imported in the proto file.
7. `Effect.bracket[A, B, S](acquire)((a, r: Result[...]) => ...)(use)` becomes
   `Effect.bracket(acquire)((a, ex: Maybe[Throwable]) => ...)(use)`: the proto release clause takes
   `Maybe[Throwable]`, not `Result`, so `case Result.Panic(ex)` becomes `case Maybe.Present(ex)`.
8. `Arrow[...]` resolves to `kyo.proto.Arrow`, already imported.
9. The old file extends `kyo.Test` and uses `typeCheckFailure(code)(expectedMessage)`; the proto file
   extends `AnyFreeSpec` and uses `assertTypeError(code)`.
10. `handleLoopWith` and `handleLoopStateWith` need explicit type arguments at some call sites in the
    proto (see the existing proto tests for the spelling).
11. `ArrowEffect.handleFirst` has the same signature in both kernels
    (`handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2)`, `done: A => B < (S & S2)`), so the
    old file's local `handleFirst` adapter at line 1017 ports verbatim.

### Effect declarations the ports need

The proto file declares only `Ask`, `AskSub`, `Say`. These old declarations must come along:

```scala
// one effect at many type arguments, the shape Poll and Emit are handled at: two instantiations are
// separate handlers, and telling them apart is tag subsumption on a covariant parameter
sealed trait Pick[+V] extends ArrowEffect[Const[Unit], Const[V]]
def pick[V](using Tag[Pick[V]]): V < Pick[V] = ArrowEffect.suspend[Any](Tag[Pick[V]], ())

// an effect whose answers are themselves pending computations, for the boxed-answer lane
sealed trait AskBoxed extends ArrowEffect[Const[Unit], [X] =>> Int < Say]
def askBoxed: (Int < Say) < AskBoxed = ArrowEffect.suspend[Any](Tag[AskBoxed], ())
```

plus, for the "answering only the first operation" section:

```scala
enum First derives CanEqual:
    case Done(value: Int)
    case Standing(cont: Arrow[Int, First, Ask])
```

and, for the non-Const section:

```scala
sealed trait CustomEffect extends ArrowEffect[List, Option]

def customEffect(input: List[Int]): Option[Int] < CustomEffect =
    ArrowEffect.suspend[Int](Tag[CustomEffect], input)
```

---

# A. PORTABLE, missing from the proto file (64)

## A.1 handleLoopState (3 missing of 13)

### A.1.1 "deep state transitions under a suspending clause are stack safe"

Pins that a clause which suspends (here through `Effect.defer`) re-enters its region with the state
rewrapped, and that the rewrap stays one delegation layer deep across 10000 transitions. The proto
has no stack-safety pin for a *suspending* stateful clause.

PORTABLE. `Effect.defer(f: => A < S)(using Frame)` exists in the proto.

```scala
        "deep state transitions under a suspending clause are stack safe" in {
            // a suspending clause re-enters its region with the state rewrapped onto the handler;
            // the rewrap stays one delegation layer deep however many transitions the region runs
            def loop(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => loop(i - a))
            val r: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, loop(10000))(
                [C] => (s, _) => Effect.defer(Loop.continue(s + 1, 1)),
                (s, a) => s + a
            )
            assert(Eval(r) == 10000)
        }
```

### A.1.2 "the overload without done completes with the result and discards the state"

Pins the single-clause `handleLoopState(tag, state, v)(handle)` overload: no `done`, the region
completes with the body's result and the final state is discarded. The proto has that overload
(`ArrowEffect.scala:358`) but no test reaches it; the proto's "settled inputs pass through strictly"
was rewritten to use the two-clause forms, so the proto exercises **none** of the three
without-`done` overloads (`handleCont`, `handleLoop`, `handleLoopState`). Porting this one plus
restoring the old "settled inputs pass through strictly" spelling closes that.

PORTABLE.

```scala
        "the overload without done completes with the result and discards the state" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleLoopState(Tag[Ask], 10, v)([C] => (s, _) => Loop.continue(s + 1, s))
            assert(Eval(r) == 21)
        }
```

The old without-`done` spelling of the covered "settled inputs pass through strictly" test, for the
same reason:

```scala
        "settled inputs pass through strictly" in {
            val v: Int < Ask = 42
            assert(ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(0)).evalNow == Maybe(42))
            assert(ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(0)).evalNow == Maybe(42))
            assert(ArrowEffect.handleLoopState(Tag[Ask], 7, v)(
                [C] => (s, _) => Loop.continue(s, 0)
            ).evalNow == Maybe(42))
        }
```

### A.1.3 "a parked stateful region resumes with its state and done"

Pins that a park taken from inside the clause resumes at the next full eval carrying the in-flight
state, and that `done` then observes the *final* state (12), not the install-time one.

PORTABLE with cheat-sheet item 5. Partially covered outside the file by
`internal/EvalTest.scala:584` "a stateful region parked mid-loop resumes at the parked state", which
does not exercise the `done` clause's view of the final state.

```scala
        "a parked stateful region resumes with its state and done" in {
            val v = ask.map(a => ask.map(b => a + b))
            val handled: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 10, v)(
                [C] =>
                    (s, _) =>
                        if s == 10 then internal.SafepointStop.request()
                        Loop.continue(s + 1, s)
                ,
                (s, a) => s * 1000 + a * 2
            )
            val parked = Eval.partial(handled)
            assert(parked.evalNow.isEmpty)
            // the region parked after the first answer; the resume continues from state 11 and the
            // done clause observes the final state
            assert(Eval(parked) == 12 * 1000 + (10 + 11) * 2)
        }
```

## A.2 "answering only the first operation" (7 missing of 7)

The whole section is absent. It builds first-operation answering by hand out of `handleCont` at the
region's own currency: the clause returns a settled `First` carrying the continuation, a settled
value is what ends a region, so the entry pops and the continuation still carries the effect. The
proto's `handleFirst` is now implemented exactly this way (`FirstSuspended` is that settled carrier),
so this section is the hand-rolled version of the proto primitive. The four entries that pin behavior
the proto does not test anywhere (A.2.3, A.2.4, A.2.5, A.2.7) are the ones worth porting first.

All seven are PORTABLE. The section's shared fixture:

```scala
    // Answering only the first operation of a tag, with no handler kind of its own. The region's currency
    // carries both outcomes, so the clause's answer is an ordinary settled value of it, and a settled value
    // is what ends a region: the entry pops, and the continuation the clause kept still carries the effect
    // for someone else to answer.
    enum First derives CanEqual:
        case Done(value: Int)
        case Standing(cont: Arrow[Int, First, Ask])

    "answering only the first operation" - {

        def firstOf(v: Int < Ask): First < Any =
            ArrowEffect.handleCont(Tag[Ask], v.map(a => First.Done(a): First))(
                [C] => (_, cont) => First.Standing(cont),
                a => a
            )
```

### A.2.1 "answers the first and leaves the rest unhandled"

Overlaps the proto's `handleFirst` "answers the first operation and hands the raw remainder", but
pins the raw handleCont shape: the standing continuation escapes the region as data and is re-handled
by a fresh region.

```scala
        "answers the first and leaves the rest unhandled" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            Eval(firstOf(v)) match
                case First.Standing(cont) =>
                    // annotated: `cont(4)` with no expected type resolves to Arrow's two-argument apply,
                    // since a raw value inhabits the pending type's first arm
                    val rest: First < Ask = cont(4)
                    assert(Eval(ArrowEffect.handleCont(Tag[Ask], rest)([X] => (_, k) => k(2), a => a)) == First.Done(42))
                case other => fail(s"expected a standing operation, got $other")
            end match
        }
```

### A.2.2 "the clause may end the computation without resuming"

```scala
        "the clause may end the computation without resuming" in {
            var reached = false
            val v = ask.map { a =>
                reached = true
                a + 1
            }
            val r = ArrowEffect.handleCont(Tag[Ask], v.map(a => First.Done(a): First))([C] => (_, _) => First.Done(-1), a => a)
            assert(Eval(r) == First.Done(-1))
            assert(!reached)
        }
```

### A.2.3 "the continuation is resumable more than once"

Multi-shot on a continuation handed *out* of the region, each shot in its own eval, with `runs == 2`
proving the tail actually re-ran. No proto test does this for a first-operation continuation.

```scala
        "the continuation is resumable more than once" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            Eval(firstOf(v)) match
                case First.Standing(cont) =>
                    val one: First < Ask = cont(1)
                    val two: First < Ask = cont(2)
                    assert(Eval(ArrowEffect.handleCont(Tag[Ask], one)([X] => (_, k) => k(0), a => a)) == First.Done(10))
                    assert(Eval(ArrowEffect.handleCont(Tag[Ask], two)([X] => (_, k) => k(0), a => a)) == First.Done(20))
                    assert(runs == 2)
                case other => fail(s"expected a standing operation, got $other")
            end match
        }
```

### A.2.4 "an operation the clause raises re-enters the same region"

The routing law for a clause that answers at the region's own currency: its own-tag raise comes back
to the *same* region, not the one outside. Worth checking against the proto's answer routing before
asserting: `internal/EvalTest.scala` pins "an effectful answer's own-tag re-raise is answered by this
handler" for `handleLoop`, which is the consistent reading, but no proto test pins it for
`handleCont`.

```scala
        // the clause answers at the region's own currency, so an operation it raises comes back to the same
        // region rather than to the one outside. This is where the shape parts company with a handler whose
        // clause sits outside the region it serves, and a clause that raises its own tag unguarded never ends
        "an operation the clause raises re-enters the same region" in {
            var clauseRuns = 0
            val r: First < Any = ArrowEffect.handleCont(Tag[Ask], ask.map(a => First.Done(a): First))(
                [C] =>
                    (_, cont) =>
                        clauseRuns += 1
                        // the re-entry is dispatched here too, and what it receives is the continuation of
                        // the clause's own operation, so resuming it delivers the answer to `extra`
                        if clauseRuns == 1 then ask.map(extra => First.Done(extra * 10): First)
                        else cont(7)
                ,
                a => a
            )
            assert(Eval(r) == First.Done(70))
            assert(clauseRuns == 2)
        }
```

### A.2.5 "an operation raised after the region reaches the outer handler"

The complement of A.2.4: after the entry pops, a raise reaches whoever is installed outside.

```scala
        // which is why the effectful half of a first-operation clause belongs after the region: by then the
        // entry has popped, so the raise reaches whoever is installed outside
        "an operation raised after the region reaches the outer handler" in {
            var outer = 0
            val captured: First < Ask =
                firstOf(ask.map(_ + 1)).map:
                    case First.Standing(_) => ask.map(extra => First.Done(extra * 10): First)
                    case done              => done
            val out = ArrowEffect.handleCont(Tag[Ask], captured)(
                [X] =>
                    (_, k) =>
                        outer += 1
                        k(4)
                ,
                a => a
            )
            assert(Eval(out) == First.Done(40))
            assert(outer == 1)
        }
```

### A.2.6 "a settled body takes the done clause"

```scala
        "a settled body takes the done clause" in {
            assert(Eval(firstOf(41)) == First.Done(41))
        }
```

### A.2.7 "deep sequential operations are stack safe"

```scala
        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            Eval(firstOf(loop(100000))) match
                case First.Standing(cont) =>
                    val rest: First < Ask = cont(1)
                    assert(Eval(ArrowEffect.handleCont(Tag[Ask], rest)([X] => (_, k) => k(1), a => a)) == First.Done(0))
                case other => fail(s"expected a standing operation, got $other")
            end match
        }
```

## A.3 handleFirst (15 missing of 17)

The proto has 5 handleFirst tests; two of them cover old cases ("answers the first operation and
leaves the rest unhandled", "the clause may end the computation without resuming"). The old section's
local adapter ports verbatim because the two `handleFirst` signatures match:

```scala
        // adapts ArrowEffect.handleFirst's arrow-shaped remainder to a function shape
        def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](effectTag: Tag[E], v: A < (E & S))(
            handle: [X] => (I[X], O[X] => A < (E & S)) => B < (S & S2),
            done: A => B < (S & S2)
        ): B < (S & S2) =
            ArrowEffect.handleFirst[I, O, E, A, B, S, S2](effectTag, v)(
                handle = [X] => (input, cont) => handle[X](input, o => cont(o)),
                done = done
            )
```

### A.3.1 "the handler stays installed until the operation arrives after a foreign crossing"

The first-operation region survives a `Say` crossing and is still installed when `Ask` finally
arrives.

```scala
        "the handler stays installed until the operation arrives after a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val first                = handleFirst(Tag[Ask], v)([X] => (_, cont) => cont(41), identity)
            val sayHandled           = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], sayHandled)([X] => (_, cont) => cont(0), a => a)) == 42)
        }
```

### A.3.2 "the done clause runs when the computation settles without the operation"

Distinct from the proto's "a body that completes without the effect takes done", which passes a
*settled* input (`5: Int < Ask`) and therefore takes handleCont's settled short-circuit branch. This
one is pending on entry, so the region is really installed and then completes without ever seeing
`Ask`: the other code path entirely.

```scala
        "the done clause runs when the computation settles without the operation" in {
            val v: Int < (Ask & Say) = say("x").map(_ => 41)
            val first                = handleFirst(Tag[Ask], v)([X] => (_, _) => -1, _ + 1)
            val r                    = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }
```

### A.3.3 "the continuation re-enters the regions the operation was raised under"

The remainder re-enters the inner `Say` region, and that region exits exactly once.

```scala
        "the continuation re-enters the regions the operation was raised under" in {
            var exits                    = 0
            val inner: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val region = ArrowEffect.handleCont(Tag[Say], inner)([X] => (_, cont) => cont(()), a => a).map { a =>
                exits += 1
                a
            }
            val first = handleFirst(Tag[Ask], region)([X] => (_, cont) => cont(41), identity)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(0), a => a)) == 42)
            assert(exits == 1)
        }
```

### A.3.4 "the remainder is handed out as a value and re-handled after a foreign crossing"

The shape `Poll.runFirst` and `Emit.runFirst` are built on. Note the explicit type arguments; in the
proto the continuation type is `Arrow[Int, Int, Ask & Say]`, same as here.

```scala
        // the shape Poll.runFirst and Emit.runFirst are built on: the remainder leaves the region as a
        // value with the effect still in its row, and the caller re-handles it with a fresh region
        "the remainder is handed out as a value and re-handled after a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val first =
                ArrowEffect.handleFirst[Const[Unit], Const[Int], Ask, Int, Either[Int, Arrow[Int, Int, Ask & Say]], Say, Any](
                    Tag[Ask],
                    v
                )(
                    handle = [X] => (_, cont) => Right(cont),
                    done = a => Left(a)
                )
            val sayHandled = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            Eval(sayHandled) match
                case Right(rest) =>
                    val resumed  = ArrowEffect.handleCont(Tag[Ask], rest(41))([X] => (_, cont) => cont(0), a => a)
                    val finished = ArrowEffect.handleCont(Tag[Say], resumed)([X] => (_, cont) => cont(()), a => a)
                    assert(Eval(finished) == 42)
                case Left(a) => fail(s"expected the remainder, got $a")
            end match
        }
```

### A.3.5 "one effect at two type arguments crosses to the outer handler"

Needs the `Pick[+V]` declaration. Nothing in the proto test tree exercises one effect at two type
arguments told apart by tag subsumption on a covariant parameter, which is the shape the stream pipes
reach the kernel at.

```scala
        // the shape the stream pipes reach it at: one effect at two type arguments, told apart only by
        // tag subsumption, so the region for one of them must let the other pass to a handler further out
        "one effect at two type arguments crosses to the outer handler" in {
            val v: (Int, String) < (Pick[Int] & Pick[String]) =
                pick[String].map(s => pick[Int].map(i => (i, s)))
            val first = handleFirst(Tag[Pick[Int]], v)([X] => (_, cont) => cont(1), identity)
            val outer = ArrowEffect.handleCont(Tag[Pick[String]], first)([X] => (_, cont) => cont("a"), a => a)
            assert(Eval(outer) == (1, "a"))
        }
```

### A.3.6 "one effect at two type arguments hands out the remainder as a value"

```scala
        "one effect at two type arguments hands out the remainder as a value" in {
            val v: (Int, String) < (Pick[Int] & Pick[String]) =
                pick[String].map(s => pick[Int].map(i => (i, s)))
            val first =
                ArrowEffect.handleFirst[
                    Const[Unit],
                    Const[Int],
                    Pick[Int],
                    (Int, String),
                    Either[(Int, String), Arrow[Int, (Int, String), Pick[Int] & Pick[String]]],
                    Pick[String],
                    Any
                ](Tag[Pick[Int]], v)(
                    handle = [X] => (_, cont) => Right(cont),
                    done = a => Left(a)
                )
            val outer = ArrowEffect.handleCont(Tag[Pick[String]], first)([X] => (_, cont) => cont("a"), a => a)
            Eval(outer) match
                case Right(rest) =>
                    val resumed  = ArrowEffect.handleCont(Tag[Pick[Int]], rest(1))([X] => (_, cont) => cont(0), a => a)
                    val finished = ArrowEffect.handleCont(Tag[Pick[String]], resumed)([X] => (_, cont) => cont("b"), a => a)
                    assert(Eval(finished) == (1, "a"))
                case Left(a) => fail(s"expected the remainder, got $a")
            end match
        }
```

### A.3.7 "the continuation is handed out as a value and resumed later"

```scala
        "the continuation is handed out as a value and resumed later" in {
            val v = ask.map(a => ask.map(b => a * 10 + b))
            val r = handleFirst(Tag[Ask], v)(
                [X] => (_, cont) => (1, () => cont(4)),
                a => (0, () => (a: Int < Ask))
            )
            val (answered, rest) = Eval(r)
            assert(answered == 1)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], rest())([X] => (_, cont) => cont(2), a => a)) == 42)
        }
```

### A.3.8 "the continuation is multi-shot"

```scala
        "the continuation is multi-shot" in {
            var runs = 0
            val v = ask.map { a =>
                runs += 1
                a * 10
            }
            val first = handleFirst(Tag[Ask], v)(
                [X] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                identity
            )
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(0), a => a)) == 30)
            assert(runs == 2)
        }
```

### A.3.9 "an operation raised by the clause reaches the outer handler"

The handleFirst clause sits outside the region it serves, so its own-tag raise goes outward. The
counterpart of A.2.4, and the pair is the whole point.

```scala
        "an operation raised by the clause reaches the outer handler" in {
            var outerAnswered = 0
            val first = handleFirst(Tag[Ask], ask.map(_ + 1))(
                [X] => (_, cont) => ask.map(extra => cont(extra * 10)),
                identity
            )
            val r = ArrowEffect.handleCont(Tag[Ask], first)(
                [X] =>
                    (_, cont) =>
                        outerAnswered += 1
                        cont(4)
                ,
                a => a
            )
            assert(Eval(r) == 41)
            assert(outerAnswered == 1)
        }
```

### A.3.10 "a settled input applies the done clause strictly"

The proto's "a body that completes without the effect takes done" checks the value but not the
strictness. This one pins strictness through `evalNow`.

```scala
        "a settled input applies the done clause strictly" in {
            val v: Int < Ask = 41
            val r            = handleFirst(Tag[Ask], v)([X] => (_, cont) => cont(0), _ + 1)
            assert(r.evalNow == Maybe(42))
        }
```

### A.3.11 "the done clause may suspend"

```scala
        "the done clause may suspend" in {
            val v: Int < (Ask & Say) = say("x").map(_ => 41)
            val first                = handleFirst(Tag[Ask], v)([X] => (_, _) => -1, a => say("done").map(_ => a + 1))
            val r                    = ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == 42)
        }
```

### A.3.12 "maps chained after the region apply to both clauses"

```scala
        "maps chained after the region apply to both clauses" in {
            def firstOf(v: Int < (Ask & Say)) =
                handleFirst(Tag[Ask], v)([X] => (_, _) => 1, _ => 2).map(_ * 10).map(_ + 1)
            val answered = ArrowEffect.handleCont(Tag[Say], firstOf(say("x").map(_ => ask)))([X] => (_, cont) => cont(()), a => a)
            val settled  = ArrowEffect.handleCont(Tag[Say], firstOf(say("x").map(_ => 41)))([X] => (_, cont) => cont(()), a => a)
            assert(Eval(answered) == 11)
            assert(Eval(settled) == 21)
        }
```

### A.3.13 "the innermost handleFirst wins under nested same-tag handlers"

```scala
        "the innermost handleFirst wins under nested same-tag handlers" in {
            var outerAnswered = 0
            val inner         = handleFirst(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(10), identity)
            val outer = handleFirst(Tag[Ask], inner: Int < Ask)(
                [X] =>
                    (_, cont) =>
                        outerAnswered += 1
                        cont(100)
                ,
                identity
            )
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], outer)([X] => (_, cont) => cont(0), a => a)) == 11)
            assert(outerAnswered == 0)
        }
```

### A.3.14 "the done clause receives a computation held as a value unboxed"

Reference-identity pin on the lift discipline through `handleFirst`'s `done`.

```scala
        "the done clause receives a computation held as a value unboxed" in {
            val payload: Int < Say           = say("p").map(_ => 7)
            val v: (Int < Say) < (Ask & Say) = say("x").map(_ => box(payload))
            var seen: AnyRef                 = null
            val first = handleFirst(Tag[Ask], v)(
                [X] => (_, _) => box(payload),
                a =>
                    seen = a.asInstanceOf[AnyRef]
                    box(a)
            )
            val boxed = Eval(ArrowEffect.handleCont(Tag[Say], first)([X] => (_, cont) => cont(()), a => box(a)))
            assert(seen eq payload.asInstanceOf[AnyRef])
            assert(Eval(ArrowEffect.handleCont(Tag[Say], boxed)([X] => (_, cont) => cont(()), a => a)) == 7)
        }
```

### A.3.15 "deep sequential operations are stack safe"

```scala
        "deep sequential operations are stack safe" in {
            def loop(n: Int): Int < Ask =
                if n == 0 then 0 else ask.map(_ => loop(n - 1))
            val first = handleFirst(Tag[Ask], loop(100000))([X] => (_, cont) => cont(1), identity)
            assert(Eval(ArrowEffect.handleCont(Tag[Ask], first)([X] => (_, cont) => cont(1), a => a)) == 0)
        }
```

## A.4 dispatchFirst (3 missing of 9)

No proto `dispatchFirst` test asserts the *input value* delivered to the clause; all five count
invocations. A.4.1 and A.4.3 restore that assertion as well as the map-root walk.

### A.4.1 "reads the input of a mapped suspension through its root"

```scala
        "reads the input of a mapped suspension through its root" in {
            var seen = ""
            ArrowEffect.dispatchFirst(Tag[Say], say("root").map(_ => 1).map(_ + 1))([X] => input => seen = input)
            assert(seen == "root")
        }
```

### A.4.2 "reads through a deferral without running the body behind it"

The load-bearing one: `dispatchFirst` is called on a live fiber from another thread, so it must never
apply a deferral's body. In the proto, `Effect.defer(f)` builds a `Kyo.DeferWith` whose `value` is the
settled unit and whose body is in `apply`, and `dispatchFirst`'s walk peels `Kyo.Defer` by reading
`value`, so the assertion should hold; nothing pins it today.

```scala
        // A deferral is where a computation keeps what it has not done yet, and reaching the operation
        // behind one means reading that. Where the payload was handed in, which is what a `map` builds,
        // reading it costs nothing; where it is a body, reading it runs that body. One step, never more:
        // this stops at the operation the step arrives at, and applies no continuation.
        "reads through a deferral without running the body behind it" in {
            var seen  = ""
            var built = false
            val v = Effect.defer {
                built = true
                say("hidden").map(_ => 1)
            }
            ArrowEffect.dispatchFirst(Tag[Say], v)([X] => input => seen = input)
            // the body is the deferral's arrow, and nothing here applies it, so the operation it would
            // build does not exist yet and the walk has nothing to find. Not running it is the point:
            // this is called on a live fiber from another thread
            assert(!built)
            assert(seen == "")
        }
```

### A.4.3 "reads through the deferrals a map chain composes to the operation under them"

```scala
        "reads through the deferrals a map chain composes to the operation under them" in {
            var seen = ""
            val v    = say("shown").map(_ => 1).map(_ + 1)
            ArrowEffect.dispatchFirst(Tag[Say], v)([X] => input => seen = input)
            assert(seen == "shown")
        }
```

## A.5 handleCatching, re-spelled as the recovery clause (13 missing of 17), plus 2 loose top-level cases

The proto's "recover" section has 4 tests and covers two of the old 17. Everything in A.5.1 through
A.5.13 re-spells `handleCatching(tag, v)(handle)(recover)` as
`handleCont(tag, v)(handle, a => a, ex => Maybe(...))` (cheat-sheet item 3). Two old handleCatching
cases are DIVERGENT and appear in section B. A.5.14 and A.5.15 are the two remaining top-level old
tests, parked here rather than given a section of their own.

### A.5.1 "answers operations when nothing fails"

The recovery-equipped region still answers normally. All four proto recover tests throw; none pins
the no-failure path.

```scala
        "answers operations when nothing fails" in {
            val v = ask.map(a => ask.map(b => a + b))
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(21))(_ => -1)
            assert(Eval(r) == 42)
        }
```

### A.5.2 "recovers a throw in the handler"

A throw raised by the clause is recovered by the region's own recovery, even though the clause sits
outside the region it serves. Check this one against the proto before asserting: the proto's loop fast
path (`Handler.answersLoopState`) turns a clause throw into
`Loop.continue(at, deferInline(throw ex))`, re-raising it inside the region's continuation lane, which
is a different routing from the old kernel's. The old kernel's answer (recovered, because the recovery
is the handler entry) may or may not survive; the pin is worth having either way.

```scala
        "recovers a throw in the handler" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)(
                [X] => (_, _) => (throw new RuntimeException("boom")): Int < Ask
            )(_ => -1)
            assert(Eval(r) == -1)
        }
```

### A.5.3 "recovers a throw raised after a foreign crossing"

```scala
        "recovers a throw raised after a foreign crossing" in {
            val v: Int < (Ask & Say) = say("x").map(_ => ask).map(_ => (throw new RuntimeException("boom")): Int)
            val caught               = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(41))(_ => -1)
            val r                    = ArrowEffect.handleCont(Tag[Say], caught)([X] => (_, cont) => cont(()), a => a)
            assert(Eval(r) == -1)
        }
```

### A.5.4 "recovers a throw raised after an inner region's exit"

```scala
        "recovers a throw raised after an inner region's exit" in {
            val region       = ArrowEffect.handleCont(Tag[Say], say("x").map(_ => 1))([X] => (_, cont) => cont(()), a => a)
            val v: Int < Ask = ask.map(_ => region.map(_ => (throw new RuntimeException("boom")): Int))
            val r            = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(41))(_ => -1)
            assert(Eval(r) == -1)
        }
```

### A.5.5 "the recovered value is the region's result"

```scala
        "the recovered value is the region's result" in {
            val v = ask.map(_ => (throw new RuntimeException("boom")): Int)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(0))(_ => 21).map(_ * 2)
            assert(Eval(r) == 42)
        }
```

### A.5.6 "a throw after the region is not recovered"

The recovery's extent ends where the region does.

```scala
        "a throw after the region is not recovered" in {
            val r = ArrowEffect
                .handleCatching(Tag[Ask], ask.map(_ + 1))([X] => (_, cont) => cont(41))(_ => -1)
                .map(_ => (throw new RuntimeException("boom")): Int)
            intercept[RuntimeException] {
                val _ = Eval(r)
            }
        }
```

### A.5.7 "a throw inside a region nested in the computation is recovered"

Uses the without-`done` `handleLoop` overload for the inner region (see A.1.2).

```scala
        // a region is a value and nothing runs until the eval reaches it, so a nested region's throw
        // happens inside the recovering scope rather than at its own definition site
        "a throw inside a region nested in the computation is recovered" in {
            val region = ArrowEffect.handleLoop(Tag[Say], say("x").map(_ => (throw new RuntimeException("boom")): Int))(
                [X] => _ => Loop.continue(())
            )
            val v: Int < Ask = ask.map(_ => region)
            val r            = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(0))(_ => -1)
            assert(Eval(r) == -1)
        }
```

### A.5.8 "a fatal error in the computation is not recovered"

The proto's settled branch already filters with `NonFatal`; the pending path has no test.

```scala
        "a fatal error in the computation is not recovered" in {
            val v = ask.map(_ => (throw new InterruptedException("fatal")): Int)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(0))(_ => -1)
            intercept[InterruptedException] {
                val _ = Eval(r)
            }
        }
```

### A.5.9 "a fatal error in the handler is not recovered"

```scala
        "a fatal error in the handler is not recovered" in {
            val r = ArrowEffect.handleCatching(Tag[Ask], ask.map(_ + 1))(
                [X] => (_, _) => (throw new InterruptedException("fatal")): Int < Ask
            )(_ => -1)
            intercept[InterruptedException] {
                val _ = Eval(r)
            }
        }
```

### A.5.10 "an inner handler keeps answering its own operations"

```scala
        "an inner handler keeps answering its own operations" in {
            val inner: Int < (Ask & Say) = say("x").map(_ => ask.map(_ + 1))
            val sayHandled: Int < Ask    = ArrowEffect.handleCont(Tag[Say], inner)([X] => (_, cont) => cont(()), a => a)
            val r                        = ArrowEffect.handleCatching(Tag[Ask], sayHandled)([X] => (_, cont) => cont(41))(_ => -1)
            assert(Eval(r) == 42)
        }
```

### A.5.11 "the done clause takes the region's result"

```scala
        "the done clause takes the region's result" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)([X] => (_, cont) => cont(41), a => a * 2)(_ => -1)
            assert(Eval(r) == 84)
        }
```

### A.5.12 "the recovery answers at the region's row, so the done clause is not reached"

```scala
        "the recovery answers at the region's row, so the done clause is not reached" in {
            var doneRan = false
            val v       = ask.map(_ => (throw new RuntimeException("boom")): Int)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)(
                [X] => (_, cont) => cont(41),
                a =>
                    doneRan = true; a
            )(_ => -1)
            assert(Eval(r) == -1)
            assert(!doneRan)
        }
```

### A.5.13 "a computation held as a value passes through untouched"

```scala
        "a computation held as a value passes through untouched" in {
            val payload: Int < Any   = (1: Int < Any).map(_ + 1)
            val v: (Int < Any) < Ask = ask.map(_ => box(payload))
            val r: (Int < Any) < Any =
                ArrowEffect.handleCatching(Tag[Ask], v)([C] => (_, cont) => cont(0), a => box(a))(_ => box(-1))
            assert(Eval(Eval(r)) == 2)
        }
```

### A.5.14 "eval throws on an unhandled suspension"

Top-level in the old file. Already covered outside the proto `ArrowEffectTest` by
`internal/EvalTest.scala:859` "an unhandled operation is a bug"; port only if the ArrowEffect file
should carry it. The proto message is `"unhandled suspension"`, not `"Unexpected pending effect"`.

```scala
    "eval throws on an unhandled suspension" in {
        val ex = intercept[kyo.bug.KyoBugException] {
            Eval(ask.asInstanceOf[Int < Any])
        }
        assert(ex.getMessage.contains("Unexpected pending effect"))
    }
```

### A.5.15 "two effects interleaved at depth cross regions each step"

Top-level in the old file, last test. A 1000-deep interleave of two effects with a region crossing at
every step; no proto test drives both effects at depth.

```scala
    "two effects interleaved at depth cross regions each step" in {
        def loop(n: Int): Int < (Ask & Say) =
            if n == 0 then 0
            else ask.map(a => say(a.toString).map(_ => loop(n - 1).map(_ + a)))
        val handled = ArrowEffect.handleCont(Tag[Say], loop(1000))([C] => (_, cont) => cont(()), a => a)
        val r       = ArrowEffect.handleCont(Tag[Ask], handled)([C] => (_, cont) => cont(1), a => a)
        assert(Eval(r) == 1000)
    }
```

## A.6 "the answer fast path" (5 missing of 5)

The section's framing is old-kernel machinery (a per-stack `Handler.Out` cell); the proto's loop fast
path is `Handler.answersLoopState`, which returns an `Outcome2` instead of writing a shared cell. The
framing comment needs rewriting, but every assertion below is behavior and holds meaning against the
proto path.

```scala
    // The answer fast path hands the clause call to a method the call site generated and reports the
    // outcome through a per-stack cell. The cell is interpreter mutability, and the kernel's invariant is
    // that everything escaping the eval is a complete value, correct under multi-shot; these pins are the
    // hostile axes of that concession, not construction arguments.
    "the answer fast path" - {
```

### A.6.1 "a capture across the fast path is multi-shot, including across threads"

```scala
        "a capture across the fast path is multi-shot, including across threads" in {
            var kref: Arrow[Unit, Int, Any] = null
            val body: Int < (Ask & Say) =
                ask.map(a => ask.map(b => say("x").andThen(ask.map(c => a + b + c))))
            val region: Int < Say =
                ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
                    [C] => (n, _) => Loop.continue(n + 1, n),
                    (n, a) => n * 1000 + a
                )
            val r0 = Eval(ArrowEffect.handleCont(Tag[Say], region)(
                [C] =>
                    (_, cont) =>
                        kref = cont.asInstanceOf[Arrow[Unit, Int, Any]]
                        cont(())
                ,
                a => a
            ))
            assert(r0 == 3003)
            // two replays on this thread: each must run the captured region independently. The
            // cross-thread replay is pinned in the jvm-native ArrowEffectThreadingTest
            assert(Eval(kref(())) == 3003)
            assert(Eval(kref(())) == 3003)
        }
```

### A.6.2 "a clause keeps only the values it was given"

Pins that the clause receives the raw input, not a `Nested` wrapper (the tuple equality would fail),
and that a second eval does not disturb what the first stored.

```scala
        "a clause keeps only the values it was given" in {
            val seen = ListBuffer[(Int, Unit)]()
            def run(): Int =
                Eval(ArrowEffect.handleLoopState(Tag[Ask], 0, ask.map(a => ask.map(b => ask.map(c => a + b + c))))(
                    [C] =>
                        (n, i) =>
                            seen += ((n, i))
                            Loop.continue(n + 1, n)
                    ,
                    (n, a) => n * 1000 + a
                ))
            assert(run() == 3003)
            val snapshot = seen.toList
            // the raw inputs, not a boxed representation: a Nested wrapper would fail the equality
            assert(snapshot == List((0, ()), (1, ()), (2, ())))
            // a second eval must not disturb what the first clause stored: the arguments were plain
            // values, not aliases of live kernel state
            assert(run() == 3003)
            assert(seen.toList.take(3) == snapshot)
            assert(seen.toList.drop(3) == snapshot)
        }
```

Needs `import scala.collection.mutable.ListBuffer`.

### A.6.3 "a clause can run a full eval of its own mid-loop"

```scala
        "a clause can run a full eval of its own mid-loop" in {
            def innerRun(): Int =
                Eval(ArrowEffect.handleLoopState(Tag[Ask], 100, ask.map(a => ask.map(b => a + b)))(
                    [C] => (n, _) => Loop.continue(n + 1, n),
                    (n, a) => n + a
                ))
            val r: Int = Eval(ArrowEffect.handleLoopState(Tag[Ask], 0, ask.map(a => ask.map(b => a + b)))(
                [C] =>
                    (n, _) =>
                        val i = innerRun()
                        Loop.continue(n + 1, (n + i))
                ,
                (n, a) => n * 100000 + a
            ))
            // inner: answers 100 and 101, state 102, sum 201, done 102 + 201; outer answers 0 and 1
            // shifted by it
            assert(r == 200607)
        }
```

### A.6.4 "a throw after settled answers recovers with every commit already made"

`Effect.catching` here wraps the whole region from outside, so cheat-sheet item 4's `try eval(...)`
form applies.

```scala
        "a throw after settled answers recovers with every commit already made" in {
            val states = ListBuffer[Int]()
            case class Boom() extends RuntimeException
            def loop(i: Int): Int < Ask =
                if i > 5 then i else ask.map(a => if a == 2 then throw Boom() else loop(i + 1))
            val region: Int < Any =
                ArrowEffect.handleLoopState(Tag[Ask], 0, loop(0))(
                    [C] =>
                        (n, _) =>
                            states += n
                            Loop.continue(n + 1, n)
                    ,
                    (n, a) => a
                )
            val r = Eval(Effect.catching(region)(_ => -1))
            assert(r == -1)
            // the throw happened applying the continuation after the third answer: all three clause
            // runs, each with the state the previous commit produced, are visible
            assert(states.toList == List(0, 1, 2))
        }
```

### A.6.5 "a park taken mid answer loop resumes in a fresh full eval"

```scala
        "a park taken mid answer loop resumes in a fresh full eval" in {
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            val region: Int < Any =
                ArrowEffect.handleLoopState(Tag[Ask], 0, countdown(100))(
                    [C] =>
                        (n, _) =>
                            if n == 10 then internal.SafepointStop.request()
                            Loop.continue(n + 1, 1)
                    ,
                    (n, a) => n + a
                )
            val first = Eval.partial(region)
            assert(first.evalNow == Maybe.Absent)
            assert(Eval(first) == 100)
        }
```

## A.7 "the cont answer fast path" (5 missing of 6)

The proto has no cont-side fast path (`Handler` exposes `answersLoopState` only), so the section name
does not carry over; the behaviors exercise the proto's general cont dispatch and are all portable.
The first old test in the section is covered by the proto's "the captured continuation is multi-shot".

### A.7.1 "a mid-loop continuation applied twice replays the tail independently"

`count == 6` is the whole assertion: each replay re-enters the clause for the remaining suspensions.

```scala
        "a mid-loop continuation applied twice replays the tail independently" in {
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            var count = 0
            val r = Eval(ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] =>
                    (_, cont) =>
                        count += 1
                        if count == 2 then cont(1).map(x => cont(1).map(y => x + y))
                        else cont(1)
                ,
                a => a
            ))
            // the second answer's clause runs the tail twice; each replay re-enters the clause for
            // the remaining suspensions, so the region completes at 4 + 4 after six clause runs
            assert(r == 8)
            assert(count == 6)
        }
```

### A.7.2 "a hoarded fast-path continuation replays after the region finished"

Partially covered outside the file by `internal/EvalTest.scala:493` "resumes after its region
completed, in a fresh evaluation, each shot from capture-time state".

```scala
        "a hoarded fast-path continuation replays after the region finished" in {
            var kref: Arrow[Int, Int, Any] = null
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            val r0 = Eval(ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] =>
                    (_, cont) =>
                        kref = cont.asInstanceOf[Arrow[Int, Int, Any]]
                        cont(1)
                ,
                a => a
            ))
            assert(r0 == 4)
            // the last capture is the settled tail: a complete value, replayable twice, never a
            // view of the answers loop it was handed out from. The cross-thread replay is pinned
            // in the jvm-native ArrowEffectThreadingTest
            assert(Eval(kref(1)) == 4)
            assert(Eval(kref(1)) == 4)
        }
```

### A.7.3 "a clause can run a full eval of its own mid-loop" (cont side)

Partially covered outside the file by `internal/EvalTest.scala:528` "a shot evaluated inside the
clause leaves the region intact for the next".

```scala
        "a clause can run a full eval of its own mid-loop" in {
            def innerRun(): Int =
                Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(a => ask.map(b => a + b)))(
                    [C] => (_, cont) => cont(7),
                    a => a
                ))
            def loop(i: Int): Int < Ask =
                if i > 3 then i else ask.map(a => loop(i + a))
            val r = Eval(ArrowEffect.handleCont(Tag[Ask], loop(0))(
                [C] => (_, cont) => cont(innerRun() / 14),
                a => a
            ))
            assert(r == 4)
        }
```

### A.7.4 "a throw while applying the continuation recovers after the answers made"

`Effect.catching` is outside the region: `try eval(region) catch ...`.

```scala
        "a throw while applying the continuation recovers after the answers made" in {
            val seen = ListBuffer[Int]()
            case class Boom() extends RuntimeException
            def loop(i: Int): Int < Ask =
                if i > 5 then i else ask.map(a => if i == 2 then throw Boom() else loop(i + a))
            val region: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], loop(0))(
                    [C] =>
                        (_, cont) =>
                            seen += seen.size
                            cont(1)
                    ,
                    a => a
                )
            assert(Eval(Effect.catching(region)(_ => -1)) == -1)
            // three answers ran before the third continuation application threw
            assert(seen.toList == List(0, 1, 2))
        }
```

### A.7.5 "a park taken mid answer loop resumes in a fresh full eval" (cont side)

```scala
        "a park taken mid answer loop resumes in a fresh full eval" in {
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            var n = 0
            val region: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], countdown(100))(
                    [C] =>
                        (_, cont) =>
                            n += 1
                            if n == 10 then internal.SafepointStop.request()
                            cont(1)
                    ,
                    a => a
                )
            val first = Eval.partial(region)
            assert(first.evalNow == Maybe.Absent)
            assert(Eval(first) == 0)
            assert(n == 100)
        }
```

## A.8 "safety audit pins" (8 portable of 10; the other 2 are in section B)

### A.8.1 "a clause that suspends on the outer effect keeps its state lane across the shared cell"

Two nested stateful regions where the inner clause suspends on the outer effect: each lane must see
only its own state sequence. The comment's "one per-stack Out cell" is old machinery, but the lane
crossing is exactly what the proto's `answersLoopState` bail-and-rebuild path does, so the pin
transfers with a rewritten comment.

```scala
        "a clause that suspends on the outer effect keeps its state lane across the shared cell" in {
            // both regions are stateful and every dispatch travels through the one per-stack Out
            // cell; the inner clause suspends on the outer effect, so each inner dispatch bails
            // mid-flight, the outer dispatch commits its own state through the same cell, and the
            // inner region rebuilds from the clause outcome. Each lane must see exactly its own
            // sequence of states, never the other's
            var outerSaw        = List.empty[String]
            val body: Int < Ask = ask.map(a => ask.map(b => ask.map(c => a * 100 + b * 10 + c)))
            val askRegion: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], "s", body)(
                [C] => (s, _) => say(s).map(_ => Loop.continue(s + "+", s.length)),
                (s, a) => if s == "s+++" then a else -1000
            )
            val sayRegion: Int < Any = ArrowEffect.handleLoopState(Tag[Say], 0, askRegion)(
                [C] =>
                    (n, msg) =>
                        outerSaw = outerSaw :+ msg; Loop.continue(n + 1, ())
                ,
                (n, a) => n * 1000 + a
            )
            // inner answers are the state's length at each dispatch: 1, 2, 3; outer counts three
            assert(Eval(sayRegion) == 3123)
            assert(outerSaw == List("s", "s+", "s++"))
        }
```

### A.8.2 "a clause throw escapes the region on the fast and general cont paths"

The `Effect.catching` here is outside the handler, so `try eval(...)` works. Both arms matter: a bare
suspension and a mapped one take different dispatch paths.

```scala
        "a clause throw escapes the region on the fast and general cont paths" in {
            case class Boom() extends RuntimeException
            def run(body: Int < Ask): Int =
                Eval(Effect.catching(
                    ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a)
                )(_ => -2))
            // a bare suspension takes the pos gate; a mapped one takes the general dump path. The
            // clause's failure escapes to the exterior on both
            assert(run(ask) == -2)
            assert(run(ask.map(_ + 1)) == -2)
        }
```

### A.8.3 "a clause throw releases the interior brackets it escapes"

Release-yes, recover-no. Needs the proto bracket release signature (cheat-sheet item 7) and
`try eval(...)`. Partially covered outside the file by `EffectBracketTest.scala:227` "a clause that
throws after capturing releases the bracket with the failure", which covers the cont side only.

```scala
        "a clause throw releases the interior brackets it escapes" in {
            // release-yes, recover-no: the escape runs the interior releases with the failure while
            // passing over interior recoveries, on both the standing-interior path (loop general)
            // and the folded-interior path (cont general)
            def probe(handle: (Int < Ask) => Int < Any): (Int, Boolean) =
                var sawPanic = false
                val body: Int < Ask =
                    Effect.bracket[Int, Int, Ask](Effect.defer(1))((_, r) =>
                        r match
                            case Result.Panic(ex) => sawPanic = ex.getMessage == "clause-boom"
                            case _                => ()
                    )(_ => ask.map(_ + 1))
                val out = Eval(Effect.catching(handle(body))(_ => -2))
                (out, sawPanic)
            end probe
            def boom(): Nothing = throw new RuntimeException("clause-boom")
            val loopSide        = probe(b => ArrowEffect.handleLoop(Tag[Ask], b)([C] => _ => boom(), a => a))
            val contSide        = probe(b => ArrowEffect.handleCont(Tag[Ask], b)([C] => (_, _) => boom(), a => a))
            assert(loopSide == ((-2, true)))
            assert(contSide == ((-2, true)))
        }
```

### A.8.4 "a throwing release in a nested eval does not disarm the enclosing slice"

Deep internals pin: a nested full eval owes the armed bit back through its `finally`, and a boundary
drain that throws must not skip the restore. All the pieces exist in the proto (`Effect.bracket`,
`Eval.partial`, `Safepoint`), with cheat-sheet items 5 and 7.

```scala
        "a throwing release in a nested eval does not disarm the enclosing slice" in {
            // a nested full eval clears the slot's armed bit on entry (save installs a fresh
            // state) and owes it back through the restore in its finally; a boundary drain that
            // throws must not skip that restore. The disarm is observable through strict
            // construction: with the armed bit lost, a pending stop stops draining the budget,
            // so an eager chain runs past the stop instead of reifying at it. The body sits in
            // a deferred payload so the whole scenario runs inside the armed slice
            val inner: Int < Ask =
                Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release"))(_ => ask.map(_ + 1))
            val dropped: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], inner)([C] => (_, _) => -1, a => a)
            var built = 0
            val outer: Int < Any =
                Effect.defer {
                    try kyo.discard(Eval(dropped))
                    catch case _: IllegalStateException => ()
                    0
                }.map { z =>
                    var acc: Int < Any = z
                    var i              = 0
                    while i < 100 do
                        acc = acc.map { x =>
                            built += 1
                            if built == 50 then internal.SafepointStop.request()
                            x + 1
                        }
                        i += 1
                    end while
                    acc
                }
            val p = Eval.partial(outer)
            assert(p.evalNow.isEmpty)
            // the armed bit came back from the nested eval, so the stop reified construction
            // within a step or two of where it lodged
            assert(built >= 50 && built <= 52, s"built=$built")
            assert(Eval(p) == 100)
            assert(built == 100)
        }
```

### A.8.5 "a throwing release on the completing path leaves the caller's safepoint state intact"

Uses `Safepoint.get/enter/exit/save/restore`, all present on the proto `Safepoint`.

```scala
        "a throwing release on the completing path leaves the caller's safepoint state intact" in {
            // the clause drops the continuation, so the bracket's release is owed by the drain at
            // the eval's boundary; its throw must not skip the safepoint restore
            val v: Int < Ask =
                Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release"))(_ => ask.map(_ + 1))
            val dropped: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            val slot = internal.Safepoint.get()
            // put the slot in a state distinct from a fresh one, so a skipped restore is visible
            kyo.discard(internal.Safepoint.enter(slot))
            kyo.discard(internal.Safepoint.enter(slot))
            try
                val before = internal.Safepoint.save(slot)
                internal.Safepoint.restore(slot, before)
                intercept[IllegalStateException](kyo.discard(Eval(dropped)))
                val after = internal.Safepoint.save(slot)
                internal.Safepoint.restore(slot, after)
                assert(after.equals(before))
            finally
                internal.Safepoint.exit(slot)
                internal.Safepoint.exit(slot)
            end try
        }
```

### A.8.6 "a throwing deferred payload after an answer does not re-run the consumed continuation"

`Effect.catching` outside the region.

```scala
        "a throwing deferred payload after an answer does not re-run the consumed continuation" in {
            case class Boom() extends RuntimeException
            var contRuns = 0
            val body: Int < Ask =
                ask.map { a =>
                    contRuns += 1
                    Effect.defer((throw Boom()): Int).map(_ + a)
                }
            val region: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Loop.continue(1),
                a => a
            )
            assert(Eval(Effect.catching(region)(_ => -1)) == -1)
            // the answers loop consumed the continuation before the payload threw; whatever the
            // exception lane pushed back must be discarded by the unwind, never re-executed
            assert(contRuns == 1)
        }
```

### A.8.7 "a stop arriving during eager construction inside a slice reifies and parks the chain"

```scala
        "a stop arriving during eager construction inside a slice reifies and parks the chain" in {
            // a mapped step builds a long strict chain while the slice runs; a stop lodged midway
            // must make the remaining construction reify from that point and the slice park, with
            // the completed prefix preserved
            var built = 0
            val v: Int < Any =
                Effect.defer(0).map { z =>
                    var acc: Int < Any = z
                    var i              = 0
                    while i < 100 do
                        acc = acc.map { x =>
                            built += 1
                            if built == 50 then internal.SafepointStop.request()
                            x + 1
                        }
                        i += 1
                    end while
                    acc
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            // the fiftieth step lodged the stop; construction reified within a step or two of it
            assert(built >= 50 && built <= 52, s"built=$built")
            assert(Eval(p) == 100)
            assert(built == 100)
        }
```

### A.8.8 "a boxed answer crosses the answers loop unopened"

Needs the `AskBoxed` declaration. The reference-identity assertion (`got eq payload`) is the pin:
exactly the lift's one level is stripped. Related proto coverage exists on the *done* side
("a done payload that is a pending computation is delivered as data") and in `PendingTest.scala`
("a loop answer payload delivers unwrapped through a bare suspension", "an answer can be a
computation value"), but nothing asserts identity for a boxed answer through `Loop.continue`.

```scala
        "a boxed answer crosses the answers loop unopened" in {
            val payload: Int < Say = say("p").map(_ => 7)
            val region: (Int < Say) < Any =
                ArrowEffect.handleLoop(Tag[AskBoxed], askBoxed.map(v => box(v)))(
                    [C] => _ => Loop.continue(box(payload): (Int < Say) < Any),
                    a => box(a)
                )
            val got: Int < Say = Eval(region)
            // exactly the lift's one level is stripped: the region result IS the boxed payload
            assert(got.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef])
            val r = Eval(ArrowEffect.handleCont(Tag[Say], got)([C] => (_, cont) => cont(()), a => a))
            assert(r == 7)
        }
```

## A.9 "non-Const inputs and outputs" (3 missing of 3)

Nothing in the entire proto test tree declares an `ArrowEffect` at non-`Const` type constructors, so
the typed-dispatch lane where the input carries `C` and the clause computes an `O[C]` from it is
untested in the proto. `ArrowEffect.suspend` / `handleCont` / `handleLoopState` are generic in
`I[_]`/`O[_]` in the proto, so all three port directly.

```scala
    // the typed-dispatch lane where the operation's answer type is the existential: the input
    // carries C, the clause computes an O[C] from it, and nothing is Const
    "non-Const inputs and outputs" - {
        sealed trait CustomEffect extends ArrowEffect[List, Option]

        def customEffect(input: List[Int]): Option[Int] < CustomEffect =
            ArrowEffect.suspend[Int](Tag[CustomEffect], input)

        "suspend and handle" in {
            val effect = customEffect(List(1, 2, 3))
            val result = ArrowEffect.handleCont(Tag[CustomEffect], effect)(
                [C] => (input, cont) => cont(input.headOption),
                a => a
            )
            assert(Eval(result) == Some(1))
        }

        "chained effects" in {
            val effect =
                for
                    a <- customEffect(List(1, 2, 3))
                    b <- customEffect(List(4, 5, 6))
                yield (a, b)
            val result = ArrowEffect.handleCont(Tag[CustomEffect], effect)(
                [C] => (input, cont) => cont(input.headOption),
                a => a
            )
            assert(Eval(result) == (Some(1), Some(4)))
        }

        "handle with state" in {
            val effect =
                for
                    a <- customEffect(List(1, 2, 3))
                    b <- customEffect(List(4, 5, 6))
                yield (a, b)
            val result = ArrowEffect.handleLoopState(Tag[CustomEffect], 0, effect)(
                [C] => (state, input) => Loop.continue(state + 1, Some(input(state))),
                (_, a) => a
            )
            assert(Eval(result) == (Some(1), Some(5)))
        }
    }
```

---

# B. DIVERGENT (4)

### B.1 handleCatching "recovers a throw raised while the computation is built"

Machinery: old `handleCatching` takes its body **by name**
(`inline v: => A < (E & S)`, `ArrowEffect.scala:577`), so a throw raised while the argument
expression is forced happens inside the recovering scope. The proto's `handleCont`/`handleLoop*` take
`v: A < (E & S)` strictly, so the argument throws at the call site before any region exists and no
recovery clause can see it. Not portable without changing the proto's parameter mode.

```scala
        "recovers a throw raised while the computation is built" in {
            val r = ArrowEffect.handleCatching(Tag[Ask], (throw new RuntimeException("boom")): Int < Ask)(
                [X] => (_, cont) => cont(0)
            )(_ => -1)
            assert(Eval(r) == -1)
        }
```

### B.2 handleCatching "a throw in the done clause is not recovered"

Contradicted by the proto's design, and by a proto test that asserts the opposite. Old semantics: the
region pops its entry before `done` runs, so a `done` throw escapes the recovery. Proto: the settled
branch of `handleCont` with a recovery wraps `done` in `try/catch`
(`ArrowEffect.scala:120-122`), and `ArrowEffectTest.scala:1006` "a settled input's done throw reaches
the recovery clause" pins that a `done` throw **is** recovered. The pending-path behavior is untested
in the proto; this is a semantics decision for the port, not a mechanical translation.

```scala
        // the recovery is the handler entry, and a region that completes pops it before the done clause
        // runs
        "a throw in the done clause is not recovered" in {
            val v = ask.map(_ + 1)
            val r = ArrowEffect.handleCatching(Tag[Ask], v)(
                [X] => (_, cont) => cont(41),
                _ => (throw new RuntimeException("boom")): Int
            )(_ => -1)
            intercept[RuntimeException] {
                val _ = Eval(r)
            }
        }
```

### B.3 safety audit "a deferred payload that throws mid answer loop cannot commit another dispatch's state or continuation"

Machinery: `Effect.catching` used **inside** the computation, between the suspensions and both
handlers (`Effect.catching(body)(_ => -1)` where `body: Int < (Ask & Say)`). The proto has no
in-computation catching; recovery exists only as a region's install-time clause. A substitute is to
install an inner recovering region on a scratch effect tag at that point, which changes what the test
is pinning (a recovery *entry* interior to both regions, rather than a `Catching` frame). Worth
rebuilding deliberately: the assertion (a cross-typed state or a foreign continuation answering the
failure breaks it) is a real safety pin for the proto's shared dispatch path.

```scala
        "a deferred payload that throws mid answer loop cannot commit another dispatch's state or continuation" in {
            case class Boom() extends RuntimeException
            // Say's stateful region holds an Int state, Ask's holds a String state; the body forces
            // a deferred payload that throws, with a recovery inside both regions. Any cross-typed
            // state or a foreign continuation answering the failure breaks the assertions
            val body: Int < (Ask & Say) =
                say("s").map(_ => ask.map(a => Effect.defer((throw Boom()): Int).map(_ + a)))
            val recovered: Int < (Ask & Say) = Effect.catching(body)(_ => -1)
            val askRegion: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], "s0", recovered)(
                [C] => (s, _) => Loop.continue(s + "+", 1),
                (s, a) => if s == "s0+" then a else -100
            )
            val sayRegion: Int < Any = ArrowEffect.handleLoopState(Tag[Say], 100, askRegion)(
                [C] => (n, _) => Loop.continue(n + 1, ()),
                (n, a) => n * 1000 + a
            )
            assert(Eval(sayRegion) == 100999)
        }
```

### B.4 safety audit "a clause throw escapes the region on every dispatch path"

Machinery: `Effect.catching` inside the body is the *subject* of the test, not incidental. The pin is
the clause-scope law: a `Catching` standing between the suspension and the handler is passed over on
every dispatch path, because the clause is the handler's code and sits outside the region it serves.
With no in-computation catching in the proto, and with the proto's loop fast path re-raising a clause
throw as `Loop.continue(state, deferInline(throw ex))` inside the region's own continuation lane
(`Handler.scala:83-86` and `Handler.scala:171-175`), the old assertion may not even hold: the interior recovering region could
now see it. This one needs a semantics decision before it is rewritten.

```scala
        "a clause throw escapes the region on every dispatch path" in {
            case class Boom() extends RuntimeException
            // the clause-scope law: the clause is the handler's code, outside the region it serves,
            // so its throw is answerable only by scopes wrapping the handler call. A Catching
            // standing between the suspension and the handler is passed over on every dispatch path
            val body: Int < Ask = Effect.catching(ask.map(_ + 1))(_ => -1)
            def viaLoop: Int < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => throw Boom(), a => a)
            def viaCont: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a)
            assert(Eval(Effect.catching(viaLoop)(_ => -2)) == -2)
            assert(Eval(Effect.catching(viaCont)(_ => -2)) == -2)
        }
```

---

# C. Already covered in the proto file (100)

Names only, in old-file order.

**handleLoop (21/21):** answers every operation in place; Loop.done stops the region; done sees the
settled result; a settled input applies done strictly; deep sequential operations are stack safe; the
innermost region of a tag answers; a clause answers effectfully; a clause ends the region effectfully;
every clause suspension re-arms the region; a clause suspension resolves outside the region; a clause
answer survives its own deep evaluation; a clause suspends on its own effect per operation; a
suspended clause dispatch is multi-shot; a foreign operation crosses the region in place; an effectful
answer resolves through an outer handler; a clause may suspend before producing its outcome; done
climbs past an inner handler without running its remainder; handles nested per recursion step in
bounded stack; context effects arise from answering handlers; stays in force across a foreign crossing
captured by an outer handle; the innermost done wins under nested same-tag handlers.

**handle, proto "handleCont" (19/19):** answers with the continuation in hand; the captured
continuation is multi-shot; can end the computation without resuming; a settled input applies done
strictly; done applies to the settled result; deep sequential operations are stack safe; answers a
single operation; lazy: the handled computation is a value and answers at eval; a long map tower on a
pending suspension handles in bounded stack; stays in force across a foreign crossing with a trailing
transform; stays in force across a budget bounce with a trailing transform; handler state threads
through resumptions; a foreign operation passes through and keeps the handler attached; nested
handlers answer their own operations; a supertype handler leaves a subtype effect in the row (proto
uses `assertTypeError`, dropping the old expected-message check); a handler at a subtype effect
answers a computation typed at the supertype; a map chained after the region applies to the result; a
map chained after the region runs outside the scope; a map chained after a settled pass-through
applies strictly.

**handleLoopState (10/13):** threads state through operations; done observes the final state;
Loop.done bypasses done; a stateful clause answers effectfully; state survives a foreign crossing; a
settled input applies done strictly with the initial state; state composes with done; state survives
an inner handler's exit; done sees the final answer; done may be effectful.

**handleWith (4/4):** applies the continuation to the region result; applies the continuation to a
settled input; the continuation can suspend on an outer effect; deep sequential operations stay stack
safe through the continuation.

**handleLoopWith (3/3):** applies the continuation to the region result; applies the continuation to a
settled input; Loop.done flows through the continuation.

**handleLoopStateWith (2/2):** applies the continuation with the final state observed; applies the
continuation to a settled input.

**suspendWith (5/5):** suspends and continues in one node; deep recursion is stack safe; maps chain
onto the node; an effectful continuation suspends again; a long map tower on the node evaluates in
bounded stack.

**Top level (7):** a handle capture crossing an inner region; a crossed region resumes without
re-running its body; a crossed stateful region resumes with its in-flight state; each shot of a
multi-shot capture resumes from capture-time state; a map after the region applies to the result; an
unresumed handler skips trailing maps at any depth; state survives dumping above a live region.

**park (3/3):** a clause parks by returning and resumes by rewrapping the continuation (proto stashes
`Int => Int < Ask` instead of `Arrow`); a park preserves standing sibling regions; a union tag
subsumes both effects the way regions are found.

**handleFirst (2/17):** answers the first operation and leaves the rest unhandled (proto: "answers the
first operation and hands the raw remainder"); the clause may end the computation without resuming.

**dispatchFirst (6/9):** runs the clause on the standing operation (proto: "reports the first
operation through a region and a handed-in deferral", which does not assert the delivered input
value); peels a region node to reach the operation; peels a stateless and a stateful region node (both
kinds are one `Kyo.Handle` case in the proto walk); does nothing when the standing operation has
another tag; stops at a settled value; leaves the computation as it was.

**handleCatching (2/17):** recovers a throw in the computation (proto: "a region's recovery clause
answers a throw raised in its extent"); recovers a throw raised after a resumption (proto: "a stateful
region's recovery clause receives the live state, not the install-time state").

**contracts (6/6):** a clause raising a foreign effect is answered by the outer handler across the
region; a continuation is a value: invoking it twice runs the rest twice; a handler may run another
handle inside its answer; a computation held as a value passes through a handler untouched; a throw in
the handler surfaces at eval; a throw in a map surfaces at construction on the settled path.

**nested box (4/4):** a pending computation held as a value double-boxes and unboxes one level per
eval; mapping over a double-boxed computation sees the once-boxed value; a pending computation held as
a value crosses a handler boxed; a stateful handler passes a pending computation value through intact.

**Top level (1):** a handler stepping a rescue at the exact budget boundary floats it outward.

**coverage (4/4):** the innermost handle wins under nested same-tag handlers; settled inputs pass
through strictly (proto uses the two-clause overloads; see A.1.2); a map chained after a parked handler
runs after the handler completes; evaluation recovers after a thrown handler.

**the cont answer fast path (1/6):** a clause may apply its continuation twice in one answer (proto:
"the captured continuation is multi-shot" and contracts' "a continuation is a value").

---

# D. Proto tests with no old counterpart (context, not a gap)

Useful when deciding whether a port would duplicate: the proto file already adds hostile-payload
coverage the old file has no equivalent for. `handleLoop`: "a done payload that is itself a Continue2
still stops the region"; "a done payload of type Any holding a Continue2 still stops the region"; "a
done payload that is a pending computation is delivered as data"; "an effectful clause ending with a
pending payload delivers it as data"; "an unboxed computation payload runs as the region's result"; "a
crossing clause ending with a computation result runs it"; "a crossing clause ending with a boxed
payload keeps it as data". `handleCont`: "the operation clause receives the operation reified at its
own tag" (`handleContOperation`, no old counterpart). `recover`: "Absent declines and the failure
unwinds to the enclosing region" (the proto recovery is `Maybe`-typed; the old one is not).
`handleFirst`: "re-handling the remainder round by round sees every operation". `dispatchFirst`:
"queries in the dispatch direction"; "sees through a parked slice".
