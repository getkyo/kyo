package kyo.compat

import java.util.concurrent.CompletableFuture
import scala.reflect.ClassTag

/** Underlying carrier is `java.util.concurrent.CompletableFuture[A]`, a single-shot completable cell. `lift` and `lower` are identity since
  * the carrier is already a native `CompletableFuture`. `succeed`/`fail` return `true` on first completion and `false` on subsequent
  * attempts (first-wins). `get` blocks the calling thread and unwraps `ExecutionException` so the cause surfaces directly. `poll` maps a
  * cancelled future to `None`.
  */
opaque type CPromise[A] = CompletableFuture[A]

object CPromise:

    /** Allocates a fresh single-shot promise. */
    inline def init[A]: CIO[CPromise[A]] =
        CIO.defer(new CompletableFuture[A]())

    /** Wraps a native `CompletableFuture` as a `CPromise`. Identity on the carrier. */
    inline def lift[A](inline u: CompletableFuture[A]): CPromise[A] = u

    extension [A](inline self: CPromise[A])

        /** Unwraps to the native `CompletableFuture`. Identity on the carrier. */
        inline def lower: CompletableFuture[A] = self

        /** Attempts to complete the promise with `a`; returns `true` if this is the first completion. */
        inline def succeed(inline a: A): CIO[Boolean] =
            CIO.defer(self.complete(a))

        /** Attempts to complete the promise with failure `e`; returns `true` if this is the first completion. */
        inline def fail(inline e: Throwable): CIO[Boolean] =
            CIO.defer(self.completeExceptionally(e))

        /** Suspends until the promise is completed and returns its value (blocks the calling thread). */
        inline def get: CIO[A] =
            CIO.defer {
                try self.get()
                catch
                    case ee: java.util.concurrent.ExecutionException =>
                        val cause = ee.getCause
                        if cause ne null then throw cause else throw ee
            }

        /** Returns the current state without blocking: `None` if pending or cancelled, `Some(Try)` if completed. */
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

        /** `true` if the promise has been completed. */
        inline def done: CIO[Boolean] =
            CIO.defer(self.isDone)

    end extension

end CPromise
