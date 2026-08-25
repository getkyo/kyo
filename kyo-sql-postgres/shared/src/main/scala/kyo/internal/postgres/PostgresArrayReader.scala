package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeArrayFormatException
import kyo.SqlDecodeInsufficientBytesException
import kyo.internal.postgres.types.PostgresEncoder

/** Parses a PostgreSQL one-dimensional array column value in either wire format and hands out its elements.
  *
  * The binary format (see `src/backend/utils/adt/arrayfuncs.c`) has this header layout:
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
  * The text format is `array_out`'s rendering, `{1,2,3}`, with an element double-quoted when it is empty, is the word `NULL`, or contains a
  * comma, a brace, a quote, a backslash, or whitespace, and with `\"` and `\\` escapes inside the quotes. An unquoted `NULL` is a NULL
  * element.
  *
  * Both formats reach here because `SimpleQueryExchange` receives every column in text, so an array column read through `simpleQuery` would
  * otherwise be handed to the binary header parser: the first four bytes of `{1,2,3}` read as an `ndim` of 2054844416.
  *
  * [[elementFormat]] is the format the elements themselves are in, which is this array's own. Element decoders need it for the same reason
  * the column decoders do, and hardcoding binary there is the same defect one level down.
  *
  * After the header is parsed, elements are read one at a time by [[nextElement]]. [[remaining]] tracks how many have not been read.
  *
  * Recognised array OIDs (element types):
  *   - 1007 = _int4 (int4[])
  *   - 1009 = _text (text[])
  *   - 1015 = _varchar (varchar[])
  *   - 199 = _json (json[])
  *   - 3807 = _jsonb (jsonb[])
  *
  * @param bytes
  *   the raw bytes of the array column value (no leading length prefix)
  * @param format
  *   the wire format the row carries, which is also the format of every element
  * @param readerFrame
  *   the frame from the enclosing row reader, used to construct decode exceptions
  */
