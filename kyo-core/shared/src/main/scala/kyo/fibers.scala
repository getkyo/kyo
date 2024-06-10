package kyo

import Fibers.internal.*
import java.util.concurrent.atomic.AtomicInteger
import kyo.core.*
import kyo.core.internal.*
import kyo.internal.Trace
import kyo.scheduler.IOPromise
import kyo.scheduler.IOTask
import scala.annotation.implicitNotFound
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.*
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

sealed abstract class Fiber[+T]:
    def isDone(using Trace): Boolean < IOs
    def get(using Trace): T < Fibers
    def getTry(using Trace): Try[T] < Fibers
    def onComplete(f: T < IOs => Unit < IOs)(using Trace): Unit < IOs
    def block(timeout: Duration)(using Trace): T < IOs
    def interrupt(using Trace): Boolean < IOs
    def toFuture(using Trace): Future[T] < IOs
    def transform[U](t: T => Fiber[U] < IOs)(using Trace): Fiber[U] < IOs
end Fiber

object Fiber:

    val unit: Fiber[Unit] = value(())

    def value[T](v: T): Fiber[T] =
        Done(v)

    def fail[T](ex: Throwable): Fiber[T] =
        Done(IOs.fail(ex))

end Fiber

object Promise:
    def apply[T](p: IOPromise[T]): Promise[T] =
        new Promise(p)

case class Promise[T] private (private val p: IOPromise[T]) extends Fiber[T]:

    def isDone(using Trace) = IOs(p.isDone())

    def get(using Trace): T < Fibers =
        FiberGets.get(this)

    def getTry(using Trace) =
        IOs {
            val r = new IOPromise[Try[T]]
            r.interrupts(p)
            p.onComplete { t =>
                discard(r.complete(IOs.toTry(t)))
            }
            new Promise(r).get
        }

    def onComplete(f: T < IOs => Unit < IOs)(using Trace) =
        IOs(p.onComplete(r => IOs.run(f(r))))

    def block(timeout: Duration)(using Trace) =
        IOs {
            val deadline =
                if timeout.isFinite then
                    System.currentTimeMillis() + timeout.toMillis
                else
                    Long.MaxValue
            p.block(deadline)
        }

    def interrupt(using Trace) =
        IOs(p.interrupt())

    def toFuture(using Trace) =
        IOs {
            val r = scala.concurrent.Promise[T]()
            p.onComplete { v =>
                val x = Try(IOs.run(v))
                println(("toFuture", v, x))
                r.complete(x)
            }
            r.future
        }

    def transform[U](t: T => Fiber[U] < IOs)(using Trace) =
        IOs {
            val r = new IOPromise[U]()
            r.interrupts(p)
            p.onComplete { v =>
                try
                    if isNull(v) || isNull(IOs.run(v)) then
                        ???
                    IOs.run(t(IOs.run(v))) match
                        case Promise(v: IOPromise[U]) =>
                            discard(r.become(v))
                        case Done(v) =>
                            discard(r.complete(v))
                    end match
                catch
                    case ex if (NonFatal(ex)) =>
                        discard(r.complete(IOs.fail(ex)))
            }
            new Promise(r)
        }

    def complete(v: T < IOs)(using Trace): Boolean < IOs =
        IOs(p.complete(v))

    def become(other: Fiber[T])(using Trace): Boolean < IOs =
        other match
            case Done(v) =>
                complete(v)
            case Promise(p2: IOPromise[T]) =>
                IOs(p.become(p2))

    private[kyo] def unsafeComplete(v: T < IOs): Boolean =
        p.complete(v)
end Promise

type Fibers >: Fibers.Effects <: Fibers.Effects

