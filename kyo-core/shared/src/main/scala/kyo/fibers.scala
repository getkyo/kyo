package kyo

import kyo.core._
import kyo.core.internal._
import kyo.scheduler.IOPromise

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util._
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal

import fibersInternal._

private[kyo] case class Failed(reason: Throwable)

object Fiber {
  private[kyo] def done[T](value: T): Fiber[T]             = value.asInstanceOf[Fiber[T]]
  private[kyo] def failed[T](reason: Throwable): Fiber[T]  = Failed(reason).asInstanceOf[Fiber[T]]
  private[kyo] def promise[T](p: IOPromise[T]): Promise[T] = p.asInstanceOf[Promise[T]]

}

class PromiseOps[T](private val p: Promise[T]) extends AnyVal {

  def complete(v: T < IOs): Boolean < IOs =
    IOs(p.asInstanceOf[IOPromise[T]].complete(v))

  private[kyo] def unsafeComplete(v: T < IOs): Boolean =
    p.asInstanceOf[IOPromise[T]].complete(v)
}

class FiberOps[T](private val state: Fiber[T]) extends AnyVal {

  private given flat[S]: Flat[T < S] = Flat.unsafe.checked[T < S]

  def isDone: Boolean < IOs =
    state match {
      case promise: IOPromise[_] =>
        IOs(promise.isDone())
      case _ =>
        true
    }

  def get: T < Fibers =
    state match {
      case promise: IOPromise[_] =>
        FiberGets(state)
      case failed: Failed =>
        FiberGets(state)
      case _ =>
        state.asInstanceOf[T < Fibers]
    }

  def onComplete(f: T < IOs => Unit): Unit < IOs =
    state match {
      case promise: IOPromise[T] @unchecked =>
        IOs(promise.onComplete(f))
      case Failed(ex) =>
        f(IOs.fail(ex))
      case _ =>
        f(state.asInstanceOf[T < IOs])
    }

  def getTry: Try[T] < Fibers =
    state match {
      case promise: IOPromise[T] @unchecked =>
        IOs {
          val p = new IOPromise[Try[T]]
          p.interrupts(promise)
          promise.onComplete { t =>
            p.complete(Try(IOs.run(t)))
          }
          Fibers.get(Fiber.promise(p))
        }
      case Failed(ex) =>
        Failure(ex)
      case _ =>
        Success(state.asInstanceOf[T])
    }

  def block: T < IOs =
    state match {
      case promise: IOPromise[T] @unchecked =>
        IOs(promise.block())
      case Failed(ex) =>
        IOs.fail(ex)
      case _ =>
        state.asInstanceOf[T < IOs]
    }

  def interrupt: Boolean < IOs =
    state match {
      case promise: IOPromise[T] @unchecked =>
        IOs(promise.interrupt())
      case _ =>
        false
    }

  def toFuture: Future[T] < IOs =
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

  def transform[U](t: T => Fiber[U]): Fiber[U] < IOs =
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
            Fiber.failed(ex)
        }
    }
}

object Fibers extends Joins[fibersInternal.Fibers] {

  type Effects = FiberGets & IOs

  case object Interrupted
      extends RuntimeException
      with NoStackTrace

  private[kyo] val interrupted = IOs.fail(Interrupted)

  def run[T](v: T < Fibers)(using f: Flat[T < Fibers]): Fiber[T] < IOs =
    FiberGets.run(v)

  def runAndBlock[T, S](v: T < (Fibers & S))(implicit
      f: Flat[T < (Fibers & S)]
  ): T < (IOs & S) =
    FiberGets.runAndBlock[T, S](v)

  def value[T](v: T)(using f: Flat[T < Any]): Fiber[T] =
    Fiber.done(v)

  def get[T, S](v: Fiber[T] < S): T < (Fibers & S) =
    v.map(_.get)

  private val _promise = IOs(unsafeInitPromise[Object])

  def initPromise[T]: Promise[T] < IOs =
    _promise.asInstanceOf[Promise[T] < IOs]

  private[kyo] def unsafeInitPromise[T]: Promise[T] =
    Fiber.promise(new IOPromise[T]())

