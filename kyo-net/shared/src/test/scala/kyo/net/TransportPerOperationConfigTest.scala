package kyo.net

import kyo.*

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
  *
  * The deadline leaves are the exception: a deadline fires at an exact virtual instant only on a transport whose clock the leaf controls, so
  * each builds one transport of its own and runs both operations on it. The shape is the same, two values on one instance.
  */
class TransportPerOperationConfigTest extends Test:

    import AllowUnsafe.embrace.danger

    // 192.0.2.1 is in RFC 5737 TEST-NET-1: reserved and routable but unanswered, so a TCP connect parks in SYN_SENT until a deadline fires
    // rather than being refused. The same black hole TransportConnectTimeoutProducedTest uses.
    private val blackHoleHost = "192.0.2.1"
    private val blackHolePort = 80

    "channelCapacity is per connection, not per transport" in {
        given Frame   = Frame.internal
        val transport = NetPlatform.transport
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
            // Guards every acquisition above if a later one fails: e.g. if the second connect aborts, `small` and `listener` would otherwise
            // never reach their trailing close() calls below.
            Scope.ensure(Sync.defer(listener.close())).andThen {
                transport.connect("127.0.0.1", listener.port, config = NetConfig(channelCapacity = 4)).safe.get.map {
                    small =>
                        Scope.ensure(Sync.defer(small.close())).andThen {
                            transport.connect(
                                "127.0.0.1",
                                listener.port,
                                config = NetConfig(channelCapacity = 64)
                            ).safe.get.map { large =>
                                Scope.ensure(Sync.defer(large.close())).andThen {
                                    // Read both before closing anything: the point is that two live connections on one transport carry different
                                    // capacities at the same time, which a construction-captured value could not produce.
                                    val smallCapacity = small.inbound.capacity
                                    val largeCapacity = large.inbound.capacity
                                    small.close()
                                    large.close()
                                    listener.close()
                                    assert(smallCapacity == 4, s"the connection that asked for 4 got $smallCapacity")
                                    assert(largeCapacity == 64, s"the connection that asked for 64 got $largeCapacity")
                                    assert(
                                        NetPlatform.transport eq transport,
                                        "both connections must have come from the one shared transport"
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

    "a channel capacity of zero or less makes rendezvous pump channels that still round-trip" - eachBackend { transport =>
        Kyo.foreach(Chunk(0, -1)) { capacity =>
            val config = NetConfig(channelCapacity = capacity)
            for
                listener <- transport.listen("127.0.0.1", 0, 16, config) { conn =>
                    discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Abort.run[Closed] {
                        Loop.foreach(conn.inbound.safe.take.map(chunk => conn.outbound.safe.put(chunk).andThen(Loop.continue)))
                    }.andThen(Sync.defer(conn.close())))))
                }.safe.get
                _      <- Scope.ensure(Sync.defer(listener.close()))
                client <- transport.connect("127.0.0.1", listener.port, config = config).safe.get
                _      <- Scope.ensure(Sync.defer(client.close()))
                message = s"rendezvous $capacity".getBytes("UTF-8")
                _      <- client.outbound.safe.put(Span.fromUnsafe(message))
                echoed <- Loop(Array.emptyByteArray) { acc =>
                    if acc.length >= message.length then Loop.done(acc)
                    else client.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
                }
            yield (capacity, client.inbound.capacity, new String(echoed, "UTF-8"))
            end for
        }.map { outcomes =>
            assert(outcomes == Chunk((0, 0, "rendezvous 0"), (-1, 0, "rendezvous -1")))
        }
    }

    "an operation that passes no config gets the documented defaults" in {
        given Frame   = Frame.internal
        val transport = NetPlatform.transport
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
            // Guards the listener if `transport.connect` below fails before reaching the trailing `listener.close()`.
            Scope.ensure(Sync.defer(listener.close())).andThen {
                transport.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                    val capacity = conn.inbound.capacity
                    conn.close()
                    listener.close()
                    // The no-config path must resolve to the companion constant, not to whatever some other caller last passed.
                    assert(capacity == NetConfig.DefaultChannelCapacity, s"expected the default capacity, got $capacity")
                }
            }
        }
    }

    "connectTimeout is per connect, not per transport" - eachBackendOnClock { (transport, tc) =>
        if kyo.internal.Platform.isNative then cancel("a TEST-NET-1 connect can fail fast as unreachable on Native instead of parking")
        val tight    = 200.millis
        val generous = 30.seconds
        // Both connects go to the same black hole on the same transport. Each must fail on ITS OWN deadline: a shared or construction-captured
        // deadline would fail both at one instant, and the generous one would leave no timer armed after the tight one fired.
        for
            generousOutcome <- Fiber.init(Abort.run[NetException](transport.connect(blackHoleHost, blackHolePort, generous).safe.get))
            tightOutcome    <- Fiber.init(Abort.run[NetException](transport.connect(blackHoleHost, blackHolePort, tight).safe.get))
            _               <- tc.awaitPendingSleepers(2)
            _               <- tc.advance(tight)
            tightResult     <- tightOutcome.get
            _               <- tc.awaitPendingSleepers(1)
            _               <- tc.advance(generous.minusOrZero(tight))
            generousResult  <- generousOutcome.get
        yield
            def timeoutOf(result: Result[NetException, Connection]): Maybe[Duration] = result match
                case Result.Failure(e: NetConnectTimeoutException) => Present(e.timeout)
                case Result.Success(conn)                          => conn.close(); Absent
                case _                                             => Absent
            assert(timeoutOf(tightResult) == Present(tight), s"the $tight connect must fail on its own deadline, got $tightResult")
            assert(
                timeoutOf(generousResult) == Present(generous),
                s"the $generous connect must fail on its own deadline, got $generousResult"
            )
        end for
    }

    "two listeners on one transport reap stalled handshakes on their own deadlines" - eachBackendTlsOnClock {
        (transport, tc, material, clientTls) =>
            // Same transport, same TLS material, two deadlines. A plaintext client completes each TCP accept and never sends a ClientHello, so both
            // server handshakes park; only the listener that asked for a finite deadline may reap its connection.
            val reaping   = material.copy(handshakeTimeout = 150.millis)
            val unbounded = material.copy(handshakeTimeout = Duration.Infinity)
            val noLimit   = clientTls.copy(handshakeTimeout = Duration.Infinity)
            for
                reapingListener   <- transport.listenTls("127.0.0.1", 0, 16, reaping)(_ => ()).safe.get
                _                 <- Scope.ensure(Sync.defer(reapingListener.close()))
                unboundedListener <- transport.listenTls("127.0.0.1", 0, 16, unbounded)(_ => ()).safe.get
                _                 <- Scope.ensure(Sync.defer(unboundedListener.close()))
                reapedClient      <- transport.connect("127.0.0.1", reapingListener.port, Duration.Infinity).safe.get
                _                 <- Scope.ensure(Sync.defer(reapedClient.close()))
                heldClient        <- transport.connect("127.0.0.1", unboundedListener.port, Duration.Infinity).safe.get
                // A witness completes its handshake on the unbounded listener, which accepts in connection order: the held client was accepted
                // first, so a deadline wrongly armed for it is pending before the advance.
                witness <- transport.connectTls("127.0.0.1", unboundedListener.port, noLimit).safe.get
                _       <- Scope.ensure(Sync.defer(witness.close()))
                _       <- tc.awaitPendingSleepers(1)
                _       <- tc.advance(150.millis)
                // The reaped side closes the accepted fd, which this client observes as its inbound terminating.
                reaped <- Abort.run[Closed](reapedClient.inbound.safe.take)
                // The unbounded side is still alive past the other listener's deadline: its handshake completes now.
                held <- Abort.run[NetException](transport.upgradeToTls(heldClient, noLimit, 16).safe.get)
            yield
                held.foreach(_.close())
                val wasReaped = reaped match
                    case Result.Success(span) => span.isEmpty
                    case Result.Failure(_)    => true
                    case _                    => false
                assert(wasReaped, s"the 150ms listener must reap its stalled handshake, got $reaped")
                assert(held.isSuccess, s"the Infinity listener must not reap its stalled handshake, got $held")
            end for
    }

end TransportPerOperationConfigTest
