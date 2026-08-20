package kyo.kernel.internal

import kyo.Arrow
import kyo.Maybe
import kyo.Maybe.*
import kyo.Tag
import kyo.bug
import scala.annotation.static
import scala.annotation.tailrec

private[kyo] final class Stack:
    private var entries = new Array[Arrow[?, ?, ?]](16)
    private var states  = Array.fill[Maybe[Any]](16)(Absent)
    private var mask    = 15
    private var head    = 0
    private var tail    = 0
    private val reach   = Safepoint.period() / 2

    def isEmpty: Boolean = head == tail

    def size: Int = tail - head

    private def put(idx: Int, f: Arrow[?, ?, ?]): Unit =
        entries(idx) = f
        f match
            case f: Handler.HandlerLoopState[?, ?, ?, ?, ?, ?, ?] =>
                states(idx) = Present(states(idx).getOrElse(f.initialState))
            case _ =>
                ()
        end match
    end put

    def push(f: Arrow[?, ?, ?]): Unit =
        f match
            case f if f eq Arrow.Id =>
                ()
            case f: Arrow.Chain[?, ?, ?, ?] =>
                val n = count(f, 0)
                ensure(n)
                head -= n
                fill(f, 0)
            case f =>
                ensure(1)
                head -= 1
                put(head & mask, f)

    @tailrec private def count(f: Arrow[?, ?, ?], n: Int): Int =
        f match
            case c: Arrow.Chain[?, ?, ?, ?] => count(c.b, if c.a eq Arrow.Id then n else n + 1)
            case f if f eq Arrow.Id         => n
            case _                          => n + 1

    @tailrec private def fill(f: Arrow[?, ?, ?], i: Int): Unit =
        f match
            case c: Arrow.Chain[?, ?, ?, ?] =>
                if c.a eq Arrow.Id then
                    fill(c.b, i)
                else
                    put((head + i) & mask, c.a)
                    fill(c.b, i + 1)
            case f if f eq Arrow.Id =>
                ()
            case f =>
                put((head + i) & mask, f)

    def pop(): Arrow[?, ?, ?] =
        val i = head & mask
        val e = entries(i)
        entries(i) = null
        states(i) = Absent
        head += 1
        e
    end pop

    def state[A](i: Int): Maybe[A] =
        states((head + i) & mask).asInstanceOf[Maybe[A]]

    def putState[A](i: Int, value: A): Unit =
        states((head + i) & mask) = Present(value)

    def handler(i: Int): Handler[?, ?, ?, ?] =
        entries((head + i) & mask).asInstanceOf[Handler[?, ?, ?, ?]]

    // an indexed read of a slot without knowing its kind, which the trace sweep needs and neither
    // handler(i) nor state(i) can serve. The indexing is copied from both so a change to the ring
    // buffer breaks or fixes the three together
    private[kernel] def entry(i: Int): Arrow[?, ?, ?] = entries((head + i) & mask)

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

    def dump[A, B, S](pos: Int): Arrow[A, B, S] = dump(pos, true)

    private def dump[A, B, S](pos: Int, wrap: Boolean): Arrow[A, B, S] =
        @tailrec def loop(i: Int, acc: Arrow[Any, Any, Any], handlers: Boolean): Arrow[Any, Any, Any] =
            if i < 0 then acc
            else
                val idx = (head + i) & mask
                val e =
                    entries(idx) match
                        case h: Handler.HandlerLoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                            states(idx).fold(h)(Handler.HandlerLoopState(h, _))
                        case e => e
                entries(idx) = null
                states(idx) = Absent
                val below =
                    acc match
                        case c: Arrow.Chain[?, ?, ?, ?] if wrap && !handlers && !(c.b eq Arrow.Id) => new Arrow.Chain(c, Arrow.id)
                        case _                                                                     => acc
                val link =
                    e match
                        case c: Arrow.Chain[?, ?, ?, ?] if below eq Arrow.Id => new Arrow.Chain(c, Arrow.id)
                        case _                                               => e.chain(below)
                loop(i - 1, link.asInstanceOf[Arrow[Any, Any, Any]], handlers || e.isInstanceOf[Handler[?, ?, ?, ?]])
        val k = loop(pos - 1, Arrow.id, false)
        head += pos
        k.asInstanceOf[Arrow[A, B, S]]
    end dump

    def dump[A, B, S](): Arrow[A, B, S] =
        @tailrec def boundary(i: Int): Int =
            if i == size || i == reach || entries((head + i) & mask).isInstanceOf[Handler[?, ?, ?, ?]] then i
            else boundary(i + 1)
        dump[A, B, S](boundary(0), false)
    end dump

    def truncate(n: Int): Unit =
        @tailrec def loop(n: Int): Unit =
            if n > 0 && head != tail then
                entries(head & mask) = null
                states(head & mask) = Absent
                head += 1
                loop(n - 1)
        loop(n)
    end truncate

    def clear(): Unit =
        truncate(size)
        head = 0
        tail = 0
    end clear

    private def ensure(n: Int): Unit =
        if size + n > entries.length then
            val s   = size
            var cap = entries.length
            while s + n > cap do cap <<= 1
            val arr = new Array[Arrow[?, ?, ?]](cap)
            val sts = Array.fill[Maybe[Any]](cap)(Absent)
            var i   = 0
            while i < s do
                val idx = (head + i) & mask
                arr(i) = entries(idx)
                sts(i) = states(idx)
                i += 1
            end while
            entries = arr
            states = sts
            mask = cap - 1
            head = 0
            tail = s
end Stack

private[kyo] object Stack:
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
