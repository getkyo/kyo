package kyo

import kyo.Result.Panic
import kyo.Tag
import kyo.internal.AsyncPlatformSpecific
import kyo.kernel.*
import kyo.scheduler.*
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.NotGiven
import scala.util.control.NonFatal

/** Asynchronous computation effect.
  *
  * While Sync handles pure effect suspension, Async provides the complete toolkit for concurrent programming - managing fibers, scheduling,
  * and execution control. It includes Sync in its effect set, making it a unified solution for both synchronous and asynchronous
  * operations.
  *
  * This separation, enabled by Kyo's algebraic effect system, is reflected in the codebase's design: the presence of Async in pending
  * effects signals that a computation may park or involve fiber scheduling, contrasting with Sync-only operations that run to completion.
  *
  * Most application code can work exclusively with Async, with the Sync/Async distinction becoming relevant primarily in library code or
  * performance-critical sections where precise control over execution characteristics is needed.
  *
  * Note: For collection operations, Async provides concurrent execution variants of the `Kyo` companion object sequential operations. These
  * operations use a bounded concurrency model to prevent resource exhaustion, defaulting to twice the number of available processors. This
  * can be overridden per operation when needed. On platforms like the JVM, these operations may execute in parallel using multiple threads.
  * On single-threaded platforms like JavaScript, operations will be interleaved concurrently but not execute in parallel.
  *
  * This effect includes Sync in its effect set to handle both async and sync execution in a single effect.
  *
  * @see
  *   [[Async.run]] for executing asynchronous computations and obtaining a Fiber
  * @see
  *   [[Fiber]] for the low-level primitive that powers async execution (primarily for library authors)
  * @see
  *   [[Async.race]] for racing multiple computations and getting the first result
  * @see
  *   [[Async.gather]] for concurrently executing multiple computations and collecting their sucessful results
  * @see
  *   [[Async.foreach]], [[Async.collect]], and their variants for concurrent collection processing with bounded concurrency
  */
opaque type Async <: (Sync & Async.Join) = Async.Join & Sync

