/**
 * kyo-net TLS introspection helpers for Scala Native.
 *
 * Provides RFC 5929 tls-server-end-point certificate hash extraction:
 * - kyo_tls_peer_cert_sha256: compute SHA-256 of the server's leaf cert DER bytes
 *
 * Returns the number of bytes written to the output buffer (32 for SHA-256),
 * or -1 if no peer certificate is available or an error occurs.
 */
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/evp.h>
#include <openssl/err.h>
#include <string.h>

/**
 * Compute SHA-256 of the peer (server) leaf certificate DER bytes.
 *
 * @param ssl_ptr  Opaque SSL* pointer (cast from CLong).
 * @param out_buf  Caller-allocated buffer of at least 32 bytes.
 * @param out_len  Length of out_buf (must be >= 32).
 * @return         Number of bytes written (32), or -1 on error / no peer cert.
 */
int kyo_tls_peer_cert_sha256(long ssl_ptr, unsigned char *out_buf, int out_len) {
    if (!ssl_ptr || !out_buf || out_len < 32) return -1;

    SSL *ssl = (SSL *)ssl_ptr;
    X509 *cert = SSL_get_peer_certificate(ssl);
    if (!cert) return -1;

    unsigned char *der = NULL;
    int der_len = i2d_X509(cert, &der);
    if (der_len <= 0 || !der) {
        X509_free(cert);
        return -1;
    }

    unsigned int hash_len = 0;
    int result = EVP_Digest(der, (size_t)der_len, out_buf, &hash_len, EVP_sha256(), NULL);

    OPENSSL_free(der);
    X509_free(cert);

    return (result == 1) ? (int)hash_len : -1;
}
