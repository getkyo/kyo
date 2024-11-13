package kyo

import kyo.Result.Panic
import kyo.Tag
import kyo.kernel.*
import kyo.scheduler.*
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.NotGiven
import scala.util.control.NonFatal

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
opaque type Async <: (IO & Async.Join & Abort[Nothing]) = Async.Join & IO & Abort[Nothing]

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
        using frame: Frame
    ): Fiber[E, A] < (IO & Ctx) =
        _run(v)

    private[kyo] inline def _run[E, A: Flat, Ctx](inline v: => A < (Abort[E] & Async & Ctx))(
        using
        boundary: Boundary[Ctx, IO & Abort[E]],
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
    inline def runAndBlock[E, A: Flat, Ctx](timeout: Duration)(v: => A < (Abort[E] & Async & Ctx))(
        using frame: Frame
    ): A < (Abort[E | Timeout] & IO & Ctx) =
        _runAndBlock(timeout)(v)

    private def _runAndBlock[E, A: Flat, Ctx](timeout: Duration)(v: => A < (Abort[E] & Async & Ctx))(
        using
        boundary: Boundary[Ctx, IO & Abort[E | Timeout]],
        frame: Frame
    ): A < (Abort[E | Timeout] & IO & Ctx) =
        _run(v).map { fiber =>
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
    inline def mask[E, A: Flat, Ctx](v: => A < (Abort[E] & Async & Ctx))(
        using frame: Frame
    ): A < (Abort[E] & Async & Ctx) =
        _mask(v)

    private def _mask[E, A: Flat, Ctx](v: => A < (Abort[E] & Async & Ctx))(
        using
        boundary: Boundary[Ctx, Async & Abort[E]],
        frame: Frame
    ): A < (Abort[E] & Async & Ctx) =
        _run(v).map(_.mask.map(_.get))

    /** Runs an async computation with interrupt masking and state isolation.
      *
      * @param isolate
      *   Controls state propagation during masked execution
      * @param v
      *   The computation to mask
      * @return
      *   The computation result
      */
    inline def mask[E, A: Flat, S, Ctx](isolate: Isolate[S])(v: => A < (Abort[E] & Async & S & Ctx))(
        using frame: Frame
    ): A < (Abort[E] & Async & S & Ctx) =
        _mask(isolate)(v)

    private def _mask[E, A: Flat, S, Ctx](isolate: Isolate[S])(v: => A < (Abort[E] & Async & S & Ctx))(
        using
        boundary: Boundary[Ctx, S & Async & Abort[E]],
        frame: Frame
    ): A < (Abort[E] & Async & S & Ctx) =
        isolate.use { state =>
            _mask(isolate.resume(state, v)).map(isolate.restore(_, _))
        }

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
    def sleep(duration: Duration)(using Frame): Unit < Async =
        if duration == Duration.Zero then ()
        else Clock.sleep(duration).map(_.get)

    /** Runs a computation with a timeout.
      *
      * @param d
      *   The timeout duration
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation, or a Timeout error
      */
    inline def timeout[E, A: Flat, Ctx](after: Duration)(v: => A < (Abort[E] & Async & Ctx))(
        using frame: Frame
    ): A < (Abort[E | Timeout] & Async & Ctx) =
        _timeout(after)(v)

    private def _timeout[E, A: Flat, Ctx](after: Duration)(v: => A < (Abort[E] & Async & Ctx))(
        using
        boundary: Boundary[Ctx, Async & Abort[E | Timeout]],
        frame: Frame
    ): A < (Abort[E | Timeout] & Async & Ctx) =
        if !after.isFinite then v
        else
            boundary { (trace, context) =>
                Clock.use { clock =>
                    IO.Unsafe {
                        val sleepFiber = clock.unsafe.sleep(after)
                        val task       = IOTask[Ctx, E | Timeout, A](v, trace, context)
                        sleepFiber.onComplete(_ => discard(task.interrupt(Result.Fail(Timeout(frame)))))
                        task.onComplete(_ => discard(sleepFiber.interrupt()))
                        Async.get(task)
                    }
                }
            }

    /** Runs a computation with timeout and state isolation.
      *
      * @param after
      *   The timeout duration
      * @param isolate
      *   Controls state propagation during execution
      * @param v
      *   The computation to timeout
      * @return
      *   The result or Timeout error
      */
    inline def timeout[E, A: Flat, S, Ctx](after: Duration, isolate: Isolate[S])(v: => A < (Abort[E] & Async & S & Ctx))(
        using frame: Frame
    ): A < (Abort[E | Timeout] & Async & S & Ctx) =
        _timeout(after, isolate)(v)

    private def _timeout[E, A: Flat, S, Ctx](after: Duration, isolate: Isolate[S])(v: => A < (Abort[E] & Async & S & Ctx))(
        using
        boundary: Boundary[Ctx, S & Async & Abort[E | Timeout]],
        frame: Frame
    ): A < (Abort[E | Timeout] & Async & S & Ctx) =
        isolate.use { state =>
            _timeout(after)(isolate.resume(state, v)).map(isolate.restore(_, _))
        }

    /** Races multiple computations and returns the result of the first to complete. When one computation completes, all other computations
      * are interrupted.
      *
      * @param seq
      *   The sequence of computations to race
      * @return
      *   The result of the first computation to complete
      */
    inline def race[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using frame: Frame
    ): A < (Abort[E] & Async & Ctx) =
        _race(seq)

    private def _race[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using
        boundary: Boundary[Ctx, Async & Abort[E]],
        frame: Frame
    ): A < (Abort[E] & Async & Ctx) =
        if seq.isEmpty then seq(0)
        else Fiber._race(seq).map(_.get)

    /** Races computations with state isolation, returning first to complete.
      *
      * @param isolate
      *   Controls state propagation during race
      * @param seq
      *   Computations to race
      * @return
      *   First successful result
      */
    inline def race[E, A: Flat, S, Ctx](isolate: Isolate[S])(seq: Seq[A < (Abort[E] & Async & S & Ctx)])(
        using frame: Frame
    ): A < (Abort[E] & Async & S & Ctx) =
        _race(isolate)(seq)

    private def _race[E, A: Flat, S, Ctx](isolate: Isolate[S])(seq: Seq[A < (Abort[E] & Async & S & Ctx)])(
        using
        boundary: Boundary[Ctx, S & Async & Abort[E]],
        frame: Frame
    ): A < (Abort[E] & Async & S & Ctx) =
        isolate.use { state =>
            _race(seq.map(isolate.resume(state, _))).map(isolate.restore(_, _))
        }

    /** Races two or more computations and returns the result of the first to complete.
      *
      * @param first
      *   The first computation
      * @param rest
      *   The rest of the computations
      * @return
      *   The result of the first computation to complete
      */
    inline def race[E, A: Flat, Ctx](
        first: A < (Abort[E] & Async & Ctx),
        rest: A < (Abort[E] & Async & Ctx)*
    )(
        using frame: Frame
    ): A < (Abort[E] & Async & Ctx) =
        race[E, A, Ctx](first +: rest)

    /** Races multiple computations with state isolation.
      *
      * @param isolate
      *   Controls state propagation during race
      * @param first
      *   First computation to race
      * @param rest
      *   Additional computations to race
      * @return
      *   First successful result
      */
    inline def race[E, A: Flat, S, Ctx](isolate: Isolate[S])(
        first: A < (Abort[E] & Async & S & Ctx),
        rest: A < (Abort[E] & Async & S & Ctx)*
    )(
        using frame: Frame
    ): A < (Abort[E] & Async & S & Ctx) =
        race[E, A, S, Ctx](isolate)(first +: rest)

    /** Runs multiple computations in parallel with unlimited parallelism and returns their results.
      *
      * Unlike [[parallel]], this method starts all computations immediately without any concurrency control. This can lead to resource
      * exhaustion if the input sequence is large. Consider using [[parallel]] instead, which allows you to control the level of
      * concurrency.
      *
      * If any computation fails or is interrupted, all other computations are interrupted.
      *
      * @param seq
      *   The sequence of computations to run in parallel
      * @return
      *   A sequence containing the results of all computations in their original order
      */
    inline def parallelUnbounded[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using frame: Frame
    ): Seq[A] < (Abort[E] & Async & Ctx) =
        _parallelUnbounded(seq)

    private def _parallelUnbounded[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using
        boundary: Boundary[Ctx, Async & Abort[E]],
        frame: Frame
    ): Seq[A] < (Abort[E] & Async & Ctx) =
        seq.size match
            case 0 => Seq.empty
            case 1 => seq(0).map(Seq(_))
            case _ => Fiber._parallelUnbounded(seq).map(_.get)
        end match
    end _parallelUnbounded

    /** Runs computations in parallel with unlimited concurrency and state isolation.
      *
      * @param isolate
      *   Controls state propagation during parallel execution
      * @param seq
      *   Computations to run in parallel
      * @return
      *   Results in original order
      */
    inline def parallelUnbounded[E, A: Flat, S, Ctx](isolate: Isolate[S], seq: Seq[A < (Abort[E] & Async & S & Ctx)])(
        using frame: Frame
    ): Seq[A] < (Abort[E] & Async & S & Ctx) =
        _parallelUnbounded(isolate, seq)

    private def _parallelUnbounded[E, A: Flat, S, Ctx](isolate: Isolate[S], seq: Seq[A < (Abort[E] & Async & S & Ctx)])(
        using
        boundary: Boundary[Ctx, S & Async & Abort[E]],
        frame: Frame
    ): Seq[A] < (Abort[E] & Async & S & Ctx) =
        isolate.use { state =>
            _parallelUnbounded(seq.map(isolate.resume(state, _))).map { results =>
                Kyo.collect(results.map((state, result) => isolate.restore(state, result)))
            }
        }

    /** Runs multiple computations in parallel with a specified level of parallelism and returns their results.
      *
      * This method allows you to execute a sequence of computations with controlled parallelism by grouping them into batches. The
      * computations are divided into groups based on the parallelism parameter. Each group is assigned to a new fiber, and the computations
      * within each fiber are processed sequentially.
      *
      * For example, with a parallelism of 2 and 6 tasks, there will be 2 fibers, each processing 3 tasks sequentially. The group size is
      * calculated as ceil(n/parallelism) where n is the total number of computations.
      *
      * If any computation fails or is interrupted, all other computations are interrupted.
      *
      * @param parallelism
      *   The maximum number of computations to run concurrently
      * @param seq
      *   The sequence of computations to run in parallel
      * @return
      *   A sequence containing the results of all computations in their original order
      */
    inline def parallel[E, A: Flat, Ctx](parallelism: Int)(seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using frame: Frame
    ): Seq[A] < (Abort[E] & Async & Ctx) =
        _parallel(parallelism)(seq)

    private def _parallel[E, A: Flat, Ctx](parallelism: Int)(seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using
        boundary: Boundary[Ctx, Async & Abort[E]],
        frame: Frame
    ): Seq[A] < (Abort[E] & Async & Ctx) =
        seq.size match
            case 0 => Seq.empty
            case 1 => seq(0).map(Seq(_))
            case n => Fiber._parallel(parallelism)(seq).map(_.get)

    /** Runs computations in parallel with controlled concurrency and state isolation.
      *
      * @param parallelism
      *   Maximum concurrent computations
      * @param isolate
      *   Controls state propagation during parallel execution
      * @param seq
      *   Computations to run in parallel
      * @return
      *   Results in original order
      */
    inline def parallel[E, A: Flat, S, Ctx](parallelism: Int, isolate: Isolate[S])(seq: Seq[A < (Abort[E] & Async & S & Ctx)])(
        using frame: Frame
    ): Seq[A] < (Abort[E] & Async & S & Ctx) =
        _parallel(parallelism, isolate)(seq)

    private def _parallel[E, A: Flat, S, Ctx](parallelism: Int, isolate: Isolate[S])(seq: Seq[A < (Abort[E] & Async & S & Ctx)])(
        using
        boundary: Boundary[Ctx, S & Async & Abort[E]],
        frame: Frame
    ): Seq[A] < (Abort[E] & Async & S & Ctx) =
        isolate.use { state =>
            _parallel(parallelism)(seq.map(isolate.resume(state, _))).map { results =>
                Kyo.collect(results.map((state, result) => isolate.restore(state, result)))
            }
        }

    /** Runs two computations in parallel and returns their results as a tuple.
      *
      * @param v1
      *   The first computation
      * @param v2
      *   The second computation
      * @return
      *   A tuple containing the results of both computations
      */
    inline def parallel[E, A1: Flat, A2: Flat, Ctx](
        v1: A1 < (Abort[E] & Async & Ctx),
        v2: A2 < (Abort[E] & Async & Ctx)
    )(
        using frame: Frame
    ): (A1, A2) < (Abort[E] & Async & Ctx) =
        parallelUnbounded(Seq(v1, v2))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2])
        }

    /** Runs two computations in parallel with state isolation.
      *
      * @param isolate
      *   Controls state propagation during parallel execution
      * @param v1
      *   First computation
      * @param v2
      *   Second computation
      * @return
      *   Tuple of results
      */
    inline def parallel[E, A1: Flat, A2: Flat, S, Ctx](isolate: Isolate[S])(
        v1: A1 < (Abort[E] & Async & S & Ctx),
        v2: A2 < (Abort[E] & Async & S & Ctx)
    )(
        using frame: Frame
    ): (A1, A2) < (Abort[E] & Async & S & Ctx) =
        parallelUnbounded(isolate, Seq(v1, v2))(using Flat.unsafe.bypass).map { s =>
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
    inline def parallel[E, A1: Flat, A2: Flat, A3: Flat, Ctx](
        v1: A1 < (Abort[E] & Async & Ctx),
        v2: A2 < (Abort[E] & Async & Ctx),
        v3: A3 < (Abort[E] & Async & Ctx)
    )(
        using frame: Frame
    ): (A1, A2, A3) < (Abort[E] & Async & Ctx) =
        parallelUnbounded(Seq(v1, v2, v3))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3])
        }

    /** Runs three computations in parallel with state isolation.
      *
      * @param isolate
      *   Controls state propagation during parallel execution
      * @param v1
      *   First computation
      * @param v2
      *   Second computation
      * @param v3
      *   Third computation
      * @return
      *   Tuple of results
      */
    inline def parallel[E, A1: Flat, A2: Flat, A3: Flat, S, Ctx](isolate: Isolate[S])(
        v1: A1 < (Abort[E] & Async & Ctx),
        v2: A2 < (Abort[E] & Async & Ctx),
        v3: A3 < (Abort[E] & Async & Ctx)
    )(
        using frame: Frame
    ): (A1, A2, A3) < (Abort[E] & Async & Ctx) =
        parallelUnbounded(Seq(v1, v2, v3))(using Flat.unsafe.bypass).map { s =>
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
    inline def parallel[E, A1: Flat, A2: Flat, A3: Flat, A4: Flat, Ctx](
        v1: A1 < (Abort[E] & Async & Ctx),
        v2: A2 < (Abort[E] & Async & Ctx),
        v3: A3 < (Abort[E] & Async & Ctx),
        v4: A4 < (Abort[E] & Async & Ctx)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4) < (Abort[E] & Async & Ctx) =
        parallelUnbounded(Seq(v1, v2, v3, v4))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3], s(3).asInstanceOf[A4])
        }

    /** Runs four computations in parallel with state isolation.
      *
      * @param isolate
      *   Controls state propagation during parallel execution
      * @param v1
      *   First computation
      * @param v2
      *   Second computation
      * @param v3
      *   Third computation
      * @param v4
      *   Fourth computation
      * @return
      *   Tuple of results
      */
    inline def parallel[E, A1: Flat, A2: Flat, A3: Flat, A4: Flat, S, Ctx](isolate: Isolate[S])(
        v1: A1 < (Abort[E] & Async & S & Ctx),
        v2: A2 < (Abort[E] & Async & S & Ctx),
        v3: A3 < (Abort[E] & Async & S & Ctx),
        v4: A4 < (Abort[E] & Async & S & Ctx)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4) < (Abort[E] & Async & S & Ctx) =
        parallelUnbounded(isolate, Seq(v1, v2, v3, v4))(using Flat.unsafe.bypass).map { s =>
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
        ArrowEffect.suspendAndMap[A](Tag[Join], v)(f)

end Async
