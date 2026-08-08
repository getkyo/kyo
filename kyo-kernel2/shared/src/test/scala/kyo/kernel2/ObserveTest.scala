package kyo.kernel2

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.test.Test
import language.implicitConversions

sealed trait ObserveAsk extends ArrowEffect[Const[Unit], Const[Int]]
sealed trait ObserveLog extends ArrowEffect[Const[String], Const[Unit]]

class ObserveTest extends Test[Any]:

    def ask: Int < ObserveAsk =
        ArrowEffect.suspend[Any](Tag[ObserveAsk], ())

    def park(v: Int < ObserveAsk): Arrow[Int, Int, ObserveAsk] =
        var parked: Arrow[Int, Int, ObserveAsk] = null
        val _ = ArrowEffect.handlePartial(Tag[ObserveAsk], v, Context.empty)(
            [C] =>
                (input, cont) =>
                    parked = cont
                    Maybe.Absent
        )
        parked
    end park

    "reports steps and survives park and resume" in {
        var seen     = List.empty[Int]
        val k        = ask.map(_ + 1).map(_ * 2)
        val observed = Observe((f, v) => seen :+= v.asInstanceOf[Int])(k)
        val cont     = park(observed.asInstanceOf[Int < ObserveAsk])
        assert(seen == Nil)
        val done = cont(10).asInstanceOf[Int < Any].eval
        assert(done == 22)
        assert(seen == List(10, 11))
    }

    "reports steps across a long continuation" in {
        var seen                = List.empty[Int]
        var k: Int < ObserveAsk = ask
        var i                   = 0
        while i < 40 do
            k = k.map(_ + 1)
            i += 1
        val observed = Observe((f, v) => seen :+= v.asInstanceOf[Int])(k)
        val cont     = park(observed.asInstanceOf[Int < ObserveAsk])
        assert(seen == Nil)
        val done = cont(0).asInstanceOf[Int < Any].eval
        assert(done == 40)
        assert(seen == (0 until 40).toList)
    }

    "effectful observer sequences before each step" in {
        def emit(s: String): Unit < ObserveLog =
            ArrowEffect.suspend[Any](Tag[ObserveLog], s)

        var log = List.empty[String]
        val k   = ask.map(_ + 1).map(_ * 2)
        val observed: Int < (ObserveAsk & ObserveLog) =
            Observe((f, v) => emit(s"before:$v"))(k)
        val answered = ArrowEffect.handle(Tag[ObserveAsk], observed)(
            [C] => (input, cont) => cont(10)
        )
        val result = ArrowEffect.handleResume(Tag[ObserveLog], answered)(
            [C] =>
                input =>
                    log :+= input
                    ()
        )
        assert(result.eval == 22)
        assert(log == List("before:10", "before:11"))
    }

    "leaves a completed computation untouched" in {
        var seen = List.empty[Any]
        val v    = Observe((f, x) => seen :+= x)(42: Int < Any)
        assert(v.eval == 42)
        assert(seen == Nil)
    }
end ObserveTest
