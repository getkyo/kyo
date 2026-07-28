package kyo.net.internal

import kyo.*
import kyo.internal.Diagnostics
import kyo.net.NetConfig
import kyo.net.NetPlatform
import kyo.net.Test

/** Guards the process-shared transport marker (see [[ProcessSharedTransport]]) and the sharing it marks.
  *
  * `NetPlatform.transport` is the single instance for the whole process, shared by every client and server and never closed, so its drivers
  * MUST carry the `processSharedTransport` frame: their idle kept-alive carriers are expected to sit armed forever, and the end-of-run
  * stranded-op / fiber-leak gate allowlists them on that frame.
  *
  * An unmarked shared transport fails the fork's stranded-op check for every module using the process-wide `HttpClient.*` API with a keep-alive
  * connection.
  */
class ProcessSharedTransportTest extends Test:

    import AllowUnsafe.embrace.danger

    private def markedDrivers: Set[String] =
        Diagnostics.probeAll().iterator.map(_._1).filter(_.contains("processSharedTransport")).toSet

    /** The marker is carried by a driver's Diagnostics registration, and only the posix drivers register one: `NioIoDriver` registers no probe
      * at all, so on a run that forces the nio floor there is nothing for the frame to appear on and nothing for the leak-check allowlist to
      * match. Cancel there rather than assert an empty set, which would report the forced backend as a marker regression.
      */
    private def assumeRegisteringDriver(): Unit =
        if Diagnostics.probeAll().isEmpty then cancel("the active backend registers no Diagnostics probes; the marker has nothing to mark")

    "the shared transport marks its drivers" in {
        Sync.Unsafe.defer {
            discard(NetPlatform.transport)
            assumeRegisteringDriver()
            // Force the one shared instance, then assert marked drivers EXIST rather than that a delta appeared. There is only one shared
            // transport, so it cannot be rebuilt to produce a delta, and another leaf having already forced it must not change the outcome.
            discard(NetPlatform.transport)
            val marked = markedDrivers
            assert(marked.nonEmpty, "the shared transport must mark its drivers with the processSharedTransport frame")
        }
    }

    "there is exactly one shared transport" in {
        Sync.Unsafe.defer {
            // Settings are passed per operation, so wanting different ones is never a reason to build a second transport. Every caller of the
            // shared instance gets this one, whatever config it intends to pass to its connects and listens.
            val a = NetPlatform.transport
            val b = NetPlatform.transport
            assert(a eq b, "every caller must get the same instance, or clients and servers would each build their own I/O fabric")
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
                Scope.ensure(Sync.defer(listener.close())).andThen {
                    val a = transport.connect("127.0.0.1", listener.port, config = NetConfig(channelCapacity = 2, readChunkSize = 1024))
                    val b = transport.connect("127.0.0.1", listener.port, config = NetConfig(channelCapacity = 32, readChunkSize = 65536))
                    a.safe.get.map { connA =>
                        Scope.ensure(Sync.defer(connA.close())).andThen {
                            b.safe.get.map { connB =>
                                Scope.ensure(Sync.defer(connB.close())).andThen {
                                    val after = Diagnostics.probeAll().size
                                    assert(
                                        after == before,
                                        s"two connections with different settings must not grow the shared transport's driver set: $before -> $after"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end ProcessSharedTransportTest
