package kyo.kernel2

import kyo.Maybe
import kyo.Tag
import kyo.test.Test
import language.implicitConversions

sealed trait DiscardAsk extends ArrowEffect[Const[Unit], Const[Int]]

class EffectInternalTest extends Test[Any]:

    def ask: Int < DiscardAsk =
        ArrowEffect.suspend[Any](Tag[DiscardAsk], ())

    def park(v: Int < DiscardAsk): Int < DiscardAsk =
        ArrowEffect.handlePartial(Tag[DiscardAsk], v)(
            [C] => (input, cont) => Maybe.Absent
        )

    "discarding a parked bracket releases the resource" in {
        var log = List.empty[String]
        val v = Effect.bracket {
            log :+= "acq"
            42
        } { _ =>
            log :+= "rel"
            ()
        } { r =>
            ask.map(a => a + r)
        }
        val parked = park(v)
        assert(log == List("acq"))
        parked.discard
        assert(log == List("acq", "rel"))
    }
end EffectInternalTest
