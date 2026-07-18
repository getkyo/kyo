package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines

/** De-boxing witness: the [[ChunkConsumer]] single-abstract-method class on the TLS write encrypt path ([[TlsEngineIo.encryptPlaintext]]).
  *
  * The change queue (submitChange/dispatchCmd adapted long-lambda) was already de-boxed in the committed baseline (the change FIFO is a raw
  * `MpscLongQueue` carrying a primitive `long`); the one live numeric-boxing callback on the poller hot paths was the
  * `(Buffer[Byte], Int) => Unit` per-record callback in `encryptPlaintext`, which a `Function2[Buffer[Byte], Int, Unit]` would box the `Int`
  * length on per call (Scala 3 does not specialize Function2 on Int). It was converted to the `ChunkConsumer` SAM so the call-site lambda passes
  * the primitive `Int` with no box.
  *
  * The witness asserts the SAM is BEHAVIOR-PRESERVING: every drained ciphertext chunk is handed to the SAM with the correct `(buf, len)`, and the
  * concatenation of the delivered chunks is valid ciphertext that decrypts byte-for-byte back to the original plaintext through a real peer engine.
  * The `len` is read as a primitive `Int` directly from the SAM parameter (no `java.lang.Integer` unbox at the call site).
  */
class TlsEngineIoChunkConsumerTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** Minimal [[TlsEngineIo]] subclass exposing [[TlsEngineIo.encryptPlaintext]]; submitEngineOp runs inline (the test serializes engine access). */
    final private class Harness extends TlsEngineIo:
        def submitEngineOp(op: () => Unit)(using AllowUnsafe, Frame): Unit = op()
        def label: String                                                  = "ChunkConsumerHarness"
        def callEncrypt(handle: PosixHandle, data: Span[Byte], engine: TlsEngine)(append: ChunkConsumer)(using AllowUnsafe): Boolean =
            encryptPlaintext(handle, data, engine)(append)
    end Harness

    "TlsEngineIo ChunkConsumer SAM" - {

        "encryptPlaintext hands each ciphertext chunk to the SAM, and the chunks decrypt back to the plaintext" in {
            TlsRealEngines.assumeTlsReady()
            Sync.defer {
                TlsRealEngines.withEngines { (client, server) =>
                    assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")
                    val harness = new Harness
                    val handle  = PosixHandle.socket(77, PosixHandle.DefaultReadBufferSize, Absent)
                    val plain   = Array.tabulate[Byte](200)(i => (i & 0xff).toByte)

                    // Collect each ciphertext chunk the SAM receives. `len` arrives as a primitive Int parameter (no Integer box at the call site);
                    // we copy exactly `len` bytes out of the reused drain buffer to reconstruct the wire ciphertext.
                    val cipher     = new java.io.ByteArrayOutputStream
                    var chunkCount = 0
                    var totalLen   = 0
                    val collectChunk = new ChunkConsumer:
                        def apply(buf: Buffer[Byte], len: Int): Unit =
                            assert(len > 0, s"a delivered chunk must have a positive length, got $len")
                            chunkCount += 1
                            totalLen += len
                            cipher.write(Buffer.copyToArray[Byte](buf, 0, len))
                        end apply

                    val ok =
                        try harness.callEncrypt(handle, Span.fromUnsafe(plain), client)(collectChunk)
                        finally ()
                    assert(ok, "encryptPlaintext should consume the whole plaintext")
                    assert(chunkCount >= 1, "the SAM must receive at least one ciphertext chunk")
                    assert(totalLen == cipher.size, "the summed primitive lengths must equal the bytes collected")

                    // Behavior-preserving: feeding the collected ciphertext to the real peer (server) engine decrypts back to the exact plaintext.
                    val wire      = cipher.toByteArray
                    val decrypted = TlsEngineLoopback.decrypt(server, wire)
                    assert(decrypted.toList == plain.toList, s"SAM-delivered ciphertext must decrypt to the original plaintext")

                    // The buffer the SAM received is the per-handle reused encryptDrain (one off-heap alloc, not per-record).
                    handle.encryptDrain match
                        case Present(_) => ()
                        case Absent     => fail("handle.encryptDrain must be Present after encryptPlaintext")
                    end match
                }
            }.map(_ => succeed)
        }

        "a small single-record plaintext still routes through the SAM with the correct bytes" in {
            TlsRealEngines.assumeTlsReady()
            Sync.defer {
                TlsRealEngines.withEngines { (client, server) =>
                    assert(TlsEngineLoopback.handshake(client, server), "handshake did not complete")
                    val harness = new Harness
                    val handle  = PosixHandle.socket(78, PosixHandle.DefaultReadBufferSize, Absent)
                    val plain   = Array[Byte](1, 2, 3, 4, 5)
                    val cipher  = new java.io.ByteArrayOutputStream
                    val sam = new ChunkConsumer:
                        def apply(buf: Buffer[Byte], len: Int): Unit =
                            cipher.write(Buffer.copyToArray[Byte](buf, 0, len))
                    assert(harness.callEncrypt(handle, Span.fromUnsafe(plain), client)(sam))
                    val decrypted = TlsEngineLoopback.decrypt(server, cipher.toByteArray)
                    assert(decrypted.toList == plain.toList)
                }
            }.map(_ => succeed)
        }
    }
end TlsEngineIoChunkConsumerTest
