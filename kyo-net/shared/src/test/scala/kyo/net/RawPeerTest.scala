package kyo.net

import kyo.*

class RawPeerTest extends Test:

    import AllowUnsafe.embrace.danger

    private val payload = Span.fromUnsafe(Array.tabulate[Byte](200 * 1024)(i => (i % 251).toByte))

    /** A raw peer connected to a kyo listener, and the kyo connection that accepted it. */
    private def acceptedPair(transport: Transport)(using Frame): (RawPeer, Connection) < (Async & Abort[NetException] & Scope) =
        val accepted = Promise.Unsafe.init[Connection, Any]()
        for
            listener <- transport.listen("127.0.0.1", 0, 16)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            peer     <- RawPeer.connect(listener.port)
            server   <- accepted.safe.get
            _        <- Scope.ensure(Sync.defer(server.close()))
        yield (peer, server)
        end for
    end acceptedPair

    private def takeExactly(conn: Connection, n: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Chunk.empty[Array[Byte]], 0) { (acc, got) =>
            if got >= n then Loop.done(acc.toArray.flatten)
            else conn.inbound.safe.take.map(span => Loop.continue(acc.append(span.toArray), got + span.size))
        }

    "bytes cross both ways between a raw peer and a kyo connection" - eachBackend { transport =>
        for
            (peer, server) <- acceptedPair(transport)
            _              <- peer.send(payload)
            atServer       <- Abort.run[Closed](takeExactly(server, payload.size))
            _              <- server.outbound.safe.put(payload)
            atPeer         <- peer.recvExactly(payload.size)
        yield
            assert(atServer.map(_.sameElements(payload.toArray)) == Result.succeed(true), "the kyo side must receive the peer's bytes")
            assert(atPeer.sameElements(payload.toArray), s"the peer must receive the kyo side's bytes: ${atPeer.length} of ${payload.size}")
        end for
    }

    "a kyo close reaches the raw peer as every byte, then a FIN" - eachBackend { transport =>
        for
            (peer, server) <- acceptedPair(transport)
            _              <- server.outbound.safe.put(payload)
            _              <- Sync.defer(server.close())
            (bytes, end)   <- peer.recvToEnd()
        yield
            assert(
                bytes.sameElements(payload.toArray),
                s"every byte written before close() must arrive: ${bytes.length} of ${payload.size}"
            )
            assert(end == RawPeer.Read.Fin, s"the stream must end with a FIN, got $end")
        end for
    }

    "a raw peer's half-close ends the kyo connection's inbound" - eachBackend { transport =>
        for
            (peer, server) <- acceptedPair(transport)
            _              <- peer.send(Span(1.toByte, 2.toByte))
            _              <- peer.shutdownWr()
            got            <- Abort.run[Closed](takeExactly(server, 2))
            next           <- Abort.run[Closed](server.inbound.safe.take)
        yield
            assert(got.map(_.toSeq) == Result.succeed(Seq[Byte](1, 2)), s"the bytes before the FIN must arrive first, got $got")
            next match
                case Result.Failure(_: Closed)            => succeed
                case Result.Success(span) if span.isEmpty => succeed
                case other                                => fail(s"the inbound must end after the peer's FIN, got $other")
            end match
        end for
    }

    "a raw peer's reset ends the kyo connection's inbound" - eachBackend { transport =>
        for
            (peer, server) <- acceptedPair(transport)
            _              <- peer.send(Span(1.toByte))
            first          <- Abort.run[Closed](takeExactly(server, 1))
            _              <- peer.reset()
            next           <- Abort.run[Closed](server.inbound.safe.take)
        yield
            assert(first.map(_.toSeq) == Result.succeed(Seq[Byte](1)), s"got $first")
            next match
                case Result.Failure(_: Closed)            => succeed
                case Result.Success(span) if span.isEmpty => succeed
                case other                                => fail(s"the inbound must end after the peer's reset, got $other")
            end match
        end for
    }

    "a raw listener accepts a kyo client" - eachBackend { transport =>
        for
            listener <- RawPeer.listen()
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
            _        <- Scope.ensure(Sync.defer(client.close()))
            peer     <- listener.accept()
            _        <- client.outbound.safe.put(payload)
            atPeer   <- peer.recvExactly(payload.size)
        yield assert(atPeer.sameElements(payload.toArray), s"the raw listener's peer must receive the client's bytes: ${atPeer.length}")
        end for
    }

end RawPeerTest
