package kyo.kernel.proto

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
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

    "a continuation folded from the drive stack runs every pending map exactly once" in {
        val runs = new Array[Int](2)
        val body: Int < Ask =
            ask.map(a => ask.map(b => a * 10 + b))
                .map { v =>
                    runs(0) += 1; v + 1
                }
                .map { v =>
                    runs(1) += 1; v * 2
                }
        assert(Eval(answerAsk(3)(body)) == 68)
        assert(runs.toList == List(1, 1))
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

    "partial evaluation" - {

        "a preemption stop reifies and resumes with handler state" in {
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            val counted: Int < Any = ArrowEffect.handleLoopState(Tag[Ask], 0, countdown(100))(
                [C] =>
                    (n, _) =>
                        if n == 10 then discard(Safepoint.stop(Thread.currentThread()))
                        Loop.continue(n + 1, 1)
                ,
                (n, a) => n + a
            )
            val first = Eval.partial(counted)
            assert(first.evalNow == Maybe.Absent)
            assert(Eval.partial(first).evalNow == Maybe(100))
        }

        "the stop function ends the slice" in {
            var steps = 0
            val stop = () =>
                steps += 1; steps > 50
            def countdown(i: Int): Int < Ask =
                if i == 0 then 0 else ask.map(a => countdown(i - a))
            val first = Eval.partial(answerAsk(1)(countdown(1000)), stop)
            assert(first.evalNow == Maybe.Absent)
            assert(Eval.partial(first).evalNow == Maybe(0))
        }

        "partial completes when nothing stops" in {
            assert(Eval.partial(answerAsk(21)(ask.map(_ * 2))).evalNow == Maybe(42))
        }

        "a stop delivered between slices short-circuits" in {
            val v: Int < Any = answerAsk(41)(ask.map(_ + 1))
            discard(Safepoint.stop(Thread.currentThread()))
            val r = Eval.partial(v)
            assert(r.asInstanceOf[AnyRef] eq v.asInstanceOf[AnyRef])
            assert(Eval.partial(r).evalNow == Maybe(42))
        }

        // A throw escaping a root eval skipped `Safepoint.exit`, leaking one unit of depth per throw
        // on a slot that is per thread, so every later unrelated computation on that thread paid for
        // it. The symptom is not a wrong answer: `enter` returning false is the ordinary
        // budget-exhausted path, so an affected thread simply parks and allocates more, forever, with
        // nothing to point at. `partial` already bracketed its boundary and `apply` did not, and that
        // asymmetry was the whole bug. Measured before the fix at exactly one of 512 lost per throw.
        "a throw escaping a root eval leaves the safepoint depth unchanged" in {
            val slot = Safepoint.get()
            // `save` resets as it reads, so reading it back is a save/restore pair
            def depth() =
                val d = Safepoint.save(slot)
                Safepoint.restore(slot, d)
                d
            end depth
            val before = depth()
            var caught = 0
            var i      = 0
            while i < 50 do
                try discard(Eval(answerAsk(1)(ask.map(v => if v > 0 then throw new RuntimeException("boom") else v))))
                catch case _: RuntimeException => caught += 1
                i += 1
            end while
            assert(caught == 50)
            // `equals` rather than `==`: `State` is opaque and carries no `CanEqual`, and a cast to
            // its underlying Int would be a new cast for a test's convenience
            assert(depth().equals(before))
        }
    }

end EvalTest
