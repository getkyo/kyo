/*
 * kyo-net shared TLS state machine: the memory-BIO TLS implementation both the bundled-BoringSSL shim
 * (kyo_net_boringssl.c) and the system-OpenSSL shim (kyo_net_openssl.c) export.
 *
 * Each shim defines KYO_SSL_PREFIX to its export prefix (kyo_bssl_ / kyo_ossl_) and #includes this header.
 * The KYO_SSL_FN(name) macro token-pastes that prefix onto each function name, so one body compiles into
 * two prefix-distinct symbol sets that coexist on a single Native binary without collision. Every function
 * here is `static`, so two translation units including this header produce no duplicate-symbol clash; the
 * only EXPORTED surface is the thin extern wrappers each shim defines around these static functions.
 *
 * TLS data flow is the classic two-memory-BIO state machine: each SSL is wired to a pair of BIO_s_mem
 * buffers. The read-BIO holds inbound ciphertext the caller fed from the socket; the write-BIO collects
 * outbound ciphertext the caller drains to the socket. The handshake and record layer run entirely in
 * memory, so the Scala driver owns all socket I/O. Opaque SSL_CTX* / SSL* cross the FFI boundary as
 * pointers carried in a long; the caller never dereferences them, it only round-trips them back here.
 *
 * The two backends diverge in one spot, selected by OPENSSL_IS_BORINGSSL (defined only under BoringSSL's
 * headers): the peer-certificate accessor name (SSL_get_peer_certificate on BoringSSL vs
 * SSL_get1_peer_certificate on OpenSSL 3, both up-reffing). Each shim resolves that macro against its own
 * headers when it compiles this body.
 *
 * ERR_clear_error() runs before each SSL op (do_handshake_step, read_plain, write_plain) on both backends so
 * SSL_get_error classifies only the current op: a stale entry left in the thread-local error queue by an
 * unrelated earlier call would otherwise make a benign WANT_READ look like a fatal error.
 */

#ifndef KYO_SSL_PREFIX
#error "KYO_SSL_PREFIX must be defined to the export prefix (e.g. kyo_bssl_) before including kyo_ssl_common.h"
#endif

#include <arpa/inet.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#define KYO_SSL_CAT_(a, b) a##b
#define KYO_SSL_CAT(a, b) KYO_SSL_CAT_(a, b)
/*
 * Internal name for a shared static function or type: <prefix><name>_impl, e.g. kyo_bssl_ctx_new_impl.
 * The `_impl` suffix keeps these statics distinct from the exported wrappers each shim defines
 * (kyo_bssl_ctx_new), so the public symbol forwards to the private body with no name collision.
 */
#define KYO_SSL_FN(name) KYO_SSL_CAT(KYO_SSL_CAT(KYO_SSL_PREFIX, name), _impl)

/*
 * One-time, thread-safe library initialization. OpenSSL/BoringSSL auto-initialize lazily on first use, but that lazy init is NOT safe to race
 * from multiple threads: the kyo-net Native runtime drives SSL operations and the availability probe (SSL_CTX_new) concurrently from several
 * scheduler worker carriers, so the first concurrent SSL_CTX_new calls race the library's global setup (cipher tables, the error-string and
 * algorithm registries) and corrupt the process heap, which then aborts later at an unrelated allocation (the nanov2 guard-corruption / OPENSSL_malloc
 * aborts observed on macOS). Running OPENSSL_init_ssl exactly once, to completion, through pthread_once before any SSL object is created makes the
 * global state fully initialized before any concurrent use, closing the init race. Idempotent and cheap after the first call.
 */
static pthread_once_t KYO_SSL_FN(init_once) = PTHREAD_ONCE_INIT;
static void KYO_SSL_FN(do_init)(void) {
    OPENSSL_init_ssl(OPENSSL_INIT_LOAD_SSL_STRINGS | OPENSSL_INIT_LOAD_CRYPTO_STRINGS, NULL);
}
static void KYO_SSL_FN(ensure_init)(void) {
    pthread_once(&KYO_SSL_FN(init_once), KYO_SSL_FN(do_init));
}

/*
 * Per-SSL state. The two memory BIOs are owned by the SSL once handed to SSL_set_bio (the library frees
 * them with the SSL), but we keep our own pointers so feed/drain can address each side directly without
 * SSL_get_rbio/SSL_get_wbio lookups on every call.
 */
