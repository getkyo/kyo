package kyo

import fibersInternal.*
import java.util.concurrent.atomic.AtomicInteger
import kyo.core.*
import kyo.core.internal.*
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
    def isDone: Boolean < IOs
    def get: T < Fibers
    def getTry: Try[T] < Fibers
    def onComplete(f: T < IOs => Unit < IOs): Unit < IOs
    def block(timeout: Duration): T < IOs
    def interrupt: Boolean < IOs
    def toFuture: Future[T] < IOs
    def transform[U: Flat](t: T => Fiber[U] < IOs): Fiber[U] < IOs
end Fiber

object Fiber:

    val unit: Fiber[Unit] = value(())

    def value[T: Flat](v: T): Fiber[T] =
        Done(v)

    def fail[T: Flat](ex: Throwable): Fiber[T] =
        Done(IOs.fail(ex))

end Fiber

object Promise:
    def apply[T: Flat](p: IOPromise[T]): Promise[T] =
        new Promise(p)

case class Promise[T] private (private val p: IOPromise[T]) extends Fiber[T]:

    import Flat.unsafe.bypass // avoid capturing

    def isDone = IOs(p.isDone())

    def get = FiberGets(this)

    def getTry =
        IOs {
            val r = new IOPromise[Try[T]]
            r.interrupts(p)
            p.onComplete { t =>
                discard(r.complete(IOs.attempt(t)))
            }
            new Promise(r).get
        }

    def onComplete(f: T < IOs => Unit < IOs) =
        IOs(p.onComplete(r => IOs.run(f(r))))

    def block(timeout: Duration) =
        IOs {
            val deadline =
                if timeout.isFinite then
                    System.currentTimeMillis() + timeout.toMillis
                else
                    Long.MaxValue
            p.block(deadline)
        }

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
            new Promise(r)
        }

    def complete(v: T < IOs): Boolean < IOs =
        if isNull(v) then
            throw new NullPointerException("Can't complete a fiber with `null`.")
        IOs(p.complete(v))
    end complete

    def become(other: Fiber[T]): Boolean < IOs =
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

    def run[T: Flat](v: T < Fibers): Fiber[T] < IOs =
        FiberGets.run(v)

    def runAndBlock[T: Flat, S](timeout: Duration)(v: T < (Fibers & S)): T < (IOs & S) =
        FiberGets.runAndBlock(timeout)(v)

    def get[T, S](v: Fiber[T] < S): T < (Fibers & S) =
        v.map(_.get)

    private val _promise = IOs(unsafeInitPromise[Object])

    def initPromise[T]: Promise[T] < IOs =
        _promise.asInstanceOf[Promise[T] < IOs]

    private[kyo] def unsafeInitPromise[T: Flat]: Promise[T] =
        Promise(new IOPromise[T]())

    def init[T: Flat, S](v: => T < (Fibers & S))(
        using
        @implicitNotFound(
            "Fibers.init only accepts Fibers and IOs-based effects. Found: ${S}"
        ) ev: S => IOs
    ): Fiber[T] < (IOs & S) =
        Locals.save { st =>
            Promise(IOTask(IOs(v.asInstanceOf[T < Fibers]), st))(
                using Flat.unsafe.bypass // avoid capturing
            )
        }

    def parallel[T: Flat](l: Seq[T < Fibers]): Seq[T] < Fibers =
        l.size match
            case 0 => Seq.empty
            case 1 => l(0).map(Seq(_))
            case _ =>
                Fibers.get(parallelFiber[T](l))

    def parallelFiber[T: Flat](l: Seq[T < Fibers]): Fiber[Seq[T]] < IOs =
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
                                    results(i) =
                                        IOs.run(r)(
                                            using Flat.unsafe.bypass // bypass to avoid capturing
                                        )
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

    def race[T: Flat](l: Seq[T < Fibers]): T < Fibers =
        Fibers.get(raceFiber[T](l))

    def raceFiber[T: Flat](l: Seq[T < Fibers]): Fiber[T] < IOs =
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

    def never: Fiber[Unit] < IOs =
        IOs(Promise(new IOPromise[Unit]))

    def delay[T, S](d: Duration)(v: => T < S): T < (S & Fibers) =
        sleep(d).andThen(v)

    def sleep(d: Duration): Unit < Fibers =
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

    def timeout[T: Flat](d: Duration)(v: => T < Fibers): T < Fibers =
        init(v).map { f =>
            val timeout: Unit < IOs =
                IOs(discard(IOTask(IOs(f.interrupt), Locals.State.empty)))
            Timers.schedule(d)(timeout).map { t =>
                IOs.ensure(t.cancel.unit)(f.get)
            }
        }

    def fromFuture[T: Flat, S](f: Future[T]): T < Fibers =
        Fibers.get(fromFutureFiber(f))

    def fromFutureFiber[T: Flat](f: Future[T]): Fiber[T] < IOs =
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

    private inline def foreach[T, U](l: Seq[T])(inline f: (Int, T) => Unit): Unit =
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

    class FiberGets extends Effect[FiberGets]:
        type Command[T] = Fiber[T]

    object FiberGets extends FiberGets:
        type Command[T] = Fiber[T]

        def apply[T, S](f: Fiber[T]): T < (FiberGets & S) =
            suspend(this)(f)

        private val deepHandler =
            new DeepHandler[Fiber, FiberGets, IOs]:
                def done[T: Flat](v: T) = Fiber.value(v)
                def resume[T, U: Flat](m: Fiber[T], f: T => Fiber[U] < IOs) =
                    m.transform(f)

        def run[T: Flat](v: T < Fibers): Fiber[T] < IOs =
            IOs(deepHandle(deepHandler, IOs.runLazy(v)))

        def runAndBlock[T: Flat, S](timeout: Duration)(
            v: T < (IOs & FiberGets & S)
        ): T < (IOs & S) =
            IOs {
                val deadline =
                    if timeout.isFinite then
                        System.currentTimeMillis() + timeout.toMillis
                    else
                        Long.MaxValue
                val handler =
                    new Handler[Fiber, FiberGets, IOs]:
                        def resume[T, U: Flat, S](m: Fiber[T], f: T => U < (FiberGets & S))(using Tag[FiberGets]) =
                            m match
                                case Promise(p) =>
                                    Resume((), p.block(deadline).map(f))
                                case Done(v) =>
                                    Resume((), v.map(f))
                FiberGets.handle(handler)((), v)
            }

    end FiberGets
end fibersInternal
