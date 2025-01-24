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
    def run[E, A: Flat, S](
        using isolate: Isolate.Contextual[S, IO]
    )(v: => A < (Abort[E] & Async & S))(using Frame): Fiber[E, A] < (IO & S) =
        isolate.runInternal((trace, context) =>
            Fiber.fromTask(IOTask(v, trace, context))
        )

    /** Runs an asynchronous computation and blocks until completion or timeout.
      *
      * @param timeout
      *   The maximum duration to wait
      * @param v
      *   The computation to run
      * @return
      *   The result of the computation, or a Timeout error
      */
    def runAndBlock[E, A: Flat, S](
        using isolate: Isolate.Contextual[S, IO]
    )(timeout: Duration)(v: => A < (Abort[E] & Async & S))(
        using frame: Frame
    ): A < (Abort[E | Timeout] & IO & S) =
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
    def mask[E, A: Flat, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(v: => A < (Abort[E] & Async & S))(
        using frame: Frame
    ): A < (Abort[E] & Async & S) =
        isolate.capture { state =>
            Async.run(isolate.isolate(state, v)).map(_.mask.map(fiber => isolate.restore(fiber.get)))
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
    def timeout[E, A: Flat, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(after: Duration)(v: => A < (Abort[E] & Async & S))(using frame: Frame): A < (Abort[E | Timeout] & Async & S) =
        isolate.capture { state =>
            Async.run(isolate.isolate(state, v)).map { task =>
                Clock.use { clock =>
                    IO.Unsafe {
                        val sleepFiber = clock.unsafe.sleep(after)
                        sleepFiber.onComplete(_ => discard(task.unsafe.interrupt(Result.Failure(Timeout()))))
                        task.unsafe.onComplete(_ => discard(sleepFiber.interrupt()))
                        isolate.restore(task.get)
                    }
                }
            }
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
    def race[E, A: Flat, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(seq: Seq[A < (Abort[E] & Async & S)])(
        using frame: Frame
    ): A < (Abort[E] & Async & S) =
        if seq.isEmpty then seq(0)
        else
            isolate.capture { state =>
                Fiber.race(seq.map(isolate.isolate(state, _))).map(fiber => isolate.restore(fiber.get))
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
    def race[E, A: Flat, S](
        using Isolate.Stateful[S, Abort[E] & Async]
    )(
        first: A < (Abort[E] & Async & S),
        rest: A < (Abort[E] & Async & S)*
    )(
        using frame: Frame
    ): A < (Abort[E] & Async & S) =
        race[E, A, S](first +: rest)

    /** Concurrently executes two or more computations and collects their successful results.
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
    def gather[E, A: Flat, S](
        using Isolate.Stateful[S, Abort[E] & Async]
    )(
        first: A < (Abort[E] & Async & S),
        rest: A < (Abort[E] & Async & S)*
    )(using frame: Frame): Chunk[A] < (Abort[E] & Async & S) =
        gather(first +: rest)

    /** Concurrently executes two or more and collects up to `max` successful results.
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
    def gather[E, A: Flat, S](
        using Isolate.Stateful[S, Abort[E] & Async]
    )(max: Int)(
        first: A < (Abort[E] & Async & S),
        rest: A < (Abort[E] & Async & S)*
    )(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S) =
        gather(max)(first +: rest)

    /** Concurrently executes computations and collects successful results.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
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
    def gather[E, A: Flat, S](
        using Isolate.Stateful[S, Abort[E] & Async]
    )(seq: Seq[A < (Abort[E] & Async & S)])(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S) =
        gather(seq.size)(seq)

    /** Concurrently executes computations and collects up to `max` successful results.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
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
    def gather[E, A: Flat, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(max: Int)(seq: Seq[A < (Abort[E] & Async & S)])(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S) =
        isolate.capture { state =>
            Fiber.gather(max)(seq.map(isolate.isolate(state, _)))
                .map(_.use(chunk => Kyo.collect(chunk.map(isolate.restore))))
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
    def parallelUnbounded[E, A: Flat, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(seq: Seq[A < (Abort[E] & Async & S)])(using frame: Frame): Seq[A] < (Abort[E] & Async & S) =
        seq.size match
            case 0 => Seq.empty
            case 1 => seq(0).map(Seq(_))
            case _ =>
                isolate.capture { state =>
                    Fiber.parallelUnbounded(seq.map(isolate.isolate(state, _)))
                        .map(_.use(r => Kyo.collect(r.map(isolate.restore))))
                }
        end match
    end parallelUnbounded

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
    def parallel[E, A: Flat, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(parallelism: Int)(seq: Seq[A < (Abort[E] & Async & S)])(
        using frame: Frame
    ): Seq[A] < (Abort[E] & Async & S) =
        seq.size match
            case 0 => Seq.empty
            case 1 => seq(0).map(Seq(_))
            case n =>
                isolate.capture { state =>
                    Fiber.parallel(parallelism)(seq.map(isolate.isolate(state, _)))
                        .map(_.use(r => Kyo.collect(r.map(isolate.restore))))
                }

    inline def parallel[E, A1: Flat, A2: Flat, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2) < (Abort[E] & Async & S) =
        parallelUnbounded(Seq(v1, v2))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2])
        }

    inline def parallel[E, A1: Flat, A2: Flat, A3: Flat, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3) < (Abort[E] & Async & S) =
        parallelUnbounded(Seq(v1, v2, v3))(using Flat.unsafe.bypass).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3])
        }

    inline def parallel[E, A1: Flat, A2: Flat, A3: Flat, A4: Flat, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S),
        v4: A4 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4) < (Abort[E] & Async & S) =
        parallelUnbounded(Seq(v1, v2, v3, v4))(using Flat.unsafe.bypass).map { s =>
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
        val x = useResult(v)(_.fold(f, Abort.fail, Abort.panic))
        reduce(x)
    end use

    private[kyo] def getResult[E, A](v: IOPromise[E, A])(using Frame): Result[E, A] < Async =
        ArrowEffect.suspend[A](Tag[Join], v).asInstanceOf[Result[E, A] < Async]

    private[kyo] def useResult[E, A, B, S](v: IOPromise[E, A])(f: Result[E, A] => B < S)(using Frame): B < (S & Async) =
        ArrowEffect.suspendWith[A](Tag[Join], v)(f)

end Async
