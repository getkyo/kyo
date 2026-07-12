package kyo.net

import kyo.*
import kyo.net.internal.NioHandle
import kyo.net.internal.NioTransport
import kyo.net.internal.transport.Connection as InternalConnection

/** BUG-2 (reproduce-first, D-010): `NioTransport.upgradeToTls`'s unguarded `conn.asInstanceOf[Connection[NioHandle]]` downcast threw a raw
  * `ClassCastException` when `conn` was not NIO-backed (an in-memory connection, or any future non-NIO `Connection` reaching the NIO
  * transport). The fix replaces the downcast with a guarded match: a misrouted upgrade fails closed with the typed
  * [[NetNotUpgradableException]] on the `Abort[NetException]` row instead of an uncaught runtime exception.
  */
class NioTransportUpgradeTest extends Test:

    import AllowUnsafe.embrace.danger

    def mkTransport()(using Frame): NioTransport =
        NioTransport.init(
            channelCapacity = 8,
            readBufferSize = NioHandle.DefaultReadBufferSize,
            connectTimeout = Duration.Infinity,
            handshakeTimeout = Duration.Infinity
        )

    "misrouted upgradeToTls (non-NioHandle Connection) fails with NetNotUpgradableException, NOT ClassCastException" in {
        given Frame      = Frame.internal
        val transport    = mkTransport()
        val inbound      = Channel.Unsafe.init[Span[Byte]](8)
        val outbound     = Channel.Unsafe.init[Span[Byte]](8)
        val inMemoryConn = InternalConnection.inMemory(inbound, outbound)

        // Before the fix, this call site threw a raw ClassCastException (the unguarded asInstanceOf) instead of returning a fiber the
        // caller can Abort.run over: driving it through the SAME Abort[NetException] row the public API promises is the reproduction.
        val upgraded = transport.upgradeToTls(inMemoryConn, NetTlsConfig.default, channelCapacity = 8)
        Abort.run[NetException](upgraded.safe.get).map { result =>
            transport.close()
            result match
                case Result.Failure(_: NetNotUpgradableException) => succeed
                case other                                        => fail(s"expected Result.Failure(NetNotUpgradableException), got $other")
        }
    }

end NioTransportUpgradeTest
