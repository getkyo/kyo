package kyo.internal

import io.aeron.Aeron
import java.util.concurrent.atomic.AtomicReference
import kyo.*

/** Connects aeron clients with survivable error handling.
  *
  * Aeron's default error handler terminates the whole JVM (System.exit) on fatal client errors such as a driver-liveness timeout. Merely
  * replacing it with a logging handler is not enough: the client conductor's AgentRunner catches the error and keeps servicing a dead
  * driver, re-raising the same fatal error in a hot loop that burns a core per client and keeps the JVM alive. The handler here logs the
  * error and, when it is fatal, closes the client from a dedicated reaper thread (closing from the conductor thread itself would
  * deadlock): pending operations then fail through the closed client with the transport's typed errors and the conductor thread exits.
  */
private[kyo] object AeronClients:

    /** Connects a client to `aeronDir` with the survivable error handler and `driverTimeoutMs` sized for loaded CI hosts. */
    def connect(aeronDir: String)(using frame: Frame): Aeron =
        val ref = new AtomicReference[Aeron]()
        val context = new Aeron.Context()
            .aeronDirectoryName(aeronDir)
            .driverTimeoutMs(30000)
            .errorHandler { t =>
                import AllowUnsafe.embrace.danger
                // Unsafe: invoked on aeron's conductor thread, outside any kyo effect.
                Log.live.unsafe.error("aeron client error", t)
                if t.isInstanceOf[io.aeron.exceptions.DriverTimeoutException] then
                    val client = ref.get()
                    if client != null then
                        val reaper = new Thread(
                            (
                                () =>
                                    try client.close()
                                    catch case _: Throwable => ()
                            ): Runnable,
                            "kyo-aeron-client-reaper"
                        )
                        reaper.setDaemon(true)
                        reaper.start()
                    end if
                end if
            }
        val client = Aeron.connect(context)
        ref.set(client)
        client
    end connect
end AeronClients
