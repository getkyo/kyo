package kyo.proto

import kyo.Maybe
import kyo.Maybe.*
import kyo.Tag
import kyo.bug
import scala.annotation.static
import scala.annotation.tailrec

/** A stack of arrows backed by a circular buffer.
  *
  * Entries occupy the logical positions `[head, tail)` of the ring: `head` is the top of the stack (the next arrow to pop/run) and
  * `tail - 1` the bottom. Pushing prepends at `head`, so iterating forward from `head` walks the stack top-down, which keeps `find`
  * and `dump` as forward scans. The capacity is always a power of two so a logical position maps to a slot with `pos & mask`, which
  * remains correct for negative positions.
  *
  * Positions exposed by `find`, `handler`, and `dump` are depths relative to the top: 0 is the top entry, `size - 1` the bottom.
  */
final class Stack:
    private var entries = new Array[Arrow[?, ?, ?]](16)
    private var mask    = 15
    private var head    = 0
    private var tail    = 0

    def isEmpty: Boolean = head == tail

    def size: Int = tail - head

    def push(f: Arrow[?, ?, ?]): Unit =
        f match
            case c: Arrow.Chain[?, ?, ?, ?] =>
                push(c.b)
                push(c.a)
            case f if f eq Arrow.Id => ()
            case f =>
                grow()
                head -= 1
                entries(head & mask) = f

    def pop(): Arrow[?, ?, ?] =
        val i = head & mask
        val e = entries(i)
        entries(i) = null
        head += 1
        e
    end pop

    def handler(i: Int): Handler[?, ?, ?, ?] =
        entries((head + i) & mask).asInstanceOf[Handler[?, ?, ?, ?]]

    // depth of the nearest enclosing handler for the tag, -1 if none
    def find[A](t: Tag[A]): Int =
        val n = size
        @tailrec def loop(i: Int): Int =
            if i == n then -1
            else
                entries((head + i) & mask) match
                    case h: Handler[?, ?, ?, ?] if t <:< h.tag => i
                    case _                                     => loop(i + 1)
        loop(0)
    end find

    // dump up to pos but keep pos
    def dump[A, B, S](pos: Int): Arrow[A, B, S] =
        @tailrec def loop(i: Int, acc: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
            if i < 0 then acc
            else
                val idx = (head + i) & mask
                val e   = entries(idx)
                entries(idx) = null
                loop(i - 1, e.chain(acc).asInstanceOf[Arrow[Any, Any, Any]])
        val k = loop(pos - 1, Arrow.id)
        head += pos
        k.asInstanceOf[Arrow[A, B, S]]
    end dump

    // dump while not a handler
    def dump[A, B, S](): Arrow[A, B, S] =
        @tailrec def boundary(i: Int): Int =
            if i == size || entries((head + i) & mask).isInstanceOf[Handler[?, ?, ?, ?]] then i
            else boundary(i + 1)
        dump[A, B, S](boundary(0))
    end dump

    // drop n entries starting from the head
    def truncate(n: Int): Unit =
        @tailrec def loop(n: Int): Unit =
            if n > 0 && head != tail then
                entries(head & mask) = null
                head += 1
                loop(n - 1)
        loop(n)
    end truncate

    def clear(): Unit =
        truncate(size)
        head = 0
        tail = 0
    end clear

    private def grow(): Unit =
        if size == entries.length then
            val n   = size
            val arr = new Array[Arrow[?, ?, ?]](n << 1)
            var i   = 0
            while i < n do
                arr(i) = entries((head + i) & mask)
                i += 1
            entries = arr
            mask = arr.length - 1
            head = 0
            tail = n
end Stack

/** A pool of stacks per thread: an evaluation borrows one and returns it empty, so a nested evaluation gets its own stack and never
  * sees the entries of the one it runs inside, and no stack is allocated per evaluation once the pool is warm.
  */
object Stack:
    final private class Pool:
        private var free = new Array[Stack](4)
        private var size = 0

        def borrow(): Stack =
            if size == 0 then new Stack
            else
                size -= 1
                val s = free(size)
                free(size) = null
                s

        def release(s: Stack): Unit =
            s.clear()
            if size == free.length then free = Array.copyOf(free, size * 2)
            free(size) = s
            size += 1
        end release
    end Pool

    @static private val local: ThreadLocal[Pool] =
        new ThreadLocal[Pool]:
            override def initialValue() = new Pool

    def borrow(): Stack = local.get().borrow()

    def release(s: Stack): Unit = local.get().release(s)
end Stack
