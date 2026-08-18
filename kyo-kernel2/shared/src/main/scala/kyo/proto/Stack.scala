package kyo.proto

import java.util.Arrays
import kyo.Span
import kyo.Tag
import scala.annotation.static
import scala.annotation.tailrec

/** The evaluator's stack, booking only: continuations, and beside a region's continuation its handler as the marker and its state. */
final private[proto] class Stack:

    private var entries  = new Array[Arrow[?, ?, ?]](32)
    private var handlers = new Array[Kyo.Handler[?, ?, ?, ?]](32)
    private var states   = new Array[AnyRef](32)
    private var top      = 0

    def isEmpty: Boolean = top == 0

    def size: Int = top

    def apply(i: Int): Arrow[Any, Any, Any] = entries(i).asInstanceOf[Arrow[Any, Any, Any]]

    def marked(i: Int): Boolean = handlers(i) ne null

    def handler(i: Int): Kyo.Handler[Nothing, Any, Any, Any] = handlers(i).asInstanceOf[Kyo.Handler[Nothing, Any, Any, Any]]

    def state(i: Int): Any = states(i)

    def setState(i: Int, s: Any): Unit = states(i) = s.asInstanceOf[AnyRef]

    // the identity continuation is not stored: applying it is the value itself
    def push(f: Arrow[?, ?, ?]): Unit =
        if f ne Arrow.Id then
            if top == entries.length then grow()
            entries(top) = f
            top += 1
    end push

    def push(f: Arrow[?, ?, ?], h: Kyo.Handler[?, ?, ?, ?], s: Any): Unit =
        if top == entries.length then grow()
        entries(top) = f
        handlers(top) = h
        states(top) = s.asInstanceOf[AnyRef]
        top += 1
    end push

    def pop(): Arrow[Any, Any, Any] =
        top -= 1
        val f = entries(top)
        entries(top) = null
        handlers(top) = null
        states(top) = null
        f.asInstanceOf[Arrow[Any, Any, Any]]
    end pop

    def truncate(n: Int): Unit =
        @tailrec def loop(): Unit =
            if top > n then
                top -= 1
                entries(top) = null
                handlers(top) = null
                states(top) = null
                loop()
        loop()
    end truncate

    /** The innermost region at or above `base` answering `t`, or -1: an evaluation never sees the regions of the one it is nested in. */
    def find(t: Tag[Any], base: Int): Int =
        @tailrec def loop(i: Int): Int =
            if i < base then -1
            else
                val h = handlers(i)
                if (h ne null) && t <:< h.tag.erased then i
                else loop(i - 1)
        loop(top - 1)
    end find

    def copyEntries(from: Int): Span[Arrow[?, ?, ?]] =
        Span.fromUnsafe(Arrays.copyOfRange(entries, from, top))

    def copyHandlers(from: Int): Span[Kyo.Handler[?, ?, ?, ?]] =
        Span.fromUnsafe(Arrays.copyOfRange(handlers, from, top))

    def copyStates(from: Int): Span[Any] =
        Span.fromUnsafe(Arrays.copyOfRange(states, from, top))

    def pushAll(entries2: Span[Arrow[?, ?, ?]], handlers2: Span[Kyo.Handler[?, ?, ?, ?]], states2: Span[Any]): Unit =
        val n = entries2.size
        @tailrec def loop(i: Int): Unit =
            if i < n then
                if top == entries.length then grow()
                entries(top) = entries2(i)
                handlers(top) = handlers2(i)
                states(top) = states2(i).asInstanceOf[AnyRef]
                top += 1
                loop(i + 1)
        loop(0)
    end pushAll

    private def grow(): Unit =
        entries = Arrays.copyOf(entries, top * 2)
        handlers = Arrays.copyOf(handlers, top * 2)
        states = Arrays.copyOf(states, top * 2)
    end grow

end Stack

private[proto] object Stack:

    @static private val local: ThreadLocal[Stack] =
        new ThreadLocal[Stack]:
            override def initialValue() = new Stack

    def current(): Stack = local.get()

end Stack
