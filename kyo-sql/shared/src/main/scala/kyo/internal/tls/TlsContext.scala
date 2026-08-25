package kyo.internal.tls

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.SqlConfig.TlsMode
import kyo.SqlConnectionException
import kyo.SqlConnectionTlsConfigException
import kyo.net.NetTlsConfig

/** Builds a `kyo.net.NetTlsConfig` for a given [[SqlConfig.TlsMode]] and optional CA certificate path.
  *
  * All TLS configuration construction for kyo-sql routes through this object so that the mode → `kyo.net.NetTlsConfig` mapping is expressed in
  * one place and is not duplicated across callers.
  *
  * Mapping rules:
  *   - [[SqlConfig.TlsMode.Disable]] → [[Absent]] (no TLS)
  *   - [[SqlConfig.TlsMode.Allow]] → `Present(NetTlsConfig(trustAll = true))` (TLS config available for opportunistic upgrade; decision made by
  *     negotiator)
  *   - [[SqlConfig.TlsMode.Prefer]] → `Present(NetTlsConfig(trustAll = true))` (TLS config available for opportunistic upgrade; decision made by
  *     negotiator)
  *   - [[SqlConfig.TlsMode.Require]] → `Present(NetTlsConfig(trustAll = true))` (TLS mandatory; no cert-chain or hostname check, `require` only
  *     mandates encryption per PG/MySQL spec)
  *   - [[SqlConfig.TlsMode.VerifyCa]] → requires `caCertPath`; `Present(NetTlsConfig(caCertPath = Present(path), hostnameVerification = false))`.
  *     Fails with [[SqlConnectionException]] when `caCertPath` is [[Absent]].
  *   - [[SqlConfig.TlsMode.VerifyFull]] → requires `caCertPath`; `Present(NetTlsConfig(caCertPath = Present(path), hostnameVerification = true))`.
  *     Fails with [[SqlConnectionException]] when `caCertPath` is [[Absent]].
  *
  * Note: for `Allow` and `Prefer`, the TLS config is available and whether the connection actually upgrades is decided later, from the
  * server's response during the connection protocol exchange. Where that decision sits differs per engine, so this type names neither
  * decider: PostgreSQL asks before the handshake begins with an out-of-band SSLRequest, and MySQL reads the server's `CLIENT_SSL`
  * capability out of the handshake packet and so decides mid-handshake.
  */
object TlsContext:

    def build(mode: TlsMode, caCertPath: Maybe[String])(using Frame): Maybe[NetTlsConfig] < Abort[SqlConnectionException] =
        mode match
            case TlsMode.Disable =>
                Absent
            case TlsMode.Allow | TlsMode.Prefer =>
                // Opportunistic TLS: provide a permissive TLS config; whether the upgrade happens is decided per engine, not here.
                // trustAll=true because allow/prefer are not certificate-validating modes, the server's cert is accepted as-is.
                Present(NetTlsConfig(trustAll = true))
            case TlsMode.Require =>
                // TLS required; accept ANY server cert (no chain validation, no hostname check).
                // Per PG/MySQL spec, `require` only mandates encryption, it does NOT validate the
                // server's certificate. Use trustAll=true so self-signed or unknown-CA certs work.
                // Validation modes are `verify-ca` (chain only) and `verify-full` (chain + hostname).
                Present(NetTlsConfig(trustAll = true))
            case TlsMode.VerifyCa =>
                caCertPath match
                    case Present(path) =>
                        Present(NetTlsConfig(caCertPath = Present(path), hostnameVerification = false))
                    case Absent =>
                        Abort.fail(SqlConnectionTlsConfigException(TlsMode.VerifyCa))
            case TlsMode.VerifyFull =>
                caCertPath match
                    case Present(path) =>
                        Present(NetTlsConfig(caCertPath = Present(path), hostnameVerification = true))
                    case Absent =>
                        Abort.fail(SqlConnectionTlsConfigException(TlsMode.VerifyFull))

end TlsContext
