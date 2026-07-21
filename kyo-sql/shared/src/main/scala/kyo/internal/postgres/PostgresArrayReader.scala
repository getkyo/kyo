package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Codec
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlException
import kyo.internal.JsonReader
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder

/** Parses the PostgreSQL binary array wire format and delegates element reads back to the row reader.
  *
  * The PostgreSQL binary array format (see src/backend/utils/adt/arrayfuncs.c) has the following header layout:
  * {{{
  *   Int32 ndim         -- number of dimensions (we only support 1)
  *   Int32 hasnulls     -- 1 if any element is NULL, 0 otherwise
  *   Int32 elemOID      -- OID of the element type
  *   [for each dimension:]
  *     Int32 dim_size   -- number of elements
  *     Int32 lbound     -- lower bound (always 1 for PG arrays)
  *   [for each element:]
  *     Int32 elemLen    -- byte length of element, or -1 for NULL
  *     <elemLen bytes>  -- element data (only when elemLen >= 0)
  * }}}
  *
  * After the header is parsed, elements are read one at a time by [[nextElement]], which returns the raw bytes for the element and advances
  * the cursor. The cursor state is maintained by this object: [[remaining]] tracks how many elements have not yet been read.
  *
  * Recognised array OIDs (element types):
  *   - 1007 = _int4 (int4[])
  *   - 1009 = _text (text[])
  *   - 1015 = _varchar (varchar[])
  *   - 199 = _json (json[])
  *   - 3807 = _jsonb (jsonb[])
  *
  * @param bytes
  *   the raw bytes of the array column value (binary-format, no leading length prefix)
  * @param readerFrame
  *   the frame from the enclosing row reader, used to construct decode exceptions
  */
final class PostgresArrayReader(bytes: Span[Byte], readerFrame: Frame):

    private var pos: Int        = 0
    private var _remaining: Int = 0

    /** Number of elements remaining to be read in the current dimension. Decremented by each [[nextElement]] call. */
    def remaining: Int = _remaining

    /** Whether there are elements left to read. */
    def hasNext: Boolean = _remaining > 0

    /** Parses the array header and sets [[remaining]] to the first dimension's element count.
      *
      * @return
      *   the element count (first dimension's `dim_size`)
      * @throws SqlException.Decode
      *   if the header is malformed or the array has more than one dimension
      */
    def openArray(): Int =
        if bytes.size < 12 then
            throw SqlException.Decode(
                s"PG array binary format: header too short (${bytes.size} bytes, expected ≥ 12)",
                Absent,
                readerFrame
            )
        end if
        pos = 0
        val ndim     = readInt32BE()
        val _hasnull = readInt32BE()
        val _elemOid = readInt32BE()
        if ndim == 0 then
            // Empty array with zero dimensions
            _remaining = 0
            0
        else if ndim != 1 then
            throw SqlException.Decode(
                s"PG array binary format: multidimensional arrays (ndim=$ndim) not yet supported",
                Absent,
                readerFrame
            )
        else
            val dimSize = readInt32BE()
            val _lbound = readInt32BE() // lower bound, unused
            _remaining = dimSize
            dimSize
        end if
    end openArray

    /** Reads the raw bytes for the next element and decrements [[remaining]].
      *
      * @return
      *   `Maybe.Present(bytes)` for a non-NULL element, `Maybe.Absent` for a NULL element
      * @throws SqlException.Decode
      *   if there are no elements remaining or if the length prefix is malformed
      */
    def nextElement(): Maybe[Span[Byte]] =
        if _remaining <= 0 then
            throw SqlException.Decode("PG array binary format: nextElement called with no elements remaining", Absent, readerFrame)
        _remaining -= 1
        if pos + 4 > bytes.size then
            throw SqlException.Decode(
                s"PG array binary format: truncated element length at offset $pos (total ${bytes.size} bytes)",
                Absent,
                readerFrame
            )
        end if
        val elemLen = readInt32BE()
        if elemLen == -1 then
            // NULL element
            Absent
        else if elemLen < 0 then
            throw SqlException.Decode(s"PG array binary format: invalid element length $elemLen at offset ${pos - 4}", Absent, readerFrame)
        else if pos + elemLen > bytes.size then
            throw SqlException.Decode(
                s"PG array binary format: element data truncated (need $elemLen bytes at offset $pos, total ${bytes.size})",
                Absent,
                readerFrame
            )
        else
            val slice = bytes.slice(pos, pos + elemLen)
            pos += elemLen
            Maybe.Present(slice)
        end if
    end nextElement

    // --- Internal helpers ---

    private def readInt32BE(): Int =
        val v =
            ((bytes(pos) & 0xff) << 24) |
                ((bytes(pos + 1) & 0xff) << 16) |
                ((bytes(pos + 2) & 0xff) << 8) |
                (bytes(pos + 3) & 0xff)
        pos += 4
        v
    end readInt32BE

end PostgresArrayReader

object PostgresArrayReader:

    /** Recognised PostgreSQL array OIDs (column-level, i.e. the OID stored in FieldDescription.dataType). */
    val ArrayOids: Set[Int] = Set(
        1007, // _int4 (int4[])
        1009, // _text (text[])
        1015, // _varchar (varchar[])
        199,  // _json (json[])
        3807  // _jsonb (jsonb[])
    )

    /** Returns true if `oid` is a recognised PostgreSQL array OID. */
    def isArrayOid(oid: Int): Boolean = ArrayOids.contains(oid)

end PostgresArrayReader
