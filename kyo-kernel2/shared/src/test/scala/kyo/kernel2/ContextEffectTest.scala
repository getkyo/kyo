package kyo.kernel2

import kyo.Tag
import kyo.discard
import kyo.kernel2.internal.Context
import kyo.test.Test
import language.implicitConversions

sealed trait Env    extends ContextEffect[Int]
sealed trait Name   extends ContextEffect[String]
sealed trait CtxOp  extends ArrowEffect[Const[Unit], Const[Int]]
sealed trait CtxOp2 extends ArrowEffect[Const[Int], Const[Int]]

class ContextEffectTest extends Test[Any]:

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
        val effect: Int < TestRuntimeEffect1 = testRuntimeEffect1
        discard(effect)
        succeed("ContextEffect.suspend produces an Int < TestRuntimeEffect1; the type ascription above is the verification")
    }

    "handle" - {

        "const value" in {
            val effect = testRuntimeEffect1
            val result = ContextEffect.handle(Tag[TestRuntimeEffect1], 42)(effect)
            assert(result.eval == 42)
        }

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

    // kernel2-specific coverage beyond the ported suite

    def env: Int < Env =
        ContextEffect.suspend[Int, Env](Tag[Env])

    def readName: String < Name =
        ContextEffect.suspend[String, Name](Tag[Name])

    def op: Int < CtxOp =
        ArrowEffect.suspend[Any](Tag[CtxOp], ())

    "a read observes the handler's binding" in {
        val program = env.map(_ + 1)
        assert(ContextEffect.handle(Tag[Env], 41)(program).eval == 42)
    }

    "reads observe the same binding throughout the scope" in {
        val program = env.map(a => env.map(b => a * 100 + b))
        assert(ContextEffect.handle(Tag[Env], 7)(program).eval == 707)
    }

    "handling installs without executing" in {
        var ran = false
        val program = env.map { v =>
            ran = true
            v
        }
        val handled = ContextEffect.handle(Tag[Env], 1)(program)
        assert(!ran)
        assert(handled.eval == 1)
        assert(ran)
    }

    "inner binding shadows the outer one" in {
        val program = env.map(_ * 10)
        val inner   = ContextEffect.handle(Tag[Env], 2)(program)
        val outer   = ContextEffect.handle(Tag[Env], 9)(inner.asInstanceOf[Int < Env])
        assert(outer.eval == 20)
    }

    "ifDefined transforms the outer binding" in {
        val program = env.map(identity)
        val inner   = ContextEffect.handle(Tag[Env], 100, outer => outer + 1)(program)
        val outer   = ContextEffect.handle(Tag[Env], 10)(inner.asInstanceOf[Int < Env])
        assert(outer.eval == 11)
    }

    "ifUndefined supplies the value with no outer binding" in {
        val program = env.map(identity)
        val handled = ContextEffect.handle(Tag[Env], 100, outer => outer + 1)(program)
        assert(handled.eval == 100)
    }

    "a default read runs unhandled" in {
        val v = ContextEffect.suspend[Int, Env](Tag[Env], 5)
        assert(v.map(_ + 1).eval == 6)
    }

    "a binding wins over the default" in {
        val v: Int < Any = ContextEffect.suspend[Int, Env](Tag[Env], 5)
        val handled      = ContextEffect.handle(Tag[Env], 10)(v.asInstanceOf[Int < Env])
        assert(handled.map(_ + 1).eval == 11)
    }

    "a defaulted read maps in one step" in {
        val v = ContextEffect.suspendWith[Int, Env, Int, Any](Tag[Env], 5)(_ * 2)
        assert(v.eval == 10)
        val handled = ContextEffect.handle(Tag[Env], 10)(v.asInstanceOf[Int < Env])
        assert(handled.eval == 20)
    }

    "distinct context effects resolve independently" in {
        val program: String < (Env & Name) =
            env.map(n => readName.map(s => s + n))
        val handled =
            ContextEffect.handle(Tag[Name], "id-")(
                ContextEffect.handle(Tag[Env], 7)(program)
            )
        assert(handled.eval == "id-7")
    }

    "a binding installed after composition completes the read" in {
        val program = env.map(_ + 1)
        assert(ContextEffect.handle(Tag[Env], 10)(program).eval == 11)
    }

    "bindings travel with parked computations" in {
        val program: Int < (Env & CtxOp) =
            op.map(a => env.map(b => a + b))
        val bound: Int < CtxOp = ContextEffect.handle(Tag[Env], 5)(program)
        var captured: Any      = null
        val parked = ArrowEffect.handlePartial(Tag[CtxOp], bound, Context.empty)(
            [C] =>
                (in, cont) =>
                    captured = cont
                    kyo.Maybe.Absent
        )
        val resumed = captured.asInstanceOf[Arrow[Int, Int, CtxOp]](100)
        assert(resumed.asInstanceOf[Int < Any].eval == 105)
    }

    "a multi shot continuation re-resolves reads consistently" in {
        val program: Int < (Env & CtxOp) =
            op.map(a => env.map(b => a + b))
        val bound: Int < CtxOp = ContextEffect.handle(Tag[Env], 5)(program)
        var captured: Any      = null
        val handled = ArrowEffect.handle(Tag[CtxOp], bound)(
            [C] =>
                (in, cont) =>
                    captured = cont
                    cont(1)
        )
        assert(handled.eval == 6)
        val k = captured.asInstanceOf[Int => Int < CtxOp]
        assert(k(10).asInstanceOf[Int < Any].eval == 15)
        assert(k(20).asInstanceOf[Int < Any].eval == 25)
    }

    "context reads inside a handle function resolve at the handler's scope" in {
        val program = op.map(_ + 1)
        val handled: Int < Env = ArrowEffect.handleResume(Tag[CtxOp], program.asInstanceOf[Int < (CtxOp & Env)])(
            [C] => (_) => env.map(_ * 2)
        )
        assert(ContextEffect.handle(Tag[Env], 3)(handled).eval == 7)
    }

    "a rebind inside the handled computation stays invisible to the handle function" in {
        // reads inside the handled computation see the inner binding; the handle
        // function sees the binding outside the handler: 100 * 2 = 200 answers the
        // operation, and the inner read of 42 completes 200 * 1000 + 42
        val program: Int < (Env & CtxOp) = op.flatMap(x => env.map(e => x * 1000 + e))
        val inner                        = ContextEffect.handle(Tag[Env], 42)(program)
        val handled = ArrowEffect.handleResume(Tag[CtxOp], inner.asInstanceOf[Int < (CtxOp & Env)])(
            [C] => (_) => env.map(_ * 2)
        )
        assert(ContextEffect.handle(Tag[Env], 100)(handled).eval == 200042)
    }

    "a handle function suspending on an outer effect keeps its scope after resuming" in {
        // the handle function parks on CtxOp2, resumes with 8, and its read still
        // resolves at the handler's scope (3), not the inner rebind (42)
        def t(i: Int): Int < CtxOp2 = ArrowEffect.suspend[Any](Tag[CtxOp2], i)
        val inner                   = ContextEffect.handle(Tag[Env], 42)(op)
        val handled: Int < (Env & CtxOp2) = ArrowEffect.handleResume(Tag[CtxOp], inner.asInstanceOf[Int < (CtxOp & Env & CtxOp2)])(
            [C] => (_) => t(7).flatMap(x => env.map(e => x * 1000 + e))
        )
        val bound = ContextEffect.handle(Tag[Env], 3)(handled)
        assert(ArrowEffect.handle(Tag[CtxOp2], bound)([C] => (in, cont) => cont(in + 1)).eval == 8003)
    }

    "a bare defaulted read resolves at the boundary" in {
        val v: Int < Any = ContextEffect.suspend(Tag[Env], 99)
        assert(v.eval == 99)
    }
end ContextEffectTest
