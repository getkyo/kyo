package kyo2.kernel

import kyo.Tag
import kyo2.Test
import kyo2.kernel.*

class RuntimeEffectTest extends Test:

    sealed trait TestRuntimeEffect1 extends ContextEffect[Int]
    sealed trait TestRuntimeEffect2 extends ContextEffect[String]
    sealed trait TestRuntimeEffect3 extends ContextEffect[Boolean]

    def testRuntimeEffect1: Int < TestRuntimeEffect1 =
        ContextEffect.suspend(Tag[TestRuntimeEffect1])

    def testRuntimeEffect2: String < TestRuntimeEffect2 =
        ContextEffect.suspend(Tag[TestRuntimeEffect2])

    def testRuntimeEffect3: Boolean < TestRuntimeEffect3 =
        ContextEffect.suspend(Tag[TestRuntimeEffect3])

    "suspend" in {
        val effect = testRuntimeEffect1
        assert(effect.isInstanceOf[Int < TestRuntimeEffect1])
    }

    "handle" - {
        "single effect" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1)(effect)
            assert(result.eval == 42)
        }

        "two effects" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                yield s"$i-$s"

            val result =
                ContextEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1) {
                    ContextEffect.handle(Tag[TestRuntimeEffect2], "default", _.toUpperCase)(effect)
                }

            assert(result.eval == "42-default")
        }

        "three effects" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                    b <- testRuntimeEffect3
                yield s"$i-$s-$b"

            val result =
                ContextEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1) {
                    ContextEffect.handle(Tag[TestRuntimeEffect2], "default", _.toUpperCase) {
                        ContextEffect.handle(Tag[TestRuntimeEffect3], false, !_)(effect): String < (TestRuntimeEffect1 & TestRuntimeEffect2)
                    }
                }

            assert(result.eval == "42-default-false")
        }

        "ifUndefined behavior" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2)(effect)
            assert(result.eval == 100)
        }

        "ifDefined behavior" in {
            val effect =
                for
                    _ <- testRuntimeEffect1
                    i <- testRuntimeEffect1
                yield i

            val result =
                ContextEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2) {
                    ContextEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2)(effect)
                }
            assert(result.eval == 200)
        }

        "multiple uses of the same effect" in {
            val effect =
                for
                    i1 <- testRuntimeEffect1
                    i2 <- testRuntimeEffect1
                    i3 <- testRuntimeEffect1
                yield i1 + i2 + i3

            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 10, _ + 1)(effect)
            assert(result.eval == 30)
        }

        "nested effects" in {
            val innerEffect = testRuntimeEffect2
            val outerEffect =
                for
                    i <- testRuntimeEffect1
                    s <- ContextEffect.handle(Tag[TestRuntimeEffect2], "inner", _.toUpperCase)(innerEffect)
                yield s"$i-$s"

            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1)(outerEffect)
            assert(result.eval == "42-inner")
        }

        "effect order preservation" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                    b <- testRuntimeEffect3
                yield s"$i-$s-$b"

            val result =
                ContextEffect.handle(Tag[TestRuntimeEffect3], true, !_) {
                    ContextEffect.handle(Tag[TestRuntimeEffect2], "middle", _.toUpperCase) {
                        ContextEffect.handle(Tag[TestRuntimeEffect1], 10, _ * 2)(effect): String < (TestRuntimeEffect2 & TestRuntimeEffect3)
                    }
                }

            assert(result.eval == "10-middle-true")
        }

        "with transformation" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                yield s"$i-$s"

            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 1, i => if i < 10 then i * 2 else i / 2) {
                ContextEffect.handle(Tag[TestRuntimeEffect2], "start", s => s + s.length.toString)(effect)
            }

            assert(result.eval == "1-start")
        }
    }

    "boundary" - {
        sealed trait TestEffect       extends ContextEffect[Int]
        sealed trait AnotherEffect    extends ContextEffect[String]
        sealed trait NotRuntimeEffect extends Effect[Const[Int], Const[Int]]

        "isolates runtime effect" in {
            val a = ContextEffect.suspend(Tag[TestEffect])
            val b: Int < TestEffect =
                ContextEffect.boundary(a) { cont =>
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
                ContextEffect.boundary(a) { cont =>
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
                    inner <- ContextEffect.boundary(innerEffect) { isolatedEffect =>
                        isolatedEffect.map(_ * 2)
                    }
                yield outer + inner
            val handled = ContextEffect.handle(Tag[TestEffect], 20, _ => 20)(result)
            assert(handled.eval == 60)
        }

        "nested boundaries" in {
            val effect: Int < TestEffect = ContextEffect.suspend(Tag[TestEffect])
            val result: Int < TestEffect =
                ContextEffect.boundary(effect) { outer =>
                    ContextEffect.boundary(outer) { inner =>
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

            val result = ContextEffect.boundary(effect) { isolated =>
                val _: String < Any = isolated
                isolated.map(_.toUpperCase)
            }

            val handled = ContextEffect.handle(Tag[TestEffect], 10, _ => 42) {
                ContextEffect.handle(Tag[AnotherEffect], "default", _.reverse)(result)
            }

            assert(handled.eval == "10-DEFAULT")
        }
    }

end RuntimeEffectTest
