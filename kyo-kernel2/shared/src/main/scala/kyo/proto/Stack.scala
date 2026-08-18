package kyo.proto

import java.util.Arrays
import kyo.Span
import kyo.Tag

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

    def push(f: Arrow[?, ?, ?]): Unit =
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
        while top > n do
            top -= 1
            entries(top) = null
            handlers(top) = null
            states(top) = null

    def find(t: Tag[Any]): Int =
        var i = top - 1
        while i >= 0 do
            val h = handlers(i)
            if (h ne null) && t <:< h.tag.erased then return i
            i -= 1
        -1
    end find

    def copyEntries(from: Int): Span[Arrow[?, ?, ?]] =
        Span.fromUnsafe(Arrays.copyOfRange(entries, from, top))

    def copyHandlers(from: Int): Span[Kyo.Handler[?, ?, ?, ?]] =
        Span.fromUnsafe(Arrays.copyOfRange(handlers, from, top))

    def copyStates(from: Int): Span[Any] =
        Span.fromUnsafe(Arrays.copyOfRange(states, from, top))

    def pushAll(entries2: Span[Arrow[?, ?, ?]], handlers2: Span[Kyo.Handler[?, ?, ?, ?]], states2: Span[Any]): Unit =
        val n = entries2.size
        var i = 0
        while i < n do
            if top == entries.length then grow()
            entries(top) = entries2(i)
            handlers(top) = handlers2(i)
            states(top) = states2(i).asInstanceOf[AnyRef]
            top += 1
            i += 1
        end while
    end pushAll

    private def grow(): Unit =
        entries = Arrays.copyOf(entries, top * 2)
        handlers = Arrays.copyOf(handlers, top * 2)
        states = Arrays.copyOf(states, top * 2)
    end grow

end Stack
