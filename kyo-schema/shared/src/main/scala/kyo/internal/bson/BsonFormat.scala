package kyo.internal.bson

import kyo.OrderedDict
import kyo.Span

private[kyo] object BsonFormat:
    val Double: Int     = 0x01
    val String: Int     = 0x02
    val Document: Int   = 0x03
    val Array: Int      = 0x04
    val Binary: Int     = 0x05
    val Boolean: Int    = 0x08
    val DateTime: Int   = 0x09
    val Null: Int       = 0x0a
    val Int32: Int      = 0x10
    val Int64: Int      = 0x12
    val Decimal128: Int = 0x13

    val BinarySubtypeGeneric: Int = 0x00
    val BinarySubtypeOld: Int     = 0x02
    val MinDocumentLength: Int    = 5
end BsonFormat

private[kyo] enum BsonValue derives CanEqual:
    // Document field store preserves insertion order. A well-formed document (unique field names)
    // encodes each field once, in insertion order. A malformed document with duplicate field names
    // collapses to one entry at the first-seen position holding the last-written value.
    case DocumentValue(fields: OrderedDict[String, BsonValue])
    case ArrayValue(values: Vector[BsonValue])
    case StringValue(value: String)
    case DoubleValue(value: Double)
    case BinaryValue(value: Span[Byte], subtype: Int)
    case BooleanValue(value: Boolean)
    case DateTimeValue(value: java.time.Instant)
    case NullValue
    case Int32Value(value: Int)
    case Int64Value(value: Long)
    case Decimal128Value(value: BigDecimal)
end BsonValue
