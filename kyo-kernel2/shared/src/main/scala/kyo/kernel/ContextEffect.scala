package kyo.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.*
import kyo.Result
import kyo.Tag
import kyo.bug
// unqualified so the inline expansions do not select it from Kyo.type at a site outside package kyo,
// where it is not accessible. See the note in Pending.scala
import kyo.kernel.internal.Kyo.Binding
import scala.annotation.nowarn

/** Represents the requirement for a value that a binding higher up provides.
  *
  * Where an ArrowEffect is an operation awaiting an answer, this is a value awaiting provision: a read asks
  * for the value bound at the point the computation runs, and a binding provides it for the extent of a
  * computation, dynamically rather than by position. A read that names a default requires nothing, so it can
  * run with no binding at all.
  *
  * Reads and bindings are both `Kyo.Binding`, which carries the semantics: what a binding holds is a function
  * of what is bound around it, so nesting layers rather than replaces, and a read takes the innermost.
  *
  * @tparam A
  *   The type of value a binding provides
  */
abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    // No non-inheritable marker: a binding that must not cross a fork says so with `fork = _ => Absent`,
    // which also lets one cross as something else, where a type could only say yes or no.

    /** Reads the value bound for this effect, requiring one.
      *
      * The effect stays in the row, so a computation that reads is one a binding has to answer.
      */
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline frame: Frame): A < E =
        suspendWith(effectTag)(identity)

    /** Reads the value bound for this effect and transforms it in the same step. */
    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < (E & S) =
        new Binding[A, E, B, E & S]:
            def frame = _frame
            def tag   = Maybe(effectTag)
            def bound = Absent
            def resume(held: Maybe[A]) =
                f(held.getOrElse(bug(s"Missing value for context effect '${effectTag.show}'")))

    /** Reads the value bound for this effect, falling back to a default.
      *
      * The effect does not enter the row: a read that answers itself when nothing binds it is not a
      * requirement, which is what lets a computation that only reads optional values run unhandled.
      */
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline default: => A
    )(using inline frame: Frame): A < Any =
        suspendWith(effectTag, default)(identity)

    /** Reads the value bound for this effect with a default, and transforms it in the same step. */
    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline default: => A
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < S =
        new Binding[A, E, B, S]:
            def frame                  = _frame
            def tag                    = Maybe(effectTag)
            def bound                  = Absent
            def resume(held: Maybe[A]) = f(held.getOrElse(default))

    /** Binds a value for the extent of a computation.
      *
      * What it binds is derived from whatever is bound around it: `ifUndefined` where nothing is, `ifDefined`
      * applied to what is. That is the form that composes, and the one `Local` rests on, since every local
      * shares a tag and a map and each binding merges itself into the enclosing one rather than replacing it.
      * Leaving `ifDefined` out binds the value outright, ignoring what is around it. Because a binding
      * resolves when it is installed, a computation captured here and resumed under a different enclosing
      * binding merges into that one instead.
      *
      * The other three say what happens at the edges of the extent, and each defaults to the plainest answer:
      *
      *   - `fork` is what a computation forked from here receives, `Absent` for a value that must not cross.
      *     This is what the previous kernel's non-inheritable marker said, as a function rather than a type.
      *   - `join` is what this holds once a fork ends, given what it holds and what the fork ended with.
      *     Keeping this one, taking the fork's, or merging them is the whole of an isolate strategy.
      *   - `release` is what the value owes when the extent ends. It is `Maybe` rather than a function with
      *     an empty default because a binding that owes nothing must not pay for one.
      */
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag, value, (_: A) => value)(v)

    @nowarn("msg=anonymous")
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A,
        inline fork: A => Maybe[A] < S = (a: A) => Maybe(a),
        inline join: (A, A) => A < S = (held: A, _: A) => held,
        inline release: Maybe[(A, Result[Any, B]) => Any < Any] = Absent
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        // named apart from the members below, which would shadow the parameters inside the class body
        def onFork(held: A)            = fork(held)
        def onJoin(held: A, forked: A) = join(held, forked)
        def onRelease                  = release
        new Binding[A, E, B, S]:
            def frame                             = _frame
            def tag                               = Maybe(effectTag)
            val bound                             = Maybe((outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined))
            override def fork(held: A)            = onFork(held)
            override def join(held: A, forked: A) = onJoin(held, forked)
            override val release                  = onRelease
            def resume(held: Maybe[A])            = v
        end new
    end handle

end ContextEffect
