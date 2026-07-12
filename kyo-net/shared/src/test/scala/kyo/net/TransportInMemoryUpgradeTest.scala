package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection as InternalConnection

/** A STARTTLS upgrade on a connection that is not socket-backed (the in-memory connection) must abort `NetException` on every backend, not throw.
  * The in-memory connection is a plain `kyo.net.Connection` with no driver or handle, so `Transport.upgradeToTls` has nothing to upgrade; the
  * documented contract (Connection.inMemory) is that it aborts `NetException`. Asserted over every registered backend via [[eachBackend]]: the
  * rejection happens before any TLS engine is built, so it is TLS-implementation-independent and needs no provider matrix.
  */
class TransportInMemoryUpgradeTest extends Test:

    import AllowUnsafe.embrace.danger

    "upgradeToTls on a non-upgradable in-memory connection aborts NetException" - eachBackend { transport =>
        val clientTls = NetTlsConfig(trustAll = true)
        val inbound   = Channel.Unsafe.init[Span[Byte]](8)
        val outbound  = Channel.Unsafe.init[Span[Byte]](8)
        val inMem     = InternalConnection.inMemory(inbound, outbound)
        Abort.run[NetException | Closed](transport.upgradeToTls(inMem, clientTls, 16).safe.get).map { result =>
            assert(result.isFailure, s"upgradeToTls on a non-upgradable in-memory connection must abort NetException, got $result")
        }
    }

end TransportInMemoryUpgradeTest
