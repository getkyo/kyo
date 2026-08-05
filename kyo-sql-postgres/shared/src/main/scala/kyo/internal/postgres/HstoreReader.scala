package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlCodec.Format
import kyo.SqlDecodeHstoreFormatException
import kyo.SqlDecodeInsufficientBytesException

/** Parses a PostgreSQL `hstore` column value in either wire format and exposes alternating key / value access.
  *
  * The PG hstore binary format (PostgreSQL `contrib/hstore`, see `hstore.c`) is:
  * {{{
  *   Int32 entryCount            -- number of key/value pairs, big-endian
  *   [for each entry:]
  *     Int32 keyLen              -- byte length of UTF-8 key, big-endian
  *     <keyLen bytes>            -- key bytes (always non-NULL)
  *     Int32 valLen              -- byte length of UTF-8 value, OR -1 for SQL NULL
  *     <valLen bytes>            -- value bytes, omitted when valLen == -1
  * }}}
  *
  * The text format is `hstore_out`'s rendering, `"a"=>"1", "b"=>NULL`: keys and non-NULL values double-quoted with `\"` and `\\` escapes, a
  * NULL value spelled as an unquoted `NULL`, and entries separated by `, `. A bare token is accepted where the rendering would have quoted,
  * since `hstore_in` accepts one. Both formats reach here because `SimpleQueryExchange` receives every column in text, so an hstore column
  * read through `simpleQuery` would otherwise be handed to the binary header parser and report an entry count taken from four ASCII bytes.
  *
  * The hstore OID is not fixed across PG installations because hstore is a contrib extension; callers identify the OID at session startup
  * via `pg_type` lookup. [[kyo.internal.postgres.PostgresRowReader.mapStart]] dispatches to this reader for any non-JSON / non-JSONB OID,
  * leaving JSON object decoding to [[kyo.internal.JsonReader]] + [[kyo.internal.reader.JsonStructureCounter]].
  *
  * After [[openMap]], call [[nextKey]] / [[nextValue]] (or [[nextValueBytes]]) in strict alternation per entry. [[hasNext]] returns true
  * while entries remain.
  *
  * @param bytes
  *   the raw bytes of the hstore column value (no leading length prefix)
  * @param format
  *   the wire format the row carries
  * @param readerFrame
  *   the frame from the enclosing row reader, used to construct decode exceptions
  */
final class HstoreReader(bytes: Span[Byte], format: Format, readerFrame: Frame):

    private var pos: Int              = 0
    private var entriesRemaining: Int = 0
    // Strict alternating state: true means the next call must be nextKey(); false means nextValue.
    private var expectingKey: Boolean = true

    // Text-format entries, parsed in full by openMap. The rendering carries no lengths and no count, so
    // neither the entry count nor an entry's end can be found without scanning it.
    private var textEntries: Chunk[(String, Maybe[Span[Byte]])] = Chunk.empty
    private var textIndex: Int                                  = 0

    /** Whether there are unread entries left in the map. */
    def hasNext: Boolean = entriesRemaining > 0

    /** Parses the value's header, or its whole rendering under [[Format.Text]], and returns the entry count.
      *
      * @return
      *   number of key/value pairs in the hstore
      * @throws SqlDecodeInsufficientBytesException
      *   if a binary value is shorter than its 4-byte header
      * @throws SqlDecodeHstoreFormatException
      *   if the header count is negative, or a text rendering is malformed
      */
    def openMap(): Int = format match
        case Format.Binary => openBinaryMap()
        case Format.Text   => openTextMap()

    /** Read the next entry's key as a UTF-8 [[String]].
      *
      * Hstore keys are always non-NULL; a `-1` keyLen sentinel is a wire-format error.
      *
      * @throws SqlDecodeHstoreFormatException
      *   if called out of order (value expected next), if there are no entries remaining, or if the key length is invalid
      * @throws SqlDecodeInsufficientBytesException
      *   if the key body is truncated
      */
    def nextKey(): String =
        given Frame = readerFrame
        if !expectingKey then
            throw SqlDecodeHstoreFormatException(entriesRemaining, 0, 0, pos)
        if entriesRemaining <= 0 then
            throw SqlDecodeHstoreFormatException(0, 0, 0, pos)
        format match
            case Format.Text =>
                expectingKey = false
                textEntries(textIndex)._1
            case Format.Binary =>
                val keyLen = readInt32BE()
                if keyLen < 0 then
                    throw SqlDecodeHstoreFormatException(entriesRemaining, keyLen, 0, pos - 4)
                if pos + keyLen > bytes.size then
                    throw SqlDecodeInsufficientBytesException("hstore key", keyLen, bytes.size - pos, pos)
                end if
                val keyArr = bytes.slice(pos, pos + keyLen).toArray
                pos += keyLen
                expectingKey = false
                new String(keyArr, StandardCharsets.UTF_8)
        end match
    end nextKey

    /** Read the next entry's value as `Maybe.Present(String)` or `Maybe.Absent` when the value is SQL NULL.
      *
      * @throws SqlDecodeHstoreFormatException
      *   if called out of order (key expected next) or if the value length is invalid
      * @throws SqlDecodeInsufficientBytesException
      *   if the value body is truncated
      */
    def nextValue(): Maybe[String] =
        nextValueBytes() match
            case Absent             => Absent
            case Maybe.Present(buf) => Maybe.Present(new String(buf.toArray, StandardCharsets.UTF_8))

    /** Read the next entry's value as raw bytes; `Absent` for SQL NULL. */
    def nextValueBytes(): Maybe[Span[Byte]] =
        given Frame = readerFrame
        if expectingKey then
            throw SqlDecodeHstoreFormatException(entriesRemaining, 0, 0, pos)
        format match
            case Format.Text =>
                val value = textEntries(textIndex)._2
                textIndex += 1
                expectingKey = true
                entriesRemaining -= 1
                value
            case Format.Binary =>
                val valLen = readInt32BE()
                expectingKey = true
                entriesRemaining -= 1
                if valLen == -1 then Absent
                else if valLen < 0 then
                    throw SqlDecodeHstoreFormatException(entriesRemaining + 1, 0, valLen, pos - 4)
                else if pos + valLen > bytes.size then
                    throw SqlDecodeInsufficientBytesException("hstore value", valLen, bytes.size - pos, pos)
                else
                    val out = bytes.slice(pos, pos + valLen)
                    pos += valLen
                    Maybe.Present(out)
                end if
        end match
    end nextValueBytes

    // --- Internal helpers ---

    private def openBinaryMap(): Int =
        given Frame = readerFrame
        if bytes.size < 4 then
            throw SqlDecodeInsufficientBytesException("hstore header", 4, bytes.size, 0)
        end if
        pos = 0
        val count = readInt32BE()
        if count < 0 then
            throw SqlDecodeHstoreFormatException(count, 0, 0, pos)
        entriesRemaining = count
        expectingKey = true
        count
    end openBinaryMap

    private def openTextMap(): Int =
        given Frame = readerFrame
        textEntries = HstoreReader.parseText(new String(bytes.toArray, StandardCharsets.UTF_8))
        textIndex = 0
        entriesRemaining = textEntries.size
        expectingKey = true
        entriesRemaining
    end openTextMap

    private def readInt32BE(): Int =
        val v =
            ((bytes(pos) & 0xff) << 24) |
                ((bytes(pos + 1) & 0xff) << 16) |
                ((bytes(pos + 2) & 0xff) << 8) |
                (bytes(pos + 3) & 0xff)
        pos += 4
        v
    end readInt32BE

