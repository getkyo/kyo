package kyo

import Local.internal.*
import kyo.kernel.*
import scala.annotation.nowarn

/** Represents a context value with a default that can be modified within scopes.
  *
  * `Local` provides functionality similar to thread-local variables in a functional context. Unlike `Env`, a `Local` value always has a
  * default and doesn't create a pending effect that must be satisfied. This makes it more suitable for optional contextual values that can
  * fall back to reasonable defaults when not explicitly provided.
  *
  * Values in a `Local` can be temporarily modified within specific computation scopes using methods like `let` or `update`. These
  * modifications only affect the specified scope and automatically revert when the computation exits that scope. This scoping behavior
  * makes `Local` ideal for contextual information that varies within different parts of your application.
  *
  * Each local carries its own strategy for crossing fork boundaries: what a forked computation receives (everything, nothing, or a
  * transformation of the current value) and what the parent holds once a fork ends. The default strategy inherits the value into forks and
  * keeps the parent's own value on join, matching inheritable thread locals; `initNoninheritable` builds one that never crosses.
  *
  * This effect useful for managing request context information, tracing and logging context, temporary configuration overrides, and user or
  * tenant context. Choose `Local` when you have context that always has a sensible default value and may need to be modified temporarily.
  * For required dependencies that must be explicitly provided, `Env` would be the more appropriate choice.
  *
  * @tparam A
  *   The type of the local value
  *
  * @see
  *   [[kyo.Local.init]], [[kyo.Local.initNoninheritable]] for creating Local instances
  * @see
  *   [[kyo.Local#get]], [[kyo.Local#use]] for retrieving values
  * @see
  *   [[kyo.Local#let]], [[kyo.Local#update]] for modifying values within scopes
  * @see
  *   [[kyo.Env]] for required dependencies without defaults
  */
abstract class Local[A] extends Serializable:

    /** The default value for this Local. */
    def default: A

    /** What a computation forked from a scope holding this local receives; Absent for a value that must not cross. */
    def fork(value: A): Maybe[A]

    /** What this local holds once a fork ends, given what the parent held and what the fork ended with. */
    def join(held: A, forked: A): A

    /** Retrieves the current value of this Local.
      *
      * @return
      *   An effect that produces the current value
      */
    def get(using Frame): A < Any =
        ContextEffect.suspendWith(Tag[State], Map.empty)(_.getOrElse(this, default).asInstanceOf[A])

    /** Applies a function to the current value of this Local.
      *
      * @param f
      *   The function to apply to the local value
      * @return
      *   An effect that produces the result of applying the function
      */
    def use[B, S](f: A => B < S)(using Frame): B < S =
        ContextEffect.suspendWith(Tag[State], Map.empty)(map => f(map.getOrElse(this, default).asInstanceOf[A]))

    /** Runs an effect with a temporarily modified local value.
      *
      * @param value
      *   The temporary value to use
      * @param v
      *   The effect to run with the modified value
      * @return
      *   The result of running the effect with the modified value
      */
    def let[B, S](value: A)(v: B < S)(using Frame): B < S =
        scoped(
            Map.empty[Local[?], AnyRef].updated(this, value.asInstanceOf[AnyRef]),
            _.updated(this, value.asInstanceOf[AnyRef])
        )(v)

    /** Runs an effect with an updated local value.
      *
      * @param f
      *   The function to update the local value
      * @param v
      *   The effect to run with the updated value
      * @return
      *   The result of running the effect with the updated value
      */
    def update[B, S](f: A => A)(v: B < S)(using Frame): B < S =
        scoped(
            Map(this -> f(default).asInstanceOf[AnyRef]),
            map => map.updated(this, f(map.getOrElse(this, default).asInstanceOf[A]).asInstanceOf[AnyRef])
        )(v)

    // All locals share one tag, so every binding installs these strategies: a fork asks each local for
    // its own crossing, and a join asks each held local against what the fork ended with.
    private def scoped[B, S](
        ifUndefined: Map[Local[?], AnyRef],
        ifDefined: Map[Local[?], AnyRef] => Map[Local[?], AnyRef]
    )(v: B < S)(using Frame): B < S =
        ContextEffect.handle(
            Tag[State],
            ifUndefined,
            ifDefined,
            fork = map =>
                map.foldLeft(Map.empty[Local[?], AnyRef]) { case (acc, (local, value)) =>
                    local.asInstanceOf[Local[AnyRef]].fork(value) match
                        case Maybe.Present(v) => acc.updated(local, v)
                        case Maybe.Absent     => acc
                },
            join = (held, _, forked) =>
                held.map { case (local, value) =>
                    forked.get(local) match
                        case Some(fv) => local -> local.asInstanceOf[Local[AnyRef]].join(value, fv)
                        case None     => local -> value
                }
        )(v)
end Local

/** Companion object for Local, providing utility methods for creating Local instances. */
object Local:

    /** Creates a new Local instance with the given default value.
      *
      * The value is inherited by forked computations, and the parent keeps its own value when a fork ends,
      * matching inheritable thread locals.
      *
      * @param defaultValue
      *   The default value for the Local
      * @return
      *   A new Local instance
      */
    @nowarn("msg=anonymous")
    inline def init[A](inline defaultValue: A): Local[A] =
        new Local[A]:
            lazy val default: A             = defaultValue
            def fork(value: A): Maybe[A]    = Maybe(value)
            def join(held: A, forked: A): A = held

    /** Creates a new Local instance with the given default value and fork-boundary strategy.
      *
      * `forkValue` decides what a forked computation receives: the value itself to inherit it, a
      * transformation of it, or `Absent` for a local that must not cross. `joinValue` decides what the
      * parent holds once a fork ends, defaulting to keeping its own value.
      *
      * @param defaultValue
      *   The default value for the Local
      * @param forkValue
      *   What a forked computation receives, given the current value
      * @param joinValue
      *   What the parent holds after a fork ends, given its value and the fork's final value
      * @return
      *   A new Local instance carrying the strategy
      */
    @nowarn("msg=anonymous")
    inline def init[A](inline defaultValue: A)(
        inline forkValue: A => Maybe[A],
        inline joinValue: (A, A) => A = (held: A, _: A) => held
    ): Local[A] =
        new Local[A]:
            lazy val default: A             = defaultValue
            def fork(value: A): Maybe[A]    = forkValue(value)
            def join(held: A, forked: A): A = joinValue(held, forked)

    /** Creates a new non-inheritable Local instance with the given default value.
      *
      * Child computations always start with the default value and do not inherit from their parent,
      * matching non-inheritable thread locals. Shorthand for `init(defaultValue)(_ => Absent)`.
      *
      * @param defaultValue
      *   The default value for the Local
      * @return
      *   A new non-inheritable Local instance
      */
    inline def initNoninheritable[A](inline defaultValue: A): Local[A] =
        init(defaultValue)(_ => Maybe.Absent)

    object internal:

        sealed private[kyo] trait State extends ContextEffect[Map[Local[?], AnyRef]]
    end internal

end Local
