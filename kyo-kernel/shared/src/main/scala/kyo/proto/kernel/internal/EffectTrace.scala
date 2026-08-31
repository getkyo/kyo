package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.Arrow
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

/** The effect-level frames of a failure, carried as a suppressed exception on the failure itself.
  *
  * The frames are not recorded while the computation runs. They are reconstructed at the boundary the exception crosses, from what the
  * eval's guard is already holding, so nothing is paid when nothing throws. The carrier exists for three reasons and no others: its
  * presence among `getSuppressed` marks an exception as already enriched, it accumulates the reconstructions of every boundary an
  * exception crosses, and `getMessage` renders them for a reader that would rather not parse a stack trace.
  */
final class EffectTrace extends Exception(null, null, false, false):

    private[kyo] var elements: Array[StackTraceElement]        = EffectTrace.noElements
    private[kyo] var dropped: Int                              = 0
    private[kyo] var physical: Maybe[Array[StackTraceElement]] = Maybe.Absent

    override def getMessage: String =
        val body = elements.iterator.map(e => s"at $e").mkString("\n")
        if dropped == 0 then s"effect trace:\n$body"
        else s"effect trace:\n$body\n... $dropped more not walked"
    end getMessage

end EffectTrace

/** The reconstruction and the splice.
  *
  * This kernel unwinds centrally: a throw anywhere in an eval's extent lands at the guard's one catch with the region stack intact, so
  * the reconstruction runs there, once per eval boundary, over the standing regions and the continuations they hold. What the guard does
  * not hold are the loop's own registers, the folded continuations of the step that threw, so the innermost pending frames are not
  * synthesized; the physical trace covers that ground, because the per-site transform a user's `map` mints is an anonymous class in the
  * user's own compilation unit and is never filtered. Parking the registers on the stack was built and measured: publishing the loop's
  * values to heap fields defeats escape analysis, and the stores cost 2.2x on `suspensionBaseline` and 1.3x to 1.7x across every
  * allocation-sensitive row, so the register frames stay out. A build-flag variant that folds the parking away in production remains an
  * open option if debug-build fidelity is wanted.
  */
