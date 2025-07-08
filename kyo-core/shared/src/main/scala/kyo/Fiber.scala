package kyo

import java.lang.invoke.VarHandle
import java.util.Arrays
import kyo.Result.Panic
import kyo.kernel.internal.Safepoint
import kyo.scheduler.IOPromise
import kyo.scheduler.IOPromiseBase
import kyo.scheduler.IOTask
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.NotGiven

/** A low-level primitive for asynchronous computation control.
  *
  * WARNING: This is a low-level API primarily intended for library authors and system integrations. For application-level concurrent
  * programming, use the [[Async]] effect instead, which provides a safer, more structured interface.
  *
  * Fiber is the underlying mechanism that powers Kyo's concurrent execution model. It provides fine-grained control over asynchronous
  * computations, including lifecycle management, interruption handling, and completion callbacks.
  *
  * Key capabilities:
  *   - Lifecycle control (completion, interruption)
  *   - Callback registration for completion and interruption
  *   - Result transformation and composition
  *   - Integration with external async systems (e.g., Future)
  *
  * @tparam A
  *   The type of the successful result
  * @tparam S
  *   The effect type that the Fiber computation may perform
  *
  * @see
  *   [[Async]] for the high-level, structured concurrency API
  * @see
  *   [[Promise]] for creating and completing Fibers manually
  * @see
  *   [[Fiber.Unsafe]] for low-level operations requiring [[AllowUnsafe]]
  */
opaque type Fiber[+A, -S] = IOPromiseBase[Any, A < (Async & S)]

export Fiber.Promise

