package kyo.proto4

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.test.Test
import language.implicitConversions

sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]

class PendingSchedulerTest extends Test[Any]:

    def ask: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    "interrupting a parked fiber runs finalizers without resuming" in {
        var log = List.empty[String]
        def mk(name: String)(body: Int => Int < Ask): Int < Ask =
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
        val remainder = `<`.evalPartial(Tag[Ask], program)(
            [X] => (input: Unit, cont: Arrow[Int, Int, Ask]) => Maybe.Absent
        )
        assert(log == List("acq-outer", "acq-inner"))
        remainder.discard
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
        val remainder = `<`.evalPartial(Tag[Ask], program)(
            [X] =>
                (input: Unit, cont: Arrow[Int, Int, Ask]) =>
                    parked = cont
                    Maybe.Absent
        )
        assert(log == List("acq"))
        assert(parked.asInstanceOf[Arrow[Int, Int, Ask]](100).asInstanceOf[Int < Any].eval == 142)
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
        var k: Int < Ask = program
        var i            = 0
        while i < 40 do
            k = k.map(_ + 1)
            i += 1
        var parked: Any = null
        val r = `<`.evalPartial(Tag[Ask], k)(
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

end PendingSchedulerTest
