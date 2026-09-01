package kyo.proto.kernel

import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec

class EffectBracketTest extends AnyFreeSpec:

    private def eval[A, S](v: A < S): A =
        Nested.unnest[A](Eval(v))

    private def requestStop(): Unit =
        discard(Safepoint.get())
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop

    private object Boom extends RuntimeException("boom", null, false, false)

    sealed trait Ask extends ArrowEffect[kyo.Const[Unit], kyo.Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    "bracket" - {
        "releases with Absent on completion, after use" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Effect.bracket(Effect.defer { log += "open"; 42 }) { (a, outcome) =>
                discard(log += s"close $a ${outcome.isEmpty}")
            } { a =>
                Effect.defer { log += "use"; a + 1 }
            }
            assert(eval(v) == 43)
            assert(log.toList == List("open", "use", "close 42 true"))
        }

        "a pure use completes the bracket through the settled fast path" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer(1))((_, _) => count += 1)(a => a + 1)
            assert(eval(v) == 2)
            assert(count == 1)
        }

        "a use that throws during application still releases" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { _ =>
                (throw Boom): Int < Any
            }
            val ex = intercept[RuntimeException](eval(v))
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "releases exactly once" in {
            var count = 0
            val v     = Effect.bracket(Effect.defer(1))((_, _) => count += 1)(a => Effect.defer(a))
            assert(eval(v) == 1)
            assert(count == 1)
        }

        "releases with the failure when the use throws" in {
            var seen = Maybe.empty[Throwable]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = outcome) { _ =>
                Effect.defer((throw Boom): Int)
            }
            val ex = intercept[RuntimeException](eval(v))
            assert(ex eq Boom)
            assert(seen.exists(_ eq Boom))
        }

        "releases when a parked remainder is abandoned" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(seen.isEmpty)
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "a resumed parked bracket completes and releases with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                Effect.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(eval(parked) == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "nested brackets release innermost first on failure" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner")) { _ =>
                    Effect.defer((throw Boom): Int)
                }
            }
            val ex = intercept[RuntimeException](eval(v))
            assert(ex eq Boom)
            assert(log.toList == List("inner", "outer"))
        }

        "a stop landing as the acquire settles still installs the region" in {
            var count = 0
            val v = Effect.bracket(Effect.defer {
                requestStop()
                7
            })((_, _) => count += 1)(a => Effect.defer(a + 1))
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(count == 1)
            assert(eval(parked) == 8)
            assert(count == 1)
        }

        "a loop clause answering done releases a bracket opened inside" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any =
                ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => kyo.proto.Loop.done(-1), b => b)
            assert(eval(r) == -1)
            assert(seen.exists(_.isDefined))
        }

        "the acquire is not guarded before it settles" in {
            var count = 0
            val v = Effect.bracket(Effect.defer {
                requestStop()
                Effect.defer(7)
            })((_, _) => count += 1)(a => Effect.defer(a))
            val parked = Eval.partial(v)
            assert(parked.isInstanceOf[Pending[?, ?]])
            Eval.release(parked, Boom)
            assert(count == 0)
            assert(eval(parked) == 7)
            assert(count == 1)
        }
    }

    "captured continuations" - {
        "a discarded captured continuation still releases the bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val dropped: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
            assert(eval(dropped) == -1)
            assert(seen.exists(_.isDefined))
        }

        "a captured continuation resumed in the clause completes the bracket there" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val resumed: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), b => b)
            assert(eval(resumed) == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "a park after a crossing resume still owes the bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map { x =>
                        requestStop()
                        Effect.defer(a + x)
                    }
                }
            val resumed: Int < Any =
                ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), b => b)
            val parked = Eval.partial(resumed)
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            Eval.release(parked, Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }

        "an effectful loop clause resuming after the pop completes the bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Effect.defer(kyo.proto.Loop.continue((), 1: Int < Ask)),
                b => b
            )
            assert(eval(r) == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "an effectful loop clause answering done releases through the eval root" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < Ask =
                Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val r: Int < Any = ArrowEffect.handleLoop(Tag[Ask], body)(
                [C] => _ => Effect.defer(kyo.proto.Loop.done(-1)),
                b => b
            )
            assert(eval(r) == -1)
            assert(seen.exists(_.isDefined))
        }
    }

    "unit acquire" - {
        "runs after completion with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Effect.bracket(())((_, outcome) => seen = Maybe(outcome))(_ => Effect.defer(5))
            assert(eval(v) == 5)
            assert(seen == Maybe(Maybe.empty))
        }

        "runs with the failure on unwind" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Effect.bracket(())((_, outcome) => seen = Maybe(outcome))(_ => Effect.defer((throw Boom): Int))
            val ex   = intercept[RuntimeException](eval(v))
            assert(ex eq Boom)
            assert(seen.exists(_.exists(_ eq Boom)))
        }
    }
end EffectBracketTest
