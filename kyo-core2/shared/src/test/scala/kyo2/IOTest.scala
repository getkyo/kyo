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

    // "ensure" - {
    //     "success" in {
    //         var called = false
    //         assert(
    //             IO.toTry(IO.run(IO.ensure { called = true }(1))).eval ==
    //                 Try(1)
    //         )
    //         assert(called)
    //     }
    //     "failure" in {
    //         val ex     = new Exception
    //         var called = false
    //         assert(
    //             IO.toTry(IO.run(IO.ensure { called = true } {
    //                 IO[Int, Any](throw ex)
    //             })).eval ==
    //                 Failure(ex)
    //         )
    //         assert(called)
    //     }
    // }

    val e = new Exception

    "toTry" - {
        "failure" in {
            assert(
                IO.run(IO.toTry((throw e): Int)).eval ==
                    Failure(e)
            )
        }
        "success" in {
            assert(
                IO.run(IO.toTry(1)).eval ==
                    Success(1)
            )
        }
    }

    "fromTry" - {
        "failure" in {
            assert(
                IO.run(IO.toTry(IO.fromTry(Failure[Int](e)))).eval ==
                    Failure(e)
            )
        }
        "success" in {
            assert(
                IO.run(IO.toTry(IO.fromTry(Success(1)))).eval ==
                    Success(1)
            )
        }
    }

    "fail" in {
        assert(
            IO.run(IO.toTry(IO.fail(e))).eval ==
                Failure(e)
        )
    }

    "failures" - {
        "handle" in {
            assert(
                IO.run(IO.toTry(1: Int < IO)).eval ==
                    Try(1)
            )
        }
        "handle + transform" in {
            assert(
                IO.run(IO.toTry((1: Int < IO).map(_ + 1))).eval ==
                    Try(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                IO.run(IO.toTry((1: Int < IO).map(i => IO.fromTry(Try(i + 1))))).eval ==
                    Try(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                IO.run(IO.toTry((1: Int < IO).map(_ + 1).map(i => IO.fromTry(Try(i + 1))))).eval ==
                    Try(3)
            )
        }
        "handle + transform + failed effectful transform" in {
            val e = new Exception
            assert(
                IO.run(
                    IO.toTry((1: Int < IO).map(_ + 1).map(i => IO.fromTry(Try[Int](throw e))))
                ).eval ==
                    Failure(e)
            )
        }
    }

    "effectful" - {
        "handle" in {
            assert(
                IO.run(IO.toTry(IO.fromTry(Try(1)))).eval ==
                    Try(1)
            )
        }
        "handle + transform" in {
            assert(
                IO.run(IO.toTry(IO.fromTry(Try(1)).map(_ + 1))).eval ==
                    Try(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                IO.run(IO.toTry(IO.fromTry(Try(1)).map(i => IO.fromTry(Try(i + 1))))).eval ==
                    Try(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                IO.run(
                    IO.toTry((IO.fromTry(Try(1))).map(_ + 1).map(i => IO.fromTry(Try(i + 1))))
                ).eval ==
                    Try(3)
            )
        }
        "handle + failed transform" in {
            assert(
                IO.run(IO.toTry((IO.fromTry(Try(1))).map(_ => (throw e): Int))).eval ==
                    Failure(e)
            )
        }
        "handle + transform + effectful transform + failed transform" in {
            assert(
                IO.run(IO.toTry((IO.fromTry(Try(1))).map(_ + 1).map(i =>
                    IO.fromTry(Try(i + 1))
                ).map(_ =>
                    (throw e): Int
                ))).eval ==
                    Failure(e)
            )
        }
        "handle + transform + failed effectful transform" in {
            assert(
                IO.run(IO.toTry((IO.fromTry(Try(1))).map(_ + 1).map(i =>
                    IO.fromTry(Try((throw e): Int))
                ))).eval ==
                    Failure(e)
            )
        }
        "nested effect + failure" in {
            assert(
                IO.run(
                    Abort.run(
                        IO.toTry[Int, Abort[None.type] & IO](
                            IO.fromTry(Try(Option(1))).map(opt =>
                                Abort.get(opt: Option[Int]).map(_ => (throw e): Int)
                            )
                        )
                    )
                ).eval ==
                    Result.success(Failure(e))
            )
        }
    }
end IOTest
