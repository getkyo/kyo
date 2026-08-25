package kyo.kernel

import kyo.Arrow
// unqualified so the inline expansions do not select these from Kyo.type or Arrow.type at a site
// outside package kyo, where they are not accessible. See the note in Pending.scala
import kyo.Arrow.BindingStep
import kyo.Arrow.Step
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.*
import kyo.Result
import kyo.kernel.internal.*
import kyo.kernel.internal.Kyo.Binding
import kyo.kernel.internal.Kyo.Catching
import kyo.kernel.internal.Kyo.Defer
import scala.annotation.nowarn

/** The base trait for all effects in the Kyo effect system.
  *
  * When code performs an effectful operation, instead of executing immediately, effects create a suspended computation that captures what
  * needs to be done. These suspended computations can then be interpreted in different ways through effect handlers.
  *
  * This suspension mechanism is the foundation of Kyo's effect system. It allows effectful code to be pure and composable - rather than
  * performing operations directly, code builds up a description of what operations should occur. This description can then be interpreted
  * by handlers that determine how the operations are actually executed.
  *
  * There are two kinds of effects:
  *   - [[ArrowEffect]] for suspended computations involving input/output transformations.
  *   - [[ContextEffect]] for suspended computations requiring contextual values.
  */
abstract class Effect private[kernel] ()

