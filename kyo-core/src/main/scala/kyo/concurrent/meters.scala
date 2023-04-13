package kyo.concurrent

import kyo.core._
import kyo.ios._
import kyo.resources._

import scala.annotation.tailrec
import scala.concurrent.duration.Duration

import channels._
import fibers._
import timers._
import kyo.lists.Lists

object meters {

  trait Meter { self =>
    def available: Int > IOs
    def isAvailable: Boolean > IOs = available.map(_ > 0)
    def run[T, S](v: => T > S): T > (S | IOs | Fibers)
    def tryRun[T, S](v: => T > S): Option[T] > (S | IOs)
  }

  object Meters {

    def mutex: Meter > IOs =
      semaphore(1)

    def semaphore(permits: Int): Meter > IOs =
      Channels.blocking[Unit](permits).map { chan =>
        offer(permits, chan, ()).map { _ =>
          new Meter {
            val available = chan.size
            val release   = chan.offer(()).unit
            def run[T, S](v: => T > S): T > (S | IOs | Fibers) =
              IOs.ensure(release) {
                chan.take.map(_ => v)
              }
            def tryRun[T, S](v: => T > S) =
              IOs {
                IOs.run(chan.poll) match {
                  case None =>
                    None
                  case _ =>
                    IOs.ensure(release) {
                      v.map(Some(_))
                    }
                }
              }
          }
        }
      }

    def rateLimiter(rate: Int, period: Duration): Meter > (IOs | Timers) =
      Channels.blocking[Unit](rate).map { chan =>
        Timers.scheduleAtFixedRate(period)(offer(rate, chan, ())).map { _ =>
          new Meter {
            val available = chan.size
            def run[T, S](v: => T > S): T > (S | IOs | Fibers) =
              chan.take.map(_ => v)
            def tryRun[T, S](v: => T > S) =
              chan.poll.map {
                case None =>
                  None
                case _ =>
                  v.map(Some(_))
              }
          }
        }
      }

    def pipeline[S](l: (Meter > (S | IOs))*): Meter > (S | IOs) =
      pipeline(l.toList)

    def pipeline[S](l: List[Meter > (S | IOs)]): Meter > (S | IOs) =
      Lists.collect(l).map { meters =>
        new Meter {
          val available =
            def loop(l: List[Meter], acc: Int): Int > IOs =
              l match {
                case Nil => acc
                case h :: t =>
                  h.available.map(v => loop(t, acc + v))
              }
            loop(meters, 0)

          def run[T, S](v: => T > S) =
            def loop(l: List[Meter]): T > (S | IOs | Fibers) =
              l match {
                case Nil => v
                case h :: t =>
                  h.run(loop(t))
              }
            loop(meters)
          def tryRun[T, S](v: => T > S) =
            def loop(l: List[Meter]): Option[T] > (S | IOs) =
              l match {
                case Nil => v.map(Some(_))
                case h :: t =>
                  h.tryRun(loop(t)).map {
                    case None => None
                    case r    => r.flatten
                  }
              }
            loop(meters)
        }
      }

    private def offer[T](n: Int, chan: Channel[T], v: T): Unit > IOs =
      if (n > 0) {
        chan.offer(v).map {
          case true => offer(n - 1, chan, v)
          case _    => ()
        }
      } else {
        IOs.unit
      }
  }
}
