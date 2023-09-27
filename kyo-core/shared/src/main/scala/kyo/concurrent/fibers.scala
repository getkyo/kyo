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
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util._
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal

import scheduler._
import timers._
import scala.annotation.implicitNotFound

object fibers {

  private[concurrent] case class Failed(reason: Throwable)

  type Fiber[+T] // = T | Failed[T] | IOPromise[T]

  type Promise[+T] <: Fiber[T] // = IOPromise[T]

  implicit class PromiseOps[T](private val p: Promise[T]) extends AnyVal {

    def complete(v: => T > IOs): Boolean > IOs =
      IOs(p.asInstanceOf[IOPromise[T]].complete(IOs(v)))

    private[kyo] def unsafeComplete(v: T > IOs): Boolean =
      p.asInstanceOf[IOPromise[T]].complete(v)
  }

  object Fiber {
    def done[T](value: T): Fiber[T]                          = value.asInstanceOf[Fiber[T]]
    def failed[T](reason: Throwable): Fiber[T]               = Failed(reason).asInstanceOf[Fiber[T]]
    private[kyo] def promise[T](p: IOPromise[T]): Promise[T] = p.asInstanceOf[Promise[T]]
  }

  implicit class FiberOps[T](private val state: Fiber[T]) extends AnyVal {

    def isDone: Boolean > IOs =
      state match {
        case promise: IOPromise[_] =>
          IOs(promise.isDone())
        case _ =>
          true
      }

    def get: T > Fibers =
      state match {
        case promise: IOPromise[_] =>
          Fibers.join(state)
        case failed: Failed =>
          Fibers.join(state)
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

    def getTry: Try[T] > (Fibers with IOs) =
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
          IOs.fail(ex)
        case _ =>
          state.asInstanceOf[T > IOs]
      }

    def interruptAwait: Boolean > (Fibers with IOs) =
      state match {
        case ioTask: IOTask[T] @unchecked =>
          IOs {
            val p = new IOPromise[Boolean]()
            ioTask.ensure(() => p.complete(true))
            interrupt.map {
              case true  => Fiber.promise(p).get
              case false => false
            }
          }
        case promise: IOPromise[T] @unchecked =>
          interrupt
        case _ =>
          false
      }

    def interrupt: Boolean > IOs =
      state match {
        case promise: IOPromise[T] @unchecked =>
          IOs(promise.interrupt())
        case _ =>
          false
      }

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
          val r = new IOPromise[U]()
          r.interrupts(promise)
          promise.onComplete { v =>
            try {
              t(IOs.run(v)) match {
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
          Fiber.promise(r)
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

    case object Interrupted
        extends RuntimeException
        with NoStackTrace

    private[kyo] val interrupted = IOs.fail(Interrupted)

    def run[T](v: T > Fibers)(implicit
        @implicitNotFound(
            "Computation can have only `Fibers` pending. Found: `${T}`"
        ) ng: kyo.NotGiven[(
            Nothing > Any
        ) => T]
    ): Fiber[T] > IOs = {
      implicit val handler: DeepHandler[Fiber, Fibers] =
        new DeepHandler[Fiber, Fibers] {
          def pure[T](v: T) = Fiber.done(v)
          def apply[T, U](m: Fiber[T], f: T => Fiber[U]): Fiber[U] =
            m.unsafeTransform(f)
        }
      IOs(deepHandle[Fiber, Fibers, T](Fibers)(v))
    }

    def runBlocking[T, S](v: T > (Fibers with S)): T > (IOs with S) = {
      implicit def handler: Handler[Fiber, Fibers] =
        new Handler[Fiber, Fibers] {
          def pure[T](v: T) = Fiber.done(v)
          override def handle[T](ex: Throwable): T > Fibers =
            Fibers.join(Fiber.failed[T](ex))
          def apply[T, U, S](m: Fiber[T], f: T => U > (Fibers with S)) =
            m match {
              case m: IOPromise[T] @unchecked =>
                f(m.block())
              case Failed(ex) =>
                handle(ex)
              case _ =>
                f(m.asInstanceOf[T])
            }
        }
      IOs[T, S](handle[T, IOs with S](v).map(_.block))
    }

    def value[T](v: T): Fiber[T] =
      Fiber.done(v)

    def get[T, S](v: Fiber[T] > S): T > (Fibers with S) =
      v.map(_.get)

    private[fibers] def join[T, S](v: Fiber[T] > S): T > (Fibers with S) =
      suspend(v)

    def fail[T](ex: Throwable): Fiber[T] =
      Fiber.failed(ex)

    private val _promise = IOs(unsafePromise[Object])

    def promise[T]: Promise[T] > IOs =
      _promise.asInstanceOf[Promise[T] > IOs]

    private[kyo] def unsafePromise[T]: Promise[T] =
      Fiber.promise(new IOPromise[T]())

    // compiler bug workaround
    private val IOTask = kyo.concurrent.scheduler.IOTask

    /*inline*/
    def forkFiber[T]( /*inline*/ v: => T > (IOs with Fibers)): Fiber[T] > IOs =
      Locals.save.map(st => Fiber.promise(IOTask(IOs(v), st)))

    /*inline*/
    def fork[T]( /*inline*/ v: => T > (IOs with Fibers)): T > (IOs with Fibers) =
      Fibers.join(forkFiber(v))

    def parallel[T1, T2](
        v1: => T1 > (IOs with Fibers),
        v2: => T2 > (IOs with Fibers)
    ): (T1, T2) > (IOs with Fibers) =
      parallel(List(IOs(v1), IOs(v2))).map(s => (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2]))

    def parallel[T1, T2, T3](
        v1: => T1 > (IOs with Fibers),
        v2: => T2 > (IOs with Fibers),
        v3: => T3 > (IOs with Fibers)
    ): (T1, T2, T3) > (IOs with Fibers) =
      parallel(List(IOs(v1), IOs(v2), IOs(v3))).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
      )

    def parallel[T1, T2, T3, T4](
        v1: => T1 > (IOs with Fibers),
        v2: => T2 > (IOs with Fibers),
        v3: => T3 > (IOs with Fibers),
        v4: => T4 > (IOs with Fibers)
    ): (T1, T2, T3, T4) > (IOs with Fibers) =
      parallel(List(IOs(v1), IOs(v2), IOs(v3), IOs(v4))).map(s =>
        (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
      )

    def parallel[T](l: Seq[T > (IOs with Fibers)]): Seq[T] > (IOs with Fibers) =
      Fibers.join(parallelFiber[T](l))

    def parallelFiber[T](l: Seq[T > (IOs with Fibers)]): Fiber[Seq[T]] > IOs =
      Locals.save.map { st =>
        IOs {
          val p       = new IOPromise[Seq[T]]
          val size    = l.size
          val results = (new Array[Any](size)).asInstanceOf[Array[T]]
          val pending = new AtomicInteger(size)
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
                  p.complete(IOs.fail(ex))
              }
            }
            i += 1
          }
          Fiber.promise(p)
        }
      }