object Effect:

    /** Holds a computation unevaluated until an eval reaches it. */
    private[kyo] def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    /** The payload a deferred body stands on.
      *
      * Cast rather than lifted: a raw value is already the union's first arm, and `Unit` admits no `Boxed`
      * subtype, so there is nothing to nest. Lifting here would summon the macro inside a core kernel file,
      * which is the cascade that ends in a stale-symbol crash on the clean build.
      */
    private val unitValue: Unit < Any = ().asInstanceOf[Unit < Any]

    // The body lives in the arrow, not the payload: building the node does not run it, and applying the
    // arrow is what runs it. That is the same deferral by a different carrier, and it leaves the payload a
    // value already in hand, which is what lets an inspection read it without running a step.
    //
    // The node is its own step, the shape the outcome dispatchers already use, so the arrow costs no
    // allocation of its own and this stays exactly what the by-name form was: one object. The applying
    // body is the one `Arrow.apply` mints, since a standalone arrow is what this is.
    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        new Defer[Unit, A, A, S] with Step[Unit, A, S]:
            def frame                   = _frame
            val value                   = unitValue
            def contA                   = this
            def contB                   = Arrow.id[A]
            override def apply(v: Unit) = f
            def apply[C, S2](v: Unit < S2, cont: Arrow[A, C, S2]) =
                v match
                    case kyo: Kyo[Unit, S2] @unchecked =>
                        defer(kyo, this, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            defer(v, this, cont)
                        else
                            val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if

    /** Reifies the application of a continuation to a computation as a node.
      *
      * Kernel plumbing, not user API: the combinators' inline expansions and the site-generated
      * arrow classes call this from downstream modules when an application cannot run strictly (the
      * input is a suspension, or the budget drained). Public so those expansions reach it without
      * an accessor; non-inline so the cold arm stays one call instead of a class per site.
      * `Effect.defer(v, cont)` evaluates to exactly what `cont(v)` evaluates to.
      */
    def defer[A, B, S](v: A < S, cont: Arrow[A, B, S]): B < S =
        new Defer[A, B, B, S]:
            val value = v
            def contA = cont
            def contB = Arrow.id[B]

    def defer[A, B, C, S](v: A < S, a: Arrow[A, B, S], b: Arrow[B, C, S]): C < S =
        if b eq Arrow.id then
            defer(v, a.asInstanceOf[Arrow[A, C, S]])
        else
            new Defer[A, B, C, S]:
                val value = v
                def contA = a
                def contB = b

    /** Wraps a computation with error handling: `f` runs if a non-fatal exception escapes `v`, whether during the initial evaluation or
      * during any later effect operation.
      *
      * The scope is a stack entry the eval consults while unwinding, so a throw from anything above it lands here, including one raised
      * while the body is being built and one raised after a resumption. It stops applying where the scope ends: a value flowing back
      * through the entry pops it, so a throw after the computation completes is not this scope's.
      *
      * Fatal errors pass untouched.
      */
    @nowarn("msg=anonymous")
    inline def catching[A, S, B >: A, S2](inline v: => A < S)(
        inline f: Throwable => B < S2
    )(using inline _frame: Frame): B < (S & S2) =
        new Catching[B, S & S2]:
            def frame                  = _frame
            def value                  = v
            def recover(ex: Throwable) = f(ex)

    /** Detaches a computation from the bindings standing at this point, so the child carries them and can be evaluated elsewhere. */
    // private[kyo] inline def detach[A, S](inline v: A < S)(using inline _frame: Frame): (A < S) < S

    /** Acquires a resource, uses it, and releases it, with the release running whether or not the use completes.
      *
      * A resource is a value scoped to an extent, which is what a binding is, so this is one: the acquire's
      * result is bound for the extent of `use`, and the binding carries the release. An eval that completes
      * runs it where the use ends; one that throws runs it on the way down; one that ends holding a
      * continuation a clause never applied runs it at the boundary. The binding is unnamed, so nothing can
      * read the resource out of it: `use` is handed it directly and no tag exists to look it up by.
      *
      * The binding is built once the acquire settles, and the eval never stops in front of a binding, so no
      * slice can end between the resource existing and the scope that owes it being installed.
      *
      * The release takes no effects. It has to be able to run where nothing is installed to answer for it,
      * which is what an eval that is ending can offer. It is told how the extent ended, so it can commit on a
      * value, roll back on a failure, and tell either from an extent that was abandoned.
      *
      * This form's release only wants the resource, and delegates to the one that also takes the outcome.
      */
    inline def bracket[A, B, S](inline acquire: A < S)(inline release: A => Any < Any)(
        inline use: A => B < S
    )(using inline _frame: Frame): B < S =
        bracket(acquire)((a: A, _: Result[Any, B]) => release(a))(use)

    @nowarn("msg=anonymous")
    inline def bracket[A, B, S](inline acquire: A < S)(inline _release: (A, Result[Any, B]) => Any < Any)(
        inline _use: A => B < S
    )(using inline _frame: Frame): B < S =
        // the parameters are named apart from the members below rather than bound to locals first: a local
        // would cost a call through the lambda at every application instead of expanding the body here
        //
        // the binding is built once the acquire settles, through a `BindingStep` rather than a map: the
        // step is what the eval's park guard recognizes, so no slice can end between the resource existing
        // and the scope that owes it being installed, and its apply skips the budget gate so a pending
        // stop cannot defer the bind against that refusal. A settled acquire binds right here: the
        // resource already exists, and the Binding node is the value
        //
        // not fused into the deferral the way the previous shape was: as a pending entry a resource's binding
        // takes the resource and answers with the use's result, while an installed one is the identity its
        // extent ends at, and one object cannot be typed for both without erasing to `Any`. Fusing would also
        // make `Binding` a trait, which puts mixin forwarders at every binding and every read
        //
        // the two Binding constructions are textual copies, kept parallel on purpose
        val acq = acquire // bound once: `acquire` is inline and two reads would build it twice
        acq match
            case kyo: Kyo[A, S] @unchecked =>
                Effect.defer(
                    kyo,
                    new BindingStep[A, B, S]:
                        def frame = _frame
                        override def apply(resource: A): B < S =
                            new Binding[A, Nothing, B, S]:
                                def frame                  = _frame
                                def tag                    = Absent
                                val bound                  = Maybe((_: Maybe[A]) => resource)
                                override val release       = Maybe(_release)
                                def resume(held: Maybe[A]) = _use(resource)
                )
            case _ =>
                val resource = Nested.unnest[A](acq)
                new Binding[A, Nothing, B, S]:
                    def frame                  = _frame
                    def tag                    = Absent
                    val bound                  = Maybe((_: Maybe[A]) => resource)
                    override val release       = Maybe(_release)
                    def resume(held: Maybe[A]) = _use(resource)
                end new
        end match
    end bracket

end Effect
