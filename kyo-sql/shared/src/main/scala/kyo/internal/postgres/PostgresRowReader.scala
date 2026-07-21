package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Codec
import kyo.Frame
import kyo.Instant
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.BufferedSqlReader
import kyo.internal.JsonReader
import kyo.internal.JsonStructureCounter
import kyo.internal.SqlReader
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Maps `Codec.Reader` primitive calls to column values read positionally from a [[SqlRow]].
  *
  * Each primitive method reads the next column from [[row]] at the current cursor position, advances the cursor by one, and decodes the raw
  * wire bytes using the [[row.format]]-aware logic of the corresponding [[PostgresDecoder]] singleton. The format is propagated from the
  * row so that both extended-protocol (binary) and simple-query (text) results decode correctly.
  *
  * For Schema-derived case class reads, the object-iteration protocol (`objectStart`, `hasNextField`, `fieldParse`, `matchField`,
  * `objectEnd`) is implemented positionally: fields are consumed in the order they appear in the row, matched by column name from
  * [[SqlRow.fields]]. Nested arrays and maps are decoded by reading the column bytes and delegating to an in-memory sub-reader:
  *   - PG binary array OIDs (1007, 1009, 1015, 199, 3807) → [[PostgresArrayReader]] for binary-format element reads
  *   - JSONB/JSON columns → [[kyo.internal.JsonReader]] for map/object reads
  *
  * @param row
  *   the SQL result row to read from
  * @param frame
  *   call-site frame attached to any decode errors
  */