typedef struct KYO_SSL_FN(ssl_state) {
    SSL *ssl;
    BIO *read_bio;  /* inbound ciphertext: caller writes here, SSL reads */
    BIO *write_bio; /* outbound ciphertext: SSL writes here, caller drains */
} KYO_SSL_FN(ssl_state);

/* ---- context lifecycle ---------------------------------------------------------------------- */

/*
 * Create an SSL_CTX. isServer != 0 selects the accept (server) role default, 0 the connect (client)
 * role. TLS_method() yields a version-flexible method; min/max are pinned later via
 * ctx_set_min_max_version. Returns the context pointer as a long, or 0 on alloc failure.
 */
static long KYO_SSL_FN(ctx_new)(int isServer) {
    (void)isServer; /* role is selected per-SSL via set_connect/accept_state */
    KYO_SSL_FN(ensure_init)();
    SSL_CTX *ctx = SSL_CTX_new(TLS_method());
    if (!ctx) return 0;
    /* Sane floor: TLS 1.2. Callers tighten via set_min_max_version. */
    SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
    return (long)(intptr_t)ctx;
}

static void KYO_SSL_FN(ctx_free)(long ctx_ptr) {
    if (!ctx_ptr) return;
    SSL_CTX_free((SSL_CTX *)(intptr_t)ctx_ptr);
}

/*
 * Load a PEM certificate (possibly a chain) and its PEM private key into the context. Returns 0 on
 * success, -1 on any failure (bad PEM, key/cert mismatch). The PEM strings are read through a memory
 * BIO so no temp file is needed.
 */
static int KYO_SSL_FN(ctx_set_cert)(long ctx_ptr, const char *cert_pem, const char *key_pem) {
    if (!ctx_ptr || !cert_pem || !key_pem) return -1;
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    int rc = -1;

    BIO *cbio = BIO_new_mem_buf(cert_pem, -1);
    if (!cbio) return -1;
    X509 *cert = PEM_read_bio_X509(cbio, NULL, NULL, NULL);
    if (!cert) { BIO_free(cbio); return -1; }
    if (SSL_CTX_use_certificate(ctx, cert) != 1) { X509_free(cert); BIO_free(cbio); return -1; }

    /* Append any remaining certs in the PEM as the chain. */
    X509 *chain;
    while ((chain = PEM_read_bio_X509(cbio, NULL, NULL, NULL)) != NULL) {
        if (SSL_CTX_add0_chain_cert(ctx, chain) != 1) { X509_free(chain); break; }
        /* add0 takes ownership of chain on success; do not free here. */
    }
    X509_free(cert);
    BIO_free(cbio);

    BIO *kbio = BIO_new_mem_buf(key_pem, -1);
    if (!kbio) return -1;
    EVP_PKEY *key = PEM_read_bio_PrivateKey(kbio, NULL, NULL, NULL);
    BIO_free(kbio);
    if (!key) return -1;
    if (SSL_CTX_use_PrivateKey(ctx, key) == 1 && SSL_CTX_check_private_key(ctx) == 1) rc = 0;
    EVP_PKEY_free(key);
    return rc;
}

/* mode: 0 = none (no peer verify), 1 = optional (verify if presented), 2 = required. */
static void KYO_SSL_FN(ctx_set_verify_mode)(long ctx_ptr, int mode) {
    if (!ctx_ptr) return;
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    int v;
    switch (mode) {
        case 2:  v = SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT; break;
        case 1:  v = SSL_VERIFY_PEER; break;
        default: v = SSL_VERIFY_NONE; break;
    }
    SSL_CTX_set_verify(ctx, v, NULL);
}

/*
 * Load PEM CA certificate(s) into the context's trust store. Returns the number of certs added on
 * success (>= 1), or -1 on failure / no certs parsed.
 */
static int KYO_SSL_FN(ctx_load_ca)(long ctx_ptr, const char *ca_pem) {
    if (!ctx_ptr || !ca_pem) return -1;
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    X509_STORE *store = SSL_CTX_get_cert_store(ctx);
    if (!store) return -1;

    BIO *bio = BIO_new_mem_buf(ca_pem, -1);
    if (!bio) return -1;
    int added = 0;
    X509 *ca;
    while ((ca = PEM_read_bio_X509(bio, NULL, NULL, NULL)) != NULL) {
        if (X509_STORE_add_cert(store, ca) == 1) added++;
        X509_free(ca); /* X509_STORE_add_cert up-refs on success */
    }
    BIO_free(bio);
    return added > 0 ? added : -1;
}

/*
 * Pin the TLS version window. min/max are 2 or 3 (TLS 1.2 / TLS 1.3); 0 leaves that bound at the
 * library default. Returns 0 on success, -1 on a rejected version.
 */
