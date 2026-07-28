package kyo

/** Represents a quantity of storage in bytes.
  *
  * `ByteSize` is an opaque type over `Long` (representing bytes). Values are non-negative:
  * construction methods clamp negative inputs to `ByteSize.Zero`.
  *
  * Construct through the unit extensions (`64L.mib`, `1L.gib`), the factory methods
  * (`ByteSize.fromBytes`, `ByteSize.fromUnits`), or by parsing from text
  * (`ByteSize.parse("64MiB")`).
  *
  * Arithmetic saturates on overflow: addition and multiplication that would exceed `Long.MaxValue`
  * clamp to `Long.MaxValue`; subtraction clamps to `ByteSize.Zero`.
  *
  * The `show` method produces a human-readable string at the coarsest lossless binary unit:
  * `"64.mib"`, `"1.gib"`, `"ByteSize.Zero"`.
  *
  * @see
  *   [[Duration]] for the analogous time-span type
  */
opaque type ByteSize = Long

/** Companion object for ByteSize type. */
object ByteSize:

    inline given CanEqual[ByteSize, ByteSize] = CanEqual.derived

    /** Exception thrown for invalid byte size parsing. */
    class InvalidByteSize(message: String)(using Frame) extends KyoException(message)

    /** Represents zero bytes. */
    val Zero: ByteSize = 0L

    /** Creates a `ByteSize` from a raw byte count.
      *
      * Negative values are clamped to `ByteSize.Zero`.
      */
    def fromBytes(value: Long): ByteSize =
        if value <= 0 then ByteSize.Zero else value

    /** Creates a `ByteSize` from a value and unit.
      *
      * The byte count is the exact integer product `value * unit.bytes`; no floating-point
      * arithmetic is used, so results carry full precision at every magnitude. Negative and zero
      * values are clamped to `ByteSize.Zero`. Values whose product would exceed `Long.MaxValue`
      * clamp to `Long.MaxValue`.
      */
    def fromUnits(value: Long, unit: Units): ByteSize =
        if value <= 0 then ByteSize.Zero
        else if value > Long.MaxValue / unit.bytes then Long.MaxValue
        else value * unit.bytes

    /** Parses a human-readable byte size string.
      *
      * Accepted formats: `"64MiB"`, `"64 MiB"`, `"1.5GiB"`, `"512B"`, `"1024"` (bare digits
      * equal bytes), `"10MB"`. Unit matching is case-insensitive. An integral value with a unit
      * (for example `"10MB"`) yields the exact byte count `value * unit.bytes`; a fractional value
      * (for example `"1.5GiB"`) is scaled with `BigDecimal` and rounded to whole bytes using
      * `HALF_UP`. Negative values and values that overflow `Long.MaxValue` are rejected with an
      * `InvalidByteSize` error.
      *
      * @param s
      *   the string to parse
      * @return
      *   a `Result` containing either the parsed `ByteSize` or an `InvalidByteSize` error
      */
    def parse(s: String)(using Frame): Result[InvalidByteSize, ByteSize] =
        // dotPattern handles the show output format: "64.mib", "1.bytes", "1.tib"
        // (integer immediately followed by a dot and letters -- never matches "1.5GiB" because "5" is not a letter)
        val dotPattern = """(\d+)\.([a-zA-Z]+)""".r
        // userPattern handles human input: "64MiB", "64 MiB", "1.5GiB", "10MB", or bare "1024"
        val userPattern = """(\d+(?:\.\d+)?)\s*([a-zA-Z]*)""".r
        s.trim match
            case str if str.equalsIgnoreCase("ByteSize.Zero") =>
                Result.succeed(Zero)
            case dotPattern(numStr, unitStr) =>
                parseNumberAndUnit(numStr, unitStr, s)
            case userPattern(numStr, unitStr) if unitStr.isEmpty =>
                Result.catching[NumberFormatException](numStr.toLong)
                    .mapFailure(_ => InvalidByteSize(s"Invalid number: $numStr"))
                    .map(fromBytes)
            case userPattern(numStr, unitStr) =>
                parseNumberAndUnit(numStr, unitStr, s)
            case _ => Result.fail(InvalidByteSize(s"Invalid byte size format: $s"))
        end match
    end parse

    private def parseNumberAndUnit(numStr: String, unitStr: String, original: String)(using Frame): Result[InvalidByteSize, ByteSize] =
        Units.values.find(u => u.symbol.equalsIgnoreCase(unitStr) || u.toString.equalsIgnoreCase(unitStr))
            .map(Result.succeed)
            .getOrElse(Result.fail(InvalidByteSize(s"Invalid unit: $unitStr")))
            .flatMap { unit =>
                bytesFromParsed(numStr, unit) match
                    case Present(byteCount) => Result.succeed(fromBytes(byteCount))
                    case Absent             => Result.fail(InvalidByteSize(s"Byte size overflow: $original"))
            }

    /** Computes the exact byte count for a parsed numeric string in the given unit.
      *
      * An integral input (no decimal point) uses exact integer arithmetic: the byte count is
      * `value * unit.bytes` computed without floating point. A fractional input (for example
      * `"1.5"`) is scaled by `unit.bytes` using `BigDecimal` and rounded to a whole number of
      * bytes with `HALF_UP`. Returns `Absent` when the byte count would exceed `Long.MaxValue`.
      *
      * The input is assumed to be a non-negative decimal literal, as produced by the parse
      * patterns. Shared by `ByteSize.parse` and the `Flag.Reader` given so the two cannot drift.
      */
    private[kyo] def bytesFromParsed(numStr: String, unit: Units): Maybe[Long] =
        val byteCount =
            if numStr.contains('.') then
                (BigDecimal(numStr) * BigDecimal(unit.bytes))
                    .setScale(0, BigDecimal.RoundingMode.HALF_UP)
                    .toBigInt
            else
                BigInt(numStr) * BigInt(unit.bytes)
        if byteCount > BigInt(Long.MaxValue) then Absent
        else Present(byteCount.toLong)
    end bytesFromParsed

    /** Storage size units with their byte counts and symbols. */
    enum Units(val bytes: Long, val symbol: String):
        case Bytes extends Units(1L, "B")
        case KiB   extends Units(1L << 10, "KiB")
        case MiB   extends Units(1L << 20, "MiB")
        case GiB   extends Units(1L << 30, "GiB")
        case TiB   extends Units(1L << 40, "TiB")
        case KB    extends Units(1000L, "KB")
        case MB    extends Units(1000000L, "MB")
        case GB    extends Units(1000000000L, "GB")
    end Units

    object Units:
        val all = Units.values

        /** Binary units in ascending order. Used by `show` to find the coarsest lossless representation. */
        private[kyo] val binary: Seq[Units] = Seq(Bytes, KiB, MiB, GiB, TiB)
    end Units

    extension (self: ByteSize)

        private def toLong: Long = self

        /** Returns the size in bytes. */
        def toBytes: Long = self.toLong

        /** Returns the size as a `Double` in the given unit. */
        def to(unit: Units): Double = self.toLong.toDouble / unit.bytes.toDouble

        infix def >=(that: ByteSize): Boolean = self.toLong >= that.toLong
        infix def <=(that: ByteSize): Boolean = self.toLong <= that.toLong
        infix def >(that: ByteSize): Boolean  = self.toLong > that.toLong
        infix def <(that: ByteSize): Boolean  = self.toLong < that.toLong

        infix def +(that: ByteSize): ByteSize =
            val sum: Long = self.toLong + that.toLong
            if sum >= 0 then sum else Long.MaxValue

        infix def -(that: ByteSize): ByteSize =
            val diff: Long = self.toLong - that.toLong
            if diff > 0 then diff else ByteSize.Zero

        infix def *(factor: Double): ByteSize =
            if factor <= 0 || self.toLong <= 0L then ByteSize.Zero
            else if factor <= Long.MaxValue / self.toLong.toDouble then Math.round(self.toLong.toDouble * factor)
            else Long.MaxValue

        def max(that: ByteSize): ByteSize = Math.max(self.toLong, that.toLong)
        def min(that: ByteSize): ByteSize = Math.min(self.toLong, that.toLong)

        /** Returns a human-readable string at the coarsest lossless binary unit.
          *
          * Examples: `"64.mib"`, `"1.gib"`, `"ByteSize.Zero"`. The format mirrors `Duration.show`.
          */
        def show: String =
            if self == Zero then "ByteSize.Zero"
            else
                val b = self.toBytes
                Units.binary.reverse.find(unit => b % unit.bytes == 0) match
                    case Some(unit) =>
                        val value = b / unit.bytes
                        val name  = unit.toString.toLowerCase
                        s"$value.$name"
                    case None =>
                        s"$b.bytes"
                end match
    end extension

    /** Parses a `ByteSize` from a string without requiring a `Frame`, for use in CLI/config-flag contexts.
      *
      * This mirrors `ByteSize.parse`'s pattern matching to avoid the `Frame` requirement while
      * sharing `bytesFromParsed` for the numeric conversion, so the two stay consistent.
      */
    given Flag.Reader.Scalar[ByteSize] with
        private val dotPattern  = """(\d+)\.([a-zA-Z]+)""".r
        private val userPattern = """(\d+(?:\.\d+)?)\s*([a-zA-Z]*)""".r

        def apply(s: String): Either[Throwable, ByteSize] =
            s.trim match
                case str if str.equalsIgnoreCase("ByteSize.Zero") =>
                    Right(Zero)
                case dotPattern(numStr, unitStr) =>
                    applyUnit(numStr, unitStr, s)
                case userPattern(numStr, unitStr) if unitStr.isEmpty =>
                    try Right(fromBytes(numStr.toLong))
                    catch case _: NumberFormatException => Left(new IllegalArgumentException(s"Invalid byte size number: $numStr"))
                case userPattern(numStr, unitStr) =>
                    applyUnit(numStr, unitStr, s)
                case _ => Left(new IllegalArgumentException(s"Invalid byte size format: $s"))
            end match
        end apply

        private def applyUnit(numStr: String, unitStr: String, original: String): Either[Throwable, ByteSize] =
            Units.values.find(u => u.symbol.equalsIgnoreCase(unitStr) || u.toString.equalsIgnoreCase(unitStr)) match
                case None => Left(new IllegalArgumentException(s"Invalid byte size unit: $unitStr"))
                case Some(unit) =>
                    bytesFromParsed(numStr, unit) match
                        case Present(byteCount) => Right(fromBytes(byteCount))
                        case Absent             => Left(new IllegalArgumentException(s"Byte size overflow: $original"))

        def typeName: String = "ByteSize"
    end given

