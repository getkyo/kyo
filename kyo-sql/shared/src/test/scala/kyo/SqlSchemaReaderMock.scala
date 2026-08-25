package kyo

import kyo.db.Idiom
import kyo.internal.SqlFieldMatcher

/** Replaying [[SqlCodec.Reader]] for tests: hands back the values a [[SqlSchemaWriterMock]] recorded, in order.
  *
  * Pairing the two mocks makes a schema round-trip assertable without a backend: write a value into a [[SqlSchemaWriterMock]], build a reader
  * from its [[SqlSchemaWriterMock.calls]], and read the value back. A read that asks for a different SQL type than the next recorded call
  * carries fails with [[kyo.SqlDecodeColumnDecodeException]], so a schema whose write and read halves disagree is a test failure rather than
  * a silent coercion.
  *
  * @param recorded
  *   the calls to replay, normally a [[SqlSchemaWriterMock.calls]] result
  * @param dialect
  *   the dialect this reader pretends to decode for
  * @param columnNames
  *   the column names the row carries, for the field resolution a derived row decode drives; empty leaves the resolution positional
  * @param elements
  *   the calls each composite element made, normally a [[SqlSchemaWriterMock.elementCalls]] result
  * @param elementFormats
  *   the wire format each element was encoded under, normally a [[SqlSchemaWriterMock.elementFormats]] result
  * @param matchesFieldAt
  *   decides which row field the column at a given index belongs to, the same [[SqlFieldMatcher]] both real readers are handed; `Absent`
  *   leaves matching on the verbatim column names
  */
