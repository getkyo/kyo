package kyo.kernel.internal

import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.ArrowEffect
import kyo.kernel.Bracket
import kyo.kernel.Effect
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable.ListBuffer

class ReportTest extends AnyFreeSpec:

    private object Bad extends RuntimeException("bad", null, true, false)

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    "delivers to the thread's uncaught-exception handler" in {
        var reported = Maybe.empty[Throwable]
        val thread   = Thread.currentThread()
        val previous = thread.getUncaughtExceptionHandler()
        thread.setUncaughtExceptionHandler((_, ex) => reported = Maybe(ex))
        try Report.unhandled(Bad)
        finally thread.setUncaughtExceptionHandler(previous)
        assert(reported.exists(_ eq Bad))
    }

    "a held release that throws on the discard drain is reported" in {
        val log = ListBuffer[String]()
        val body: Int < Ask =
            Bracket(Effect.defer(1)) { _ =>
                Bracket(Effect.defer(2)) { _ =>
                    ask.map(x => x)
                }((_, _) => throw Bad)
            }((_, _) => discard(log += "outer"))
        val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
        var reported           = Maybe.empty[Throwable]
        val thread             = Thread.currentThread()
        val previous           = thread.getUncaughtExceptionHandler()
        thread.setUncaughtExceptionHandler((_, ex) => reported = Maybe(ex))
        try
            assert(dropped.eval == -1)
        finally thread.setUncaughtExceptionHandler(previous)
        // uniform: the outer release still runs; the inner throw, with no computation left to fail, is reported
        assert(log.toList == List("outer"))
        assert(reported.exists(_ eq Bad))
    }
end ReportTest
