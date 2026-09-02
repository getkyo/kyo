# Test-port audit: Effect, ContextEffect, Pending, Arrow

Audit of 52 old kernel test cases that an automated name match found with no exact-name twin in
any proto test file. Every case body was read; every candidate proto case body was read before a
TWIN was recorded.

## Verdict rules applied

- **TWIN**: a proto case asserts the same observable law under another name. Renaming (`Transform`
  to `Step`, `handle` to `handleCont`, `Finalizer.Spent` to `kyo.Closed`) does not break a twin.
  Merely touching the same feature does.
- **MISSING**: the construct exists in the proto and the law is writable there, but no proto case
  pins it.
- **DIVERGENT**: the case tests a construct the proto does not have, or the proto deliberately
  asserts the opposite outcome. Behavioral divergences are labelled `(behavioral)` because they are
  not gaps to port, they are decisions to be aware of.

### On `Effect.catching`

The proto kernel has no `Effect.catching` and no `handleCatching`. `kyo.proto.kernel.Effect`
exposes only `bracket`, `defer` (four arities), and `deferInline`. Recovery is the third `recover`
argument of `ArrowEffect.handleCont` / `handleLoop` / `handleLoopState`, typed
`Throwable => Maybe[A]`, where `Absent` declines. The proto bracket suite already carries a
`recovering` helper (`EffectBracketTest.scala:604`) that reproduces the old standalone guard through
a `Wrap` effect, so a catching case is judged on its law, not on the missing method: TWIN or MISSING
where the law is reproducible, DIVERGENT only where the proto genuinely cannot express it.

---

