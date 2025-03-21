package kyo

import Layer.internal.*
import kyo.Tag

/** Represents a composable layer of functionality in an application.
  *
  * Layers allow for modular composition of different parts of an application, facilitating dependency injection and separation of concerns.
  *
  * @tparam Out
  *   The type of output produced by this layer
  * @tparam S
  *   The effect type of this layer
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
      * @return
      *   A new layer that requires an environment with A and B and produces C
      */
    def from[A: Tag, B: Tag, C: Tag, S](f: (A, B) => C < S)(using Frame): Layer[C, Env[A & B] & S] =
        apply {
            Env.useAll[A & B] { env =>
                f(env.get[A], env.get[B])
            }
        }

    /** Creates a layer from a function that takes three inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, and C and produces D
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, S](f: (A, B, C) => D < S)(using Frame): Layer[D, Env[A & B & C] & S] =
        apply {
            Env.useAll[A & B & C] { env =>
                f(env.get[A], env.get[B], env.get[C])
            }
        }

    /** Creates a layer from a function that takes four inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, and D and produces E
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, S](f: (A, B, C, D) => E < S)(using Frame): Layer[E, Env[A & B & C & D] & S] =
        apply {
            Env.useAll[A & B & C & D] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D])
            }
        }

    /** Creates a layer from a function that takes five inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, S](f: (A, B, C, D, E) => F < S)(using
        Frame
    ): Layer[F, Env[A & B & C & D & E] & S] =
        apply {
            Env.useAll[A & B & C & D & E] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E])
            }
        }

    /** Creates a layer from a function that takes six inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, S](f: (A, B, C, D, E, F) => G < S)(using
        Frame
    ): Layer[G, Env[A & B & C & D & E & F] & S] =
        apply {
            Env.useAll[A & B & C & D & E & F] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F])
            }
        }

    /** Creates a layer from a function that takes seven inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, S](f: (A, B, C, D, E, F, G) => H < S)(using
        Frame
    ): Layer[H, Env[A & B & C & D & E & F & G] & S] =
        apply {
            Env.useAll[A & B & C & D & E & F & G] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F], env.get[G])
            }
        }

    /** Creates a layer from a function that takes eight inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, S](f: (A, B, C, D, E, F, G, H) => I < S)(using
        Frame
    ): Layer[I, Env[A & B & C & D & E & F & G & H] & S] =
        apply {
            Env.useAll[A & B & C & D & E & F & G & H] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F], env.get[G], env.get[H])
            }
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
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, S](f: (
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
        Frame
    ): Layer[J, Env[A & B & C & D & E & F & G & H & I] & S] =
        apply {
            Env.useAll[A & B & C & D & E & F & G & H & I] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F], env.get[G], env.get[H], env.get[I])
            }
        }

    /** Creates a layer from a function that takes ten inputs and produces an effect.
      *
      * @param f
      *   The function to wrap in a layer
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, S](f: (
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
        Frame
    ): Layer[K, Env[A & B & C & D & E & F & G & H & I & J] & S] =
        apply {
            Env.useAll[A & B & C & D & E & F & G & H & I & J] { env =>
                f(env.get[A], env.get[B], env.get[C], env.get[D], env.get[E], env.get[F], env.get[G], env.get[H], env.get[I], env.get[J])
            }
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