end HstoreReader

object HstoreReader:

    /** Parses `hstore_out`'s rendering into its entries.
      *
      * `"a"=>"1", "b"=>NULL` is the canonical output shape: both sides double-quoted, a NULL value unquoted. A bare token is accepted on
      * either side because `hstore_in` accepts one, and a bare `NULL` value is the null entry while a quoted `"NULL"` is the four-character
      * string, which is the distinction the quoting exists to keep.
      */
    private[postgres] def parseText(text: String)(using Frame): Chunk[(String, Maybe[Span[Byte]])] =
        val builder = Chunk.newBuilder[(String, Maybe[Span[Byte]])]
        var i       = 0

        def skipSpaces(): Unit =
            while i < text.length && text.charAt(i).isWhitespace do i += 1

        /** Reads one quoted or bare token, returning its text and whether it was quoted. */
        def readToken(): (String, Boolean) =
            if i < text.length && text.charAt(i) == '"' then
                i += 1
                val out    = new StringBuilder
                var closed = false
                while i < text.length && !closed do
                    val c = text.charAt(i)
                    if c == '\\' && i + 1 < text.length then
                        out.append(text.charAt(i + 1))
                        i += 2
                    else if c == '"' then
                        closed = true
                        i += 1
                    else
                        out.append(c)
                        i += 1
                    end if
                end while
                if !closed then throw SqlDecodeHstoreFormatException(builder.result().size, 0, 0, i)
                (out.toString, true)
            else
                val start = i
                while i < text.length && !text.charAt(i).isWhitespace && text.charAt(i) != ',' && text.charAt(i) != '=' do i += 1
                if i == start then throw SqlDecodeHstoreFormatException(builder.result().size, 0, 0, i)
                (text.substring(start, i), false)
            end if
        end readToken

        skipSpaces()
        while i < text.length do
            val (key, _) = readToken()
            skipSpaces()
            if i + 1 >= text.length || text.charAt(i) != '=' || text.charAt(i + 1) != '>' then
                throw SqlDecodeHstoreFormatException(0, 0, 0, i)
            end if
            i += 2
            skipSpaces()
            val (value, valueQuoted) = readToken()
            val entry =
                if !valueQuoted && value.equalsIgnoreCase("NULL") then Maybe.empty[Span[Byte]]
                else Maybe.Present(Span.from(value.getBytes(StandardCharsets.UTF_8)))
            builder.addOne((key, entry))
            skipSpaces()
            if i < text.length then
                if text.charAt(i) != ',' then throw SqlDecodeHstoreFormatException(0, 0, 0, i)
                i += 1
                skipSpaces()
            end if
        end while
        builder.result()
    end parseText

end HstoreReader
