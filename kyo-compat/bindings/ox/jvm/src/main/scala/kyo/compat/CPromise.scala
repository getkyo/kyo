package kyo.compat

import java.util.concurrent.CompletableFuture
import scala.reflect.ClassTag

/** Backed by `java.util.concurrent.CompletableFuture[A]`. `get` unwraps `ExecutionException`. `poll` uses `ClassTag`-guarded cast;
  * `CancellationException` → `None`.
  */
opaque type CPromise[A] = CompletableFuture[A]

object CPromise:

    inline def init[A]: CIO[CPromise[A]] =
        CIO.defer(new CompletableFuture[A]())

    inline def lift[A](inline u: CompletableFuture[A]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        inline def lower: CompletableFuture[A] = self

        inline def succeed(inline a: A): CIO[Boolean] =
            CIO.defer(self.complete(a))

        inline def fail(inline e: Throwable): CIO[Boolean] =
            CIO.defer(self.completeExceptionally(e))

        inline def get: CIO[A] =
            CIO.defer {
                try self.get()
                catch
                    case ee: java.util.concurrent.ExecutionException =>
                        val cause = ee.getCause
                        if cause ne null then throw cause else throw ee
            }

        inline def poll: CIO[Option[scala.util.Try[A]]] =
            CIO.defer {
                if !self.isDone then Option.empty
                else
                    try
                        val v = self.get()
                        Option(scala.util.Success(v))
                    catch
                        case ee: java.util.concurrent.ExecutionException =>
                            val cause = ee.getCause
                            val t     = if cause ne null then cause else ee
                            Option(scala.util.Failure(t))
                        case _: java.util.concurrent.CancellationException =>
                            Option.empty
                end if
            }

        inline def done: CIO[Boolean] =
            CIO.defer(self.isDone)

    end extension

end CPromise