    def race[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers)
    ): T > (IOs with Fibers) =
      race(List(IOs(v1), IOs(v2)))

    def race[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers),
        v3: => T > (IOs with Fibers)
    ): T > (IOs with Fibers) =
      race(List(IOs(v1), IOs(v2), IOs(v2)))

    def race[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers),
        v3: => T > (IOs with Fibers),
        v4: => T > (IOs with Fibers)
    ): T > (IOs with Fibers) =
      race(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4)))

    def race[T](l: Seq[T > (IOs with Fibers)]): T > (IOs with Fibers) =
      Fibers.join(raceFiber[T](l))

    def raceFiber[T](l: Seq[T > (IOs with Fibers)]): Fiber[T] > IOs = {
      require(!l.isEmpty)
      Locals.save.map { st =>
        IOs {
          val p = new IOPromise[T]
          foreach(l) { io =>
            val f = IOTask(IOs(io), st)
            p.interrupts(f)
            f.onComplete(p.complete(_))
          }
          Fiber.promise(p)
        }
      }
    }

    def await[T](
        v1: => T > (IOs with Fibers)
    ): Unit > (IOs with Fibers) =
      fork(v1).map(_ => ())

    def await[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers)
    ): Unit > (IOs with Fibers) =
      Fibers.join(awaitFiber(List(IOs(v1), IOs(v2))))

    def await[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers),
        v3: => T > (IOs with Fibers)
    ): Unit > (IOs with Fibers) =
      Fibers.join(awaitFiber(List(IOs(v1), IOs(v2), IOs(v2))))

    def await[T](
        v1: => T > (IOs with Fibers),
        v2: => T > (IOs with Fibers),
        v3: => T > (IOs with Fibers),
        v4: => T > (IOs with Fibers)
    ): Unit > (IOs with Fibers) =
      Fibers.join(awaitFiber(List(IOs(v1), IOs(v2), IOs(v2), IOs(v4))))

    def awaitFiber[T](l: Seq[T > (IOs with Fibers)]): Fiber[Unit] > IOs =
      Locals.save.map { st =>
        IOs {
          val p       = new IOPromise[Unit]
          val pending = new AtomicInteger(l.size)
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
                  p.complete(IOs.fail(ex))
              }
          foreach(l) { io =>
            val fiber = IOTask(IOs(io), st)
            p.interrupts(fiber)
            fiber.onComplete(f)
            i += 1
          }
          Fiber.promise(p)
        }
      }

    def never: Fiber[Unit] > IOs =
      IOs(Fiber.promise(new IOPromise[Unit]))

    def sleep(d: Duration): Unit > (IOs with Fibers with Timers) =
      promise[Unit].map { p =>
        if (d.isFinite) {
          val run: Unit > IOs =
            IOs {
              IOTask(IOs(p.complete(())), Locals.State.empty)
              ()
            }
          Timers.schedule(d)(run).map { t =>
            IOs.ensure(t.cancel.unit)(p.get)
          }
        } else {
          p.get
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
          IOs.ensure(t.cancel.unit)(f.get)
        }
      }

    def join[T, S](f: Future[T]): T > (IOs with Fibers) =
      Fibers.join(joinFiber(f))

    def joinFiber[T](f: Future[T]): Fiber[T] > IOs = {
      Locals.save.map { st =>
        IOs {
          val p = new IOPromise[T]()
          f.onComplete { r =>
            val io =
              IOs[Boolean, IOs] {
                r match {
                  case Success(v) =>
                    p.complete(v)
                  case Failure(ex) =>
                    p.complete(IOs.fail(ex))
                }
              }
            IOTask(io, st)
          }(ExecutionContext.parasitic)
          Fiber.promise(p)
        }
      }
    }

    private def foreach[T, U](l: Seq[T])(f: T => Unit): Unit = {
      val it = l.iterator
      while (it.hasNext) {
        f(it.next())
      }
    }
  }
  val Fibers = new Fibers

}
