package kyo.concurrent

import kyo._
import kyo.ios._

import java.util.concurrent.atomic.AtomicInteger

import fibers._
import atomics._

object latches {

  abstract class Latch {

    def await: Unit > (IOs with Fibers)

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
              val promise = Fibers.unsafeInitPromise[Unit]
              val count   = new AtomicInteger(n)

              val await = promise.get

              val release =
                IOs {
                  var c = count.get()
                  while (c > 0 && !count.compareAndSet(c, c - 1)) {
                    c = count.get()
                  }
                  if (c == 1) {
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
