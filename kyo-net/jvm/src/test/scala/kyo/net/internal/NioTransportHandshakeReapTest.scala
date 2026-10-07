package kyo.net.internal

import kyo.*
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test

/** A failed NIO accept handshake reaps its handle through the driver, so the driver's `pendingReads` entry for it goes away.
  *
  * The server TLS accept path arms a read on the accepted handle (the driver's `pendingReads[channel] -> handle` entry) while the handshake
  * waits for the client's next flight. The handshake's failure arm, reached by a handshake failure, the handshake deadline, and a listener
  * close, must route through `driver.closeHandle(handle)` before closing the channel: a bare channel close cancels the selection key but
  * leaves the entry and its armed promise in the driver, one per stalled handshake.
  *
  * Observed through the driver's own `Diagnostics` registration, the same state the end-of-run stranded-op gate reads. That gate does not
  * catch this leak by itself, because it treats a closed driver as clean and the NIO probe reports no cycle count. A peer or socket-table
  * observation cannot discriminate: the bare close still closes the socket, so the client sees its inbound end and the server socket reaches
  * TIME_WAIT with or without the reap; only the driver's entry and its armed promise leak. The client runs on a separate transport so the
  * server driver's `pendingReads` holds only the accepted handshake's read.
  */
class NioTransportHandshakeReapTest extends Test:

    import AllowUnsafe.embrace.danger

    lazy val serverTlsConfig: NetTlsConfig = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath),
        handshakeTimeout = Duration.Infinity
    )

    /** The `pendingReads` count `driver` reports in its `Diagnostics` dump, or Absent when the driver is not registered. */
    private def pendingReads(driver: NioIoDriver): Maybe[Int] =
        val marker = "=== NioIoDriver@" + java.lang.System.identityHashCode(driver) + " ===\n"
        val dump   = kyo.internal.Diagnostics.dumpAll()
        val at     = dump.indexOf(marker)
        if at < 0 then Absent
        else
            val body = dump.substring(at + marker.length).takeWhile(_ != '\n')
            Maybe.fromOption("pendingReads=(\\d+)".r.findFirstMatchIn(body).map(_.group(1).toInt))
        end if
    end pendingReads

    /** Poll the driver dump until `cond` holds or `bound` passes; the bound is a ceiling, the condition is the pass signal. */
    private def awaitPendingReads(driver: NioIoDriver, bound: Duration)(cond: Maybe[Int] => Boolean)(using Frame): Maybe[Int] < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            val now = pendingReads(driver)
            if cond(now) || java.lang.System.nanoTime() >= deadline then Loop.done(now)
            else Async.sleep(5.millis).andThen(Loop.continue(()))
        }
    end awaitPendingReads

    "a failed accept handshake's teardown removes its pendingReads entry from the driver" in {
        val server = NioTransport.init()
        val client = NioTransport.init()
        Scope.ensure(Sync.defer {
            server.pool.next().close()
            client.pool.next().close()
        }).andThen {
            Abort.run[NetException | Closed] {
                server.listenTls("127.0.0.1", 0, 16, serverTlsConfig)(_ => ()).safe.get.map { listener =>
                    Scope.ensure(Sync.defer(listener.close())).andThen {
                        client.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                            Scope.ensure(Sync.defer(conn.close())).andThen {
                                // One ClientHello, then a stall: the server answers and parks a read for the client's next flight.
                                val hello = StalledTlsClient.clientHello(listener.port)
                                assert(hello.nonEmpty, "the client engine produced no ClientHello")
                                conn.outbound.safe.put(hello).andThen(conn.inbound.safe.take).map { serverFlight =>
                                    assert(serverFlight.nonEmpty, "the server handshake answered the ClientHello with no bytes")
                                }.andThen {
                                    awaitPendingReads(server.driver, 10.seconds)(_ == Present(1)).map { armed =>
                                        assert(
                                            armed == Present(1),
                                            s"the stalled handshake must hold one armed read on the driver; got $armed"
                                        )
                                    }
                                }.andThen {
                                    // The listener close fails the handshake; its failure arm tears the handle down and closes the channel, which
                                    // the client observes as its inbound terminating. The reap runs before that close, so it has run by then.
                                    listener.close()
                                    Abort.run[Timeout](Async.timeout(10.seconds)(StalledTlsClient.awaitInboundClosed(conn))).map { closed =>
                                        assert(closed.isSuccess, s"the listener close must tear down the stalled handshake; got $closed")
                                        val after = pendingReads(server.driver)
                                        assert(
                                            after == Present(0),
                                            s"the handshake teardown must reap the handle through the driver, leaving no pendingReads entry; got $after"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }.map(Abort.get)
        }
    }

end NioTransportHandshakeReapTest
