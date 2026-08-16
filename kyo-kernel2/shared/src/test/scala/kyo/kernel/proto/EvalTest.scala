package kyo.kernel.proto

import kyo.Const
import kyo.Tag
import kyo.kernel.proto.Arrow.Bind
import kyo.kernel.proto.Arrow.Identity
import kyo.kernel.proto.Arrow.Transform
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class EvalTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A](value: Int)(v: A < Ask): A < Any =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

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

    "a deep map tower over a suspension evaluates in bounded stack" in {
        @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        assert(Eval(answerAsk(1)(tower(ask, 100000))) == 100001)
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

    "a deferred continuation replays each trailing map exactly once" in {
        val runs = new Array[Int](64)
        val defer: Int < Any =
            new Transform[Any, Int, Any]:
                def frame = kyo.Frame.internal
                def apply[C, S2](v: Any < S2, next: Arrow[Int, C, S2]): C < S2 =
                    Bind(7, next)
        var r: Int < Any = defer
        for i <- 0 until 64 do
            val j = i
            r = r.map { x =>
                runs(j) += 1
                x + 1
            }
        end for
        assert(Eval(r) == 71)
        assert(runs.forall(_ == 1))
    }

    "a continuation applied twice replays trailing maps twice at any depth" in {
        for depth <- List(8, 64) do
            val runs = new Array[Int](depth)
            val both: Int < Any =
                new Transform[Any, Int, Any]:
                    def frame = kyo.Frame.internal
                    def apply[C, S2](v: Any < S2, next: Arrow[Int, C, S2]): C < S2 =
                        Eval(Identity(3, next.asInstanceOf[Arrow[Any, C, Any]]).asInstanceOf[C < Any])
                        Bind(10, next)
            var r: Int < Any = both
            for i <- 0 until depth do
                val j = i
                r = r.map { x =>
                    runs(j) += 1
                    x + 1
                }
            end for
            assert(Eval(r) == 10 + depth)
            assert(runs.forall(_ == 2))
    }

    "a reified continuation stays valid after its drive completes" in {
        for depth <- List(8, 64) do
            val runs                        = new Array[Int](depth)
            var stash: Arrow[Any, Any, Any] = null
            val node: Int < Any =
                new Transform[Any, Int, Any]:
                    def frame = kyo.Frame.internal
                    def apply[C, S2](v: Any < S2, next: Arrow[Int, C, S2]): C < S2 =
                        stash = next.asInstanceOf[Arrow[Any, Any, Any]]
                        Bind(0, next)
            var r: Int < Any = node
            for i <- 0 until depth do
                val j = i
                r = r.map { x =>
                    runs(j) += 1
                    x + 1
                }
            end for
            assert(Eval(r) == depth)
            assert(runs.forall(_ == 1))
            assert(Eval(Identity(100, stash).asInstanceOf[Int < Any]) == 100 + depth)
            assert(runs.forall(_ == 2))
    }

    "a throw inside a region leaves no findable handler behind" in {
        def stateful[A](v: A < Ask): A < Any =
            ArrowEffect.handleLoopState(Tag[Ask], 0, v)(
                [C] => (st, _) => Loop.continue(st + 1, st),
                (_, a) => a
            )
        intercept[RuntimeException](Eval(stateful(ask.map(_ => (throw new RuntimeException("boom")): Int))))
        val ex = intercept[Throwable](Eval(ask.asInstanceOf[Int < Any]))
        assert(ex.getMessage.contains("unhandled suspension"))
        assert(Eval(stateful(ask.map(a => ask.map(b => a * 10 + b)))) == 1)
    }

end EvalTest
