package kyo.concurrent

import kyo.concurrent.scheduler.IOPromise
import kyo.concurrent.scheduler.IOTask
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

object fibers {

  private val timer = Executors.newScheduledThreadPool(1, ThreadFactory("kyo-fiber-sleep-timer"))

  opaque type Fiber[T]               = T | IOPromise[T]
  opaque type Promise[T] <: Fiber[T] = IOPromise[T]

  extension [T](p: Promise[T]) {

    /*inline(2)*/
    def complete(v: => T > IOs): Boolean > IOs =
      IOs(p.complete(IOs(v)))
  }

  extension [T](fiber: Fiber[T]) {

    def isDone: Boolean > IOs =
      fiber match {
        case f: IOPromise[T] @unchecked =>
          IOs(f.isDone)
        case _ =>
          true
      }

    def join: T > Fibers =
      fiber match {
        case f: IOPromise[T] @unchecked =>
          f > Fibers
        case _ =>
          fiber.asInstanceOf[T > Fibers]
      }

    /*inline(2)*/
    def joinTry: Try[T] > (Fibers | IOs) =
      /*inline(2)*/ fiber match {
        case f: IOPromise[T] @unchecked =>
          IOs {
            val p = new IOPromise[Try[T]]
            p.interrupts(f)
            f.onComplete { t =>
              p.complete(Try(IOs.run(t)))
            }
            p > Fibers
          }
        case _ =>
          Try(fiber.asInstanceOf[T])
      }

    /*inline(2)*/
    def block: T > IOs =
      fiber match {
        case f: IOPromise[T] @unchecked =>
          IOs(f.block())
        case _ =>
          fiber.asInstanceOf[T > IOs]
      }

    /*inline(2)*/
    def interrupt: Boolean > IOs =
      fiber match {
        case f: IOPromise[T] @unchecked =>
          IOs(f.interrupt())
        case _ =>
          false
      }

    def transform[U](f: T => Fiber[U]): Fiber[U] =
      fiber match {
        case fiber: IOPromise[T] @unchecked =>
          val r = IOPromise[U]
          r.interrupts(fiber)
          fiber.onComplete { v =>
            try {
              f(IOs.run(v)) match {
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
        case _ =>
          f(fiber.asInstanceOf[T])
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
    def forkFiber[T](v: => T > IOs): Fiber[T] > IOs =
      IOs(IOTask(IOs(v)))

    /*inline(2)*/
    def fork[T](v: => T > IOs): T > (IOs | Fibers) =
      forkFiber(v)(_.join)

    def fork[T1, T2](
        v1: => T1 > IOs,
        v2: => T2 > IOs
    ): (T1, T2) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2)))(s => (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2]))

    def fork[T1, T2, T3](
        v1: => T1 > IOs,
        v2: => T2 > IOs,
        v3: => T3 > IOs
    ): (T1, T2, T3) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2), IOs(v3)))(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
      )

    def fork[T1, T2, T3, T4](
        v1: => T1 > IOs,
        v2: => T2 > IOs,
        v3: => T3 > IOs,
        v4: => T4 > IOs
    ): (T1, T2, T3, T4) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2), IOs(v3), IOs(v4)))(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
      )

    def race[T](
        v1: => T > IOs,
        v2: => T > IOs
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2)))(_.join)

    def race[T](
        v1: => T > IOs,
        v2: => T > IOs,
        v3: => T > IOs
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2), IOs(v2)))(_.join)

    def race[T](
        v1: => T > IOs,
        v2: => T > IOs,
        v3: => T > IOs,
        v4: => T > IOs
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4)))(_.join)

    def raceFiber[T](l: List[T > IOs]): Fiber[T] > IOs =
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
        v1: => T > IOs
    ): Unit > (IOs | Fibers) =
      fork(v1)(_ => ())

    def await[T](
        v1: => T > IOs,
        v2: => T > IOs
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2)))(_.join)

    def await[T](
        v1: => T > IOs,
        v2: => T > IOs,
        v3: => T > IOs
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2), IOs(v2)))(_.join)

    def await[T](
        v1: => T > IOs,
        v2: => T > IOs,
        v3: => T > IOs,
        v4: => T > IOs
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4)))(_.join)

    def awaitFiber[T](l: List[T > IOs]): Fiber[Unit] > IOs =
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

    def collect[T](l: List[T > IOs]): Seq[T] > (IOs | Fibers) =
      collectFiber[T](l)(_.join)

    def collectFiber[T](l: List[T > IOs]): Fiber[Seq[T]] > IOs =
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

    def sleep(d: Duration): Unit > (IOs | Fibers) =
      IOs {
        val p = new IOPromise[Unit] with Runnable with (ScheduledFuture[_] => Unit) { self =>
          @volatile var timerTask: ScheduledFuture[_] = null
          def apply(f: ScheduledFuture[_]) =
            timerTask = f
          def run() =
            IOTask(IOs(self.complete(())))
            val t = timerTask
            if (t != null) {
              t.cancel(false)
              timerTask = null
            }
        }
        if (d.isFinite) {
          p(timer.schedule(p, d.toMillis, TimeUnit.MILLISECONDS))
        }
        p > Fibers
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
