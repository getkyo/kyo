package kyo.proto.kernel.internal

import java.lang.Boolean.FALSE
import java.lang.Boolean.TRUE
import kyo.Chunk
import kyo.proto.kernel.Arrow
import kyo.proto.kernel.Effect
import scala.annotation.tailrec

// TODO please review if all the APIs are still necessary and if we can simplify them. Also check if we can improve naming for clarity
final private[kernel] class Stack:

    private var handlers      = new Array[Handler[?, ?, ?]](0)
    private var states        = new Array[Any](0)
    private var continuations = new Array[Arrow[?, ?, ?]](0)
    private var size          = 0

    private var owed = new Array[Chunk[Stack.Snapshot]](0)

    private var evalOwed: Chunk[Stack.Snapshot] = Chunk.empty

    var scratch: Any = null

    private var epochCount = 0

    def epoch: Int = epochCount

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

    def pop(): Unit = size -= 1

    private var owes = false

    def owesAny: Boolean = owes

    def takePopped(): Chunk[Stack.Snapshot] = takeOwed(size)

    def takeOwed(i: Int): Chunk[Stack.Snapshot] =
        val owedHere = owed(i)
        if !owedHere.isEmpty then owed(i) = Chunk.empty
        owedHere
    end takeOwed

    def owe(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            owes = true
            owed(i) = owed(i).concat(snapshots)

    def oweBelow(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            owes = true
            if i == 0 then evalOwed = evalOwed.concat(snapshots)
            else owed(i - 1) = owed(i - 1).concat(snapshots)

    def takeEvalOwed(): Chunk[Stack.Snapshot] =
        val owedHere = evalOwed
        if !owedHere.isEmpty then evalOwed = Chunk.empty
        owedHere
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
        owes = false
        epochCount += 1
    end clear

    def snapshot(): Stack.Snapshot =
        val out = new Array[AnyRef](size * 4 + 1)
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
        out(size * 4) = FALSE
        size = 0
        Stack.wrap(out)
    end snapshot

    def contextual(): Stack.Snapshot =
        var count = 0
        var i     = 0
        while i < size do
            if handlers(i).isInstanceOf[Handler.ContextHandler[?, ?, ?, ?]] then count += 1
            i += 1
        val out = new Array[AnyRef](count * 4 + 1)
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
        out(j) = FALSE
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

    def findExact[E <: Effect](tag: kyo.Tag[E]): Int =
        @tailrec def loop(i: Int): Int =
            if i < 0 then -1
            else if handlers(i).tag.erased =:= tag.erased then i
            else loop(i - 1)
        loop(size - 1)
    end findExact

    def dump(from: Int): Stack.Snapshot =
        val count = size - from
        val out   = new Array[AnyRef](count * 4 + 1)
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
        out(count * 4) = FALSE
        size = from
        val snapshot = Stack.wrap(out)
        owes = true
        owed(from - 1) = pruned(owed(from - 1)).append(snapshot)
        snapshot
    end dump

    @tailrec private def pruned(lane: Chunk[Stack.Snapshot]): Chunk[Stack.Snapshot] =
        if lane.isEmpty || !lane.last.settled then lane
        else pruned(lane.dropRight(1))

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

    opaque type Snapshot = Array[AnyRef]

    private def wrap(entries: Array[AnyRef]): Snapshot = entries

    object Snapshot:
        private[kernel] val empty: Snapshot = wrap(Array[AnyRef](FALSE))

        final private[kernel] class Builder(regions: Int):
            private val entries = new Array[AnyRef](regions * 4 + 1)
            private var count   = 0

            def add(handler: Handler[?, ?, ?], state: Any): Unit =
                entries(count) = handler
                entries(count + 1) = state.asInstanceOf[AnyRef]
                entries(count + 2) = Arrow.id[Any]
                entries(count + 3) = Chunk.empty
                count += 4
            end add

            def result(): Snapshot =
                val out = if count == entries.length - 1 then entries else java.util.Arrays.copyOf(entries, count + 1)
                out(count) = FALSE
                wrap(out)
        end Builder
    end Snapshot

    extension (self: Snapshot)
        def regions: Int                         = (self.length - 1) / 4
        def isEmpty: Boolean                     = self.length == 1
        def settled: Boolean                     = self(self.length - 1) eq TRUE
        def settle(): Unit                       = self(self.length - 1) = TRUE
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
