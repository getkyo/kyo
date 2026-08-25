package kyo.internal

import kyo.Span
import kyo.net.internal.util.GrowableByteBuffer

/** The format-agnostic core of a wire-protocol byte writer.
  *
  * Backed by a [[GrowableByteBuffer]] that grows automatically. Call [[toSpan]] to obtain an immutable snapshot of the written bytes; further
  * writes after that call do not affect the returned [[Span]]. A subclass adds the writes its protocol needs: the multi-byte integers whose
  * byte order is the protocol's, the length-prefixed and NUL-terminated strings, and any back-patching its framing needs. What lives here is
  * only what writes the same bytes for every protocol: the single-byte and raw-bytes writes, the size, and the snapshot.
  */
abstract class SqlBufferWriter:
    private[kyo] val buf = new GrowableByteBuffer

    /** Returns an immutable snapshot of the bytes written so far.
      *
      * Further writes after this call do not affect the returned [[Span]].
      */
    def toSpan: Span[Byte] = Span.from(buf.toByteArray)

    /** Current number of bytes written. */
    def size: Int = buf.size

    /** Writes a single byte. */
    def writeByte(b: Byte): Unit = buf.writeByte(b)

    /** Writes raw bytes from a [[Span]]. */
    def writeBytes(span: Span[Byte]): Unit =
        val arr = span.toArray
        buf.writeBytes(arr, 0, arr.length)

    /** Writes raw bytes from a raw Array[Byte]. Callers should prefer [[writeBytes(Span[Byte])]] when possible. */
    @scala.annotation.targetName("writeBytesArray")
    def writeBytes(arr: Array[Byte]): Unit =
        buf.writeBytes(arr, 0, arr.length)

end SqlBufferWriter
