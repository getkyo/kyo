package kyo.internal.tls

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.SqlException
import kyo.net.NetTlsConfig

/** Builds a [[NetTlsConfig]] for a given [[TlsMode]] and optional CA certificate path.
  *
  * All TLS configuration construction for kyo-sql routes through this object so that the mode → [[NetTlsConfig]] mapping is expressed in
  * one place and is not duplicated across callers.
  *
  * Mapping rules:
  *   - [[TlsMode.Disable]] → [[Absent]] (no TLS)
  *   - [[TlsMode.Allow]] → `Present(NetTlsConfig(trustAll = true))` (TLS config available for opportunistic upgrade; decision made by
  *     negotiator)
  *   - [[TlsMode.Prefer]] → `Present(NetTlsConfig(trustAll = true))` (TLS config available for opportunistic upgrade; decision made by
  *     negotiator)
  *   - [[TlsMode.Require]] → `Present(NetTlsConfig(trustAll = true))` (TLS mandatory; no cert-chain or hostname check — `require` only
  *     mandates encryption per PG/MySQL spec)
  *   - [[TlsMode.VerifyCa]] → requires `caCertPath`; `Present(NetTlsConfig(caCertPath = Present(path), hostnameVerification = false))`.
  *     Fails with [[SqlException.Connection]] when `caCertPath` is [[Absent]].
  *   - [[TlsMode.VerifyFull]] → requires `caCertPath`; `Present(NetTlsConfig(caCertPath = Present(path), hostnameVerification = true))`.
  *     Fails with [[SqlException.Connection]] when `caCertPath` is [[Absent]].
  *
  * Note: for `Allow` and `Prefer`, the TLS config is available but the [[TlsNegotiator]] decides whether to actually upgrade based on the
  * server's response during the connection protocol exchange.
  */
object TlsContext:

    def build(mode: TlsMode, caCertPath: Maybe[String])(using Frame): Maybe[NetTlsConfig] < Abort[SqlException.Connection] =
        mode match
            case TlsMode.Disable =>
                Absent
            case TlsMode.Allow | TlsMode.Prefer =>
                // Opportunistic TLS: provide a permissive TLS config; the TlsNegotiator decides whether to upgrade.
                // trustAll=true because allow/prefer are not certificate-validating modes — the server's cert is accepted as-is.
                Present(NetTlsConfig(trustAll = true))
            case TlsMode.Require =>
                // TLS required; accept ANY server cert (no chain validation, no hostname check).
                // Per PG/MySQL spec, `require` only mandates encryption — it does NOT validate the
                // server's certificate. Use trustAll=true so self-signed or unknown-CA certs work.
                // Validation modes are `verify-ca` (chain only) and `verify-full` (chain + hostname).
                Present(NetTlsConfig(trustAll = true))
            case TlsMode.VerifyCa =>
                caCertPath match
                    case Present(path) =>
                        Present(NetTlsConfig(caCertPath = Present(path), hostnameVerification = false))
                    case Absent =>
                        Abort.fail(SqlException.Connection("sslmode=verify-ca requires sslrootcert", summon[Frame]))
            case TlsMode.VerifyFull =>
                caCertPath match
                    case Present(path) =>
                        Present(NetTlsConfig(caCertPath = Present(path), hostnameVerification = true))
                    case Absent =>
                        Abort.fail(SqlException.Connection("sslmode=verify-full requires sslrootcert", summon[Frame]))

end TlsContext
