package pendingtest

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.proto.*
import kyo.test.Test
import language.implicitConversions

class PendingTest extends Test[Any]:

    def ask: Int < Ask =
        val s = new Kyo.Suspend[Const[Unit], Const[Int], Ask, Any]:
            def frame = Frame.derive
            def input = ()
            def tag   = Tag[Ask]
        s.map(Arrow[Int])
    end ask

    def resume(k: Int < Ask, v: Int): Int < Ask =
        k.asInstanceOf[Kyo.Continue[Const[Unit], Const[Int], Ask, Any, Int, Any]].cont(v)

    def transform(f: Int => Int < Ask): Arrow[Int, Int, Ask] =
        new Arrow.Transform[Int, Int, Ask]:
            def frame                                                       = Frame.derive
            def run[C, S2](v: Int, cont: Arrow[Int, C, S2]): C < (Ask & S2) = cont(f(v))

    "deep resumed continuation is stack safe" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 100000 do
            k = k.map(_ + 1)
            i += 1
        assert(resume(k, 0).asInstanceOf[Int < Any].eval == 100000)
    }

    "eager recursion through map is stack safe" in {
        def loop(i: Int): Int < Any =
            if i == 0 then 0
            else (i: Int < Any).map(_ => loop(i - 1))
        assert(loop(1000000).eval == 0)
    }

    "preemption yields and the remainder resumes" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 10000 do
            k = k.map(_ + 1)
            i += 1
        var polls = 0
        val suspended = resume(k, 0).asInstanceOf[Int < Any].eval(() =>
            polls += 1; polls == 2
        )
        assert(polls == 2)
        assert(suspended.eval == 10000)
    }

    "period controls poll cadence" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 10000 do
            k = k.map(_ + 1)
            i += 1
        var p512 = 0
        assert(resume(k, 0).asInstanceOf[Int < Any].eval(
            () =>
                p512 += 1;
                false
            ,
            512
        ).eval == 10000)
        var p4096 = 0
        assert(resume(k, 0).asInstanceOf[Int < Any].eval(
            () =>
                p4096 += 1;
                false
            ,
            4096
        ).eval == 10000)
        assert(p512 > p4096 * 4)
    }

    "handler eval resolves a suspension synchronously" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 1000 do
            k = k.map(_ + 1)
            i += 1
        val done = `<`.eval(Tag[Ask], k, () => false, 512)(
            [X] => (input: Unit, cont: Arrow[Int, Int, Ask]) => Maybe(cont(5))
        )
        assert(done.unsafeGet == 1005)
    }

    "handler eval parks and the continuation resumes" in {
        var k: Int < Ask = ask
        var i            = 0
        while i < 1000 do
            k = k.map(_ + 1)
            i += 1
        var parked: Any = null
        val r = `<`.eval(Tag[Ask], k, () => false, 512)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        val resumed = parked.asInstanceOf[Arrow[Int, Int, Ask]](7)
        assert(resumed.asInstanceOf[Int < Any].eval == 1007)
    }

    "bracket releases on completion" in {
        var log = List.empty[String]
        val b = new Kyo.Bracket[Int, Int, Any]:
            def frame = Frame.derive
            def acquire =
                log :+= "acq"; 42
            def release(r: Int) =
                log :+= "rel"; ()
            def cont = new Arrow.Transform[Int, Int, Any]:
                def frame                                                       = Frame.derive
                def run[C, S2](v: Int, cont: Arrow[Int, C, S2]): C < (Any & S2) = cont(v + 1)
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
            def cont = transform(v => ask.map(a => a + v))
        var parked: Any = null
        val r = `<`.eval(Tag[Ask], b.map(Arrow[Int]), () => false, 512)(
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
            def cont = new Arrow.Transform[Int, Int, Any]:
                def frame = Frame.derive
                def run[C, S2](v: Int, cont: Arrow[Int, C, S2]): C < (Any & S2) =
                    throw new RuntimeException("boom")
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
        val inner = new Arrow.Transform[Int, Int, Any]:
            def frame                                                       = Frame.derive
            def run[C, S2](v: Int, cont: Arrow[Int, C, S2]): C < (Any & S2) = cont(v + 1)
        val outer = new Arrow.Transform[Int, Int, Any]:
            def frame                                                       = Frame.derive
            def run[C, S2](v: Int, cont: Arrow[Int, C, S2]): C < (Any & S2) = cont(mk("inner", inner))
        assert(mk("outer", outer).eval == 2)
        assert(log == List("acq-outer", "acq-inner", "rel-inner", "rel-outer"))
    }

    "exceptions carry effect frames in the stack trace" in {
        val program: Int < Ask = ask.map { _ =>
            (1: Int < Any).map(_ => (throw new RuntimeException("boom")): Int)
        }
        val ex =
            try
                val _ = `<`.eval(Tag[Ask], program, () => false, 512)(
                    [X] => (input: Unit, cont: Arrow[Int, Int, Ask]) => Maybe(cont(1))
                )
                null
            catch case e: RuntimeException => e
        assert(ex.getMessage == "boom")
        val top = ex.getStackTrace.take(2)
        assert(top.forall(_.getClassName == "map @ PendingTest"))
        assert(top.forall(_.getFileName == "PendingTest.scala"))
        assert(top(0).getLineNumber + 1 == top(1).getLineNumber)
    }

    "observe reports steps and survives park and resume" in {
        var seen        = List.empty[Int]
        val k           = ask.map(_ + 1).map(_ * 2)
        val observed    = `<`.observe((f, v) => seen :+= v.asInstanceOf[Int])(k)
        var parked: Any = null
        val r = `<`.eval(Tag[Ask], observed, () => false, 512)(
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

    "observe reports steps across a spanned continuation" in {
        var seen         = List.empty[Int]
        var k: Int < Ask = ask
        var i            = 0
        while i < 40 do
            k = k.map(_ + 1)
            i += 1
        val observed    = `<`.observe((f, v) => seen :+= v.asInstanceOf[Int])(k)
        var parked: Any = null
        val r = `<`.eval(Tag[Ask], observed, () => false, 512)(
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

end PendingTest
