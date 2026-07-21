package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlException

/** Parses the PostgreSQL `hstore` binary wire format and exposes alternating key / value access.
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
  * The hstore OID is not fixed across PG installations because hstore is a contrib extension; callers identify the OID at session startup
  * via `pg_type` lookup. [[kyo.internal.postgres.PostgresRowReader.mapStart]] dispatches to this reader for any non-JSON / non-JSONB OID,
  * leaving JSON object decoding to [[kyo.internal.JsonReader]] + [[kyo.internal.JsonStructureCounter]].
  *
  * After [[openMap]], call [[nextKey]] / [[nextValue]] (or [[nextValueBytes]]) in strict alternation per entry. [[hasNext]] returns true
  * while entries remain. [[closed]] becomes true after the final value is read.
  *
  * @param bytes
  *   the raw bytes of the hstore column value (binary-format, no leading length prefix)
  * @param readerFrame
  *   the frame from the enclosing row reader, used to construct decode exceptions
  */
final class HstoreReader(bytes: Span[Byte], readerFrame: Frame):

    private var pos: Int              = 0
    private var entriesRemaining: Int = 0
    // Strict alternating state: true means the next call must be nextKey(); false means nextValue.
    private var expectingKey: Boolean = true

    /** Whether there are unread entries left in the map. */
    def hasNext: Boolean = entriesRemaining > 0

    /** Parse the 4-byte header and return the entry count.
      *
      * @return
      *   number of key/value pairs in the hstore
      * @throws SqlException.Decode
      *   if the bytes are shorter than 4 bytes (header truncated)
      */
    def openMap(): Int =
        if bytes.size < 4 then
            throw SqlException.Decode(
                s"PG hstore binary format: header too short (${bytes.size} bytes, expected ≥ 4)",
                Absent,
                readerFrame
            )
        end if
        pos = 0
        val count = readInt32BE()
        if count < 0 then
            throw SqlException.Decode(s"PG hstore binary format: negative entry count $count", Absent, readerFrame)
        entriesRemaining = count
        expectingKey = true
        count
    end openMap

    /** Read the next entry's key as a UTF-8 [[String]].
      *
      * Hstore keys are always non-NULL; a `-1` keyLen sentinel is a wire-format error.
      *
      * @throws SqlException.Decode
      *   if called out of order (value expected next), if there are no entries remaining, or if the key length is invalid / the body is
      *   truncated
      */
    def nextKey(): String =
        if !expectingKey then
            throw SqlException.Decode("PG hstore: nextKey() called when a value was expected", Absent, readerFrame)
        if entriesRemaining <= 0 then
            throw SqlException.Decode("PG hstore: nextKey() called with no entries remaining", Absent, readerFrame)
        val keyLen = readInt32BE()
        if keyLen < 0 then
            throw SqlException.Decode(s"PG hstore: invalid keyLen=$keyLen (key is never NULL)", Absent, readerFrame)
        if pos + keyLen > bytes.size then
            throw SqlException.Decode(
                s"PG hstore: key body truncated (need $keyLen bytes at offset $pos, total ${bytes.size})",
                Absent,
                readerFrame
            )
        end if
        val keyArr = bytes.slice(pos, pos + keyLen).toArray
        pos += keyLen
        expectingKey = false
        new String(keyArr, StandardCharsets.UTF_8)
    end nextKey

    /** Read the next entry's value as `Maybe.Present(String)` or `Maybe.Absent` when the value is SQL NULL.
      *
      * @throws SqlException.Decode
      *   if called out of order (key expected next) or if the value body is truncated
      */
    def nextValue(): Maybe[String] =
        nextValueBytes() match
            case Absent             => Absent
            case Maybe.Present(buf) => Maybe.Present(new String(buf.toArray, StandardCharsets.UTF_8))

    /** Read the next entry's value as raw bytes; `Absent` for SQL NULL. */
    def nextValueBytes(): Maybe[Span[Byte]] =
        if expectingKey then
            throw SqlException.Decode("PG hstore: nextValue() called when a key was expected", Absent, readerFrame)
        val valLen = readInt32BE()
        expectingKey = true
        entriesRemaining -= 1
        if valLen == -1 then Absent
        else if valLen < 0 then
            throw SqlException.Decode(s"PG hstore: invalid valLen=$valLen", Absent, readerFrame)
        else if pos + valLen > bytes.size then
            throw SqlException.Decode(
                s"PG hstore: value body truncated (need $valLen bytes at offset $pos, total ${bytes.size})",
                Absent,
                readerFrame
            )
        else
            val out = bytes.slice(pos, pos + valLen)
            pos += valLen
            Maybe.Present(out)
        end if
    end nextValueBytes

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

end HstoreReader
