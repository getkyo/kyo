package kyo.net

import kyo.*

class PeerTlsTest extends Test:

    import AllowUnsafe.embrace.danger

    private val message = Span.fromUnsafe(Array.tabulate[Byte](4096)(i => (i % 251).toByte))

    "a peer TLS session exchanges records with a kyo TLS connection and sees its close_notify" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            val provider = clientTls.tlsProvider.getOrElse(fail("the cell pins no provider"))
            if !PeerTls.available(provider) then cancel(s"$provider builds no in-process engine for a raw peer on this host")
            val accepted = Promise.Unsafe.init[Connection, Any]()
            for
                listener <-
                    transport.listenTls("127.0.0.1", 0, 16, serverTls)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                peer     <- RawPeer.connect(listener.port)
                tls      <- PeerTls.client(peer, provider)
                _        <- tls.handshake()
                server   <- accepted.safe.get
                _        <- Scope.ensure(Sync.defer(server.close()))
                _        <- tls.send(message)
                received <- Loop(Chunk.empty[Array[Byte]], 0) { (acc, got) =>
                    if got >= message.size then Loop.done(acc.toArray.flatten)
                    else server.inbound.safe.take.map(span => Loop.continue(acc.append(span.toArray), got + span.size))
                }
                _     <- server.outbound.safe.put(message)
                _     <- Sync.defer(server.close())
                ended <- tls.recvToEnd()
            yield
                assert(received.sameElements(message.toArray), s"the kyo side must decrypt the peer's records: ${received.length} bytes")
                assert(
                    ended.plain.toArray.sameElements(message.toArray),
                    s"the peer must decrypt the kyo side's records: ${ended.plain.size} bytes"
                )
                assert(ended.closeNotify, "the kyo side's close must send close_notify before its FIN")
                assert(ended.end == RawPeer.Read.Fin, s"the stream must end with a FIN, got ${ended.end}")
            end for
    }

end PeerTlsTest
