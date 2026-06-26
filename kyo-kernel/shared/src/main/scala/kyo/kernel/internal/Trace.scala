package kyo.kernel.internal

import kyo.Frame
import kyo.kernel.internal.*
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
    private[kernel] val frames: Array[Frame],
    private[kernel] var index: Int
) extends Serializable

private[kyo] object Trace:

    private[kyo] def init: Trace = Trace(new Array[Frame](maxTraceFrames), 0)

    /** Captures the current Safepoint's execution trace for a detached unsafe computation. The only path by which kyo-core's
      * Fiber.Unsafe.init can snapshot the caller trace, since saveTrace() is private[kernel] on Trace.Owner (Safepoint extends it).
      */
    private[kyo] def saved()(using safepoint: Safepoint): Trace = safepoint.saveTrace()

    /** Walks the ring newest-first, dropping nulls and collapsing consecutive identical frames into a
      * single run. Returns each surviving frame paired with the count of consecutive occurrences it
      * represents, plus the snippet padding width. A caller that uses the count can annotate a repeated
      * run; a caller that ignores it sees each distinct frame once. Returns `(Nil, 0)` for an empty trace.
      */
    private def orderedRuns(frames: Array[Frame], index: Int): (List[(Frame, Int)], Int) =
        val size = if index < maxTraceFrames then index else maxTraceFrames
        if size <= 0 then (Nil, 0)
        else
            val start =
                if index < maxTraceFrames then 0
                else index & (maxTraceFrames - 1)

            val ordered = new Array[Frame](size)
            @tailrec def parse(idx: Int, maxSnippetSize: Int): Int =
                if idx < size then
                    val curr = frames((start + idx) & (maxTraceFrames - 1))
                    ordered(idx) = curr
                    if curr ne null then
                        val snippetSize = curr.snippetShort.size
                        parse(idx + 1, if snippetSize > maxSnippetSize then snippetSize else maxSnippetSize)
                    else
                        parse(idx + 1, maxSnippetSize)
                    end if
                else
                    maxSnippetSize + 1
            val toPad = parse(0, 0)

            // Fold oldest-first, prepending so the result is newest-first. A non-null frame equal to the
            // current head extends that run (bumps its count); a different non-null frame opens a new run.
            val runs = ordered.foldLeft(List.empty[(Frame, Int)]) {
                case (acc, curr) =>
                    if curr eq null then acc
                    else
                        acc match
                            case (`curr`, count) :: tail => (curr, count + 1) :: tail
                            case _                       => (curr, 1) :: acc
            }
            (runs, toPad)
        end if
    end orderedRuns

    private def toElement(frame: Frame, toPad: Int): StackTraceElement =
        StackTraceElement(
            frame.snippetShort.reverse.padTo(toPad, ' ').reverse + " @ " + frame.className,
            frame.callerName,
            frame.position.fileName,
            frame.position.lineNumber
        )

    private def orderedElements(frames: Array[Frame], index: Int): List[StackTraceElement] =
        val (runs, toPad) = orderedRuns(frames, index)
        runs.map((frame, _) => toElement(frame, toPad))
    end orderedElements

    /** Renders a Trace snapshot to the same readable frame lines kyo splices into an enriched exception
      * stack trace, newest-first (innermost / most-recent frame first). Each line is `at ` followed by a
      * StackTraceElement's toString, i.e. `at <paddedSnippet> @ <className>.<callerName>(<fileName>:<lineNumber>)`.
      * A run of consecutive identical frames (a tight loop pushing the same frame) is shown once with a
      * ` (xN)` suffix carrying the repeat count; a single occurrence carries no suffix. Reuses the same
      * ordering as enrich, so a rendered trace and an enriched exception name the same frames the same way.
      * Returns "" for an empty trace.
      */
    private[kyo] def render(trace: Trace): String =
        val (runs, toPad) = orderedRuns(trace.frames, trace.index)
        runs.map { (frame, count) =>
            val line = s"at ${toElement(frame, toPad)}"
            if count > 1 then s"$line (x$count)" else line
        }.mkString("\n")
    end render

    abstract private[kernel] class Owner extends TracePool.Local:
        final private var frames = new Array[Frame](maxTraceFrames)
        final private var index  = 0

        private[kernel] inline def pushFrame(frame: Frame): Unit =
            // Skip the shared internal placeholder: it carries no per-site position, only crowds the
            // 16-slot ring, and renders as a uniform framework line. A single identity/equality check
            // against the shared placeholder keeps the hot path allocation-free.
            if (frame ne null) && (frame ne Frame.internal) then
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
                // Save the advanced index back into the trace so the trace reflects the frames pushed
                // during this run, not just the position captured when it was created. A cross-thread
                // reader (the end-of-run leak probe inspecting a still-busy fiber) then sees the fiber's
                // live execution frames rather than a stale fork-time snapshot. The frames themselves are
                // already in `trace.frames` (aliased above); only the index needs to advance with them.
                //
                // Fold the index into the canonical range [0, 2*maxTraceFrames) before storing it. The
                // index counts every frame pushed over the trace's whole life, so a long-lived fiber would
                // otherwise climb toward Int.MaxValue and overflow to a negative index, handing saveTrace
                // and TracePool.clear a negative arraycopy length and emptying every render. maxTraceFrames
                // is a power of two, so the fold preserves both the ring-full predicate (index <
                // maxTraceFrames) and the slot mask (index & (maxTraceFrames - 1)) every consumer reads,
                // leaving saved and rendered traces identical to the unfolded index.
                trace.index =
                    if index < maxTraceFrames then index
                    else maxTraceFrames + (index & (maxTraceFrames - 1))
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
            if !ex.isInstanceOf[NoStackTrace] then
                val elements = Trace.orderedElements(frames, index).toArray
                if elements.nonEmpty then
                    val prefix = ex.getStackTrace.takeWhile(e =>
                        e.getFileName() != elements(0).getFileName() || e.getLineNumber != elements(0).getLineNumber()
                    )
                    val suffix = (new Exception).getStackTrace().drop(2)
                    ex.setStackTrace(prefix ++ elements ++ suffix)
                end if
            end if
        end enrich
    end Owner
end Trace
