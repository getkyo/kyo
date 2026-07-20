package kyo.net

import kyo.*
import kyo.net.internal.TlsProviderPlatform

/** The acceptance gate for the per-operation configuration model: a setting reaches the operation that was given it, on the ONE shared
  * [[NetPlatform.transport]], with no new transport built.
  *
  * Threading a parameter through a signature proves nothing on its own, and neither does a test that builds a transport per setting: that only
  * shows the old construction-captured behavior still works. Each leaf here instead runs TWO operations with DIFFERENT values for one field on
  * the SAME transport instance and asserts each observes its own. That shape fails if a value is captured at construction, cached per config, or
  * dropped on the floor, which are the three ways this model can silently regress.
  *
  * The transport identity is asserted alongside every leaf, so "each caller got its own setting" is never satisfied by having quietly built a
  * second transport. The driver-count half of the no-new-transport proof lives in the jvm-native `ProcessSharedTransportTest`, which can reach
  * `Diagnostics` to count them.
  */
class TransportPerOperationConfigTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumeTls(): Unit =
        if !TlsProviderPlatform.registered.exists(_.isAvailable) then cancel("no TLS provider available on this backend")

    // 192.0.2.1 is in RFC 5737 TEST-NET-1: reserved and routable but unanswered, so a TCP connect parks in SYN_SENT until a deadline fires
    // rather than being refused. The same black hole TransportConnectTimeoutProducedTest uses.
    private val blackHoleHost = "192.0.2.1"
    private val blackHolePort = 80

    "channelCapacity is per connection, not per transport" in {
        given Frame   = Frame.internal
        val transport = NetPlatform.transport
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
            transport.connect("127.0.0.1", listener.port, config = NetConfig(channelCapacity = 4)).safe.get.map { small =>
                transport.connect("127.0.0.1", listener.port, config = NetConfig(channelCapacity = 64)).safe.get.map { large =>
                    // Read both before closing anything: the point is that two live connections on one transport carry different capacities
                    // at the same time, which a construction-captured value could not produce.
                    val smallCapacity = small.inbound.capacity
                    val largeCapacity = large.inbound.capacity
                    small.close()
                    large.close()
                    listener.close()
                    assert(smallCapacity == 4, s"the connection that asked for 4 got $smallCapacity")
                    assert(largeCapacity == 64, s"the connection that asked for 64 got $largeCapacity")
                    assert(NetPlatform.transport eq transport, "both connections must have come from the one shared transport")
                }
            }
        }
    }

    "an operation that passes no config gets the documented defaults" in {
        given Frame   = Frame.internal
        val transport = NetPlatform.transport
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
            transport.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                val capacity = conn.inbound.capacity
                conn.close()
                listener.close()
                // The no-config path must resolve to the companion constant, not to whatever some other caller last passed.
                assert(capacity == NetConfig.DefaultChannelCapacity, s"expected the default capacity, got $capacity")
            }
        }
    }

    "connectTimeout is per connect, not per transport".notNative in {
        given Frame   = Frame.internal
        val transport = NetPlatform.transport
        val tight     = 200.millis
        val generous  = 30.seconds
        // Both connects go to the same black hole on the same transport. The tight one must fail with ITS OWN deadline while the generous one
        // is still parked: a shared or construction-captured deadline would either fail both or neither.
        Fiber.initUnscoped(
            Abort.run[NetException](transport.connect(blackHoleHost, blackHolePort, generous).safe.get)
        ).map { generousFiber =>
            Abort.run[NetException | Closed | Timeout](
                Async.timeout(10.seconds)(transport.connect(blackHoleHost, blackHolePort, tight).safe.get)
            ).map { tightOutcome =>
                // The generous connect must STILL BE PARKED at this moment. Without this check the leaf proves only that the tight connect
                // timed out, which a single shared deadline would also produce: the assertions below would pass unchanged if both connects
                // were racing one 200ms timer. Poll before interrupting, since interrupting settles it.
                generousFiber.poll.map { generousStillPending =>
                    assert(
                        generousStillPending.isEmpty,
                        s"the connect that asked for $generous must still be parked when the $tight one has already fired; " +
                            s"it settled as $generousStillPending, which is what a shared or construction-captured deadline looks like"
                    )
                    generousFiber.interrupt
                }.map { _ =>
                    tightOutcome match
                        case Result.Failure(e: NetConnectTimeoutException) =>
                            assert(
                                e.timeout == tight,
                                s"the connect that asked for $tight must fail with its own deadline, got ${e.timeout}"
                            )
                            assert(NetPlatform.transport eq transport, "the connect must have used the one shared transport")
                        case other =>
                            assert(
                                false,
                                s"expected the tight per-connect deadline to produce NetConnectTimeoutException($tight), got $other " +
                                    "(a Timeout means no deadline was armed for this call)"
                            )
                    end match
                }
            }
        }
    }

    "two listeners on one transport reap stalled handshakes on their own deadlines" in {
        assumeTls()
        given Frame = Frame.internal
        TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
            val transport = NetPlatform.transport
            val material  = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
            // Same transport, same TLS material, two deadlines. A plaintext client completes each TCP accept and never sends a ClientHello, so
            // both server handshakes park; only the listener that asked for a finite deadline may reap its connection.
            val reaping   = material.copy(handshakeTimeout = 150.millis)
            val unbounded = material.copy(handshakeTimeout = Duration.Infinity)
            transport.listenTls("127.0.0.1", 0, 16, reaping)(_ => ()).safe.get.map { reapingListener =>
                transport.listenTls("127.0.0.1", 0, 16, unbounded)(_ => ()).safe.get.map { unboundedListener =>
                    transport.connect("127.0.0.1", reapingListener.port).safe.get.map { reapedClient =>
                        transport.connect("127.0.0.1", unboundedListener.port).safe.get.map { heldClient =>
                            // The reaped side closes the accepted fd, which this client observes as its inbound terminating.
                            Abort.run[Timeout](Async.timeout(3.seconds)(Abort.run[Closed](reapedClient.inbound.safe.take))).map { reaped =>
                                // The unbounded side must still be held open at that moment, proving the two listeners did not share a deadline.
                                Abort.run[Timeout](Async.timeout(500.millis)(Abort.run[Closed](heldClient.inbound.safe.take))).map { held =>
                                    reapedClient.close()
                                    heldClient.close()
                                    reapingListener.close()
                                    unboundedListener.close()
                                    assert(reaped.isSuccess, s"the 150ms listener must reap its stalled handshake, got $reaped")
                                    assert(held.isFailure, s"the Infinity listener must not reap its stalled handshake, got $held")
                                    assert(
                                        NetPlatform.transport eq transport,
                                        "both listeners must have come from the one shared transport"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end TransportPerOperationConfigTest
