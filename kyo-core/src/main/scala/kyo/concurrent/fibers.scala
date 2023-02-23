package kyo.concurrent

import kyo.core._
import kyo.frames._
import kyo.ios._
import kyo.resources._

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util._
import scala.util.control.NonFatal

import scheduler._
import timers._

object fibers {

  opaque type Fiber[T]               = T | IOPromise[T]
  opaque type Promise[T] <: Fiber[T] = IOPromise[T]

  extension [T](p: Promise[T]) {

    /*inline(2)*/
    def complete(v: => T > IOs): Boolean > IOs =
      IOs(p.complete(IOs(v)))

    /*inline(2)*/
    private[kyo] def unsafeComplete(v: T > IOs): Boolean =
      p.complete(v)
  }

  extension [T](fiber: Fiber[T]) {

    def isDone: Boolean > IOs =
      if (fiber.isInstanceOf[IOPromise[_]]) {
        val f = fiber.asInstanceOf[IOPromise[T]]
        IOs(f.isDone())
      } else {
        true
      }

    def join: T > Fibers =
      if (fiber.isInstanceOf[IOPromise[_]]) {
        val f = fiber.asInstanceOf[IOPromise[T]]
        f > Fibers
      } else {
        fiber.asInstanceOf[T > Fibers]
      }

    /*inline(2)*/
    def joinTry: Try[T] > (Fibers | IOs) =
      if (fiber.isInstanceOf[IOPromise[_]]) {
        val f = fiber.asInstanceOf[IOPromise[T]]
        IOs {
          val p = new IOPromise[Try[T]]
          p.interrupts(f)
          f.onComplete { t =>
            p.complete(Try(IOs.run(t)))
          }
          p > Fibers
        }
      } else {
        Success(fiber.asInstanceOf[T])
      }

    /*inline(2)*/
    def block: T > IOs =
      if (fiber.isInstanceOf[IOPromise[_]]) {
        val f = fiber.asInstanceOf[IOPromise[T]]
        IOs(f.block())
      } else {
        fiber.asInstanceOf[T > IOs]
      }

    /*inline(2)*/
    def interrupt: Boolean > IOs =
      if (fiber.isInstanceOf[IOPromise[_]]) {
        val f = fiber.asInstanceOf[IOPromise[T]]
        IOs(f.interrupt())
      } else {
        false
      }

    /*inline(2)*/
    def transform[U](t: T => Fiber[U]): Fiber[U] =
      if (fiber.isInstanceOf[IOPromise[_]]) {
        val f = fiber.asInstanceOf[IOPromise[T]]
        val r = IOPromise[U]
        r.interrupts(f)
        f.onComplete { v =>
          try {
            t(IOs.run(v)) match {
              case v: IOPromise[U] @unchecked =>
                v.onComplete(r.complete(_))
              case v =>
                r.complete(v.asInstanceOf[U])
            }
          } catch {
            case ex if (NonFatal(ex)) =>
              r.complete(IOs(throw ex))
          }
        }
        r
      } else {
        t(fiber.asInstanceOf[T])
      }
  }

  final class Fibers private[fibers] extends Effect[Fiber] {

    def run[T](v: T > Fibers): Fiber[T] =
      val a: Fiber[T] > Nothing = v << Fibers
      a

    def done[T](v: T): Fiber[T] =
      v

    def promise[T]: Promise[T] > IOs =
      IOs(IOPromise[T])

    private[kyo] def unsafePromise[T]: Promise[T] =
      IOPromise[T]

    /*inline(2)*/
    def forkFiber[T](v: => T > (IOs | Fibers)): Fiber[T] > IOs =
      IOs(IOTask(IOs(v)))

    /*inline(2)*/
    def fork[T](v: => T > (IOs | Fibers)): T > (IOs | Fibers) =
      forkFiber(v)(_.join)

