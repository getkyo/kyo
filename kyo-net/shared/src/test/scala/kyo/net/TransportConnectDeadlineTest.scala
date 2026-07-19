package kyo.net

import kyo.*

/** Cross-backend, cross-TLS-implementation client connect/handshake deadline guarantee (Netty #9266 / Go #23518 class), over the full
  * backend x TLS-impl matrix via [[eachBackendTls]].
  *
  * kyo-net does not bake a client-side connect deadline into the transport; the caller composes one with `Async.timeout`. This locks that
  * composition on every backend and TLS implementation: a client TLS connect whose handshake stalls (a plaintext listener accepts the TCP
  * connection but never speaks TLS, so the client parks waiting for a ServerHello that never arrives, and `connect(tls)` does not return until the
  * handshake completes or fails) MUST be boundable by `Async.timeout`, aborting cleanly rather than hanging or swallowing the interrupt.
  */
class TransportConnectDeadlineTest extends Test:

    import AllowUnsafe.embrace.danger

    "a stalled client TLS connect is bounded by Async.timeout" - eachBackendTls { (transport, _, clientTls) =>
        for
            listener <- transport.listen("127.0.0.1", 0, 128)(_ => ()).safe.get
            outcome <- Abort.run[Closed | Timeout](
                Async.timeout(1.second)(transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get)
            )
        yield
            listener.close()
            assert(outcome.isFailure, s"a stalled client TLS connect must be boundable by Async.timeout (no hang), got $outcome")
        end for
    }

end TransportConnectDeadlineTest
