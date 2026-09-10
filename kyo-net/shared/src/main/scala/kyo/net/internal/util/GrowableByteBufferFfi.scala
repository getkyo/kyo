package kyo.net.internal.util

import kyo.AllowUnsafe

/** kyo-ffi-dependent extension of [[GrowableByteBuffer]], available only where kyo-ffi is (JVM and Native; kyo-net does not link kyo-ffi on
  * JS/Wasm). The core buffer stays cross-platform in `shared`; this off-heap copy lives beside the drivers that use it.
  */
extension (self: GrowableByteBuffer)

    /** Copies `len` bytes from an off-heap `Buffer[Byte]` into the internal array at the current position, growing the array if needed and
      * advancing the position by `len`. One bulk copy straight into the internal array, no intermediate allocation.
      *
      * Used by decryptAll (multi-record path) and appendPending (TLS write backpressure).
      */
    def writeFromBuffer(src: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Unit =
        self.ensureCapacityFor(len)
        src.copyToArray(self.array, self.size, 0, len)
        self.advance(len)
    end writeFromBuffer
end extension
