package kyo.concurrent

import kyo.core._
import kyo.ios._
import kyo.resources._
import channels._
import fibers._

object semaphores {

  trait Semaphore {
    def availablePermits: Int > IOs
    def apply[T, S](v: => T > S): T > (S | IOs | Fibers)
  }

  object Semaphores {
    def make(permits: Int): Semaphore > IOs =
      Channels.makeBlocking[Unit](permits) { chan =>
        def add(n: Int): Unit > IOs =
          if (n > 0) {
            chan.offer(())(_ => add(n - 1))
          } else {
            IOs.unit
          }
        add(permits) { _ =>
          new Semaphore {
            def availablePermits = chan.size
            def apply[T, S](v: => T > S): T > (S | IOs | Fibers) =
              IOs.ensure(chan.offer(()).unit) {
                chan.take(_ => v)
              }
          }
        }
      }
  }

}
