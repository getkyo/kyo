package kyo

import fibersInternal.*
import java.util.concurrent.atomic.AtomicInteger
import kyo.core.*
import kyo.core.internal.*
import kyo.scheduler.IOPromise
import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.*
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

sealed abstract class Fiber[+T]:
    def isDone: Boolean < IOs
    def get: T < Fibers
    def getTry: Try[T] < Fibers
    def onComplete(f: T < IOs => Unit < IOs): Unit < IOs
    def block(timeout: Duration): T < IOs
    def interrupt: Boolean < IOs
    def toFuture: Future[T] < IOs
    def transform[U: Flat](t: T => Fiber[U] < IOs): Fiber[U] < IOs
end Fiber

case class Promise[T: Flat](private[kyo] val p: IOPromise[T]) extends Fiber[T]:

    def isDone = IOs(p.isDone())

    def get = FiberGets(this)

    def getTry =
        IOs {
            val r = new IOPromise[Try[T]]
            r.interrupts(p)
            p.onComplete { t =>
                discard(r.complete(IOs.attempt(t)))
            }
            Promise(r).get
        }

    def onComplete(f: T < IOs => Unit < IOs) =
        IOs(p.onComplete(r => IOs.run(f(r))))

    def block(timeout: Duration) =
        p.block(timeout)

    def interrupt =
        IOs(p.interrupt())

    def toFuture =
        IOs {
            val r = scala.concurrent.Promise[T]()
            p.onComplete { v =>
                r.complete(Try(IOs.run(v)))
            }
            r.future
        }

    def transform[U: Flat](t: T => Fiber[U] < IOs) =
        IOs {
            val r = new IOPromise[U]()
            r.interrupts(p)
            p.onComplete { v =>
                try
                    IOs.run(t(IOs.run(v))) match
                        case Promise(v: IOPromise[U]) =>
                            discard(r.become(v))
                        case Done(v) =>
                            discard(r.complete(v))
                catch
                    case ex if (NonFatal(ex)) =>
                        discard(r.complete(IOs.fail(ex)))
            }
            Promise(r)
        }

    def complete(v: T < IOs): Boolean < IOs = IOs(p.complete(v))

    private[kyo] def unsafeComplete(v: T < IOs): Boolean =
        p.complete(v)
end Promise

type Fibers >: Fibers.Effects <: Fibers.Effects

