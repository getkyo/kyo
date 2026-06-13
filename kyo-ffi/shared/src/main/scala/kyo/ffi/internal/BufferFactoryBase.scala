package kyo.ffi.internal

import kyo.ffi.Buffer
import kyo.internal.BorrowOwner
import kyo.internal.UnsafeLayout

/** Shared base trait for per-platform [[BufferFactory]] objects. Provides the platform-independent conversion and wrap methods. */
private[ffi] trait BufferFactoryBase:

    // --- Abstract -- each platform implements these ---

    def alloc[A](size: Int, l: UnsafeLayout[A]): Buffer[A]
    def allocConfined[A](size: Int, l: UnsafeLayout[A]): Buffer[A]
    protected def wrapBorrowedImpl[A](raw: Buffer.Raw, size: Int, owner: BorrowOwner | Null, l: UnsafeLayout[A]): Buffer[A]
    def currentBorrowOwner(): BorrowOwner
    def rotateBorrowOwner(): BorrowOwner
    def mmapReadOnly(path: String, offset: Long, size: Long): Buffer[Byte]
    def mmapReadWrite(path: String, offset: Long, size: Long): Buffer[Byte]

    // --- Shared implementations ---

    /** Copy an on-heap [[scala.Array]] into a freshly allocated shared buffer. */
    def fromArray[A](a: Array[A], l: UnsafeLayout[A]): Buffer[A] =
        BufferConversions.fromArray(a, (n, ll) => alloc[A](n, ll), l)

    /** Copy a range of a [[Buffer]] into a freshly allocated on-heap [[scala.Array]]. */
    def copyToArray[A: scala.reflect.ClassTag](b: Buffer[A], from: Int, len: Int): Array[A] =
        BufferConversions.copyToArray(b, from, len)

    /** Encode `s` as UTF-8 and store it null-terminated in a fresh [[Buffer]] of [[Byte]]. */
    def fromUtf8(s: String): Buffer[Byte] =
        BufferConversions.fromUtf8(s, (n, ll) => alloc[Byte](n, ll), summon[UnsafeLayout[Byte]])

    /** Wrap a raw platform-specific handle in a borrowed [[Buffer]] whose `close` is a no-op. The caller retains ownership. */
    def wrapBorrowed[A](raw: Buffer.Raw, size: Int, l: UnsafeLayout[A]): Buffer[A] =
        wrapBorrowedImpl(raw, size, null, l)

    /** Checked-borrow variant of [[wrapBorrowed]]. Every `get`/`set` verifies `owner.isValid` before access. */
    def wrapBorrowedChecked[A](raw: Buffer.Raw, size: Int, owner: BorrowOwner, l: UnsafeLayout[A]): Buffer[A] =
        wrapBorrowedImpl(raw, size, owner, l)
end BufferFactoryBase
