package kyo.concurrent

import kyo.core._
import kyo.ios._

import java.util.concurrent.atomic.AtomicInteger

import fibers._
import atomics._

object latches {

  trait Latch {
    def await: Unit > Fibers
    def release: Unit > IOs
  }

  object Latches {
    def apply(n: Int): Latch > IOs =
      if (n <= 0) {
        new Latch {
          def await: Unit > Fibers = ()
          def release: Unit > IOs  = ()
        }
      } else {
        IOs {
          new Latch {
            val promise = Fibers.unsafePromise[Unit]
            val count   = AtomicInteger(n)
            def await: Unit > Fibers =
              promise.join
            def release: Unit > IOs =
              IOs {
                if (count.decrementAndGet() == 0) {
                  promise.unsafeComplete(())
                }
              }
          }
        }
      }

  }
}
