package proto2test

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.proto2.*
import kyo.test.Test
import language.implicitConversions

sealed trait Ask  extends Effect[Const[Unit], Const[Int]]
sealed trait Ask2 extends Effect[Const[Unit], Const[Int]]

class PendingTest extends Test[Any]:

    def rawAsk: Kyo.Suspend[Const[Unit], Const[Int], Ask, Any] =
        new Kyo.Suspend[Const[Unit], Const[Int], Ask, Any]:
            def frame = Frame.derive
            def input = ()
            def tag   = Tag[Ask]

    def ask: Int < Ask =
        rawAsk.map(Arrow[Int])

    def resolve(v: Int < Ask, answers: Int*): Int =
        var remaining = answers.toList
        val result = `<`.evalPartial(Tag[Ask], v)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    remaining match
                        case a :: tail =>
                            remaining = tail
                            Maybe(cont(a))
                        case Nil =>
                            Maybe.Absent
        )
        result.asInstanceOf[Int < Any].eval
    end resolve

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
        val raw: Int < Ask = rawAsk.map(Arrow[Int])
        assert(resolve(raw.map(_ + 1), 41) == 42)
    }

    "handler parks and the continuation resumes" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 1000 do
            k = k.map(_ + 1)
            i += 1
        var parked: Any = null
        val r = `<`.evalPartial(Tag[Ask], k)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        val resumed = parked.asInstanceOf[Arrow[Int, Int, Ask]](7)
        assert(resumed.asInstanceOf[Int < Any].eval == 1007)
    }

    "a parked continuation is multi shot" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 10 do
            k = k.map(_ + 1)
            i += 1
        var parked: Any = null
        val r = `<`.evalPartial(Tag[Ask], k)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        val cont = parked.asInstanceOf[Arrow[Int, Int, Ask]]
        assert(cont(0).asInstanceOf[Int < Any].eval == 10)
        assert(cont(100).asInstanceOf[Int < Any].eval == 110)
    }

    "appending after a park does not disturb the parked continuation" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 5 do
            k = k.map(_ + 1)
            i += 1
        var parked: Any = null
        val r = `<`.evalPartial(Tag[Ask], k)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        val cont     = parked.asInstanceOf[Arrow[Int, Int, Ask]]
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

    "bracket releases on completion" in {
        var log = List.empty[String]
        val b = new Kyo.Bracket[Int, Int, Any]:
            def frame = Frame.derive
            def acquire =
                log :+= "acq"; 42
            def release(r: Int) =
                log :+= "rel"; ()
            def cont = transform(_ + 1)
        assert(b.map(Arrow[Int]).eval == 43)
        assert(log == List("acq", "rel"))
    }

    "bracket releases exactly once across a park" in {
        var log = List.empty[String]
        val b = new Kyo.Bracket[Int, Int, Ask]:
            def frame = Frame.derive
            def acquire =
                log :+= "acq"; 42
            def release(r: Int) =
                log :+= "rel"; ()
            def cont = contAsk(v => ask.map(a => a + v))
        var parked: Any = null
        val r = `<`.evalPartial(Tag[Ask], b.map(Arrow[Int]))(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        assert(log == List("acq"))
        assert(parked.asInstanceOf[Arrow[Int, Int, Ask]](100).asInstanceOf[Int < Any].eval == 142)
        assert(log == List("acq", "rel"))
    }

    "bracket releases on exception" in {
        var log = List.empty[String]
        val b = new Kyo.Bracket[Int, Int, Any]:
            def frame = Frame.derive
            def acquire =
                log :+= "acq"; 42
            def release(r: Int) =
                log :+= "rel"; ()
            def cont = Arrow.of(
                new Arrow.Transform[Int, Int, Any]:
                    def frame = Frame.derive
                    def run[C, S2](v: Any, cont: Arrow[Int, C, S2]): C < (Any & S2) =
                        throw new RuntimeException("boom")
            )
        val thrown =
            try
                val _ = b.map(Arrow[Int]).eval
                false
            catch case e: RuntimeException => e.getMessage == "boom"
        assert(thrown)
        assert(log == List("acq", "rel"))
    }

    "nested brackets release in reverse order" in {
        var log = List.empty[String]
        def mk(name: String, body: Arrow[Int, Int, Any]): Int < Any =
            val b = new Kyo.Bracket[Int, Int, Any]:
                def frame = Frame.derive
                def acquire =
                    log :+= s"acq-$name"; 1
                def release(r: Int) =
                    log :+= s"rel-$name"; ()
                def cont = body
            b.map(Arrow[Int])
        end mk
        val inner = transform(_ + 1)
        val outer = Arrow.of(
            new Arrow.Transform[Int, Int, Any]:
                def frame = Frame.derive
                def run[C, S2](v: Any, cont: Arrow[Int, C, S2]): C < (Any & S2) =
                    cont(mk("inner", inner))
        )
        assert(mk("outer", outer).eval == 2)
        assert(log == List("acq-outer", "acq-inner", "rel-inner", "rel-outer"))
    }

    def contAsk(f: Int => Int < Ask): Arrow[Int, Int, Ask] =
        Arrow.of(
            new Arrow.Transform[Int, Int, Ask]:
                def frame = Frame.derive
                def run[C, S2](v: Any, cont: Arrow[Int, C, S2]): C < (Ask & S2) =
                    cont(f(v.asInstanceOf[Int]))
        )

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
            val s = new Kyo.Suspend[Const[Unit], Const[Int], Ask2, Any]:
                def frame = Frame.derive
                def input = ()
                def tag   = Tag[Ask2]
            s.map(Arrow[Int])
        end ask2
        val program: Int < Ask =
            ask.map(a => ask2.asInstanceOf[Int < Ask].map(b => a * 10 + b))
        val inner = `<`.evalPartial(Tag[Ask], program)(
            [X] => (input: Unit, cont: Arrow[Int, Int, Ask]) => Maybe(cont(1))
        )
        val outer = `<`.eval(Tag[Ask2], inner.asInstanceOf[Int < Ask2])(
            [X] => (input: Unit, cont: Arrow[Int, Int, Ask2]) => cont(2)
        )
        assert(outer == 12)
    }

    "observe reports steps and survives park and resume" in {
        var seen        = List.empty[Int]
        val k           = ask.map(_ + 1).map(_ * 2)
        val observed    = `<`.observe((f, v) => seen :+= v.asInstanceOf[Int])(k)
        var parked: Any = null
        val r = `<`.evalPartial(Tag[Ask], observed)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        assert(seen == Nil)
        val done = parked.asInstanceOf[Arrow[Int, Int, Ask]](10).asInstanceOf[Int < Any].eval
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
        val observed    = `<`.observe((f, v) => seen :+= v.asInstanceOf[Int])(k)
        var parked: Any = null
        val r = `<`.evalPartial(Tag[Ask], observed)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        assert(seen == Nil)
        val done = parked.asInstanceOf[Arrow[Int, Int, Ask]](0).asInstanceOf[Int < Any].eval
        assert(done == 40)
        assert(seen == (0 until 40).toList)
    }

    "exceptions carry effect frames in the stack trace" in {
        val program: Int < Ask = ask.map { _ =>
            (1: Int < Any).map(_ => (throw new RuntimeException("boom")): Int)
        }
        val ex =
            try
                val _ = `<`.evalPartial(Tag[Ask], program)(
                    [X] => (input: Unit, cont: Arrow[Int, Int, Ask]) => Maybe(cont(1))
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

end PendingTest
