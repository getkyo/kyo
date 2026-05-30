package kyo.internal.tasty.binary

import kyo.AllowUnsafe

/** A window into a byte buffer, tracking a mutable read cursor.
  *
  * ByteView is a sealed trait with two concrete cases:
  *   - Heap: backed by an in-memory Array[Byte] with start/end bounds and a mutable cursor.
  *   - Mapped: stub for memory-mapped file support (Phase 7).
  *
  * All cursor-advancing operations are performed on the concrete Heap instance; there is no effect wrapper here because ByteView is a
  * low-level building block used inside Sync.defer blocks at the call site.
  */
sealed trait ByteView:

    /** Read the byte at absolute position `at` without advancing the cursor. */
    def peekByte(at: Long): Byte

    /** Read the byte at the current cursor position and advance cursor by 1. */
    def readByte()(using AllowUnsafe): Byte

    /** Read an unsigned LEB128 Nat (big-endian base-128, stop-bit on last byte) as Int. */
    def readNat()(using AllowUnsafe): Int = Varint.readNat(this)

    /** Read a signed 2's complement big-endian base-128 Int (dotty TastyReader semantics). */
    def readInt()(using AllowUnsafe): Int = Varint.readInt(this)

    /** Read an unsigned LEB128 Nat as Long. */
    def readLongNat()(using AllowUnsafe): Long = Varint.readLongNat(this)

    /** Read a Nat as the payload length, then return the absolute end address (cursor + nat). */
    def readEnd()(using AllowUnsafe): Long

    /** Return a sub-view sharing the same underlying bytes, with its own cursor reset to `from`. */
    def subView(from: Long, until: Long): ByteView

    /** Move the cursor to the given absolute position. */
    def goto(addr: Long)(using AllowUnsafe): Unit

    /** Number of bytes remaining from the cursor to the end. */
    def remaining: Long

    /** Current cursor position. */
    def position: Long

    /** Int-narrowing wrapper: Math.toIntExact(readEnd()). Use at TastyOrigin constructor sites. */
    final def readEndInt(using AllowUnsafe): Int = Math.toIntExact(readEnd())

    /** Int-narrowing wrapper: Math.toIntExact(position). Use at TastyOrigin constructor sites. */
    final def positionInt: Int = Math.toIntExact(position)

    /** Int-narrowing wrapper: Math.toIntExact(remaining). Use when Int-width remaining is needed. */
    final def remainingInt: Int = Math.toIntExact(remaining)

    /** Int-narrowing wrapper: goto(addr.toLong). */
    final def gotoInt(addr: Int)(using AllowUnsafe): Unit = goto(addr.toLong)

    /** Int-narrowing wrapper: subView(from.toLong, until.toLong). */
    final def subViewInt(from: Int, until: Int): ByteView = subView(from.toLong, until.toLong)

    /** The underlying raw byte array. Used by TreeUnpickler to obtain the section bytes for lazy body decode. */
    def allBytes: Array[Byte]

end ByteView

object ByteView:

    /** Create a Heap view over the full array. */
    def apply(bytes: Array[Byte]): Heap =
        new Heap(bytes, 0, bytes.length)

    /** Create a Heap view over a slice of an array. */
    def apply(bytes: Array[Byte], start: Int, end: Int): Heap =
        new Heap(bytes, start, end)

    /** In-memory ByteView backed by an Array[Byte].
      *
      * Invariants:
      *   - `start` and `end` are fixed after construction.
      *   - `cursor` is the mutable read position, always in range [start, end].
      *   - Callers must not read past `end`; `readByte` throws ArrayIndexOutOfBoundsException explicitly.
      */
    final class Heap(val bytes: Array[Byte], val start: Int, val end: Int) extends ByteView:

        private var cursor: Int = start

        def peekByte(at: Long): Byte = bytes(Math.toIntExact(at))

        def readByte()(using AllowUnsafe): Byte =
            if cursor >= end then throw new ArrayIndexOutOfBoundsException(cursor)
            val b = bytes(cursor)
            cursor += 1
            b
        end readByte

        /** Copy bytes from absolute positions [from, until) into a fresh array.
          *
          * Does not advance the cursor. Used for lazy UTF-8 decoding in the constant pool reader.
          */
        def copyBytes(from: Int, until: Int): Array[Byte] =
            val len = until - from
            val out = new Array[Byte](len)
            java.lang.System.arraycopy(bytes, from, out, 0, len)
            out
        end copyBytes

        def readEnd()(using AllowUnsafe): Long =
            val len = Varint.readNat(this)
            cursor.toLong + len

        override def subView(from: Long, until: Long): ByteView.Heap =
            val fromInt  = Math.toIntExact(from)
            val untilInt = Math.toIntExact(until)
            if fromInt < 0 || untilInt < fromInt || untilInt > bytes.length then
                throw new ArrayIndexOutOfBoundsException(
                    s"ByteView.subView: from=$fromInt until=$untilInt length=${bytes.length}"
                )
            end if
            new Heap(bytes, fromInt, untilInt)
        end subView

        def goto(addr: Long)(using AllowUnsafe): Unit =
            cursor = Math.toIntExact(addr)

        def remaining: Long = (end - cursor).toLong

        def position: Long = cursor.toLong

        def allBytes: Array[Byte] = bytes

    end Heap

    /** Base class for memory-mapped ByteView implementations.
      *
      * Platform-specific subclasses (MappedByteView in jvm/ and native/) extend this class. After the backing memory arena is closed, reads
      * throw IllegalStateException, which Symbol.body catches and maps to TastyError.ClasspathClosed.
      */
    abstract class Mapped extends ByteView:
        def allBytes: Array[Byte] = Array.empty[Byte]

end ByteView
