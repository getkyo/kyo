package kyo.internal.tls

import kyo.*
import kyo.Maybe.Present
import kyo.SqlConnectionConnectFailedException
import kyo.SqlException
import kyo.net.Connection
import kyo.net.NetTlsConfig

/** Performs the socket-level TLS upgrade shared by the Postgres and MySQL connect paths.
  *
  * The two engines negotiate TLS differently (Postgres asks out-of-band with an 8-byte SSLRequest before the handshake, MySQL sends a
  * mid-handshake SslRequest packet), so the negotiation itself stays per engine. What is byte-identical between them is the step after the
  * server has agreed: inject the connect host into `sniHostname` when the caller left it empty, then call [[kyo.net.Connection.upgradeToTls]]
  * on the same socket and map a transport-level failure or panic to [[SqlConnectionConnectFailedException]]. That step lives here so it is
  * expressed once.
  *
  * The success value is the raw TLS-wrapped [[Connection]]. What each engine does with it stays per engine: Postgres returns it as-is, MySQL
  * wraps a new channel over it and transfers the sequence id.
  */
private[kyo] object TlsUpgrade:

    /** Upgrades `conn` to TLS after the server has accepted, returning the TLS-wrapped connection.
      *
      * Injects `host` into `tls.sniHostname` when the caller left it empty so that JDK hostname verification and JS Node TLS have a reference
      * identity to check the server certificate against; without it, `verify-full` is either fail-closed on the JVM or checks against the
      * platform default servername, neither of which is useful.
      *
      * @param conn
      *   the plaintext [[Connection]] whose socket is upgraded in place
      * @param host
      *   server hostname, injected as the SNI identity and used in error messages
      * @param port
      *   server port, used in error messages
      * @param tls
      *   the [[NetTlsConfig]] to hand to [[kyo.net.Connection.upgradeToTls]]
      * @return
      *   the TLS-wrapped [[Connection]] on success, or fails with [[SqlConnectionConnectFailedException]] on transport failure or panic
      */
    def upgrade(
        conn: Connection,
        host: String,
        port: Int,
        tls: NetTlsConfig
    )(using Frame): Connection < (Async & Abort[SqlException]) =
        val tlsWithHost =
            if host.nonEmpty && tls.sniHostname.isEmpty then tls.copy(sniHostname = Present(host))
            else tls
        Abort.run[kyo.net.NetException] {
            // Unsafe: bridges to the raw kyo-net transport upgrade, which builds the TLS channel outside the safe tier.
            Sync.Unsafe.defer {
                kyo.net.NetPlatform.transport.upgradeToTls(
                    conn,
                    tlsWithHost,
                    kyo.net.NetConfig.DefaultChannelCapacity
                ).safe.use(identity)
            }
        }.flatMap {
            case Result.Success(tlsConn) => tlsConn
            case Result.Failure(netEx) =>
                Abort.fail(SqlConnectionConnectFailedException(host, port, netEx))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] TlsUpgrade: TLS upgrade panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlConnectionConnectFailedException(host, port, t))
                )
        }
    end upgrade

end TlsUpgrade
