package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.util.GrowableByteBuffer

/** The TLS encrypt/decrypt steps shared by the readiness driver ([[PollerIoDriver]]) and the completion driver ([[IoUringDriver]]).
  *
  * Both drivers run the SAME engine-touching logic for a TLS connection: encrypt outbound plaintext through `writePlain` + `drainCiphertext`,
  * and decrypt inbound ciphertext through `feedCiphertext` + `readPlain`. What differs is only how the resulting ciphertext reaches the wire
  * (the poller sends inline and re-arms writability on backpressure; io_uring submits a send SQE and re-submits one on backpressure). Lifting
  * the engine steps here keeps the two drivers in lockstep: a future change to the encrypt or decrypt path touches one place, and the
  * driver-specific send mechanism is supplied by the caller.
  *
  * Every method here is an in-memory engine operation under `(using AllowUnsafe)`; there is no syscall to suspend on. The caller MUST run
  * these on its per-driver engine FIFO worker (via `submitEngineOp`), so the engine is touched by exactly one carrier at a time (the TLS engine
  * serialization invariant); this trait does not enforce the FIFO, it assumes the caller already routed the call onto it.
  */
private[posix] trait TlsEngineIo:

    /** Encrypt the whole `data` plaintext through `engine`, handing each drained ciphertext chunk to `appendChunk`.
      *
      * A single `writePlain` does not necessarily consume the whole plaintext (the JDK SSLEngine.wrap arm encrypts one record per call), so this
      * loops `writePlain` until every byte is consumed, draining each call's ciphertext and handing it to `appendChunk`. The caller decides what
      * to do with each chunk (the poller appends it to the pending tail, io_uring accumulates it for one send SQE). Returns `true` when every
      * byte was encrypted, `false` when the engine could not make progress (a non-positive `writePlain`: a fatal error or no plaintext accepted,
      * which post-handshake should not happen for application data).
      *
      * Allocation notes: the per-handle [[PosixHandle.plaintextStaging]] buffer is reused across records (one off-heap alloc for the driver
      * lifetime, not per-record). The per-handle [[PosixHandle.encryptDrain]] buffer is reused across records (one off-heap alloc for the
      * driver lifetime, not per-record). The `appendChunk` callback receives `(drain, len)` directly (no intermediate heap array). The per-
      * record suffix copy that advances the staging window is bounded by the TLS record size (typically 16 KB); this is one copy per record
      * instead of two (the previous code did a copyOfRange + fromArray per record; the new code does an element-wise copy of the suffix into
      * the reused staging buffer).
      */
    private[posix] def encryptPlaintext(
        handle: PosixHandle,
        data: Span[Byte],
        engine: TlsEngine
    )(appendChunk: (Buffer[Byte], Int) => Unit)(using
        AllowUnsafe
    ): Boolean =
        val arr     = data.toArrayUnsafe
        val staging = plaintextStagingFor(handle, arr.length)
        // Copy the whole plaintext once into staging (one alloc for the whole write, not per-record).
        var si = 0
        while si < arr.length do
            staging.set(si, arr(si))
            si += 1
        end while
        val drain  = encryptDrainFor(handle) // reused drain buffer (one alloc for driver lifetime, not per-record)
        var offset = 0
        var ok     = true
        while offset < arr.length && ok do
            val remainingLen = arr.length - offset
            // Advance staging content to the current offset by re-copying the suffix element-wise.
            // Bounded by <= record_size per call; the previous code did two copies per record (copyOfRange + fromArray).
            if offset > 0 then
                var ri = 0
                while ri < remainingLen do
                    staging.set(ri, arr(offset + ri))
                    ri += 1
                end while
            end if
            val consumed = engine.writePlain(staging, remainingLen)
            if consumed <= 0 then
                // < 0 is a fatal engine error; 0 means no plaintext accepted (want-read/want-write), which post-handshake should
                // not happen for application data. Cannot make progress; stop the loop.
                ok = false
            else
                offset += consumed
                var more = true
                while more do
                    val n = engine.drainCiphertext(drain, handle.readBufferSize)
                    if n <= 0 then more = false
                    else appendChunk(drain, n) // pass drain + len directly; no intermediate heap Array
                end while
            end if
        end while
        ok
    end encryptPlaintext

    /** Return the per-handle plaintextStaging Buffer, grown to at least `size` bytes if needed. FIFO-worker-owned.
      * Lazily allocated on the first TLS write; grown on demand (never shrunk). When growth is needed, the old buffer is closed before the new
      * one is allocated to prevent a native-memory leak (off-heap buffers are not GC-collected). Owned by the engine FIFO worker (single
      * owner): no cross-fiber synchronization is needed beyond the engine-op FIFO's sequencing.
      */
    private[posix] def plaintextStagingFor(handle: PosixHandle, size: Int)(using AllowUnsafe): Buffer[Byte] =
        handle.plaintextStaging match
            case Present(buf) if buf.size >= size => buf
            case _ =>
                handle.plaintextStaging.foreach(_.close())
                val buf = Buffer.alloc[Byte](size)
                handle.plaintextStaging = Present(buf)
                buf
    end plaintextStagingFor

    /** Return the per-handle encryptDrain Buffer, lazily allocated on the first TLS write. FIFO-worker-owned.
      * Sized to readBufferSize (the maximum one drainCiphertext call can produce). No growth is needed. Owned by the engine FIFO worker
      * (single owner): no cross-fiber synchronization is needed beyond the engine-op FIFO's sequencing.
      */
    private[posix] def encryptDrainFor(handle: PosixHandle)(using AllowUnsafe): Buffer[Byte] =
        handle.encryptDrain match
            case Present(buf) => buf
            case Absent =>
                val buf = Buffer.alloc[Byte](handle.readBufferSize)
                handle.encryptDrain = Present(buf)
                buf
    end encryptDrainFor

    /** Feed `len` bytes of inbound ciphertext from `cipher` to `engine`, decrypt every buffered application byte, then drain any ciphertext the
      * engine queued on its write side while decrypting (a TLS 1.3 KeyUpdate response, a TLS 1.2 renegotiation flight) so it reaches the wire.
      * Returns the decrypted plaintext (possibly empty when only handshake / partial-record bytes were consumed). The `cipher` buffer is owned by
      * the caller. The `handle` parameter provides access to per-handle reused drain and accumulator buffers (FIFO-worker-owned).
      *
      * Fatal record handling (RFC 5246 §7.2.2): when a fatal record-layer error (`readPlain == -2`, e.g. a bad-MAC / tampered record) is
      * reached, the stream is invalid and the connection MUST terminate. The accumulated good-prefix bytes are discarded (per the RFC, all data
      * is dropped on a fatal alert) and the handle is torn down via [[PosixHandle.requestClose]], so the fatal is never swallowed behind valid
      * application data: the driver's read continuation observes the closing handle and fails the read with `Closed` rather than re-arming.
      *
      * Read-side drain: a `readPlain` can produce outbound ciphertext that must be sent (OpenSSL/BoringSSL `SSL_read` WANT_WRITE; a TLS 1.3
      * KeyUpdate response queued during the read). After decrypting, this drains the engine's write side onto the same pending-ciphertext tail
      * ([[PosixHandle.pendingCipher]]) the write path appends to ([[encryptDrain]] reused as the drain landing buffer), so the driver's existing
      * flush machinery sends it. Both the decrypt and the drain run on the engine FIFO worker (feedAndDecrypt is called inside submitEngineOp),
      * so the single owner of the tail is preserved.
      */
    private[posix] def feedAndDecrypt(engine: TlsEngine, cipher: Buffer[Byte], len: Int, handle: PosixHandle)(using
        AllowUnsafe
    ): Array[Byte] =
        discard(engine.feedCiphertext(cipher, len))
        val decrypted = decryptInbound(engine, handle)
        if decrypted eq TlsEngineIo.FatalRecord then
            // Fatal record reached behind (possibly) good data: terminate the connection so the error is not lost. Skip the read-side drain;
            // the stream is being torn down. requestClose defers the resource free behind the in-flight read guard (poller) or routes the
            // engine free through the FIFO (both drivers), so it never races this in-flight decrypt.
            handle.requestClose()
            Array.emptyByteArray
        else
            drainReadProducedCiphertext(engine, handle)
            decrypted
        end if
    end feedAndDecrypt

    /** Decrypt every buffered application byte the engine can produce, distinguishing a clean drain from a fatal record-layer error.
      *
      * Returns the decrypted plaintext on a clean decode, or the [[TlsEngineIo.FatalRecord]] sentinel (compared by
      * identity, never delivered as data) when any `readPlain` returns `-2` (fatal). A `-2` reached AFTER one or more good records (the bad-MAC /
      * tampered record coalesced behind valid application data) discards the accumulated good prefix and returns the sentinel: per RFC 5246
      * §7.2.2 a fatal alert terminates the connection and remaining data is dropped, so the good prefix must NOT be delivered while the fatal is
      * silently swallowed. The single-record fast path and the multi-record loop both check `-2` explicitly so a fatal in either position is
      * surfaced rather than collapsed into want-read (`0`).
      *
      * Clean-close handling (RFC 8446 6.1): a `readPlain == -3` (the peer's close_notify was consumed) sets [[PosixHandle.peerCleanClose]] so
      * the connection's `closeReason` can tell an orderly close from a bare-FIN truncation. The `-3` is observable in any read position (the
      * close_notify can arrive alone or coalesced behind the last data record); whichever call sees it sets the flag and stops the drain, after
      * the already-decoded good prefix is delivered (the close_notify is the last thing on the stream, so there is no further app data to lose).
      *
      * Owned by the engine FIFO worker (called from feedAndDecrypt inside submitEngineOp); reuses the per-handle drain buffer and accumulator.
      */
    private def decryptInbound(engine: TlsEngine, handle: PosixHandle)(using AllowUnsafe): Array[Byte] =
        val chunkSize = handle.readBufferSize
        val out       = drainFor(handle)
        val n0        = engine.readPlain(out, chunkSize)
        if n0 == -2 then TlsEngineIo.FatalRecord
        else if n0 == -3 then
            handle.peerCleanClose = true
            Array.emptyByteArray
        else if n0 <= 0 then Array.emptyByteArray
        else
            val first = Buffer.copyToArray[Byte](out, 0, n0)
            val n1    = engine.readPlain(out, chunkSize)
            if n1 == -2 then TlsEngineIo.FatalRecord
            else if n1 == -3 then
                handle.peerCleanClose = true
                first
            else if n1 <= 0 then first
            else
                val acc = accFor(handle)
                acc.reset()
                acc.writeBytes(first, 0, first.length)
                acc.writeFromBuffer(out, n1)
                var more  = true
                var fatal = false
                while more do
                    val nk = engine.readPlain(out, chunkSize)
                    if nk > 0 then acc.writeFromBuffer(out, nk)
                    else
                        if nk == -2 then fatal = true
                        else if nk == -3 then handle.peerCleanClose = true
                        more = false
                    end if
                end while
                if fatal then TlsEngineIo.FatalRecord else acc.toByteArray
            end if
        end if
    end decryptInbound

    /** Drain the ciphertext the engine queued on its write side during the inbound decode and append it to the handle's pending-ciphertext tail
      * so the driver's flush machinery sends it. FIFO-worker-owned (called from feedAndDecrypt inside submitEngineOp).
      *
      * A `readPlain` can produce outbound ciphertext the peer is waiting for: a TLS 1.3 post-handshake KeyUpdate with `update_requested` queues
      * a KeyUpdate response, a TLS 1.2 HelloRequest drives a renegotiation flight. Without draining the write side after the read, that ciphertext
      * sits in the engine and is never sent, so the peer stalls. This mirrors the write path's drain loop ([[encryptPlaintext]]): it loops
      * `drainCiphertext` into the reused [[encryptDrain]] buffer and appends each chunk onto [[PosixHandle.pendingCipher]] (the same backpressure
      * tail the write path's append uses), extending the unsent region the driver's next flush sends. The common case (a read that produces no
      * outbound ciphertext) drains nothing on the first call and allocates no tail.
      */
    private def drainReadProducedCiphertext(engine: TlsEngine, handle: PosixHandle)(using AllowUnsafe): Unit =
        val drain = encryptDrainFor(handle)
        var more  = true
        while more do
            val n = engine.drainCiphertext(drain, handle.readBufferSize)
            if n <= 0 then more = false
            else appendReadProducedCiphertext(handle, drain, n)
        end while
    end drainReadProducedCiphertext

    /** Append `len` bytes of read-produced ciphertext from the reused drain buffer onto the handle's pending-ciphertext tail, lazily allocating
      * the tail on the first append. FIFO-worker-owned: the engine FIFO serializes every read and write engine op for this connection, so the
      * tail (also appended by the write path) is never mutated concurrently. Mirrors the driver write path's append onto the same field.
      */
    private def appendReadProducedCiphertext(handle: PosixHandle, drain: Buffer[Byte], len: Int): Unit =
        val buf =
            handle.pendingCipher match
                case Present(b) => b
                case Absent =>
                    val b = new GrowableByteBuffer
                    handle.pendingCipher = Present(b)
                    b
        buf.writeFromBuffer(drain, len)
    end appendReadProducedCiphertext

    /** Return the per-handle decryptDrain Buffer, lazily allocated on the first TLS read. Owned by the engine FIFO worker: only called from
      * the inbound decode, which runs inside submitEngineOp, so only one carrier touches this field at a time.
      */
    private[posix] def drainFor(handle: PosixHandle)(using AllowUnsafe): Buffer[Byte] =
        handle.decryptDrain match
            case Present(buf) => buf
            case Absent =>
                val buf = Buffer.alloc[Byte](handle.readBufferSize)
                handle.decryptDrain = Present(buf)
                buf
    end drainFor

    /** Return the per-handle decryptAcc GrowableByteBuffer, lazily allocated on the first multi-record read. Owned by the engine FIFO worker:
      * only called from the multi-record inbound decode, which runs inside submitEngineOp. Not used on the single-record fast path.
      */
    private[posix] def accFor(handle: PosixHandle): GrowableByteBuffer =
        handle.decryptAcc match
            case Present(acc) => acc
            case Absent =>
                val acc = new GrowableByteBuffer
                handle.decryptAcc = Present(acc)
                acc
    end accFor
end TlsEngineIo

private[posix] object TlsEngineIo:
    /** Identity sentinel returned by `decryptInbound` to mark a fatal record-layer error (`readPlain == -2`), distinct from a clean empty
      * decode (want-read / partial record). Compared with `eq` (reference identity) and never delivered as application data: it is an empty
      * array, so any accidental delivery would be harmless, but the identity check is what carries the fatal-vs-clean distinction. A single
      * shared immutable instance (an empty array is read-only), so the fast path allocates nothing extra.
      */
    val FatalRecord: Array[Byte] = new Array[Byte](0)
end TlsEngineIo
