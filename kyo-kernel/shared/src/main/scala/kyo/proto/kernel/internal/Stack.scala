package kyo.proto.kernel.internal

import kyo.proto.Arrow
import kyo.proto.kernel.Effect
import scala.annotation.tailrec

final private[kernel] class Stack:

    private var handlers      = new Array[Handler[?, ?, ?, ?, ?]](0)
    private var states        = new Array[Any](0)
    private var continuations = new Array[Arrow[?, ?, ?]](0)
    private var size          = 0

    // Written on every HandlerLoop dispatch so the clause outcome escapes and is
    // never read back: C2's scalar replacement of the outcome inside the eval loop
    // compiles to code that roughly doubles the settled HandlerLoop benchmark rows,
    // and a store into the pooled stack is one it cannot elide.
    var scratch: Any = null

    def isEmpty: Boolean = size == 0

    def push[E <: Effect, A, B, S, State](
        handler: Handler[E, A, B, S, State],
        state: State,
        cont: Arrow[B, Any, S]
    ): Unit =
        if size == handlers.length then grow()
        handlers(size) = handler
        states(size) = state
        continuations(size) = cont
        size += 1
    end push

    def pop(): Unit = size -= 1

    def clear(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < size then
                handlers(i) = null
                states(i) = null

                continuations(i) = null
                loop(i + 1)
        loop(0)
        size = 0
        scratch = null
    end clear

    def snapshot(): Array[AnyRef] =
        val out = new Array[AnyRef](size * 3)
        @tailrec def loop(i: Int): Unit =
            if i < size then
                out(i * 3) = handlers(i)
                out(i * 3 + 1) = states(i).asInstanceOf[AnyRef]
                out(i * 3 + 2) = continuations(i)
                handlers(i) = null
                states(i) = null
                continuations(i) = null
                loop(i + 1)
        loop(0)
        size = 0
        out
    end snapshot

    def depth: Int                              = size
    def handler(i: Int): Handler[?, ?, ?, ?, ?] = handlers(i)
    def state(i: Int): Any                      = states(i)
    def setState(i: Int, value: Any): Unit      = states(i) = value
    def continuation(i: Int): Arrow[?, ?, ?]    = continuations(i)

    def truncate(to: Int): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < size then
                handlers(i) = null
                states(i) = null
                continuations(i) = null
                loop(i + 1)
        loop(to)
        size = to
    end truncate

    def find[E <: Effect](tag: kyo.Tag[E]): Int =
        @tailrec def loop(i: Int): Int =
            if i < 0 then -1
            else if handlers(i).tag.erased <:< tag.erased then i
            else loop(i - 1)
        loop(size - 1)
    end find

    def dump(from: Int): Array[AnyRef] =
        val count = size - from
        val out   = new Array[AnyRef](count * 3)
        @tailrec def loop(i: Int): Unit =
            if i < count then
                val j = from + i
                out(i * 3) = handlers(j)
                out(i * 3 + 1) = states(j).asInstanceOf[AnyRef]
                out(i * 3 + 2) = continuations(j)
                handlers(j) = null
                states(j) = null
                continuations(j) = null
                loop(i + 1)
        loop(0)
        size = from
        out
    end dump

    private def grow(): Unit =
        val capacity           = if size == 0 then 8 else size * 2
        val grownHandlers      = new Array[Handler[?, ?, ?, ?, ?]](capacity)
        val grownStates        = new Array[Any](capacity)
        val grownContinuations = new Array[Arrow[?, ?, ?]](capacity)
        Array.copy(handlers, 0, grownHandlers, 0, size)
        Array.copy(states, 0, grownStates, 0, size)
        Array.copy(continuations, 0, grownContinuations, 0, size)
        handlers = grownHandlers
        states = grownStates
        continuations = grownContinuations
    end grow
end Stack

private[kernel] object Stack:

    final private class Pool:
        private var free = new Array[Stack](4)
        private var size = 0

        def borrow(): Stack =
            if size == 0 then new Stack
            else
                size -= 1
                val stack = free(size)
                free(size) = null
                stack

        def release(stack: Stack): Unit =
            stack.clear()
            if size == free.length then free = Array.copyOf(free, size * 2)
            free(size) = stack
            size += 1
        end release
    end Pool

    private val pool =
        new ThreadLocal[Pool]:
            override def initialValue() = new Pool

    def borrow(): Stack = pool.get().borrow()

    def release(stack: Stack): Unit = pool.get().release(stack)
end Stack
