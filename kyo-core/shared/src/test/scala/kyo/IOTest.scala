package kyo

import org.scalatest.compatible.Assertion
import scala.util.Try

class IOTest extends Test:

    "lazyRun" - {
        "execution" in run {
            var called = false
            val v =
                IO {
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
                    IO {
                        called = true
                        i
                    }
                }
            assert(!called)
            val v2 = IO.Unsafe.run(v)
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
                IO(fail),
                IO(fail).map(_ + 1),
                IO(1).map(_ => fail),
                IO(IO(1)).map(_ => fail)
            )
            ios.foreach { io =>
                assert(Try(IO.Unsafe.evalOrThrow(io)) == Try(fail))
            }
            succeed
        }
        "stack-safe" in run {
            val frames = 10000
            def loop(i: Int): Int < IO =
                IO {
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
            val v: Int < IO =
                IO {
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
            def loop(i: Int): Assertion < IO =
                IO {
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
                IO(fail),
                IO(fail).map(_ + 1),
                IO(1).map(_ => fail),
                IO(IO(1)).map(_ => fail)
            )
            ios.foreach { io =>
                assert(Try(IO.Unsafe.evalOrThrow(io)) == Try(fail))
            }
            succeed
        }
    }

    "ensure" - {
        "success" in run {
            var called = false
            IO.ensure { called = true }(1).map { result =>
                assert(result == 1)
                assert(called)
            }
        }
        "failure" in run {
            val ex     = new Exception
            var called = false
            Abort.run[Any](IO.ensure { called = true } {
                IO[Int, Any](throw ex)
            }).map { result =>
                assert(result == Result.panic(ex))
                assert(called)
            }
        }
    }

    "evalOrThrow" - {
        import AllowUnsafe.embrace.danger
        "success" in run {
            val result = IO.Unsafe.evalOrThrow(IO(42))
            assert(result == 42)
        }

        "throws exceptions" in run {
            val ex = new Exception("test error")
            val io = IO[Int, Any](throw ex)

            val caught = intercept[Exception] {
                IO.Unsafe.evalOrThrow(io)
            }
            assert(caught == ex)
        }

        "propagates nested exceptions" in run {
            val ex = new Exception("nested error")
            val io = IO(IO(throw ex))

            val caught = intercept[Exception] {
                IO.Unsafe.evalOrThrow(io)
            }
            assert(caught == ex)
        }

        "works with mapped values" in run {
            val result = IO.Unsafe.evalOrThrow(IO(21).map(_ * 2))
            assert(result == 42)
        }
    }

    "abort" - {
        "IO includes Abort[Nothing]" in {
            val a: Int < Abort[Nothing] = 1
            val b: Int < IO             = a
            succeed
        }

        "does not include wider Abort types" in {
            typeCheckFailure("""
                val a: Int < Abort[String] = 1
                val b: Int < IO            = a
            """)(
                "Required: Int < kyo.IO"
            )
        }

        "preserves Nothing as most specific error type" in {
            typeCheckFailure("""
                val io: Int < IO = IO {
                    Abort.fail[String]("error")
                }
            """)(
                "Required: Int < kyo.IO"
            )
        }
    }

    "withLocal" - {
        "basic usage" in run {
            val local      = Local.init("test")
            var sideEffect = ""

            IO.withLocal(local) { value =>
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
                IO.withLocal(local) { value =>
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
                IO.withLocal(local) { value =>
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

            IO.Unsafe.withLocal(local) { value =>
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
                IO.Unsafe.withLocal(local) { value =>
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
                    v1 <- IO.Unsafe.withLocal(local) { value =>
                        steps = unsafeOperation(value) :: steps
                        value * 2
                    }
                    v2 <- IO.Unsafe {
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

end IOTest
