package kyo

trait Layer[In, Out]:
    self =>
    def run[T, S](effect: T < (In & S))(using fl: Flat[T < (In & S)]): T < (S & Out)

    final def andThen[Out1, In1](other: Layer[In1, Out1]): Layer[In & In1, Out & Out1] =
        new Layer[In & In1, Out & Out1]:
            override def run[T, S](
                effect: T < (In & In1 & S)
            )(
                using fl: Flat[T < (In & In1 & S)]
            ): T < (S & Out & Out1) =
                val selfRun =
                    self.run[T, S & In1](effect)
                val otherRun =
                    other.run[T, S & Out](selfRun)
                otherRun
            end run

    final def chain[In2, Out2](other: Layer[In2, Out2])(
        using ap: ChainLayer[Out, In2]
    ): Layer[In, Out2 & ap.RemainingOut1] =
        ap.applyLayer[In, Out2](self, other)
end Layer

sealed trait ChainLayer[Out1, In2]:
    type RemainingOut1

    def applyLayer[In1, Out2](
        layer1: Layer[In1, Out1],
        layer2: Layer[In2, Out2]
    ): Layer[In1, RemainingOut1 & Out2]
end ChainLayer

trait ChainLayers2:
    given application[Out1, Shared, In2]: ChainLayer.Aux[Out1 & Shared, In2 & Shared, Out1] =
        new ChainLayer[Out1 & Shared, In2 & Shared]:
            type RemainingOut1 = Out1
            override def applyLayer[In1, Out2](
                layer1: Layer[In1, Out1 & Shared],
                layer2: Layer[In2 & Shared, Out2]
            ): Layer[In1, Out1 & Out2] =
                new Layer[In1, Out1 & Out2]:
                    override def run[T, S](effect: T < (In1 & S))(implicit
                        fl: Flat[T < (In1 & S)]
                    ): T < (S & Out2 & Out1) =
                        val handled1 = layer1.run[T, S](effect)
                        val handled2 = layer2.run[T, S & Out1](handled1)
                        handled2
                    end run
end ChainLayers2

trait ChainLayers1:
    given applyAll1[Shared, In2]: ChainLayer.Aux[Shared, In2 & Shared, Any] =
        new ChainLayer[Shared, In2 & Shared]:
            type RemainingOut1 = Any

            override def applyLayer[In1, Out2](
                layer1: Layer[In1, Shared],
                layer2: Layer[In2 & Shared, Out2]
            ): Layer[In1, Out2] =
                new Layer[In1, Out2]:
                    override def run[T, S](effect: T < (In1 & S))(implicit
                        fl: Flat[T < (In1 & S)]
                    ): T < (S & Out2) =
                        val handled1 = layer1.run[T, S](effect)
                        val handled2 = layer2.run[T, S](handled1)
                        handled2
                    end run

    given applyAll2[Out1, Shared]: ChainLayer.Aux[Out1 & Shared, Shared, Out1] =
        new ChainLayer[Out1 & Shared, Shared]:
            type RemainingOut1 = Out1

            override def applyLayer[In1, Out2](
                layer1: Layer[In1, Out1 & Shared],
                layer2: Layer[Shared, Out2]
            ): Layer[In1, Out1 & Out2] =
                new Layer[In1, Out1 & Out2]:
                    override def run[T, S](effect: T < (In1 & S))(implicit
                        fl: Flat[T < (In1 & S)]
                    ): T < (S & Out1 & Out2) =
                        val handled1: T < (S & Out1 & Shared) = layer1.run[T, S](effect)
                        val handled2: T < (S & Out1 & Out2) =
                            layer2.run[T, S & Out1](handled1)
                        handled2
                    end run
end ChainLayers1

object ChainLayer extends ChainLayers1:
    type Aux[Out1, In2, R] = ChainLayer[Out1, In2] { type RemainingOut1 = R }

    given simpleChain[Out]: ChainLayer.Aux[Out, Out, Any] =
        new ChainLayer[Out, Out]:
            type RemainingOut1 = Any

            override def applyLayer[In1, Out2](
                layer1: Layer[In1, Out],
                layer2: Layer[Out, Out2]
            ): Layer[In1, Any & Out2] =
                new Layer[In1, Any & Out2]:
                    override def run[T, S](effect: T < (In1 & S))(implicit
                        fl: Flat[T < (In1 & S)]
                    ): T < (S & Out2) =
                        val handled1 = layer1.run[T, S](effect)
                        val handled2 = layer2.run[T, S](handled1)
                        handled2
                    end run
end ChainLayer
