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

/** Represents the requirement for a value that will be provided later by a handler.
  *
  * While ArrowEffect represents functions awaiting implementation, ContextEffect represents values awaiting provision. It captures the need
  * for a value of type A without specifying where that value comes from. When a handler provides a value, that value becomes available
  * within the handler's scope - once the handler's scope ends, the value is no longer available to computations.
  *
  * This mechanism aligns with dependency injection patterns - handlers act as injectors that provide values within their scope, and
  * different handlers can provide different values in different scopes. The composition of effects automatically tracks these requirements
  * through the type system.
  *
  * Reads and bindings are both `Kyo.Binding`, which carries the semantics: what a binding holds is a function of what is bound around it,
  * so nesting layers rather than replaces, and a read takes the innermost.
  *
  * The polymorphic type parameter A defines what type of value is required:
  * @tparam A
  *   The type of value that will be provided by a handler
  */
abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    // No non-inheritable marker: a binding that must not cross a fork says so with `fork = _ => Absent`,
    // which also lets one cross as something else, where a type could only say yes or no.

    /** Creates a suspended computation that requests a value from a context effect. This establishes a requirement for a value that must be
      * satisfied by a handler higher up in the program. The requirement becomes part of the effect type, ensuring that handlers must
      * provide the requested value before the program can execute.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @return
      *   A computation that will receive the requested value when executed
      */
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline frame: Frame): A < E =
        suspendWith(effectTag)(identity)

    /** Creates a suspended computation that requests a context value and transforms it immediately upon receipt. This combines the
      * operations of requesting and transforming a context value into a single step.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @param f
      *   The transformation to apply to the received value
      * @return
      *   A computation containing the transformed value
      */
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

    /** Requests a value from a context effect with a specified default value. Unlike standard suspend, this version does not create a
      * mandatory effect requirement. If no handler provides a value, the computation proceeds with the default value instead. This makes
      * the context value optional rather than required.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @param default
      *   The value to use when no handler provides one
      * @return
      *   A computation that provides either the context value or default
      */
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline default: => A
    )(using inline frame: Frame): A < Any =
        suspendWith(effectTag, default)(identity)

    /** Requests an optional context value and transforms it, using a default if no value is available. This combines requesting an optional
      * context value with immediate transformation. The transformation function receives either the context value if available or the
      * default value if not.
      *
      * @param effectTag
      *   Identifies which context effect to request the value from
      * @param default
      *   The value to use when no handler provides one
      * @param f
      *   The transformation to apply to either the context or default value
      * @return
      *   A computation containing the transformed value
      */
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

    /** Handles a context effect by providing a value for a specific computation scope. This satisfies suspend operations within that scope
      * by making the provided value available to them. The handler establishes a region where the context value is defined and can be
      * accessed.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param value
      *   The value to provide to the computation
      * @param v
      *   The computation requiring the context value
      * @return
      *   The computation result with the context value provided
      */
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag, value, (_: A) => value)(v)

    /** Handles a context effect by either providing a new value or transforming an existing one. This allows for layered handling of
      * context values, where a handler can either establish a new value when none exists or modify a value that was provided by an outer
      * handler. Because a binding resolves when it is installed, a computation captured here and resumed under a different enclosing
      * binding merges into that one instead.
      *
      * The other three parameters say what happens at the edges of the extent, and each defaults to the plainest answer:
      *
      *   - `fork` is what a computation forked from here receives, `Absent` for a value that must not cross.
      *   - `join` is what this holds once a fork ends, given what it holds and what the fork ended with.
      *     Keeping this one, taking the fork's, or merging them is the whole of an isolate strategy.
      *   - `release` is what the value owes when the extent ends. It is `Maybe` rather than a function with
      *     an empty default because a binding that owes nothing must not pay for one.
      *
      * @param effectTag
      *   Identifies which context effect to handle
      * @param ifUndefined
      *   The value to use when no existing value is found
      * @param ifDefined
      *   The transformation to apply to any existing value
      * @param v
      *   The computation requiring the context value
      * @return
      *   The computation result with the context value handled
      */
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
