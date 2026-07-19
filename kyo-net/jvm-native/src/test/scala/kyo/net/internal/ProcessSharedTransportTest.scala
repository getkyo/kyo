package kyo.net.internal

import kyo.*
import kyo.internal.Diagnostics
import kyo.net.NetConfig
import kyo.net.NetPlatform
import kyo.net.Test

/** Guards the process-shared transport marker (see [[ProcessSharedTransport]]) and the sharing it marks.
  *
  * `NetPlatform.transport` is one instance for the whole process, shared by every client and server and never closed, so its drivers MUST carry
  * the `processSharedTransport` frame: their idle kept-alive carriers are expected to sit armed forever, and the end-of-run stranded-op /
  * fiber-leak gate allowlists them on that frame. `NetPlatform.ownedTransport()` is the escape hatch a caller closes itself, so it MUST stay
  * unmarked, or a genuinely leaked owned transport would be silently excused.
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
            // Force the one shared instance, then assert marked drivers EXIST rather than that a delta appeared. There is only one shared
            // transport, so it cannot be rebuilt to produce a delta, and another leaf having already forced it must not change the outcome.
            discard(NetPlatform.transport)
            val marked = markedDrivers
            assert(marked.nonEmpty, "the shared transport must mark its drivers with the processSharedTransport frame")

            // An owned transport builds its own drivers; none of them may be marked, or a genuinely leaked owned transport would be excused.
            val owned      = NetPlatform.ownedTransport()
            val afterOwned = markedDrivers
            discard(owned.close())
            assert(afterOwned == marked, "ownedTransport must not carry the processSharedTransport marker")
        }
    }

    "there is exactly one shared transport, and an owned one is not it" in {
        Sync.Unsafe.defer {
            // Settings are passed per operation, so wanting different ones is never a reason to build a second transport. Every caller of the
            // shared instance gets this one, whatever config it intends to pass to its connects and listens.
            val a = NetPlatform.transport
            val b = NetPlatform.transport
            assert(a eq b, "every caller must get the same instance, or clients and servers would each build their own I/O fabric")

            val owned = NetPlatform.ownedTransport()
            assert(owned ne a, "ownedTransport must hand back an isolated instance, never the shared one")
            discard(owned.close())
            succeed
        }
    }

    "serving differing per-operation settings builds no additional drivers" in {
        Sync.Unsafe.defer {
            // The other half of the no-new-transport proof: TransportPerOperationConfigTest asserts each operation observes its own settings
            // and that the instance is the same one; this asserts the shared transport's driver set does not grow while doing it. A per-config
            // transport (the shape this model replaced) would add a whole pool of drivers here.
            discard(NetPlatform.transport)
            val before = Diagnostics.probeAll().size

            val transport = NetPlatform.transport
            transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
                val a = transport.connect("127.0.0.1", listener.port, config = NetConfig(channelCapacity = 2, readChunkSize = 1024))
                val b = transport.connect("127.0.0.1", listener.port, config = NetConfig(channelCapacity = 32, readChunkSize = 65536))
                a.safe.get.map { connA =>
                    b.safe.get.map { connB =>
                        val after = Diagnostics.probeAll().size
                        connA.close()
                        connB.close()
                        listener.close()
                        assert(
                            after == before,
                            s"two connections with different settings must not grow the shared transport's driver set: $before -> $after"
                        )
                    }
                }
            }
        }
    }

end ProcessSharedTransportTest
