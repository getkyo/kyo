package kyo.kernel2

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.kernel2.internal.EffectTrace
import kyo.kernel2.internal.Handlers
import kyo.kernel2.internal.Kyo
import kyo.kernel2.internal.LiftMacro.defaultLift
import kyo.kernel2.internal.Safepoint
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.publicInBinary
import scala.annotation.tailrec

sealed abstract class Arrow[-A, +B, -S]

object Arrow:

    // TODO write a doc explaning this mechanism using code snippets. Self-contained, direct, and clear. Then ask me to review
    // (delivered as kernel2-arrow-mechanism.md at the repo root, awaiting review)

    abstract class Transform[-A, +B, -S] extends Arrow[A, B, S]:
        def frame: Frame
        // context and handlers are the execution ambient, threaded from the caller:
        // plain transforms pass them through untouched, bindings pass an updated
        // context downstream, handled scopes pass extended handlers downstream, reads
        // and local operation dispatch consume them. Neither is ever stored; they
        // exist only in flight.
        def run[C, S2](v: A, context: Context, handlers: Handlers, cont: Arrow[B, C, S2]): C < (S & S2)
        override def toString = "Transform(" + frame.position.show + ")"
    end Transform

    final private[kyo] class AndThen[-A, B, +C, -S](
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        override def toString = internal.render(this, internal.RenderDepth)
    end AndThen

    /** The pre-linked chain node and the decomposition handle in one: `head` is the first executable transform, `next` the rest of
      * the chain, and the middle type connecting them is the second type parameter. Phase 1 of dispatch obtains it via [[step]];
      * phase 2 is `head.run(v, next)` written in the caller's own bytecode, where the receiver profile is private to that site.
      */
    final class Step[-A, B, +C, -S] private[kyo] (
        val head: Transform[A, B, S],
        val next: Arrow[B, C, S]
    ) extends Transform[A, C, S]:
        def frame = Frame.internal

        def run[C2, S2](v: A, context: Context, handlers: Handlers, cont: Arrow[C, C2, S2]): C2 < (S & S2) =
            val k = cont.asInstanceOf[Arrow[Any, Any, Any]]
            @tailrec def loop(o: Step[Any, Any, Any, Any], cur: Any): Any =
                o.head match
                    case jump: Step[Any, Any, Any, Any] @unchecked if isEmpty(o.next) =>
                        loop(jump, cur)
                    case t =>
                        val w =
                            try t.run(cur, context, handlers, empty)
                            catch
                                case ex: Throwable =>
                                    EffectTrace.attach(ex, "map", t.frame)
                                    throw ex
                        if w.isInstanceOf[Kyo[?, ?]] then
                            o.next.map(k)(w.asInstanceOf[Any < Any], context, handlers)
                        else
                            o.next match
                                case n: Step[Any, Any, Any, Any] @unchecked => loop(n, Kyo.unnest(w))
                                case _                                      => k(Kyo.unnest(w).asInstanceOf[Any < Any], context, handlers)
                        end if
            loop(this.asInstanceOf[Step[Any, Any, Any, Any]], v).asInstanceOf[C2 < (S & S2)]
        end run

        override def toString = internal.render(this, internal.RenderDepth)
    end Step

    private val empty = new Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            // the central lift, not a cast: a raw value that is itself a computation must
            // re-enter the chain as data (Nested), not as a suspension to run
            cont(defaultLift(v), context, handlers)

    // Spliced into long chains every Period elements by optimize: hops unwind here
    // via the returned Defer and evalLoop's trampoline drives the next segment, so
    // a resumed chain's stack depth is bounded by the segment size. The interior
    // hops carry no check; the cadence lives in the chain structure itself.
    private val segmentBoundary = new Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            Kyo.Defer(defaultLift(v), cont.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[C < (Any & S2)]

    def apply[A]: Arrow[A, A, Any] = empty.asInstanceOf[Arrow[A, A, Any]]

    private[kyo] inline def isEmpty[A, B, S](f: Arrow[A, B, S]): Boolean =
        f.asInstanceOf[AnyRef] eq empty

    private def rescue[A, B, S, S2](self: Arrow[A, B, S], v: A < S2): B < (S & S2) =
        Kyo.Defer(v, self)

    private def guardedRun[A, B, S, S2](t: Transform[A, B, S], v: A < S2, context: Context, handlers: Handlers): B < (S & S2) =
        val safepoint = Safepoint.get
        if !safepoint.enter() then rescue(t, v)
        else
            try
                val r = t.run(Kyo.unnest(v).asInstanceOf[A], context, handlers, Arrow[B]).asInstanceOf[B < (S & S2)]
                safepoint.exit()
                r
            catch
                case ex: Throwable =>
                    safepoint.exit()
                    EffectTrace.attach(ex, "map", t.frame)
                    throw ex
        end if
    end guardedRun

    private def applySlow[A, B, S, S2](self: Arrow[A, B, S], v: A < S2, context: Context, handlers: Handlers): B < (S & S2) =
        self match
            case at: AndThen[?, ?, ?, ?] =>
                self.optimize(v, context, handlers)
            case _ =>
                Kyo.Defer(v, self)
    end applySlow

    private[kyo] def stepSlow[A, B, S](self: Arrow[A, B, S]): Maybe[Step[A, ?, B, S]] =
        self match
            case at: AndThen[?, ?, ?, ?] =>
                self.optimize.step
            case t: Transform[?, ?, ?] =>
                if isEmpty(t) then Maybe.Absent
                else Maybe(new Step(t.asInstanceOf[Transform[Any, Any, Any]], empty).asInstanceOf[Step[A, ?, B, S]])
    end stepSlow

    /** The cold machinery: diagnostics rendering and the normalization working set. Nothing here executes on the dispatch paths. */
    private[kyo] object internal:

        inline def SmallLimit = 32

        // Rendering is diagnostics-facing (test failure output, hang dumps) and must stay
        // cheap on arbitrarily long chains: the walk is depth-bounded, never O(chain), so
        // a renderer invoked on a hundred-thousand-node chain terminates immediately.
        inline def RenderDepth = 8

        def render(a: Arrow[?, ?, ?], depth: Int): String =
            if depth <= 0 then "..."
            else
                a match
                    case o: Step[?, ?, ?, ?] =>
                        "Step(" + render(o.head, depth - 1) + ", " + render(o.next, depth - 1) + ")"
                    case at: AndThen[?, ?, ?, ?] =>
                        "AndThen(" + render(at.a, depth - 1) + ", " + render(at.b, depth - 1) + ")"
                    case t =>
                        t.toString

        val optimizeBuffer = new ThreadLocal[java.util.ArrayDeque[Any]]:
            override def initialValue = new java.util.ArrayDeque[Any]

    end internal

    extension [A, B, S](self: Arrow[A, B, S])

        /** Applies this arrow under the ambient parameters of the site where the result is embedded.
          *
          * Application constructs a Defer; the arrow runs when a drive pops it. Bindings and handlers enclosing the embedding site
          * rotate around the node as it parks outward, so the arrow executes under exactly the parameters in scope there: resume-time
          * semantics without asking the caller for them. Execution paths use the parameter-passing form, never this one.
          */
        def apply[S2](v: A < S2): B < (S & S2) =
            if isEmpty(self) then
                v.asInstanceOf[B < (S & S2)]
            else if v.isInstanceOf[Kyo[?, ?]] then
                v.asInstanceOf[Kyo[A, S2]].map(self)
            else
                Kyo.Defer(v, self)

        /** The execution form: applies this arrow now, under the context and handlers the caller is executing with.
          *
          * Every internal execution site (drives, dispatch, fused chains) uses this form and passes the parameters it received.
          * `Context.empty` and `Handlers.empty` are fabricated only at the true roots (eval, evalPartial) and at construction-time
          * eager runs of kernel-minted transforms, which cannot consume them.
          */
        // private[kyo] with binary-public linkage: the minted transforms reference this from
        // inline-expanded code at user sites, and a plain private[kyo] made dotty emit inline$
        // accessor forwarders there, measured at +40B/op. publicInBinary keeps the direct call.
        @publicInBinary private[kyo] def apply[S2](v: A < S2, context: Context, handlers: Handlers): B < (S & S2) =
            // a separate method so the cold branch does not weigh down apply's inlined body
            def dispatchLocal(kyo: Kyo[Any, Any]): B < (S & S2) =
                kyo match
                    case s: Kyo.NeverResumed[?, ?, ?, ?, ?, ?] =>
                        // no handler can resume it: nothing after the operation can run
                        s.asInstanceOf[B < (S & S2)]
                    case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] if !handlers.isEmpty =>
                        handlers.resolve(s.erasedTag) match
                            case Maybe.Present(r: Handlers.Entry.Resume) =>
                                // answer in place and keep going forward
                                self(ArrowEffect.answerNow(s, r, context, handlers).asInstanceOf[A < S2], context, handlers)
                            case Maybe.Present(_: Handlers.Entry.Stop) =>
                                // the skip: every frame from here to the handler is dead, the innermost
                                // matching loop's matched arm answers on arrival
                                s.asInstanceOf[B < (S & S2)]
                            case _ =>
                                // Absent, or a shadow entry: structural travel
                                s.asInstanceOf[Kyo[A, S2]].map(self)
                    case kyo =>
                        kyo.asInstanceOf[Kyo[A, S2]].map(self)
            if isEmpty(self) then
                v.asInstanceOf[B < (S & S2)]
            else if v.isInstanceOf[Kyo[?, ?]] then
                // the one place suspensions bubble: execution dispatches the operation at the
                // point it surfaced. The hot shape stays two checks; the dispatch is cold.
                if handlers.isEmpty && !v.isInstanceOf[Kyo.NeverResumed[?, ?, ?, ?, ?, ?]] then
                    v.asInstanceOf[Kyo[A, S2]].map(self)
                else
                    dispatchLocal(v.asInstanceOf[Kyo[Any, Any]])
            else
                self match
                    case o: Step[Any, Any, Any, Any] @unchecked =>
                        o.head.run(Kyo.unnest(v), context, handlers, o.next).asInstanceOf[B < (S & S2)]
                    case t: Transform[A, B, S] @unchecked =>
                        guardedRun(t, v, context, handlers)
                    case _ =>
                        applySlow(self, v, context, handlers)
            end if
        end apply

        def map[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
            if isEmpty(self) then f.asInstanceOf[Arrow[A, C, S & S2]]
            else if isEmpty(f) then self.asInstanceOf[Arrow[A, C, S & S2]]
            else new AndThen(self, f)

        private[kyo] def optimize: Arrow[A, B, S] =
            def linearize(node: Arrow[?, ?, ?], rest: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
                node match
                    case at: AndThen[?, ?, ?, ?] =>
                        linearize(at.a, linearize(at.b, rest))
                    case t =>
                        new Step(t.asInstanceOf[Transform[Any, Any, Any]], rest)
            end linearize
            def unfold(at: AndThen[?, ?, ?, ?]): Arrow[Any, Any, Any] =
                val buffer = internal.optimizeBuffer.get()
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
                    else if n == Safepoint.Period then
                        link(new Step(segmentBoundary, acc), 0)
                    else link(new Step(it.next().asInstanceOf[Transform[Any, Any, Any]], acc), n + 1)
                val result = link(empty, 0)
                buffer.clear()
                result
            end unfold
            // the remaining node budget, or -1 once exhausted: decremented on every node,
            // interior or leaf, so the walk and its recursion depth stay bounded even on a
            // deep left spine, where the first leaf appears only after the full descent. A
            // chain of n transforms has 2n-1 nodes, so the doubled limit keeps the leaf
            // capacity at SmallLimit
            def fits(node: Arrow[?, ?, ?], budget: Int): Int =
                if budget < 0 then -1
                else
                    node match
                        case at: AndThen[?, ?, ?, ?] => fits(at.b, fits(at.a, budget - 1))
                        case _                       => budget - 1
            self match
                case at: AndThen[?, ?, ?, ?] =>
                    // a small chain linearizes with plain recursion, skipping the working
                    // buffer; segment boundaries only matter past Safepoint.Period, far above
                    // SmallLimit, so the small path never needs them
                    if fits(at, 2 * internal.SmallLimit) >= 0 then linearize(at, empty).asInstanceOf[Arrow[A, B, S]]
                    else unfold(at).asInstanceOf[Arrow[A, B, S]]
                case _ =>
                    self
            end match
        end optimize

        /** Phase 1 of dispatch: decompose this arrow so the caller can execute its
          * first step in the caller's own bytecode via `s.head.run(v, s.next)`.
          * Absent for the identity arrow. An optimized chain is its own decomposition;
          * a composition is optimized first; a lone transform is wrapped in a fresh node.
          */
        private[kyo] inline def step: Maybe[Step[A, ?, B, S]] =
            self match
                case o: Step[Any, Any, Any, Any] @unchecked =>
                    Maybe(self.asInstanceOf[Step[A, ?, B, S]])
                case _ =>
                    stepSlow(self)

    end extension

end Arrow