object Async extends AsyncPlatformSpecific:

    /** Default concurrency level for collection operations.
      *
      * This value determines the maximum number of concurrent operations that can run in parallel for collection processing methods like
      * foreach, collect, and their variants. It defaults to twice the number of available processors.
      *
      * This default can be overridden in two ways:
      *   1. Per operation by passing an explicit concurrency parameter
      *   2. Globally by setting the "kyo.async.concurrency.default" system property
      *
      * Example of setting the system property:
      * ```
      * java -Dkyo.async.concurrency.default=4 MyApp
      * ```
      *
      * Consider adjusting this based on:
      *   - Nature of operations (CPU vs Sync bound)
      *   - Available system resources
      *   - Specific performance requirements
      *
      * Note: This only affects collection processing methods. Operations like race and gather run with unbounded concurrency.
      */
    val defaultConcurrency =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        Sync.Unsafe.evalOrThrow(System.property[Int]("kyo.async.concurrency.default", Runtime.getRuntime().availableProcessors() * 2))
    end defaultConcurrency

    /** Convenience method for suspending computations in an Async effect.
      *
      * While Sync is specifically designed to suspend side effects without handling asynchronicity, Async provides both side effect
      * suspension and asynchronous execution capabilities (fibers, async scheduling). Since Async includes Sync in its effect set, this
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
        Sync(v)

    /** Runs an asynchronous computation and returns a Fiber.
      *
      * @param v
      *   The computation to run
      * @return
      *   A Fiber representing the running computation
      */
    def run[E, A, S](
        using isolate: Isolate.Contextual[S, Sync]
    )(v: => A < (Abort[E] & Async & S))(using Frame): Fiber[E, A] < (Sync & S) =
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
    def runAndBlock[E, A, S](
        using isolate: Isolate.Contextual[S, Sync]
    )(timeout: Duration)(v: => A < (Abort[E] & Async & S))(
        using frame: Frame
    ): A < (Abort[E | Timeout] & Sync & S) =
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
    def mask[E, A, S](
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
    def never[A](using Frame): A < Async = Fiber.never[Nothing, A].get

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
    def timeout[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(after: Duration)(v: => A < (Abort[E] & Async & S))(using frame: Frame): A < (Abort[E | Timeout] & Async & S) =
        if after == Duration.Zero then Abort.fail(Timeout(Present(after)))
        else if !after.isFinite then v
        else
            isolate.capture { state =>
                Async.run(isolate.isolate(state, v)).map { task =>
                    Clock.use { clock =>
                        Sync.Unsafe {
                            val sleepFiber = clock.unsafe.sleep(after)
                            sleepFiber.onComplete(_ => discard(task.unsafe.interrupt(Result.Failure(Timeout(Present(after))))))
                            task.unsafe.onComplete(_ => discard(sleepFiber.interrupt()))
                            isolate.restore(task.get)
                        }
                    }
                }
            }
        end if
    end timeout

    /** Races multiple computations and returns the result of the first successful computation to complete. When one computation succeeds,
      * all other computations are interrupted.
      *
      * WARNING: Executes all computations concurrently without bounds. Use with caution on large sequences to avoid resource exhaustion. On
      * platforms supporting parallel execution (like JVM), computations may run in parallel.
      *
      * Note: Unlike `raceFirst`, this method will only complete when a successful computation completes. If all computations fail, it will
      * wait for the last failure. If some fail while others never complete, it will wait indefinitely for a success.
      *
      * @param seq
      *   The sequence of computations to race
      * @return
      *   The result of the first successful computation to complete
      */
    def race[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A < (Abort[E] & Async & S)])(
        using frame: Frame
    ): A < (Abort[E] & Async & S) =
        require(iterable.nonEmpty, "Can't race an empty collection.")
        isolate.capture { state =>
            Fiber.race(iterable.map(isolate.isolate(state, _))).map(fiber => isolate.restore(fiber.get))
        }
    end race

    /** Races two or more computations and returns the result of the first successful computation to complete.
      *
      * Note: Unlike `race`, this method will only complete when a successful computation completes. If all computations fail, it will wait
      * for the last failure. If some fail while others never complete, it will wait indefinitely for a success.
      *
      * @param first
      *   The first computation
      * @param rest
      *   The rest of the computations
      * @return
      *   The result of the first successful computation to complete
      */
    def race[E, A, S](
        using Isolate.Stateful[S, Abort[E] & Async]
    )(
        first: A < (Abort[E] & Async & S),
        rest: A < (Abort[E] & Async & S)*
    )(
        using frame: Frame
    ): A < (Abort[E] & Async & S) =
        race[E, A, S](first +: rest)

    /** Races multiple computations and returns the result of the first to complete. When one computation completes, all other computations
      * are interrupted.
      *
      * WARNING: Executes all computations concurrently without bounds. Use with caution on large sequences to avoid resource exhaustion. On
      * platforms supporting parallel execution (like JVM), computations may run in parallel.
      *
      * Note: Unlike `race`, this method will complete as soon as any computation completes, regardless of whether it succeeded or failed.
      * For example, if one computation fails while another never completes, this method will return the failure, interrupting the other
      * computation(s).
      *
      * @param seq
      *   The sequence of computations to race
      * @return
      *   The result of the first computation to complete
      */
    def raceFirst[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A < (Abort[E] & Async & S)])(
        using frame: Frame
    ): A < (Abort[E] & Async & S) =
        require(iterable.nonEmpty, "Can't race an empty collection.")
        isolate.capture { state =>
            Fiber.raceFirst(iterable.map(isolate.isolate(state, _))).map(fiber => isolate.restore(fiber.get))
        }
    end raceFirst

    /** Races two or more computations and returns the result of the first to complete. When one computation completes, all other
      * computations are interrupted.
      *
      * Note: Unlike `race`, this method will complete as soon as any computation completes, regardless of whether it succeeded or failed.
      * For example, if one computation fails while another never completes, this method will return the failure, interrupting the other
      * computation(s).
      *
      * @param first
      *   The first computation
      * @param rest
      *   The rest of the computations
      * @return
      */
    def raceFirst[E, A, S](
        using Isolate.Stateful[S, Abort[E] & Async]
    )(
        first: A < (Abort[E] & Async & S),
        rest: A < (Abort[E] & Async & S)*
    )(
        using frame: Frame
    ): A < (Abort[E] & Async & S) =
        raceFirst[E, A, S](first +: rest)

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
    def gather[E, A, S](
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
    def gather[E, A, S](
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
      * WARNING: Executes all computations concurrently without bounds. Use with caution on large sequences to avoid resource exhaustion. On
      * platforms supporting parallel execution (like JVM), computations may run in parallel.
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
    def gather[E, A, S](
        using Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A < (Abort[E] & Async & S)])(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S) =
        gather(iterable.size)(iterable)

    /** Concurrently executes computations and collects up to `max` successful results.
      *
      * WARNING: Executes all computations concurrently without bounds. Use with caution on large sequences to avoid resource exhaustion. On
      * platforms supporting parallel execution (like JVM), computations may run in parallel.
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
    def gather[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(max: Int)(iterable: Iterable[A < (Abort[E] & Async & S)])(
        using frame: Frame
    ): Chunk[A] < (Abort[E] & Async & S) =
        isolate.capture { state =>
            Fiber.gather(max)(iterable.map(isolate.isolate(state, _)))
                .map(_.use(chunk => Kyo.collectAll(chunk.map(isolate.restore))))
        }

    /** Executes a sequence of computations with indexed access, using bounded concurrency.
      *
      * @param iterable
      *   The collection of elements to process
      * @param concurrency
      *   Maximum number of concurrent computations (defaults to [[defaultConcurrency]])
      * @param f
      *   Function that takes an index and element, returning a computation
      * @return
      *   Chunk containing results in the original sequence order
      */
    def foreachIndexed[E, A, B, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A], concurrency: Int = defaultConcurrency)(f: (Int, A) => B < (Abort[E] & Async & S))(using
        Frame
    ): Chunk[B] < (Abort[E] & Async & S) =
        if concurrency <= 1 then
            Kyo.foreachIndexed(iterable.toSeq)(f)
        else
            iterable.size match
                case 0 => Chunk.empty
                case 1 => f(0, iterable.head).map(Chunk(_))
                case size if size <= concurrency =>
                    isolate.capture { state =>
                        Fiber.foreachIndexed(iterable)((idx, v) => isolate.isolate(state, f(idx, v)))
                            .map(_.use(r => Kyo.foreach(r)(isolate.restore)))
                    }
                case size =>
                    isolate.capture { state =>
                        val groupSize = Math.ceil(size.toDouble / Math.max(1, concurrency)).toInt
                        Fiber.foreachIndexed(iterable.grouped(groupSize).toSeq)((idx, group) =>
                            Kyo.foreachIndexed(group.toSeq)((idx2, v) => isolate.isolate(state, f(idx + idx2, v)))
                        ).map(_.use(r => Kyo.foreach(r.flattenChunk)(isolate.restore)))
                    }

    /** Executes a sequence of computations using bounded concurrency.
      *
      * @param iterable
      *   The collection of elements to process
      * @param concurrency
      *   Maximum number of concurrent computations (defaults to [[defaultConcurrency]])
      * @param f
      *   Function that processes each element
      * @return
      *   Chunk containing results in the original sequence order
      */
    def foreach[E, A, B, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A], concurrency: Int = defaultConcurrency)(
        f: A => B < (Abort[E] & Async & S)
    )(using Frame): Chunk[B] < (Abort[E] & Async & S) =
        foreachIndexed(iterable, concurrency)((_, v) => f(v))

    /** Executes a sequence of computations in parallel, discarding the results.
      *
      * @param iterable
      *   The collection of elements to process
      * @param concurrency
      *   Maximum number of concurrent computations (defaults to defaultConcurrency)
      * @param f
      *   Function that processes each element
      */
    def foreachDiscard[E, A, B, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A], concurrency: Int = defaultConcurrency)(
        f: A => B < (Abort[E] & Async & S)
    )(using Frame): Unit < (Abort[E] & Async & S) =
        foreach(iterable, concurrency)(f).unit

    /** Filters elements from a sequence using bounded concurrency.
      *
      * @param iterable
      *   The collection to filter
      * @param concurrency
      *   Maximum number of concurrent predicate evaluations (defaults to [[defaultConcurrency]])
      * @param f
      *   Predicate function returning true for elements to keep
      * @return
      *   Chunk containing only elements that satisfied the predicate
      */
    def filter[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A], concurrency: Int = defaultConcurrency)(
        f: A => Boolean < (Abort[E] & Async & S)
    )(using Frame): Chunk[A] < (Abort[E] & Async & S) =
        collect(iterable, concurrency)(v => f(v).map(Maybe.when(_)(v)))

    /** Transforms and filters elements from a sequence using bounded concurrency.
      *
      * @param iterable
      *   The collection to process
      * @param concurrency
      *   Maximum number of concurrent evaluations (defaults to [[defaultConcurrency]])
      * @param f
      *   Function that returns Some with transformed value for elements to keep, None for elements to filter out
      * @return
      *   Chunk containing transformed values for elements that weren't filtered
      */
    def collect[E, A, B, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A], concurrency: Int = defaultConcurrency)(
        f: A => Maybe[B] < (Abort[E] & Async & S)
    )(using Frame): Chunk[B] < (Abort[E] & Async & S) =
        foreach(iterable, concurrency)(f).map(_.flatten)

    /** Executes a sequence of computations using bounded concurrency.
      *
      * @param iterable
      *   The collection of computations to execute
      * @param concurrency
      *   Maximum number of concurrent computations (defaults to [[defaultConcurrency]])
      * @return
      *   Chunk containing results in the original sequence order
      */
    def collectAll[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A < (Abort[E] & Async & S)], concurrency: Int = defaultConcurrency)(using
        Frame
    ): Chunk[A] < (Abort[E] & Async & S) =
        foreach(iterable, concurrency)(identity)

    /** Executes a sequence of computations in parallel, discarding their results.
      *
      * @param iterable
      *   The collection of computations to execute
      * @param concurrency
      *   Maximum number of concurrent computations (defaults to defaultConcurrency)
      */
    def collectAllDiscard[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(iterable: Iterable[A < (Abort[E] & Async & S)], concurrency: Int = defaultConcurrency)(using Frame): Unit < (Abort[E] & Async & S) =
        foreachDiscard(iterable, concurrency)(identity)

    /** Repeats a computation n times in parallel.
      *
      * @param n
      *   Number of times to repeat the computation
      * @param concurrency
      *   Maximum number of concurrent computations (defaults to defaultConcurrency)
      * @param f
      *   The computation to repeat
      * @return
      *   Chunk containing results of all iterations
      */
    def fill[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(n: Int, concurrency: Int = defaultConcurrency)(
        f: => A < (Abort[E] & Async & S)
    )(using Frame): Chunk[A] < (Abort[E] & Async & S) =
        fillIndexed(n, concurrency)(_ => f)

    /** Repeats a computation n times in parallel with index access.
      *
      * Similar to `fill`, but provides the current index to the computation function. This is useful when the computation needs to know
      * which iteration it's processing.
      *
      * @param n
      *   Number of times to repeat the computation
      * @param concurrency
      *   Maximum number of concurrent computations (defaults to [[defaultConcurrency]])
      * @param f
      *   Function that takes the current index (0 to n-1) and returns a computation
      * @return
      *   Chunk containing results of all iterations in index order
      */
    def fillIndexed[E, A, S](
        using isolate: Isolate.Stateful[S, Abort[E] & Async]
    )(n: Int, concurrency: Int = defaultConcurrency)(
        f: Int => A < (Abort[E] & Async & S)
    )(using Frame): Chunk[A] < (Abort[E] & Async & S) =
        foreach(0 until n, concurrency)(f)

    /** Executes two computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2), 2).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2])
        }

    /** Executes three computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, A3, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2, v3), 3).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3])
        }

    /** Executes four computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, A3, A4, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S),
        v4: A4 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2, v3, v4), 4).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3], s(3).asInstanceOf[A4])
        }

    /** Executes five computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, A3, A4, A5, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S),
        v4: A4 < (Abort[E] & Async & S),
        v5: A5 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4, A5) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2, v3, v4, v5), 5).map { s =>
            (s(0).asInstanceOf[A1], s(1).asInstanceOf[A2], s(2).asInstanceOf[A3], s(3).asInstanceOf[A4], s(4).asInstanceOf[A5])
        }

    /** Executes six computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, A3, A4, A5, A6, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S),
        v4: A4 < (Abort[E] & Async & S),
        v5: A5 < (Abort[E] & Async & S),
        v6: A6 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4, A5, A6) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2, v3, v4, v5, v6), 6).map { s =>
            (
                s(0).asInstanceOf[A1],
                s(1).asInstanceOf[A2],
                s(2).asInstanceOf[A3],
                s(3).asInstanceOf[A4],
                s(4).asInstanceOf[A5],
                s(5).asInstanceOf[A6]
            )
        }

    /** Executes seven computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, A3, A4, A5, A6, A7, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S),
        v4: A4 < (Abort[E] & Async & S),
        v5: A5 < (Abort[E] & Async & S),
        v6: A6 < (Abort[E] & Async & S),
        v7: A7 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4, A5, A6, A7) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2, v3, v4, v5, v6, v7), 7).map { s =>
            (
                s(0).asInstanceOf[A1],
                s(1).asInstanceOf[A2],
                s(2).asInstanceOf[A3],
                s(3).asInstanceOf[A4],
                s(4).asInstanceOf[A5],
                s(5).asInstanceOf[A6],
                s(6).asInstanceOf[A7]
            )
        }

    /** Executes eight computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, A3, A4, A5, A6, A7, A8, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S),
        v4: A4 < (Abort[E] & Async & S),
        v5: A5 < (Abort[E] & Async & S),
        v6: A6 < (Abort[E] & Async & S),
        v7: A7 < (Abort[E] & Async & S),
        v8: A8 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4, A5, A6, A7, A8) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2, v3, v4, v5, v6, v7, v8), 8).map { s =>
            (
                s(0).asInstanceOf[A1],
                s(1).asInstanceOf[A2],
                s(2).asInstanceOf[A3],
                s(3).asInstanceOf[A4],
                s(4).asInstanceOf[A5],
                s(5).asInstanceOf[A6],
                s(6).asInstanceOf[A7],
                s(7).asInstanceOf[A8]
            )
        }

    /** Executes nine computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, A3, A4, A5, A6, A7, A8, A9, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S),
        v4: A4 < (Abort[E] & Async & S),
        v5: A5 < (Abort[E] & Async & S),
        v6: A6 < (Abort[E] & Async & S),
        v7: A7 < (Abort[E] & Async & S),
        v8: A8 < (Abort[E] & Async & S),
        v9: A9 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4, A5, A6, A7, A8, A9) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2, v3, v4, v5, v6, v7, v8, v9), 9).map { s =>
            (
                s(0).asInstanceOf[A1],
                s(1).asInstanceOf[A2],
                s(2).asInstanceOf[A3],
                s(3).asInstanceOf[A4],
                s(4).asInstanceOf[A5],
                s(5).asInstanceOf[A6],
                s(6).asInstanceOf[A7],
                s(7).asInstanceOf[A8],
                s(8).asInstanceOf[A9]
            )
        }

    /** Executes ten computations in parallel and returns their results as a tuple.
      */
    inline def zip[E, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, S](
        v1: A1 < (Abort[E] & Async & S),
        v2: A2 < (Abort[E] & Async & S),
        v3: A3 < (Abort[E] & Async & S),
        v4: A4 < (Abort[E] & Async & S),
        v5: A5 < (Abort[E] & Async & S),
        v6: A6 < (Abort[E] & Async & S),
        v7: A7 < (Abort[E] & Async & S),
        v8: A8 < (Abort[E] & Async & S),
        v9: A9 < (Abort[E] & Async & S),
        v10: A10 < (Abort[E] & Async & S)
    )(
        using frame: Frame
    ): (A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) < (Abort[E] & Async & S) =
        collectAll(Seq(v1, v2, v3, v4, v5, v6, v7, v8, v9, v10), 10).map { s =>
            (
                s(0).asInstanceOf[A1],
                s(1).asInstanceOf[A2],
                s(2).asInstanceOf[A3],
                s(3).asInstanceOf[A4],
                s(4).asInstanceOf[A5],
                s(5).asInstanceOf[A6],
                s(6).asInstanceOf[A7],
                s(7).asInstanceOf[A8],
                s(8).asInstanceOf[A9],
                s(9).asInstanceOf[A10]
            )
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
      *   A nested computation that returns the memoized result
      */
    def memoize[A, S](v: A < S)(using Frame): A < (S & Async) < Sync =
        Sync.Unsafe {
            val ref = AtomicRef.Unsafe.init(Maybe.empty[Promise.Unsafe[Nothing, A]])
            @tailrec def loop(): A < (S & Async) =
                ref.get() match
                    case Present(v) => v.safe.get
                    case Absent =>
                        val promise = Promise.Unsafe.init[Nothing, A]()
                        if ref.compareAndSet(Absent, Present(promise)) then
                            Abort.run(v).map { r =>
                                Sync.Unsafe {
                                    if !r.isSuccess then
                                        ref.set(Absent)
                                    promise.completeDiscard(r)
                                    Abort.get(r)
                                }
                            }.handle(Sync.ensure {
                                Sync.Unsafe {
                                    if !promise.done() then
                                        ref.set(Absent)
                                }
                            })
                        else
                            loop()
                        end if
            Kyo.lift(Sync(loop()))
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

    private[kyo] def get[E, A](v: IOPromise[? <: E, ? <: A])(
        using
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): A < (reduce.SReduced & Async) =
        use(v)(identity)

    private[kyo] def use[E, A, B, S](v: IOPromise[? <: E, ? <: A])(f: A => B < S)(
        using
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): B < (S & reduce.SReduced & Async) =
        val x = useResult(v)(_.fold(f, Abort.fail, Abort.panic))
        reduce(x)
    end use

    sealed trait Join extends ArrowEffect[IOPromise[?, *], Result[Nothing, *]]

    private[kyo] def getResult[E, A](v: IOPromise[E, A])(using Frame): Result[E, A] < Async =
        ArrowEffect.suspend[A](Tag[Join], v)

    private[kyo] def useResult[E, A, B, S](v: IOPromise[E, A])(f: Result[E, A] => B < S)(using Frame): B < (S & Async) =
        ArrowEffect.suspendWith[A](Tag[Join], v)(f)

end Async
