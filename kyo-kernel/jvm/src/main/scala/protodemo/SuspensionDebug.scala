package protodemo

import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.*
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Nested

/** The three `ProtoBench` shapes, traceable.
  *
  * `suspensionBaseline` and `handleLoopAnswersInPlace` are the rows that run 2x to 2.6x the pre-stack baseline and about 4x `kyo.kernel`.
  * `suspensionFusesContinuation` runs the same ten thousand suspensions under the same handler and is at parity. The three differ only in
  * how the continuation reaches the region, so tracing them side by side is where the difference has to show.
  *
  * {{{
  * sbt 'kyo-kernelJVM/runMain protodemo.SuspensionDebug map 3'      // suspensionBaseline's shape
  * sbt 'kyo-kernelJVM/runMain protodemo.SuspensionDebug with 3'     // suspensionFusesContinuation's shape
  * sbt 'kyo-kernelJVM/runMain protodemo.SuspensionDebug loop 3'     // handleLoopAnswersInPlace's shape
  * sbt 'kyo-kernelJVM/runMain protodemo.SuspensionDebug map 600 quiet'   // counts only, no step log
  * }}}
  *
  * `quiet` swaps the console tracer for one that only counts, which is what you want past a handful of iterations: the step log is a few
  * lines per hook.
  *
  * One caveat on what a trace can and cannot say. An installed debugger answers `enter()` with the inherited `true`, and
  * `Safepoint.enterPark` takes that as "run this application inline" without draining, so a traced run stops deferring at the budget
  * boundary. Production defers there and replenishes on the unwind. So a trace shows the step sequence faithfully and does not show the
  * deferral rhythm. Pass `guarded` to make the tracer answer `false` instead, which drives the same branch the uninstalled case drives and
  * restores that rhythm.
  */
object SuspensionDebug:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask(using Frame): Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    /** Counts hooks without printing them, for depths where the step log is unreadable. */
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

    def main(args: Array[String]): Unit =
        val shape = if args.length > 0 then args(0) else "map"
        val depth = if args.length > 1 then args(1).toInt else 3
        val quiet = args.contains("quiet")
        val prod  = args.contains("guarded")

        // built inside the installed window on purpose: nodes report their own allocation from their
        // constructors, and ConsoleDebugger refuses an operand it never saw born
        def build(): Int < Any =
            def mapped(i: Int): Int < Ask =
                if i > depth then i else ask.map(a => mapped(i + a))
            def fused(i: Int): Int < Ask =
                if i > depth then i else ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => fused(i + a))
            shape match
                case "with" => ArrowEffect.handleCont(Tag[Ask], fused(0))([C] => (_, cont) => cont(1), a => a)
                case "loop" => ArrowEffect.handleLoop(Tag[Ask], mapped(0))([C] => _ => Loop.continue((), 1: Int < Any), a => a)
                case _      => ArrowEffect.handleCont(Tag[Ask], mapped(0))([C] => (_, cont) => cont(1), a => a)
            end match
        end build

        println(s"shape=$shape depth=$depth quiet=$quiet guarded=$prod")
        val counting = Counting(prod)
        val debugger = if quiet then counting else ConsoleDebugger()
        Debugger.install(debugger)
        try
            val out = Nested.unnest[Int](Eval(build()))
            println(s"RESULT $out   (expected ${depth + 1})")
            debugger match
                case c: ConsoleDebugger => println(s"STATS ${c.stats}")
                case _                  => println("COUNTS\n" + counting.report)
        finally Debugger.uninstall()
        end try
    end main
end SuspensionDebug
