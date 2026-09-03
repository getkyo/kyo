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
                rt  <- AeronPlatform.embedded(dir.unsafe.show)
                _ <- Scope.ensure(
                    Sync.Unsafe.defer {
                        rt.close()
                        discard(embeddedRuntimeReleases.incrementAndGet())
                    }.andThen(Path.run(dir.removeAll))
                )
                out <- body(rt)
            yield out
        }

end Test