end ByteSize

extension (value: Long)
    /** Creates a `ByteSize` of the given number of bytes. */
    def bytes: ByteSize = ByteSize.fromBytes(value)

    /** Creates a `ByteSize` of the given number of kibibytes (1 KiB = 1024 bytes). */
    def kib: ByteSize = value.asByteSizeUnit(ByteSize.Units.KiB)

    /** Creates a `ByteSize` of the given number of mebibytes (1 MiB = 1,048,576 bytes). */
    def mib: ByteSize = value.asByteSizeUnit(ByteSize.Units.MiB)

    /** Creates a `ByteSize` of the given number of gibibytes (1 GiB = 1,073,741,824 bytes). */
    def gib: ByteSize = value.asByteSizeUnit(ByteSize.Units.GiB)

    /** Creates a `ByteSize` of the given number of tebibytes (1 TiB = 1,099,511,627,776 bytes). */
    def tib: ByteSize = value.asByteSizeUnit(ByteSize.Units.TiB)

    /** Creates a `ByteSize` of the given number of kilobytes (1 KB = 1,000 bytes). */
    def kb: ByteSize = value.asByteSizeUnit(ByteSize.Units.KB)

    /** Creates a `ByteSize` of the given number of megabytes (1 MB = 1,000,000 bytes). */
    def mb: ByteSize = value.asByteSizeUnit(ByteSize.Units.MB)

    /** Creates a `ByteSize` of the given number of gigabytes (1 GB = 1,000,000,000 bytes). */
    def gb: ByteSize = value.asByteSizeUnit(ByteSize.Units.GB)

    /** Creates a `ByteSize` from a specific unit. */
    private[kyo] def asByteSizeUnit(unit: ByteSize.Units): ByteSize =
        ByteSize.fromUnits(value, unit)
