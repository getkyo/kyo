# Test-port gap analysis: Effect, Isolate, EffectTrace, outsidekyo

Read-only comparison of four OLD kernel test files against their PROTO counterparts. Coverage, not names, is
what is compared: an old case counts as covered when some proto test pins the same behavior, whatever it is
called and wherever it lives.

## Counts

| Old file | leaves | portable-missing | divergent | already covered |
|---|---|---|---|---|
| `kyo/kernel/EffectTest.scala` | 95 | 53 | 19 | 23 |
| `kyo/kernel/IsolateTest.scala` | 49 | 8 | 3 | 38 |
| `kyo/kernel/internal/EffectTraceTest.scala` | 26 | 19 | 1 | 6 |
| `outsidekyo/KernelTest.scala` | 91 | 65 | 26 | 0 |
| **total** | **261** | **145** | **49** | **67** |

Proto counterparts consulted:

- `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/EffectBracketTest.scala` (41 leaves)
- `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/ArrowEffectTest.scala`
- `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala`
- `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/IsolateTest.scala` (49 leaves)
- `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EffectTraceTest.scala` (13 leaves)
- `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/ContextEffectTest.scala`

## Standing API deltas (apply to every port below)

These are the spelling and semantic differences that decide PORTABLE vs DIVERGENT throughout. Sources:
`kyo-kernel/shared/src/main/scala/kyo/proto/kernel/Effect.scala`,
`.../kyo/proto/kernel/ContextEffect.scala`, `.../kyo/proto/kernel/Isolate.scala`,
`.../kyo/proto/kernel/ArrowEffect.scala`.

