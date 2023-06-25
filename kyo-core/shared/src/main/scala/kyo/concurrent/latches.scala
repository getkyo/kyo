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
            val await   = ()
            val release = ()
            val pending = 0

            override def toString = "Latches(0)"
          }
        } else {
          IOs {
            new Latch {
              val promise = Fibers.unsafePromise[Unit]
              val count   = new AtomicInteger(n)

              val await = promise.get

              val release =
                IOs {
                  if (count.get() > 0 && count.decrementAndGet() == 0) {
                    promise.unsafeComplete(())
                    ()
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
