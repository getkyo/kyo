package kyo.kernel.internal

import kyo.Chunk
import kyo.Span
import kyo.kernel.Arrow
import kyo.kernel.Effect
import scala.annotation.tailrec

/** The regions installed around the computation the evaluator is running, innermost last.
  *
  * Four parallel arrays indexed by depth rather than one array of region objects: the handler, its state, the continuation for its result,
  * and the snapshots it owes. Pushing a region then writes four slots and allocates nothing, which matters because a region is pushed and
  * popped for every handled computation.
  *
  * The stack is mutable and borrowed from a per-thread pool for one evaluation, then cleared and returned. Nothing that leaves the evaluator
  * points at it: what escapes is a [[Stack.Snapshot]], an immutable copy.
  */
final private[kernel] class Stack:

    private var handlers      = new Array[Handler[?, ?, ?]](0)
    private var states        = new Array[Any](0)
    private var continuations = new Array[Arrow[?, ?, ?]](0)
    private var size          = 0

    // What each region still has to discharge on behalf of regions dumped out from above it, indexed the same way.
    private var owed = new Array[Chunk[Stack.Snapshot]](0)

    // The same debt for what sits below depth 0, discharged by the evaluation itself rather than by a region.
    private var evalOwed: Chunk[Stack.Snapshot] = Chunk.empty

    var sink: Any = null

    private var epochCount = 0

    /** Bumped each time the stack is cleared for reuse.
      *
      * The object itself is recycled through the pool, so a value that held on to a stack compares the epoch it recorded against this one to
      * find out whether what it saw is still the same evaluation.
      */
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

    def pop(): Unit =
        size -= 1
        // `clear` reaches only `0 until size`, and a released stack goes back to a per-thread pool on a live
        // worker, so a slot left set keeps that region's handler, state and continuation reachable for as long
        // as the worker lives. `owed` is deliberately not cleared: `takePopped` reads this index straight after
        // the decrement.
        handlers(size) = null
        states(size) = null
        continuations(size) = null
    end pop

    // A flag rather than a scan: `settle` runs on a path where there is usually no debt at all, and this lets it
    // return without touching the lanes.
    private var owes = false

    def owesAny: Boolean = owes

    def takePopped(): Chunk[Stack.Snapshot] = takeOwed(size)

    def takeOwed(i: Int): Chunk[Stack.Snapshot] =
        val owedHere = owed(i)
        if !owedHere.isEmpty then owed(i) = Chunk.empty
        owedHere
    end takeOwed

    /** Records that region `i` owes the obligations in `snapshots`.
      *
      * A region dumped out of the live stack still has obligations to discharge, a bracket's release among them, and they cannot be
      * discharged where it was taken from, because the continuation holding it may yet be resumed. The debt moves to a region that is still
      * installed, which discharges it when its own extent ends.
      */
    def owe(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            owes = true
            owed(i) = owed(i).concat(snapshots)

    /** [[owe]] against the region below `i`, or against the evaluation itself when `i` is the outermost. */
    def oweBelow(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            owes = true
            if i == 0 then evalOwed = evalOwed.concat(snapshots)
            else owed(i - 1) = owed(i - 1).concat(snapshots)

    /** Cancels one snapshot's debt, in the innermost lane recording it, because those regions ended on their own. */
    def settle(snapshot: Stack.Snapshot): Unit =
        if owes then
            @tailrec def loop(i: Int): Unit =
                if i < 0 then evalOwed = settleIn(evalOwed, snapshot)
                else
                    val lane    = owed(i)
                    val settled = settleIn(lane, snapshot)
                    if settled ne lane then owed(i) = settled
                    else loop(i - 1)
            loop(size - 1)
    end settle

    private def settleIn(lane: Chunk[Stack.Snapshot], snapshot: Stack.Snapshot): Chunk[Stack.Snapshot] =
        if lane.isEmpty then lane
        else
            val indexed = lane.toIndexed
            @tailrec def loop(j: Int): Chunk[Stack.Snapshot] =
                if j < 0 then lane
                else if indexed(j).asInstanceOf[AnyRef] eq snapshot.asInstanceOf[AnyRef] then
                    if indexed.length == 1 then Chunk.empty
                    else indexed.take(j).concat(indexed.drop(j + 1))
                else loop(j - 1)
            loop(indexed.length - 1)
    end settleIn

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
        sink = null
        evalOwed = Chunk.empty
        owes = false
        epochCount += 1
    end clear

    /** Takes every region off the stack as a snapshot, leaving it empty. What a computation carries across an execution boundary. */
    def takeAll(): Stack.Snapshot =
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
    end takeAll

    /** Copies the context regions alone, leaving the stack untouched.
      *
      * A fork inherits bindings but not the handlers around the parent, so the copy keeps the context handlers and drops the rest. The
      * continuation slot is [[Arrow.id]] for each: these regions are being reinstalled, not resumed into.
      */
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

    /** The depth of the innermost region answering `tag`, or -1 when nothing does.
      *
      * Walking inward-out is what makes an inner handler shadow an outer one for the same effect, and the match is on tag subtyping so a
      * handler for a supertype answers an operation of a subtype.
      */
    def find[E <: Effect](tag: kyo.Tag[E]): Int =
        @tailrec def loop(i: Int): Int =
            if i < 0 then -1
            else if handlers(i).tag.erased <:< tag.erased then i
            else loop(i - 1)
        loop(size - 1)
    end find

    /** Takes the regions from `from` upward off the live stack, returning them as a snapshot the region below then owes.
      *
      * This is what puts the regions sitting between a handler and a suspension into the continuation the clause receives: they stop being
      * installed, so the clause runs outside them, and they are reinstalled if the continuation is resumed. The debt recorded below is what
      * discharges them should it never be.
      */
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
        owes = true
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

    /** Regions captured out of a stack, in the order it held them.
      *
      * Flat: four slots per region, the same handler, state, continuation and owed that the stack keeps in parallel arrays. A `Span[AnyRef]`
      * rather than an array of region objects, so capturing a stack costs one object whatever its depth.
      *
      * A snapshot is immutable and complete, which is what lets the same one be reinstalled on another thread, or more than once.
      */
    opaque type Snapshot = Span[AnyRef]

    private def wrap(entries: Array[AnyRef]): Snapshot = Span.fromUnsafe(entries)

    object Snapshot:
        private[kernel] val empty: Snapshot = Span.fromUnsafe(new Array[AnyRef](0))

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

    /** A per-thread free list of stacks.
      *
      * An evaluation borrows one and releases it when it ends, so the arrays are reused across evaluations on the same thread rather than
      * allocated per run. Release clears the stack, which is also what bumps its epoch.
      */
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
