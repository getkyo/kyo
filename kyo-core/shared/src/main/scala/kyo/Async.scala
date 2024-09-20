package kyo

export Async.Fiber
export Async.Promise
import java.util.concurrent.atomic.AtomicInteger
import kyo.Maybe.Empty
import kyo.Result.Panic
import kyo.Tag
import kyo.internal.FiberPlatformSpecific
import kyo.kernel.*
import kyo.scheduler.*
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.NotGiven
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

/** Represents an asynchronous computation effect.
  *
  * This effect provides methods for running asynchronous computations, creating and managing fibers, handling promises, and performing
  * parallel and race operations.
  *
  * @see
  *   [[Async.run]] for running asynchronous computations
  * @see
  *   [[Async.Fiber]] for managing asynchronous tasks
  * @see
  *   [[Async.Promise]] for creating and managing promises
  * @see
  *   [[Async.parallel]] for running computations in parallel
  * @see
  *   [[Async.race]] for racing multiple computations
  */
opaque type Async <: (IO & Async.Join) = Async.Join & IO

object Async:

    sealed trait Join extends ArrowEffect[IOPromise[?, *], Result[Nothing, *]]

    /** Runs an asynchronous computation and returns a Fiber.
      *
      * @param v
      *   The computation to run
      * @return
      *   A Fiber representing the running computation
      */
    inline def run[E, A: Flat, Ctx](inline v: => A < (Abort[E] & Async & Ctx))(
        using
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[E, A] < (IO & Ctx) =
        boundary((trace, context) => IOTask(v, trace, context))

    /** Runs an asynchronous computation and blocks until completion or timeout.
      *
      * @param timeout
      *   The maximum duration to wait
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation, or a Timeout error
      */
    def runAndBlock[E, A: Flat, Ctx](timeout: Duration)(v: => A < (Abort[E] & Async & Ctx))(
        using
        boundary: Boundary[Ctx, IO & Abort[E]],
        frame: Frame
    ): A < (Abort[E | Timeout] & IO & Ctx) =
        run(v).map { fiber =>
            IO(Abort.get(fiber.block(deadline(timeout))))
        }
    end runAndBlock

    opaque type Promise[E, A] <: Fiber[E, A] = IOPromise[E, A]

    object Promise:
        inline given [E, A]: Flat[Promise[E, A]] = Flat.unsafe.bypass

        /** Initializes a new Promise.
          *
          * @return
          *   A new Promise
          */
        def init[E, A](using Frame): Promise[E, A] < IO = IO(IOPromise())

        extension [E, A](self: Promise[E, A])
            /** Completes the Promise with a result.
              *
              * @param v
              *   The result to complete the Promise with
              * @return
              *   Whether the Promise was successfully completed
              */
            def complete[E2 <: E, A2 <: A](v: Result[E, A])(using Frame): Boolean < IO = IO(self.complete(v))

            /** Completes the Promise with a result, discarding the return value.
              *
              * @param v
              *   The result to complete the Promise with
              */
            def completeUnit[E2 <: E, A2 <: A](v: Result[E, A])(using Frame): Unit < IO = IO(discard(self.complete(v)))

            /** Makes this Promise become another Fiber.
              *
              * @param other
              *   The Fiber to become
              * @return
              *   Whether the Promise successfully became the other Fiber
              */
            def become[E2 <: E, A2 <: A](other: Fiber[E2, A2])(using Frame): Boolean < IO = IO(self.become(other))

            /** Makes this Promise become another Fiber, discarding the return value.
              *
              * @param other
              *   The Fiber to become
              */
            def becomeUnit[E2 <: E, A2 <: A](other: Fiber[E2, A2])(using Frame): Unit < IO = IO(discard(self.become(other)))
        end extension
    end Promise

    opaque type Fiber[E, A] = IOPromise[E, A]

    object Fiber extends FiberPlatformSpecific:

        inline given [E, A]: Flat[Fiber[E, A]] = Flat.unsafe.bypass

        private val _unit = succeed(())

        /** Creates a unit Fiber.
          *
          * @return
          *   A Fiber that completes with unit
          */
        def unit[E]: Fiber[E, Unit] = _unit.asInstanceOf[Fiber[E, Unit]]

        /** Creates a never-completing Fiber.
          *
          * @return
          *   A Fiber that never completes
          */
        def never: Fiber[Nothing, Unit] = IOPromise[Nothing, Unit]()

        /** Creates a successful Fiber.
          *
          * @param v
          *   The value to complete the Fiber with
          * @return
          *   A Fiber that completes successfully with the given value
          */
        def succeed[E, A](v: A): Fiber[E, A] = result(Result.succeed(v))

        /** Creates a failed Fiber.
          *
          * @param ex
          *   The error to fail the Fiber with
          * @return
          *   A Fiber that fails with the given error
          */
        def error[E, A](ex: E): Fiber[E, A] = result(Result.error(ex))

        /** Creates a panicked Fiber.
          *
          * @param ex
          *   The throwable to panic the Fiber with
          * @return
          *   A Fiber that panics with the given throwable
          */
        def panic[E, A](ex: Throwable): Fiber[E, A] = result(Result.panic(ex))

        /** Creates a Fiber from a Future.
          *
          * @param f
          *   The Future to create a Fiber from
          * @return
          *   A Fiber that completes with the result of the Future
          */
        def fromFuture[A: Flat](f: Future[A])(using Frame): Fiber[Nothing, A] < IO =
            import scala.util.*
            IO {
                val p = new IOPromise[Nothing, A] with (Try[A] => Unit):
                    def apply(result: Try[A]) =
                        result match
                            case Success(v) =>
                                completeUnit(Result.succeed(v))
                            case Failure(ex) =>
                                completeUnit(Result.panic(ex))

                f.onComplete(p)(ExecutionContext.parasitic)
                p
            }
        end fromFuture

        private def result[E, A](result: Result[E, A]): Fiber[E, A] = IOPromise(result)

        private[kyo] inline def initUnsafe[E, A](p: IOPromise[E, A]): Fiber[E, A] = p

        extension [E, A](self: Fiber[E, A])

            /** Gets the result of the Fiber.
              *
              * @return
              *   The result of the Fiber
              */
            def get(using reduce: Reducible[Abort[E]], frame: Frame): A < (reduce.SReduced & Async) =
                Async.get(self)

            /** Uses the result of the Fiber to compute a new value.
              *
              * @param f
              *   The function to apply to the Fiber's result
              * @return
              *   The result of applying the function to the Fiber's result
              */
            def use[B, S](f: A => B < S)(using reduce: Reducible[Abort[E]], frame: Frame): B < (reduce.SReduced & Async & S) =
                Async.use(self)(f)

            /** Gets the result of the Fiber as a Result.
              *
              * @return
              *   The Result of the Fiber
              */
            def getResult(using Frame): Result[E, A] < Async = Async.getResult(self)

            /** Uses the Result of the Fiber to compute a new value.
              *
              * @param f
              *   The function to apply to the Fiber's Result
              * @return
              *   The result of applying the function to the Fiber's Result
              */
            def useResult[B, S](f: Result[E, A] => B < S)(using Frame): B < (Async & S) = Async.useResult(self)(f)

            /** Checks if the Fiber is done.
              *
              * @return
              *   Whether the Fiber is done
              */
            def done(using Frame): Boolean < IO = IO(self.done())

            /** Registers a callback to be called when the Fiber completes.
              *
              * @param f
              *   The callback function
              */
            def onComplete(f: Result[E, A] => Unit < IO)(using Frame): Unit < IO = IO(self.onComplete(r => IO.run(f(r)).eval))

            /** Blocks until the Fiber completes or the timeout is reached.
              *
              * @param timeout
              *   The maximum duration to wait
              * @return
              *   The Result of the Fiber, or a Timeout error
              */
            def block(timeout: Duration)(using Frame): Result[E | Timeout, A] < IO = IO(self.block(deadline(timeout)))

            /** Converts the Fiber to a Future.
              *
              * @return
              *   A Future that completes with the result of the Fiber
              */
            def toFuture(using E <:< Throwable, Frame): Future[A] < IO =
                IO {
                    val r = scala.concurrent.Promise[A]()
                    self.onComplete { v =>
                        r.complete(v.toTry)
                    }
                    r.future
                }

            /** Maps the result of the Fiber.
              *
              * @param f
              *   The function to apply to the Fiber's result
              * @return
              *   A new Fiber with the mapped result
              */
            def map[B](f: A => B)(using Frame): Fiber[E, B] < IO =
                IO {
                    val p = new IOPromise[E, B](interrupts = self) with (Result[E, A] => Unit):
                        def apply(v: Result[E, A]) = completeUnit(v.map(f))
                    self.onComplete(p)
                    p
                }

            /** Flat maps the result of the Fiber.
              *
              * @param f
              *   The function to apply to the Fiber's result
              * @return
              *   A new Fiber with the flat mapped result
              */
            def flatMap[E2, B](f: A => Fiber[E2, B])(using Frame): Fiber[E | E2, B] < IO =
                IO {
                    val p = new IOPromise[E | E2, B](interrupts = self) with (Result[E, A] => Unit < IO):
                        def apply(r: Result[E, A]) = r.fold(completeUnit)(v => becomeUnit(f(v)))
                    self.onComplete(p)
                    p
                }

            /** Interrupts the Fiber.
              *
              * @return
              *   Whether the Fiber was successfully interrupted
              */
            def interrupt(using frame: Frame): Boolean < IO =
                interrupt(Result.Panic(Interrupted(frame)))

            /** Interrupts the Fiber with a specific error.
              *
              * @param error
              *   The error to interrupt the Fiber with
              * @return
              *   Whether the Fiber was successfully interrupted
              */
            def interrupt(error: Panic)(using Frame): Boolean < IO =
                IO(self.interrupt(error))

            /** Interrupts the Fiber with a specific error, discarding the return value.
              *
              * @param error
              *   The error to interrupt the Fiber with
              */
            def interruptUnit(error: Panic)(using Frame): Unit < IO =
                IO(discard(self.interrupt(error)))

            private[kyo] inline def unsafe: IOPromise[E, A] = self

        end extension

        case class Interrupted(at: Frame)
            extends RuntimeException("Fiber interrupted at " + at.parse.position)
            with NoStackTrace:
            override def getCause() = null
        end Interrupted

        /** Races multiple Fibers and returns a Fiber that completes with the result of the first to complete. When one Fiber completes, all
          * other Fibers are interrupted.
          *
          * @param seq
          *   The sequence of Fibers to race
          * @return
          *   A Fiber that completes with the result of the first Fiber to complete
          */
        def race[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
            using
            boundary: Boundary[Ctx, IO],
            reduce: Reducible[Abort[E]],
            frame: Frame,
            safepoint: Safepoint
        ): Fiber[E, A] < (IO & Ctx) =
            IO {
                class State extends IOPromise[E, A] with Function1[Result[E, A], Unit]:
                    val pending = new AtomicInteger(seq.size)
                    def apply(result: Result[E, A]): Unit =
                        val last = pending.decrementAndGet() == 0
                        result.fold(e => if last then completeUnit(e))(v => completeUnit(Result.succeed(v)))
                    end apply
                end State
                val state = new State
                import state.*
                foreach(seq)((idx, io) => io.evalNow.foreach(v => state(Result.succeed(v))))
                if state.done() then
                    state
                else
                    boundary { (trace, context) =>
                        IO {
                            foreach(seq) { (_, v) =>
                                val fiber = IOTask(v, safepoint.copyTrace(trace), context)
                                state.interrupts(fiber)
                                fiber.onComplete(state)
                            }
                            state
                        }
                    }
                end if
            }

        /** Runs multiple Fibers in parallel and returns a Fiber that completes with their results. If any Fiber fails or is interrupted,
          * all other Fibers are interrupted.
          *
          * @param seq
          *   The sequence of Fibers to run in parallel
          * @return
          *   A Fiber that completes with the results of all Fibers
          */
        def parallel[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
            using
            boundary: Boundary[Ctx, IO],
            reduce: Reducible[Abort[E]],
            frame: Frame,
            safepoint: Safepoint
        ): Fiber[E, Seq[A]] < (IO & Ctx) =
            seq.size match
                case 0 => Fiber.succeed(Seq.empty)
                case _ =>
                    IO {
                        class State extends IOPromise[E, Seq[A]]:
                            val results = (new Array[Any](seq.size)).asInstanceOf[Array[A]]
                            val pending = new AtomicInteger(seq.size)
                            def update(idx: Int, value: A) =
                                results(idx) = value
                                if pending.decrementAndGet() == 0 then
                                    this.completeUnit(Result.succeed(ArraySeq.unsafeWrapArray(results)))
                            end update
                        end State
                        val state = new State
                        import state.*
                        foreach(seq)((idx, io) => io.evalNow.foreach(update(idx, _)))
                        if state.done() then state
                        else
                            boundary { (trace, context) =>
                                IO {
                                    foreach(seq) { (idx, v) =>
                                        if isNull(results(idx)) then
                                            val fiber = IOTask(v, safepoint.copyTrace(trace), context)
                                            state.interrupts(fiber)
                                            fiber.onComplete(_.fold(state.completeUnit)(update(idx, _)))
                                    }
                                    state
                                }
                            }
                        end if
                    }

    end Fiber

    /** Delays execution of a computation by a specified duration.
      *
      * @param d
      *   The duration to delay
      * @param v
      *   The computation to execute after the delay
      * @return
      *   The result of the computation
      */
    def delay[A, S](d: Duration)(v: => A < S)(using Frame): A < (S & Async) =
        sleep(d).andThen(v)

    /** Suspends execution for a specified duration.
      *
      * @param d
      *   The duration to sleep
      */
    def sleep(d: Duration)(using Frame): Unit < Async =
        if d == Duration.Zero then ()
        else
            IO {
                val p = IOPromise[Nothing, Unit]()
                if d.isFinite then
                    Timer.schedule(d)(p.completeUnit(Result.unit)).map { t =>
                        IO.ensure(t.cancel.unit)(get(p))
                    }
                else
                    get(p)
                end if
            }

    /** Runs a computation with a timeout.
      *
      * @param d
      *   The timeout duration
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation, or a Timeout error
      */
    def timeout[E, A: Flat, Ctx](d: Duration)(v: => A < (Abort[E] & Async & Ctx))(
        using
        boundary: Boundary[Ctx, Async & Abort[E]],
        frame: Frame
    ): A < (Abort[E | Timeout] & Async & Ctx) =
        boundary { (trace, context) =>
            val task = IOTask[Ctx, E | Timeout, A](v, trace, context)
            Timer.schedule(d)(task.completeUnit(Result.error(Timeout(frame)))).map { t =>
                IO.ensure(t.cancel.unit)(Async.get(task))
            }
        }
    end timeout

    /** Races multiple computations and returns the result of the first to complete. When one computation completes, all other computations
      * are interrupted.
      *
      * @param seq
      *   The sequence of computations to race
      * @return
      *   The result of the first computation to complete
      */
    def race[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using
        boundary: Boundary[Ctx, Async],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): A < (reduce.SReduced & Async & Ctx) =
        if seq.isEmpty then reduce(seq(0))
        else Fiber.race(seq).map(get)

    /** Races two or more computations and returns the result of the first to complete.
      *
      * @param first
      *   The first computation
      * @param rest
      *   The rest of the computations
      * @return
      *   The result of the first computation to complete
      */
    def race[E, A: Flat, Ctx](
        first: A < (Abort[E] & Async & Ctx),
        rest: A < (Abort[E] & Async & Ctx)*
    )(
        using
        boundary: Boundary[Ctx, Async],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): A < (reduce.SReduced & Async & Ctx) =
        race[E, A, Ctx](first +: rest)

    /** Runs multiple computations in parallel and returns their results. If any computation fails or is interrupted, all other computations
      * are interrupted.
      *
      * @param seq
      *   The sequence of computations to run in parallel
      * @return
      *   A sequence containing the results of all computations
      */
    def parallel[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using
        boundary: Boundary[Ctx, Async],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Seq[A] < (reduce.SReduced & Async & Ctx) =
        seq.size match
            case 0 => Seq.empty
            case 1 => reduce(seq(0).map(Seq(_)))
            case _ => Fiber.parallel(seq).map(get)
        end match
    end parallel

    /** Runs two computations in parallel and returns their results as a tuple.
      *
      * @param v1
      *   The first computation
      * @param v2
      *   The second computation
      * @return
      *   A tuple containing the results of both computations
      */
    def parallel[E, A1: Flat, A2: Flat, Ctx](
        v1: A1 < (Abort[E] & Async & Ctx),
        v2: A2 < (Abort[E] & Async & Ctx)
    )(
        using
        boundary: Boundary[Ctx, Async],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): (A1, A2) < (reduce.SReduced & Async & Ctx) =
        parallel(Seq(v1, v2))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2])
        }

    /** Runs three computations in parallel and returns their results as a tuple.
      *
      * @param v1
      *   The first computation
      * @param v2
      *   The second computation
      * @param v3
      *   The third computation
      * @return
      *   A tuple containing the results of all three computations
      */
    def parallel[E, A1: Flat, A2: Flat, A3: Flat, Ctx](
        v1: A1 < (Abort[E] & Async & Ctx),
        v2: A2 < (Abort[E] & Async & Ctx),
        v3: A3 < (Abort[E] & Async & Ctx)
    )(
        using
        boundary: Boundary[Ctx, Async],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): (A1, A2, A3) < (reduce.SReduced & Async & Ctx) =
        parallel(Seq(v1, v2, v3))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3])
        }

    /** Runs four computations in parallel and returns their results as a tuple.
      *
      * @param v1
      *   The first computation
      * @param v2
      *   The second computation
      * @param v3
      *   The third computation
      * @param v4
      *   The fourth computation
      * @return
      *   A tuple containing the results of all four computations
      */
    def parallel[E, A1: Flat, A2: Flat, A3: Flat, A4: Flat, Ctx](
        v1: A1 < (Abort[E] & Async & Ctx),
        v2: A2 < (Abort[E] & Async & Ctx),
        v3: A3 < (Abort[E] & Async & Ctx),
        v4: A4 < (Abort[E] & Async & Ctx)
    )(
        using
        boundary: Boundary[Ctx, Async],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): (A1, A2, A3, A4) < (reduce.SReduced & Async & Ctx) =
        parallel(Seq(v1, v2, v3, v4))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3], s(3).asInstanceOf[A4])
        }

    /** Gets the result of an IOPromise.
      *
      * @param v
      *   The IOPromise to get the result from
      * @return
      *   The result of the IOPromise
      */
    def get[E, A](v: IOPromise[E, A])(using reduce: Reducible[Abort[E]], frame: Frame): A < (reduce.SReduced & Async) =
        reduce(use(v)(identity))

    private[kyo] def use[E, A, B, S](v: IOPromise[E, A])(f: A => B < S)(
        using
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): B < (S & reduce.SReduced & Async) =
        val x = useResult(v)(_.fold(Abort.fail)(f))
        reduce(x)
    end use

    private[kyo] def getResult[E, A](v: IOPromise[E, A])(using Frame): Result[E, A] < Async =
        ArrowEffect.suspend[A](Tag[Join], v).asInstanceOf[Result[E, A] < Async]

    private[kyo] def useResult[E, A, B, S](v: IOPromise[E, A])(f: Result[E, A] => B < S)(using Frame): B < (S & Async) =
        ArrowEffect.suspendMap[A](Tag[Join], v)(f)

    private def deadline(timeout: Duration): Long =
        if timeout.isFinite then
            java.lang.System.currentTimeMillis() + timeout.toMillis
        else
            Long.MaxValue

    private inline def foreach[A](l: Seq[A])(inline f: (Int, A) => Unit): Unit =
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
