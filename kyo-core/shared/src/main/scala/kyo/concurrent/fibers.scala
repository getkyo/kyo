package kyo.concurrent

import kyo._
import kyo.core._
import kyo.frames._
import kyo.ios._
import kyo.locals._
import kyo.resources._

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util._
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal

import scheduler._
import timers._

object fibers {

  private case class Failed(reason: Throwable)

  opaque type Fiber[T]               = T | IOPromise[T] | Failed
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
      fiber match {
        case promise: IOPromise[_] =>
          IOs(promise.isDone())
        case _: Failed =>
          true
        case _ =>
          false
      }

    def join: T > Fibers =
      fiber match {
        case promise: IOPromise[_] =>
          promise > Fibers
        case failed: Failed =>
          val f: Fiber[T] = failed
          f > Fibers
        case _ =>
          fiber.asInstanceOf[T > Fibers]
      }

    def onComplete(f: T > IOs => Unit): Unit > IOs =
      fiber match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.onComplete(f))
        case Failed(ex) =>
          f(IOs.fail(ex))
        case _ =>
          f(fiber.asInstanceOf[T > IOs])
      }

    /*inline(2)*/
    def joinTry: Try[T] > (Fibers | IOs) =
      fiber match {
        case promise: IOPromise[T] @unchecked =>
          IOs {
            val p = new IOPromise[Try[T]]
            p.interrupts(promise)
            promise.onComplete { t =>
              p.complete(Try(IOs.run(t)))
            }
            p > Fibers
          }
        case Failed(ex) =>
          Failure(ex)
        case _ =>
          Success(fiber.asInstanceOf[T])
      }

    def block: T > IOs =
      fiber match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.block())
        case Failed(ex) =>
          throw ex
        case _ =>
          fiber.asInstanceOf[T > IOs]
      }

    /*inline(2)*/
    def interruptAwait: Boolean > (Fibers | IOs) =
      interruptAwait("")

    /*inline(2)*/
    def interruptAwait(reason: String): Boolean > (Fibers | IOs) =
      fiber match {
        case ioTask: IOTask[T] @unchecked =>
          IOs {
            val p = IOPromise[Boolean]()
            ioTask.ensure(() => p.unsafeComplete(true))
            interrupt(reason).map {
              case true  => p.join
              case false => false
            }
          }
        case promise: IOPromise[T] @unchecked =>
          interrupt(reason)
        case _ =>
          false
      }

    /*inline(2)*/
    def interrupt(reason: String): Boolean > IOs =
      fiber match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.interrupt(reason))
        case _ =>
          false
      }

    def interrupt: Boolean > IOs =
      interrupt("")

    def toFuture: Future[T] > IOs =
      fiber match {
        case promise: IOPromise[T] @unchecked =>
          IOs {
            val p = scala.concurrent.Promise[T]()
            promise.onComplete { v =>
              p.complete(Try(IOs.run(v)))
            }
            p.future
          }
        case Failed(ex) =>
          Future.failed(ex)
        case _ =>
          Future.successful(fiber.asInstanceOf[T])
      }

    def transform[U](t: T => Fiber[U]): Fiber[U] > IOs =
      IOs(fiber.unsafeTransform(t))

    private[kyo] def unsafeTransform[U](t: T => Fiber[U]): Fiber[U] =
      fiber match {
        case promise: IOPromise[T] @unchecked =>
          val r = IOPromise[U]()
          r.interrupts(promise)
          promise.onComplete { v =>
            try {
              t(IOs.run(v)) match {
                case v: IOPromise[U] @unchecked =>
                  r.become(v)
                case v =>
                  r.complete(v.asInstanceOf[U])
              }
            } catch {
              case ex if (NonFatal(ex)) =>
                r.complete(IOs(throw ex))
            }
          }
          r
        case failed: Failed =>
          failed
        case _ =>
          try t(fiber.asInstanceOf[T])
          catch {
            case ex if (NonFatal(ex)) =>
              Fibers.fail(ex)
          }
      }
  }

  final class Fibers private[fibers] extends Effect[Fiber] {

    case class Interrupted(reason: String, frame: Frame[String])
        extends RuntimeException(s"reason=$reason, frame=$frame")
        with NoStackTrace

    /*inline(2)*/
    def run[T](v: T > Fibers): Fiber[T] =
      val a: Fiber[T] > Nothing = v << Fibers
      a

    def value[T](v: T): Fiber[T] =
      v

    def fail[T](ex: Throwable): Fiber[T] =
      Failed(ex)

    /*inline(2)*/
    def promise[T]: Promise[T] > IOs =
      IOs(IOPromise[T]())

    private[kyo] def unsafePromise[T]: Promise[T] =
      IOPromise[T]()

    // compiler bug workaround
    private val IOTask = kyo.concurrent.scheduler.IOTask

    /*inline(2)*/
    def forkFiber[T](v: => T > (IOs | Fibers)): Fiber[T] > IOs =
      Locals.save.map(st => IOTask(IOs(v), st))

    /*inline(2)*/
    def fork[T](v: => T > (IOs | Fibers)): T > (IOs | Fibers) =
      forkFiber(v).map(_.join)

    def fork[T1, T2](
        v1: => T1 > (IOs | Fibers),
        v2: => T2 > (IOs | Fibers)
    ): (T1, T2) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2))).map(s => (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2]))

    def fork[T1, T2, T3](
        v1: => T1 > (IOs | Fibers),
        v2: => T2 > (IOs | Fibers),
        v3: => T3 > (IOs | Fibers)
    ): (T1, T2, T3) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2), IOs(v3))).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
      )

    def fork[T1, T2, T3, T4](
        v1: => T1 > (IOs | Fibers),
        v2: => T2 > (IOs | Fibers),
        v3: => T3 > (IOs | Fibers),
        v4: => T4 > (IOs | Fibers)
    ): (T1, T2, T3, T4) > (IOs | Fibers) =
      collect(List(IOs(v1), IOs(v2), IOs(v3), IOs(v4))).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
      )

    def race[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers)
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2))).map(_.join)

    def race[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers),
        v3: => T > (IOs | Fibers)
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2), IOs(v2))).map(_.join)

    def race[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers),
        v3: => T > (IOs | Fibers),
        v4: => T > (IOs | Fibers)
    ): T > (IOs | Fibers) =
      raceFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4))).map(_.join)

    private inline def foreach[T, U](inline l: List[T])(inline f: T => Unit): Unit =
      var curr = l
      while (curr ne Nil) {
        f(curr.head)
        curr = curr.tail
      }

    def raceFiber[T](l: List[T > (IOs | Fibers)]): Fiber[T] > IOs =
      require(!l.isEmpty)
      Locals.save.map { st =>
        val p = IOPromise[T]
        foreach(l) { io =>
          val f = IOTask(IOs(io), st)
          p.interrupts(f)
          f.onComplete(p.complete(_))
        }
        p
      }

    def await[T](
        v1: => T > (IOs | Fibers)
    ): Unit > (IOs | Fibers) =
      fork(v1).map(_ => ())

    def await[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers)
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2))).map(_.join)

    def await[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers),
        v3: => T > (IOs | Fibers)
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2), IOs(v2))).map(_.join)

    def await[T](
        v1: => T > (IOs | Fibers),
        v2: => T > (IOs | Fibers),
        v3: => T > (IOs | Fibers),
        v4: => T > (IOs | Fibers)
    ): Unit > (IOs | Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4))).map(_.join)

    def awaitFiber[T](l: List[T > (IOs | Fibers)]): Fiber[Unit] > IOs =
      Locals.save.map { st =>
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
        foreach(l) { io =>
          val fiber = IOTask(IOs(io), st)
          p.interrupts(fiber)
          fiber.onComplete(f)
          i += 1
        }
        p
      }

    def collect[T](l: List[T > (IOs | Fibers)]): Seq[T] > (IOs | Fibers) =
      collectFiber[T](l).map(_.join)

    def collectFiber[T](l: List[T > (IOs | Fibers)]): Fiber[Seq[T]] > IOs =
      Locals.save.map { st =>
        val p       = IOPromise[Seq[T]]
        val size    = l.size
        val results = (new Array[Any](size)).asInstanceOf[Array[T]]
        val pending = AtomicInteger(size)
        var i       = 0
        foreach(l) { io =>
          val fiber = IOTask(IOs(io), st)
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
      promise[Unit].map { p =>
        if (d.isFinite) {
          val run: Unit > IOs =
            IOs {
              IOTask(IOs(p.complete(())), Locals.State.empty)
              ()
            }
          Timers.schedule(d)(run).map { t =>
            IOs.ensure(t.cancel.unit)(p.join)
          }
        } else {
          p.join
        }
      }

    def timeout[T](d: Duration)(v: => T > (IOs | Fibers))(using
        fr: Frame["Fibers.timeout"]
    ): T > (IOs | Fibers | Timers) =
      forkFiber(v).map { f =>
        val timeout: Unit > IOs =
          IOs {
            IOTask(IOs(f.interrupt), Locals.State.empty)
            ()
          }
        Timers.schedule(d)(timeout).map { t =>
          IOs.ensure(t.cancel.unit)(f.join)
        }
      }

    /*inline(2)*/
    def block[T, S](v: T > (S | Fibers)): T > (S | IOs) =
      given Handler[Fiber, Fibers] =
        new Handler[Fiber, Fibers] {
          def pure[T](v: T) = v
          override def handle[T](ex: Throwable): T > Fibers =
            IOPromise(IOs(throw ex)) > Fibers
          def apply[T, U, S](m: Fiber[T], f: T => U > (S | Fibers)) =
            m match {
              case m: IOPromise[T] @unchecked =>
                f(m.block())
              case _ =>
                f(m.asInstanceOf[T])
            }
        }
      (v < Fibers).map(_.block)

    def join[T](f: Future[T]): T > (IOs | Fibers) =
      joinFiber(f).map(_.join)

    def joinFiber[T](f: Future[T]): Fiber[T] > IOs =
      import scala.concurrent.ExecutionContext.Implicits.global
      Locals.save.map { st =>
        val p = IOPromise[T]()
        f.onComplete { r =>
          val io =
            IOs {
              r match {
                case Success(v) =>
                  p.complete(v)
                case Failure(ex) =>
                  p.complete(IOs(throw ex))
              }
            }
          IOTask(io, st)
        }
        p
      }
  }
  val Fibers = new Fibers

  /*inline(2)*/
  private[kyo] given DeepHandler[Fiber, Fibers] =
    new DeepHandler[Fiber, Fibers] {
      def pure[T](v: T) = v
      def apply[T, U](m: Fiber[T], f: T => Fiber[U]): Fiber[U] =
        m.unsafeTransform(f)
    }
}
