package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.kernel.Arrow
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

final class EffectTrace extends Exception(null, null, false, false):

    private[kyo] var elements: Array[StackTraceElement]        = EffectTrace.noElements
    private[kyo] var dropped: Int                              = 0
    private[kyo] var physical: Maybe[Array[StackTraceElement]] = Maybe.Absent
    private[kyo] var seen: Maybe[Stack]                        = Maybe.Absent
    private[kyo] var seenEpoch: Int                            = 0

    override def getMessage: String =
        val body = elements.iterator.map(e => s"at $e").mkString("\n")
        if dropped == 0 then s"effect trace:\n$body"
        else s"effect trace:\n$body\n... $dropped more not walked"
    end getMessage

end EffectTrace

private[kernel] object EffectTrace:

    private val noElements = new Array[StackTraceElement](0)

    def attach(ex: Throwable, cont: Arrow[?, ?, ?], frame: Frame): Unit =
        reconstruct(ex, Maybe.Absent) { builder =>
            builder.arrow(cont)
            builder.frame(frame)
        }

    def attach(ex: Throwable, stack: Stack): Unit =
        reconstruct(ex, Maybe(stack)) { builder =>
            builder.regions(stack)
        }

    def attach(ex: Throwable, node: Pending[?, ?], stack: Stack): Unit =
        reconstruct(ex, Maybe(stack)) { builder =>
            builder.node(node)
            builder.regions(stack)
        }

    def attach(ex: Throwable, node: Pending[?, ?], cont: Arrow[?, ?, ?], stack: Stack): Unit =
        reconstruct(ex, Maybe(stack)) { builder =>
            builder.arrow(cont)
            builder.node(node)
            builder.regions(stack)
        }

    private inline def reconstruct(ex: Throwable, stack: Maybe[Stack])(inline fill: Builder => Unit): Unit =
        if NonFatal(ex) then
            try
                val carrier = carrierOf(ex)
                val walked  = stack.exists(s => carrier.seen.exists(_ eq s) && carrier.seenEpoch == s.epoch)
                if !walked then
                    stack.foreach { s =>
                        carrier.seen = stack
                        carrier.seenEpoch = s.epoch
                    }
                    val builder = new Builder(maxTraceFrames - carrier.elements.length)
                    fill(builder)
                    builder.installInto(carrier)
                end if
            catch case failure if NonFatal(failure) => ()
        end if
    end reconstruct

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

        ex.synchronized {
            find(ex) match
                case Maybe.Present(carrier) => carrier
                case Maybe.Absent =>
                    val carrier = new EffectTrace
                    ex.addSuppressed(carrier)
                    carrier
        }

    final private class Node(val kyo: Pending[?, ?])

    final private class Region[E](val tag: Tag[E])

    private type Item = Arrow[?, ?, ?] | Node | Region[?]

    final private class Builder(budget: Int):

        private val out  = new Array[StackTraceElement](Math.max(0, Math.min(budget, maxTraceFrames)))
        private val work = new ArrayDeque[Item]

        private var size    = 0
        private var last    = Frame.internal
        private var dropped = 0

        private def full: Boolean = size == out.length

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

        def region[E](tag: Tag[E]): Unit =
            if full then dropped += 1
            else
                out(size) = new StackTraceElement(tag.show, "handle", null, -1)
                size += 1
                last = Frame.internal
            end if
        end region

        @tailrec private def pushValue(v: Any): Unit =
            v match
                case n: Nested[?]     => pushValue(n.value)
                case p: Pending[?, ?] => push(new Node(p))
                case _                => ()

        private def push(item: Item): Unit =
            if !(item.isInstanceOf[Arrow.Id[?]]) then
                if work.size == maxTraceFrames then

                    discard(work.removeLast())
                    dropped += 1
                end if
                discard(work.prepend(item))
            end if
        end push

        def arrow(a: Arrow[?, ?, ?]): Unit =
            push(a)
            drain()

        def node(p: Pending[?, ?]): Unit =
            push(new Node(p))
            drain()

        def regions(stack: Stack): Unit =
            val n = stack.depth
            @tailrec def loop(i: Int): Unit =
                if i >= 0 then
                    if full then dropped += i + 1
                    else
                        region(stack.handler(i).tag)
                        push(stack.continuation(i))
                        drain()
                        loop(i - 1)
            loop(n - 1)
        end regions

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
                            case s: Kyo.Suspend[?, ?, ?, ?] =>

                                frame(s.frame)
                                push(s.cont)
                            case s: Kyo.Snapshot[?, ?] =>
                                frame(s.frame)
                                push(s.cont)
                            case h: Kyo.Handle[?, ?, ?, ?, ?, ?] =>

                                push(h.cont)
                                push(new Region(h.handler.tag))
                                pushValue(h.value)
                            case d: Kyo.Defer[?, ?, ?, ?] =>
                                push(d.contB)
                                push(d.contA)
                                pushValue(d.value)
                            case p: Kyo.Park[?, ?] =>

                                val entries = p.entries
                                @tailrec def parked(i: Int): Unit =
                                    if i < entries.regions then
                                        push(entries.continuation(i))
                                        push(new Region(entries.handler(i).tag))
                                        parked(i + 1)
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
