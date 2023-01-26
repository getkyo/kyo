package kyo.concurrent

import kyo.core._
import kyo.ios._
import fibers._
import refs._

object latches {

  trait Latch {
    def await: Unit > Fibers
    def release: Unit > IOs
  }

  object Latch {
    def apply(n: Int): Latch > IOs =
      if (n <= 0) {
        new Latch {
          def await: Unit > Fibers = ()
          def release: Unit > IOs  = ()
        }
      } else {
        for {
          count   <- IntRef(n)
          promise <- Fibers.promise[Unit]
        } yield {
          new Latch {
            def await: Unit > Fibers =
              promise.join
            def release: Unit > IOs =
              count.decrementAndGet {
                case 0 => promise.complete(()).unit
                case _ => ()
              }
          }
        }
      }
  }

}
