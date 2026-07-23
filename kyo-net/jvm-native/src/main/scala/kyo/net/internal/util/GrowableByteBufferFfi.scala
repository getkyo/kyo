package kyo.net.internal.util

import kyo.AllowUnsafe

/** kyo-ffi-dependent extension of [[GrowableByteBuffer]], available only where kyo-ffi is (JVM and Native; kyo-net does not link kyo-ffi on
  * JS/Wasm). The core buffer stays cross-platform in `shared`; this off-heap copy lives beside the drivers that use it.
  */
extension (self: GrowableByteBuffer)

    /** Copies `len` bytes from an off-heap `Buffer[Byte]` into the internal array at the current position, growing the array if needed and
      * advancing the position by `len`.
      *
      * The kyo-ffi Buffer API does not expose a bulk copyInto(dst: Array[Byte], dstOffset: Int, len: Int) primitive, so this reads
      * element-wise directly into the internal array starting at the current position, with no intermediate allocation. The loop is the same
      * per-element cost as Buffer.copyToArray internally (which also loops buf.get(from+i)), but without the temporary array or the subsequent
      * arraycopy, yielding strictly zero extra allocation per call.
      *
      * Used by decryptAll (multi-record path) and appendPending (TLS write backpressure).
      */
    def writeFromBuffer(src: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Unit =
        self.ensureCapacityFor(len)
        val arr = self.array
        val pos = self.size
        var i   = 0
        while i < len do
            arr(pos + i) = src.get(i)
            i += 1
        self.advance(len)
    end writeFromBuffer
end extension
