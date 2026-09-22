package kyo.kernel.debug

import kyo.kernel.<
import kyo.kernel.Arrow
import kyo.kernel.Loop
import kyo.kernel.internal.Debugger
import kyo.kernel.internal.Handler
import kyo.kernel.internal.Pending

/** A `Debugger` that prints the evaluator's steps as they happen, indented by region depth.
  *
  * The reported JVM stack depth at each step tells a fused run building frames apart from one deferring through the evaluator. Nothing here
  * is on unless the kernel was compiled with `-Dkyo.kernel.internal.Debugger.enabled=true`, since the hooks are erased otherwise.
  */
final class ConsoleDebugger extends Debugger:

    private var depth        = 0
    private var counts       = Map.empty[String, Int]
    private var unfusedCount = 0

    // Thread-safe: the fiber scheduler evaluates across carrier threads, so an arrow reported via onAlloc on one thread must be visible
    // to checkReported on another; a plain IdentityHashMap set would throw "unreported allocation" on a value reported elsewhere.
    private val reported =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Any, java.lang.Boolean]))
    reported.add(Arrow.id)

    private def checkReported(vs: Any*): Unit =
        for v <- vs do
            if (v.isInstanceOf[Pending[?, ?]] || v.isInstanceOf[Arrow[?, ?, ?]]) && !reported.contains(v) then
                throw new IllegalStateException(s"unreported allocation reached a hook: $v (${v.getClass.getName})")

    private def pad = "    " * depth

    private def log(entry: String): Unit =
        val p = pad
        println((("-" * (80 - p.length)) +: entry.linesIterator.toSeq).map(p + _).mkString("\n"))

    private def stackSize: Int = (new Exception).getStackTrace.length

    override def onAlloc(value: Any): Unit =
        reported.add(value)
        val name = value match
            case _: Pending.Defer[?, ?, ?, ?]              => "Defer"
            case _: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] => "SuspendArrow"
            case _: Pending.SuspendContext[?, ?, ?, ?]     => "SuspendContext"
            case _: Pending.HandleArrow[?, ?, ?, ?, ?, ?]  => "HandleArrow"
            case _: Pending.HandleContext[?, ?, ?, ?]      => "HandleContext"
            case _: Arrow.Id[?]                            => "Id"
            case _: Arrow.Chain[?, ?, ?, ?]                => "Chain"
            case _: Loop.Continue[?]                       => "Continue"
            case _: Loop.Continue2[?, ?]                   => "Continue2"
            case _: Loop.Continue3[?, ?, ?]                => "Continue3"
            case _: Loop.Continue4[?, ?, ?, ?]             => "Continue4"
            case _: Arrow.Step[?, ?, ?]                    => "Step"
            case _: Arrow.Transform[?, ?, ?]               => "Transform"
            case v                                         => v.getClass.getSimpleName
        counts = counts.updated(name, counts.getOrElse(name, 0) + 1)
        println(s"$pad🧮 alloc: $value")
    end onAlloc

    override def onUnfused(arrow: Arrow[?, ?, ?]): Unit =
        checkReported(arrow)
        unfusedCount += 1
        println(s"$pad✂️ apply: $arrow")
    end onUnfused

    override def onLoop(value: Any < Nothing, contA: Arrow[?, ?, ?], contB: Arrow[?, ?, ?]): Unit =
        checkReported(value, contA, contB)
        log(
            s"""|🔁 loop
                |value: $value
                |contA: $contA
                |contB: $contB""".stripMargin
        )
    end onLoop

    override def onContext(node: Pending[?, ?], state: Any): Unit =
        checkReported(node, state)
        log(
            s"""|📖 context
                |node: $node
                |state: $state""".stripMargin
        )
    end onContext

    override def onRegionEnter(handler: Handler[?, ?, ?], state: Any): Unit =
        checkReported(state)
        log(
            s"""|📥 region enter
                |handler: $handler
                |state: $state
                |stack: $stackSize""".stripMargin
        )
        depth += 1
    end onRegionEnter

    override def onRegionExit(handler: Handler[?, ?, ?], result: Any): Unit =
        checkReported(result)
        depth -= 1
        log(
            s"""|📤 region exit
                |handler: $handler
                |result: $result
                |stack: $stackSize""".stripMargin
        )
    end onRegionExit

    override def onForeign(suspend: Pending.Suspend[?, ?, ?, ?], handler: Handler[?, ?, ?]): Unit =
        checkReported(suspend)
        log(
            s"""|🫧 foreign suspend
                |suspend: $suspend
                |handler: $handler""".stripMargin
        )
    end onForeign

    override def onRelease(handler: Handler[?, ?, ?], ex: Throwable): Unit =
        log(
            s"""|🧹 release
                |handler: $handler
                |ex: $ex""".stripMargin
        )

    override def onRecover(handler: Handler[?, ?, ?], ex: Throwable): Unit =
        log(
            s"""|🩹 recover
                |handler: $handler
                |ex: $ex""".stripMargin
        )

    override def onHandle(suspend: Pending.Suspend[?, ?, ?, ?], handler: Handler[?, ?, ?], state: Any): Unit =
        checkReported(suspend, state)
        log(
            s"""|⚡ handle
                |suspend: $suspend
                |handler: $handler
                |state: $state""".stripMargin
        )
        depth += 1
    end onHandle

    override def onResult(value: Any): Unit =
        checkReported(value)
        log(
            s"""|📦 result
                |value: $value""".stripMargin
        )
        depth -= 1
    end onResult

    def stats: String =
        val total  = counts.values.sum
        val allocs =
            if total == 0 then "allocs: 0"
            else
                val parts = counts.toList.sortBy((n, c) => (-c, n)).map((n, c) => s"$n x$c").mkString(", ")
                s"allocs: $total ($parts)"
        s"$allocs | unfused applies: $unfusedCount"
    end stats
end ConsoleDebugger
