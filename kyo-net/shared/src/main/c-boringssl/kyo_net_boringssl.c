/*
 * kyo-net BoringSSL shim (kyonet_boringssl).
 *
 * BoringSSL ships as a static archive (libssl.a + libcrypto.a) and exports the same raw `SSL_*`
 * symbols as system OpenSSL. Two problems follow:
 *   1. Panama (JVM) needs a loadable `.so`/`.dylib`, not a `.a`.
 *   2. If both BoringSSL and a system OpenSSL are present, the raw `SSL_*` symbols collide.
 *
 * This shim solves both: it links the BoringSSL archives statically and re-exports the TLS surface
 * kyo-net needs behind a `kyo_bssl_*` prefix, so the loaded library carries exactly one, unambiguous,
 * insulated TLS ABI. The build (build-boringssl.sh + the kyonet_boringssl FfiLibrary) compiles this
 * file against the staged BoringSSL headers and folds the static archives in (linux: -Wl,-Bstatic;
 * darwin: archive-path link; Native: Scala Native archive link).
 *
 * The TLS state machine itself (the two-memory-BIO handshake and record layer) lives in the shared
 * kyo_ssl_common.h, included below with KYO_SSL_PREFIX = kyo_bssl_ so each static function token-pastes
 * to a kyo_bssl_* name. The system-OpenSSL twin (kyo_net_openssl.c) includes the same header with the
 * kyo_ossl_ prefix. This file adds only the BoringSSL openssl headers, the load probe, and the thin
 * exported extern wrappers around the shared static functions.
 *
 *   socket --recv--> kyo_bssl_feed_ciphertext --> [read-BIO] --> SSL_read/handshake (decrypt)
 *   SSL_write/handshake (encrypt) --> [write-BIO] --> kyo_bssl_drain_ciphertext --recv--> socket
 *
 * Opaque `SSL_CTX*` / `SSL*` cross the FFI boundary as pointers (carried as `long`). The caller
 * never dereferences them; it only round-trips them back into these functions.
 *
 * Header gate: the real body compiles only against BoringSSL's own headers, and the #else branch at
 * the end defines the same kyo_bssl_* surface as stubs whose probe reports 0. A Scala Native consumer
 * compiles this file on the machine that links the binary, where the headers in scope are usually a
 * system OpenSSL's or none at all, and the BoringSSL archives this body needs are not on its link; the
 * stub keeps that link whole and lets BoringSslProvider demote at selection. Compiling the real body
 * against another OpenSSL's headers would be worse than failing: OpenSSL 3's BIO_new_mem_buf takes an
 * int length and BoringSSL's an ossl_ssize_t, so a -1 reaches BoringSSL as 0xFFFFFFFF and the PEM
 * reader runs past the string. A build that stages BoringSSL defines KYO_NET_REQUIRE_BORINGSSL, which
 * turns a wrong include order there into a compile error instead of a silent stub.
 */
#if __has_include(<openssl/ssl.h>)
#include <openssl/ssl.h>
#endif

#if defined(KYO_NET_REQUIRE_BORINGSSL) && !defined(OPENSSL_IS_BORINGSSL)
#error "kyo_net_boringssl.c is compiled against non-BoringSSL headers: the staged BoringSSL include must precede every other"
#endif

#if defined(OPENSSL_IS_BORINGSSL)

#include <openssl/bio.h>
#include <openssl/x509.h>
#include <openssl/evp.h>
#include <openssl/objects.h>
#include <openssl/err.h>
#include <openssl/crypto.h>

#define KYO_SSL_PREFIX kyo_bssl_
#include "kyo_ssl_common.h"

/* ---- load probe ----------------------------------------------------------------------------- */

/*
 * Availability probe: allocate and free an SSL_CTX. Returns true (1) when the bundled BoringSSL is
 * present and functional, false (0) otherwise. BoringSslProvider.isAvailable calls this to confirm
 * the bundled lib loaded with no missing symbol before selecting the BoringSSL engine.
 */
int kyo_bssl_probe_available(void) {
    kyo_bssl_ensure_init_impl();
    SSL_CTX *ctx = SSL_CTX_new(TLS_method());
    if (!ctx) return 0;
    SSL_CTX_free(ctx);
    return 1;
}

/*
 * Which body of this file was compiled: 0 here, 1 in the stub below. Kept apart from the probe's 1/0 so
 * SslLibProvider can report a binary built without BoringSSL's headers as a build fact rather than as a
 * library that failed its SSL_CTX probe.
 */
int kyo_bssl_compiled_stub(void) { return 0; }

/* ---- exported wrappers ---------------------------------------------------------------------- */

