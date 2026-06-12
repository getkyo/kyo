package kyo.net.internal.tls

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Reproduce-first test for the missing close_notify / truncation-indistinguishability defect in the BoringSSL engine surface
  * ([[NativeSslEngine]] over the `kyo_net_boringssl.c` shim).
  *
  * RFC 8446 §6.1 / RFC 5246 §7.2.1: each party MUST send a close_notify before closing the write side; a connection ended without one is
  * indicative of a truncation attack and the receiver MUST be able to tell the two apart. The shim collapses the distinction:
  * `kyo_bssl_read_plain` (lines 268-273) maps BOTH `SSL_ERROR_WANT_READ` (no more data yet) AND `SSL_ERROR_ZERO_RETURN` (the peer's
  * close_notify) to the same `0` return. So `readPlain == 0` after a clean shutdown is byte-for-byte indistinguishable from `readPlain == 0`
  * after a bare TCP FIN: the driver (which treats `recv == 0` as a clean empty Span and tears the connection down on it) cannot tell an
  * authentic end-of-stream from a truncated one. The [[BoringSslBindings.shutdownStep]] entry that EMITS a close_notify exists in the
  * shim and bindings but is never called anywhere in the connection lifecycle, and even if it were, the read side maps the peer's reply to the
  * same `0`.
  *
  * This leaf drives the handshake and the close_notify exchange directly over [[BoringSslBindings]] so the test OWNS both `ssl` pointers and can
  * call `kyo_bssl_shutdown_step` (the seam the engine surface never reaches). It establishes two facts: (1) `shutdown_step` DOES emit a real
  * close_notify alert record (the machinery exists), and (2) the client's `kyo_bssl_read_plain` returns the SAME `0` whether it consumed that
  * close_notify (clean close) or saw no further bytes at all (truncation), so the surface cannot distinguish the two.
  *
  * Gate: cancels where BoringSSL is not staged. Synchronous, in-memory (two BIO_s_mem buffers per session), no socket, no sleep.
  */
class BoringSslEngineCloseNotifyTest extends Test:

    import AllowUnsafe.embrace.danger

    private val bssl = Ffi.load[BoringSslBindings]

    private val chunk = 16 * 1024 + 512

    /** Drain everything one ssl has queued in its write BIO into a byte array (the bytes a peer would receive on the wire). */
    private def drainAll(ssl: Long)(using AllowUnsafe): Array[Byte] =
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

    /** Feed a byte array into one ssl's read BIO. */
    private def feed(ssl: Long, bytes: Array[Byte])(using AllowUnsafe): Unit =
        if bytes.length > 0 then
            val buf = Buffer.fromArray[Byte](bytes)
            try discard(bssl.feedCiphertext(ssl, buf, bytes.length))
            finally buf.close()
    end feed

    /** readPlain one chunk off an ssl, returning the raw return code (`> 0` bytes, `0` want-read OR clean close, `-1` want-write, `-2` fatal). */
    private def rawRead(ssl: Long)(using AllowUnsafe): Int =
        val out = Buffer.alloc[Byte](chunk)
        try bssl.readPlain(ssl, out, chunk)
        finally out.close()
    end rawRead

    /** Drive both sessions' handshakes to completion by shuttling ciphertext, mirroring [[TlsEngineLoopback.handshake]] at the binding level. */
    private def handshake(client: Long, server: Long)(using AllowUnsafe): Boolean =
        var round      = 0
        var clientDone = false
        var serverDone = false
        while round < 50 && !(clientDone && serverDone) do
            val c = bssl.doHandshakeStep(client)
            if c == 1 then clientDone = true
            else if c == -2 then return false
            feed(server, drainAll(client))
            val s = bssl.doHandshakeStep(server)
            if s == 1 then serverDone = true
            else if s == -2 then return false
            feed(client, drainAll(server))
            round += 1
        end while
        clientDone && serverDone
    end handshake

    /** Build a connect/accept ssl pair, run `f` with their pointers, then free both contexts and ssls. */
    private def withSslPair[A](f: (Long, Long) => A)(using kyo.test.AssertScope, Frame, AllowUnsafe): A =
        val clientCtx = bssl.ctxNew(0)
        val serverCtx = bssl.ctxNew(1)
        assert(clientCtx != 0L && serverCtx != 0L, "SSL_CTX_new failed")
        // Server presents the test cert; client trusts all (the close-semantics test is orthogonal to verification).
        discard(bssl.ctxSetVerifyMode(clientCtx, 0))
        discard(bssl.ctxSetCert(serverCtx, TlsTestCert.certPem, TlsTestCert.keyPem))
        val client = bssl.sslNew(clientCtx, "localhost")
        val server = bssl.sslNew(serverCtx, "localhost")
        assert(client != 0L && server != 0L, "SSL_new failed")
        bssl.sslSetConnectState(client)
        bssl.sslSetAcceptState(server)
        try f(client, server)
        finally
            bssl.sslFree(client)
            bssl.sslFree(server)
            bssl.ctxFree(clientCtx)
            bssl.ctxFree(serverCtx)
        end try
    end withSslPair

    // Anti-flakiness: handshake and close_notify exchange run synchronously over in-memory BIOs. No async, no sleep.
    "a real close_notify is emitted by shutdown_step but the read side cannot distinguish it from a bare-FIN truncation" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        Sync.defer {
            // Case A: CLEAN close. The server drives SSL_shutdown, which queues a real close_notify alert; the client consumes it then reads.
            val cleanRead = withSslPair { (client, server) =>
                assert(handshake(client, server), "clean-case handshake did not complete")
                val step = bssl.shutdownStep(server)
                assert(step == 0 || step == 1, s"shutdown_step should send close_notify (0 unidirectional / 1 done), got $step")
                val alert = drainAll(server)
                assert(alert.length > 0, "shutdown_step produced no close_notify alert record")
                // The alert is a real TLS alert record: content type 21 (0x15) on a TLS 1.2 record, or an encrypted app-data record (0x17)
                // carrying the alert under TLS 1.3. Either way it is a non-empty record the peer must process as the orderly-close signal.
                feed(client, alert)
                rawRead(client) // SSL_read after consuming the peer's close_notify -> SSL_ERROR_ZERO_RETURN -> shim returns 0.
            }

            // Case B: TRUNCATION. A fresh handshake; the server sends NO close_notify (a bare TCP FIN leaves the client with nothing more to read).
            val truncRead = withSslPair { (client, server) =>
                assert(handshake(client, server), "truncation-case handshake did not complete")
                rawRead(client) // SSL_read with no further ciphertext -> SSL_ERROR_WANT_READ -> shim returns 0.
            }

            // The bug: the clean close (peer DID send close_notify) and the truncation (peer did NOT) surface the SAME readPlain == 0. The
            // CORRECT behavior is that a close_notify-terminated close is distinguishable from a truncation so the driver can fail a truncated
            // read. This assertion FAILS today: kyo_bssl_read_plain maps ZERO_RETURN and WANT_READ both to 0.
            assert(
                cleanRead != truncRead,
                s"SECURITY: clean close_notify shutdown (readPlain=$cleanRead) is indistinguishable from a bare-FIN truncation " +
                    s"(readPlain=$truncRead); kyo_bssl_read_plain collapses ZERO_RETURN and WANT_READ to 0, so a truncation attack reads as a clean EOF"
            )
        }
    }

end BoringSslEngineCloseNotifyTest