final class PostgresArrayReader(bytes: Span[Byte], format: Format, readerFrame: Frame):

    private var pos: Int         = 0
    private var _remaining: Int  = 0
    private var _elementOid: Int = PostgresEncoder.OID_UNSPECIFIED

    // Elements handed out so far, counted for both wire formats so an element's own position in the array is
    // reportable. Advanced only after a read succeeds, so a throwing read hands out nothing and counts nothing.
    private var consumed: Int = 0

    // Text-format elements, parsed in full by openArray. A text rendering carries no element lengths, so an
    // element cannot be found without scanning quotes and escapes to the end of it anyway.
    private var textElements: Chunk[Maybe[Span[Byte]]] = Chunk.empty
    private var textIndex: Int                         = 0

    /** Number of elements remaining to be read in the current dimension. Decremented by each [[nextElement]] call. */
    def remaining: Int = _remaining

    /** The wire format the elements are in, which is the array's own. */
    def elementFormat: Format = format

    /** The element type's OID from the array header, which the element decoders need for the same reason a column decoder needs the column's:
      * an element's wire width is the element type's, not the Scala type's. [[PostgresEncoder.OID_UNSPECIFIED]] before [[openArray]] runs, and
      * under [[Format.Text]], where the rendering names no element type and every decoder resolves the value from its own digits.
      */
    def elementOid: Int = _elementOid

    /** Whether there are elements left to read. */
    def hasNext: Boolean = _remaining > 0

    /** The zero-based position in the array of the element the next [[nextElement]] will return.
      *
      * Total at every point: 0 before the first read, and the element count once the array is exhausted. The row reader captures it before
      * each read so that a NULL element names its own position, rather than reporting a position the reader would have to invent. A read
      * whose result is discarded, which is how the row reader skips an element, advances it too: a skipped element still occupied a
      * position.
      */
    def nextElementIndex: Int = consumed

    /** Parses the array value's header, or its whole rendering under [[Format.Text]], and sets [[remaining]] to the element count.
      *
      * @return
      *   the element count
      * @throws SqlDecodeArrayFormatException
      *   if the value is malformed or the array has more than one dimension
      */
    def openArray(): Int = format match
        case Format.Binary => openBinaryArray()
        case Format.Text   => openTextArray()

    /** Reads the next element's bytes, in [[elementFormat]], and decrements [[remaining]].
      *
      * @return
      *   `Maybe.Present(bytes)` for a non-NULL element, `Maybe.Absent` for a NULL element
      * @throws SqlDecodeArrayFormatException
      *   if there are no elements remaining or if a binary length prefix is malformed
      * @throws SqlDecodeInsufficientBytesException
      *   if binary element data is truncated
      */
    def nextElement(): Maybe[Span[Byte]] =
        val element = format match
            case Format.Binary => nextBinaryElement()
            case Format.Text   => nextTextElement()
        consumed += 1
        element
    end nextElement

    // --- Binary format ---

    private def openBinaryArray(): Int =
        given Frame = readerFrame
        if bytes.size < 12 then
            throw SqlDecodeArrayFormatException(0, bytes.size, 0)
        end if
        pos = 0
        val ndim     = readInt32BE()
        val _hasnull = readInt32BE()
        _elementOid = readInt32BE()
        if ndim == 0 then
            // Empty array with zero dimensions
            _remaining = 0
            0
        else if ndim != 1 then
            throw SqlDecodeArrayFormatException(ndim, 0, pos)
        else
            val dimSize = readInt32BE()
            val _lbound = readInt32BE() // lower bound, unused
            _remaining = dimSize
            dimSize
        end if
    end openBinaryArray

    private def nextBinaryElement(): Maybe[Span[Byte]] =
        given Frame = readerFrame
        if _remaining <= 0 then
            throw SqlDecodeArrayFormatException(1, 0, pos)
        _remaining -= 1
        if pos + 4 > bytes.size then
            throw SqlDecodeInsufficientBytesException("array element length", 4, bytes.size - pos, pos)
        end if
        val elemLen = readInt32BE()
        if elemLen == -1 then
            // NULL element
            Absent
        else if elemLen < 0 then
            throw SqlDecodeArrayFormatException(1, elemLen, pos - 4)
        else if pos + elemLen > bytes.size then
            throw SqlDecodeInsufficientBytesException("array element", elemLen, bytes.size - pos, pos)
        else
            val slice = bytes.slice(pos, pos + elemLen)
            pos += elemLen
            Maybe.Present(slice)
        end if
    end nextBinaryElement

    // --- Text format ---

    private def openTextArray(): Int =
        given Frame = readerFrame
        val text    = new String(bytes.toArray, StandardCharsets.UTF_8).trim
        // PostgreSQL prefixes an explicit dimension range whenever a lower bound is not 1, as in
        // `[0:2]={1,2,3}`. A Chunk has no lower bound, so the range is skipped exactly as the binary
        // header's `lbound` field is read and discarded.
        val body =
            if text.startsWith("[") then
                val rangeEnd = text.indexOf("]=")
                if rangeEnd < 0 then throw SqlDecodeArrayFormatException(0, bytes.size, 0)
                text.substring(rangeEnd + 2)
            else text
        if body.length < 2 || !body.startsWith("{") || !body.endsWith("}") then
            throw SqlDecodeArrayFormatException(0, bytes.size, 0)
        end if
        textElements = parseTextElements(body.substring(1, body.length - 1))
        textIndex = 0
        _remaining = textElements.size
        _remaining
    end openTextArray

    /** Splits the inside of a `{...}` rendering into its elements, resolving quotes, escapes, and the unquoted `NULL`. */
    private def parseTextElements(inner: String)(using Frame): Chunk[Maybe[Span[Byte]]] =
        if inner.trim.isEmpty then Chunk.empty
        else
            val builder   = Chunk.newBuilder[Maybe[Span[Byte]]]
            val current   = new StringBuilder
            var quoted    = false
            var wasQuoted = false
            var i         = 0

            def emit(): Unit =
                val raw = if wasQuoted then current.toString else current.toString.trim
                // Only an UNQUOTED NULL is the null element; PostgreSQL quotes a literal "NULL" string
                // precisely so that the two stay distinguishable.
                if !wasQuoted && raw.equalsIgnoreCase("NULL") then builder.addOne(Absent)
                else builder.addOne(Maybe.Present(Span.from(raw.getBytes(StandardCharsets.UTF_8))))
                current.clear()
                wasQuoted = false
            end emit

            while i < inner.length do
                val c = inner.charAt(i)
                if quoted then
                    if c == '\\' && i + 1 < inner.length then
                        current.append(inner.charAt(i + 1))
                        i += 2
                    else if c == '"' then
                        quoted = false
                        i += 1
                    else
                        current.append(c)
                        i += 1
                    end if
                else if c == '"' then
                    quoted = true
                    wasQuoted = true
                    i += 1
                else if c == ',' then
                    emit()
                    i += 1
                else if c == '{' || c == '}' then
                    // A nested brace is a second dimension, which the binary path rejects through its
                    // `ndim` field and which a Chunk cannot carry either way.
                    throw SqlDecodeArrayFormatException(2, 0, i)
                else
                    current.append(c)
                    i += 1
                end if
            end while
            if quoted then throw SqlDecodeArrayFormatException(1, 0, inner.length)
            emit()
            builder.result()
        end if
    end parseTextElements

    private def nextTextElement(): Maybe[Span[Byte]] =
        given Frame = readerFrame
        if _remaining <= 0 then
            throw SqlDecodeArrayFormatException(1, 0, textIndex)
        _remaining -= 1
        val element = textElements(textIndex)
        textIndex += 1
        element
    end nextTextElement

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
