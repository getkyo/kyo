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
                    Result.success(1)
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
        "doesn't accept other pending effects" in {
            assertDoesNotCompile("IO.Unsafe.run[Int < Options](Options.get(Some(1)))")
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
            assertDoesNotCompile("""
                val a: Int < Abort[String] = 1
                val b: Int < IO            = a
            """)
        }

        "preserves Nothing as most specific error type" in {
            assertDoesNotCompile("""
                val io: Int < IO = IO {
                    Abort.fail[String]("error")
                }
            """)
        }
    }

end IOTest
