package kyo.concurrent

import kyo._
import kyo.core._
import kyo.core.internal._
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
import scala.annotation.targetName

object fibers {

  private[concurrent] case class Failed(reason: Throwable)

  object Fiber {

    class Promise[T] private[kyo] (p: IOPromise[T]) extends Fiber[T](p) {

      def complete(v: => T > IOs): Boolean > IOs =
        IOs(p.complete(IOs(v)))

      private[kyo] def unsafeComplete(v: T > IOs): Boolean =
        p.complete(v)
    }

    def done[T](value: T): Fiber[T]                        = Fiber(value)
    def failed[T](reason: Throwable): Fiber[T]             = Fiber(Failed(reason))
    private[kyo] def promise[T](p: IOPromise[T]): Fiber[T] = Fiber(p)
  }

  class Fiber[T] private[Fiber] (
      private[concurrent] val state: Any /* T | IOPromise[T] | Failed */
  ) {

    def isDone: Boolean > IOs =
      state match {
        case promise: IOPromise[_] =>
          IOs(promise.isDone())
        case _: Failed =>
          true
        case _ =>
          false
      }

    def join: T > Fibers =
      state match {
        case promise: IOPromise[_] =>
          Fibers.join(this)
        case failed: Failed =>
          Fibers.join(this)
        case _ =>
          state.asInstanceOf[T > Fibers]
      }

    def onComplete(f: T > IOs => Unit): Unit > IOs =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.onComplete(f))
        case Failed(ex) =>
          f(IOs.fail(ex))
        case _ =>
          f(state.asInstanceOf[T > IOs])
      }

    def joinTry: Try[T] > (Fibers with IOs) =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs {
            val p = new IOPromise[Try[T]]
            p.interrupts(promise)
            promise.onComplete { t =>
              p.complete(Try(IOs.run(t)))
            }
            Fibers.join(Fiber.promise(p))
          }
        case Failed(ex) =>
          Failure(ex)
        case _ =>
          Success(state.asInstanceOf[T])
      }

    def block: T > IOs =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.block())
        case Failed(ex) =>
          throw ex
        case _ =>
          state.asInstanceOf[T > IOs]
      }

    def interruptAwait: Boolean > (Fibers with IOs) =
      interruptAwait("")

    def interruptAwait(reason: String): Boolean > (Fibers with IOs) =
      state match {
        case ioTask: IOTask[T] @unchecked =>
          IOs {
            val p = IOPromise[Boolean]()
            ioTask.ensure(() => p.complete(true))
            interrupt(reason).map {
              case true  => Fiber(p).join
              case false => false
            }
          }
        case promise: IOPromise[T] @unchecked =>
          interrupt(reason)
        case _ =>
          false
      }

    def interrupt(reason: String): Boolean > IOs =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.interrupt(reason))
        case _ =>
          false
      }

    def interrupt: Boolean > IOs =
      interrupt("")

    def toFuture: Future[T] > IOs =
      state match {
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
          Future.successful(state.asInstanceOf[T])
      }

    def transform[U](t: T => Fiber[U]): Fiber[U] > IOs =
      IOs(unsafeTransform(t))

    private[kyo] def unsafeTransform[U](t: T => Fiber[U]): Fiber[U] =
      state match {
        case promise: IOPromise[T] @unchecked =>
          val r = IOPromise[U]()
          r.interrupts(promise)
          promise.onComplete { v =>
            try {
              t(IOs.run(v)).state match {
                case v: IOPromise[U] @unchecked =>
                  r.become(v)
                case Failed(ex) =>
                  r.complete(IOs.fail(ex))
                case v =>
                  r.complete(v.asInstanceOf[U])
              }
            } catch {
              case ex if (NonFatal(ex)) =>
                r.complete(IOs.fail(ex))
            }
          }
          Fiber(r)
        case failed: Failed =>
          this.asInstanceOf[Fiber[U]]
        case _ =>
          try t(state.asInstanceOf[T])
          catch {
            case ex if (NonFatal(ex)) =>
              Fibers.fail(ex)
          }
      }
  }

  final class Fibers private[fibers] extends Effect[Fiber, Fibers] {

    case class Interrupted(reason: String)
        extends RuntimeException(reason)
        with NoStackTrace

    def run[T](v: T > Fibers): Fiber[T] = {
      implicit val handler: DeepHandler[Fiber, Fibers] =
        new DeepHandler[Fiber, Fibers] {
          def pure[T](v: T) = Fiber.done(v)
          def apply[T, U](m: Fiber[T], f: T => Fiber[U]): Fiber[U] =
            m.unsafeTransform(f)
        }
      deepHandle(this)(v)
    }

    def value[T](v: T): Fiber[T] =
      Fiber.done(v)

    def join[T, S](v: Fiber[T] > S): T > (Fibers with S) =
      suspend(v)

    def fail[T](ex: Throwable): Fiber[T] =
      Fiber.failed(ex)

    def promise[T]: Fiber.Promise[T] > IOs =
      IOs(Fiber.Promise(IOPromise[T]()))

    private[kyo] def unsafePromise[T]: Fiber.Promise[T] =
      Fiber.Promise(IOPromise[T]())

    // compiler bug workaround
    private val IOTask = kyo.concurrent.scheduler.IOTask

    def forkFiber[T](v: => T > (IOs with Fibers)): Fiber[T] > IOs =
      Locals.save.map(st => Fiber.promise(IOTask(IOs(v), st)))

    def fork[T](v: => T > (IOs with Fibers)): T > (IOs with Fibers) =
      forkFiber(v).map(_.join)

    def fork[T1, T2](
        v1: => T1 > (IOs with Fibers),
        v2: => T2 > (IOs with Fibers)
    ): (T1, T2) > (IOs with Fibers) =
      collect(List(IOs(v1), IOs(v2))).map(s => (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2]))

    def fork[T1, T2, T3](
        v1: => T1 > (IOs with Fibers),
        v2: => T2 > (IOs with Fibers),
        v3: => T3 > (IOs with Fibers)
    ): (T1, T2, T3) > (IOs with Fibers) =
      collect(List(IOs(v1), IOs(v2), IOs(v3))).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
      )

    def fork[T1, T2, T3, T4](
        v1: => T1 > (IOs with Fibers),
        v2: => T2 > (IOs with Fibers),
        v3: => T3 > (IOs with Fibers),
        v4: => T4 > (IOs with Fibers)
    ): (T1, T2, T3, T4) > (IOs with Fibers) =
      collect(List(IOs(v1), IOs(v2), IOs(v3), IOs(v4))).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
      )

    def race[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers)
    ): T > (IOs with Fibers) =
      raceFiber(List(IOs(v1), IOs(v2))).map(_.join)

    def race[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers),
        v3: => T > (IOs with Fibers)
    ): T > (IOs with Fibers) =
      raceFiber(List(IOs(v1), IOs(v2), IOs(v2))).map(_.join)

    def race[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers),
        v3: => T > (IOs with Fibers),
        v4: => T > (IOs with Fibers)
    ): T > (IOs with Fibers) =
      raceFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4))).map(_.join)

    private inline def foreach[T, U](inline l: List[T])(inline f: T => Unit): Unit =
      var curr = l
      while (curr ne Nil) {
        f(curr.head)
        curr = curr.tail
      }

    def raceFiber[T](l: List[T > (IOs with Fibers)]): Fiber[T] > IOs =
      require(!l.isEmpty)
      Locals.save.map { st =>
        val p = IOPromise[T]
        foreach(l) { io =>
          val f = IOTask(IOs(io), st)
          p.interrupts(f)
          f.onComplete(p.complete(_))
        }
        Fiber.promise(p)
      }

    def await[T](
        v1: => T > (IOs with Fibers)
    ): Unit > (IOs with Fibers) =
      fork(v1).map(_ => ())

    def await[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers)
    ): Unit > (IOs with Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2))).map(_.join)

    def await[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers),
        v3: => T > (IOs with Fibers)
    ): Unit > (IOs with Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2), IOs(v2))).map(_.join)

    def await[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers),
        v3: => T > (IOs with Fibers),
        v4: => T > (IOs with Fibers)
    ): Unit > (IOs with Fibers) =
      awaitFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4))).map(_.join)

    def awaitFiber[T](l: List[T > (IOs with Fibers)]): Fiber[Unit] > IOs =
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
                p.complete(IOs[Unit, Any](throw ex))
            }
        foreach(l) { io =>
          val fiber = IOTask(IOs(io), st)
          p.interrupts(fiber)
          fiber.onComplete(f)
          i += 1
        }
        Fiber.promise(p)
      }

    def collect[T](l: List[T > (IOs with Fibers)]): Seq[T] > (IOs with Fibers) =
      collectFiber[T](l).map(_.join)

    def collectFiber[T](l: List[T > (IOs with Fibers)]): Fiber[Seq[T]] > IOs =
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
                p.complete(IOs[Seq[T], Any](throw ex))
            }
          }
          i += 1
        }
        Fiber.promise(p)
      }

    def never: Fiber[Unit] > IOs =
      IOs(Fiber.promise(IOPromise[Unit]))

    def sleep(d: Duration): Unit > (IOs with Fibers with Timers) =
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

    def timeout[T](d: Duration)(v: => T > (IOs with Fibers)): T > (IOs with Fibers with Timers) =
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

    def block[T, S](v: T > (Fibers with S)): T > (IOs with S) =
      implicit def handler: Handler[Fiber, Fibers] =
        new Handler[Fiber, Fibers] {
          def pure[T](v: T) = Fiber.done(v)
          override def handle[T](ex: Throwable): T > Fibers =
            Fibers.join(Fiber.failed(ex))
          def apply[T, U, S](m: Fiber[T], f: T => U > (S with Fibers)) =
            m.state match {
              case m: IOPromise[T] @unchecked =>
                f(m.block())
              case Failed(ex) =>
                handle(ex)
              case _ =>
                f(m.asInstanceOf[T])
            }
        }
      handle(v).map(_.block)

    def join[T](f: Future[T]): T > (IOs with Fibers) =
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
        Fiber.promise(p)
      }
  }
  val Fibers = new Fibers

}
