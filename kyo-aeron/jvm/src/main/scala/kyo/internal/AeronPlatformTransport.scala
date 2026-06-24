package kyo.internal

import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import kyo.*

// JVM-only: launches MediaDriver.launchEmbedded(ctx) + Aeron.connect, JVM primitives; this object is the JVM platform-selection mechanism (mirrors kyo-net's NetPlatformTransport).
private[kyo] object AeronPlatformTransport:
    // embedded(dir): launches the driver + client in the Async row. The anonymous
    // AeronRuntime's close()(using AllowUnsafe) is a method definition (the capability is
    // required at the call site, not here), so the safe Sync.defer suffices, matching external().
    def embedded(dir: String)(using Frame): AeronRuntime < Async =
        Sync.defer {
            val driver = MediaDriver.launchEmbedded(
                // Route the driver to the caller-supplied unique directory so concurrent
                // embedded() calls never share the same CnC file. dirDeleteOnStart ensures
                // a clean state at start (no stale CnC from a prior run at the same path).
                new io.aeron.driver.MediaDriver.Context().aeronDirectoryName(dir).dirDeleteOnStart(true)
            )
            // Install a recording error handler on the Aeron context so fatal conductor
            // conditions (driver timeout, conductor service timeout, buffer full) are captured
            // in errorSlot rather than triggering the default handler (which spawns an exit
            // daemon thread on DriverTimeoutException). The slot is read by fatalError
            // at the offer/poll boundary and surfaced as TopicTransportFailedException.
            // AtomicReference[String] is JVM-only and correct here (jvm/src/main/scala/).
            val errorSlot = new java.util.concurrent.atomic.AtomicReference[String](null)
            val aeron = Aeron.connect(
                new Aeron.Context()
                    // Use dir directly (same string as the context passed to launchEmbedded),
                    // avoiding an extra JNI call through driver.aeronDirectoryName().
                    .aeronDirectoryName(dir)
                    .errorHandler { t =>
                        // Record a never-empty detail. t.getMessage is null for throwables with
                        // no message text; t.toString is always non-empty.
                        val msg = Maybe(t.getMessage).filter(_.nonEmpty).getOrElse(t.toString)
                        // Record the FIRST fatal error only, matching the C handler's first-wins slot.
                        discard(errorSlot.compareAndSet(null, msg))
                    }
            )
            new AeronRuntime:
                val transport: AeronTransport = new JvmAeronTransport(aeron, errorSlot)
                def close()(using AllowUnsafe): Unit =
                    aeron.close()
                    driver.close()
            end new
        }
    end embedded

    // external(aeronDir): connects an Aeron client to an external driver (caller-owned
    // driver lifecycle). The blocking Aeron.connect (~10 s on a driver-absent connect)
    // runs under Sync.defer (the safe tier). The scheduler detects the carrier as blocked
    // (thread-state and user-time monitoring) and keeps other fibers progressing on other
    // workers rather than stranding the pool, the established JVM precedent. The recording error
    // handler is installed for mid-session TopicTransportFailedException parity with embedded().
    // A driver-absent connect throws DriverTimeoutException (a subclass of AeronException)
    // in-band; Abort.catching[AeronException] maps it to a uniform TopicTransportFailedException.
    // Aeron.connect closes its own context on the thrown path, so no client leaks on failure.
    // close() closes ONLY the client, never a driver.
    def external(aeronDir: String)(using Frame): AeronRuntime < (Async & Abort[TopicTransportFailedException]) =
        Sync.defer {
            val errorSlot = new java.util.concurrent.atomic.AtomicReference[String](null)
            val aeron = Aeron.connect(
                new Aeron.Context()
                    .aeronDirectoryName(aeronDir)
                    .errorHandler { t =>
                        val msg = Maybe(t.getMessage).filter(_.nonEmpty).getOrElse(t.toString)
                        // Record the FIRST fatal error only, matching the C handler's first-wins slot.
                        discard(errorSlot.compareAndSet(null, msg))
                    }
            )
            new AeronRuntime:
                val transport: AeronTransport        = new JvmAeronTransport(aeron, errorSlot)
                def close()(using AllowUnsafe): Unit = aeron.close()
            end new
        }.handle(Abort.catching[io.aeron.exceptions.AeronException] { e =>
            TopicTransportFailedException(Maybe(e.getMessage).filter(_.nonEmpty).getOrElse(e.toString))
        })
    end external
end AeronPlatformTransport
