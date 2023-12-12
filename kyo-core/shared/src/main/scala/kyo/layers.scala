package kyo

object layers {

  trait Layer[Sin, Sout] { self =>
    def run[T, S](effect: T > (S with Sout))(implicit fl: Flat[T]): T > (S with Sin)

    final def ++[Sin1, Sout1](other: Layer[Sin1, Sout1]): Layer[Sin with Sin1, Sout with Sout1] =
      new Layer[Sin with Sin1, Sout with Sout1] {
        override def run[T, S](
            effect: T > (S with Sout with Sout1)
        )(
            implicit fl: Flat[T]
        ): T > (S with Sin with Sin1) = {
          val selfRun: T > (S with Sout1 with Sin) =
            self.run[T, S with Sout1](effect: T > (S with Sout1 with Sout))
          val otherRun: T > (S with Sin with Sin1) = other.run[T, S with Sin](selfRun)
          otherRun
        }
      }

    final def >>>[Sin1, Sout1](other: Layer[Sin1, Sout1])(implicit
        ap: ApplyLayer[Sout, Sin1]
    ): Layer[Sin with ap.Sin, Sout1 with ap.Sout] = {
      ap.applyLayer[Sin, Sout1](self, other)
    }
  }

  sealed trait ApplyLayer[S1, S2] {
    type Sin
    type Sout

    def applyLayer[Sin1, Sout2](
        layer1: Layer[Sin1, S1],
        layer2: Layer[S2, Sout2]
    ): Layer[Sin1 with Sin, Sout2 with Sout]
  }

  trait LowPriorityApplyLayers1 {
    implicit def partialApplication[Sshared, Sextra1, Sextra2]
        : ApplyLayer[Sshared with Sextra1, Sshared with Sextra2] {
          type Sin = Sextra2; type Sout = Sextra1
        } = {
      new ApplyLayer[Sshared with Sextra1, Sshared with Sextra2] {
        type Sin  = Sextra2
        type Sout = Sextra1

        override def applyLayer[Sin1, Sout2](
            layer1: Layer[Sin1, Sshared with Sextra1],
            layer2: Layer[Sshared with Sextra2, Sout2]
        ): Layer[Sin1 with Sextra2, Sout2 with Sextra1] = {
          new Layer[Sin1 with Sextra2, Sout2 with Sextra1] {
            override def run[T, S](
                effect: T > (S with Sout2 with Sextra1)
            )(
                implicit fl: Flat[T]
            ): T > (S with Sin1 with Sextra2) = {
              val run2: T > (S with Sshared with Sextra2 with Sextra1) =
                layer2.run[T, S with Sextra1](effect)
              val run1: T > (S with Sin1 with Sextra2) = layer1.run[T, S with Sextra2](run2)
              run1
            }
          }
        }
      }
    }
  }

  trait LowPriorityApplyLayers extends LowPriorityApplyLayers1 {
    implicit def partialApplicationLeft[Sshared, Sextra]
        : ApplyLayer[Sshared, Sshared with Sextra] { type Sin = Sextra; type Sout = Any } = {
      new ApplyLayer[Sshared, Sshared with Sextra] {
        type Sin  = Sextra
        type Sout = Any

        def applyLayer[Sin1, Sout2](
            layer1: Layer[Sin1, Sshared],
            layer2: Layer[Sshared with Sextra, Sout2]
        ): Layer[Sin1 with Sextra, Sout2] = {
          new Layer[Sin1 with Sextra, Sout2] {
            override def run[T, S](effect: T > (S with Sout2))(
                implicit fl: Flat[T]
            ): T > (S with Sin1 with Sextra) = {
              val run2: T > (S with Sshared with Sextra) = layer2.run[T, S](effect)
              val run1: T > (S with Sextra with Sin1)    = layer1.run[T, S with Sextra](run2)
              run1
            }
          }
        }
      }
    }

    implicit def partialApplicationRight[Sshared, Sextra]
        : ApplyLayer[Sshared with Sextra, Sshared] { type Sin = Any; type Sout = Sextra } = {
      new ApplyLayer[Sshared with Sextra, Sshared] {
        type Sin  = Any
        type Sout = Sextra

        override def applyLayer[Sin1, Sout2](
            layer1: Layer[Sin1, Sshared with Sextra],
            layer2: Layer[Sshared, Sout2]
        ): Layer[Sin1 with Any, Sout2 with Sextra] = {
          new Layer[Sin1 with Any, Sout2 with Sextra] {
            override def run[T, S](
                effect: T > (S with Sout2 with Sextra)
            )(
                implicit fl: Flat[T]
            ): T > (S with Sin1) = {
              val run2: T > (S with Sshared with Sextra) = layer2.run[T, S with Sextra](effect)
              val run1: T > (S with Sin1)                = layer1.run[T, S](run2)
              run1
            }
          }
        }
      }
    }
  }

  object ApplyLayer extends LowPriorityApplyLayers {
    type Aux[S1, S2, Sin0, Sout0] = ApplyLayer[S1, S2] { type Sin = Sin0; type Sout = Sout0 }

    implicit def simpleApplication[Sshare]: ApplyLayer.Aux[Sshare, Sshare, Any, Any] =
      new ApplyLayer[Sshare, Sshare] {
        type Sin  = Any
        type Sout = Any

        def applyLayer[Sin1, Sout2](
            layer1: Layer[Sin1, Sshare],
            layer2: Layer[Sshare, Sout2]
        ): Layer[Sin1, Sout2] = {
          new Layer[Sin1, Sout2] {
            override def run[T, S](effect: T > (S with Sout2))(implicit
                fl: Flat[T]
            ): T > (S with Sin1) = {
              val run2: T > (S with Sshare) = layer2.run[T, S](effect)
              val run1: T > (S with Sin1)   = layer1.run[T, S](run2)
              run1
            }
          }
        }
      }
  }

}