final class PostgresRowReader(row: SqlRow)(using Frame) extends SqlReader(summon[Frame]):

    private var idx = 0

    // Sub-reader for in-column JSON structural reads (map). When set (non-null),
    // primitive reads are delegated to this reader instead of advancing to the next SQL column.
    // Cleared by mapEnd().
    private var jsonSubReaderOrNull: Codec.Reader | Null = null

    // PG binary array cursor. Set by arrayStart(); drives hasNextElement() and element reads.
    // Cleared by arrayEnd().
    private var pgArrayReaderOrNull: PostgresArrayReader | Null = null

    // PG hstore binary cursor. Set by mapStart() when the column OID is not json/jsonb;
    // drives hasNextEntry(), field() (returns key), and string() (returns value). Cleared by mapEnd().
    private var hstoreReaderOrNull: HstoreReader | Null = null

    /** Returns the raw bytes for the current column and advances the cursor.
      *
      * @throws SqlException.Decode
      *   if the current column value is SQL NULL (i.e. [[row.column]] returns [[Maybe.Absent]])
      */
    private def nextBytes(): Span[Byte] =
        val column = row.column(idx)
        idx += 1
        column.getOrElse(throw SqlException.Decode(s"column ${idx - 1} is NULL", Absent, frame))
    end nextBytes

    // --- Nil check, peek without advancing ---

    override def isNil(): Boolean =
        pgArrayReaderOrNull match
            case null => ()
            case _    => return false // inside array, element nil check deferred
        jsonSubReaderOrNull match
            case null => row.column(idx).isEmpty
            case sub  => sub.isNil()
    end isNil

    // --- Primitive value reads ---

    override def boolean(): Boolean =
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.bool.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.bool.read(Format.Binary, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Boolean", Absent, frame)
            case sub => sub.boolean()

    override def short(): Short =
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.int2.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.int2.read(Format.Binary, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Short", Absent, frame)
            case sub => sub.short()

    override def int(): Int =
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.int4.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.int4.read(Format.Binary, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Int", Absent, frame)
            case sub => sub.int()

    override def long(): Long =
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.int8.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.int8.read(Format.Binary, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Long", Absent, frame)
            case sub => sub.long()

    override def float(): Float =
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.float4.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.float4.read(Format.Binary, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Float", Absent, frame)
            case sub => sub.float()

    override def double(): Double =
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.float8.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.float8.read(Format.Binary, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Double", Absent, frame)
            case sub => sub.double()

    override def string(): String =
        // textDecoder accepts both binary and text format identically (UTF-8 bytes either way).
        hstoreReaderOrNull match
            case null =>
                jsonSubReaderOrNull match
                    case null =>
                        pgArrayReaderOrNull match
                            case null => PostgresDecoder.textDecoder.read(row.format, nextBytes())
                            case arr =>
                                arr.nextElement() match
                                    case Maybe.Present(b) => PostgresDecoder.textDecoder.read(Format.Binary, b)(using frame)
                                    case Absent =>
                                        throw SqlException.Decode("PG array element is NULL, cannot decode as String", Absent, frame)
                    case sub => sub.string()
            case hs =>
                hs.nextValue() match
                    case Maybe.Present(s) => s
                    case Absent           => throw SqlException.Decode("PG hstore value is NULL, cannot decode as String", Absent, frame)

    override def bytes(): Span[Byte] =
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.bytea.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.bytea.read(Format.Binary, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Bytes", Absent, frame)
            case sub => sub.bytes()

    override def bigDecimal(): BigDecimal =
        // Use the row's wire format: Binary for extended-protocol results, Text for simple-query results.
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.numeric.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.numeric.read(row.format, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as BigDecimal", Absent, frame)
            case sub => sub.bigDecimal()

    override def instant(): java.time.Instant =
        // timestamptz decoder returns kyo.Instant; convert at the boundary.
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.timestamptz.read(row.format, nextBytes()).toJava
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.timestamptz.read(Format.Binary, b)(using frame).toJava
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Instant", Absent, frame)
            case sub => sub.instant()

    override def byte(): Byte =
        // PG encodes byte as int2 (widened short); narrow back to Byte at the boundary.
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.int2.read(row.format, nextBytes()).toByte
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.int2.read(Format.Binary, b)(using frame).toByte
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Byte", Absent, frame)
            case sub => sub.byte()

    override def char(): Char =
        // char is encoded as a 1-char text string; take the first character.
        jsonSubReaderOrNull match
            case null =>
                val s = pgArrayReaderOrNull match
                    case null => PostgresDecoder.textDecoder.read(row.format, nextBytes())
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.textDecoder.read(Format.Binary, b)(using frame)
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as Char", Absent, frame)
                if s.isEmpty then throw SqlException.Decode(s"column ${idx - 1} is an empty string, cannot read as Char", Absent, frame)
                else s.charAt(0)
            case sub => sub.char()
    end char

    override def bigInt(): BigInt =
        // Use the row's wire format: Binary for extended-protocol results, Text for simple-query results.
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => PostgresDecoder.numeric.read(row.format, nextBytes()).toBigInt
                    case arr =>
                        arr.nextElement() match
                            case Maybe.Present(b) => PostgresDecoder.numeric.read(row.format, b)(using frame).toBigInt
                            case Absent => throw SqlException.Decode("PG array element is NULL, cannot decode as BigInt", Absent, frame)
            case sub => sub.bigInt()

    override def duration(): java.time.Duration =
        PostgresDecoder.interval.read(row.format, nextBytes())

    // --- Custom escape for opaque backend-specific types ---

    override def custom(typeName: String): Span[Byte] =
        nextBytes()

    // --- Skip ---

    override def skip(): Unit =
        jsonSubReaderOrNull match
            case null =>
                pgArrayReaderOrNull match
                    case null => idx += 1
                    case arr  => kyo.discard(arr.nextElement())
            case sub => sub.skip()

    // --- Case-class field-iteration protocol ---
    //
    // Schema-derived read bodies for case classes / tuples call objectStart / hasNextField /
    // fieldParse / matchField / objectEnd to iterate over fields. The generated body emits
    // an if/else-if dispatch on matchField, so matchField MUST return true only for the arm
    // that matches the current column's name; otherwise the first arm swallows every column
    // and the required-fields mask reports the later fields missing.
    //
    // For column-name comparison we accept two shapes: the column name reported by the
    // server (SqlRow.fields(idx).name), and a positional `_<n>` alias for tuple projections
    // whose column names are the SQL projection labels, not `_1` / `_2`. Everything else is
    // read positionally through primitive reads, which advance the cursor.

    /** Opens an object (case class) scope. The return value is not used by the generated decoder. */
    override def objectStart(): Int = 0

    /** Closes the object scope. No cleanup needed for positional SQL reads. */
    override def objectEnd(): Unit = ()

    /** Returns true while there are unread columns at the current cursor position. */
    override def hasNextField(): Boolean = idx < row.values.size

    /** No-op: SQL field names come from SqlRow.fields, not from the wire stream. */
    override def fieldParse(): Unit = ()

    /** Accepts the field at the current cursor position when its name matches the probe.
      *
      * The generated Schema decoder emits an `if matchField(nameOfField0) then arm0 else if matchField(nameOfField1) ...` chain per loop
      * iteration, so a `matchField` that always returned true would pin every column to arm0 and leave later fields' `_seen` bits
      * unset, producing spurious `Missing required field` errors. Compare the probe against the current column's UTF-8 name, and also
      * accept `_<idx+1>` so tuple projections whose SQL columns are aliased (or named after arbitrary expressions) still map to `_1` /
      * `_2` positionally. Does NOT advance the cursor; the cursor advances only when a primitive value is read.
      */
    override def matchField(nameBytes: Array[Byte]): Boolean =
        if idx >= row.fields.size then false
        else
            val currentName  = row.fields(idx).name
            val currentBytes = currentName.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            if java.util.Arrays.equals(currentBytes, nameBytes) then true
            else
                // Accept `_<idx+1>` for tuple projections whose SQL columns are named after
                // the expressions they compute, not the Schema-declared tuple element names.
                val probe = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                probe.length > 1 && probe.charAt(0) == '_' && probe.drop(1) == (idx + 1).toString
            end if

    /** Returns the name of the column at the current cursor position for error reporting. */
    override def lastFieldName(): String =
        if idx < row.fields.size then row.fields(idx).name else s"<column $idx>"

    // --- Structural reads, array, map, and captureValue ---

    /** Reads the current column as a PostgreSQL binary array and returns its element count.
      *
      * Recognised array OIDs (from [[PostgresArrayReader.ArrayOids]]): 1007 (int4[]), 1009 (text[]), 1015 (varchar[]), 199 (json[]), 3807
      * (jsonb[]). Raises [[SqlException.Decode]] (not [[SqlException.Unsupported]]) when the column OID is not a recognised array OID.
      */
    override def arrayStart(): Int =
        val colIdx = idx
        val colOid = if colIdx < row.fields.size then row.fields(colIdx).dataType else 0
        if !PostgresArrayReader.isArrayOid(colOid) && colOid != 0 then
            throw SqlException.Decode(
                s"column $colIdx has OID $colOid which is not a recognised array OID ${PostgresArrayReader.ArrayOids.mkString("{", ",", "}")}",
                Absent,
                frame
            )
        end if
        val bytes  = nextBytes()
        val reader = new PostgresArrayReader(bytes, frame)
        val count  = reader.openArray()
        pgArrayReaderOrNull = reader
        count
    end arrayStart

    /** Clears the active PG binary array cursor. */
    override def arrayEnd(): Unit =
        pgArrayReaderOrNull = null

    /** Returns true while the active array has unread elements. */
    override def hasNextElement(): Boolean =
        pgArrayReaderOrNull match
            case null => false
            case arr  => arr.hasNext

    /** Reads the current column as a map-shaped value (JSONB/JSON object or hstore) and returns its real entry count.
      *
      * Dispatch is by column OID from [[SqlRow.fields]]: 114 (`json`) and 3802 (`jsonb`) route to [[kyo.internal.JsonReader]] with the
      * top-level entry count walked once by [[kyo.internal.JsonStructureCounter.countObjectEntries]]; any other OID is treated as a PG
      * `hstore` binary column (its OID is contrib-extension-installed and not fixed across databases) and routes to [[HstoreReader]]. The
      * returned count is the real entry count, never `-1`, matching the [[kyo.Codec.Reader.mapStart]] contract.
      */
    override def mapStart(): Int =
        val oid   = if idx < row.fields.size then row.fields(idx).dataType else 0
        val bytes = nextBytes()
        if oid == 114 || oid == 3802 then
            // JSONB columns sent in binary format have a 1-byte version prefix (currently 0x01).
            // Strip it so the remainder is standard JSON text.
            val jsonBytes =
                if bytes.nonEmpty && bytes(0) == 0x01.toByte then bytes.slice(1, bytes.size)
                else bytes
            given Frame = frame
            val reader  = JsonReader(jsonBytes)
            kyo.discard(reader.mapStart())
            jsonSubReaderOrNull = reader
            JsonStructureCounter.countObjectEntries(new String(jsonBytes.toArray, StandardCharsets.UTF_8))
        else
            val reader = new HstoreReader(bytes, frame)
            val count  = reader.openMap()
            hstoreReaderOrNull = reader
            count
        end if
    end mapStart

    /** Clears the active map sub-reader (JSON or hstore). */
    override def mapEnd(): Unit =
        jsonSubReaderOrNull match
            case null => ()
            case sub  => sub.mapEnd()
        jsonSubReaderOrNull = null
        hstoreReaderOrNull = null
    end mapEnd

    /** Returns true while the active map sub-reader has unread entries. */
    override def hasNextEntry(): Boolean =
        hstoreReaderOrNull match
            case null =>
                jsonSubReaderOrNull match
                    case null => false
                    case sub  => sub.hasNextEntry()
            case hs => hs.hasNext

    /** Returns the current column's name; when inside a JSON map, the next JSON key; when inside hstore, the next hstore key. */
    override def field(): String =
        hstoreReaderOrNull match
            case null =>
                jsonSubReaderOrNull match
                    case null => if idx < row.fields.size then row.fields(idx).name else s"<column $idx>"
                    case sub  => sub.field()
            case hs => hs.nextKey()

    /** Buffers the current column's bytes and returns a [[BufferedSqlReader]] scoped to that column.
      *
      * Used by sum codecs (sealed traits, `Result`, `Either`) for field-order-independent decoding. The returned reader's primitive methods
      * decode against the buffered bytes using the same PostgreSQL binary-format decoders as this row reader. Structural reads (arrayStart,
      * mapStart) on the buffered reader raise [[SqlException.Decode]].
      */
    override def captureValue(): Codec.Reader =
        val bytes = nextBytes()
        new BufferedSqlReader(bytes, row.format, BufferedSqlReader.Backend.Postgres, frame)

end PostgresRowReader