static int KYO_SSL_FN(ctx_set_min_max_version)(long ctx_ptr, int min, int max) {
    if (!ctx_ptr) return -1;
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;
    int min_v = (min == 3) ? TLS1_3_VERSION : (min == 2) ? TLS1_2_VERSION : 0;
    int max_v = (max == 3) ? TLS1_3_VERSION : (max == 2) ? TLS1_2_VERSION : 0;
    if (min != 0 && SSL_CTX_set_min_proto_version(ctx, min_v) != 1) return -1;
    if (max != 0 && SSL_CTX_set_max_proto_version(ctx, max_v) != 1) return -1;
    return 0;
}

/* ---- SSL lifecycle -------------------------------------------------------------------------- */

/* True when host is a textual IPv4 or IPv6 literal (inet_pton parses it). SNI must NOT carry an IP
 * (RFC 6066: the server name is a host_name only), and an IP reference identity is verified through
 * the IP-ID path (X509_VERIFY_PARAM_set1_ip_asc), not the DNS-name path, so both decisions branch on
 * this test. */
static int KYO_SSL_FN(is_ip_literal)(const char *host) {
    unsigned char buf[16];
    if (!host || host[0] == '\0') return 0;
    if (inet_pton(AF_INET, host, buf) == 1) return 1;
    if (inet_pton(AF_INET6, host, buf) == 1) return 1;
    return 0;
}

/*
 * Create an SSL from the context and wire it to a fresh pair of memory BIOs. When hostname is a
 * non-empty DNS name it is set as the SNI server name; an IP literal sets no SNI (RFC 6066). This
 * sets ONLY the SNI routing hint, never the verification reference identity: the caller binds the
 * reference identity separately via ssl_set_verify_name, so chain validation and name checking stay
 * decoupled (no name check runs unless a reference identity is set). Returns a pointer to the heap
 * ssl_state wrapper as a long, or 0 on failure.
 */
static long KYO_SSL_FN(ssl_new)(long ctx_ptr, const char *hostname) {
    if (!ctx_ptr) return 0;
    SSL_CTX *ctx = (SSL_CTX *)(intptr_t)ctx_ptr;

    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)calloc(1, sizeof(KYO_SSL_FN(ssl_state)));
    if (!st) return 0;

    st->ssl = SSL_new(ctx);
    if (!st->ssl) { free(st); return 0; }

    st->read_bio  = BIO_new(BIO_s_mem());
    st->write_bio = BIO_new(BIO_s_mem());
    if (!st->read_bio || !st->write_bio) {
        if (st->read_bio)  BIO_free(st->read_bio);
        if (st->write_bio) BIO_free(st->write_bio);
        SSL_free(st->ssl);
        free(st);
        return 0;
    }
    /* SSL takes ownership of both BIOs; they are freed with SSL_free. */
    SSL_set_bio(st->ssl, st->read_bio, st->write_bio);

    if (hostname && hostname[0] != '\0' && !KYO_SSL_FN(is_ip_literal)(hostname)) {
        SSL_set_tlsext_host_name(st->ssl, hostname);
    }
    return (long)(intptr_t)st;
}

/*
 * Bind the client reference identity for verification, decoupled from SNI. An IP literal is bound
 * through the IP-ID path (X509_VERIFY_PARAM_set1_ip_asc against the iPAddress SAN, RFC 9525 exact
 * matching); any other non-empty string is bound through the DNS-ID path (SSL_set1_host against the
 * dNSName SAN / CN). The provider calls this for a verifying client that has a reference identity;
 * for a verifying client with NO identity it calls ssl_require_unmatchable_identity instead. Returns
 * 1 on success, 0 on an empty host or a set failure.
 */
static int KYO_SSL_FN(ssl_set_verify_name)(long ssl_ptr, const char *hostname) {
    if (!ssl_ptr || !hostname || hostname[0] == '\0') return 0;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    if (KYO_SSL_FN(is_ip_literal)(hostname))
        return X509_VERIFY_PARAM_set1_ip_asc(SSL_get0_param(st->ssl), hostname) == 1 ? 1 : 0;
    return SSL_set1_host(st->ssl, hostname) == 1 ? 1 : 0;
}

