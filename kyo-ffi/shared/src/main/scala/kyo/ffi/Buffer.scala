package kyo.ffi

import kyo.AllowUnsafe
import kyo.internal.BorrowOwner
import kyo.internal.BorrowRevoked
import kyo.internal.UnsafeBuffer
import kyo.internal.UnsafeLayout

/** A typed off-heap buffer.
  *
  * Lifetime is explicit: the buffer is valid until [[close]] is called, or until the enclosing [[Buffer.use]] / [[Buffer.confinedUse]]
  * scope exits.
  *
  * Create via the factories on the companion object -- [[Buffer.alloc]], [[Buffer.use]], [[Buffer.confinedUse]], [[Buffer.fromArray]], or
  * [[Buffer.fromUtf8]].
  *
  * Thread safety:
  *   - [[Buffer.alloc]] and [[Buffer.use]] are thread-safe; on JVM they are backed by `Arena.ofShared()`.
  *   - [[Buffer.confinedUse]] is single-thread only; cross-thread access throws on JVM (via `Arena.ofConfined()`).
  *
  * @tparam A
  *   the element type -- one of the primitives for which an [[UnsafeLayout]] instance is provided.
  */
final class Buffer[A] private[ffi] (
    private[ffi] val underlying: UnsafeBuffer,
    private[ffi] val layout: UnsafeLayout[A],
    private val core: internal.BufferCore,
    private val rawHandle: Buffer.Raw
):

    /** Number of elements the buffer can hold. */
    def size: Int = core.size

    /** Total byte size -- `size * UnsafeLayout[A].size`. */
    def byteSize: Long = core.byteSize

    /** Whether this buffer has been closed. */
    def isClosed: Boolean = core.isClosed

    /** Read the element at `i`. Throws [[java.lang.IndexOutOfBoundsException]] if `i` is out of range, or
      * [[java.lang.IllegalStateException]] if the buffer is closed.
      */
    def get(i: Int)(using AllowUnsafe): A =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap read at the checked byte offset, through the UnsafeLayout typeclass dispatch.
        layout.read(underlying, i.toLong * layout.size)
    end get

    /** Write `v` at index `i`. Throws [[java.lang.IndexOutOfBoundsException]] if `i` is out of range, or
      * [[java.lang.IllegalStateException]] if the buffer is closed.
      */
    def set(i: Int, v: A)(using AllowUnsafe): Unit =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap write at the checked byte offset, through the UnsafeLayout typeclass dispatch.
        layout.write(underlying, i.toLong * layout.size, v)
    end set

    /** Read the `Long` element at `i`, bypassing the [[kyo.internal.UnsafeLayout]] typeclass dispatch [[get]]
      * goes through. `get`/`set` box every primitive through that generic dispatch (type erasure), which a hot
      * read/compare loop cannot afford; this calls the underlying buffer's own non-generic primitive accessor
      * directly, avoiding that boxing on every platform. Callable only when `A` is provably `Long`, which the
      * compiler enforces via the evidence parameter, so it can never misread a differently-typed buffer's
      * bytes. Same bounds/closed checks as [[get]].
      */
    def getLong(i: Int)(using ev: A =:= Long, allow: AllowUnsafe): Long =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Long read at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.getLong(i.toLong * layout.size)
    end getLong

    /** Write the `Long` `v` at index `i`, bypassing the typeclass dispatch [[set]] goes through. See
      * [[getLong]] for why.
      */
    def setLong(i: Int, v: Long)(using ev: A =:= Long, allow: AllowUnsafe): Unit =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Long write at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.setLong(i.toLong * layout.size, v)
    end setLong

    /** Read the `Int` element at `i`, bypassing the typeclass dispatch [[get]] goes through. See [[getLong]]
      * for why.
      */
    def getInt(i: Int)(using ev: A =:= Int, allow: AllowUnsafe): Int =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Int read at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.getInt(i.toLong * layout.size)
    end getInt

    /** Write the `Int` `v` at index `i`, bypassing the typeclass dispatch [[set]] goes through. See
      * [[getLong]] for why.
      */
    def setInt(i: Int, v: Int)(using ev: A =:= Int, allow: AllowUnsafe): Unit =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Int write at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.setInt(i.toLong * layout.size, v)
    end setInt

    /** Read the `Short` element at `i`, bypassing the typeclass dispatch [[get]] goes through. See [[getLong]]
      * for why.
      */
    def getShort(i: Int)(using ev: A =:= Short, allow: AllowUnsafe): Short =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Short read at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.getShort(i.toLong * layout.size)
    end getShort

    /** Write the `Short` `v` at index `i`, bypassing the typeclass dispatch [[set]] goes through. See
      * [[getLong]] for why.
      */
    def setShort(i: Int, v: Short)(using ev: A =:= Short, allow: AllowUnsafe): Unit =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Short write at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.setShort(i.toLong * layout.size, v)
    end setShort

    /** Read the `Double` element at `i`, bypassing the typeclass dispatch [[get]] goes through. See
      * [[getLong]] for why.
      */
    def getDouble(i: Int)(using ev: A =:= Double, allow: AllowUnsafe): Double =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Double read at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.getDouble(i.toLong * layout.size)
    end getDouble

    /** Write the `Double` `v` at index `i`, bypassing the typeclass dispatch [[set]] goes through. See
      * [[getLong]] for why.
      */
    def setDouble(i: Int, v: Double)(using ev: A =:= Double, allow: AllowUnsafe): Unit =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Double write at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.setDouble(i.toLong * layout.size, v)
    end setDouble

    /** Read the `Float` element at `i`, bypassing the typeclass dispatch [[get]] goes through. See [[getLong]]
      * for why.
      */
    def getFloat(i: Int)(using ev: A =:= Float, allow: AllowUnsafe): Float =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Float read at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.getFloat(i.toLong * layout.size)
    end getFloat

    /** Write the `Float` `v` at index `i`, bypassing the typeclass dispatch [[set]] goes through. See
      * [[getLong]] for why.
      */
    def setFloat(i: Int, v: Float)(using ev: A =:= Float, allow: AllowUnsafe): Unit =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Float write at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.setFloat(i.toLong * layout.size, v)
    end setFloat

    /** Read the `Byte` element at `i`, bypassing the typeclass dispatch [[get]] goes through. See [[getLong]]
      * for why.
      */
    def getByte(i: Int)(using ev: A =:= Byte, allow: AllowUnsafe): Byte =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Byte read at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.getByte(i.toLong * layout.size)
    end getByte

    /** Write the `Byte` `v` at index `i`, bypassing the typeclass dispatch [[set]] goes through. See
      * [[getLong]] for why.
      */
    def setByte(i: Int, v: Byte)(using ev: A =:= Byte, allow: AllowUnsafe): Unit =
        core.checkOpen()
        core.checkIndex(i)
        // Unsafe: raw off-heap Byte write at the checked byte offset, bypassing the boxing UnsafeLayout dispatch.
        underlying.setByte(i.toLong * layout.size, v)
    end setByte

    /** Release the underlying memory. Idempotent -- subsequent calls are no-ops. */
    def close()(using AllowUnsafe): Unit =
        // For borrowed buffers `close` must be a true no-op -- including not flipping the closed flag --
        // so that post-close reads continue to succeed.
        if core.owned && core.beginClose() then
            underlying.close()

    /** Opaque platform-specific handle. Internal and generated-code use only. */
    def raw: Buffer.Raw = rawHandle
