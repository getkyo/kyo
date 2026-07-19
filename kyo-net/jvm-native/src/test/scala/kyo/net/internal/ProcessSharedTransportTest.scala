package kyo.net.internal

import kyo.*
import kyo.internal.Diagnostics
import kyo.net.NetPlatform
import kyo.net.Test
import kyo.net.TransportConfig

/** Guards the process-lifetime transport marker (see [[ProcessSharedTransport]]). The never-closed default HTTP client's transport is built
  * via `NetPlatform.processLifetimeTransport`, which MUST mark its drivers so their idle kept-alive carriers are allowlisted by the
  * end-of-run stranded-op / fiber-leak gate; an owned `NetPlatform.transport(config)` a caller closes MUST stay unmarked so a genuinely leaked
  * owned transport is still reported. A regression here (the default client borrowing the unmarked owned path) makes every module that uses
  * the process-wide `HttpClient.*` convenience API plus a keep-alive connection (kyo-pod, kyo-browser) fail its fork's stranded-op check.
  */
class ProcessSharedTransportTest extends Test:

    import AllowUnsafe.embrace.danger

    "processLifetimeTransport marks its drivers; owned transport(config) does not" in {
        Sync.Unsafe.defer {
            // Force the process-shared singleton first so its marked drivers already exist before we measure. Within kyo-net only the
            // singleton and this process-lifetime path build marked transports, so once the singleton is built, any marked driver that
            // appears while an owned transport is built is a real regression (and concurrent owned builds by other leaves add only
            // unmarked drivers, so they cannot perturb the marked set).
            discard(NetPlatform.transport)
            def markedDrivers: Set[String] =
                Diagnostics.probeAll().iterator.map(_._1).filter(_.contains("processSharedTransport")).toSet

            val baseline    = markedDrivers
            val owned       = NetPlatform.transport(TransportConfig.default)
            val ownedMarked = markedDrivers
            owned.close()

            val lifetime = NetPlatform.processLifetimeTransport(TransportConfig.default)
            val added    = markedDrivers -- baseline
            lifetime.close()

            assert(ownedMarked == baseline, "an owned transport(config) must not carry the processSharedTransport marker")
            assert(added.nonEmpty, "processLifetimeTransport must mark its drivers with the processSharedTransport frame")
        }
    }

end ProcessSharedTransportTest
