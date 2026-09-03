package kyo.kernel.debug

import kyo.kernel.*
import kyo.kernel.internal.Debugger
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Nested

final class Counting(guardsLikeProduction: Boolean) extends Debugger:
    private var counts                = Map.empty[String, Int]
    private def bump(k: String): Unit = counts = counts.updated(k, counts.getOrElse(k, 0) + 1)

    override def enter(): Boolean = !guardsLikeProduction

    override def onAlloc(value: Any): Unit                              = bump("alloc " + value.getClass.getSimpleName)
    override def onUnfused(arrow: Any): Unit                            = bump("unfused " + arrow.getClass.getSimpleName)
    override def onLoop(value: Any, contA: Any, contB: Any): Unit       = bump("loop")
    override def onRegionEnter(handler: Any, state: Any): Unit          = bump("regionEnter")
    override def onRegionExit(handler: Any, result: Any): Unit          = bump("regionExit")
    override def onForeign(suspend: Any, handler: Any): Unit            = bump("foreign")
    override def onHandle(suspend: Any, handler: Any, state: Any): Unit = bump("handle")
    override def onResult(value: Any): Unit                             = bump("result")

    def report: String = counts.toList.sortBy(-_._2).map((k, n) => f"$n%7d  $k").mkString("\n")
end Counting

object DebugSession:
    def run(quiet: Boolean, guardsLikeProduction: Boolean)(build: () => Int < Any): Int =
        val counting = Counting(guardsLikeProduction)
        val debugger = if quiet then counting else ConsoleDebugger()
        Debugger.install(debugger)
        try
            val out = Nested.unnest[Int](Eval(build()))
            debugger match
                case c: ConsoleDebugger => println(s"STATS ${c.stats}")
                case _                  => println("COUNTS\n" + counting.report)
            out
        finally Debugger.uninstall()
        end try
    end run
end DebugSession
