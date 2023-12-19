package kyo

object layers {

  trait Layer[In, Out] { self =>
    def run[T, S](effect: T < (In with S))(implicit fl: Flat[T < (In with S)]): T < (S with Out)

    final def andThen[Out1, In1](other: Layer[In1, Out1]): Layer[In with In1, Out with Out1] =
      new Layer[In with In1, Out with Out1] {
        override def run[T, S](
            effect: T < (In with In1 with S)
        )(
            implicit fl: Flat[T < (In with In1 with S)]
        ): T < (S with Out with Out1) = {
          val selfRun =
            self.run[T, S with In1](effect)
          val otherRun =
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
            override def run[T, S](effect: T < (In1 with S))(implicit
                fl: Flat[T < (In1 with S)]
            ): T < (S with Out2 with Out1) = {
              val handled1 = layer1.run[T, S](effect)
              val handled2 = layer2.run[T, S with Out1](handled1)(Flat.unsafe.unchecked)
              handled2
            }
          }

      }
  }

  trait ChainLayers1 {
    implicit def applyAll1[Shared, In2]: ChainLayer.Aux[Shared, In2 with Shared, Any] =
      new ChainLayer[Shared, In2 with Shared] {
        type RemainingOut1 = Any

        override def applyLayer[In1, Out2](
            layer1: Layer[In1, Shared],
            layer2: Layer[In2 with Shared, Out2]
        ): Layer[In1, Out2] =
          new Layer[In1, Out2] {
            override def run[T, S](effect: T < (In1 with S))(implicit
                fl: Flat[T < (In1 with S)]
            ): T < (S with Out2) = {
              val handled1 = layer1.run[T, S](effect)
              val handled2 = layer2.run[T, S](handled1)(Flat.unsafe.unchecked)
              handled2
            }
          }

      }

    implicit def applyAll2[Out1, Shared]: ChainLayer.Aux[Out1 with Shared, Shared, Out1] =
      new ChainLayer[Out1 with Shared, Shared] {
        type RemainingOut1 = Out1

        override def applyLayer[In1, Out2](
            layer1: Layer[In1, Out1 with Shared],
            layer2: Layer[Shared, Out2]
        ): Layer[In1, Out1 with Out2] =
          new Layer[In1, Out1 with Out2] {
            override def run[T, S](effect: T < (In1 with S))(implicit
                fl: Flat[T < (In1 with S)]
            ): T < (S with Out1 with Out2) = {
              val handled1: T < (S with Out1 with Shared) = layer1.run[T, S](effect)
              val handled2: T < (S with Out1 with Out2) =
                layer2.run[T, S with Out1](handled1)(Flat.unsafe.unchecked)
              handled2
            }
          }

      }
  }

  object ChainLayer extends ChainLayers1 {
    type Aux[Out1, In2, R] = ChainLayer[Out1, In2] { type RemainingOut1 = R }

    implicit def simpleChain[Out]: ChainLayer.Aux[Out, Out, Any] =
      new ChainLayer[Out, Out] {
        type RemainingOut1 = Any

        override def applyLayer[In1, Out2](
            layer1: Layer[In1, Out],
            layer2: Layer[Out, Out2]
        ): Layer[In1, Any with Out2] =
          new Layer[In1, Any with Out2] {
            override def run[T, S](effect: T < (In1 with S))(implicit
                fl: Flat[T < (In1 with S)]
            ): T < (S with Out2) = {
              val handled1 = layer1.run[T, S](effect)
              val handled2 = layer2.run[T, S](handled1)(Flat.unsafe.unchecked)
              handled2
            }
          }

      }
  }

}
