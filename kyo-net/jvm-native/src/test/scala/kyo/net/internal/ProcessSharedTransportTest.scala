package kyo.net.internal

import kyo.*
import kyo.internal.Diagnostics
import kyo.net.NetPlatform
import kyo.net.Test
import kyo.net.TransportConfig

/** Guards the process-shared transport marker (see [[ProcessSharedTransport]]) and the sharing it marks.
  *
  * `NetPlatform.transport(config)` hands back one instance per distinct config, shared for the life of the process and never closed, so its
  * drivers MUST carry the `processSharedTransport` frame: their idle kept-alive carriers are expected to sit armed forever, and the end-of-run
  * stranded-op / fiber-leak gate allowlists them on that frame. `NetPlatform.ownedTransport(config)` is the escape hatch a caller closes
  * itself, so it MUST stay unmarked, or a genuinely leaked owned transport would be silently excused.
  *
  * A regression in either direction is costly: an unmarked shared transport fails the fork's stranded-op check for every module using the
  * process-wide `HttpClient.*` API with a keep-alive connection, and a marked owned transport hides real leaks.
  */
class ProcessSharedTransportTest extends Test:

    import AllowUnsafe.embrace.danger

    private def markedDrivers: Set[String] =
        Diagnostics.probeAll().iterator.map(_._1).filter(_.contains("processSharedTransport")).toSet

    "the shared transport marks its drivers; an owned one does not" in {
        Sync.Unsafe.defer {
            // Force the default shared instance first so its marked drivers already exist before measuring. Only the shared path builds marked
            // transports, so any marked driver appearing while an owned one is built is a real regression; concurrent owned builds by other
            // leaves add only unmarked drivers and cannot perturb the marked set.
            discard(NetPlatform.transport)
            val baseline = markedDrivers

            val owned      = NetPlatform.ownedTransport(TransportConfig.default)
            val afterOwned = markedDrivers
            discard(owned.close())

            // A config no other leaf will ask for, so the drivers it adds are attributable to this call.
            val distinct = TransportConfig.default.copy(channelCapacity = TransportConfig.default.channelCapacity + 17)
            discard(NetPlatform.transport(distinct))
            val addedByShared = markedDrivers -- baseline

            assert(afterOwned == baseline, "ownedTransport must not carry the processSharedTransport marker")
            assert(addedByShared.nonEmpty, "the shared transport must mark its drivers with the processSharedTransport frame")
        }
    }

    "the shared transport is one instance per config, and the default config is not a special case" in {
        Sync.Unsafe.defer {
            val a = NetPlatform.transport(TransportConfig.default)
            val b = NetPlatform.transport(TransportConfig.default)
            assert(a eq b, "the same config must yield the same shared instance, or clients and servers would each build their own")
            assert(
                NetPlatform.transport eq a,
                "the no-arg singleton must be the same instance as transport(TransportConfig.default), not a second shared transport"
            )
            val other = NetPlatform.transport(TransportConfig.default.copy(readChunkSize = TransportConfig.default.readChunkSize * 2))
            assert(other ne a, "a different config must yield a different instance, since its settings are captured at construction")
            succeed
        }
    }

end ProcessSharedTransportTest