object Fibers extends Joins[Fibers] with fibersPlatformSpecific:

    type Effects = FiberGets & IOs

    case object Interrupted
        extends RuntimeException("Fiber Interrupted")
        with NoStackTrace:
        override def getCause() = null
    end Interrupted

    private[kyo] val interrupted = IOs.fail(Interrupted)

    def run[T](v: T < Fibers)(using Trace): Fiber[T] < IOs =
        FiberGets.run(v)

    def runAndBlock[T, S](timeout: Duration)(v: T < (Fibers & S))(using Trace): T < (IOs & S) =
        FiberGets.runAndBlock(timeout)(v)

    def get[T, S](v: Fiber[T] < S)(using Trace): T < (Fibers & S) =
        v.map(_.get)

    private val _promise = IOs(unsafeInitPromise[Object])

    def initPromise[T]: Promise[T] < IOs =
        _promise.asInstanceOf[Promise[T] < IOs]

    private[kyo] def unsafeInitPromise[T]: Promise[T] =
        Promise(new IOPromise[T]())

    def init[T, S](v: => T < (Fibers & S))(
        using
        @implicitNotFound(
            "Fibers.init only accepts Fibers and IOs-based effects. Found: ${S}"
        ) ev: S => IOs,
        trace: Trace
    ): Fiber[T] < (IOs & S) =
        Locals.save { st =>
            Promise(IOTask(IOs(v.asInstanceOf[T < Fibers]), st))
        }

    def parallel[T](l: Seq[T < Fibers])(using Trace): Seq[T] < Fibers =
        l.size match
            case 0 => Seq.empty
            case 1 => l(0).map(Seq(_))
            case _ =>
                Fibers.get(parallelFiber[T](l))

    def parallelFiber[T](l: Seq[T < Fibers])(using Trace): Fiber[Seq[T]] < IOs =
        l.size match
            case 0 => Fiber.value(Seq.empty)
            case 1 => Fibers.run(l(0).map(Seq(_)))
            case _ =>
                Locals.save { st =>
                    IOs {
                        class State extends IOPromise[Seq[T]]:
                            val results = (new Array[Any](l.size)).asInstanceOf[Array[T]]
                            val pending = new AtomicInteger(l.size)
                        end State
                        val state = new State
                        import state.*
                        foreach(l) { (i, io) =>
                            val fiber = IOTask(IOs(io), st)
                            state.interrupts(fiber)
                            fiber.onComplete { r =>
                                try
                                    results(i) = IOs.run(r)
                                    if pending.decrementAndGet() == 0 then
                                        discard(state.complete(ArraySeq.unsafeWrapArray(results)))
                                catch
                                    case ex if (NonFatal(ex)) =>
                                        discard(state.complete(IOs.fail(ex)))
                            }
                        }
                        Promise(state)
                    }
                }

    def race[T](l: Seq[T < Fibers])(using Trace): T < Fibers =
        Fibers.get(raceFiber[T](l))

    def raceFiber[T](l: Seq[T < Fibers])(using Trace): Fiber[T] < IOs =
        l.size match
            case 0 => IOs.fail("Can't race an empty list.")
            case 1 => Fibers.run(l(0))
            case size =>
                Locals.save { st =>
                    IOs {
                        class State extends IOPromise[T] with Function1[T < IOs, Unit]:
                            val pending = new AtomicInteger(size)
                            def apply(v: T < IOs): Unit =
                                val last = pending.decrementAndGet() == 0
                                try discard(complete(IOs.run(v)))
                                catch
                                    case ex if (NonFatal(ex)) =>
                                        if last then discard(complete(IOs.fail(ex)))
                                end try
                            end apply
                        end State
                        val state = new State
                        import state.*
                        foreach(l) { (i, io) =>
                            val f = IOTask(IOs(io), st)
                            state.interrupts(f)
                            f.onComplete(state)
                        }
                        Promise(state)
                    }
                }

    def never(using Trace): Fiber[Unit] < IOs =
        IOs(Promise(new IOPromise[Unit]))

    def delay[T, S](d: Duration)(v: => T < S)(using Trace): T < (S & Fibers) =
        sleep(d).andThen(v)

    def sleep(d: Duration)(using Trace): Unit < Fibers =
        initPromise[Unit].map { p =>
            if d.isFinite then
                val run: Unit < IOs =
                    IOs(discard(IOTask(IOs(p.complete(())), Locals.State.empty)))
                Timers.schedule(d)(run).map { t =>
                    IOs.ensure(t.cancel.unit)(p.get)
                }
            else
                p.get
        }

    def timeout[T](d: Duration)(v: => T < Fibers)(using Trace): T < Fibers =
        init(v).map { f =>
            val timeout: Unit < IOs =
                IOs(discard(IOTask(IOs(f.interrupt), Locals.State.empty)))
            Timers.schedule(d)(timeout).map { t =>
                IOs.ensure(t.cancel.unit)(f.get)
            }
        }

    def fromFuture[T, S](f: Future[T])(using Trace): T < Fibers =
        Fibers.get(fromFutureFiber(f))

    def fromFutureFiber[T](f: Future[T])(using Trace): Fiber[T] < IOs =
        Locals.save { st =>
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

    private inline def foreach[T, U](l: Seq[T])(inline f: (Int, T) => Unit)(using Trace): Unit =
        l match
            case l: IndexedSeq[?] =>
                val s = l.size
                @tailrec def loop(i: Int): Unit =
                    if i < s then
                        f(i, l(i))
                        loop(i + 1)
                loop(0)
            case _ =>
                val it = l.iterator
                @tailrec def loop(i: Int): Unit =
                    if it.hasNext then
                        f(i, it.next())
                        loop(i + 1)
                loop(0)

    object internal:

        case class Done[T](result: T < IOs) extends Fiber[T]:
            def isDone(using Trace)                               = true
            def get(using Trace)                                  = result
            def getTry(using Trace)                               = IOs.toTry(result)
            def onComplete(f: T < IOs => Unit < IOs)(using Trace) = f(result)
            def block(timeout: Duration)(using Trace)             = result
            def interrupt(using Trace)                            = false

            def toFuture(using Trace) = Future.fromTry(Try(IOs.run(result)))

            def transform[U](t: T => Fiber[U] < IOs)(using Trace) =
                result.map(t)
        end Done

        sealed trait FiberGets extends Effect[Fiber, Id]

        object FiberGets:

            def get[T](f: Fiber[T])(
                using
                tag: Tag[FiberGets],
                trace: Trace
            ): T < FiberGets =
                suspend[T](tag, f)

            def run[T](v: T < Fibers)(using Trace): Fiber[T] < IOs =
                def loop(v: Fiber[T] < Fibers): Fiber[T] < IOs =
                    handle[Fiber, Id, FiberGets, Fiber[T], IOs, IOs](Tag[FiberGets], v) {
                        [C] =>
                            (input, cont) =>
                                input.transform(v => loop(cont(v)))
                    }
                loop(v.map(Done(_)))
            end run

            def runAndBlock[T, S](timeout: Duration)(
                v: T < (IOs & FiberGets & S)
            )(using Trace): T < (IOs & S) =
                IOs {
                    val deadline =
                        if timeout.isFinite then
                            System.currentTimeMillis() + timeout.toMillis
                        else
                            Long.MaxValue
                    handle(Tag[FiberGets], v) {
                        [C] =>
                            (input, cont) =>
                                input match
                                    case Promise(p) =>
                                        p.block(deadline).map(cont(_))
                                    case Done(v) =>
                                        v.map(cont(_))
                    }
                }

        end FiberGets
    end internal

end Fibers
