package kyo.kernel.proto

import java.util.Arrays
import kyo.Span
import kyo.Tag
import scala.annotation.static
import scala.annotation.tailrec

final private[proto] class Stack:

    private var entries = new Array[Arrow[?, ?, ?]](32)
    private var tags    = new Array[AnyRef](32)
    private var states  = new Array[AnyRef](32)
    private var top     = 0

    def size: Int = top

    def apply(i: Int): Arrow[Any, Any, Any] = entries(i).asInstanceOf[Arrow[Any, Any, Any]]

    def marked(i: Int): Boolean = tags(i) ne null

    def state(i: Int): Any = states(i)

    def setState(i: Int, s: Any): Unit = states(i) = s.asInstanceOf[AnyRef]

    def push(f: Arrow[?, ?, ?]): Unit =
        if top == entries.length then grow()
        entries(top) = f
        top += 1
    end push

    def push(f: Arrow[?, ?, ?], t: Tag[Any]): Unit =
        if top == entries.length then grow()
        entries(top) = f
        tags(top) = t.asInstanceOf[AnyRef]
        top += 1
    end push

    def push(f: Arrow[?, ?, ?], t: Tag[Any], s: Any): Unit =
        if top == entries.length then grow()
        entries(top) = f
        tags(top) = t.asInstanceOf[AnyRef]
        states(top) = s.asInstanceOf[AnyRef]
        top += 1
    end push

    def pop(): Arrow[Any, Any, Any] =
        top -= 1
        val f = entries(top)
        entries(top) = null
        tags(top) = null
        states(top) = null
        f.asInstanceOf[Arrow[Any, Any, Any]]
    end pop

    def truncate(n: Int): Unit =
        while top > n do
            top -= 1
            entries(top) = null
            tags(top) = null
            states(top) = null

    def copyEntries(from: Int): Span[Arrow[?, ?, ?]] =
        Span.fromUnsafe(Arrays.copyOfRange(entries, from, top))

    def copyTags(from: Int): Span[AnyRef] =
        Span.fromUnsafe(Arrays.copyOfRange(tags, from, top))

    def copyStates(from: Int): Span[AnyRef] =
        Span.fromUnsafe(Arrays.copyOfRange(states, from, top))

    def pushAll(entries2: Span[Arrow[?, ?, ?]], tags2: Span[AnyRef], states2: Span[AnyRef]): Unit =
        val n = entries2.size
        var i = 0
        while i < n do
            if top == entries.length then grow()
            entries(top) = entries2(i)
            tags(top) = tags2(i)
            states(top) = states2(i)
            top += 1
            i += 1
        end while
    end pushAll

    def find(t: Tag[Any], base: Int): Int =
        @tailrec def loop(i: Int): Int =
            if i < base then -1
            else
                val tag = tags(i)
                if (tag ne null) && t <:< tag.asInstanceOf[Tag[Any]] then i
                else loop(i - 1)
        loop(top - 1)
    end find

    private def grow(): Unit =
        entries = Arrays.copyOf(entries, top * 2)
        tags = Arrays.copyOf(tags, top * 2)
        states = Arrays.copyOf(states, top * 2)
    end grow

end Stack

private[proto] object Stack:

    @static private val local: ThreadLocal[Stack] =
        new ThreadLocal[Stack]:
            override def initialValue() = new Stack

    @static def current(): Stack = local.get()

end Stack