  // compiler bug workaround
  private val IOTask = kyo.scheduler.IOTask

  def init[T](v: => T < Fibers)(using f: Flat[T < Fibers]): Fiber[T] < IOs =
    Locals.save.map(st => Fiber.promise(IOTask(IOs(v), st)))

  def parallel[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): Seq[T] < Fibers =
    l.size match {
      case 0 => Seq.empty
      case 1 => l(0).map(Seq(_))
      case _ =>
        Fibers.get(parallelFiber[T](l))
    }

  def parallelFiber[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): Fiber[Seq[T]] < IOs =
    l.size match {
      case 0 => Fiber.done(Seq.empty)
      case 1 => Fibers.run(l(0).map(Seq(_)))
      case _ =>
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
    }

  def race[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): T < Fibers =
    l.size match {
      case 0 => IOs.fail("Can't race an empty list.")
      case 1 => l(0)
      case _ =>
        Fibers.get(raceFiber[T](l))
    }

  def raceFiber[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): Fiber[T] < IOs =
    l.size match {
      case 0 => IOs.fail("Can't race an empty list.")
      case 1 => Fibers.run(l(0))
      case _ =>
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

  def never: Fiber[Unit] < IOs =
    IOs(Fiber.promise(new IOPromise[Unit]))

  def delay[T, S](d: Duration)(v: => T < S): T < (S & Fibers) =
    sleep(d).andThen(v)

  def sleep(d: Duration): Unit < Fibers =
    initPromise[Unit].map { p =>
      if (d.isFinite) {
        val run: Unit < IOs =
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

  def timeout[T](d: Duration)(v: => T < Fibers)(using f: Flat[T < Fibers]): T < Fibers =
    init(v).map { f =>
      val timeout: Unit < IOs =
        IOs {
          IOTask(IOs(f.interrupt), Locals.State.empty)
          ()
        }
      Timers.schedule(d)(timeout).map { t =>
        IOs.ensure(t.cancel.unit)(f.get)
      }
    }

  def fromFuture[T, S](f: Future[T]): T < Fibers =
    Fibers.get(fromFutureFiber(f))

  def fromFutureFiber[T](f: Future[T]): Fiber[T] < IOs = {
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

object fibersInternal {

  type Fibers >: Fibers.Effects <: Fibers.Effects

  final class FiberGets private[kyo] () extends Effect[Fiber, FiberGets] {

    def apply[T, S](f: Fiber[T] < S): T < (FiberGets & S) =
      suspend(f)

    def run[T](v: T < Fibers)(using f: Flat[T < Fibers]): Fiber[T] < IOs = {
      given DeepHandler[Fiber, FiberGets] =
        new DeepHandler[Fiber, FiberGets] {
          def pure[T](v: T) = Fiber.done(v)
          def apply[T, U](m: Fiber[T], f: T => Fiber[U]): Fiber[U] =
            m.unsafeTransform(f)
        }
      IOs(deepHandle[Fiber, FiberGets, T](FiberGets)(IOs.runLazy(v)))
    }

    def runAndBlock[T, S](v: T < (Fibers & S))(implicit
        f: Flat[T < (Fibers & S)]
    ): T < (IOs & S) = {
      given Handler[Fiber, FiberGets, Any] =
        new Handler[Fiber, FiberGets, Any] {
          def pure[T](v: T) = Fiber.done(v)
          override def handle[T](ex: Throwable): T < FiberGets =
            FiberGets(Fiber.failed[T](ex))
          def apply[T, U, S](m: Fiber[T], f: T => U < (FiberGets & S)) =
            try {
              m match {
                case m: IOPromise[T] @unchecked =>
                  f(m.block())
                case Failed(ex) =>
                  handle(ex)
                case _ =>
                  f(m.asInstanceOf[T])
              }
            } catch {
              case ex if (NonFatal(ex)) =>
                handle(ex)
            }
        }
      IOs[T, S](handle[T, IOs & S, Any](v).map(_.block))
    }
  }
  val FiberGets = new FiberGets
}
