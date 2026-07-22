package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConfig.Address
import kyo.SqlConnectionConnectFailedException
import kyo.SqlConnectionWritePanicException
import kyo.SqlException
import kyo.internal.postgres.CancelRequest
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.marshaller.CancelRequestMarshaller
import kyo.net.NetPlatform
import kyo.net.NetTlsConfig

/** Sends a [[CancelRequest]] on a brand-new TCP connection (no auth, no StartupMessage).
  *
  * Reference: PostgreSQL §55.2.7 "Canceling Requests in Progress"
  */
private[kyo] object CancelExchange:

    def cancel(
        address: SqlConfig.Address,
        tls: Maybe[NetTlsConfig],
        processId: Int,
        secretKey: Int
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        Scope.run {
            Abort.run[kyo.net.NetException](Sync.Unsafe.defer(NetPlatform.transport.connect(
                address.host,
                address.port
            ).safe).flatMap(_.use(identity))).flatMap {
                case Result.Failure(_) =>
                    Abort.fail(SqlConnectionConnectFailedException(address.host, address.port, new Exception("cancel connect failed")))
                case Result.Panic(t) =>
                    Log.error(s"[kyo-sql] CancelExchange: connect panic: ${t.getMessage}").andThen(
                        Abort.fail(SqlConnectionConnectFailedException(address.host, address.port, t))
                    )
                case Result.Success(rawConn) =>
                    tls match
                        case Absent =>
                            sendCancel(rawConn, processId, secretKey)
                        case Present(tlsConfig) =>
                            upgradeTlsOrFallback(rawConn, tlsConfig).flatMap(sendCancel(_, processId, secretKey))
            }
        }

    private def upgradeTlsOrFallback(rawConn: kyo.net.Connection, tls: NetTlsConfig)(using
        Frame
    ): kyo.net.Connection < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        buf.writeInt32(8); buf.writeInt32(80877103)
        Abort.run[Closed](rawConn.outbound.safe.put(buf.toSpan)).flatMap {
            case Result.Failure(_) | Result.Panic(_) => rawConn
            case Result.Success(_) =>
                Abort.run[Closed](rawConn.inbound.safe.take).flatMap {
                    case Result.Failure(_) | Result.Panic(_) => rawConn
                    case Result.Success(span) =>
                        if span.nonEmpty && span(0) == 'S'.toByte then
                            Abort.run[kyo.net.NetException] {
                                Sync.Unsafe.defer(
                                    kyo.net.NetPlatform.transport.upgradeToTls(
                                        rawConn,
                                        tls,
                                        kyo.net.NetConfig.DefaultChannelCapacity
                                    ).safe
                                ).flatMap(_.use(identity))
                            }.flatMap {
                                case Result.Success(tlsConn) => tlsConn
                                case _                       => rawConn
                            }
                        else rawConn
                }
        }
    end upgradeTlsOrFallback

    private def sendCancel(conn: kyo.net.Connection, processId: Int, secretKey: Int)(using Frame): Unit < (Async & Abort[SqlException]) =
        val buf = new PostgresBufferWriter
        CancelRequestMarshaller.write(CancelRequest(processId, secretKey), buf)
        Abort.run[Closed](conn.outbound.safe.put(buf.toSpan)).flatMap {
            case Result.Success(_) | Result.Failure(_) => () // Failure = server closed, acceptable for cancel
            case Result.Panic(t) =>
                Log.error(s"[kyo-sql] CancelExchange: write panic: ${t.getMessage}").andThen(
                    Abort.fail(SqlConnectionWritePanicException(t))
                )
        }
    end sendCancel

end CancelExchange
