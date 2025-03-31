package kyo

import kyo.*
import kyo.Tag
import kyo.kernel.*
import scala.util.NotGiven

/** Typed dependency injection mechanism in the Kyo effect system.
  *
  * The `Env` effect enables accessing typed values from a contextual environment without explicitly passing them as parameters. Unlike
  * `Local`, `Env` creates a pending effect that must be satisfied by a handler higher in the call stack. This requirement ensures that all
  * dependencies are properly provided before a computation can run.
  *
  * `Env` stores values in a `TypeMap` and retrieves them based on their types, allowing for clean separation between components that
  * require values and those that provide them. Multiple requirements can be composed through intersection types, enabling precise
  * dependency specification. For example, a computation requiring multiple services can express this as
  * `Env[ServiceA & ServiceB & ServiceC]`.
  *
  * This effect integrates closely with the `Layer` abstraction, providing a structured approach to building and composing application
  * components. Layers can be combined and managed to satisfy the environmental requirements of your application.
  *
  * `Env` is particularly well-suited for application configuration, service dependencies, resource access (databases, clients, etc.), and
  * cross-cutting concerns. You should use `Env` when a value is a required dependency that must be provided before the computation can
  * proceed. If you need optional context with sensible defaults, consider using `Local` instead.
  *
  * @tparam R
  *   The type of values in the environment
  *
  * @see
  *   [[kyo.Env.get]], [[kyo.Env.getAll]] for retrieving values from the environment
  * @see
  *   [[kyo.Env.run]], [[kyo.Env.runLayer]] for providing values to the environment
  * @see
  *   [[kyo.Layer]] for composable dependency provision
  * @see
  *   [[kyo.Local]] for optional contextual values with defaults
  */
sealed trait Env[+R] extends ContextEffect[TypeMap[R]]

/** Companion object for Env, providing utility methods for working with environments. */
object Env:

    /** Retrieves a value of type R from the environment.
      *
      * @tparam R
      *   The type of value to retrieve
      * @return
      *   A computation that retrieves the value from the environment
      */
    inline def get[R](using inline tag: Tag[R])(using inline frame: Frame): R < Env[R] =
        use[R](identity)

    /** Retrieves the entire TypeMap containing all environment values specified by the type intersection R.
      *
      * This is useful when you need access to multiple environment values at once or want to inspect the complete environment context. The
      * type parameter R should be an intersection type of all the values you want to retrieve, for example:
      * `getAll[String & Int & Boolean]` will retrieve a TypeMap containing String, Int, and Boolean values from the environment.
      *
      * @tparam R
      *   An intersection type (A & B & C...) specifying which values to retrieve from the environment
      * @return
      *   A computation that retrieves the complete TypeMap from the environment
      */
    inline def getAll[R](using inline frame: Frame): TypeMap[R] < Env[R] =
        useAll[R](identity)

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
        runAll(TypeMap(env))(v)

    /** Runs a computation with a provided TypeMap environment.
      *
      * @param env
      *   The TypeMap environment
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation with the environment handled
      */
    def runAll[R >: Nothing, A: Flat, S, VR](env: TypeMap[R])(v: A < (Env[R & VR] & S))(
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
                    runAll(env)(value)
                }

    /** Applies a function to the value from the environment.
      *
      * @param f
      *   The function to apply to the environment value
      * @return
      *   A computation that applies the function to the environment value
      */
    inline def use[R](
        using Frame
    )[A, S](inline f: R => A < S)(
        using inline tag: Tag[R]
    ): A < (Env[R] & S) =
        ContextEffect.suspendWith(erasedTag[R]) { map =>
            f(map.asInstanceOf[TypeMap[R]].get(using tag))
        }

    /** Applies a function to the entire TypeMap of environment values specified by the type intersection R.
      *
      * This is similar to `use` but provides access to the complete environment context rather than just a single value. The type parameter
      * R should be an intersection type of all the values you want to access, for example: `useAll[String & Int]` will provide a TypeMap
      * containing String and Int values.
      *
      * @tparam R
      *   An intersection type (A & B & C...) specifying which values to access from the environment
      * @return
      *   Operations for applying functions to the environment TypeMap
      */
    inline def useAll[R](using Frame)[A, S](inline f: TypeMap[R] => A < S): A < (Env[R] & S) =
        ContextEffect.suspendWith(erasedTag[R]) { map =>
            f(map.asInstanceOf[TypeMap[R]])
        }

    private val cachedIsolate                         = Isolate.Contextual.derive[Env[Any], Any]
    given isolate[V]: Isolate.Contextual[Env[V], Any] = cachedIsolate.asInstanceOf[Isolate.Contextual[Env[V], Any]]

    given eliminateEnv: Reducible.Eliminable[Env[Any]] with {}
    private inline def erasedTag[R] = Tag[Env[Any]].asInstanceOf[Tag[Env[R]]]

end Env
