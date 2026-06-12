package kyo.net.internal.util

import kyo.*
import kyo.net.Test

class GrowableByteBufferTest extends Test:

    import AllowUnsafe.embrace.danger

    "GrowableByteBuffer" - {

        // Test 1: Write single byte. writeByte increments pos and stores the byte correctly.
        "write single byte" in {
            val buf = new GrowableByteBuffer()
            buf.writeByte(42.toByte)
            assert(buf.size == 1)
            assert(buf.array(0) == 42.toByte)
            succeed
        }

        // Test 2: Write multiple bytes sequentially. writeBytes copies from source and increments pos by length.
        "write multiple bytes sequentially" in {
            val buf = new GrowableByteBuffer()
            val src = Array[Byte](1, 2, 3, 4, 5)
            buf.writeBytes(src, 0, src.length)
            assert(buf.size == 5)
            val arr = buf.array
            assert(arr(0) == 1.toByte)
            assert(arr(1) == 2.toByte)
            assert(arr(2) == 3.toByte)
            assert(arr(3) == 4.toByte)
            assert(arr(4) == 5.toByte)
            succeed
        }

        // Test 3: Reset clears position. reset() sets pos=0; next write starts at 0.
        "reset clears position" in {
            val buf = new GrowableByteBuffer()
            buf.writeByte(10.toByte)
            buf.writeByte(20.toByte)
            assert(buf.size == 2)
            buf.reset()
            assert(buf.size == 0)
            buf.writeByte(99.toByte)
            assert(buf.size == 1)
            assert(buf.array(0) == 99.toByte)
            succeed
        }

        // Test 4: Size returns current position. size property equals pos after writes.
        "size returns current position" in {
            val buf = new GrowableByteBuffer()
            assert(buf.size == 0)
            buf.writeByte(1.toByte)
            assert(buf.size == 1)
            buf.writeByte(2.toByte)
            assert(buf.size == 2)
            val src = Array[Byte](3, 4, 5)
            buf.writeBytes(src, 0, 3)
            assert(buf.size == 5)
            succeed
        }

        // Test 5: Capacity expansion triggers at boundary. Writes until pos + needed >= arr.length; array doubles.
        "capacity expansion triggers at boundary" in {
            val buf = new GrowableByteBuffer()
            // Initial capacity is 512; fill it up completely
            val chunk = new Array[Byte](512)
            java.util.Arrays.fill(chunk, 7.toByte)
            buf.writeBytes(chunk, 0, 512)
            assert(buf.size == 512)
            // The internal array length should be exactly 512 right now
            assert(buf.array.length == 512)
            // One more byte must trigger growth
            buf.writeByte(99.toByte)
            assert(buf.size == 513)
            // After growth the array must be larger than 512
            assert(buf.array.length > 512)
            // The previously written data must still be intact
            val arr = buf.array
            assert(arr(0) == 7.toByte)
            assert(arr(511) == 7.toByte)
            assert(arr(512) == 99.toByte)
            succeed
        }

        // Test 6: Zero-copy access via array property. array returns internal ref; modification is visible without copying.
        "zero-copy access via array property" in {
            val buf = new GrowableByteBuffer()
            buf.writeByte(55.toByte)
            val ref = buf.array
            // Mutate through the returned reference
            ref(0) = 77.toByte
            // The mutation must be visible via array without any copy
            assert(buf.array(0) == 77.toByte)
            succeed
        }

        // Test 7: toByteArray returns right-sized copy. Allocates Array[Byte](pos) and copies [0..pos).
        "toByteArray returns right-sized copy" in {
            val buf = new GrowableByteBuffer()
            buf.writeByte(1.toByte)
            buf.writeByte(2.toByte)
            buf.writeByte(3.toByte)
            val copy = buf.toByteArray
            assert(copy.length == 3)
            assert(copy(0) == 1.toByte)
            assert(copy(1) == 2.toByte)
            assert(copy(2) == 3.toByte)
            // Must be a distinct object (a copy, not the internal array)
            assert(!(copy eq buf.array))
            succeed
        }

        // Test 8: copyTo writes to destination offset. copyTo(dest, destOffset) copies [0..pos) to dest.
        "copyTo writes to destination offset" in {
            val buf = new GrowableByteBuffer()
            buf.writeByte(10.toByte)
            buf.writeByte(20.toByte)
            buf.writeByte(30.toByte)
            val dest = new Array[Byte](10)
            buf.copyTo(dest, 4)
            // Bytes before offset 4 must remain 0
            assert(dest(0) == 0.toByte)
            assert(dest(3) == 0.toByte)
            // Written bytes start at offset 4
            assert(dest(4) == 10.toByte)
            assert(dest(5) == 20.toByte)
            assert(dest(6) == 30.toByte)
            // Bytes after the copied region must remain 0
            assert(dest(7) == 0.toByte)
            succeed
        }

        // Test 9: Write ASCII string "HTTP/1.1". writeAscii iterates char-by-char and stores char.toByte.
        "write ASCII string HTTP/1.1" in {
            val buf = new GrowableByteBuffer()
            buf.writeAscii("HTTP/1.1")
            assert(buf.size == 8)
            val arr = buf.array
            assert(arr(0) == 'H'.toByte)
            assert(arr(1) == 'T'.toByte)
            assert(arr(2) == 'T'.toByte)
            assert(arr(3) == 'P'.toByte)
            assert(arr(4) == '/'.toByte)
            assert(arr(5) == '1'.toByte)
            assert(arr(6) == '.'.toByte)
            assert(arr(7) == '1'.toByte)
            succeed
        }

        // Test 10: Write ASCII with all ASCII chars (0-127). All encode correctly.
        "write ASCII with all ASCII chars 0 to 127" in {
            val buf      = new GrowableByteBuffer()
            val allAscii = (0 until 128).map(_.toChar).mkString
            buf.writeAscii(allAscii)
            assert(buf.size == 128)
            val arr = buf.array
            for i <- 0 until 128 do
                assert(arr(i) == i.toByte, s"Mismatch at index $i: expected ${i.toByte} got ${arr(i)}")
            succeed
        }

        // Test 11: Write non-ASCII char (é = 233). Stores low byte; no crash.
        "write non-ASCII char stores low byte" in {
            val buf = new GrowableByteBuffer()
            // 'é' is char 233 (U+00E9); char.toByte stores the low byte (233.toByte = -23)
            buf.writeAscii("é")
            assert(buf.size == 1)
            assert(buf.array(0) == 233.toByte)
            succeed
        }

        // Test 12: Write integer 0. writeIntAscii(0) special case writes single '0' byte.
        "write integer 0" in {
            val buf = new GrowableByteBuffer()
            buf.writeIntAscii(0)
            assert(buf.size == 1)
            assert(buf.array(0) == '0'.toByte)
            succeed
        }

        // Test 13: Write integer 12345. writeIntAscii(12345) writes "12345" right-to-left.
        "write integer 12345" in {
            val buf = new GrowableByteBuffer()
            buf.writeIntAscii(12345)
            assert(buf.size == 5)
            val arr = buf.array
            assert(arr(0) == '1'.toByte)
            assert(arr(1) == '2'.toByte)
            assert(arr(2) == '3'.toByte)
            assert(arr(3) == '4'.toByte)
            assert(arr(4) == '5'.toByte)
            succeed
        }

        // Test 14: Write integer 1 (single digit). Writes "1" and increments pos by 1.
        "write integer 1 single digit" in {
            val buf = new GrowableByteBuffer()
            buf.writeIntAscii(1)
            assert(buf.size == 1)
            assert(buf.array(0) == '1'.toByte)
            succeed
        }

        // Test 15: Write negative integer (undefined behavior). Should not crash or hang.
        "write negative integer does not crash" in {
            val buf = new GrowableByteBuffer()
            // Behavior is undefined for negatives, but must not crash or hang
            try
                buf.writeIntAscii(-42)
                // If it doesn't throw, that is acceptable; just verify we can read size
                assert(buf.size >= 0)
            catch
                case _: Exception =>
                    // Throwing is also acceptable for undefined input
                    ()
            end try
            succeed
        }

        // Test 16: Reuse buffer after reset. reset(), then write again; data overwrites from position 0.
        "reuse buffer after reset" in {
            val buf = new GrowableByteBuffer()
            buf.writeAscii("Hello")
            assert(buf.size == 5)
            buf.reset()
            assert(buf.size == 0)
            buf.writeAscii("World")
            assert(buf.size == 5)
            val copy = buf.toByteArray
            assert(new String(copy, java.nio.charset.StandardCharsets.US_ASCII) == "World")
            succeed
        }

        // Test 17: Multiple growth cycles. Write 512 bytes, then 512 more (grow to 1024), then 1024 more (grow to 2048).
        "multiple growth cycles" in {
            val buf  = new GrowableByteBuffer()
            val fill = new Array[Byte](512)
            java.util.Arrays.fill(fill, 1.toByte)
            // First batch: 512 bytes; fills initial capacity exactly
            buf.writeBytes(fill, 0, 512)
            assert(buf.size == 512)
            // Second batch: 512 more; forces first growth (to 1024)
            buf.writeBytes(fill, 0, 512)
            assert(buf.size == 1024)
            assert(buf.array.length >= 1024)
            // Third batch: 1024 more; forces second growth (to 2048)
            val fill2 = new Array[Byte](1024)
            java.util.Arrays.fill(fill2, 2.toByte)
            buf.writeBytes(fill2, 0, 1024)
            assert(buf.size == 2048)
            assert(buf.array.length >= 2048)
            // Spot-check data integrity
            val arr = buf.array
            assert(arr(0) == 1.toByte)
            assert(arr(511) == 1.toByte)
            assert(arr(512) == 1.toByte)
            assert(arr(1023) == 1.toByte)
            assert(arr(1024) == 2.toByte)
            assert(arr(2047) == 2.toByte)
            succeed
        }

        // Test 18: Write at capacity boundary. ensureCapacity(1) when arr.length=pos triggers growth.
        "write at capacity boundary triggers growth" in {
            val buf = new GrowableByteBuffer()
            // Fill exactly to the initial capacity (512 bytes)
            val data = new Array[Byte](512)
            java.util.Arrays.fill(data, 33.toByte)
            buf.writeBytes(data, 0, 512)
            assert(buf.size == 512)
            val arrayBefore = buf.array
            assert(arrayBefore.length == 512)
            // One single-byte write at pos == arr.length must trigger ensureCapacity growth
            buf.writeByte(44.toByte)
            val arrayAfter = buf.array
            // The internal array must have been replaced with a larger one
            assert(arrayAfter.length > 512)
            assert(buf.size == 513)
            // All prior data must have been copied over
            for i <- 0 until 512 do
                assert(arrayAfter(i) == 33.toByte, s"Data lost after growth at index $i")
            assert(arrayAfter(512) == 44.toByte)
            succeed
        }

        // The doubling-on-grow growth strategy can overflow Int to a negative array length (CWE-190). The pre-fix code doubled in Int arithmetic
        // (`grow(newLen * 2)`): once the doubling target reaches 2^30 the next double is 2^31, which wraps to Int.MinValue and feeds a negative
        // length to `new Array[Byte](...)` (a NegativeArraySizeException), or, doubling again, to 0 (a non-terminating growth loop / silent
        // under-allocation). The fix computes the required capacity and the doubling target in Long and caps both at MaxArrayLength. The guard's
        // contract: any request STRICTLY ABOVE MaxArrayLength fails fast with a clear, bounded IllegalArgumentException and NEVER asks the runtime
        // for a negative or undersized array; the failure is in the length computation, BEFORE any large allocation, so these leaves cost nothing.

        // Test 19: a capacity request strictly above MaxArrayLength fails with a clear bounded error, not a NegativeArraySizeException, and without
        // allocating. Int.MaxValue == MaxArrayLength + 8 > MaxArrayLength, so `required > MaxArrayLength` trips the guard up front (no doubling loop,
        // no allocation). The pre-fix Int doubling would instead wrap the length negative.
        "capacity request above the max array length fails bounded, never negative array length" in {
            val buf = new GrowableByteBuffer()
            val thrown =
                try
                    buf.ensureCapacityFor(Int.MaxValue)
                    None
                catch
                    case t: Throwable => Some(t)
            // The guard must surface a clear, bounded failure (not a NegativeArraySizeException from a wrapped length, and not a silent
            // under-allocation that leaves the array smaller than requested).
            assert(thrown.isDefined, "a capacity request above the max array length must fail rather than wrap or under-allocate")
            assert(
                !thrown.get.isInstanceOf[NegativeArraySizeException],
                s"growth must not produce a negative array length, got ${thrown.get}"
            )
            assert(
                thrown.get.isInstanceOf[IllegalArgumentException],
                s"an over-cap request must fail with a clear bounded IllegalArgumentException, got ${thrown.get}"
            )
            // The size accounting must be untouched by the failed growth (no partial advance).
            assert(buf.size == 0, "a failed growth must not advance the position")
            succeed
        }

        // Test 20: a non-zero position plus a request whose SUM exceeds MaxArrayLength still fails bounded: the guard sums `pos + needed` in Long, so
        // `pos` (here a few bytes already written) plus a `needed` of MaxArrayLength overflows the cap and trips the guard, with no negative length and
        // no allocation. This pins that the cap is on the SUM (the real array size after the write), not on `needed` alone.
        "position plus request exceeding the max array length fails bounded, never negative array length" in {
            val buf = new GrowableByteBuffer()
            buf.writeBytes(Array[Byte](1, 2, 3, 4), 0, 4) // pos = 4
            val thrown =
                try
                    buf.ensureCapacityFor(GrowableByteBuffer.MaxArrayLength) // 4 + MaxArrayLength > MaxArrayLength
                    None
                catch case t: Throwable => Some(t)
            assert(thrown.isDefined, "pos + needed exceeding the max array length must fail rather than wrap or under-allocate")
            assert(
                !thrown.get.isInstanceOf[NegativeArraySizeException],
                s"growth must not produce a negative array length, got ${thrown.get}"
            )
            assert(
                thrown.get.isInstanceOf[IllegalArgumentException],
                s"an over-cap request must fail with a clear bounded IllegalArgumentException, got ${thrown.get}"
            )
            assert(buf.size == 4, "a failed growth must not advance the position")
            succeed
        }

        // Test 21: a legitimate large-but-safe growth (just over the previous power-of-two boundary, well under the cap) still succeeds and
        // preserves prior data. This pins that the overflow guard does NOT reject ordinary growth: it only caps the unrepresentable case.
        "legitimate growth just over a power-of-two boundary still succeeds and preserves data" in {
            val buf  = new GrowableByteBuffer()
            val seed = Array[Byte](1, 2, 3, 4)
            buf.writeBytes(seed, 0, 4)
            // Grow to ~1 MB: a real, representable, well-under-cap target. The doubling loop reaches it without overflow.
            buf.ensureCapacityFor((1 << 20) + 5)
            assert(buf.array.length >= 4 + (1 << 20) + 5, "the array must be grown to cover the requested capacity")
            // Prior data must be intact after the grow-copy.
            assert(buf.array(0) == 1.toByte && buf.array(3) == 4.toByte, "prior data must survive a legitimate growth")
            assert(buf.size == 4, "ensureCapacityFor must not change the position")
            succeed
        }
    }

end GrowableByteBufferTest
