package kyo

import kyo.kernel.Reducible
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.reflect.ClassTag
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
      *   An effect that manages the resource lifecycle using Resource and IO effects
      */
    def acquireRelease[A, S](acquire: => A < S)(release: A => Unit < IO)(using Frame): A < (S & Resource & IO) =
        acquire.map(a => Resource.ensure(release(a)).as(a))

    /** Adds a finalizer to the current effect using Resource.
      *
      * @param finalizer
      *   The effect to add as a finalizer
      * @return
      *   An effect that ensures the finalizer is executed when the effect is completed
      */
    def addFinalizer(finalizer: => Unit < IO)(using Frame): Unit < (Resource & IO) =
        Resource.ensure(finalizer)

    /** Creates an asynchronous effect that can be completed by the given register function.
      *
      * @param register
      *   A function that takes an asynchronous effect and registers it to be completed
      * @return
      *   An effect that can be completed by the given register function
      */
    def async[A](register: (A < Async => Unit) => Unit < Async)(
        using
        Flat[A],
        Frame
    ): A < Async =
        for
            promise <- Promise.init[Nothing, A]
            registerFn = (eff: A < Async) =>
                val effFiber = Async.run(eff)
                val updatePromise =
                    effFiber.map(_.onComplete(a => promise.completeUnit(a)))
                val updatePromiseIO = Async.run(updatePromise).unit
                IO.run(updatePromiseIO).eval
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
    def attempt[A, S](effect: => A < S)(using Flat[A], Frame): A < (S & Abort[Throwable]) =
        Abort.catching[Throwable](effect)

    /** Collects elements from a sequence using a partial function and returns a new sequence.
      *
      * @param sequence
      *   The sequence to collect elements from
      * @param useElement
      *   A partial function to apply to each element of the sequence
      * @return
      *   A new sequence with elements collected using the partial function
      */
    def collect[A, S, A1, S1](
        sequence: => Seq[A] < S
    )(
        useElement: PartialFunction[A, A1 < S1]
    )(using Frame): Seq[A1] < (S & S1) =
        sequence.flatMap((seq: Seq[A]) => Kyo.collect(seq.collect(useElement)))

    /** Prints a message to the console.
      *
      * @param message
      *   The message to print
      * @return
      *   An effect that prints the message to the console
      */
    def debugln[S](message: => String < S)(using Frame): Unit < (S & IO) =
        message.map(m => Console.println(m))

    /** Creates an effect that fails with Abort[E].
      *
      * @param error
      *   The error to fail with
      * @return
      *   An effect that fails with the given error
      */
    def fail[E, S](error: => E < S)(using Frame): Nothing < (S & Abort[E]) =
        error.map(e => Abort.fail(e))

    /** Applies a function to each element in parallel and returns a new sequence with the results.
      *
      * @param sequence
      *   The sequence to apply the function to
      * @param useElement
      *   A function to apply to each element of the sequence
      * @return
      *   A new sequence with elements collected using the function
      */
    def foreachPar[A, S, A1](
        sequence: => Seq[A] < S
    )(
        useElement: A => A1 < Async
    )(using Flat[A1], Frame): Seq[A1] < (S & Async) =
        sequence.map(seq => Async.parallel(seq.map(useElement)))

    /** Applies a function to each element in parallel and discards the results.
      *
      * @param sequence
      *   The sequence to apply the function to
      * @param useElement
      *   A function to apply to each element of the sequence
      * @return
      *   Discards the results of the function application and returns Unit
      */
    def foreachParDiscard[A, S, Any](
        sequence: => Seq[A] < S
    )(
        useElement: A => Any < Async
    )(using Flat[Any], Frame): Unit < (S & Async) =
        sequence.map(seq => Async.parallel(seq.map(v => useElement(v)))).unit

    /** Creates an effect from an AutoCloseable resource.
      *
      * @param closeable
      *   The AutoCloseable resource to create an effect from
      * @return
      *   An effect that manages the resource lifecycle using Resource and IO effects
      */
    def fromAutoCloseable[A <: AutoCloseable, S](closeable: => A < S)(using Frame): A < (S & Resource & IO) =
        acquireRelease(closeable)(c => IO(c.close()))

    /** Creates an effect from an Either[E, A] and handles Left[E] to Abort[E].
      *
      * @param either
      *   The Either[E, A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles Left[E] to Abort[E].
      */
    def fromEither[E, A, S](either: => Either[E, A] < S)(using Frame): A < (S & Abort[E]) =
        either.map(Abort.get(_))

    /** Creates an effect from an Option[A] and handles None to Abort[Maybe.Empty].
      *
      * @param option
      *   The Option[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles None to Abort[Maybe.Empty].
      */
    def fromOption[A, S](option: => Option[A] < S)(using Frame): A < (S & Abort[Maybe.Empty]) =
        option.map(o => Abort.get(o.toRight[Maybe.Empty](Maybe.Empty)))

    /** Creates an effect from a Maybe[A] and handles Maybe.Empty to Abort[Maybe.Empty].
      *
      * @param maybe
      *   The Maybe[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles Maybe.Empty to Abort[Maybe.Empty].
      */
    def fromMaybe[A, S](maybe: => Maybe[A] < S)(using Frame): A < (S & Abort[Maybe.Empty]) =
        maybe.map(m => Abort.get(m))

    /** Creates an effect from a Result[E, A] and handles Result.Failure[E] to Abort[E].
      *
      * @param result
      *   The Result[E, A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles Result.Failure[E] to Abort[E].
      */
    def fromResult[E, A, S](result: => Result[E, A] < S)(using Frame): A < (S & Abort[E]) =
        result.map(Abort.get(_))

    /** Creates an effect from a Future[A] and handles the Future to Async.
      *
      * @param future
      *   The Future[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles the Future to Async.
      */
    def fromFuture[A: Flat, S](future: => Future[A] < S)(using Frame): A < (S & Async & Abort[Throwable]) =
        future.map(f => Async.fromFuture(f))

    /** Creates an effect from a Promise[A] and handles the Promise to Async.
      *
      * @param promise
      *   The Promise[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles the Promise to Async.
      */
    def fromPromiseScala[A: Flat, S](promise: => scala.concurrent.Promise[A] < S)(using Frame): A < (S & Async & Abort[Throwable]) =
        promise.map(p => fromFuture(p.future))

    /** Creates an effect from a sequence and handles the sequence to Choice.
      *
      * @param sequence
      *   The sequence to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles the sequence to Choice.
      */
    def fromSeq[A, S](sequence: => Seq[A] < S)(using Frame): A < (S & Choice) =
        sequence.map(seq => Choice.get(seq))

    /** Creates an effect from a Try[A] and handles the Try to Abort[Throwable].
      *
      * @param _try
      *   The Try[A] to create an effect from
      * @return
      *   An effect that attempts to run the given effect and handles the Try to Abort[Throwable].
      */
    def fromTry[A, S](_try: => scala.util.Try[A] < S)(using Frame): A < (S & Abort[Throwable]) =
        _try.map(Abort.get(_))

    /** Logs an informational message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logInfo[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.info(m))

    /** Logs an informational message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logInfo[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.info(m, e)))

    /** Logs a warning message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logWarn[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.warn(m))

    /** Logs a warning message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logWarn[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.warn(m, e)))

    /** Logs a debug message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logDebug[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.debug(m))

    /** Logs a debug message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logDebug[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.debug(m, e)))

    /** Logs an error message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logError[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.error(m))

    /** Logs an error message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logError[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.error(m, e)))

    /** Logs a trace message to the console.
      *
      * @param message
      *   The message to log
      * @return
      *   An effect that logs the message to the console
      */
    inline def logTrace[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.trace(m))

    /** Logs a trace message to the console with an error.
      *
      * @param message
      *   The message to log
      * @param err
      *   The error to log
      * @return
      *   An effect that logs the message and error to the console
      */
    inline def logTrace[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.trace(m, e)))

    /** Creates an effect that never completes using Async.
      *
      * @return
      *   An effect that never completes
      */
    def never(using Frame): Nothing < Async =
        Fiber.never.join
            *> IO(throw new IllegalStateException("Async.never completed"))

    /** Provides a dependency to an effect using Env.
      *
      * @param dependency
      *   The dependency to provide
      * @param effect
      *   The effect to provide the dependency to
      * @return
      *   An effect that provides the dependency to the effect
      */
    def provideFor[E, SD, A, SA, ER](
        dependency: => E < SD
    )(
        effect: A < (SA & Env[E | ER])
    )(
        using
        reduce: Reducible[Env[ER]],
        t: Tag[E],
        fl: Flat[A],
        frame: Frame
    ): A < (SA & SD & reduce.SReduced) =
        dependency.map(d => Env.run(d)(effect))

    /** Creates a scoped effect using Resource.
      *
      * @param resource
      *   The resource to create a scoped effect from
      * @return
      *   An effect that manages the resource lifecycle using Resource and IO effects
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
    def serviceWith[D](using Tag[D], Frame): [A, S] => (D => A < S) => A < (S & Env[D]) =
        [A, S] => (fn: D => (A < S)) => service[D].map(d => fn(d))

    /** Sleeps for a given duration using Async.
      *
      * @param duration
      *   The duration to sleep for
      * @return
      *   An effect that sleeps for the given duration
      */
    def sleep[S](duration: => Duration < S)(using Frame): Unit < (S & Async) =
        duration.map(d => Async.sleep(d))

    /** Suspends an effect using IO.
      *
      * @param effect
      *   The effect to suspend
      * @return
      *   An effect that suspends the given effect
      */
    def suspend[A, S](effect: => A < S)(using Frame): A < (S & IO) =
        IO(effect)

    /** Suspends an effect using IO and handles any exceptions that occur to Abort[Throwable].
      *
      * @param effect
      *   The effect to suspend
      * @return
      *   An effect that suspends the given effect and handles any exceptions that occur to Abort[Throwable]
      */
    def suspendAttempt[A, S](effect: => A < S)(using
        Flat[A],
        Frame
    ): A < (S & IO & Abort[Throwable]) =
        IO(Abort.catching[Throwable](effect))

    /** Traverses a sequence of effects and collects the results.
      *
      * @param sequence
      *   The sequence of effects to traverse
      * @return
      *   An effect that traverses the sequence of effects and collects the results
      */
    def traverse[A, S, S1](
        sequence: => Seq[A < S] < S1
    )(using Frame): Seq[A] < (S & S1) =
        sequence.flatMap((seq: Seq[A < S]) => Kyo.collect(seq))

    /** Traverses a sequence of effects and discards the results.
      *
      * @param sequence
      *   The sequence of effects to traverse
      * @return
      *   An effect that traverses the sequence of effects and discards the results
      */
    def traverseDiscard[A, S, S1](
        sequence: => Seq[A < S] < S1
    )(using Frame): Unit < (S & S1) =
        sequence.flatMap(Kyo.collectDiscard)

    /** Traverses a sequence of effects in parallel and collects the results.
      *
      * @param sequence
      *   The sequence of effects to traverse in parallel
      * @return
      *   An effect that traverses the sequence of effects in parallel and collects the results
      */
    def traversePar[A, S](
        sequence: => Seq[A < Async] < S
    )(using Flat[A], Frame): Seq[A] < (S & Async) =
        sequence.map(seq => foreachPar(seq)(identity))

    /** Traverses a sequence of effects in parallel and discards the results.
      *
      * @param sequence
      *   The sequence of effects to traverse in parallel
      * @return
      *   An effect that traverses the sequence of effects in parallel and discards the results
      */
    def traverseParDiscard[A, S](
        sequence: => Seq[A < Async] < S
    )(using Flat[A < S], Frame): Unit < (S & Async) =
        sequence.map(seq =>
            foreachPar(seq.map(_.unit))(identity).unit
        )
end extension
