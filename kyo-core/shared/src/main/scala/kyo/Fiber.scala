package kyo

export Fiber.Promise
import java.lang.invoke.VarHandle
import java.util.Arrays
import kyo.Result.Panic
import kyo.internal.FiberPlatformSpecific
import kyo.kernel.*
import kyo.kernel.internal.*
import kyo.scheduler.*
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.NotGiven
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

/** A low-level primitive for asynchronous computation control.
  *
  * Fiber is the underlying mechanism that powers Kyo's concurrent execution model. It provides fine-grained control over asynchronous
  * computations, including lifecycle management, interruption handling, and completion callbacks.
  *
  * WARNING: This is a low-level API primarily intended for library authors and system integrations. For application-level concurrent
  * programming, use the [[Async]] effect instead, which provides a safer, more structured interface.
  *
  * Key capabilities:
  *   - Lifecycle control (completion, interruption)
  *   - Callback registration for completion and interruption
  *   - Result transformation and composition
  *   - Integration with external async systems (e.g., Future)
  *
  * @see
  *   [[Async]] for the high-level, structured concurrency API
  * @see
  *   [[Promise]] for creating and completing Fibers manually
  * @see
  *   [[Fiber.Unsafe]] for low-level operations requiring [[AllowUnsafe]]
  */
opaque type Fiber[+E, +A] = IOPromise[E, A]

