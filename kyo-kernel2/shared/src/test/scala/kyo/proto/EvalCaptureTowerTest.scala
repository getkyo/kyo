package kyo.proto

import kyo.Const
import kyo.Tag
import org.scalatest.freespec.AnyFreeSpec

/** A map tower resumed through a captured continuation at every level: each capture holds the trailing maps of all the levels before
  * it, so whatever the capture and the resume cost per entry is paid once per level. Kept apart from the corpus so it can be run and
  * debugged alone.
  */
class EvalCaptureTowerTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    "a long map tower across captures evaluates in bounded stack" in {
        def loop(i: Int): Int < Ask =
            if i > 20000 then i else ask.map(a => loop(i + a)).map(x => x)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(0))([C] => (_, cont) => cont(1), a => a)
        assert(Eval(r) == 20001)
    }
end EvalCaptureTowerTest
