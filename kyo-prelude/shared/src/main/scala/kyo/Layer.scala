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
      * @param fn
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
      * @param fn
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
    def from[A: Tag, B: Tag, C: Tag, S](fn: (A, B) => C < S)(using Frame): Layer[C, Env[A & B] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B]).map { case (a, b) => fn(a, b) }
        }

    /** Creates a layer from a function that takes three inputs and produces an effect.
      *
      * @param fn
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
    def from[A: Tag, B: Tag, C: Tag, D: Tag, S](fn: (A, B, C) => D < S)(using Frame): Layer[D, Env[A & B & C] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C])
                .map { case (a, b, c) => fn(a, b, c) }
        }

    /** Creates a layer from a function that takes four inputs and produces an effect.
      *
      * @param fn
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
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, S](fn: (A, B, C, D) => E < S)(using Frame): Layer[E, Env[A & B & C & D] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C], Env.get[D]).map { case (a, b, c, d) => fn(a, b, c, d) }
        }

    /** Creates a layer from a function that takes five inputs and produces an effect.
      *
      * @param fn
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
      *   The fifth input type required by the function
      * @tparam F
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, S](fn: (A, B, C, D, E) => F < S)(using
        Frame
    ): Layer[F, Env[A & B & C & D & E] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C], Env.get[D], Env.get[E]).map { case (a, b, c, d, e) => fn(a, b, c, d, e) }
        }

    /** Creates a layer from a function that takes six inputs and produces an effect.
      *
      * @param fn
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
      *   The fifth input type required by the function
      * @tparam F
      *   The sixth input type required by the function
      * @tparam G
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, S](fn: (A, B, C, D, E, F) => G < S)(using
        Frame
    ): Layer[G, Env[A & B & C & D & E & F] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C], Env.get[D], Env.get[E], Env.get[F]).map { case (a, b, c, d, e, f) =>
                fn(a, b, c, d, e, f)
            }
        }

    /** Creates a layer from a function that takes seven inputs and produces an effect.
      *
      * @param fn
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
      *   The fifth input type required by the function
      * @tparam F
      *   The sixth input type required by the function
      * @tparam G
      *   The seventh input type required by the function
      * @tparam H
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, S](fn: (A, B, C, D, E, F, G) => H < S)(using
        Frame
    ): Layer[H, Env[A & B & C & D & E & F & G] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C], Env.get[D], Env.get[E], Env.get[F], Env.get[G]).map { case (a, b, c, d, e, f, g) =>
                fn(a, b, c, d, e, f, g)
            }
        }

    /** Creates a layer from a function that takes eight inputs and produces an effect.
      *
      * @param fn
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
      *   The fifth input type required by the function
      * @tparam F
      *   The sixth input type required by the function
      * @tparam G
      *   The seventh input type required by the function
      * @tparam H
      *   The eighth input type required by the function
      * @tparam I
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, S](fn: (A, B, C, D, E, F, G, H) => I < S)(using
        Frame
    ): Layer[I, Env[A & B & C & D & E & F & G & H] & S] =
        apply {
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C], Env.get[D], Env.get[E], Env.get[F], Env.get[G], Env.get[H]).map {
                case (a, b, c, d, e, f, g, h) =>
                    fn(a, b, c, d, e, f, g, h)
            }
        }

    /** Creates a layer from a function that takes nine inputs and produces an effect.
      *
      * @param fn
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
      *   The fifth input type required by the function
      * @tparam F
      *   The sixth input type required by the function
      * @tparam G
      *   The seventh input type required by the function
      * @tparam H
      *   The eighth input type required by the function
      * @tparam I
      *   The ninth input type required by the function
      * @tparam J
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, S](fn: (
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
            Kyo.zip(Env.get[A], Env.get[B], Env.get[C], Env.get[D], Env.get[E], Env.get[F], Env.get[G], Env.get[H], Env.get[I]).map {
                case (a, b, c, d, e, f, g, h, i) =>
                    fn(a, b, c, d, e, f, g, h, i)
            }
        }

    /** Creates a layer from a function that takes ten inputs and produces an effect.
      *
      * @param fn
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
      *   The fifth input type required by the function
      * @tparam F
      *   The sixth input type required by the function
      * @tparam G
      *   The seventh input type required by the function
      * @tparam H
      *   The eighth input type required by the function
      * @tparam I
      *   The ninth input type required by the function
      * @tparam J
      *   The tenth input type required by the function
      * @tparam K
      *   The output type of the function
      * @tparam S
      *   The effect type of the function
      * @return
      *   A new layer that requires an environment with A, B, C, D, E and produces F
      */
    def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, S](fn: (
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
            Kyo.zip(
                Env.get[A],
                Env.get[B],
                Env.get[C],
                Env.get[D],
                Env.get[E],
                Env.get[F],
                Env.get[G],
                Env.get[H],
                Env.get[I],
                Env.get[J]
            ).map {
                case (a, b, c, d, e, f, g, h, i, j) =>
                    fn(a, b, c, d, e, f, g, h, i, j)
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
