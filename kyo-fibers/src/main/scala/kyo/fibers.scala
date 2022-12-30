package kyo

import kyo.lists.Lists
import kyo.scheduler.IOPromise
import kyo.scheduler.IOTask

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.Duration
import scala.util._
import scala.util.control.NonFatal

import core._
import ios._
import scheduler._
import frames._
import scheduler._
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object fibers {

  private val timer = Executors.newScheduledThreadPool(1, ThreadFactory("kyo-timer"))

  opaque type Promise[T] <: Fiber[T] = IOPromise[T]
  opaque type Fiber[T]               = IOPromise[T]

  extension [T](p: Promise[T]) {

    inline def complete(v: T > IOs): Boolean > IOs =
      IOs(p.complete(v))
  }

  extension [T](f: Fiber[T]) {

    inline def isDone: Boolean > IOs =
      IOs(f.isDone)

    inline def join: T > Fibers =
      f > Fibers

    inline def join(timeout: Duration): Option[T] > Fibers =
      
      // f > Fibers
      ???

    inline def block: T > IOs =
      IOs(f.block())

    inline def interrupt: Boolean > IOs =
      IOs(f.interrupt())
  }

  final class Fibers private[fibers] extends Effect[Fiber] {

    inline def promise[T]: Promise[T] > IOs =
      IOs(IOPromise[T])

    inline def fork[T](inline v: => T > IOs): Fiber[T] > IOs =
      IOs(IOTask(v))

    inline def sleep(d: Duration): Fiber[Unit] > IOs =
      IOs {
        val p = new IOPromise[Unit] with Runnable {
          def run() = super.complete(())
        }
        if (d.isFinite) {
          timer.schedule(p, d.toMillis, TimeUnit.MILLISECONDS)
        }
        p
      }

    inline def race[T](l: Seq[T > IOs]): Fiber[T] > IOs =
      IOs {
        val p = new IOPromise[T]
        l.foreach { io =>
          val f = IOTask(io)
          p.interrupts(f)
          f.onComplete(p.complete(_))
        }
        p
      }

    def par[T](l: Seq[T > IOs]): Fiber[Seq[T]] > IOs =
      IOs {
        val p       = IOPromise[Seq[T]]
        val size    = l.size
        val results = (new Array[Any](size)).asInstanceOf[Array[T]]
        val pending = new AtomicInteger(size)
        var i       = 0
        while (i < size) {
          val io    = l(i)
          val fiber = IOTask(io)
          p.interrupts(fiber)
          val j = i
          fiber.onComplete { r =>
            try {
              results(j) = IOs.run(r)
              if (pending.decrementAndGet() == 0) {
                p.complete(ArraySeq.unsafeWrapArray(results))
              }
            } catch {
              case ex if (NonFatal(ex)) =>
                p.complete(IOs(throw ex))
            }
          }
          i += 1
        }
        p
      }

    def block[T, S](v: T > (S | Fibers)): T > (S | IOs) =
      given ShallowHandler[Fiber, Fibers] =
        new ShallowHandler[Fiber, Fibers] {
          def pure[T](v: T) =
            val p = IOPromise[T]
            p.complete(v)
            p
          def apply[T, U, S](m: Fiber[T], f: T => U > (S | Fibers)) =
            f(m.block())
        }
      IOs((v < Fibers)(_.block()))

    // def apply[T1, T2](v1: T1 > IOs, v2: T2 > IOs): (T1, T2) > (IOs | Fibers) = ???
  }
  val Fibers = new Fibers

  given DeepHandler[Fiber, Fibers] =
    new DeepHandler[Fiber, Fibers] {
      def pure[T](v: T) =
        val p = IOPromise[T]
        p.complete(v)
        p
      def flatMap[T, U](fiber: Fiber[T], f: T => Fiber[U]): Fiber[U] =
        val r = IOPromise[U]
        r.interrupts(fiber)
        fiber.onComplete { v =>
          try f(IOs.run(v)).onComplete(r.complete(_))
          catch {
            case ex if (NonFatal(ex)) =>
              r.complete(IOs(throw ex))
          }
        }
        r
    }
}
