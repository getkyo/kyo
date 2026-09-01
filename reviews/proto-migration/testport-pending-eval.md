# Test port gap: PendingTest, EvalTest, EvalCaptureTowerTest

Read-only comparison of three old-kernel test files against their proto counterparts. Coverage, not
names, decides the verdict: a case counts as covered when some proto case exercises the same
behavior, whatever it is called.

Files compared:

| old | proto |
|---|---|
| `kyo-kernel/shared/src/test/scala/kyo/kernel/PendingTest.scala` (92 leaf cases) | `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/PendingTest.scala` |
| `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala` (86 leaf cases) | `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala` |
| `kyo-kernel/shared/src/test/scala/kyo/kernel/internal/EvalCaptureTowerTest.scala` (3 leaf cases) | none |

## Totals

| file | portable-missing | divergent | already covered | partially covered |
|---|---|---|---|---|
| PendingTest | 30 | 0 | 56 | 6 |
| EvalTest | 29 | 0 | 57 | 0 |
| EvalCaptureTowerTest | 3 | 0 | 0 | 0 |

**No case in any of the three files is DIVERGENT.** None of them touches the old-only machinery the
brief listed: there is no use of the old `Stack` span API, no `Finalizer` registry, no session
`Debugger`, no `Effect.catching`, and no `Handler.Out` anywhere in these three files. Every missing
case is expressible against the proto surface; what varies is spelling, catalogued below.

## Spelling deltas that apply to every port

| old | proto |
|---|---|
| `kyo.kernel.internal.Eval(v)` returns `A` | `Eval(v)` returns `A < S`; tests wrap it (`private def eval[A, S](v: A < S): A = Nested.unnest[A](Eval(v))` in proto EvalTest, `v.eval` in proto PendingTest) |
| `Loop.continue(answer)` | `Loop.continue((), answer)`, and the answer must be a computation: `Loop.continue((), 41: Int < Any)` |
| `Loop.continue(state, answer)` | same shape, answer ascribed: `Loop.continue(s + 1, s: Int < Any)` |
| `Loop.done(inner)` where `inner` is itself a computation | `Loop.done(Kyo.lift(inner))` (proto PendingTest:172 precedent) |
| `Kyo.lift` from `kyo` | `kyo.proto.Kyo.lift` (`kyo-kernel/shared/src/main/scala/kyo/proto/Arrow.scala:21`), or a local `def lifted[A](v: A): A < Any = v` as proto PendingTest:446 does |
| `kyo.Test` + `typeCheckFailure(code)(msg)` | `AnyFreeSpec` + `assertTypeError(code)`, or the local `typeCheckFailure` helper proto `ImplicitsTest.scala:25-32` defines over `typeCheckErrors` |
| bug message `"Unexpected pending effect"` | `"unhandled suspension"` (`Eval.scala:99`) |
| `SafepointStop.request()` (jvm-native old test helper) | the `requestStop()` helper proto EvalTest:542-546 already defines over `Safepoint.get`, `Safepoint.stop(Thread.currentThread())`, `Safepoint.deadline` |
| `Arrow[Unit, Int, Say]` for a stored continuation | `Unit => Int < Say` with `Maybe(cont(_))` (proto EvalTest:494-499 precedent) |
| `ContextEffect.handle(Tag[E], value)(v)` | `ContextEffect.handleInheritable(Tag[E], value)(v)` |
| `Eval.partial` returns a park the test drives with `Eval` | same, plus `parked.isInstanceOf[Kyo.Park[?, ?]]` is directly assertable |

Proto API confirmed present for every port below: `map`/`flatMap`/`andThen`/`unit`/`flatten`/
`handle` (arities 1..10)/`eval`/`evalNow` on `<` (`Pending.scala`), the `Render` given
(`Pending.scala:301`), `Effect.defer` (by-name, `Effect.scala:105`), `ArrowEffect.handleLoop` /
`handleLoopState` both with and without a `done` clause (`ArrowEffect.scala:221,247,256,358`),
`handleLoopWith` (`:405`), `handleCont` (`:62,126`), `Eval.apply`/`partial`/`release`
(`Eval.scala:144,146,26`), `Safepoint.period/get/save/restore/enter/exit/stop/deadline`, and the
pure-function lifts one through six (`Implicits.scala:17-47`).

---

# 1. PendingTest

## 1.1 Portable, missing from the proto file (30)

### Helpers the ports need

`Kyo.lift` (or the local `lifted`), and for case 27 the old file's third effect, absent from the
proto file:

```scala
sealed trait TestEffect3 extends ContextEffect[Boolean]
object TestEffect3:
    def apply(): Boolean < TestEffect3 =
        ContextEffect.suspend(Tag[TestEffect3])

    def run[A, S](value: Boolean)(v: A < (TestEffect3 & S)): A < S =
        ContextEffect.handle(Tag[TestEffect3], value)(v)
end TestEffect3
```

Port note: `ContextEffect.handle(Tag, value)(v)` becomes
`kyo.proto.kernel.ContextEffect.handleInheritable(Tag, value)(v)`.

---

### 1. `evalNow builds its receiver once` (old:282-290)

Guards against an `inline self` receiver being substituted per branch of `evalNow`, which would run
a settled receiver's lambda twice. The old file carries the rationale as a comment above it.

PORTABLE. Proto's `evalNow` (`Pending.scala:251-256`) binds `val v = self` once, so the shape it
guards is structurally different; the test is still the regression guard for that property.
`evalNow` is `private[kyo]`, and proto PendingTest is in `kyo.proto.kernel`, so it is reachable
(the file already calls it).

```scala
var runs = 0
val r = (1: Int < Any).map { a =>
    runs += 1
    a + 1
}.evalNow
assert(r == Maybe(2))
assert(runs == 1)
```

### 2. `a for-comprehension chains through flatMap and map` (old:414-421)

Desugaring: two generators over `Int < Any` compose through `flatMap` then `map`.

PORTABLE, verbatim.

```scala
val result =
    for
        x <- 5: Int < Any
        y <- 3: Int < Any
    yield x + y
assert(result.eval == 8)
```

### 3. `lift / a pure value lifts into a computation` (old:424-427)

The implicit value lift: a bare `5` types as `Int < Any`.

PORTABLE, verbatim. Overlapping coverage exists outside the file in proto
`ImplicitsTest` ("lift / primitives").

