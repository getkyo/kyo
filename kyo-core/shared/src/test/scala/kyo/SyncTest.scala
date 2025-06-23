package kyo

import org.scalatest.compatible.Assertion
import scala.util.Try

class SyncTest extends Test:

    "lazyRun" - {
        "execution" in run {
            var called = false
            val v =
                Sync {
                    called = true
                    1
                }
            assert(!called)
            v.map { result =>
                assert(result == 1)
                assert(called)
            }
        }
        "next handled effects can execute" in run {
            import AllowUnsafe.embrace.danger
            var called = false
            val v =
                Env.get[Int].map { i =>
                    Sync {
                        called = true
                        i
                    }
                }
            assert(!called)
            val v2 = Sync.Unsafe.run(v)
            assert(!called)
            assert(
                Abort.run(Env.run(1)(v2)).eval ==
                    Result.succeed(1)
            )
            assert(called)
        }
        "failure" in run {
            import AllowUnsafe.embrace.danger
            val ex        = new Exception
            def fail: Int = throw ex

            val ios = List(
                Sync(fail),
                Sync(fail).map(_ + 1),
                Sync(1).map(_ => fail),
                Sync(Sync(1)).map(_ => fail)
            )
            ios.foreach { io =>
                assert(Try(Sync.Unsafe.evalOrThrow(io)) == Try(fail))
            }
            succeed
        }
        "stack-safe" in run {
            val frames = 10000
            def loop(i: Int): Int < Sync =
                Sync {
                    if i < frames then
                        loop(i + 1)
                    else
                        i
                }
            loop(0).map { result =>
                assert(result == frames)
            }
        }
    }
    "run" - {
        "execution" in run {
            var called = false
            val v: Int < Sync =
                Sync {
                    called = true
                    1
                }
            assert(!called)
            v.map { result =>
                assert(result == 1)
                assert(called)
            }
        }
        "stack-safe" in run {
            val frames = 100000
            def loop(i: Int): Assertion < Sync =
                Sync {
                    if i < frames then
                        loop(i + 1)
                    else
                        succeed
                }
            loop(0)
        }
        "failure" in run {
            import AllowUnsafe.embrace.danger
            val ex        = new Exception
            def fail: Int = throw ex

            val ios = List(
                Sync(fail),
                Sync(fail).map(_ + 1),
                Sync(1).map(_ => fail),
                Sync(Sync(1)).map(_ => fail)
            )
            ios.foreach { io =>
                assert(Try(Sync.Unsafe.evalOrThrow(io)) == Try(fail))
            }
            succeed
        }
    }

    "ensure" - {
        "success" in run {
            var called = false
            Sync.ensure { called = true }(1).map { result =>
                assert(result == 1)
                assert(called)
            }
        }
        "failure" in run {
            val ex     = new Exception
            var called = false
            Abort.run[Any](Sync.ensure { called = true } {
                Sync[Int, Any](throw ex)
            }).map { result =>
                assert(result == Result.panic(ex))
                assert(called)
            }
        }
        "call-by-name" in run {
            var count       = 0
            var countEnsure = 0

            val io: Unit < Sync =
                Sync.ensure({ countEnsure = countEnsure + 1 })({ count = count + 1 })

            io.andThen(io).map: _ =>
                assert(count == 2)
                assert(countEnsure == 2)
        }
    }

    "evalOrThrow" - {
        import AllowUnsafe.embrace.danger
        "success" in run {
            val result = Sync.Unsafe.evalOrThrow(Sync(42))
            assert(result == 42)
        }

        "throws exceptions" in run {
            val ex = new Exception("test error")
            val io = Sync[Int, Any](throw ex)

            val caught = intercept[Exception] {
                Sync.Unsafe.evalOrThrow(io)
            }
            assert(caught == ex)
        }

        "propagates nested exceptions" in run {
            val ex = new Exception("nested error")
            val io = Sync(Sync(throw ex))

            val caught = intercept[Exception] {
                Sync.Unsafe.evalOrThrow(io)
            }
            assert(caught == ex)
        }

        "works with mapped values" in run {
            val result = Sync.Unsafe.evalOrThrow(Sync(21).map(_ * 2))
            assert(result == 42)
        }
    }

    "abort" - {
        "Sync includes Abort[Nothing]" in {
            val a: Int < Abort[Nothing] = 1
            val b: Int < Sync           = a
            succeed
        }

        "does not include wider Abort types" in {
            typeCheckFailure("""
                val a: Int < Abort[String] = 1
                val b: Int < Sync            = a
            """)(
                "Required: Int < kyo.Sync"
            )
        }

        "preserves Nothing as most specific error type" in {
            typeCheckFailure("""
                val io: Int < Sync = Sync {
                    Abort.fail[String]("error")
                }
            """)(
                "Required: Int < kyo.Sync"
            )
        }
    }

    "withLocal" - {
        "basic usage" in run {
            val local      = Local.init("test")
            var sideEffect = ""

            Sync.withLocal(local) { value =>
                sideEffect = value
                value.length
            }.map { result =>
                assert(sideEffect == "test")
                assert(result == 4)
            }
        }

        "respects local modifications" in run {
            val local    = Local.init("initial")
            var captured = ""

            local.let("modified") {
                Sync.withLocal(local) { value =>
                    captured = value
                    value.toUpperCase
                }
            }.map { result =>
                assert(captured == "modified")
                assert(result == "MODIFIED")
            }
        }

        "lazy evaluation" in run {
            val local    = Local.init("test")
            var executed = false

            val computation =
                Sync.withLocal(local) { value =>
                    executed = true
                    value
                }

            assert(!executed)
            computation.map { result =>
                assert(executed)
                assert(result == "test")
            }
        }
    }

    "Unsafe.withLocal" - {

        def unsafeOperation(value: Int)(using unsafe: AllowUnsafe): Int =
            value * 2

        "allows unsafe operations" in run {
            val local      = Local.init(42)
            var sideEffect = 0

            Sync.Unsafe.withLocal(local) { value =>
                sideEffect = unsafeOperation(value)
                sideEffect
            }.map { result =>
                assert(result == 84)
                assert(sideEffect == 84)
            }
        }

        "respects local context" in run {
            val local    = Local.init(10)
            var captured = 0

            local.let(20) {
                Sync.Unsafe.withLocal(local) { value =>
                    captured = unsafeOperation(value)
                    value + 1
                }
            }.map { result =>
                assert(captured == 40)
                assert(result == 21)
            }
        }

        "composes with other unsafe operations" in run {
            val local            = Local.init(5)
            var steps: List[Int] = Nil

            val computation =
                for
                    v1 <- Sync.Unsafe.withLocal(local) { value =>
                        steps = unsafeOperation(value) :: steps
                        value * 2
                    }
                    v2 <- Sync.Unsafe {
                        steps = v1 :: steps
                        v1 + 1
                    }
                yield v2

            computation.map { result =>
                assert(steps == List(10, 10))
                assert(result == 11)
            }
        }
    }

end SyncTest
