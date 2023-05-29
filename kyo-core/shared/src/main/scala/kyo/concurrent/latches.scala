package kyo.concurrent

import kyo._
import kyo.ios._

import java.util.concurrent.atomic.AtomicInteger

import fibers._
import atomics._

object latches {

  trait Latch {
    def await: Unit > Fibers
    def release: Unit > IOs
    def pending: Int > IOs
  }

  object Latches {
    def init[S](n: Int > S): Latch > (IOs with S) =
      n.map { n =>
        if (n <= 0) {
          new Latch {
            val await: Unit > Fibers = ()
            val release: Unit > IOs  = ()
            val pending: Int > IOs   = 0

            override def toString = "Latches(0)"
          }
        } else {
          IOs {
            new Latch {
              val promise = Fibers.unsafePromise[Unit]
              val count   = AtomicInteger(n)
              val await: Unit > Fibers =
                promise.join
              val release: Unit > IOs =
                IOs {
                  if (count.get() > 0 && count.decrementAndGet() == 0) {
                    promise.unsafeComplete(())
                  }
                }
              val pending = IOs(count.get())

              override def toString = s"Latches($count)"
            }
          }
        }
      }
  }
}
