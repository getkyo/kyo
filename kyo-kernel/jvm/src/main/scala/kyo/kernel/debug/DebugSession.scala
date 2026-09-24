package kyo.kernel.debug

import kyo.kernel.*
import kyo.kernel.internal.Debugger
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Handler
import kyo.kernel.internal.Nested
import kyo.kernel.internal.Pending

/** A `Debugger` that counts what happened instead of printing it, for a run too large to read step by step.
  *
  * `guardsLikeProduction` decides whether `enter` answers as the production build would, so a count can be taken with or without the guards
  * a real run has.
  */
final class Counting(guardsLikeProduction: Boolean) extends Debugger:
    private var counts                = Map.empty[String, Int]
    private def bump(k: String): Unit = counts = counts.updated(k, counts.getOrElse(k, 0) + 1)

    override def enter(): Boolean = !guardsLikeProduction

    override def onAlloc(value: Any): Unit              = bump("alloc " + value.getClass.getSimpleName)
    override def onUnfused(arrow: Arrow[?, ?, ?]): Unit = bump("unfused " + arrow.getClass.getSimpleName)
    override def onLoop(value: Any < Nothing, contA: Arrow[?, ?, ?], contB: Arrow[?, ?, ?]): Unit            = bump("loop")
    override def onRegionEnter(handler: Handler[?, ?, ?], state: Any): Unit                                  = bump("regionEnter")
    override def onRegionExit(handler: Handler[?, ?, ?], result: Any): Unit                                  = bump("regionExit")
    override def onForeign(suspend: Pending.Suspend[?, ?, ?, ?], handler: Handler[?, ?, ?]): Unit            = bump("foreign")
    override def onHandle(suspend: Pending.Suspend[?, ?, ?, ?], handler: Handler[?, ?, ?], state: Any): Unit = bump("handle")
    override def onResult(value: Any): Unit                                                                  = bump("result")

    def report: String = counts.toList.sortBy(-_._2).map((k, n) => f"$n%7d  $k").mkString("\n")
end Counting

object DebugSession:
    /** Evaluates `build()` with a debugger installed, printing a step-by-step trace or a summary, and uninstalls it afterwards.
      *
      * The computation is built inside rather than passed as a value so that the nodes it allocates are seen by the debugger too.
      */
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
