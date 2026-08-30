// inside kyo on purpose: this tooling exercises the kernel's private Debugger seam, which is the
// sanctioned reason to stand on private surface. The computations it traces are built outside kyo,
// on the public surface, so their frames derive at real positions
package kyo.proto.debug

import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Pending
import org.openjdk.jol.datamodel.Model64
import org.openjdk.jol.datamodel.Model64_Lilliput
import org.openjdk.jol.info.ClassLayout
import org.openjdk.jol.layouters.HotSpotLayouter
import scala.jdk.CollectionConverters.*

object ConsoleDebugger:

    // a layout is class-static, so the JOL parse and the rendering are a one-time cost per
    // class; every allocation prints the cached line
    private var layoutCache = Map.empty[Class[?], String]

    private def layoutLine(cls: Class[?]): String =
        layoutCache.getOrElse(
            cls, {
                val line = layout(cls)
                layoutCache = layoutCache.updated(cls, line)
                line
            }
        )

    // both header geometries, always: the running VM's mode is measured (marked *), the other is
    // modeled through JOL's HotSpot layouter (classic = compressed oops and class pointers, 12 B
    // header; compact = the JEP 450 8 B header)
    private val jdk           = Runtime.version().feature()
    private val classicLayout = new HotSpotLayouter(new Model64(true, true, 8), jdk)
    private val compactLayout = new HotSpotLayouter(new Model64_Lilliput(true, 8, false), jdk)

    /** The class's memory footprint as a segment map in offset order, in both header geometries: header, each field with its size, and
      * every byte alignment loses, where it loses it. Inline combinators expand an anonymous class per call site, so capture oddities (an
      * `$outer`, a duplicated capture slot) show up as segments, per site. Compiler suffixes are trimmed from field names; `$`-prefixed
      * synthetics keep their names.
      */
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

// TODO move to kyo-core and make the output async to reduce interference, following the
// Log.Unsafe.AsyncUnsafe pattern: hooks enqueue structured events to the dispatcher and the drain
// does the rendering, the JOL parse, and the println. The completeness check stays synchronous
// (it must fail the run at the offending operation), depth and stack size are captured at hook
// time, each scenario awaits the drain before printing stats, and the JOL dependency travels with
// the move.
/** Console tracer for the eval: renders the execution timeline (loop steps, regions, handler answers, context reads, allocations) with
  * region-depth indentation and keeps the per-run allocation tally. One instance per scenario; install before running, read `stats` after.
  */
final class ConsoleDebugger extends Debugger:

    private var depth        = 0
    private var counts       = Map.empty[String, Int]
    private var unfusedCount = 0

    // every allocation reported so far, by identity: the completeness net. Any Pending or Arrow
    // operand reaching a hook below must have been born through onAlloc; an orphan means an
    // allocation site lost its hook, and the run fails on the spot instead of silently
    // under-reporting. Seeded with the identity arrow, which is minted at module initialization
    // and may predate any installed debugger.
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
            case _: Kyo.Defer[?, ?, ?, ?]                 => "Defer"
            case _: Kyo.SuspendArrow[?, ?, ?, ?, ?, ?]    => "SuspendArrow"
            case _: Kyo.SuspendContext[?, ?, ?, ?]        => "SuspendContext"
            case _: Kyo.SuspendContextDefault[?, ?, ?, ?] => "SuspendContextDefault"
            case _: Kyo.Handle[?, ?, ?, ?, ?, ?]          => "Handle"
            case _: Arrow.Id[?]                           => "Id"
            case _: Arrow.Chain[?, ?, ?, ?]               => "Chain"
            case _: Loop.Continue[?]                      => "Continue"
            case _: Loop.Continue2[?, ?]                  => "Continue2"
            case _: Loop.Continue3[?, ?, ?]               => "Continue3"
            case _: Loop.Continue4[?, ?, ?, ?]            => "Continue4"
            case _: Arrow.Step[?, ?, ?]                   => "Step"
            case _: Arrow.Transform[?, ?, ?]              => "Transform"
            case v                                        => v.getClass.getSimpleName
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

    override def onContextDefault(suspend: Any, state: Any): Unit =
        checkReported(suspend, state)
        log(
            s"""|📖 context default
                |suspend: $suspend
                |state: $state""".stripMargin
        )
    end onContextDefault

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
