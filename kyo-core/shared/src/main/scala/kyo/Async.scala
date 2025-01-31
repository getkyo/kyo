package kyo

import kyo.Result.Panic
import kyo.Tag
import kyo.kernel.*
import kyo.scheduler.*
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.NotGiven
import scala.util.control.NonFatal

/** Asynchronous computation effect.
  *
  * While IO handles pure effect suspension, Async provides the complete toolkit for concurrent programming - managing fibers, scheduling,
  * and execution control. It includes IO in its effect set, making it a unified solution for both synchronous and asynchronous operations.
  *
  * This separation, enabled by Kyo's algebraic effect system, is reflected in the codebase's design: the presence of Async in pending
  * effects signals that a computation may park or involve fiber scheduling, contrasting with IO-only operations that run to completion.
  *
  * Most application code can work exclusively with Async, with the IO/Async distinction becoming relevant primarily in library code or
  * performance-critical sections where precise control over execution characteristics is needed.
  *
  * This effect includes IO in its effect set to handle both async and sync execution in a single effect.
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

    /** Convenience method for suspending computations in an Async effect.
      *
      * While IO is specifically designed to suspend side effects without handling asynchronicity, Async provides both side effect
      * suspension and asynchronous execution capabilities (fibers, async scheduling). Since Async includes IO in its effect set, this
      * method allows users to work with a single unified effect that handles both concerns.
      *
      * Note that this method only suspends the computation - it does not fork execution into a new fiber. For concurrent execution, use
      * Async.run or combinators like Async.parallel instead.
      *
      * This is particularly useful in application code where the distinction between pure side effects and asynchronous execution is less
      * important than having a simple, consistent way to handle effects. The underlying effects are typically managed together at the
      * application boundary through KyoApp.
      *
      * @param v
      *   The computation to suspend
      * @param frame
      *   Implicit frame for the computation
      * @tparam A
      *   The result type of the computation
      * @tparam S
      *   Additional effects in the computation
      * @return
      *   The suspended computation wrapped in an Async effect
      */
    inline def apply[A, S](inline v: => A < S)(using inline frame: Frame): A < (Async & S) =
        IO(v)

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

    /** Creates a computation that never completes.
      *
      * @return
      *   A computation that never completes
      */
    def never[E, A](using Frame): A < Async = Fiber.never[Nothing, A].get

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
                        sleepFiber.onComplete(_ => discard(task.interrupt(Result.Failure(Timeout()))))
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
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
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

    /** Concurrently executes effects and collects their successful results.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
      *
      * Similar to the sequence-based gather, but accepts varargs input.
      *
      * @param first
      *   First effect to execute
      * @param rest
      *   Rest of the effects to execute
      * @return
      *   Successful results as a Chunk
      */
    inline def gather[E, A: Flat, Ctx](
        first: A < (Abort[E] & Async & Ctx),
        rest: A < (Abort[E] & Async & Ctx)*
    )(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & Ctx) =
        gather(first +: rest)

    /** Concurrently executes effects and collects up to `max` successful results.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
      *
      * Similar to the sequence-based gather with max, but accepts varargs input.
      *
      * @param max
      *   Maximum number of successful results to collect
      * @param first
      *   First effect to execute
      * @param rest
      *   Rest of the effects to execute
      * @return
      *   Successful results as a Chunk (size <= max)
      */
    inline def gather[E, A: Flat, Ctx](max: Int)(
        first: A < (Abort[E] & Async & Ctx),
        rest: A < (Abort[E] & Async & Ctx)*
    )(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & Ctx) =
        gather(max)(first +: rest)

    /** Concurrently executes effects and collects their successful results.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
      *
      * Executes all effects concurrently and returns successful results in completion order. If all computations fail, the last encountered
      * error is propagated. The operation completes when all effects have either succeeded or failed.
      *
      * @tparam Ctx
      *   Context requirements
      * @param seq
      *   Sequence of effects to execute
      * @return
      *   Successful results as a Chunk
      */
    inline def gather[E, A: Flat, Ctx](seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & Ctx) =
        _gather(seq.size)(seq)

    /** Concurrently executes effects and collects up to `max` successful results.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
      *
      * Similar to `gather`, but completes early once the specified number of `max` successful results is reached. If not enough successes
      * occur and all remaining computations fail, the last encountered error is propagated.
      *
      * @param max
      *   Maximum number of successful results to collect
      * @param seq
      *   Sequence of effects to execute
      * @return
      *   Successful results as a Chunk (size <= max)
      */
    inline def gather[E, A: Flat, Ctx](max: Int)(seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & Ctx) =
        _gather(max)(seq)

    private def _gather[E, A: Flat, Ctx](max: Int)(seq: Seq[A < (Abort[E] & Async & Ctx)])(
        using
        boundary: Boundary[Ctx, Async & Abort[E]],
        frame: Frame
    ): Chunk[A] < (Abort[E] & Async & Ctx) =
        Fiber._gather(max)(seq.size, seq).map(_.get)

    /** Concurrently executes effects with state isolation and collects their successful results.
      *
      * @param isolate
      *   Controls state propagation during execution
      * @param first
      *   First effect to execute
      * @param rest
      *   Rest of the effects to execute
      * @return
      *   Successful results as a Chunk
      */
    inline def gather[E, A: Flat, S, Ctx](isolate: Isolate[S])(
        first: A < (Abort[E] & Async & S & Ctx),
        rest: A < (Abort[E] & Async & S & Ctx)*
    )(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S & Ctx) =
        gather(isolate)(first +: rest)

    /** Concurrently executes effects with state isolation and collects up to `max` successful results.
      *
      * @param max
      *   Maximum number of successful results to collect
      * @param isolate
      *   Controls state propagation during execution
      * @param first
      *   First effect to execute
      * @param rest
      *   Rest of the effects to execute
      * @return
      *   Successful results as a Chunk (size <= max)
      */
    inline def gather[E, A: Flat, S, Ctx](max: Int, isolate: Isolate[S])(
        first: A < (Abort[E] & Async & S & Ctx),
        rest: A < (Abort[E] & Async & S & Ctx)*
    )(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S & Ctx) =
        gather(max, isolate)(first +: rest)

    /** Concurrently executes effects with state isolation and collects their successful results.
      *
      * @param isolate
      *   Controls state propagation during execution
      * @param seq
      *   Sequence of effects to execute
      * @return
      *   Successful results as a Chunk
      */
    inline def gather[E, A: Flat, S, Ctx](isolate: Isolate[S])(seq: Seq[A < (Abort[E] & Async & S & Ctx)])(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S & Ctx) =
        gather(seq.size, isolate)(seq)

    /** Concurrently executes effects with state isolation and collects up to `max` successful results.
      *
      * @param max
      *   Maximum number of successful results to collect
      * @param isolate
      *   Controls state propagation during execution
      * @param seq
      *   Sequence of effects to execute
      * @return
      *   Successful results as a Chunk (size <= max)
      */
    inline def gather[E, A: Flat, S, Ctx](max: Int, isolate: Isolate[S])(seq: Seq[A < (Abort[E] & Async & S & Ctx)])(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S & Ctx) =
        isolate.use { state =>
            _gather(max)(seq.map(isolate.resume(state, _))).map { results =>
                Kyo.collect(results.map((state, result) => isolate.restore(state, result)))
            }
        }

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

    /** Creates a memoized version of a computation.
      *
      * Returns a function that will cache the result of the first execution and return the cached result for subsequent calls. During
      * initialization, only one fiber will execute the computation while others wait for the result. If the first execution fails, the
      * cache is cleared and the computation will be retried on the next invocation. Note that successful results are cached indefinitely,
      * so use this for stable values that won't need refreshing.
      *
      * Unlike `Memo`, this memoization is optimized for performance and can be safely used in hot paths. If you're memoizing global
      * initialization code or need more control over cache isolation, consider using `Memo` instead.
      *
      * WARNING: If the initial computation never completes (e.g., hangs indefinitely), all subsequent calls will be permanently blocked
      * waiting for the result. Ensure the computation can complete in a reasonable time or introduce a timeout via `Async.timeout`.
      *
      * @param v
      *   The computation to memoize
      * @return
      *   A function that returns the memoized computation result
      */
    def memoize[A: Flat, S](v: A < S)(using Frame): (() => A < (S & Async)) < Async =
        IO.Unsafe {
            val ref = AtomicRef.Unsafe.init(Maybe.empty[Promise.Unsafe[Nothing, A]])
            () =>
                @tailrec def loop(): A < (S & Async) =
                    ref.get() match
                        case Present(v) => v.safe.get
                        case Absent =>
                            val promise = Promise.Unsafe.init[Nothing, A]()
                            if ref.compareAndSet(Absent, Present(promise)) then
                                Abort.run(v).map { r =>
                                    IO.Unsafe {
                                        if !r.isSuccess then
                                            ref.set(Absent)
                                        promise.completeDiscard(r)
                                        Abort.get(r)
                                    }
                                }.pipe(IO.ensure {
                                    IO.Unsafe {
                                        if !promise.done() then
                                            ref.set(Absent)
                                    }
                                })
                            else
                                loop()
                            end if
                loop()

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
        val x = useResult(v)(_.foldAll(Abort.panic)(Abort.fail)(f))
        reduce(x)
    end use

    private[kyo] def getResult[E, A](v: IOPromise[E, A])(using Frame): Result[E, A] < Async =
        ArrowEffect.suspend[A](Tag[Join], v).asInstanceOf[Result[E, A] < Async]

    private[kyo] def useResult[E, A, B, S](v: IOPromise[E, A])(f: Result[E, A] => B < S)(using Frame): B < (S & Async) =
        ArrowEffect.suspendWith[A](Tag[Join], v)(f)

end Async
