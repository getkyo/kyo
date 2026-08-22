package kyo

import kyo.Frame
import kyo.kernel.*
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

// deliberately not a Function1. Function1 is @specialized on both parameters, so every class that
// mixes it in emits the whole apply$mcXY$sp forwarder grid: 26 methods, measured at 19776 definitions
// across this module and not one genuine call site. Handlers take an Arrow directly instead, which
// also spares the eval an eta-expansion per suspension
sealed trait Arrow[-A, +B, -S]:
    self =>

    def frame: Frame

    def apply(v: A): B < S

    def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2)

    final def chain[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if f eq Arrow.Id then this.asInstanceOf[Arrow[A, C, S]]
        else new Arrow.Chain(this, f)

    type X
    def head: Arrow[A, X, S]
    def tail: Arrow[X, B, S]

end Arrow

object Arrow:

    def id[A]: Arrow.Id[A] = Id.asInstanceOf[Id[A]]

    @nowarn
    inline def apply[A, B, S](inline f: A => B < S)(using _frame: Frame): Arrow[A, B, S] =
        new TransformBase[A, B, S]:
            def frame                = _frame
            override def apply(v: A) = f(v)
            def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]) =
                v match
                    case kyo: Kyo[A, S2] @unchecked =>
                        Effect.defer(kyo, this, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, this, cont)
                        else
                            val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if

    @nowarn
    inline def recursive[A, B, S](inline f: (Arrow[A, B, S], A) => B < S)(using _frame: Frame): Arrow[A, B, S] =
        new TransformBase[A, B, S]:
            def frame                = _frame
            override def apply(v: A) = f(this, v)
            def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]) =
                v match
                    case kyo: Kyo[A, S2] @unchecked =>
                        Effect.defer(kyo, this, cont)
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
        def tail = Arrow.id[B]

        def apply(v: A) = this(v, Arrow.id[B])

        override def toString: String = s"Arrow(${frame.position.show}, ${frame.snippetShort})"
    end Transform

    /** A plain transformation: one link, and nothing on the stack ever looks for it.
      *
      * `Transform` says only that something is a single link. That is true of a map body and equally true of a
      * handler, a binding and a finalizer, but the latter are found by scanning the entries: `Stack.find` looks
      * for a handler, `lookup` and `resolve` for a binding, the drain for a finalizer. Fold one of those into an
      * arrow and the scan stops finding it.
      *
      * A `Step` carries no such obligation, so a run of them can be held folded as a single entry. That is what
      * `AndThen` is, and its head being a `Step` is what keeps a region out of it.
      *
      * A trait rather than a class because six sites fuse a step onto a `Kyo` node, and `Kyo` is a class.
      *
      * Also a `Cont`, because a single step already is a normalized continuation: its head is itself and
      * there is nothing after it. That is what lets a fold of one entry hand back the step untouched, so it
      * applies inline rather than through a node that exists only to terminate the run.
      */
    private[kyo] trait Step[-A, B, -S] extends Transform[A, B, S], Cont[A, B, S]

    /** A region marker: one link whose presence among the entries is what makes it work.
      *
      * `Handler`, `Catching`, `Binding` and `Finalizer`. Deliberately not a `Step`, so the type system keeps it
      * out of an `AndThen` and it cannot be folded out of sight.
      *
      * A trait for the same reason `Step` is: `Finalizer` extends `AtomicBoolean` and `Catching` and `Binding`
      * extend `Kyo`, so none of them can take a second superclass.
      */
    private[kyo] trait Region[-A, B, -S] extends Transform[A, B, S]

    /** `Step` as a class, for the sites that mint a standalone arrow.
      *
      * A mixin forwarder is emitted into every class that mixes a trait in, for each concrete trait member not
      * already implemented in a superclass. Since `Arrow` and `Transform` are both traits, an anonymous
      * `new Transform` is the first class in its chain and emits one for `head`, `tail`, `apply`, `toString` and
      * `chain`. Extending this instead inherits them, and an anonymous subclass carries only what it implements.
      *
      * `Transform` stays a trait because the sites that fuse an arrow into a `Kyo` node mix it onto that node's
      * class, and those cannot take a second superclass.
      */
    abstract private[kyo] class TransformBase[-A, B, -S] extends Step[A, B, S]

    /** The step a bracket's pending acquire settles into: applying it is what turns the resource into the
      * scope that owes its release, so the eval must never end a slice between the two. The eval's park
      * guard refuses to park a settled value about to flow into one of these, and the apply below runs
      * without the budget gate: with a stop pending the budget stays drained, so a gated apply would defer
      * against that refusal forever. The body allocates one node and runs no user code, which is what makes
      * skipping the gate safe.
      */
    abstract private[kyo] class BindingStep[A, B, -S] extends TransformBase[A, B, S]:
        // re-abstracted: the settled arm below calls it, and the inherited delegation through
        // `this(v, id)` would recurse
        override def apply(v: A): B < S
        def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2) =
            v match
                case kyo: Kyo[A, S2] @unchecked => Effect.defer(kyo, this, cont)
                case _                          => cont(apply(Nested.unnest(v)), Arrow.id)
    end BindingStep

    /** A normalized continuation: an `AndThen` or an `Id`, and nothing else.
      *
      * Sealed to exactly those two, so a value of this type is a straight run of steps ending in identity, all
      * the way down rather than only at the head. That is the property the stack relies on to hold one whole:
      * it is already in the shape the entries want, so flattening it would rebuild what was just built, and
      * there is nothing inside it for a scan to miss.
      */
    sealed private[kyo] trait Cont[-A, +B, -S] extends Arrow[A, B, S]

    /** A straight chain of transformations, built once by `dump` and thereafter pointed at.
      *
      * The head is a `Step` and the tail is another normalized continuation, so neither a region nor an
      * arbitrary nesting can occur inside one. Contrast `Chain`, which is how a computation accumulates while
      * it is being built and is deliberately unnormalized so that accumulating stays O(1) per combinator.
      */
    final private[kyo] class AndThen[-A, B, +C, -S](
        val t: Step[A, B, S],
        val cont: Cont[B, C, S]
    ) extends Cont[A, C, S]:
        type X = B
        def head  = t
        def tail  = cont
        def frame = Frame.internal

        // both peel the first step off rather than deferring with the whole run, which is what a chain does.
        // A chain can defer with itself because pushing it takes it apart again; this is stored whole, on
        // purpose, so deferring with itself would push the same run back and arrive here again having made no
        // progress.
        //
        // The peeled step is applied directly rather than through a deferral: the eval's round trip for
        // `Effect.defer(v, t, cont)` ends in exactly `t(v, cont)` (push cont, push t, settled delivery), so
        // this is the same law without the node, and the step's own apply carries the budget check that
        // makes it safe. The payload handling is unchanged: the same lift the deferring form applied to `v`
        // fires on the same argument here
        def apply(v: A)                                    = t(v, cont)
        def apply[D, S2](v: A < S2, cont: Arrow[C, D, S2]) = t(v, this.cont.chain(cont))

        override def toString: String = s"Arrow.AndThen($t, $cont)"
    end AndThen

    // the evaluator flattens a chain onto its stack, so it sees the two halves
    private[kyo] class Chain[-A, B, +C, -S](
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        type X = B
        def head = a
        def tail = b

        def frame = Frame.internal
        // a chain applies by deferring, never by running its head: the tail may carry a region (that is
        // what makes it a Chain rather than an AndThen), and the eval's round trip installs the tail's
        // entries on the stack before the head runs, so a failure in the head finds its scope. Running the
        // head here would run it with the scope absent; EffectTest's "failure in map" pins exactly that
        def apply(v: A) =
            Effect.defer(v, a, b)
        def apply[D, S2](v: A < S2, cont: Arrow[C, D, S2]) =
            Effect.defer(v, this, cont)

        // a chain of any depth renders in bounded stack: the walk is a loop with a depth cap, so a
        // capture folded from a long eval stack stays printable in a debugger
        override def toString: String =
            val out = new StringBuilder
            @tailrec def loop(pending: List[Arrow[?, ?, ?] | String], fuel: Int): Unit =
                pending match
                    case (s: String) :: rest =>
                        out.append(s)
                        loop(rest, fuel)
                    case (link: Arrow[?, ?, ?]) :: rest =>
                        link match
                            case c: Chain[?, ?, ?, ?] if fuel > 0 =>
                                out.append("Arrow.Chain(")
                                loop(c.a :: ", " :: c.b :: ")" :: rest, fuel - 1)
                            case _: Chain[?, ?, ?, ?] =>
                                out.append("...")
                                loop(rest, fuel)
                            case other =>
                                out.append(other.toString)
                                loop(rest, fuel)
                    case _ => ()
            loop(this :: Nil, 32)
            out.result()
        end toString

    end Chain

    private[Arrow] class Id[A] extends Cont[A, A, Any]:
        type X = A
        def head                      = this
        def tail                      = this
        def frame                     = Frame.internal
        def apply(v: A): A < Any      = v
        override def toString: String = "Arrow(identity)"
        override def apply[C, S2](v: A < S2, cont: Arrow[A, C, S2]) =
            if cont eq Id then
                v.asInstanceOf[C < S2]
            else
                cont(v, Arrow.id)
    end Id

    private[kyo] object Id extends Id[Any]

end Arrow
