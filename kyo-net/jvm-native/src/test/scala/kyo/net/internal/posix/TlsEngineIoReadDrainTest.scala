package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines

/** Reproduce-first confirmation of the read-path WANT_WRITE / queued-ciphertext drop in [[TlsEngineIo]].
  *
  * OpenSSL/BoringSSL `SSL_read` can produce outbound ciphertext that MUST be drained and sent: a TLS 1.3 post-handshake KeyUpdate with
  * `update_requested` makes the receiver queue a KeyUpdate response, and a TLS 1.2 HelloRequest drives a renegotiation flight. The engine
  * contract ([[TlsEngine.readPlain]]) names `-1` as WANT_WRITE for exactly this. The read path [[TlsEngineIo.feedAndDecrypt]] ->
  * [[TlsEngineIo.decryptAll]] calls only `feedCiphertext` then `readPlain`; it NEVER calls `drainCiphertext`. So any ciphertext the engine
  * produces in response to an inbound record (the KeyUpdate response, the renegotiation flight) sits in the write BIO and is never sent: the
  * peer waits for it forever (a TLS 1.3 KeyUpdate stall, a broken TLS 1.2 renegotiation).
  *
  * Driving a real end-to-end KeyUpdate is NOT reachable from the test: the `kyonet_boringssl` / `kyonet_openssl` shims re-export only the
  * `kyo_bssl_*` / `kyo_ossl_*` surface (verified: `nm -gU` on the dylib shows zero raw `SSL_*` exports), and neither shim binds `SSL_key_update`
  * or `SSL_renegotiate`, so a test cannot make a real engine emit a KeyUpdate response without adding a production shim function, which this
  * test does not do. It instead pins the ROOT CAUSE deterministically with real engines: it drives a real record through the
  * actual driver read path and asserts the read path issues ZERO `drainCiphertext` calls, so there is no code path by which read-produced
  * ciphertext could ever be sent. A correct read path would drain the write BIO after the read (the `feedAndDecrypt` step would call
  * `drainCiphertext` at least once and hand the bytes to the driver's send mechanism); today it does not.
  *
  * Gate: cancels where no BoringSSL provider is staged. Synchronous, in-memory, no socket, no sleep.
  */
class TlsEngineIoReadDrainTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** Minimal [[TlsEngineIo]] subclass exposing the protected feedAndDecrypt seam (the real driver read step). */
    final private class TlsEngineIoHarness extends TlsEngineIo:
        def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()
        def label: String                                                  = "TlsEngineIoHarness"
        def feedAndDecryptForTest(engine: TlsEngine, cipher: Buffer[Byte], len: Int, handle: PosixHandle)(using AllowUnsafe): Array[Byte] =
            feedAndDecrypt(engine, cipher, len, handle, () => handle.requestClose())
    end TlsEngineIoHarness

    // Anti-flakiness: a real handshake completes synchronously over the in-memory loopback; the read step runs inline. No async, no sleep.
    "the read path never drains the write BIO, so any read-produced ciphertext (KeyUpdate / renegotiation response) is dropped" in {
        TlsRealEngines.assumeTlsReady()
        Sync.defer {
            TlsRealEngines.withEngines { (client, server) =>
                assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")

                // A real application record from the client to the server (the inbound ciphertext the driver hands to the read path). After a
                // post-handshake message that required a response, this is exactly the moment the read path would have to drain the write BIO.
                val payload = "application-data-on-the-read-path".getBytes("UTF-8")
                val record  = TlsEngineLoopback.encrypt(client, payload)

                val recording = RecordingTlsEngine(server)
                val harness   = new TlsEngineIoHarness
                val handle    = PosixHandle.socket(303, PosixHandle.DefaultReadBufferSize, Absent)
                val cipher    = Buffer.fromArray[Byte](record)
                val decrypted =
                    try harness.feedAndDecryptForTest(recording, cipher, record.length, handle)
                    finally cipher.close()

                // Sanity: the read path did decrypt the inbound record (it really ran the engine, not a no-op).
                assert(decrypted.sameElements(payload), s"read path did not decrypt the record: got ${new String(decrypted, "UTF-8")}")

                // The defect: the read path issued ZERO drainCiphertext calls. There is therefore no code path by which a KeyUpdate response or
                // a renegotiation flight that the engine queues during the read could ever reach the wire. A correct read path would drain the
                // write BIO after the read (drainCalls >= 1) and hand the bytes to the driver's send mechanism. This assertion FAILS today.
                val drains = recording.drainCalls.get()
                assert(
                    drains > 0,
                    s"read path made $drains drainCiphertext calls: feedAndDecrypt/decryptAll never drain the write BIO, so a TLS 1.3 KeyUpdate " +
                        "response or a TLS 1.2 renegotiation flight produced during SSL_read is queued in the write BIO and never sent (the peer stalls)"
                )
            }
        }
    }

end TlsEngineIoReadDrainTest
