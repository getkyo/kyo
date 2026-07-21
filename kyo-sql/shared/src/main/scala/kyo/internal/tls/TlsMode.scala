package kyo.internal.tls

/** Supported TLS modes, matching PostgreSQL's `sslmode` connection parameter semantics.
  *
  * Variants:
  *   - [[Disable]], no TLS; all traffic is plaintext.
  *   - [[Allow]], prefer plaintext; upgrade to TLS only if the server demands it (opportunistic).
  *   - [[Prefer]], prefer TLS; fall back to plaintext if the server does not support it (opportunistic).
  *   - [[Require]], TLS is mandatory; accept any certificate (no CA verification, no hostname check).
  *   - [[VerifyCa]], TLS is mandatory; validate the server certificate chain against the configured CA bundle (`sslrootcert`), but skip
  *     hostname verification.
  *   - [[VerifyFull]], TLS is mandatory; validate the certificate chain AND verify the server hostname against the certificate's CN/SAN.
  */
enum TlsMode derives CanEqual:
    case Disable
    case Allow
    case Prefer
    case Require
    case VerifyCa
    case VerifyFull
end TlsMode