private[kernel] object EffectTrace:

    /** One total cap across every boundary the exception crosses. The walk emits innermost first and stops here, so a chain deeper than
      * the cap hides the outer regions, which is the failure mode a truncated Java stack already has.
      */
    private inline def MaxFrames = 64

    private val noElements = new Array[StackTraceElement](0)

    /** The guard caught `ex` with the eval's regions still standing: each region's label and the continuation it holds are pending, the
      * innermost region first.
      */
    def attach(ex: Throwable, stack: Stack): Unit =
        reconstruct(ex) { builder =>
            builder.entries(stack)
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

    /** Writes the accumulated frames into the exception's stack trace, synthesized frames first, then the physical trace with the
      * kernel's plumbing removed.
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

    /** The kernel's own frames, which say only that a computation was being evaluated. The whole prototype is plumbing here: the frames a
      * reader needs live in the anonymous classes the inline combinators expand at the user's own sites, and those carry the user's class
      * names, so the one prefix removes everything that describes evaluation and nothing that describes the program.
      */
    private def isPlumbing(e: StackTraceElement): Boolean =
        e.getClassName.startsWith("kyo.proto.")

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
        // the find and the add must be one step: two threads racing the first attach on a shared
        // exception would otherwise both add a carrier. `addSuppressed` and `getSuppressed` already
        // synchronize on the exception, so this takes the same monitor they do, held a few
        // instructions longer; nothing user-written runs inside it
        ex.synchronized {
            find(ex) match
                case Maybe.Present(carrier) => carrier
                case Maybe.Absent =>
                    val carrier = new EffectTrace
                    ex.addSuppressed(carrier)
                    carrier
        }

    /** A value-position node. An arrow-position node is walked as an arrow, which is what makes a self-referential continuation slot emit
      * one frame and stop instead of re-enqueueing itself forever.
      */
    final private class Node(val kyo: Pending[?, ?])

    /** A region label pending emission, so the park walk can interleave labels with continuations through the one worklist. */
    final private class Region[E](val tag: Tag[E])

    private type Item = Arrow[?, ?, ?] | Node | Region[?]

    /** The reconstruction walk.
      *
      * A node in this kernel can be two things at once, and the eval tells the two apart by position. A value position holds a
      * computation the eval will take apart, so it is walked as a node. An arrow position holds something the eval will only ever apply,
      * so it is walked as an arrow: it contributes its frame and nothing else. Every self-referential slot the kernel mints is a
      * continuation slot, so it is reached in the arrow role and termination is structural rather than defensive.
      *
      * The cap is the walk's stack-safe carrier: emission stops at it and the worklist never holds more than that many items, so a chain
      * or a region stack of any depth is bounded, and nothing recurses on the Java stack.
      */
    final private class Builder(budget: Int):

        private val out  = new Array[StackTraceElement](Math.max(0, Math.min(budget, MaxFrames)))
        private val work = new ArrayDeque[Item]

        private var size    = 0
        private var last    = Frame.internal
        private var dropped = 0

        private def full: Boolean = size == out.length

        /** Emits one frame, skipping the shared internal placeholder and collapsing a run of the same frame to one element, which is what
          * a tight loop over a single `map` site produces.
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

        /** A value position. It holds exactly the pending union: a settled value, a node, or a `Nested` payload. An `Arrow` is not an arm
          * of that union and never appears here.
          */
        @tailrec private def pushValue(v: Any): Unit =
            v match
                case n: Nested[?]     => pushValue(n.value)
                case p: Pending[?, ?] => push(new Node(p))
                case _                => ()

        private def push(item: Item): Unit =
            if !(item.isInstanceOf[Arrow.Id[?]]) then
                if work.size == MaxFrames then
                    // the innermost pending steps are the ones a reader looks at first,
                    // so a full worklist gives up its outermost entry, not the new one
                    discard(work.removeLast())
                    dropped += 1
                end if
                discard(work.prepend(item))
            end if
        end push

        /** The standing regions, innermost first: each region's label, then the continuation it holds for what follows its extent. */
        def entries(stack: Stack): Unit =
            val n = stack.depth
            @tailrec def loop(i: Int): Unit =
                if i >= 0 then
                    if full then dropped += i + 1
                    else
                        region(stack.handlerAt(i).tag)
                        push(stack.continuationAt(i))
                        drain()
                        loop(i - 1)
            loop(n - 1)
        end entries

        @tailrec private def drain(): Unit =
            if work.isEmpty then ()
            else if full then
                dropped += work.size
                work.clear()
            else
                work.removeHead() match
                    case r: Region[?] => region(r.tag)
                    case n: Node =>
                        n.kyo match
                            case s: Kyo.Suspend[?, ?, ?] =>
                                // the operation's own site, then whatever it answers into
                                frame(s.frame)
                                push(s.cont)
                            case h: Kyo.Handle[?, ?, ?, ?, ?, ?] =>
                                // mirrors the eval's Handle arm: cont, then the region label, then the
                                // body, so the body drains first and the label follows it
                                push(h.cont)
                                push(new Region(h.handler.tag))
                                pushValue(h.value)
                            case d: Kyo.Defer[?, ?, ?, ?] =>
                                push(d.contB)
                                push(d.contA)
                                pushValue(d.value)
                            case p: Kyo.Park[?, ?] =>
                                // mirrors the resume arm: the parked regions stand above the value, so they
                                // drain after it, innermost nearest the value the way re-installation puts
                                // them. Three slots per region: handler, state, continuation
                                val entries = p.entries
                                @tailrec def parked(i: Int): Unit =
                                    if i < entries.length then
                                        push(entries(i + 2).asInstanceOf[Arrow[?, ?, ?]])
                                        push(new Region(entries(i).asInstanceOf[Handler[?, ?, ?, ?, ?]].tag))
                                        parked(i + 3)
                                parked(0)
                                pushValue(p.value)
                    case c: Arrow.Chain[?, ?, ?, ?] =>
                        push(c.b)
                        push(c.a)
                    case a: Arrow[?, ?, ?] =>
                        frame(a.frame)
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
