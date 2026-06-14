package kyo.ffi.internal

import kyo.internal.BorrowOwner
import kyo.internal.BorrowRevoked

/** Shared state and bounds-/lifecycle-checking logic for [[kyo.ffi.Buffer]] implementations.
  *
  * Composed by the [[kyo.ffi.Buffer]] final class. Manages the closed flag (CAS for exactly-once teardown), optional [[BorrowOwner]]
  * validation, and index bounds checking. Borrowed buffers pass `owned = false` so `close()` skips platform teardown.
  */
final private[ffi] class BufferCore(
    val size: Int,
    val bytesPerElement: Long,
    val owned: Boolean,
    val owner: BorrowOwner | Null = null
):

    val byteSize: Long = size.toLong * bytesPerElement

    private val _closed = new java.util.concurrent.atomic.AtomicBoolean(false)

    def isClosed: Boolean = _closed.get()

    /** Mark the buffer closed if not already. Returns `true` exactly once; platform teardown should only run when `true` and `owned`. */
    def beginClose(): Boolean =
        _closed.compareAndSet(false, true)

    /** Verifies the buffer is open and the borrow owner is valid. Reads are not fully atomic, a concurrent owner revoke between the two
      * loads could produce a stale pass. The runtime does not race checkOpen against owner rotation, so this narrow window is benign.
      */
    def checkOpen(): Unit =
        val o = owner
        if (o ne null) && !o.isValid then throw new BorrowRevoked(o.label)
        if _closed.get() then throw new IllegalStateException(FfiErrors.BufferClosed)
    end checkOpen

    /** Throws [[java.lang.IndexOutOfBoundsException]] with [[FfiErrors.bufferIndexOutOfRange]] if the index is out of range. */
    def checkIndex(i: Int): Unit =
        if i < 0 || i >= size then throw new IndexOutOfBoundsException(FfiErrors.bufferIndexOutOfRange(i))
end BufferCore
