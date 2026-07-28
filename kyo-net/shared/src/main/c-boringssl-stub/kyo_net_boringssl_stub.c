/*
 * kyo-net BoringSSL shim STUB (kyonet_boringssl, unstaged fallback).
 *
 * When BoringSSL is not staged for the host os-arch (build-boringssl.sh never ran, or the os-arch is
 * unsupported), build.sbt selects THIS file in place of kyo_net_boringssl.c. It defines the exact
 * kyo_bssl_* symbol surface the @extern BoringSslBindings reference, so the kyonet_boringssl library
 * still links: on JVM the loadable lib loads but every call reports "unavailable"; on Native the
 * symbols resolve at link time with no staged libssl/libcrypto archive (the real shim needs them).
 *
 * Every function reports the bundle absent: kyo_bssl_probe_available returns 0 (false), the rest
 * return the same not-available sentinel their real counterparts use on failure (0 / NULL-as-0 / -1).
 * BoringSslProvider.isAvailable then sees probe_available() == false and the TLS registry falls
 * through to the system-OpenSSL shim (kyonet_openssl) on Native or the jdk floor on JVM, and the
 * BoringSSL load tests cancel rather than fail.
 *
 * Signatures here MUST match kyo_net_boringssl.c byte-for-byte: opaque SSL_CTX and SSL pointers cross
 * the FFI boundary as long, ciphertext and plaintext as (unsigned char *, int). No BoringSSL headers are
 * included, so this file compiles on a host with no staged archives and no openssl/ headers.
 */
#include <stddef.h>

/* ---- context lifecycle ---------------------------------------------------------------------- */

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

/* ---- SSL lifecycle -------------------------------------------------------------------------- */

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

/* ---- handshake + record layer --------------------------------------------------------------- */

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

/* ---- peer certificate hash ------------------------------------------------------------------ */

int kyo_bssl_peer_cert_sha256(long ssl_ptr, unsigned char *out_buf, int out_len) {
    (void)ssl_ptr;
    (void)out_buf;
    (void)out_len;
    return -1; /* no peer cert */
}

/* ---- load probe ----------------------------------------------------------------------------- */

/*
 * Availability probe: always false in the stub. BoringSslProvider.isAvailable reads this and the TLS
 * registry falls through to the system-OpenSSL shim (Native) or the jdk floor (JVM).
 */
int kyo_bssl_probe_available(void) {
    return 0;
}

/* ---- test-only error-injection seams (no-op in the stub) ------------------------------------ */

void kyo_bssl_test_put_error(void) {
}

int kyo_bssl_test_break_write_bio(long ssl_ptr) {
    (void)ssl_ptr;
    return -1;
}
