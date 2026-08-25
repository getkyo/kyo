package kyo.internal

import kyo.Frame
import kyo.SqlDecodeNumericException
import kyo.SqlDecodeValueRangeException

/** Numeric-decode helpers shared by the backend numeric decoders.
  *
  * Each backend resolves the wire representation of a numeric column its own way, from a PostgreSQL OID or a MySQL type byte, and reads the
  * bytes with its own endianness. What both share, once a value is in hand as a [[BigDecimal]], is the exact-narrowing rule and the text-parse
  * rule below. Keeping them here is what makes the two decoders narrow and parse a value the same way rather than drifting on the next edit.
  */
private[kyo] object SqlNumericDecode:

    /** The `Long` bounds every exact integral target is narrowed against. */
    val LongMinDecimal: BigDecimal = BigDecimal(Long.MinValue)
    val LongMaxDecimal: BigDecimal = BigDecimal(Long.MaxValue)

    /** The `Long` an exact numeric value carries, or a [[kyo.SqlDecodeValueRangeException]] when it is fractional or outside `Long` range.
      *
      * `scalaType` names the type the caller asked for and `wire` the column representation the value came from, so a rejection reports both.
      */
    def wholeOf(value: BigDecimal, scalaType: String, wire: String)(using Frame): Long =
        if value.isWhole && value >= LongMinDecimal && value <= LongMaxDecimal then value.toLong
        else throw SqlDecodeValueRangeException(scalaType, value.toString, wire)

    /** Parses a text-format numeric value as the `BigDecimal` every exact target narrows from.
      *
      * The text arms take this route rather than `String.toInt` so that a value too large for the requested type, and a value with a
      * fractional part, both fail as the range error they are instead of as an untyped `NumberFormatException`.
      */
    def parseDecimalText(s: String)(using Frame): BigDecimal =
        try BigDecimal(s)
        catch
            case _: NumberFormatException =>
                throw SqlDecodeNumericException(s, SqlDecodeNumericException.Subtype.Parse)
        end try
    end parseDecimalText

end SqlNumericDecode
