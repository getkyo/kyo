package kyo

import kyo.Envs.HasEnvs
import scala.annotation.implicitNotFound
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.NotGiven


object KYO:
    def acquireRelease[A, S](acquire: => A < S)(release: A => Unit < IOs): A < (S & Resources) =
        acquire.map(a => Resources.ensure(release(a)).as(a))

    def addFinalizer(finalizer: => Unit < IOs): Unit < Resources =
        Resources.ensure(finalizer)

    def async[A](register: (A < Fibers => Unit) => Unit < Fibers)(
        using Flat[A]
    ): A < Fibers =
        for
            promise <- Fibers.initPromise[A]
            registerFn = (eff: A < Fibers) =>
                val effFiber = Fibers.init(eff)
                val updatePromise =
                    effFiber.map(_.onComplete(a => promise.complete(a).unit))
                val updatePromiseIO = Fibers.init(updatePromise).unit
                IOs.run(updatePromiseIO)
            _ <- register(registerFn)
            a <- promise.get
        yield a

    def attempt[A, S](effect: => A < S)(using Flat[A]): A < (S & Aborts[Throwable]) =
        Aborts.catching[Throwable](effect)

    def collect[A, S, A1, S1](
        sequence: => Seq[A] < S
    )(
        useElement: PartialFunction[A, A1 < S1]
    ): Seq[A1] < (S & S1) =
        sequence.flatMap((seq: Seq[A]) => Seqs.collect(seq.collect(useElement)))

    def debug[S](message: => String < S): Unit < (S & IOs) =
        message.map(m => Console.default.println(m))

    def fail[E, S](error: => E < S): Nothing < (S & Aborts[E]) =
        error.map(e => Aborts.fail(e))

    def foreach[A, S, A1, S1](
        sequence: => Seq[A] < S
    )(
        useElement: A => A1 < S1
    ): Seq[A1] < (S & S1) =
        collect(sequence)(a => useElement(a))

    def foreachDiscard[A, S, S1](
        sequence: => Seq[A] < S
    )(
        useElement: A => Any < S1
    ): Unit < (S & S1) =
        foreach(sequence)(useElement).unit

    def foreachPar[A, S, A1](
        sequence: => Seq[A] < S
    )(
        useElement: A => A1 < Fibers
    )(using Flat[A1]): Seq[A1] < (S & Fibers) =
        sequence.map(seq => Fibers.parallel(seq.map(useElement)))

    def foreachParDiscard[A, S, Any](
        sequence: => Seq[A] < S
    )(
        useElement: A => Any < Fibers
    )(using Flat[Any]): Unit < (S & Fibers) =
        sequence.map(seq => Fibers.parallel(seq.map(v => useElement(v).unit))).unit

    def fromAutoCloseable[A <: AutoCloseable, S](closeable: => A < S): A < (S & Resources) =
        acquireRelease(closeable)(c => IOs(c.close()))

    def fromEither[E, A](either: => Either[E, A]): A < Aborts[E] =
        Aborts.get(either)

    def fromFuture[A: Flat, S](future: => Future[A] < S): A < (S & Fibers) =
        future.map(f => Fibers.fromFuture(f))

    def fromPromiseScala[A: Flat, S](promise: => scala.concurrent.Promise[A] < S)
        : A < (S & Fibers) =
        promise.map(p => fromFuture(p.future))

    def fromOption[A, S](option: => Option[A] < S): A < (S & Options) =
        Options.get(option)

    def fromSeq[A, S](sequence: => Seq[A] < S): A < (S & Choices) =
        sequence.flatMap(seq => Choices.get(seq))

    def fromTry[A, S](_try: => scala.util.Try[A] < S): A < (S & Aborts[Throwable]) =
        _try.map(t => Aborts.get(t.toEither))

    inline def logInfo[S](message: => String < S): Unit < (S & IOs) =
        message.map(m => Logs.info(m))

    inline def logInfo[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IOs) =
        message.map(m => err.map(e => Logs.info(m, e)))

    inline def logWarn[S](message: => String < S): Unit < (S & IOs) =
        message.map(m => Logs.warn(m))

    inline def logWarn[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IOs) =
        message.map(m => err.map(e => Logs.warn(m, e)))

    inline def logDebug[S](message: => String < S): Unit < (S & IOs) =
        message.map(m => Logs.debug(m))

    inline def logDebug[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IOs) =
        message.map(m => err.map(e => Logs.debug(m, e)))

    inline def logError[S](message: => String < S): Unit < (S & IOs) =
        message.map(m => Logs.error(m))

    inline def logError[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IOs) =
        message.map(m => err.map(e => Logs.error(m, e)))

    inline def logTrace[S](message: => String < S): Unit < (S & IOs) =
        message.map(m => Logs.trace(m))

    inline def logTrace[S, S1](
        message: => String < S,
        err: => Throwable < S1
    ): Unit < (S & S1 & IOs) =
        message.map(m => err.map(e => Logs.trace(m, e)))

    def never: Nothing < Fibers =
        Fibers.never.join
            *> IOs(throw new IllegalStateException("Fibers.never completed"))

    val none: Nothing < Options =
        Options.empty

    def provide[D, SD, A, SA, E, SR](
        dependency: => D < SD
    )(
        effect: A < (SA & Envs[E])
    )(
        using
        he: HasEnvs[D, E] { type Remainder = SR },
        t: Tag[D],
        fl: Flat[A]
    ): A < (SA & SD & SR) =
        dependency.map(d => Envs.run[D, A, SA, E, SR](d)(effect))
        // V >: Nothing: Tag, T: Flat, S, VS, VR

    def scoped[A, S](resource: => A < (S & Resources)): A < (Fibers & S) =
        Resources.run(resource)

    def service[D](using Tag[D]): D < Envs[D] =
        Envs.get[D]

    def serviceWith[D](using Tag[D]): [A, S] => (D => A < S) => A < (S & Envs[D]) =
        [A, S] => (fn: D => (A < S)) => service[D].map(d => fn(d))

    def sleep[S](duration: => Duration < S): Unit < (S & Fibers) =
        duration.map(d => Fibers.sleep(d))

    def suspend[A, S](effect: => A < S): A < (S & IOs) =
        IOs(effect)

    def suspendAttempt[A, S](effect: => A < S)(using
        Flat[A]
    ): A < (S & IOs & Aborts[Throwable]) =
        IOs(Aborts.catching[Throwable](effect))

    def traverse[A, S, S1](
        sequence: => Seq[A < S] < S1
    ): Seq[A] < (S & S1) =
        sequence.flatMap((seq: Seq[A < S]) => Seqs.collect(seq))

    def traverseDiscard[A, S, S1](
        sequence: => Seq[A < S] < S1
    ): Unit < (S & S1) =
        sequence.flatMap { (seq: Seq[A < S]) =>
            Seqs.collect(seq.map(_.map(_ => ()))).unit
        }
    end traverseDiscard

    def traversePar[A, S](
        sequence: => Seq[A < Fibers] < S
    )(using Flat[A]): Seq[A] < (S & Fibers) =
        sequence.map(seq => foreachPar(seq)(identity))

    def traverseParDiscard[A, S](
        sequence: => Seq[A < Fibers] < S
    )(using Flat[A < S]): Unit < (S & Fibers) =
        sequence.map(seq =>
            foreachPar(seq.map(_.discard))(identity).discard
        )
end KYO
