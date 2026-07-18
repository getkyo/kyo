package kyo.net.internal

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Reproduce-first test for the masked-drain-error defect in the shared TLS state machine.
  *
  * `drain_ciphertext` guards `BIO_ctrl_pending == 0` and returns `0` (nothing to send) before reading the write BIO, so a `BIO_read` that
  * fails AFTER pending reported non-zero is a genuine BIO error, not emptiness. The original body masked it (`return n < 0 ? 0 : n`), which
  * told the driver "nothing pending" on a broken write BIO instead of surfacing the failure. The fix returns `-1` so the driver's drain loop
  * raises a TLS error rather than silently dropping outbound ciphertext.
  *
  * This leaf drives the write BIO into exactly that state (via the test-only `kyo_bssl_test_break_write_bio` seam: pending reports non-zero,
  * the next `BIO_read` fails) and asserts `drainCiphertext` returns `-1`, not the masked `0`. Synchronous, in-memory, no socket, no sleep.
  */
class BoringSslDrainErrorTest extends Test:

    import AllowUnsafe.embrace.danger

    "drainCiphertext returns -1 when the write BIO fails to read with pending bytes" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val bssl = Ffi.load[BoringSslBindings]
            val test = Ffi.load[BoringSslTestBindings]
            val ctx  = bssl.ctxNew(0)
            assert(ctx != 0L, "SSL_CTX_new failed")
            discard(bssl.ctxSetVerifyMode(ctx, 0))
            val ssl = bssl.sslNew(ctx, "localhost")
            assert(ssl != 0L, "SSL_new failed")
            bssl.sslSetConnectState(ssl)
            try
                // Break the write BIO: it now reports pending bytes but the next read fails, the exact condition the drain-error fix surfaces.
                assert(test.kyo_bssl_test_break_write_bio(ssl) == 0, "test seam failed to break the write BIO")
                val buf = Buffer.alloc[Byte](256)
                try
                    val n = bssl.drainCiphertext(ssl, buf, 256)
                    assert(n == -1, s"a failed BIO_read with pending bytes was masked instead of surfaced: expected -1, got $n")
                finally buf.close()
                end try
            finally
                bssl.sslFree(ssl)
                bssl.ctxFree(ctx)
            end try
        }
    }

end BoringSslDrainErrorTest
