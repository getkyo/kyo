package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Integration reproduction for the stale-error-queue behavior the shared TLS state machine guards.
  *
  * OpenSSL/BoringSSL keep a per-thread error queue. The shared body calls `ERR_clear_error` before every SSL op
  * (`do_handshake_step`, `read_plain`, `write_plain`) so a stale entry left by an unrelated earlier call on the same carrier never affects the
  * current op's classification. The system-OpenSSL shim already cleared; the bundled-BoringSSL shim did not, an asymmetry the unified body
  * removes.
  *
  * The standalone misclassification reproduction (prime the queue, drive one want-read step, expect it not to be reported fatal) is not
  * constructible against the staged BoringSSL or system OpenSSL: both libraries clear the error queue internally at the start of each SSL op, so
  * a primed entry does not reach `SSL_get_error`. Per the design's documented fallback for that case, this is the integration reproduction: it
  * primes a benign error on the carrier (via the test-only `kyo_bssl_test_put_error` seam), then drives a FULL real client/server handshake to
  * completion on that same carrier and asserts it SURVIVES, the observable guarantee the per-op clear preserves. Synchronous, in-memory, no
  * socket, no sleep.
  */
class BoringSslErrQueueTest extends Test:

    import AllowUnsafe.embrace.danger

    private val chunk = 16 * 1024

    private def drainAll(bssl: BoringSslBindings, ssl: Long)(using AllowUnsafe): Array[Byte] =
        val acc  = new java.io.ByteArrayOutputStream
        var more = true
        while more do
            val buf = Buffer.alloc[Byte](chunk)
            try
                val n = bssl.drainCiphertext(ssl, buf, chunk)
                if n <= 0 then more = false
                else acc.write(Buffer.copyToArray[Byte](buf, 0, n))
            finally buf.close()
            end try
        end while
        acc.toByteArray
    end drainAll

    private def feed(bssl: BoringSslBindings, ssl: Long, bytes: Array[Byte])(using AllowUnsafe): Unit =
        if bytes.length > 0 then
            val buf = Buffer.fromArray[Byte](bytes)
            try discard(bssl.feedCiphertext(ssl, buf, bytes.length))
            finally buf.close()

    private def handshake(bssl: BoringSslBindings, client: Long, server: Long)(using AllowUnsafe): Boolean =
        var round      = 0
        var clientDone = false
        var serverDone = false
        while round < 50 && !(clientDone && serverDone) do
            val c = bssl.doHandshakeStep(client)
            if c == 1 then clientDone = true
            else if c == -2 then return false
            feed(bssl, server, drainAll(bssl, client))
            val s = bssl.doHandshakeStep(server)
            if s == 1 then serverDone = true
            else if s == -2 then return false
            feed(bssl, client, drainAll(bssl, server))
            round += 1
        end while
        clientDone && serverDone
    end handshake

    "a benign error primed on the carrier does not poison the next real handshake" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            val bssl      = Ffi.load[BoringSslBindings]
            val test      = Ffi.load[BoringSslTestBindings]
            val clientCtx = bssl.ctxNew(0)
            val serverCtx = bssl.ctxNew(1)
            assert(clientCtx != 0L && serverCtx != 0L, "SSL_CTX_new failed")
            discard(bssl.ctxSetVerifyMode(clientCtx, 0))
            discard(bssl.ctxSetCert(serverCtx, TlsTestCert.certPem, TlsTestCert.keyPem))
            val client = bssl.sslNew(clientCtx, "localhost")
            val server = bssl.sslNew(serverCtx, "localhost")
            assert(client != 0L && server != 0L, "SSL_new failed")
            bssl.sslSetConnectState(client)
            bssl.sslSetAcceptState(server)
            try
                // Prime a stale benign entry on this carrier's error queue, then drive a full handshake on the same carrier: the per-op clear
                // keeps the stale entry from poisoning the negotiation, so the handshake completes on both sides.
                test.kyo_bssl_test_put_error()
                assert(handshake(bssl, client, server), "a primed benign error poisoned the handshake: it did not complete on both sides")
            finally
                bssl.sslFree(client)
                bssl.sslFree(server)
                bssl.ctxFree(clientCtx)
                bssl.ctxFree(serverCtx)
            end try
        }
    }

end BoringSslErrQueueTest
