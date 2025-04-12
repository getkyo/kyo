package kyo.kernel3.internal

import kyo.Frame
import kyo.kernel3.internal.*
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

/** Trace maintains a fixed-size circular buffer of [[Frame]] objects that record the execution trace.
  *
  * Each Trace instance can store up to [[maxTraceFrames]] frames in its circular buffer. When the buffer is full, new frames replace the
  * oldest ones. This ensures that the most recent execution context is always available while keeping memory usage bounded.
  *
  * Traces are managed through a pooling system (see [[TracePool]]) to minimize allocation overhead. The pool maintains both thread-local
  * and global sets of reusable Trace instances.
  *
  * @param frames
  *   The circular buffer array storing Frame objects
  * @param index
  *   Current position in the circular buffer
  */
final private[kyo] class Trace(
    private[kernel3] val frames: Array[Frame],
    private[kernel3] var index: Int
)

private[kyo] object Trace:

    private[kernel3] def init: Trace = Trace(new Array[Frame](maxTraceFrames), 0)

    abstract private[kernel3] class Owner extends TracePool.Local:
        final private var frames = new Array[Frame](maxTraceFrames)
        final private var index  = 0

        private[kernel3] inline def pushFrame(frame: Frame): Unit =
            val idx = this.index
            frames(idx & (maxTraceFrames - 1)) = frame
            this.index = idx + 1
        end pushFrame

        final private[kernel3] def saveTrace(): Trace =
            val newTrace  = borrow()
            val newFrames = newTrace.frames
            val newIndex  = math.min(index, maxTraceFrames)

            if index <= maxTraceFrames then
                System.arraycopy(frames, 0, newFrames, 0, newIndex)
            else
                val splitIndex      = index & (maxTraceFrames - 1)
                val firstPartLength = maxTraceFrames - splitIndex
                System.arraycopy(frames, splitIndex, newFrames, 0, firstPartLength)
                System.arraycopy(frames, 0, newFrames, firstPartLength, splitIndex)
            end if
            newTrace.index = newIndex
            newTrace
        end saveTrace

        final private[kyo] def copyTrace(trace: Trace): Trace =
            val newTrace = borrow()
            System.arraycopy(trace.frames, 0, newTrace.frames, 0, Math.min(trace.index, maxTraceFrames - 1))
            newTrace.index = trace.index
            newTrace
        end copyTrace

        final private[kyo] def releaseTrace(trace: Trace): Unit =
            release(trace)

        private[kernel3] inline def withTrace[A](trace: Trace)(inline f: => A)(using frame: Frame): A =
            val prevFrames = frames
            val prevIdx    = index
            frames = trace.frames
            index = trace.index
            pushFrame(frame)
            try f
            catch
                case ex: Throwable if NonFatal(ex) =>
                    enrich(ex)
                    throw ex
            finally
                frames = prevFrames
                index = prevIdx
            end try
        end withTrace

        private[kernel3] inline def withNewTrace[A](inline f: => A)(using Frame): A =
            val trace: Trace = borrow()
            try
                withTrace(trace)(f)
            finally
                release(trace)
            end try
        end withNewTrace

        final private[kernel3] def enrich(ex: Throwable): Unit =
            val size = if index < maxTraceFrames then index else maxTraceFrames
            if size > 0 && !ex.isInstanceOf[NoStackTrace] then

                val start =
                    if index < maxTraceFrames then 0
                    else index & (maxTraceFrames - 1)

                val ordered = new Array[Frame](size)
                @tailrec def parse(idx: Int, maxSnippetSize: Int): Int =
                    if idx < size then
                        val curr = frames((start + idx) & (maxTraceFrames - 1))
                        ordered(idx) = curr
                        val snippetSize = curr.snippetShort.size
                        parse(idx + 1, if snippetSize > maxSnippetSize then snippetSize else maxSnippetSize)
                    else
                        maxSnippetSize + 1
                val toPad = parse(0, 0)

                val elements =
                    ordered.foldLeft(List.empty[Frame]) {
                        case (acc, curr) =>
                            acc match
                                case `curr` :: tail => acc
                                case _              => curr :: acc
                    }.map { frame =>
                        StackTraceElement(
                            frame.snippetShort.reverse.padTo(toPad, ' ').reverse + " @ " + frame.className,
                            frame.methodName,
                            frame.position.fileName,
                            frame.position.lineNumber
                        )
                    }
                val prefix = ex.getStackTrace.takeWhile(e =>
                    e.getFileName() != elements(0).getFileName() || e.getLineNumber != elements(0).getLineNumber()
                )
                val suffix = (new Exception).getStackTrace().drop(2)
                ex.setStackTrace(prefix ++ elements ++ suffix)
            end if
        end enrich
    end Owner
end Trace
