package kyo.ffi.internal

import java.lang.foreign.MemorySegment
import kyo.AllowUnsafe
import kyo.ffi.Buffer
import kyo.internal.BorrowOwner
import kyo.internal.UnsafeBuffer
import kyo.internal.UnsafeLayout

/** JVM backend for the [[Buffer]] factory surface.
  *
  * The shared [[Buffer]] companion delegates every allocation to this object -- it exists in each platform source set under the same name
  * so that shared code can reference `internal.BufferFactory` without any abstract factory interface. Creates [[UnsafeBuffer]] instances
  * and wraps them in [[Buffer]].
  */
private[ffi] object BufferFactory extends BufferFactoryBase:

    // --- Allocation ---

    /** Allocate a shared-arena buffer of `size` elements. Thread-safe; backed by `Arena.ofShared()`. */
    def alloc[A](size: Int, l: UnsafeLayout[A]): Buffer[A] =
        // Unsafe: allocate a shared Panama arena and wrap it; the returned Buffer owns and later closes the segment.
        import AllowUnsafe.embrace.danger
        val byteLen   = size.toLong * l.size
        val buf       = UnsafeBuffer.alloc(byteLen)
        val core      = new BufferCore(size, l.size.toLong, owned = true)
        val rawHandle = Buffer.Raw.wrap(buf.raw)
        new Buffer[A](buf, l, core, rawHandle)
    end alloc

    /** Allocate a confined-arena buffer of `size` elements. Single-thread only; cross-thread access throws. */
    def allocConfined[A](size: Int, l: UnsafeLayout[A]): Buffer[A] =
        // Unsafe: allocate a single-thread confined Panama arena; cross-thread access is rejected by the confined arena itself.
        import AllowUnsafe.embrace.danger
        val byteLen   = size.toLong * l.size
        val buf       = UnsafeBuffer.allocConfined(byteLen)
        val core      = new BufferCore(size, l.size.toLong, owned = true)
        val rawHandle = Buffer.Raw.wrap(buf.raw)
        new Buffer[A](buf, l, core, rawHandle)
    end allocConfined

    // --- Borrowed ---

    protected def wrapBorrowedImpl[A](raw: Buffer.Raw, size: Int, owner: BorrowOwner | Null, l: UnsafeLayout[A]): Buffer[A] =
        // Unsafe: reinterpret a C-owned MemorySegment as a non-owning Buffer; extent is capped by borrowedBufferMaxBytes.
        import AllowUnsafe.embrace.danger
        Buffer.Raw.unwrap(raw) match
            case seg: MemorySegment =>
                val byteLen: Long = size.toLong * l.size
                val bound: Long   = math.min(byteLen, borrowedBufferMaxBytes)
                val sized: MemorySegment =
                    if seg.byteSize() == 0L then seg.reinterpret(bound).nn
                    else seg
                val buf  = UnsafeBuffer.wrapBorrowed(sized, byteLen)
                val core = new BufferCore(size, l.size.toLong, owned = false, owner = owner)
                new Buffer[A](buf, l, core, raw)
            case other =>
                throw new IllegalArgumentException(
                    FfiErrors.wrapBorrowedExpected("MemorySegment on JVM", other.getClass.getName)
                )
        end match
    end wrapBorrowedImpl

    // --- Thread-local borrow owner for generated code ---

    private val borrowHolder = new BorrowOwnerHolder("kyo.ffi.jvm.thread-owner")

    /** Generated-code entry point: returns the current per-thread [[BorrowOwner]]. Revoked + replaced at the entry of the next binding call
      * via [[rotateBorrowOwner]], so borrows from a previous call are invalidated on the next call.
      */
    def currentBorrowOwner(): BorrowOwner = borrowHolder.currentBorrowOwner()

    /** Revoke the current per-thread [[BorrowOwner]] and install a fresh one. Called by generated-code entry paths in checked-borrows mode
      * so borrows issued by the previous call no longer dereference freed foreign memory.
      */
    def rotateBorrowOwner(): BorrowOwner = borrowHolder.rotateBorrowOwner()

    // --- Memory-mapped files ---

    def mmapReadOnly(path: String, offset: Long, size: Long): Buffer[Byte] =
        // Unsafe: memory-map a file region read-only into an owned off-heap buffer.
        import AllowUnsafe.embrace.danger
        val buf       = UnsafeBuffer.mmapReadOnly(path, offset, size)
        val mapSize   = if size < 0 then buf.byteSize else size
        val core      = new BufferCore(buf.byteSize.toInt, 1L, owned = true)
        val rawHandle = Buffer.Raw.wrap(buf.raw)
        new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], core, rawHandle)
    end mmapReadOnly

    def mmapReadWrite(path: String, offset: Long, size: Long): Buffer[Byte] =
        // Unsafe: memory-map a file region read-write into an owned off-heap buffer.
        import AllowUnsafe.embrace.danger
        val buf       = UnsafeBuffer.mmapReadWrite(path, offset, size)
        val core      = new BufferCore(buf.byteSize.toInt, 1L, owned = true)
        val rawHandle = Buffer.Raw.wrap(buf.raw)
        new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], core, rawHandle)
    end mmapReadWrite

    /** Default upper bound on the reinterpreted extent of a borrowed [[MemorySegment]] produced by [[wrapBorrowed]]. Overridable via
      * `-Dkyo.ffi.borrowedBufferMaxBytes=`; minimum of `1` byte, default `1 << 30` (1 GiB).
      *
      * Rationale: borrowed buffers wrap C-owned memory whose real extent the JVM cannot verify. Capping the reinterpret size prevents
      * generated code (or user code via `Buffer.Unsafe.wrapBorrowed`) from handing Panama a 2^63-byte segment -- under which any random
      * address looks in-bounds. The cap only affects zero-sized incoming segments; already-bounded segments (e.g. slices of shared arenas)
      * flow through untouched.
      *
      * Read as a `def` so tests and other callers can mutate the sys-prop between wraps and observe the updated cap without reloading the
      * class.
      */
    def borrowedBufferMaxBytes: Long =
        val prop = java.lang.System.getProperty("kyo.ffi.borrowedBufferMaxBytes")
        if prop == null then 1L << 30
        else
            try math.max(1L, prop.toLong)
            catch case _: NumberFormatException => 1L << 30
        end if
    end borrowedBufferMaxBytes
end BufferFactory
