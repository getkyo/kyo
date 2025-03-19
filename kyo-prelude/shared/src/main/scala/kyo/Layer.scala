package kyo

import Layer.internal.*
import kyo.Tag

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
abstract class Layer[+Out, -S]:
    self =>

    /** Composes this layer with another layer that depends on the output of this layer.
      *
      * @param that
      *   The layer to compose with this one
      * @tparam Out2
      *   The output type of the composed layer
      * @tparam S2
      *   Additional effects of the composed layer
      * @tparam In2
      *   The input type required by the second layer
      * @return
      *   A new layer representing the composition of both layers
      */
    final infix def to[Out2, S2, In2](that: Layer[Out2, Env[In2] & S2]): Layer[Out2, S & S2] = To(self, that)

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
      * @tparam In2
      *   The input type required by the second layer
      * @return
      *   A new layer producing both outputs
      */
    final infix def using[Out2, S2, In2](that: Layer[Out2, Env[In2] & S2]): Layer[Out & Out2, S & S2] = self and (self to that)

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

    /** Creates a layer from a function that takes one input and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @tparam A
      *   The input type required by the function
      * @tparam B
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A and produces B
      */
    def from[A: Tag, B: Tag, S](f: A => B < S)(using Frame): Layer[B, Env[A] & S] =
        apply {
            Env.get[A].map(f)
        }

    /** Creates a layer from a function that takes two inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @tparam A
      *   The first input type required by the function
      * @tparam B
      *   The second input type required by the function
      * @tparam C
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A and B and produces C
      */
    def from[A: Tag, B: Tag, C: Tag, S](f: (A, B) => C < S)(using Frame): Layer[C, Env[A & B] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B]).map { case (a, b) => f(a, b) }
        }

    /** Creates a layer from a function that takes three inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @tparam A
      *   The first input type required by the function
      * @tparam B
      *   The second input type required by the function
      * @tparam C
      *   The third input type required by the function
      * @tparam D
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, and C and produces D
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, S](f: (A, B, C) => D < S)(using Frame): Layer[D, Env[A & B & C] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C])
                .map { case (a, b, c) => f(a, b, c) }
        }

    /** Creates a layer from a function that takes four inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @tparam A
      *   The first input type required by the function
      * @tparam B
      *   The second input type required by the function
      * @tparam C
      *   The third input type required by the function
      * @tparam D
      *   The fourth input type required by the function
      * @tparam E
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, C, and D and produces E
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, S](f: (A, B, C, D) => E < S)(using Frame): Layer[E, Env[A & B & C & D] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C], Env.get[D]).map { case (a, b, c, d) => f(a, b, c, d) }
        }

    transparent inline def init[Target](inline layers: Layer[?, ?]*): Layer[Target, ?] =
        kyo.internal.LayerMacros.make[Target](layers*)

    private[kyo] object internal:
        case class And[Out1, Out2, S1, S2](lhs: Layer[Out1, S1], rhs: Layer[Out2, S2])                   extends Layer[Out1 & Out2, S1 & S2]
        case class To[Out1, Out2, S1, S2](lhs: Layer[?, ?], rhs: Layer[?, ?])                            extends Layer[Out1 & Out2, S1 & S2]
        case class FromKyo[In, Out, S](kyo: () => TypeMap[Out] < (Env[In] & S))(using val tag: Tag[Out]) extends Layer[Out, S]

        private given Frame = Frame.internal

        class DoRun[Out, S]:
            private val memo = Memo[Layer[Out, S], TypeMap[Out], S & Memo] { self =>
                type Expected = TypeMap[Out] < (S & Memo)
                self match
                    case And(lhs, rhs) =>
                        {
                            for
                                leftResult  <- doRun(lhs)
                                rightResult <- doRun(rhs)
                            yield leftResult.union(rightResult)
                        }.asInstanceOf[Expected]

                    case To(lhs, rhs) =>
                        {
                            for
                                leftResult  <- doRun(lhs)
                                rightResult <- Env.runAll(leftResult)(doRun(rhs))
                            yield rightResult
                        }.asInstanceOf[Expected]

                    case FromKyo(kyo) =>
                        kyo().asInstanceOf[Expected]
                end match
            }
            def apply(layer: Layer[Out, S]): TypeMap[Out] < (S & Memo) = memo(layer)
        end DoRun

        private val _doRun               = new DoRun
        def doRun[Out, S]: DoRun[Out, S] = _doRun.asInstanceOf[DoRun[Out, S]]

    end internal

end Layer
