package kyo

import Layer.internal.*
import kyo.internal.Reducible

/** Represents a composable layer of functionality for dependency management.
  *
  * Layer provides a type-safe, composable approach to dependency injection within the Kyo effect system. Each layer encapsulates a piece of
  * functionality that produces values of type `Out` while potentially requiring effects of type `S` for its construction. Layers form the
  * building blocks of modular applications, allowing functionality to be assembled in a structured manner with clear dependency
  * relationships.
  *
  * The core power of layers comes from their composability. While layers can be manually composed using methods like `and`, `to`, and
  * `using`, most applications should prefer the automatic dependency resolution provided by `Layer.init`, which analyzes your layers at
  * compile-time and connects them based on their input and output types.
  *
  * Layers integrate seamlessly with the Env effect, providing a clean solution to the "environment problem" in functional programming. When
  * a computation requires certain dependencies through `Env[SomeType]`, layers can satisfy those requirements through well-defined
  * composition patterns. The layer system automatically handles dependency resolution, ensuring that values are provided in the correct
  * order.
  *
  * Under the hood, layers use memoization to ensure efficiency when the same layer is referenced multiple times in a dependency graph. This
  * prevents redundant computation and resource allocation while preserving the functional semantics of the system.
  *
  * @tparam Out
  *   The type of output produced by this layer
  * @tparam S
  *   The effect type of this layer
  *
  * @see
  *   [[kyo.Layer.init]] for the preferred way to construct layers with automatic dependency resolution
  * @see
  *   [[kyo.Layer.from]], [[kyo.Layer.apply]] for creating individual layers
  * @see
  *   [[kyo.Env.runLayer]] for running computations with layered dependencies
  * @see
  *   [[kyo.Env]], [[kyo.Memo]] for related effects that interact with layers
  */
abstract class Layer[+Out, -S] extends Serializable:
    self =>

    /** Composes this layer with another layer that depends on the output of this layer.
      *
      * @param that
      *   The layer to compose with this one
      * @tparam Out2
      *   The output type of the composed layer
      * @tparam S2
      *   Additional effects of the composed layer
      * @return
      *   A new layer representing the composition of both layers
      */
    final infix def to[Out2, S2](that: Layer[Out2, Env[Out] & S2]): Layer[Out2, S & S2] = To(self, that)

    /** Combines this layer with another independent layer.
      *
      * @param that
      *   The layer to combine with this one
      * @tparam Out2
      *   The output type of the other layer
      * @tparam S2
      *   The effect type of the other layer
      * @return
      *   A new layer producing both outputs
      */
    final infix def and[Out2, S2](that: Layer[Out2, S2]): Layer[Out & Out2, S & S2] = And(self, that)

    /** Combines this layer with another layer that depends on this layer's output.
      *
      * @param that
      *   The layer to combine with this one
      * @tparam Out2
      *   The output type of the other layer
      * @tparam S2
      *   Additional effects of the other layer
      * @return
      *   A new layer producing both outputs
      */
    final infix def using[Out2, S2](that: Layer[Out2, Env[Out] & S2]): Layer[Out & Out2, S & S2] = self and (self to that)

end Layer

