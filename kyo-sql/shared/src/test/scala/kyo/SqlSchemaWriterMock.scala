package kyo

import kyo.db.Idiom

/** Recording [[SqlCodec.Writer]] for tests: every write call appends one [[SqlSchemaWriterMock.Call]] to [[calls]].
  *
  * Lets a test assert what a codec wrote, in order, without a backend on the other side, which is what an [[SqlSchema]] decides: it names
  * the SQL type of each column it occupies and hands over the values. Byte-level expectations belong in the backend writers' own suites.
  *
  * [[extension]] behaves like a real writer's: a payload belonging to another dialect is rejected with
  * [[kyo.SqlUnsupportedTypeOnBackendException]] rather than recorded, so a mock constructed with the MySQL id exercises the rejection path
  * for a PostgreSQL-owned type.
  *
  * @param dialect
  *   the dialect this writer pretends to emit for
  */
final class SqlSchemaWriterMock(val dialect: Idiom.Id)(using Frame) extends SqlCodec.Writer(summon[Frame]):

    // A Chunk rather than a builder: `calls` is read more than once per test, and a builder's `result()`
    // empties it.
    private var recorded = Chunk.empty[SqlSchemaWriterMock.Call]

    /** The calls this writer received, in order. */
    def calls: Chunk[SqlSchemaWriterMock.Call] = recorded

    private def record(call: SqlSchemaWriterMock.Call): Unit = recorded = recorded.append(call)

    private var elements = Chunk.empty[Chunk[SqlSchemaWriterMock.Call]]

    /** The calls each [[encodeElement]] received, in the order the elements were encoded. */
    def elementCalls: Chunk[Chunk[SqlSchemaWriterMock.Call]] = elements

    private var formats = Chunk.empty[SqlCodec.Format]

    /** The wire format each [[encodeElement]] was asked for, in the order the elements were encoded.
      *
      * The composite's demand is the thing a format-blind mock could not show: a range that asks for binary elements and a writer that
      * produces text ones agree on nothing, and nothing in the recorded calls says so. Recording the demand is what lets a test assert it.
      */
    def elementFormats: Chunk[SqlCodec.Format] = formats

    /** The single call this writer received, or [[Maybe.Absent]] when it received none or more than one. */
    def onlyCall: Maybe[SqlSchemaWriterMock.Call] =
        val all = calls
        if all.size == 1 then Maybe(all.head) else Maybe.Absent

    import SqlSchemaWriterMock.Call

    // --- Scalar primitives ---

    override def boolean(value: Boolean): Unit             = record(Call.Boolean(value))
    override def short(value: Short): Unit                 = record(Call.Short(value))
    override def int(value: Int): Unit                     = record(Call.Int(value))
    override def long(value: Long): Unit                   = record(Call.Long(value))
    override def float(value: Float): Unit                 = record(Call.Float(value))
    override def double(value: Double): Unit               = record(Call.Double(value))
    override def bigDecimal(value: BigDecimal): Unit       = record(Call.Decimal(value))
    override def bigInt(value: BigInt): Unit               = record(Call.Integral(value))
    override def string(value: String): Unit               = record(Call.Str(value))
    override def bytes(value: Span[Byte]): Unit            = record(Call.Bytes(value))
    override def byte(value: Byte): Unit                   = record(Call.Byte(value))
    override def char(value: Char): Unit                   = record(Call.Char(value))
    override def instant(value: java.time.Instant): Unit   = record(Call.Instant(value))
    override def duration(value: java.time.Duration): Unit = record(Call.Duration(value))
    override def nil(): Unit                               = record(Call.Nil)

    // --- SQL type vocabulary ---

    override def json(text: String): Unit                          = record(Call.Json(text))
    override def uuid(value: java.util.UUID): Unit                 = record(Call.Uuid(value))
    override def date(value: java.time.LocalDate): Unit            = record(Call.Date(value))
    override def time(value: java.time.LocalTime): Unit            = record(Call.Time(value))
    override def timeWithOffset(value: java.time.OffsetTime): Unit = record(Call.TimeWithOffset(value))
    override def dateTime(value: java.time.LocalDateTime): Unit    = record(Call.DateTime(value))
    override def calendarInterval(value: java.time.Period): Unit   = record(Call.CalendarInterval(value))
    override def arrayOfInt(values: Chunk[Int]): Unit              = record(Call.ArrayOfInt(values))
    override def arrayOfString(values: Chunk[String]): Unit        = record(Call.ArrayOfString(values))
    override def arrayOfJson(values: Chunk[String]): Unit          = record(Call.ArrayOfJson(values))

    override def extension(payload: SqlCodec.Writer.Payload): Unit =
        if payload.dialect != dialect then
            throw SqlUnsupportedTypeOnBackendException(payload.dialect, payload.typeName, dialect)(using frame)
        end if
        record(Call.Extension(payload.typeName, payload.format, payload.bytes))
    end extension

    /** Encodes an element by recording the calls its column made into [[elementCalls]], returning that element's index as one byte.
      *
      * The index is deliberately not a wire encoding: this mock has no wire format, so byte-level expectations belong to the backend writers'
      * own suites. Having no wire format is also why no demand is refused here: what the mock records is
      * which format the composite asked for, in [[elementFormats]], so a test can assert the demand a real writer would have to satisfy.
      * Elements are kept OUT of [[calls]] so that list keeps meaning "one entry per column the codec occupied", which is what a composite's
      * callers assert: a range is one column however many elements it carries. [[SqlSchemaReaderMock.replaying]] threads [[elementCalls]] and
      * [[elementFormats]] through so a round-trip can replay each element and check the two sides agree on its format.
      */
    override def encodeElement[A](column: SqlSchema.Column[A], value: A, typeName: String, format: SqlCodec.Format): Span[scala.Byte] =
        val nested = new SqlSchemaWriterMock(dialect)(using frame)
        column.write(value, nested)
        elements = elements.append(nested.calls)
        formats = formats.append(format)
        Span.from(Array[scala.Byte]((elements.size - 1).toByte))
    end encodeElement

