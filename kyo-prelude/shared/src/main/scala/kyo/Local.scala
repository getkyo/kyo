package kyo

import kyo.Tag
import kyo.kernel.*
import scala.annotation.nowarn

/** Represents a local value that can be accessed and modified within a specific scope.
  *
  * Local provides a way to manage thread-local state in a functional manner.
  *
  * @tparam A
  *   The type of the local value
  */
abstract class Local[A]:

    import Local.State

    /** The default value for this Local. */
    lazy val default: A

    /** Retrieves the current value of this Local.
      *
      * @return
      *   An effect that produces the current value
      */
    final def get(using Frame): A < Any =
        ContextEffect.suspendMap(Tag[Local.State], Map.empty)(_.getOrElse(this, default).asInstanceOf[A])

    /** Applies a function to the current value of this Local.
      *
      * @param f
      *   The function to apply to the local value
      * @return
      *   An effect that produces the result of applying the function
      */
    final def use[B, S](f: A => B < S)(using Frame): B < S =
        ContextEffect.suspendMap(Tag[Local.State], Map.empty)(map => f(map.getOrElse(this, default).asInstanceOf[A]))

    /** Runs an effect with a temporarily modified local value.
      *
      * @param value
      *   The temporary value to use
      * @param v
      *   The effect to run with the modified value
      * @return
      *   The result of running the effect with the modified value
      */
    final def let[B, S](value: A)(v: B < S)(using Frame): B < S =
        ContextEffect.handle(Tag[Local.State], Map(this -> value), _.updated(this, value.asInstanceOf[AnyRef]))(v)

    /** Runs an effect with an updated local value.
      *
      * @param f
      *   The function to update the local value
      * @param v
      *   The effect to run with the updated value
      * @return
      *   The result of running the effect with the updated value
      */
    final def update[B, S](f: A => A)(v: B < S)(using Frame): B < S =
        ContextEffect.handle(
            Tag[Local.State],
            Map(this -> f(default)),
            map => map.updated(this, f(map.getOrElse(this, default).asInstanceOf[A]).asInstanceOf[AnyRef])
        )(v)
end Local

/** Companion object for Local, providing utility methods for creating Local instances. */
object Local:

    /** Creates a new Local instance with the given default value.
      *
      * @param defaultValue
      *   The default value for the Local
      * @return
      *   A new Local instance
      */
    @nowarn("msg=anonymous")
    inline def init[A](inline defaultValue: A): Local[A] =
        new Local[A]:
            lazy val default: A = defaultValue

    sealed private trait State extends ContextEffect[Map[Local[?], AnyRef]]
end Local
