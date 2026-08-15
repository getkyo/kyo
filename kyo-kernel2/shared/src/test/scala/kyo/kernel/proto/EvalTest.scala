package kyo.kernel.proto

import kyo.Const
import kyo.Loop
import kyo.Loop.Outcome
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class EvalTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def continue[A, O](v: A): Outcome[A, O] < Any =
        Loop.continue[A, O, Any](v).asInstanceOf[Outcome[A, O] < Any]

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => continue(value), a => a)

    "a settled value evaluates to itself" in {
        assert(Eval(42: Int < Any) == 42)
    }

    "a map chain runs strictly" in {
        assert(Eval((1: Int < Any).map(_ + 1).map(_ * 3)) == 6)
    }

    "a long map tower evaluates in bounded stack" in {
        @tailrec def tower(v: Int < Any, n: Int): Int < Any =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        assert(Eval(tower(0, 1000000)) == 1000000)
    }

    "deep recursion through map pays rescues only" in {
        def loop(i: Int): Int < Any =
            if i == 0 then 0 else (0: Int < Any).map(_ => loop(i - 1))
        assert(Eval(loop(1000000)) == 0)
    }

    "a nested eval shares the stack safely" in {
        val inner = answerAsk(5)(ask)
        val outer = answerAsk(1)(ask.map(a => a + Eval(inner)))
        assert(Eval(outer) == 6)
    }

    "an unhandled suspension reports a bug" in {
        val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
    }

    "a drive cleans its stack after a throw" in {
        def boom: Int < Any = (0: Int < Any).map(_ => throw new RuntimeException("boom"))
        intercept[RuntimeException](Eval(answerAsk(1)(ask.map(_ => boom))))
        assert(Eval(answerAsk(41)(ask.map(_ + 1))) == 42)
    }

end EvalTest
