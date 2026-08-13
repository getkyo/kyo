package kyo.kernel

import kyo.Arrow
import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.kernel.`<`.fromKyo
import kyo.kernel.Implicits.liftInternal
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.targetName

/** Represents the requirement for a value that will be provided later by a handler.
  *
  * A context effect is an arrow effect whose operations take no input and produce the provided value, so provision is an answering handler
  * and the requirement composes like any other effect.
  */
abstract class ContextEffect[+A] extends ArrowEffect[Const[Unit], Const[A]]

object ContextEffect:

    /** A marker trait for context effects that do not persist across asynchronous boundaries. */
    trait Noninheritable:
        self: ContextEffect[?] =>

    // The structural recognizer a fork's transplant tests. Mixed in only by the
    // region `handle` builds below, so "this cell may cross a fork" is a property
    // of the representation instead of a tag test: a user-installed handler over a
    // context tag (possible now that ContextEffect is an ordinary ArrowEffect) never
    // mixes this in and is never a candidate for transplant. A pure marker: it adds
    // no member of its own, because the Noninheritable bit is tested at fork time
    // (Eval.transplant, reading the same `tag` the handler already carries), not
    // decided here at construction. `handle` is the call every scoped binding goes
    // through (Local.let, Env.run, Scope.run, ...) and stays on the currency
    // discipline's warm paths (ArrowEffectBytecodeTest pins this module's
    // allocation shape); a fork is comparatively rare and already pays for an
    // allocation and a schedule. Testing at construction would tax every `handle`
    // call whether or not its region ever crosses a fork; testing at fork time
    // taxes only the cells actually walked when a fork happens, and costs nothing
    // on a program that never forks at all.
    private[kyo] trait Provision:
        self: Handler.Loop[?, ?, ?, ?, ?] =>

    private val undefined = new AnyRef {}

    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline frame: Frame): A < E =
        ArrowEffect.suspend[Any](effectTag, ())

    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: A => B < S
    )(using inline frame: Frame): B < (E & S) =
        ArrowEffect.suspendWith[Any](effectTag, ())(f)

    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline _default: => A
    )(using inline _frame: Frame): A < Any =
        new Kyo.Suspend[Const[Unit], Const[A], E, Any, A, Any] with Kyo.Defaulted:
            def tag     = effectTag
            def input   = ()
            def frame   = _frame
            def cont    = Arrow[A]
            def default = _default

    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline _default: => A
    )(
        inline f: A => B < S
    )(using inline frame: Frame): B < S =
        suspend(effectTag, _default).map(f)

    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline frame: Frame): B < S =
        val value0 = value
        provision(effectTag, v)([X] => _ => Loop.continue(value0))
    end handle

    /** Handles a context effect by either providing a new value or transforming one provided by an outer handler. The clause runs outside
      * its own scope, so re-raising the effect resolves against the outer handlers or falls back when none provides a value.
      */
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A
    )(v: B < (E & S))(using inline frame: Frame): B < S =
        provision(effectTag, v)(
            [X] =>
                _ =>
                    probe(effectTag).map { outer =>
                        Loop.continue(
                            if outer.asInstanceOf[AnyRef] eq undefined then ifUndefined
                            else ifDefined(outer.asInstanceOf[A])
                        )
                }
        )

    // Shared by both handle overloads above: one allocation, the object is the
    // handler, the region node, and the Provision recognizer. Mirrors
    // ArrowEffect.handleLoop's stateless construction (ArrowEffect.scala) with the
    // recognizer mixed in; the Noninheritable test itself happens later, at fork
    // time (Eval.transplant), not here.
    @nowarn("msg=anonymous")
    private inline def provision[A, E <: ContextEffect[A], B, S, S2](inline effectTag: Tag[E], v: B < (E & S))(
        inline f: [X] => Unit => Loop.Outcome[A < (E & S & S2), B] < S2
    )(using inline frame: Frame): B < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                new Handler.Loop[Const[Unit], Const[A], E, B, S & S2] with Kyo.Handled[Const[Unit], Const[A], E, B, B, S & S2, Any]
                    with Provision:
                    def tag = effectTag
                    @targetName("applyInput")
                    def apply[X](input: Unit) = f(())
                    val value                 = v
                    def handler               = this
                    def exit                  = Arrow[B]
                end new
            case v =>
                // no unnest: the value stays inside the computation, so its
                // nesting box stays on
                v.asInstanceOf[B < (S & S2)]
        end match
    end provision

    // raises the effect keeping the value erased so the sentinel can be
    // compared by identity before any cast; the clause of the layered handle
    // runs outside its own scope, so this resolves against the outer
    // handlers or falls back to the sentinel
    @nowarn("msg=anonymous")
    private inline def probe[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline _frame: Frame): Any < Any =
        new Kyo.Suspend[Const[Unit], Const[A], E, Any, Any, Any] with Kyo.Defaulted:
            def tag     = effectTag
            def input   = ()
            def frame   = _frame
            def cont    = Arrow[Any]
            def default = undefined

end ContextEffect
