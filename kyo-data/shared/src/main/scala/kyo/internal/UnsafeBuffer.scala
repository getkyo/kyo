package kyo.internal

import kyo.AllowUnsafe

/** Off-heap memory container. Truly unsafe -- no bounds checking, no lifecycle validation.
  *
  * Platform subclasses provide actual implementations:
  *   - JVM: [[JvmUnsafeBuffer]] backed by `MemorySegment`
  *   - Native: `NativeUnsafeBuffer` backed by `Ptr[Byte]`
  *   - JS: `JsUnsafeBuffer` backed by `Uint8Array`/`DataView`
  */
abstract class UnsafeBuffer(
    val byteSize: Long,
    private[kyo] val closer: () => Unit
):
    // CAS-guarded so the off-heap closer (e.g. a native `free(ptr)`) runs at most once even when two carriers
    // race close(): a plain `if !closed then closed = true` is read-then-write, so both racers can observe
    // `false` and both invoke the closer, double-freeing the pointer. On Scala Native that double `free` corrupts
    // the malloc arena and aborts the process later at an unrelated allocation; the JVM MemorySegment closer is
    // itself idempotent so it only matters on Native, but the guard is correct on every platform.
    private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether this buffer has been closed. */
    def isClosed: Boolean = closed.get()

    // Primitive get/set -- abstract, implemented by platform subclasses
    def getByte(offset: Long)(using AllowUnsafe): Byte
    def setByte(offset: Long, value: Byte)(using AllowUnsafe): Unit
    def getInt(offset: Long)(using AllowUnsafe): Int
    def setInt(offset: Long, value: Int)(using AllowUnsafe): Unit
    def getLong(offset: Long)(using AllowUnsafe): Long
    def setLong(offset: Long, value: Long)(using AllowUnsafe): Unit
    def getFloat(offset: Long)(using AllowUnsafe): Float
    def setFloat(offset: Long, value: Float)(using AllowUnsafe): Unit
    def getDouble(offset: Long)(using AllowUnsafe): Double
    def setDouble(offset: Long, value: Double)(using AllowUnsafe): Unit
    def getShort(offset: Long)(using AllowUnsafe): Short
    def setShort(offset: Long, value: Short)(using AllowUnsafe): Unit

    // Bulk operations
    def copyTo(target: UnsafeBuffer, srcOffset: Long, targetOffset: Long, bytes: Long)(using AllowUnsafe): Unit
    def copyToArray(arr: Array[Byte], srcOffset: Long, len: Int)(using AllowUnsafe): Unit

    // Lifecycle
    /** Close this buffer. Idempotent -- second call is a no-op. */
    final def close()(using AllowUnsafe): Unit =
        if closed.compareAndSet(false, true) then
            closer()

    // Sub-view (shares underlying memory, no-op closer)
    def view(offset: Long, byteSize: Long)(using AllowUnsafe): UnsafeBuffer

    // Raw backing for FFI interop
    def raw(using AllowUnsafe): AnyRef
end UnsafeBuffer

object UnsafeBuffer:
    def alloc(byteSize: Long)(using AllowUnsafe): UnsafeBuffer         = UnsafeBufferPlatform.alloc(byteSize)
    def allocConfined(byteSize: Long)(using AllowUnsafe): UnsafeBuffer = UnsafeBufferPlatform.allocConfined(byteSize)
    def fromArray(arr: Array[Byte])(using AllowUnsafe): UnsafeBuffer   = UnsafeBufferPlatform.fromArray(arr)
    def fromUtf8(s: String)(using AllowUnsafe): UnsafeBuffer           = UnsafeBufferPlatform.fromUtf8(s)
    def mmapReadOnly(path: String, offset: Long = 0L, size: Long = -1L)(using AllowUnsafe): UnsafeBuffer =
        UnsafeBufferPlatform.mmapReadOnly(path, offset, size)
    def mmapReadWrite(path: String, offset: Long = 0L, size: Long = -1L)(using AllowUnsafe): UnsafeBuffer =
        UnsafeBufferPlatform.mmapReadWrite(path, offset, size)
    def wrapBorrowed(raw: AnyRef, byteSize: Long)(using AllowUnsafe): UnsafeBuffer =
        UnsafeBufferPlatform.wrapBorrowed(raw, byteSize)
end UnsafeBuffer