end Buffer

object Buffer:

    // --- Public API ---

    /** Allocate a fresh thread-safe buffer of `size` elements. Caller must [[Buffer.close]] (or use [[use]]) to release.
      */
    def alloc[A](size: Int)(using l: UnsafeLayout[A], allow: AllowUnsafe): Buffer[A] =
        internal.BufferFactory.alloc[A](size, l)

    /** Allocate a single-thread confined buffer of `size` elements. Caller must [[Buffer.close]] (or use [[confinedUse]]) to release. */
    def allocConfined[A](size: Int)(using l: UnsafeLayout[A], allow: AllowUnsafe): Buffer[A] =
        internal.BufferFactory.allocConfined[A](size, l)

    /** Allocate, run `f`, and close the buffer -- even if `f` throws. The primary user-facing lifetime pattern.
      */
    inline def use[A, R](size: Int)(inline f: Buffer[A] => R)(using UnsafeLayout[A], AllowUnsafe): R =
        val b = alloc[A](size)
        try f(b)
        finally b.close()
    end use

    /** Like [[use]] but backed by a single-thread confined arena on JVM for faster allocation. Do not pass the buffer across threads or
      * suspend a fiber inside the block. On Native and JS this is identical to [[use]].
      */
    inline def confinedUse[A, R](size: Int)(inline f: Buffer[A] => R)(using UnsafeLayout[A], AllowUnsafe): R =
        val b = allocConfined[A](size)
        try f(b)
        finally b.close()
    end confinedUse

    /** Copy the contents of an on-heap [[scala.Array]] into a freshly allocated buffer. */
    def fromArray[A](a: Array[A])(using l: UnsafeLayout[A], allow: AllowUnsafe): Buffer[A] =
        internal.BufferFactory.fromArray[A](a, l)

    /** Copy an [[scala.Array]] into a buffer, run `f`, and close the buffer -- even if `f` throws. Combines [[fromArray]] with scoped
      * lifetime.
      */
    inline def useArray[A, R](arr: Array[A])(inline f: Buffer[A] => R)(using UnsafeLayout[A], AllowUnsafe): R =
        val b = fromArray(arr)
        try f(b)
        finally b.close()
    end useArray

    /** Copy a range `[from, from + len)` of `b` into a freshly allocated on-heap [[scala.Array]]. */
    def copyToArray[A: scala.reflect.ClassTag](b: Buffer[A], from: Int, len: Int)(using AllowUnsafe): Array[A] =
        internal.BufferFactory.copyToArray[A](b, from, len)

    /** Encode `s` as UTF-8 and store it null-terminated in a freshly allocated [[Buffer]] of [[Byte]]. */
    def fromUtf8(s: String)(using AllowUnsafe): Buffer[Byte] =
        internal.BufferFactory.fromUtf8(s)

    /** Memory-map a file as a read-only `Buffer[Byte]`.
      *
      * JVM/Native: true mmap (zero-copy, OS-managed paging). JS: file read into ArrayBuffer (full copy fallback, same API).
      *
      * The returned buffer owns the mapping. Call [[Buffer.close]] to release it. On JVM, the underlying `MappedByteBuffer` is unmapped on
      * GC; `close()` closes the file channel. On Native, `close()` calls `munmap`. On JS, `close()` is a flag-flip only (GC reclaims).
      *
      * @param path
      *   file system path to the file to map
      * @param offset
      *   byte offset into the file where mapping starts (default 0)
      * @param size
      *   number of bytes to map; -1 means "map from offset to end of file" (default -1)
      * @throws java.io.IOException
      *   if the file does not exist or cannot be opened
      * @throws IllegalArgumentException
      *   if file size exceeds 2 GB on JVM
      */
    def mmapReadOnly(path: String, offset: Long = 0L, size: Long = -1L)(using AllowUnsafe): Buffer[Byte] =
        internal.BufferFactory.mmapReadOnly(path, offset, size)

    /** Memory-map a file as a read-write `Buffer[Byte]`.
      *
      * JVM/Native: true mmap (changes are persisted to the file on close/flush). JS: in-memory copy only -- writes are NOT persisted to the
      * file. This is a documented limitation of the JS fallback.
      *
      * @param path
      *   file system path to the file to map
      * @param offset
      *   byte offset into the file where mapping starts (default 0)
      * @param size
      *   number of bytes to map; -1 means "map from offset to end of file" (default -1)
      * @throws java.io.IOException
      *   if the file does not exist or cannot be opened
      * @throws IllegalArgumentException
      *   if file size exceeds 2 GB on JVM
      */
    def mmapReadWrite(path: String, offset: Long = 0L, size: Long = -1L)(using AllowUnsafe): Buffer[Byte] =
        internal.BufferFactory.mmapReadWrite(path, offset, size)

    // --- Nested Types ---

    /** Opaque platform-specific handle backing a [[Buffer]]. Internal and generated-code use only. */
    opaque type Raw = AnyRef

    object Raw:
        private[ffi] inline def wrap(v: AnyRef): Raw   = v
        private[ffi] inline def unwrap(r: Raw): AnyRef = r
    end Raw

    /** Unsafe constructors -- intended exclusively for generated FFI code and advanced users interoperating with foreign-owned memory.
      *
      * Everything in [[Unsafe]] bypasses the standard ownership / lifetime model: the returned [[Buffer]] does NOT own its underlying
      * memory and [[Buffer.close]] is a no-op. Misuse produces memory-safety errors (reads past the real end of a C-owned region will
      * segfault on Native and JVM, or throw on JS).
      */
    object Unsafe:

        /** Wrap a platform-specific raw handle in a **borrowed** [[Buffer]] (unchecked mode).
          *
          * The C side retains ownership; `Buffer.close()` is a no-op. Lifetime is whatever the C callee guarantees -- keeping the buffer
          * past that is a use-after-free. For opt-in lifetime validation use [[wrapBorrowedChecked]].
          */
        def wrapBorrowed[A](raw: AnyRef, size: Int)(using l: UnsafeLayout[A], allow: AllowUnsafe): Buffer[A] =
            internal.BufferFactory.wrapBorrowed[A](Buffer.Raw.wrap(raw), size, l)

        /** Wrap a platform-specific raw handle in a **checked** borrowed [[Buffer]].
          *
          * Like [[wrapBorrowed]] but every `get`/`set` first verifies `owner.isValid`; throws [[BorrowRevoked]] if revoked. Enable via
          * `-Dkyo.ffi.checkedBorrows=true` or `Ffi.Config.checkedBorrows`.
          */
        def wrapBorrowedChecked[A](raw: AnyRef, size: Int, owner: BorrowOwner)(using l: UnsafeLayout[A], allow: AllowUnsafe): Buffer[A] =
            internal.BufferFactory.wrapBorrowedChecked[A](Buffer.Raw.wrap(raw), size, owner, l)
    end Unsafe
end Buffer
