package kyo.ffi.internal

import kyo.AllowUnsafe
import kyo.ffi.Buffer
import kyo.internal.BorrowOwner
import kyo.internal.UnsafeBuffer
import kyo.internal.UnsafeLayout
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.scalajs.js.typedarray.Uint8Array

/** JS backend for the [[Buffer]] factory surface. Creates [[UnsafeBuffer]] instances and wraps them in [[Buffer]]. The raw handle exposed
  * by [[Buffer.raw]] contains a [[JsRawSegment]] for codegen compatibility.
  *
  * `close` is a no-op on JS -- memory is reclaimed by the JS garbage collector once the last reference is dropped, but the `closed` flag
  * still flips so post-close access fails the same way as on JVM and Native.
  */
private[ffi] object BufferFactory extends BufferFactoryBase:

    // --- Allocation ---

    /** Allocate a `Uint8Array`-backed buffer of `size` elements, zero-initialized. */
    def alloc[A](size: Int, l: UnsafeLayout[A]): Buffer[A] =
        // Unsafe: allocate a Uint8Array-backed buffer and wrap it for codegen; the runtime GC reclaims it after the last reference drops.
        import AllowUnsafe.embrace.danger
        val byteLen = size.toLong * l.size
        val buf     = UnsafeBuffer.alloc(byteLen)
        val core    = new BufferCore(size, l.size.toLong, owned = true)
        // Convert Uint8Array -> JsRawSegment for codegen compatibility
        val u8a       = buf.raw.asInstanceOf[Uint8Array]
        val rawHandle = Buffer.Raw.wrap(JsRawSegment.fromUint8(u8a))
        new Buffer[A](buf, l, core, rawHandle)
    end alloc

    /** JS has no confined/shared distinction -- same as [[alloc]]. */
    def allocConfined[A](size: Int, l: UnsafeLayout[A]): Buffer[A] = alloc[A](size, l)

    // --- Borrowed ---

    protected def wrapBorrowedImpl[A](raw: Buffer.Raw, size: Int, owner: BorrowOwner | Null, l: UnsafeLayout[A]): Buffer[A] =
        // Unsafe: wrap a JS-owned Uint8Array / JsRawSegment as a non-owning Buffer.
        import AllowUnsafe.embrace.danger
        val byteLen = size.toLong * l.size
        Buffer.Raw.unwrap(raw) match
            case u8a: Uint8Array =>
                val buf  = UnsafeBuffer.wrapBorrowed(u8a, byteLen)
                val core = new BufferCore(size, l.size.toLong, owned = false, owner = owner)
                // Wrap with JsRawSegment for codegen compatibility
                val rawHandle = Buffer.Raw.wrap(JsRawSegment.fromUint8(u8a))
                new Buffer[A](buf, l, core, rawHandle)
            case seg: JsRawSegment =>
                val buf  = UnsafeBuffer.wrapBorrowed(seg.u8a, byteLen)
                val core = new BufferCore(size, l.size.toLong, owned = false, owner = owner)
                new Buffer[A](buf, l, core, raw) // raw already contains JsRawSegment
            case other =>
                throw new IllegalArgumentException(
                    FfiErrors.wrapBorrowedExpected("Uint8Array on JS", other.getClass.getName)
                )
        end match
    end wrapBorrowedImpl

    // --- Memory-mapped files (JS fallback: full file read into ArrayBuffer) ---

    /** JS fallback: reads the entire file (or region) into memory via `fs.readFileSync`. This is NOT a true mmap -- the entire content is
      * copied into a JS ArrayBuffer. The API is identical to JVM/Native for portability.
      */
    def mmapReadOnly(path: String, offset: Long, size: Long): Buffer[Byte] =
        mmapImpl(path, offset, size)

    /** JS fallback: same as [[mmapReadOnly]] -- writes go to the in-memory copy only and are NOT persisted to the file. This is a
      * documented limitation of the JS platform.
      */
    def mmapReadWrite(path: String, offset: Long, size: Long): Buffer[Byte] =
        mmapImpl(path, offset, size)

    private def mmapImpl(path: String, offset: Long, size: Long): Buffer[Byte] =
        // Unsafe: read the file region into a JS ArrayBuffer (not a true mmap on JS) and wrap it.
        import AllowUnsafe.embrace.danger
        val nodeBuffer = NodeFs.readFileSync(path)
        val fileLength = nodeBuffer.length.asInstanceOf[Int]
        val start      = offset.toInt
        val end        = if size < 0 then fileLength else math.min(start + size.toInt, fileLength)
        val mapSize    = end - start
        if mapSize == 0 then
            val buf       = UnsafeBuffer.alloc(0L)
            val core      = new BufferCore(0, 1L, owned = true)
            val u8a       = buf.raw.asInstanceOf[Uint8Array]
            val rawHandle = Buffer.Raw.wrap(JsRawSegment.fromUint8(u8a))
            new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], core, rawHandle)
        else
            // Create a copy of the relevant slice so the Node Buffer can be GC'd
            val sliced     = nodeBuffer.slice(start, end)
            val arrayBuf   = sliced.buffer.asInstanceOf[ArrayBuffer]
            val byteOffset = sliced.byteOffset.asInstanceOf[Int]
            val u8a        = new Uint8Array(arrayBuf, byteOffset, mapSize)
            val buf        = UnsafeBuffer.wrapBorrowed(u8a, mapSize.toLong)
            val core       = new BufferCore(mapSize, 1L, owned = true)
            val rawHandle  = Buffer.Raw.wrap(JsRawSegment.fromUint8(u8a))
            new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], core, rawHandle)
        end if
    end mmapImpl

    // --- Per-runtime borrow owner for generated code ---
    //
    // JS is single-threaded so a plain `var` provides the same semantics as a per-thread handle without dragging
    // in `ThreadLocal.withInitial`, which Scala.js does not support.

    private var currentOwner: BorrowOwner =
        new BorrowOwner("kyo.ffi.js.thread-owner")

    /** Generated-code entry point: returns the current JS-runtime [[BorrowOwner]]. */
    def currentBorrowOwner(): BorrowOwner = currentOwner

    /** Revoke the current JS-runtime [[BorrowOwner]] and install a fresh one. */
    def rotateBorrowOwner(): BorrowOwner =
        currentOwner.revoke()
        val fresh = new BorrowOwner(currentOwner.label)
        currentOwner = fresh
        fresh
    end rotateBorrowOwner
end BufferFactory
