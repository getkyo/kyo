package kyo.proto

import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.kernel.<
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec

class SyncTest extends AnyFreeSpec:

    private def eval[A, S](v: A < S): A =
        Nested.unnest[A](Eval(v))

    private def requestStop(): Unit =
        discard(Safepoint.get())
        discard(Safepoint.stop(Thread.currentThread()))
        Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)
    end requestStop

    private object Boom extends RuntimeException("boom", null, false, false)

    "acquireReleaseWith" - {
        "releases with Absent on completion, after use" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Sync.acquireReleaseWith(Sync.defer { log += "open"; 42 }) { (a, outcome) =>
                discard(log += s"close $a ${outcome.isEmpty}")
            } { a =>
                Sync.defer { log += "use"; a + 1 }
            }
            assert(eval(Sync.Unsafe.run(v)) == 43)
            assert(log.toList == List("open", "use", "close 42 true"))
        }

        "releases exactly once" in {
            var count = 0
            val v     = Sync.acquireReleaseWith(Sync.defer(1))((_, _) => count += 1)(a => Sync.defer(a))
            assert(eval(Sync.Unsafe.run(v)) == 1)
            assert(count == 1)
        }

        "releases with the failure when the use throws" in {
            var seen = Maybe.empty[Throwable]
            val v = Sync.acquireReleaseWith(Sync.defer(7))((_, outcome) => seen = outcome) { _ =>
                Sync.defer((throw Boom): Int)
            }
            val ex = intercept[RuntimeException](eval(Sync.Unsafe.run(v)))
            assert(ex eq Boom)
            assert(seen == Maybe(Boom))
        }

        "releases when a parked remainder is abandoned" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Sync.acquireReleaseWith(Sync.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                Sync.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }
            val parked = Eval.partial(Sync.Unsafe.run(v))
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(seen.isEmpty)
            Eval.release(parked, Boom)
            assert(seen == Maybe(Maybe(Boom)))
        }

        "a resumed parked bracket completes and releases with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v = Sync.acquireReleaseWith(Sync.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                Sync.defer {
                    requestStop()
                    Effect.defer(a + 1)
                }
            }
            val parked = Eval.partial(Sync.Unsafe.run(v))
            assert(parked.isInstanceOf[Kyo.Park[?, ?]])
            assert(eval(parked) == 8)
            assert(seen == Maybe(Maybe.empty))
        }

        "nested brackets release innermost first on failure" in {
            val log = collection.mutable.ListBuffer[String]()
            val v = Sync.acquireReleaseWith(Sync.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Sync.acquireReleaseWith(Sync.defer(2))((_, _) => discard(log += "inner")) { _ =>
                    Sync.defer((throw Boom): Int)
                }
            }
            val ex = intercept[RuntimeException](eval(Sync.Unsafe.run(v)))
            assert(ex eq Boom)
            assert(log.toList == List("inner", "outer"))
        }

        "the acquire is not guarded before it settles" in {
            var count = 0
            val v = Sync.acquireReleaseWith(Sync.defer {
                requestStop()
                Effect.defer(7)
            })((_, _) => count += 1)(a => Sync.defer(a))
            val parked = Eval.partial(Sync.Unsafe.run(v))
            assert(parked.isInstanceOf[kyo.proto.kernel.internal.Pending[?, ?]])
            Eval.release(parked, Boom)
            assert(count == 0)
            assert(eval(parked) == 7)
            assert(count == 1)
        }
    }

    "captured continuations" - {
        sealed trait Ask extends kyo.proto.kernel.ArrowEffect[kyo.Const[Unit], kyo.Const[Int]]
        def ask: Int < Ask = kyo.proto.kernel.ArrowEffect.suspend[Any](Tag[Ask], ())

        "a discarded captured continuation still releases the bracket" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < (Ask & Sync) =
                Sync.acquireReleaseWith(Sync.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val dropped: Int < Sync =
                kyo.proto.kernel.ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
            assert(eval(Sync.Unsafe.run(dropped)) == -1)
            assert(seen.exists(_.isDefined))
        }

        "a captured continuation resumed in the clause completes the bracket there" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val body: Int < (Ask & Sync) =
                Sync.acquireReleaseWith(Sync.defer(7))((_, outcome) => seen = Maybe(outcome)) { a =>
                    ask.map(x => a + x)
                }
            val resumed: Int < Sync =
                kyo.proto.kernel.ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => cont(1), b => b)
            assert(eval(Sync.Unsafe.run(resumed)) == 8)
            assert(seen == Maybe(Maybe.empty))
        }
    }

    "ensure" - {
        "runs after completion with Absent" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Sync.ensure(outcome => seen = Maybe(outcome))(Sync.defer(5))
            assert(eval(Sync.Unsafe.run(v)) == 5)
            assert(seen == Maybe(Maybe.empty))
        }

        "runs with the failure on unwind" in {
            var seen = Maybe.empty[Maybe[Throwable]]
            val v    = Sync.ensure(outcome => seen = Maybe(outcome))(Sync.defer((throw Boom): Int))
            val ex   = intercept[RuntimeException](eval(Sync.Unsafe.run(v)))
            assert(ex eq Boom)
            assert(seen == Maybe(Maybe(Boom)))
        }
    }
end SyncTest
