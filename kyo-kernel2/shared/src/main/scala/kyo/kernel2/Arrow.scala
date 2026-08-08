package kyo.kernel2

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.KyoException
import kyo.kernel2.internal.Safepoint
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

sealed abstract class Arrow[-A, +B, -S]:
    /** Whether any delimiter lives in this arrow: lets dispatch skip handler-free subtrees. */
    // TODO can we avoid?
    private[kyo] def hasHandler: Boolean
end Arrow

object Arrow:

    /** A decomposed arrow: one executable transform and the rest of the chain. Phase 1
      * of dispatch obtains it via [[step]]; phase 2 is `head.run(v, next)` written in
      * the caller's own bytecode, where the receiver profile is private to that site.
      * Mid is the intermediate type between head and next; callers never name it.
      */
    sealed private[kyo] trait Step[-A, +B, -S]:
        type Mid
        def head: Transform[A, Mid, S]
        def next: Arrow[Mid, B, S]
    end Step

    // TODO this is a Safepoint concern
    private[kyo] inline def Period = 512
    private inline def SmallLimit  = 32

    abstract class Transform[-A, +B, -S] extends Arrow[A, B, S]:
        private[kyo] def hasHandler: Boolean = false
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
        override private[kyo] val hasHandler = a.hasHandler || b.hasHandler
        override def toString                = render(this, RenderDepth)
    end AndThen

    // Rendering is diagnostics-facing (test failure output, hang dumps) and must stay
    // cheap on arbitrarily long chains: the walk is depth-bounded, never O(chain), so
    // a renderer invoked on a hundred-thousand-node chain terminates immediately.
    private inline def RenderDepth = 8

    private def render(a: Arrow[?, ?, ?], depth: Int): String =
        if depth <= 0 then "..."
        else
            a match
                case o: Offset[?, ?, ?, ?] =>
                    "Offset(" + render(o.head, depth - 1) + ", " + render(o.next, depth - 1) + ")"
                case at: AndThen[?, ?, ?, ?] =>
                    "AndThen(" + render(at.a, depth - 1) + ", " + render(at.b, depth - 1) + ")"
                case t =>
                    t.toString

    private val empty = new Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            // Kyo.lift, not a cast: a raw value that is itself a computation must
            // re-enter the chain as data (Nested), not as a suspension to run
            cont(Kyo.lift(v))

    // Spliced into long chains every Period elements by optimize: hops unwind here
    // via the returned Defer and evalLoop's trampoline drives the next segment, so
    // a resumed chain's stack depth is bounded by the segment size. The interior
    // hops carry no check; the cadence lives in the chain structure itself.
    private val segmentBoundary = new Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            Kyo.Defer(Kyo.lift(v), cont.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[C < (Any & S2)]

    def apply[A]: Arrow[A, A, Any] = empty.asInstanceOf[Arrow[A, A, Any]]

    extension [A, B, S](self: Arrow[A, B, S])

        def apply[S2](v: A < S2): B < (S & S2) =
            if self.asInstanceOf[AnyRef] eq empty then
                v.asInstanceOf[B < (S & S2)]
            else if v.isInstanceOf[Kyo[?, ?]] then
                v.asInstanceOf[Kyo[A, S2]].map(self)
            else
                self match
                    case o: Offset[Any, Any, Any, Any] @unchecked =>
                        o.head.run(Kyo.unnest(v), o.next).asInstanceOf[B < (S & S2)]
                    case t: Transform[A, B, S] @unchecked =>
                        guardedRun(t, v)
                    case _ =>
                        applySlow(self, v)

        def map[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
            if self.asInstanceOf[AnyRef] eq empty then f.asInstanceOf[Arrow[A, C, S & S2]]
            else if f.asInstanceOf[AnyRef] eq empty then self.asInstanceOf[Arrow[A, C, S & S2]]
            else new AndThen(self, f)

        private[kyo] def optimize: Arrow[A, B, S] =
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
        private[kyo] inline def step: Maybe[Step[A, B, S]] =
            self match
                case o: Offset[Any, Any, Any, Any] @unchecked =>
                    Maybe(o.asInstanceOf[Step[A, B, S]])
                case _ =>
                    stepSlow(self)

    end extension

    private[kyo] def stepSlow[A, B, S](self: Arrow[A, B, S]): Maybe[Step[A, B, S]] =
        self match
            case at: AndThen[?, ?, ?, ?] =>
                self.optimize.step
            case t: Transform[?, ?, ?] =>
                if t.asInstanceOf[AnyRef] eq empty then Maybe.Absent
                else Maybe(new Offset(t.asInstanceOf[Transform[Any, Any, Any]], empty).asInstanceOf[Step[A, B, S]])
    end stepSlow

    private def rescue[A, B, S, S2](self: Arrow[A, B, S], v: A < S2): B < (S & S2) =
        Kyo.Defer(v, self.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[B < (S & S2)]

    private def guardedRun[A, B, S, S2](t: Transform[A, B, S], v: A < S2): B < (S & S2) =
        val safepoint = Safepoint.get
        if !safepoint.enter() then rescue(t, v)
        else
            try
                val r = t.run(Kyo.unnest(v), Arrow[B]).asInstanceOf[B < (S & S2)]
                safepoint.exit()
                r
            catch
                case ex: Throwable =>
                    safepoint.exit()
                    KyoException.attach(ex, "map", t.frame)
                    throw ex
        end if
    end guardedRun

    private def applySlow[A, B, S, S2](self: Arrow[A, B, S], v: A < S2): B < (S & S2) =
        self match
            case at: AndThen[?, ?, ?, ?] =>
                self.optimize(v)
            case _ =>
                Kyo.Defer(v, self.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[B < (S & S2)]
    end applySlow

    /** The pre-linked chain node: simultaneously an Arrow (it can be stored, composed,
      * driven) and the Step handle for its own position, so phase 1 decomposes it for
      * free by identity.
      */
    final class Offset[-A, B, +C, -S] private[kyo] (
        val head: Transform[A, B, S],
        val next: Arrow[B, C, S]
    ) extends Transform[A, C, S], Step[A, C, S]:
        override private[kyo] val hasHandler = head.hasHandler || next.hasHandler
        type Mid = B
        def frame = Frame.internal

        def run[C2, S2](v: Any, cont: Arrow[C, C2, S2]): C2 < (S & S2) =
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
                            o.next match
                                case n: Offset[Any, Any, Any, Any] @unchecked => loop(n, Kyo.unnest(w))
                                case _                                        => k(Kyo.unnest(w).asInstanceOf[Any < Any])
                        end if
            loop(this.asInstanceOf[Offset[Any, Any, Any, Any]], v).asInstanceOf[C2 < (S & S2)]
        end run

        override def toString = render(this, RenderDepth)
    end Offset

    private val optimizeBuffer = new ThreadLocal[java.util.ArrayDeque[Any]]:
        override def initialValue = new java.util.ArrayDeque[Any]

    private[kyo] def isEmpty[A, B, S](f: Arrow[A, B, S]): Boolean =
        f.asInstanceOf[AnyRef] eq empty

end Arrow