object Fiber extends FiberPlatformSpecific:

    inline given [E, A]: Flat[Fiber[E, A]] = Flat.unsafe.bypass

    private val _unit  = IOPromise(Result.unit).mask()
    private val _never = IOPromise[Nothing, Unit]().mask()

    private[kyo] inline def fromTask[E, A](inline ioTask: IOTask[?, E, A]): Fiber[E, A] = ioTask

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
    def never[E, A]: Fiber[E, A] = _never.asInstanceOf[Fiber[E, A]]

    /** Creates a successful Fiber.
      *
      * @param v
      *   The value to complete the Fiber with
      * @return
      *   A Fiber that completes successfully with the given value
      */
    def success[E, A](v: A): Fiber[E, A] = result(Result.succeed(v))

    /** Creates a failed Fiber.
      *
      * @param ex
      *   The error to fail the Fiber with
      * @return
      *   A Fiber that fails with the given error
      */
    def fail[E, A](ex: E): Fiber[E, A] = result(Result.fail(ex))

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
      * This method allows integration of existing Future-based code with Kyo's Fiber system. It handles successful completion, expected
      * failures (of type E), and unexpected failures.
      *
      * @param f
      *   The Future to convert into a Fiber
      * @tparam E
      *   The expected error type that the Future might fail with. Use Throwable if you don't need to catch specific exceptions.
      * @tparam A
      *   The type of the successful result
      * @return
      *   A Fiber that completes with the result of the Future
      */
    def fromFuture[A](future: => Future[A])(using frame: Frame): Fiber[Throwable, A] < IO =
        IO.Unsafe(Unsafe.fromFuture(future))

    private def result[E, A](result: Result[E, A]): Fiber[E, A] = IOPromise(result)

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
        def onComplete[E2 >: E, A2 >: A](f: Result[E2, A2] => Any < IO)(using Frame): Unit < IO =
            import AllowUnsafe.embrace.danger
            IO(self.onComplete(r => IO.Unsafe.evalOrThrow(f(r).unit)))

        /** Registers a callback to be called when the Fiber is interrupted.
          *
          * This method allows you to specify a callback that will be executed if the Fiber is interrupted. The callback receives the Panic
          * value that caused the interruption.
          *
          * @param f
          *   The callback function to be executed on interruption
          * @return
          *   A unit value wrapped in IO, representing the registration of the callback
          */
        def onInterrupt(f: Result.Error[E] => Any < IO)(using Frame): Unit < IO =
            import AllowUnsafe.embrace.danger
            IO(self.onInterrupt(r => IO.Unsafe.evalOrThrow(f(r).unit)))

        /** Blocks until the Fiber completes or the timeout is reached.
          *
          * @param timeout
          *   The maximum duration to wait
          * @return
          *   The Result of the Fiber, or a Timeout error
          */
        def block(timeout: Duration)(using Frame): Result[E | Timeout, A] < IO =
            Clock.deadline(timeout).map(d => self.block(d.unsafe))

        /** Converts the Fiber to a Future.
          *
          * @return
          *   A Future that completes with the result of the Fiber
          */
        def toFuture(using E <:< Throwable, Frame): Future[A] < IO =
            IO.Unsafe(Unsafe.toFuture(self)())

        /** Maps the result of the Fiber.
          *
          * @param f
          *   The function to apply to the Fiber's result
          * @return
          *   A new Fiber with the mapped result
          */
        def map[B: Flat](f: A => B < IO)(using Frame): Fiber[E, B] < IO =
            IO.Unsafe(Unsafe.map(self)((r => IO.Unsafe.evalOrThrow(f(r)))))

        /** Flat maps the result of the Fiber.
          *
          * @param f
          *   The function to apply to the Fiber's result
          * @return
          *   A new Fiber with the flat mapped result
          */
        def flatMap[E2, B](f: A => Fiber[E2, B] < IO)(using Frame): Fiber[E | E2, B] < IO =
            IO.Unsafe(Unsafe.flatMap(self)(r => IO.Unsafe.evalOrThrow(f(r))))

        /** Maps the Result of the Fiber using the provided function.
          *
          * This method allows you to transform both the error and success types of the Fiber's result. It's useful when you need to modify
          * the error type or perform a more complex transformation on the success value that may also produce a new error type.
          *
          * @param f
          *   The function to apply to the Fiber's Result. It should take a Result[E, A] and return a Result[E2, B].
          * @return
          *   A new Fiber with the mapped Result
          */
        def mapResult[E2, B: Flat](f: Result[E, A] => Result[E2, B] < IO)(using Frame): Fiber[E2, B] < IO =
            IO.Unsafe(Unsafe.mapResult(self)(r => IO.Unsafe.evalOrThrow(f(r))))

        /** Creates a new Fiber that runs with interrupt masking.
          *
          * This method returns a new Fiber that, when executed, will not propagate interrupts to previous "steps" of the computation. The
          * returned Fiber can still be interrupted, but the interruption won't affect the masked portion. This is useful for ensuring that
          * critical operations or cleanup tasks complete even if an interrupt occurs.
          *
          * @return
          *   A new Fiber that runs with interrupt masking
          */
        def mask(using Frame): Fiber[E, A] < IO = IO.Unsafe(Unsafe.mask(self)())

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
        def interrupt(error: Result.Error[E])(using Frame): Boolean < IO =
            IO(self.interrupt(error))

        /** Interrupts the Fiber with a specific error, discarding the return value.
          *
          * @param error
          *   The error to interrupt the Fiber with
          */
        def interruptDiscard(error: Result.Error[E])(using Frame): Unit < IO =
            IO(discard(self.interrupt(error)))

        def unsafe: Fiber.Unsafe[E, A] = self

    end extension

    case class Interrupted(at: Frame)
        extends RuntimeException("Fiber interrupted at " + at.position.show)
        with NoStackTrace:
        override def getCause() = null
    end Interrupted

    /** Races multiple Fibers and returns a Fiber that completes with the result of the first to complete. When one Fiber completes, all
      * other Fibers are interrupted.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
      *
      * @param seq
      *   The sequence of Fibers to race
      * @return
      *   A Fiber that completes with the result of the first Fiber to complete
      */
    private[kyo] def race[E, A: Flat, S](
        using isolate: Isolate.Contextual[S, IO]
    )(seq: Iterable[A < (Abort[E] & Async & S)])(using frame: Frame, safepoint: Safepoint): Fiber[E, A] < (IO & S) =
        IO.Unsafe {
            class State extends IOPromise[E, A] with Function1[Result[E, A], Unit]:
                val pending = AtomicInt.Unsafe.init(seq.size)
                def apply(result: Result[E, A]): Unit =
                    val last = pending.decrementAndGet() == 0
                    result.foldError(v => completeDiscard(Result.succeed(v)), e => if last then completeDiscard(e))
                end apply
            end State
            val state = new State
            import state.*
            isolate.runInternal { (trace, context) =>
                IO {
                    inline def interruptPanic = Result.Panic(Fiber.Interrupted(frame))
                    foreach(seq) { (_, v) =>
                        val fiber = IOTask(v, safepoint.copyTrace(trace), context)
                        state.onComplete(_ => discard(fiber.interrupt(interruptPanic)))
                        fiber.onComplete(state)
                    }
                    state
                }
            }
        }

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
      *   Fiber containing successful results as a Chunk (size <= max)
      */
    private[kyo] def gather[E, A: Flat, S](
        using isolate: Isolate.Contextual[S, IO]
    )(max: Int)(seq: Iterable[A < (Abort[E] & Async & S)])(
        using
        frame: Frame,
        safepoint: Safepoint
    ): Fiber[E, Chunk[A]] < (IO & S) =
        val total = seq.size
        if total == 0 || max <= 0 then Fiber.success(Chunk.empty)
        else
            IO.Unsafe {
                class State extends IOPromise[E, Chunk[A]] with Function2[Int, Result[E, A], Unit]:
                    val results = new Array[AnyRef](max)

                    // Helper array to store original indices to maintain ordering
                    // Initialized to Int.MaxValue to handle partial results
                    val indices = new Array[Int](max)
                    Arrays.fill(indices, Int.MaxValue)

                    // Packed representation to avoid allocations and ensure atomicity
                    // - lower 32 bits  => successful results count (ok)
                    // - higher 32 bits => failed results count (nok)
                    val packed = AtomicLong.Unsafe.init(0)

                    def apply(idx: Int, result: Result[E, A]): Unit =
                        @tailrec def loop(): Unit =
                            // Atomically update both ok/nok counters using CAS
                            val p   = packed.get()
                            val ok  = (p & 0xffffffffL) + (if result.isSuccess then 1 else 0)
                            val nok = (p >>> 32) + (if result.isFailure then 1 else 0)
                            val np  = (nok << 32) | ok
                            if !packed.compareAndSet(p, np) then
                                // CAS failed, retry the update
                                loop()
                            else
                                val okInt = ok.toInt
                                result match
                                    case Result.Success(v) =>
                                        if ok <= max then
                                            // Store successful result and its original index for ordering
                                            indices(okInt - 1) = idx
                                            results(okInt - 1) = v.asInstanceOf[AnyRef]
                                            discard(VarHandle.storeStoreFence())
                                    case result: Result.Error[?] =>
                                        if ok == 0 && ok + nok == total then
                                            // If we have no successful results and all computations have completed,
                                            // propagate the last encountered error since there's nothing else to return
                                            completeDiscard(result)
                                end match
                                // Complete if we have max successes or all results are in
                                if ok > 0 && (ok == max || ok + nok == total) then
                                    val size = okInt.min(max)

                                    // Handle race condition
                                    waitForResults(results, size)

                                    // Restore original ordering but limit size since later
                                    // results might still arrive and we want to avoid races
                                    quickSort(indices, results, size)

                                    // Limit final result to max successful results
                                    completeDiscard(Result.succeed(Chunk.fromNoCopy(results).take(size)))
                                end if
                            end if
                        end loop
                        loop()
                    end apply
                end State
                val state = new State
                import state.*
                isolate.runInternal { (trace, context) =>
                    IO {
                        inline def interruptPanic = Result.Panic(Fiber.Interrupted(frame))
                        foreach(seq) { (idx, v) =>
                            val fiber = IOTask(v, safepoint.copyTrace(trace), context)
                            state.onComplete(_ => discard(fiber.interrupt(interruptPanic)))
                            fiber.onComplete(state(idx, _))
                        }
                        state
                    }
                }
            }
        end if
    end gather

    /** Busy waits until all results are present in the `_gather` array.
      *
      * This is necessary because there's a race condition between:
      *   - One fiber successfully incrementing the counter via CAS
      *   - Another fiber seeing the updated counter and trying to complete the gather
      *   - The first fiber hasn't written its result to the array yet
      *
      * Without this wait, we might start processing results before all fibers have finished writing their values to the array.
      */
    private def waitForResults(results: Array[AnyRef], size: Int): Unit =
        @tailrec def loop(i: Int): Unit =
            if i < size then
                if results(i) == null then
                    discard(VarHandle.loadLoadFence())
                    loop(0)
                else
                    loop(i + 1)
        loop(0)
    end waitForResults

    /** Custom quicksort that sorts both indices and results arrays together.
      *
      * Since `_gather` collects results as they complete but needs to preserve input sequence order, we sort before returning. This
      * specialized implementation avoids allocating tuples or wrapper objects by sorting both arrays in-place.
      */
    private[kyo] def quickSort(indices: Array[Int], results: Array[AnyRef], items: Int): Unit =

        def swap(i: Int, j: Int): Unit =
            val tempIdx = indices(i)
            indices(i) = indices(j)
            indices(j) = tempIdx

            val tempRes = results(i)
            results(i) = results(j)
            results(j) = tempRes
        end swap

        @tailrec def partitionLoop(low: Int, hi: Int, pivot: Int, i: Int, j: Int): Int =
            if j >= hi then
                swap(i, pivot)
                i
            else if indices(j) < indices(pivot) then
                swap(i, j)
                partitionLoop(low, hi, pivot, i + 1, j + 1)
            else
                partitionLoop(low, hi, pivot, i, j + 1)

        def partition(low: Int, hi: Int): Int =
            partitionLoop(low, hi, hi, low, low)

        def loop(low: Int, hi: Int): Unit =
            if low < hi then
                val p = partition(low, hi)
                loop(low, p - 1)
                loop(p + 1, hi)

        if items > 0 then
            loop(0, items - 1)
    end quickSort

    private[kyo] def foreachIndexed[A, B: Flat, E, S](
        using isolate: Isolate.Contextual[S, IO]
    )(seq: Iterable[A])(f: (Int, A) => B < (Abort[E] & Async & S))(
        using
        frame: Frame,
        safepoint: Safepoint
    ): Fiber[E, Chunk[B]] < (IO & S) =
        seq.size match
            case 0 => Fiber.success(Chunk.empty)
            case size =>
                IO.Unsafe {
                    class State extends IOPromise[E, Chunk[B]] with ((Int, Result[E, B]) => Unit):
                        val results = (new Array[Any](size)).asInstanceOf[Array[B]]
                        val pending = AtomicInt.Unsafe.init(size)
                        def apply(idx: Int, result: Result[E, B]): Unit =
                            result.foldError(
                                { value =>
                                    results(idx) = value
                                    if pending.decrementAndGet() == 0 then
                                        this.completeDiscard(Result.succeed(Chunk.fromNoCopy(results)))
                                },
                                this.interruptDiscard
                            )
                    end State
                    val state = new State
                    import state.*
                    isolate.runInternal { (trace, context) =>
                        foreach(seq) { (idx, v) =>
                            val fiber = IOTask(IO(f(idx, v)), safepoint.copyTrace(trace), context)
                            state.interrupts(fiber)
                            fiber.onComplete(state(idx, _))
                        }
                        state
                    }
                }

    opaque type Unsafe[+E, +A] = IOPromise[E, A]

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        inline given [E, A]: Flat[Unsafe[E, A]] = Flat.unsafe.bypass

        def init[E, A](result: Result[E, A])(using AllowUnsafe): Unsafe[E, A] = IOPromise(result)

        def fromFuture[A](f: => Future[A])(using AllowUnsafe): Unsafe[Throwable, A] =
            import scala.util.*
            val p = new IOPromise[Throwable, A] with (Try[A] => Unit):
                def apply(result: Try[A]) =
                    result match
                        case Success(v) =>
                            completeDiscard(Result.succeed(v))
                        case Failure(ex) =>
                            completeDiscard(Result.fail(ex))

            f.onComplete(p)(ExecutionContext.parasitic)
            p
        end fromFuture

        extension [E, A](self: Unsafe[E, A])
            def done()(using AllowUnsafe): Boolean                                                       = self.done()
            def onComplete(f: Result[E, A] => Unit)(using AllowUnsafe): Unit                             = self.onComplete(f)
            def onInterrupt(f: Result.Error[E] => Unit)(using Frame): Unit                               = self.onInterrupt(f)
            def block(deadline: Clock.Deadline.Unsafe)(using AllowUnsafe, Frame): Result[E | Timeout, A] = self.block(deadline)
            def interrupt()(using frame: Frame, allow: AllowUnsafe): Boolean      = self.interrupt(Panic(Interrupted(frame)))
            def interrupt(error: Result.Error[E])(using AllowUnsafe): Boolean     = self.interrupt(error)
            def interruptDiscard(error: Result.Error[E])(using AllowUnsafe): Unit = discard(self.interrupt(error))
            def mask()(using AllowUnsafe): Unsafe[E, A]                           = self.mask()

            def toFuture()(using E <:< Throwable, AllowUnsafe): Future[A] =
                val r = scala.concurrent.Promise[A]()
                self.onComplete { v =>
                    r.complete(v.toTry)
                }
                r.future
            end toFuture

            def map[B](f: A => B)(using AllowUnsafe): Unsafe[E, B] =
                val p = new IOPromise[E, B](interrupts = self) with (Result[E, A] => Unit):
                    def apply(v: Result[E, A]) = completeDiscard(v.map(f))
                self.onComplete(p)
                p
            end map

            def flatMap[E2, B](f: A => Unsafe[E2, B])(using AllowUnsafe): Unsafe[E | E2, B] =
                val p = new IOPromise[E | E2, B](interrupts = self) with (Result[E, A] => Unit):
                    def apply(r: Result[E, A]) = r.foldError(v => becomeDiscard(f(v)), completeDiscard)
                self.onComplete(p)
                p
            end flatMap

            def mapResult[E2, B](f: Result[E, A] => Result[E2, B])(using AllowUnsafe): Unsafe[E2, B] =
                val p = new IOPromise[E2, B](interrupts = self) with (Result[E, A] => Unit):
                    def apply(r: Result[E, A]) = completeDiscard(Result(f(r)).flatten)
                self.onComplete(p)
                p
            end mapResult

            def safe: Fiber[E, A] = self
        end extension
    end Unsafe

    opaque type Promise[+E, +A] <: Fiber[E, A] = IOPromise[E, A]

    object Promise:
        inline given [E, A]: Flat[Promise[E, A]] = Flat.unsafe.bypass

        private[kyo] inline def fromTask[E, A](inline ioTask: IOTask[?, E, A]): Promise[E, A] = ioTask

        /** Initializes a new Promise.
          *
          * @return
          *   A new Promise
          */
        def init[E, A](using Frame): Promise[E, A] < IO = initWith[E, A](identity)

        /** Uses a new Promise with the provided type parameters.
          * @param f
          *   The function to apply to the new Promise
          * @return
          *   The result of applying the function
          */
        inline def initWith[E, A](using inline frame: Frame)[B, S](inline f: Promise[E, A] => B < S): B < (S & IO) =
            IO(f(IOPromise()))

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
            def completeDiscard[E2 <: E, A2 <: A](v: Result[E, A])(using Frame): Unit < IO = IO(discard(self.complete(v)))

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
            def becomeDiscard[E2 <: E, A2 <: A](other: Fiber[E2, A2])(using Frame): Unit < IO = IO(discard(self.become(other)))

            def unsafe: Unsafe[E, A] = self
        end extension

        opaque type Unsafe[+E, +A] <: Fiber.Unsafe[E, A] = IOPromise[E, A]

        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        object Unsafe:
            inline given [E, A]: Flat[Unsafe[E, A]] = Flat.unsafe.bypass

            def init[E, A]()(using AllowUnsafe): Unsafe[E, A] = IOPromise()

            def initMasked[E, A]()(using AllowUnsafe): Unsafe[E, A] =
                new IOPromise[E, A]:
                    override def interrupt[E2 >: E](error: Result.Error[E2]): Boolean = false

            private[kyo] def fromIOPromise[E, A](p: IOPromise[E, A]): Unsafe[E, A] = p

            extension [E, A](self: Unsafe[E, A])
                def complete[E2 <: E, A2 <: A](v: Result[E, A])(using AllowUnsafe): Boolean        = self.complete(v)
                def completeDiscard[E2 <: E, A2 <: A](v: Result[E, A])(using AllowUnsafe): Unit    = discard(self.complete(v))
                def become[E2 <: E, A2 <: A](other: Fiber[E2, A2])(using AllowUnsafe): Boolean     = self.become(other)
                def becomeDiscard[E2 <: E, A2 <: A](other: Fiber[E2, A2])(using AllowUnsafe): Unit = discard(self.become(other))
                def safe: Promise[E, A]                                                            = self
            end extension
        end Unsafe
    end Promise

    private inline def foreach[A](l: Iterable[A])(inline f: (Int, A) => Unit): Unit =
        l match
            case l: IndexedSeq[A] =>
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
end Fiber
