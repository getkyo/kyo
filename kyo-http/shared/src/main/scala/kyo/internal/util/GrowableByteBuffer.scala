package kyo.internal.util

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

    /** Ensures the internal array can hold at least `needed` additional bytes. */
    private def ensureCapacity(needed: Int): Unit =
        val required = pos + needed
        if required > arr.length then
            @tailrec def grow(newLen: Int): Int = if newLen < required then grow(newLen * 2) else newLen
            val newArr                          = new Array[Byte](grow(arr.length))
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
end GrowableByteBuffer
