package kyo

import kyo.kernel.*
import kyo.kernel.internal.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

class ArrowTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value: Int < Any), a => a)

    def inc(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
        new Arrow.Transform[Int, Int, Any]:
            def frame = _frame
            def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) =
                v.map(i => next(i + 1))

    def double(using _frame: Frame): Arrow.Transform[Int, Int, Any] =
        new Arrow.Transform[Int, Int, Any]:
            def frame = _frame
            def apply[C, S2](v: Int < S2, next: Arrow[Int, C, S2]) =
                v.map(i => next(i * 2))

    "identity" - {
        "returns its input" in {
            assert(Arrow.id[Int](42).eval == 42)
        }

        "is one shared instance across element types" in {
            assert(Arrow.id[Int] eq Arrow.id[String])
        }

        "is its own head and tail" in {
            val id = Arrow.id[Int]
            assert(id.head eq id)
            assert(id.tail eq id)
        }

        "passes a pending input through unchanged" in {
            assert(answerAsk(7)(Arrow.id[Int](ask, Arrow.id[Int])).eval == 7)
        }
    }

    "apply builds an arrow from a function" - {
        "over a settled input" in {
            val f = Arrow[Int, Int, Any](i => i + 1)
            assert(f(41).eval == 42)
        }

        "over a pending input" in {
            val f = Arrow[Int, Int, Any](i => i + 1)
            assert(answerAsk(41)(f(ask, Arrow.id[Int])).eval == 42)
        }

        "with an effectful body" in {
            val f = Arrow[Int, Int, Ask](i => ask.map(_ + i))
            assert(answerAsk(10)(f(1)).eval == 11)
        }

        "is multi-shot" in {
            val f = Arrow[Int, Int, Any](i => i + 1)
            assert(f(1).eval == 2)
            assert(f(1).eval == 2)
            assert(f(10).eval == 11)
        }

        "runs its body once per application" in {
            var runs = 0
            val f = Arrow[Int, Int, Any] { i =>
                runs += 1
                i + 1
            }
            assert(runs == 0)
            discard(f(1).eval)
            discard(f(2).eval)
            assert(runs == 2)
        }
    }

    "recursive builds an arrow that can call itself" - {
        "over a settled input" in {
            val countdown = Arrow.recursive[Int, Int, Any]((self, i) => if i == 0 then 0 else self(i - 1))
            assert(countdown(10).eval == 0)
        }

        "in bounded stack" in {
            val countdown = Arrow.recursive[Int, Int, Any]((self, i) => if i == 0 then 0 else self(i - 1))
            assert(countdown(100000).eval == 0)
        }

        "with an effectful body" in {
            val sum = Arrow.recursive[Int, Int, Ask]((self, i) => if i == 0 then 0 else ask.map(a => self(i - a)))
            assert(answerAsk(1)(sum(1000)).eval == 0)
        }
    }

    "chain" - {
        "composes in order" in {
            assert(inc.chain(double)(20).eval == 42)
            assert(double.chain(inc)(20).eval == 41)
        }

        "with identity on the right returns the same arrow" in {
            val f = inc
            assert(f.chain(Arrow.id[Int]) eq f)
        }

        "exposes the two halves as head and tail" in {
            val first  = inc
            val second = double
            val chain  = first.chain(second)
            assert(chain.head eq first)
            assert(chain.tail eq second)
        }

        "a transform is its own head with an identity tail" in {
            val f = inc
            assert(f.head eq f)
            assert(f.tail eq Arrow.id[Int])
        }

        "a deep chain evaluates in bounded stack" in {
            @tailrec def build(acc: Arrow[Int, Int, Any], n: Int): Arrow[Int, Int, Any] =
                if n == 0 then acc else build(acc.chain(inc), n - 1)
            assert(build(Arrow.id[Int], 100000)(0).eval == 100000)
        }

        "carries effects from both halves" in {
            val f = Arrow[Int, Int, Ask](i => ask.map(_ + i))
            val g = Arrow[Int, Int, Ask](i => ask.map(_ * i))
            assert(answerAsk(2)(f.chain(g)(1)).eval == 6)
        }
    }

    "frame" - {
        "a transform reports the frame it was built with" in {
            val f = Arrow[Int, Int, Any](i => i + 1)
            assert(f.frame.position.show.contains("ArrowTest.scala"))
        }

        "a chain reports the internal frame" in {
            assert(inc.chain(double).frame eq Frame.internal)
        }
    }

    "an arrow is a function into the pending type" in {
        val f: Int => Int < Any = Arrow[Int, Int, Any](i => i + 1)
        assert(List(1, 2, 3).map(i => f(i).eval) == List(2, 3, 4))
    }

    // Not supported yet: the previous kernel rendered arrows through `toString` and exposed the
    // composed shape through `step`. This implementation has neither, so the corpus is kept with
    // its code commented until the surface exists.
    //
    // "toString renders identity and transform frames" in {
    //     assert(Arrow.id[Int].toString == "Arrow(identity)")
    //     assert(inc.toString.startsWith("Arrow("))
    //     assert(inc.toString.contains("ArrowTest.scala"))
    // }
    //
    // "a composed arrow renders its shape and frame info" in {
    //     val step = inc.chain(inc)
    //     assert(step.toString.startsWith("Arrow.Step("))
    //     assert(step.toString.contains("ArrowTest.scala"))
    //     val chained = inc.chain(inc).chain(inc)
    //     assert(chained.toString.startsWith("Arrow.Chain("))
    //     assert(chained.toString.contains("Arrow.Step("))
    // }
    //
    // "step exposes the first transform and the rest" in {
    //     val first  = inc
    //     val second = inc
    //     val step   = first.chain(second).step
    //     assert(step.head eq first)
    //     assert(step.tail eq second)
    // }

end ArrowTest
