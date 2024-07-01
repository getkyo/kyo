package kyo2.kernel

import kyo.Tag
import kyo2.Test
import scala.concurrent.Future

class BoundaryTest extends Test:
    sealed trait TestEffect       extends ContextEffect[Int]
    sealed trait AnotherEffect    extends ContextEffect[String]
    sealed trait NotContextEffect extends Effect[Const[Int], Const[Int]]

    "isolates runtime effect" in {
        val a = ContextEffect.suspend(Tag[TestEffect])
        val b: Int < TestEffect =
            Boundary[TestEffect, Any](a) { cont =>
                val _: Int < Any = cont
                cont.map(_ * 2)
            }
        val c = ContextEffect.handle(Tag[TestEffect], 2, _ => 3)(b)
        assert(c.eval == 4)
    }

    "continuation can be evaluated" in {
        var called = 0
        val a =
            ContextEffect.suspend(Tag[TestEffect]).map { v =>
                called += 1
                v
            }
        val b: Int < TestEffect =
            Boundary[TestEffect, Any](a) { cont =>
                assert(called == 0)
                val _: Int < Any = cont
                assert(called == 0)
                val r = cont.map(_ * 2).eval
                assert(called == 1)
                r
            }
        val c = ContextEffect.handle(Tag[TestEffect], 2, _ => 3)(b)
        assert(c.eval == 4)
        assert(called == 1)
    }

    "preserve outer runtime effects" in {
        val outerEffect = ContextEffect.suspend(Tag[TestEffect])
        val innerEffect = ContextEffect.suspend(Tag[TestEffect])
        val result =
            for
                outer <- outerEffect
                inner <- Boundary[TestEffect, Any](innerEffect) { isolatedEffect =>
                    isolatedEffect.map(_ * 2)
                }
            yield outer + inner
        val handled = ContextEffect.handle(Tag[TestEffect], 20, _ => 20)(result)
        assert(handled.eval == 60)
    }

    "nested boundaries" in {
        val effect: Int < TestEffect = ContextEffect.suspend(Tag[TestEffect])
        val result: Int < TestEffect =
            Boundary[TestEffect, Any](effect) { outer =>
                Boundary[TestEffect, Any](outer) { inner =>
                    inner.map(_ * 2)
                }
            }
        val handled: Int < Any = ContextEffect.handle(Tag[TestEffect], 10, _ => 21)(result)
        assert(handled.eval == 20)
    }

    "two effects" in {
        val effect1 = ContextEffect.suspend[Int, TestEffect](Tag[TestEffect])
        val effect2 = ContextEffect.suspend[String, AnotherEffect](Tag[AnotherEffect])

        val effect: String < (TestEffect & AnotherEffect) =
            for
                i <- effect1
                s <- effect2
            yield s"$i-$s"

        val result = Boundary[TestEffect & AnotherEffect, Any](effect) { isolated =>
            val _: String < Any = isolated
            isolated.map(_.toUpperCase)
        }

        val handled = ContextEffect.handle(Tag[TestEffect], 10, _ => 42) {
            ContextEffect.handle(Tag[AnotherEffect], "default", _.reverse)(result)
        }

        assert(handled.eval == "10-DEFAULT")
    }

    "with non-context effect" in {
        val effect1 = ContextEffect.suspend[Int, TestEffect](Tag[TestEffect])
        val effect2 = Effect.suspend[Int](Tag[NotContextEffect], 1)

        val effect: String < (TestEffect & NotContextEffect) =
            for
                i <- effect1
                s <- effect2
            yield s"$i-$s"

        val result = Boundary[TestEffect, Any](effect) { isolated =>
            val _: String < NotContextEffect = isolated
            assertDoesNotCompile("val _: String < Any = isolated")
            isolated.map(_.toUpperCase)
        }

        val handled = ContextEffect.handle(Tag[TestEffect], 10, _ => 42) {
            Effect.handle(Tag[NotContextEffect], result) {
                [C] => (input, cont) => cont(input + 1)
            }
        }

        assert(handled.eval == "10-2")
    }

    "detects for non-context effects" in {
        assertDoesNotCompile("Boundary[NotContextEffect, Any]")
    }

    "fork boundary" - {

        "leaving only context effects pending" - {

            def fork[E, A, Ctx](v: => A < (NotContextEffect & Ctx))(
                using b: Boundary[Ctx, Any]
            ): Future[A] < Ctx =
                b(v) { v =>
                    val _: A < NotContextEffect = v
                    assertDoesNotCompile("val _: A < Any = v")
                    Future {
                        Effect.handle(Tag[NotContextEffect], v) {
                            [C] => (input, cont) => cont(input + 1)
                        }.eval
                    }
                }
            end fork

            "no suspension" in {
                fork(1).eval.map(i => assert(i == 1))
            }

            "context effect suspension" in {
                val a: Future[Int] < TestEffect = fork(ContextEffect.suspend(Tag[TestEffect]))
                val b: Future[Int] < Any        = ContextEffect.handle(Tag[TestEffect], 10, _ + 10)(a)
                b.eval.map(i => assert(i == 10))
            }

            "non-context effect suspension" in {
                val a: Future[Int] < Any = fork(Effect.suspend[Any](Tag[NotContextEffect], 1))
                a.eval.map(i => assert(i == 2))
            }

            "context and non-context effect suspension" in {
                val a: Future[Int] < TestEffect =
                    fork(Effect.suspend[Any](Tag[NotContextEffect], 1).map(i => ContextEffect.suspend(Tag[TestEffect]).map(_ + i)))
                val b: Future[Int] < Any =
                    ContextEffect.handle(Tag[TestEffect], 10, _ + 10)(a)
                b.eval.map(i => assert(i == 12))
            }
        }

        "leaving context effects + non-context effect pending" - {

            def fork[E, A, Ctx](v: => A < (NotContextEffect & Ctx))(
                using b: Boundary[Ctx, NotContextEffect]
            ): Future[A] < (Ctx & NotContextEffect) =
                b(v) { v =>
                    val _: A < NotContextEffect = v
                    assertDoesNotCompile("val _: A < Any = v")
                    Effect.suspendMap[Any](Tag[NotContextEffect], 1) { i =>
                        Future {
                            Effect.handle(Tag[NotContextEffect], v) {
                                [C] => (input, cont) => cont(input + i)
                            }.eval
                        }
                    }
                }
            end fork

            "no suspension" in {
                val a: Future[Int] < NotContextEffect = fork(1)
                val b: Future[Int] < Any =
                    Effect.handle(Tag[NotContextEffect], a) {
                        [C] => (input, cont) => cont(input + 1)
                    }
                b.eval.map(i => assert(i == 1))
            }

            "context effect suspension" in {
                val a: Future[Int] < (TestEffect & NotContextEffect) =
                    fork(ContextEffect.suspend(Tag[TestEffect]))
                val b: Future[Int] < NotContextEffect =
                    ContextEffect.handle(Tag[TestEffect], 10, _ + 10)(a)
                val c: Future[Int] < Any =
                    Effect.handle(Tag[NotContextEffect], b) {
                        [C] => (input, cont) => cont(input + 1)
                    }
                c.eval.map(i => assert(i == 10))
            }

            "non-context effect suspension" in {
                val a: Future[Int] < NotContextEffect = fork(Effect.suspend[Any](Tag[NotContextEffect], 1))
                val b: Future[Int] < Any =
                    Effect.handle(Tag[NotContextEffect], a) {
                        [C] => (input, cont) => cont(input + 1)
                    }
                b.eval.map(i => assert(i == 3))
            }

            "context and non-context effect suspension" in {
                val a: Future[Int] < (TestEffect & NotContextEffect) =
                    fork(Effect.suspend[Any](Tag[NotContextEffect], 1).map(i => ContextEffect.suspend(Tag[TestEffect]).map(_ + i)))
                val b: Future[Int] < NotContextEffect =
                    ContextEffect.handle(Tag[TestEffect], 10, _ + 10)(a)
                val c =
                    Effect.handle(Tag[NotContextEffect], b) {
                        [C] => (input, cont) => cont(input + 1)
                    }
                c.eval.map(i => assert(i == 13))
            }
        }
    }

    "seq boundary" - {
        "empty sequence" in {
            val result = Boundary[TestEffect, Any](Seq.empty[Int < TestEffect]) { seq =>
                assert(seq.isEmpty)
                42
            }
            val handled = ContextEffect.handle(Tag[TestEffect], 0, _ => 0)(result)
            assert(handled.eval == 42)
        }

        "sequence of pure values" in {
            val seq = Seq(1, 2, 3)
            val result = Boundary[TestEffect, Any](seq.map(x => x: Int < TestEffect)) { isolatedSeq =>
                assert(isolatedSeq.size == 3)
                isolatedSeq.map(_.eval).sum
            }
            val handled = ContextEffect.handle(Tag[TestEffect], 0, _ => 0)(result)
            assert(handled.eval == 6)
        }

        "sequence with effects" in {
            val seq = Seq(
                ContextEffect.suspend(Tag[TestEffect]),
                ContextEffect.suspend(Tag[TestEffect]),
                ContextEffect.suspend(Tag[TestEffect])
            )
            val result = Boundary[TestEffect, Any](seq) { isolatedSeq =>
                isolatedSeq.map(_.eval).sum
            }
            val handled = ContextEffect.handle(Tag[TestEffect], 10, _ + 1)(result)
            assert(handled.eval == 30)
        }

        "sequence with mixed pure and effect values" in {
            val seq = Seq(
                1: Int < TestEffect,
                ContextEffect.suspend(Tag[TestEffect]),
                3: Int < TestEffect
            )
            val result = Boundary[TestEffect, Any](seq) { isolatedSeq =>
                isolatedSeq.map(_.eval).sum
            }
            val handled = ContextEffect.handle(Tag[TestEffect], 10, _ + 1)(result)
            assert(handled.eval == 14)
        }

        "nested effects in sequence" in {
            val seq = Seq(
                ContextEffect.suspend(Tag[TestEffect]).map(_ * 2),
                ContextEffect.suspend(Tag[TestEffect]).flatMap(x => ContextEffect.suspend(Tag[TestEffect]).map(_ + x)),
                ContextEffect.suspend(Tag[TestEffect])
            )
            val result = Boundary[TestEffect, Any](seq) { isolatedSeq =>
                isolatedSeq.map(_.eval).sum
            }
            val handled = ContextEffect.handle(Tag[TestEffect], 10, _ + 1)(result)
            assert(handled.eval == 50)
        }

        "with multiple effect types" in {
            val seq = Seq(
                ContextEffect.suspend[Int, TestEffect](Tag[TestEffect]),
                ContextEffect.suspend[String, AnotherEffect](Tag[AnotherEffect]).map(_.length)
            )
            val result = Boundary[TestEffect & AnotherEffect, Any](seq) { isolatedSeq =>
                isolatedSeq.map(_.eval).sum
            }
            val handled = ContextEffect.handle(Tag[TestEffect], 10, _ + 1) {
                ContextEffect.handle(Tag[AnotherEffect], "test", _.toUpperCase)(result)
            }
            assert(handled.eval == 14)
        }
    }
end BoundaryTest
