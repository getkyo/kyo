package kyo

import java.io.IOException
import scala.concurrent.Future
import scala.util.Failure
import scala.util.NotGiven
import scala.util.Success

extension (kyoObject: Kyo.type)

    /** Acquires a resource and ensures its release.
      *
      * @param acquire
      *   The effect to acquire the resource
      * @param release
      *   The effect to release the resource
      * @return
      *   An effect that manages the resource lifecycle using Resource and Sync effects
      */
    def acquireRelease[A, S](acquire: => A < S)(release: A => Any < Async)(using Frame): A < (S & Resource & Sync) =
        Resource.acquireRelease(acquire)(release)

    /** Adds a finalizer to the current effect using Resource.
      *
      * @param finalizer
      *   The effect to add as a finalizer
      * @return
      *   An effect that ensures the finalizer is executed when the effect is completed
      */
    def addFinalizer(finalizer: => Any < Async)(using Frame): Unit < (Resource & Sync) =
        Resource.ensure(finalizer)

    /** Creates an asynchronous effect that can be completed by the given register function.
      *
      * @param register
      *   A function that takes an asynchronous effect and registers it to be completed
      * @return
      *   An effect that can be completed by the given register function
      */
    def async[A](register: (A < Async => Unit) => Any < Async)(using Frame): A < Async =
        for
            promise <- Promise.init[Nothing, A]
            registerFn = (eff: A < Async) =>
                val effFiber = Async.run(eff)
                val updatePromise =
                    effFiber.map(_.onComplete(a => promise.completeDiscard(a)))
                val updatePromiseIO = Async.run(updatePromise).unit
                import AllowUnsafe.embrace.danger
                Sync.Unsafe.evalOrThrow(updatePromiseIO)
            _ <- register(registerFn)
            a <- promise.get
        yield a

    /** Creates an effect that attempts to run the given effect and handles any exceptions that occur to Abort[Throwable].
      *
      * @param effect
      *   The effect to attempt to run
      * @return
      *   An effect that attempts to run the given effect and handles any exceptions that occur
      */
    def attempt[A, S](effect: => A < S)(using Frame): A < (S & Abort[Throwable]) =
        Abort.catching[Throwable](effect)

    /** Emits a value
      *
      * @param value
      *   Value to emit
      * @return
      *   An effect that emits a value
      */

    def emit[A](value: A)(using Tag[Emit[A]], Frame): Unit < Emit[A] =
        Emit.value(value)

    /** Creates an effect that fails with Abort[E].
      *
      * @param error
      *   The error to fail with
      * @return
      *   An effect that fails with the given error
      */
    def fail[E](error: => E)(using Frame): Nothing < Abort[E] =
        Abort.fail(error)

    /** Applies a function to each element in parallel and returns a new sequence with the results.
      *
      * @param sequence
      *   The sequence to apply the function to
      * @param useElement
      *   A function to apply to each element of the sequence
      * @return
      *   A new sequence with elements collected using the function
      */
    inline def foreachPar[E, A, S, A1, Ctx](sequence: Seq[A])(useElement: A => A1 < (Abort[E] & Async & Ctx))(
        using frame: Frame
    ): Seq[A1] < (Abort[E] & Async & Ctx) =
        Async.collectAll[E, A1, Ctx](sequence.map(useElement))

    /** Applies a function to each element in parallel and discards the results.
      *
      * @param sequence
      *   The sequence to apply the function to
      * @param useElement
      *   A function to apply to each element of the sequence
      * @return
      *   Discards the results of the function application and returns Unit
      */
    inline def foreachParDiscard[E, A, S, A1, Ctx](sequence: Seq[A])(useElement: A => A1 < (Abort[E] & Async & Ctx))(
        using frame: Frame
    ): Unit < (Abort[E] & Async & Ctx) =
        foreachPar(sequence)(useElement).unit

    /** Creates an effect from an AutoCloseable resource.
      *
      * @param closeable
      *   The AutoCloseable resource to create an effect from
      * @return
      *   An effect that manages the resource lifecycle using Resource and Sync effects
      */
    def fromAutoCloseable[A <: AutoCloseable, S](closeable: => A < S)(using Frame): A < (S & Resource & Sync) =
        acquireRelease(closeable)(c => Sync(c.close()))

    /** Creates an effect from an Either[E, A] and handles Left[E] to Abort[E].
      *
      * @param either
      *   The Either[E, A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles Left[E] to Abort[E].
      */
    def fromEither[E, A](either: Either[E, A])(using Frame): A < Abort[E] =
        Abort.get(either)

    /** Creates an effect from an Option[A] and handles None to Abort[Absent].
      *
      * @param option
      *   The Option[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles None to Abort[Absent].
      */
    def fromOption[A](option: Option[A])(using Frame): A < Abort[Absent] =
        Abort.get(option)

    /** Creates an effect from a Maybe[A] and handles Absent to Abort[Absent].
      *
      * @param maybe
      *   The Maybe[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles Absent to Abort[Absent].
      */
    def fromMaybe[A](maybe: Maybe[A])(using Frame): A < Abort[Absent] =
        Abort.get(maybe)

    /** Creates an effect from a Result[E, A] and handles Result.Failure[E] to Abort[E].
      *
      * @param result
      *   The Result[E, A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles Result.Failure[E] to Abort[E].
      */
    def fromResult[E, A](result: Result[E, A])(using Frame): A < Abort[E] =
        Abort.get(result)

    /** Creates an effect from a Future[A] and handles the Future to Async.
      *
      * @param future
      *   The Future[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles the Future to Async.
      */
    def fromFuture[A](future: => Future[A])(using Frame): A < (Async & Abort[Throwable]) =
        Async.fromFuture(future)

    /** Creates an effect from a Promise[A] and handles the Promise to Async.
      *
      * @param promise
      *   The Promise[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles the Promise to Async.
      */
    def fromPromiseScala[A](promise: => scala.concurrent.Promise[A])(using Frame): A < (Async & Abort[Throwable]) =
        fromFuture(promise.future)

    /** Creates an effect from a sequence and handles the sequence to Choice.
      *
      * @param sequence
      *   The sequence to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles the sequence to Choice.
      */
    def fromSeq[A](sequence: Seq[A])(using Frame): A < Choice =
        Choice.evalSeq(sequence)

    /** Creates an effect from a Try[A] and handles the Try to Abort[Throwable].
      *
      * @param _try
      *   The Try[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles the Try to Abort[Throwable].
      */
    def fromTry[A](_try: scala.util.Try[A])(using Frame): A < Abort[Throwable] =
        Abort.get(_try)

    /** Logs an informational message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logInfo(inline message: => String): Unit < Sync =
        Log.info(message)

    /** Logs an informational message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logInfo(inline message: => String, inline err: => Throwable): Unit < Sync =
        Log.info(message, err)

    /** Logs a warning message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logWarn(inline message: => String): Unit < Sync =
        Log.warn(message)

    /** Logs a warning message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logWarn[S, S1](inline message: => String, inline err: => Throwable): Unit < Sync =
        Log.warn(message, err)

    /** Logs a debug message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logDebug(inline message: => String): Unit < Sync =
        Log.debug(message)

    /** Logs a debug message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logDebug(inline message: => String, inline err: => Throwable): Unit < Sync =
        Log.debug(message, err)

    /** Logs an error message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logError(inline message: => String): Unit < Sync =
        Log.error(message)

    /** Logs an error message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logError(inline message: => String, inline err: => Throwable): Unit < Sync =
        Log.error(message, err)

    /** Logs a trace message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logTrace(inline message: => String): Unit < Sync =
        Log.trace(message)

    /** Logs a trace message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logTrace(inline message: => String, inline err: Throwable): Unit < Sync =
        Log.trace(message, err)

    /** Creates an effect that never completes using Async.
      *
      * @return
      *   An effect that never completes
      */
    def never(using Frame): Nothing < Async = Async.never

    /** Provides a dependency to an effect using Env.
      *
      * @param dependency
      *   The dependency to provide
      * @param effect
      *   The effect to provide the dependency to
      * @return
      *   An effect that provides the dependency to the effect
      */
    def provideFor[E, A, SA, ER](dependency: E)(effect: A < (SA & Env[E | ER]))(
        using
        reduce: Reducible[Env[ER]],
        t: Tag[E],
        frame: Frame
    ): A < (SA & reduce.SReduced) =
        Env.run(dependency)(effect)

    /** Creates a scoped effect using Resource.
      *
      * @param resource
      *   The resource to create a scoped effect from
      * @return
      *   An effect that manages the resource lifecycle using Resource and Sync effects
      */
    def scoped[A, S](resource: => A < (S & Resource))(using Frame): A < (Async & S) =
        Resource.run(resource)

    /** Retrieves a dependency from Env.
      *
      * @return
      *   An effect that retrieves the dependency from Env
      */
    def service[D](using Tag[D], Frame): D < Env[D] =
        Env.get[D]

    /** Retrieves a dependency from Env and applies a function to it.
      *
      * @param fn
      *   The function to apply to the dependency
      * @return
      *   An effect that retrieves the dependency from Env and applies the function to it
      */
    def serviceWith[D](using
        Tag[D],
        Frame
    ): [A, S] => (D => A < S) => A < (S & Env[D]) =
        [A, S] => (fn: D => (A < S)) => service[D].map(d => fn(d))

    /** Sleeps for a given duration using Async.
      *
      * @param duration
      *   The duration to sleep for
      * @return
      *   An effect that sleeps for the given duration
      */
    def sleep(duration: Duration)(using Frame): Unit < Async =
        Async.sleep(duration)

    /** Suspends an effect using Sync.
      *
      * @param effect
      *   The effect to suspend
      * @return
      *   An effect that suspends the given effect
      */
    def suspend[A, S](effect: => A < S)(using Frame): A < (S & Sync) =
        Sync(effect)

    /** Suspends an effect using Sync and handles any exceptions that occur to Abort[Throwable].
      *
      * @param effect
      *   The effect to suspend
      * @return
      *   An effect that suspends the given effect and handles any exceptions that occur to Abort[Throwable]
      */
    def suspendAttempt[A, S](effect: => A < S)(using Frame): A < (S & Sync & Abort[Throwable]) =
        Sync(Abort.catching[Throwable](effect))

    /** Traverses a sequence of effects and collects the results.
      *
      * @param sequence
      *   The sequence of effects to traverse
      * @return
      *   An effect that traverses the sequence of effects and collects the results
      */
    def traverse[A, S](sequence: Seq[A < S])(using Frame): Seq[A] < S =
        Kyo.collectAll(sequence)

    /** Traverses a sequence of effects and discards the results.
      *
      * @param sequence
      *   The sequence of effects to traverse
      * @return
      *   An effect that traverses the sequence of effects and discards the results
      */
    def traverseDiscard[A, S](sequence: Seq[A < S])(using Frame): Unit < S =
        Kyo.collectAllDiscard(sequence)

    /** Traverses a sequence of effects in parallel and collects the results.
      *
      * @param sequence
      *   The sequence of effects to traverse in parallel
      * @return
      *   An effect that traverses the sequence of effects in parallel and collects the results
      */
    def traversePar[A](
        sequence: => Seq[A < Async]
    )(using Frame): Seq[A] < Async =
        foreachPar(sequence)(identity)

    /** Traverses a sequence of effects in parallel and discards the results.
      *
      * @param sequence
      *   The sequence of effects to traverse in parallel
      * @return
      *   An effect that traverses the sequence of effects in parallel and discards the results
      */
    def traverseParDiscard[A](
        sequence: => Seq[A < Async]
    )(using Frame): Unit < Async =
        foreachPar(sequence)(identity).unit
end extension