| Old | Proto | Consequence |
|---|---|---|
| `Effect.catching(v)(recover)` | **does not exist** | every catching-shaped case is DIVERGENT unless it can be rewritten onto a region's `recover` clause |
| `Effect.bracket(acq)(release: A => Any < Any)(use)` | `Effect.bracket(acq)(release: (A, Maybe[Throwable]) => Unit)[B, S2](use)` | release is **not effectful** and does **not** see the success value |
| `Effect.bracket(acq)((a, r: Result[Any, B]) => ...)(use)` | outcome is `Maybe[Throwable]`, not `Result` | "release is told the result on completion" is not representable |
| `Finalizer.Spent` | `kyo.Closed` (raised by `Effect.bracket`'s `ContextHandler.reenter`, Effect.scala:55-57) | re-entry refusal ports 1:1 with a new exception type |
| `Finalizer.Abandoned` | caller-supplied signal: `Eval.release(v, ex)` (Eval.scala:26) | abandonment outcome is whatever the holder passes |
| `Eval.finalizeResources(v)` | `Eval.release(v, ex)` | same role |
| `Eval(v): A` | `Eval(v): A < S`, so tests use `Nested.unnest[A](Eval(v))` or the public `.eval` | mechanical |
| `SafepointStop.request()` | `Safepoint.get()` / `Safepoint.stop(Thread.currentThread())` / `Safepoint.deadline(now - 1)`, as `requestStop()` in EffectBracketTest:18-22 | mechanical |
| `ContextEffect.handle(tag, v)` / `(tag, ifUndef, ifDef)` | `ContextEffect.handleInheritable(tag, v)` / `(tag, ifUndef, ifDef)` | mechanical rename |
| `fork: A => Maybe[A] < S` (refusable, effectful) | `fork: A => A` (total, pure) | every "a binding refuses the crossing" case is DIVERGENT |
| `join: (held, forked) => A < S` (effectful) | `join: (parent, forked, child) => A` (pure, three-arg) | ports with an extra parameter |
| — | proto adds `done: A => Unit` and `release: (A, Throwable) => Unit` to `ContextEffect.handle` | no old case depends on them |
| `Loop.continue(v)` under a region | `Loop.continue((), v)` | mechanical |
| `Isolate[Remove, -Keep, -Restore]` | same variance | the old "Restore parameter (covariant)" heading is a stale label; assertions are identical |
| `kyo.Kyo.lift` + `kyo.Kyo.unit` + `kyo.Kyo.foreach`/… | `kyo.proto.Kyo.lift` **only** | `Kyo.unit` and every collection combinator are absent from the proto |
| `Isolate.internal.Contextual` public within `kyo` | `private[kernel]` | reachable from `kyo.proto.kernel` tests, not from `outsidekyo` |
| `Effect.deferInline`, `ArrowEffect.handleFirst`, `dispatchFirst` | `private[kyo]` in the proto | reachable from `kyo.proto.*` tests, not from `outsidekyo` |

`EffectTrace` itself ports cleanly: the proto builder is structurally identical to the old one
(`kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/EffectTrace.scala:98-214` vs
`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:183-...`): same `MaxFrames = 64`, same
element encoding (`className = s"$callee @ ${f.className}"`, `methodName = f.callerName`), same region element
(`tag.show` / `"handle"`), same `elements` / `dropped` / `getMessage` surface. Every old assertion about frame
shape is therefore expressible verbatim against the proto carrier.

---

# 1. `kyo-kernel/shared/src/test/scala/kyo/kernel/EffectTest.scala`

95 leaves: 53 portable-missing, 19 divergent, 23 already covered.

## 1.1 Already covered (names only)

Deferral:

- `defer delays evaluation until the eval` — covered only *incidentally*, by ArrowEffectTest
  "a crossing clause ending with a boxed payload keeps it as data" (asserts `evaluated == 0` before the boxed
  payload is evaluated, `== 1` after). No proto test names defer laziness directly.
- `defer suspends effects performed by its body` — EvalTest:588, ArrowEffectMaskTest:174 both run an effect
  raised from inside a `Effect.defer` body.
- `the deferral node / defers a pending value` — ArrowEffectTest:1091 `Effect.defer(ask.map(b => a + b), Arrow.id)`.

Bracket:

- `releases after the use completes, not at the boundary`
- `releases when the use throws, and the exception still propagates`
- `releases when the use suspends and the continuation is answered`
- `releases when a clause receives the continuation and never applies it`
- `a handleLoop clause that stops the computation still releases`
- `releases exactly once when the use completes and the eval then ends`
- `nested brackets release innermost first`
- `nested brackets both release when the inner use throws`
- `sequential brackets each release`
- `a continuation held past the end of the eval refuses to run again`
- `nested releases run innermost first on a failure`
- `abandoning nested parked brackets releases innermost first`
- `abandoning a park with no outstanding releases is a no-op` (EvalTest "release of a settled value or an
  obligation-free computation owes nothing")
- `abandoning a slice parked in the acquire window still releases`
- `an acquire that throws owes no release`
- `a release that throws on the completing path surfaces`
- `a release that throws does not stop the releases after it`
- `a release that throws while the eval is already failing is suppressed onto the original`
- `a resource a multi-shot clause shares is released at the first branch, and the second is refused`
- `a recovery outside the bracket runs after the release, which is told the failure`

## 1.2 DIVERGENT (19)

### 1.2.1 `Effect.catching` as a node kind (15)

Machinery: the old `Kyo.Catching` node (`kyo/kernel/Effect.scala:108-115`) and the `Recover` stack entry the
eval consults while unwinding. The proto has no such node; failure recovery is a region's `recover` clause on
`ArrowEffect.handleCont` / `handleLoop` / `handleLoopState`.

- `catching / match`
- `catching / no match`
- `catching / failure in map`
- `catching / multiple exception types`
- `catching / failure in a map after a region`
- `catching / failure in a map after a first region`
- `catching / failure in a map after a stateful region`
- `catching / failure after a stateful region reached through a continuation`
- `catching / catching catches past the budget rescue`
- `catching / catching catches past the budget inside a stateful region`
- `catching / catching does not reach into a boxed computation`
- `catching / catching guards a stateful region across a park`
- `catching / a stateful region threads state under catching`
- `defer with catching`
- `combining multiple effects` (a `for` over `Effect.defer`, `Effect.catching`, `Effect.defer`; the defer half
  is trivially covered, the catching leg is not representable)

Several of these test something the recover clause *does* have an analogue for (a throw crossing a region, a
throw past a budget rescue, a throw across a park). If the aim is to keep that coverage rather than the
`catching` surface, they should be re-authored against `recover`, not ported. The proto already has starts on
that in EffectTraceTest ("a recovery clause inspects the enriched exception", "a failure born in a recovery is
described from the regions under it") and EvalTest ("a recover that fails itself is the failure the enclosing
region sees").

### 1.2.2 Effectful release (2)

Machinery: old release type `A => Any < Any` / `(A, Result[Any, B]) => Any < Any`. Proto release is
`(A, Maybe[Throwable]) => Unit`.

- `the release itself may be a deferred computation` — body wraps the release in `Effect.defer`.
- `a release may itself bracket` — the release opens a nested `Effect.bracket` and asserts the inner extent
  ends inside the outer release.

### 1.2.3 `Result`-shaped outcome (1)

- `the release receives the outcome: the result on completion, the failure on a throw` — asserts
  `outcomes == List(Result.succeed(42), Result.panic(boom))`. The failure/absent *discrimination* is covered by
  proto "releases with Absent on completion, after use" and "releases with the failure when the use throws";
  the **success payload** (`Result.succeed(42)`) has no proto representation, since the proto outcome is
  `Maybe[Throwable]`.

### 1.2.4 Deliberately different abandonment semantics (1)

- `abandoning a parked bracket releases with the abandoned outcome, and a later resume is harmless` — the
  first half is covered by proto "releases when a parked remainder is abandoned". The second half asserts
  `Eval(p) == 42` *after* abandonment (a later resume succeeds). The proto refuses it:
  "a stop landing as the acquire settles still installs the region" asserts
  `intercept[kyo.Closed](eval(parked))` after `Eval.release`. This is a semantic decision, not a gap; porting
  the old assertion would contradict the proto.

## 1.3 PORTABLE-MISSING (53)

### 1.3.1 Deferral (7)

**`defer composes with maps without running early`** — a `Effect.defer` composed under `.map` still does not run
until the eval.

```scala
var ran = false
val d: Int < Any = Effect.defer {
    ran = true
    1
}
val r = d.map(_ + 1)
assert(!ran)
assert(Eval(r) == 2)
assert(ran)
```

**`deferInline delays evaluation until the eval`** — the inline variant is a separate entry point
(`Effect.deferInline`, proto Effect.scala:111, `private[kyo]` so reachable from `kyo.proto.*` tests). No proto
test calls it directly.

```scala
var ran = false
val d: Int < Any = Effect.deferInline {
    ran = true
    7
}
assert(!ran)
assert(Eval(d.map(_ * 6)) == 42)
assert(ran)
```

**`defer evaluates once per eval of a fresh value`** — a `def`-shaped deferral runs its body once per eval.

```scala
var runs = 0
def d: Int < Any = Effect.defer {
    runs += 1
    runs
}
assert(Eval(d) == 1)
assert(Eval(d) == 2)
```

**`nested defer calls run innermost last, in order`** — proto EvalTest:624 nests two deferrals but asserts
nothing about order.

```scala
var order = List.empty[Int]
val d: Int < Any = Effect.defer {
    order = 1 :: order
    Effect.defer {
        order = 2 :: order
        Effect.defer {
            order = 3 :: order
            42
        }
    }
}
assert(Eval(d) == 42)
assert(order == List(3, 2, 1))
```

**`the deferral node / runs the value into its continuation`** — the arrow-taking `Effect.defer(v, cont)`
overload with a non-identity continuation. Proto only ever passes `Arrow.id` (ArrowEffectTest:1057, :1091).
The fixture `inc` is defined at EffectTest.scala:97-100 and is needed by the next two cases as well:

```scala
def inc(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
    new Arrow.Transform[Int, Int, Any]:
        def frame                                              = _frame
        def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i + 1))
```

```scala
assert(Eval(Effect.defer(1: Int < Any, inc)) == 2)
```

**`the deferral node / runs both continuations in order`** — the three-arg `Effect.defer(v, cont1, cont2)`
overload (proto Effect.scala:82-93). Untested in the proto.

```scala
def double(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
    new Arrow.Transform[Int, Int, Any]:
        def frame                                              = _frame
        def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i * 2))
assert(Eval(Effect.defer(1: Int < Any, inc, double)) == 4)
assert(Eval(Effect.defer(1: Int < Any, double, inc)) == 3)
```

**`the deferral node / collapses an identity second continuation into the one-continuation node`** — pins the
Id-collapse branches, which the proto also has (Effect.scala:83-92 for the three-arg form, :95-103 for the
four-arg form). Both proto forms are entirely untested.

```scala
val node = Effect.defer(1: Int < Any, inc, Arrow.id[Int])
node match
    case d: Kyo.Defer[?, ?, ?, ?] => assert(d.contB eq Arrow.id[Int])
    case other                    => fail(s"expected a deferral node, got $other")
assert(Eval(node) == 2)
```

> Note beyond the old file: the proto's **four-argument** `Effect.defer(v, cont1, cont2, cont3)`
> (Effect.scala:95-103) has no old-test ancestor and no proto test at all.

### 1.3.2 Bracket: clause-stop and discard paths (5)

**`a handleLoopState clause that stops the computation still releases`** — proto covers the `handleLoop`
`Loop.done` stop only.

```scala
var released = Maybe.empty[Int]
val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(r => ask.map(_ + r))
val stopped  = ArrowEffect.handleLoopState(Tag[Ask], 0, v)([C] => (_, _) => Loop.done(-1), (_, a) => a)
assert(Eval(stopped) == -1)
assert(released == Maybe(1))
```

**`a stateful clause that stops after advancing still releases`**

```scala
var released = Maybe.empty[Int]
val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(r => ask.map(a => ask.map(b => a + b + r)))
val stopped =
    ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
        [C] => (s, _) => if s == 1 then Loop.done(-1) else Loop.continue(s + 1, 1: Int < Any),
        (_, a) => a
    )
assert(Eval(stopped) == -1)
assert(released == Maybe(1))
```

**`every outstanding bracket releases when a clause stops the computation`** — nested brackets on the
clause-stop path. The proto only pins nested release order on the *unwind* and *abandonment* paths.

```scala
var released = List.empty[String]
val v =
    Effect.bracket(Effect.defer("outer"))(r => released :+= r) { _ =>
        Effect.bracket(Effect.defer("inner"))(r => released :+= r) { _ =>
            ask.map(_ + 1)
        }
    }
val stopped = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a)
assert(Eval(stopped) == -1)
assert(released == List("inner", "outer"))
```

**`a discarded continuation releases every outstanding bracket`** — nested brackets on the discard path.

```scala
var released = List.empty[String]
val v =
    Effect.bracket(Effect.defer("outer"))(r => released :+= r) { _ =>
        Effect.bracket(Effect.defer("inner"))(r => released :+= r) { _ =>
            ask.map(_ + 1)
        }
    }
val dropped = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
assert(Eval(dropped) == -1)
assert(released == List("inner", "outer"))
```

**`does not release when the acquire never completes`** — an acquire that suspends and is never answered owes
nothing. Proto has the *park*-in-acquire case ("the acquire is not guarded before it settles") and the
*throwing* acquire ("a failing acquire never releases"), but not the dropped-continuation acquire.

```scala
var released = false
val v =
    Effect.bracket(ask.map(_ => 1))(_ => released = true)(r => r + 1)
// the region answers nothing, so the acquire never settles and the bracket arrow is never reached
val never = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
assert(Eval(never) == -1)
assert(!released)
```

### 1.3.3 Bracket: parks, budget, and stack safety (8)

**`an acquire resumed twice owes a release for each resume`** — the suspension is in the *acquire*, so each
branch acquires its own resource and each owes its own release. Proto only has the suspension-in-*use*
multi-shot case (which is refused).

```scala
var released = List.empty[Int]
val v        = Effect.bracket(ask)(r => released :+= r)(r => r * 10)
val r =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
        a => a
    )
assert(Eval(r) == 30)
assert(released == List(1, 2))
```

**`a bracket spanning a budget park releases once`** — the park comes from the automatic budget rescue, not
from a requested stop. Proto's "a bracket held across two parks releases once on completion" drives the parks
with `requestStop()`, a different code path.

```scala
var count = 0
def chain(n: Int, v: Int < Any): Int < Any =
    if n == 0 then v else chain(n - 1, v.map(_ + 1))
val v = Effect.bracket(Effect.defer(0))(_ => count += 1)(r => chain(1000, r))
assert(Eval(v) == 1000)
assert(count == 1)
```

**`a slice stopped inside a bracket releases once, at the end`** — a stop in the acquire *and* a stop in the
use, resumed to completion in a loop, with `released == 0` asserted at every intermediate park. Proto's halves
("a stop landing as the acquire settles still installs the region" abandons rather than resumes; "a resumed
parked bracket completes and releases with Absent" parks only in the use) do not compose into this.

```scala
var released = 0
val v: Int < Any =
    Effect.bracket(Effect.defer {
        SafepointStop.request()
        1
    })(_ => released += 1)(r =>
        Effect.defer {
            SafepointStop.request()
            r + 1
        }.map(_ + 1)
    )

@tailrec def run(v: Int < Any, steps: Int): (Int, Int) =
    v.evalNow match
        case Present(a) => (a, steps)
        case Absent =>
            assert(released == 0)
            assert(steps < 100)
            run(Eval.partial(v), steps + 1)

val (result, steps) = run(v, 0)
assert(result == 3)
assert(steps > 1)
assert(released == 1)
```

**`deeply nested brackets release in bounded stack`** — the proto has **no** stack-safety test for brackets at
all.

```scala
var count = 0
def nest(n: Int): Int < Any =
    if n == 0 then 0
    else Effect.bracket(Effect.defer(n))(_ => count += 1)(_ => nest(n - 1))
assert(Eval(nest(1000)) == 0)
assert(count == 1000)
```

**`many sequential brackets each release`**

```scala
var count = 0
def loop(n: Int, acc: Int < Any): Int < Any =
    if n == 0 then acc
    else loop(n - 1, acc.map(a => Effect.bracket(Effect.defer(1))(_ => count += 1)(r => a + r)))
assert(Eval(loop(1000, 0: Int < Any)) == 1000)
assert(count == 1000)
```

**`a use that fails after a resume still releases once with the failure`** — park, resume, then throw. Proto
pins park+resume+success and no-park+throw, never the two together.

```scala
var released = 0
var out: Any = null
val boom     = new RuntimeException("late")
val v: Int < Any =
    Effect.bracket(Effect.defer(1)) { (_, r: Result[Any, Int]) =>
        released += 1
        out = r
    } { r =>
        Effect.defer {
            SafepointStop.request()
            r
        }.map(x => if x == 1 then throw boom else x)
    }
val p = Eval.partial(v)
assert(p.evalNow.isEmpty)
assert(released == 0)
assert(intercept[RuntimeException](Eval(p)) eq boom)
assert(released == 1)
assert(out.equals(Result.panic(boom)))
```

(The `out` assertion needs the outcome delta: in the proto it becomes `seen.exists(_.exists(_ eq boom))`.)

**`a park inside nested brackets carries both releases`** — nested brackets parked and then **resumed** to
completion. Proto's "two stacked brackets abandoned together release innermost first" abandons instead.

```scala
var released = List.empty[String]
val v: Int < Any =
    Effect.bracket(Effect.defer(1))(_ => released :+= "outer") { a =>
        Effect.bracket(Effect.defer(2))(_ => released :+= "inner") { b =>
            Effect.defer {
                SafepointStop.request()
                a + b
            }.map(_ + 39)
        }
    }
val p = Eval.partial(v)
assert(p.evalNow.isEmpty)
assert(released.isEmpty)
assert(Eval(p) == 42)
assert(released == List("inner", "outer"))
```

**`a park evaluated twice releases its resource once`** — the run-once guard is the finalizer's own, not a
replay restriction.

```scala
var released = 0
val v: Int < Any =
    Effect.bracket(Effect.defer(1))(_ => released += 1)(r =>
        Effect.defer {
            SafepointStop.request()
            r
        }.map(_ + 41)
    )
val p = Eval.partial(v)
assert(p.evalNow.isEmpty)
assert(Eval(p) == 42)
assert(Eval(p) == 42)
// one acquisition happened before the park, so both replays share the resource and the
// release runs once: run-once is the finalizer's own guard, not a replay restriction
assert(released == 1)
```

### 1.3.4 Bracket: nesting and composition (3)

**`a bracket inside a nested eval releases at that eval's boundary`**

```scala
var events = List.empty[String]
val inner  = Effect.bracket(Effect.defer(1))(_ => events :+= "inner release")(r => r + 1)
val outer =
    Effect.bracket(Effect.defer(2))(_ => events :+= "outer release") { r =>
        // bound first: `events :+= s"...${Eval(inner)}"` reads `events` before running the inner
        // eval, so the append would overwrite what the inner release recorded
        val got = Eval(inner)
        events :+= s"inner = $got"
        r
    }
assert(Eval(outer) == 2)
assert(events == List("inner release", "inner = 2", "outer release"))
```

**`an acquire that is itself a bracket releases both`**

```scala
var released = List.empty[String]
val acquire  = Effect.bracket(Effect.defer("a"))(r => released :+= r)(r => r + "!")
val v        = Effect.bracket(acquire)(r => released :+= r)(r => r.length)
assert(Eval(v) == 2)
assert(released == List("a", "a!"))
```

**`nested brackets separated by a handler still release innermost first on a failure`** — a region stands
between the two brackets.

```scala
var order = List.empty[String]
val boom  = new RuntimeException("boom")
val v: Int < Any =
    Effect.bracket(Effect.defer(1))(_ => order :+= "outer") { _ =>
        answerAsk(1) {
            Effect.bracket(Effect.defer(2))(_ => order :+= "inner") { i =>
                Effect.defer(i).map(_ => (throw boom): Int)
            }
        }
    }
assert(intercept[RuntimeException](Eval(v)) eq boom)
assert(order == List("inner", "outer"))
```

### 1.3.5 Bracket: acquire and release failure edges (7)

**`a release that throws during unwind does not lose the failure or the recovery`** — the suppression half is
covered by proto "a release failure on the unwind is suppressed onto the failure"; what is missing is that the
**recovery below** still runs and observes both. Rewrite `Effect.catching` as a `recover` clause.

```scala
var seen       = List.empty[String]
var suppressed = List.empty[String]
val v: Int < Any = Effect.catching {
    Effect.bracket(Effect.defer(1))((_, _: Result[Any, Int]) => throw new IllegalStateException("release")) { _ =>
        (throw new UnsupportedOperationException("body")): Int
    }
} { ex =>
    seen :+= ex.getMessage
    suppressed = ex.getSuppressed.toList.map(_.getMessage)
    -1
}
assert(Eval(v) == -1)
assert(seen == List("body"))
assert(suppressed == List("release"))
```

**`a recovery that throws surfaces its own failure with the original suppressed`** — and the release outcome is
the recovery's failure, not the original.

```scala
val original     = new UnsupportedOperationException("body")
val fromRecovery = new IllegalStateException("recovery")
var out: Any     = null
val v: Int < Any = Effect.bracket(Effect.defer(1))((_, r: Result[Any, Int]) => out = r) { _ =>
    Effect.catching((throw original): Int)(_ => throw fromRecovery)
}
val ex = intercept[IllegalStateException](Eval(v))
assert(ex eq fromRecovery)
assert(ex.getSuppressed.exists(_ eq original))
assert(out.equals(Result.panic(fromRecovery)))
```

**`an effectful acquire whose handler fails owes no release`** — the acquire suspends and the clause throws, so
the acquire never settles.

```scala
var released = 0
val boom     = new RuntimeException("clause")
val v: Int < TestEffect1 =
    Effect.bracket(testEffect1(1))(_ => released += 1)(r => r.length)
val handled: Int < Any =
    ArrowEffect.handleCont(Tag[TestEffect1], v)([C] => (_, _) => throw boom, a => a)
assert(intercept[RuntimeException](Eval(handled)) eq boom)
assert(released == 0)
```

**`an interior recovery turns the release outcome into the recovered success`**

```scala
var out: Any = null
val v: Int < Any =
    Effect.bracket(Effect.defer(1))((_, r: Result[Any, Int]) => out = r) { a =>
        Effect.catching((throw new RuntimeException("use")): Int)(_ => a + 41)
    }
assert(Eval(v) == 42)
assert(out.equals(Result.succeed(42)))
```

(The `Result.succeed(42)` assertion becomes `seen == Maybe(Maybe.empty)` in the proto; the *behavior* being
pinned — an interior recovery makes the extent end successfully — is what matters.)

**`a failing use with an effectful acquire still releases with the failure`** — the acquire suspends and is
answered; then the use throws. Proto's "a bracket failing after a crossing still releases before the recovery"
has a *settled* acquire and the crossing in the use.

```scala
var out: Any = null
val boom     = new RuntimeException("use")
val v: Int < TestEffect1 =
    Effect.bracket(testEffect1(10))((_, r: Result[Any, Int]) => out = r) { a =>
        if a == "10" then throw boom else a.length
    }
val handled: Int < Any =
    ArrowEffect.handleCont(Tag[TestEffect1], v)([C] => (input, cont) => cont(input.toString))
assert(intercept[RuntimeException](Eval(handled)) eq boom)
assert(out.equals(Result.panic(boom)))
```

**`a release that throws during abandonment does not silence the others`** — nested brackets, abandonment,
inner release throws, outer still attempted. Proto has the two-level *unwind* version and the single-bracket
abandonment version, never both.

```scala
var order = List.empty[String]
val v: Int < Any =
    Effect.bracket(Effect.defer(1))(_ => order :+= "outer") { a =>
        Effect.bracket(Effect.defer(2)) { _ =>
            order :+= "inner"
            throw new IllegalStateException("inner-release")
        } { b =>
            Effect.defer {
                SafepointStop.request()
                a + b
            }.map(_ + 39)
        }
    }
val p = Eval.partial(v)
assert(p.evalNow.isEmpty)
// whatever propagates out of the abandonment, every release must have been attempted
try Eval.finalizeResources(p)
catch case _: IllegalStateException => ()
assert(order == List("inner", "outer"))
```

**`every release runs even when several throw`** — three levels, two throwing. Proto has only the two-level
version.

```scala
var released = List.empty[String]
def level(name: String, failing: Boolean)(inner: Int < Ask): Int < Ask =
    Effect.bracket(Effect.defer(name))(r =>
        if failing then throw new IllegalStateException(s"$r release")
        else released :+= r
    )(_ => inner)
val v       = level("a", false)(level("b", true)(level("c", true)(ask.map(_ + 1))))
val stopped = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
val caught =
    try
        discard(Eval(stopped))
        Maybe.empty[(String, List[String])]
    catch
        case ex: Throwable =>
            Maybe((ex.getMessage, ex.getSuppressed.toList.map(_.getMessage)))
// innermost first, so c throws, b is suppressed onto it, and a still releases
assert(caught == Maybe(("c release", List("b release"))))
assert(released == List("a"))
```

### 1.3.6 Bracket: trivial resource-plumbing cases (2)

**`a resource the use hands back is still released`**

```scala
var released = false
val v        = Effect.bracket(Effect.defer("res"))(_ => released = true)(r => r)
assert(Eval(v) == "res")
assert(released)
```

**`a use that ignores the resource still releases it`**

```scala
var released = Maybe.empty[Int]
val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(_ => "done")
assert(Eval(v) == "done")
assert(released == Maybe(1))
```

### 1.3.7 Bracket vs regions (5)

**`a bracket interleaved with a region releases after the region completes`**

```scala
var events = List.empty[String]
val v =
    Effect.bracket(Effect.defer(1))(r => events :+= s"release $r") { r =>
        answerAsk(41)(ask.map { a =>
            events :+= "region answered"
            a + r
        })
    }
assert(Eval(v) == 42)
assert(events == List("region answered", "release 1"))
```

**`a region installed inside the use does not intercept the release`**

```scala
var events = List.empty[String]
val v =
    Effect.bracket(Effect.defer(1))(r => events :+= s"release $r") { r =>
        ArrowEffect.handleCont(Tag[Ask], ask.map(_ + r))(
            [C] =>
                (_, _) =>
                    events :+= "clause stopped"
                    -1
            ,
            a => a
        )
    }
assert(Eval(v) == -1)
assert(events == List("clause stopped", "release 1"))
```

**`a discarded continuation releases when its region completes, before the handler's continuation`** — the
*ordering* claim: the release lands before anything below the handler observes the completion. Proto's
"a discarded captured continuation still releases the bracket" asserts the release happened, not when.

```scala
var events             = List.empty[String]
val acquire: Int < Ask = Effect.defer { events :+= "acquire"; 1 }
val v                  = Effect.bracket(acquire)(r => events :+= s"release $r")(r => ask.map(_ + r))
val dropped            = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
val after =
    dropped.map { a =>
        events :+= s"after $a"
        a
    }
assert(Eval(after) == -1)
assert(events == List("acquire", "release 1", "after -1"))
```

**`a bracket a clause opens around the continuation releases when the body result settles`** — a bracket opened
*inside a clause*, around the captured continuation; it ends before `done` runs.

```scala
var events       = List.empty[String]
val v: Int < Ask = ask.map(_ + 1)
val handled =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] =>
            (_, cont) =>
                Effect.bracket(Effect.defer { events :+= "clause acquire"; 41 })(_ => events :+= "clause release")(r =>
                    cont(r)
            ),
        a =>
            events :+= s"done $a"
            a
    )
assert(Eval(handled) == 42)
assert(events == List("clause acquire", "clause release", "done 42"))
```

**`a bracket outside two regions releases at its own extent, not when an inner region discards`**

```scala
var events = List.empty[String]
val v: Int < Any =
    Effect.bracket(Effect.defer { events :+= "acquire"; 1 })(r => events :+= s"release $r") { r =>
        ArrowEffect.handleCont(Tag[Ask], ask.map(_ + r))([C] => (_, _) => -1, a => a).map { a =>
            events :+= s"inner done $a"
            a + 100
        }
    }
assert(Eval(v) == 99)
assert(events == List("acquire", "inner done -1", "release 1"))
```

### 1.3.8 Bracket: multi-shot clauses (9)

Proto has exactly one multi-shot bracket test ("a multi-shot capture over a bracket refuses the second shot").
The nine below are the surrounding law: *what* the refusal guarantees, *where* it lands, and the shapes that
must NOT be refused.

**`a multi-shot clause that acquires per branch releases each where its branch ends`** — the positive case:
suspension in the acquire, so each branch owns a resource.

```scala
var events = List.empty[String]
val v =
    Effect.bracket(ask)(r => events :+= s"release $r") { r =>
        events :+= s"use $r"
        r * 10
    }
val thrice =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] => (_, cont) => cont(1).map(a => cont(2).map(b => cont(3).map(c => a + b + c))),
        a => a
    )
assert(Eval(thrice) == 60)
assert(events == List("use 1", "release 1", "use 2", "release 2", "use 3", "release 3"))
```

**`a later branch is refused before it can run, throwing branch included`** — the refusal lands at the scope
entry, before the branch body.

```scala
var events             = List.empty[String]
val acquire: Int < Ask = Effect.defer { events :+= "acquire"; 1 }
val boom               = new RuntimeException("boom")
val v =
    Effect.bracket(acquire)(r => events :+= s"release $r") { r =>
        ask.map { a =>
            if a < 0 then throw boom else a + r
        }
    }
val twice =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] => (_, cont) => cont(10).map(a => cont(-1).map(b => a + b)),
        a => a
    )
val failure = intercept[Finalizer.Spent](Eval(twice))
assert(!failure.getSuppressed.contains(boom))
assert(events == List("acquire", "release 1"))
```

**`no branch of a multi-shot clause reads a resource that was already released`** — the safety property the
refusal stands for.

```scala
var closed             = false
var seen               = List.empty[String]
val acquire: Int < Ask = Effect.defer(1)
val v =
    Effect.bracket(acquire)(_ => closed = true) { r =>
        ask.map { a =>
            seen :+= (if closed then s"branch $a after release" else s"branch $a")
            a + r
        }
    }
val twice =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
        a => a
    )
discard(intercept[Finalizer.Spent](Eval(twice)))
assert(seen == List("branch 10"))
```

**`a Choice-shaped clause is refused at its second branch`** — three branches, so the refusal is shown to stop
the whole fan-out at the first re-entry rather than letting later ones through.

```scala
var closed             = false
var seen               = List.empty[String]
val acquire: Int < Ask = Effect.defer(100)
val v =
    Effect.bracket(acquire)(_ => closed = true) { r =>
        ask.map { a =>
            seen :+= (if closed then s"branch $a after release" else s"branch $a")
            a + r
        }
    }
val branches =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] => (_, cont) => cont(1).map(a => cont(2).map(b => cont(3).map(c => a + b + c))),
        a => a
    )
discard(intercept[Finalizer.Spent](Eval(branches)))
assert(seen == List("branch 1"))
```

**`nested brackets shared by a multi-shot clause are refused at the second branch`** — two scopes in the folded
continuation; both come due at the first branch.

```scala
var events           = List.empty[String]
val outer: Int < Ask = Effect.defer(1)
val v =
    Effect.bracket(outer)(_ => events :+= "release outer") { o =>
        Effect.bracket(Effect.defer(2))(_ => events :+= "release inner") { i =>
            ask.map { a =>
                events :+= s"branch $a"
                a + o + i
            }
        }
    }
val twice =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
        a => a
    )
discard(intercept[Finalizer.Spent](Eval(twice)))
assert(events == List("branch 10", "release inner", "release outer"))
```

**`a continuation held past the end of the eval refuses every time it is applied`** — the refusal is not a
one-time state a second attempt slips past. Proto's leaked-capture test applies once.

```scala
var count = 0
var stash = Maybe.empty[Arrow[Int, Int, Ask & Any]]
val v     = Effect.bracket(Effect.defer(1))(_ => count += 1)(r => ask.map(_ + r))
val dropped =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] =>
            (_, cont) =>
                stash = Maybe(cont)
                -1
        ,
        a => a
    )
assert(Eval(dropped) == -1)
assert(count == 1)
discard(intercept[Finalizer.Spent](Eval(answerAsk(0)(stash.get(2)))))
discard(intercept[Finalizer.Spent](Eval(answerAsk(0)(stash.get(5)))))
assert(count == 1)
```

**`a multi-shot handleFirst clause is refused at its second branch`** — the refusal must also reach the
`handleFirst` route (proto `ArrowEffect.handleFirst` is `private[kyo]`, reachable from `kyo.proto.kernel` tests).

```scala
var closed             = false
var seen               = List.empty[String]
val acquire: Int < Ask = Effect.defer(1)
val v =
    Effect.bracket(acquire)(_ => closed = true) { r =>
        ask.map { a =>
            seen :+= (if closed then s"branch $a after release" else s"branch $a")
            a + r
        }
    }
val branches: Int < Ask =
    ArrowEffect.handleFirst(Tag[Ask], v)(
        handle = [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
        done = a => a
    )
discard(intercept[Finalizer.Spent](Eval(answerAsk(0)(branches))))
assert(seen == List("branch 10"))
```

**`branches of a multi-shot clause share the resource the use closed over`** — the reason re-acquiring cannot
rescue the shape: one acquire for the whole clause, and the branch that ran held it.

```scala
var acquired           = 0
var seen               = List.empty[Int]
val acquire: Int < Ask = Effect.defer { acquired += 1; acquired }
val v =
    Effect.bracket(acquire)(_ => ()) { r =>
        ask.map { a =>
            seen :+= r
            a + r
        }
    }
val twice =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
        a => a
    )
discard(intercept[Finalizer.Spent](Eval(twice)))
// one acquire for the whole clause, and the branch that did run held it: re-entry has no
// channel to hand a second branch anything else, which is why refusing is the only answer
assert(acquired == 1)
assert(seen == List(1))
```

**`a branch built inside a clause and evaluated later is refused`** — a clause can *build* branch values its
region never evaluates (this is `Chunk.from(input).map(cont(_))` in `Choice.runStream`). The refusal must land
at the install, before the branch body.

```scala
var closed = false
var seen   = List.empty[String]
var stash  = Maybe.empty[Int < Ask]
val v =
    Effect.bracket(Effect.defer(1))(_ => closed = true) { r =>
        ask.map { a =>
            seen :+= (if closed then s"branch $a after release" else s"branch $a")
            a + r
        }
    }
val built =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] =>
            (_, cont) =>
                stash = Maybe(cont(10))
                -1
        ,
        a => a
    )
assert(Eval(built) == -1)
assert(closed)
discard(intercept[Finalizer.Spent](Eval(answerAsk(0)(stash.get))))
// the refusal lands at the install, so the branch's body never ran at all
assert(seen == Nil)
```

**`a bracket that encloses a multi-shot region releases after every branch`** — the mirror of the refusals: the
arrangement `Scope.run { Choice.run { ... } }` produces, which must NOT be refused. This is the single most
load-bearing missing case in the group.

```scala
var closed = false
var seen   = List.empty[String]
val v: Int < Any =
    Effect.bracket(Effect.defer(1))(_ => closed = true) { r =>
        ArrowEffect.handleCont(
            Tag[Ask],
            ask.map { a =>
                seen :+= (if closed then s"branch $a after release" else s"branch $a")
                a + r
            }
        )(
            [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
            a => a
        )
    }
assert(Eval(v) == 32)
assert(seen == List("branch 10", "branch 20"))
assert(closed)
```

### 1.3.9 Bracket: release-before-recovery and fatal failures (7)

The first two need `Effect.catching` rewritten as a `recover` clause; the fatal ones need none.

**`a release that throws on the completing path still runs the outer release before a recovery`**

```scala
var order    = List.empty[String]
var outcomes = List.empty[Result[Any, Int]]
val boom     = new IllegalStateException("inner release")
val v: Int < Any =
    Effect.catching {
        Effect.bracket(Effect.defer("outer"))((_, r: Result[Any, Int]) =>
            order :+= "outer release"
            outcomes :+= r
        ) { _ =>
            Effect.bracket(Effect.defer("inner"))(_ => throw boom)(_ => 1)
        }
    } { _ =>
        order :+= "recover"
        -1
    }
assert(Eval(v) == -1)
assert(order == List("outer release", "recover"))
assert(outcomes == List(Result.panic(boom)))
```

**`a done transform that throws releases the bracket below it before a recovery`** — the site a throwing
`handleFirst` clause reaches, since `handleFirst` runs its clause in `handleCont`'s done lane.

```scala
var order    = List.empty[String]
var outcomes = List.empty[Result[Any, Int]]
val boom     = new RuntimeException("done")
val v: Int < Any =
    Effect.catching {
        Effect.bracket(Effect.defer(1))((_, r: Result[Any, Int]) =>
            order :+= "release"
            outcomes :+= r
        ) { r =>
            ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + r))(
                [C] => _ => Loop.continue(1: Int < Any),
                _ => (throw boom): Int
            )
        }
    } { _ =>
        order :+= "recover"
        -1
    }
assert(Eval(v) == -1)
assert(order == List("release", "recover"))
assert(outcomes == List(Result.panic(boom)))
```

**`a fatal failure runs nested releases innermost first`** — the proto's bracket suite never uses a fatal.
`InterruptedException` is the fatal the proto's own EffectTraceTest uses ("a fatal error passes through
untouched").

```scala
var order = List.empty[String]
val boom  = new InterruptedException("fatal")
val v: Int < Any =
    Effect.bracket(Effect.defer(1))(_ => order :+= "outer") { _ =>
        Effect.bracket(Effect.defer(2))(_ => order :+= "inner") { _ =>
            (throw boom): Int
        }
    }
assert(intercept[InterruptedException](Eval(v)) eq boom)
assert(order == List("inner", "outer"))
```

**`a fatal failure thrown from a map after the acquire runs the release`**

```scala
var order = List.empty[String]
val boom  = new InterruptedException("fatal")
val v: Int < Any =
    Effect.bracket(Effect.defer(1))(_ => order :+= "release") { r =>
        Effect.defer(r).map(_ => (throw boom): Int)
    }
assert(intercept[InterruptedException](Eval(v)) eq boom)
assert(order == List("release"))
```

**`a fatal failure runs the release`**

```scala
var order = List.empty[String]
val boom  = new InterruptedException("fatal")
val v: Int < Any =
    Effect.bracket(Effect.defer(1))(_ => order :+= "release")(_ => (throw boom): Int)
assert(intercept[InterruptedException](Eval(v)) eq boom)
assert(order == List("release"))
```

**`a fatal failure runs the release and is not answered by a recovery`** — the asymmetry the old file called
out explicitly: the unwind runs finalizers with no `NonFatal` guard while the recovery arm declines a fatal.

```scala
var order = List.empty[String]
val boom  = new InterruptedException("fatal")
val v: Int < Any =
    Effect.catching {
        Effect.bracket(Effect.defer(1))(_ => order :+= "release")(_ => (throw boom): Int)
    } { _ =>
        order :+= "recover"
        -1
    }
assert(intercept[InterruptedException](Eval(v)) eq boom)
assert(order == List("release"))
```

---

# 2. `kyo-kernel/shared/src/test/scala/kyo/kernel/IsolateTest.scala`

49 leaves: 8 portable-missing, 3 divergent, 38 already covered. This file has by far the best proto parity: the
`derive`, `run`, `andThen`, `use`, `variance`, and `nest` groups are transcribed almost verbatim into the proto
IsolateTest.

## 2.1 Already covered (names only, 38)

`derive`: creates an isolate for context effects · fails compilation for non-context effects · fails
compilation for non-context effect traits · a derived isolate for context effects passes the computation
through · resolves implicitly for context effects.

`run`: threads capture, isolation, and restore · isolation starts from the captured enclosing state · a local
isolate keeps the enclosing state untouched · a pending arrow effect crosses the boundary and is handled
outside · an operation raised at a subtype tag crosses and is answered outside.

`andThen`: composes captures, isolations, and restores of both isolates · composing with Contextual leaves the
other side's management untouched.

`use`: provides the isolate as a given.

`Contextual`: passes the computation through untouched · nested bindings of one tag / the inner binding still
answers its own value after a crossing (proto "the inner binding of a tag still answers after the crossing") ·
nested bindings of one tag / the inner binding still derives from the outer after a crossing (proto "derived
layers reconstruct the fork point values on resume").

`variance` (all 10): Remove cannot accept supertypes · Remove cannot accept subtypes · Keep accepts subtypes ·
Keep does not accept supertypes · Restore accepts supertypes · Restore does not accept subtypes · contravariant
Keep with covariant Restore · variance preserved through andThen · complex intersection types · all three
variance interactions.

`nest` (all 4): tunnels effects through isolation · allows effect handling between nest and flatten ·
transforms Remove to Restore in type signature · a stateful isolate defers the restore to the nested layer.

`apply`: with nothing bound, the computation is unchanged (proto "with no region in scope the cycle is the
identity") · a bound value crosses into the computation · a binding crosses as what its strategy answers · the
innermost binding of a tag is what crosses · the crossed value is complete: it runs more than once, anywhere ·
the forking computation keeps what it had · a resource does not cross (proto EffectBracketTest "a contextual
isolate inside a bracket forks an inert obligation") · what a fork ended holding is joined into what is bound
here.

## 2.2 DIVERGENT (3)

Machinery in all three: the old **refusable, effectful fork strategy** `fork: A => Maybe[A] < S`
(`kyo/kernel/ContextEffect.scala:164`). The proto's is `fork: A => A` (proto ContextEffect.scala:94) — total
and pure, so a binding cannot decline to cross and the strategy cannot read another effect.

- `apply / a binding that refuses the crossing does not cross` — `fork = (_: Int) => Maybe.empty[Int]`.
- `apply / a binding the fork did not carry is left alone` — same, plus the consequence for `join`.
- `apply / a crossing runs where it was defined` — `fork = (n: Int) => read3.map(flag => …)`, an effectful
  strategy answered by the forking computation's own handler; asserts `asked == 1`.

## 2.3 PORTABLE-MISSING (8)

### 2.3.1 Nested bindings of one tag (3)

**`every layer of a three-deep nest survives a crossing`**

```scala
val v =
    ContextEffect.handle(Tag[TestEffect2], "outer") {
        ContextEffect.handle(Tag[TestEffect2], "middle") {
            ContextEffect.handle(Tag[TestEffect2], "inner") {
                Isolate.internal.Contextual.run(()).map(_ => ContextEffect.suspend(Tag[TestEffect2]))
            }
        }
    }
assert(v.eval == "inner")
```

**`a merging join updates the layer that was visible at the fork`** — two nested bindings of one tag; the join
must land on the *inner* one (10 = 5 + 5), not the outer (which would give 200). Proto's join tests
("join observes the parent's current state, the forked state, and the child's final state", "the origin
continues at the joined state") all use a single region.

```scala
val v =
    ContextEffect.handle(Tag[TestEffect1], ifUndefined = 100, ifDefined = _ => 100, join = (h: Int, f: Int) => h + f) {
        ContextEffect.handle(Tag[TestEffect1], ifUndefined = 5, ifDefined = _ => 5, join = (h: Int, f: Int) => h + f) {
            Isolate.internal.Contextual.run(()).map(_ => ContextEffect.suspend(Tag[TestEffect1]))
        }
    }
assert(v.eval == 10)
```

(Port note: proto join is three-arg, so `join = (h: Int, f: Int) => h + f` becomes
`join = (parent: Int, forked: Int, _: Int) => parent + forked`.)

**`a join never reaches a scope that did not own the crossing`** — the crossing is nested under region A, then
flattened under a *different* region B of the same tag; B must not be joined. Proto "a region exited before the
merge is not joined" pins the converse (the owning region having exited), not this.

```scala
val v =
    ContextEffect.handle(Tag[TestEffect1], ifUndefined = 1, ifDefined = _ => 1, join = (h: Int, f: Int) => h + f) {
        ContextEffect.handle(Tag[TestEffect1], ifUndefined = 2, ifDefined = _ => 2, join = (h: Int, f: Int) => h + f) {
            Isolate.internal.Contextual.nest(())
        }.map { nested =>
            ContextEffect.handle(
                Tag[TestEffect1],
                ifUndefined = 10,
                ifDefined = _ => 10,
                join = (h: Int, f: Int) => h + f
            ) {
                nested.map(_ => ContextEffect.suspend(Tag[TestEffect1]))
            }
        }
    }
assert(v.eval == 10)
```

### 2.3.2 The `Contextual` crossing surface (5)

All five use the fixtures defined at IsolateTest.scala:385-394:

```scala
def crossing[A, S](v: A < S)(using Frame): (A < S) < Any =
    Isolate.internal.Contextual(v)(Kyo.lift[A < S, Any](_))

def read1: Int < Any     = ContextEffect.suspend[Int, TestEffect1](Tag[TestEffect1], -1)
def read2: String < Any  = ContextEffect.suspend[String, TestEffect2](Tag[TestEffect2], "none")
def read3: Boolean < Any = ContextEffect.suspend[Boolean, TestEffect3](Tag[TestEffect3], false)

def bind1[A, S](value: Int)(v: A < S)(using Frame): A < S =
    ContextEffect.handle(Tag[TestEffect1], value, (_: Int) => value)(v)
```

**`every binding in scope is asked`** — three distinct tags standing at once, each asked at the fork. Proto
"joins run for every region in scope, in entry order" pins the *join* side only; nothing pins the fork side.
(The middle tag's refusal is DIVERGENT; drop it and assert all three cross.)

```scala
val v =
    ContextEffect.handle(Tag[TestEffect1], 1, (_: Int) => 1)(
        ContextEffect.handle(Tag[TestEffect2], "a", (_: String) => "a", fork = (_: String) => Maybe.empty[String])(
            ContextEffect.handle(Tag[TestEffect3], true, (_: Boolean) => true)(
                crossing(read1.map(a => read2.map(b => read3.map(c => (a, b, c)))))
            )
        )
    )
// the first and third cross, the second refuses and reads its default
assert(Eval(Eval(v)) == ((1, "none", true)))
```

**`an intervening map leaves the crossing resolving against the stack live at its point`** — the crossing is
composed under a `map` before any binding stands; the capture must read what stands when the eval *reaches* it.

```scala
// the crossing is composed under a map before any binding stands; what the capture reads
// is what stands when the eval reaches it, not what stood where the crossing was written
val composed = crossing(read1).map(child => child.map(_ + 1))
val child    = Eval(bind1(7)(composed))
assert(Eval(child) == 8)
```

**`the fused form hands the crossing straight to its consumer`** — `Contextual.apply(v)(f)` with no step between
the crossing and its consumer, so the crossed computation is never a value of its own. Proto's `apply` group has
one test and it uses `updateA`, never `Contextual`.

```scala
// no step between the crossing and what reads it, and the crossed computation is never a
// value of its own, so nothing nests it
val v = bind1(11)(Isolate.internal.Contextual(read1)(crossed => Eval(crossed) + 1))
assert(Eval(v) == 12)
```

**`a composed isolate crosses the context too`** — composing `Contextual` with a state-managing isolate must
keep both halves. Proto "composing with Contextual leaves the other side's management untouched" checks the
state half through `run`; nothing checks that the context binding still crosses through `apply`.

```scala
// what is bound around a fork crosses it whatever else the fork handles, so composing the
// context isolate with one that handles an effect must keep both halves
val composed     = Isolate.internal.Contextual.andThen(localA)
val (_, crossed) = Eval(runA(0)(bind1(42)(composed(read1)(Kyo.lift[Int < Any, Any](_)))))
assert(Eval(crossed) == 42)
```

**`a fork of a fork asks the same strategies`** — the crossed binding keeps its strategy, so a second crossing
asks it again.

```scala
val v = ContextEffect.handle(Tag[TestEffect1], 2, (_: Int) => 2, fork = (n: Int) => Maybe(n + 1))(
    crossing(crossing(read1))
)
// the first crossing answers 3, and the crossed binding keeps the strategy, so the second
// crossing asks it again and answers 4
assert(Eval(Eval(Eval(v))) == 4)
```

(Port note: `fork = (n: Int) => Maybe(n + 1)` becomes `fork = (n: Int) => n + 1`.)

---

# 3. `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EffectTraceTest.scala`

26 leaves: 19 portable-missing, 1 divergent, 6 already covered.

The proto suite (13 leaves) is oriented around the **splice** (what lands on `getStackTrace`) and around
recovery clauses. The old suite is oriented around the **carrier's own elements** (what `carrier.elements`
holds, its frame shape, its ordering, its cap). Almost the whole element-shape half is missing.

## 3.1 Already covered (names only, 6)

- `nested evals accumulate their regions innermost first` (proto "nested evals accumulate their regions")
- `NoStackTrace keeps its carrier and its empty stack` (proto "NoStackTrace keeps its carrier and skips the splice")
- `a chain past the cap reports the drop`
- `a fatal error passes through untouched`
- `region nesting / appears as one element per handler tag, innermost first` (proto "regions splice innermost first")
- `the carrier renders the frames as a message`

## 3.2 DIVERGENT (1)

- `the effect frames of a throw are carried through a catching guard` — machinery: `Effect.catching` as a node
  kind. Also asserts `classes(ex).exists(_.startsWith("catching @ "))`, i.e. the catching node contributing its
  own frame to the walk. The proto walk has no such node case (EffectTrace.scala:166-201 handles Suspend,
  Snapshot, Handle, Defer, Park, Chain, Arrow).

## 3.3 PORTABLE-MISSING (19)

The old file's shared fixtures (EffectTraceTest.scala:30-54) are needed by most of these:

```scala
def carrier(ex: Throwable): Option[EffectTrace] =
    ex.getSuppressed.collectFirst { case t: EffectTrace => t }

def methods(ex: Throwable): List[String] =
    carrier(ex).toList.flatMap(_.elements.iterator.map(_.getMethodName))

def classes(ex: Throwable): List[String] =
    carrier(ex).toList.flatMap(_.elements.iterator.map(_.getClassName))

class Boom extends RuntimeException("boom")

def innerStep(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
def outerStep(v: Int < Ask): Int < Ask = innerStep(v).map(_ + 1)

def stepA(v: Int < Ask): Int < Ask = v.map(_ + 1)
def stepB(v: Int < Ask): Int < Ask = v.map(_ + 2)

// alternating sites so consecutive frames differ: a run of one frame collapses to one
// element, which is what a loop over a single map site produces
def deepChain(depth: Int): Int < Ask =
    @tailrec def loop(i: Int, acc: Int < Ask): Int < Ask =
        if i == 0 then acc
        else loop(i - 1, if i % 2 == 0 then stepA(acc) else stepB(acc))
    loop(depth, innerStep(ask))
end deepChain
```

**`a throw in a handler clause carries the suspension and its region`** — the proto only pins a throw from the
region *body*.

```scala
val boom = new RuntimeException("boom")
val ex = intercept[RuntimeException] {
    Eval(ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => throw boom, a => a))
}
assert(ex eq boom)
val t = carrier(ex)
assert(t.nonEmpty)
val msg = t.get.getMessage
assert(msg.contains("EffectTraceTest.scala"))
assert(msg.contains("ask"))
assert(msg.contains("handle"))
```

**`a throw in a continuation frame names its site`**

```scala
def deep(i: Int): Int < Any =
    if i == 0 then 0 else (0: Int < Any).map(_ => deep(i - 1))
def boomAt(v: Int < Any): Int < Any = v.map(_ => (throw new RuntimeException("late")): Int)
val ex                              = intercept[RuntimeException](Eval(boomAt(deep(10000))))
val t                               = carrier(ex)
assert(t.nonEmpty)
assert(t.get.getMessage.contains("boomAt"))
```

**`an unhandled suspension arrives enriched`** — proto EvalTest "an unhandled operation is a bug" pins the
message text only, never the carrier or its frames.

```scala
val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
assert(ex.getMessage.contains("Unexpected pending effect"))
val t = carrier(ex)
assert(t.nonEmpty)
assert(t.get.getMessage.contains("ask"))
```

(Port note: the proto message is `"unhandled suspension"`, per EvalTest:861.)

**`the effect frames of a throw inside a mapped step / are carried through an eval`**

```scala
val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
assert(methods(ex).contains("innerStep"))
assert(methods(ex).contains("outerStep"))
```

**`… / name the call site's callee and the enclosing definition`** — the element-shape contract itself. The
proto builder produces identical elements, so this ports verbatim.

```scala
val ex  = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
val els = carrier(ex).get.elements.toList
val inner = els.find(_.getMethodName == "innerStep") match
    case Some(e) => e
    case None    => fail("no element for innerStep")
assert(inner.getClassName == s"map @ ${classOf[EffectTraceTest].getName}")
assert(inner.getFileName == "EffectTraceTest.scala")
assert(inner.getLineNumber > 0)
```

**`… / run innermost first`**

```scala
val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
val ms = methods(ex)
assert(ms.indexOf("innerStep") < ms.indexOf("outerStep"))
```

**`… / skip the internal frame placeholder`** — pins the `f ne Frame.internal` guard (proto
EffectTrace.scala:110).

```scala
val ex = intercept[Boom](Eval(answerAsk(1)(outerStep(ask))))
assert(carrier(ex).get.elements.forall(_.getFileName != "<internal>"))
```

**`… / a fused suspension carries the operation's own frame`** — the fused `suspendWith` node contributes its
own frame, ahead of the surrounding map. Fixture: `inline def askWith[B, S](inline f: Int => B < S): B < (Ask & S) =
ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)` (EffectTraceTest.scala:17-18).

```scala
def fusedStep: Int < Ask                 = askWith(_ => throw new Boom)
def aroundFused(v: Int < Ask): Int < Ask = v.map(_ + 1)
val ex                                   = intercept[Boom](Eval(answerAsk(1)(aroundFused(fusedStep))))
val ms                                   = methods(ex)
assert(ms.contains("fusedStep"))
assert(ms.contains("aroundFused"))
assert(ms.indexOf("fusedStep") < ms.indexOf("aroundFused"))
assert(classes(ex).exists(_.startsWith("askWith @ ")))
```

**`a suspension boundary the physical stack cannot cross`** — the reason the carrier exists at all.

```scala
def thrower(v: Int < Ask): Int < Ask = v.map(_ => throw new Boom)
def around(v: Int < Ask): Int < Ask  = thrower(v).map(_ + 1)
val ex                               = intercept[Boom](Eval(answerAsk(1)(around(ask))))
assert(methods(ex).contains("around"))
assert(methods(ex).contains("thrower"))
```

**`a deferred block / carries the steps after a budget rescue`**

```scala
def boomHere: Int < Any = (0: Int < Any).map(_ => (throw new Boom): Int)
def deep(i: Int): Int < Any =
    if i == 0 then boomHere else (0: Int < Any).map(_ => deep(i - 1))
val ex = intercept[Boom](Eval(deep(600)))
assert(methods(ex).contains("deep") || methods(ex).contains("boomHere"))
```

> The old file records a deliberate non-test next to this one (EffectTraceTest.scala:197-209): a throw from an
> `Effect.defer` **body** arrives without effect frames, because guarding it would put a try region on the
> hottest arm of the eval to describe a surface that carries no frame of its own. The proto `Kyo.Defer`
> likewise declares no `frame` (KyoInternal.scala:37-42), so the limit and its rationale carry over unchanged.
> Worth re-recording as a comment in the proto file rather than porting as a test.

**`region nesting / names each region exactly once`**

```scala
val ex = intercept[Boom](Eval(dropSay(answerAsk(1)(useAsk))))
assert(classes(ex).count(_.endsWith("Ask")) == 1)
assert(classes(ex).count(_.endsWith("Say")) == 1)
```

(Fixture: `def useAsk: Int < (Ask & Say) = outerStep(ask).map(v => say("x").map(_ => v))`, EffectTraceTest.scala:214.)

**`region nesting / a fused region names the body, then the region`** — the `handleLoopWith` shape.

```scala
val fused: Int < Any =
    ArrowEffect.handleLoopWith[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], innerStep(ask))(
        [C] => _ => Loop.continue(1),
        a => a
    )((_: Int) + 1)
val ex  = intercept[Boom](Eval(fused))
val els = carrier(ex).get.elements.toList
assert(els.exists(_.getMethodName == "innerStep"))
assert(els.exists(_.getMethodName == "handle"))
assert(els.indexWhere(_.getMethodName == "innerStep") < els.indexWhere(_.getMethodName == "handle"))
```

**`region nesting / a throw under an emitting clause walks without looping`** — a **termination guard**: a
clause that suspends produces a self-referential adapter node, and walked in the wrong role it would re-enqueue
itself forever, inside a catch, with an exception in flight. The proto has the same self-referential shape
(`Kyo.DeferWith`, `SuspendArrowWith`, `SnapshotWith` all extend both the node and the arrow), so this is the
highest-value single case in the file.

```scala
val v: Int < Any =
    dropSay(
        ArrowEffect.handleLoop(Tag[Ask], innerStep(ask))(
            [C] => _ => say("e").map(_ => Loop.continue(1: Int < Any)),
            a => a
        )
    )
val ex = intercept[Boom](Eval(v))
assert(carrier(ex).nonEmpty)
assert(classes(ex).exists(_.endsWith("Say")))
assert(carrier(ex).get.elements.forall(_.getFileName != "<internal>"))
```

**`region nesting / a throw in the second application of a multi-shot capture names each region once`**

```scala
val r: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], innerStep(ask))(
        [C] => (_, cont) => cont(1).map(_ => cont(2)),
        a => a
    )
val ex = intercept[Boom](Eval(r))
assert(classes(ex).count(_.endsWith("Ask")) == 1)
```

**`a fatal error keeps its original stack trace`** — proto "a fatal error passes through untouched" asserts the
carrier is absent; it does not assert the physical trace and the suppressed array are untouched.

```scala
val fatal                    = new StackOverflowError("fatal")
val before                   = fatal.getStackTrace
def fatalStep: Int < Ask     = ask.map(_ => throw fatal)
var caught: Throwable | Null = null
try discard(Eval(answerAsk(1)(fatalStep)))
catch case ex: Throwable => caught = ex
assert(caught eq fatal)
assert(fatal.getSuppressed.isEmpty)
assert(fatal.getStackTrace.sameElements(before))
```

**`the cap / stops the walk at exactly the cap and records what it did not reach`** — the proto asserts
`dropped > 0` but never `elements.length == 64`, so nothing pins `MaxFrames`.

```scala
val ex = intercept[Boom](Eval(answerAsk(1)(deepChain(200))))
assert(carrier(ex).get.elements.length == 64)
assert(carrier(ex).get.dropped > 0)
```

**`the cap / bounds a chain far deeper than the Java stack`**

```scala
val ex = intercept[Boom](Eval(answerAsk(1)(deepChain(1000000))))
assert(carrier(ex).get.elements.length == 64)
assert(carrier(ex).get.dropped > 0)
```

**`a failure of the walk itself leaves the original failure travelling`** — a node whose `frame` throws;
describing a failure must never replace the failure being described. Ports onto `Kyo.SuspendArrow`
(KyoInternal.scala:59-60), whose `frame` is inherited from `Pending` and overridable.

```scala
// a node whose frame cannot be read: describing a failure must never replace the
// failure being described
val unreadable =
    new Kyo.Suspend[Const[Unit], Const[Int], Ask, Any, Int, Any]:
        def tag   = Tag[Ask]
        def input = ()
        def frame = throw new IllegalStateException("frame read failed")
        def cont  = Arrow.id[Int]
val ex = intercept[Throwable](Eval(unreadable.asInstanceOf[Int < Any]))
assert(ex.getMessage.contains("Unexpected pending effect"))
assert(carrier(ex).toList.flatMap(_.elements.toList).isEmpty)
```

**`a second crossing rewrites the spliced trace rather than duplicating it`** — proto "nested evals accumulate
their regions" pins `getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1`, but nothing pins that the
**spliced physical trace** is rewritten rather than appended to on a second crossing (proto EffectTrace.scala:55-61
caches `carrier.physical` precisely for this). `Effect.catching` appears here only as a rethrow vehicle and
becomes a `recover` clause that rethrows.

```scala
def rethrown: Int < Any = Effect.catching {
    val crossed: Int = Eval(answerAsk(1)(outerStep(ask)))
    crossed
}(e => throw e)
val ex = intercept[Boom](Eval(rethrown))
assert(ex.getStackTrace.count(_.getMethodName == "innerStep") == 1)
assert(ex.getStackTrace.count(_.getMethodName == "outerStep") == 1)
assert(ex.getSuppressed.count(_.isInstanceOf[EffectTrace]) == 1)
```

---

# 4. `kyo-kernel/shared/src/test/scala/outsidekyo/KernelTest.scala`

91 leaves: 65 portable-missing, 26 divergent, 0 covered. **There is no proto counterpart file** — the proto test
tree has only `kyo/` and `outsidekyo/` roots, and `outsidekyo` contains this one old-kernel file.

## 4.1 What the file pins

Not behavior. It is a **visibility and inline-accessor gate**, and its header states the two failures that
motivated it (KernelTest.scala:9-25):

1. An inline body is re-typechecked where it expands, so a `private[kyo]` name it selects qualified does not
   resolve at a call site outside `kyo`. `map` and `Eval` both stopped compiling outside `kyo` that way.
2. A `private[kyo]` term an inline body names gets an inline accessor, and for a top-level object in
   `kyo.kernel.internal` dotty emits that accessor with the package itself as the receiver, so every eval
   failed with `NoClassDefFoundError: kyo/kernel/internal`.

Because the two halves need different evidence, every test both **expands** the entry point (proving the name
resolves) and **asserts on what it produces** (proving the emitted accessor is well formed). The file's standing
rule is "a new public inline entry point belongs here", with one deliberate omission (`Implicits.abortCastUnit`,
which exists to fail compilation).

The proto needs this gate at least as much as the old kernel does: its public surface is inline-heavy in exactly
the same way (`<.map` / `flatMap` / `andThen` / `unit` / `handle` / `eval` in Pending.scala,
`ArrowEffect.suspend` / `handleCont` / `handleLoop` / … in ArrowEffect.scala, `ContextEffect.suspend` /
`handleInheritable` / `handle`, `Arrow.apply` / `recursive`, `Loop.*`, `Implicits.lift` /
`liftPureFunction1..6`), and it has more `private[kyo]` internals for those inline bodies to name
(`Pending`, `Nested`, `Eval`, `Safepoint`, `Kyo.*`, `Stack`, `Debugger`).

Three proto-specific spellings the counterpart must use:

- `Eval` is `private[kyo]` in the proto (Eval.scala:24) **and** returns `A < S` rather than `A`. Outside `kyo`
  the entry point is the public `.eval` extension (Pending.scala:290-298), which is itself inline and is
  therefore exactly the kind of thing this file exists to test.
- `Effect.deferInline`, `ArrowEffect.handleFirst`, `ArrowEffect.dispatchFirst` and `Isolate.internal` are
  `private[kyo]` / `private[kernel]` in the proto, so they are out of scope for an `outsidekyo` file by
  construction (the old file does not exercise them either).
- `ContextEffect.handle(tag, value)` becomes `handleInheritable(tag, value)`.

## 4.2 DIVERGENT (26)

### 4.2.1 `Effect.catching` (1)

- `the effect surface / catching` — machinery: `Effect.catching`. Body:
  ```scala
  val v = Effect.catching((throw new RuntimeException("boom")): Int < Any)(_ => 42)
  assert(Eval(v) == 42)
  ```

### 4.2.2 Surfaces the proto does not have (25)

`kyo.Kyo` in the proto (`kyo/proto/Arrow.scala:19-22`) is **only** `lift`. There is no `Kyo.unit` and there are
no collection combinators; the old `kyo.Kyo` object (`kyo-kernel/shared/src/main/scala/kyo/Kyo.scala`) has no
proto analogue in this module.

- `the lifts / Kyo.unit`
- `the collection combinators` (all 24): foreach · foreachConcat · foreachIndexed · foreachDiscard · filter ·
  foldLeft · collect · collectAll · collectAllDiscard · findFirst · takeWhile · span · dropWhile · partition ·
  partitionMap · scanLeft · groupBy · groupMap · filterKeys · fill · zip · when with both branches · when with
  one branch · unless

If those combinators land in the proto later, these 24 become PORTABLE and should come back with them.

## 4.3 PORTABLE-MISSING (65)

Every one of these ports mechanically: same entry point, same assertion, modulo the three spellings in §4.1 and
the standing deltas in the preamble. Below I quote verbatim the groups with a real API delta, and give exact
line ranges for the groups whose bodies are one-line surface expansions that transcribe unchanged.

### 4.3.1 Groups that transcribe unchanged (37) — quote by line range

| Group | leaves | old lines | delta |
|---|---|---|---|
| `the pending combinators` (map, flatMap, andThen, unit, flatten, eval) | 6 | 42-75 | `Eval(x)` → `x.eval`; `Kyo.lift` → `kyo.proto.Kyo.lift` |
| `handle, at every arity` (one … ten) | 10 | 80-179 | `Eval(answer(v))` → `answer(v).eval` |
| `the arrow constructors` (Arrow.apply, Arrow.recursive) | 2 | 184-193 | none |
| `Loop` (apply ×4, apply resuming through an effect, indexed ×5, foreach, repeat, whileTrue, forever) | 14 | 320-410 | `Kyo.lift[Unit, Any]{…}` in `repeat`/`whileTrue` becomes the plain block (proto `Loop.repeat` takes `Any < S`, `whileTrue` takes `Boolean < S` / `Unit < S`) |
| `the lifts` minus `Kyo.unit` (Kyo.lift, bare value in a lambda, singleton via the CanLift macro, pure function of 1-6 args) | 9 (of 10) | 415-464 | `kyo.Kyo.lift` → `kyo.proto.Kyo.lift`; the pure-function lifts are `Implicits.liftPureFunction1..6` in the proto |

Each body in those ranges is two to four lines of the form "expand the entry point, assert the value it
produces"; they are reproduced faithfully by copying the range and applying the delta column.

### 4.3.2 `the effect surface` minus `catching` (17) — verbatim

Shared fixtures (KernelTest.scala:29-38):

```scala
sealed trait Ask   extends ArrowEffect[Const[Unit], Const[Int]]
sealed trait Say   extends ArrowEffect[Const[String], Const[Unit]]
sealed trait Level extends ContextEffect[Int]

def ask: Int < Ask             = ArrowEffect.suspend[Any](Tag[Ask], ())
def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

/** Answers every Ask with 1. */
def answer[A, S](v: A < (Ask & S)): A < S =
    ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(1), a => a)
```

```scala
"suspend" in {
    assert(Eval(answer(ask)) == 1)
}

"suspendWith" in {
    val v = ArrowEffect.suspendWith[Any](Tag[Ask], ())(i => i + 5)
    assert(Eval(answer(v)) == 6)
}

"handleCont" in {
    val v = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))([C] => (_, cont) => cont(2), a => a * 10)
    assert(Eval(v) == 30)
}

"handleLoop" in {
    val v = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(3))
    assert(Eval(v) == 4)
}

"handleLoop completing from the clause" in {
    val v = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.done(99), a => a)
    assert(Eval(v) == 99)
}

"handleLoopState" in {
    // a done clause that computes leaves the result type to the expected type
    val v: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 7, ask.map(_ + 1))(
        [C] => (state, _) => Loop.continue(state + 1, state),
        (state, a) => a * 100 + state
    )
    assert(Eval(v) == 808)
}

"handleLoopState without a done clause" in {
    val v = ArrowEffect.handleLoopState(Tag[Ask], 7, ask.map(_ + 1))(
        [C] => (state, _) => Loop.continue(state + 1, state)
    )
    assert(Eval(v) == 8)
}

"handleContWith" in {
    val v: Int < Any = ArrowEffect.handleContWith(Tag[Ask], ask.map(_ + 1))(
        [C] => (_, cont) => cont(2),
        a => a
    )((b: Int) => b * 10)
    assert(Eval(v) == 30)
}

"handleLoopWith" in {
    val v: Int < Any = ArrowEffect.handleLoopWith(Tag[Ask], ask.map(_ + 1))(
        [C] => _ => Loop.continue(3),
        a => a
    )((b: Int) => b * 10)
    assert(Eval(v) == 40)
}

"handleLoopStateWith" in {
    val v: Int < Any = ArrowEffect.handleLoopStateWith(Tag[Ask], 7, ask.map(_ + 1))(
        [C] => (state, _) => Loop.continue(state + 1, state),
        (state, a) => a + state
    )(b => b * 10)
    // the clause answers 7 and advances the state to 8, so the body settles at 8 and the done
    // clause adds the final state, not the initial one
    assert(Eval(v) == 160)
}

"bracket" in {
    var released = false
    val v        = Effect.bracket(1)(_ => released = true)(r => ask.map(_ + r))
    assert(Eval(answer(v)) == 2)
    assert(released)
}

"ContextEffect.suspend and handle" in {
    val v = ContextEffect.suspend(Tag[Level])
    assert(Eval(ContextEffect.handle(Tag[Level], 42)(v)) == 42)
}

"ContextEffect.suspendWith" in {
    val v = ContextEffect.suspendWith(Tag[Level])(l => ask.map(_ + l))
    assert(Eval(answer(ContextEffect.handle(Tag[Level], 41)(v))) == 42)
}

"ContextEffect.suspend with a default" in {
    assert(Eval(ContextEffect.suspend(Tag[Level], -1)) == -1)
}

"ContextEffect.suspendWith with a default" in {
    val v = ContextEffect.suspendWith(Tag[Level], 40)(l => ask.map(_ + l))
    assert(Eval(answer(v)) == 41)
}

"ContextEffect.handle layering" in {
    val v = ContextEffect.handle(Tag[Level], 100, _ * 2) {
        ContextEffect.handle(Tag[Level], 100, _ * 2)(ContextEffect.suspend(Tag[Level]))
    }
    assert(Eval(v) == 200)
}

"a region nested under another effect" in {
    var said = List.empty[String]
    val body = ask.map(a => say("a" + a).map(_ => a))
    val v = ArrowEffect.handleCont(Tag[Say], answer(body))(
        [C] =>
            (input, cont) =>
                said = said :+ input
                cont(())
        ,
        a => a
    )
    assert(Eval(v) == 1)
    assert(said == List("a1"))
}
```

Deltas in this group: `Loop.continue(3)` → `Loop.continue((), 3)`; `Loop.continue(state + 1, state)` keeps its
two-argument shape; every `ContextEffect.handle(Tag[Level], …)` becomes `handleInheritable`; the `bracket`
release becomes `(_, _) => released = true`; `Eval(x)` becomes `x.eval`. The proto also adds
`ArrowEffect.handleContOperation` (ArrowEffect.scala:135, :163), a public inline entry point with **no** old
ancestor, which by this file's own standing rule ("a new public inline entry point belongs here") should be
added.

### 4.3.3 `Isolate` (5) — verbatim

```scala
def level: Int < Level = ContextEffect.suspend(Tag[Level])

"derive for a context effect and run" in {
    val isolate = Isolate.derive[Level, Any, Any]
    val r       = ContextEffect.handle(Tag[Level], 3)(isolate.run(level.map(_ + 1)))
    assert(Eval(r) == 4)
}

"summons implicitly" in {
    val isolate = Isolate[Level, Any, Level]
    assert(Eval(ContextEffect.handle(Tag[Level], 2)(isolate.run(level))) == 2)
}

"nest and flatten" in {
    val isolate = Isolate.derive[Level, Any, Level]
    val nested  = isolate.nest(level.map(_ * 10))
    assert(Eval(ContextEffect.handle(Tag[Level], 5)(nested.flatten)) == 50)
}

"use provides the isolate as a given" in {
    val r = Isolate.derive[Level, Any, Any].use {
        summon[Isolate[Level, Any, Any]].run(level)
    }
    assert(Eval(ContextEffect.handle(Tag[Level], 8)(r)) == 8)
}

"andThen composes" in {
    sealed trait Level2 extends ContextEffect[Int]
    val isolate = Isolate.derive[Level, Any, Any].andThen(Isolate.derive[Level2, Any, Any])
    val v       = level.map(a => ContextEffect.suspend(Tag[Level2]).map(_ + a))
    val r       = ContextEffect.handle(Tag[Level], 1)(ContextEffect.handle(Tag[Level2], 10)(isolate.run(v)))
    assert(Eval(r) == 11)
}
```

`Isolate.derive` is a macro (`Isolate.scala:61`, `deriveImpl` at :157) that expands at the call site and
summons `Isolate` instances there, so it is precisely the kind of entry point this file exists to gate; note
that the proto's expansion names `Contextual`, which is `private[kernel]` (Isolate.scala:67). That is the exact
failure mode described in the file's header (an inline/macro body naming a term that does not resolve at the
expansion site), so these five are the highest-value ports in the whole file.

### 4.3.4 `context binding edges` (2) — verbatim

```scala
"a fork strategy is accepted at the handle site" in {
    val v = ContextEffect.suspend(Tag[Level])
    val r = ContextEffect.handle(Tag[Level], 7, identity, fork = l => Maybe(l + 1))(v)
    assert(Eval(r) == 7)
}

"a join strategy is accepted at the handle site" in {
    val v = ContextEffect.suspend(Tag[Level])
    val r = ContextEffect.handle(Tag[Level], 7, identity, join = (held, _) => held)(v)
    assert(Eval(r) == 7)
}
```

Deltas: proto `fork` is `A => A`, so `fork = l => Maybe(l + 1)` becomes `fork = (l: Int) => l + 1`; proto `join`
is three-arg, so `join = (held, _) => held` becomes `join = (parent: Int, _: Int, _: Int) => parent`. The proto
`handle` overload that takes strategies also requires `ifUndefined` and `ifDefined` positionally
(ContextEffect.scala:89-97), and adds two further optional hooks (`done`, `release`, ContextEffect.scala:117-118)
that have no old ancestor and should get leaves of their own here.

---

# 5. Priority read

If the ports are triaged rather than done wholesale, the ranking by what each protects:

1. **`outsidekyo` gate, `Isolate` group first** (§4.3.3). Nothing currently proves the proto's public inline
   and macro surface resolves outside package `kyo`; the file exists because that failed twice, and the proto
   has strictly more `private[kyo]` internals for inline bodies to name.
2. **Bracket multi-shot law** (§1.3.8), especially "a bracket that encloses a multi-shot region releases after
   every branch" — the one shape that must NOT be refused, and the arrangement `Scope.run { Choice.run { … } }`
   produces. The proto pins one refusal and nothing else in this family.
3. **Bracket stack safety** (§1.3.3: `deeply nested brackets release in bounded stack`, `many sequential
   brackets each release`) — the proto has no stack-safety test for brackets at all.
4. **`a throw under an emitting clause walks without looping`** (§3.3) — a non-termination guard on a
   self-referential node shape the proto shares.
5. **Effect-trace element shape and cap** (§3.3) — nothing in the proto pins `MaxFrames = 64`, the element
   encoding, the innermost-first ordering of the carrier's own elements, or the `Frame.internal` skip.
6. **Fatal-failure release** (§1.3.9) — the unwind runs finalizers unguarded while recovery declines a fatal;
   the two arms deliberately disagree and nothing pins the disagreement.
7. **Deferral-node overloads** (§1.3.1) — the proto's three- and four-argument `Effect.defer` and their
   Id-collapse branches are entirely untested.
