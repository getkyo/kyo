package kyo.concurrent

import kyo.core._
import kyo.ios._
import kyo.resources._
import channels._
import fibers._

object semaphores {

  trait Semaphore {
    def availablePermits: Int > IOs
    def run[T, S](v: => T > S): T > (S | IOs | Fibers)
    def tryRun[T, S](v: => T > S): Option[T] > (S | IOs | Fibers)
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

            val release = chan.offer(()).unit
            def run[T, S](v: => T > S): T > (S | IOs | Fibers) =
              IOs.ensure(release) {
                chan.take(_ => v)
              }
            def tryRun[T, S](v: => T > S) =
              IOs {
                IOs.run(chan.poll) match {
                  case None =>
                    None
                  case _ =>
                    IOs.ensure(release) {
                      v(Some(_))
                    }
                }
              }
          }
        }
      }
  }

}
