package com.example.stub
import kyo.*
import kyo.SqlCodec

/** A [[kyo.SqlCodec.Writer]] built from the public SPI alone.
  *
  * The stub encodes no parameters, so every writer refuses. It exists to prove the whole `Writer` surface (the scalar primitives and the SQL
  * type vocabulary) is implementable outside the `kyo` package, and that it asks for nothing else: the class below overrides no member the
  * writer does not declare, so a framing or field-iteration protocol creeping back into the contract would fail this compile.
  */
final class StubWriter(frame: Frame) extends SqlCodec.Writer(frame):

    private def inert(member: String): Nothing =
        throw new UnsupportedOperationException(s"StubWriter.$member: the stub engine encodes no parameters")

    // Scalar primitives.
    def string(value: String): Unit               = inert("string")
    def int(value: Int): Unit                     = inert("int")
    def long(value: Long): Unit                   = inert("long")
    def float(value: Float): Unit                 = inert("float")
    def double(value: Double): Unit               = inert("double")
    def boolean(value: Boolean): Unit             = inert("boolean")
    def short(value: Short): Unit                 = inert("short")
    def byte(value: Byte): Unit                   = inert("byte")
    def char(value: Char): Unit                   = inert("char")
    def bigDecimal(value: BigDecimal): Unit       = inert("bigDecimal")
    def bigInt(value: BigInt): Unit               = inert("bigInt")
    def bytes(value: Span[Byte]): Unit            = inert("bytes")
    def instant(value: java.time.Instant): Unit   = inert("instant")
    def duration(value: java.time.Duration): Unit = inert("duration")
    def nil(): Unit                               = inert("nil")

    // SQL type vocabulary.
    def json(text: String): Unit                          = inert("json")
    def uuid(value: java.util.UUID): Unit                 = inert("uuid")
    def date(value: java.time.LocalDate): Unit            = inert("date")
    def time(value: java.time.LocalTime): Unit            = inert("time")
    def timeWithOffset(value: java.time.OffsetTime): Unit = inert("timeWithOffset")
    def dateTime(value: java.time.LocalDateTime): Unit    = inert("dateTime")
    def calendarInterval(value: java.time.Period): Unit   = inert("calendarInterval")
    def arrayOfInt(values: Chunk[Int]): Unit              = inert("arrayOfInt")
    def arrayOfString(values: Chunk[String]): Unit        = inert("arrayOfString")
    def arrayOfJson(values: Chunk[String]): Unit          = inert("arrayOfJson")
    def extension(payload: SqlCodec.Writer.Payload): Unit = inert("extension")

    def encodeElement[A](column: SqlSchema.Column[A], value: A, typeName: String, format: SqlCodec.Format): Span[Byte] =
        inert("encodeElement")

end StubWriter
