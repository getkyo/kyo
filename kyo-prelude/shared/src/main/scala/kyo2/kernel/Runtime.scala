package kyo2.kernel

import internal.*
import java.util.Arrays
import kyo2.isNull
import scala.annotation.tailrec
import scala.util.control.NonFatal

final class Runtime(initDepth: Int, initState: Runtime.State):
    import Runtime.State

    private val owner         = Thread.currentThread()
    private[kernel] var depth = initDepth
    private var traceIdx      = initState.traceIdx
    private val trace         = Runtime.copyTrace(initState.trace, traceIdx)

    private def enter(_frame: Frame): Int =
        require(Thread.currentThread eq owner, "Leaked runtime! " + (Thread.currentThread, owner))
        if depth < maxStackDepth then
            pushFrame(_frame)
            val depth = this.depth
            this.depth = depth + 1
            depth + 1
        else
            -1
        end if
    end enter

    private def pushFrame(frame: Frame): Unit =
        val traceIdx = this.traceIdx
        trace(traceIdx & (maxTraceFrames - 1)) = frame
        this.traceIdx = traceIdx + 1
    end pushFrame

    private[kernel] def clearTrace(): Unit =
        traceIdx = 0

    private def exit(depth: Int): Unit =
        this.depth = depth - 1

    private[kernel] def save(values: Values) =
        State(Runtime.copyTrace(trace, traceIdx), traceIdx, values)
end Runtime

object Runtime:

    implicit def get: Runtime = local.get()

    private[kernel] inline def eval[T](
        inline f: => T
    )(using inline frame: Frame): T =
        val parent = Runtime.local.get()
        val self   = new Runtime(0, State.empty)
        Runtime.local.set(self)
        self.pushFrame(frame)
        try f
        catch
            case ex: Throwable if NonFatal(ex) =>
                handle(self, ex)
        finally
            Runtime.local.set(parent)
        end try
    end eval

    private[kernel] inline def handle[A, S](
        inline suspend: Runtime ?=> A < S,
        inline continue: => A < S
    )(using inline frame: Frame, self: Runtime): A < S =
        self.enter(frame) match
            case -1 => Effect.defer(suspend)
            case depth =>
                try continue
                finally self.exit(depth)
    end handle

    private def handle(self: Runtime, cause: Throwable): Nothing =
        val size   = Math.min(self.traceIdx, maxTraceFrames)
        val trace  = copyTrace(self.trace, self.traceIdx).filter(_ != null).map(_.parse)
        val toDrop = trace.map(_.snippetShort.takeWhile(_ == ' ').size).min
        val elements = trace.map { frame =>
            StackTraceElement(
                frame.snippetShort.drop(toDrop) + " @ " + frame.declaringClass,
                frame.methodName,
                frame.position.fileName,
                frame.position.lineNumber
            )
        }.reverse
        val prefix = cause.getStackTrace.takeWhile(e =>
            e.getFileName() != elements(0).getFileName() || e.getLineNumber != elements(0).getLineNumber()
        )
        val suffix = (new Exception).getStackTrace().drop(2)
        cause.setStackTrace(prefix ++ elements ++ suffix)
        throw cause
    end handle

    private def copyTrace(trace: Array[Frame], idx: Int): Array[Frame] =
        val result = new Array[Frame](maxTraceFrames)
        val offset = Math.max(0, idx - maxTraceFrames)
        @tailrec def loop(i: Int): Unit =
            if i < maxTraceFrames then
                val t = trace((offset + i) & (maxTraceFrames - 1))
                if t != null then
                    result(i) = t
                    loop(i + 1)
        loop(0)
        result
    end copyTrace

    import internal.*

    final class State(
        val trace: Array[Frame],
        val traceIdx: Int,
        val values: Values
    )

    object State:
        val empty = State(new Array(maxStackDepth), 0, Values.empty)

    private[kernel] val local = ThreadLocal.withInitial(() => Runtime(0, State.empty))

end Runtime
