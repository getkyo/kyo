package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConnectionClosedException
import kyo.SqlConnectionConnectFailedException
import kyo.SqlConnectionSslRequestFailedException
import kyo.SqlConnectionTlsNotAdvertisedException
import kyo.SqlConnectionWritePanicException
import kyo.SqlException
import kyo.internal.postgres.PostgresBufferWriter
import kyo.net.Connection
import kyo.net.NetTlsConfig

/** Sends the Postgres SSLRequest pre-startup packet and upgrades the connection to TLS if the server accepts.
  *
  * Protocol (§55.2.10 of the PG docs):
  *   1. Client sends SSLRequest: 8 bytes total, Int32(8) length | Int32(80877103) magic.
  *   2. Server replies with a single byte: 'S' (SSL supported) or 'N' (not supported). No other bytes follow before the TLS handshake.
  *   3. On 'S': call conn.upgradeToTls(tls) on the same socket, returning a new TLS-wrapped Connection.
  *   4. On 'N': behaviour depends on mode:
  *      - strict mode (require/verify-ca/verify-full): fail with [[SqlConnectionException]].
  *      - prefer mode: return the original plaintext [[Connection]] so startup can proceed without TLS.
  *   5. Any other byte is a protocol error.
  *
  * The socket CAN be reused after 'N': per PG protocol §55.2.10, "the connection can then be used normally for non-TLS communication."
  */
private[internal] object InitSSLExchange:

    // SSLRequest magic constant from the Postgres protocol spec.
    private val SslRequestMagic: Int = 80877103

    /** Builds and sends the 8-byte SSLRequest, reads the 1-byte server response, and either upgrades to TLS or fails.
      *
      * @param conn
      *   plaintext [[Connection]] (must be freshly opened; StartupMessage must NOT have been sent yet)
      * @param host
      *   server hostname, used only in error messages
      * @param port
      *   server port, used only in error messages
      * @param tls
      *   TLS configuration to pass to [[Connection.upgradeToTls]]
      * @return
      *   a new TLS-wrapped [[Connection]] on 'S', or fails with [[SqlConnectionException]] on 'N' / protocol error
      */
    def run(
        conn: Connection,
        host: String,
        port: Int,
        tls: NetTlsConfig
    )(using Frame): Connection < (Async & Abort[SqlException]) =
        sendSslRequest(conn, host, port).flatMap {
            case Result.Failure(e) => Abort.fail(e)
            case Result.Panic(t)   => Abort.error(Result.Panic(t))
            case Result.Success(_) =>
                readSslResponse(conn, host, port, tls, fallbackToPlaintext = false)
        }
    end run

    /** Sends SSLRequest and upgrades to TLS if server accepts; falls back to plaintext if server responds 'N'.
      *
      * Used by `sslmode=prefer`: try TLS first, but accept 'N' as a signal to proceed without TLS. Per PG protocol §55.2.10, the same
      * socket can be reused for plaintext startup after a 'N' response.
      *
      * @param conn
      *   plaintext [[Connection]]
      * @param host
      *   server hostname
      * @param port
      *   server port
      * @param tls
      *   TLS configuration to pass to [[Connection.upgradeToTls]] if server accepts
      * @return
      *   a TLS-wrapped [[Connection]] on 'S'; the original plaintext [[Connection]] on 'N'
      */
    def runPrefer(
        conn: Connection,
        host: String,
        port: Int,
        tls: NetTlsConfig
    )(using Frame): Connection < (Async & Abort[SqlException]) =
        sendSslRequest(conn, host, port).flatMap {
            case Result.Failure(e) => Abort.fail(e)
            case Result.Panic(t)   => Abort.error(Result.Panic(t))
            case Result.Success(_) =>
                readSslResponse(conn, host, port, tls, fallbackToPlaintext = true)
        }
    end runPrefer

    private def sendSslRequest(
        conn: Connection,
        host: String,
        port: Int
    )(using Frame): Result[SqlException, Unit] < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        buf.writeInt32(8) // total length = 8 bytes
        buf.writeInt32(SslRequestMagic)
        val packet = buf.toSpan
        Abort.run[Closed](conn.outbound.safe.put(packet)).flatMap {
            case Result.Failure(_) =>
                Result.Failure(SqlConnectionClosedException("writing (SSLRequest)"))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] InitSSLExchange: write panic: ${t.getMessage}").andThen(
                    Result.Failure(SqlConnectionWritePanicException(t))
                )
            case Result.Success(_) =>
                Result.unit
        }
    end sendSslRequest

    private def readSslResponse(
        conn: Connection,
        host: String,
        port: Int,
        tls: NetTlsConfig,
        fallbackToPlaintext: Boolean
    )(using Frame): Connection < (Async & Abort[SqlException]) =
        Abort.run[Closed](conn.inbound.safe.take).flatMap {
            case Result.Failure(_) =>
                Abort.fail(SqlConnectionClosedException("reading (SSLRequest response)"))
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] InitSSLExchange: read panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlConnectionClosedException("reading (panic: " + t.getMessage + ")"))
                )
            case Result.Success(span) =>
                if span.isEmpty then
                    Abort.fail(SqlConnectionSslRequestFailedException(host, port, 0.toByte))
                else if span.size > 1 then
                    // Protocol error: server sent more than 1 byte before TLS handshake.
                    Abort.fail(SqlConnectionSslRequestFailedException(host, port, span(0)))
                else
                    val responseByte = span(0)
                    responseByte match
                        case 'S' =>
                            // Server accepts TLS, upgrade the connection.
                            // Inject the server hostname into sniHostname (if not already set) so that
                            // NioTransport.upgradeToTls can apply HTTPS endpoint identification when
                            // hostnameVerification is enabled. Without this, upgradeToTls receives an
                            // empty host string and skips hostname verification entirely.
                            val tlsWithHost =
                                if host.nonEmpty && tls.sniHostname.isEmpty then tls.copy(sniHostname = Present(host))
                                else tls
                            Abort.run[kyo.net.NetException] {
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
                                    Log.error(s"[kyo-sql] InitSSLExchange: TLS upgrade panic: ${t.getMessage}").andThen(
                                        Abort.fail(SqlConnectionConnectFailedException(host, port, t))
                                    )
                            }
                        case 'N' =>
                            if fallbackToPlaintext then
                                // sslmode=prefer: server doesn't support TLS; reuse socket for plaintext startup.
                                // Per PG protocol §55.2.10, the same socket continues with plaintext after 'N'.
                                conn
                            else
                                Abort.fail(SqlConnectionTlsNotAdvertisedException(host, port))
                        case other =>
                            Abort.fail(SqlConnectionSslRequestFailedException(host, port, other))
                    end match
                end if
        }
    end readSslResponse

end InitSSLExchange
