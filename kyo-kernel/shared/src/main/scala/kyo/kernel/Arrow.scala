package kyo.kernel

import kyo.Frame
import kyo.kernel.internal.Debugger
import kyo.kernel.internal.Kyo
import kyo.kernel.internal.Nested
import kyo.kernel.internal.Pending
import kyo.kernel.internal.Safepoint
import kyo.kernel.internal.short
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.annotation.targetName

/** A transformation from an `A` to a `B` that may perform `S` on the way, reified as a value.
  *
  * A computation is what you compose and a handler is what consumes it. An arrow is the third piece: a transformation that outlives the
  * expression which built it, so it can be stored, passed around, composed with another, and applied whenever its holder decides.
  *
  * Applying an arrow answers a computation rather than a value, which is how an arrow performs effects of its own: a body that suspends
  * `Ask` gives an `Arrow[Int, Int, Ask]`, and applying it gives an `Int < Ask`. [[chain]] composes two by feeding the first result into the
  * second and intersecting both rows, with [[Arrow.id]] as the neutral element of that composition.
  *
  * Because an arrow outlives its application, building one ahead of time is also a performance tool. A transformation hoisted into a `val`
  * is allocated once and applied as many times as needed rather than rebuilt on every round of the loop or call that uses it, and a
  * pipeline pre-composed with [[chain]] pays for its composition once as well. [[Arrow.recursive]] is the same idea for a step that
  * re-enters itself.
  *
  * The two-argument [[apply]] is where the module's throughput comes from. A continuation handed in as an argument is applied at the call
  * site that already has the transformation inlined into it, so the JIT fuses a chain of steps into straight-line code instead of routing
  * each one back through the evaluator. [[head]] and [[tail]] are what let a composed arrow take part in that.
  *
  * A handler clause receives its continuation as an arrow of this same type, composed and applied like any other and as many times as the
  * clause likes, which is what makes multi-shot continuations ordinary here rather than a separate capability.
  *
  * IMPORTANT: the continuation a clause receives carries [[Region.NoEscape]] in its row, confining it to that clause. An arrow built with
  * [[Arrow.apply]] carries no such marker and goes wherever it is sent.
  *
  * @tparam A
  *   The value the arrow accepts
  * @tparam B
  *   The value it produces
  * @tparam S
  *   The effects it may perform while producing that value
  *
  * @see
  *   [[Arrow.apply]] For building an arrow from an ordinary function
  * @see
  *   [[Arrow.recursive]] For an arrow whose body can re-enter it
  * @see
  *   [[Arrow.id]] For the arrow that returns its input untouched
  * @see
  *   [[Region.NoEscape]] For what confines the continuation a clause receives
  * @see
  *   [[ArrowEffect.handleCont]] For the handler that hands a continuation to its clause
  */
