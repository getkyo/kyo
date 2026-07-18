package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines

/** Tests the engine-step allocation seams in [[TlsEngineIo]] over a real BoringSSL engine.
  *
  * [[TlsEngineIo.feedAndDecrypt]] decrypts every application byte the engine buffers into one array. On a multi-record decode it reuses two
  * per-handle buffers: the drain buffer (drainFor) that every readPlain writes into, and the GrowableByteBuffer accumulator (decryptAcc)
  * that survives across feedAndDecrypt calls. This suite drives real ciphertext (encrypted by a real client engine, one TLS record per
  * writePlain) through a real server engine and observes the reused buffers via a [[RecordingTlsEngine]] spy whose readPlain delegates to
  * the real engine and records the Buffer instance it received.
  *
  * Gate: assumeTlsReady cancels where no BoringSSL/OpenSSL provider is staged. Synchronous: feedAndDecrypt runs inline, no async completion.
  */
class TlsEngineIoTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** Minimal concrete [[TlsEngineIo]] subclass exposing the feedAndDecrypt seam. The submitEngineOp here runs the op inline because
      * the test calls feedAndDecrypt directly (no driver FIFO); the trait assumes the caller already serialized engine access.
      */
    final private class TlsEngineIoHarness extends TlsEngineIo:
        def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()
        def label: String                                                  = "TlsEngineIoHarness"
        def callFeedAndDecrypt(engine: TlsEngine, cipher: Buffer[Byte], len: Int, handle: PosixHandle)(using AllowUnsafe): Array[Byte] =
            feedAndDecrypt(engine, cipher, len, handle, () => handle.requestClose())
    end TlsEngineIoHarness

    /** Encrypt each plaintext chunk as its own TLS record through the real client engine and concatenate the ciphertext. One writePlain per
      * chunk yields one record, so feeding the concatenation to the server makes feedAndDecrypt take the multi-record path (one readPlain per
      * record).
      */
    private def encryptRecords(client: TlsEngine, chunks: Seq[Array[Byte]])(using AllowUnsafe): Array[Byte] =
        val out = new java.io.ByteArrayOutputStream
        chunks.foreach(c => out.write(TlsEngineLoopback.encrypt(client, c)))
        out.toByteArray
    end encryptRecords

    "TlsEngineIo" - {

        // Anti-flakiness: a real handshake completes synchronously via the in-memory loopback; feedAndDecrypt runs inline on the test fiber.
        // No async completion, no sleep.
        "feedAndDecrypt reuses one drain buffer across the readPlain calls of a multi-record decode" in {
            TlsRealEngines.assumeTlsReady()
            Sync.defer {
                TlsRealEngines.withEngines { (client, server) =>
                    assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")
                    val harness   = new TlsEngineIoHarness
                    val recording = RecordingTlsEngine(server)
                    val handle    = PosixHandle.socket(99, PosixHandle.DefaultReadBufferSize, Absent)

                    // Two records arriving together: feedAndDecrypt loops readPlain once per record, then once more to see the drain.
                    val r1        = Array.tabulate[Byte](32)(i => i.toByte)
                    val r2        = Array.tabulate[Byte](24)(i => (i + 32).toByte)
                    val cipher    = encryptRecords(client, Seq(r1, r2))
                    val cipherBuf = Buffer.fromArray[Byte](cipher)

                    val decrypted =
                        try harness.callFeedAndDecrypt(recording, cipherBuf, cipher.length, handle)
                        finally cipherBuf.close()
                    assert(decrypted.toList == (r1 ++ r2).toList, s"decrypted bytes mismatch: got ${decrypted.toList}")

                    // Every readPlain the real engine ran received the same per-handle drain buffer instance.
                    import scala.jdk.CollectionConverters.*
                    val drainBufs = recording.readPlainBufs.iterator().asScala.toList
                    assert(drainBufs.size >= 2, s"expected at least 2 readPlain calls for a multi-record decode, got ${drainBufs.size}")
                    val drainBuf0 = drainBufs.head
                    drainBufs.zipWithIndex.foreach { case (buf, i) =>
                        assert(
                            buf eq drainBuf0,
                            s"readPlain call $i received a different drain buffer: expected $drainBuf0, got $buf"
                        )
                    }
                    // The reused buffer is the per-handle decryptDrain field set by drainFor.
                    handle.decryptDrain match
                        case Present(field) =>
                            assert(
                                drainBuf0 eq field,
                                s"readPlain drain buffer differs from handle.decryptDrain: got $drainBuf0, field=$field"
                            )
                        case Absent =>
                            fail("handle.decryptDrain must be Present after a multi-record feedAndDecrypt")
                    end match
                }
            }
        }

        // Anti-flakiness: both feedAndDecrypt calls run inline; the handshake completes synchronously in memory. No async, no sleep.
        "feedAndDecrypt reuses one decryptAcc accumulator across two multi-record decodes" in {
            TlsRealEngines.assumeTlsReady()
            Sync.defer {
                TlsRealEngines.withEngines { (client, server) =>
                    assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")
                    val harness = new TlsEngineIoHarness
                    val engine  = RecordingTlsEngine(server)
                    val handle  = PosixHandle.socket(100, PosixHandle.DefaultReadBufferSize, Absent)

                    // First multi-record decode allocates the accumulator.
                    val a1         = Array.tabulate[Byte](20)(i => i.toByte)
                    val a2         = Array.tabulate[Byte](16)(i => (i + 20).toByte)
                    val cipher1    = encryptRecords(client, Seq(a1, a2))
                    val cipher1Buf = Buffer.fromArray[Byte](cipher1)
                    val out1 =
                        try harness.callFeedAndDecrypt(engine, cipher1Buf, cipher1.length, handle)
                        finally cipher1Buf.close()
                    assert(out1.toList == (a1 ++ a2).toList, s"first decode mismatch: got ${out1.toList}")
                    val accAfterFirst = handle.decryptAcc.getOrElse(fail("decryptAcc must be Present after a multi-record decode"))

                    // Second multi-record decode must reuse the same accumulator instance.
                    val b1         = Array.tabulate[Byte](12)(i => (i + 100).toByte)
                    val b2         = Array.tabulate[Byte](8)(i => (i + 120).toByte)
                    val cipher2    = encryptRecords(client, Seq(b1, b2))
                    val cipher2Buf = Buffer.fromArray[Byte](cipher2)
                    val out2 =
                        try harness.callFeedAndDecrypt(engine, cipher2Buf, cipher2.length, handle)
                        finally cipher2Buf.close()
                    assert(out2.toList == (b1 ++ b2).toList, s"second decode mismatch: got ${out2.toList}")
                    val accAfterSecond = handle.decryptAcc.getOrElse(fail("decryptAcc must still be Present after the second decode"))

                    assert(
                        accAfterFirst eq accAfterSecond,
                        s"decryptAcc was re-allocated across decodes: first=$accAfterFirst, second=$accAfterSecond (allocated once per handle)"
                    )
                }
            }
        }

        // Anti-flakiness: a single record decodes through the fast path inline; the handshake completes synchronously in memory. No async.
        "feedAndDecrypt single-record fast path returns the bytes without allocating the accumulator" in {
            TlsRealEngines.assumeTlsReady()
            Sync.defer {
                TlsRealEngines.withEngines { (client, server) =>
                    assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")
                    val harness = new TlsEngineIoHarness
                    val engine  = RecordingTlsEngine(server)
                    val handle  = PosixHandle.socket(101, PosixHandle.DefaultReadBufferSize, Absent)

                    val record    = Array.tabulate[Byte](40)(i => (i + 1).toByte)
                    val cipher    = encryptRecords(client, Seq(record))
                    val cipherBuf = Buffer.fromArray[Byte](cipher)
                    val out =
                        try harness.callFeedAndDecrypt(engine, cipherBuf, cipher.length, handle)
                        finally cipherBuf.close()

                    assert(out.toList == record.toList, s"single-record decode mismatch: got ${out.toList}")
                    assert(
                        handle.decryptAcc.isEmpty,
                        s"single-record fast path must not allocate decryptAcc, got ${handle.decryptAcc}"
                    )
                }
            }
        }
    }

end TlsEngineIoTest
