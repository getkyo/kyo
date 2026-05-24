package kyo.internal.reflect.binary

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
    def peekByte(at: Int): Byte

    /** Read the byte at the current cursor position and advance cursor by 1. */
    def readByte(): Byte

    /** Read an unsigned LEB128 Nat (big-endian base-128, stop-bit on last byte) as Int. */
    def readNat(): Int = Varint.readNat(this)

    /** Read a signed 2's complement big-endian base-128 Int (dotty TastyReader semantics). */
    def readInt(): Int = Varint.readInt(this)

    /** Read an unsigned LEB128 Nat as Long. */
    def readLongNat(): Long = Varint.readLongNat(this)

    /** Read a Nat as the payload length, then return the absolute end address (cursor + nat). */
    def readEnd(): Int

    /** Return a sub-view sharing the same underlying bytes, with its own cursor reset to `from`. */
    def subView(from: Int, until: Int): ByteView

    /** Move the cursor to the given absolute position. */
    def goto(addr: Int): Unit

    /** Number of bytes remaining from the cursor to the end. */
    def remaining: Int

    /** Current cursor position. */
    def position: Int

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
      *   - Callers must not read past `end`; ArrayIndexOutOfBoundsException is the intended signal.
      */
    final class Heap(val bytes: Array[Byte], val start: Int, val end: Int) extends ByteView:

        private var cursor: Int = start

        def peekByte(at: Int): Byte = bytes(at)

        def readByte(): Byte =
            val b = bytes(cursor)
            cursor += 1
            b
        end readByte

        def readEnd(): Int =
            val len = Varint.readNat(this)
            cursor + len

        def subView(from: Int, until: Int): ByteView =
            new Heap(bytes, from, until)

        def goto(addr: Int): Unit =
            cursor = addr

        def remaining: Int = end - cursor

        def position: Int = cursor

    end Heap

    /** Stub for memory-mapped file support. Wired in Phase 7. */
    sealed abstract class Mapped extends ByteView

end ByteView