```scala
val x: Int < Any = 5
assert(x.eval == 5)
```

### 4-9. `lift / a pure function lifts into a computation-returning one / one|two|three|four|five|six param(s)` (old:430-464)

A pure `A => B` converts to `A => B < Any` at each arity one through six, and the lifted function
evaluates.

PORTABLE, verbatim; proto `Implicits.scala:17-47` defines `liftPureFunction1` through
`liftPureFunction6`. Proto `ImplicitsTest` covers arities one through four only, so five and six
have no coverage anywhere in the proto suite.

```scala
"one param" in {
    val f: Int => String            = _.toString
    val lifted: Int => String < Any = f
    assert(lifted(42).eval == "42")
}

"two params" in {
    val f: (Int, Int) => String            = (a, b) => (a + b).toString
    val lifted: (Int, Int) => String < Any = f
    assert(lifted(20, 22).eval == "42")
}

"three params" in {
    val f: (Int, Int, Int) => String            = (a, b, c) => (a + b + c).toString
    val lifted: (Int, Int, Int) => String < Any = f
    assert(lifted(10, 20, 12).eval == "42")
}

"four params" in {
    val f: (Int, Int, Int, Int) => String            = (a, b, c, d) => (a + b + c + d).toString
    val lifted: (Int, Int, Int, Int) => String < Any = f
    assert(lifted(10, 20, 10, 2).eval == "42")
}

"five params" in {
    val f: (Int, Int, Int, Int, Int) => String            = (a, b, c, d, e) => (a + b + c + d + e).toString
    val lifted: (Int, Int, Int, Int, Int) => String < Any = f
    assert(lifted(10, 20, 10, 1, 1).eval == "42")
}

"six params" in {
    val f: (Int, Int, Int, Int, Int, Int) => String            = (a, b, c, d, e, g) => (a + b + c + d + e + g).toString
    val lifted: (Int, Int, Int, Int, Int, Int) => String < Any = f
    assert(lifted(10, 20, 10, 1, 0, 1).eval == "42")
}
```

### 10. `lift / a computation-returning function does not lift again` (old:467-477)

A function already returning `B < Any` must not lift a second time into `B < Any < Any`, at arities
one through four.

PORTABLE, verbatim (`typeCheckErrors` is used directly, no `kyo.Test` helper). Proto
`ImplicitsTest` covers the arity-one case only ("do not lift into nested computations").

```scala
val f1: Int => String < Any                  = _ => "test"
val f2: (Int, Int) => String < Any           = (_, _) => "test"
val f3: (Int, Int, Int) => String < Any      = (_, _, _) => "test"
val f4: (Int, Int, Int, Int) => String < Any = (_, _, _, _) => "test"
discard(f1, f2, f3, f4)
assert(typeCheckErrors("val _: Int => String < Any < Any = f1").nonEmpty)
assert(typeCheckErrors("val _: (Int, Int) => String < Any < Any = f2").nonEmpty)
assert(typeCheckErrors("val _: (Int, Int, Int) => String < Any < Any = f3").nonEmpty)
assert(typeCheckErrors("val _: (Int, Int, Int, Int) => String < Any < Any = f4").nonEmpty)
```

### 11. `lift / a generic method does not accept a wider effect row` (old:479-483)

A method taking `A < Any` rejects an argument whose row still names an unhandled effect.

PORTABLE, verbatim. Proto `ImplicitsTest` ("lift rejections / generic method effect mismatch")
covers the same law with a different effect, so this is duplicate coverage outside the file.

```scala
def widen[A](v: A < Any) = v
discard(widen(1: Int < Any))
assert(typeCheckErrors("widen(ask)").nonEmpty)
```

### 12. `handle / applies a function to a settled value` (old:504-506)

PORTABLE, verbatim.

```scala
assert((5: Int < Any).handle(_.map(_ + 1)).eval == 6)
```

### 13. `handle / applies a function to an effectful value` (old:508-510)

PORTABLE, verbatim.

```scala
assert(ask.handle(v => answerAsk(2)(v)).eval == 2)
```

### 14. `handle / chains handles` (old:512-514)

Two separate `.handle` calls compose left to right.

PORTABLE, verbatim.

```scala
assert(ask.handle(v => v.map(_ * 2)).handle(v => answerAsk(3)(v)).eval == 6)
```

### 15. `handle / works with the identity function` (old:516-519)

PORTABLE, verbatim.

```scala
val v: Int < Ask = ask
assert(answerAsk(2)(v.handle(identity)).eval == 2)
```

### 16. `handle / can produce a value instead of a computation` (old:521-524)

The last stage of a `handle` chain may return a plain value (here by evaluating), not a computation.

PORTABLE, verbatim.

```scala
val result: Int = ask.handle(v => answerAsk(2)(v)).handle(_.eval)
assert(result == 2)
```

### 17-25. `handle / works with two|three|four|five|six|seven|eight|nine|ten functions` (old:526-632)

Each `handle` overload at arity two through ten threads its stages in order, changing the value's
type as it goes.

PORTABLE, verbatim; proto `Pending.scala` defines `handle` at every arity one through ten. Proto's
single "handle applies transformations fluently" exercises arity one, two, and ten only; arities
three through nine have no coverage.

```scala
"works with two functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2)
    )
    assert(result.eval == 12)
}

"works with three functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2),
        _.map(_.toString)
    )
    assert(result.eval == "12")
}

"works with four functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2),
        _.map(_.toString),
        _.map(_.length)
    )
    assert(result.eval == 2)
}

"works with five functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2),
        _.map(_.toString),
        _.map(_.length),
        _.map(_ > 1)
    )
    assert(result.eval == true)
}

"works with six functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2),
        _.map(_.toString),
        _.map(_.length),
        _.map(_ > 1),
        _.map(if _ then "Yes" else "No")
    )
    assert(result.eval == "Yes")
}

"works with seven functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2),
        _.map(_.toString),
        _.map(_.length),
        _.map(_ > 1),
        _.map(if _ then "Yes" else "No"),
        _.map(_.toLowerCase)
    )
    assert(result.eval == "yes")
}

"works with eight functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2),
        _.map(_.toString),
        _.map(_.length),
        _.map(_ > 1),
        _.map(if _ then "Yes" else "No"),
        _.map(_.toLowerCase),
        _.map(_.length)
    )
    assert(result.eval == 3)
}

"works with nine functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2),
        _.map(_.toString),
        _.map(_.length),
        _.map(_ > 1),
        _.map(if _ then "Yes" else "No"),
        _.map(_.toLowerCase),
        _.map(_.length),
        _.map(_ * 2)
    )
    assert(result.eval == 6)
}

"works with ten functions" in {
    val result = (5: Int < Any).handle(
        _.map(_ + 1),
        _.map(_ * 2),
        _.map(_.toString),
        _.map(_.length),
        _.map(_ > 1),
        _.map(if _ then "Yes" else "No"),
        _.map(_.toLowerCase),
        _.map(_.length),
        _.map(_ * 2),
        _.map(_ > 5)
    )
    assert(result.eval == true)
}
```

