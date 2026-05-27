package kyo.internal.tasty.binary

/** A window into a byte buffer, tracking a mutable read cursor.
  *
  * ByteView is a sealed trait with two concrete cases:
  *   - Heap: backed by an in-memory Array[Byte] with start/end bounds and a mutable cursor.
  *   - NioBacked: backed by a java.nio.ByteBuffer (e.g. a direct / off-heap buffer from jar inflation).
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

    /** The absolute end boundary of this view (exclusive).
      *
      * For a Heap view this is the `end` field. For NioBacked this is the buffer limit. Used to construct fork sub-views from an absolute
      * address to the end of the full section.
      */
    def totalEnd: Int

    /** The underlying raw byte array. Used by TreeUnpickler to obtain the section bytes for lazy body decode.
      *
      * Deprecated: prefer passing ByteView directly to avoid materializing a heap Array[Byte] for NioBacked views. Returns an empty array
      * for NioBacked and Mapped views.
      */
    def allBytes: Array[Byte]

    /** Copy all bytes from [position, totalEnd) into a fresh heap Array[Byte].
      *
      * Used by `read()` callers that need a raw byte array (e.g. ModuleInfoReader, SnapshotReader). Does not advance the cursor.
      */
    def copyAllToArray(): Array[Byte] =
        val len = totalEnd - position
        val arr = new Array[Byte](len)
        var i   = position
        var j   = 0
        while j < len do
            arr(j) = peekByte(i)
            i += 1
            j += 1
        end while
        arr
    end copyAllToArray

end ByteView

object ByteView:

    /** Create a Heap view over the full array. */
    def apply(bytes: Array[Byte]): Heap =
        new Heap(bytes, 0, bytes.length)

    /** Create a Heap view over a slice of an array. */
    def apply(bytes: Array[Byte], start: Int, end: Int): Heap =
        new Heap(bytes, start, end)

    /** Create a NioBacked view over a java.nio.ByteBuffer. The buffer's current position is treated as byte-address 0; limit is the
      * exclusive end. The buffer must not be modified after this call.
      */
    def apply(buf: java.nio.ByteBuffer): NioBacked =
        new NioBacked(buf, buf.position(), buf.limit(), buf.position())

    /** In-memory ByteView backed by an Array[Byte].
      *
      * Invariants:
      *   - `start` and `end` are fixed after construction.
      *   - `cursor` is the mutable read position, always in range [start, end].
      *   - Callers must not read past `end`; `readByte` throws ArrayIndexOutOfBoundsException explicitly.
      */
    final class Heap(val bytes: Array[Byte], val start: Int, val end: Int) extends ByteView:

        private var cursor: Int = start

        def peekByte(at: Int): Byte = bytes(at)

        def readByte(): Byte =
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

        def readEnd(): Int =
            val len = Varint.readNat(this)
            cursor + len

        override def subView(from: Int, until: Int): ByteView.Heap =
            new Heap(bytes, from, until)

        def goto(addr: Int): Unit =
            cursor = addr

        def remaining: Int = end - cursor

        def position: Int = cursor

        def totalEnd: Int = end

        def allBytes: Array[Byte] = bytes

        override def copyAllToArray(): Array[Byte] =
            val len = end - start
            val arr = new Array[Byte](len)
            java.lang.System.arraycopy(bytes, start, arr, 0, len)
            arr
        end copyAllToArray

    end Heap

    /** ByteView backed by a java.nio.ByteBuffer.
      *
      * Supports both heap-backed and direct (off-heap) ByteBuffers. Used to route jar entry inflation output into an off-heap direct
      * buffer, eliminating the large heap Array[Byte] allocation on the DEFLATED read path.
      *
      * Invariants:
      *   - `bufStart` is the base offset of this view within the underlying ByteBuffer (buffers may be slices).
      *   - `bufEnd` is the exclusive end (buffer limit from construction time).
      *   - `cursor` is the mutable read position, always in range [bufStart, bufEnd].
      *   - Sub-views share the same underlying ByteBuffer; each has its own independent cursor.
      *   - The ByteBuffer must not be modified after this view is constructed.
      */
    final class NioBacked(
        private val buf: java.nio.ByteBuffer,
        private val bufStart: Int,
        private val bufEnd: Int,
        private var cursor: Int
    ) extends ByteView:

        def peekByte(at: Int): Byte = buf.get(at)

        def readByte(): Byte =
            if cursor >= bufEnd then throw new ArrayIndexOutOfBoundsException(cursor)
            val b = buf.get(cursor)
            cursor += 1
            b
        end readByte

        def readEnd(): Int =
            val len = Varint.readNat(this)
            cursor + len

        def subView(from: Int, until: Int): ByteView.NioBacked =
            new NioBacked(buf, from, until, from)

        def goto(addr: Int): Unit =
            cursor = addr

        def remaining: Int = bufEnd - cursor

        def position: Int = cursor

        def totalEnd: Int = bufEnd

        def allBytes: Array[Byte] = Array.empty[Byte]

        override def copyAllToArray(): Array[Byte] =
            val len = bufEnd - bufStart
            val arr = new Array[Byte](len)
            val dup = buf.duplicate()
            dup.position(bufStart)
            dup.get(arr, 0, len)
            arr
        end copyAllToArray

    end NioBacked

    /** Base class for memory-mapped ByteView implementations.
      *
      * Platform-specific subclasses (MappedByteView in jvm/ and native/) extend this class. After the backing memory arena is closed, reads
      * throw IllegalStateException, which Symbol.body catches and maps to TastyError.ClasspathClosed.
      */
    abstract class Mapped extends ByteView:
        def allBytes: Array[Byte] = Array.empty[Byte]

end ByteView
