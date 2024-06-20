package kyo2

import org.scalatest.compatible.Assertion
import scala.util.Failure
import scala.util.Success
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
            assert(IO.runLazy(v).eval == 1)
            assert(called)
        }
        "next handled effects can execute" in run {
            var called = false
            val v =
                Env.get[Int].map { i =>
                    IO {
                        called = true
                        i
                    }
                }
            assert(!called)
            val v2 = IO.runLazy(v)
            assert(!called)
            assert(
                Env.run(1)(v2).eval ==
                    1
            )
            assert(called)
        }
        "failure" in run {
            val ex        = new Exception
            def fail: Int = throw ex

            val ios = List(
                IO(fail),
                IO(fail).map(_ + 1),
                IO(1).map(_ => fail),
                IO(IO(1)).map(_ => fail)
            )
            ios.foreach { io =>
                assert(Try(IO.runLazy(io)) == Try(fail))
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
            assert(
                IO.runLazy(loop(0)).eval ==
                    frames
            )
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
            assert(
                IO.run(v).eval == 1
            )
            assert(called)
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
            IO.run(loop(0))
        }
        "failure" in run {
            val ex        = new Exception
            def fail: Int = throw ex

            val ios = List(
                IO(fail),
                IO(fail).map(_ + 1),
                IO(1).map(_ => fail),
                IO(IO(1)).map(_ => fail)
            )
            ios.foreach { io =>
                assert(Try(IO.run(io)) == Try(fail))
            }
            succeed
        }
        "doesn't accept other pending effects" in {
            assertDoesNotCompile("IO.run[Int < Options](Options.get(Some(1)))")
        }
    }

    "ensure" - {
        "success" in {
            var called = false
            assert(
                IO.run(IO.ensure { called = true }(1)).eval ==
                    1
            )
            assert(called)
        }
        "failure" in {
            val ex     = new Exception
            var called = false
            assert(
                Abort.run[Any](IO.run(IO.ensure { called = true } {
                    IO[Int, Any](throw ex)
                })).eval ==
                    Result.panic(ex)
            )
            assert(called)
        }
    }

end IOTest
