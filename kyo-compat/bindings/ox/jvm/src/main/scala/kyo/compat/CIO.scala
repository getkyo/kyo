package kyo.compat

import ox.Ox
import scala.annotation.nowarn
import scala.concurrent.Await
import scala.concurrent.Future as ScalaFuture
import scala.concurrent.duration.Duration as ScalaDuration
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS
import scala.util.control.NonFatal

/** Underlying carrier is `(Int, Ox) => A`: the `Int` is the current `flatMap` nesting depth, the `Ox` is the structured-concurrency scope.
  * Errors are encoded as `throw`; recovery uses `try/catch` on `Throwable` (via `liftToTry`). Deep `flatMap` chains are stack-safe:
  * `flatMap` threads the depth, and once it reaches `LIMIT` it re-runs the remaining chain inside a fresh `ox.forkUnsupervised` — a virtual
  * thread with a fresh stack — and joins it, capping JVM stack growth. `zip` arities 2-4 use `ox.par`; arities 5-7 use fork/join. `cede`
  * yields the virtual thread. `blocking` wraps in `scala.concurrent.blocking`.
  */
opaque type CIO[+A] = (Int, Ox) => A

object CIO:

    /** Max `flatMap` nesting per stack segment; beyond it, the chain hops to a fresh fork. */
    private inline val LIMIT = 256

    /** Re-runs `body` on a fresh virtual-thread stack (an unsupervised fork) and joins it. `join()` on an unsupervised fork rethrows a
      * failed body as a plain exception without crashing the surrounding scope.
      */
    private inline def bounce[T](inline body: => T)(using Ox): T = ox.forkUnsupervised(body).join()

    inline def lift[A](inline f: Ox ?=> A): CIO[A] =
        (_: Int, ox: Ox) => f(using ox)

    /** Suspend side-effecting code that produces the backend computation. Ox has no separate eager effect type -- its carrier is itself the
      * suspension -- so `deferLift` aliases [[CIO.lift]]; it exists for API uniformity with the other bindings.
      */
    inline def deferLift[A](inline f: Ox ?=> A): CIO[A] = lift(f)

    /** Wrap an already-evaluated, side-effect-free value in a CIO. For side-effecting expressions use [[CIO.defer]] instead.
      */
    inline def value[A](inline a: A): CIO[A] = (_: Int, _: Ox) => a

    inline def unit: CIO[Unit] = (_: Int, _: Ox) => ()

    inline def defer[A](inline thunk: => A): CIO[A] = lift(thunk)

    inline def fail(inline e: Throwable): CIO[Nothing] =
        deferLift(throw e)

    inline def get[A](inline t: scala.util.Try[A]): CIO[A] =
        deferLift(t.get)

    inline def fromScalaFuture[A](inline f: ScalaFuture[A]): CIO[A] =
        deferLift(Await.result(f, ScalaDuration.Inf))

    inline def acquireReleaseWith[A, B](
        inline acquire: CIO[A]
    )(
        inline release: A => CIO[Unit]
    )(
        inline use: A => CIO[B]
    ): CIO[B] =
        deferLift {
            val a = acquire.lower
            val useResult: scala.util.Try[B] =
                try scala.util.Success(use(a).lower)
                catch case t: Throwable if NonFatal(t) => scala.util.Failure(t)
            try release(a).lower
            catch
                case relT: Throwable if NonFatal(relT) =>
                    useResult match
                        case scala.util.Failure(useT) => useT.addSuppressed(relT)
                        case scala.util.Success(_)    => throw relT
            end try
            useResult.get
        }

    inline def ensure[A](
        inline cleanup: CIO[Unit]
    )(
        inline c: CIO[A]
    ): CIO[A] =
        acquireReleaseWith(CIO.unit)(_ => cleanup)(_ => c)

    inline def never: CIO[Nothing] =
        deferLift(ox.never)

    extension [A](inline self: CIO[A])

        inline def lower: Ox ?=> A = self(0, summon[Ox])

        inline def liftToTry: CIO[scala.util.Try[A]] =
            (_: Int, ox: Ox) =>
                try scala.util.Success(self.lower(using ox))
                catch case t: Throwable if NonFatal(t) => scala.util.Failure(t)

        inline def recover[A2 >: A](inline handler: Throwable => CIO[A2]): CIO[A2] =
            self.liftToTry.flatMap {
                case scala.util.Success(a) => CIO.value(a)
                case scala.util.Failure(t) => handler(t)
            }

        inline def fold[B](
            inline onSuccess: A => CIO[B],
            inline onFail: Throwable => CIO[B]
        ): CIO[B] =
            self.liftToTry.flatMap {
                case scala.util.Success(a) => onSuccess(a)
                case scala.util.Failure(t) => onFail(t)
            }

        inline def unit: CIO[Unit] = self.map(_ => ())

        inline def orElse[A2 >: A](inline that: CIO[A2]): CIO[A2] =
            self.liftToTry.flatMap {
                case scala.util.Success(a) => CIO.value(a)
                case scala.util.Failure(_) => that
            }

        inline def mapError(inline f: Throwable => Throwable): CIO[A] =
            self.liftToTry.flatMap {
                case scala.util.Success(a) => CIO.value(a)
                case scala.util.Failure(t) => CIO.fail(f(t))
            }

        inline def map[B](inline f: A => B): CIO[B] = self.flatMap(a => CIO.value(f(a)))

        inline def flatMap[B](f: A => CIO[B]): CIO[B] =
            (depth: Int, ox: Ox) =>
                if depth >= LIMIT then
                    // fresh stack: re-run the remaining chain at depth 0 inside an unsupervised fork
                    bounce(f(self(0, ox))(0, ox))(using ox)
                else
                    f(self(depth + 1, ox))(depth + 1, ox)

        inline def unsafeRun(using ec: scala.concurrent.ExecutionContext): ScalaFuture[A] =
            ScalaFuture {
                ox.supervised {
                    self.lower(using summon[Ox])
                }
            }
    end extension

    inline def sleep(inline d: FiniteDuration): CIO[Unit] =
        deferLift(ox.sleep(d))

    inline def now: CIO[java.time.Instant] =
        defer(java.time.Instant.now())

    inline def nowMonotonic: CIO[FiniteDuration] =
        defer(FiniteDuration(java.lang.System.nanoTime(), NANOSECONDS))

    inline def timeout[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[Option[A]] =
        deferLift {
            ox.timeoutOption(d)(c.lower)
        }

    inline def timeoutWithError[A](inline d: FiniteDuration)(inline e: Throwable)(inline c: CIO[A]): CIO[A] =
        deferLift {
            ox.timeoutOption(d)(c.lower).getOrElse(throw e)
        }

    inline def delay[A](inline d: FiniteDuration)(inline c: CIO[A]): CIO[A] =
        deferLift {
            ox.sleep(d)
            c.lower
        }

    inline def race[A](
        inline a: CIO[A],
        inline b: CIO[A]
    ): CIO[A] =
        deferLift {
            ox.raceSuccess(a.lower, b.lower)
        }

    inline def foreach[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: A => CIO[B]): CIO[CChunk[B]] =
        deferLift {
            val capturedOx = summon[Ox]
            val items      = coll.toList
            val thunks     = items.map(a => () => f(a).lower(using capturedOx))
            val results =
                if concurrency == Int.MaxValue then ox.par(thunks)
                else ox.parLimit(concurrency)(thunks)
            CChunk.lift(results.toVector)
        }

    inline def foreachIndexed[A, B](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: (Int, A) => CIO[B]): CIO[CChunk[B]] =
        deferLift {
            val capturedOx = summon[Ox]
            val items      = coll.toList.zipWithIndex
            val thunks     = items.map { case (a, i) => () => f(i, a).lower(using capturedOx) }
            val results =
                if concurrency == Int.MaxValue then ox.par(thunks)
                else ox.parLimit(concurrency)(thunks)
            CChunk.lift(results.toVector)
        }

    inline def foreachDiscard[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline f: A => CIO[Any]): CIO[Unit] =
        deferLift {
            val capturedOx = summon[Ox]
            val thunks     = coll.toList.map(a => () => f(a).lower(using capturedOx))
            val _ =
                if concurrency == Int.MaxValue then ox.par(thunks)
                else ox.parLimit(concurrency)(thunks)
        }

    @nowarn("msg=anonymous")
    inline def filter[A](
        inline coll: Iterable[A],
        inline concurrency: Int = Int.MaxValue
    )(inline p: A => CIO[Boolean]): CIO[CChunk[A]] =
        deferLift {
            val capturedOx = summon[Ox]
            val items      = coll.toList
            val thunks     = items.map(a => () => p(a).lower(using capturedOx))
            val flags =
                if concurrency == Int.MaxValue then ox.par(thunks)
                else ox.parLimit(concurrency)(thunks)
            CChunk.lift(items.zip(flags).collect { case (a, true) => a }.toVector)
        }

    inline def collectAll[A](
        inline coll: Iterable[CIO[A]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[CChunk[A]] =
        deferLift {
            val capturedOx = summon[Ox]
            val thunks     = coll.toList.map(c => () => c.lower(using capturedOx))
            val results =
                if concurrency == Int.MaxValue then ox.par(thunks)
                else ox.parLimit(concurrency)(thunks)
            CChunk.lift(results.toVector)
        }

    inline def collectAllDiscard(
        inline coll: Iterable[CIO[Any]],
        inline concurrency: Int = Int.MaxValue
    ): CIO[Unit] =
        deferLift {
            val capturedOx = summon[Ox]
            val thunks     = coll.toList.map(c => () => c.lower(using capturedOx))
            val _ =
                if concurrency == Int.MaxValue then ox.par(thunks)
                else ox.parLimit(concurrency)(thunks)
        }

    inline def async[A](inline register: ((scala.util.Try[A] => Unit) => Unit)): CIO[A] =
        deferLift {
            val cf = new java.util.concurrent.CompletableFuture[A]()
            val cb: scala.util.Try[A] => Unit = {
                case scala.util.Success(a) =>
                    val _ = cf.complete(a)
                    ()
                case scala.util.Failure(e) =>
                    val _ = cf.completeExceptionally(e)
                    ()
            }
            register(cb)
            try cf.get()
            catch
                case ee: java.util.concurrent.ExecutionException =>
                    val cause = ee.getCause
                    if cause ne null then throw cause else throw ee
            end try
        }
    end async

    inline def cede: CIO[Unit] =
        deferLift {
            val p = ox.forkUnsupervised(())
            p.join()
        }

    inline def blocking[A](inline thunk: => A): CIO[A] =
        deferLift {
            scala.concurrent.blocking {
                thunk
            }
        }

    inline def zip[A1, A2](
        inline a1: CIO[A1],
        inline a2: CIO[A2]
    ): CIO[(A1, A2)] =
        deferLift {
            ox.par(a1.lower, a2.lower)
        }

    inline def zip[A1, A2, A3](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3]
    ): CIO[(A1, A2, A3)] =
        deferLift {
            ox.par(a1.lower, a2.lower, a3.lower)
        }

    inline def zip[A1, A2, A3, A4](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4]
    ): CIO[(A1, A2, A3, A4)] =
        deferLift {
            ox.par(a1.lower, a2.lower, a3.lower, a4.lower)
        }

    inline def zip[A1, A2, A3, A4, A5](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5]
    ): CIO[(A1, A2, A3, A4, A5)] =
        deferLift {
            val f1 = ox.fork(a1.lower)
            val f2 = ox.fork(a2.lower)
            val f3 = ox.fork(a3.lower)
            val f4 = ox.fork(a4.lower)
            val f5 = ox.fork(a5.lower)
            (f1.join(), f2.join(), f3.join(), f4.join(), f5.join())
        }

    inline def zip[A1, A2, A3, A4, A5, A6](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6]
    ): CIO[(A1, A2, A3, A4, A5, A6)] =
        deferLift {
            val f1 = ox.fork(a1.lower)
            val f2 = ox.fork(a2.lower)
            val f3 = ox.fork(a3.lower)
            val f4 = ox.fork(a4.lower)
            val f5 = ox.fork(a5.lower)
            val f6 = ox.fork(a6.lower)
            (f1.join(), f2.join(), f3.join(), f4.join(), f5.join(), f6.join())
        }

    inline def zip[A1, A2, A3, A4, A5, A6, A7](
        inline a1: CIO[A1],
        inline a2: CIO[A2],
        inline a3: CIO[A3],
        inline a4: CIO[A4],
        inline a5: CIO[A5],
        inline a6: CIO[A6],
        inline a7: CIO[A7]
    ): CIO[(A1, A2, A3, A4, A5, A6, A7)] =
        deferLift {
            val f1 = ox.fork(a1.lower)
            val f2 = ox.fork(a2.lower)
            val f3 = ox.fork(a3.lower)
            val f4 = ox.fork(a4.lower)
            val f5 = ox.fork(a5.lower)
            val f6 = ox.fork(a6.lower)
            val f7 = ox.fork(a7.lower)
            (f1.join(), f2.join(), f3.join(), f4.join(), f5.join(), f6.join(), f7.join())
        }

end CIO
