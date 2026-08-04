package kyo.internal.postgres

import kyo.*
import kyo.SqlConfig.TlsMode
import kyo.SqlException
import kyo.net.Connection
import kyo.net.NetTlsConfig

/** Opportunistic TLS orchestration for PostgreSQL, sitting ABOVE [[PostgresConnection]]'s `connect` methods.
  *
  * A [[TlsNegotiator]] intercepts a raw plaintext [[Connection]] and either:
  *   - upgrades it to TLS (returning a TLS-wrapped [[Connection]]), or
  *   - returns it unchanged (proceeding with plaintext startup).
  *
  * Under `prefer` it sends SSLRequest, upgrades on 'S', and falls back to plaintext on 'N'. The plaintext socket is reused rather than
  * reopened, per PG protocol §55.2.10: after 'N' the same socket continues with plaintext startup messages.
  *
  * The `allow` mode reconnect path (plaintext first, then TLS on server refusal) is handled where connections are opened
  * ([[PostgresSqlConnection]]) because the reconnect requires opening a fresh TCP connection, which is above this layer's scope.
  *
  * This layer is Postgres-only, and the reason is protocol rather than convention: PostgreSQL is the one engine here that can upgrade before
  * the handshake begins, because its SSLRequest is out of band and precedes any startup message. MySQL learns whether the server advertises
  * `CLIENT_SSL` only from the HandshakeV10 packet, so it cannot decide this early; it negotiates mid-handshake instead, from the
  * `preferFallback` flag its connection derives from the mode. So there is no engine-neutral abstraction to be had at this point in the
  * sequence, and a MySQL implementation of this trait would have nothing to do but return its argument.
  */
sealed trait TlsNegotiator:
    /** Negotiates TLS on a raw plaintext [[Connection]].
      *
      * @param conn
      *   a freshly-opened, plaintext [[Connection]] (no bytes exchanged yet)
      * @return
      *   either a TLS-upgraded [[Connection]] or the original plaintext [[Connection]], depending on mode and server response
      */
    def negotiate(conn: Connection)(using Frame): Connection < (Async & Abort[SqlException])
end TlsNegotiator

object TlsNegotiator:

    /** Selects the appropriate [[TlsNegotiator]] for a Postgres connection, by mode.
      *
      * @param mode
      *   the TLS mode (must be [[TlsMode.Prefer]] for meaningful negotiation; [[TlsMode.Allow]] negotiation is a no-op, the allow-upgrade
      *   path is handled at the backend layer)
      * @param tls
      *   the TLS configuration to use when upgrading
      * @param host
      *   server hostname (for error messages and SNI)
      * @param port
      *   server port (for error messages)
      */
    def postgres(mode: TlsMode, tls: NetTlsConfig, host: String, port: Int): TlsNegotiator =
        PostgresNegotiator(mode, tls, host, port)

end TlsNegotiator

/** PostgreSQL opportunistic TLS negotiator.
  *
  * For `prefer`: sends SSLRequest; upgrades on 'S', falls back to plaintext on 'N'. For `allow`: returns conn unchanged (the allow-upgrade
  * path is handled at the backend layer after startup fails).
  */
final private[postgres] class PostgresNegotiator(mode: TlsMode, tls: NetTlsConfig, host: String, port: Int) extends TlsNegotiator:

    def negotiate(conn: Connection)(using Frame): Connection < (Async & Abort[SqlException]) =
        mode match
            case TlsMode.Prefer =>
                // Send SSLRequest; upgrade on 'S', reuse socket for plaintext on 'N'.
                exchange.InitSSLExchange.runPrefer(conn, host, port, tls)
            case TlsMode.Allow =>
                // allow: proceed plaintext; if startup fails with "SSL required", the backend layer reconnects with TLS.
                conn
            case _ =>
                // Other modes are not routed through TlsNegotiator.
                conn

end PostgresNegotiator
