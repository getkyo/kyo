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
  * Parallel arrays indexed by depth rather than one array of region objects: the handler, its state, the continuation for its result, the
  * releases the entry owes, and the remainders owed to it. Pushing a region writes the first three and allocates nothing, which matters
  * because a region is pushed and popped for every handled computation; the release and owed-remainder lanes are filled only when a region
  * takes one on. A [[Stack.Snapshot]] captures the first four; a park carries the owed lanes beside its snapshot ([[takeEntryOwed]]) so a
  * dump snapshot, which never owns a lane, pays nothing for one.
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

    // Remainders an escaping peel handed out, owed to an entry (the scope below the peel) until the remainder is
    // either resumed, when `settle` pulls it out of the lane, or that entry exits with it still owed, when its
    // regions are drained as discarded. A remainder is settled or drained, never both, so the release each region
    // carries in its snapshot runs exactly once: at the region's own end when the remainder is consumed, or here
    // when it is dropped. That single-home property is why no once-guard is needed.
    private var owedRemainders = new Array[Chunk[Stack.Snapshot]](0)

    // Remainders owed below depth 0, drained by the evaluation itself rather than by a region.
    private var evalOwedRemainders: Chunk[Stack.Snapshot] = Chunk.empty

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
        // after the decrement. `owedRemainders` is cleared, since every exit drains it before the pop and the
        // slot is reused by the next push.
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

    /** Installs a context region's own release on its entry, as an [[Stack.OwnRelease]] that binds its state late. */
    def oweOwn(i: Int, handler: Handler.ContextHandler[?, ?, ?, ?]): Unit =
        owes = true
        releases(i) = releases(i).add(new Stack.OwnRelease(handler))

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

    /** Owes the remainder `snapshot` to the entry below `i` (an escaping peel hands it to the scope below), or to the
      * evaluation itself when `i` is the outermost.
      */
    def oweRemainderBelow(i: Int, snapshot: Stack.Snapshot): Unit =
        owes = true
        if i == 0 then evalOwedRemainders = evalOwedRemainders.append(snapshot)
        else owedRemainders(i - 1) = owedRemainders(i - 1).append(snapshot)
    end oweRemainderBelow

    /** Passes remainders owed to a region down to the scope below it, for an escaping region that hands its own
      * continuation out: what was owed to it goes on being owed downward until a non-escaping region drains it.
      */
    def oweRemaindersBelow(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            owes = true
            if i == 0 then evalOwedRemainders = evalOwedRemainders.concat(snapshots)
            else owedRemainders(i - 1) = owedRemainders(i - 1).concat(snapshots)

    /** Takes the remainders owed to entry `i`, leaving none: their regions are drained as discarded when the entry ends. */
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

    /** Owes a chunk of remainders to entry `i` itself (restoring a resumed park's owed remainders). */
    def oweRemainders(i: Int, snapshots: Chunk[Stack.Snapshot]): Unit =
        if !snapshots.isEmpty then
            owes = true
            owedRemainders(i) = owedRemainders(i).concat(snapshots)

    /** Removes `snapshot` from wherever it is owed. Called when the remainder resumes: the entry that owed it no longer
      * drains it, and the reinstalled regions run their own ends instead. Identity, not equality: it is the same snapshot.
      */
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

    /** Takes every region off the stack as a snapshot, leaving it empty. What a computation carries across an execution boundary.
      *
      * Unlike [[dump]], the releases travel with the snapshot: a parked computation is the same computation resuming elsewhere, so its
      * regions run their extents to an end where they resume, and their releases must be there to run. The owed-remainder lanes travel with
      * the park too, but alongside it ([[takeEntryOwed]]), not in the snapshot, so a dump snapshot pays nothing for a lane it never carries.
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

    /** Takes the per-entry owed-remainder lanes, aligned with what [[takeAll]] captures, leaving none. A park carries these beside its
      * snapshot so each region re-owes the remainders it owed on resume, or drains them if the park is abandoned. Call before [[takeAll]].
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
                owedRemainders(i) = Chunk.empty
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
                // An escaping peel leaves each region its own release in the snapshot, so a resumed remainder closes
                // the region at its own end (a bracket is a context region: it drains at its own exit, wherever it is
                // consumed). The peel owes the whole snapshot to the scope below, which drains it if the remainder is
                // dropped; settled or drained, never both, so the release runs once with no guard. A non-escaping dump
                // moves the release to the holder, which is what makes a held (replayed) region release once, at the holder.
                if escaping then out(i * 4 + 3) = releases(j).asInstanceOf[AnyRef]
                else
                    out(i * 4 + 3) = null
                    moved = moved.concat(releases(j).capture(states(j)))
                end if
                // A dumped snapshot carries no owed lane: what each dumped entry owed moves to the holder (below), the
                // way its release does, rather than riding in the snapshot as a park's lanes ride beside it.
                // Remainders that were owed to a dumped entry move to the holder as its releases do: the entry is
                // leaving the live stack, so what it owed goes on being owed below, settled by a resume or drained on exit.
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

    /** A region's releases: the finalizer functions its entry owes.
      *
      * Empty, a single release stored bare so the common one-release case allocates no wrapper, or a `Chunk` once a second arrives. The
      * empty case is part of the type rather than a `null` the callers test for, so a stack entry is a `Releases` and nothing outside here
      * knows how the three cases are stored.
      */
    opaque type Releases = Null | (Maybe[Throwable] => Unit) | Chunk[Maybe[Throwable] => Unit]

    object Releases:
        val empty: Releases                              = null
        def apply(f: Maybe[Throwable] => Unit): Releases = f

    /** A context region's own release, in a form that binds its state late.
      *
      * It does not close over the state at push, because a fork's `join` writes the entry's state before the region ends, and the release
      * must see that. Instead it is resolved against the live entry when the region ends (see `Eval`), and fixed to a captured state by
      * [[capture]] only at the one moment the region leaves the live stack (a dump), after which the entry is gone. It is never applied
      * directly.
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

        /** Appends one release, keeping a single bare and materializing a `Chunk` only when a second arrives. */
        def add(f: Maybe[Throwable] => Unit): Releases =
            if self.isEmpty then f
            else
                self match
                    case c: Chunk[Maybe[Throwable] => Unit] @unchecked => c.append(f)
                    case g                                             => Chunk(g.asInstanceOf[Maybe[Throwable] => Unit], f)

        /** Appends `that` after this one. */
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

        /** Fixes each own release to `state`, for a region leaving the live stack: after this the releases no longer read the entry, which is
          * about to be reused. A release that is already a plain closure is left as is.
          */
        def capture(state: Any): Releases =
            if self.isEmpty then self
            else
                self match
                    case c: Chunk[Maybe[Throwable] => Unit] @unchecked => c.map(Stack.captureOne(_, state))
                    case f                                             => Stack.captureOne(f.asInstanceOf[Maybe[Throwable] => Unit], state)

        /** Runs each release once, innermost first, with `failure`. A throw is handed to `onError` so the rest still run. */
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

        /** Runs a region's own list at its end, resolving its [[OwnRelease]] against `state` (the entry's live state) and running any
          * releases held for it. Innermost first, with `failure`; a throw goes to `onError`.
          */
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

        /** Like [[runOwn]] told `Absent`, but the region's own [[OwnRelease]] runs through `complete` rather than `release`: the extent ran
          * to a clean end in place. Releases held for it (moved by a dump) are clean drops, told `Absent`.
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

    /** Regions captured out of a stack, in the order it held them.
      *
      * Flat: four slots per region, the same handler, state, continuation and releases that the stack keeps in parallel arrays. A
      * `Span[AnyRef]` rather than an array of region objects, so capturing a stack costs one object whatever its depth.
      *
      * A snapshot is immutable and complete, which is what lets the same one be reinstalled on another thread, or more than once.
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
