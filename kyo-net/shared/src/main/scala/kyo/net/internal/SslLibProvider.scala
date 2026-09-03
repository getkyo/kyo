package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.internal.backend.CapabilityOutcome
import kyo.net.internal.backend.CapabilityProbe

/** Shared base for the two native TLS providers ([[BoringSslProvider]], [[SystemOpenSslProvider]]): one body over the backend-neutral
  * [[SslLibBindings]], so the BoringSSL primary and the system-OpenSSL fallback share their engine construction, config application, and
  * client identity binding, and differ only in the backing library, name, and priority.
  *
  * A concrete provider supplies [[lib]] (the loaded [[SslLibBindings]]), [[name]], [[priority]], and its library id. [[doProbe]] runs the
  * one-call `probeAvailable` probe (allocate + free an `SSL_CTX`) and classifies any load failure (missing staged archive / symbol / no
  * system OpenSSL) rather than collapsing it: a host without the backend still falls through to whatever else is registered, and now says
  * which library it was missing. [[createEngine]] applies the [[NetTlsConfig]] to a fresh `SSL_CTX`, wires an `SSL` with its two memory BIOs,
  * selects the connect/accept role, and returns a [[NativeSslEngine]] over the same backend.
  */
abstract private[net] class SslLibProvider extends TlsEngineProvider:

    /** The backing TLS library binding (BoringSSL or system OpenSSL). */
    private[internal] def lib(using AllowUnsafe): SslLibBindings

    /** Allocate and free an `SSL_CTX` through the backing library. Memoized by `CapabilityDescriptor.probe`, which matters here beyond the
      * general host-static argument: running this per TLS connect/listen meant many concurrent `SSL_CTX_new`/`SSL_CTX_free` calls across
      * scheduler carriers, multiplying the surface for any OpenSSL/BoringSSL global-state contention. That memo replaces the `@volatile`
      * Boolean this class used to hold, and unlike the Boolean it keeps the reason the probe gave.
      */
    private[net] def doProbe(using AllowUnsafe): CapabilityOutcome =
        CapabilityProbe.run(libraryIds) {
            if lib.probeAvailable() then CapabilityOutcome.Available
            else CapabilityOutcome.Unavailable(s"the '$name' TLS library is present but its SSL_CTX probe reported it unusable")
        }

    def createEngine(config: NetTlsConfig, hostname: String, isServer: Boolean)(using AllowUnsafe, Frame): TlsEngine =
        val l   = lib
        val ctx = l.ctxNew(if isServer then 1 else 0)
        if ctx == 0L then throw NetTlsConfigException("SSL_CTX_new failed")
        // Held outside the try so the failure path can tell "no SSL yet" from "an SSL that now owns two memory BIOs and a
        // malloc'd state struct", which are reclaimed only by sslFree.
        var ssl = 0L
        try
            applyConfig(l, ctx, config, isServer)
            ssl = l.sslNew(ctx, hostname)
            if ssl == 0L then throw NetTlsConfigException("SSL_new failed")
            bindClientIdentity(l, ssl, config, hostname, isServer)
            if isServer then l.sslSetAcceptState(ssl)
            else l.sslSetConnectState(ssl)
            new NativeSslEngine(l, ssl)
        catch
            case t: Throwable =>
                // Config application and the identity binding both throw, and the second of those runs with the SSL already
                // built, so the SSL is freed here whenever one exists. The context is not freed here: the finally below owns
                // that on every path.
                if ssl != 0L then l.sslFree(ssl)
                throw t
        finally
            // SSL_CTX is refcounted and SSL_new took its own reference, so this drops only the reference this method created.
            // On success the engine's SSL keeps the context alive until NativeSslEngine.free runs SSL_free; on failure the
            // reference taken by SSL_new was already released above, so this is the one that reclaims it. Dropping it here
            // rather than only on the failure path is what stops a context, with its chains and keys, leaking per engine.
            l.ctxFree(ctx)
        end try
    end createEngine

    private def applyConfig(lib: SslLibBindings, ctx: Long, config: NetTlsConfig, isServer: Boolean)(using AllowUnsafe, Frame): Unit =
        // The server verifies client certs against trustStorePath, falling back to caCertPath; the client verifies the server chain against
        // caCertPath.
        val serverCa = if isServer then config.trustStorePath.orElse(config.caCertPath) else config.caCertPath
        readPem(serverCa) match
            case Present(ca) =>
                // Returns the number of CAs added, or -1. A configured trust anchor that did not load is not a degraded mode: the store is
                // empty or short, and every verification decision from here on is made against trust the caller never chose. Failing the
                // engine build is the only place that failure is still attributable to the setting that caused it.
                if lib.ctxLoadCa(ctx, ca) < 1 then
                    throw NetTlsConfigException(s"the configured CA at ${serverCa.getOrElse("<unset>")} loaded no certificates")
            case Absent =>
                // A verifying CLIENT with no configured caCertPath validates the server chain against the platform default trust store,
                // converging with the JDK floor (NioTransport: `Absent => JDK default trust store`). Without this a bundled BoringSSL client has
                // an EMPTY X509 store, so every public-internet handshake fails with EngineError. NOT applied to a server: a server verifies the
                // peer's CLIENT certificate and must anchor on an explicit trust store, never the public CA set.
                if !isServer && !config.trustAll then
                    // Returns 1 when any trust source loaded, 0 otherwise. A verifying client that loaded none has no anchors at all, so it
                    // would reject every peer; that is safe but indistinguishable from a certificate problem at the handshake, which is where
                    // this used to surface.
                    if lib.ctxLoadSystemCa(ctx) != 1 then
                        throw NetTlsConfigException("no platform trust store could be loaded for a verifying client with no configured CA")
        end match
        lib.ctxSetVerifyMode(ctx, verifyMode(config, isServer))
        // Returns 0, or -1 when a bound is rejected. Discarding it leaves the version window UNSET while the config says otherwise, so a
        // caller who pinned a floor of TLS 1.3 could negotiate 1.2 and never learn the pin did not take.
        if lib.ctxSetMinMaxVersion(ctx, versionCode(config.minVersion), versionCode(config.maxVersion)) != 0 then
            throw NetTlsConfigException(s"the TLS version window ${config.minVersion} to ${config.maxVersion} was rejected")
        // Load the certificate + key whenever both are configured, for the client too: a mutual-TLS client presents its own client certificate
        // when the server sends a CertificateRequest. A plaintext client leaves both Absent, so this is a no-op for the common (no client cert)
        // client, and a server still always loads its termination cert.
        (readPem(config.certChainPath), readPem(config.privateKeyPath)) match
            case (Present(cert), Present(key)) =>
                // Returns 0, or -1 on bad PEM or a key that does not match the certificate. A server whose termination cert did not load has
                // no identity to present and fails every handshake opaquely; naming it here points at the config instead.
                if lib.ctxSetCert(ctx, cert, key) != 0 then
                    throw NetTlsConfigException("the configured certificate and private key could not be loaded, or do not match")
            case _ => ()
        end match
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
        AllowUnsafe,
        Frame
    ): Unit =
        // Returns 1 when the unmatchable identity was set, 0 on error. This is the one return code on this path whose failure
        // ACCEPTS rather than rejects: it is reached only when the client is verifying and has no usable reference identity, so an
        // unbound name means the handshake runs with no name check at all and takes any chain-valid certificate. Discarding it turns
        // the fail-closed rule (RFC 9525 6.1) into fail-open, silently, which is the outcome the whole path exists to prevent.
        def requireUnmatchable(): Unit =
            if lib.sslRequireUnmatchableIdentity(ssl) != 1 then
                throw NetTlsConfigException(
                    "a verifying client could not be given an unmatchable reference identity, so the handshake would accept any " +
                        "chain-valid certificate"
                )

        if isServer || config.trustAll || !config.hostnameVerification then ()
        else if hostname.isEmpty then requireUnmatchable()
        else if lib.sslSetVerifyName(ssl, hostname) != 1 then requireUnmatchable()
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
      * cannot be read or decoded FAILS CLOSED with [[NetTlsConfigException]] rather than degrading to `Absent`. Swallowing the read error would silently drop an
      * operator's pinned private CA (or a server's configured cert/key) and fall back to the system trust store, the CWE-295 silent-weakening
      * the JDK floor also refuses (`NioTransport` reports the same [[NetTlsConfigException]] for an unreadable configured path). Distinguishing
      * not-configured from configured-but-unreadable is the whole fix.
      */
    private def readPem(path: Maybe[String])(using AllowUnsafe, Frame): Maybe[String] =
        path.map { p =>
            // kyo.Path, not java.nio.file: the readiness path is shared across JVM/Native/JS, and java.nio.file does not link on Scala.js.
            // Path.read defaults to UTF-8 on every platform (Files.readString on the JDK, readFileSync(_, "utf8") on Node), matching the
            // charset this used to pass explicitly, and it captures the I/O failure in a Result rather than throwing, so the fail-closed
            // wrap below is the only place a read error escapes.
            Path(p).unsafe.read().foldError(
                identity,
                err => throw NetTlsConfigException(new java.io.IOException(s"configured PEM file could not be read: $p", err.exception))
            )
        }

end SslLibProvider
