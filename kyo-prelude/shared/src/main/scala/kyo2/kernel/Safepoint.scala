package kyo2.kernel

import internal.*
import java.util.Arrays
import kyo2.isNull
import kyo2.kernel.Safepoint.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

final class Safepoint(initDepth: Int, initInterceptor: Interceptor, initState: State):
    import Safepoint.State

    private val owner               = Thread.currentThread()
    private[kernel] var depth       = initDepth
    private[kernel] var interceptor = initInterceptor
    private var traceIdx            = initState.traceIdx
    private val trace               = copyTrace(initState.trace, traceIdx)

    private def enter(frame: Frame, value: Any): Int =
        if (Thread.currentThread eq owner) && depth < maxStackDepth &&
            (isNull(interceptor) || interceptor.enter(frame, value))
        then
            pushFrame(frame)
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
        if !isNull(interceptor) then
            interceptor.exit()
    end exit

    private[kernel] def save(context: Context) =
        State(Safepoint.copyTrace(trace, traceIdx), traceIdx, context)
end Safepoint

object Safepoint:

    implicit def get: Safepoint = local.get()

    abstract private[kyo2] class Interceptor:
        def enter(frame: Frame, value: Any): Boolean
        def exit(): Unit
    end Interceptor

    private[kyo2] inline def immediate[A, S](p: Interceptor)(inline v: => A < S)(
        using safepoint: Safepoint
    ): A < S =
        val prev = safepoint.interceptor
        val np =
            if isNull(prev) || (prev eq p) then p
            else
                new Interceptor:
                    def enter(frame: Frame, value: Any) =
                        p.enter(frame, value) && prev.enter(frame, value)
                    def exit() =
                        p.exit()
                        prev.exit()
        safepoint.interceptor = np
        try v
        finally safepoint.interceptor = prev
    end immediate

    private[kyo2] inline def propagating[A, S](p: Interceptor)(inline v: => A < S)(
        using
        inline safepoint: Safepoint,
        inline _frame: Frame
    ): A < S =
        def loop(v: A < S): A < S =
            v match
                case <(kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked) =>
                    new KyoContinue[IX, OX, EX, Any, A, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint): A < S =
                            loop(immediate(p)(kyo(v, context)))
                case _ =>
                    v
        immediate(p)(loop(v))
    end propagating

    private[kernel] inline def eval[T](
        inline f: => T
    )(using inline frame: Frame): T =
        val parent = Safepoint.local.get()
        val self   = new Safepoint(0, parent.interceptor, State.empty)
        Safepoint.local.set(self)
        self.pushFrame(frame)
        try f
        catch
            case ex: Throwable if NonFatal(ex) =>
                insertTrace(ex)(using self)
                throw ex
        finally
            Safepoint.local.set(parent)
        end try
    end eval

    private[kernel] inline def handle[V, A, S](value: V)(
        inline suspend: Safepoint ?=> A < S,
        inline continue: => A < S
    )(using inline frame: Frame, self: Safepoint): A < S =
        self.enter(frame, value) match
            case -1 =>
                Effect.defer(suspend)
            case depth =>
                try continue
                finally self.exit(depth)
    end handle

    // TODO compiler crash if private[kyo2]
    def insertTrace(cause: Throwable)(using self: Safepoint): Unit =
        val size = Math.min(self.traceIdx, maxTraceFrames)
        if size > 0 then
            val trace = copyTrace(self.trace, self.traceIdx).filter(_ != null).map(_.parse)
            val toPad = trace.map(_.snippetShort.size).maxOption.getOrElse(0) + 1
            val elements =
                trace.foldLeft(List.empty[Frame.Parsed]) {
                    case (acc, curr) =>
                        acc match
                            case `curr` :: tail => acc
                            case _              => curr :: acc
                }.reverse.map { frame =>
                    StackTraceElement(
                        frame.snippetShort.reverse.padTo(toPad, ' ').reverse + " @ " + frame.declaringClass,
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
        end if
    end insertTrace

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
        val context: Context
    )

    object State:
        val empty = State(new Array(maxStackDepth), 0, Context.empty)

    private[kernel] val local = ThreadLocal.withInitial(() => Safepoint(0, null, State.empty))

end Safepoint
