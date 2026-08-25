package kyo.kernel.internal

import kyo.Const
import kyo.Tag
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.tailrec

/** A map tower resumed through a captured continuation at every level: each capture holds the trailing maps of all the levels before it, so
  * whatever the capture and the resume cost per entry is paid once per level. Kept apart from the corpus so it can be run and debugged
  * alone.
  */
class EvalCaptureTowerTest extends AnyFreeSpec:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

    private val Reach = Safepoint.period() / 2

    "a long map tower across captures evaluates in bounded stack" in {
        def loop(i: Int): Int < Ask =
            if i > 20000 then i else ask.map(a => loop(i + a)).map(x => x)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(0))([C] => (_, cont) => cont(1), a => a)
        assert(Eval(r) == 20001)
    }

    "a capture across an inner region keeps the region as an entry" in {
        @tailrec def tower(v: Int < (Ask & Say), n: Int): Int < (Ask & Say) =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        var seen = List.empty[String]
        val inner: Int < Ask = ArrowEffect.handleCont(Tag[Say], tower(ask.map(a => say("x").map(_ => a)), Reach + 8))(
            [C] =>
                (s, cont) =>
                    seen = s :: seen
                    cont(())
            ,
            a => a
        )
        val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], inner)([C] => (_, cont) => cont(1), a => a)
        assert(Eval(r) == 1 + Reach + 8)
        assert(seen == List("x"))
    }

    "a deep capture is multi-shot" in {
        @tailrec def tower(v: Int < Ask, n: Int): Int < Ask =
            if n == 0 then v else tower(v.map(_ + 1), n - 1)
        val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], tower(ask, Reach + 8))(
            [C] => (_, cont) => cont(1).map(r1 => cont(2).map(r2 => r1 * 100000 + r2)),
            a => a
        )
        assert(Eval(r) == (1 + Reach + 8) * 100000 + (2 + Reach + 8))
    }
end EvalCaptureTowerTest
