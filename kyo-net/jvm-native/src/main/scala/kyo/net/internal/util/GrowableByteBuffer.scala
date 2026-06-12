package kyo.net.internal.util

import scala.annotation.tailrec

/** A reusable growable byte buffer for connection-scoped serialization.
  *
  * Designed for zero-alloc reuse across HTTP requests on a single connection. The internal array grows as needed but is never shrunk, so
  * steady-state usage after warm-up allocates nothing. Only `toByteArray` allocates (a right-sized copy).
  */
final private[kyo] class GrowableByteBuffer:
    private var arr = new Array[Byte](512)
    private var pos = 0

    /** Resets the buffer position to zero without clearing or reallocating. */
    def reset(): Unit = pos = 0

    /** Current number of bytes written. */
    def size: Int = pos

    /** Ensures the internal array can hold at least `needed` additional bytes.
      *
      * The required capacity and the doubling-growth target are computed in `Long` arithmetic and capped at [[GrowableByteBuffer.MaxArrayLength]].
      * The naive `Int` doubling `newLen * 2` overflows for a large array: once `newLen` reaches `2^30` the next double is `2^31`, which wraps to a
      * negative `Int` (a `NegativeArraySizeException` from `new Array[Byte]`) and, doubling again, to `0` (an unterminating growth loop). Computing
      * in `Long` and capping keeps the target representable, and a request beyond the largest allocatable array fails fast with a clear, bounded
      * error rather than wrapping. `pos` is incremented by callers only after this returns, so a failed growth leaves the position unchanged.
      */
    private def ensureCapacity(needed: Int): Unit =
        val required = pos.toLong + needed.toLong
        if required > arr.length then
            if required > GrowableByteBuffer.MaxArrayLength then
                throw new IllegalArgumentException(
                    s"GrowableByteBuffer capacity request ($required bytes) exceeds the maximum array length ${GrowableByteBuffer.MaxArrayLength}"
                )
            end if
            @tailrec def grow(newLen: Long): Int =
                if newLen >= required then newLen.toInt
                else
                    val doubled = newLen * 2
                    grow(if doubled > GrowableByteBuffer.MaxArrayLength then GrowableByteBuffer.MaxArrayLength.toLong else doubled)
            val newArr = new Array[Byte](grow(arr.length.toLong))
            java.lang.System.arraycopy(arr, 0, newArr, 0, pos)
            arr = newArr
        end if
    end ensureCapacity

    /** Writes a single byte. */
    def writeByte(b: Byte): Unit =
        ensureCapacity(1)
        arr(pos) = b
        pos += 1
    end writeByte

    /** Writes `length` bytes from `src` starting at `offset`. */
    def writeBytes(src: Array[Byte], offset: Int, length: Int): Unit =
        ensureCapacity(length)
        java.lang.System.arraycopy(src, offset, arr, pos, length)
        pos += length
    end writeBytes

    /** Writes an ASCII string char-by-char, avoiding `getBytes` allocation.
      *
      * IMPORTANT: This method truncates chars > 127 to their low byte. It is only safe for content known to be ASCII: HTTP method names,
      * status lines, header names, and header values that are guaranteed ASCII per RFC 7230. For header values that may contain non-ASCII
      * octets, use `writeBytes` with `s.getBytes(StandardCharsets.UTF_8)` instead.
      */
    def writeAscii(s: String): Unit =
        ensureCapacity(s.length)
        @tailrec def loop(i: Int): Unit =
            if i < s.length then
                arr(pos) = s.charAt(i).toByte
                pos += 1
                loop(i + 1)
        loop(0)
    end writeAscii

    /** Writes a non-negative integer as ASCII decimal digits without String allocation. */
    def writeIntAscii(value: Int): Unit =
        if value == 0 then
            ensureCapacity(1)
            arr(pos) = '0'.toByte
            pos += 1
        else
            // Count digits
            @tailrec def countDigits(n: Int, count: Int): Int =
                if n == 0 then count else countDigits(n / 10, count + 1)
            val numDigits = countDigits(value, 0)
            ensureCapacity(numDigits)
            // Write digits right-to-left
            @tailrec def writeDigits(n: Int, i: Int): Unit =
                if n > 0 then
                    arr(i) = ('0' + (n % 10)).toByte
                    writeDigits(n / 10, i - 1)
            writeDigits(value, pos + numDigits - 1)
            pos += numDigits
        end if
    end writeIntAscii

    /** Returns a right-sized copy of the written bytes. 1 allocation. */
    def toByteArray: Array[Byte] =
        val result = new Array[Byte](pos)
        java.lang.System.arraycopy(arr, 0, result, 0, pos)
        result
    end toByteArray

    /** Copies the written bytes into `dest` starting at `destOffset`. */
    def copyTo(dest: Array[Byte], destOffset: Int): Unit =
        java.lang.System.arraycopy(arr, 0, dest, destOffset, pos)

    /** Raw access to the internal array for zero-copy reads. */
    def array: Array[Byte] = arr

    /** Ensures the internal array can hold at least `additional` bytes beyond the current position.
      * Must be called before reading `array` if growth may occur, so callers always get the current reference.
      */
    private[kyo] def ensureCapacityFor(additional: Int): Unit = ensureCapacity(additional)

    /** Advances the position by `n` bytes, recording that `n` bytes have been written directly into `array` at offset `size`. */
    private[kyo] def advance(n: Int): Unit = pos += n

    /** Copies `len` bytes from an off-heap Buffer[Byte] into the internal array at the current position, growing the array if needed and
      * advancing the position by `len`.
      *
      * The kyo-ffi Buffer API does not expose a bulk copyInto(dst: Array[Byte], dstOffset: Int, len: Int) primitive, so this method reads
      * element-wise directly into the internal array starting at `pos`, with no intermediate allocation. The loop is the same per-element
      * cost as Buffer.copyToArray internally (which also loops buf.get(from+i)), but without the temporary array or the subsequent
      * arraycopy, yielding strictly zero extra allocation per call.
      *
      * Used by decryptAll (multi-record path) and appendPending (TLS write backpressure).
      */
    def writeFromBuffer(src: kyo.ffi.Buffer[Byte], len: Int): Unit =
        ensureCapacity(len)
        var i = 0
        while i < len do
            arr(pos + i) = src.get(i)
            i += 1
        pos += len
    end writeFromBuffer
end GrowableByteBuffer

private[kyo] object GrowableByteBuffer:
    /** Largest length the internal array may grow to. Matches the JDK's effective array-size ceiling (`Int.MaxValue` minus a small header
      * allowance): some VMs reserve a few header words, so requesting exactly `Int.MaxValue` can fail with an `OutOfMemoryError` on the
      * allocation. Growth is capped here, and a capacity request above this fails fast with a clear, bounded error rather than overflowing the
      * doubling target to a negative or zero length.
      */
    final val MaxArrayLength = Int.MaxValue - 8
end GrowableByteBuffer
