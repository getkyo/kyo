package kyo.internal

import scala.scalanative.unsafe.*

/** Scala Native @extern bindings to kyo_tls.c (OpenSSL wrappers). */
private[kyo] object TlsBindings:

    // ── Context ───────────────────────────────────────────

    /** Create SSL_CTX. isServer: 0=client, 1=server. Returns opaque ptr or 0 on error. */
    @extern @name("kyo_tls_ctx_new")
    def tlsCtxNew(isServer: CInt): CLong = extern

    @extern @name("kyo_tls_ctx_free")
    def tlsCtxFree(ctx: CLong): Unit = extern

    /** Load server cert + key from PEM files. Returns 0 on success, -1 on error. */
    @extern @name("kyo_tls_ctx_set_cert")
    def tlsCtxSetCert(ctx: CLong, certPath: CString, keyPath: CString): CInt = extern

    /** Set client cert verification. mode: 0=none, 1=optional, 2=required. */
    @extern @name("kyo_tls_ctx_set_verify")
    def tlsCtxSetVerify(ctx: CLong, mode: CInt): CInt = extern

    /** Load CA certificates for verifying peer certs. Returns 0 on success, -1 on error. */
    @extern @name("kyo_tls_ctx_set_ca")
    def tlsCtxSetCa(ctx: CLong, caPath: CString): CInt = extern

    // ── Connection ────────────────────────────────────────

    /** Create SSL object with memory BIOs. hostname sets SNI + hostname verification. Returns opaque ptr or 0. */
    @extern @name("kyo_tls_new")
    def tlsNew(ctx: CLong, hostname: CString): CLong = extern

    @extern @name("kyo_tls_set_connect_state")
    def tlsSetConnectState(ssl: CLong): Unit = extern

    @extern @name("kyo_tls_set_accept_state")
    def tlsSetAcceptState(ssl: CLong): Unit = extern

    @extern @name("kyo_tls_free")
    def tlsFree(ssl: CLong): Unit = extern

    // ── Non-blocking I/O ──────────────────────────────────

    /** Drive handshake one step. Returns: 1=done, 0=want_read, -1=want_write, -2=error. */
    @extern @name("kyo_tls_handshake")
    def tlsHandshake(ssl: CLong): CInt = extern

    /** Feed encrypted bytes from TCP into the read BIO. Returns bytes fed, or -1 on error. */
    @extern @name("kyo_tls_feed_input")
    def tlsFeedInput(ssl: CLong, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Get encrypted bytes from the write BIO to send over TCP. Returns bytes extracted, 0 if none. */
    @extern @name("kyo_tls_get_output")
    def tlsGetOutput(ssl: CLong, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Decrypt application data. Returns bytes decrypted, 0 if need more input, -1 on closed/error. */
    @extern @name("kyo_tls_read")
    def tlsRead(ssl: CLong, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Encrypt application data. Returns bytes consumed, 0 if need to flush first, -1 on error. */
    @extern @name("kyo_tls_write")
    def tlsWrite(ssl: CLong, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Initiate TLS shutdown. Returns 1=done, 0=need more I/O, -1=error. */
    @extern @name("kyo_tls_shutdown")
    def tlsShutdown(ssl: CLong): CInt = extern

    /** Get human-readable error string for the last OpenSSL error. */
    @extern @name("kyo_tls_error_string")
    def tlsErrorString(): CString = extern

end TlsBindings
