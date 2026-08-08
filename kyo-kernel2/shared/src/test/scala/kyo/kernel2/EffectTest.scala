package kyo.kernel2

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Tag
import kyo.discard
import kyo.kernel2.*
import kyo.kernel2.internal.Context
import kyo.test.Test
import language.implicitConversions

sealed trait EffAsk extends ArrowEffect[Const[Unit], Const[Int]]

class EffectTest extends Test[Any]:

    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[String]]

    def testEffect1(i: Int): String < TestEffect1 =
        ArrowEffect.suspend[Any](Tag[TestEffect1], i)

    "catching" - {
        def ask: Int < EffAsk = ArrowEffect.suspend[Any](Tag[EffAsk], ())

        "a throw outside the guarded computation escapes" in {
            // scope is structural: a step appended after catching is outside it, so
            // the throw fires instead of being rescued to -1
            interceptThrown[RuntimeException] {
                val guarded = Effect.catching(ask.map(_ + 1))(_ => -1)
                val handled = ArrowEffect.handle(Tag[EffAsk], guarded.map(_ => (throw new RuntimeException("boom")): Int))(
                    [C] => (_, cont) => cont(5)
                )
                handled.eval
            }
        }

        "a throw after a suspension resumes inside the guarded computation is caught" in {
            // the guard rotates with the computation: the throw happens after the
            // operation resumes with 5, and still lands in the rescue
            val guarded =
                Effect.catching(ask.map(x => if x > 0 then throw new RuntimeException("boom") else x))(_ => -7)
            assert(ArrowEffect.handle(Tag[EffAsk], guarded)([C] => (_, cont) => cont(5)).eval == -7)
        }

        "handleStop discards the rest of the computation, dropping a guard inside it" in {
            // the discard is not an exception: the guarded region between the
            // operation and the handler is dropped without its rescue running
            val region = Effect.catching(ask.map(_ + 999))(_ => -1)
            val handled = ArrowEffect.handleStop(Tag[EffAsk], region)(
                [C] => (_) => 15
            )
            assert(handled.map(_ + 1).eval == 16)
        }

        "match" in {
            val effect = Effect.catching {
                throw new RuntimeException("Test exception")
            } {
                case _: RuntimeException => 42
            }

            assert(effect.eval == 42)
        }

        "no match" in {
            interceptThrown[Exception] {
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

            val result = ArrowEffect.handle(Tag[TestEffect1], effect)(
                [C] => (input, cont) => cont(input.toString)
            )

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
    }

    "defer" - {

        "simple" in {
            var executed = false
            val effect = Effect.defer {
                executed = true
                42
            }
            assert(!executed)
            assert(effect.eval == 42)
            assert(executed)
        }

        "nested defer calls" in {
            var order = List.empty[Int]
            val effect = Effect.defer {
                order = 1 :: order
                Effect.defer {
                    order = 2 :: order
                    Effect.defer {
                        order = 3 :: order
                        42
                    }
                }
            }
            assert(effect.eval == 42)
            assert(order == List(3, 2, 1))
        }
    }

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

    "combining multiple effects" in {
        val effect =
            for
                a <- Effect.defer(1)
                b <- Effect.catching(2 / 0) { case _: ArithmeticException => 2 }
                c <- Effect.defer(3)
            yield a + b + c

        assert(effect.eval == 6)
    }

    // kernel2-specific coverage beyond the ported suite

    def ask: Int < EffAsk =
        ArrowEffect.suspend[Any](Tag[EffAsk], ())

    def park(v: Int < EffAsk): Int < EffAsk =
        ArrowEffect.handlePartial(Tag[EffAsk], v, Context.empty)(
            [C] => (input, cont) => Maybe.Absent
        )

    def resume(v: Int < EffAsk, answer: Int): Int =
        ArrowEffect.handle(Tag[EffAsk], v)(
            [C] => (input, cont) => cont(answer)
        ).eval

    "defer does not run at construction" in {
        var ran = false
        val v = Effect.defer {
            ran = true
            1
        }
        assert(!ran)
        assert(v.eval == 1)
        assert(ran)
    }

    "defer runs once per drive" in {
        var runs = 0
        val v = Effect.defer {
            runs += 1
            runs
        }
        assert(v.eval == 1)
        assert(v.eval == 2)
        assert(runs == 2)
    }

    "defer composes with map and stays lazy" in {
        var log = List.empty[String]
        val v = Effect.defer {
            log :+= "defer"
            10
        }.map { n =>
            log :+= "map"
            n + 1
        }
        assert(log == Nil)
        assert(v.eval == 11)
        assert(log == List("defer", "map"))
    }

    "defer result can suspend" in {
        val v = Effect.defer(ask.map(_ + 1))
        assert(resume(park(v), 41) == 42)
    }

    "deferred recursion is stack safe" in {
        def loop(i: Int): Int < Any =
            if i == 0 then 0
            else Effect.defer(loop(i - 1))
        assert(loop(100000).eval == 0)
    }

    "defer propagates exceptions at drive time" in {
        val v: Int < Any = Effect.defer((throw new RuntimeException("boom")): Int)
        val thrown =
            try
                val _ = v.eval
                false
            catch case e: RuntimeException => e.getMessage == "boom"
        assert(thrown)
    }

    "bracket releases on completion" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            log :+= "acq"
            42
        } { _ =>
            log :+= "rel"
            ()
        } { r =>
            r + 1
        }
        assert(v.eval == 43)
        assert(log == List("acq", "rel"))
    }

    "bracket releases exactly once across a park" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            log :+= "acq"
            42
        } { _ =>
            log :+= "rel"
            ()
        } { r =>
            ask.map(a => a + r)
        }
        val parked = park(v)
        assert(log == List("acq"))
        assert(resume(parked, 100) == 142)
        assert(log == List("acq", "rel"))
    }

    "bracket releases on exception" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            log :+= "acq"
            42
        } { _ =>
            log :+= "rel"
            ()
        } { _ =>
            (throw new RuntimeException("boom")): Int
        }
        val thrown =
            try
                val _ = v.eval
                false
            catch case e: RuntimeException => e.getMessage == "boom"
        assert(thrown)
        assert(log == List("acq", "rel"))
    }

    "nested brackets release in reverse order" in {
        var log = List.empty[String]
        def mk(name: String)(body: Int => Int < Any): Int < Any =
            Effect.bracket {
                log :+= s"acq-$name"
                1
            } { _ =>
                log :+= s"rel-$name"
                ()
            }(body)
        val v = mk("outer")(_ => mk("inner")(r => r + 1))
        assert(v.eval == 2)
        assert(log == List("acq-outer", "acq-inner", "rel-inner", "rel-outer"))
    }

    "a stop while acquire is in flight discards the bracket" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            ask.map { a =>
                log :+= s"acq-$a"
                a
            }
        } { r =>
            log :+= s"rel-$r"
            ()
        } { r =>
            log :+= "use"
            r + 1
        }
        val handled = ArrowEffect.handleStop(Tag[EffAsk], v)([C] => _ => -7)
        assert(handled.eval == -7)
        assert(log == List.empty)
    }

    "an operation answered during acquire completes the bracket normally" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            ask.map { a =>
                log :+= s"acq-$a"
                a
            }
        } { r =>
            log :+= s"rel-$r"
            ()
        } { r =>
            r + 1
        }
        val handled = ArrowEffect.handle(Tag[EffAsk], v)([C] => (_, cont) => cont(42))
        assert(handled.eval == 43)
        assert(log == List("acq-42", "rel-42"))
    }

    "a ctl transform after resume inside acquire applies to the final result" in {
        var log = List.empty[String]
        val v = Effect.bracket(ask) { r =>
            log :+= s"rel-$r"
            ()
        } { r =>
            r + 1
        }
        val handled = ArrowEffect.handle(Tag[EffAsk], v)([C] => (_, cont) => cont(10).map(x => x * 100))
        assert(handled.eval == 1100)
        assert(log == List("rel-10"))
    }

    "a stop after a park while a resource is held still releases" in {
        // the Finalize step sits outside the handler's rotate in the resumed chain, so a
        // stop answered inside the rotate still flows through the release
        var log = List.empty[String]
        val v: Int < (TestEffect1 & EffAsk) =
            Effect.bracket {
                log :+= "acq"
                42
            } { r =>
                log :+= s"rel-$r"
                ()
            } { r =>
                ask.map(a => testEffect1(a + r).map(_ => 0))
            }
        val stopped: Int < EffAsk = ArrowEffect.handleStop(Tag[TestEffect1], v)(
            [C] => (in) => in
        )
        val parked = park(stopped)
        assert(log == List("acq"))
        assert(resume(parked, 1) == 43)
        assert(log == List("acq", "rel-42"))
    }

    "bracket acquire runs per drive" in {
        var acquisitions = 0
        val v = Effect.bracket {
            acquisitions += 1
            acquisitions
        }(_ => ())(r => r)
        assert(v.eval == 1)
        assert(v.eval == 2)
    }

    "catching intercepts an exception at construction" in {
        val v = Effect.catching[Int, Any, Int, Any] {
            throw new RuntimeException("boom")
        }(_ => 42)
        assert(v.eval == 42)
    }

    "catching passes through when nothing throws" in {
        val v = Effect.catching((1: Int < Any).map(_ + 1))(_ => -1)
        assert(v.eval == 2)
    }

    "catching intercepts a throw in a frame after resume" in {
        val program: Int < EffAsk =
            ask.map(x => if x > 0 then throw new RuntimeException("pos") else x)
        val wrapped = Effect.catching(program)(_ => 99)
        assert(resume(wrapped, 1) == 99)
        assert(resume(wrapped, -1) == -1)
    }

    "catching intercepts a throw inside a deferred thunk" in {
        val v = Effect.catching(Effect.defer[Int, Any] {
            throw new RuntimeException("late")
        })(_ => 7)
        assert(v.eval == 7)
    }

    "catching hands the thrown exception to the handler" in {
        val ex              = new RuntimeException("original")
        var seen: Throwable = null
        val v = Effect.catching[Int, Any, Int, Any] {
            throw ex
        } { t =>
            seen = t
            0
        }
        assert(v.eval == 0)
        assert(seen eq ex)
    }

    "catching stays armed across multiple suspensions" in {
        val program: Int < EffAsk =
            ask.map(a => ask.map(b => if b > a then throw new RuntimeException("desc") else a - b))
        val wrapped   = Effect.catching(program)(_ => -100)
        var remaining = List(5, 9)
        val handled = ArrowEffect.handle(Tag[EffAsk], wrapped)(
            [C] =>
                (input, cont) =>
                    val a = remaining.head
                    remaining = remaining.tail
                    cont(a)
        )
        assert(handled.eval == -100)
    }

end EffectTest
