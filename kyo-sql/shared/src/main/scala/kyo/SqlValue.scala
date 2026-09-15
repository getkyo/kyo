package kyo

/** One column's value, decoded off the wire and not yet spelled.
  *
  * A backend produces this for [[SqlRow.Codec.columnValue]] and never a string, which is what lets [[SqlRow.Codec.text]] be final.
  *
  * Every variant therefore carries DATA. Where a rendering needs a decision (a float's digits, which run of an address collapses to `::`),
  * the variant carries the fields the renderer decides from; pre-rendered text would hand that choice back to the backend.
  */
sealed trait SqlValue derives CanEqual

object SqlValue:

    /** `BigInt` because the union of the engines' integer domains does not fit a signed `Long`: an unsigned 64-bit column reaches 2^64-1. */
    final case class Integer(value: BigInt) extends SqlValue

    final case class Decimal(value: BigDecimal) extends SqlValue

    /** The two float widths are separate variants because they do not render alike, and reading the narrower at the wider type widens it
      * first: `0.1` becomes `0.10000000149011612`.
      */
    final case class Float4(value: Float) extends SqlValue

    final case class Float8(value: Double) extends SqlValue

    /** The three values an exact numeric column can hold and a `BigDecimal` cannot. */
    final case class NonFiniteNumber(kind: NonFinite) extends SqlValue

    enum NonFinite derives CanEqual:
        case NaN, PositiveInfinity, NegativeInfinity

    final case class Bool(value: Boolean) extends SqlValue

    final case class Text(value: String) extends SqlValue

    final case class Json(value: String) extends SqlValue

    final case class Uuid(value: java.util.UUID) extends SqlValue

    /** The era is separate from the year so a proleptic year 0 and `1 BC` are one value rather than two spellings. */
    final case class Date(year: Int, month: Int, day: Int, bc: Boolean) extends SqlValue

    /** A signed SPAN rather than a time of day, which is what the union of the engines' time columns needs: one runs from -838:59:59 to
      * 838:59:59 and the other reaches 24:00:00, and a `java.time.LocalTime` holds neither endpoint.
      */
    final case class Time(negative: Boolean, hours: Long, minutes: Int, seconds: Int, micros: Int) extends SqlValue

    final case class TimeWithOffset(hours: Int, minutes: Int, seconds: Int, micros: Int, offsetSeconds: Int) extends SqlValue

    /** A wall clock: a date and a time with no instant attached. */
    final case class DateTime(year: Int, month: Int, day: Int, bc: Boolean, hours: Int, minutes: Int, seconds: Int, micros: Int)
        extends SqlValue

    /** An instant, which the renderer spells at UTC, the only zone that is a property of the value rather than of the session. */
    final case class Timestamp(epochSecond: Long, micros: Int) extends SqlValue

    /** The infinite endpoints a temporal column holds and no `java.time` type does. Decoding one at a type answers a plausible wrong date
      * near year 5881610, which is where adding `Int.MaxValue` days lands.
      */
    final case class TemporalInfinity(negative: Boolean) extends SqlValue

    /** Months, days and microseconds do not reduce to one another: a month is not a fixed number of days, and a day is not a fixed number of
      * seconds across a DST boundary.
      */
    final case class Interval(months: Long, days: Long, micros: Long) extends SqlValue

    final case class Bytes(value: Span[Byte]) extends SqlValue

    /** The wire struct's fields rather than the address text, which matters most here: rendering IPv6 means choosing which run of zero groups
      * collapses to `::` (RFC 5952), and two backends returning their own text could disagree about it.
      *
      * @param family
      *   2 for IPv4, 3 for IPv6
      * @param maskBits
      *   written by the renderer only when it is not the full width for the family
      */
    final case class NetworkAddress(family: Int, maskBits: Int, address: Span[Byte]) extends SqlValue

    /** An array's elements, each at its own kind, with [[Absent]] for SQL NULL. An array has no single element type to decode at, and an
      * absent element is a case no typed element read can express.
      */
    final case class Elements(values: Chunk[Maybe[SqlValue]]) extends SqlValue

    /** Raw text for a row carrying no type metadata at all, which is all a codec without it can answer.
      *
      * Constructed only by the default [[SqlRow.Codec.columnValue]], never by a backend in this repository: a backend that cannot render a
      * column reports [[SqlRow.ColumnKind.Unknown]], and [[kyo.internal.SqlPositionalRowCodec]] then refuses under both wire formats.
      */
    final case class ServerRendering(value: String) extends SqlValue

end SqlValue
