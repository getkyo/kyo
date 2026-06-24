package kyo.net.internal.tls

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.ffi.Ffi

/** Binding to the BoringSSL shim's error-injection seams (`kyo_bssl_test_*`), used only by the C-shim reproduction tests.
  *
  * These seams have no production caller: they exist so a test can deterministically prime the thread-local OpenSSL error queue or force a
  * write-BIO read error, reproducing the stale-error-queue and drain-error defects the shared TLS body fixes. They are bound on a binding
  * separate from [[BoringSslBindings]] so the production surface stays exactly the cross-backend [[SslLibBindings]] intersection, and the
  * real-vs-stub symbol-parity check excludes these `kyo_bssl_test_*` symbols.
  */
private[net] trait BoringSslTestBindings extends Ffi:

    /** `kyo_bssl_test_put_error`: push one entry onto the calling thread's OpenSSL error queue, so a test can drive a benign WANT_READ that
      * `SSL_get_error` would misclassify as fatal if the queue were not cleared before the SSL op.
      */
    def kyo_bssl_test_put_error()(using AllowUnsafe): Unit

    /** `kyo_bssl_test_break_write_bio`: put the session's write BIO into the state the drain-error fix guards (pending reports non-zero but the
      * next read fails), so a test can drive a BIO_read failure during drain. Returns `0` on success, `-1` on a null pointer / set failure.
      */
    def kyo_bssl_test_break_write_bio(ssl: Long)(using AllowUnsafe): Int

end BoringSslTestBindings

private[net] object BoringSslTestBindings extends Ffi.Config(
        library = "kyonet_boringssl",
        headers = Chunk("openssl/ssl.h"),
        // Same bundled shim as BoringSslBindings; the test-only seams resolve through the one loaded library.
        nativeBundled = true
    )
