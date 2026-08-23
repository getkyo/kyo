package kyo.kernel

import kyo.Arrow
import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.*
import kyo.Result
import kyo.Tag
import kyo.discard
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Finalizer
import kyo.kernel.internal.Kyo
import kyo.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class EffectTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)

    "defer delays evaluation until the eval" in {
        var ran = false
        val d: Int < Any = Effect.defer {
            ran = true
            42
        }
        assert(!ran)
        assert(Eval(d) == 42)
        assert(ran)
    }

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

    "defer composes with maps without running early" in {
        var ran = false
        val d: Int < Any = Effect.defer {
            ran = true
            1
        }
        val r = d.map(_ + 1)
        assert(!ran)
        assert(Eval(r) == 2)
        assert(ran)
    }

    "deferInline delays evaluation until the eval" in {
        var ran = false
        val d: Int < Any = Effect.deferInline {
            ran = true
            7
        }
        assert(!ran)
        assert(Eval(d.map(_ * 6)) == 42)
        assert(ran)
    }

    "defer evaluates once per eval of a fresh value" in {
        var runs = 0
        def d: Int < Any = Effect.defer {
            runs += 1
            runs
        }
        assert(Eval(d) == 1)
        assert(Eval(d) == 2)
    }

    "nested defer calls run innermost last, in order" in {
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
    }

    "the deferral node" - {
        def inc(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
            new Arrow.Transform[Int, Int, Any]:
                def frame                                              = _frame
                def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i + 1))

        "runs the value into its continuation" in {
            assert(Eval(Effect.defer(1: Int < Any, inc)) == 2)
        }

        "runs both continuations in order" in {
            def double(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
                new Arrow.Transform[Int, Int, Any]:
                    def frame                                              = _frame
                    def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) = v.map(i => next(i * 2))
            assert(Eval(Effect.defer(1: Int < Any, inc, double)) == 4)
            assert(Eval(Effect.defer(1: Int < Any, double, inc)) == 3)
        }

        "collapses an identity second continuation into the one-continuation node" in {
            val node = Effect.defer(1: Int < Any, inc, Arrow.id[Int])
            node match
                case d: Kyo.Defer[?, ?, ?, ?] => assert(d.contB eq Arrow.id[Int])
                case other                    => fail(s"expected a deferral node, got $other")
            assert(Eval(node) == 2)
        }

        "defers a pending value" in {
            assert(Eval(answerAsk(41)(Effect.defer(ask, inc))) == 42)
        }
    }

    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]

    def testEffect1(i: Int): String < TestEffect1 =
        ArrowEffect.suspend[Any](Tag[TestEffect1], i)

    sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[Unit]]

    def testEffect2(s: String): Unit < TestEffect2 =
        ArrowEffect.suspend[Any](Tag[TestEffect2], s)

    def box[A](v: A): A < Any = v

    private val Period = 512

    def burn(n: Int): Int < Any =
        if n == 0 then 0 else (0: Int < Any).map(_ => burn(n - 1))

    "catching" - {
        "match" in {
            val effect = Effect.catching {
                throw new RuntimeException("Test exception")
            } {
                case _: RuntimeException => 42
            }

            assert(effect.eval == 42)
        }

        "no match" in {
            intercept[Exception] {
                Effect.catching {
                    throw new Exception("Test exception")
                } {
                    case _: RuntimeException => 42
                }.eval
            }
        }

        "failure in map" in {
            val effect = Effect.catching {
                testEffect1(42).map(_ => (throw new RuntimeException("Test exception")): String)
            } {
                case _: RuntimeException => "caught"
            }

            val result = ArrowEffect.handleCont(Tag[TestEffect1], effect)([C] => (input, cont) => cont(input.toString))

            assert(result.eval == "caught")
        }

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

        "failure in a map after a region" in {
            val region = ArrowEffect.handleLoop(Tag[TestEffect1], testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                [C] => input => Loop.continue(input.toString)
            )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "caught")
        }

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

        "failure in a map after a stateful region" in {
            val region = ArrowEffect.handleLoopState(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                // the old stateful clause applied a continuation it was handed; this one hands the answer
                // back in the outcome and the region resumes with it
                [C] => (state, input) => Loop.continue(state + 1, (input * state).toString)
            )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "caught")
        }

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

        "catching catches past the budget rescue" in {
            val effect = Effect.catching {
                burn(Period * 2).map(_ => (throw new RuntimeException("Test exception")): Int)
            } {
                case _: RuntimeException => -1
            }
            assert(effect.eval == -1)
        }

        "catching catches past the budget inside a stateful region" in {
            val body = testEffect1(1).map(a => burn(Period * 2).map(_ => testEffect1(2).map(b => a + b)))
            val region = ArrowEffect.handleLoopState(Tag[TestEffect1], 7, body)(
                // the old stateful clause applied a continuation it was handed; this one hands the answer
                // back in the outcome and the region resumes with it
                [C] => (state, input) => Loop.continue(state + 1, (input * state).toString)
            )
            val effect = Effect.catching {
                region.map(s => if s.nonEmpty then throw new RuntimeException("Test exception") else s)
            } {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "caught")
        }

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

        // the original parked here by suspending an effect no handler answered, which a slice used to be
        // allowed to do. It takes `A < Any` now, so the park comes from a stop instead, which tests the same
        // thing more directly: the recovery is a stack entry, so it has to survive the snapshot and come back
        "catching guards a stateful region across a park" in {
            val body = testEffect1(1).map { a =>
                discard(Safepoint.stop(Thread.currentThread()))
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

        "a stateful region threads state under catching" in {
            val region = ArrowEffect.handleLoopState(Tag[TestEffect1], 7, testEffect1(1).map(a => testEffect1(2).map(b => a + b)))(
                // the old stateful clause applied a continuation it was handed; this one hands the answer
                // back in the outcome and the region resumes with it
                [C] => (state, input) => Loop.continue(state + 1, (input * state).toString)
            )
            val effect = Effect.catching(region) {
                case _: RuntimeException => "caught"
            }
            assert(effect.eval == "716")
        }
    }
    //
    //
    // "defer with catching" in {
    //     val effect = Effect.defer {
    //         Effect.catching {
    //             throw new RuntimeException("Test exception")
    //         } {
    //             case _: RuntimeException => 42
    //         }
    //     }
    //     assert(effect.eval == 42)
    // }
    //
    // "combining multiple effects" in {
    //     val effect =
    //         for
    //             a <- Effect.defer(1)
    //             b <- Effect.catching(2 / 0) { case _: ArithmeticException => 2 }
    //             c <- Effect.defer(3)
    //         yield a + b + c
    //
    //     assert(effect.eval == 6)
    // }
    //
    // // Parked with the removal of ContextEffect and Effect.detach from kyo-kernel2.
    // // Restore against the replacement design.
    // "detach" - {
    //
    //     sealed trait TestCtx extends ContextEffect[Int]
    //
    //     def testCtx: Int < TestCtx = ContextEffect.suspend(Tag[TestCtx])
    //
    //     // a computation nested under detach genuinely raises the effect it uses,
    //     // but detach itself erases the row to Any, so wrapping the detach call with
    //     // a ContextEffect.handle over that effect needs the row cast back. Harmless:
    //     // the row is phantom, and the handler installed dynamically (found on `hs`
    //     // when the detach suspension is answered) is what makes the transplant
    //     // real, not this ascription. The same direct cast this file's suite already
    //     // uses elsewhere to build fixtures whose declared row does not match
    //     // dynamic behavior (e.g. "the innermost handler of a tag answers")
    //
    //     // extracts a still-pending, boxed child from a fully evaluated outer
    //     // computation. Not `.eval`: its own settle step picks the primitive or
    //     // the Nested branch from the *static* type, and here the static type is
    //     // itself a pending type whose payload is a JVM primitive ((Int < TestCtx)
    //     // < Any), which reads as the primitive case and unboxes the Nested
    //     // wrapper itself instead of what it carries. Nested.unnest checks the
    //     // *runtime* shape instead, so it has no such blind spot: the currency
    //     // discipline's own cast-at-the-boundary pattern (CONTRIBUTING.md) for
    //     // exactly this class of erased-type read
    //     def extract[A, S](v: A < S): A = Nested.unnest(Eval(v))
    //
    //     "a fork transplants a standing binding onto the detached child" in {
    //         val forked = Effect.detach(testCtx).asInstanceOf[(Int < TestCtx) < TestCtx]
    //         val bound  = ContextEffect.handle(Tag[TestCtx], 42)(forked)
    //         // the child is dynamically self-answering: its own transplanted cell
    //         // resolves TestCtx on a fresh eval. Its declared row still names
    //         // TestCtx, so it is cast back to Any to call `.eval`; the value
    //         // position is a plain Int, so that final `.eval` is not the footgun
    //         // `extract` exists to route around
    //         val child = extract(bound).asInstanceOf[Int < Any]
    //         assert(child.eval == 42)
    //     }
    //
    //     "a computation with no standing bindings detaches unchanged" in {
    //         val child = Effect.detach(42: Int < Any).eval
    //         assert(child.eval == 42)
    //     }
    //
    //     "the marker survives an intervening map, resolving against the stack live at that point" in {
    //         val forked = Effect.detach(testCtx).map(c => c.map(_ + 1)).asInstanceOf[(Int < TestCtx) < TestCtx]
    //         val bound  = ContextEffect.handle(Tag[TestCtx], 7)(forked)
    //         assert(extract(bound).asInstanceOf[Int < Any].eval == 8)
    //     }
    //
    //     "the child ships boxed as data and evaluates correctly on a separate, fresh eval" in {
    //         val forked = Effect.detach(testCtx).asInstanceOf[(Int < TestCtx) < TestCtx]
    //         val bound  = ContextEffect.handle(Tag[TestCtx], 100)(forked)
    //         val child  = extract(bound).asInstanceOf[Int < Any]
    //         // a second, independent Eval call: the child is a self-contained value,
    //         // not something still wired into the eval that produced it
    //         assert(Eval(child).eval == 100)
    //         assert(child.eval == 100)
    //     }
    // }
    //

    "bracket" - {

        "releases after the use completes, not at the boundary" in {
            var events = List.empty[String]
            val v = Effect.bracket(Effect.defer { events :+= "acquire"; 1 })(r => events :+= s"release $r") { r =>
                events :+= s"use $r"
                r + 1
            }
            // the trailing map runs after the release, which is what "at the end of the use" means: the
            // release is spliced where the use ends rather than deferred to the end of the eval
            val out = Eval(v.map { r =>
                events :+= "after"; r
            })
            assert(out == 2)
            assert(events == List("acquire", "use 1", "release 1", "after"))
        }

        "releases when the use throws, and the exception still propagates" in {
            var released = Maybe.empty[Int]
            val v = Effect.bracket(Effect.defer(1))(r => released = Maybe(r)) { _ =>
                throw new IllegalStateException("boom")
            }
            val thrown =
                try
                    discard(Eval(v))
                    false
                catch case ex: IllegalStateException => ex.getMessage == "boom"
            assert(thrown)
            assert(released == Maybe(1))
        }

        "releases when the use suspends and the continuation is answered" in {
            var released = Maybe.empty[Int]
            val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(r => ask.map(_ + r))
            assert(Eval(answerAsk(41)(v)) == 42)
            assert(released == Maybe(1))
        }

        "releases when a clause receives the continuation and never applies it" in {
            var released = Maybe.empty[Int]
            val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(r => ask.map(_ + r))
            val dropped  = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(Eval(dropped) == -1)
            assert(released == Maybe(1))
        }

        // A handler that stops the computation must not be able to skip the release. This is the shape that
        // is broken in kyo today: `Sync.ensure` cannot run its finalizer when `Abort` short circuits, because
        // the ensure sits above the handler that cuts the computation off and never gets to see the cut.
        // Here the bracket is registered by the eval, below every handler, so nothing a clause does can get
        // between an acquire that completed and the release it owes.

        "a handleLoop clause that stops the computation still releases" in {
            var released = Maybe.empty[Int]
            val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(r => ask.map(_ + r))
            val stopped  = ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.done(-1), a => a)
            assert(Eval(stopped) == -1)
            assert(released == Maybe(1))
        }

        "a handleLoopState clause that stops the computation still releases" in {
            var released = Maybe.empty[Int]
            val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(r => ask.map(_ + r))
            val stopped  = ArrowEffect.handleLoopState(Tag[Ask], 0, v)([C] => (_, _) => Loop.done(-1), (_, a) => a)
            assert(Eval(stopped) == -1)
            assert(released == Maybe(1))
        }

        "a stateful clause that stops after advancing still releases" in {
            var released = Maybe.empty[Int]
            val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(r => ask.map(a => ask.map(b => a + b + r)))
            val stopped =
                ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                    [C] => (s, _) => if s == 1 then Loop.done(-1) else Loop.continue(s + 1, 1: Int < Any),
                    (_, a) => a
                )
            assert(Eval(stopped) == -1)
            assert(released == Maybe(1))
        }

        "every outstanding bracket releases when a clause stops the computation" in {
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
        }

        "a discarded continuation releases every outstanding bracket" in {
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
        }

        "does not release when the acquire never completes" in {
            var released = false
            val v =
                Effect.bracket(ask.map(_ => 1))(_ => released = true)(r => r + 1)
            // the region answers nothing, so the acquire never settles and the bracket arrow is never reached
            val never = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            assert(Eval(never) == -1)
            assert(!released)
        }

        "releases exactly once when the use completes and the eval then ends" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer(1))(_ => count += 1)(r => r + 1)
            assert(Eval(v) == 2)
            assert(count == 1)
        }

        "nested brackets release innermost first" in {
            var events = List.empty[String]
            val v =
                Effect.bracket(Effect.defer("outer"))(r => events :+= s"release $r") { outer =>
                    Effect.bracket(Effect.defer("inner"))(r => events :+= s"release $r") { inner =>
                        events :+= s"use $outer/$inner"
                        1
                    }
                }
            assert(Eval(v) == 1)
            assert(events == List("use outer/inner", "release inner", "release outer"))
        }

        "nested brackets both release when the inner use throws" in {
            var released = List.empty[String]
            val v =
                Effect.bracket(Effect.defer("outer"))(r => released :+= r) { _ =>
                    Effect.bracket(Effect.defer("inner"))(r => released :+= r) { _ =>
                        throw new IllegalStateException("boom")
                    }
                }
            val thrown =
                try
                    discard(Eval(v))
                    false
                catch case _: IllegalStateException => true
            assert(thrown)
            assert(released == List("inner", "outer"))
        }

        "sequential brackets each release" in {
            var released = List.empty[Int]
            val v =
                Effect.bracket(Effect.defer(1))(r => released :+= r)(r => r).map { a =>
                    Effect.bracket(Effect.defer(2))(r => released :+= r)(r => r + a)
                }
            assert(Eval(v) == 3)
            assert(released == List(1, 2))
        }

        "the release itself may be a deferred computation" in {
            var released = false
            val v        = Effect.bracket(Effect.defer(1))(_ => Effect.defer { released = true })(r => r + 1)
            assert(Eval(v) == 2)
            assert(released)
        }

        // the two paths to a release, the arrow and the drain, have to be exclusive. A continuation held past
        // the end of the eval is where they meet: the drain has already run by the time the arrow is
        // applied, and nothing orders those two events
        "a continuation held past the end of the eval does not release again" in {
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
            // the stashed continuation still holds the finalizer arrow, and applying it must not release again
            assert(Eval(answerAsk(0)(stash.get(2))) == 3)
            assert(count == 1)
        }

        "an acquire resumed twice owes a release for each resume" in {
            var released = List.empty[Int]
            val v        = Effect.bracket(ask)(r => released :+= r)(r => r * 10)
            val r =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(1).map(a => cont(2).map(b => a + b)),
                    a => a
                )
            assert(Eval(r) == 30)
            assert(released == List(1, 2))
        }

        "a bracket spanning a budget park releases once" in {
            var count = 0
            def chain(n: Int, v: Int < Any): Int < Any =
                if n == 0 then v else chain(n - 1, v.map(_ + 1))
            val v = Effect.bracket(Effect.defer(0))(_ => count += 1)(r => chain(1000, r))
            assert(Eval(v) == 1000)
            assert(count == 1)
        }

        // the eval never stops in front of a binding, so a slice cannot end between the acquire settling and
        // the scope that owes the resource being installed. A stop lodged inside the acquire parks at that
        // exact window, and one inside the use parks mid-scope: the release must run once, at the end, and
        // never while the remainder is still resumable
        "a slice stopped inside a bracket releases once, at the end" in {
            var released = 0
            val v: Int < Any =
                Effect.bracket(Effect.defer {
                    discard(Safepoint.stop(Thread.currentThread()))
                    1
                })(_ => released += 1)(r =>
                    Effect.defer {
                        discard(Safepoint.stop(Thread.currentThread()))
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
        }

        "deeply nested brackets release in bounded stack" in {
            var count = 0
            def nest(n: Int): Int < Any =
                if n == 0 then 0
                else Effect.bracket(Effect.defer(n))(_ => count += 1)(_ => nest(n - 1))
            assert(Eval(nest(1000)) == 0)
            assert(count == 1000)
        }

        "many sequential brackets each release" in {
            var count = 0
            def loop(n: Int, acc: Int < Any): Int < Any =
                if n == 0 then acc
                else loop(n - 1, acc.map(a => Effect.bracket(Effect.defer(1))(_ => count += 1)(r => a + r)))
            assert(Eval(loop(1000, 0: Int < Any)) == 1000)
            assert(count == 1000)
        }

        "a bracket inside a nested eval releases at that eval's boundary" in {
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
        }

        "an acquire that is itself a bracket releases both" in {
            var released = List.empty[String]
            val acquire  = Effect.bracket(Effect.defer("a"))(r => released :+= r)(r => r + "!")
            val v        = Effect.bracket(acquire)(r => released :+= r)(r => r.length)
            assert(Eval(v) == 2)
            assert(released == List("a", "a!"))
        }

        "the release receives the outcome: the result on completion, the failure on a throw" in {
            var outcomes = List.empty[Result[Nothing, Int]]
            val ok: Int < Any =
                Effect.bracket(Effect.defer(1))((_, r: Result[Nothing, Int]) => outcomes :+= r)(r => r + 41)
            assert(Eval(ok) == 42)
            val boom = new RuntimeException("boom")
            val bad: Int < Any =
                Effect.bracket(Effect.defer(1))((_, r: Result[Nothing, Int]) => outcomes :+= r)(_ => throw boom)
            assert(intercept[RuntimeException](Eval(bad)) eq boom)
            assert(outcomes == List(Result.succeed(42), Result.panic(boom)))
        }

        "nested releases run innermost first on a failure" in {
            var order = List.empty[String]
            val boom  = new RuntimeException("boom")
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))(_ => order :+= "outer") { a =>
                    Effect.bracket(Effect.defer(2))(_ => order :+= "inner") { b =>
                        if a + b == 3 then throw boom else a + b
                    }
                }
            assert(intercept[RuntimeException](Eval(v)) eq boom)
            assert(order == List("inner", "outer"))
        }

        "a cross-thread stop parks inside a bracket and abandonment releases" in {
            @volatile var started       = false
            @volatile var released      = 0
            @volatile var sawUnreleased = false
            val t = new Thread(() =>
                def spin(i: Int): Int < Any =
                    ((i + 1) & 63: Int < Any).map { v =>
                        started = true
                        spin(v)
                    }
                val v: Int < Any = Effect.bracket(Effect.defer(1))(_ => released += 1)(r => spin(r))
                val p            = Eval.partial(v)
                sawUnreleased = released == 0 && p.evalNow.isEmpty
                Eval.finalizeResources(p)
            )
            t.start()
            while !started do ()
            assert(Safepoint.stop(t))
            t.join(20000)
            assert(!t.isAlive)
            assert(sawUnreleased)
            assert(released == 1)
        }

        "a use that fails after a resume still releases once with the failure" in {
            var released = 0
            var out: Any = null
            val boom     = new RuntimeException("late")
            val v: Int < Any =
                Effect.bracket(Effect.defer(1)) { (_, r: Result[Nothing, Int]) =>
                    released += 1
                    out = r
                } { r =>
                    Effect.defer {
                        discard(Safepoint.stop(Thread.currentThread()))
                        r
                    }.map(x => if x == 1 then throw boom else x)
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(released == 0)
            assert(intercept[RuntimeException](Eval(p)) eq boom)
            assert(released == 1)
            assert(out.equals(Result.panic(boom)))
        }

        "a park inside nested brackets carries both releases" in {
            var released = List.empty[String]
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))(_ => released :+= "outer") { a =>
                    Effect.bracket(Effect.defer(2))(_ => released :+= "inner") { b =>
                        Effect.defer {
                            discard(Safepoint.stop(Thread.currentThread()))
                            a + b
                        }.map(_ + 39)
                    }
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            assert(released.isEmpty)
            assert(Eval(p) == 42)
            assert(released == List("inner", "outer"))
        }

        "a park evaluated twice releases its resource once" in {
            var released = 0
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))(_ => released += 1)(r =>
                    Effect.defer {
                        discard(Safepoint.stop(Thread.currentThread()))
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
        }

        "abandoning a parked bracket releases with the abandoned outcome, and a later resume is harmless" in {
            var released = 0
            var out: Any = null
            val v: Int < Any =
                Effect.bracket(Effect.defer(1)) { (_, r: Result[Nothing, Int]) =>
                    released += 1
                    out = r
                } { r =>
                    Effect.defer {
                        discard(Safepoint.stop(Thread.currentThread()))
                        r
                    }.map(_ + 41)
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            Eval.finalizeResources(p)
            assert(released == 1)
            assert(out.equals(Result.panic(Finalizer.Abandoned)))
            assert(Eval(p) == 42)
            assert(released == 1)
        }

        "abandoning nested parked brackets releases innermost first" in {
            var order = List.empty[String]
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))(_ => order :+= "outer") { a =>
                    Effect.bracket(Effect.defer(2))(_ => order :+= "inner") { b =>
                        Effect.defer {
                            discard(Safepoint.stop(Thread.currentThread()))
                            a + b
                        }.map(_ + 39)
                    }
                }
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            Eval.finalizeResources(p)
            assert(order == List("inner", "outer"))
        }

        "abandoning a park with no outstanding releases is a no-op" in {
            val v: Int < Any = Effect.defer {
                discard(Safepoint.stop(Thread.currentThread()))
                1
            }.map(_ + 41)
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            Eval.finalizeResources(p)
            assert(Eval(p) == 42)
        }

        "abandoning a slice parked in the acquire window still releases" in {
            // the acquire has settled when the stop parks the slice, so the resource exists; the
            // scope that owes it has not installed yet. Abandonment must still release: a resource
            // that was created and never freed is a leak, whatever the park's internal shape was
            var released = 0
            val v: Int < Any =
                Effect.bracket(Effect.defer {
                    discard(Safepoint.stop(Thread.currentThread()))
                    1
                })(_ => released += 1)(r => r + 41)
            val p = Eval.partial(v)
            assert(p.evalNow.isEmpty)
            Eval.finalizeResources(p)
            assert(released == 1)
        }

        "a release that throws during unwind does not lose the failure or the recovery" in {
            var seen       = List.empty[String]
            var suppressed = List.empty[String]
            val v: Int < Any = Effect.catching {
                Effect.bracket(Effect.defer(1))((_, _: Result[Nothing, Int]) => throw new IllegalStateException("release")) { _ =>
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
        }

        "a recovery that throws surfaces its own failure with the original suppressed" in {
            val original     = new UnsupportedOperationException("body")
            val fromRecovery = new IllegalStateException("recovery")
            var out: Any     = null
            val v: Int < Any = Effect.bracket(Effect.defer(1))((_, r: Result[Nothing, Int]) => out = r) { _ =>
                Effect.catching((throw original): Int)(_ => throw fromRecovery)
            }
            val ex = intercept[IllegalStateException](Eval(v))
            assert(ex eq fromRecovery)
            assert(ex.getSuppressed.exists(_ eq original))
            assert(out.equals(Result.panic(fromRecovery)))
        }

        "an acquire that throws owes no release" in {
            var released = 0
            val boom     = new RuntimeException("acquire")
            val v: Int < Any =
                Effect.bracket(Effect.defer((throw boom): Int))(_ => released += 1)(r => r + 1)
            assert(intercept[RuntimeException](Eval(v)) eq boom)
            assert(released == 0)
        }

        "an effectful acquire whose handler fails owes no release" in {
            var released = 0
            val boom     = new RuntimeException("clause")
            val v: Int < TestEffect1 =
                Effect.bracket(testEffect1(1))(_ => released += 1)(r => r.length)
            val handled: Int < Any =
                ArrowEffect.handleCont(Tag[TestEffect1], v)([C] => (_, _) => throw boom, a => a)
            assert(intercept[RuntimeException](Eval(handled)) eq boom)
            assert(released == 0)
        }

        "an interior recovery turns the release outcome into the recovered success" in {
            var out: Any = null
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))((_, r: Result[Nothing, Int]) => out = r) { a =>
                    Effect.catching((throw new RuntimeException("use")): Int)(_ => a + 41)
                }
            assert(Eval(v) == 42)
            assert(out.equals(Result.succeed(42)))
        }

        "a failing use with an effectful acquire still releases with the failure" in {
            var out: Any = null
            val boom     = new RuntimeException("use")
            val v: Int < TestEffect1 =
                Effect.bracket(testEffect1(10))((_, r: Result[Nothing, Int]) => out = r) { a =>
                    if a == "10" then throw boom else a.length
                }
            val handled: Int < Any =
                ArrowEffect.handleCont(Tag[TestEffect1], v)([C] => (input, cont) => cont(input.toString))
            assert(intercept[RuntimeException](Eval(handled)) eq boom)
            assert(out.equals(Result.panic(boom)))
        }

        "a release that throws during abandonment does not silence the others" in {
            var order = List.empty[String]
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))(_ => order :+= "outer") { a =>
                    Effect.bracket(Effect.defer(2)) { _ =>
                        order :+= "inner"
                        throw new IllegalStateException("inner-release")
                    } { b =>
                        Effect.defer {
                            discard(Safepoint.stop(Thread.currentThread()))
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
        }

        "an abandonment racing a resume releases exactly once" in {
            var iterations = 0
            while iterations < 200 do
                val released = new java.util.concurrent.atomic.AtomicInteger
                val v: Int < Any =
                    Effect.bracket(Effect.defer(1))(_ => discard(released.incrementAndGet()))(r =>
                        Effect.defer {
                            discard(Safepoint.stop(Thread.currentThread()))
                            r
                        }.map(_ + 41)
                    )
                val p = Eval.partial(v)
                assert(p.evalNow.isEmpty)
                val resumer   = new Thread(() => discard(Eval(p)))
                val abandoner = new Thread(() => Eval.finalizeResources(p))
                resumer.start()
                abandoner.start()
                resumer.join(10000)
                abandoner.join(10000)
                assert(!resumer.isAlive && !abandoner.isAlive)
                assert(released.get == 1)
                iterations += 1
            end while
        }

        "a release may itself bracket" in {
            var released = List.empty[String]
            val v =
                Effect.bracket(Effect.defer("outer"))(r =>
                    Effect.bracket(Effect.defer("in-release"))(x => released :+= x)(x => released :+= s"$r via $x")
                )(r => r.length)
            assert(Eval(v) == 5)
            assert(released == List("outer via in-release", "in-release"))
        }

        "a resource the use hands back is still released" in {
            var released = false
            val v        = Effect.bracket(Effect.defer("res"))(_ => released = true)(r => r)
            assert(Eval(v) == "res")
            assert(released)
        }

        "a use that ignores the resource still releases it" in {
            var released = Maybe.empty[Int]
            val v        = Effect.bracket(Effect.defer(1))(r => released = Maybe(r))(_ => "done")
            assert(Eval(v) == "done")
            assert(released == Maybe(1))
        }

        "a release that throws on the completing path surfaces" in {
            val v = Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release"))(r => r + 1)
            val message =
                try
                    discard(Eval(v))
                    Maybe.empty[String]
                catch case ex: IllegalStateException => Maybe(ex.getMessage)
            assert(message == Maybe("release"))
        }

        "a release that throws does not stop the releases after it" in {
            var released = List.empty[String]
            val v =
                Effect.bracket(Effect.defer("outer"))(r => released :+= r) { _ =>
                    Effect.bracket(Effect.defer("inner"))(_ => throw new IllegalStateException("inner release")) { _ =>
                        ask.map(_ + 1)
                    }
                }
            // the clause stops the computation, so both releases are owed at the drain; the inner one throws
            // and the outer one still has to run
            val stopped = ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, _) => -1, a => a)
            val message =
                try
                    discard(Eval(stopped))
                    Maybe.empty[String]
                catch case ex: IllegalStateException => Maybe(ex.getMessage)
            assert(message == Maybe("inner release"))
            assert(released == List("outer"))
        }

        "a release that throws while the eval is already failing is suppressed onto the original" in {
            val v =
                Effect.bracket(Effect.defer(1))(_ => throw new IllegalStateException("release")) { _ =>
                    ask.map(_ + 1)
                }
            // the use suspends and the clause drops the continuation, so the release is owed at the drain.
            // The eval is leaving on the body's exception, which is the one that says why the computation
            // ended, so the release failure attaches to it rather than replacing it
            val stopped =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, _) => throw new UnsupportedOperationException("body"),
                    a => a
                )
            val caught =
                try
                    discard(Eval(stopped))
                    Maybe.empty[(String, Boolean)]
                catch
                    case ex: Throwable =>
                        // the effect trace attaches its own carrier the same way, so the release failure is
                        // one of the suppressed rather than the only one
                        Maybe((ex.getMessage, ex.getSuppressed.exists(_.getMessage == "release")))
            assert(caught == Maybe(("body", true)))
        }

        "every release runs even when several throw" in {
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
        }

        "a bracket interleaved with a region releases after the region completes" in {
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
        }

        "a region installed inside the use does not intercept the release" in {
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
        }

        // the shape Choice and Parse handlers have: one clause applies the same continuation once per
        // branch. The suspension is inside the use, so the dumped continuation carries the extent's
        // finalizer and every branch re-enters the same extent. One resource was acquired, so one release
        // is owed, and it cannot come due while a branch that can still read the resource has not run
        "a resource shared by a multi-shot clause outlives every branch" in {
            var events             = List.empty[String]
            val acquire: Int < Ask = Effect.defer { events :+= "acquire"; 1 }
            val v =
                Effect.bracket(acquire)(r => events :+= s"release $r") { r =>
                    ask.map { a =>
                        events :+= s"use $a with $r"
                        a + r
                    }
                }
            val twice =
                ArrowEffect.handleCont(Tag[Ask], v)(
                    [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
                    a => a
                )
            assert(Eval(twice) == 32)
            assert(events == List("acquire", "use 10 with 1", "use 20 with 1", "release 1"))
        }

        // the same clause shape with the suspension in the acquire instead: each branch acquires its own
        // resource, so each owes its own release, and each release belongs at the end of its own branch
        "a multi-shot clause that acquires per branch releases each where its branch ends" in {
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
        }

        // the guarantee's observable shape, which nothing pinned before: a handler cannot discard a
        // release, and the drain that owes it runs after every handler, so by the time it runs the
        // handler's own continuation has already gone
        "a discarded continuation releases after the handler's own continuation has run" in {
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
            assert(events == List("acquire", "after -1", "release 1"))
        }

        // a clause runs outside the region it serves, and the continuation it is handed produces the
        // region's body result rather than the handled result: `done` is applied below the region's own
        // entry, so a bracket the clause opens around the continuation ends before `done` runs
        "a bracket a clause opens around the continuation releases when the body result settles" in {
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
        }

        "a bracket outside two regions releases at its own extent, not when an inner region discards" in {
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
        }

        // both paths to the release can be reached for one resource under a multi-shot clause: the arrow
        // on the branch that completed, and the unwind on the branch that throws. The flag has to make
        // them exclusive, and the failure must not be swallowed by a release that is no longer owed
        "a later branch that throws does not release again what an earlier branch released" in {
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
            assert(intercept[RuntimeException](Eval(twice)) eq boom)
            assert(events == List("acquire", "release 1"))
        }

        // the ordering stated as the safety property it stands for: no branch may observe the resource
        // after the release that closed it
        "no branch of a multi-shot clause reads a resource that was already released" in {
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
            assert(Eval(twice) == 32)
            assert(seen == List("branch 10", "branch 20"))
        }

        // Choice.run's shape: one clause runs the same continuation once per input. Parse reaches it the
        // same way when it explores an alternative after backtracking. Three branches, so a release that
        // comes due at the first is visible to the two after it
        "no branch of a Choice-shaped clause reads a resource that was already released" in {
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
            assert(Eval(branches) == 306)
            assert(seen == List("branch 1", "branch 2", "branch 3"))
        }

        "nested brackets shared by a multi-shot clause both survive every branch" in {
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
            assert(Eval(twice) == 36)
            assert(events == List("branch 10", "branch 20", "release inner", "release outer"))
        }

        // the sibling of the held-continuation case above, with the continuation applied more than once
        // after the drain has already run: neither application may release again
        "a continuation held past the end of the eval and applied twice releases only once" in {
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
            assert(Eval(answerAsk(0)(stash.get(2))) == 3)
            assert(Eval(answerAsk(0)(stash.get(5))) == 6)
            assert(count == 1)
        }

        // handleFirst has its own dispatch, and Choice.runStream drives it multi-shot the same way
        // Choice.run drives handleCont
        "no branch of a multi-shot handleFirst clause reads a resource that was already released" in {
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
            assert(Eval(answerAsk(0)(branches)) == 32)
            assert(seen == List("branch 10", "branch 20"))
        }

        // The four below share one root cause, independent of the multi-shot ones above. `dump()` bounds its
        // fold at a Handler or a Recover (Stack.scala:236) and a Finalizer is neither, so a value delivered
        // into a map body or a done transform folds the finalizer away and advances head past it. If that
        // body throws, both delivery arms rethrow through attachThrow (Eval.scala:676, 689), which only
        // attaches a trace, where the clause-throw path deliberately pushes the fold back first
        // (Eval.scala:549-553). The unwind then cannot see the release: it does not run on the way down, it
        // runs after any recovery below it, and it is told Abandoned rather than the failure.

        "a recovery outside the bracket runs after the release, which is told the failure" in {
            var order    = List.empty[String]
            var outcomes = List.empty[Result[Nothing, Int]]
            val boom     = new RuntimeException("boom")
            val v: Int < Any =
                Effect.catching {
                    Effect.bracket(Effect.defer(1))((_, r: Result[Nothing, Int]) =>
                        order :+= "release"
                        outcomes :+= r
                    ) { r =>
                        Effect.defer(r).map(_ => (throw boom): Int)
                    }
                } { _ =>
                    order :+= "recover"
                    -1
                }
            assert(Eval(v) == -1)
            assert(order == List("release", "recover"))
            assert(outcomes == List(Result.panic(boom)))
        }

        "nested brackets separated by a handler still release innermost first on a failure" in {
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
        }

        "a release that throws on the completing path still runs the outer release before a recovery" in {
            var order    = List.empty[String]
            var outcomes = List.empty[Result[Nothing, Int]]
            val boom     = new IllegalStateException("inner release")
            val v: Int < Any =
                Effect.catching {
                    Effect.bracket(Effect.defer("outer"))((_, r: Result[Nothing, Int]) =>
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
        }

        // the site a throwing handleFirst clause reaches, since handleFirst runs its clause in the done
        // lane of handleCont (ArrowEffect.scala:244, 252-257)
        "a done transform that throws releases the bracket below it before a recovery" in {
            var order    = List.empty[String]
            var outcomes = List.empty[Result[Nothing, Int]]
            val boom     = new RuntimeException("done")
            val v: Int < Any =
                Effect.catching {
                    Effect.bracket(Effect.defer(1))((_, r: Result[Nothing, Int]) =>
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
        }

        // the unwind runs finalizers with no NonFatal guard (Stack.scala:414-416) while Recover.panic
        // declines a fatal (Eval.scala:62-63), so the two arms deliberately disagree and only one of them
        // is guarded. Nothing pinned that a fatal still releases
        "a fatal failure runs the release" in {
            var order = List.empty[String]
            val boom  = new InterruptedException("fatal")
            val v: Int < Any =
                Effect.bracket(Effect.defer(1))(_ => order :+= "release")(_ => (throw boom): Int)
            assert(intercept[InterruptedException](Eval(v)) eq boom)
            assert(order == List("release"))
        }

        "a fatal failure runs the release and is not answered by a recovery" in {
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
        }
    }

end EffectTest