long kyo_bssl_ctx_new(int isServer) { return kyo_bssl_ctx_new_impl(isServer); }
void kyo_bssl_ctx_free(long ctx_ptr) { kyo_bssl_ctx_free_impl(ctx_ptr); }
int kyo_bssl_ctx_set_cert(long ctx_ptr, const char *cert_pem, const char *key_pem) {
    return kyo_bssl_ctx_set_cert_impl(ctx_ptr, cert_pem, key_pem);
}
void kyo_bssl_ctx_set_verify_mode(long ctx_ptr, int mode) { kyo_bssl_ctx_set_verify_mode_impl(ctx_ptr, mode); }
int kyo_bssl_ctx_load_ca(long ctx_ptr, const char *ca_pem) { return kyo_bssl_ctx_load_ca_impl(ctx_ptr, ca_pem); }
int kyo_bssl_ctx_load_system_ca(long ctx_ptr) { return kyo_bssl_ctx_load_system_ca_impl(ctx_ptr); }
int kyo_bssl_ctx_set_min_max_version(long ctx_ptr, int min, int max) {
    return kyo_bssl_ctx_set_min_max_version_impl(ctx_ptr, min, max);
}
long kyo_bssl_ssl_new(long ctx_ptr, const char *hostname) { return kyo_bssl_ssl_new_impl(ctx_ptr, hostname); }
int kyo_bssl_ssl_set_verify_name(long ssl_ptr, const char *hostname) {
    return kyo_bssl_ssl_set_verify_name_impl(ssl_ptr, hostname);
}
int kyo_bssl_ssl_require_unmatchable_identity(long ssl_ptr) { return kyo_bssl_ssl_require_unmatchable_identity_impl(ssl_ptr); }
void kyo_bssl_ssl_set_connect_state(long ssl_ptr) { kyo_bssl_ssl_set_connect_state_impl(ssl_ptr); }
void kyo_bssl_ssl_set_accept_state(long ssl_ptr) { kyo_bssl_ssl_set_accept_state_impl(ssl_ptr); }
void kyo_bssl_ssl_free(long ssl_ptr) { kyo_bssl_ssl_free_impl(ssl_ptr); }
int kyo_bssl_do_handshake_step(long ssl_ptr) { return kyo_bssl_do_handshake_step_impl(ssl_ptr); }
int kyo_bssl_feed_ciphertext(long ssl_ptr, const unsigned char *buf, int len) {
    return kyo_bssl_feed_ciphertext_impl(ssl_ptr, buf, len);
}
int kyo_bssl_drain_ciphertext(long ssl_ptr, unsigned char *buf, int len) {
    return kyo_bssl_drain_ciphertext_impl(ssl_ptr, buf, len);
}
int kyo_bssl_read_plain(long ssl_ptr, unsigned char *buf, int len) { return kyo_bssl_read_plain_impl(ssl_ptr, buf, len); }
int kyo_bssl_write_plain(long ssl_ptr, const unsigned char *buf, int len) {
    return kyo_bssl_write_plain_impl(ssl_ptr, buf, len);
}
int kyo_bssl_pending(long ssl_ptr) { return kyo_bssl_pending_impl(ssl_ptr); }
int kyo_bssl_shutdown_step(long ssl_ptr) { return kyo_bssl_shutdown_step_impl(ssl_ptr); }
int kyo_bssl_peer_cert_end_point_hash(long ssl_ptr, unsigned char *out_buf, int out_len) {
    return kyo_bssl_peer_cert_end_point_hash_impl(ssl_ptr, out_buf, out_len);
}

/* ---- test-only error-injection seams -------------------------------------------------------- */

/*
 * Push one entry onto the calling thread's OpenSSL error queue, so a test can reproduce a stale-error-queue
 * scenario: a benign WANT_READ that SSL_get_error would misclassify as fatal if the queue were not cleared
 * before the SSL op. Used only by the error-queue reproduction test; not part of the production binding.
 */
void kyo_bssl_test_put_error(void) {
    ERR_put_error(ERR_LIB_SSL, 0, 1, __FILE__, __LINE__);
}

/*
 * Put the session's write BIO into the state the drain-error fix guards: BIO_ctrl_pending reports non-zero
 * but the next BIO_read fails (returns <= 0). A plain memory BIO cannot reach that state (it always reads
 * the bytes it reports pending), so swap the write BIO for a tiny custom BIO whose pending control reports a
 * positive count while its read callback fails. drain_ciphertext then sees pending > 0 at its guard and a
 * failing BIO_read, the exact condition the masked-vs-surfaced return covers. Used only by the drain-error
 * reproduction test; not part of the production binding.
 */
static int kyo_bssl_broken_bread(BIO *bio, char *buf, int len) {
    (void)bio; (void)buf; (void)len;
    return -1; /* read always fails, even though ctrl reports pending bytes */
}
static long kyo_bssl_broken_ctrl(BIO *bio, int cmd, long larg, void *parg) {
    (void)bio; (void)larg; (void)parg;
    if (cmd == BIO_CTRL_PENDING) return 8; /* report bytes pending so the drain guard passes */
    if (cmd == BIO_CTRL_FLUSH) return 1;
    return 0;
}
static int kyo_bssl_broken_create(BIO *bio) {
    BIO_set_init(bio, 1);
    return 1;
}

