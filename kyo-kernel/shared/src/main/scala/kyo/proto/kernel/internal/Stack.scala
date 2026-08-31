package kyo.proto.kernel.internal

import kyo.proto.Arrow
import kyo.proto.kernel.Effect
import scala.annotation.tailrec

final private[kernel] class Stack:

    private var handlers      = Stack.noHandlers
    private var states        = Stack.noStates
    private var contexts      = Stack.noContexts
    private var continuations = Stack.noContinuations
    private var size          = 0

    def isEmpty: Boolean = size == 0

    def push[E <: Effect, A, B, S, State](
        handler: Handler[E, A, B, S, State],
        state: State,
        ctx: Context,
        cont: Arrow[B, Any, S]
    ): Unit =
        if size == handlers.length then grow()
        handlers(size) = handler
        states(size) = state
        contexts(size) = ctx
        continuations(size) = cont
        size += 1
    end push

    def pop(): Unit = size -= 1

    def clear(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < size then
                handlers(i) = null
                states(i) = null

                contexts(i) = Context.empty
                continuations(i) = null
                loop(i + 1)
        loop(0)
        size = 0
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
                contexts(i) = Context.empty
                continuations(i) = null
                loop(i + 1)
        loop(0)
        size = 0
        out
    end snapshot

    def depth: Int                                = size
    def handlerAt(i: Int): Handler[?, ?, ?, ?, ?] = handlers(i)
    def continuationAt(i: Int): Arrow[?, ?, ?]    = continuations(i)

    def handler: Handler[?, ?, ?, ?, ?] = handlers(size - 1)
    def state: Any                      = states(size - 1)
    def state_=(value: Any): Unit       = states(size - 1) = value
    def ctx: Context                    = contexts(size - 1)
    def cont: Arrow[?, ?, ?]            = continuations(size - 1)

    private def grow(): Unit =
        val capacity           = if size == 0 then 8 else size * 2
        val grownHandlers      = new Array[Handler[?, ?, ?, ?, ?]](capacity)
        val grownStates        = new Array[Any](capacity)
        val grownContexts      = new Array[Context](capacity)
        val grownContinuations = new Array[Arrow[?, ?, ?]](capacity)
        Array.copy(handlers, 0, grownHandlers, 0, size)
        Array.copy(states, 0, grownStates, 0, size)
        Array.copy(contexts, 0, grownContexts, 0, size)
        Array.copy(continuations, 0, grownContinuations, 0, size)
        handlers = grownHandlers
        states = grownStates
        contexts = grownContexts
        continuations = grownContinuations
    end grow
end Stack

private[kernel] object Stack:

    private val noHandlers      = new Array[Handler[?, ?, ?, ?, ?]](0)
    private val noStates        = new Array[Any](0)
    private val noContexts      = new Array[Context](0)
    private val noContinuations = new Array[Arrow[?, ?, ?]](0)

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
