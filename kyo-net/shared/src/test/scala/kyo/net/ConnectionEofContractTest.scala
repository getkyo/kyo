package kyo.net

import kyo.*

/** The end-of-stream contract, clauses E1 and E2: how the peer's end of the stream is reported through [[Connection.status]], and that every
  * byte read before it reaches the reader first. Each leaf is named after its clause and runs once per backend cell, plain and TLS.
  */
class ConnectionEofContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private def drainInbound(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](Loop.foreach(conn.inbound.safe.take.map(span => if span.isEmpty then Loop.done else Loop.continue))).unit

    "E1" - {

        // A FIN with no close_notify is what a truncation attack looks like; no kyo connection sends one, so a relay half-closes the
        // server's peer at the TCP level.
        "a FIN without close_notify reports Truncated" - eachBackendTls { (transport, serverTls, clientTls) =>
            if !RawRelay.available then cancel("the raw relay needs posix sockets, which this host does not have")
            val accepted = Promise.Unsafe.init[Connection, Any]()
            for
                listener <-
                    transport.listenTls("127.0.0.1", 0, 16, serverTls)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
                _      <- Scope.ensure(Sync.defer(listener.close()))
                relay  <- RawRelay.init(listener.port)
                client <- transport.connectTls("127.0.0.1", relay.port, clientTls).safe.get
                _      <- Scope.ensure(Sync.defer(client.close()))
                server <- accepted.safe.get
                _      <- Scope.ensure(Sync.defer(server.close()))
                _      <- relay.halfCloseUpstream()
                _      <- drainInbound(server)
            yield
                val expected = if transport.reportsTlsCloseReason then Connection.Status.Truncated else Connection.Status.Active
                assert(server.status == expected, s"a FIN with no close_notify must report $expected, got ${server.status}")
            end for
        }
    }

end ConnectionEofContractTest
