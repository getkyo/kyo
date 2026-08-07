package kyo.kernel2

import kyo.Tag
import kyo.test.Test
import language.implicitConversions

sealed trait Env   extends ContextEffect[Int]
sealed trait Name  extends ContextEffect[String]
sealed trait CtxOp extends ArrowEffect[Const[Unit], Const[Int]]

class ContextEffectTest extends Test[Any]:

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
        val parked = ArrowEffect.handlePartial(Tag[CtxOp], bound)(
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
        val k = captured.asInstanceOf[Arrow[Int, Int, CtxOp]]
        assert(k(10).asInstanceOf[Int < Any].eval == 15)
        assert(k(20).asInstanceOf[Int < Any].eval == 25)
    }

    "context reads inside arrow handler clauses resolve against the clause scope" in {
        val program = op.map(_ + 1)
        val handled: Int < Env = ArrowEffect.handleResume(Tag[CtxOp], program.asInstanceOf[Int < (CtxOp & Env)])(
            [C] => (_) => env.map(_ * 2)
        )
        assert(ContextEffect.handle(Tag[Env], 3)(handled).eval == 7)
    }

end ContextEffectTest
