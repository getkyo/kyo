package kyo

/** TLS configuration for client and server connections.
  *
  * The same type is used on both sides of a connection, but with different fields in play:
  *   - **Client**: `trustAll` skips certificate validation (development only). `sniHostname` overrides the SNI extension sent during the
  *     handshake when connecting through a proxy or when the TCP host differs from the certificate's CN/SAN.
  *   - **Server**: `certChainPath` and `privateKeyPath` point to PEM files for TLS termination. `clientAuth` controls whether clients must
  *     present a certificate (mutual TLS). `trustStorePath` provides the CA bundle for verifying client certificates.
  *
  * Both sides respect `minVersion` and `maxVersion` to constrain the negotiated TLS version. The defaults allow TLS 1.2 and 1.3.
  *
  * WARNING: Setting `trustAll = true` disables all certificate validation and makes the connection vulnerable to MITM attacks. Use only in
  * development or integration test environments where you control both endpoints.
  *
  * @see
  *   [[kyo.HttpClientConfig]] Accepts an `HttpTlsConfig` via the `tls` field
  * @see
  *   [[kyo.HttpServerConfig]] Accepts an `HttpTlsConfig` via the `tls` field
  */
case class HttpTlsConfig(
    /** Skip certificate validation entirely. Development/testing only. */
    trustAll: Boolean = false,
    /** Override SNI hostname (defaults to connect host). */
    sniHostname: Maybe[String] = Absent,
    /** PEM-encoded certificate chain file path (server-side). */
    certChainPath: Maybe[String] = Absent,
    /** PEM-encoded private key file path (server-side). */
    privateKeyPath: Maybe[String] = Absent,
    /** Client certificate authentication mode for servers. */
    clientAuth: HttpTlsConfig.ClientAuth = HttpTlsConfig.ClientAuth.None,
    /** CA certificates file path for verifying client certs. */
    trustStorePath: Maybe[String] = Absent,
    /** Minimum TLS version to accept. */
    minVersion: HttpTlsConfig.Version = HttpTlsConfig.Version.TLS12,
    /** Maximum TLS version to accept. */
    maxVersion: HttpTlsConfig.Version = HttpTlsConfig.Version.TLS13
) derives CanEqual

object HttpTlsConfig:
    enum ClientAuth derives CanEqual:
        case None, Optional, Required

    enum Version derives CanEqual:
        case TLS12, TLS13

    val default: HttpTlsConfig = HttpTlsConfig()
end HttpTlsConfig
