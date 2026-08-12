package kyo.kernel

import kyo.Arrow
import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.kernel.`<`.fromKyo
import kyo.kernel.Implicits.liftInternal
import kyo.kernel.internal.*
import scala.annotation.nowarn

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
        ArrowEffect.handleLoop(effectTag, v)([X] => _ => Loop.continue(value))

    /** Handles a context effect by either providing a new value or transforming one provided by an outer handler. The clause runs outside
      * its own scope, so re-raising the effect resolves against the outer handlers or falls back when none provides a value.
      */
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A
    )(v: B < (E & S))(using inline frame: Frame): B < S =
        ArrowEffect.handleLoop(effectTag, v)(
            [X] =>
                _ =>
                    probe(effectTag).map { outer =>
                        Loop.continue(
                            if outer.asInstanceOf[AnyRef] eq undefined then ifUndefined
                            else ifDefined(outer.asInstanceOf[A])
                        )
                }
        )

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
