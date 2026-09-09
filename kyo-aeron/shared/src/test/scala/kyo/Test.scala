package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronRuntime

/** kyo-aeron test base on the kyo-test V3 framework.
  *
  * Leaves use the V3 DSL (`"name" in { ... }`); platform gating uses `.onlyJvm`/`.notJvm`/`.onlyJs`/`.onlyNative`.
  */
abstract class Test extends kyo.test.Test[Any]:

    /** Counts drivers actually released by [[withEmbeddedRuntime]]'s finalizer.
      *
      * The point of routing the close through a scope is that it runs whether or not the body finishes, and
      * that is a claim about what executes rather than about what the code says. This counter is what lets a
      * leaf assert the finalizer ran on a body that threw, instead of trusting the shape of the helper.
      */
    val embeddedRuntimeReleases = new java.util.concurrent.atomic.AtomicInteger(0)

    /** Runs `body` against an embedded driver whose teardown cannot be skipped.
      *
      * The driver is a real C media driver reached through FFI on every platform, so a runtime that is
      * never closed leaves its conductor thread running for the rest of the JVM's life. Closing it as an
      * ordinary step of a for-comprehension makes that teardown conditional on the body finishing: a failed
      * assert, an abort, or an interrupt anywhere in between skips it. Registering the close as a scope
      * finalizer instead makes it unconditional, which is what a native resource needs.
      */
    def withEmbeddedRuntime[A, S](prefix: String = "kyo-aeron-embedded-test")(body: AeronRuntime => A < (Async & S))(using
        Frame
    ): A < (Async & Abort[FileSystemException] & S) =
        Scope.run {
            for
                dir <- Path.run(Path.tempDir(prefix))
                // The driver creates its own directory inside `dir`, matching what AeronDriver and
                // Topic.run hand it: Aeron deletes and recreates a driver directory that already exists,
                // so a suite that pre-creates it pays that round trip on every leaf.
                rt <- AeronPlatform.embedded((dir / AeronDriver.mediaDirName).unsafe.show)
                _ <- Scope.ensure(
                    Sync.Unsafe.defer {
                        rt.close()
                        discard(embeddedRuntimeReleases.incrementAndGet())
                    }.andThen(Path.run(dir.removeAll))
                )
                out <- body(rt)
            yield out
        }

    /** Counts external drivers actually released by [[withExternalDriver]]'s finalizer. */
    val externalDriverReleases = new java.util.concurrent.atomic.AtomicInteger(0)

    /** Keeps an external Aeron driver running at `dir` for the duration of `body`.
      *
      * `dir` is the directory the driver will CREATE, not one already made for it: Aeron deletes and
      * recreates a driver directory that already exists, so callers pass a path inside their temp
      * directory (`root / AeronDriver.mediaDirName`) and let the driver make it.
      *
      * `AeronPlatform.embedded(dir)` launches a media driver, which is the "external" driver from
      * `Topic.run(aeronDir)`'s perspective, plus a keepalive client. The driver lives in an unscoped fiber
      * parked on a latch so the body connects its own independent client to the same directory.
      *
      * The latch release is a scope finalizer rather than a step of the comprehension. A body that
      * short-circuits via a typed `Abort` skips every remaining step, which would leave the fiber parked
      * forever and its C media driver and conductor thread alive for the rest of the process; a finalizer
      * runs on that path too. Registering it before the body also orders it correctly: finalizers unwind in
      * reverse, so the body's own clients close before the driver they connected to.
      */
    def withExternalDriver[A](dir: Path)(body: => A < (Async & Abort[TopicException] & Scope))(using
        Frame
    ): A < (Async & Abort[TopicException] & Scope) =
        for
            release <- Latch.init(1)
            ready   <- Latch.init(1)
            driverFiber <- Fiber.initUnscoped {
                AeronPlatform.embedded(dir.unsafe.show).map { runtime =>
                    // Signal readiness, then suspend on `release` so the driver stays alive for the body.
                    ready.release.andThen(release.await).andThen(Sync.Unsafe.defer {
                        runtime.close()
                        discard(externalDriverReleases.incrementAndGet())
                    })
                }
            }
            _      <- ready.await
            _      <- Scope.ensure(release.release.andThen(driverFiber.get))
            result <- body
        yield result

end Test
