package kyo

import kyo.*
import kyoTest.KyoTest
import org.scalatest.compatible.Assertion
import scala.util.Try

class DefersTest extends KyoTest:

    "run" - {
        "execution" in {
            var called = false
            val v: Int < Defers =
                Defers {
                    called = true
                    1
                }
            assert(!called)
            assert(
                Defers.run(v).pure ==
                    1
            )
            assert(called)
        }
        "stack-safe" in {
            val frames = 100000
            def loop(i: Int): Assertion < Defers =
                Defers {
                    if i < frames then
                        loop(i + 1)
                    else
                        succeed
                }
            val r = Defers.run(loop(0)).pure
            r
        }
        "failure" in {
            val ex        = new Exception
            def fail: Int = throw ex

            val defers = List(
                Defers(fail),
                Defers(fail).map(_ + 1),
                Defers(1).map(_ => fail),
                Defers(Defers(1)).map(_ => fail)
            )
            defers.foreach { d =>
                assert(Try(Defers.run(d)) == Try(fail))
            }
            succeed
        }
        "accepts other pending effects" in {
            assert(Options.run(Defers.run(Options.get(Some(1)))).pure == Some(1))
        }
    }

    "composition" - {
        "handle" in {
            assert(
                Defers.run(1: Int < Defers).pure ==
                    1
            )
        }
        "handle + transform" in {
            assert(
                Defers.run((1: Int < Defers).map(_ + 1)).pure ==
                    2
            )
        }
        "handle + effectful transform" in {
            assert(
                Defers.run((1: Int < Defers).map(i => Defers(i + 1))).pure ==
                    2
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                Defers.run((1: Int < Defers).map(_ + 1).map(i => Defers(i + 1))).pure ==
                    3
            )
        }
    }
end DefersTest