int kyo_bssl_test_break_write_bio(long ssl_ptr) {
    if (!ssl_ptr) return -1;
    KYO_SSL_FN(ssl_state) *st = (KYO_SSL_FN(ssl_state) *)(intptr_t)ssl_ptr;
    if (!st->ssl) return -1;
    BIO_METHOD *method = BIO_meth_new(BIO_get_new_index() | BIO_TYPE_SOURCE_SINK, "kyo_bssl_broken_write");
    if (!method) return -1;
    BIO_meth_set_read(method, kyo_bssl_broken_bread);
    BIO_meth_set_ctrl(method, kyo_bssl_broken_ctrl);
    BIO_meth_set_create(method, kyo_bssl_broken_create);
    BIO *broken = BIO_new(method);
    if (!broken) { BIO_meth_free(method); return -1; }
    /* Replace the SSL's write BIO with the broken BIO. SSL_set0_wbio takes ownership and frees the old write
       BIO; the read BIO is left untouched so feed/handshake state is unaffected. The method outlives the BIO
       here (a process-lifetime leak of one BIO_METHOD, acceptable in a single-scenario test seam). */
    SSL_set0_wbio(st->ssl, broken);
    st->write_bio = broken;
    return 0;
}

#else

/*
 * Every entry point reports the bundle absent with the sentinel its real counterpart returns on failure
 * (0 / NULL-as-0 / -1 / -2). Signatures MUST match the real wrappers above byte for byte, since the
 * @extern BoringSslBindings name them either way.
 */

long kyo_bssl_ctx_new(int isServer) {
    (void)isServer;
    return 0; /* allocation-failure sentinel: no live context */
}

void kyo_bssl_ctx_free(long ctx_ptr) {
    (void)ctx_ptr;
}

int kyo_bssl_ctx_set_cert(long ctx_ptr, const char *cert_pem, const char *key_pem) {
    (void)ctx_ptr;
    (void)cert_pem;
    (void)key_pem;
    return -1;
}

void kyo_bssl_ctx_set_verify_mode(long ctx_ptr, int mode) {
    (void)ctx_ptr;
    (void)mode;
}

int kyo_bssl_ctx_load_ca(long ctx_ptr, const char *ca_pem) {
    (void)ctx_ptr;
    (void)ca_pem;
    return -1;
}

int kyo_bssl_ctx_load_system_ca(long ctx_ptr) {
    (void)ctx_ptr;
    return 0;
}

int kyo_bssl_ctx_set_min_max_version(long ctx_ptr, int min, int max) {
    (void)ctx_ptr;
    (void)min;
    (void)max;
    return -1;
}

long kyo_bssl_ssl_new(long ctx_ptr, const char *hostname) {
    (void)ctx_ptr;
    (void)hostname;
    return 0; /* allocation-failure sentinel: no live SSL */
}

int kyo_bssl_ssl_set_verify_name(long ssl_ptr, const char *hostname) {
    (void)ssl_ptr;
    (void)hostname;
    return 0; /* set-failure sentinel: no reference identity bound */
}

int kyo_bssl_ssl_require_unmatchable_identity(long ssl_ptr) {
    (void)ssl_ptr;
    return 0; /* set-failure sentinel: no reference identity bound */
}

void kyo_bssl_ssl_set_connect_state(long ssl_ptr) {
    (void)ssl_ptr;
}

void kyo_bssl_ssl_set_accept_state(long ssl_ptr) {
    (void)ssl_ptr;
}

void kyo_bssl_ssl_free(long ssl_ptr) {
    (void)ssl_ptr;
}

int kyo_bssl_do_handshake_step(long ssl_ptr) {
    (void)ssl_ptr;
    return -2; /* fatal-error sentinel */
}

int kyo_bssl_feed_ciphertext(long ssl_ptr, const unsigned char *buf, int len) {
    (void)ssl_ptr;
    (void)buf;
    (void)len;
    return -1;
}

int kyo_bssl_drain_ciphertext(long ssl_ptr, unsigned char *buf, int len) {
    (void)ssl_ptr;
    (void)buf;
    (void)len;
    return -1;
}

int kyo_bssl_read_plain(long ssl_ptr, unsigned char *buf, int len) {
    (void)ssl_ptr;
    (void)buf;
    (void)len;
    return -2; /* fatal-error sentinel */
}

int kyo_bssl_write_plain(long ssl_ptr, const unsigned char *buf, int len) {
    (void)ssl_ptr;
    (void)buf;
    (void)len;
    return -2; /* fatal-error sentinel */
}

int kyo_bssl_pending(long ssl_ptr) {
    (void)ssl_ptr;
    return 0;
}

int kyo_bssl_shutdown_step(long ssl_ptr) {
    (void)ssl_ptr;
    return -2; /* fatal-error sentinel */
}

int kyo_bssl_peer_cert_end_point_hash(long ssl_ptr, unsigned char *out_buf, int out_len) {
    (void)ssl_ptr;
    (void)out_buf;
    (void)out_len;
    return -1; /* no peer cert */
}

int kyo_bssl_probe_available(void) {
    return 0;
}

int kyo_bssl_compiled_stub(void) {
    return 1;
}

void kyo_bssl_test_put_error(void) {
}

int kyo_bssl_test_break_write_bio(long ssl_ptr) {
    (void)ssl_ptr;
    return -1;
}

#endif
