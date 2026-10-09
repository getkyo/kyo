package kyo.net

import kyo.*

/** The connection close contract as the posix poller drivers (kqueue, epoll) must honor it, asserted through the public [[Connection]] over
  * every backend. Cells a driver still violates are pending with the violation id; every other cell asserts the contract.
  */
class ConnectionCloseContractPosixTest extends Test:

    import AllowUnsafe.embrace.danger

    private def poller(backend: String): Boolean = backend == "kqueue" || backend == "epoll"

    "a TLS close delivers every byte accepted before it, then close_notify" - eachBackendTlsPending(NetConfig.default)((backend, _) =>
        if poller(backend) then
            Present("P1: a poller TLS write is Done once queued, and the close releases with its ciphertext unsent")
        else if backend == "io_uring" then
            Present("P1 (io_uring form, #16a): a TLS write is Done once queued on the engine FIFO, and the close releases with it unsent")
        else Absent
    ) { (transport, serverTls, clientTls) =>
        val accepted = Promise.Unsafe.init[Connection, Any]()
        val spans    = 16
        val spanSize = 64 * 1024
        val payload  = Array.tabulate[Byte](spans * spanSize)(i => (i % 251).toByte)
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
            clientEnd = client.status
        yield
            assert(received.length == payload.length, s"the client read ${received.length} of ${payload.length} bytes before the close")
            assert(received.sameElements(payload), "every byte accepted before the close reaches the peer in order")
            if transport.reportsTlsCloseReason then
                assert(clientEnd == Connection.Status.CleanClose, s"the close_notify follows the payload; got $clientEnd")
        end for
    }

end ConnectionCloseContractPosixTest
