package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.Codec
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlException
import kyo.internal.JsonReader
import kyo.internal.JsonStructureCounter

/** Factory for creating [[JsonReader]] instances from MySQL TYPE_JSON column bytes.
  *
  * MySQL stores JSON column values as UTF-8 text bytes on the wire (binary protocol sends them as lenenc-prefixed byte strings; the length
  * prefix is stripped before these bytes reach the row reader). This object extracts the JSON element or entry count from the top-level
  * value and creates a [[JsonReader]] for further parsing.
  *
  * Array columns: `[…]`, element count is returned by [[openArray]]. Object (map) columns: `{…}`, entry count is returned by [[openMap]].
  *
  * The [[JsonReader]] returned from [[openArray]] / [[openMap]] is then stored as the active sub-reader in
  * [[kyo.internal.mysql.MysqlRowReader]] and drives subsequent primitive reads until [[Codec.Reader.arrayEnd]] or [[Codec.Reader.mapEnd]]
  * is called.
  */
object MysqlJsonReader:

    /** Parse a MySQL JSON column that encodes an array.
      *
      * @param bytes
      *   raw wire bytes of the JSON column (UTF-8, no length prefix)
      * @param readerFrame
      *   frame from the enclosing row reader for error reporting
      * @return
      *   `(jsonReader, elementCount)`, `jsonReader` is positioned at the start of the array (i.e., past the opening `[`), ready for
      *   element reads; `elementCount` is the size of the array.
      * @throws SqlException.Decode
      *   if the bytes do not represent a JSON array
      */
    def openArray(bytes: Span[Byte], readerFrame: Frame): (JsonReader, Int) =
        val jsonText = new String(bytes.toArray, StandardCharsets.UTF_8).trim
        if !jsonText.startsWith("[") then
            throw SqlException.Decode(
                s"MySQL JSON column is not an array (starts with '${jsonText.take(10)}')",
                Absent,
                readerFrame
            )
        end if
        // Count top-level elements by scanning the JSON text.
        val count = JsonStructureCounter.countArrayElements(jsonText)
        // Create a JsonReader positioned at the beginning of the JSON bytes.
        given Frame = readerFrame
        val reader  = JsonReader(bytes)
        // openArray() on the JsonReader advances past '[' and returns -1 (unknown count) or 0 for empty.
        // We discard the reader's return value and use our pre-counted value instead.
        kyo.discard(reader.arrayStart())
        (reader, count)
    end openArray

    /** Parse a MySQL JSON column that encodes an object (map).
      *
      * @param bytes
      *   raw wire bytes of the JSON column (UTF-8, no length prefix)
      * @param readerFrame
      *   frame from the enclosing row reader for error reporting
      * @return
      *   `(jsonReader, entryCount)`, `jsonReader` is positioned past the opening `{`, ready for entry reads; `entryCount` is the number of
      *   key/value pairs.
      * @throws SqlException.Decode
      *   if the bytes do not represent a JSON object
      */
    def openMap(bytes: Span[Byte], readerFrame: Frame): (JsonReader, Int) =
        val jsonText = new String(bytes.toArray, StandardCharsets.UTF_8).trim
        if !jsonText.startsWith("{") then
            throw SqlException.Decode(
                s"MySQL JSON column is not an object (starts with '${jsonText.take(10)}')",
                Absent,
                readerFrame
            )
        end if
        val count   = JsonStructureCounter.countObjectEntries(jsonText)
        given Frame = readerFrame
        val reader  = JsonReader(bytes)
        kyo.discard(reader.mapStart())
        (reader, count)
    end openMap

end MysqlJsonReader
