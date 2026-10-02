package kyo.internal

import kyo.*

/** How a number read from the input narrows to an integral target.
  *
  * Every reader narrows through here, whether it read the number directly or from a captured tree, so one input fails the same way on
  * every path: a fraction is a `TypeMismatchException`, and a whole value outside the target is a `RangeException`. Nothing is truncated
  * or wrapped. Protobuf's varint int32 reads are the exception: protobuf defines them as a cast.
  */
private[kyo] object Numeric:

    enum Target(val name: String, val min: Long, val max: Long) derives CanEqual:
        case Int8  extends Target("Byte", Byte.MinValue.toLong, Byte.MaxValue.toLong)
        case Int16 extends Target("Short", Short.MinValue.toLong, Short.MaxValue.toLong)
        case Int32 extends Target("Int", Int.MinValue.toLong, Int.MaxValue.toLong)
        case Int64 extends Target("Long", Long.MinValue, Long.MaxValue)
    end Target

    def whole(value: Long, target: Target)(using Frame): Long =
        if value < target.min || value > target.max then outOfRange(BigDecimal(value), target)
        else value

    def whole(value: BigInt, target: Target)(using Frame): Long =
        if value.isValidLong then whole(value.toLong, target) else outOfRange(BigDecimal(value), target)

    def whole(value: BigDecimal, target: Target)(using Frame): Long =
        if !value.isWhole then fraction(target)
        else if value.isValidLong then whole(value.toLong, target)
        else outOfRange(value, target)

    def whole(value: Double, target: Target)(using Frame): Long =
        if value.isNaN || value.isInfinite then throw TypeMismatchException(Seq.empty, target.name, "a non-finite number")
        else if value != Math.rint(value) then fraction(target)
        else whole(BigDecimal(value), target)

    /** A JSON or Yaml number token. `Absent` when the text is not a number at all, which the reader reports in its own terms. */
    def parse(text: String): Maybe[BigDecimal] =
        Result.catching[NumberFormatException](BigDecimal(text)).toMaybe

    private def fraction(target: Target)(using Frame): Nothing =
        throw TypeMismatchException(Seq.empty, target.name, "a number with a fraction")

    private def outOfRange(value: BigDecimal, target: Target)(using Frame): Nothing =
        throw RangeException(value, target.name, target.min, target.max)

end Numeric
