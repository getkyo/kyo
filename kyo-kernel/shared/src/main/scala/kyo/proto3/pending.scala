package kyo.proto3

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

trait Effect[I[_], O[_]]

sealed abstract class Kyo[+A, -S]:
    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)
    private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S

object Kyo:

    // Compiled as a JVM static of class Kyo: hot callers (minted arrow fragments,
    // Arrow.apply, Offset.run) reach it via invokestatic with no module load and,
    // in minted fragments, no captured reference to an enclosing object.
    @static def unwrap(v: Any): Any =
        v match
            case n: Nested[?] => n.value
            case _            => v

    final class Nested[+A](val value: A):
        override def toString = "Nested"

    abstract class Suspend[I[_], O[_], E <: Effect[I, O], A] extends Kyo[O[A], E]:

        def input: I[A]
        def tag: Tag[E]
        def frame: Frame

        final def map[B, S](f: Arrow[O[A], B, S]): B < (E & S) =
            Continue[I, O, E, A, B, S](this, f)

        final private[kyo] def prepend(f: Arrow[Any, Any, Any]): O[A] < E =
            map(f.asInstanceOf[Arrow[O[A], O[A], Any]])

        final override def toString = "Suspend(" + tag.show + ", " + frame.position.show + ")"

    end Suspend

    final class Continue[I[_], O[_], E <: Effect[I, O], A, +B, -S](
        val suspend: Suspend[I, O, E, A],
        val cont: Arrow[O[A], B, S]
    ) extends Kyo[B, E & S]:

        def map[C, S2](f: Arrow[B, C, S2]): C < (E & S & S2) =
            Continue(suspend, cont.map(f))

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): B < (E & S) =
            Continue(suspend, f.map(cont.asInstanceOf[Arrow[Any, B, S]]).asInstanceOf[Arrow[O[A], B, S]])

        override def toString = "Continue(" + suspend + ")"

    end Continue

    abstract class Bracket[R, A, S] extends Kyo[A, S]:

        def acquire: R < S
        def release(r: R): Unit < S
        def cont: Arrow[R, A, S]
        def frame: Frame

        final def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            val outer = this
            new Bracket[R, B, S & S2]:
                def acquire       = outer.acquire
                def release(r: R) = outer.release(r)
                def cont          = outer.cont.map(f)
                def frame         = outer.frame
            end new
        end map

        final private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S =
            val outer = this
            new Bracket[R, A, S]:
                def acquire =
                    outer.acquire match
                        case kyo: Kyo[R, S] @unchecked => kyo.prepend(f)
                        case v                         => v
                def release(r: R) = outer.release(r)
                def cont          = f.map(outer.cont.asInstanceOf[Arrow[Any, A, S]]).asInstanceOf[Arrow[R, A, S]]
                def frame         = outer.frame
            end new
        end prepend

        final override def toString = "Bracket(" + frame.position.show + ")"

    end Bracket

    // public because the inline trampoline's Defer arm expands at user sites
    final class Defer[A, +B, -S](
        val value: A,
        val cont: Arrow[A, B, S]
    ) extends Kyo[B, S]:

        def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Defer(value, cont.map(f))

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): B < S =
            Defer(value, f.map(cont.asInstanceOf[Arrow[Any, B, S]]).asInstanceOf[Arrow[A, B, S]])

        override def toString = "Defer"

    end Defer

end Kyo

opaque type <[+A, -S] = A | Kyo[A, S] | Kyo.Nested[A]

