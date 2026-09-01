package kyo.proto.kernel.internal

import kyo.Chunk
import kyo.Span
import kyo.proto.Arrow
import kyo.proto.kernel.Effect
import scala.annotation.tailrec

final private[kernel] class Stack:

    private var handlers      = new Array[Handler[?, ?, ?]](0)
    private var states        = new Array[Any](0)
    private var continuations = new Array[Arrow[?, ?, ?]](0)
    private var size          = 0

    // What each entry owes: the snapshots its dumps produced, newest last. Every slot
    // holds a real chunk, empty by default; the sites that null the other arrays reset
    // these to empty, so no slot is ever null.
    private var owed = new Array[Chunk[Stack.Snapshot]](0)

    // What the eval itself owes: dumps whose owner dissolved with nothing below. Lives
    // here rather than as an eval local so the nested eval functions do not lift it into
    // a per-eval box; the same reset protocol as the owed slots keeps the pool clean.
    private var evalOwed: Chunk[Stack.Snapshot] = Chunk.empty

    // Written on every LoopHandler dispatch so the clause outcome escapes and is
    // never read back: C2's scalar replacement of the outcome inside the eval loop
    // compiles to code that roughly doubles the settled LoopHandler benchmark rows,
    // and a store into the pooled stack is one it cannot elide.
    var scratch: Any = null

    def isEmpty: Boolean = size == 0

    def push[E <: Effect, B, S](
        handler: Handler[E, B, S],
        state: Any,
        cont: Arrow[B, Any, S]
    ): Unit =
        if size == handlers.length then grow()
        handlers(size) = handler
        states(size) = state
        continuations(size) = cont
        size += 1
    end push

    // Popping hands back what the entry owes, so no exit site can forget to drain it and
    // no stale list can survive into the slot's next occupant.
    def pop(): Chunk[Stack.Snapshot] =
        size -= 1
        takeOwed(size)

    def owedOf(i: Int): Chunk[Stack.Snapshot] = owed(i)

    def takeOwed(i: Int): Chunk[Stack.Snapshot] =
        val l = owed(i)
        if !l.isEmpty then owed(i) = Chunk.empty
        l
    end takeOwed

    def owe(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then owed(i) = owed(i).concat(snapshots)

    // Re-homes what a dissolving extent owes to the entry directly below it, or to the
    // eval itself when nothing is below: everything the dissolved extent can reach lives
    // inside the extent below.
    def oweBelow(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            if i == 0 then evalOwed = evalOwed.concat(snapshots)
            else owed(i - 1) = owed(i - 1).concat(snapshots)

    def takeEvalOwed(): Chunk[Stack.Snapshot] =
        val l = evalOwed
        if !l.isEmpty then evalOwed = Chunk.empty
        l
    end takeEvalOwed

    def clear(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < size then
                handlers(i) = null
                states(i) = null

                continuations(i) = null
                owed(i) = Chunk.empty
                loop(i + 1)
        loop(0)
        size = 0
        scratch = null
        evalOwed = Chunk.empty
    end clear

    def snapshot(): Stack.Snapshot =
        val out = new Array[AnyRef](size * 4)
        @tailrec def loop(i: Int): Unit =
            if i < size then
                out(i * 4) = handlers(i)
                out(i * 4 + 1) = states(i).asInstanceOf[AnyRef]
                out(i * 4 + 2) = continuations(i)
                out(i * 4 + 3) = takeOwed(i)
                handlers(i) = null
                states(i) = null
                continuations(i) = null
                loop(i + 1)
        loop(0)
        size = 0
        Stack.wrap(out)
    end snapshot

    // Reads the contextual regions in scope as Park currency without consuming the stack:
    // bindings only, the continuation slot of every entry is identity. Owed slots stay
    // behind: bindings fork, obligations do not.
    def contextual(): Stack.Snapshot =
        var count = 0
        var i     = 0
        while i < size do
            if handlers(i).isInstanceOf[Handler.ContextHandler[?, ?, ?, ?]] then count += 1
            i += 1
        val out = new Array[AnyRef](count * 4)
        var j   = 0
        i = 0
        while i < size do
            if handlers(i).isInstanceOf[Handler.ContextHandler[?, ?, ?, ?]] then
                out(j) = handlers(i)
                out(j + 1) = states(i).asInstanceOf[AnyRef]
                out(j + 2) = Arrow.id[Any]
                out(j + 3) = Chunk.empty
                j += 4
            end if
            i += 1
        end while
        Stack.wrap(out)
    end contextual

    def depth: Int                           = size
    def handler(i: Int): Handler[?, ?, ?]    = handlers(i)
    def state(i: Int): Any                   = states(i)
    def setState(i: Int, value: Any): Unit   = states(i) = value
    def continuation(i: Int): Arrow[?, ?, ?] = continuations(i)

    def truncate(to: Int): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < size then
                handlers(i) = null
                states(i) = null
                continuations(i) = null
                owed(i) = Chunk.empty
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

    // Every dump is owed by the entry directly below the dumped run: the capture is that
    // entry's currency, so its exit bounds every resumption. The attachment lives here so
    // no caller can produce an unowed dump.
    def dump(from: Int): Stack.Snapshot =
        val count = size - from
        val out   = new Array[AnyRef](count * 4)
        @tailrec def loop(i: Int): Unit =
            if i < count then
                val j = from + i
                out(i * 4) = handlers(j)
                out(i * 4 + 1) = states(j).asInstanceOf[AnyRef]
                out(i * 4 + 2) = continuations(j)
                out(i * 4 + 3) = takeOwed(j)
                handlers(j) = null
                states(j) = null
                continuations(j) = null
                loop(i + 1)
        loop(0)
        size = from
        val snapshot = Stack.wrap(out)
        owed(from - 1) = owed(from - 1).append(snapshot)
        snapshot
    end dump

    private def grow(): Unit =
        val capacity           = if size == 0 then 8 else size * 2
        val grownHandlers      = new Array[Handler[?, ?, ?]](capacity)
        val grownStates        = new Array[Any](capacity)
        val grownContinuations = new Array[Arrow[?, ?, ?]](capacity)
        Array.copy(handlers, 0, grownHandlers, 0, size)
        Array.copy(states, 0, grownStates, 0, size)
        Array.copy(continuations, 0, grownContinuations, 0, size)
        handlers = grownHandlers
        states = grownStates
        continuations = grownContinuations
        val grownOwed = new Array[Chunk[Stack.Snapshot]](capacity)
        Array.copy(owed, 0, grownOwed, 0, size)
        @tailrec def fill(i: Int): Unit =
            if i < capacity then
                grownOwed(i) = Chunk.empty
                fill(i + 1)
        fill(size)
        owed = grownOwed
    end grow
end Stack

private[kernel] object Stack:

    // A reified run of stack regions as [handler, state, continuation, owed] quadruples,
    // region 0 outermost. Immutable once built; the one home for that layout: producers
    // and consumers go through the accessors, never the raw representation.
    opaque type Snapshot = Span[AnyRef]

    private def wrap(entries: Array[AnyRef]): Snapshot = Span.fromUnsafe(entries)

    object Snapshot:
        private[kernel] val empty: Snapshot = Span.fromUnsafe(new Array[AnyRef](0))

        // Builds a snapshot region by region, keeping the layout here. Built snapshots
        // carry bindings, not resumptions or obligations: every continuation slot is
        // identity and every owed slot is empty.
        final private[kernel] class Builder(regions: Int):
            private val entries = new Array[AnyRef](regions * 4)
            private var count   = 0

            def add(handler: Handler[?, ?, ?], state: Any): Unit =
                entries(count) = handler
                entries(count + 1) = state.asInstanceOf[AnyRef]
                entries(count + 2) = Arrow.id[Any]
                entries(count + 3) = Chunk.empty
                count += 4
            end add

            def result(): Snapshot =
                if count == entries.length then Span.fromUnsafe(entries)
                else Span.fromUnsafe(java.util.Arrays.copyOf(entries, count))
        end Builder
    end Snapshot

    extension (self: Snapshot)
        def regions: Int                         = self.size / 4
        def isEmpty: Boolean                     = self.size == 0
        def handler(i: Int): Handler[?, ?, ?]    = self(i * 4).asInstanceOf[Handler[?, ?, ?]]
        def state(i: Int): Any                   = self(i * 4 + 1)
        def continuation(i: Int): Arrow[?, ?, ?] = self(i * 4 + 2).asInstanceOf[Arrow[?, ?, ?]]
        def owed(i: Int): Chunk[Snapshot]        = self(i * 4 + 3).asInstanceOf[Chunk[Snapshot]]

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
