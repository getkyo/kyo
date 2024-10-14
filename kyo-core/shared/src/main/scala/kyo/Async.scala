package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.Result.Panic
import kyo.Tag
import kyo.internal.FiberPlatformSpecific
import kyo.kernel.*
import kyo.scheduler.*
import scala.annotation.implicitNotFound
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
        boundary((trace, context) => Fiber.fromTask(IOTask(v, trace, context)))

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
            fiber.block(timeout).map(Abort.get(_))
        }

    /** Runs an asynchronous computation with interrupt masking.
      *
      * This method executes the given computation in a context where interrupts are not propagated to previous "steps" of the computation.
      * The returned computation can still be interrupted, but the interruption won't affect the masked portion. This is useful for ensuring
      * that cleanup operations or critical sections complete even if an interrupt occurs.
      *
      * @param v
      *   The computation to run with interrupt masking
      * @return
      *   The result of the computation, which can still be interrupted
      */
    def mask[E, A: Flat, Ctx](v: => A < (Abort[E] & Async & Ctx))(
        using
        boundary: Boundary[Ctx, IO],
        frame: Frame
    ): A < (Abort[E] & Async & Ctx) =
        Async.run(v).map(_.mask.map(_.get))

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
            IO.Unsafe {
                val p = Promise.Unsafe.init[Nothing, Unit]()
                if d.isFinite then
                    Timer.schedule(d)(p.completeDiscard(Result.success(()))).map { t =>
                        IO.ensure(t.cancel.unit)(p.safe.get)
                    }
                else
                    p.safe.get
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
            Timer.schedule(d)(task.completeDiscard(Result.fail(Timeout(frame)))).map { t =>
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
        else Fiber.race(seq).map(_.get)

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
            case _ => Fiber.parallel(seq).map(_.get)
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

    /** Converts a Future to an asynchronous computation.
      *
      * This method allows integration of existing Future-based code with Kyo's asynchronous system. It handles successful completion and
      * failures, wrapping any exceptions in an Abort effect.
      *
      * @param f
      *   The Future to convert into an asynchronous computation
      * @return
      *   An asynchronous computation that completes with the result of the Future or aborts with Throwable
      */
    def fromFuture[A](f: Future[A])(using frame: Frame): A < (Async & Abort[Throwable]) =
        Fiber.fromFuture(f).map(_.get)

    private[kyo] def get[E, A](v: IOPromise[E, A])(using reduce: Reducible[Abort[E]], frame: Frame): A < (reduce.SReduced & Async) =
        reduce(use(v)(identity))

    private[kyo] def use[E, A, B, S](v: IOPromise[E, A])(f: A => B < S)(
        using
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): B < (S & reduce.SReduced & Async) =
        val x = useResult(v)(_.fold(Abort.error)(f))
        reduce(x)
    end use

    private[kyo] def getResult[E, A](v: IOPromise[E, A])(using Frame): Result[E, A] < Async =
        ArrowEffect.suspend[A](Tag[Join], v).asInstanceOf[Result[E, A] < Async]

    private[kyo] def useResult[E, A, B, S](v: IOPromise[E, A])(f: Result[E, A] => B < S)(using Frame): B < (S & Async) =
        ArrowEffect.suspendMap[A](Tag[Join], v)(f)

end Async
