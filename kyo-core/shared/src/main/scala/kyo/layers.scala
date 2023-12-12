package kyo

object layers {

  trait Layer[In, Out] { self =>
    def run[T, S](effect: T > (S with In))(implicit fl: Flat[T > (S with In)]): T > (S with Out)

    final def add[Out1, In1](other: Layer[In1, Out1]): Layer[In with In1, Out with Out1] =
      new Layer[In with In1, Out with Out1] {
        override def run[T, S](
            effect: T > (S with In with In1)
        )(
            implicit fl: Flat[T > (S with In with In1)]
        ): T > (S with Out with Out1) = {
          val selfRun: T > (S with In1 with Out) =
            self.run[T, S with In1](effect: T > (S with In1 with In))
          val otherRun: T > (S with Out with Out1) =
            other.run[T, S with Out](selfRun)(Flat.unsafe.unchecked)
          otherRun
        }
      }

    final def chain[In2, Out2](other: Layer[In2, Out2])(
        implicit ap: ChainLayer[Out, In2]
    ): Layer[In, Out2 with ap.RemainingOut1] = {
      ap.applyLayer[In, Out2](self, other)
    }
  }

  /** Use layer1 to handle unhandled dependencies (Out) of layer2
    */
  sealed trait ChainLayer[Out1, In2] {
    type RemainingOut1

    def applyLayer[In1, Out2](
        layer1: Layer[In1, Out1],
        layer2: Layer[In2, Out2]
    ): Layer[In1, RemainingOut1 with Out2]
  }

  trait ChainLayers2 {

    implicit def application[Out1, Shared, In2]
        : ChainLayer.Aux[Out1 with Shared, In2 with Shared, Out1] =
      new ChainLayer[Out1 with Shared, In2 with Shared] {
        type RemainingOut1 = Out1
        override def applyLayer[In1, Out2](
            layer1: Layer[In1, Out1 with Shared],
            layer2: Layer[In2 with Shared, Out2]
        ): Layer[In1, Out1 with Out2] =
          new Layer[In1, Out1 with Out2] {
            override def run[T, S](effect: T > (S with In1))(implicit
                fl: Flat[T > (S with In1)]
            ): T > (S with Out2 with Out1) = {
              val handled1: T > (S with Out1 with Shared) = layer1.run[T, S](effect)
              val handled2: T > (S with Out2 with Out1) =
                layer2.run[T, S with Out1](handled1)(Flat.unsafe.unchecked)
              handled2
            }
          }

      }
  }

  object ChainLayer extends ChainLayers2 {
    type Aux[Out1, In2, R] = ChainLayer[Out1, In2] { type RemainingOut1 = R }

    implicit def simpleChain[Out]: ChainLayer.Aux[Out, Out, Any] =
      new ChainLayer[Out, Out] {
        type RemainingOut1 = Any

        override def applyLayer[In1, Out2](
            layer1: Layer[In1, Out],
            layer2: Layer[Out, Out2]
        ): Layer[In1, Any with Out2] =
          new Layer[In1, Any with Out2] {
            override def run[T, S](effect: T > (S with In1))(implicit
                fl: Flat[T > (S with In1)]
            ): T > (S with Out2) = {
              val handled1: T > (S with Out) = layer1.run[T, S](effect)
              val handled2: T > (S with Out2) =
                layer2.run[T, S](handled1)(Flat.unsafe.unchecked)
              handled2
            }
          }

      }
  }

}
