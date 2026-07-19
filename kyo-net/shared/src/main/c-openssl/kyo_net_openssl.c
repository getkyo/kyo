/*
 * kyo-net system-OpenSSL TLS shim (kyonet_openssl), the Native fallback below BoringSSL.
 *
 * This is the system-OpenSSL twin of kyo_net_boringssl.c: the SAME two-memory-BIO TLS state machine,
 * exported behind a `kyo_ossl_*` prefix instead of `kyo_bssl_*`, compiled against the host's system
 * OpenSSL headers (macOS: openssl@3 via brew; Linux: libssl-dev). It backs the Native
 * `SystemOpenSslProvider` (priority 20), the fallback selected when BoringSSL (priority 30) is not
 * staged for the host or `-Dkyo.net.tls=openssl` forces it.
 *
 * Coexistence with BoringSSL on the single Native binary:
 *   The kyo_bssl shim and this kyo_ossl shim both `#include <openssl/ssl.h>` and both reference the
 *   raw `SSL_*` symbols. They do NOT clash because every `kyo_ossl_*` / `kyo_bssl_*` function is a
 *   distinct, prefixed export, and the raw `SSL_*` calls in both resolve to the ONE TLS implementation
 *   the binary actually links: the system OpenSSL dylib (`-lssl -lcrypto`), which the linker binds
 *   first so the staged BoringSSL archive's same-named objects are never pulled. BoringSSL's headers
 *   are API-compatible with that runtime (opaque `SSL*`/`SSL_CTX*` passed by pointer), so both shims
 *   drive the same OpenSSL through their own prefixed entry points with no symbol collision. The
 *   only exported surface from this translation unit is `kyo_ossl_*`; the `SSL_*` references stay
 *   undefined-imported, exactly as the BoringSSL shim's do.
 *
 * The TLS state machine itself lives in the shared kyo_ssl_common.h, included below with
 * KYO_SSL_PREFIX = kyo_ossl_ so each shared static function token-pastes to a kyo_ossl_*_impl name. The
 * bundled-BoringSSL twin (kyo_net_boringssl.c) includes the same header with the kyo_bssl_ prefix. This
 * file adds only the system-OpenSSL headers, the load probe, and the thin exported extern wrappers
 * around the shared static functions.
 *
 *   socket --recv--> kyo_ossl_feed_ciphertext --> [read-BIO] --> SSL_read/handshake (decrypt)
 *   SSL_write/handshake (encrypt) --> [write-BIO] --> kyo_ossl_drain_ciphertext --send--> socket
 *
 * Opaque `SSL_CTX*` / `SSL*` cross the FFI boundary as pointers (carried as `long`). The caller never
 * dereferences them; it only round-trips them back into these functions.
 */
#include <openssl/ssl.h>
#include <openssl/bio.h>
#include <openssl/x509.h>
#include <openssl/evp.h>
#include <openssl/err.h>
#include <openssl/crypto.h>

#define KYO_SSL_PREFIX kyo_ossl_
#include "kyo_ssl_common.h"

/* ---- load probe ----------------------------------------------------------------------------- */

/*
 * Availability probe: allocate and free an SSL_CTX. Returns true (1) when system OpenSSL is present
 * and functional, false (0) otherwise. SystemOpenSslProvider.isAvailable calls this to confirm the
 * system library loaded with no missing symbol before selecting the OpenSSL engine.
 */
int kyo_ossl_probe_available(void) {
    kyo_ossl_ensure_init_impl();
    SSL_CTX *ctx = SSL_CTX_new(TLS_method());
    if (!ctx) return 0;
    SSL_CTX_free(ctx);
    return 1;
}

/* ---- exported wrappers ---------------------------------------------------------------------- */

long kyo_ossl_ctx_new(int isServer) { return kyo_ossl_ctx_new_impl(isServer); }
void kyo_ossl_ctx_free(long ctx_ptr) { kyo_ossl_ctx_free_impl(ctx_ptr); }
int kyo_ossl_ctx_set_cert(long ctx_ptr, const char *cert_pem, const char *key_pem) {
    return kyo_ossl_ctx_set_cert_impl(ctx_ptr, cert_pem, key_pem);
}
void kyo_ossl_ctx_set_verify_mode(long ctx_ptr, int mode) { kyo_ossl_ctx_set_verify_mode_impl(ctx_ptr, mode); }
int kyo_ossl_ctx_load_ca(long ctx_ptr, const char *ca_pem) { return kyo_ossl_ctx_load_ca_impl(ctx_ptr, ca_pem); }
int kyo_ossl_ctx_load_system_ca(long ctx_ptr) { return kyo_ossl_ctx_load_system_ca_impl(ctx_ptr); }
int kyo_ossl_ctx_set_min_max_version(long ctx_ptr, int min, int max) {
    return kyo_ossl_ctx_set_min_max_version_impl(ctx_ptr, min, max);
}
long kyo_ossl_ssl_new(long ctx_ptr, const char *hostname) { return kyo_ossl_ssl_new_impl(ctx_ptr, hostname); }
int kyo_ossl_ssl_set_verify_name(long ssl_ptr, const char *hostname) {
    return kyo_ossl_ssl_set_verify_name_impl(ssl_ptr, hostname);
}
int kyo_ossl_ssl_require_unmatchable_identity(long ssl_ptr) { return kyo_ossl_ssl_require_unmatchable_identity_impl(ssl_ptr); }
void kyo_ossl_ssl_set_connect_state(long ssl_ptr) { kyo_ossl_ssl_set_connect_state_impl(ssl_ptr); }
void kyo_ossl_ssl_set_accept_state(long ssl_ptr) { kyo_ossl_ssl_set_accept_state_impl(ssl_ptr); }
void kyo_ossl_ssl_free(long ssl_ptr) { kyo_ossl_ssl_free_impl(ssl_ptr); }
int kyo_ossl_do_handshake_step(long ssl_ptr) { return kyo_ossl_do_handshake_step_impl(ssl_ptr); }
int kyo_ossl_feed_ciphertext(long ssl_ptr, const unsigned char *buf, int len) {
    return kyo_ossl_feed_ciphertext_impl(ssl_ptr, buf, len);
}
int kyo_ossl_drain_ciphertext(long ssl_ptr, unsigned char *buf, int len) {
    return kyo_ossl_drain_ciphertext_impl(ssl_ptr, buf, len);
}
int kyo_ossl_read_plain(long ssl_ptr, unsigned char *buf, int len) { return kyo_ossl_read_plain_impl(ssl_ptr, buf, len); }
int kyo_ossl_write_plain(long ssl_ptr, const unsigned char *buf, int len) {
    return kyo_ossl_write_plain_impl(ssl_ptr, buf, len);
}
int kyo_ossl_pending(long ssl_ptr) { return kyo_ossl_pending_impl(ssl_ptr); }
int kyo_ossl_shutdown_step(long ssl_ptr) { return kyo_ossl_shutdown_step_impl(ssl_ptr); }
int kyo_ossl_peer_cert_sha256(long ssl_ptr, unsigned char *out_buf, int out_len) {
    return kyo_ossl_peer_cert_sha256_impl(ssl_ptr, out_buf, out_len);
}
