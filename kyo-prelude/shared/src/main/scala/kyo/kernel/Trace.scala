package kyo.kernel

import internal.*
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

class Trace(
    private[kernel] var frames: Array[Frame],
    private[kernel] var index: Int
)

object Trace:

    private[kernel] def init: Trace = Trace(new Array[Frame](maxTraceFrames), 0)

    abstract class Owner extends TracePool.Local:
        final private var frames = new Array[Frame](maxTraceFrames)
        final private var index  = 0

        private[kernel] inline def pushFrame(frame: Frame): Unit =
            val idx = this.index
            frames(idx & (maxTraceFrames - 1)) = frame
            this.index = idx + 1
        end pushFrame

        final private[kernel] def saveTrace(): Trace =
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

        private[kernel] inline def withTrace[A](trace: Trace)(inline f: => A)(using frame: Frame): A =
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

        private[kernel] inline def withNewTrace[A](inline f: => A)(using Frame): A =
            val trace: Trace = borrow()
            try
                withTrace(trace)(f)
            finally
                release(trace)
            end try
        end withNewTrace

        final private[kernel] def enrich(ex: Throwable): Unit =
            val size = if index < maxTraceFrames then index else maxTraceFrames
            if size > 0 && !ex.isInstanceOf[NoStackTrace] then

                val start =
                    if index < maxTraceFrames then 0
                    else index & (maxTraceFrames - 1)

                val parsed = new Array[Frame.Parsed](size)
                @tailrec def parse(idx: Int, maxSnippetSize: Int): Int =
                    if idx < size then
                        val curr = frames((start + idx) & (maxTraceFrames - 1)).parse
                        parsed(idx) = curr
                        val snippetSize = curr.snippetShort.size
                        parse(idx + 1, if snippetSize > maxSnippetSize then snippetSize else maxSnippetSize)
                    else
                        maxSnippetSize + 1
                val toPad = parse(0, 0)

                val elements =
                    parsed.foldLeft(List.empty[Frame.Parsed]) {
                        case (acc, curr) =>
                            acc match
                                case `curr` :: tail => acc
                                case _              => curr :: acc
                    }.map { frame =>
                        StackTraceElement(
                            frame.snippetShort.reverse.padTo(toPad, ' ').reverse + " @ " + frame.declaringClass,
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