object Fibers extends Joins[Fibers]:

    type Effects = FiberGets & IOs

    case object Interrupted
        extends RuntimeException
        with NoStackTrace

    private[kyo] val interrupted = IOs.fail(Interrupted)

    def run[T](v: T < Fibers)(using f: Flat[T < Fibers]): Fiber[T] < IOs =
        FiberGets.run(v)

    def runAndBlock[T, S](timeout: Duration)(v: T < (Fibers & S))(implicit
        f: Flat[T < (Fibers & S)]
    ): T < (IOs & S) =
        FiberGets.runAndBlock(timeout)(v)

    def value[T: Flat](v: T): Fiber[T] =
        Done(v)

    def fail[T: Flat](ex: Throwable): Fiber[T] =
        Done(IOs.fail(ex))

    def get[T, S](v: Fiber[T] < S): T < (Fibers & S) =
        v.map(_.get)

    private val _promise = IOs(unsafeInitPromise[Object])

    def initPromise[T]: Promise[T] < IOs =
        _promise.asInstanceOf[Promise[T] < IOs]

    private[kyo] def unsafeInitPromise[T: Flat]: Promise[T] =
        Promise(new IOPromise[T]())

    // compiler bug workaround
    private val IOTask = kyo.scheduler.IOTask

    def init[T](v: => T < Fibers)(using f: Flat[T < Fibers]): Fiber[T] < IOs =
        Locals.save.map(st => Promise(IOTask(IOs(v), st)))

    def parallel[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): Seq[T] < Fibers =
        l.size match
            case 0 => Seq.empty
            case 1 => l(0).map(Seq(_))
            case _ =>
                Fibers.get(parallelFiber[T](l))

    def parallelFiber[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): Fiber[Seq[T]] < IOs =
        l.size match
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
                                try
                                    results(j) = IOs.run(r)
                                    if pending.decrementAndGet() == 0 then
                                        discard(p.complete(ArraySeq.unsafeWrapArray(results)))
                                catch
                                    case ex if (NonFatal(ex)) =>
                                        discard(p.complete(IOs.fail(ex)))
                            }
                            i += 1
                        }
                        Promise(p)
                    }
                }

    def race[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): T < Fibers =
        l.size match
            case 0 => IOs.fail("Can't race an empty list.")
            case 1 => l(0)
            case _ =>
                Fibers.get(raceFiber[T](l))

    def raceFiber[T](l: Seq[T < Fibers])(using f: Flat[T < Fibers]): Fiber[T] < IOs =
        l.size match
            case 0 => IOs.fail("Can't race an empty list.")
            case 1 => Fibers.run(l(0))
            case _ =>
                Locals.save.map { st =>
                    IOs {
                        val p = new IOPromise[T]
                        foreach(l) { io =>
                            val f = IOTask(IOs(io), st)
                            p.interrupts(f)
                            f.onComplete(v => discard(p.complete(v)))
                        }
                        Promise(p)
                    }
                }

    def never: Fiber[Unit] < IOs =
        IOs(Promise(new IOPromise[Unit]))

    def delay[T, S](d: Duration)(v: => T < S): T < (S & Fibers) =
        sleep(d).andThen(v)

    def sleep(d: Duration): Unit < Fibers =
        initPromise[Unit].map { p =>
            if d.isFinite then
                val run: Unit < IOs =
                    IOs {
                        IOTask(IOs(p.complete(())), Locals.State.empty)
                        ()
                    }
                Timers.schedule(d)(run).map { t =>
                    IOs.ensure(t.cancel.unit)(p.get)
                }
            else
                p.get
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

    def fromFuture[T: Flat, S](f: Future[T]): T < Fibers =
        Fibers.get(fromFutureFiber(f))

    def fromFutureFiber[T: Flat](f: Future[T]): Fiber[T] < IOs =
        Locals.save.map { st =>
            IOs {
                val p = new IOPromise[T]()
                f.onComplete { r =>
                    val io =
                        IOs {
                            r match
                                case Success(v) =>
                                    p.complete(v)
                                case Failure(ex) =>
                                    p.complete(IOs.fail(ex))
                        }
                    IOTask(io, st)
                }(ExecutionContext.parasitic)
                Promise(p)
            }
        }

    private def foreach[T, U](l: Seq[T])(f: T => Unit): Unit =
        val it = l.iterator
        while it.hasNext do
            f(it.next())
    end foreach
end Fibers

object fibersInternal:

    case class Done[T: Flat](result: T < IOs) extends Fiber[T]:
        def isDone                               = true
        def get                                  = result
        def getTry                               = IOs.attempt(result)
        def onComplete(f: T < IOs => Unit < IOs) = f(result)
        def block(timeout: Duration)             = result
        def interrupt                            = false

        def toFuture = Future.fromTry(Try(IOs.run(result)))

        def transform[U: Flat](t: T => Fiber[U] < IOs) =
            result.map(t)
    end Done

    final class FiberGets private[kyo] () extends Effect[Fiber, FiberGets]:

        def apply[T, S](f: Fiber[T] < S): T < (FiberGets & S) =
            this.suspend(f)

        def run[T](v: T < Fibers)(using f: Flat[T < Fibers]): Fiber[T] < IOs =
            given DeepHandler[Fiber, FiberGets, IOs] =
                new DeepHandler[Fiber, FiberGets, IOs]:
                    def pure[T: Flat](v: T) = Done(v)
                    def apply[T, U: Flat](m: Fiber[T], f: T => Fiber[U] < IOs) =
                        m.transform(f)
            IOs(deepHandle[Fiber, FiberGets, T, IOs](FiberGets)(IOs.runLazy(v)))
        end run

        def runAndBlock[T, S](timeout: Duration)(v: T < (Fibers & S))(implicit
            f: Flat[T < (Fibers & S)]
        ): T < (IOs & S) =
            given Handler[Fiber, FiberGets, IOs] =
                new Handler[Fiber, FiberGets, IOs]:
                    def pure[T: Flat](v: T) = Done(v)
                    override def handle[T: Flat](ex: Throwable): T < FiberGets =
                        FiberGets(Done(IOs.fail(ex)))
                    def apply[T, U: Flat, S](m: Fiber[T], f: T => U < (FiberGets & S)) =
                        try
                            m match
                                case m: Promise[T] @unchecked =>
                                    m.block(timeout).map(f)
                                case Done(v) =>
                                    v.map(f)
                        catch
                            case ex if (NonFatal(ex)) =>
                                handle(ex)
            IOs(this.handle[T, IOs & S, IOs](v).map(_.block(timeout)))
        end runAndBlock
    end FiberGets
    val FiberGets = new FiberGets
end fibersInternal
