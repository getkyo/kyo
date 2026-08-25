package com.example.stub
import kyo.*
import kyo.SqlCodec
import kyo.db.Idiom

/** A [[kyo.SqlCodec.Reader]] built from the public SPI alone.
  *
  * The stub decodes no rows, so every reader refuses. It exists to prove the whole `Reader` surface (the scalar primitives and the SQL type
  * vocabulary) is implementable outside the `kyo` package, and that it asks for nothing else: the class below overrides no member the reader
  * does not declare, so a framing or field-iteration protocol creeping back into the contract would fail this compile.
  */
final class StubReader(frame: Frame) extends SqlCodec.Reader(frame):

    private def inert(member: String): Nothing =
        throw new UnsupportedOperationException(s"StubReader.$member: the stub engine decodes no rows")

    // Scalar primitives.
    def string(): String               = inert("string")
    def int(): Int                     = inert("int")
    def long(): Long                   = inert("long")
    def float(): Float                 = inert("float")
    def double(): Double               = inert("double")
    def boolean(): Boolean             = inert("boolean")
    def short(): Short                 = inert("short")
    def byte(): Byte                   = inert("byte")
    def char(): Char                   = inert("char")
    def bigDecimal(): BigDecimal       = inert("bigDecimal")
    def bigInt(): BigInt               = inert("bigInt")
    def bytes(): Span[Byte]            = inert("bytes")
    def instant(): java.time.Instant   = inert("instant")
    def duration(): java.time.Duration = inert("duration")
    def isNil(): Boolean               = inert("isNil")
    def skip(): Unit                   = inert("skip")

    // SQL type vocabulary.
    def nextJson(): String                                                            = inert("nextJson")
    def nextUuid(): java.util.UUID                                                    = inert("nextUuid")
    def nextDate(): java.time.LocalDate                                               = inert("nextDate")
    def nextTime(): java.time.LocalTime                                               = inert("nextTime")
    def nextTimeWithOffset(): java.time.OffsetTime                                    = inert("nextTimeWithOffset")
    def nextDateTime(): java.time.LocalDateTime                                       = inert("nextDateTime")
    def nextCalendarInterval(): java.time.Period                                      = inert("nextCalendarInterval")
    def nextArrayOfInt(): Chunk[Int]                                                  = inert("nextArrayOfInt")
    def nextArrayOfString(): Chunk[String]                                            = inert("nextArrayOfString")
    def nextArrayOfJson(): Chunk[String]                                              = inert("nextArrayOfJson")
    def nextExtension(dialect: Idiom.Id, typeName: String): SqlCodec.Reader.Extension = inert("nextExtension")

    def decodeElement[A](column: SqlSchema.Column[A], bytes: Span[Byte], format: SqlCodec.Format): A =
        inert("decodeElement")

end StubReader