end SqlSchemaWriterMock

object SqlSchemaWriterMock:

    /** The PostgreSQL dialect id, the spelling `PostgresTypes` writes into its payloads. */
    val postgres: Idiom.Id = Idiom.Id("postgres")

    /** The MySQL dialect id. */
    val mysql: Idiom.Id = Idiom.Id("mysql")

    /** A [[SqlSchemaWriterMock]] that pretends to emit for PostgreSQL. */
    def postgresMock(using Frame): SqlSchemaWriterMock = new SqlSchemaWriterMock(postgres)

    /** A [[SqlSchemaWriterMock]] that pretends to emit for MySQL. */
    def mysqlMock(using Frame): SqlSchemaWriterMock = new SqlSchemaWriterMock(mysql)

    /** One recorded write call and the value it carried.
      *
      * `Str`, `Decimal`, and `Integral` avoid shadowing `String`, `BigDecimal`, and `BigInt` inside the enum body.
      */
    enum Call derives CanEqual:
        case Boolean(value: scala.Boolean)
        case Short(value: scala.Short)
        case Int(value: scala.Int)
        case Long(value: scala.Long)
        case Float(value: scala.Float)
        case Double(value: scala.Double)
        case Decimal(value: BigDecimal)
        case Integral(value: BigInt)
        case Str(value: java.lang.String)
        case Bytes(value: Span[scala.Byte])
        case Byte(value: scala.Byte)
        case Char(value: scala.Char)
        case Instant(value: java.time.Instant)
        case Duration(value: java.time.Duration)
        case Nil
        case Json(text: java.lang.String)
        case Uuid(value: java.util.UUID)
        case Date(value: java.time.LocalDate)
        case Time(value: java.time.LocalTime)
        case TimeWithOffset(value: java.time.OffsetTime)
        case DateTime(value: java.time.LocalDateTime)
        case CalendarInterval(value: java.time.Period)
        case ArrayOfInt(values: Chunk[scala.Int])
        case ArrayOfString(values: Chunk[java.lang.String])
        case ArrayOfJson(values: Chunk[java.lang.String])
        case Extension(typeName: java.lang.String, format: SqlCodec.Format, bytes: Span[scala.Byte])
    end Call

end SqlSchemaWriterMock