/** Companion object for Layer, providing factory methods and utilities. */
object Layer:

    // Existing extension method
    extension [In, Out, S](layer: Layer[Out, Env[In] & S])
        def run[R](using reduce: Reducible[Env[In]]): TypeMap[Out] < (S & reduce.SReduced & Memo) =
            reduce(doRun(layer))

    /** An empty layer that produces no output. */
    val empty: Layer[Any, Any] = FromKyo { () => TypeMap.empty }

    /** Creates a layer from a Kyo effect.
      *
      * @param kyo
      *   The effect to wrap in a layer
      * @tparam A
      *   The output type of the effect
      * @tparam S
      *   The effect type
      * @return
      *   A new layer wrapping the given effect
      */
    def apply[A: Tag, S](kyo: => A < S)(using Frame): Layer[A, S] =
        FromKyo { () =>
            kyo.map { result => TypeMap(result) }
        }

    /** Widens a `from` layer's body to the environment its caller declared.
      *
      * Each `from` takes that environment as its own type parameter `R`, tied to the inputs by `R <:< (A & B ...)` evidence, rather
      * than writing `Env[A & B]` in its result type. An expected type such as `Layer[R2, Env[String & Int]]` would otherwise hand A
      * and B that intersection as a lower bound, both would be inferred as `String & Int`, and the layer would ask the environment for
      * a value no environment holds. The evidence is searched after `f` has fixed the inputs, so the expected type reaches only `R`.
      */
    private def requiring[C, S, In, R](v: C < (Env[In] & S))(using required: R <:< In): C < (Env[R] & S) =
        required.substituteContra[[X] =>> C < (Env[X] & S)](v)

    /** Creates a layer from a function that takes one input and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A and produces B
      */
    def from[A: Tag, B: Tag, S, R](f: A => B < S)(using Frame, R <:< A): Layer[B, Env[R] & S] =
        apply {
            requiring(Env.get[A].map(f))
        }

    /** Creates a layer from a function that takes two inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A and B and produces C
      */
    def from[A: Tag, B: Tag, C: Tag, S, R](f: (A, B) => C < S)(using Frame, R <:< (A & B)): Layer[C, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B] { env =>
                f(env.get[A], env.get[B])
            })
        }

    /** Creates a layer from a function that takes three inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, and C and produces D
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, S, R](f: (A, B, C) => D < S)(using Frame, R <:< (A & B & C)): Layer[D, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B & C] { env =>
                f(env.get[A], env.get[B], env.get[C])
            })
        }

    /** Creates a layer from a function that takes four inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, and D and produces E
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, S, R](f: (A, B, C, D) => E < S)(using
        Frame,
        R <:< (A & B & C & D)
    ): Layer[E, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B & C & D] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D])
            })
        }

    /** Creates a layer from a function that takes five inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, S, R](f: (A, B, C, D, E) => F < S)(using
        Frame,
        R <:< (A & B & C & D & E)
    ): Layer[F, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B & C & D & E] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E])
            })
        }

    /** Creates a layer from a function that takes six inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, S, R](f: (A, B, C, D, E, F) => G < S)(using
        Frame,
        R <:< (A & B & C & D & E & F)
    ): Layer[G, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B & C & D & E & F] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F])
            })
        }

    /** Creates a layer from a function that takes seven inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, S, R](f: (A, B, C, D, E, F, G) => H < S)(using
        Frame,
        R <:< (A & B & C & D & E & F & G)
    ): Layer[H, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B & C & D & E & F & G] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F], env.get[G])
            })
        }

    /** Creates a layer from a function that takes eight inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, S, R](f: (A, B, C, D, E, F, G, H) => I < S)(using
        Frame,
        R <:< (A & B & C & D & E & F & G & H)
    ): Layer[I, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B & C & D & E & F & G & H] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F], env.get[G], env.get[H])
            })
        }

    /** Creates a layer from a function that takes nine inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, S, R](f: (
        A,
        B,
        C,
        D,
        E,
        F,
        G,
        H,
        I
    ) => J < S)(using
        Frame,
        R <:< (A & B & C & D & E & F & G & H & I)
    ): Layer[J, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B & C & D & E & F & G & H & I] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F], env.get[G], env.get[H], env.get[I])
            })
        }

    /** Creates a layer from a function that takes ten inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, S, R](f: (
        A,
        B,
        C,
        D,
        E,
        F,
        G,
        H,
        I,
        J
    ) => K < S)(using
        Frame,
        R <:< (A & B & C & D & E & F & G & H & I & J)
    ): Layer[K, Env[R] & S] =
        apply {
            requiring(Env.useAll[A & B & C & D & E & F & G & H & I & J] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F], env.get[G], env.get[H], env.get[I], env.get[J])
            })
        }

    transparent inline def init[Target](inline layers: Layer[?, ?]*): Layer[Target, ?] =
        kyo.internal.LayerMacros.make[Target](layers*)

    private[kyo] object internal:

        case class And[Out1, Out2, S1, S2](_1: Layer[Out1, S1], _2: Layer[Out2, S2])            extends Layer[Out1 & Out2, S1 & S2]
        case class To[Out1, Out2, S1, S2](_1: Layer[Out1, S1], _2: Layer[Out2, S2 & Env[Out1]]) extends Layer[Out2, S1 & S2]
        case class FromKyo[Out, S](kyo: () => TypeMap[Out] < S)(using val tag: Tag[Out])        extends Layer[Out, S]

        class DoRun[Out, S] extends Serializable:
            private given Frame = Frame.internal

            private type Expected = TypeMap[Out] < (S & Memo)
            private val memo = Memo[Layer[Out, S], TypeMap[Out], S & Memo] {
                case And(lhs, rhs) =>
                    for
                        leftResult  <- doRun(lhs)
                        rightResult <- doRun(rhs)
                    yield leftResult.union(rightResult)

                case To(lhs, rhs) =>
                    for
                        leftResult  <- doRun(lhs)
                        rightResult <- Env.runAll(leftResult)(doRun(rhs))
                    yield rightResult

                case FromKyo(kyo) => kyo()
            }
            def apply(layer: Layer[Out, S]): TypeMap[Out] < (S & Memo) = memo(layer)
        end DoRun

        private val _doRun               = new DoRun
        def doRun[Out, S]: DoRun[Out, S] = _doRun.asInstanceOf[DoRun[Out, S]]

    end internal

end Layer
