package kyo2.kernel

import internal.*
import java.util.Arrays
import java.util.concurrent.ThreadLocalRandom
import kyo2.isNull
import kyo2.kernel.Safepoint.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

final class Safepoint(initDepth: Int, initPreempt: Preempt, initState: State):
    import Safepoint.State

    private val owner           = Thread.currentThread()
    private[kernel] var depth   = initDepth
    private[kernel] var preempt = initPreempt
    private val random          = ThreadLocalRandom.current()
    private var traceIdx        = initState.traceIdx
    private val trace           = copyTrace(initState.trace, traceIdx)

    private def enter(frame: Frame, value: Any): Int =
        val depth = this.depth
        if (Thread.currentThread eq owner) &&
            depth < maxStackDepth &&
            (isNull(preempt) || preempt.enter(frame, random.nextInt(), value))
        then
            pushFrame(frame, traceIdx)
            this.depth = depth + 1
            depth + 1
        else
            -1
        end if
    end enter

    private def pushFrame(frame: Frame, traceIdx: Int): Unit =
        trace(traceIdx & (maxTraceFrames - 1)) = frame
        this.traceIdx = traceIdx + 1
    end pushFrame

    private[kernel] def clearTrace(): Unit =
        traceIdx = 0

    private def exit(depth: Int): Unit =
        this.depth = depth - 1
        if preempt ne null then
            preempt.exit()
    end exit

    private[kernel] def save(context: Context) =
        State(Safepoint.copyTrace(trace, traceIdx), traceIdx, context)
end Safepoint

object Safepoint:

    abstract private[kyo2] class Preempt:
        def enter(frame: Frame, token: Int, value: Any): Boolean
        def exit(): Unit

    private[kyo2] inline def preempt[A, S](p: Preempt)(inline v: => A < S)(using safepoint: Safepoint): A < S =
        val prev = safepoint.preempt
        val np =
            if prev eq null then p
            else
                new Preempt:
                    def enter(frame: Frame, token: Int, value: Any) =
                        p.enter(frame, token, value) && prev.enter(frame, token, value)
                    def exit() =
                        p.exit()
                        prev.exit()
        safepoint.preempt = np
        try v
        finally safepoint.preempt = prev
    end preempt

    abstract private[kyo2] class Listen(samplingPercent: Double) extends Preempt:
        val samplingThreshold = (samplingPercent * 1024).toInt
        final def enter(frame: Frame, token: Int, value: Any) =
            println(("l", token & 1023, samplingThreshold))
            if (token & 1023) < samplingThreshold then
                onEnter(frame, token, value)
            true
        end enter
        final def exit(): Unit = {}
        def onEnter(frame: Frame, token: Int, value: Any): Unit
    end Listen

    private[kyo2] inline def listen[A, S](p: Listen)(inline v: => A < S)(using inline _frame: Frame): A < S =
        def listenLoop(v: A < S)(using Safepoint): A < S =
            v match
                case <(kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked) =>
                    new KyoSuspend[IX, OX, EX, Any, A, S]:
                        val tag   = kyo.tag
                        val input = kyo.input
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using safepoint: Safepoint) =
                            preempt(p)(listenLoop(kyo(v, context)))
                case kyo =>
                    kyo
        listenLoop(v)
    end listen

    implicit def get: Safepoint = local.get()

    private[kernel] inline def eval[T](inline f: => T)(using inline self: Safepoint): T =
        try f
        catch
            case ex: Throwable if NonFatal(ex) =>
                handle(self, ex)
        end try
    end eval

    private[kernel] inline def handle[V, A, S](value: V)(
        inline suspend: Safepoint ?=> A < S,
        inline continue: => A < S
    )(using inline frame: Frame, self: Safepoint): A < S =
        self.enter(frame, value) match
            case -1 => Effect.defer(suspend)
            case depth =>
                try continue
                finally self.exit(depth)
    end handle

    private def handle(self: Safepoint, cause: Throwable): Nothing =
        val size  = Math.min(self.traceIdx, maxTraceFrames)
        val trace = copyTrace(self.trace, self.traceIdx).filter(_ != null).map(_.parse)
        val toPad = trace.map(_.snippetShort.size).max + 1
        val elements = trace.map { frame =>
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
        val context: Context
    )

    object State:
        val empty = State(new Array(maxStackDepth), 0, Context.empty)

    private[kernel] val local = ThreadLocal.withInitial(() => Safepoint(0, null, State.empty))

end Safepoint
