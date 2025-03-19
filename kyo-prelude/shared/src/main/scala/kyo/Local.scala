package kyo

import Local.internal.*
import kyo.Tag
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
  * `Local` comes in two variants: regular (inheritable) and non-inheritable. Regular locals pass their values across asynchronous
  * boundaries, similar to inheritable thread locals, while non-inheritable locals do not, starting with the default value in new async
  * contexts.
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
abstract class Local[A]:

    /** The default value for this Local. */
    def default: A

    /** Retrieves the current value of this Local.
      *
      * @return
      *   An effect that produces the current value
      */
    def get(using Frame): A < Any

    /** Applies a function to the current value of this Local.
      *
      * @param f
      *   The function to apply to the local value
      * @return
      *   An effect that produces the result of applying the function
      */
    def use[B, S](f: A => B < S)(using Frame): B < S

    /** Runs an effect with a temporarily modified local value.
      *
      * @param value
      *   The temporary value to use
      * @param v
      *   The effect to run with the modified value
      * @return
      *   The result of running the effect with the modified value
      */
    def let[B, S](value: A)(v: B < S)(using Frame): B < S

    /** Runs an effect with an updated local value.
      *
      * @param f
      *   The function to update the local value
      * @param v
      *   The effect to run with the updated value
      * @return
      *   The result of running the effect with the updated value
      */
    def update[B, S](f: A => A)(v: B < S)(using Frame): B < S
end Local

/** Companion object for Local, providing utility methods for creating Local instances. */
object Local:

    /** Creates a new regular Local instance with the given default value.
      *
      * Regular locals are similar to inheritable thread locals, where child fibers inherit the value from their parent fiber.
      *
      * @param defaultValue
      *   The default value for the Local
      * @return
      *   A new regular Local instance
      */
    @nowarn("msg=anonymous")
    inline def init[A](inline defaultValue: A): Local[A] =
        new Base[A, State]:
            def tag             = Tag[State]
            lazy val default: A = defaultValue

    /** Creates a new non-inheritable Local instance with the given default value.
      *
      * It's similar to Java's non-inheritable thread locals, where child fibers always start with the default value and do not inherit from
      * their parent fiber.
      *
      * @param defaultValue
      *   The default value for the Local
      * @return
      *   A new isolated Local instance
      */
    @nowarn("msg=anonymous")
    inline def initNoninheritable[A](inline defaultValue: A): Local[A] =
        new Base[A, NoninheritableState]:
            def tag             = Tag[NoninheritableState]
            lazy val default: A = defaultValue

    object internal:

        sealed private[kyo] trait State               extends ContextEffect[Map[Local[?], AnyRef]]
        sealed private[kyo] trait NoninheritableState extends ContextEffect[Map[Local[?], AnyRef]] with ContextEffect.Noninheritable

        abstract class Base[A, E <: ContextEffect[Map[Local[?], AnyRef]]] extends Local[A]:

            def tag: Tag[E]

            def get(using Frame) =
                ContextEffect.suspendWith(tag, Map.empty)(_.getOrElse(this, default).asInstanceOf[A])

            def use[B, S](f: A => B < S)(using Frame) =
                ContextEffect.suspendWith(tag, Map.empty)(map => f(map.getOrElse(this, default).asInstanceOf[A]))

            def let[B, S](value: A)(v: B < S)(using Frame) =
                ContextEffect.handle(tag, Map.empty[Local[?], AnyRef].updated(this, value), _.updated(this, value.asInstanceOf[AnyRef]))(v)

            def update[B, S](f: A => A)(v: B < S)(using Frame) =
                ContextEffect.handle(
                    tag,
                    Map(this -> f(default)),
                    map => map.updated(this, f(map.getOrElse(this, default).asInstanceOf[A]).asInstanceOf[AnyRef])
                )(v)
        end Base
    end internal

end Local
