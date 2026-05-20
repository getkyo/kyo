package kyo.internal

import scala.scalanative.unsafe.*

/** Scala Native FFI bindings to `kyo_tls.c` — OpenSSL non-blocking TLS wrappers using memory BIOs.
  *
  * The C layer uses `BIO_s_mem()` read and write BIOs so that ciphertext is never tied to a file descriptor. Instead, the Scala side pumps
  * ciphertext in and out manually:
  *   - `tlsFeedInput` — push raw bytes read from the TCP socket into the OpenSSL read BIO
  *   - `tlsGetOutput` — drain bytes that OpenSSL has encrypted into the write BIO, ready to send over TCP
  *   - `tlsRead` — decrypt application data from the read BIO into a caller-supplied buffer
  *   - `tlsWrite` — encrypt application data into the write BIO; caller must flush via `tlsGetOutput`
  *
  * All context and SSL object pointers are passed as `CLong` for portability across OpenSSL versions where pointer width varies.
  *
  * Return value conventions match those documented on each binding method.
  */
private[kyo] object TlsBindings:

    // ---- Context ----

    /** Create SSL_CTX. isServer: 0=client, 1=server. Returns opaque pointer as CLong, or 0 on error. */
    @extern @name("kyo_tls_ctx_new")
    def tlsCtxNew(isServer: CInt): CLong = extern

    /** Free SSL_CTX. */
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

    // ---- SSL connection ----

    /** Create SSL with memory BIOs. hostname used for SNI + verification. Returns opaque pointer as CLong, or 0 on error. */
    @extern @name("kyo_tls_new")
    def tlsNew(ctx: CLong, hostname: CString): CLong = extern

    /** Set SSL to client connect state. */
    @extern @name("kyo_tls_set_connect_state")
    def tlsSetConnectState(ssl: CLong): Unit = extern

    /** Set SSL to server accept state. */
    @extern @name("kyo_tls_set_accept_state")
    def tlsSetAcceptState(ssl: CLong): Unit = extern

    /** Free SSL + BIOs. */
    @extern @name("kyo_tls_free")
    def tlsFree(ssl: CLong): Unit = extern

    // ---- Non-blocking I/O ----

    /** Drive handshake one step. Returns: 1=done, 0=want_read, -1=want_write, -2=error. */
    @extern @name("kyo_tls_handshake")
    def tlsHandshake(ssl: CLong): CInt = extern

    /** Feed encrypted bytes from TCP into read BIO. Returns bytes fed, or -1 on error. */
    @extern @name("kyo_tls_feed_input")
    def tlsFeedInput(ssl: CLong, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Get encrypted bytes from write BIO to send over TCP. Returns bytes extracted, 0 if none pending. */
    @extern @name("kyo_tls_get_output")
    def tlsGetOutput(ssl: CLong, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Decrypt application data from read BIO. Returns bytes decrypted (>0), 0 if need more input, -1 on closed/error. */
    @extern @name("kyo_tls_read")
    def tlsRead(ssl: CLong, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Encrypt application data into write BIO. Returns bytes consumed (>0), 0 if need flush, -1 on error. */
    @extern @name("kyo_tls_write")
    def tlsWrite(ssl: CLong, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Initiate TLS shutdown (close_notify). Returns 1=done, 0=need more I/O, -1=error. */
    @extern @name("kyo_tls_shutdown")
    def tlsShutdown(ssl: CLong): CInt = extern

    /** Get human-readable error string for last OpenSSL error. */
    @extern @name("kyo_tls_error_string")
    def tlsErrorString(): CString = extern

end TlsBindings
