/*
 * OpenSSL stub, ABI-compatible shims for a minimal subset of libssl/libcrypto
 * calls, just enough to exercise the kyo-ffi binding path without depending on
 * OpenSSL being installed. Real users would remove this stub and set
 * `ffiLinkLibs := Seq("ssl", "crypto")` instead, keeping the Scala trait
 * identical.
 *
 * Symbols mirrored here:
 *   - OPENSSL_init_ssl(options, settings) -> int (always returns 1 = success)
 *   - SSL_CTX_new(method)                 -> long (fake handle)
 *   - SSL_CTX_free(ctx)                   -> void
 *   - RAND_bytes(buf, num)                -> int (fills buf, returns 1)
 *   - TLS_client_method()                 -> long (fake handle)
 */

#include <stdint.h>
#include <stdlib.h>

/* Monotonic counter faking SSL_CTX* allocations. */
static int64_t g_next_handle = 1;

/* Track the most recently allocated "context" so SSL_CTX_free can validate. */
static int64_t g_last_handle = 0;

/* Arbitrary method handle (the real TLS_client_method returns a pointer).
 * The `unused` param lets the kyo-ffi JvmEmitter's with-args branch fire;
 * zero-arg methods emit a trailing-comma descriptor in this codegen build. */
int64_t kyo_openssl_tls_client_method(int64_t unused) {
    (void)unused;
    return 0x7151L; /* sentinel, not a real pointer */
}

/* OPENSSL_init_ssl(uint64_t opts, const void *settings), we ignore both. */
int kyo_openssl_init_ssl(int64_t opts, int64_t settings) {
    (void)opts;
    (void)settings;
    return 1;
}

/* SSL_CTX_new(method), returns an opaque handle encoded as int64. */
int64_t kyo_openssl_ssl_ctx_new(int64_t method) {
    (void)method;
    g_last_handle = g_next_handle++;
    return g_last_handle;
}

/* SSL_CTX_free(ctx), "frees" the handle. No-op; we track for assertion. */
void kyo_openssl_ssl_ctx_free(int64_t ctx) {
    if (ctx == g_last_handle) {
        g_last_handle = 0;
    }
}

/* RAND_bytes(unsigned char *buf, int num), fills buf with deterministic
 * pseudo-random bytes. Real RAND_bytes uses CSPRNG; this stub uses a simple
 * linear-congruential to avoid any library dependency while still writing
 * observable non-zero content into the caller's buffer. */
int kyo_openssl_rand_bytes(char *buf, int num) {
    static uint32_t state = 0xDEADBEEFu;
    for (int i = 0; i < num; i++) {
        state = state * 1664525u + 1013904223u;
        buf[i] = (char)(state >> 24);
    }
    return 1;
}
