package kyo.net

import kyo.*

/** The half-close contract, clause H1: a peer's FIN ends the inbound direction only, and the outbound direction stays writable until the
  * application closes it. Each leaf is named after its clause and runs once per backend cell, plain and TLS; a relay half-closes the
  * server's peer at the TCP level, which no kyo connection does on cue.
  */
class ConnectionHalfCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    // Every backend violates H1 through the shared connection. A cell the relay cannot reach cancels, so it is never marked.
    private def s10: Maybe[String] =
        if RawRelay.available then Present("S10: a peer FIN runs the full close, closing outbound to new writes") else Absent

    private def takeBytes(conn: Connection, n: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Chunk.empty[Array[Byte]], 0) { (acc, got) =>
            if got >= n then Loop.done(acc.toArray.flatten)
            else conn.inbound.safe.take.map(span => Loop.continue(acc.append(span.toArray), got + span.size))
        }

    private def drainToEnd(conn: Connection)(using Frame): Array[Byte] < Async =
        Loop(Chunk.empty[Array[Byte]]) { acc =>
            Abort.run[Closed](conn.inbound.safe.take).map {
                case Result.Success(span) if !span.isEmpty => Loop.continue(acc.append(span.toArray))
                case _                                     => Loop.done(acc.toArray.flatten)
            }
        }

    private def responseAfterPeerFin(transport: Transport, tls: Maybe[(NetTlsConfig, NetTlsConfig)])(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[NetException | Closed] & Scope) =
        if !RawRelay.available then cancel("the raw relay needs posix sockets, which this host does not have")
        val request = Array.fill[Byte](64)(5)
        // Many spans, so the writes after the FIN keep parking on the pump long past the moment a violating close seals outbound: one span
        // could land in the window between inbound ending and that seal, and pass where the clause is broken.
        val spans    = 16
        val response = Array.tabulate[Byte](spans * 64 * 1024)(i => (i % 251).toByte)
        val accepted = Promise.Unsafe.init[Connection, Any]()
        for
            listener <- tls match
                case Present((serverTls, _)) =>
                    transport.listenTls("127.0.0.1", 0, 16, serverTls)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
                case Absent => transport.listen("127.0.0.1", 0, 16)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
            _      <- Scope.ensure(Sync.defer(listener.close()))
            relay  <- RawRelay.init(listener.port)
            client <- tls match
                case Present((_, clientTls)) => transport.connectTls("127.0.0.1", relay.port, clientTls).safe.get
                case Absent                  => transport.connect("127.0.0.1", relay.port).safe.get
            _       <- Scope.ensure(Sync.defer(client.close()))
            server  <- accepted.safe.get
            _       <- Scope.ensure(Sync.defer(server.close()))
            _       <- client.outbound.safe.put(Span.fromUnsafe(request))
            got     <- takeBytes(server, request.length)
            _       <- relay.halfCloseUpstream()
            ended   <- Abort.run[Closed](server.inbound.safe.take)
            drained <- Fiber.init(drainToEnd(client))
            put     <- Abort.run[Closed](Kyo.foreachDiscard(Chunk.from(0 until spans)) { i =>
                server.outbound.safe.put(Span.fromUnsafe(response.slice(i * 64 * 1024, (i + 1) * 64 * 1024)))
            })
            _        <- Sync.defer(server.close())
            received <- drained.get
        yield
            assert(got.sameElements(request), "the request must reach the server before the peer's FIN")
            assert(ended.isFailure, s"the peer's FIN must end inbound; got $ended")
            assert(put.isSuccess, s"outbound must stay writable after the peer's FIN; got $put")
            assert(
                received.sameElements(response),
                s"the response written after the peer's FIN must reach it: ${received.length} of ${response.length} bytes"
            )
        end for
    end responseAfterPeerFin

    "H1" - {

        "the response written after the peer's FIN reaches the peer" - eachBackendPending(_ => s10) { transport =>
            responseAfterPeerFin(transport, Absent)
        }

        "TLS: the response written after the peer's FIN reaches the peer" - eachBackendTlsPending((_, _) => s10) {
            (transport, serverTls, clientTls) =>
                responseAfterPeerFin(transport, Present((serverTls, clientTls)))
        }
    }

end ConnectionHalfCloseContractTest
