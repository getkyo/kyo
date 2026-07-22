package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionConnectFailedException
import kyo.SqlConnectionTlsNotAdvertisedException
import kyo.SqlException
import kyo.internal.mysql.Capabilities
import kyo.internal.mysql.MysqlChannel
import kyo.internal.mysql.SslRequest
import kyo.net.NetTlsConfig

/** Sends a MySQL SSLRequest mid-handshake and upgrades the connection to TLS.
  *
  * The SslRequest carries the SAME capability flags that the caller intends to use in the subsequent full HandshakeResponse41. This is
  * required because MySQL 8.0 reads capabilities from the SslRequest to configure its auth state machine BEFORE the TLS handshake. If
  * SslRequest advertises a different (smaller) capability set, MySQL's state machine is misconfigured and authentication fails with error
  * 1251 ("Client does not support authentication protocol requested by server").
  *
  * Protocol sequence (MySQL Internals, Connection Phase / SSL):
  *   1. Read HandshakeV10 from server (done by caller, passed in via `serverCaps`).
  *   2. Pre-compute full client capabilities (caller responsibility), same value used here and in HandshakeResponse41.
  *   3. Send [[SslRequest]] carrying those capabilities at seqId=1 (right after the server's HandshakeV10 at seqId=0).
  *   4. Call [[kyo.net.Connection.upgradeToTls]] on the underlying socket, no additional bytes are exchanged; TLS handshake follows.
  *   5. Return a new [[MysqlChannel]] wrapping the TLS connection, with seqId=2 ready for HandshakeResponse41.
  *
  * Reference: MySQL Internals, Protocol::SSLRequest
  */
private[mysql] object InitTlsExchange:

    private val MaxPacketSize = 16777215L // 2^24 - 1
    private val Charset       = 255       // utf8mb4

    /** Performs the CLIENT_SSL mid-handshake upgrade on `channel`.
      *
      * @param channel
      *   the plaintext [[MysqlChannel]] after the HandshakeV10 has been received (seqId already at 1)
      * @param serverCaps
      *   the capability flags advertised by the server (from HandshakeV10)
      * @param clientCaps
      *   the full client capability flags that will also be used in the subsequent HandshakeResponse41. Using the same capability set in
      *   SslRequest and HandshakeResponse41 is required: MySQL 8.0 reads capabilities from SslRequest to configure its auth state machine
      *   before the TLS handshake. If SslRequest omits flags like CLIENT_PLUGIN_AUTH that HandshakeResponse41 later includes, MySQL raises
      *   error 1251 ("Client does not support authentication protocol requested by server").
      * @param host
      *   server hostname, for error messages
      * @param port
      *   server port, for error messages
      * @param tls
      *   the [[NetTlsConfig]] to use for the TLS handshake
      * @return
      *   a new [[MysqlChannel]] wrapping the TLS connection, with seqId positioned for the full HandshakeResponse41
      */
    def run(
        channel: MysqlChannel,
        serverCaps: Long,
        clientCaps: Long,
        host: String,
        port: Int,
        tls: NetTlsConfig
    )(using Frame): MysqlChannel < (Async & Abort[SqlException]) =
        // Verify that the server advertises CLIENT_SSL support.
        if (serverCaps & Capabilities.CLIENT_SSL) == 0L then
            Abort.fail(SqlConnectionTlsNotAdvertisedException(host, port))
        else
            // Use the same capabilities as the full HandshakeResponse41 so MySQL's auth state machine
            // is configured correctly before the TLS handshake begins.
            val sslRequest = SslRequest(
                capabilities = clientCaps,
                maxPacket = MaxPacketSize,
                charset = Charset
            )
            // Send SslRequest using the existing channel (seqId=1, after the server's HandshakeV10 seqId=0).
            channel.send(sslRequest)(using channel.marshallers.sslRequest).flatMap { _ =>
                // Inject the connect host into sniHostname when the caller left it empty so that JDK's
                // hostname verifier and JS's Node TLS have a reference identity to check the server
                // certificate against (mirrors the Postgres path in InitSSLExchange). Without this,
                // sslmode=verify-full on MySQL is either fail-closed on JVM or verifies against the
                // JS default servername ("localhost"), neither of which is useful.
                val tlsWithHost =
                    if host.nonEmpty && tls.sniHostname.isEmpty then tls.copy(sniHostname = Present(host))
                    else tls
                // Perform TLS upgrade on the underlying connection (no additional protocol bytes exchanged).
                Abort.run[kyo.net.NetException] {
                    Sync.Unsafe.defer {
                        kyo.net.NetPlatform.transport.upgradeToTls(
                            channel.conn,
                            tlsWithHost,
                            kyo.net.NetConfig.DefaultChannelCapacity
                        ).safe.use(identity)
                    }
                }.flatMap {
                    case Result.Success(tlsConn) =>
                        // Create a new MysqlChannel over the TLS connection, but preserve the current seqId
                        // so the next send (full HandshakeResponse41) uses seqId=2.
                        MysqlChannel(tlsConn).map { tlsChannel =>
                            // Transfer seqId: the SslRequest was sent with seqId N; the server will expect N+1 next.
                            // channel.currentSeq already reflects the incremented value after the send.
                            tlsChannel.setSeq(channel.currentSeq)
                            tlsChannel
                        }
                    case Result.Failure(netEx) =>
                        Abort.fail(SqlConnectionConnectFailedException(host, port, netEx))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] InitTlsExchange: TLS upgrade panic: ${t.getMessage}")
                        Abort.fail(SqlConnectionConnectFailedException(host, port, t))
                }
            }
        end if
    end run

end InitTlsExchange
