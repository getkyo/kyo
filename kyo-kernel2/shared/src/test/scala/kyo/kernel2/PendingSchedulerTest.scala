package kyo.kernel2

import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.test.Test
import language.implicitConversions

sealed trait SchedulerAsk extends ArrowEffect[Const[Unit], Const[Int]]

class PendingSchedulerTest extends Test[Any]:

    def ask: Int < SchedulerAsk =
        ArrowEffect.suspend[Any](Tag[SchedulerAsk], ())

    "interrupting a parked fiber runs finalizers without resuming" in {
        var log = List.empty[String]
        def mk(name: String)(body: Int => Int < SchedulerAsk): Int < SchedulerAsk =
            Effect.bracket {
                log :+= s"acq-$name"
                1
            } { _ =>
                log :+= s"rel-$name"
                ()
            }(body)
        val program = mk("outer") { v =>
            mk("inner") { w =>
                ask.map { a =>
                    log :+= "resumed"
                    a + v + w
                }
            }
        }
        val remainder = ArrowEffect.handlePartial(Tag[SchedulerAsk], program, Context.empty)(
            [C] => (input, cont) => Maybe.Absent
        )
        assert(log == List("acq-outer", "acq-inner"))
        remainder.finalizeBracket
        assert(log == List("acq-outer", "acq-inner", "rel-inner", "rel-outer"))
    }

    "a resumed parked fiber releases in-band" in {
        var log = List.empty[String]
        val program = Effect.bracket {
            log :+= "acq"
            42
        } { _ =>
            log :+= "rel"
            ()
        } { v =>
            ask.map(a => a + v)
        }
        var parked: Any = null
        val remainder = ArrowEffect.handlePartial(Tag[SchedulerAsk], program, Context.empty)(
            [C] =>
                (input, cont) =>
                    parked = cont
                    Maybe.Absent
        )
        assert(log == List("acq"))
        val resumed = parked.asInstanceOf[Arrow[Int, Int, Ask]](100)
        assert(resumed.asInstanceOf[Int < Any].eval == 142)
        assert(log == List("acq", "rel"))
    }

    "interrupting a resumed spanned continuation runs finalizers" in {
        var log = List.empty[String]
        val program = Effect.bracket {
            log :+= "acq"
            1
        } { _ =>
            log :+= "rel"
            ()
        } { v =>
            ask.map(a => ask.map(b => a + b + v))
        }
        var k: Int < SchedulerAsk = program
        var i                     = 0
        while i < 40 do
            k = k.map(_ + 1)
            i += 1
        var captured: Any = null
        val r = ArrowEffect.handlePartial(Tag[SchedulerAsk], k, Context.empty)(
            [C] =>
                (input, cont) =>
                    captured = cont
                    Maybe.Absent
        )
        assert(log == List("acq"))
        val resumed = captured.asInstanceOf[Arrow[Int, Int, Ask]](10)
        assert(log == List("acq"))
        resumed.finalizeBracket
        assert(log == List("acq", "rel"))
    }

end PendingSchedulerTest