object `<`:

    // Inline with compile-time elision: a value type is provably not a computation
    // (Kyo, Nested, and the opaque < all erase to references bounded by Any), so
    // those sites lift by identity with no call and no tests. Everything else
    // keeps the runtime check in liftSlow.
    implicit inline def lift[A](v: A): A < Any =
        scala.compiletime.summonFrom {
            case _: (A <:< AnyVal) => v.asInstanceOf[A < Any]
            case _: (A <:< String) => v.asInstanceOf[A < Any]
            case _                 => liftSlow(v)
        }

    def liftSlow[A](v: A): A < Any =
        v match
            case _: Kyo[?, ?] | _: Kyo.Nested[?] => Kyo.Nested(v).asInstanceOf[A < Any]
            case _                               => v

    implicit private[kyo] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    extension [A, S](self: A < S)

        private[kyo] def discard: Unit =
            discardValue(self) match
                case Nil => ()
                case t :: rest =>
                    rest.foreach(t.addSuppressed)
                    throw t
    end extension

    extension [A, S](inline self: A < S)
        @nowarn
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: Any, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v.asInstanceOf[A])
                    (cont: Any) match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unwrap(w), o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w)
                    end match
                end run
            arrow(self)
        end map

    end extension

    def observe[A, S](observer: (Frame, Any) => Unit)(v: A < S): A < S =
        v match
            case kyo: Kyo[A, S] @unchecked =>
                kyo.prepend(Arrow.of(new Observe(observer)).asInstanceOf[Arrow[Any, Any, Any]])
            case _ =>
                v
    end observe

    final class Observe(observer: (Frame, Any) => Unit) extends Arrow.Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            @tailrec def loop(o: Arrow.Offset[Any, Any, Any, Any], cur: Any): Any =
                o.head match
                    case jump: Arrow.Offset[Any, Any, Any, Any] @unchecked if Arrow.isEmpty(o.next) =>
                        loop(jump, cur)
                    case t =>
                        observer(t.frame, cur)
                        val w = t.run(cur, Arrow[Any])
                        if w.isInstanceOf[Kyo[?, ?]] then
                            val rest =
                                if Arrow.isEmpty(o.next) then Arrow.of[Any, Any, Any](this)
                                else Arrow.map(Arrow.of[Any, Any, Any](this))(o.next)
                            w.asInstanceOf[Kyo[Any, Any]].map(rest)
                        else
                            (o.next: Any) match
                                case n: Arrow.Offset[Any, Any, Any, Any] @unchecked => loop(n, Kyo.unwrap(w))
                                case _                                              => Kyo.unwrap(w)
                        end if
            (cont: Any) match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                    loop(o, v).asInstanceOf[C < (Any & S2)]
                case _ =>
                    cont(v.asInstanceOf[Any < Any])
            end match
        end run
    end Observe

    extension [A](self: A < Any)

        inline def eval: A =
            if self.isInstanceOf[Kyo[?, ?]] then
                Kyo.unwrap(evalLoop(self.asInstanceOf[Any < Any], never, 1, unhandled)).asInstanceOf[A]
            else Kyo.unwrap(self).asInstanceOf[A]

        def eval(preempt: () => Boolean, period: Int): A < Any =
            evalLoop(self.asInstanceOf[Any < Any], preempt, Integer.max(1, period / Arrow.Period), unhandled).asInstanceOf[A < Any]

    end extension

    inline def evalPartial[I[_], O[_], E <: Effect[I, O], A](
        tag: Tag[E],
        v: A < E,
        preempt: () => Boolean = never,
        period: Int = Arrow.Period
    )(
        handle: [X] => (I[X], Arrow[O[X], A, E]) => Maybe[A < E]
    ): A < E =
        val handler: Kyo[Any, Any] => Maybe[Any < Any] =
            case c: Kyo.Continue[I, O, E, Any, A, E] @unchecked if c.suspend.tag =:= tag =>
                handle(c.suspend.input, c.cont.optimize).asInstanceOf[Maybe[Any < Any]]
            case s: Kyo.Suspend[I, O, E, Any] @unchecked if s.tag =:= tag =>
                handle(s.input, Arrow[A].asInstanceOf[Arrow[O[Any], A, E]]).asInstanceOf[Maybe[Any < Any]]
            case _ =>
                Maybe.Absent
        end handler
        evalLoop(v.asInstanceOf[Any < Any], preempt, Integer.max(1, period / Arrow.Period), handler).asInstanceOf[A < E]
    end evalPartial

    inline def eval[I[_], O[_], E <: Effect[I, O], A](
        tag: Tag[E],
        v: A < E
    )(
        handle: [X] => (I[X], Arrow[O[X], A, E]) => A < E
    ): A =
        Kyo.unwrap(
            evalPartial(tag, v)(
                [X] => (input: I[X], cont: Arrow[O[X], A, E]) => Maybe(handle(input, cont))
            )
        ).asInstanceOf[A]

    private val never: () => Boolean = () => false

    private val unhandled: Kyo[Any, Any] => Maybe[Any < Any] =
        kyo => throw new IllegalStateException("unhandled suspension: " + kyo)

    private inline def BracketDepth = 512

    private def discardValue[A, S](v: A < S): List[Throwable] =
        v match
            case kyo: Kyo.Continue[?, ?, ?, ?, ?, ?] => discardArrow(kyo.cont)
            case kyo: Kyo.Defer[?, ?, ?]             => discardArrow(kyo.cont)
            case _                                   => Nil

    private def discardArrow(arrow: Any): List[Throwable] =
        arrow match
            case finalize: Finalize[?, ?, ?] =>
                try
                    val _ = finalize.bracket.release(finalize.value).asInstanceOf[Unit < Any].eval
                    Nil
                catch
                    case t: Throwable =>
                        KyoException.attach(t, "release", finalize.bracket.frame)
                        t :: Nil
            case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                discardChain(o)
            case at: Arrow.AndThen[?, ?, ?, ?] =>
                discardArrow(at.a) ++ discardArrow(at.b)
            case _ =>
                Nil

    private def discardChain(o: Arrow.Offset[Any, Any, Any, Any]): List[Throwable] =
        @tailrec def loop(cur: Any, errors: List[Throwable]): List[Throwable] =
            cur match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked => loop(o.next, errors ++ discardArrow(o.head))
                case _                                              => errors
        loop(o, Nil)
    end discardChain

    private def yieldValue[A](v: A): Arrow[Unit, A, Any] =
        Arrow.of(
            new Arrow.Transform[Unit, A, Any]:
                def frame = Frame.internal
                def run[C, S2](x: Any, cont: Arrow[A, C, S2]): C < (Any & S2) =
                    cont(v.asInstanceOf[A < Any])
        )

    // public because the inline trampoline's bracket arm expands at user sites
    final class Finalize[R, A, S](val bracket: Kyo.Bracket[R, ?, S], val value: R)
        extends Arrow.Transform[A, A, S]:
        def frame = bracket.frame
        def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) =
            cont(yieldValue(v.asInstanceOf[A])(bracket.release(value)))
    end Finalize

    private def constant(v: Any < Any): Arrow[Any, Any, Any] =
        Arrow.of(
            new Arrow.Transform[Any, Any, Any]:
                def frame = Frame.internal
                def run[C, S2](x: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    cont(v)
        )

    private def reacquire(bracket: Kyo.Bracket[Any, Any, Any]): Arrow[Any, Any, Any] =
        Arrow.of(
            new Arrow.Transform[Any, Any, Any]:
                def frame = Frame.internal
                def run[C, S2](r: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    cont(
                        new Kyo.Bracket[Any, Any, Any]:
                            def acquire         = r
                            def release(x: Any) = bracket.release(x)
                            def cont            = bracket.cont
                            def frame           = bracket.frame
                    )
        )

    private def cleanup(bracket: Kyo.Bracket[Any, Any, Any], resource: Any, t: Throwable): Unit =
        try
            val _ = bracket.release(resource).eval
        catch
            case t2: Throwable =>
                KyoException.attach(t2, "release", bracket.frame)
                t.addSuppressed(t2)

    private def preempted(v: Any < Any): Boolean =
        v.isInstanceOf[Kyo.Defer[?, ?, ?]]

    // Inline so every eval site gets a private copy of the trampoline: the
    // handle(kyo) dispatch and the suspension-shape tests then profile per
    // handler instead of pooling across every eval in the program.
    private inline def evalLoop(
        v0: Any < Any,
        preempt: () => Boolean,
        stride: Int,
        handle: Kyo[Any, Any] => Maybe[Any < Any]
    ): Any < Any =
        def drive(v: Any < Any, depth: Int): Any < Any =
            @tailrec def loop(curr: Any < Any, n: Int): Any < Any =
                curr match
                    case bracket: Kyo.Bracket[Any, Any, Any] @unchecked =>
                        if depth >= BracketDepth then bracket
                        else
                            drive(bracket.acquire, depth + 1) match
                                case suspended: Kyo[Any, Any] @unchecked =>
                                    val wrapped = suspended.map(reacquire(bracket))
                                    if depth == 0 && !preempted(wrapped) then loop(wrapped, n) else wrapped
                                case acquired =>
                                    val resource = Kyo.unwrap(acquired)
                                    val result =
                                        try drive(bracket.cont(acquired), depth + 1)
                                        catch
                                            case t: Throwable =>
                                                cleanup(bracket, resource, t)
                                                throw t
                                    result match
                                        case suspended: Kyo[Any, Any] @unchecked =>
                                            val wrapped = suspended.map(new Finalize[Any, Any, Any](bracket, resource))
                                            if depth == 0 && !preempted(wrapped) then loop(wrapped, n) else wrapped
                                        case _ =>
                                            drive(bracket.release(resource), depth + 1) match
                                                case suspended: Kyo[Any, Any] @unchecked =>
                                                    val wrapped = suspended.map(constant(result))
                                                    if depth == 0 && !preempted(wrapped) then loop(wrapped, n) else wrapped
                                                case _ =>
                                                    result
                                    end match
                    case defer: Kyo.Defer[Any, Any, Any] @unchecked =>
                        if n == 0 then
                            if preempt() then curr
                            else loop(defer.cont(defer.value.asInstanceOf[Any < Any]), stride - 1)
                        else loop(defer.cont(defer.value.asInstanceOf[Any < Any]), n - 1)
                    case kyo: Kyo[Any, Any] @unchecked =>
                        if depth == 0 then
                            handle(kyo) match
                                case Maybe.Present(next) =>
                                    if n == 0 then
                                        if preempt() then next
                                        else loop(next, stride - 1)
                                    else loop(next, n - 1)
                                case _ => kyo
                        else kyo
                    case _ =>
                        curr
            loop(v, 0)
        end drive
        try drive(v0, 0)
        catch
            case ex: Throwable =>
                KyoException.install(ex)
                throw ex
        end try
    end evalLoop

end `<`

sealed abstract class Arrow[-A, +B, -S]

object Arrow:

    /** A decomposed arrow: one executable transform and the rest of the chain. Phase 1
      * of dispatch obtains it via [[step]]; phase 2 is `head.run(v, next)` written in
      * the caller's own bytecode, where the receiver profile is private to that site.
      * X is the intermediate type between head and next; callers never name it.
      */
    sealed trait Step[-A, +B, -S]:
        type X
        def head: Transform[A, X, S]
        def next: Arrow[X, B, S]
    end Step

    private[kyo] inline def Period = 512
    private inline def SmallLimit  = 32

    final private[kyo] class Depth private ()

    private[kyo] object Depth:

        inline def Limit = 512

        private inline def Slots        = 256
        private inline def Mask         = Slots - 1
        private inline def Probes       = 8
        private inline def Shift        = 3
        private inline def Transferring = -1L

        @static private val owners  = new java.util.concurrent.atomic.AtomicLongArray(Slots)
        @static private val threads = new java.util.concurrent.atomic.AtomicReferenceArray[Thread](Slots)

        // one cache line per cell; the last cell is pinned at Limit and never written
        @static private val cells =
            val a = new Array[Long]((Slots + 1) << Shift)
            a(Slots << Shift) = Limit
            a
        end cells

        // returns the previous depth; does not write at or past Limit, so the
        // shared overflow cell is never mutated
        @static def increase(slot: Int): Long =
            val depth = cells(slot)
            if depth < Limit then cells(slot) = depth + 1
            depth
        end increase

        @static def decrease(slot: Int): Unit =
            cells(slot) -= 1

        @static def slot(): Int =
            val tid = Thread.currentThread().threadId
            val i   = tid.toInt & Mask
            if owners.get(i) == tid then i << Shift
            else slow(tid)
        end slot

        @static private def slow(tid: Long): Int =
            val self = Thread.currentThread()
            @tailrec def probe(i: Int, remaining: Int): Int =
                if remaining == 0 then Slots << Shift
                else
                    val owner = owners.get(i)
                    if owner == tid then i << Shift
                    else if owner == 0L && owners.compareAndSet(i, 0L, Transferring) then claim(i, self, tid)
                    else if owner > 0L && dead(i) && owners.compareAndSet(i, owner, Transferring) then claim(i, self, tid)
                    else probe((i + 1) & Mask, remaining - 1)
                    end if
            probe(tid.toInt & Mask, Probes)
        end slow

        @static private def dead(i: Int): Boolean =
            val t = threads.get(i)
            (t ne null) && !t.isAlive

        @static private def claim(i: Int, self: Thread, tid: Long): Int =
            threads.set(i, self)
            cells(i << Shift) = 0L
            owners.set(i, tid)
            i << Shift
        end claim

        @static private[kyo] def owned: Boolean =
            val tid = Thread.currentThread().threadId
            @tailrec def scan(i: Int): Boolean =
                if i == Slots then false
                else if owners.get(i) == tid then true
                else scan(i + 1)
            scan(0)
        end owned

    end Depth

    abstract class Transform[-A, +B, -S] extends Arrow[A, B, S]:
        def frame: Frame
        // v is Any rather than A: a typed parameter makes subclasses with a concrete
        // A carry an erasure bridge, and the extra call level halves how many fused
        // steps the JIT can inline per compilation.
        def run[C, S2](v: Any, cont: Arrow[B, C, S2]): C < (S & S2)
        override def toString = "Transform(" + frame.position.show + ")"
    end Transform

    final private[kyo] class AndThen[-A, B, +C, -S](
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        override def toString = "AndThen(" + a + ", " + b + ")"
    end AndThen

    private val empty = new Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont(v.asInstanceOf[Any < Any])

    // Spliced into long chains every Period elements by optimize: hops unwind here
    // via the returned Defer and evalLoop's trampoline drives the next segment, so
    // a resumed chain's stack depth is bounded by the segment size. The interior
    // hops carry no check; the cadence lives in the chain structure itself.
    private val segmentBoundary = new Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            Kyo.Defer(v, cont.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[C < (Any & S2)]

    def apply[A]: Arrow[A, A, Any] = empty.asInstanceOf[Arrow[A, A, Any]]

    def of[A, B, S](t: Transform[A, B, S]): Arrow[A, B, S] = t

    extension [A, B, S](self: Arrow[A, B, S])

        def apply[S2](v: A < S2): B < (S & S2) =
            if self.asInstanceOf[AnyRef] eq empty then
                v.asInstanceOf[B < (S & S2)]
            else if v.isInstanceOf[Kyo[?, ?]] then
                v.asInstanceOf[Kyo[A, S2]].map(self)
            else
                self match
                    case o: Offset[Any, Any, Any, Any] @unchecked =>
                        o.head.run(Kyo.unwrap(v), o.next).asInstanceOf[B < (S & S2)]
                    case t: Transform[A, B, S] @unchecked =>
                        guardedRun(t, v)
                    case _ =>
                        applySlow(self, v)

        def map[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
            if self.asInstanceOf[AnyRef] eq empty then f.asInstanceOf[Arrow[A, C, S & S2]]
            else if f.asInstanceOf[AnyRef] eq empty then self.asInstanceOf[Arrow[A, C, S & S2]]
            else new AndThen(self, f)

        def optimize: Arrow[A, B, S] =
            def respine(node: Arrow[?, ?, ?], rest: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
                node match
                    case at: AndThen[?, ?, ?, ?] =>
                        respine(at.a, respine(at.b, rest))
                    case t =>
                        new Offset(t.asInstanceOf[Transform[Any, Any, Any]], rest)
            end respine
            def unfold(at: AndThen[?, ?, ?, ?]): Arrow[Any, Any, Any] =
                val buffer = optimizeBuffer.get()
                buffer.clear()
                buffer.push(at)
                @tailrec def drain(pending: Int): Unit =
                    if pending > 0 then
                        buffer.pop() match
                            case inner: AndThen[?, ?, ?, ?] =>
                                buffer.push(inner.b)
                                buffer.push(inner.a)
                                drain(pending + 1)
                            case t =>
                                val _ = buffer.add(t)
                                drain(pending - 1)
                drain(1)
                val it = buffer.descendingIterator()
                @tailrec def link(acc: Arrow[Any, Any, Any], n: Int): Arrow[Any, Any, Any] =
                    if !it.hasNext then acc
                    else if n == Period then
                        link(new Offset(segmentBoundary, acc), 0)
                    else link(new Offset(it.next().asInstanceOf[Transform[Any, Any, Any]], acc), n + 1)
                val result = link(empty, 0)
                buffer.clear()
                result
            end unfold
            def count(node: Arrow[?, ?, ?], depth: Int): Int =
                if depth > SmallLimit then -1
                else
                    node match
                        case at: AndThen[?, ?, ?, ?] =>
                            val left = count(at.a, depth + 1)
                            if left < 0 then -1
                            else
                                val right = count(at.b, depth + 1)
                                if right < 0 || left + right > SmallLimit then -1
                                else left + right
                            end if
                        case _ =>
                            1
            end count
            self match
                case at: AndThen[?, ?, ?, ?] =>
                    if count(at, 0) > 0 then respine(at, empty).asInstanceOf[Arrow[A, B, S]]
                    else unfold(at).asInstanceOf[Arrow[A, B, S]]
                case _ =>
                    self
            end match
        end optimize

        /** Phase 1 of dispatch: decompose this arrow so the caller can execute its
          * first step in the caller's own bytecode via `s.head.run(v, s.next)`.
          * Absent for the identity arrow. An optimized chain is its own Step and
          * decomposes by identity; a composition is optimized first; a lone
          * transform is wrapped in a fresh node.
          */
        inline def step: Maybe[Step[A, B, S]] =
            self match
                case o: Offset[Any, Any, Any, Any] @unchecked =>
                    Maybe(o.asInstanceOf[Step[A, B, S]])
                case _ =>
                    stepSlow(self)

    end extension

    def stepSlow[A, B, S](self: Arrow[A, B, S]): Maybe[Step[A, B, S]] =
        self match
            case at: AndThen[?, ?, ?, ?] =>
                self.optimize.step
            case t: Transform[?, ?, ?] =>
                if t.asInstanceOf[AnyRef] eq empty then Maybe.Absent
                else Maybe(new Offset(t.asInstanceOf[Transform[Any, Any, Any]], empty).asInstanceOf[Step[A, B, S]])
    end stepSlow

    private def rescue[A, B, S, S2](self: Arrow[A, B, S], v: A < S2): B < (S & S2) =
        Kyo.Defer(Kyo.unwrap(v), self.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[B < (S & S2)]

    private def guardedRun[A, B, S, S2](t: Transform[A, B, S], v: A < S2): B < (S & S2) =
        val slot = Depth.slot()
        if Depth.increase(slot) >= Depth.Limit then rescue(t, v)
        else
            try
                val r = t.run(Kyo.unwrap(v), Arrow[B]).asInstanceOf[B < (S & S2)]
                Depth.decrease(slot)
                r
            catch
                case ex: Throwable =>
                    Depth.decrease(slot)
                    KyoException.attach(ex, "map", t.frame)
                    throw ex
        end if
    end guardedRun

    private def applySlow[A, B, S, S2](self: Arrow[A, B, S], v: A < S2): B < (S & S2) =
        self match
            case at: AndThen[?, ?, ?, ?] =>
                self.optimize(v)
            case _ =>
                Kyo.Defer(Kyo.unwrap(v), self.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[B < (S & S2)]
    end applySlow

    /** The pre-linked chain node: simultaneously an Arrow (it can be stored, composed,
      * driven) and the Step handle for its own position, so phase 1 decomposes it for
      * free by identity.
      */
    final class Offset[-A, X0, +B, -S] private[kyo] (
        val head: Transform[A, X0, S],
        val next: Arrow[X0, B, S]
    ) extends Transform[A, B, S], Step[A, B, S]:
        type X = X0
        def frame = Frame.internal

        def run[C, S2](v: Any, cont: Arrow[B, C, S2]): C < (S & S2) =
            val k = cont.asInstanceOf[Arrow[Any, Any, Any]]
            @tailrec def loop(o: Offset[Any, Any, Any, Any], cur: Any): Any =
                o.head match
                    case jump: Offset[Any, Any, Any, Any] @unchecked if isEmpty(o.next) =>
                        loop(jump, cur)
                    case t =>
                        val w =
                            try t.run(cur, empty)
                            catch
                                case ex: Throwable =>
                                    KyoException.attach(ex, "map", t.frame)
                                    throw ex
                        if w.isInstanceOf[Kyo[?, ?]] then
                            o.next.map(k)(w.asInstanceOf[Any < Any])
                        else
                            (o.next: Any) match
                                case n: Offset[Any, Any, Any, Any] @unchecked => loop(n, Kyo.unwrap(w))
                                case _                                        => k(Kyo.unwrap(w).asInstanceOf[Any < Any])
                        end if
            loop(this.asInstanceOf[Offset[Any, Any, Any, Any]], v).asInstanceOf[C < (S & S2)]
        end run

        override def toString = "Offset"
    end Offset

    private val optimizeBuffer = new ThreadLocal[java.util.ArrayDeque[Any]]:
        override def initialValue = new java.util.ArrayDeque[Any]

    private[kyo] def isEmpty[X, Y, Z](f: Arrow[X, Y, Z]): Boolean =
        f.asInstanceOf[AnyRef] eq empty

end Arrow

final private[kyo] class KyoException extends Exception(null, null, false, false):
    var frames: Chunk[(String, Frame)] = Chunk.empty
    var installed: Int                 = 0
    override def getMessage =
        frames.map((op, f) => "at " + KyoException.describe(op, f) + "(" + f.position.show + ")")
            .mkString("effect trace: ", "; ", "")
end KyoException

private[kyo] object KyoException:

    private inline def MaxFrames = 64

    def describe(op: String, f: Frame): String =
        val cls    = f.className.split('.').last.stripSuffix("$")
        val caller = if f.callerName == "$anonfun" then "<lambda>" else f.callerName
        op + " @ " + cls + "." + caller
    end describe

    def attach(ex: Throwable, op: String, frame: Frame): Unit =
        ex.getSuppressed.collectFirst { case o: KyoException => o } match
            case Some(o) =>
                o.frames = o.frames.append((op, frame))
                if o.frames.size > MaxFrames then
                    val dropped = o.frames.size - MaxFrames
                    o.frames = o.frames.dropLeft(dropped)
                    o.installed = Integer.max(0, o.installed - dropped)
                end if
            case None =>
                val o = new KyoException
                o.frames = Chunk((op, frame))
                ex.addSuppressed(o)

    def install(ex: Throwable): Unit =
        ex.getSuppressed.collectFirst { case o: KyoException => o } match
            case Some(o) if o.frames.size > o.installed =>
                val pending = o.frames.dropLeft(o.installed)
                val collapsed = pending.foldLeft(Chunk.empty[(String, Frame)]) { (acc, f) =>
                    if acc.nonEmpty && acc.last == f then acc else acc.append(f)
                }
                val fresh = collapsed.map { (op, f) =>
                    val cls    = f.className.split('.').last.stripSuffix("$")
                    val caller = if f.callerName == "$anonfun" then "<lambda>" else f.callerName
                    StackTraceElement(op + " @ " + cls, caller, f.position.fileName, f.position.lineNumber)
                }
                val user = ex.getStackTrace.filterNot(e => e.getClassName.startsWith("kyo.proto3"))
                ex.setStackTrace((fresh.toArray ++ user))
                o.installed = o.frames.size
            case _ =>
                ()
end KyoException
