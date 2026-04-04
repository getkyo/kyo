/**
 * OpenSSL non-blocking TLS wrappers for Scala Native.
 *
 * Uses BIO_s_mem() for non-blocking operation — no file descriptors involved:
 *   - Caller writes ciphertext from TCP into rbio via kyo_tls_feed_input()
 *   - Caller reads ciphertext for TCP from wbio via kyo_tls_get_output()
 *   - SSL_read/SSL_write operate on plaintext; OpenSSL handles the TLS record layer
 *
 * All functions use opaque long pointers (cast from SSL_CTX* and SSL*) to avoid
 * struct layout issues across OpenSSL versions.
 */
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/bio.h>
#include <string.h>

/* ── Context ────────────────────────────────────────────── */

/**
 * Create SSL_CTX. is_server: 0=client, 1=server.
 * Returns opaque pointer (cast to long), or 0 on error.
 */
long kyo_tls_ctx_new(int is_server) {
    const SSL_METHOD *method = is_server ? TLS_server_method() : TLS_client_method();
    SSL_CTX *ctx = SSL_CTX_new(method);
    if (!ctx) return 0;

    if (!is_server) {
        SSL_CTX_set_default_verify_paths(ctx);
        SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, NULL);
    }

    SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
    SSL_CTX_set_max_proto_version(ctx, TLS1_3_VERSION);

    return (long)ctx;
}

void kyo_tls_ctx_free(long ctx_ptr) {
    SSL_CTX_free((SSL_CTX *)ctx_ptr);
}

/** Load server cert + key from PEM files. Returns 0 on success, -1 on error. */
int kyo_tls_ctx_set_cert(long ctx_ptr, const char *cert_path, const char *key_path) {
    SSL_CTX *ctx = (SSL_CTX *)ctx_ptr;
    if (SSL_CTX_use_certificate_chain_file(ctx, cert_path) != 1) return -1;
    if (SSL_CTX_use_PrivateKey_file(ctx, key_path, SSL_FILETYPE_PEM) != 1) return -1;
    if (SSL_CTX_check_private_key(ctx) != 1) return -1;
    return 0;
}

/** Set client cert verification. mode: 0=none, 1=optional, 2=required. */
int kyo_tls_ctx_set_verify(long ctx_ptr, int mode) {
    SSL_CTX *ctx = (SSL_CTX *)ctx_ptr;
    int flags = (mode == 0) ? SSL_VERIFY_NONE :
                (mode == 1) ? SSL_VERIFY_PEER :
                              SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT;
    SSL_CTX_set_verify(ctx, flags, NULL);
    return 0;
}

/** Load CA certificates for verifying peer certs. */
int kyo_tls_ctx_set_ca(long ctx_ptr, const char *ca_path) {
    SSL_CTX *ctx = (SSL_CTX *)ctx_ptr;
    return SSL_CTX_load_verify_locations(ctx, ca_path, NULL) == 1 ? 0 : -1;
}

/* ── SSL connection ─────────────────────────────────────── */

/**
 * Create SSL object with memory BIOs.
 * hostname: used for SNI and hostname verification (client mode).
 * Returns opaque pointer (cast to long), or 0 on error.
 */
long kyo_tls_new(long ctx_ptr, const char *hostname) {
    SSL_CTX *ctx = (SSL_CTX *)ctx_ptr;
    SSL *ssl = SSL_new(ctx);
    if (!ssl) return 0;

    BIO *rbio = BIO_new(BIO_s_mem());
    BIO *wbio = BIO_new(BIO_s_mem());
    if (!rbio || !wbio) {
        SSL_free(ssl);
        return 0;
    }
    SSL_set_bio(ssl, rbio, wbio);

    if (hostname && strlen(hostname) > 0) {
        SSL_set_tlsext_host_name(ssl, hostname);   /* SNI */
        SSL_set1_host(ssl, hostname);               /* hostname verification */
    }

    return (long)ssl;
}

void kyo_tls_set_connect_state(long ssl_ptr) {
    SSL_set_connect_state((SSL *)ssl_ptr);
}

void kyo_tls_set_accept_state(long ssl_ptr) {
    SSL_set_accept_state((SSL *)ssl_ptr);
}

void kyo_tls_free(long ssl_ptr) {
    SSL_free((SSL *)ssl_ptr); /* also frees BIOs */
}

/* ── Non-blocking I/O ───────────────────────────────────── */

/**
 * Drive TLS handshake one step.
 * Returns: 1=done, 0=want_read (feed more ciphertext), -1=want_write (flush output), -2=error.
 */
int kyo_tls_handshake(long ssl_ptr) {
    SSL *ssl = (SSL *)ssl_ptr;
    ERR_clear_error();
    int rc = SSL_do_handshake(ssl);
    if (rc == 1) return 1; /* done */
    int err = SSL_get_error(ssl, rc);
    if (err == SSL_ERROR_WANT_READ)  return 0;
    if (err == SSL_ERROR_WANT_WRITE) return -1;
    return -2; /* error */
}

/** Feed encrypted bytes received from TCP into the read BIO. Returns bytes fed, or -1 on error. */
int kyo_tls_feed_input(long ssl_ptr, const char *buf, int len) {
    SSL *ssl = (SSL *)ssl_ptr;
    return BIO_write(SSL_get_rbio(ssl), buf, len);
}

/**
 * Get encrypted bytes from the write BIO to send over TCP.
 * Returns bytes extracted, 0 if none pending.
 */
int kyo_tls_get_output(long ssl_ptr, char *buf, int len) {
    SSL *ssl = (SSL *)ssl_ptr;
    int pending = (int)BIO_ctrl_pending(SSL_get_wbio(ssl));
    if (pending <= 0) return 0;
    int to_read = pending < len ? pending : len;
    return BIO_read(SSL_get_wbio(ssl), buf, to_read);
}

/**
 * Decrypt application data from the read BIO.
 * Returns bytes decrypted (>0), 0 if need more input, -1 on closed/error.
 */
int kyo_tls_read(long ssl_ptr, char *buf, int len) {
    SSL *ssl = (SSL *)ssl_ptr;
    ERR_clear_error();
    int n = SSL_read(ssl, buf, len);
    if (n > 0) return n;
    int err = SSL_get_error(ssl, n);
    if (err == SSL_ERROR_WANT_READ)    return 0;  /* need more ciphertext */
    if (err == SSL_ERROR_ZERO_RETURN)  return -1; /* clean close_notify */
    return -1; /* error */
}

/**
 * Encrypt application data into the write BIO.
 * Returns bytes consumed (>0), 0 if need to flush output first, -1 on error.
 */
int kyo_tls_write(long ssl_ptr, const char *buf, int len) {
    SSL *ssl = (SSL *)ssl_ptr;
    ERR_clear_error();
    int n = SSL_write(ssl, buf, len);
    if (n > 0) return n;
    int err = SSL_get_error(ssl, n);
    if (err == SSL_ERROR_WANT_WRITE) return 0;
    return -1; /* error */
}

/**
 * Initiate TLS shutdown (send close_notify).
 * Returns 1=done, 0=need more I/O, -1=error.
 */
int kyo_tls_shutdown(long ssl_ptr) {
    SSL *ssl = (SSL *)ssl_ptr;
    int rc = SSL_shutdown(ssl);
    if (rc == 1) return 1;
    if (rc == 0) return 0;
    return -1;
}

/** Get human-readable error string for the last OpenSSL error. */
const char* kyo_tls_error_string(void) {
    unsigned long e = ERR_peek_last_error();
    if (e == 0) return "unknown TLS error";
    return ERR_reason_error_string(e);
}
