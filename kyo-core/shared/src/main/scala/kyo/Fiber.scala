package kyo

export Fiber.Promise
import java.util.concurrent.atomic.AtomicInteger
import kyo.Maybe.Empty
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

opaque type Fiber[E, A] = IOPromise[E, A]

object Fiber extends FiberPlatformSpecific:

    inline given [E, A]: Flat[Fiber[E, A]] = Flat.unsafe.bypass

    private val _unit  = success(()).mask
    private val _never = IOPromise[Nothing, Unit]().mask

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
    def never[E]: Fiber[E, Unit] = _never.asInstanceOf[Fiber[E, Unit]]

    /** Creates a successful Fiber.
      *
      * @param v
      *   The value to complete the Fiber with
      * @return
      *   A Fiber that completes successfully with the given value
      */
    def success[E, A](v: A): Fiber[E, A] = result(Result.success(v))

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
    def fromFuture[A](f: Future[A])(using frame: Frame): Fiber[Throwable, A] < IO =
        import scala.util.*
        IO {
            val p = new IOPromise[Throwable, A] with (Try[A] => Unit):
                def apply(result: Try[A]) =
                    result match
                        case Success(v) =>
                            completeDiscard(Result.success(v))
                        case Failure(ex) =>
                            completeDiscard(Result.fail(ex))

            f.onComplete(p)(ExecutionContext.parasitic)
            p
        }
    end fromFuture

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
        def onComplete(f: Result[E, A] => Unit < IO)(using Frame): Unit < IO =
            import AllowUnsafe.embrace.danger
            IO(self.onComplete(r => IO.Unsafe.run(f(r)).eval))

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
        def onInterrupt(f: Panic => Unit < IO)(using Frame): Unit < IO =
            import AllowUnsafe.embrace.danger
            IO(self.onInterrupt(r => IO.Unsafe.run(f(r)).eval))

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
                    def apply(v: Result[E, A]) = completeDiscard(v.map(f))
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
                val p = new IOPromise[E | E2, B](interrupts = self) with (Result[E, A] => Unit):
                    def apply(r: Result[E, A]) = r.fold(completeDiscard)(v => becomeDiscard(f(v)))
                self.onComplete(p)
                p
            }

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
        def mapResult[E2, B](f: Result[E, A] => Result[E2, B])(using Frame): Fiber[E2, B] < IO =
            IO {
                val p = new IOPromise[E2, B](interrupts = self) with (Result[E, A] => Unit):
                    def apply(r: Result[E, A]) = completeDiscard(Result(f(r)).flatten)
                self.onComplete(p)
                p
            }

        /** Creates a new Fiber that runs with interrupt masking.
          *
          * This method returns a new Fiber that, when executed, will not propagate interrupts to previous "steps" of the computation. The
          * returned Fiber can still be interrupted, but the interruption won't affect the masked portion. This is useful for ensuring that
          * critical operations or cleanup tasks complete even if an interrupt occurs.
          *
          * @return
          *   A new Fiber that runs with interrupt masking
          */
        def mask(using Frame): Fiber[E, A] < IO = IO(self.mask)

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
        def interruptDiscard(error: Panic)(using Frame): Unit < IO =
            IO(discard(self.interrupt(error)))

        def unsafe: Fiber.Unsafe[E, A] = self

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
                    result.fold(e => if last then completeDiscard(e))(v => completeDiscard(Result.success(v)))
                end apply
            end State
            val state = new State
            import state.*
            foreach(seq)((idx, io) => io.evalNow.foreach(v => state(Result.success(v))))
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

    /** Runs multiple Fibers in parallel and returns a Fiber that completes with their results. If any Fiber fails or is interrupted, all
      * other Fibers are interrupted.
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
            case 0 => Fiber.success(Seq.empty)
            case _ =>
                IO {
                    class State extends IOPromise[E, Seq[A]]:
                        val results = (new Array[Any](seq.size)).asInstanceOf[Array[A]]
                        val pending = new AtomicInteger(seq.size)
                        def update(idx: Int, value: A) =
                            results(idx) = value
                            if pending.decrementAndGet() == 0 then
                                this.completeDiscard(Result.success(ArraySeq.unsafeWrapArray(results)))
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
                                        fiber.onComplete(_.fold(state.completeDiscard)(update(idx, _)))
                                }
                                state
                            }
                        }
                    end if
                }

    opaque type Unsafe[E, A] = IOPromise[E, A]

    /* WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        inline given [E, A]: Flat[Unsafe[E, A]] = Flat.unsafe.bypass

        def init[E, A]()(using AllowUnsafe): Unsafe[E, A] = IOPromise()

        def fromPromise[E, A](p: Promise.Unsafe[E, A]): Unsafe[E, A] = p.safe

        extension [E, A](self: Unsafe[E, A])
            def done()(using AllowUnsafe): Boolean                                                       = self.done()
            def onComplete(f: Result[E, A] => Unit)(using AllowUnsafe): Unit                             = self.onComplete(f)
            def onInterrupt(f: Panic => Unit)(using Frame): Unit                                         = self.onInterrupt(f)
            def block(deadline: Clock.Deadline.Unsafe)(using AllowUnsafe, Frame): Result[E | Timeout, A] = self.block(deadline)
            def interrupt(error: Panic)(using AllowUnsafe): Boolean                                      = self.interrupt(error)
            def interruptDiscard(error: Panic)(using AllowUnsafe): Unit                                  = discard(self.interrupt(error))
            def safe: Fiber[E, A]                                                                        = self
        end extension
    end Unsafe

    opaque type Promise[E, A] <: Fiber[E, A] = IOPromise[E, A]

    object Promise:
        inline given [E, A]: Flat[Promise[E, A]] = Flat.unsafe.bypass

        private[kyo] inline def fromTask[E, A](inline ioTask: IOTask[?, E, A]): Promise[E, A] = ioTask

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

        opaque type Unsafe[E, A] <: Fiber.Unsafe[E, A] = IOPromise[E, A]

        /* WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        object Unsafe:
            inline given [E, A]: Flat[Unsafe[E, A]] = Flat.unsafe.bypass

            def init[E, A]()(using AllowUnsafe): Unsafe[E, A] = IOPromise()

            extension [E, A](self: Unsafe[E, A])
                def complete[E2 <: E, A2 <: A](v: Result[E, A])(using AllowUnsafe): Boolean        = self.complete(v)
                def completeDiscard[E2 <: E, A2 <: A](v: Result[E, A])(using AllowUnsafe): Unit    = discard(self.complete(v))
                def become[E2 <: E, A2 <: A](other: Fiber[E2, A2])(using AllowUnsafe): Boolean     = self.become(other)
                def becomeDiscard[E2 <: E, A2 <: A](other: Fiber[E2, A2])(using AllowUnsafe): Unit = discard(self.become(other))
                def safe: Promise[E, A]                                                            = self
            end extension
        end Unsafe
    end Promise

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
end Fiber
