package kyo.net.internal.util

import scala.annotation.tailrec

/** A reusable growable byte buffer for connection-scoped serialization.
  *
  * Designed for zero-alloc reuse across HTTP requests on a single connection. The internal array grows as needed but is never shrunk, so
  * steady-state usage after warm-up allocates nothing. Only `toByteArray` allocates (a right-sized copy).
  */
final private[kyo] class GrowableByteBuffer(initialCapacity: Int = 512):
    private var arr = new Array[Byte](math.max(1, initialCapacity))
    private var pos = 0

    /** Resets the buffer position to zero without clearing or reallocating. */
    def reset(): Unit = pos = 0

    /** Current number of bytes written. */
    def size: Int = pos

    /** Writes a single byte. */
    def writeByte(b: Byte): Unit =
        ensureCapacity(1)
        arr(pos) = b
        pos += 1
    end writeByte

    /** Writes `length` bytes from `src` starting at `offset`. */
    def writeBytes(src: Array[Byte], offset: Int, length: Int): Unit =
        ensureCapacity(length)
        // System.arraycopy: no kyo equivalent for a bulk primitive-array copy on this hot serialization path.
        java.lang.System.arraycopy(src, offset, arr, pos, length)
        pos += length
    end writeBytes

    /** Writes an ASCII string char-by-char, avoiding `getBytes` allocation.
      *
      * IMPORTANT: Every char must be ASCII (0x00-0x7F). A char above 0x7F has no single-byte encoding, so the whole string is rejected with an
      * `IllegalArgumentException` naming the offending char code and its index, and nothing is written. Use this only for content an ASCII
      * grammar already constrains: an HTTP method name, a status line, a reason phrase, a formatted date, or a field name (a token, which admits
      * only letters, digits and a few symbols). Content with no such constraint, an HTTP field value being the common case, is not ASCII-bound
      * and belongs on `writeBytes` with `s.getBytes(StandardCharsets.UTF_8)`. A caller that would rather refuse such content than encode it tests
      * it with [[GrowableByteBuffer.isAscii]] and takes its own failure path.
      *
      * This is an encodability precondition, not a validity check: it says nothing about which characters a given grammar permits. CR, LF and
      * NUL are all ASCII and all pass, so a caller writing content that lands on a wire where a line break is structural validates that itself.
      */
    def writeAscii(s: String): Unit =
        // The whole string is checked before any byte is written: `toByte` alone would narrow a char above 0x7F to its low byte and put a
        // corrupted octet on the wire, and checking each char inside the write loop would leave the chars before the offending one written.
        val bad = GrowableByteBuffer.firstNonAscii(s)
        require(bad < 0, s"writeAscii requires ASCII (0x00-0x7F); got char code ${s.charAt(bad).toInt} at index $bad")
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
        // System.arraycopy: no kyo equivalent for a bulk primitive-array copy on this hot serialization path.
        java.lang.System.arraycopy(arr, 0, result, 0, pos)
        result
    end toByteArray

    /** Copies the written bytes into `dest` starting at `destOffset`. */
    def copyTo(dest: Array[Byte], destOffset: Int): Unit =
        // System.arraycopy: no kyo equivalent for a bulk primitive-array copy on this hot serialization path.
        java.lang.System.arraycopy(arr, 0, dest, destOffset, pos)

    /** Raw access to the internal array for zero-copy reads. */
    def array: Array[Byte] = arr

    /** Ensures the internal array can hold at least `needed` additional bytes.
      *
      * The required capacity and the doubling-growth target are computed in `Long` arithmetic and capped at [[GrowableByteBuffer.MaxArrayLength]].
      * The naive `Int` doubling `newLen * 2` overflows for a large array: once `newLen` reaches `2^30` the next double is `2^31`, which wraps to a
      * negative `Int` (a `NegativeArraySizeException` from `new Array[Byte]`) and, doubling again, to `0` (an unterminating growth loop). Computing
      * in `Long` and capping keeps the target representable, and a request beyond the largest allocatable array fails fast with a clear, bounded
      * error rather than wrapping. `pos` is incremented by callers only after this returns, so a failed growth leaves the position unchanged.
      */
    private def ensureCapacity(needed: Int): Unit =
        // Long arithmetic is intentional: pos + needed can exceed Int.MaxValue for a buffer approaching the array-length ceiling, and the
        // doubling target below would overflow to a negative or zero Int without it (see MaxArrayLength). The buffer stays Int-sized: the
        // Long is intermediate arithmetic, capped back to MaxArrayLength, not a Long-addressable capacity.
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
            // System.arraycopy: no kyo equivalent for a bulk primitive-array copy on this hot serialization path.
            java.lang.System.arraycopy(arr, 0, newArr, 0, pos)
            arr = newArr
        end if
    end ensureCapacity

    /** Ensures the internal array can hold at least `additional` bytes beyond the current position.
      * Must be called before reading `array` if growth may occur, so callers always get the current reference.
      */
    private[kyo] def ensureCapacityFor(additional: Int): Unit = ensureCapacity(additional)

    /** Advances the position by `n` bytes, recording that `n` bytes have been written directly into `array` at offset `size`. */
    private[kyo] def advance(n: Int): Unit = pos += n
end GrowableByteBuffer

private[kyo] object GrowableByteBuffer:
    /** Whether every char of `s` is ASCII (0x00-0x7F), and `s` can therefore be written by `writeAscii`.
      *
      * A plain predicate over the string, so a caller holding content that may not be ASCII (a host name, a request path) can test it and take
      * its own failure path instead of relying on `writeAscii`'s precondition. `writeAscii` tests the same way, so a string this returns true
      * for always writes and a string it returns false for always rejects.
      *
      * It answers whether `s` is encodable one byte per char, and nothing more. It is not a validity test for any grammar: CR, LF, NUL and DEL
      * are ASCII and this returns true for a string carrying them.
      */
    def isAscii(s: String): Boolean = firstNonAscii(s) < 0

    /** Index of the first char of `s` above 0x7F, or -1 when every char is ASCII. */
    private def firstNonAscii(s: String): Int =
        @tailrec def loop(i: Int): Int =
            if i >= s.length then -1
            else if s.charAt(i) > 0x7f then i
            else loop(i + 1)
        loop(0)
    end firstNonAscii

    /** Largest length the internal array may grow to. Matches the JDK's effective array-size ceiling (`Int.MaxValue` minus a small header
      * allowance): some VMs reserve a few header words, so requesting exactly `Int.MaxValue` can fail with an `OutOfMemoryError` on the
      * allocation. Growth is capped here, and a capacity request above this fails fast with a clear, bounded error rather than overflowing the
      * doubling target to a negative or zero length.
      */
    final val MaxArrayLength = Int.MaxValue - 8
end GrowableByteBuffer
