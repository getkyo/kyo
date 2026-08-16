package kyo.kernel.proto

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.discard
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

/** The effect-level frames of a failure, carried as a suppressed exception on the failure itself.
  *
  * The frames are not recorded while the computation runs. They are reconstructed at the boundary the exception crosses, from the failing
  * value and the drive stack the evaluator is already holding, so nothing is paid when nothing throws. The carrier exists for three reasons
  * and no others: its presence among `getSuppressed` marks an exception as already enriched, it accumulates the reconstructions of every
  * boundary an exception crosses, and `getMessage` renders them for a reader that would rather not parse a stack trace.
  */
final private[kyo] class EffectTrace extends Exception(null, null, false, false):

    private[kyo] var elements: Array[StackTraceElement]        = EffectTrace.noElements
    private[kyo] var dropped: Int                              = 0
    private[kyo] var physical: Maybe[Array[StackTraceElement]] = Maybe.Absent

    override def getMessage: String =
        val body = elements.iterator.map(e => s"at $e").mkString("\n")
        if dropped == 0 then s"effect trace:\n$body"
        else s"effect trace:\n$body\n... $dropped more not walked"
    end getMessage

end EffectTrace

private[kyo] object EffectTrace:

    /** One total cap across every boundary the exception crosses. The walk emits innermost first and stops here, so a chain deeper than the
      * cap hides the outer regions, which is the failure mode a truncated Java stack already has.
      */
    private inline def MaxFrames = 64

    private val noElements = new Array[StackTraceElement](0)

    /** Reconstructs the frames around a failing value and appends them to the exception's carrier.
      *
      * `v` is what the evaluator was working on when the throw crossed it, either the current node or the frame just popped, and the sweep
      * over the drive stack from its top down to `base` adds the pending continuation of the whole drive: user transforms with their source
      * positions and handle frames as region labels.
      */
    def attach(ex: Throwable, v: Any, stack: Stack, base: Int): Unit =
        reconstruct(ex) { builder =>
            builder.value(v)
            builder.entries(stack, base)
        }

    /** The application boundary: the evaluator applied a frame through the step protocol with the pending continuation already folded into
      * `next`, so the fold, not the stack, holds what was left to run.
      */
    def attach(ex: Throwable, v: Any, next: Arrow[?, ?, ?], stack: Stack, base: Int): Unit =
        reconstruct(ex) { builder =>
            builder.value(v)
            builder.arrow(next)
            builder.entries(stack, base)
        }

    /** Runs one reconstruction into the exception's carrier.
      *
      * A fatal error is returned unmodified: the test lives here rather than in a catch guard so that every guarded site rethrows
      * unconditionally and propagation is the same for every exception. A non-fatal failure of the walk itself is dropped, because an
      * exception raised while describing a failure would replace the failure, which is strictly worse than describing nothing.
      */
    private inline def reconstruct(ex: Throwable)(inline fill: Builder => Unit): Unit =
        if NonFatal(ex) then
            try
                val carrier = carrierOf(ex)
                val builder = new Builder(MaxFrames - carrier.elements.length)
                fill(builder)
                builder.installInto(carrier)
            catch case failure if NonFatal(failure) => ()
        end if
    end reconstruct

    /** Writes the accumulated frames into the exception's stack trace, synthesized frames first, then the physical trace with the kernel's
      * plumbing removed.
      *
      * Leading with the synthesized frames means there is no splice position to locate. `NoStackTrace` keeps its carrier and skips the
      * splice: the frames stay readable as data on a value that deliberately has no stack.
      */
    def splice(ex: Throwable): Unit =
        if NonFatal(ex) && !ex.isInstanceOf[NoStackTrace] then
            try
                find(ex) match
                    case Maybe.Present(carrier) if carrier.elements.length > 0 =>
                        val physical =
                            carrier.physical match
                                case Maybe.Present(p) => p
                                case Maybe.Absent =>
                                    val p = ex.getStackTrace.filterNot(isPlumbing)
                                    carrier.physical = Maybe(p)
                                    p
                        ex.setStackTrace(carrier.elements ++ physical)
                    case _ => ()
            catch case failure if NonFatal(failure) => ()
        end if
    end splice

    /** The kernel's own frames, which say only that a computation was being evaluated.
      *
      * The per-site `Arrow.Transform` a user's `map` mints is an anonymous class in the user's own compilation unit carrying the user's
      * line numbers, so it is the most informative physical frame present and is never filtered.
      */
    private def isPlumbing(e: StackTraceElement): Boolean =
        val cls = e.getClassName
        cls.startsWith("kyo.kernel.proto.Eval") || cls.startsWith("kyo.kernel.proto.Arrow") ||
        cls.startsWith("kyo.kernel.proto.ArrowEffect") || cls.startsWith("kyo.kernel.proto.Stack") ||
        cls.startsWith("kyo.kernel.proto.Nested") || cls.startsWith("kyo.kernel.proto.Pending")
    end isPlumbing

    private def find(ex: Throwable): Maybe[EffectTrace] =
        val suppressed = ex.getSuppressed
        @tailrec def loop(i: Int): Maybe[EffectTrace] =
            if i == suppressed.length then Maybe.Absent
            else
                suppressed(i) match
                    case carrier: EffectTrace => Maybe(carrier)
                    case _                    => loop(i + 1)
        loop(0)
    end find

    private def carrierOf(ex: Throwable): EffectTrace =
        find(ex) match
            case Maybe.Present(carrier) => carrier
            case Maybe.Absent =>
                val carrier = new EffectTrace
                ex.addSuppressed(carrier)
                carrier

    /** The reconstruction walk.
      *
      * The cap is the walk's stack-safe carrier: emission stops at it and the worklist never holds more than that many arrows, so a chain or
      * a drive stack of any depth is bounded, and nothing recurses on the Java stack. The worklist is local rather than shared scratch, and
      * the step protocol is never invoked: it mints nodes on a path that is already handling a failure.
      */
    final private class Builder(budget: Int):

        private val out  = new Array[StackTraceElement](Math.max(0, Math.min(budget, MaxFrames)))
        private val work = new ArrayDeque[Arrow[?, ?, ?]]

        private var size    = 0
        private var last    = Frame.internal
        private var dropped = 0

        private def full: Boolean = size == out.length

        /** Emits one frame, skipping the shared internal placeholder and collapsing a run of the same frame to one element, which is what a
          * tight loop over a single `map` site produces.
          */
        def frame(f: Frame): Unit =
            if (f ne Frame.internal) && (f ne last) then
                if full then dropped += 1
                else
                    val callee = f.calleeName
                    val cls    = if callee.isEmpty then f.className else s"$callee @ ${f.className}"
                    out(size) = new StackTraceElement(cls, f.callerName, f.position.fileName, f.position.lineNumber)
                    size += 1
                    last = f
                end if
        end frame

        /** Emits one region label. A handle frame carries no source position, only the effect it answers. */
        def region[E](tag: Tag[E]): Unit =
            if full then dropped += 1
            else
                out(size) = new StackTraceElement(tag.show, "handle", null, -1)
                size += 1
                last = Frame.internal
            end if
        end region

        /** One value in either of its roles: a payload unwraps once, an arrow walks its chain, anything settled contributes nothing. */
        def value(v: Any): Unit =
            v match
                case n: Nested[?]      => value(n.value)
                case a: Arrow[?, ?, ?] => arrow(a)
                case _                 => ()

        /** The pending continuation of the drive, innermost first: every entry from the stack's top down to the drive's base. Entries the
          * cap keeps the sweep from reaching are counted as dropped.
          */
        def entries(stack: Stack, base: Int): Unit =
            @tailrec def loop(i: Int): Unit =
                if i >= base then
                    if full then dropped += i - base + 1
                    else
                        value(stack(i))
                        loop(i - 1)
            loop(stack.size - 1)
        end entries

        /** The arrow role: the steps of one chain, in the order they would have run. */
        def arrow(a: Arrow[?, ?, ?]): Unit =
            push(a)
            drain()
        end arrow

        private def push(a: Arrow[?, ?, ?]): Unit =
            if work.size == MaxFrames then
                // the innermost pending steps are the ones a reader looks at first,
                // so a full worklist gives up its outermost entry, not the new one
                discard(work.removeLast())
                dropped += 1
            end if
            discard(work.prepend(a))
        end push

        @tailrec private def drain(): Unit =
            if work.isEmpty then ()
            else if full then
                dropped += work.size
                work.clear()
            else
                work.removeHead() match
                    case c: Arrow.Chain[?, ?, ?, ?] =>
                        push(c.b)
                        push(c.a)
                    case s: Arrow.Suspend[?, ?, ?, ?, ?, ?] =>
                        frame(s.frame)
                    case h: Arrow.Handle[?, ?, ?, ?, ?] =>
                        region(h.handler.tag)
                    case b: Arrow.Bind[?, ?, ?] =>
                        push(b.cont)
                        b.value match
                            case v: Arrow[?, ?, ?] => push(v)
                            case _                 => ()
                    case e: Arrow.Eval[?, ?, ?] =>
                        var i = 0
                        while i < e.entries.size do
                            push(e.entries(i))
                            i += 1
                        e.value match
                            case v: Arrow[?, ?, ?] => push(v)
                            case _                 => ()
                    case t: Arrow.Transform[?, ?, ?] =>
                        frame(t.frame)
                    case s: Arrow.Step[?, ?, ?] =>
                        push(s.tail)
                        frame(s.head.frame)
                end match
                drain()
            end if
        end drain

        def installInto(carrier: EffectTrace): Unit =
            if size > 0 then
                val fresh = out.slice(0, size)
                carrier.elements = if carrier.elements.length == 0 then fresh else carrier.elements ++ fresh
            end if
            carrier.dropped += dropped
        end installInto

    end Builder

end EffectTrace