end extension

extension (value: Int)
    /** Creates a `ByteSize` of the given number of bytes. */
    def bytes: ByteSize = ByteSize.fromBytes(value.toLong)

    /** Creates a `ByteSize` of the given number of kibibytes (1 KiB = 1024 bytes). */
    def kib: ByteSize = value.asIntByteSizeUnit(ByteSize.Units.KiB)

    /** Creates a `ByteSize` of the given number of mebibytes (1 MiB = 1,048,576 bytes). */
    def mib: ByteSize = value.asIntByteSizeUnit(ByteSize.Units.MiB)

    /** Creates a `ByteSize` of the given number of gibibytes (1 GiB = 1,073,741,824 bytes). */
    def gib: ByteSize = value.asIntByteSizeUnit(ByteSize.Units.GiB)

    /** Creates a `ByteSize` of the given number of tebibytes (1 TiB = 1,099,511,627,776 bytes). */
    def tib: ByteSize = value.asIntByteSizeUnit(ByteSize.Units.TiB)

    /** Creates a `ByteSize` of the given number of kilobytes (1 KB = 1,000 bytes). */
    def kb: ByteSize = value.asIntByteSizeUnit(ByteSize.Units.KB)

    /** Creates a `ByteSize` of the given number of megabytes (1 MB = 1,000,000 bytes). */
    def mb: ByteSize = value.asIntByteSizeUnit(ByteSize.Units.MB)

    /** Creates a `ByteSize` of the given number of gigabytes (1 GB = 1,000,000,000 bytes). */
    def gb: ByteSize = value.asIntByteSizeUnit(ByteSize.Units.GB)

    private[kyo] def asIntByteSizeUnit(unit: ByteSize.Units): ByteSize =
        ByteSize.fromUnits(value.toLong, unit)
end extension
