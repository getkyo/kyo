package kyo

import Local.internal.*
import kyo.Tag
import kyo.kernel.*
import scala.annotation.nowarn

/** Represents a local value that can be accessed and modified within a specific scope.
  *
  * Local provides a way to manage thread-local-like state in a functional manner. There are two types of locals: regular and isolated.
  *
  * Regular locals behave similarly to inheritable thread locals, where child fibers inherit the value from their parent fiber. Isolated
  * locals, on the other hand, are similar to non-inheritable thread locals, where child fibers always start with the default value.
  *
  * @tparam A
  *   The type of the local value
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

    /** Creates a new isolated Local instance with the given default value.
      *
      * Isolated locals are similar to non-inheritable thread locals, where child fibers always start with the default value and do not
      * inherit from their parent fiber.
      *
      * @param defaultValue
      *   The default value for the Local
      * @return
      *   A new isolated Local instance
      */
    @nowarn("msg=anonymous")
    inline def initIsolated[A](inline defaultValue: A): Local[A] =
        new Base[A, IsolatedState]:
            def tag             = Tag[IsolatedState]
            lazy val default: A = defaultValue

    object internal:

        sealed private[kyo] trait State         extends ContextEffect[Map[Local[?], AnyRef]]
        sealed private[kyo] trait IsolatedState extends ContextEffect[Map[Local[?], AnyRef]] with ContextEffect.Isolated

        abstract class Base[A, E <: ContextEffect[Map[Local[?], AnyRef]]] extends Local[A]:

            def tag: Tag[E]

            def get(using Frame) =
                ContextEffect.suspendAndMap(tag, Map.empty)(_.getOrElse(this, default).asInstanceOf[A])

            def use[B, S](f: A => B < S)(using Frame) =
                ContextEffect.suspendAndMap(tag, Map.empty)(map => f(map.getOrElse(this, default).asInstanceOf[A]))

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
