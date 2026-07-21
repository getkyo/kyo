package kyo.internal.tls

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.exchange.InitSSLExchange
import kyo.net.Connection
import kyo.net.NetTlsConfig

/** Opportunistic TLS orchestration layer, sitting ABOVE the individual connection `connect` methods.
  *
  * A [[TlsNegotiator]] intercepts a raw plaintext [[Connection]] and either:
  *   - upgrades it to TLS (returning a TLS-wrapped [[Connection]]), or
  *   - returns it unchanged (proceeding with plaintext startup).
  *
  * The decision is mode-specific:
  *   - [[PostgresNegotiator]] with `prefer`: sends SSLRequest; upgrades on 'S', falls back to plaintext on 'N'. The plaintext socket is
  *     reused (per PG protocol §55.2.10: after 'N', the same socket continues with plaintext startup messages).
  *   - [[MysqlNegotiator]] with `prefer`: consults the server's capability flags in HandshakeV10; upgrades if CLIENT_SSL advertised, falls
  *     back to plaintext if not.
  *
  * The `allow` mode reconnect path (plaintext first, then TLS on server refusal) is handled at the backend layer
  * ([[kyo.internal.client.PgSqlClientBackend]] / [[kyo.internal.client.MySqlClientBackend]]) because the reconnect requires opening a fresh
  * TCP connection — which is above this layer's scope.
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
      *   the TLS mode (must be [[TlsMode.Prefer]] for meaningful negotiation; [[TlsMode.Allow]] negotiation is a no-op — the allow-upgrade
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

    /** Selects the appropriate [[TlsNegotiator]] for a MySQL connection, by mode.
      *
      * For MySQL, the actual negotiation happens inside [[HandshakeExchange.run]] (which reads the server's capability flags). The MySQL
      * negotiator's [[negotiate]] method is a no-op; the mode is threaded through [[HandshakeExchange.run]] via the `preferFallback` flag.
      *
      * @param mode
      *   the TLS mode
      */
    def mysql(mode: TlsMode): TlsNegotiator =
        MysqlNegotiator(mode)

end TlsNegotiator

/** PostgreSQL opportunistic TLS negotiator.
  *
  * For `prefer`: sends SSLRequest; upgrades on 'S', falls back to plaintext on 'N'. For `allow`: returns conn unchanged (the allow-upgrade
  * path is handled at the backend layer after startup fails).
  */
final private[tls] class PostgresNegotiator(mode: TlsMode, tls: NetTlsConfig, host: String, port: Int) extends TlsNegotiator:

    def negotiate(conn: Connection)(using Frame): Connection < (Async & Abort[SqlException]) =
        mode match
            case TlsMode.Prefer =>
                // Send SSLRequest; upgrade on 'S', reuse socket for plaintext on 'N'.
                InitSSLExchange.runPrefer(conn, host, port, tls)
            case TlsMode.Allow =>
                // allow: proceed plaintext; if startup fails with "SSL required", the backend layer reconnects with TLS.
                conn
            case _ =>
                // Other modes are not routed through TlsNegotiator.
                conn

end PostgresNegotiator

/** MySQL opportunistic TLS negotiator.
  *
  * For MySQL, TLS negotiation happens inside [[HandshakeExchange.run]] which reads the server's CLIENT_SSL capability flag. The
  * [[negotiate]] method here is a pre-handshake no-op — it returns the connection unchanged. The actual mode-aware behaviour is wired into
  * [[HandshakeExchange.run]] via the `preferFallback` flag and via the [[NetTlsConfig]] produced by [[TlsContext.build]]:
  *
  *   - [[TlsMode.Disable]] — no TLS; `tls=Absent` is passed to HandshakeExchange; no upgrade attempt.
  *   - [[TlsMode.Allow]] — opportunistic plaintext; backend layer reconnects with TLS on ER_SECURE_TRANSPORT_REQUIRED (error 3159).
  *   - [[TlsMode.Prefer]] — `preferFallback=true` in HandshakeExchange; upgrades when server advertises CLIENT_SSL, falls back to plaintext
  *     otherwise.
  *   - [[TlsMode.Require]] — `preferFallback=false`; TLS mandatory; no CA or hostname verification (`NetTlsConfig(hostnameVerification =
  *     false)`). HandshakeExchange fails if server does not advertise CLIENT_SSL.
  *   - [[TlsMode.VerifyCa]] — `preferFallback=false`; TLS mandatory; CA certificate chain verified (`caCertPath` required); no hostname
  *     check. HandshakeExchange fails if server does not advertise CLIENT_SSL.
  *   - [[TlsMode.VerifyFull]] — `preferFallback=false`; TLS mandatory; CA chain + hostname verified (`caCertPath` required).
  *     HandshakeExchange fails if server does not advertise CLIENT_SSL.
  *
  * @param mode
  *   the TLS mode determining the security semantics of the connection
  */
final private[tls] class MysqlNegotiator(mode: TlsMode) extends TlsNegotiator:

    /** Returns the connection unchanged.
      *
      * MySQL TLS negotiation is mid-handshake (inside [[HandshakeExchange.run]]) because the server's CLIENT_SSL capability is only known
      * after receiving the initial HandshakeV10 packet. Unlike Postgres (which sends an out-of-band SSLRequest before any handshake data),
      * MySQL cannot upgrade TLS before the handshake begins. The pre-handshake [[negotiate]] hook is therefore a no-op for all MySQL modes;
      * the mode-specific semantics are enforced by [[HandshakeExchange.run]] via `preferFallback` and by the [[NetTlsConfig]] produced by
      * [[TlsContext.build]].
      */
    def negotiate(conn: Connection)(using Frame): Connection < (Async & Abort[SqlException]) =
        mode match
            case TlsMode.Disable =>
                // No TLS — return the plaintext connection unchanged. HandshakeExchange receives tls=Absent.
                conn
            case TlsMode.Allow =>
                // Opportunistic plaintext — return unchanged. The backend layer reconnects with TLS on ER_SECURE_TRANSPORT_REQUIRED.
                conn
            case TlsMode.Prefer =>
                // Opportunistic TLS — return unchanged. HandshakeExchange uses preferFallback=true to upgrade when CLIENT_SSL is
                // advertised and fall back to plaintext otherwise.
                conn
            case TlsMode.Require =>
                // TLS required, no CA or hostname verification — return unchanged. HandshakeExchange uses preferFallback=false and
                // fails if the server does not advertise CLIENT_SSL. NetTlsConfig(hostnameVerification=false) is used.
                conn
            case TlsMode.VerifyCa =>
                // TLS required, CA chain verified, no hostname check — return unchanged. HandshakeExchange uses preferFallback=false
                // and fails if the server does not advertise CLIENT_SSL. NetTlsConfig with caCertPath is used.
                conn
            case TlsMode.VerifyFull =>
                // TLS required, CA chain + hostname verified — return unchanged. HandshakeExchange uses preferFallback=false and
                // fails if the server does not advertise CLIENT_SSL. NetTlsConfig with caCertPath and hostnameVerification=true is used.
                conn

end MysqlNegotiator
