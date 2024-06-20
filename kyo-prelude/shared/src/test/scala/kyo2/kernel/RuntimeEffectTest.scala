package kyo2.kernel

import kyo.Tag
import kyo2.Test
import kyo2.kernel.*

class RuntimeEffectTest extends Test:

    sealed trait TestRuntimeEffect1 extends RuntimeEffect[Int]
    sealed trait TestRuntimeEffect2 extends RuntimeEffect[String]
    sealed trait TestRuntimeEffect3 extends RuntimeEffect[Boolean]

    def testRuntimeEffect1: Int < TestRuntimeEffect1 =
        RuntimeEffect.suspend(Tag[TestRuntimeEffect1])

    def testRuntimeEffect2: String < TestRuntimeEffect2 =
        RuntimeEffect.suspend(Tag[TestRuntimeEffect2])

    def testRuntimeEffect3: Boolean < TestRuntimeEffect3 =
        RuntimeEffect.suspend(Tag[TestRuntimeEffect3])

    "suspend" in {
        val effect = testRuntimeEffect1
        assert(effect.isInstanceOf[Int < TestRuntimeEffect1])
    }

    "handle" - {
        "single effect" in {
            val effect = testRuntimeEffect1
            val result = RuntimeEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1)(effect)
            assert(result.eval == 42)
        }

        "two effects" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                yield s"$i-$s"

            val result =
                RuntimeEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1) {
                    RuntimeEffect.handle(Tag[TestRuntimeEffect2], "default", _.toUpperCase)(effect)
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
                RuntimeEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1) {
                    RuntimeEffect.handle(Tag[TestRuntimeEffect2], "default", _.toUpperCase) {
                        RuntimeEffect.handle(Tag[TestRuntimeEffect3], false, !_)(effect): String < (TestRuntimeEffect1 & TestRuntimeEffect2)
                    }
                }

            assert(result.eval == "42-default-false")
        }

        "ifUndefined behavior" in {
            val effect = testRuntimeEffect1
            val result = RuntimeEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2)(effect)
            assert(result.eval == 100)
        }

        "ifDefined behavior" in {
            val effect =
                for
                    _ <- testRuntimeEffect1
                    i <- testRuntimeEffect1
                yield i

            val result =
                RuntimeEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2) {
                    RuntimeEffect.handle(Tag[TestRuntimeEffect1], 100, _ * 2)(effect)
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

            val result = RuntimeEffect.handle(Tag[TestRuntimeEffect1], 10, _ + 1)(effect)
            assert(result.eval == 30)
        }

        "nested effects" in {
            val innerEffect = testRuntimeEffect2
            val outerEffect =
                for
                    i <- testRuntimeEffect1
                    s <- RuntimeEffect.handle(Tag[TestRuntimeEffect2], "inner", _.toUpperCase)(innerEffect)
                yield s"$i-$s"

            val result = RuntimeEffect.handle(Tag[TestRuntimeEffect1], 42, _ + 1)(outerEffect)
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
                RuntimeEffect.handle(Tag[TestRuntimeEffect3], true, !_) {
                    RuntimeEffect.handle(Tag[TestRuntimeEffect2], "middle", _.toUpperCase) {
                        RuntimeEffect.handle(Tag[TestRuntimeEffect1], 10, _ * 2)(effect): String < (TestRuntimeEffect2 & TestRuntimeEffect3)
                    }
                }

            assert(result.eval == "10-middle-true")
        }

        "complex transformation" in {
            val effect =
                for
                    i <- testRuntimeEffect1
                    s <- testRuntimeEffect2
                yield s"$i-$s"

            val result = RuntimeEffect.handle(Tag[TestRuntimeEffect1], 1, i => if i < 10 then i * 2 else i / 2) {
                RuntimeEffect.handle(Tag[TestRuntimeEffect2], "start", s => s + s.length.toString)(effect)
            }

            assert(result.eval == "1-start")
        }
    }

end RuntimeEffectTest
