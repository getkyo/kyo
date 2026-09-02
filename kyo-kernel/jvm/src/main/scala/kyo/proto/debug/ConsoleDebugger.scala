package kyo.proto.debug

import kyo.proto.kernel.Arrow
import kyo.proto.kernel.Loop
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Pending
import org.openjdk.jol.datamodel.Model64
import org.openjdk.jol.datamodel.Model64_Lilliput
import org.openjdk.jol.info.ClassLayout
import org.openjdk.jol.layouters.HotSpotLayouter
import scala.jdk.CollectionConverters.*

object ConsoleDebugger:

    private var layoutCache = Map.empty[Class[?], String]

    private def layoutLine(cls: Class[?]): String =
        layoutCache.getOrElse(
            cls, {
                val line = layout(cls)
                layoutCache = layoutCache.updated(cls, line)
                line
            }
        )

    private val jdk           = Runtime.version().feature()
    private val classicLayout = new HotSpotLayouter(new Model64(true, true, 8), jdk)
    private val compactLayout = new HotSpotLayouter(new Model64_Lilliput(true, 8, false), jdk)

    private def layout(cls: Class[?]): String =
        val measured           = ClassLayout.parseClass(cls)
        val runsCompact        = measured.headerSize == 8
        val other              = ClassLayout.parseClass(cls, if runsCompact then classicLayout else compactLayout)
        val (classic, compact) = if runsCompact then (other, measured) else (measured, other)
        val star               = if runsCompact then ("", "*") else ("*", "")
        s"📐 classic${star._1} ${segments(classic)} ∙ compact${star._2} ${segments(compact)}"
    end layout

    private def segments(l: ClassLayout): String =
        val fields = l.fields().asScala.toSeq.sortBy(_.offset)
        val sb     = new StringBuilder
        sb.append(s"${l.instanceSize} B [hdr ${l.headerSize}")
        var end: Long = l.headerSize
        for f <- fields do
            if f.offset > end then sb.append(s" | gap ${f.offset - end}")
            val name = if f.name.startsWith("$") then f.name else f.name.takeWhile(_ != '$')
            sb.append(s" | $name ${f.size}")
            end = f.offset + f.size
        end for
        if l.instanceSize > end then sb.append(s" | pad ${l.instanceSize - end}")
        sb.append("]")
        sb.toString
    end segments

end ConsoleDebugger

final class ConsoleDebugger extends Debugger:

    private var depth        = 0
    private var counts       = Map.empty[String, Int]
    private var unfusedCount = 0

    private val reported = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Any, java.lang.Boolean])
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
            case _: Pending.Handle[?, ?, ?, ?, ?, ?]       => "Handle"
            case _: Arrow.Id[?]                        => "Id"
            case _: Arrow.Chain[?, ?, ?, ?]            => "Chain"
            case _: Loop.Continue[?]                   => "Continue"
            case _: Loop.Continue2[?, ?]               => "Continue2"
            case _: Loop.Continue3[?, ?, ?]            => "Continue3"
            case _: Loop.Continue4[?, ?, ?, ?]         => "Continue4"
            case _: Arrow.Step[?, ?, ?]                => "Step"
            case _: Arrow.Transform[?, ?, ?]           => "Transform"
            case v                                     => v.getClass.getSimpleName
        counts = counts.updated(name, counts.getOrElse(name, 0) + 1)
        println(s"$pad🧮 alloc: $value")
        println(s"$pad${ConsoleDebugger.layoutLine(value.getClass)}")
    end onAlloc

    override def onUnfused(arrow: Any): Unit =
        checkReported(arrow)
        unfusedCount += 1
        println(s"$pad✂️ apply: $arrow")
    end onUnfused

    override def onLoop(value: Any, contA: Any, contB: Any): Unit =
        checkReported(value, contA, contB)
        log(
            s"""|🔁 loop
                |value: $value
                |contA: $contA
                |contB: $contB""".stripMargin
        )
    end onLoop

    override def onContext(suspend: Any, state: Any): Unit =
        checkReported(suspend, state)
        log(
            s"""|📖 context
                |suspend: $suspend
                |state: $state""".stripMargin
        )
    end onContext

    override def onRegionEnter(handler: Any, state: Any): Unit =
        checkReported(state)
        log(
            s"""|📥 region enter
                |handler: $handler
                |state: $state
                |stack: $stackSize""".stripMargin
        )
        depth += 1
    end onRegionEnter

    override def onRegionExit(handler: Any, result: Any): Unit =
        checkReported(result)
        depth -= 1
        log(
            s"""|📤 region exit
                |handler: $handler
                |result: $result
                |stack: $stackSize""".stripMargin
        )
    end onRegionExit

    override def onForeign(suspend: Any, handler: Any): Unit =
        checkReported(suspend)
        log(
            s"""|🫧 foreign suspend
                |suspend: $suspend
                |handler: $handler""".stripMargin
        )
    end onForeign

    override def onRelease(handler: Any, ex: Any): Unit =
        log(
            s"""|🧹 release
                |handler: $handler
                |ex: $ex""".stripMargin
        )

    override def onRecover(handler: Any, ex: Any): Unit =
        log(
            s"""|🩹 recover
                |handler: $handler
                |ex: $ex""".stripMargin
        )

    override def onHandle(suspend: Any, handler: Any, state: Any): Unit =
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
        val total = counts.values.sum
        val allocs =
            if total == 0 then "allocs: 0"
            else
                val parts = counts.toList.sortBy((n, c) => (-c, n)).map((n, c) => s"$n x$c").mkString(", ")
                s"allocs: $total ($parts)"
        s"$allocs | unfused applies: $unfusedCount"
    end stats
end ConsoleDebugger
