package kyo.compat

import cats.effect.IO
import cats.effect.syntax.all.*
import cats.syntax.parallel.*
import scala.annotation.nowarn
import scala.concurrent.Future as ScalaFuture
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.reflect.ClassTag

/** Underlying carrier is `cats.effect.IO[A]`. E is phantom — `IO` has no typed error channel; recovery narrows via `ClassTag`-guarded
  * matching. `now` uses `IO.realTime` (not `IO.realTimeInstant`, which is JVM-only) for cross-platform uniformity. `zip` arity 2 uses
  * `IO.both`; arities 3–7 use `parTupled`. `race` uses `IO.race` (loser is interrupted natively).
  */
opaque type CIO[+A] = IO[A]

object CIO:

    inline def lift[A](inline io: IO[A]): CIO[A] = io

    /** Suspends side-effecting code that produces an `IO`; `IO.defer` defers the construction to effect-evaluation time. */
    inline def deferLift[A](inline io: => IO[A]): CIO[A] = lift(IO.defer(io))

    inline def value[A](inline a: A): CIO[A] = lift(IO.pure(a))

    inline def unit: CIO[Unit] = lift(IO.unit)

    inline def defer[A](inline thunk: => A): CIO[A] =
        lift(IO.delay(thunk))

    inline def fail(inline e: Throwable): CIO[Nothing] =
        lift(IO.raiseError(e))

    inline def get[A](inline t: scala.util.Try[A]): CIO[A] =
        lift(IO.fromTry(t))

    inline def fromScalaFuture[A](inline f: ScalaFuture[A]): CIO[A] =
        // fromFutureCancelable (not fromFuture): a scala Future cannot be cancelled, but the resulting IO must
        // still be cancelable so CIO.timeout / CIO.race can drop it. The cancel token is a no-op; without it
        // IO.fromFuture is uncancelable and cancelling a pending fromScalaFuture deadlocks the surrounding op.
        lift(IO.fromFutureCancelable(IO.pure(f -> IO.unit)))

    inline def acquireReleaseWith[A, B](
        inline acquire: CIO[A]
    )(
        inline release: A => CIO[Unit]
    )(
        inline use: A => CIO[B]
    ): CIO[B] =
        lift(IO.bracketFull(_ => acquire.lower)(use(_).lower)((a, _) => release(a).lower))

    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    ): CIO[A] =
        acquireReleaseWith(CIO.defer(()))(_ => cleanup)(_ => c)

    inline def never: CIO[Nothing] =
        lift(IO.never)

    extension [A](inline self: CIO[A])

        inline def lower: IO[A] = self

        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2]): CIO[A2] =
            lift(self.lower.handleErrorWith(t => handler(t).lower))

        inline def fold[B](
            inline onSuccess: A => CIO[B],
            inline onFail: Throwable => CIO[B]
        ): CIO[B] =
            lift(self.lower.redeemWith(
                t => onFail(t).lower,
                a => onSuccess(a).lower
            ))

        inline def liftToTry: CIO[scala.util.Try[A]] =
            lift(self.lower.attempt.map {
                case Right(a) => scala.util.Success(a)
                case Left(t)  => scala.util.Failure(t)
            })

        inline def unit: CIO[Unit] =
            lift(self.lower.void)

        inline def orElse[A2 >: A](inline that: CIO[A2]): CIO[A2] =
            lift(self.lower.handleErrorWith(_ => that.lower))

        @nowarn("msg=anonymous")
        inline def mapError(inline f: Throwable => Throwable): CIO[A] =
            lift(self.lower.adaptError {
                case t => f(t)
            })

        inline def map[B](inline f: A => B): CIO[B] =
            lift(self.lower.map(f))

        inline def flatMap[B](inline f: A => CIO[B]): CIO[B] =
            lift(self.lower.flatMap(a => f(a).lower))

        inline def unsafeRun: scala.concurrent.Future[A] =
            self.lower.unsafeToFuture()(using cats.effect.unsafe.IORuntime.global)
    end extension

    inline def sleep(inline d: FiniteDuration): CIO[Unit] =
        lift(IO.sleep(d))

    inline def now: CIO[java.time.Instant] =
        lift(IO.realTime.map(d => java.time.Instant.ofEpochMilli(d.toMillis)))

    inline def nowMonotonic: CIO[FiniteDuration] =
        lift(IO.monotonic.map(d => FiniteDuration(d.toNanos, NANOSECONDS)))

    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[Option[A]] =
        lift(c.lower.map(Option(_)).timeoutTo(d, IO.none[A]))

    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A]): CIO[A] =
        lift(c.lower.timeoutTo(d, IO.raiseError(e)))

    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[A] =
        lift(c.lower.delayBy(d))

    // `IO.race` returns `Either[A, B]`; `.merge` narrows since both legs share type A.
    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    ): CIO[A] =
        lift(IO.race(a.lower, b.lower).map(_.merge))

    inline def foreach[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: A => CIO[B]): CIO[CChunk[B]] =
        lift {
            val list = coll.toList
            val io =
                if concurrency == Int.MaxValue then list.parTraverse(a => f(a).lower)
                else IO.parTraverseN(concurrency)(list)(a => f(a).lower)
            io.map(lst => CChunk.lift(lst.toVector))
        }

    inline def foreachIndexed[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: (Int, A) => CIO[B]): CIO[CChunk[B]] =
        lift {
            val list = coll.toList.zipWithIndex
            val io =
                if concurrency == Int.MaxValue then list.parTraverse { case (a, i) => f(i, a).lower }
                else IO.parTraverseN(concurrency)(list) { case (a, i) => f(i, a).lower }
            io.map(lst => CChunk.lift(lst.toVector))
        }

    inline def foreachDiscard[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(f: A => CIO[Any]): CIO[Unit] =
        lift {
            val list = coll.toList
            if concurrency == Int.MaxValue then list.parTraverse_(a => f(a).lower)
            else IO.parTraverseN_(concurrency)(list)(a => f(a).lower)
        }

    @nowarn("msg=anonymous")
    inline def filter[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(p: A => CIO[Boolean]): CIO[CChunk[A]] =
        lift {
            val list = coll.toList
            if concurrency == Int.MaxValue then
                list.parFilterA(a => p(a).lower).map(lst => CChunk.lift(lst.toVector))
            else
                IO.parTraverseN(concurrency)(list)(a => p(a).lower.map(b => a -> b))
                    .map(pairs => CChunk.lift(pairs.collect { case (a, true) => a }.toVector))
            end if
        }

    inline def collectAll[A](
        inline coll: Iterable[CIO[A]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[CChunk[A]] =
        lift {
            val list = coll.toList.map(_.lower)
            if concurrency == Int.MaxValue then list.parSequence.map(lst => CChunk.lift(lst.toVector))
            else IO.parSequenceN(concurrency)(list).map(lst => CChunk.lift(lst.toVector))
        }

    inline def collectAllDiscard(
        inline coll: Iterable[CIO[Any]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[Unit] =
        lift {
            val list = coll.toList.map(_.lower)
            if concurrency == Int.MaxValue then list.parSequence_
            else IO.parSequenceN_(concurrency)(list)
        }

    inline def async[A](inline register: ((scala.util.Try[A] => Unit) => Unit)): CIO[A] =
        lift(IO.async_[A] { k =>
            val cb: scala.util.Try[A] => Unit = {
                case scala.util.Success(a) => k(Right(a))
                case scala.util.Failure(e) => k(Left(e))
            }
            register(cb)
        })

    inline def blocking[A](inline thunk: => A): CIO[A] =
        lift(IO.blocking(thunk))

    inline def cede: CIO[Unit] =
        lift(IO.cede)

    // Arity 2 uses `IO.both`; arities 3–7 use `parTupled` from `cats.syntax.parallel.*`.
    inline def zip[A1, A2](
        inline a1: CIO[A1],
        inline a2: CIO[A2]
    ): CIO[(A1, A2)] =
        lift(IO.both(a1.lower, a2.lower))

    inline def zip[A1, A2, A3](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3]
    ): CIO[(A1, A2, A3)] =
        lift((a1.lower, a2.lower, a3.lower).parTupled)

    inline def zip[A1, A2, A3, A4](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4]
    ): CIO[(A1, A2, A3, A4)] =
        lift((a1.lower, a2.lower, a3.lower, a4.lower).parTupled)

    inline def zip[A1, A2, A3, A4, A5](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5]
    ): CIO[(A1, A2, A3, A4, A5)] =
        lift((a1.lower, a2.lower, a3.lower, a4.lower, a5.lower).parTupled)

    inline def zip[A1, A2, A3, A4, A5, A6](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6]
    ): CIO[(A1, A2, A3, A4, A5, A6)] =
        lift((a1.lower, a2.lower, a3.lower, a4.lower, a5.lower, a6.lower).parTupled)

    inline def zip[A1, A2, A3, A4, A5, A6, A7](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6],
        inline a7: CIO[A7]
    ): CIO[(A1, A2, A3, A4, A5, A6, A7)] =
        lift((a1.lower, a2.lower, a3.lower, a4.lower, a5.lower, a6.lower, a7.lower).parTupled)

end CIO
