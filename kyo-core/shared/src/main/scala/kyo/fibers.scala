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

sealed abstract class Fiber[+T] {
  def isDone: Boolean < IOs
  def get: T < Fibers
  def getTry(using f: Flat[T]): Try[T] < Fibers
  def onComplete(f: T < IOs => Unit < IOs): Unit < IOs
  def block: T < IOs
  def interrupt: Boolean < IOs
  def toFuture(using f: Flat[T]): Future[T] < IOs
  def transform[U](t: T => Fiber[U] < IOs)(using f: Flat[T]): Fiber[U] < IOs
}

case class Promise[T](private[kyo] val p: IOPromise[T]) extends Fiber[T] {

  def isDone = IOs(p.isDone())

  def get = FiberGets(this)

  def getTry(using f: Flat[T]) =
    IOs {
      val r = new IOPromise[Try[T]]
      r.interrupts(p)
      p.onComplete { t =>
        r.complete(IOs.attempt(t))
      }
      Promise(r).get
    }

  def onComplete(f: T < IOs => Unit < IOs) =
    IOs(p.onComplete(r => IOs.run(f(r))))

  def block =
    p.block

  def interrupt =
    IOs(p.interrupt())

  def toFuture(using f: Flat[T]) =
    IOs {
      val r = scala.concurrent.Promise[T]()
      p.onComplete { v =>
        r.complete(Try(IOs.run(v)))
      }
      r.future
    }

  def transform[U](t: T => Fiber[U] < IOs)(using f: Flat[T]) =
    IOs {
      val r = new IOPromise[U]()
      r.interrupts(p)
      p.onComplete { v =>
        try {
          t(IOs.run(v)).map {
            case Promise(v: IOPromise[U]) =>
              r.become(v)
            case Done(v) =>
              r.complete(v)
          }
        } catch {
          case ex if (NonFatal(ex)) =>
            r.complete(IOs.fail(ex))
        }
      }
      Promise(r)
    }

  def complete(v: T < IOs): Boolean < IOs = IOs(p.complete(v))

  private[kyo] def unsafeComplete(v: T < IOs): Boolean =
    p.complete(v)
}

type Fibers >: Fibers.Effects <: Fibers.Effects

object Fibers extends Joins[Fibers] {

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
    Done(v)

  def get[T, S](v: Fiber[T] < S): T < (Fibers & S) =
    v.map(_.get)

  private val _promise = IOs(unsafeInitPromise[Object])

  def initPromise[T]: Promise[T] < IOs =
    _promise.asInstanceOf[Promise[T] < IOs]

  private[kyo] def unsafeInitPromise[T]: Promise[T] =
    Promise(new IOPromise[T]())

  // compiler bug workaround
  private val IOTask = kyo.scheduler.IOTask

  def init[T](v: => T < Fibers)(using f: Flat[T < Fibers]): Fiber[T] < IOs =
    Locals.save.map(st => Promise(IOTask(IOs(v), st)))

  def parallel[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): Seq[T] < Fibers =
    l.size match {
      case 0 => Seq.empty
      case 1 => l(0).map(Seq(_))
      case _ =>
        Fibers.get(parallelFiber[T](l))
    }

  def parallelFiber[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): Fiber[Seq[T]] < IOs =
    l.size match {
      case 0 => Done(Seq.empty)
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
            Promise(p)
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
            Promise(p)
          }
        }
    }

  def never: Fiber[Unit] < IOs =
    IOs(Promise(new IOPromise[Unit]))

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
        Promise(p)
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

  case class Done[T](result: T < IOs) extends Fiber[T] {
    def isDone                               = true
    def get                                  = result
    def getTry(using f: Flat[T])             = IOs.attempt(result)
    def onComplete(f: T < IOs => Unit < IOs) = f(result)
    def block                                = result
    def interrupt                            = false

    def toFuture(using f: Flat[T]) =
      Future.fromTry(Try(IOs.run(result)))

    def transform[U](t: T => Fiber[U] < IOs)(using f: Flat[T]) =
      result.map(t)
  }

  final class FiberGets private[kyo] () extends Effect[Fiber, FiberGets] {

    def apply[T, S](f: Fiber[T] < S): T < (FiberGets & S) =
      suspend(f)

    def run[T](v: T < Fibers)(using f: Flat[T < Fibers]): Fiber[T] < IOs = {
      given DeepHandler[Fiber, FiberGets, IOs] =
        new DeepHandler[Fiber, FiberGets, IOs] {
          def pure[T](v: T) = Done(v)
          def apply[T, U](m: Fiber[T], f: T => Fiber[U] < IOs)(using flat: Flat[T]) =
            m.transform(f)
        }
      IOs(deepHandle[Fiber, FiberGets, T, IOs](FiberGets)(IOs.runLazy(v)))
    }

    def runAndBlock[T, S](v: T < (Fibers & S))(implicit
        f: Flat[T < (Fibers & S)]
    ): T < (IOs & S) = {
      given Handler[Fiber, FiberGets, IOs] =
        new Handler[Fiber, FiberGets, IOs] {
          def pure[T](v: T) = Done(v)
          override def handle[T](ex: Throwable): T < FiberGets =
            FiberGets(Done(IOs.fail(ex)))
          def apply[T, U, S](m: Fiber[T], f: T => U < (FiberGets & S))(using flat: Flat[U]) =
            try {
              m match {
                case m: Promise[T] @unchecked =>
                  m.block.map(f)
                case Done(v) =>
                  v.map(f)
              }
            } catch {
              case ex if (NonFatal(ex)) =>
                handle(ex)
            }
        }
      IOs[T, S](handle[T, IOs & S, IOs](v).map(_.block))
    }
  }
  val FiberGets = new FiberGets
}
