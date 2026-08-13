package kyo.kernel3.internal

import java.util.Arrays
import kyo.Tag
import kyo.kernel3.*
import scala.annotation.tailrec

final private[internal] class Stack:

    private var frames = new Array[AnyRef](32).asInstanceOf[Array[Stack.Entry]]
    private var tags   = new Array[AnyRef](32)
    private var top    = 0

    def size: Int = top

    def apply(i: Int): Stack.Entry = frames(i)

    def push(f: Stack.Entry): Unit =
        if top == frames.length then grow()
        frames(top) = f
        top += 1
    end push

    def push(f: Stack.Entry, t: Tag[Any]): Unit =
        if top == frames.length then grow()
        frames(top) = f
        tags(top) = t.asInstanceOf[AnyRef]
        top += 1
    end push

    def pop(): Stack.Entry =
        top -= 1
        val f = frames(top)
        frames(top) = null
        tags(top) = null
        f
    end pop

    def truncate(n: Int): Unit =
        while top > n do
            top -= 1
            frames(top) = null
            tags(top) = null

    def copyFrom(i: Int): Array[Stack.Entry] =
        Arrays.copyOfRange(frames.asInstanceOf[Array[AnyRef]], i, top).asInstanceOf[Array[Stack.Entry]]

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
        frames = Arrays.copyOf(frames.asInstanceOf[Array[AnyRef]], top * 2).asInstanceOf[Array[Stack.Entry]]
        tags = Arrays.copyOf(tags, top * 2)

end Stack

private[internal] object Stack:

    type Entry = Arrow[?, ?, ?] | Kyo[?, ?]

    private val local: ThreadLocal[Stack] =
        new ThreadLocal[Stack]:
            override def initialValue() = new Stack

    def current(): Stack = local.get()

end Stack
