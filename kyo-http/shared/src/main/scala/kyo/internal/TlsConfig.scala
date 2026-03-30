package kyo.internal

import kyo.*

/** TLS configuration for client and server connections.
  *
  * Client-side: controls certificate validation, SNI, ALPN. Server-side: provides certificate chain and private key for TLS termination.
  */
case class TlsConfig(
    /** Skip certificate validation entirely. Development/testing only. */
    trustAll: Boolean = false,
    /** Override SNI hostname (defaults to connect host). */
    sniHostname: Maybe[String] = Absent,
    /** ALPN protocols to offer, in preference order. */
    alpnProtocols: Seq[String] = Seq("http/1.1"),
    /** PEM-encoded certificate chain file path (server-side). */
    certChainPath: Maybe[String] = Absent,
    /** PEM-encoded private key file path (server-side). */
    privateKeyPath: Maybe[String] = Absent,
    /** Client certificate authentication mode for servers. */
    clientAuth: TlsConfig.ClientAuth = TlsConfig.ClientAuth.None,
    /** CA certificates file path for verifying client certs. */
    trustStorePath: Maybe[String] = Absent,
    /** Minimum TLS version to accept. */
    minVersion: TlsConfig.Version = TlsConfig.Version.TLS12,
    /** Maximum TLS version to accept. */
    maxVersion: TlsConfig.Version = TlsConfig.Version.TLS13
) derives CanEqual

object TlsConfig:
    enum ClientAuth derives CanEqual:
        case None, Optional, Required

    enum Version derives CanEqual:
        case TLS12, TLS13

    val default: TlsConfig = TlsConfig()
end TlsConfig
