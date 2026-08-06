package kyo.proto

import kyo.Const
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.test.Test
import language.implicitConversions

sealed trait Ask extends Effect[Const[Unit], Const[Int]]

class PendingSchedulerTest extends Test[Any]:

    def ask: Int < Ask =
        val s = new Kyo.Suspend[Const[Unit], Const[Int], Ask, Any]:
            def frame = Frame.internal
            def input = ()
            def tag   = Tag[Ask]
        s.map(Arrow[Int])
    end ask

    def transform(f: Int => Int < Ask): Arrow[Int, Int, Ask] =
        new Arrow.Transform[Int, Int, Ask]:
            def frame                                                       = Frame.internal
            def run[C, S2](v: Int, cont: Arrow[Int, C, S2]): C < (Ask & S2) = cont(f(v))

    "interrupting a queued remainder runs finalizers" in {
        var log = List.empty[String]
        val bracket = new Kyo.Bracket[Int, Int, Any]:
            def frame = Frame.internal
            def acquire =
                log :+= "acq"; 1
            def release(r: Int) =
                log :+= "rel"; ()
            def cont = new Arrow.Transform[Int, Int, Any]:
                def frame = Frame.internal
                def run[C, S2](v: Int, cont: Arrow[Int, C, S2]): C < (Any & S2) =
                    var k: Int < Any = v
                    var i            = 0
                    while i < 5000 do
                        k = k.map(_ + 1)
                        i += 1
                    cont(k)
                end run
        val remainder = bracket.map(Arrow[Int]).eval(() => true)
        assert(log == List("acq"))
        remainder.discard
        assert(log == List("acq", "rel"))
    }

    "interrupting a parked fiber runs finalizers without resuming" in {
        var log = List.empty[String]
        def mk(name: String, body: Arrow[Int, Int, Ask]): Int < Ask =
            val b = new Kyo.Bracket[Int, Int, Ask]:
                def frame = Frame.internal
                def acquire =
                    log :+= s"acq-$name"; 1
                def release(r: Int) =
                    log :+= s"rel-$name"; ()
                def cont = body
            b.map(Arrow[Int])
        end mk
        val inner = transform(v =>
            ask.map(a =>
                log :+= "resumed"; a + v
            )
        )
        val outer = transform(v => mk("inner", inner))
        val remainder = `<`.eval(Tag[Ask], mk("outer", outer), () => false, 512)(
            [X] => (input: Unit, cont: Arrow[Int, Int, Ask]) => Maybe.Absent
        )
        assert(log == List("acq-outer", "acq-inner"))
        remainder.discard
        assert(log == List("acq-outer", "acq-inner", "rel-inner", "rel-outer"))
    }

    "interrupting a resumed spanned continuation runs finalizers" in {
        var log = List.empty[String]
        val bracket = new Kyo.Bracket[Int, Int, Ask]:
            def frame = Frame.internal
            def acquire =
                log :+= "acq"; 1
            def release(r: Int) =
                log :+= "rel"; ()
            def cont = transform(v => ask.map(a => ask.map(b => a + b + v)))
        var k: Int < Ask = bracket.map(Arrow[Int])
        var i            = 0
        while i < 40 do
            k = k.map(_ + 1)
            i += 1
        var parked: Any = null
        val r = `<`.eval(Tag[Ask], k, () => false, 512)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        assert(log == List("acq"))
        val resumed = parked.asInstanceOf[Arrow[Int, Int, Ask]](10)
        assert(log == List("acq"))
        resumed.discard
        assert(log == List("acq", "rel"))
    }

    "a resumed parked fiber releases in-band" in {
        var log = List.empty[String]
        val bracket = new Kyo.Bracket[Int, Int, Ask]:
            def frame = Frame.internal
            def acquire =
                log :+= "acq"; 42
            def release(r: Int) =
                log :+= "rel"; ()
            def cont = transform(v => ask.map(a => a + v))
        var parked: Any = null
        val remainder = `<`.eval(Tag[Ask], bracket.map(Arrow[Int]), () => false, 512)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        assert(log == List("acq"))
        assert(parked.asInstanceOf[Arrow[Int, Int, Ask]](100).asInstanceOf[Int < Any].eval == 142)
        assert(log == List("acq", "rel"))
    }

end PendingSchedulerTest
