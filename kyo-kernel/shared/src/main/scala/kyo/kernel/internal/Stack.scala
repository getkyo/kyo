package kyo.kernel.internal

import kyo.Chunk
import kyo.IsFatal
import kyo.Maybe
import kyo.Span
import kyo.bug
import kyo.kernel.Arrow
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import scala.annotation.tailrec

/** The regions installed around the computation the evaluator is running, innermost last.
  *
  * Parallel arrays indexed by depth rather than region objects, so a push allocates nothing on the hot push/pop path; the release and
  * owed-remainder lanes are filled only when a region takes one on. The stack is mutable, borrowed from a per-thread pool for one
  * evaluation, then cleared and returned: what escapes is an immutable [[Stack.Snapshot]], never a pointer into the stack itself.
  */
final private[kernel] class Stack:

    private var handlers      = new Array[Handler[?, ?, ?]](0)
    private var states        = new Array[Any](0)
    private var continuations = new Array[Arrow[?, ?, ?]](0)
    private var size          = 0

    // A region's release runs when its own extent ends, so the presence of a release here is what marks the region as
    // still owning it: a region dumped into a continuation moves its releases to the holder's entry and reinstalls with
    // none, which makes a held region release once, at the holder, without a separate flag.
    private var releases = new Array[Stack.Releases](0)

    // Releases owed below depth 0, run by the evaluation rather than a region.
    private var evalReleases: Stack.Releases = Stack.Releases.empty

    // Remainders an escaping peel handed out, owed to an entry until the remainder resumes (settled out of the lane) or
    // that entry exits with it still owed (drained as discarded). Settled or drained, never both, so the release each
    // region carries in its snapshot runs exactly once, with no once-guard.
    private var owedRemainders = new Array[Chunk[Stack.Snapshot]](0)

    // Remainders owed below depth 0, drained by the evaluation rather than a region.
    private var evalOwedRemainders: Chunk[Stack.Snapshot] = Chunk.empty

    // Fast-path guard read before scanning the lanes; every write to a release or owed-remainder lane must set it.
    private var owes = false

    /** A write-only store that forces a loop handler's outcome to escape. Nothing reads it; deleting it is a large regression: the write
      * keeps C2 from scalar-replacing the outcome away, and without it the settled handleLoop rows run about twice as slow at byte-identical
      * allocation (the slow compilation has no allocation site for the outcome; the fast one materializes it). Measure
      * `handleLoopAnswersInPlace`, `handleLoopFusesContinuation` and `statefulAnswersPaySuccessor` in the kernel benchmarks before touching it.
      */
    var sink: Any = null

    private var epochCount = 0

    /** A staleness token bumped on each clear/reuse: since the object is recycled through the pool, a value holding a stack compares its
      * recorded epoch against this to tell whether the stack is still on the same evaluation.
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
        // Null the popped slots for GC: `clear` only reaches `0 until size`, and the stack returns to a live worker's
        // pool, so a slot left set stays reachable for the worker's life. `releases` is deliberately left: `takePopped`
        // reads this index right after the decrement.
        handlers(size) = null
        states(size) = null
        continuations(size) = null
        owedRemainders(size) = Chunk.empty
    end pop

    def owesAny: Boolean = owes

    def takePopped(): Stack.Releases = takeReleases(size)

    def takeReleases(i: Int): Stack.Releases =
        val here = releases(i)
        if !here.isEmpty then releases(i) = Stack.Releases.empty
        here
    end takeReleases

    def owe(i: Int, rs: Stack.Releases): Unit =
        if !rs.isEmpty then
            owes = true
            releases(i) = releases(i).concat(rs)

    def oweRelease(i: Int, r: Maybe[Throwable] => Unit): Unit =
        owes = true
        releases(i) = releases(i).add(r)

    def oweOwn(i: Int, handler: Handler.ContextHandler[?, ?, ?, ?]): Unit =
        owes = true
        releases(i) = releases(i).add(new Stack.OwnRelease(handler))

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

    def oweRemainderBelow(i: Int, snapshot: Stack.Snapshot): Unit =
        owes = true
        if i == 0 then evalOwedRemainders = evalOwedRemainders.append(snapshot)
        else owedRemainders(i - 1) = owedRemainders(i - 1).append(snapshot)
    end oweRemainderBelow

    /** Passes remainders owed to a region down to the scope below, for an escaping region that hands its continuation out: they stay owed
      * downward until a non-escaping region drains them.
      */
    def oweRemaindersBelow(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            owes = true
            if i == 0 then evalOwedRemainders = evalOwedRemainders.concat(snapshots)
            else owedRemainders(i - 1) = owedRemainders(i - 1).concat(snapshots)

    def takeOwedRemainders(i: Int): Chunk[Stack.Snapshot] =
        val here = owedRemainders(i)
        if !here.isEmpty then owedRemainders(i) = Chunk.empty
        here
    end takeOwedRemainders

    def takeEvalOwedRemainders(): Chunk[Stack.Snapshot] =
        val here = evalOwedRemainders
        if !here.isEmpty then evalOwedRemainders = Chunk.empty
        here
    end takeEvalOwedRemainders

    def oweRemainders(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            owes = true
            owedRemainders(i) = owedRemainders(i).concat(snapshots)

    /** Removes `snapshot` from wherever it is owed, when its remainder resumes. Identity, not equality: the same snapshot object. */
    def settle(snapshot: Stack.Snapshot): Unit =
        if owes then
            @tailrec def loop(i: Int): Unit =
                if i < 0 then evalOwedRemainders = settleIn(evalOwedRemainders, snapshot)
                else
                    val lane    = owedRemainders(i)
                    val settled = settleIn(lane, snapshot)
                    if settled ne lane then owedRemainders(i) = settled
                    else loop(i - 1)
            loop(size - 1)
    end settle

    private def settleIn(lane: Chunk[Stack.Snapshot], snapshot: Stack.Snapshot): Chunk[Stack.Snapshot] =
        if lane.isEmpty then lane
        else
            @tailrec def loop(j: Int): Chunk[Stack.Snapshot] =
                if j < 0 then lane
                else if lane(j).asInstanceOf[AnyRef] eq snapshot.asInstanceOf[AnyRef] then
                    if lane.size == 1 then Chunk.empty
                    else lane.take(j).concat(lane.drop(j + 1))
                else loop(j - 1)
            loop(lane.size - 1)
    end settleIn

    def clear(): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < size then
                handlers(i) = null
                states(i) = null
                continuations(i) = null
                releases(i) = Stack.Releases.empty
                owedRemainders(i) = Chunk.empty
                loop(i + 1)
        loop(0)
        size = 0
        sink = null
        evalReleases = Stack.Releases.empty
        evalOwedRemainders = Chunk.empty
        owes = false
        epochCount += 1
    end clear

    /** Takes every region off the stack as a snapshot, leaving it empty: what a computation carries across an execution boundary. Unlike
      * [[dump]], the releases travel in the snapshot, because a park is the same computation resuming elsewhere and its regions run their
      * extents to an end there.
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

    /** Takes the per-entry owed-remainder lanes, aligned with what [[takeAll]] captures, leaving none. A park carries them beside its
      * snapshot, not in it, so a dump snapshot pays nothing for lanes it never owns; each region re-owes its remainders on resume, or drains
      * them if the park is abandoned. Call before [[takeAll]], which empties the stack.
      */
    def takeEntryOwed(): Chunk[Chunk[Stack.Snapshot]] =
        val out = new Array[Chunk[Stack.Snapshot]](size)
        var i   = 0
        while i < size do
            out(i) = owedRemainders(i)
            owedRemainders(i) = Chunk.empty
            i += 1
        end while
        Chunk.fromNoCopy(out)
    end takeEntryOwed

    /** Copies the context regions alone (a fork inherits bindings, not the parent's handlers), leaving the stack untouched. Each copied
      * region gets [[Arrow.id]] as its continuation and no releases: it is being reinstalled, not resumed into, and the parent still owns
      * the releases.
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
                owedRemainders(i) = Chunk.empty
                loop(i + 1)
        loop(to)
        size = to
    end truncate

    /** The depth of the innermost region answering `tag`, or -1 when nothing does. The inward-out walk makes an inner handler shadow an
      * outer one, and the match is on tag subtyping, so a handler for a supertype answers an operation of a subtype. Context reads resolve
      * the same way, through the handler stack rather than a separate context.
      */
    def find[E <: Effect](tag: kyo.Tag[E]): Int =
        @tailrec def loop(i: Int): Int =
            if i < 0 then -1
            else if handlers(i).tag.erased <:< tag.erased then i
            else loop(i - 1)
        loop(size - 1)
    end find

    /** Takes the regions from `from` upward off the live stack as a snapshot (reinstalled if the continuation is resumed), moving the
      * releases they owed onto the region below. The releases do not travel with them, because the continuation may be resumed more than
      * once against the live resource; the region below runs them once, at its own end.
      */
    def dump(from: Int, escaping: Boolean = false): Stack.Snapshot =
        val count                            = size - from
        val out                              = new Array[AnyRef](count * 4)
        var moved: Stack.Releases            = Stack.Releases.empty
        var movedOwed: Chunk[Stack.Snapshot] = Chunk.empty
        @tailrec def loop(i: Int): Unit =
            if i < count then
                val j = from + i
                out(i * 4) = handlers(j)
                out(i * 4 + 1) = states(j).asInstanceOf[AnyRef]
                out(i * 4 + 2) = continuations(j)
                // Escaping peel: each region keeps its own release in the snapshot, and the whole snapshot is owed to the
                // scope below (settled if the remainder resumes, drained if it is dropped, never both, so the release runs
                // once). A non-escaping dump instead moves the release to the holder, which runs it once at the holder.
                if escaping then out(i * 4 + 3) = releases(j).asInstanceOf[AnyRef]
                else
                    out(i * 4 + 3) = null
                    moved = moved.concat(releases(j).capture(states(j)))
                end if
                // Owed remainders move to the holder the same way releases do, not into the snapshot.
                movedOwed = movedOwed.concat(owedRemainders(j))
                handlers(j) = null
                states(j) = null
                continuations(j) = null
                releases(j) = Stack.Releases.empty
                owedRemainders(j) = Chunk.empty
                loop(i + 1)
        loop(0)
        size = from
        if !movedOwed.isEmpty then
            owes = true
            owedRemainders(from - 1) = owedRemainders(from - 1).concat(movedOwed)
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
        val grownOwed = new Array[Chunk[Stack.Snapshot]](capacity)
        Array.copy(owedRemainders, 0, grownOwed, 0, size)
        var i = size
        while i < capacity do
            grownOwed(i) = Chunk.empty
            i += 1
        owedRemainders = grownOwed
    end grow
end Stack

private[kernel] object Stack:

    /** A region's releases, encoded to allocate no wrapper in the common single-release case: empty is `null`, one release is stored bare,
      * a `Chunk` materializes only once a second arrives. The type hides which, so callers never test for `null`.
      */
    opaque type Releases = Null | (Maybe[Throwable] => Unit) | Chunk[Maybe[Throwable] => Unit]

    object Releases:
        val empty: Releases                              = null
        def apply(f: Maybe[Throwable] => Unit): Releases = f

    /** A context region's own release that binds its state late: it does not close over the state at push, because a fork's `join` rewrites
      * the entry's state before the region ends and the release must see that. It is resolved against the live entry when the region ends,
      * or fixed to a captured state by [[capture]] when the region leaves the live stack (a dump). Never applied directly.
      */
    final private[kernel] class OwnRelease(val handler: Handler.ContextHandler[?, ?, ?, ?]) extends (Maybe[Throwable] => Unit):
        private def hc                             = handler.asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]]
        def apply(failure: Maybe[Throwable]): Unit = bug("OwnRelease must be resolved against its region's state")
        def run(state: Any, failure: Maybe[Throwable]): Unit = hc.release(state, failure)
        def runComplete(state: Any): Unit                    = hc.complete(state)
        def captured(state: Any): Maybe[Throwable] => Unit   = failure => hc.release(state, failure)
    end OwnRelease

    private def captureOne(r: Maybe[Throwable] => Unit, state: Any): Maybe[Throwable] => Unit =
        r match
            case o: OwnRelease => o.captured(state)
            case _             => r

    extension (self: Releases)
        // Reference identity rather than `==`: the union has a function arm, which has no multiversal equality.
        def isEmpty: Boolean = self.asInstanceOf[AnyRef] eq null

        def add(f: Maybe[Throwable] => Unit): Releases =
            if self.isEmpty then f
            else
                self match
                    case c: Chunk[Maybe[Throwable] => Unit] @unchecked => c.append(f)
                    case g                                             => Chunk(g.asInstanceOf[Maybe[Throwable] => Unit], f)

        def concat(that: Releases): Releases =
            if self.isEmpty then that
            else if that.isEmpty then self
            else
                self match
                    case c: Chunk[Maybe[Throwable] => Unit] @unchecked =>
                        that match
                            case c2: Chunk[Maybe[Throwable] => Unit] @unchecked => c.concat(c2)
                            case f2                                             => c.append(f2.asInstanceOf[Maybe[Throwable] => Unit])
                    case g =>
                        that match
                            case c2: Chunk[Maybe[Throwable] => Unit] @unchecked =>
                                Chunk(g.asInstanceOf[Maybe[Throwable] => Unit]).concat(c2)
                            case f2 => Chunk(g.asInstanceOf[Maybe[Throwable] => Unit], f2.asInstanceOf[Maybe[Throwable] => Unit])

        /** Fixes each [[OwnRelease]] to `state` before the entry is reused, so the releases no longer read the live entry. A plain closure is
          * left as is.
          */
        def capture(state: Any): Releases =
            if self.isEmpty then self
            else
                self match
                    case c: Chunk[Maybe[Throwable] => Unit] @unchecked => c.map(Stack.captureOne(_, state))
                    case f                                             => Stack.captureOne(f.asInstanceOf[Maybe[Throwable] => Unit], state)

        /** Runs each release once, innermost first; a throw goes to `onError` so the rest still run. */
        inline def run(failure: Maybe[Throwable])(inline onError: Throwable => Unit): Unit =
            if !self.isEmpty then
                self match
                    case c: Chunk[Maybe[Throwable] => Unit] @unchecked =>
                        var i = c.size - 1
                        while i >= 0 do
                            try c(i)(failure)
                            catch case ex if !IsFatal(ex) => onError(ex)
                            i -= 1
                        end while
                    case f =>
                        try f.asInstanceOf[Maybe[Throwable] => Unit](failure)
                        catch case ex if !IsFatal(ex) => onError(ex)

        /** Like [[run]], but resolves the region's [[OwnRelease]] against `state` (the entry's live state) before running it. */
        inline def runOwn(state: Any, failure: Maybe[Throwable])(inline onError: Throwable => Unit): Unit =
            if !self.isEmpty then
                self match
                    case c: Chunk[Maybe[Throwable] => Unit] @unchecked =>
                        var i = c.size - 1
                        while i >= 0 do
                            try
                                c(i) match
                                    case o: OwnRelease => o.run(state, failure)
                                    case f             => f(failure)
                            catch case ex if !IsFatal(ex) => onError(ex)
                            end try
                            i -= 1
                        end while
                    case o: OwnRelease =>
                        try o.run(state, failure)
                        catch case ex if !IsFatal(ex) => onError(ex)
                    case f =>
                        try f.asInstanceOf[Maybe[Throwable] => Unit](failure)
                        catch case ex if !IsFatal(ex) => onError(ex)

        /** Like [[runOwn]] with `Absent`, but the [[OwnRelease]] runs through `complete` rather than `release`: the extent ran to a clean end
          * in place.
          */
        inline def runOwnComplete(state: Any)(inline onError: Throwable => Unit): Unit =
            if !self.isEmpty then
                self match
                    case c: Chunk[Maybe[Throwable] => Unit] @unchecked =>
                        var i = c.size - 1
                        while i >= 0 do
                            try
                                c(i) match
                                    case o: OwnRelease => o.runComplete(state)
                                    case f             => f(Maybe.Absent)
                            catch case ex if !IsFatal(ex) => onError(ex)
                            end try
                            i -= 1
                        end while
                    case o: OwnRelease =>
                        try o.runComplete(state)
                        catch case ex if !IsFatal(ex) => onError(ex)
                    case f =>
                        try f.asInstanceOf[Maybe[Throwable] => Unit](Maybe.Absent)
                        catch case ex if !IsFatal(ex) => onError(ex)
    end extension

    /** Regions captured out of a stack in order, flat: four slots per region (handler, state, continuation, releases), a single
      * `Span[AnyRef]` whatever the depth. Immutable, so the same snapshot can be reinstalled on another thread or more than once.
      */
    opaque type Snapshot = Span[AnyRef]

    private def wrap(entries: Array[AnyRef]): Snapshot = Span.fromUnsafe(entries)
    end wrap

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
        def regions: Int                         = self.size / 4
        def isEmpty: Boolean                     = self.size == 0
        def handler(i: Int): Handler[?, ?, ?]    = self(i * 4).asInstanceOf[Handler[?, ?, ?]]
        def state(i: Int): Any                   = self(i * 4 + 1)
        def continuation(i: Int): Arrow[?, ?, ?] = self(i * 4 + 2).asInstanceOf[Arrow[?, ?, ?]]
        def releases(i: Int): Releases           = self(i * 4 + 3).asInstanceOf[Releases]

    end extension

    /** A per-thread free list of stacks, reused across evaluations rather than allocated per run. Release clears the stack, which also bumps
      * its epoch.
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
