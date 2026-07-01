package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines

/** Regression test for the fatal-record-behind-good-data swallow that [[TlsEngineIo.feedAndDecrypt]] guards against.
  *
  * `feedAndDecrypt` decrypts every application byte buffered in the engine. When two TLS records arrive coalesced in one `feedCiphertext` and
  * the FIRST decrypts cleanly while the SECOND is a fatal record-layer error (a tampered / bad-MAC record, RFC 5246 §7.2.2 requires a fatal
  * alert and connection termination), it MUST NOT return the good prefix as if nothing was wrong: it surfaces the fatal record (the decode
  * yields the FatalRecord sentinel) and tears down the handle, so the fatal error reaches the driver instead of being silently dropped.
  *
  * This leaf feeds `[good record][corrupted record]` (the corruption flips a body byte of the second record so its AEAD tag fails) and pins
  * two facts: (1) the engine genuinely reaches the fatal state on the corrupt record (a direct `readPlain` returns `-2`), and (2)
  * `feedAndDecrypt` does NOT deliver only the good prefix, so the fatal error is not swallowed.
  *
  * Gate: cancels where no BoringSSL provider is staged. Synchronous: encrypt, corrupt, feed, decrypt all run inline in memory, no socket, no
  * sleep.
  */
class TlsEngineIoCorruptRecordTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** Minimal [[TlsEngineIo]] subclass exposing the feedAndDecrypt seam, as in TlsEngineIoTest. */
    final private class TlsEngineIoHarness extends TlsEngineIo:
        def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()
        def label: String                                                  = "TlsEngineIoHarness"
        def feedAndDecryptForTest(engine: TlsEngine, cipher: Buffer[Byte], len: Int, handle: PosixHandle)(using AllowUnsafe): Array[Byte] =
            feedAndDecrypt(engine, cipher, len, handle, () => handle.requestClose())
    end TlsEngineIoHarness

    /** Decrypt one record directly off the engine, returning the engine's raw readPlain code (`> 0` bytes, `0` want-read/close, `-2` fatal). */
    private def rawReadOne(engine: TlsEngine, cap: Int)(using AllowUnsafe): Int =
        val out = Buffer.alloc[Byte](cap)
        try engine.readPlain(out, cap)
        finally out.close()
    end rawReadOne

    // Anti-flakiness: a real handshake completes synchronously over the in-memory loopback; encrypt/corrupt/feed/decrypt run inline. No async.
    "feedAndDecrypt surfaces a fatal record coalesced behind good data instead of delivering only the good prefix" in {
        TlsRealEngines.assumeTlsReady()
        Sync.defer {
            TlsRealEngines.withEngines { (client, server) =>
                assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")

                val good = "GOOD-application-record".getBytes("UTF-8")
                val bad  = "TAMPERED-application-record".getBytes("UTF-8")

                // One writePlain per record yields one TLS record each.
                val goodRecord = TlsEngineLoopback.encrypt(client, good)
                val badRecord  = TlsEngineLoopback.encrypt(client, bad)
                assert(goodRecord.length > 5 && badRecord.length > 5, "expected real TLS records with a 5-byte header plus body")

                // Corrupt the body of the SECOND record (skip the 5-byte record header) so its AEAD tag fails. The first record stays intact.
                val corrupted = badRecord.clone()
                corrupted(corrupted.length - 1) = (corrupted(corrupted.length - 1) ^ 0xff).toByte

                // Coalesce: [good record][corrupted record] in one feed, exactly the on-wire batching the driver sees under load.
                val coalesced = new Array[Byte](goodRecord.length + corrupted.length)
                java.lang.System.arraycopy(goodRecord, 0, coalesced, 0, goodRecord.length)
                java.lang.System.arraycopy(corrupted, 0, coalesced, goodRecord.length, corrupted.length)

                // Probe engine: confirm the corrupt record genuinely drives the engine to the fatal state. The first readPlain yields the good
                // record; the second readPlain on the corrupt record returns -2 (fatal). This proves the engine DOES detect the tampering; the
                // question is only whether feedAndDecrypt surfaces it.
                val probeServer = TlsRealEngines.singleEngine(isServer = true)
                try
                    val probeClient = TlsRealEngines.singleEngine(isServer = false)
                    try
                        assert(TlsEngineLoopback.handshake(probeClient, probeServer), "probe handshake did not complete")
                        val pGood    = TlsEngineLoopback.encrypt(probeClient, good)
                        val pBad     = TlsEngineLoopback.encrypt(probeClient, bad)
                        val pCorrupt = pBad.clone()
                        pCorrupt(pCorrupt.length - 1) = (pCorrupt(pCorrupt.length - 1) ^ 0xff).toByte
                        val pCoalesced = new Array[Byte](pGood.length + pCorrupt.length)
                        java.lang.System.arraycopy(pGood, 0, pCoalesced, 0, pGood.length)
                        java.lang.System.arraycopy(pCorrupt, 0, pCoalesced, pGood.length, pCorrupt.length)
                        TlsEngineLoopback.feed(probeServer, pCoalesced)
                        val first  = rawReadOne(probeServer, 16 * 1024)
                        val second = rawReadOne(probeServer, 16 * 1024)
                        assert(first > 0, s"probe: first record should decrypt cleanly, got readPlain=$first")
                        assert(
                            second == -2,
                            s"probe: corrupt second record should drive the engine fatal (readPlain == -2), got $second"
                        )
                    finally probeClient.free()
                    end try
                finally probeServer.free()
                end try

                // Now the actual subject: feedAndDecrypt over the coalesced [good][corrupt] batch. The driver gets only this array; there is no
                // separate error signal. feedAndDecrypt must surface the fatal record rather than returning the good prefix.
                val harness = new TlsEngineIoHarness
                val handle  = PosixHandle.socket(202, PosixHandle.DefaultReadBufferSize, Absent)
                val cipher  = Buffer.fromArray[Byte](coalesced)
                val delivered =
                    try harness.feedAndDecryptForTest(server, cipher, coalesced.length, handle)
                    finally cipher.close()

                // What feedAndDecrypt delivered to the driver: it must NOT be exactly the good prefix, which would mean the fatal record was
                // swallowed.
                val deliveredGoodPrefixOnly = delivered.sameElements(good)

                // The CORRECT behavior: a fatal record coalesced behind good data must abort the connection (the driver must learn of the
                // fatal error), NOT be silently dropped while the good prefix is delivered as a normal read. feedAndDecrypt surfaces the fatal
                // record (FatalRecord sentinel -> empty result + requestClose), so the good prefix is not delivered alone.
                assert(
                    !deliveredGoodPrefixOnly,
                    "SECURITY: feedAndDecrypt delivered only the good prefix and silently swallowed the fatal (bad-MAC) record coalesced behind it; " +
                        "RFC 5246 §7.2.2 requires the connection to be torn down on a fatal record, but the driver never learns of the -2"
                )
            }
        }
    }

end TlsEngineIoCorruptRecordTest