object Fiber:

    private val _unit = IOPromise(Result.succeed((): Unit < Any))

    /** Creates a unit Fiber.
      *
      * @return
      *   A Fiber that completes with unit
      */
    def unit: Fiber[Unit, Any] = _unit

    /** Creates a successful Fiber.
      *
      * @param v
      *   The value to complete the Fiber with
      * @return
      *   A Fiber that completes successfully with the given value
      */
    def succeed[A](v: A): Fiber[A, Any] = fromResult(Result.succeed(v))

    /** Creates a failed Fiber.
      *
      * @param ex
      *   The error to fail the Fiber with
      * @return
      *   A Fiber that fails with the given error
      */
    def fail[E](ex: E): Fiber[Nothing, Abort[E]] = fromResult(Result.fail(ex))

    /** Creates a panicked Fiber.
      *
      * @param ex
      *   The throwable to panic the Fiber with
      * @return
      *   A Fiber that panics with the given throwable
      */
    def panic(ex: Throwable): Fiber[Nothing, Any] = fromResult(Result.panic(ex))

    /** Creates a never-completing Fiber.
      *
      * @return
      *   A Fiber that never completes
      */
    def never(using Frame): Fiber[Nothing, Any] < Sync =
        Sync.defer(IOPromise[Nothing, Nothing < Any]())

    def fromResult[E, A, S](result: Result[E, A < S]): Fiber[A, Abort[E] & S] =
        IOPromise(result)

    /** Creates a Fiber from a Future.
      *
      * This method allows integration of existing Future-based code with Kyo's Fiber system. It handles successful completion and
      * unexpected failures.
      *
      * @param future
      *   The Future to convert into a Fiber
      * @tparam A
      *   The type of the successful result
      * @return
      *   A Fiber that completes with the result of the Future
      */
    def fromFuture[A](future: => Future[A])(using Frame): Fiber[A, Any] < Sync =
        Sync.Unsafe(Unsafe.fromFuture(future))

    /** Runs an asynchronous computation in a new Fiber.
      *
      * @param v
      *   The computation to run
      * @return
      *   A Fiber representing the running computation
      */
    def init[E, A, S, S2](
        using isolate: Isolate[S, Sync, S2]
    )(
        v: => A < (Abort[E] & Async & S)
    )(
        using
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[A, reduce.SReduced & S2] < (Sync & S) =
        Isolate.internal.runDetached((trace, context) =>
            isolate.capture { state =>
                val io = isolate.isolate(state, v).map(r => Kyo.lift(isolate.restore(r)))
                IOTask(io, trace, context).asInstanceOf[Fiber[A, reduce.SReduced & S2]]
            }
        )

    extension [A, S](self: Fiber[A, S])
        /** Checks if the Fiber is done.
          *
          * @return
          *   Whether the Fiber is done
          */
        def done(using Frame): Boolean < Sync =
            Sync.Unsafe(Unsafe.done(self)())

        /** Maps the result of the Fiber.
          *
          * @param f
          *   The function to apply to the Fiber's result
          * @return
          *   A new Fiber with the mapped result
          */
        def map[B](f: A => B < Sync)(using Frame): Fiber[B, S] < Sync =
            Sync.Unsafe(Unsafe.map(self)((r => Sync.Unsafe.evalOrThrow(f(r)))))

        /** Flat maps the result of the Fiber.
          *
          * @param f
          *   The function to apply to the Fiber's result
          * @return
          *   A new Fiber with the flat mapped result
          */
        def flatMap[B, S2](f: A < S => Fiber[B, S2] < Sync)(using Frame): Fiber[B, S & S2] < Sync =
            Sync.Unsafe(Unsafe.flatMap(self)(r => Sync.Unsafe.evalOrThrow(f(r))))

        /** Creates a new Fiber that runs with interrupt masking.
          *
          * This method returns a new Fiber that, when executed, will not propagate interrupts to previous "steps" of the computation. The
          * returned Fiber can still be interrupted, but the interruption won't affect the masked portion. This is useful for ensuring that
          * critical operations or cleanup tasks complete even if an interrupt occurs.
          *
          * @return
          *   A new Fiber that runs with interrupt masking
          */
        def mask(using Frame): Fiber[A, S] < Sync =
            Sync.Unsafe(Unsafe.mask(self)())

        /** Gets the number of waiters on this Fiber.
          *
          * This method returns the count of callbacks and other fibers waiting for this fiber to complete. Primarily useful for debugging
          * and monitoring purposes.
          *
          * @return
          *   The number of waiters on this Fiber
          */
        def waiters(using Frame): Int < Sync =
            Sync.Unsafe(Unsafe.waiters(self)())

        def unsafe: Fiber.Unsafe[A, S] =
            self
    end extension

    extension [E, A, S](self: Fiber[A, Abort[E] & S])
        private[kyo] def lower: IOPromise[E, A < S] = self.asInstanceOf[IOPromise[E, A < S]]

        /** Gets the result of the Fiber.
          *
          * @return
          *   The result of the Fiber
          */
        def get(using Frame): A < (Abort[E] & Async & S) =
            Async.use(self.lower)(identity)

        /** Uses the result of the Fiber to compute a new value.
          *
          * @param f
          *   The function to apply to the Fiber's result
          * @return
          *   The result of applying the function to the Fiber's result
          */
        def use[B, S2](f: A => B < S2)(using Frame): B < (Abort[E] & Async & S & S2) =
            Async.use(self.lower)(_.map(f))

        /** Gets the result of the Fiber as a Result.
          *
          * @return
          *   The Result of the Fiber
          */
        def getResult(using Frame): Result[E, A < S] < Async =
            Async.getResult(self.lower)

        /** Uses the Result of the Fiber to compute a new value.
          *
          * @param f
          *   The function to apply to the Fiber's Result
          * @return
          *   The result of applying the function to the Fiber's Result
          */
        def useResult[B, S2](f: Result[E, A < S] => B < S2)(using Frame): B < (Async & S2) =
            Async.useResult(self.lower)(f)

        /** Registers a callback to be called when the Fiber completes.
          *
          * @param f
          *   The callback function
          */
        def onComplete(f: Result[E, A < S] => Any < Sync)(using Frame): Unit < Sync =
            Sync.Unsafe(Unsafe.onComplete(self)(r => Sync.Unsafe.evalOrThrow(f(r).unit)))

        /** Registers a callback to be called when the Fiber is interrupted.
          *
          * This method allows you to specify a callback that will be executed if the Fiber is interrupted. The callback receives the Error
          * value that caused the interruption.
          *
          * @param f
          *   The callback function to be executed on interruption
          * @return
          *   A unit value wrapped in Sync, representing the registration of the callback
          */
        def onInterrupt(f: Result.Error[E] => Any < Sync)(using Frame): Unit < Sync =
            Sync.Unsafe(Unsafe.onInterrupt(self)(r => Sync.Unsafe.evalOrThrow(f(r).unit)))

        /** Blocks until the Fiber completes or the timeout is reached.
          *
          * @param timeout
          *   The maximum duration to wait
          * @return
          *   The Result of the Fiber, or a Timeout error
          */
        def block(timeout: Duration)(using Frame): Result[E | Timeout, A < S] < Sync =
            Clock.deadline(timeout).map(d => self.lower.block(d.unsafe))

        /** Maps the Result of the Fiber using the provided function.
          *
          * This method allows you to transform both the error and success types of the Fiber's result. It's useful when you need to modify
          * the error type or perform a more complex transformation on the success value that may also produce a new error type.
          *
          * @param f
          *   The function to apply to the Fiber's Result. It should take a Result[E, A < S] and return a Result[E2, B < S2].
          * @return
          *   A new Fiber with the mapped Result
          */
        def mapResult[E2, B, S2](f: Result[E, A < S] => Result[E2, B < S2] < Sync)(using Frame): Fiber[B, Abort[E2] & S & S2] < Sync =
            Sync.Unsafe(Unsafe.mapResult(self)(r => Sync.Unsafe.evalOrThrow(f(r))))

        /** Interrupts the Fiber.
          *
          * @return
          *   Whether the Fiber was successfully interrupted
          */
        def interrupt(using frame: Frame): Boolean < Sync =
            interrupt(Result.Panic(Interrupted(frame)))

        /** Interrupts the Fiber with a specific error.
          *
          * @param error
          *   The error to interrupt the Fiber with
          * @return
          *   Whether the Fiber was successfully interrupted
          */
        def interrupt(error: Result.Error[E])(using Frame): Boolean < Sync =
            Sync.Unsafe(Unsafe.interrupt(self)(error))

        /** Interrupts the Fiber with a specific error, discarding the return value.
          *
          * @param error
          *   The error to interrupt the Fiber with
          */
        def interruptDiscard(error: Result.Error[E])(using Frame): Unit < Sync =
            Sync.Unsafe(Unsafe.interruptDiscard(self)(error))

        /** Polls the Fiber for a result without blocking.
          *
          * @return
          *   Maybe containing the Result if the Fiber is done, or Absent if still pending
          */
        def poll(using Frame): Maybe[Result[E, A < S]] < Sync =
            Sync.Unsafe(Unsafe.poll(self)())

        /** Converts the Fiber to a Future.
          *
          * @return
          *   A Future that completes with the result of the Fiber
          */
        def toFuture(using E <:< Throwable, S =:= Any, Frame): Future[A] < Sync =
            Sync.Unsafe(Unsafe.toFuture(self)())
    end extension

    opaque type Unsafe[+A, -S] = IOPromiseBase[Any, A < (Async & S)]

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init[E, A, S](result: Result[E, A < S])(using allow: AllowUnsafe, reduce: Reducible[Abort[E]]): Unsafe[A, reduce.SReduced & S] =
            IOPromise(result)

        def fromFuture[A](f: => Future[A])(using AllowUnsafe): Unsafe[A, Any] =
            import scala.util.*
            val p = new IOPromise[Any, A < Any] with (Try[A] => Unit):
                def apply(result: Try[A]) =
                    result match
                        case Success(v) =>
                            completeDiscard(Result.succeed(v))
                        case Failure(ex) =>
                            completeDiscard(Result.panic(ex))

            f.onComplete(p)(using ExecutionContext.parasitic)
            p
        end fromFuture

        extension [A, S](self: Unsafe[A, S])
            def done()(using AllowUnsafe): Boolean      = self.lower.done()
            def mask()(using AllowUnsafe): Unsafe[A, S] = self.lower.mask()

            def map[B](f: A => B)(using AllowUnsafe, Frame): Unsafe[B, S] =
                val p = new IOPromise[Any, B < S](interrupts = self.lower) with (Result[Any, A < S] => Unit):
                    def apply(v: Result[Any, A < S]) = completeDiscard(v.map(_.map(f)))
                self.lower.onComplete(p)
                p
            end map

            def flatMap[B, S2](f: A < S => Unsafe[B, S2])(using AllowUnsafe): Unsafe[B, S & S2] =
                val p = new IOPromise[Any, B < (S & S2)](interrupts = self.lower) with (Result[Any, A < S] => Unit):
                    def apply(r: Result[Any, A < S]) = r.foldError(v => becomeDiscard(f(v).lower), completeDiscard)
                self.lower.onComplete(p)
                p
            end flatMap

            def safe: Fiber[A, S] = self

            def waiters()(using AllowUnsafe): Int = self.lower.waiters()
        end extension

        extension [E, A, S](self: Unsafe[A, Abort[E] & S])

            def onComplete(f: Result[E, A < S] => Unit)(using AllowUnsafe): Unit = self.lower.onComplete(f)

            def onInterrupt(f: Result.Error[E] => Unit)(using Frame): Unit        = self.lower.onInterrupt(f)
            def interrupt()(using frame: Frame, allow: AllowUnsafe): Boolean      = self.lower.interrupt(Panic(Interrupted(frame)))
            def interrupt(error: Result.Error[E])(using AllowUnsafe): Boolean     = self.lower.interrupt(error)
            def interruptDiscard(error: Result.Error[E])(using AllowUnsafe): Unit = discard(self.lower.interrupt(error))

            def block(deadline: Clock.Deadline.Unsafe)(using AllowUnsafe, Frame): Result[E | Timeout, A < S] =
                self.lower.block(deadline)

            def toFuture()(using E <:< Throwable, S =:= Any, AllowUnsafe, Frame): Future[A] =
                val r = scala.concurrent.Promise[A]()
                self.lower.onComplete { v =>
                    r.complete(v.map(_.asInstanceOf[A < Any].eval).toTry)
                }
                r.future
            end toFuture

            def mapResult[E2, B, S2](f: Result[E, A < S] => Result[E2, B < S2])(using AllowUnsafe): Unsafe[B, Abort[E2] & S & S2] =
                val p = new IOPromise[E2, B < (Abort[E2] & S & S2)](interrupts = self.lower) with (Result[E, A < S] => Unit):
                    def apply(r: Result[E, A < S]) = completeDiscard(Result(f(r)).flatten)
                self.lower.onComplete(p)
                p
            end mapResult

            def poll()(using AllowUnsafe): Maybe[Result[E, A < S]] = self.lower.poll()
        end extension
    end Unsafe

    opaque type Promise[A, S] <: Fiber[A, S] = IOPromise[Any, A < S]

    object Promise:

        /** Initializes a new Promise.
          *
          * @return
          *   A new Promise
          */
        def init[E, A](using Frame): Promise[E, A] < Sync =
            initWith[E, A](identity)

        /** Uses a new Promise with the provided type parameters.
          * @param f
          *   The function to apply to the new Promise
          * @return
          *   The result of applying the function
          */
        inline def initWith[E, A](using inline frame: Frame)[B, S](inline f: Promise[E, A] => B < S): B < (S & Sync) =
            Sync.defer(f(IOPromise()))

        extension [A, S](self: Promise[A, S])(using NotGiven[S <:< Abort[Any]])

            /** Completes the Promise with a result.
              *
              * @param v
              *   The result to complete the Promise with
              * @return
              *   Whether the Promise was successfully completed
              */
            def complete(v: Result[Nothing, A < S])(using Frame): Boolean < Sync =
                Sync.Unsafe(Unsafe.complete(self)(v))

            /** Completes the Promise with a result, discarding the return value.
              *
              * @param v
              *   The result to complete the Promise with
              */
            def completeDiscard(v: Result[Nothing, A < S])(using Frame): Unit < Sync =
                Sync.Unsafe(Unsafe.completeDiscard(self)(v))

            def completeUnit(using frame: Frame, ev: Unit =:= A): Boolean < Sync =
                Sync.Unsafe(Unsafe.complete(self)(Result.succeed(ev(()))))

            def completeUnitDiscard(using frame: Frame, ev: Unit =:= A): Unit < Sync =
                Sync.Unsafe(Unsafe.completeDiscard(self)(Result.succeed(ev(()))))

            /** Makes this Promise become another Fiber.
              *
              * @param other
              *   The Fiber to become
              * @return
              *   Whether the Promise successfully became the other Fiber
              */
            def become(other: Fiber[A, S])(using Frame): Boolean < Sync =
                Sync.Unsafe(Unsafe.become(self)(other))

            /** Makes this Promise become another Fiber, discarding the return value.
              *
              * @param other
              *   The Fiber to become
              */
            def becomeDiscard(other: Fiber[A, S])(using Frame): Unit < Sync =
                Sync.Unsafe(Unsafe.becomeDiscard(self)(other))

            /** Polls the Promise for a result without blocking.
              *
              * @return
              *   Maybe containing the Result if the Promise is done, or Absent if still pending
              */
            def poll(using Frame): Maybe[Result[Nothing, A < S]] < Sync =
                Sync.Unsafe(Unsafe.poll(self)())

            /** Gets the number of waiters on this Promise.
              *
              * This method returns the count of callbacks and other fibers waiting for this promise to complete. Primarily useful for
              * debugging and monitoring purposes.
              *
              * @return
              *   The number of waiters on this Promise
              */
            def waiters(using Frame): Int < Sync =
                Sync.Unsafe(Unsafe.waiters(self)())

            def unsafe: Unsafe[A, S] = self
        end extension

        extension [E, A, S](self: Promise[A, Abort[E] & S])

            /** Completes the Promise with a result.
              *
              * @param v
              *   The result to complete the Promise with
              * @return
              *   Whether the Promise was successfully completed
              */
            def complete(v: Result[E, A < S])(using Frame): Boolean < Sync =
                Sync.Unsafe(Unsafe.complete(self)(v))

            /** Completes the Promise with a result, discarding the return value.
              *
              * @param v
              *   The result to complete the Promise with
              */
            def completeDiscard(v: Result[E, A < S])(using Frame): Unit < Sync =
                Sync.Unsafe(Unsafe.completeDiscard(self)(v))

            def completeUnit(using frame: Frame, ev: Unit =:= A): Boolean < Sync =
                Sync.Unsafe(Unsafe.complete(self)(Result.succeed(ev(()))))

            def completeUnitDiscard(using frame: Frame, ev: Unit =:= A): Unit < Sync =
                Sync.Unsafe(Unsafe.completeDiscard(self)(Result.succeed(ev(()))))

            /** Makes this Promise become another Fiber.
              *
              * @param other
              *   The Fiber to become
              * @return
              *   Whether the Promise successfully became the other Fiber
              */
            def become(other: Fiber[A, Abort[E] & S])(using Frame): Boolean < Sync =
                Sync.Unsafe(Unsafe.become(self)(other))

            /** Makes this Promise become another Fiber, discarding the return value.
              *
              * @param other
              *   The Fiber to become
              */
            def becomeDiscard(other: Fiber[A, Abort[E] & S])(using Frame): Unit < Sync =
                Sync.Unsafe(Unsafe.becomeDiscard(self)(other))

            /** Polls the Promise for a result without blocking.
              *
              * @return
              *   Maybe containing the Result if the Promise is done, or Absent if still pending
              */
            def poll(using Frame): Maybe[Result[E, A < S]] < Sync =
                Sync.Unsafe(Unsafe.poll(self)())

            /** Gets the number of waiters on this Promise.
              *
              * This method returns the count of callbacks and other fibers waiting for this promise to complete. Primarily useful for
              * debugging and monitoring purposes.
              *
              * @return
              *   The number of waiters on this Promise
              */
            def waiters(using Frame): Int < Sync =
                Sync.Unsafe(Unsafe.waiters(self)())

            def unsafe: Unsafe[A, Abort[E] & S] = self

        end extension

        opaque type Unsafe[A, S] <: Fiber.Unsafe[A, S] = IOPromise[Any, A < S]

        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        object Unsafe:
            def init[A, S]()(using AllowUnsafe): Unsafe[A, S] = IOPromise()

            def initMasked[A, S]()(using AllowUnsafe): Unsafe[A, S] =
                new IOPromise[Any, A < S]:
                    override def interrupt(error: Result.Error[Any]): Boolean = false

            private[kyo] def fromIOPromise[A, S](p: IOPromise[?, A < S]): Unsafe[A, S] = p.asInstanceOf[Unsafe[A, S]]

            extension [A, S](self: Unsafe[A, S])(using NotGiven[S <:< Abort[Any]])
                private def lower: IOPromise[Nothing, A < S] =
                    self.asInstanceOf[IOPromise[Nothing, A < S]]
                def complete(v: Result[Nothing, A < S])(using AllowUnsafe): Boolean =
                    self.lower.complete(v)
                def completeDiscard(v: Result[Nothing, A < S])(using AllowUnsafe): Unit =
                    discard(self.lower.complete(v))
                def become(other: Fiber[A, S])(using AllowUnsafe): Boolean =
                    self.lower.become(Fiber.lower(other))
                def becomeDiscard(other: Fiber[A, S])(using AllowUnsafe): Unit =
                    discard(lower.become(Fiber.lower(other)))
                def waiters()(using AllowUnsafe): Int =
                    self.lower.waiters()
                def poll()(using AllowUnsafe): Maybe[Result[Nothing, A < S]] =
                    self.lower.poll()
                def safe: Promise[A, S] = self
            end extension

            extension [S](self: Unsafe[Unit, S])
                def completeUnit(using AllowUnsafe): Boolean =
                    self.lower.complete(Result.succeed(()))
                def completeUnitDiscard(using AllowUnsafe): Unit =
                    self.lower.completeDiscard(Result.succeed(()))
            end extension

            extension [E, A, S](self: Unsafe[A, Abort[E] & S])
                private def lower: IOPromise[E, A < S] =
                    self.asInstanceOf[IOPromise[E, A < S]]
                def complete(v: Result[E, A < S])(using AllowUnsafe): Boolean =
                    self.lower.complete(v)
                def completeDiscard(v: Result[E, A < S])(using AllowUnsafe): Unit =
                    discard(self.lower.complete(v))
                def become(other: Fiber[A, Abort[E] & S])(using AllowUnsafe): Boolean =
                    self.lower.become(Fiber.lower(other))
                def becomeDiscard(other: Fiber[A, Abort[E] & S])(using AllowUnsafe): Unit =
                    discard(lower.become(Fiber.lower(other)))
                def waiters()(using AllowUnsafe): Int =
                    self.lower.waiters()
                def poll()(using AllowUnsafe): Maybe[Result[E, A < S]] =
                    self.lower.poll()
                def safe: Promise[A, Abort[E] & S] = self
            end extension
        end Unsafe
    end Promise

    /** Races multiple Fibers and returns a Fiber that completes with the result of the first to complete. When one Fiber completes, all
      * other Fibers are interrupted.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
      *
      * @param iterable
      *   The sequence of effects to race
      * @return
      *   A Fiber that completes with the result of the first Fiber to complete
      */
    private[kyo] def race[E, A](iterable: Iterable[A < (Abort[E] & Async)])(using Frame): Fiber[A, Abort[E]] < Sync =
        internal.race(iterable)

    private[kyo] def raceFirst[E, A](iterable: Iterable[A < (Abort[E] & Async)])(using Frame): Fiber[A, Abort[E]] < Sync =
        internal.raceFirst(iterable)

    /** Concurrently executes effects and collects up to `max` successful results.
      *
      * WARNING: Executes all computations in parallel without bounds. Use with caution on large sequences to avoid resource exhaustion.
      *
      * Similar to `gather`, but completes early once the specified number of `max` successful results is reached. If not enough successes
      * occur and all remaining computations fail, the last encountered error is propagated.
      *
      * @param max
      *   Maximum number of successful results to collect
      * @param iterable
      *   Sequence of effects to execute
      * @return
      *   Fiber containing successful results as a Chunk (size <= max)
      */
    private[kyo] def gather[E, A](max: Int)(iterable: Iterable[A < (Abort[E] & Async)])(
        using frame: Frame
    ): Fiber[Chunk[A], Abort[E]] < Sync =
        internal.gather(max)(iterable)

    private[kyo] def foreachIndexed[E, A, B](iterable: Iterable[A])(f: (Int, A) => B < (Abort[E] & Async))(
        using frame: Frame
    ): Fiber[Chunk[B], Abort[E]] < Sync =
        internal.foreachIndexed(iterable)(f)

    private[kyo] object internal:

        def foreachIndexed[E, A, B](iterable: Iterable[A])(f: (Int, A) => B < (Abort[E] & Async))(
            using frame: Frame
        ): Fiber[Chunk[B], Abort[E]] < Sync =
            iterable.size match
                case 0 => Fiber.succeed(Chunk.empty)
                case size =>
                    Sync.Unsafe {
                        class State extends IOPromise[Any, Chunk[B] < Abort[E]]
                            with ((Int, Result[E, B]) => Unit):
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
                        Isolate.internal.runDetached { (trace, context) =>
                            val safepoint = Safepoint.get
                            foreach(iterable) { (idx, v) =>
                                val fiber = IOTask(f(idx, v), safepoint.copyTrace(trace), context)
                                state.interrupts(fiber)
                                fiber.onComplete(state(idx, _))
                            }
                            state
                        }
                    }

        def race[E, A](iterable: Iterable[A < (Abort[E] & Async)])(using Frame): Fiber[A, Abort[E]] < Sync =
            Race.success(iterable)

        def raceFirst[E, A](iterable: Iterable[A < (Abort[E] & Async)])(using Frame): Fiber[A, Abort[E]] < Sync =
            Race.first(iterable)

        sealed abstract private class Race[E, A](frame: Frame) extends IOPromise[E, A] with (Result[E, A] => Unit)

        private object Race:

            private inline def apply[E, A](state: Race[E, A], iterable: Iterable[A < (Abort[E] & Async)])(
                using frame: Frame
            ): Fiber[A, Abort[E]] < Sync =
                Isolate.internal.runDetached { (trace, context) =>
                    val safepoint = Safepoint.get
                    foreach(iterable) { (_, v) =>
                        val fiber = IOTask(v, safepoint.copyTrace(trace), context)
                        state.onComplete(_ => fiber.interruptDiscard(Result.Panic(Interrupted(frame))))
                        fiber.onComplete(state)
                    }
                    state.asInstanceOf[Fiber[A, Abort[E]]]
                }
            end apply

            inline def success[E, A](iterable: Iterable[A < (Abort[E] & Async)])(using frame: Frame): Fiber[A, Abort[E]] < Sync =
                apply(new Success(iterable.size, frame), iterable)

            inline def first[E, A](iterable: Iterable[A < (Abort[E] & Async)])(using frame: Frame): Fiber[A, Abort[E]] < Sync =
                apply(new First(frame), iterable)

            final class Success[E, A](size: Int, frame: Frame) extends Race[E, A](frame):
                import AllowUnsafe.embrace.danger
                val pending = AtomicInt.Unsafe.init(size)
                def apply(result: Result[E, A]): Unit =
                    val last = pending.decrementAndGet() == 0
                    result.foldError(
                        v => super.completeDiscard(Result.succeed(v)),
                        e => if last then super.completeDiscard(e)
                    )
                end apply
            end Success

            final class First[E, A](frame: Frame) extends Race[E, A](frame):
                def apply(result: Result[E, A]): Unit =
                    super.completeDiscard(result)
            end First
        end Race

        def gather[E, A](max: Int)(iterable: Iterable[A < (Abort[E] & Async)])(
            using frame: Frame
        ): Fiber[Chunk[A], Abort[E]] < Sync =
            val total = iterable.size
            if total == 0 || max <= 0 then Fiber.succeed(Chunk.empty)
            else
                Sync.Unsafe {
                    class State extends IOPromise[Any, Chunk[A] < Abort[E]]
                        with Function2[Int, Result[E, A], Unit]:
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
                                        case result: Result.Error[Any] @unchecked =>
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
                                        completeDiscard(
                                            Result.succeed(
                                                Kyo.collectAll(
                                                    Chunk.fromNoCopy(results).take(size).asInstanceOf[Chunk[A < Abort[E]]]
                                                )
                                            )
                                        )
                                    end if
                                end if
                            end loop
                            loop()
                        end apply
                    end State
                    val state = new State
                    Isolate.internal.runDetached { (trace, context) =>
                        val safepoint             = Safepoint.get
                        inline def interruptPanic = Result.Panic(Interrupted(frame))
                        foreach(iterable) { (idx, v) =>
                            val fiber = IOTask(v, safepoint.copyTrace(trace), context)
                            state.onComplete(_ => discard(fiber.interrupt(interruptPanic)))
                            fiber.onComplete(state(idx, _))
                        }
                        state
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
        def quickSort(indices: Array[Int], results: Array[AnyRef], items: Int): Unit =

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
    end internal
end Fiber
