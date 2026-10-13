package kyo.net

import kyo.*

/** Cross-backend, cross-TLS-implementation client connect/handshake deadline guarantee (Netty #9266 / Go #23518 class), over the full
  * backend x TLS-impl matrix.
  *
  * A caller composes its own bound with `Async.timeout`. This locks that composition on every backend and TLS implementation: a client TLS
  * connect whose handshake stalls (a plaintext listener accepts the TCP connection but never speaks TLS, so the client parks waiting for a
  * ServerHello that never arrives) MUST be boundable by `Async.timeout`, aborting cleanly at the caller's instant rather than hanging or
  * swallowing the interrupt. The transport's own deadlines are off (`Infinity`), so the caller's timeout, on a controlled clock of the
  * caller's own, is the only timer the leaf waits on.
  */
class TransportConnectDeadlineTest extends Test:

    import AllowUnsafe.embrace.danger

    "a stalled client TLS connect is bounded by Async.timeout at its instant" - eachBackendTls { (transport, _, clientTls) =>
        val unbounded = clientTls.copy(handshakeTimeout = Duration.Infinity)
        for
            listener <- transport.listen("127.0.0.1", 0, 128)(_ => ()).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            result   <- Clock.withTimeControl { tc =>
                Fiber.init(Abort.run[NetException | Timeout](
                    Async.timeout(1.second)(transport.connectTls("127.0.0.1", listener.port, unbounded, Duration.Infinity).safe.get)
                )).map { outcome =>
                    armedThenAdvance(tc, "caller's Async.timeout", 1.second).andThen(outcome.get)
                }
            }
        yield result match
            case Result.Failure(_: Timeout) => succeed
            case Result.Success(conn)       =>
                conn.close()
                fail("a stalled client TLS connect completed instead of being bounded by Async.timeout")
            case other => fail(s"a stalled client TLS connect must end with the caller's Timeout, got $other")
        end for
    }

end TransportConnectDeadlineTest
