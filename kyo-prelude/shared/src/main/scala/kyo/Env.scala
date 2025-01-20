package kyo

import kyo.*
import kyo.Tag
import kyo.kernel.*
import scala.util.NotGiven

/** Represents an environment effect that provides access to typed values.
  *
  * @tparam R
  *   The type of values in the environment
  */
sealed trait Env[+R] extends ContextEffect[TypeMap[R]]

/** Companion object for Env, providing utility methods for working with environments. */
object Env:

    given eliminateEnv: Reducible.Eliminable[Env[Any]] with {}
    private inline def erasedTag[R] = Tag[Env[Any]].asInstanceOf[Tag[Env[R]]]

    /** Retrieves a value of type R from the environment.
      *
      * @tparam R
      *   The type of value to retrieve
      * @return
      *   A computation that retrieves the value from the environment
      */
    inline def get[R](using inline tag: Tag[R])(using inline frame: Frame): R < Env[R] =
        use[R](identity)

    /** Runs a computation with a provided environment value.
      *
      * @param env
      *   The environment value
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation with the environment handled
      */
    def run[R >: Nothing: Tag, A: Flat, S, VR](env: R)(v: A < (Env[R & VR] & S))(
        using
        reduce: Reducible[Env[VR]],
        frame: Frame
    ): A < (S & reduce.SReduced) =
        runTypeMap(TypeMap(env))(v)

    /** Runs a computation with a provided TypeMap environment.
      *
      * @param env
      *   The TypeMap environment
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation with the environment handled
      */
    def runTypeMap[R >: Nothing, A: Flat, S, VR](env: TypeMap[R])(v: A < (Env[R & VR] & S))(
        using
        reduce: Reducible[Env[VR]],
        frame: Frame
    ): A < (S & reduce.SReduced) =
        reduce(ContextEffect.handle(erasedTag[R], env, _.union(env))(v): A < (Env[VR] & S))

    /** Runs a computation with an environment created from provided layers.
      *
      * @param layers
      *   The layers to create the environment from
      * @param value
      *   The computation to run
      * @return
      *   The result of the computation with the layered environment handled
      */
    transparent inline def runLayer[A, S, V](inline layers: Layer[?, ?]*)(value: A < (Env[V] & S)): A < Nothing =
        inline Layer.init[V](layers*) match
            case layer: Layer[V, s] =>
                layer.run.map { env =>
                    runTypeMap(env)(value)
                }

    /** Provides operations for using values from the environment.
      *
      * @tparam R
      *   The type of value in the environment
      */
    final class UseOps[R](dummy: Unit) extends AnyVal:
        /** Applies a function to the value from the environment.
          *
          * @param f
          *   The function to apply to the environment value
          * @return
          *   A computation that applies the function to the environment value
          */
        inline def apply[A, S](inline f: R => A < S)(
            using
            inline tag: Tag[R],
            inline frame: Frame
        ): A < (Env[R] & S) =
            ContextEffect.suspendWith(erasedTag[R]) { map =>
                f(map.asInstanceOf[TypeMap[R]].get(using tag))
            }
    end UseOps

    /** Creates a UseOps instance for a given type. */
    inline def use[R >: Nothing]: UseOps[R] = UseOps(())

end Env
