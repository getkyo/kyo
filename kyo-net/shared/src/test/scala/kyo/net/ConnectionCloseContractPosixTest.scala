package kyo.net

import kyo.*

/** The TLS close flush of the connection close contract, asserted through the public [[Connection]] over every backend and TLS provider. A
  * driver that reports a write done before its ciphertext is in the kernel lets the close release with that ciphertext unsent.
  */
class ConnectionCloseContractPosixTest extends Test:

    import AllowUnsafe.embrace.danger

    private val spans    = 16
    private val spanSize = 64 * 1024
    private val payload  = Array.tabulate[Byte](spans * spanSize)(i => (i % 251).toByte)

    /** Writes [[payload]] from the server side of a TLS pair, closes it, and reads the client to its end: the bytes read and the end. */
    private def closeAfterPayload(transport: Transport, serverTls: NetTlsConfig, clientTls: NetTlsConfig)(using
        Frame
    ): (Array[Byte], Connection.Status) < (Async & Abort[NetException | Closed] & Scope) =
        val accepted = Promise.Unsafe.init[Connection, Any]()
        val config   = NetConfig(closeFlushGrace = 10.seconds.grace, channelCapacity = spans * 2)
        for
            listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls, config)(conn =>
                accepted.completeDiscard(Result.succeed(conn))
            ).safe.get
            _      <- Scope.ensure(Sync.defer(listener.close()))
            client <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
            _      <- Scope.ensure(Sync.defer(client.close()))
            server <- accepted.safe.get
            _      <- Kyo.foreachDiscard(0 until spans) { i =>
                server.outbound.safe.put(Span.from(payload.slice(i * spanSize, (i + 1) * spanSize)))
            }
            _        <- Sync.defer(server.close())
            received <- Loop(Array.emptyByteArray) { acc =>
                Abort.run[Closed](client.inbound.safe.take).map {
                    case Result.Success(span) => Loop.continue(acc ++ span.toArray)
                    case _                    => Loop.done(acc)
                }
            }
        yield (received, client.status)
        end for
    end closeAfterPayload

    "a TLS close delivers every byte accepted before it" - eachBackendTls { (transport, serverTls, clientTls) =>
        closeAfterPayload(transport, serverTls, clientTls).map { (received, _) =>
            assert(received.length == payload.length, s"the client read ${received.length} of ${payload.length} bytes before the close")
            assert(received.sameElements(payload), "every byte accepted before the close reaches the peer in order")
        }
    }

    "a TLS close sends close_notify after every byte accepted before it" - eachBackendTlsPending((backend, _) =>
        if backend == "kqueue" || backend == "epoll" || backend == "io_uring" then
            Present("the close_notify is sent only once the output-shutdown sequence appends it to the tail behind the last record")
        else Absent
    ) { (transport, serverTls, clientTls) =>
        closeAfterPayload(transport, serverTls, clientTls).map { (received, clientEnd) =>
            assert(received.sameElements(payload), "every byte accepted before the close reaches the peer in order")
            if transport.reportsTlsCloseReason then
                assert(clientEnd == Connection.Status.CleanClose, s"the close_notify follows the payload; got $clientEnd")
        }
    }

end ConnectionCloseContractPosixTest
