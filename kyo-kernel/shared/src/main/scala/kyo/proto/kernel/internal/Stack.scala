package kyo.proto.kernel.internal

import kyo.Span
import kyo.proto.Arrow
import kyo.proto.kernel.Effect
import scala.annotation.tailrec

final private[kernel] class Stack:

    private var handlers      = new Array[Handler[?, ?, ?, ?, ?]](0)
    private var states        = new Array[Any](0)
    private var continuations = new Array[Arrow[?, ?, ?]](0)
    private var size          = 0

    // Written on every LoopHandler dispatch so the clause outcome escapes and is
    // never read back: C2's scalar replacement of the outcome inside the eval loop
    // compiles to code that roughly doubles the settled LoopHandler benchmark rows,
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

    def snapshot(): Stack.Snapshot =
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
        Stack.wrap(out)
    end snapshot

    // Reads the contextual regions in scope as Park currency without consuming the stack:
    // bindings only, the continuation slot of every entry is identity.
    def contextual(): Stack.Snapshot =
        var count = 0
        var i     = 0
        while i < size do
            if handlers(i).isInstanceOf[Handler.ContextHandler[?, ?, ?, ?, ?]] then count += 1
            i += 1
        val out = new Array[AnyRef](count * 3)
        var j   = 0
        i = 0
        while i < size do
            if handlers(i).isInstanceOf[Handler.ContextHandler[?, ?, ?, ?, ?]] then
                out(j) = handlers(i)
                out(j + 1) = states(i).asInstanceOf[AnyRef]
                out(j + 2) = Arrow.id[Any]
                j += 3
            end if
            i += 1
        end while
        Stack.wrap(out)
    end contextual

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

    def dump(from: Int): Stack.Snapshot =
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
        Stack.wrap(out)
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

    // A reified run of stack regions as [handler, state, continuation] triples, region 0
    // outermost. Immutable once built; the one home for that layout: producers and
    // consumers go through the accessors, never the raw representation.
    opaque type Snapshot = Span[AnyRef]

    private def wrap(entries: Array[AnyRef]): Snapshot = Span.fromUnsafe(entries)

    object Snapshot:
        // Builds a snapshot region by region, keeping the layout here. Built snapshots
        // carry bindings, not resumptions: every continuation slot is identity.
        final private[kernel] class Builder(regions: Int):
            private val entries = new Array[AnyRef](regions * 3)
            private var count   = 0

            def add(handler: Handler[?, ?, ?, ?, ?], state: Any): Unit =
                entries(count) = handler
                entries(count + 1) = state.asInstanceOf[AnyRef]
                entries(count + 2) = Arrow.id[Any]
                count += 3
            end add

            def result(): Snapshot =
                if count == entries.length then Span.fromUnsafe(entries)
                else Span.fromUnsafe(java.util.Arrays.copyOf(entries, count))
        end Builder
    end Snapshot

    extension (self: Snapshot)
        def regions: Int                            = self.size / 3
        def isEmpty: Boolean                        = self.size == 0
        def handler(i: Int): Handler[?, ?, ?, ?, ?] = self(i * 3).asInstanceOf[Handler[?, ?, ?, ?, ?]]
        def state(i: Int): Any                      = self(i * 3 + 1)
        def continuation(i: Int): Arrow[?, ?, ?]    = self(i * 3 + 2).asInstanceOf[Arrow[?, ?, ?]]

    end extension

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
