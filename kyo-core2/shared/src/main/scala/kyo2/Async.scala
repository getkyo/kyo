package kyo2

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kyo.Tag
import kyo2.Maybe.Empty
import kyo2.kernel.*
import kyo2.kernel.ContextEffect.suspendMap
import kyo2.scheduler.*
import scala.annotation.implicitNotFound
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.collection.immutable.ArraySeq
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.NotGiven
import scala.util.control.NonFatal

sealed trait Async extends Effect[IOPromise[?, *], Result[Nothing, *]]

export Async.Fiber
export Async.Promise

object Async:

    def run[E, A, Ctx](v: => A < (Abort[E] & Async & IO & Ctx))(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[E, A] < (IO & Ctx) =
        val io = IO(Abort.run[Any](v)).asInstanceOf[Result[E, A] < (Async & IO & Ctx)]
        boundary(io)(IOTask(_))
    end run

    def runAndBlock[E, A, Ctx](timeout: Duration)(v: => A < (Abort[E] & Async & IO & Ctx))(
        using
        boundary: Boundary[Ctx, IO],
        frame: Frame
    ): A < (Abort[E | Timeout] & IO & Ctx) =
        run(v).map { fiber =>
            IO(Abort.get(fiber.block(deadline(timeout))))
        }
    end runAndBlock

    opaque type Promise[E, A] <: Fiber[E, A] = IOPromise[E, A]

    object Promise:
        def init[E, A](using Frame): Promise[E, A] < IO = IO(IOPromise())

        extension [E, A](self: Promise[E, A])
            def complete[E2 <: E, A2 <: A](v: Result[E, A])(using Frame): Boolean < IO    = IO(self.complete(v))
            def become[E2 <: E, A2 <: A](other: Fiber[E2, A2])(using Frame): Boolean < IO = IO(self.become(other))
        end extension
    end Promise

    opaque type Fiber[E, A] = IOPromise[E, A]

    object Fiber:

        private val _unit = success(())

        def unit[E]: Fiber[E, Unit] = _unit.asInstanceOf[Fiber[E, Unit]]

        def never: Fiber[Nothing, Unit] = IOPromise[Nothing, Unit]()

        def success[E, A](v: A): Fiber[E, A]        = result(Result.success(v))
        def fail[E, A](ex: E): Fiber[E, A]          = result(Result.fail(ex))
        def panic[E, A](ex: Throwable): Fiber[E, A] = result(Result.panic(ex))

        private def result[E, A](result: Result[E, A]): Fiber[E, A] = IOPromise(result)

        private[kyo2] inline def unsafe[E, A](p: IOPromise[E, A]): Fiber[E, A] = p

        extension [E, A](self: Fiber[E, A])

            def get(using reduce: Reducible[Abort[E]], frame: Frame): A < (reduce.SReduced & Async) =
                Async.get(self)

            def use[B, S](f: A => B < S)(using reduce: Reducible[Abort[E]], frame: Frame): B < (reduce.SReduced & Async & S) =
                Async.use(self)(f)

            def getResult(using Frame): Result[E, A] < Async                            = Async.getResult(self)
            def useResult[B, S](f: Result[E, A] => B < S)(using Frame): B < (Async & S) = Async.useResult(self)(f)
            def isDone(using Frame): Boolean < IO                                       = IO(self.isDone())
            def onComplete(f: Result[E, A] => Unit < IO)(using Frame): Unit < IO        = IO(self.onComplete(r => IO.run(f(r)).eval))
            def block(timeout: Duration)(using Frame): Result[E | Timeout, A] < IO      = IO(self.block(deadline(timeout)))

            def toFuture(using E <:< Throwable, Frame): Future[A] < IO =
                IO {
                    val r = scala.concurrent.Promise[A]()
                    self.onComplete { v =>
                        r.complete(v.toTry)
                    }
                    r.future
                }

            def map[B](f: A => B): Fiber[E, B] < IO =
                IO {
                    val p = IOPromise[E, B](interrupts = self)
                    self.onComplete(v => p.completeUnit(v.map(f)))
                    p
                }

            def flatMap[E2, B](f: A => Fiber[E2, B]): Fiber[E | E2, B] < IO =
                IO {
                    val p = IOPromise[E | E2, B](interrupts = self)
                    self.onComplete(_.fold(p.completeUnit)(v => p.becomeUnit(f(v))))
                    p
                }

            def interrupt(error: Result.Error[E])(using Frame): Boolean < IO =
                IO(self.interrupt(error))

            def interruptUnit(error: Result.Error[E])(using Frame): Unit < IO =
                IO(discard(self.interrupt(error)))

            def interruptAwait(error: Result.Error[E])(using Frame): Boolean < (IO & Async) =
                IO {
                    if !self.interrupt(error) then false
                    else self.useResult(_ => true)
                }
        end extension
    end Fiber

    def sleep(d: Duration)(using Frame): Unit < (Async & IO) =
        IO {
            val p = IOPromise[Nothing, Unit]()
            if d.isFinite then
                Timers.schedule(d)(p.completeUnit(Result.success(()))).map { t =>
                    IO.ensure(t.cancel.unit)(get(p))
                }
            else
                get(p)
            end if
        }

    def timeout[E, A, Ctx](d: Duration)(v: => A < (Abort[E | Timeout] & Async & IO & Ctx))(
        using
        boundary: Boundary[Ctx, IO],
        frame: Frame
    ): A < (Abort[E | Timeout] & Async & IO & Ctx) =
        Async.run(v).map { f =>
            Timers.schedule(d)(f.interruptUnit(Result.Fail(Timeout(frame)))).map { t =>
                IO.ensure(t.cancel.unit)(f.get)
            }
        }

    def race[E, A, Ctx](first: A < (Abort[E] & Async & IO & Ctx), rest: (A < (Abort[E] & Async & IO & Ctx))*)(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): A < (reduce.SReduced & Async & IO & Ctx) =
        if rest.isEmpty then reduce(first)
        else raceFiber(first, rest*).map(get)

    def raceFiber[E, A, Ctx](first: A < (Abort[E] & Async & IO & Ctx), rest: (A < (Abort[E] & Async & IO & Ctx))*)(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[E, A] < (IO & Ctx) =
        IO {
            val seq = first +: rest
            class State extends IOPromise[E, A] with Function1[Result[E, A], Unit]:
                val pending = new AtomicInteger(seq.size)
                def apply(result: Result[E, A]): Unit =
                    val last = pending.decrementAndGet() == 0
                    result.fold(e => if last then completeUnit(e))(v => completeUnit(Result.success(v)))
                end apply
            end State
            val state = new State
            import state.*
            foreach(seq)((idx, io) => io.pure.foreach(v => state(Result.success(v))))
            if state.isDone() then
                state
            else
                boundary(seq) { seq =>
                    IO {
                        foreach(seq) { (_, v) =>
                            val io    = IO(Abort.run[Any](v)).asInstanceOf[Result[E, A] < (Async & IO)]
                            val fiber = IOTask(io)
                            state.interrupts(fiber)
                            fiber.onComplete(state)
                        }
                        state
                    }
                }
            end if
        }
    end raceFiber

    def parallel[E, A, Ctx](seq: Seq[A < (Abort[E] & Async & IO & Ctx)])(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Seq[A] < (reduce.SReduced & Async & IO & Ctx) =
        seq.size match
            case 0 => Seq.empty
            case 1 => reduce(seq(0).map(Seq(_)))
            case _ => parallelFiber(seq).map(get)
        end match
    end parallel

    def parallelFiber[E, A, Ctx](seq: Seq[A < (Abort[E] & Async & IO & Ctx)])(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[E, Seq[A]] < (IO & Ctx) =
        seq.size match
            case 0 => Fiber.success(Seq.empty)
            case _ =>
                IO {
                    class State extends IOPromise[E, Seq[A]]:
                        val results = (new Array[Any](seq.size)).asInstanceOf[Array[A]]
                        val pending = new AtomicInteger(seq.size)
                        def done(idx: Int, value: A) =
                            results(idx) = value
                            if pending.decrementAndGet() == 0 then
                                discard(this.complete(Result.success(ArraySeq.unsafeWrapArray(results))))
                        end done
                    end State
                    val state = new State
                    import state.*
                    foreach(seq)((idx, io) => io.pure.foreach(done(idx, _)))
                    if state.isDone() then state
                    else
                        boundary(seq) { seq =>
                            IO {
                                foreach(seq) { (idx, v) =>
                                    if isNull(results(idx)) then
                                        val io    = IO(Abort.run[Any](v)).asInstanceOf[Result[E, A] < (Async & IO)]
                                        val fiber = IOTask(io)
                                        state.interrupts(fiber)
                                        fiber.onComplete(_.fold(state.completeUnit)(done(idx, _)))
                                }
                                state
                            }
                        }
                    end if
                }
    end parallelFiber

    def parallel[E, A1, A2, Ctx](
        v1: A1 < (Abort[E] & Async & IO & Ctx),
        v2: A2 < (Abort[E] & Async & IO & Ctx)
    )(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): (A1, A2) < (reduce.SReduced & Async & IO & Ctx) =
        parallel(Seq(v1, v2)).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2])
        }

    def parallel[E, A1, A2, A3, Ctx](
        v1: A1 < (Abort[E] & Async & IO & Ctx),
        v2: A2 < (Abort[E] & Async & IO & Ctx),
        v3: A3 < (Abort[E] & Async & IO & Ctx)
    )(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): (A1, A2, A3) < (reduce.SReduced & Async & IO & Ctx) =
        parallel(Seq(v1, v2, v3)).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3])
        }

    def parallel[E, A1, A2, A3, A4, Ctx](
        v1: A1 < (Abort[E] & Async & IO & Ctx),
        v2: A2 < (Abort[E] & Async & IO & Ctx),
        v3: A3 < (Abort[E] & Async & IO & Ctx),
        v4: A4 < (Abort[E] & Async & IO & Ctx)
    )(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): (A1, A2, A3, A4) < (reduce.SReduced & Async & IO & Ctx) =
        parallel(Seq(v1, v2, v3, v4)).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3], s(3).asInstanceOf[A4])
        }

    private[kyo2] def get[E, A](v: IOPromise[E, A])(using reduce: Reducible[Abort[E]], frame: Frame): A < (reduce.SReduced & Async) =
        reduce(use(v)(identity))

    private[kyo2] def use[E, A, B, S](v: IOPromise[E, A])(f: A => B < S)(using
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): B < (S & reduce.SReduced & Async) =
        val x = useResult(v)(_.fold(Abort.error)(f))
        reduce(x)
    end use

    private[kyo2] def getResult[E, A](v: IOPromise[E, A])(using Frame): Result[E, A] < Async =
        Effect.suspend[A](Tag[Async], v).asInstanceOf[Result[E, A] < Async]

    private[kyo2] def useResult[E, A, B, S](v: IOPromise[E, A])(f: Result[E, A] => B < S)(using Frame): B < (S & Async) =
        Effect.suspendMap[A](Tag[Async], v)(f)

    private def deadline(timeout: Duration): Long =
        if timeout.isFinite then
            System.currentTimeMillis() + timeout.toMillis
        else
            Long.MaxValue

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

end Async
