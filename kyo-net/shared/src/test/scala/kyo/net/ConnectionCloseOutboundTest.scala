package kyo.net

import kyo.*

/** [[Connection.closeOutbound]] over every backend with real sockets, plaintext and TLS.
  *
  * The server's outbound channel holds the whole payload, so every span is queued, in the channel, the pump or a driver's send tail,
  * when `closeOutbound` runs, and the client reads nothing until then: a FIN sent ahead of any of those bytes cuts the payload short.
  */
class ConnectionCloseOutboundTest extends Test:

    import AllowUnsafe.embrace.danger

    private val spans        = 16
    private val payload      = Array.tabulate[Byte](spans * 64 * 1024)(i => (i % 251).toByte)
    private val holdsPayload = NetConfig(channelCapacity = spans * 2)

    private def queueAll(conn: Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        Kyo.foreachDiscard(0 until spans) { i =>
            conn.outbound.safe.put(Span.from(payload.slice(i * 64 * 1024, (i + 1) * 64 * 1024)))
        }

    private def readToEnd(conn: Connection)(using Frame): Array[Byte] < Async =
        Loop(Array.emptyByteArray) { acc =>
            Abort.run[Closed](conn.inbound.safe.take).map {
                case Result.Success(span) => Loop.continue(acc ++ span.toArray)
                case _                    => Loop.done(acc)
            }
        }

    private def readN(conn: Connection, n: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= n then Loop.done(acc)
            else conn.inbound.safe.take.map(span => Loop.continue(acc ++ span.toArray))
        }

    private def halfClose(server: Connection, client: Connection)(using
        Frame,
        kyo.test.AssertScope
    ): Connection.Status < (Async & Abort[Closed]) =
        // A kyo client closes once it reads the server's FIN, so it sends before reading; the server still reads while its write side ends.
        val reply = "after closeOutbound".getBytes("UTF-8")
        for
            _         <- queueAll(server)
            _         <- Sync.defer(server.closeOutbound())
            lateWrite <- Abort.run[Closed](server.outbound.safe.put(Span.from(Array[Byte](1))))
            _         <- client.outbound.safe.put(Span.from(reply))
            echoed    <- readN(server, reply.length)
            stillOpen = server.isOpen
            received <- readToEnd(client)
            clientEnd = client.status
            afterPeer <- readToEnd(server)
            _         <- server.onClosing.safe.get
        yield
            assert(received.length == payload.length, s"the client read ${received.length} of ${payload.length} bytes before the FIN")
            assert(received.sameElements(payload), "the bytes queued before closeOutbound reach the peer in order")
            assert(lateWrite.isFailure, s"a put after closeOutbound fails Closed; got $lateWrite")
            assert(echoed.sameElements(reply), s"the server still reads after closeOutbound; got ${new String(echoed, "UTF-8")}")
            assert(stillOpen, "the connection stays open after its outbound direction ends")
            assert(afterPeer.isEmpty, s"nothing follows the peer's close; got ${afterPeer.length} bytes")
            assert(!server.isOpen, "the peer's close closes the connection")
            clientEnd
        end for
    end halfClose

    "closeOutbound sends every queued byte before the FIN and keeps reading until the peer closes" - eachBackend { transport =>
        val accepted = Promise.Unsafe.init[Connection, Any]()
        for
            listener <- transport.listen("127.0.0.1", 0, 16, holdsPayload)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
            _        <- Scope.ensure(Sync.defer(client.close()))
            server   <- accepted.safe.get
            _        <- Scope.ensure(Sync.defer(server.close()))
            _        <- halfClose(server, client)
        yield ()
        end for
    }

    "closeOutbound over TLS sends every queued byte and the close_notify before the FIN, and keeps reading" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val accepted = Promise.Unsafe.init[Connection, Any]()
            for
                listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls, holdsPayload)(conn =>
                    accepted.completeDiscard(Result.succeed(conn))
                ).safe.get
                _         <- Scope.ensure(Sync.defer(listener.close()))
                client    <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                _         <- Scope.ensure(Sync.defer(client.close()))
                server    <- accepted.safe.get
                _         <- Scope.ensure(Sync.defer(server.close()))
                clientEnd <- halfClose(server, client)
            yield
                if transport.reportsTlsCloseReason then
                    assert(clientEnd == Connection.Status.CleanClose, s"the client saw a close_notify before the FIN; got $clientEnd")
            end for
    }

    "closeOutbound on a closed connection does nothing" - eachBackend { transport =>
        val accepted = Promise.Unsafe.init[Connection, Any]()
        for
            listener <- transport.listen("127.0.0.1", 0, 16)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
            _        <- Scope.ensure(Sync.defer(client.close()))
            server   <- accepted.safe.get
            _        <- Sync.defer(server.close())
            _        <- Sync.defer(server.closeOutbound())
            received <- readToEnd(client)
        yield
            assert(!server.isOpen)
            assert(received.isEmpty, s"a closed connection sends nothing; got ${received.length} bytes")
        end for
    }

end ConnectionCloseOutboundTest
