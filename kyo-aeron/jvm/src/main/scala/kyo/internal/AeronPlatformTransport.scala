package kyo.internal

import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import kyo.*

/** JVM platform selector, backed by the io.aeron Java client. Mirrors kyo-net's
  * `NetPlatformTransport`.
  */
private[kyo] object AeronPlatformTransport:

    /** Launches an embedded media driver in `dir` and connects a client to it.
      *
      * `dir` must be unique per call (callers pass `Path.tempDir`), since Aeron otherwise routes
      * every runtime through the same CnC file; `dirDeleteOnStart` clears a stale one left by a
      * prior run.
      */
    def embedded(dir: String)(using Frame): AeronRuntime < Async =
        Sync.defer {
            val driver = MediaDriver.launchEmbedded(
                new io.aeron.driver.MediaDriver.Context().aeronDirectoryName(dir).dirDeleteOnStart(true)
            )
            // The recording error handler captures fatal conductor conditions (driver timeout,
            // conductor service timeout, buffer full) into errorSlot, which fatalError reads at the
            // offer/poll boundary. The default handler instead spawns an exit daemon thread on
            // DriverTimeoutException.
            val errorSlot = new java.util.concurrent.atomic.AtomicReference[String](null)
            val aeron = Aeron.connect(
                new Aeron.Context()
                    .aeronDirectoryName(dir)
                    .errorHandler { t =>
                        // getMessage is null for throwables with no message text; toString never is.
                        val msg = Maybe(t.getMessage).filter(_.nonEmpty).getOrElse(t.toString)
                        // First error wins, matching the C handler's slot.
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

    /** Connects a client to a caller-owned external driver at `aeronDir`.
      *
      * No driver is started, so the returned runtime closes only the client. `Aeron.connect` blocks
      * for ~10s when the driver is absent, which the scheduler absorbs through its thread-state and
      * user-time monitoring; it then throws `DriverTimeoutException` in-band, closing its own
      * context on the way out, so no client leaks.
      */
    def external(aeronDir: String)(using Frame): AeronRuntime < (Async & Abort[TopicTransportFailedException]) =
        Sync.defer {
            val errorSlot = new java.util.concurrent.atomic.AtomicReference[String](null)
            val aeron = Aeron.connect(
                new Aeron.Context()
                    .aeronDirectoryName(aeronDir)
                    .errorHandler { t =>
                        val msg = Maybe(t.getMessage).filter(_.nonEmpty).getOrElse(t.toString)
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
