package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConfig.Address
import kyo.SqlConfig.TlsMode
import kyo.SqlConnectionConnectFailedException
import kyo.SqlConnectionTlsNotConfiguredException
import kyo.SqlConnectionWritePanicException
import kyo.SqlException
import kyo.internal.postgres.CancelRequest
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.marshaller.CancelRequestMarshaller
import kyo.net.NetPlatform
import kyo.net.NetTlsConfig

/** Sends a [[CancelRequest]] on a brand-new TCP connection (no auth, no StartupMessage).
  *
  * The `(processId, secretKey)` pair is a credential: the server accepts it from anyone who presents it on a bare connection, so which socket
  * it travels on is a security property rather than a preference. It is therefore decided by `tlsMode`, and decided by the same code the
  * pooled connect path uses, [[InitSSLExchange]]. A mode demanding encryption gets the strict form, which ends the cancel when the server
  * declines the upgrade; an opportunistic mode gets the prefer form, for which plaintext is a legitimate outcome.
  *
  * Reference: PostgreSQL §55.2.7 "Canceling Requests in Progress"
  */
private[kyo] object CancelExchange:

    /** Opens a fresh connection to `address` and asks the server to stop the statement running under `(processId, secretKey)`.
      *
      * @param tlsMode
      *   the mode the caller configured, which decides whether a declined upgrade ends the cancel or continues in the clear
      * @param tls
      *   the TLS settings to negotiate with, [[Absent]] when the caller configured none
      */
    def cancel(
        address: SqlConfig.Address,
        tlsMode: TlsMode,
        tls: Maybe[NetTlsConfig],
        processId: Int,
        secretKey: Int
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        tls match
            case Absent =>
                if tlsMode.demandsEncryption then
                    // The refusal `SqlConnectionPool.connect` already makes before it opens a pooled connection, restated at
                    // the one other place that opens a socket to this server. Before the connect rather than after, because
                    // a mode with nothing to encrypt with has no socket this credential may travel on.
                    Abort.fail(SqlConnectionTlsNotConfiguredException(tlsMode))
                else onFreshConnection(address)(sendCancel(_, processId, secretKey))
            case Present(tlsConfig) =>
                onFreshConnection(address) { rawConn =>
                    negotiate(rawConn, address, tlsMode, tlsConfig).flatMap { conn =>
                        // The SECOND bracket, and it is not redundant with the one `onFreshConnection` holds. A successful
                        // STARTTLS upgrade returns a DIFFERENT connection owning the same descriptor, and by then a close on
                        // the pre-upgrade one is a no-op, so the connection the cancel was written on needs its own. Over the
                        // plaintext fallback the two are the same connection and the second close is idempotent.
                        closingOnce(conn)(sendCancel(conn, processId, secretKey))
                    }
                }
    end cancel

    /** Opens a fresh plaintext connection to `address`, runs `body` on it, and closes it, reporting a failed or panicking connect as a typed
      * refusal.
      *
      * The close here covers the socket this method opened, which matters most on the edges where `body` never produces a connection to close:
      * a server declining the upgrade, answering nothing, or failing the handshake. Under a mode demanding encryption those are the ordinary
      * outcomes rather than exotic ones, so a bracket placed only around a successful negotiation would miss the common path.
      */
    private def onFreshConnection[A](address: SqlConfig.Address)(
        body: kyo.net.Connection => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        // Unsafe: raw kyo-net transport connect; the cancel sidecar socket is opened outside the safe tier.
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
                closingOnce(rawConn)(body(rawConn))
        }

    /** Runs `body` and closes `conn` exactly once, on whichever edge ends it.
      *
      * Two paths, because neither reaches every edge, and the shape is [[kyo.internal.mysql.MysqlSqlConnection]]'s own cancel-sidecar bracket for
      * the same reason. `Sync.ensure` fires on an interrupt or a panic, including the interrupt the pool's cancel budget delivers when it expires;
      * it does NOT fire on a typed `Abort` whose handler sits outside it, and a declined TLS upgrade is exactly such a failure, so trusting the
      * finalizer alone would leak the socket on every refused cancel. The typed and successful edges are therefore resolved by handling the abort
      * and re-raising it, and a flag makes the close idempotent so both paths firing costs nothing.
      *
      * The close is owed on the SUCCESSFUL edge too, which is what separates this from [[kyo.db.Connection.closingOnFailure]]: a cancel
      * is one write and then nothing, so no caller downstream inherits the socket. Nothing else closed it either, because the connect hands back
      * a completed `Fiber` rather than a scoped resource, so awaiting it registers no finalizer.
      *
      * Closing straight after the write does not discard it: the descriptor is released only once the write pump has finished with the outbound
      * channel, so every queued span has actually been written rather than merely taken off the channel.
      */
    private def closingOnce[A](conn: kyo.net.Connection)(body: A < (Async & Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        // Unsafe: a plain flag, read and written only by the two resolution paths below.
        Sync.Unsafe.defer(AtomicBoolean.Unsafe.init(false)).flatMap { closed =>
            def once(using Frame): Unit < Sync =
                Sync.Unsafe.defer(closed.compareAndSet(false, true)).flatMap {
                    // Unsafe: closing a socket this exchange owns outright, outside fiber suspension.
                    case true  => Sync.Unsafe.defer(conn.close())
                    case false => ()
                }
            Sync.ensure(once) {
                Abort.run[SqlException](body).flatMap { outcome =>
                    once.andThen {
                        outcome match
                            case Result.Success(a) => a
                            case Result.Failure(e) => Abort.fail[SqlException](e)
                            case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    }
                }
            }
        }

    /** Resolves which connection the [[CancelRequest]] may be written on, for a caller that configured TLS settings.
      *
      * Branches on [[TlsMode.demandsEncryption]] rather than on the modes themselves, so a mode added to the enum lands on whichever side its
      * own answer puts it. Under a demanding mode [[InitSSLExchange.run]] returns a TLS-wrapped connection or fails, and there is no third
      * outcome: a declined upgrade, an unanswered SSLRequest and a failed handshake all end the cancel rather than producing a socket. Under
      * an opportunistic mode plaintext IS a legitimate outcome, and [[InitSSLExchange.runPrefer]] reuses the socket for it exactly as the
      * pooled `prefer` path does through [[kyo.internal.postgres.TlsNegotiator]].
      */
    private def negotiate(rawConn: kyo.net.Connection, address: SqlConfig.Address, tlsMode: TlsMode, tls: NetTlsConfig)(using
        Frame
    ): kyo.net.Connection < (Async & Abort[SqlException]) =
        if tlsMode.demandsEncryption then InitSSLExchange.run(rawConn, address.host, address.port, tls)
        else InitSSLExchange.runPrefer(rawConn, address.host, address.port, tls)

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