final class SqlSchemaReaderMock(
    recorded: Chunk[SqlSchemaWriterMock.Call],
    val dialect: Idiom.Id,
    columnNames: Chunk[String] = Chunk.empty,
    elements: Chunk[Chunk[SqlSchemaWriterMock.Call]] = Chunk.empty,
    elementFormats: Chunk[SqlCodec.Format] = Chunk.empty,
    matchesFieldAt: Maybe[(Int, String) => Boolean] = Maybe.Absent
)(using Frame)
    extends SqlCodec.Reader(summon[Frame]):

    import SqlSchemaWriterMock.Call

    private var idx = 0

    /** True when every recorded call has been consumed. */
    def exhausted: Boolean = idx >= recorded.size

    private def next(): Call =
        if idx >= recorded.size then
            throw SqlDecodeColumnDecodeException(idx, new Exception(s"mock reader has only ${recorded.size} recorded calls"))(using frame)
        end if
        val call = recorded(idx)
        idx += 1
        call
    end next

    private def mismatch(expected: String, got: Call): Nothing =
        throw SqlDecodeColumnDecodeException(idx - 1, new Exception(s"expected a $expected column, the writer recorded $got"))(using frame)

    // --- Scalar primitives ---

    override def boolean(): Boolean = next() match
        case Call.Boolean(v) => v
        case other           => mismatch("boolean", other)

    override def short(): Short = next() match
        case Call.Short(v) => v
        case other         => mismatch("short", other)

    override def int(): Int = next() match
        case Call.Int(v) => v
        case other       => mismatch("int", other)

    override def long(): Long = next() match
        case Call.Long(v) => v
        case other        => mismatch("long", other)

    override def float(): Float = next() match
        case Call.Float(v) => v
        case other         => mismatch("float", other)

    override def double(): Double = next() match
        case Call.Double(v) => v
        case other          => mismatch("double", other)

    override def bigDecimal(): BigDecimal = next() match
        case Call.Decimal(v) => v
        case other           => mismatch("decimal", other)

    override def bigInt(): BigInt = next() match
        case Call.Integral(v) => v
        case other            => mismatch("integral", other)

    override def string(): String = next() match
        case Call.Str(v) => v
        case other       => mismatch("string", other)

    override def bytes(): Span[Byte] = next() match
        case Call.Bytes(v) => v
        case other         => mismatch("bytes", other)

    override def byte(): Byte = next() match
        case Call.Byte(v) => v
        case other        => mismatch("byte", other)

    override def char(): Char = next() match
        case Call.Char(v) => v
        case other        => mismatch("char", other)

    override def instant(): java.time.Instant = next() match
        case Call.Instant(v) => v
        case other           => mismatch("instant", other)

    override def duration(): java.time.Duration = next() match
        case Call.Duration(v) => v
        case other            => mismatch("duration", other)

    /** Consumes the next recorded call when it is a nil, and leaves it in place otherwise.
      *
      * The asymmetry is the reader contract both real readers implement: a nullable column's read asks `isNil` first and reads the value
      * only when the answer is false, so answering true without consuming would leave the null column in front of the next field's read.
      */
    override def isNil(): Boolean =
        idx < recorded.size && (recorded(idx) match
            case Call.Nil =>
                idx += 1
                true
            case _ => false)

    override def skip(): Unit = kyo.discard(next())

    // --- SQL type vocabulary ---

    override def nextJson(): String = next() match
        case Call.Json(v) => v
        case other        => mismatch("json", other)

    override def nextUuid(): java.util.UUID = next() match
        case Call.Uuid(v) => v
        case other        => mismatch("uuid", other)

    override def nextDate(): java.time.LocalDate = next() match
        case Call.Date(v) => v
        case other        => mismatch("date", other)

    override def nextTime(): java.time.LocalTime = next() match
        case Call.Time(v) => v
        case other        => mismatch("time", other)

    override def nextTimeWithOffset(): java.time.OffsetTime = next() match
        case Call.TimeWithOffset(v) => v
        case other                  => mismatch("time with offset", other)

    override def nextDateTime(): java.time.LocalDateTime = next() match
        case Call.DateTime(v) => v
        case other            => mismatch("date and time", other)

    override def nextCalendarInterval(): java.time.Period = next() match
        case Call.CalendarInterval(v) => v
        case other                    => mismatch("calendar interval", other)

    override def nextArrayOfInt(): Chunk[Int] = next() match
        case Call.ArrayOfInt(v) => v
        case other              => mismatch("array of int", other)

    override def nextArrayOfString(): Chunk[String] = next() match
        case Call.ArrayOfString(v) => v
        case other                 => mismatch("array of string", other)

    override def nextArrayOfJson(): Chunk[String] = next() match
        case Call.ArrayOfJson(v) => v
        case other               => mismatch("array of json", other)

    override def nextExtension(dialect: Idiom.Id, typeName: String): SqlCodec.Reader.Extension =
        if dialect != this.dialect then
            throw SqlUnsupportedTypeOnBackendException(dialect, typeName, this.dialect)(using frame)
        end if
        next() match
            case Call.Extension(_, format, bytes) => SqlCodec.Reader.Extension(format, bytes)
            case other                            => mismatch("extension", other)
    end nextExtension

    /** Decodes an element by replaying the calls its column made, the inverse of [[SqlSchemaWriterMock.encodeElement]].
      *
      * `bytes` carries the element's index rather than a wire encoding, matching what the writer mock produced;
      * [[SqlSchemaReaderMock.replaying]] supplies the recorded elements. A reader built from a raw call list has none, so a composite read
      * through it fails loudly rather than silently decoding the wrong thing. `format` is checked against the format the writer was asked
      * for: the two sides of a composite have to agree on it, and a mock that ignored the argument would let a disagreement round-trip.
      */
    override def decodeElement[A](column: SqlSchema.Column[A], bytes: Span[Byte], format: SqlCodec.Format): A =
        val idx = if bytes.isEmpty then -1 else bytes(0).toInt
        if idx < 0 || idx >= elements.size then
            throw new AssertionError(
                s"decodeElement got element index $idx with ${elements.size} recorded; build this reader with SqlSchemaReaderMock.replaying"
            )
        end if
        if idx < elementFormats.size && elementFormats(idx) != format then
            throw new AssertionError(
                s"decodeElement asked for $format on element $idx, which was encoded as ${elementFormats(idx)}"
            )
        end if
        column.read(new SqlSchemaReaderMock(elements(idx), dialect)(using frame))
    end decodeElement

    // --- Field resolution: which row field the column at an index fills ---

    /** Resolves the row field the column at `index` belongs to through the same rule the real readers use, positional when no column name
      * matches. A reader built with no column names never leaves the positional answer, which is what a single-column read and a
      * declaration-order replay both want.
      */
    override private[kyo] def fieldIndex(index: Int, names: Chunk[String]): Int =
        var j = 0
        while j < names.size do
            if matches(index, names(j)) then return j
            j += 1
        end while
        index
    end fieldIndex

    /** The field-matching rule for this reader, resolved once, the same [[SqlFieldMatcher]] the real readers use. */
    private lazy val matches: (Int, String) => Boolean =
        matchesFieldAt.getOrElse(SqlFieldMatcher.verbatim(columnNames))

end SqlSchemaReaderMock

object SqlSchemaReaderMock:

    /** A reader replaying `recorded` as PostgreSQL columns. */
    def postgresMock(recorded: Chunk[SqlSchemaWriterMock.Call])(using Frame): SqlSchemaReaderMock =
        new SqlSchemaReaderMock(recorded, SqlSchemaWriterMock.postgres)

    /** A reader replaying `recorded` as MySQL columns. */
    def mysqlMock(recorded: Chunk[SqlSchemaWriterMock.Call])(using Frame): SqlSchemaReaderMock =
        new SqlSchemaReaderMock(recorded, SqlSchemaWriterMock.mysql)

    /** A reader replaying everything `writer` recorded, values and element formats alike.
      *
      * The pairing a round-trip wants. A write emits a row's columns in declaration order, so the replay carries no column names and each
      * column lands in its own field positionally; the by-name path is exercised where a row's columns are named, through
      * [[SqlRowCodecMock]]. A composite's decode states the format its elements went out in.
      */
    def replaying(writer: SqlSchemaWriterMock)(using Frame): SqlSchemaReaderMock =
        new SqlSchemaReaderMock(writer.calls, writer.dialect, Chunk.empty, writer.elementCalls, writer.elementFormats)

end SqlSchemaReaderMock
