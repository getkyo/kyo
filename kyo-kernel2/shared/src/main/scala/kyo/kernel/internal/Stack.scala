package kyo.kernel.internal

import kyo.Arrow
import kyo.Maybe
import kyo.Maybe.*
import kyo.Result
import kyo.Span
import kyo.Tag
import kyo.bug
import scala.annotation.static
import scala.annotation.tailrec

final private[kyo] class Stack:
    private var entries = new Array[Arrow[?, ?, ?]](16)
    private var states  = Array.fill[Maybe[Any]](16)(Absent)
    private var mask    = 15
    private var head    = 0
    private var tail    = 0
    private val reach   = Safepoint.period() / 2

    // outstanding releases, held apart from the entries so a fold cannot bury one: `dump` merges runs of
    // entries into a chain, and a release that ended up inside one would be invisible to the drain
    private var finalizers = Array.fill[Maybe[Finalizer[?, ?]]](8)(Absent)
    private var pending    = 0

    // the out-parameter a stateful answer reports through, one per stack so it is pooled with it. Only the
    // eval and the handler's generated answer method touch it, within one dispatch; nothing it holds
    // survives past the dispatch that wrote it
    private[kernel] val out = new Handler.Out

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
                resolveFrom(flatten(f) - 1)
            case f =>
                ensure(1)
                head -= 1
                put(head & mask, f)
                resolve(0)

    /** Resolves what each binding in a freshly installed run holds, outermost first.
      *
      * A binding is a function of what is bound below it, so the entry below has to hold its own value
      * before the one above can be resolved against it. `fill` writes a chain innermost first, which is the
      * opposite order, so this walks back down.
      */
    @tailrec private def resolveFrom(i: Int): Unit =
        if i >= 0 then
            resolve(i)
            resolveFrom(i - 1)

    private def resolve(i: Int): Unit =
        val idx = (head + i) & mask
        entries(idx) match
            case b: Kyo.Binding[v, e, ?, ?] @unchecked =>
                // a scope that binds no name has nothing bound around it to derive from, and what a lookup
                // returns is typed by the slot it came from rather than by this binding
                b.bound.foreach { f =>
                    val outer = b.tag.fold(Absent)(k => lookup(i + 1, k)).asInstanceOf[Maybe[v]]
                    states(idx) = Present(f(outer))
                }
            case _ => ()
        end match
    end resolve

    /** Writes every link of `f` into the entries below `head`, leaving no chain among them.
      *
      * A chain is a tree, so flattening it is a walk that has to remember where it has been. The buffer is
      * that memory: the region reserved below `head` holds the flattened links growing down from `head`,
      * and the arrows still to visit growing up from the far end. The two only meet when the region is too
      * small for both, which is what the retry is for, so the walk is a loop and nothing is allocated to
      * hold it.
      *
      * `a` is pushed before `b` so `b` is visited first, which writes the links right to left and leaves
      * the leftmost at the top of the stack, the order `resolveFrom` and `pop` expect.
      *
      * Returns how many links were written; `head` has already moved by that much.
      */
    private def flatten(f: Arrow[?, ?, ?]): Int =
        var cap  = 8
        var done = -1
        while done < 0 do
            ensure(cap)
            val base = head - cap
            var w    = 0
            var s    = 1
            var fits = true
            entries(base & mask) = f
            while s > 0 && fits do
                s -= 1
                val x = entries((base + s) & mask)
                x match
                    case c: Arrow.Chain[?, ?, ?, ?] =>
                        if w + s + 2 > cap then fits = false
                        else
                            entries((base + s) & mask) = c.a
                            entries((base + s + 1) & mask) = c.b
                            s += 2
                    case x if x eq Arrow.Id => ()
                    case x =>
                        if w + s + 1 > cap then fits = false
                        else
                            w += 1
                            put((head - w) & mask, x)
                end match
            end while
            if fits then
                head -= w
                done = w
            else cap <<= 1
            end if
        end while
        done
    end flatten

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

    // how many releases are held. Only a test reads this, to pin that an eval running one bracket after
    // another does not accumulate entries for the ones that already ran
    private[kernel] def outstanding: Int = pending

    /** What the innermost binding holds for a tag, absent where nothing binds it.
      *
      * Stops at the first match, which is what makes an inner binding shadow an outer one, and reads the
      * value the entry resolved when it was installed rather than resolving again.
      */
    def lookup[E](t: Tag[E]): Maybe[Any] = lookup(0, t)

    // the entry's own effect type is bound by the pattern rather than erased, so the tags compare at the
    // types they were written with. What comes back is untyped because the slots are: one array holds the
    // values of every binding on this stack
    @tailrec private def lookup[E](i: Int, t: Tag[E]): Maybe[Any] =
        if i == size then Absent
        else
            entries((head + i) & mask) match
                case b: Kyo.Binding[?, e, ?, ?] @unchecked if b.tag.exists(_ =:= t) => state[Any](i)
                case _                                                              => lookup(i + 1, t)

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

    /** Folds the entries above `pos` into one arrow, normalized as far as their kinds allow.
      *
      * A run of steps becomes an `AndThen`, which is the shape the entries themselves have: pushing one back
      * stores it as a single entry rather than taking it apart, so a continuation folded here and re-attached
      * on every answer is built once instead of rebuilt each time.
      *
      * A region cannot be a link of an `AndThen`, so reaching one ends the normalized run and everything from
      * there down is a `Chain`. That is what keeps a handler, a binding and a finalizer visible: a chain is
      * taken apart on the way back in, so the region lands as its own entry where the scans can find it.
      */
    def dump[A, B, S](pos: Int): Arrow[A, B, S] =
        // the fold walks the entries deepest first, so the arrow it accumulates is untyped in the middle:
        // one array holds every kind of entry and the types line up only at the two ends. The casts are the
        // erasure the ring buffer already imposes, and they are the same ones the loop carried before
        @tailrec def loop(i: Int, acc: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
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
                val link =
                    // a run of one is the entry itself, which is what `chain` answered when its argument was
                    // identity. It matters beyond the node saved: a step handed back bare applies inline,
                    // where a node built only to terminate the run would defer instead
                    if acc eq Arrow.Id then e
                    else
                        e match
                            case s: Arrow.Step[Any, Any, Any] @unchecked =>
                                acc match
                                    case c: Arrow.Cont[Any, Any, Any] @unchecked => new Arrow.AndThen(s, c)
                                    case _                                       => e.chain(acc)
                            case _ => e.chain(acc)
                loop(i - 1, link.asInstanceOf[Arrow[Any, Any, Any]])
        val k = loop(pos - 1, Arrow.id)
        head += pos
        k.asInstanceOf[Arrow[A, B, S]]
    end dump

    def dump[A, B, S](): Arrow[A, B, S] =
        @tailrec def boundary(i: Int): Int =
            val e = entries((head + i) & mask)
            // a recovery bounds a fold as a region does. Folded in, it leaves the stack, and the failure it
            // guards against happens while the folded continuation's own argument is being evaluated, before
            // anything applies it: the scope would be off the stack exactly when it is needed
            if i == size || i == reach || e.isInstanceOf[Handler[?, ?, ?, ?]] || e.isInstanceOf[Recover[?, ?]] then i
            else boundary(i + 1)
        end boundary
        dump[A, B, S](boundary(0))
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

    /** Takes everything this stack holds, linearized, and leaves it empty.
      *
      * The ring buffer is walked from `head` so the spans read innermost first, which is the order `restore`
      * expects and the order a drain runs in. Ownership moves with the call: the arrays handed out are not
      * shared with this stack, which is free to be pooled and reused.
      */
    def snapshotEntries(): Span[Arrow[?, ?, ?]] =
        val n   = size
        val arr = new Array[Arrow[?, ?, ?]](n)
        var i   = 0
        while i < n do
            arr(i) = entries((head + i) & mask)
            i += 1
        Span.fromUnsafe(arr)
    end snapshotEntries

    def snapshotStates(): Span[Maybe[Any]] =
        val n   = size
        val arr = new Array[Maybe[Any]](n)
        var i   = 0
        while i < n do
            arr(i) = states((head + i) & mask)
            i += 1
        Span.fromUnsafe(arr)
    end snapshotStates

    def snapshotFinalizers(): Span[Maybe[Finalizer[?, ?]]] =
        val arr = new Array[Maybe[Finalizer[?, ?]]](pending)
        var i   = 0
        while i < pending do
            arr(i) = finalizers(i)
            i += 1
        Span.fromUnsafe(arr)
    end snapshotFinalizers

    /** Puts a parked stack back, above whatever is already here.
      *
      * Above, because a handler installed around a parked computation was pushed before the eval reached
      * the park, and the parked regions have to run inside it rather than the other way round.
      *
      * The states are written alongside the entries rather than through `put`, which would reinitialize a
      * stateful handler's slot from its initial state and lose the state the park was holding.
      */
    def restore(es: Span[Arrow[?, ?, ?]], sts: Span[Maybe[Any]], fins: Span[Maybe[Finalizer[?, ?]]]): Unit =
        val n = es.size
        if n > 0 then
            ensure(n)
            head -= n
            var i = 0
            while i < n do
                val idx = (head + i) & mask
                entries(idx) = es(i)
                states(idx) = sts(i)
                i += 1
            end while
            // the states come back as they were, which is what a stateful region needs, and then the
            // bindings resolve again: what one holds is a function of what is bound below it, and below it
            // now is whatever this eval had already installed
            resolveFrom(n - 1)
        end if
        var i = 0
        while i < fins.size do
            fins(i).foreach(pushFinalizer)
            i += 1
    end restore

    /** Unwinds to the innermost recovery that answers, releasing everything it passes on the way.
      *
      * One walk over the entries is what orders the two: a bracket opened inside a guarded scope sits above
      * the recovery, so it releases before the recovery runs. Nothing records a depth and nothing goes stale
      * when `dump` and `restore` move `head`, because the order is the stack's own.
      *
      * Everything above the answering recovery is discarded, which is what makes the recovery the
      * continuation: the value it produces flows into the entries that were below it.
      */
    // the answer is a pending computation, typed here as `Any` so the stack keeps knowing nothing about the
    // pending type: every other member is about arrows and slots, and the caller casts anyway
    def unwind(ex: Throwable): Maybe[Any] =
        var out = Maybe.empty[Any]
        while out.isEmpty && !isEmpty do
            pop() match
                case f: Finalizer[?, ?] => f.run(Result.panic(ex))
                case r: Recover[?, ?]   => out = r.panic(ex)
                case _                  => ()
        end while
        out
    end unwind

    def pushFinalizer(f: Finalizer[?, ?]): Unit =
        // drop the run of releases on top that have already run, so an eval that brackets many resources one
        // after another holds one entry rather than one per bracket. Only the eval reaches this, on its own
        // thread and while the stack is live, which is why the finalizer itself does not remove its own entry:
        // it can be applied from a continuation held past the end of the eval, by which point this stack has
        // been pooled and belongs to someone else
        while pending > 0 && finalizers(pending - 1).fold(true)(_.get()) do
            pending -= 1
            finalizers(pending) = Absent
        end while
        if pending == finalizers.length then
            val arr = Array.fill[Maybe[Finalizer[?, ?]]](pending * 2)(Absent)
            var i   = 0
            while i < pending do
                arr(i) = finalizers(i)
                i += 1
            finalizers = arr
        end if
        finalizers(pending) = Present(f)
        pending += 1
    end pushFinalizer

    /** Runs every release still outstanding, innermost first, and forgets them.
      *
      * One that already ran through its arrow is a no-op, so an eval that completed normally drains nothing.
      * What this catches is the eval that threw, and the one that ended holding a continuation a clause
      * received and never applied.
      *
      * A release that throws never stops the ones after it: each is owed independently, and losing the rest
      * because the first failed is how a single bad close leaks everything else. The failure is attached to
      * whatever the eval was already leaving with, as a suppressed exception, since that one describes why
      * the computation ended and this one only describes the cleanup. Where the eval was leaving normally
      * there is nothing to attach to, so the first failure is thrown once the drain is complete rather than
      * dropped: a release that cannot run is a real error, and silence there is invisible resource loss.
      *
      * @param failure
      *   the exception the eval is already unwinding with, or Absent if it is completing normally
      */
    def drainFinalizers(failure: Maybe[Throwable]): Unit =
        if pending > 0 then
            var first: Maybe[Throwable] = Absent
            // what the releases are told: the failure the eval is leaving with, or that their extent was
            // abandoned, which is what an eval completing while a continuation went unresumed means
            // at `Nothing`, since `Result` is covariant in its value and each release expects its own
            val outcome = Result.panic[Nothing, Nothing](failure.getOrElse(Finalizer.Abandoned))
            while pending > 0 do
                pending -= 1
                val f = finalizers(pending)
                finalizers(pending) = Absent
                try f.foreach(_.run(outcome))
                catch
                    case ex: Throwable =>
                        failure match
                            case Present(fail) => fail.addSuppressed(ex)
                            case _ =>
                                first match
                                    case Present(fst) => fst.addSuppressed(ex)
                                    case _            => first = Present(ex)
                end try
            end while
            if failure.isEmpty then first.foreach(throw _)
        end if
    end drainFinalizers

    def clear(): Unit =
        truncate(size)
        head = 0
        tail = 0
        // the stack is pooled, so an undrained release must not reach the next borrower
        var i = 0
        while i < pending do
            finalizers(i) = Absent
            i += 1
        pending = 0
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