### 26. `show / displays a settled value through the inner type's Render` (old:636-641)

The `Render` given for `A < S` renders a settled value as `Kyo(<inner render>)`, both through
`Render.apply` and the `render` interpolator.

PORTABLE, verbatim; proto's given is `Pending.scala:301`. This exact body already exists verbatim in
proto `ImplicitsTest` ("Render instance / displays pure values wrapped, inner types via their own
Render"), so porting it into PendingTest would duplicate; the coverage itself is not lost.

```scala
val i: Result[String, Int] < Any         = Result.succeed(23)
val r: Render[Result[String, Int] < Any] = Render.apply
assert(r.asString(i) == "Kyo(Success(23))")
assert(render"$i" == "Kyo(Success(23))")
```

### 27. `nested computations / multiple operations` (old:792-808)

A method producing a doubly-effectful nested value is flattened, mapped, then `flatMap`ed into a
third (context) effect, and all three regions are handled from the outside in. The only case in
either file that combines nesting with a `ContextEffect`.

PORTABLE. Needs the `TestEffect3` block above with `handle` respelled `handleInheritable`.

```scala
def processValue(v: Int): Int < TestEffect2 < TestEffect1 =
    TestEffect1(v).map(s => Kyo.lift(TestEffect2(s + "!")))

val input = 100
val result = processValue(input).flatten
    .map(n => n * 2)
    .flatMap(n => TestEffect3().map(_ => n))

val finalResult = TestEffect1.run(
    TestEffect2.run(
        TestEffect3.run(true)(result)
    )
)

assert(finalResult.eval == ("Effect1:100!".length + 10) * 2)
```

### 28. `nested computations / nested effect suspension lifted function` (old:831-844)

A generic `g[B](f: String => B)` applied to a function returning `Int < TestEffect2` produces a
nested `Int < TestEffect2 < TestEffect1`; both the `map`-then-run and the `flatten`-then-run paths
must agree.

PORTABLE, verbatim.

```scala
def f(str: String): Int < TestEffect2 = TestEffect2(str)

def g[B](f: String => B): B < TestEffect1 =
    TestEffect1(1).map(f)

val nested: Int < TestEffect2 < TestEffect1 = g(f)

val result = TestEffect1.run(nested.map(TestEffect2.run))
assert(result.eval == 19)

val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
assert(result2.eval == 19)
```

### 29. `nested computations / nested effect suspension widened lifted function` (old:846-860)

Same as case 28, but `f` is first widened to `String => B < Any` inside `g`, so the lift happens at
the binding rather than at the call.

PORTABLE, verbatim.

```scala
def f(str: String): Int < TestEffect2 = TestEffect2(str)

def g[B](f: String => B): B < TestEffect1 =
    val liftedF: String => B < Any = f
    TestEffect1(1).map(liftedF)

val nested: Int < TestEffect2 < TestEffect1 = g(f)

val result = TestEffect1.run(nested.map(TestEffect2.run))
assert(result.eval == 19)

val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
assert(result2.eval == 19)
```

### 30. `nested computations / generic lifted functions` (old:862-875)

The lifted generic function is used through `map`, `flatMap`, and `andThen` in one chain; only the
last call's result survives, and the nesting holds through all three combinators.

PORTABLE, verbatim.

```scala
def f(str: String): Int < TestEffect2 = TestEffect2(str)

def g[B](f: String => B): B < TestEffect1 =
    TestEffect1(1).map(f).flatMap(_ => f("a")).andThen(f("b"))

val nested: Int < TestEffect2 < TestEffect1 = g(f)

val result = TestEffect1.run(nested.map(TestEffect2.run))
assert(result.eval == 11)

val result2 = TestEffect1.run(TestEffect2.run(nested.flatten))
assert(result2.eval == 11)
```

## 1.2 Present in proto but weaker (6)

These proto cases carry the old name, so they do not show up as missing, but they assert less. Each
delta is a real coverage loss.

1. **`a pure function passes to map point-free`** (old:489-493). Old evaluates
   `Eval(answerAsk(41)(ask.map(f))) == 42`; proto (`:151-157`) only `assertCompiles` on the same
   text. The runtime assertion is gone.
2. **`a generic function passes to map point-free`** (old:495-501). Same downgrade to
   `assertCompiles` (proto `:159-166`); old evaluated through both `Ask` and `Say`.
3. **`nested computations / basic nesting operations`** (old:678-684). Old runs
   `TestEffect1.run(nested.flatten)`; proto (`:448-454`) substitutes `nested.map(c => c)`. The
   flatten-through-a-handler path is not exercised by this case.
4. **`nested computations / multiple effects`** (old:686-695). Old asserts twice, the second via
   `comp.flatten.handle(TestEffect2.run).handle(TestEffect1.run)`; proto (`:456-463`) keeps only the
   `map` variant and leaves a stray blank line where the second assertion was.
5. **`nested computations / method returning nested computation`** (old:810-818). Old uses
   `compute(200).flatten`; proto (`:565-571`) uses `.map(c => TestEffect1.run(c))`.
6. **`nested computations / nested effect suspensions`** (old:820-829). Old asserts both
   `nested.map(TestEffect2.run)` and `TestEffect1.run(TestEffect2.run(nested.flatten))`; proto
   (`:573-580`) drops the flatten assertion.

The common thread in 3-6: `flatten` over a nested value that crosses a handler is systematically
un-asserted in the proto file's nested block. Proto keeps `flatten` coverage only in the top-level
cases ("flatten runs a nested payload", "flatten merges the effects of both layers", "flatten over a
doubly nested value denied by the budget strips exactly one level").

## 1.3 Already covered in proto PendingTest (56, names only)

a settled payload evaluates to itself; eval returns a pending payload without running it; map
receives a pending payload as a value; a map can return a computation as its value; a payload
returned past the budget stays a value; a handler applies done to a settled payload without driving
it; a region returns a foreign payload untouched; an answer can be a computation value; a captured
continuation accepts a computation answer; mapping over a payload derives a new payload; a payload
handles inside map; a generic function nests its result across effects; a loop can end its region
with a computation result; a fused continuation receives the answer payload; double nesting round
trips; a loop answer payload delivers unwrapped through a bare suspension; a suspended loop answer
delivering a payload resumes unwrapped; a stateful loop answer payload delivers unwrapped through a
bare suspension; a loop can end its region effectfully with a computation result; a fused handler
continuation receives a payload as a value; flatMap chains a settled value into an effectful
computation; flatMap receives a pending payload as a value; andThen sequences effects and discards
the value; andThen leaves a discarded payload untouched; unit discards the result; eval returns the
settled result; evalNow returns a settled value; evalNow is absent for a suspended computation;
evalNow returns a payload unwrapped; flatten runs a nested payload; flatten merges the effects of
both layers; handle applies transformations fluently; map on a settled value runs eagerly; map
composes; flatMap and andThen compose; a computation as a value round-trips through the nested box;
a computation as a value survives mapping; the extension surface applies to a val of nested type; a
pending value does not lift into a nested computation implicitly; a kyo module does not lift into a
computation; deep map chains evaluate; deep nested computations evaluate; construction past the
safepoint budget rescues instead of overflowing; a long map tower on a rescued computation evaluates
in bounded stack; eval does not compile for pending effects; nested computations / map over a nested
value denied by the budget defers the wrapped value; nested computations / flatMap over a nested
value denied by the budget defers the wrapped value; nested computations / flatten over a doubly
nested value denied by the budget strips exactly one level; nested computations / andThen over a
nested value denied by the budget discards it unevaluated; nested computations / unit over a nested
value denied by the budget discards it unevaluated; nested computations / map on nested; nested
computations / unit on nested; nested computations / andThen on nested; nested computations / handle
on nested; nested computations / evalNow accepts nested computations; depth leaked by throwing maps
resets at the eval loop.

(Proto also adds one case with no old counterpart: "a doubly nested value denied by the budget strips
exactly one level under map".)

---

# 2. EvalTest

## 2.1 Portable, missing from the proto file (29)

### Helpers the ports need

The proto file has `ask`, `askWith`, `say`, `answerAsk`, `recordSay`, `box`. It lacks the `VarE`
effect (cases 20, 21, 27) and the `trailing` fixture (cases 23, 24, 25):

```scala
sealed trait VarE extends ArrowEffect[Const[Int => Int], Const[Int]]

def varOp(f: Int => Int): Int < VarE = ArrowEffect.suspend[Any](Tag[VarE], f)

def runVar[A, S](init: Int)(v: A < (VarE & S)): A < S =
    ArrowEffect.handleLoopState(Tag[VarE], init, v)(
        [C] =>
            (state, f) =>
                val v2 = f(state)
                Loop.continue(v2, v2)
        ,
        (_, a) => a
    )
```

Port note: `Loop.continue(v2, v2)` becomes `Loop.continue(v2, v2: Int < Any)`.

```scala
def trailing(depth: Int, runs: Array[Int]): Int < Ask =
    var r: Int < Ask = ask
    for i <- 0 until depth do
        val j = i
        r = r.map { x =>
            runs(j) += 1
            x + 1
        }
    end for
    r
end trailing
```

The clause-scope block also shares one fixture:

```scala
val innerProgram: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
```

---

### values and map

#### 1. `evaluates andThen and unit` (old:74-77)

`andThen` yields the second value, `unit` discards to `()`, both under a full eval.

PORTABLE, verbatim modulo `Eval` to `eval`. Equivalent coverage exists in proto PendingTest
("andThen sequences effects and discards the value", "unit discards the result").

```scala
assert(Eval((1: Int < Any).andThen(2: Int < Any)) == 2)
assert(Eval((1: Int < Any).unit) == ())
```

#### 2. `evalNow is present only for settled values` (old:79-82)

PORTABLE, verbatim. Equivalent coverage exists in proto PendingTest ("evalNow returns a settled
value", "evalNow is absent for a suspended computation").

```scala
assert((1: Int < Any).evalNow.contains(1))
assert(ask.evalNow.isEmpty)
```

#### 3. `a deep map tower over a suspension evaluates in bounded stack` (old:90-94)

100k trailing maps stacked over a live suspension, then handled: the stack stays bounded when the
tower sits over an operation rather than over a settled value. Proto keeps the settled-value tower
("a long map tower evaluates in bounded stack") but not the over-a-suspension one.

PORTABLE, verbatim modulo `Eval` to `eval`.

```scala
@tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
    if n == 0 then v else tower(v.map(_ + 1), n - 1)
assert(Eval(answerAsk(1)(tower(ask, 100000))) == 100001)
```

#### 4. `a nested computation stays data until flattened` (old:114-117)

A boxed computation is inert until `flatten` opens it, at which point its effect joins the row.

PORTABLE, verbatim modulo `Eval` to `eval`. Overlaps proto PendingTest "flatten runs a nested
payload".

```scala
val nested: (Int < Ask) < Any = box(ask.map(_ + 1))
assert(Eval(answerAsk(1)(nested.flatten)) == 2)
```

### handleLoop

#### 5. `regions exit innermost first` (old:259-264)

An `Ask` region nested inside a `Say` region: the inner region's result is mapped before the outer
one sees it, so the arithmetic pins the exit order.

PORTABLE, verbatim modulo `Eval` to `eval`.

```scala
val log   = ListBuffer[String]()
val inner = answerAsk(41)(ask.map(_ + 1)).map(_ * 10)
val outer = recordSay("s", log)(inner).map(_ + 1000)
assert(Eval(outer) == 1420)
```

#### 6. `an effectful answer built by a deferred block resolves on the settled outcome path` (old:273-276)

The clause answers with `Effect.defer(7)`: a deferred answer resolves before the region continues,
on the path where the outcome itself is already settled.

PORTABLE. `Effect.defer` exists (`Effect.scala:105`); `Loop.continue(Effect.defer(7))` becomes
`Loop.continue((), Effect.defer(7))`, and the no-`done` `handleLoop` overload is
`ArrowEffect.scala:247`.

```scala
val r = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))([C] => _ => Loop.continue(Effect.defer(7)))
assert(Eval(r) == 8)
```

#### 7. `a deferred clause outcome resolves before the region continues` (old:278-283)

The whole `Loop` outcome, not just the answer, is produced by a deferred block, across a
multi-iteration region.

PORTABLE. Proto clauses already return effectful outcomes (`... < (S & S2)` in the signature), so
`Effect.defer(Loop.continue((), 1: Int < Any))` types.

```scala
def loop(i: Int): Int < Ask =
    if i < 3 then ask.map(a => loop(i + a)) else i
val r = ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Effect.defer(Loop.continue(1)))
assert(Eval(r) == 3)
```

#### 8. `a clause that suspends before a done runs outside its region` (old:297-309)

A clause suspends on `Say` and then fires `Loop.done`: the suspension is answered by the outer
handler, the body's remainder never runs, and only the outer handler logs. Proto keeps the
`continue` sibling ("a clause that suspends before its outcome runs outside its region") but not the
`done` one.

PORTABLE, verbatim modulo `Eval` to `eval` and `Loop.done(-1)` unchanged (`Loop.done` takes a plain
value).

```scala
var reached = false
val log     = ListBuffer[String]()
val program: Int < Ask = ask.map { a =>
    reached = true
    a + 1
}
val askScope: Int < Say =
    ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => say("pre").map(_ => Loop.done(-1)), a => a)
assert(Eval(recordSay("s", log)(askScope)) == -1)
assert(!reached)
assert(log.toList == List("s"))
```

#### 9. `a done climbs past an inner region without running its remainder` (old:461-473)

`Loop.done` from an outer region unwinds through an inner region that is mid-flight: the inner
region's trailing map never runs, and the inner handler's own operations already logged stay logged.

PORTABLE, verbatim modulo `Eval` to `eval`.

```scala
val log                        = ListBuffer[String]()
var innerExit                  = false
val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
val mapped = recordSay("s", log)(program).map { v =>
    innerExit = true
    v
}
val r = ArrowEffect.handleLoop(Tag[Ask], mapped)([C] => _ => Loop.done(-1), a => a)
assert(Eval(r) == -1)
assert(!innerExit)
assert(log.toList == List("s"))
```

#### 10. `a done fired while a clause outcome settles climbs to its own region` (old:475-486)

An outer `Say` handler fires `Loop.done` while an inner `Ask` clause's outcome is still settling: the
done belongs to the `Say` region, and the `Ask` body's continuation is abandoned.

PORTABLE. Both `handleLoop` calls use the no-`done` overload (`ArrowEffect.scala:247`);
`Loop.continue(41)` becomes `Loop.continue((), 41: Int < Any)`.

```scala
var reached = false
val program: Int < Ask = ask.map { a =>
    reached = true
    a + 1
}
val askScope =
    ArrowEffect.handleLoop(Tag[Ask], program)([C] => _ => say("pre").map(_ => Loop.continue(41)))
val r = ArrowEffect.handleLoop(Tag[Say], askScope)([C] => _ => Loop.done(-9))
assert(Eval(r) == -9)
assert(!reached)
```

### handleLoopState

#### 11. `composes a state update with a done` (old:546-554)

The clause counts down through state and switches to `Loop.done` when the budget runs out, before
the body finishes.

PORTABLE. `Loop.continue(remaining - 1, 1)` becomes `Loop.continue(remaining - 1, 1: Int < Any)`.

```scala
def go(n: Int): Int < Ask =
    if n == 0 then 0 else ask.map(_ => go(n - 1))
val r = ArrowEffect.handleLoopState(Tag[Ask], 3, go(5))(
    [C] => (remaining, _) => if remaining > 0 then Loop.continue(remaining - 1, 1) else Loop.done(-1),
    (_, a) => a
)
assert(Eval(r) == -1)
```

#### 12. `state threads through updates` (old:566-569)

The `VarE` shape: three successive state updates, the last reading the accumulated value. The only
case exercising a clause whose answer is derived from applying the operation's input to the state.

PORTABLE with the `VarE`/`runVar` fixture above.

```scala
val program = varOp(_ => 10).map(_ => varOp(_ + 5)).map(a => varOp(identity).map(b => a + b))
assert(Eval(runVar(0)(program)) == 30)
```

#### 13. `state updates survive an inner region's exit` (old:571-578)

A `Say` region opens and closes inside a `VarE` region; the state written before the inner region is
still visible after it exits.

PORTABLE with the `VarE`/`runVar` fixture.

```scala
val log                       = ListBuffer[String]()
val inner: Int < (VarE & Say) = varOp(_ => 7).map(_ => say("x")).map(_ => 1)
val innerScope                = recordSay("s", log)(inner)
val program                   = innerScope.map(_ => varOp(identity))
assert(Eval(runVar(0)(program)) == 7)
assert(log.toList == List("s"))
```

#### 14. `a done fired while a stateful clause outcome settles climbs to its own region` (old:591-603)

The stateful twin of case 10: the outer `Say` region's `Loop.done` wins while the stateful `Ask`
clause's outcome is settling.

PORTABLE; both handlers use the clause-only overloads (`ArrowEffect.scala:358` and `:247`), and
`Loop.continue(n + 1, n)` becomes `Loop.continue(n + 1, n: Int < Any)`.

```scala
var reached = false
val program: Int < Ask = ask.map { a =>
    reached = true
    a + 1
}
val askScope = ArrowEffect.handleLoopState(Tag[Ask], 0, program)(
    [C] => (n, _) => say("pre").map(_ => Loop.continue(n + 1, n))
)
val r = ArrowEffect.handleLoop(Tag[Say], askScope)([C] => _ => Loop.done(-9))
assert(Eval(r) == -9)
assert(!reached)
```

### clause scope

The old file's most load-bearing block: a handler's clause is the handler's own code, so its effects
belong to the handlers outside the region, never to the ones the body installed inside it. Proto
covers two of the eight shapes (see 2.2); six are missing. The old file's own explanatory comments
(old:630-632, old:681-685, old:686-689) are worth carrying over with the ports.

#### 15. `a stateful clause's suspension is answered outside its scope` (old:637-646)

PORTABLE; clause-only `handleLoopState` overload, `Loop.continue(n + 1, 41)` becomes
`Loop.continue(n + 1, 41: Int < Any)`.

```scala
val log      = ListBuffer[String]()
val sayInner = recordSay("inner", log)(innerProgram)
val askScope = ArrowEffect.handleLoopState(Tag[Ask], 0, sayInner)(
    [C] => (n, _) => say("c").map(_ => Loop.continue(n + 1, 41))
)
val sayOuter = recordSay("outer", log)(askScope)
assert(Eval(sayOuter) == 42)
assert(log.toList == List("inner", "outer"))
```

#### 16. `a handleCont clause's suspension is answered outside its scope` (old:648-658)

The same law for a continuation-capturing clause: the clause's `say` is answered by the outer
handler even though the captured continuation re-enters the region.

PORTABLE, verbatim modulo `Eval` to `eval`.

```scala
val log                         = ListBuffer[String]()
val sayInner: Int < (Ask & Say) = recordSay("inner", log)(innerProgram)
val askScope = ArrowEffect.handleCont(Tag[Ask], sayInner)(
    [C] => (_, cont) => say("c").map(_ => cont(41)),
    a => a
)
val sayOuter = recordSay("outer", log)(askScope)
assert(Eval(sayOuter) == 42)
assert(log.toList == List("inner", "outer"))
```

#### 17. `a clause's suspension before done is answered outside its scope` (old:660-668)

Distinct from case 8: here the body is the shared `innerProgram` with a `Say` region inside, so the
assertion is about which of the two `Say` handlers answers the clause.

PORTABLE, verbatim modulo `Eval` to `eval`.

```scala
val log      = ListBuffer[String]()
val sayInner = recordSay("inner", log)(innerProgram)
val askScope =
    ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => say("c").map(_ => Loop.done(-1)), a => a)
val sayOuter = recordSay("outer", log)(askScope)
assert(Eval(sayOuter) == -1)
assert(log.toList == List("inner", "outer"))
```

#### 18. `a clause's own-tag suspension before its outcome is answered by the successor` (old:686-702)

The suspension half of the own-tag law: a clause suspending on its own tag happens at row `S`,
outside the region, so the successor handler answers it. Livelock-bounded by the counter. Proto has
only the answer half ("an effectful answer's own-tag re-raise is answered by this handler").

PORTABLE; both `handleLoop` calls use the clause-only overload, and the two `Loop.continue` calls
take `((), x + 100: Int < Any)` and `((), 5: Int < Any)`.

```scala
var clauseRuns = 0
val askScope: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
    [C] =>
        _ =>
            clauseRuns += 1
            if clauseRuns > 3 then throw new IllegalStateException("clause answered its own suspension")
            ask.map(x => Loop.continue(x + 100))
)
val outerAsk = ArrowEffect.handleLoop(Tag[Ask], askScope)([C] => _ => Loop.continue(5))
// the clause suspends on ask, answered by outerAsk with 5, plus 100 -> 105, plus 1
assert(Eval(outerAsk) == 106)
assert(clauseRuns == 1)
```

#### 19. `a clause does not see handlers inside its own scope` (old:704-712)

The negative form: with no outer `Say` handler installed, the clause's `say` must fail as unhandled
rather than being answered by the region's own inner `Say` handler.

PORTABLE; the message assertion becomes `"unhandled suspension"`.

```scala
val log      = ListBuffer[String]()
val sayInner = recordSay("inner", log)(innerProgram)
val askScope =
    ArrowEffect.handleLoop(Tag[Ask], sayInner)([C] => _ => Loop.continue(say("c").map(_ => 41)), a => a)
val ex = intercept[Throwable](Eval(askScope.asInstanceOf[Int < Any]))
assert(ex.getMessage.contains("Unexpected pending effect"))
assert(log.toList == List("inner"))
```

#### 20. `a leaked clause effect cannot observe the region's inner state` (old:714-725)

The strongest form of the law: if the clause's `varOp` were answered inside the region it would read
the region's live state, so answering outside must leave it unhandled.

PORTABLE with the `VarE`/`runVar` fixture; message becomes `"unhandled suspension"`, and
`Loop.continue(v)` becomes `Loop.continue((), v: Int < Any)`.

```scala
val program: Int < (Ask & VarE) = varOp(_ => 7).map(_ => ask)
val varInner                    = runVar(0)(program)
val askScope = ArrowEffect.handleLoop(Tag[Ask], varInner)(
    [C] => _ => varOp(identity).map(v => Loop.continue(v)),
    a => a
)
val ex = intercept[Throwable](Eval(askScope.asInstanceOf[Int < Any]))
assert(ex.getMessage.contains("Unexpected pending effect"))
```

### a captured continuation is a value

#### 21. `a continuation folded from the eval stack runs every pending map exactly once` (old:809-816)

A continuation captured under a tower of trailing maps, at depths 8 and 64, runs each pending map
exactly once when applied once.

PORTABLE with the `trailing` fixture.

```scala
for depth <- List(8, 64) do
    val runs = new Array[Int](depth)
    val r: Int < Any =
        ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))([C] => (_, cont) => cont(0), a => a)
    assert(Eval(r) == depth)
    assert(runs.forall(_ == 1))
```

#### 22. `a continuation applied twice replays trailing maps twice at any depth` (old:818-827)

Two shots replay the whole trailing tower, once per shot, with the arithmetic pinning both results.

PORTABLE with the `trailing` fixture.

```scala
for depth <- List(8, 64) do
    val runs = new Array[Int](depth)
    val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))(
        [C] => (_, cont) => cont(0).map(a => cont(10).map(b => a + b)),
        a => a
    )
    assert(Eval(r) == depth + (10 + depth))
    assert(runs.forall(_ == 2))
```

#### 23. `stays valid after its eval completes, replaying the trailing maps once per shot` (old:829-848)

The continuation is stored, its eval completes with the region abandoned, and each later shot in a
fresh evaluation replays the tower once. Also asserts the tower has run zero times at capture.

PORTABLE with the `trailing` fixture; the stored type becomes `Maybe[Int => Int < Ask]` with
`stored = Maybe(cont(_))`, per proto EvalTest:512-519.

```scala
for depth <- List(8, 64) do
    val runs                                = new Array[Int](depth)
    var stored: Maybe[Arrow[Int, Int, Ask]] = Maybe.empty
    val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], trailing(depth, runs))(
        [C] =>
            (_, cont) =>
                stored = Maybe(cont)
                -1
        ,
        a => a
    )
    assert(Eval(first) == -1)
    assert(runs.forall(_ == 0))
    val k = stored.get
    assert(Eval(answerAsk(0)(k(100))) == 100 + depth)
    assert(runs.forall(_ == 1))
    assert(Eval(answerAsk(0)(k(200))) == 200 + depth)
    assert(runs.forall(_ == 2))
```

### top level

#### 24. `an operation no region in the row handles is a bug` (old:856-861)

An operation whose tag no installed region handles fails, even though a different region in the row
is present and running.

PORTABLE; message becomes `"unhandled suspension"`.

```scala
val program: Int < (Ask & Say) = say("x").map(_ => ask)
val r                          = answerAsk(41)(program)
val ex                         = intercept[Throwable](Eval(r.asInstanceOf[Int < Any]))
assert(ex.getMessage.contains("Unexpected pending effect"))
```

#### 25. `a throw inside a region leaves no findable handler behind` (old:878-888)

After a throw unwinds out of a stateful region, no stale handler is discoverable on that thread: a
bare operation still fails, and a fresh region on the same thread still works.

PORTABLE; message becomes `"unhandled suspension"`, `Loop.continue(st + 1, st)` becomes
`Loop.continue(st + 1, st: Int < Any)`.

```scala
def stateful[A](v: A < Ask): A < Any =
    ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
        [C] => (st, _) => Loop.continue(st + 1, st),
        (_, a) => a
    )
intercept[RuntimeException](Eval(stateful(ask.map(_ => (throw new RuntimeException("boom")): Int))))
val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
assert(ex.getMessage.contains("Unexpected pending effect"))
assert(Eval(stateful(ask.map(a => ask.map(b => a * 10 + b)))) == 1)
```

#### 26. `a throw escaping a root eval leaves the safepoint depth unchanged` (old:895-915)

Regression guard for a real leak: a throw escaping a root eval that skipped `Safepoint.exit` lost one
unit of per-thread depth per throw, silently degrading every later computation on that thread.
Measured at exactly one of 512 lost per throw before the fix.

PORTABLE. `Safepoint.get/save/restore` all exist, and proto EvalTest:924-954 already samples
`Safepoint.State` the same save-then-restore way and compares with `.equals`. Worth porting early:
it is the one case here whose subject (root-eval exit on the throw path) is a property the proto
evaluator must independently satisfy, so it may fail rather than pass on arrival.

```scala
val slot = Safepoint.get()
// `save` resets as it reads, so reading it back is a save/restore pair
def depth() =
    val d = Safepoint.save(slot)
    Safepoint.restore(slot, d)
    d
end depth
val before = depth()
var caught = 0
var i      = 0
while i < 50 do
    try discard(Eval(answerAsk(1)(ask.map(v => if v > 0 then throw new RuntimeException("boom") else v))))
    catch case _: RuntimeException => caught += 1
    i += 1
end while
assert(caught == 50)
// `equals` rather than `==`: `State` is opaque and carries no `CanEqual`, and a cast to its
// underlying Int would be a new cast for a test's convenience
assert(depth().equals(before))
```

#### 27. `the budget rescues rather than overflowing` (old:917-921)

PORTABLE, verbatim modulo `Eval` to `eval` and a `Period` val (`Safepoint.period()`). Equivalent
coverage exists in proto PendingTest ("construction past the safepoint budget rescues instead of
overflowing"), so this is the lowest-value port of the set.

```scala
def loop(n: Int): Int < Any =
    if n == 0 then 0 else (n: Int < Any).map(_ => loop(n - 1))
assert(Eval(loop(Period * 4)) == 0)
```

### partial evaluation

#### 28. `partial completes when nothing stops` (old:929-931)

The baseline for `Eval.partial`: with no stop requested, a slice runs to completion and the result is
settled.

PORTABLE. `Eval.partial` takes `A < Any` in proto too (`Eval.scala:146`); `evalNow` is `private[kyo]`
and the proto test file is in `kyo.proto.kernel.internal`.

```scala
assert(Eval.partial(answerAsk(21)(ask.map(_ * 2))).evalNow == Maybe(42))
```

#### 29. `a computation held as a value passes through a parked slice intact` (old:952-964)

A boxed computation crosses a park by reference: after the slice resumes, the payload is the same
object, still unevaluated, and evaluating it then gives the right answer.

PORTABLE. Uses the proto `requestStop()` helper in place of `SafepointStop.request()`; the identity
assertion needs the proto box spelling, `Nested.unnest[Int < Any](Eval(parked))` before the `eq`.

```scala
val payload: Int < Any = (3: Int < Any).map(_ + 4)
val v: (Int < Any) < Any =
    Effect.defer {
        SafepointStop.request()
        ()
    }.map(_ => box(payload))
val parked = Eval.partial(v)
assert(parked.evalNow.isEmpty)
val out = Eval(parked)
assert(out.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef])
assert(Eval(out) == 7)
```

## 2.2 Already covered in proto EvalTest (57, names only)

Two of these are covered under a different name, worth recording because they look missing on a
name diff:

- old `clause scope / a clause's effectful answer is answered outside its scope` (old:670-679) is the
  same body, handler, and log assertion as proto `handleLoop / an effectful answer runs under this
  handler with the interior parked` (proto:241-251).
- old `clause scope / the region body's handlers are intact after the clause returns` (old:727-737)
  is the same body, handler, and log assertion as proto `handleLoop / an effectful answer's
  remainder runs inside the interior region` (proto:310-320).

Two more are covered by proto cases carrying a slightly different name:

- old `partial evaluation / a stop already pending ends the slice before it starts` is proto
  `partial evaluation and parking / a stop already pending returns the input before the slice
  starts` (proto asserts strictly more: reference identity of the returned input).
- old `partial evaluation / a parked slice re-enters and completes on the next slice` is covered
  between proto `parks on a pending stop and the parked value resumes to the same answer` and the
  `Eval.partial(back)` re-entry at the end of the stop-already-pending case.

The remaining 53: values and map / a settled value evaluates to itself, map runs strictly on a
settled value, map composes, a long map tower evaluates in bounded stack, deep recursion through map
pays rescues only, a computation held as a value round trips through the box, double nesting round
trips one level per eval, a pending value does not lift into a nested computation implicitly, an eval
inside a map evaluates its argument rather than nesting it, map receives a computation held as a
value unopened; handleCont / answers with the continuation in hand, the captured continuation is
multi-shot, can end the computation without resuming, a settled input applies done strictly, done
applies to the settled result, deep sequential operations are stack safe, a capture crossing an inner
region resumes it without re-running its body, each shot of a multi-shot capture resumes from
capture-time state; handleLoop / answers every operation in place, Loop.done stops the region and
bypasses done, done sees the settled result, a settled input applies done strictly, deep sequential
operations are stack safe, the innermost region of a tag answers, a map after the region applies to
the result, a foreign operation crosses the region in place, a clause that suspends before its
outcome runs outside its region, an effectful answer runs under this handler with the interior
parked, an effectful answer's own-tag re-raise is answered by the successor state, a clause
suspending and then answering effectfully runs the answer under this handler, an effectful answer's
own-tag re-raise is answered by this handler, a clause suspending and then answering with an own-tag
re-raise is answered by this handler, an effectful answer's remainder runs inside the interior
region, an effectful answer's remainder raises the interior's effect with no outer handler for it, a
clause suspending and then answering effectfully keeps the remainder inside the interior region, an
effectful answer's fused remainder runs inside the interior region, an effectful answer's fused
remainder raises the interior's effect with no outer handler for it, a clause suspending and then
answering effectfully keeps the fused remainder inside the interior region, a computation held as a
value crosses a handler as a value, an answer that is a computation held as a value stays a value;
handleLoopState / threads state through operations, done observes the final state, Loop.done bypasses
done, state survives a foreign crossing, a stateful clause that suspends threads its state through
the park, a stateful effectful answer's remainder runs inside the interior region, a stateful
effectful answer's fused remainder runs inside the interior region; a captured continuation is a
value / resumes after its region completed in a fresh evaluation each shot from capture-time state,
resumes under a later region of the same tag which answers the remainder, a shot evaluated inside the
clause leaves the region intact for the next; top level / an unhandled operation is a bug, a nested
eval shares the thread's stack and sees none of the outer regions, an eval cleans its stack after a
throw.

(Proto adds a large body of cases with no old counterpart: the whole release/owed-dumps surface, the
exit law, recovery, and the parking cases built on `Kyo.Park`. Those are outside this comparison.)

---

# 3. EvalCaptureTowerTest

No proto counterpart file exists. All 3 cases are PORTABLE and missing. The file's own docstring
explains why it is kept separate ("Kept apart from the corpus so it can be run and debugged alone"),
which argues for creating
`kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalCaptureTowerTest.scala` rather than
folding these into the proto EvalTest.

Fixtures needed (`ask`, `say` as in proto EvalTest, plus):

```scala
private val Reach = Safepoint.period() / 2
```

`Safepoint.period` is `private[kyo]` (`Safepoint.scala:36`) and the file would sit in
`kyo.proto.kernel.internal`, so it is reachable; proto PendingTest already calls it.

### 1. `a long map tower across captures evaluates in bounded stack` (old:22-27)

20k recursive `ask` operations, each resumed through a captured continuation carrying an extra
trailing map, all in bounded stack.

PORTABLE, verbatim modulo `Eval` to `eval`.

```scala
def loop(i: Int): Int < Ask =
    if i > 20000 then i else ask.map(a => loop(i + a)).map(x => x)
val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(0))([C] => (_, cont) => cont(1), a => a)
assert(Eval(r) == 20001)
```

### 2. `a capture across an inner region keeps the region as an entry` (old:29-44)

A capture taken past half the safepoint period of trailing maps, with an inner `Ask` region inside
the captured extent: the region survives the capture as an entry, and the inner clause runs exactly
once.

PORTABLE, verbatim modulo `Eval` to `eval`.

```scala
@tailrec def tower(v: Int < (Ask & Say), n: Int): Int < (Ask & Say) =
    if n == 0 then v else tower(v.map(_ + 1), n - 1)
var seen = List.empty[String]
val inner: Int < Ask = ArrowEffect.handleCont(Tag[Say], tower(ask.map(a => say("x").map(_ => a)), Reach + 8))(
    [C] =>
        (s, cont) =>
            seen = s :: seen
            cont(())
    ,
    a => a
)
val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], inner)([C] => (_, cont) => cont(1), a => a)
assert(Eval(r) == 1 + Reach + 8)
assert(seen == List("x"))
```

### 3. `a deep capture is multi-shot` (old:46-54)

A continuation captured under a tower deeper than half the safepoint period is applied twice, and
both shots replay the full tower.

PORTABLE, verbatim modulo `Eval` to `eval`.

```scala
@tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
    if n == 0 then v else tower(v.map(_ + 1), n - 1)
val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], tower(ask, Reach + 8))(
    [C] => (_, cont) => cont(1).map(r1 => cont(2).map(r2 => r1 * 100000 + r2)),
    a => a
)
assert(Eval(r) == (1 + Reach + 8) * 100000 + (2 + Reach + 8))
```

---

# 4. Suggested port order

Highest value first, by what the missing case protects:

1. **EvalTest clause scope, six cases (15-20).** The block encodes the clause-scope contract across
   clause kinds and outcome shapes, and proto covers only two of the eight shapes. Case 20 (the
   leaked clause effect that must not read the region's inner state) is the one that fails loudest if
   the contract regresses.
2. **EvalTest cases 21-23** (continuation-as-value replay counts at depth) and **EvalCaptureTowerTest
   1-3**. Together these are the only tests that count how many times a trailing tower runs per shot;
   nothing in the proto suite does.
3. **EvalTest 26** (safepoint depth unchanged after a throw escapes a root eval). A silent per-thread
   leak with no wrong-answer symptom; the proto evaluator has to satisfy it independently.
4. **EvalTest 5-14, 24, 25, 28, 29.** Region-exit ordering, deferred clause outcomes, done climbing
   past regions, stateful state survival, unhandled-in-row failure, park payload identity.
5. **PendingTest 27-30** (nested lifted-function shapes) and **1.2's four flatten deltas**. These are
   the surface where old PendingTest is materially thinner in proto.
6. **PendingTest 12-25** (the handle arity block) and **3-11** (the lift block). Mechanical, and the
   lift block partly duplicates proto ImplicitsTest; arities five and six of the pure-function lift
   and arities three through nine of `handle` are the parts with no coverage anywhere.
7. **PendingTest 1, 2, 26 and EvalTest 1, 2, 4, 27.** Duplicates of coverage that already exists
   elsewhere in the proto suite; port for completeness, not for risk.
