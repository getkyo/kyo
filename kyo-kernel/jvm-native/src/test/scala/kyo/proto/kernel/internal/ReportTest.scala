package kyo.proto.kernel.internal

import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.Effect
import org.scalatest.freespec.AnyFreeSpec

class ReportTest extends AnyFreeSpec:

    private def eval[A](v: A < Any): A = v.eval

    private object Bad extends RuntimeException("bad", null, true, false)

    sealed trait Ask extends ArrowEffect[kyo.Const[Unit], kyo.Const[Int]]
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
        val log = collection.mutable.ListBuffer[String]()
        val body: Int < Ask =
            Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
                Effect.bracket(Effect.defer(2))((_, _) => throw Bad) { _ =>
                    ask.map(x => x)
                }
            }
        val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
        var reported           = Maybe.empty[Throwable]
        val thread             = Thread.currentThread()
        val previous           = thread.getUncaughtExceptionHandler()
        thread.setUncaughtExceptionHandler((_, ex) => reported = Maybe(ex))
        try
            assert(eval(dropped) == -1)
        finally thread.setUncaughtExceptionHandler(previous)
        assert(log.toList == List("outer"))
        assert(reported.exists(_.getSuppressed.exists(_ eq Bad)))
    }
end ReportTest
