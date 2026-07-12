package kyo.net.internal.tls

import kyo.*
import kyo.net.NetTlsConfig

/** Shared base for the two native TLS providers ([[BoringSslProvider]], [[SystemOpenSslProvider]]): one body over the backend-neutral
  * [[SslLibBindings]], so the BoringSSL primary and the system-OpenSSL fallback share their engine construction, config application, client
  * identity binding, and availability memo, and differ only in the backing library, name, and priority.
  *
  * A concrete provider supplies [[lib]] (the loaded [[SslLibBindings]]), [[name]], and [[priority]]. [[isAvailable]] runs the one-call
  * `probeAvailable` probe (allocate + free an `SSL_CTX`), collapsing any load failure (missing staged archive / symbol / no system OpenSSL)
  * into `false`: the registry contract requires a probe never throws, and a host without the backend simply falls through to whatever else is
  * registered. [[createEngine]] applies the [[NetTlsConfig]] to a fresh `SSL_CTX`, wires an `SSL` with its two memory BIOs, selects the
  * connect/accept role, and returns a [[NativeSslEngine]] over the same backend.
  */
abstract private[net] class SslLibProvider extends TlsEngineProvider:

    /** The backing TLS library binding (BoringSSL or system OpenSSL). */
    private[tls] def lib(using AllowUnsafe): SslLibBindings

    // Memoized: the probe builds and frees an SSL_CTX, and the result (does the backend load on this host) is host-static. Running it on every
    // TLS connect/listen meant many concurrent SSL_CTX_new/SSL_CTX_free calls across scheduler carriers, multiplying the surface for any
    // OpenSSL/BoringSSL global-state contention; the answer never changes, so compute it once. The `@volatile Boolean` caches the result after
    // the first successful probe (JVM publishes it; on Native the value is host-fixed so a benign re-probe before the first publish is harmless).
    @volatile private var probed: Maybe[Boolean] = Absent
    def isAvailable(using AllowUnsafe): Boolean =
        probed match
            case Present(v) => v
            case Absent =>
                val v =
                    try lib.probeAvailable()
                    catch case _: Throwable => false
                probed = Present(v)
                v

    def createEngine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine =
        val l   = lib
        val ctx = l.ctxNew(if isServer then 1 else 0)
        if ctx == 0L then throw Closed(name, summon[Frame], "SSL_CTX_new failed")
        try
            applyConfig(l, ctx, config, isServer)
            val ssl = l.sslNew(ctx, hostname)
            if ssl == 0L then throw Closed(name, summon[Frame], "SSL_new failed")
            bindClientIdentity(l, ssl, config, hostname, isServer)
            if isServer then l.sslSetAcceptState(ssl)
            else l.sslSetConnectState(ssl)
            new NativeSslEngine(l, ssl)
        catch
            case t: Throwable =>
                // SSL_new failed (or config threw) before the SSL took a reference on the context: free it so the context does not leak.
                l.ctxFree(ctx)
                throw t
        end try
    end createEngine

    private def applyConfig(lib: SslLibBindings, ctx: Long, config: NetTlsConfig, isServer: Boolean)(using AllowUnsafe, Frame): Unit =
        // The server verifies client certs against trustStorePath, falling back to caCertPath; the client verifies the server chain against
        // caCertPath.
        val serverCa = if isServer then config.trustStorePath.orElse(config.caCertPath) else config.caCertPath
        readPem(serverCa) match
            case Present(ca) => discard(lib.ctxLoadCa(ctx, ca))
            case Absent      =>
                // A verifying CLIENT with no configured caCertPath validates the server chain against the platform default trust store,
                // converging with the JDK floor (NioTransport: `Absent => JDK default trust store`). Without this a bundled BoringSSL client has
                // an EMPTY X509 store, so every public-internet handshake fails with EngineError. NOT applied to a server: a server verifies the
                // peer's CLIENT certificate and must anchor on an explicit trust store, never the public CA set.
                if !isServer && !config.trustAll then discard(lib.ctxLoadSystemCa(ctx))
        end match
        lib.ctxSetVerifyMode(ctx, verifyMode(config, isServer))
        discard(lib.ctxSetMinMaxVersion(ctx, versionCode(config.minVersion), versionCode(config.maxVersion)))
        // Load the certificate + key whenever both are configured, for the client too: a mutual-TLS client presents its own client certificate
        // when the server sends a CertificateRequest. A plaintext client leaves both Absent, so this is a no-op for the common (no client cert)
        // client, and a server still always loads its termination cert.
        (readPem(config.certChainPath), readPem(config.privateKeyPath)) match
            case (Present(cert), Present(key)) => discard(lib.ctxSetCert(ctx, cert, key))
            case _                             => ()
    end applyConfig

    /** Bind the client reference identity so chain validation is accompanied by RFC 9525 name checking, and fail closed when a verifying
      * client has no identity to check against. This is the security control that converges the native provider with the JDK floor: for any
      * `NetTlsConfig` + host the two reach the same accept/reject decision.
      *
      * Client decision matrix (the server role binds no client identity and never fails closed: it has no client reference identity to check
      * against the usually-absent client cert):
      *   - `trustAll`: no verification at all (verify mode is already 0), so bind nothing.
      *   - `!hostnameVerification`: chain-only (verify mode 2, no name check), so bind nothing (the `sslmode=verify-ca` case).
      *   - verifying with a non-empty host: bind the reference identity (IP-ID for an IP literal, DNS-ID otherwise) via the shim; a bind
      *     failure binds an unmatchable identity so the handshake fails closed rather than handshaking with no name bound.
      *   - verifying with an empty host: FAIL CLOSED. A chain-valid certificate with no name bound is never an acceptable silent outcome
      *     (RFC 9525 §6.1; the Go/rustls rule), so an unmatchable reference identity is bound and the handshake rejects every peer instead of
      *     accepting any chain-valid cert.
      */
    private def bindClientIdentity(lib: SslLibBindings, ssl: Long, config: NetTlsConfig, hostname: String, isServer: Boolean)(using
        AllowUnsafe
    ): Unit =
        if isServer || config.trustAll || !config.hostnameVerification then ()
        else if hostname.isEmpty then discard(lib.sslRequireUnmatchableIdentity(ssl))
        else if lib.sslSetVerifyName(ssl, hostname) != 1 then
            discard(lib.sslRequireUnmatchableIdentity(ssl))
    end bindClientIdentity

    /** Map the config to the shim's verify mode (0 none, 1 optional, 2 required): a `trustAll` client skips verification; a server uses its
      * `clientAuth` setting; a verifying client requires the server cert.
      */
    private def verifyMode(config: NetTlsConfig, isServer: Boolean): Int =
        if isServer then
            config.clientAuth match
                case NetTlsConfig.ClientAuth.Required => 2
                case NetTlsConfig.ClientAuth.Optional => 1
                case NetTlsConfig.ClientAuth.None     => 0
        else if config.trustAll then 0
        else 2
    end verifyMode

    private def versionCode(v: NetTlsConfig.Version): Int = v match
        case NetTlsConfig.Version.TLS12 => 2
        case NetTlsConfig.Version.TLS13 => 3

    /** Read a configured PEM file. A `path` that was never set stays `Absent` so the caller keeps the system-trust default; a `Present` path that
      * cannot be read or decoded FAILS CLOSED with `Closed` rather than degrading to `Absent`. Swallowing the read error would silently drop an
      * operator's pinned private CA (or a server's configured cert/key) and fall back to the system trust store, the CWE-295 silent-weakening
      * the JDK floor avoids (`NioTransport.loadCaCertTrustManagers` lets the file-open exception propagate). Distinguishing not-configured from
      * configured-but-unreadable is the whole fix.
      */
    private def readPem(path: Maybe[String])(using AllowUnsafe, Frame): Maybe[String] =
        path.map { p =>
            try new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p)), java.nio.charset.StandardCharsets.UTF_8)
            catch
                case t: Throwable =>
                    throw Closed(name, summon[Frame], s"configured PEM file could not be read: $p (${t.getMessage})")
        }

end SslLibProvider
