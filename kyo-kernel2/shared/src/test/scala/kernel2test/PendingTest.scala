package kernel2test

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.*
import kyo.test.Test
import language.implicitConversions

sealed trait Ask  extends ArrowEffect[Const[Unit], Const[Int]]
sealed trait Ask2 extends ArrowEffect[Const[Unit], Const[Int]]

class PendingTest extends Test[Any]:

    def ask: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def resolve(v: Int < Ask, answers: Int*): Int =
        var remaining = answers.toList
        ArrowEffect.handle(Tag[Ask], v)(
            [C] =>
                (input, cont) =>
                    remaining match
                        case a :: tail =>
                            remaining = tail
                            cont(a)
                        case Nil =>
                            throw new IllegalStateException("out of answers")
        ).eval
    end resolve

    def park(v: Int < Ask): Arrow[Int, Int, Ask] =
        var parked: Arrow[Int, Int, Ask] = null
        val _ = ArrowEffect.handlePartial(Tag[Ask], v)(
            [C] =>
                (input, cont) =>
                    parked = cont
                    Maybe.Absent
        )
        parked
    end park

    "eager recursion through map is stack safe" in {
        def loop(i: Int): Int < Any =
            if i == 0 then 0
            else (i: Int < Any).map(_ => loop(i - 1))
        assert(loop(1000000).eval == 0)
    }

    "eager chain evaluates during construction" in {
        assert(((1: Int < Any).map(_ + 1).map(_ * 2)).eval == 4)
    }

    "empty arrow is identity" in {
        assert(Arrow[Int](5).eval == 5)
    }

    "handler resolves a suspension synchronously" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 1000 do
            k = k.map(_ + 1)
            i += 1
        assert(resolve(k, 5) == 1005)
    }

    "handler resolves a bare suspension" in {
        assert(resolve(ask, 41) == 41)
        assert(resolve(ask.map(_ + 1), 41) == 42)
    }

    "handling evaluates eagerly" in {
        var ran = false
        val handled = ArrowEffect.handle(Tag[Ask], ask.map(_ + 1))(
            [C] =>
                (input, cont) =>
                    ran = true
                    cont(1)
        )
        assert(ran)
        assert(handled.eval == 2)
    }

    "handler parks and the continuation resumes" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 1000 do
            k = k.map(_ + 1)
            i += 1
        val resumed = park(k)(7)
        assert(resumed.asInstanceOf[Int < Any].eval == 1007)
    }

    "a parked continuation is multi shot" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 10 do
            k = k.map(_ + 1)
            i += 1
        val cont = park(k)
        assert(cont(0).asInstanceOf[Int < Any].eval == 10)
        assert(cont(100).asInstanceOf[Int < Any].eval == 110)
    }

    "appending after a park does not disturb the parked continuation" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 5 do
            k = k.map(_ + 1)
            i += 1
        val cont     = park(k)
        val extended = cont.map(transform(_ * 10))
        assert(cont(0).asInstanceOf[Int < Any].eval == 5)
        assert(extended(0).asInstanceOf[Int < Any].eval == 50)
        assert(cont(1).asInstanceOf[Int < Any].eval == 6)
    }

    "mid chain suspension captures the remainder" in {
        val program = ask.map(_ + 1).map(a => ask.map(b => a + b)).map(_ * 2)
        assert(resolve(program, 10, 10) == 42)
    }

    "nested suspensions resolve in order" in {
        val program = ask.map(a => ask.map(b => ask.map(c => a * 100 + b * 10 + c)))
        assert(resolve(program, 1, 2, 3) == 123)
    }

    "handling drives deep programs at the handle site" in {
        def program(i: Int): Int < Ask =
            if i == 0 then 0
            else ask.map(_ => program(i - 1))
        val handled = ArrowEffect.handle(Tag[Ask], program(100000))(
            [C] => (input, cont) => cont(0)
        )
        assert(handled.eval == 0)
    }

    "preemption yields and the remainder resumes" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 10000 do
            k = k.map(_ + 1)
            i += 1
        val resumed = park(k)(0).asInstanceOf[Int < Any]
        var polls   = 0
        val suspended = resumed.eval(
            () =>
                polls += 1; polls == 2
            ,
            512
        )
        assert(polls == 2)
        assert(suspended.eval == 10000)
    }

    "period controls poll cadence" in {
        def parkAndResume(): Int < Any =
            var k: Int < Ask = ask
            var i            = 0
            while i < 10000 do
                k = k.map(_ + 1)
                i += 1
            park(k)(0).asInstanceOf[Int < Any]
        end parkAndResume
        var p512 = 0
        assert(parkAndResume().eval(
            () =>
                p512 += 1;
                false
            ,
            512
        ).eval == 10000)
        var p4096 = 0
        assert(parkAndResume().eval(
            () =>
                p4096 += 1;
                false
            ,
            4096
        ).eval == 10000)
        assert(p512 > p4096)
    }

    "deep resumed continuation is stack safe" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 100000 do
            k = k.map(_ + 1)
            i += 1
        assert(resolve(k, 0) == 100000)
    }

    "long append chains resolve" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 5000 do
            k = k.map(_ + 1)
            i += 1
        assert(resolve(k, 0) == 5000)
    }

    def transform(f: Int => Int): Arrow[Int, Int, Any] =
        Arrow.of(
            new Arrow.Transform[Int, Int, Any]:
                def frame = Frame.derive
                def run[C, S2](v: Any, cont: Arrow[Int, C, S2]): C < (Any & S2) =
                    cont(f(v.asInstanceOf[Int]))
        )

    "handlers route by tag and nest" in {
        val ask2: Int < Ask2 =
            ArrowEffect.suspend[Any](Tag[Ask2], ())
        val program: Int < (Ask & Ask2) =
            ask.map(a => ask2.map(b => a * 10 + b))
        val inner = ArrowEffect.handle(Tag[Ask], program)(
            [C] => (input, cont) => cont(1)
        )
        val outer = ArrowEffect.handle(Tag[Ask2], inner)(
            [C] => (input, cont) => cont(2)
        )
        assert(outer.eval == 12)
    }

    "observe reports steps and survives park and resume" in {
        var seen     = List.empty[Int]
        val k        = ask.map(_ + 1).map(_ * 2)
        val observed = `<`.observe((f, v) => seen :+= v.asInstanceOf[Int])(k)
        val cont     = park(observed)
        assert(seen == Nil)
        val done = cont(10).asInstanceOf[Int < Any].eval
        assert(done == 22)
        assert(seen == List(10, 11))
    }

    "observe reports steps across a long continuation" in {
        var seen         = List.empty[Int]
        var k: Int < Ask = ask
        var i            = 0
        while i < 40 do
            k = k.map(_ + 1)
            i += 1
        val observed = `<`.observe((f, v) => seen :+= v.asInstanceOf[Int])(k)
        val cont     = park(observed)
        assert(seen == Nil)
        val done = cont(0).asInstanceOf[Int < Any].eval
        assert(done == 40)
        assert(seen == (0 until 40).toList)
    }

    "exceptions carry effect frames in the stack trace" in {
        val program: Int < Ask = ask.map { _ =>
            (1: Int < Any).map(_ => (throw new RuntimeException("boom")): Int)
        }
        val ex =
            try
                val _ = ArrowEffect.handle(Tag[Ask], program)(
                    [C] => (input, cont) => cont(1)
                )
                null
            catch case e: RuntimeException => e
        assert(ex.getMessage == "boom")
        val top = ex.getStackTrace.take(1)
        assert(top.forall(_.getClassName == "map @ PendingTest"))
        assert(top.forall(_.getFileName == "PendingTest.scala"))
    }

    "lift wraps nested computations" in {
        val inner: Int < Ask          = ask
        val nested: (Int < Ask) < Any = inner
        val out                       = nested.eval
        assert(resolve(out.map(_ + 1), 41) == 42)
    }

    "a computation as a value survives the chain" in {
        val inner: Int < Any = (1: Int < Any).map(_ + 1)
        val program: (Int < Any) < Ask =
            ask.map(n => `<`.liftSlow(inner.map(_ + n)))
        val handled = ArrowEffect.handle(Tag[Ask], program)(
            [C] => (input, cont) => cont(10)
        )
        val out = handled.eval
        assert(out.eval == 12)
    }

    "identity arrow has no step" in {
        assert(Arrow[Int].step == Maybe.Absent)
    }

    "step decomposes a continuation for caller-site execution" in {
        val cont = park(ask.map(_ + 1).map(_ * 10))
        cont.step match
            case Maybe.Present(s) =>
                assert(s.asInstanceOf[AnyRef] eq cont.asInstanceOf[AnyRef])
                assert(s.head.run(3, s.next).asInstanceOf[Int < Any].eval == 40)
            case Maybe.Absent =>
                fail("expected a step")
        end match
    }

    "step wraps a lone transform" in {
        val cont = park(ask.map(_ + 5))
        cont.step match
            case Maybe.Present(s) =>
                assert(s.head.run(2, s.next).asInstanceOf[Int < Any].eval == 7)
            case Maybe.Absent =>
                fail("expected a step")
        end match
    }

    "step drive round-trips a mid-chain suspension" in {
        val cont = park(ask.map(x => ask.map(_ + x)).map(_ * 2))
        val resumed =
            cont.step match
                case Maybe.Present(s) => s.head.run(10, s.next)
                case Maybe.Absent     => fail("expected a step")
        assert(resolve(resumed.asInstanceOf[Int < Ask], 5) == 30)
    }

    "handler hosts phase 2 end to end" in {
        var remaining = List(7, 3)
        val program   = ask.map(a => ask.map(_ + a)).map(_ * 2)
        val result = ArrowEffect.handle(Tag[Ask], program)(
            [C] =>
                (input, cont) =>
                    val a = remaining.head
                    remaining = remaining.tail
                    cont.step match
                        case Maybe.Present(s) => s.head.run(a, s.next).asInstanceOf[Int < Ask]
                        case Maybe.Absent     => a
        )
        assert(result.eval == 20)
    }

    "flatMap supports for-comprehensions" in {
        val v =
            for
                a <- ask
                b <- ask
            yield a + b
        assert(resolve(v, 10, 20) == 30)
    }

    "andThen sequences and keeps the second result" in {
        var order = List.empty[Int]
        val v = ask.map { a =>
            order = order :+ a
            a
        }.andThen(ask.map { b =>
            order = order :+ b
            b
        })
        assert(resolve(v, 1, 2) == 2)
        assert(order == List(1, 2))
    }

    "unit discards the result and keeps the effects" in {
        var seen = -1
        val v = ask.map { a =>
            seen = a
            a
        }.unit
        val result = ArrowEffect.handle(Tag[Ask], v)(
            [C] => (input, cont) => cont(7)
        ).eval
        assert(result == ())
        assert(seen == 7)
    }

    "handle applies a transformation" in {
        assert(ask.handle(resolve(_, 5)) == 5)
    }

    "handle chains transformations in sequence" in {
        def inc(b: => Int): Int    = b + 1
        def double(c: => Int): Int = c * 2
        assert(ask.handle(resolve(_, 3), inc, double) == 8)
    }

    "handle chains five transformations" in {
        def inc(b: => Int): Int         = b + 1
        def double(c: => Int): Int      = c * 2
        def dec(d: => Int): Int         = d - 1
        def toString(e: => Int): String = e.toString
        assert(ask.handle(resolve(_, 3), inc, double, dec, toString) == "7")
    }

    "flatten collapses a nested computation" in {
        val inner: Int < Ask          = ask
        val nested: (Int < Ask) < Any = inner
        assert(resolve(nested.flatten, 9) == 9)
    }

end PendingTest