sealed trait Arrow[-A, +B, -S] extends Kyo[B, S]:

    /** Applies this arrow to a value already in hand, answering a computation because the arrow may perform `S` on the way.
      *
      * This is the plain application, and the whole of [[chain]] is visible in it: a composed arrow hands the value to its first link with
      * the second passed along behind it, so no node is built for the composition itself.
      */
    def apply(v: A): B < S =
        Debugger.onUnfused(this)
        this.head(v, this.tail)

    /** Applies this arrow to a computation, with nothing composed after it. The arrow is the receiver, so a value needs no arrow-shaped
      * method of its own.
      *
      * `@targetName` because this erases to the same signature as `apply(v: A)`: a raw `A` is also an `A < S2`, the union's first arm.
      */
    @targetName("applyPending")
    def apply[S2](v: A < S2): B < (S & S2) =
        this(v, Arrow.id)

    /** Composes this arrow with another, feeding this arrow's result into `a` and intersecting both rows.
      *
      * Composing with [[Arrow.id]] on either side answers the other arrow unchanged, so a fold over a collection of arrows starting from
      * `id` allocates nothing for the empty and single-element cases.
      */
    def chain[C, S2](a: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if this.isInstanceOf[Arrow.Id[?]] then
            a.asInstanceOf[Arrow[A, C, S & S2]]
        else if a.isInstanceOf[Arrow.Id[?]] then
            this.asInstanceOf[Arrow[A, C, S & S2]]
        else
            Arrow.Chain(this, a)

    /** Applies this arrow to a computation with `cont` composed after it, so the result never becomes a value in between.
      *
      * Taking the rest of the computation as an argument is what lets the JIT fuse a chain of transformations into straight-line code. Every
      * `map` and every [[Arrow.apply]] expands to a class of its own with the body inlined into `apply`, so the receiver at each of those
      * sites is a single concrete type. Handing the continuation in means the next step is applied from that same site, where the JIT can
      * inline through it and fuse the chain. Answering with the intermediate value instead would send every step back through the
      * evaluator's loop, which is far too large to inline and sees every effect in the program, so nothing downstream of it would fuse.
      *
      * An implementation reaches the next step as `cont.head(result, cont.tail)` rather than `cont(result)`, which is what stops a
      * composition from breaking the chain: for a composed continuation that runs the first link with the second behind it, and for an atom
      * it is the atom applied with [[Arrow.id]] behind it, the same expression either way. Calling `cont` directly would reach the
      * composition node, which can only build a node and hand it back to the evaluator, ending the fusion at every composition boundary.
      *
      * It is also what keeps a deferral to one node: an arrow that must defer builds a single node carrying both halves, rather than a node
      * plus a composition for what follows it.
      *
      * Ordinary code wants `arrow(value)`; this is for callers that already hold a continuation.
      */
    def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2)

    /** The intermediate type of this arrow's own composition: what [[head]] produces and [[tail]] accepts. */
    type X

    /** The half of this arrow's composition that does work when the arrow is applied.
      *
      * For a composed arrow this is its first link; for an atom it is the arrow itself, with [[tail]] the identity. That uniformity is what
      * makes the fused application in [[apply]] possible: a site writes `cont.head(value, cont.tail)` once and it is correct for both, so
      * the receiver is always an arrow that does work and never a composition node that would only defer. Composition costs nothing at the
      * point of application, and it does not break the chain the JIT is inlining through.
      *
      * Public because the inline expansions making up the module's hot paths have to reach it, not as an invitation. Reach for [[chain]] to
      * compose and `arrow(value)` to apply.
      */
    def head: Arrow[A, X, S]

    /** The half of this arrow's composition passed along as the continuation when [[head]] is applied, [[Arrow.id]] for an atom.
      *
      * See [[head]] for why both are public and what the split buys.
      */
    def tail: Arrow[X, B, S]
end Arrow

