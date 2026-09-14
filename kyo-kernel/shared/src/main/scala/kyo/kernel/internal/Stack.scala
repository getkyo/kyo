package kyo.kernel.internal

import kyo.Chunk
import kyo.IsFatal
import kyo.Maybe
import kyo.Span
import kyo.kernel.Arrow
import kyo.kernel.Effect
import scala.annotation.tailrec

/** The regions installed around the computation the evaluator is running, innermost last.
  *
  * Four parallel arrays indexed by depth rather than one array of region objects: the handler, its state, the continuation for its result,
  * and the releases the entry owes. Pushing a region then writes four slots and allocates nothing, which matters because a region is pushed
  * and popped for every handled computation.
  *
  * The stack is mutable and borrowed from a per-thread pool for one evaluation, then cleared and returned. Nothing that leaves the evaluator
  * points at it: what escapes is a [[Stack.Snapshot]], an immutable copy.
  */
final private[kernel] class Stack:

    private var handlers      = new Array[Handler[?, ?, ?]](0)
    private var states        = new Array[Any](0)
    private var continuations = new Array[Arrow[?, ?, ?]](0)
    private var size          = 0

    // The releases each entry owes: a context region's own release, plus any moved onto it from regions dumped
    // out from above. Indexed the same way. Null is none. A region's release runs when its own extent ends, so
    // the presence of a release here is what says the region still owns it: a region dumped into a continuation
    // has its releases moved to the holder's entry and reinstalls with none, which is what makes a held region
    // release once, where the holder ends, without a separate flag.
    private var releases = new Array[Stack.Releases](0)

    // The releases owed below depth 0, run by the evaluation itself rather than by a region.
    private var evalReleases: Stack.Releases = Stack.Releases.empty

    // A flag rather than a scan: a run with no releases at all skips the lanes without touching them.
    private var owes = false

    /** A write-only store that forces a loop handler's outcome to escape. Nothing reads it, and deleting it is a large regression.
      *
      * The write is here so C2 cannot prove the outcome is local, because scalar-replacing it is pathological on this path: with the clause's
      * `Continue2` eliminated inside the fully inlined dispatch lane, the settled handleLoop rows ran about twice as slow as the pre-rewrite
      * evaluator, at byte-identical allocation. Confirmed two ways, by flag flip against `-XX:-EliminateAllocations` and by disassembly,
      * where the slow compilation has no allocation site for the outcome and the fast one materializes it.
      *
      * The rows that move if this goes away are `handleLoopAnswersInPlace`, `handleLoopFusesContinuation` and `statefulAnswersPaySuccessor`
      * in the kernel benchmarks. Measure them before touching it.
      */
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
        // as the worker lives. `releases` is deliberately not cleared: `takePopped` reads this index straight
        // after the decrement.
        handlers(size) = null
        states(size) = null
        continuations(size) = null
    end pop

    def owesAny: Boolean = owes

    def takePopped(): Stack.Releases = takeReleases(size)

    def takeReleases(i: Int): Stack.Releases =
        val here = releases(i)
        if !here.isEmpty then releases(i) = Stack.Releases.empty
        here
    end takeReleases

    /** Records that entry `i` owes `rs`, appending to whatever it already owed.
      *
      * A region dumped out of the live stack still owes its releases, a bracket's among them, and they cannot run where it was taken from,
      * because the continuation holding it may yet be resumed. They move to a region that is still installed, which runs them when its own
      * extent ends.
      */
    def owe(i: Int, rs: Stack.Releases): Unit =
        if !rs.isEmpty then
            owes = true
            releases(i) = releases(i).concat(rs)

    /** Adds one release to entry `i`, for a region installing its own. */
    def oweRelease(i: Int, r: Maybe[Throwable] => Unit): Unit =
        owes = true
        releases(i) = releases(i).add(r)

    /** [[owe]] against the region below `i`, or against the evaluation itself when `i` is the outermost. */
    def oweBelow(i: Int, rs: Stack.Releases): Unit =
        if !rs.isEmpty then
            owes = true
            if i == 0 then evalReleases = evalReleases.concat(rs)
            else releases(i - 1) = releases(i - 1).concat(rs)

    def takeEvalReleases(): Stack.Releases =
        val here = evalReleases
        if !here.isEmpty then evalReleases = Stack.Releases.empty
        here
    end takeEvalReleases

    def clear(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < size then
                handlers(i) = null
                states(i) = null
                continuations(i) = null
                releases(i) = Stack.Releases.empty
                loop(i + 1)
        loop(0)
        size = 0
        sink = null
        evalReleases = Stack.Releases.empty
        owes = false
        epochCount += 1
    end clear

    /** Takes every region off the stack as a snapshot, leaving it empty. What a computation carries across an execution boundary.
      *
      * Unlike [[dump]], the releases travel with the snapshot: a parked computation is the same computation resuming elsewhere, so its
      * regions run their extents to an end where they resume, and their releases must be there to run.
      */
    def takeAll(): Stack.Snapshot =
        val out = new Array[AnyRef](size * 4)
        @tailrec def loop(i: Int): Unit =
            if i < size then
                out(i * 4) = handlers(i)
                out(i * 4 + 1) = states(i).asInstanceOf[AnyRef]
                out(i * 4 + 2) = continuations(i)
                out(i * 4 + 3) = releases(i).asInstanceOf[AnyRef]
                handlers(i) = null
                states(i) = null
                continuations(i) = null
                releases(i) = Stack.Releases.empty
                loop(i + 1)
        loop(0)
        size = 0
        Stack.wrap(out)
    end takeAll

    /** Copies the context regions alone, leaving the stack untouched.
      *
      * A fork inherits bindings but not the handlers around the parent, so the copy keeps the context handlers and drops the rest. The
      * continuation slot is [[Arrow.id]] for each: these regions are being reinstalled, not resumed into, and they carry no releases, because
      * a fork gets an inert copy that its parent still owns.
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
                out(j + 3) = null
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
                releases(i) = Stack.Releases.empty
                loop(i + 1)
        loop(to)
        size = to
    end truncate

    /** The depth of the innermost region answering `tag`, or -1 when nothing does.
      *
      * Walking inward-out is what makes an inner handler shadow an outer one for the same effect, and the match is on tag subtyping so a
      * handler for a supertype answers an operation of a subtype. A context read resolves the same way an operation does, so a binding is
      * found here rather than in a separate context.
      */
    def find[E <: Effect](tag: kyo.Tag[E]): Int =
        @tailrec def loop(i: Int): Int =
            if i < 0 then -1
            else if handlers(i).tag.erased <:< tag.erased then i
            else loop(i - 1)
        loop(size - 1)
    end find

    /** Takes the regions from `from` upward off the live stack, returning them as a snapshot, and moves the releases they owed onto the
      * region below.
      *
      * This is what puts the regions sitting between a handler and a suspension into the continuation the clause receives: they stop being
      * installed, so the clause runs outside them, and they are reinstalled if the continuation is resumed. Their releases do not travel with
      * them, because the continuation may be resumed more than once and each shot runs against the live resource: they move to the region
      * below, which runs them once, when its own extent ends.
      */
    def dump(from: Int): Stack.Snapshot =
        val count             = size - from
        val out               = new Array[AnyRef](count * 4)
        var moved: Stack.Releases = Stack.Releases.empty
        @tailrec def loop(i: Int): Unit =
            if i < count then
                val j = from + i
                out(i * 4) = handlers(j)
                out(i * 4 + 1) = states(j).asInstanceOf[AnyRef]
                out(i * 4 + 2) = continuations(j)
                out(i * 4 + 3) = null
                moved = moved.concat(releases(j))
                handlers(j) = null
                states(j) = null
                continuations(j) = null
                releases(j) = Stack.Releases.empty
                loop(i + 1)
        loop(0)
        size = from
        if !moved.isEmpty then
            owes = true
            releases(from - 1) = releases(from - 1).concat(moved)
        Stack.wrap(out)
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
        val grownReleases = new Array[Stack.Releases](capacity)
        Array.copy(releases, 0, grownReleases, 0, size)
        releases = grownReleases
    end grow
end Stack

private[kernel] object Stack:

    /** A region's releases: the finalizer functions its entry owes.
      *
      * Empty, a single release stored bare so the common one-release case allocates no wrapper, or a `Chunk` once a second arrives. The
      * empty case is part of the type rather than a `null` the callers test for, so a stack entry is a `Releases` and nothing outside here
      * knows how the three cases are stored.
      */
    opaque type Releases = Null | (Maybe[Throwable] => Unit) | Chunk[Maybe[Throwable] => Unit]

    object Releases:
        val empty: Releases                                = null
        def apply(f: Maybe[Throwable] => Unit): Releases   = f

    extension (self: Releases)
        def isEmpty: Boolean = self == null

        /** Appends one release, keeping a single bare and materializing a `Chunk` only when a second arrives. */
        def add(f: Maybe[Throwable] => Unit): Releases =
            self match
                case null                                          => f
                case c: Chunk[Maybe[Throwable] => Unit] @unchecked => c.append(f)
                case g                                             => Chunk(g.asInstanceOf[Maybe[Throwable] => Unit], f)

        /** Appends `that` after this one. */
        def concat(that: Releases): Releases =
            self match
                case null => that
                case c: Chunk[Maybe[Throwable] => Unit] @unchecked =>
                    that match
                        case null                                           => self
                        case c2: Chunk[Maybe[Throwable] => Unit] @unchecked => c.concat(c2)
                        case f2                                             => c.append(f2.asInstanceOf[Maybe[Throwable] => Unit])
                case g =>
                    that match
                        case null                                           => self
                        case c2: Chunk[Maybe[Throwable] => Unit] @unchecked => Chunk(g.asInstanceOf[Maybe[Throwable] => Unit]).concat(c2)
                        case f2 => Chunk(g.asInstanceOf[Maybe[Throwable] => Unit], f2.asInstanceOf[Maybe[Throwable] => Unit])

        /** Runs each release once, innermost first, with `failure`. A throw is handed to `onError` so the rest still run. */
        inline def run(failure: Maybe[Throwable])(inline onError: Throwable => Unit): Unit =
            self match
                case null => ()
                case c: Chunk[Maybe[Throwable] => Unit] @unchecked =>
                    var i = c.size - 1
                    while i >= 0 do
                        try c(i)(failure)
                        catch case ex if !IsFatal(ex) => onError(ex)
                        i -= 1
                case f =>
                    try f.asInstanceOf[Maybe[Throwable] => Unit](failure)
                    catch case ex if !IsFatal(ex) => onError(ex)
    end extension

    /** Regions captured out of a stack, in the order it held them.
      *
      * Flat: four slots per region, the same handler, state, continuation and releases that the stack keeps in parallel arrays. A
      * `Span[AnyRef]` rather than an array of region objects, so capturing a stack costs one object whatever its depth.
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
                entries(count + 3) = null
                count += 4
            end add

            def result(): Snapshot =
                if count == entries.length then Span.fromUnsafe(entries)
                else Span.fromUnsafe(java.util.Arrays.copyOf(entries, count))
        end Builder
    end Snapshot

    extension (self: Snapshot)
        def regions: Int                          = self.size / 4
        def isEmpty: Boolean                      = self.size == 0
        def handler(i: Int): Handler[?, ?, ?]     = self(i * 4).asInstanceOf[Handler[?, ?, ?]]
        def state(i: Int): Any                    = self(i * 4 + 1)
        def continuation(i: Int): Arrow[?, ?, ?]  = self(i * 4 + 2).asInstanceOf[Arrow[?, ?, ?]]
        def releases(i: Int): Releases            = self(i * 4 + 3).asInstanceOf[Releases]

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
