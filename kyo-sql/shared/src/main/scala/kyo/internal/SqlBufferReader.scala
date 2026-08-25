package kyo.internal

import kyo.*
import kyo.Span
import kyo.SqlDecodeException
import kyo.SqlDecodeInsufficientBytesException

/** The format-agnostic core of a wire-protocol byte reader.
  *
  * Backed by an immutable [[Span[Byte]]]. The reader maintains a mutable position cursor internally; all read methods advance the cursor. A
  * subclass adds the reads its protocol needs: the multi-byte integers whose byte order is the protocol's, the length-prefixed and
  * NUL-terminated strings, and whatever else the wire carries. What lives here is only what reads the same bytes for every protocol: the
  * single-byte and raw-slice reads, and the cursor those reads move.
  *
  * `readByte` and `readBytes` perform a bounds check before any array access. On under-length input they return
  * `Abort.fail(SqlDecodeInsufficientBytesException(...))` rather than throwing an exception.
  *
  * @param span
  *   the immutable byte span to read from
  */
abstract class SqlBufferReader(private[kyo] val span: Span[Byte]):
    // Not thread-safe by design: one reader per message parse, never shared.
    private[kyo] var pos = 0

    /** Returns the number of bytes remaining to be read. */
    def remaining: Int = span.size - pos

    /** Reads a single byte and advances the cursor. */
    def readByte()(using Frame): Byte < Abort[SqlDecodeException] =
        if pos >= span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", 1, span.size - pos, pos))
        else
            val b = span(pos)
            pos += 1
            b
    end readByte

    /** Reads exactly `n` bytes and returns them as an immutable [[Span[Byte]]]. Advances the cursor by `n`.
      *
      * If fewer than `n` bytes remain, returns `Abort.fail(SqlDecodeInsufficientBytesException(...))`.
      */
    def readBytes(n: Int)(using Frame): Span[Byte] < Abort[SqlDecodeException] =
        if n < 0 || pos + n > span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", n, span.size - pos, pos))
        else
            val result = span.slice(pos, pos + n)
            pos += n
            result
    end readBytes

end SqlBufferReader
