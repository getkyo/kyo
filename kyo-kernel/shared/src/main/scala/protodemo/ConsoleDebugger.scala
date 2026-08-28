package protodemo

import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Kyo

/** Console tracer for the eval: renders the execution timeline (loop steps, regions, handler answers, context reads, allocations) with
  * region-depth indentation and keeps the per-run allocation tally. One instance per scenario; install before running, read `stats` after.
  */
final class ConsoleDebugger extends Debugger:

    private var depth        = 0
    private var counts       = Map.empty[String, Int]
    private var unfusedCount = 0

    private def pad = "    " * depth

    private def log(entry: String): Unit =
        val p = pad
        println((("-" * (80 - p.length)) +: entry.linesIterator.toSeq).map(p + _).mkString("\n"))

    private def stackSize: Int = (new Exception).getStackTrace.length

    override def onAlloc(value: Any): Unit =
        val name = value match
            case _: Kyo.Defer[?, ?, ?, ?]                 => "Defer"
            case _: Kyo.SuspendArrow[?, ?, ?, ?, ?, ?]    => "SuspendArrow"
            case _: Kyo.SuspendContext[?, ?, ?, ?]        => "SuspendContext"
            case _: Kyo.SuspendContextDefault[?, ?, ?, ?] => "SuspendContextDefault"
            case _: Kyo.Handle[?, ?, ?, ?, ?]             => "Handle"
            case _: Arrow.Id[?]                           => "Id"
            case _: Arrow.Chain[?, ?, ?, ?]               => "Chain"
            case _: Loop.Continue[?, ?, ?]                => "Continue"
            case _: Loop.Done[?, ?, ?]                    => "Done"
            case _: Arrow.Transform[?, ?, ?]              => "Transform"
            case v                                        => v.getClass.getSimpleName
        counts = counts.updated(name, counts.getOrElse(name, 0) + 1)
        println(s"$pad🧮 alloc: $value")
    end onAlloc

    override def onUnfused(arrow: Any): Unit =
        unfusedCount += 1
        println(s"$pad✂️ apply: $arrow")

    override def onLoop(value: Any, contA: Any, contB: Any): Unit =
        log(
            s"""|🔁 loop
                |value: $value
                |contA: $contA
                |contB: $contB""".stripMargin
        )

    override def onContext(suspend: Any, state: Any): Unit =
        log(
            s"""|📖 context
                |suspend: $suspend
                |state: $state""".stripMargin
        )

    override def onContextDefault(suspend: Any, state: Any): Unit =
        log(
            s"""|📖 context default
                |suspend: $suspend
                |state: $state""".stripMargin
        )

    override def onRegionEnter(handler: Any, state: Any): Unit =
        log(
            s"""|📥 region enter
                |handler: $handler
                |state: $state
                |stack: $stackSize""".stripMargin
        )
        depth += 1
    end onRegionEnter

    override def onRegionExit(handler: Any, result: Any): Unit =
        depth -= 1
        log(
            s"""|📤 region exit
                |handler: $handler
                |result: $result
                |stack: $stackSize""".stripMargin
        )
    end onRegionExit

    override def onForeign(suspend: Any, handler: Any): Unit =
        log(
            s"""|🫧 foreign suspend
                |suspend: $suspend
                |handler: $handler""".stripMargin
        )

    override def onHandle(suspend: Any, handler: Any, state: Any): Unit =
        log(
            s"""|⚡ handle
                |suspend: $suspend
                |handler: $handler
                |state: $state""".stripMargin
        )
        depth += 1
    end onHandle

    override def onResult(value: Any): Unit =
        log(
            s"""|📦 result
                |value: $value""".stripMargin
        )
        depth -= 1
    end onResult

    def stats: String =
        val total = counts.values.sum
        val allocs =
            if total == 0 then "allocs: 0"
            else
                val parts = counts.toList.sortBy((n, c) => (-c, n)).map((n, c) => s"$n x$c").mkString(", ")
                s"allocs: $total ($parts)"
        s"$allocs | unfused applies: $unfusedCount"
    end stats
end ConsoleDebugger
