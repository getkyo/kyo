package kyo.net

import kyo.*

/** TLS configuration for client and server connections.
  *
  * The same type is used on both sides of a connection, but with different fields in play:
  *   - **Client**: `trustAll` skips certificate validation (development only). `sniHostname` overrides the SNI extension sent during the
  *     handshake when connecting through a proxy or when the TCP host differs from the certificate's CN/SAN. `caCertPath` points to a
  *     PEM-encoded CA certificate used to validate the server certificate chain (for `sslmode=verify-ca` / `verify-full`).
  *     `hostnameVerification` controls whether the server hostname is checked against the certificate's CN/SAN (enabled by default).
  *   - **Server**: `certChainPath` and `privateKeyPath` point to PEM files for TLS termination. `clientAuth` controls whether clients must
  *     present a certificate (mutual TLS). `trustStorePath` provides the CA bundle for verifying client certificates.
  *
  * Both sides respect `minVersion` and `maxVersion` to constrain the negotiated TLS version. The defaults allow TLS 1.2 and 1.3.
  *
  * WARNING: Setting `trustAll = true` disables all certificate validation and makes the connection vulnerable to MITM attacks. Use only in
  * development or integration test environments where you control both endpoints.
  */
case class NetTlsConfig(
    /** Skip certificate validation entirely. Development/testing only. */
    trustAll: Boolean = false,
    /** Override SNI hostname (defaults to connect host). */
    sniHostname: Maybe[String] = Absent,
    /** PEM-encoded certificate chain file path (server-side). */
    certChainPath: Maybe[String] = Absent,
    /** PEM-encoded private key file path (server-side). */
    privateKeyPath: Maybe[String] = Absent,
    /** Client certificate authentication mode for servers. */
    clientAuth: NetTlsConfig.ClientAuth = NetTlsConfig.ClientAuth.None,
    /** CA certificates file path for verifying client certs (server-side). `caCertPath` is the documented fallback when this is `Absent` on the
      * server side.
      */
    trustStorePath: Maybe[String] = Absent,
    /** Minimum TLS version to accept. */
    minVersion: NetTlsConfig.Version = NetTlsConfig.Version.TLS12,
    /** Maximum TLS version to accept. */
    maxVersion: NetTlsConfig.Version = NetTlsConfig.Version.TLS13,
    /** PEM-encoded CA certificate file path for client-side server certificate validation. When set, the TLS handshake validates the
      * server's certificate chain against this CA instead of the JDK default trust store. Used for `sslmode=verify-ca` and
      * `sslmode=verify-full`.
      */
    caCertPath: Maybe[String] = Absent,
    /** Whether to verify the server hostname against the certificate's CN/SAN during the TLS handshake. Set to `false` for
      * `sslmode=verify-ca` (chain-only validation) or `sslmode=require` (no CA, no hostname check). Defaults to `true` (hostname
      * verification enabled).
      */
    hostnameVerification: Boolean = true,
    /** Pin the TLS implementation by provider id ("boringssl" | "openssl" | "jdk" | "node"); the per-connection form of `-Dkyo.net.tls`.
      * `Absent` uses the platform-selected default (the highest-priority available provider, honoring `-Dkyo.net.tls`). A present id is
      * honored or the connection fails closed: a provider not registered for the active transport, or not available on the host, aborts the
      * connection rather than silently substituting a different implementation (config truthfulness). The transport serving the connection
      * determines which ids it can honor (the posix transport drives any registered engine provider; the NIO floor drives `jdk` inline; JS
      * drives `node`).
      */
    tlsProvider: Maybe[String] = Absent,
    /** Deadline for the TLS handshake performed by the operation this config is passed to: a client `connectTls`, each connection accepted by
      * a `listenTls`, or an `upgradeToTls`. A peer that completes the TCP phase and then stalls the handshake (sends nothing, or a partial
      * ClientHello, and never finishes) would otherwise pin the fd, the TLS engine, and the per-connection buffers indefinitely (a slowloris
      * handshake-stall denial of service, CWE-400), and on the process-shared transport nothing later reclaims them. When finite, the
      * transport arms a `Clock`-driven deadline as the handshake begins and reaps the connection on expiry, running the same fd and engine
      * teardown a failed handshake runs. `Duration.Infinity` arms no deadline. The default `30.seconds` arms the guard for both roles.
      */
    handshakeTimeout: Duration = 30.seconds
) derives CanEqual:
    require(
        handshakeTimeout > Duration.Zero || handshakeTimeout == Duration.Infinity,
        s"handshakeTimeout must be positive or Infinity: $handshakeTimeout"
    )
end NetTlsConfig

object NetTlsConfig:
    enum ClientAuth derives CanEqual:
        case None, Optional, Required

    enum Version derives CanEqual:
        case TLS12, TLS13

    val default: NetTlsConfig = NetTlsConfig()
end NetTlsConfig
