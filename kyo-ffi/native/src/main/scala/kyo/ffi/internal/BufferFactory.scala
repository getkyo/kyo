package kyo.ffi.internal

import kyo.AllowUnsafe
import kyo.ffi.Buffer
import kyo.internal.BorrowOwner
import kyo.internal.NativeUnsafeBuffer
import kyo.internal.NativeUnsafeBufferPtr
import kyo.internal.UnsafeBuffer
import kyo.internal.UnsafeLayout
import scala.scalanative.unsafe.*

/** Native backend for the [[Buffer]] factory surface.
  *
  * Creates [[UnsafeBuffer]] instances (via the companion factories) and wraps them in [[Buffer]]. The raw handle exposed by [[Buffer.raw]]
  * contains a [[NativePtr]] (not [[NativeUnsafeBufferPtr]]) for codegen compatibility.
  */
private[ffi] object BufferFactory extends BufferFactoryBase:

    // --- Allocation ---

    /** Allocate a malloc-backed buffer of `size` elements, zero-initialized. */
    def alloc[A](size: Int, l: UnsafeLayout[A]): Buffer[A] =
        // Unsafe: malloc a zero-initialized native buffer; the returned Buffer owns and later frees the pointer.
        import AllowUnsafe.embrace.danger
        val byteLen = size.toLong * l.size
        val buf     = UnsafeBuffer.alloc(byteLen)
        val core    = new BufferCore(size, l.size.toLong, owned = true)
        // Convert NativeUnsafeBufferPtr -> NativePtr for codegen compatibility
        val nubp      = buf.raw.asInstanceOf[NativeUnsafeBufferPtr]
        val rawHandle = Buffer.Raw.wrap(new NativePtr(nubp.ptr))
        new Buffer[A](buf, l, core, rawHandle)
    end alloc

    /** Native has no confined/shared distinction -- same as [[alloc]]. */
    def allocConfined[A](size: Int, l: UnsafeLayout[A]): Buffer[A] = alloc[A](size, l)

    // --- Borrowed ---

    protected def wrapBorrowedImpl[A](raw: Buffer.Raw, size: Int, owner: BorrowOwner | Null, l: UnsafeLayout[A]): Buffer[A] =
        // Unsafe: wrap a C-owned native pointer as a non-owning Buffer; the C side retains ownership.
        import AllowUnsafe.embrace.danger
        Buffer.Raw.unwrap(raw) match
            case np: NativePtr =>
                // Create UnsafeBuffer from the NativePtr's pointer
                val byteLen = size.toLong * l.size
                val buf     = new NativeUnsafeBuffer(np.ptr, byteLen, () => ())
                val core    = new BufferCore(size, l.size.toLong, owned = false, owner = owner)
                new Buffer[A](buf, l, core, raw) // raw already contains NativePtr
            case other =>
                throw new IllegalArgumentException(
                    FfiErrors.wrapBorrowedExpected("NativePtr on Native", other.getClass.getName)
                )
        end match
    end wrapBorrowedImpl

    // --- Memory-mapped files ---

    def mmapReadOnly(path: String, offset: Long, size: Long): Buffer[Byte] =
        // Unsafe: memory-map a file region read-only into an owned native buffer.
        import AllowUnsafe.embrace.danger
        val buf       = UnsafeBuffer.mmapReadOnly(path, offset, size)
        val core      = new BufferCore(buf.byteSize.toInt, 1L, owned = true)
        val nubp      = buf.raw.asInstanceOf[NativeUnsafeBufferPtr]
        val rawHandle = Buffer.Raw.wrap(new NativePtr(nubp.ptr))
        new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], core, rawHandle)
    end mmapReadOnly

    def mmapReadWrite(path: String, offset: Long, size: Long): Buffer[Byte] =
        // Unsafe: memory-map a file region read-write into an owned native buffer.
        import AllowUnsafe.embrace.danger
        val buf       = UnsafeBuffer.mmapReadWrite(path, offset, size)
        val core      = new BufferCore(buf.byteSize.toInt, 1L, owned = true)
        val nubp      = buf.raw.asInstanceOf[NativeUnsafeBufferPtr]
        val rawHandle = Buffer.Raw.wrap(new NativePtr(nubp.ptr))
        new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], core, rawHandle)
    end mmapReadWrite

    // --- Thread-local borrow owner for generated code ---

    private val borrowHolder = new BorrowOwnerHolder("kyo.ffi.native.thread-owner")

    /** Generated-code entry point: returns the current per-thread [[BorrowOwner]]. */
    def currentBorrowOwner(): BorrowOwner = borrowHolder.currentBorrowOwner()

    /** Revoke the current per-thread [[BorrowOwner]] and install a fresh one. */
    def rotateBorrowOwner(): BorrowOwner = borrowHolder.rotateBorrowOwner()
end BufferFactory