/*
 * Fail closed for a verifying client that has no reference identity. A chain-valid certificate with
 * no name bound is never an acceptable silent outcome (RFC 9525 6.1; the Go/rustls rule), so bind a
 * reference identity that no certificate can satisfy: the name check then runs and rejects every
 * peer, failing the handshake fatally instead of accepting any chain-valid cert. The bound name is
 * the RFC 6761 special-use ".invalid" TLD, which can never legitimately appear in an issued
 * certificate, so the rejection is total and deterministic. Returns 1 when the unmatchable identity
 * was set, 0 on error.
 */
static int KYO_SSL_FN(ssl_require_unmatchable_identity)(long ssl_ptr) {
    if (!ssl_ptr) return 0;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    return SSL_set1_host(st->ssl, "no-reference-identity.invalid") == 1 ? 1 : 0;
}

static void KYO_SSL_FN(ssl_set_connect_state)(long ssl_ptr) {
    if (!ssl_ptr) return;
    SSL_set_connect_state(((KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr)->ssl);
}

static void KYO_SSL_FN(ssl_set_accept_state)(long ssl_ptr) {
    if (!ssl_ptr) return;
    SSL_set_accept_state(((KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr)->ssl);
}

static void KYO_SSL_FN(ssl_free)(long ssl_ptr) {
    if (!ssl_ptr) return;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    if (st->ssl) SSL_free(st->ssl); /* frees the two BIOs it owns */
    free(st);
}

/* ---- handshake + record layer (memory-BIO state machine) ------------------------------------ */

/*
 * Drive one handshake step. The caller loops: feed inbound ciphertext, call this, drain outbound
 * ciphertext, repeat until done. Return codes:
 *    1 = handshake complete
 *    0 = WANT_READ  (needs more inbound ciphertext fed via feed_ciphertext)
 *   -1 = WANT_WRITE (produced outbound ciphertext to drain via drain_ciphertext)
 *   -2 = fatal error
 */
static int KYO_SSL_FN(do_handshake_step)(long ssl_ptr) {
    if (!ssl_ptr) return -2;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    ERR_clear_error();
    int ret = SSL_do_handshake(st->ssl);
    if (ret == 1) return 1;
    int err = SSL_get_error(st->ssl, ret);
    switch (err) {
        case SSL_ERROR_WANT_READ:  return 0;
        case SSL_ERROR_WANT_WRITE: return -1;
        default:                   return -2;
    }
}

/*
 * Feed inbound ciphertext from the socket into the read-BIO. Returns the number of bytes written
 * (== len when the in-memory BIO accepts it all, which it does for the unbounded BIO_s_mem), or -1
 * on error.
 */
static int KYO_SSL_FN(feed_ciphertext)(long ssl_ptr, const unsigned char *buf, int len) {
    if (!ssl_ptr || !buf || len < 0) return -1;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    if (len == 0) return 0;
    int n = BIO_write(st->read_bio, buf, len);
    return n < 0 ? -1 : n;
}

/*
 * Drain outbound ciphertext the SSL has queued in the write-BIO, to be sent on the socket. Returns
 * the number of bytes copied into buf (0 when nothing is pending), or -1 on error.
 */
static int KYO_SSL_FN(drain_ciphertext)(long ssl_ptr, unsigned char *buf, int len) {
    if (!ssl_ptr || !buf || len < 0) return -1;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    if (len == 0) return 0;
    if (BIO_ctrl_pending(st->write_bio) == 0) return 0;
    int n = BIO_read(st->write_bio, buf, len);
    /* Pending bytes were reported, so a BIO_read returning <= 0 here is a real BIO error (the
       no-pending case already returned 0 above). Surface it as -1 instead of masking it as 0. */
    return n <= 0 ? -1 : n;
}

/*
 * Decrypt application data: read up to len plaintext bytes out of the SSL into buf. Returns:
 *    > 0 = bytes decrypted
 *      0 = WANT_READ (no complete record yet; feed more ciphertext)
 *     -1 = WANT_WRITE (renegotiation wants to send; drain then retry)
 *     -2 = fatal error
 *     -3 = clean close (peer sent close_notify, SSL_ERROR_ZERO_RETURN)
 *
 * The -3 clean-close code is deliberately distinct from the 0 want-read code so the caller can tell
 * an orderly close (the peer's authenticated close_notify was consumed) from a bare TCP FIN with no
 * close_notify (RFC 8446 6.1 truncation attack): collapsing both into 0 makes a truncation
 * indistinguishable from a clean end-of-stream. The library already splits these two at the
 * SSL_ERROR level (ZERO_RETURN vs WANT_READ); this surfaces that split to the caller.
 */
static int KYO_SSL_FN(read_plain)(long ssl_ptr, unsigned char *buf, int len) {
    if (!ssl_ptr || !buf || len < 0) return -2;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    if (len == 0) return 0;
    ERR_clear_error();
    int ret = SSL_read(st->ssl, buf, len);
    if (ret > 0) return ret;
    int err = SSL_get_error(st->ssl, ret);
    switch (err) {
        case SSL_ERROR_WANT_READ:      return 0;
        case SSL_ERROR_ZERO_RETURN:    return -3; /* peer sent close_notify: orderly close */
        case SSL_ERROR_WANT_WRITE:     return -1;
        default:                       return -2;
    }
}

/*
 * Encrypt application data: write up to len plaintext bytes from buf through the SSL. The resulting
 * ciphertext lands in the write-BIO; drain it with drain_ciphertext. Returns:
 *    > 0 = bytes consumed (encrypted)
 *      0 = WANT_READ (renegotiation wants input)
 *     -1 = WANT_WRITE (would block on the BIO; not expected for unbounded BIO_s_mem)
 *     -2 = fatal error
 */
static int KYO_SSL_FN(write_plain)(long ssl_ptr, const unsigned char *buf, int len) {
    if (!ssl_ptr || !buf || len < 0) return -2;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    if (len == 0) return 0;
    ERR_clear_error();
    int ret = SSL_write(st->ssl, buf, len);
    if (ret > 0) return ret;
    int err = SSL_get_error(st->ssl, ret);
    switch (err) {
        case SSL_ERROR_WANT_READ:  return 0;
        case SSL_ERROR_WANT_WRITE: return -1;
        default:                   return -2;
    }
}

/* Number of decrypted-but-unread application bytes buffered in the SSL (SSL_pending). */
static int KYO_SSL_FN(pending)(long ssl_ptr) {
    if (!ssl_ptr) return 0;
    return SSL_pending(((KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr)->ssl);
}

/*
 * Drive one shutdown step (close_notify exchange). The produced close_notify lands in the write-BIO
 * to drain. Returns:
 *    1 = bidirectional shutdown complete
 *    0 = unidirectional close_notify sent, awaiting the peer's (drain then feed + retry)
 *   -2 = fatal error
 */
static int KYO_SSL_FN(shutdown_step)(long ssl_ptr) {
    if (!ssl_ptr) return -2;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    int ret = SSL_shutdown(st->ssl);
    if (ret == 1) return 1;
    if (ret == 0) return 0;
    int err = SSL_get_error(st->ssl, ret);
    switch (err) {
        case SSL_ERROR_WANT_READ:
        case SSL_ERROR_WANT_WRITE: return 0;
        default:                   return -2;
    }
}

/* ---- peer certificate hash (RFC 5929 tls-server-end-point) ----------------------------------- */

/*
 * RFC 5929 tls-server-end-point channel binding: SHA-256 of the peer leaf certificate's DER bytes
 * (i2d_X509). Writes 32 bytes into out_buf. Returns 32 on success, or -1 when there is no peer cert
 * / out_len < 32 / a hashing error. The peer-cert accessor name differs per library: OpenSSL 3
 * exposes SSL_get1_peer_certificate, BoringSSL exposes SSL_get_peer_certificate; both up-ref the
 * cert, so X509_free balances it. The 32 bytes match across both backends: same DER encoding, same
 * SHA-256.
 */
static int KYO_SSL_FN(peer_cert_sha256)(long ssl_ptr, unsigned char *out_buf, int out_len) {
    if (!ssl_ptr || !out_buf || out_len < 32) return -1;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;

#if defined(OPENSSL_IS_BORINGSSL)
    /* BoringSSL does not define the OpenSSL-3 SSL_get1_peer_certificate name; its SSL_get_peer_certificate is the up-reffing accessor
       (the caller must X509_free the result), matching the OpenSSL-3 SSL_get1_peer_certificate contract. */
    X509 *cert = SSL_get_peer_certificate(st->ssl);
#else
    X509 *cert = SSL_get1_peer_certificate(st->ssl);
#endif
    if (!cert) return -1;

    unsigned char *der = NULL;
    int der_len = i2d_X509(cert, &der);
    if (der_len <= 0 || !der) { X509_free(cert); return -1; }

    unsigned int hash_len = 0;
    int ok = EVP_Digest(der, (size_t)der_len, out_buf, &hash_len, EVP_sha256(), NULL);
    OPENSSL_free(der);
    X509_free(cert);
    return (ok == 1 && hash_len == 32) ? 32 : -1;
}