    def fork[T1, T2](
        v1: => T1 > (IOs | Fibers),
        v2: => T2 > (IOs | Fibers)
    ): (T1, T2) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2)))(s => (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2]))

    def fork[T1, T2, T3](
        v1: => T1 > (IOs | Fibers),
        v2: => T2 > (IOs | Fibers),
        v3: => T3 > (IOs | Fibers)
    ): (T1, T2, T3) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2), IOs(v3)))(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
      )

    def fork[T1, T2, T3, T4](
        v1: => T1 > (IOs | Fibers),
        v2: => T2 > (IOs | Fibers),
        v3: => T3 > (IOs | Fibers),
        v4: => T4 > (IOs | Fibers)
    ): (T1, T2, T3, T4) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2), IOs(v3), IOs(v4)))(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
      )

    def race[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers)
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2)))(_.join)

    def race[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers),
        v3: => T > (IOs | Fibers)
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2), IOs(v2)))(_.join)

    def race[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers),
        v3: => T > (IOs | Fibers),
        v4: => T > (IOs | Fibers)
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4)))(_.join)

    def raceFiber[T](l: List[T > (IOs | Fibers)]): Fiber[T] > IOs =
      require(!l.isEmpty)
      IOs {
        val p = IOPromise[T]
        l.foreach { io =>
          val f = IOTask(io)
          p.interrupts(f)
          f.onComplete(p.complete(_))
        }
        p
      }

    def await[T](
        v1: => T > (IOs | Fibers)
    ): Unit > (IOs | Fibers) =
      fork(v1)(_ => ())

    def await[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers)
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2)))(_.join)

    def await[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers),
        v3: => T > (IOs | Fibers)
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2), IOs(v2)))(_.join)

    def await[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers),
        v3: => T > (IOs | Fibers),
        v4: => T > (IOs | Fibers)
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4)))(_.join)

    def awaitFiber[T](l: List[T > (IOs | Fibers)]): Fiber[Unit] > IOs =
      IOs {
        val p       = IOPromise[Unit]
        val pending = AtomicInteger(l.size)
        var i       = 0
        val f: T > IOs => Unit =
          r =>
            try {
              IOs.run(r)
              if (pending.decrementAndGet() == 0) {
                p.complete(())
              }
            } catch {
              case ex if (NonFatal(ex)) =>
                p.complete(IOs[Unit, Nothing](throw ex))
            }
        l.foreach { io =>
          val fiber = IOTask(io)
          p.interrupts(fiber)
          fiber.onComplete(f)
          i += 1
        }
        p
      }

    def collect[T](l: List[T > (IOs | Fibers)]): Seq[T] > (IOs | Fibers) =
      collectFiber[T](l)(_.join)

    def collectFiber[T](l: List[T > (IOs | Fibers)]): Fiber[Seq[T]] > IOs =
      IOs {
        val p       = IOPromise[Seq[T]]
        val size    = l.size
        val results = (new Array[Any](size)).asInstanceOf[Array[T]]
        val pending = AtomicInteger(size)
        var i       = 0
        l.foreach { io =>
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
                p.complete(IOs[Seq[T], Nothing](throw ex))
            }
          }
          i += 1
        }
        (p: Fiber[Seq[T]])
      }

    def never: Fiber[Unit] > IOs =
      IOs(IOPromise[Unit])

    def sleep(d: Duration): Unit > (IOs | Fibers | Timers) =
      promise[Unit] { p =>
        if (d.isFinite) {
          val run: Unit > IOs =
            IOs {
              IOTask(IOs(p.complete(())))
              ()
            }
          Timers.schedule(run, d) { t =>
            IOs(p.onComplete(_ => IOs.run(t.cancel)))(_ => p.join)
          }
        } else {
          p.join
        }
      }

    /*inline(2)*/
    def block[T, S](v: T > (S | Fibers)): T > (S | IOs) =
      given Handler[Fiber, Fibers] =
        new Handler[Fiber, Fibers] {
          def pure[T](v: T) =
            v
          def apply[T, U, S](m: Fiber[T], f: T => U > (S | Fibers)) =
            m match {
              case m: IOPromise[T] @unchecked =>
                f(m.block())
              case _ =>
                f(m.asInstanceOf[T])
            }
        }
      (v < Fibers)(_.block)

    def join[T](f: Future[T]): T > (IOs | Fibers) =
      import scala.concurrent.ExecutionContext.Implicits.global
      IOs {
        val p = IOPromise[T]
        f.onComplete { r =>
          try {
            p.complete(r.get)
          } catch {
            case ex if (NonFatal(ex)) =>
              p.complete(IOs(throw ex))
          }
        }
        p > Fibers
      }
  }
  val Fibers = new Fibers

  /*inline(2)*/
  given DeepHandler[Fiber, Fibers] =
    new DeepHandler[Fiber, Fibers] {
      def pure[T](v: T) = v
      def flatMap[T, U](m: Fiber[T], f: T => Fiber[U]): Fiber[U] =
        m.transform(f)
    }
}
