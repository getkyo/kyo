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

    "a release failing on the discard drain reaches the handler on its signal" in {
        val log = ListBuffer[String]()
        val body: Int < Ask =
            Bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Bracket(Effect.defer(2))((_, _) => throw Bad) { _ =>
                    ask.map(x => x)
                }
            }
        val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
        var reported           = Maybe.empty[Throwable]
        val thread             = Thread.currentThread()
        val previous           = thread.getUncaughtExceptionHandler()
        thread.setUncaughtExceptionHandler((_, ex) => reported = Maybe(ex))
        try
            assert(dropped.eval == -1)
        finally thread.setUncaughtExceptionHandler(previous)
        assert(log.toList == List("outer"))
        assert(reported.exists(_.getSuppressed.exists(_ eq Bad)))
    }
end ReportTest