object Arrow:

    /** The arrow that returns its input untouched.
      *
      * Reach for [[id]] rather than constructing one: a single instance is shared across every type, and [[Arrow.chain]] recognizes it by
      * identity to collapse the composition instead of building a node for it.
      */
    class Id[A] private[Arrow] () extends Step[A, A, Any]:
        def frame                         = Frame.internal
        override def apply(v: A): A < Any = v
        def apply[C, S2](v: A < S2, cont: Arrow[A, C, S2]) =
            if cont.isInstanceOf[Arrow.Id[?]] then
                v.asInstanceOf[C < S2]
            else
                cont(v, Arrow.id)
        override def toString = "Id"
    end Id

    private val identity = Id[Any]()

    /** The arrow that returns its input untouched, the neutral element of [[Arrow.chain]]. */
    def id[A]: Id[A] = identity.asInstanceOf[Id[A]]

    /** Builds an arrow from an ordinary function, reifying the transformation as a value that can be stored, composed and applied later.
      *
      * `f` may itself suspend, which is how an arrow comes to carry effects in `S`: a body performing `Ask` gives an `Arrow[A, B, Ask]`.
      *
      * The function is inlined into a fresh class at each call site, so an arrow costs one allocation and reaching its body costs no
      * indirection through a function object.
      *
      * @param f
      *   The transformation, which may perform effects of its own
      */
    @nowarn("msg=anonymous")
    inline def apply[A](using _frame: Frame)[B, S](inline f: A => B < S): Arrow[A, B, S] =
        new Step[A, B, S]:
            def frame                = _frame
            override def apply(v: A) = f(v)
            def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]) =
                v match
                    case v: Pending[A, S2] @unchecked =>
                        Effect.defer(v, this, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, this, cont)
                        else
                            val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if

    /** Builds an arrow that applies without polling the safepoint, so `f` runs as the value arrives with nothing schedulable in between.
      *
      * [[Arrow.apply]] polls before applying its function, so an interrupt pending when the value arrives parks the computation and the
      * function never runs. That is right nearly everywhere, and wrong where `f` records an obligation the value itself just created: the
      * resource is open, its release is not registered, and a park landing between the two loses it.
      *
      * Reach for it only for that pairing, a resource opened and its release registered, or a fiber spawned and its handle stored. Skipping
      * the poll also means the computation cannot be preempted at that point, so [[Arrow.apply]] is right everywhere else.
      *
      * @param f
      *   The transformation, run as the value arrives
      */
    @nowarn("msg=anonymous")
    inline def ensure[A](using _frame: Frame)[B, S](inline f: A => B < S): Arrow[A, B, S] =
        new Ensure[A, B, S]:
            def frame                = _frame
            override def apply(v: A) = f(v)

    /** Builds an arrow whose body receives the arrow being defined alongside the value, so a step that loops can re-enter itself.
      *
      * Naming `self` rather than rebuilding the arrow per round means one allocation for the whole loop.
      *
      * @param f
      *   The transformation, taking the arrow being defined and the input value
      */
    @nowarn("msg=anonymous")
    inline def recursive[A, B, S](inline f: (Arrow[A, B, S], A) => B < S)(using _frame: Frame): Arrow[A, B, S] =
        new Step[A, B, S]:
            def frame                = _frame
            override def apply(v: A) = f(this, v)
            def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]) =
                v match
                    case v: Pending[A, S2] @unchecked =>
                        Effect.defer(v, this, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, this, cont)
                        else
                            val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if

    private[kyo] trait Transform[-A, B, -S] extends Arrow[A, B, S]:
        type X = B
        def head = this
        def tail = Arrow.id
    end Transform

    /** Abstract rather than concrete so each construction site implements `apply` anonymously with its function inlined into the body, which
      * specializes the arrow to its site instead of dispatching through a function object.
      */
    abstract private[kyo] class Step[-A, B, -S] extends Transform[A, B, S]:
        Debugger.onAlloc(this)
        override def toString = s"Step(${frame.callSite})"
    end Step

    abstract private[kyo] class Ensure[-A, B, -S] extends Step[A, B, S]:
        override def apply(v: A): B < S

        override def toString = s"Ensure(${frame.callSite})"

        final def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2) =
            v match
                case v: Pending[A, S2] @unchecked =>
                    Effect.defer(v, this, cont)
                case _ =>
                    cont.head(apply(Nested.unnest(v)), cont.tail)
    end Ensure

    /** The composition node, and the only arrow that is not its own head.
      *
      * It does no work itself, so applying one can only build a node and hand it back to the evaluator. The hot paths pull it apart through
      * [[Arrow.head]] and [[Arrow.tail]] instead, which is what keeps composition free at the point of application.
      */
    final private[kyo] class Chain[A, B, C, S] private[kernel] (
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        Debugger.onAlloc(this)
        def frame = Frame.internal
        def apply[D, S2](v: A < S2, cont: Arrow[C, D, S2]) =
            Effect.defer(v, this, cont)

        type X = B
        def head = a
        def tail = b
        override def toString: String =
            val out = new StringBuilder
            @tailrec def render(pending: List[Arrow[?, ?, ?] | String], fuel: Int): Unit =
                pending match
                    case (s: String) :: rest =>
                        out.append(s)
                        render(rest, fuel)
                    case (link: Arrow[?, ?, ?]) :: rest =>
                        link match
                            case c: Chain[?, ?, ?, ?] if fuel > 0 =>
                                out.append("Chain(")
                                render(c.a :: ", " :: c.b :: ")" :: rest, fuel - 1)
                            case _: Chain[?, ?, ?, ?] =>
                                out.append("...")
                                render(rest, fuel)
                            case other =>
                                out.append(short(other))
                                render(rest, fuel)
                    case _ => ()
            render(this :: Nil, 32)
            out.result()
        end toString
    end Chain

end Arrow
