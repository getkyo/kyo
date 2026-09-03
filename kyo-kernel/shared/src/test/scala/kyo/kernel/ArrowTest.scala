package kyo.kernel

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.internal.Safepoint
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec
import scala.compiletime.testing.typeChecks

class ArrowTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(value), a => a)

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
            val f = Arrow[Int](i => i + 1)
            assert(f(41).eval == 42)
        }

        "over a pending input" in {
            val f = Arrow[Int](i => i + 1)
            assert(answerAsk(41)(f(ask, Arrow.id[Int])).eval == 42)
        }

        "with an effectful body" in {
            val f = Arrow[Int](i => ask.map(_ + i))
            assert(answerAsk(10)(f(1)).eval == 11)
        }

        "is multi-shot" in {
            val f = Arrow[Int](i => i + 1)
            assert(f(1).eval == 2)
            assert(f(1).eval == 2)
            assert(f(10).eval == 11)
        }

        "runs its body once per application" in {
            var runs = 0
            val f = Arrow[Int] { i =>
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

        "direct self-application recurses on the call stack" in {
            val countdown = Arrow.recursive[Int, Int, Any]((self, i) => if i == 0 then 0 else self(i - 1))
            assert(countdown(10000).eval == 0)
        }

        "each step through map is budget-rescued at any depth" in {
            val countdown = Arrow.recursive[Int, Int, Any]((self, i) => if i == 0 then 0 else ((i - 1): Int < Any).map(v => self(v)))
            assert(countdown(1000000).eval == 0)
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
            val f = Arrow[Int](i => ask.map(_ + i))
            val g = Arrow[Int](i => ask.map(_ * i))
            assert(answerAsk(2)(f.chain(g)(1)).eval == 6)
        }
    }

    "the two-argument apply" - {
        "is application fused with composition" in {
            val f = Arrow[Int](_ + 1)
            assert(f(41, Arrow.id[Int]).eval == 42)
            assert(f(20, double).eval == 42)
            assert(f(20, double).eval == f.chain(double)(20).eval)
        }

        "defers on a suspended input and evaluates as the composition" in {
            val f = Arrow[Int](_ + 1)
            assert(answerAsk(20)(f(ask, double)).eval == 42)
        }

        "defers when the budget is drained and still completes" in {
            val f     = Arrow[Int](_ + 1)
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            while Safepoint.enter(slot) do ()
            try
                val v = f(41, Arrow.id[Int])
                assert(v.evalNow.isEmpty)
                assert(v.eval == 42)
            finally Safepoint.restore(slot, saved)
            end try
        }

        "stays budget-balanced across repeated direct calls" in {
            val f = Arrow[Int](_ + 1)
            var i = Safepoint.period() * 3
            while i > 0 do
                discard(f(1, Arrow.id[Int]))
                i -= 1
            assert(f(41, Arrow.id[Int]).evalNow == Maybe(42))
        }

        "a throwing body leaves the thread evaluating normally afterwards" in {
            val boom = Arrow[Int](_ => (throw new Exception("boom")): Int)
            discard(intercept[Exception](boom(1, Arrow.id[Int])))
            assert(answerAsk(41)(inc(ask, Arrow.id[Int])).eval == 42)
            assert(Arrow[Int](_ + 1)(41, Arrow.id[Int]).eval == 42)
        }
    }

    "frame" - {
        "a step reports the frame it was built with" in {
            val f = Arrow[Int](i => i + 1)
            assert(f.frame.position.show.contains("ArrowTest.scala"))
        }

        "a chain reports the internal frame" in {
            assert(inc.chain(double).frame eq Frame.internal)
        }
    }

    "an arrow is not a function" in {
        assert(!typeChecks("val f: Int => Int < Any = Arrow[Int](i => i + 1)"))
    }

    "an arrow applies to a plain value" in {
        val f = Arrow[Int](i => i + 1)
        assert(List(1, 2, 3).map(i => f(i).eval) == List(2, 3, 4))
    }

    "toString" - {
        "renders identity" in {
            assert(Arrow.id[Int].toString == "Id")
        }

        "renders a step with its site" in {
            val f = Arrow[Int](i => i + 1)
            assert(f.toString.startsWith("Step("))
            assert(f.toString.contains("ArrowTest.scala"))
        }

        "renders a chain as its two links" in {
            val chain = inc.chain(double)
            assert(chain.toString.startsWith("Chain("))
        }

        "renders a nested chain at every level" in {
            val nested = inc.chain(double).chain(inc)
            assert(nested.toString.startsWith("Chain("))
            assert(nested.toString.indexOf("Chain(", 1) > 0)
        }

        "renders a deep chain in bounded stack" in {
            @tailrec def build(acc: Arrow[Int, Int, Any], n: Int): Arrow[Int, Int, Any] =
                if n == 0 then acc else build(acc.chain(inc), n - 1)
            val rendered = build(Arrow.id[Int], 100000).toString
            assert(rendered.startsWith("Chain("))
            assert(rendered.endsWith(")"))
        }
    }

end ArrowTest