## shared/src/test/scala/kyo/kernel/EffectTest.scala (44 cases)

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| defer delays evaluation until the eval | TWIN | `proto/kernel/EffectTest.scala`: "defer composes with maps without running early". Same three assertions (`!ran` after construction, the eval yields the body's value, `ran` after). The only delta is an intervening `.map`; the bare-eval result path is separately pinned by "defer evaluates once per eval of a fresh value". |
| defer suspends effects performed by its body | MISSING | No proto case pins that a defer body returning a pending computation stays unrun until the eval and has its effect answered by an enclosing handler as a standalone law. `proto/kernel/internal/EvalTest.scala` "parks on a pending stop and the parked value resumes to the same answer" has that shape incidentally, but its subject is parking. The proto `EffectTest` has no effects at all. |
| defers a pending value | MISSING | The proto "the deferral node" group only feeds settled values (`1: Int < Any`) into `Effect.defer(v, cont)`. Nothing pins that the node's value argument may itself be a pending computation. |
| match | TWIN | `proto/kernel/ArrowEffectTest.scala` "recover": "a settled input's done throw reaches the recovery clause". A throw raised with no operation in between reaches the recovery, which answers with its value. |
| no match | TWIN | `proto/kernel/ArrowEffectTest.scala` "recover": "Absent declines and the failure unwinds to the enclosing region". The old case declined by a `PartialFunction` `MatchError`; the proto declines explicitly with `Maybe.Absent`. Same law: a recovery that does not take the failure lets it through. |
| failure in map | TWIN | `proto/kernel/ArrowEffectTest.scala` "recover": "a region's recovery clause answers a throw raised in its extent". Its body is `ask.map(_ => (throw Boom): Int)`, the same "throw from a map body downstream of a suspension" shape. |
| multiple exception types | MISSING | The proto `recover` is `Throwable => Maybe[A]`, so exception-type dispatch is writable, but no proto case exercises a recovery that selects among several types. Low value: the dispatch is the user function's, not the kernel's. |
| failure in a map after a region | TWIN | `proto/kernel/ArrowEffectTest.scala` "recover, ported": "recovers a throw raised after an inner region's exit". |
| failure in a map after a first region | MISSING | The proto pins a recovery over an inner `handleCont` region's exit, never over a `handleFirst` region's exit. `handleFirst` exists in the proto and runs its clause in the done lane, which is a distinct exit path. |
| failure in a map after a stateful region | MISSING | The proto pins a recovery over an inner `handleCont` region's exit and a `handleLoopState` region's own recovery clause, but never a recovery catching a throw raised after a stateful region exits. |
| failure after a stateful region reached through a continuation | MISSING | No proto case installs a recovery, suspends, is answered by an outer handler, and then reaches a stateful region plus a throw through the resumed continuation. |
| catching catches past the budget rescue | MISSING | `proto/kernel/internal/EvalTest.scala` "regions that fail and recover in sequence cost no stack" runs 10000 recovering regions but pins stack cost, not a rescue landing inside one guarded extent between the guard's install and the throw. The old case burns `Period * 2` steps inside the guard deterministically. |
| catching catches past the budget inside a stateful region | MISSING | Same gap, with the burn inside a `handleLoopState` body and the guard outside the region. Nothing in the proto combines a budget-crossing burn with a recovery. |
| catching does not reach into a boxed computation | MISSING | `proto/kernel/ArrowEffectTest.scala` "recover, ported" / "a computation held as a value passes through untouched" pins that a boxed payload crosses a recovering region unopened, but has no throw. The old law is the failure path: a throw raised when the boxed computation is later evaluated escapes the guard that surrounded its construction. |
| catching guards a stateful region across a park | MISSING | No proto case combines a `recover` clause with a park. The `park` group in `ArrowEffectTest` uses no recovery, and no parking case in `EvalTest` installs one. The law is that the recovery is a stack entry that survives the park snapshot and comes back. |
| a stateful region threads state under catching | TWIN | `proto/kernel/ArrowEffectTest.scala` "recover": "a stateful region's recovery clause receives the live state, not the install-time state". Its `-102` result proves the state advanced twice while a recovery was installed. |
| defer with catching | MISSING | No proto case builds a recovery inside a deferred body. Writable as `Effect.defer(recovering(...)(...))` or with a handler's `recover` argument. |
| combining multiple effects | MISSING | No proto case composes `Effect.defer` and a recovery in one for-comprehension. `proto/kernel/PendingTest.scala` "a for-comprehension chains through flatMap and map" covers the comprehension alone, with no defer and no recovery. |
| releases after the use completes, not at the boundary | TWIN | `proto/kernel/EffectBracketTest.scala` "mixed with other kernel features": "a bracket releases before the next one acquires". Its `acquire a, release a, acquire b, release b` log pins both halves: the release is spliced where the use ends, and what follows the bracket runs after it, not at the eval boundary. |
| releases when the use throws, and the exception still propagates | TWIN | `proto/kernel/EffectBracketTest.scala` "bracket": "a use that throws during application still releases". The old use lambda also throws directly during application. |
| releases when the use suspends and the continuation is answered | TWIN | `proto/kernel/EffectBracketTest.scala` "captured continuations": "a captured continuation resumed in the clause completes the bracket there". |
| releases when a clause receives the continuation and never applies it | TWIN | `proto/kernel/EffectBracketTest.scala` "captured continuations": "a discarded captured continuation still releases the bracket". |
| a handleLoop clause that stops the computation still releases | TWIN | `proto/kernel/EffectBracketTest.scala` "bracket": "a loop clause answering done releases a bracket opened inside". Same `Loop.done(-1)` clause, same release assertion. |
| releases exactly once when the use completes and the eval then ends | TWIN | `proto/kernel/EffectBracketTest.scala` "bracket": "releases exactly once". |
| nested brackets release innermost first | TWIN | `proto/kernel/EffectBracketTest.scala` "mixed with other kernel features": "nested brackets complete innermost first". The completing path, inner release before outer. |
| nested brackets both release when the inner use throws | TWIN | `proto/kernel/EffectBracketTest.scala` "bracket": "nested brackets release innermost first on failure". |
| sequential brackets each release | TWIN | `proto/kernel/EffectBracketTest.scala` "mixed with other kernel features": "a bracket releases before the next one acquires". |
| the release itself may be a deferred computation | DIVERGENT | Construct: the effectful release. Old `Effect.bracket` takes `release: A => Any < Any` (`kernel/Effect.scala:134`) and the eval runs the computation it returns. The proto takes `release: (A, Maybe[Throwable]) => Unit` (`proto/kernel/Effect.scala:35-37`), so a returned computation would be discarded unevaluated. |
| a continuation held past the end of the eval refuses to run again | TWIN | `proto/kernel/EffectBracketTest.scala` "multi-shot clauses": "a continuation held past the end of the eval refuses every time it is applied". The single-application law is its first assertion; `kyo.Closed` replaces `Finalizer.Spent`. |
| the release receives the outcome: the result on completion, the failure on a throw | DIVERGENT | Construct: the `Result`-typed release outcome. Old overload is `(A, Result[Any, B]) => Any < Any` (`kernel/Effect.scala:140`); the proto outcome is `Maybe[Throwable]`, which cannot carry the success value. The adapted law is pinned by `EffectBracketTest.scala` "releases with Absent on completion, after use" and "releases with the failure when the use throws". |
| a park evaluated twice releases its resource once | DIVERGENT (behavioral) | The proto asserts the opposite outcome. `proto/kernel/EffectBracketTest.scala` "parks, budget, and stack safety" / "a park evaluated twice refuses the second evaluation after release" evaluates the park twice and expects `intercept[kyo.Closed]` on the second, where the old expects `42` again. The proto's `reenter` hook throws `Closed` once the cell is spent (`proto/kernel/Effect.scala:56-58`). |
| abandoning a parked bracket releases with the abandoned outcome, and a later resume is harmless | DIVERGENT (behavioral) | Two changes. Abandonment is `Eval.release(v, cause)` with an explicit cause, not `Eval.finalizeResources(v)` with an intrinsic `Finalizer.Abandoned`. And a later resume is refused with `kyo.Closed`, not harmless. Release half pinned by `EffectBracketTest.scala` "releases when a parked remainder is abandoned"; refusal half by "a stop landing as the acquire settles still installs the region". |
| abandoning nested parked brackets releases innermost first | TWIN | `proto/kernel/EffectBracketTest.scala` "mixed with other kernel features": "two stacked brackets abandoned together release innermost first". |
| abandoning a park with no outstanding releases is a no-op | MISSING | `proto/kernel/internal/EvalTest.scala` "release of a settled value or an obligation-free computation owes nothing" releases a settled value and an unstarted computation, never a parked value, and never asserts that the park still evaluates to its answer afterwards. |
| abandoning a slice parked in the acquire window still releases | TWIN | `proto/kernel/EffectBracketTest.scala` "bracket": "a stop landing as the acquire settles still installs the region". Same acquire-window stop, same `count == 1` after abandonment. |
| an acquire that throws owes no release | TWIN | `proto/kernel/EffectBracketTest.scala` "mixed with other kernel features": "a failing acquire never releases". |
| a release may itself bracket | DIVERGENT | Same construct as "the release itself may be a deferred computation": the proto release returns `Unit` strictly, so a nested `Effect.bracket` built inside it would never be evaluated. |
| a release that throws on the completing path surfaces | TWIN | `proto/kernel/EffectBracketTest.scala` "finalizer failures": "a release that throws on completion fails the computation and releases the outer bracket". Sibling: "a release that throws on completion runs exactly once". |
| a release that throws does not stop the releases after it | TWIN | `proto/kernel/EffectBracketTest.scala` "captured continuations": "a throwing release on the unwind does not starve the ones after it". Sibling in `EvalTest.scala`: "a throwing release does not starve the ones after it". |
| a release that throws while the eval is already failing is suppressed onto the original | TWIN | `proto/kernel/EffectBracketTest.scala` "finalizer failures": "a release failure on the unwind is suppressed onto the failure". The proto's failing path is the use rather than a throwing clause; the pinned law, that the release failure attaches to the failure that is leaving rather than replacing it, is the same. |
| a resource a multi-shot clause shares is released at the first branch, and the second is refused | TWIN | `proto/kernel/EffectBracketTest.scala` "mixed with other kernel features": "a multi-shot capture over a bracket refuses the second shot" (one completing release, `kyo.Closed` on the second shot). Sibling: "no branch of a multi-shot clause reads a resource that was already released" pins that only the first branch ran. |
| a multi-shot handleFirst clause is refused at its second branch | DIVERGENT (behavioral) | The proto asserts the opposite. `proto/kernel/EffectBracketTest.scala` "multi-shot clauses" / "a handleFirst clause runs before the release its remainder runs after" asserts `seen == List()`, no branch runs at all, because the release lands before the remainder. The old asserts `seen == List("branch 10")`, the first branch ran. |
| a recovery outside the bracket runs after the release, which is told the failure | TWIN | `proto/kernel/EffectBracketTest.scala` "mixed with other kernel features": "a bracket under a recovering handler releases with the failure, before the recovery". Same `List("release", "recover")` order, same failure in the outcome. |

**EffectTest.scala: TWIN 25 / MISSING 13 / DIVERGENT 6**

---

## shared/src/test/scala/kyo/kernel/ContextEffectTest.scala (1 case)

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| may be a deferred computation | DIVERGENT | Construct: the effectful context-effect release. The old `ContextEffect.handle` takes `release: Maybe[(A, Result[Any, B]) => Any < Any]` and the eval runs the returned computation. The proto's is `release: (A, Throwable) => Unit` with `done: A => Unit` (`proto/kernel/ContextEffect.scala:106,117-118`), both strict, so a deferred body would be built and discarded. |

**ContextEffectTest.scala: TWIN 0 / MISSING 0 / DIVERGENT 1**

---

## jvm-native/src/test/scala/kyo/kernel/ContextEffectThreadingTest.scala (2 cases)

Both cases are the same divergence: the old kernel re-resolves a parked binding against the
enclosing scope of the thread that resumes it; the proto keeps the value the park captured. The
proto suite has both cases, renamed, with the opposite expected values.

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| a park holding a binding resumes on another thread against that thread's enclosing scope | DIVERGENT (behavioral) | `jvm-native/.../proto/kernel/ContextEffectThreadingTest.scala`: "a park holding a binding resumes on another thread with the binding it captured" asserts `enclosed == 11` (the captured `10 + 1`); the old asserts `enclosed == 111` (re-resolved as `ifDefined(100) + 1`). |
| concurrent resumes under different enclosures do not observe each other's resolution | DIVERGENT (behavioral) | `jvm-native/.../proto/kernel/ContextEffectThreadingTest.scala`: "concurrent resumes under different enclosures each keep the captured binding" asserts `a == 11` and `b == 11`; the old asserts `a == 111` and `b == 1011`. The old law is per-thread re-resolution, the proto law is capture. |

**ContextEffectThreadingTest.scala: TWIN 0 / MISSING 0 / DIVERGENT 2**

---

## shared/src/test/scala/kyo/kernel/PendingTest.scala (3 cases)

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| works with two functions | MISSING | The proto `Pending.handle` has overloads for 1 through 10 functions (`proto/kernel/Pending.scala:100-226`). The proto suite's "handle" group covers arity 1 and arities 3 through 9. Arity 2 is unpinned; "chains handles" chains two separate single-function calls, which is a different overload. |
| works with ten functions | MISSING | The proto has the ten-function overload (`proto/kernel/Pending.scala:226`). The suite stops at nine. |
| displays a settled value through the inner type's Render | TWIN | `proto/kernel/internal/ImplicitsTest.scala` "Render instance": "displays pure values wrapped, inner types via their own Render". Identical body: same `Result.succeed(23)`, same `r.asString(i)` and `render"$i"` assertions against `"Kyo(Success(23))"`. |

**PendingTest.scala: TWIN 1 / MISSING 2 / DIVERGENT 0**

---

## shared/src/test/scala/kyo/ArrowTest.scala (2 cases)

The proto renamed the node: the old `Arrow.Transform` renders `Arrow(pos, snippet)`
(`kyo/Arrow.scala:111`), the proto's renders `Step(site)` (`proto/Arrow.scala:98`). Both proto
cases exercise the same `Transform` node through `Arrow.apply`.

| old case | verdict | proto twin (file: case title) or reason |
| --- | --- | --- |
| a transform reports the frame it was built with | TWIN | `proto/ArrowTest.scala` "frame": "a step reports the frame it was built with". Same construct (`Arrow.apply`), same `frame.position.show.contains("ArrowTest.scala")` assertion. |
| renders a transform with its frame | TWIN | `proto/ArrowTest.scala` "toString": "renders a step with its site". Same node type, same "starts with the node marker and contains the source file" assertions, with `Step(` in place of `Arrow(`. |

**ArrowTest.scala: TWIN 2 / MISSING 0 / DIVERGENT 0**

---

# MISSING case bodies

Quoted verbatim from the old suites. Helpers the ported bodies need, also verbatim from
`shared/src/test/scala/kyo/kernel/EffectTest.scala`:

```scala
    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)

    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]

    def testEffect1(i: Int): String < TestEffect1 =
        ArrowEffect.suspend[Any](Tag[TestEffect1], i)

    def box[A](v: A): A < Any = v

    private val Period = 512

    def burn(n: Int): Int < Any =
        if n == 0 then 0 else (0: Int < Any).map(_ => burn(n - 1))
```

and, for "defers a pending value", the `inc` transform local to the "the deferral node" group:

```scala
        def inc(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
            new Arrow.Transform[Int, Int, Any]:
                def frame                                              = _frame
                def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i + 1))
```

## EffectTest.scala

### defer suspends effects performed by its body (line 36)

```scala
    "defer suspends effects performed by its body" in {
        var ran = false
        val d: Int < Ask = Effect.defer {
            ran = true
            ask.map(_ + 1)
        }
        assert(!ran)
        assert(Eval(answerAsk(41)(d)) == 42)
        assert(ran)
    }
```

### defers a pending value (line 123, in the "the deferral node" group)

```scala
        "defers a pending value" in {
            assert(Eval(answerAsk(41)(Effect.defer(ask, inc))) == 42)
        }
```

### multiple exception types (line 178, in the "catching" group)

```scala
        "multiple exception types" in {
            def testCatching(ex: Throwable) = Effect.catching {
                throw ex
            } {
                case _: IllegalArgumentException => "Illegal Argument"
                case _: RuntimeException         => "Runtime"
                case _                           => "Other"
            }

            assert(testCatching(new RuntimeException()).eval == "Runtime")
            assert(testCatching(new IllegalArgumentException()).eval == "Illegal Argument")
            assert(testCatching(new Exception()).eval == "Other")
        }
```

### failure in a map after a first region (line 204, in the "catching" group)

```scala
        "failure in a map after a first region" in {
            val region =
                ArrowEffect.handleFirst(Tag[TestEffect1], testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                    handle = [C] => (input, cont) => cont(input.toString),
                    done = a => (a: String < TestEffect1)
                )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)([C] => (input, cont) => cont(input.toString))
            assert(result.eval == "caught")
        }
```

### failure in a map after a stateful region (line 219, in the "catching" group)

```scala
        "failure in a map after a stateful region" in {
            val region = ArrowEffect.handleLoopState(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                // the clause hands the answer back in the outcome and the region resumes with it
                [C] => (state, input) => Loop.continue(state + 1, (input * state).toString)
            )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "caught")
        }
```

### failure after a stateful region reached through a continuation (line 232, in the "catching" group)

```scala
        "failure after a stateful region reached through a continuation" in {
            val effect = Effect.catching {
                testEffect1(3).map { prefix =>
                    val region = ArrowEffect.handleLoopState(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                        [C] => (state, input) => Loop.continue(state + 1, (input * state).toString)
                    )
                    region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else prefix + s)
                }
            } {
                case _: RuntimeException => "caught"
            }
            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)([C] => (input, cont) => cont(input.toString))
            assert(result.eval == "caught")
        }
```

### catching catches past the budget rescue (line 247, in the "catching" group)

```scala
        "catching catches past the budget rescue" in {
            val effect = Effect.catching {
                burn(Period * 2).map(_ => (throw new RuntimeException("Test exception")): Int)
            } {
                case _: RuntimeException => -1
            }
            assert(effect.eval == -1)
        }
```

### catching catches past the budget inside a stateful region (line 256, in the "catching" group)

```scala
        "catching catches past the budget inside a stateful region" in {
            val body = testEffect1(1).map(a => burn(Period * 2).map(_ => testEffect1(2).map(b => a + b)))
            val region = ArrowEffect.handleLoopState(Tag[TestEffect1], 7, body)(
                // the clause hands the answer back in the outcome and the region resumes with it
                [C] => (state, input) => Loop.continue(state + 1, (input * state).toString)
            )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "caught")
        }
```

### catching does not reach into a boxed computation (line 270, in the "catching" group)

```scala
        "catching does not reach into a boxed computation" in {
            val fallback: String < TestEffect1 = "caught"
            val boxed = Effect.catching {
                box(testEffect1(1).map(s => (throw new RuntimeException("Test exception")): String))
            } {
                case _: RuntimeException => box(fallback)
            }
            val inner   = boxed.eval
            val handled = ArrowEffect.handleCont(Tag[TestEffect1], inner)([C] => (input, cont) => cont(input.toString))
            intercept[RuntimeException](handled.eval)
        }
```

### catching guards a stateful region across a park (line 285, in the "catching" group)

The comment above the case is part of what the port has to preserve: the old case parks through
`SafepointStop.request()`, which maps to an inline `Safepoint.stop(Thread.currentThread())` plus
`Safepoint.deadline(...)` in the proto.

```scala
        // the original parked here by suspending an effect no handler answered, which a slice used to be
        // allowed to do. It takes `A < Any` now, so the park comes from a stop instead, which tests the same
        // thing more directly: the recovery is a stack entry, so it has to survive the snapshot and come back
        "catching guards a stateful region across a park" in {
            val body = testEffect1(1).map { a =>
                SafepointStop.request()
                testEffect1(2).map(b => a + b)
            }
            val region = ArrowEffect.handleLoopState(Tag[TestEffect1], 7, body)(
                [C] => (state, input) => Loop.continue(state + 1, (input * state).toString)
            )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            val parked = Eval.partial(effect)
            assert(parked.evalNow.isEmpty)
            assert(Eval(parked) == "caught")
        }
```

### defer with catching (line 315)

```scala
    "defer with catching" in {
        val effect = Effect.defer {
            Effect.catching {
                throw new RuntimeException("Test exception")
            } {
                case _: RuntimeException => 42
            }
        }
        assert(effect.eval == 42)
    }
```

### combining multiple effects (line 326)

```scala
    "combining multiple effects" in {
        val effect =
            for
                a <- Effect.defer(1)
                b <- Effect.catching(2 / 0) { case _: ArithmeticException => 2 }
                c <- Effect.defer(3)
            yield a + b + c

        assert(effect.eval == 6)
    }
```

### abandoning a park with no outstanding releases is a no-op (line 751, in the "bracket" group)

```scala
        "abandoning a park with no outstanding releases is a no-op" in {
            val v: Int < Any = Effect.defer {
                SafepointStop.request()
                1
            }.map(_ + 41)
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            Eval.finalizeResources(p)
            assert(Eval(p) == 42)
        }
```

## PendingTest.scala

### works with two functions (line 526, in the "handle" group)

```scala
        "works with two functions" in {
            val result = (5: Int < Any).handle(
                _.map(_ + 1),
                _.map(_ * 2)
            )
            assert(result.eval == 12)
        }
```

### works with ten functions (line 618, in the "handle" group)

```scala
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

---

# Counts

| verdict | EffectTest | ContextEffectTest | ContextEffectThreadingTest | PendingTest | ArrowTest | total |
| --- | --- | --- | --- | --- | --- | --- |
| TWIN | 25 | 0 | 0 | 1 | 2 | 28 |
| MISSING | 13 | 0 | 0 | 2 | 0 | 15 |
| DIVERGENT | 6 | 1 | 2 | 0 | 0 | 9 |
| **total** | **44** | **1** | **2** | **3** | **2** | **52** |

Of the 9 DIVERGENT cases, 4 are construct changes (the effectful release in `Effect.bracket` twice,
the `Result`-typed release outcome, the effectful release in `ContextEffect.handle`) and 5 are
behavioral: the proto refuses re-entry into a spent bracket region with `kyo.Closed` (2 cases), it
releases before a `handleFirst` remainder so no branch runs (1 case), and a parked context binding
carries its captured value instead of re-resolving at the resume site (2 cases).
