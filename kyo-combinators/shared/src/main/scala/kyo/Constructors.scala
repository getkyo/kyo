package kyo

import kyo.kernel.Reducible
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.NotGiven
import scala.util.Success

extension (kyoObject: Kyo.type)
    def acquireRelease[A, S](acquire: => A < S)(release: A => Unit < IO)(using Frame): A < (S & Resource & IO) =
        acquire.map(a => Resource.ensure(release(a)).as(a))

    def addFinalizer(finalizer: => Unit < IO)(using Frame): Unit < (Resource & IO) =
        Resource.ensure(finalizer)

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

    def attempt[A, S](effect: => A < S)(using Flat[A], Frame): A < (S & Abort[Throwable]) =
        Abort.catching[Throwable](effect)

    def collect[A, S, A1, S1](
        sequence: => Seq[A] < S
    )(
        useElement: PartialFunction[A, A1 < S1]
    )(using Frame): Seq[A1] < (S & S1) =
        sequence.flatMap((seq: Seq[A]) => Kyo.collect(seq.collect(useElement)))

    def debugln[S](message: => String < S)(using Frame): Unit < (S & IO) =
        message.map(m => Console.println(m))

    def fail[E, S](error: => E < S)(using Frame): Nothing < (S & Abort[E]) =
        error.map(e => Abort.fail(e))

    def foreachPar[A, S, A1](
        sequence: => Seq[A] < S
    )(
        useElement: A => A1 < Async
    )(using Flat[A1], Frame): Seq[A1] < (S & Async) =
        sequence.map(seq => Async.parallel(seq.map(useElement)))

    def foreachParDiscard[A, S, Any](
        sequence: => Seq[A] < S
    )(
        useElement: A => Any < Async
    )(using Flat[Any], Frame): Unit < (S & Async) =
        sequence.map(seq => Async.parallel(seq.map(v => useElement(v).unit))).unit

    def fromAutoCloseable[A <: AutoCloseable, S](closeable: => A < S)(using Frame): A < (S & Resource & IO) =
        acquireRelease(closeable)(c => IO(c.close()))

    def fromEither[E, A, S](either: => Either[E, A] < S)(using Frame): A < (S & Abort[E]) =
        either.map(Abort.get(_))

    def fromOption[A, S](option: => Option[A] < S)(using Frame): A < (S & Abort[Maybe.Empty]) =
        option.map(o => Abort.get(o.toRight[Maybe.Empty](Maybe.Empty)))

    def fromMaybe[A, S](maybe: => Maybe[A] < S)(using Frame): A < (S & Abort[Maybe.Empty]) =
        maybe.map(m => Abort.get(m))

    def fromResult[E, A, S](result: => Result[E, A] < S)(using Frame): A < (S & Abort[E]) =
        result.map(Abort.get(_))

    def fromFuture[A: Flat, S](future: => Future[A] < S)(using Frame): A < (S & Async) =
        future.map(f => Fiber.fromFuture(f).map(_.get))

    def fromPromiseScala[A: Flat, S](promise: => scala.concurrent.Promise[A] < S)(using Frame): A < (S & Async) =
        promise.map(p => fromFuture(p.future))

    def fromSeq[A, S](sequence: => Seq[A] < S)(using Frame): A < (S & Choice) =
        sequence.map(seq => Choice.get(seq))

    def fromTry[A, S](_try: => scala.util.Try[A] < S)(using Frame): A < (S & Abort[Throwable]) =
        _try.map(Abort.get(_))

    inline def logInfo[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.info(m))

    inline def logInfo[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.info(m, e)))

    inline def logWarn[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.warn(m))

    inline def logWarn[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.warn(m, e)))

    inline def logDebug[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.debug(m))

    inline def logDebug[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.debug(m, e)))

    inline def logError[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.error(m))

    inline def logError[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.error(m, e)))

    inline def logTrace[S](message: => String < S): Unit < (S & IO) =
        message.map(m => Log.trace(m))

    inline def logTrace[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IO) =
        message.map(m => err.map(e => Log.trace(m, e)))

    def never(using Frame): Nothing < Async =
        Promise.init[Nothing, Nothing].join
            *> IO(throw new IllegalStateException("Async.never completed"))

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

    def scoped[A, S](resource: => A < (S & Resource))(using Frame): A < (Async & S) =
        Resource.run(resource)

    def service[D](using Tag[D], Frame): D < Env[D] =
        Env.get[D]

    def serviceWith[D](using Tag[D], Frame): [A, S] => (D => A < S) => A < (S & Env[D]) =
        [A, S] => (fn: D => (A < S)) => service[D].map(d => fn(d))

    def sleep[S](duration: => Duration < S)(using Frame): Unit < (S & Async) =
        duration.map(d => Async.sleep(d))

    def suspend[A, S](effect: => A < S)(using Frame): A < (S & IO) =
        IO(effect)

    def suspendAttempt[A, S](effect: => A < S)(using
        Flat[A],
        Frame
    ): A < (S & IO & Abort[Throwable]) =
        IO(Abort.catching[Throwable](effect))

    def traverse[A, S, S1](
        sequence: => Seq[A < S] < S1
    )(using Frame): Seq[A] < (S & S1) =
        sequence.flatMap((seq: Seq[A < S]) => Kyo.collect(seq))

    def traverseDiscard[A, S, S1](
        sequence: => Seq[A < S] < S1
    )(using Frame): Unit < (S & S1) =
        sequence.flatMap(Kyo.collectDiscard)

    def traversePar[A, S](
        sequence: => Seq[A < Async] < S
    )(using Flat[A], Frame): Seq[A] < (S & Async) =
        sequence.map(seq => foreachPar(seq)(identity))

    def traverseParDiscard[A, S](
        sequence: => Seq[A < Async] < S
    )(using Flat[A < S], Frame): Unit < (S & Async) =
        sequence.map(seq =>
            foreachPar(seq.map(_.unit))(identity).unit
        )
end extension
