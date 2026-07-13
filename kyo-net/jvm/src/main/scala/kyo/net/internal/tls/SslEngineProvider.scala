package kyo.net.internal.tls

import javax.net.ssl.SSLContext
import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.internal.NioTransport

/** JVM-only pure-JDK TLS floor provider (priority-10 `jdk`), the SSLEngine fallback when BoringSSL is not staged/loadable or `-Dkyo.net.tls=jdk`
  * is forced.
  *
  * JVM ONLY because it imports `javax.net.ssl` (absent on Native/JS). `isAvailable = true` is sound ONLY here; Native falls to system OpenSSL,
  * JS to Node tls. `createEngine` builds a [[JdkSslEngine]] over an `SSLEngine` configured from the [[NetTlsConfig]] via the existing
  * `NioTransport.createSslContext` (the same context-building the inline NIO path uses, reused 1:1).
  */
private[net] object SslEngineProvider extends TlsEngineProvider:

    def name = "jdk"

    def priority = 10

    // JVM-only TLS floor: pure JDK, always available.
    def isAvailable(using AllowUnsafe): Boolean =
        // Touch the JDK TLS stack so the probe reflects an actually-usable provider.
        val _ = SSLContext.getDefault()
        true
    end isAvailable

    def createEngine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine =
        val sslContext = NioTransport.createSslContext(config, isServer)
        val engine     = sslContext.createSSLEngine(hostname, -1)
        engine.setUseClientMode(!isServer)
        // Enforce the configured [minVersion, maxVersion] range. The raw SSLEngine enables a broad default protocol set, so a TLS1.3-only peer
        // would otherwise silently negotiate TLS1.2 with a TLS1.2 peer (CWE-326). Pinning the enabled protocols matches the BoringSSL/OpenSSL
        // providers (kyo_*_ctx_set_min_max_version) and the Node provider (minVersion/maxVersion). Set before the client hostname block, whose
        // getSSLParameters/setSSLParameters round-trip preserves the enabled protocols. The mapping is shared with the inline NIO TLS path via
        // NioTransport.enabledProtocols so both JDK-SSLEngine paths pin identically.
        engine.setEnabledProtocols(NioTransport.enabledProtocols(config))
        if isServer then
            config.clientAuth match
                case NetTlsConfig.ClientAuth.Required => engine.setNeedClientAuth(true)
                case NetTlsConfig.ClientAuth.Optional => engine.setWantClientAuth(true)
                case NetTlsConfig.ClientAuth.None     => ()
        else if config.hostnameVerification && !config.trustAll then
            // Verifying client. With a reference identity, opt into the HTTPS endpoint-identification algorithm (the raw SSLEngine does NO
            // name check by default). With NO reference identity, FAIL CLOSED: a chain-valid certificate with no name bound is never an
            // acceptable silent outcome (RFC 9525 §6.1; the Go/rustls rule). This matches the BoringSSL and OpenSSL providers so the same
            // NetTlsConfig + host reaches the identical accept/reject decision on all three.
            if hostname.isEmpty then
                throw NetTlsConfigException(
                    "verifying client has no reference identity: a hostname is required to verify the server certificate (set trustAll or " +
                        "hostnameVerification = false to opt out of name verification)"
                )
            end if
            val params = engine.getSSLParameters
            params.setEndpointIdentificationAlgorithm("HTTPS")
            engine.setSSLParameters(params)
        end if
        engine.beginHandshake()
        new JdkSslEngine(engine)
    end createEngine
end SslEngineProvider
