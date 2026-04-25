package kyo

import java.nio.charset.StandardCharsets
import kyo.DecodeException
import kyo.Frame
import kyo.Result
import kyo.Schema
import kyo.Span

/** A serialization format that pairs a [[Codec.Writer]] and [[Codec.Reader]] to encode and decode values.
  *
  * Codec is the extension point for adding new wire formats to kyo-schema. Each implementation defines how to produce a fresh Writer for
  * serialization and a fresh Reader for deserialization, while the Schema-derived traversal logic remains format-agnostic.
  *
  *   - Pluggable: implement `newWriter` and `newReader` to support any binary or text format
  *   - Used by [[kyo.Schema]] encode/decode methods to select the target format at the call site
  *   - Built-in implementations: [[Json]] (JSON) and [[Protobuf]] (Protocol Buffers wire format)
  *
  * @see
  *   [[Codec.Writer]] for the serialization side
  * @see
  *   [[Codec.Reader]] for the deserialization side
  * @see
  *   [[kyo.Schema]] for the type-driven serialization entry point
  */
abstract class Codec:
    def newWriter(): Codec.Writer
    def newReader(input: Span[Byte])(using Frame): Codec.Reader
end Codec

object Codec:

    abstract class Reader:
        /** The source location where this Reader was constructed.
          *
          * Codec implementations use this frame to attach a user-meaningful context to DecodeExceptions thrown during reading. The frame
          * corresponds to the caller's decode site (where they invoked `Codec.decode`), not the codec's internal synthesis site.
          */
        def frame: Frame

        private[kyo] var maxDepth: Int          = 512
        private[kyo] var maxCollectionSize: Int = 100000
        private var _depth: Int                 = 0

        /** Reset limits and depth counter. Called on reader reuse. */
        private[kyo] def resetLimits(maxDepth: Int, maxCollectionSize: Int): Unit =
            this.maxDepth = maxDepth
            this.maxCollectionSize = maxCollectionSize
            _depth = 0
        end resetLimits

        /** Increment depth and check against limit. */
        final protected def checkDepth(): Unit =
            _depth += 1
            if _depth > maxDepth then
                throw LimitExceededException("Nesting depth", _depth, maxDepth)(using frame)
        end checkDepth

        /** Decrement depth. */
        final protected def decrementDepth(): Unit =
            _depth -= 1

        /** Check collection size against limit. */
        final def checkCollectionSize(count: Int): Unit =
            if count > maxCollectionSize then
                throw LimitExceededException("Collection size", count, maxCollectionSize)(using frame)

        def objectStart(): Int
        def objectEnd(): Unit
        def arrayStart(): Int
        def arrayEnd(): Unit
        def field(): String
        def hasNextField(): Boolean
        def hasNextElement(): Boolean
        def string(): String
        def int(): Int
        def long(): Long
        def float(): Float
        def double(): Double
        def boolean(): Boolean
        def short(): Short
        def byte(): Byte
        def char(): Char
        def isNil(): Boolean
        def skip(): Unit
        def mapStart(): Int
        def mapEnd(): Unit
        def hasNextEntry(): Boolean
        def bytes(): Span[Byte]
        def bigInt(): BigInt
        def bigDecimal(): BigDecimal
        def instant(): java.time.Instant
        def duration(): java.time.Duration

        /** Initialize reusable field values array for n fields. Returns the array. Override for pooled implementations (e.g. JsonReader).
          * Default allocates fresh.
          */
        def initFields(n: Int): Array[AnyRef] = new Array[AnyRef](n)

        /** Clear field values to prevent reference leaks. Default is no-op. */
        def clearFields(n: Int): Unit = ()

        /** Returns a bitmask of fields that should be considered pre-satisfied during required-field validation.
          *
          * The macro-generated case-class decoder OR-s this mask into its local `seen` bitmap before checking required fields, so transform
          * wrappers (e.g. [[kyo.internal.SchemaSerializer.TransformAwareReader]]) can signal that fields dropped by the schema should not
          * trigger [[MissingFieldException]].
          *
          * Default returns `0L` — no fields pre-satisfied. Overrides must return a mask with bit `i` set iff field index `i` is
          * pre-satisfied by this reader. Field index `i` corresponds to the case class constructor position (0-based). Only the low-order
          * `n` bits are relevant; bits beyond that are ignored by the caller.
          */
        def droppedFieldsMask(n: Int): Long = 0L

        /** Parse the next field name and record it as internal state for [[matchField]] and [[lastFieldName]].
          *
          * Implementations should advance the wire stream past the field name (and any delimiters such as JSON's `:`) so subsequent value
          * reads can proceed. Zero-allocation implementations may store raw byte positions instead of materializing a String.
          */
        def fieldParse(): Unit

        /** Compare the last field name parsed via [[fieldParse]] against pre-encoded UTF-8 name bytes.
          *
          * Implementations should return `true` iff the field name captured by the most recent [[fieldParse]] call matches `nameBytes`
          * exactly. Called repeatedly against the schema's known field names to dispatch decoding.
          */
        def matchField(nameBytes: Array[Byte]): Boolean

        /** Returns a string representation of the last field parsed via [[fieldParse]].
          *
          * Used for error reporting (e.g. [[UnknownVariantException]]) when [[matchField]] has rejected every known candidate and the
          * decoder needs a human-readable name. Implementations should return the canonical field name when available, or a stable,
          * identifiable surrogate (e.g. a numeric field ID) when the underlying wire format does not carry names (as in Protobuf).
          */
        def lastFieldName(): String

        /** Release this reader back to its pool. Default is no-op. */
        def release(): Unit = ()

        /** Capture the next value into a buffered sub-Reader for deferred reading.
          *
          * Used by sum codecs to enable field-order independence: the value field may be read before the discriminator, then dispatched to
          * the right inner codec once the discriminator is known.
          *
          * After this call, the parent Reader's position is advanced past the value (as if `skip()` had been called).
          */
        def captureValue(): Reader
    end Reader

    /** Abstract base for codec-specific serialization output.
      *
      * A Writer receives a stream of typed method calls (e.g. `objectStart`, `field`, `string`, `int`) that describe a value's structure,
      * and encodes them into a target wire format such as JSON or Protocol Buffers. Schema-derived codecs call these methods in a
      * depth-first traversal of the value being serialized.
      *
      *   - Format-agnostic: each concrete subclass (JsonWriter, ProtobufWriter, ReflectValueWriter) decides how to represent objects,
      *     arrays, maps, and primitives
      *   - Streaming: the caller drives the traversal; the writer accumulates output incrementally
      *   - Symmetric with [[Reader]]: every write method has a corresponding read method, enabling round-trip serialization
      *
      * @see
      *   [[Reader]] for the deserialization counterpart
      * @see
      *   [[kyo.Codec]] for the factory that pairs a Writer with a Reader
      */
    abstract class Writer:
        def objectStart(name: String, size: Int): Unit
        def objectEnd(): Unit
        def arrayStart(size: Int): Unit
        def arrayEnd(): Unit
        def fieldBytes(nameBytes: Array[Byte], fieldId: Int): Unit
        def field(name: String, fieldId: Int): Unit = fieldBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8), fieldId)
        def string(value: String): Unit
        def int(value: Int): Unit
        def long(value: Long): Unit
        def float(value: Float): Unit
        def double(value: Double): Unit
        def boolean(value: Boolean): Unit
        def short(value: Short): Unit
        def byte(value: Byte): Unit
        def char(value: Char): Unit
        def nil(): Unit
        def mapStart(size: Int): Unit
        def mapEnd(): Unit
        def bytes(value: Span[Byte]): Unit
        def bigInt(value: BigInt): Unit
        def bigDecimal(value: BigDecimal): Unit
        def instant(value: java.time.Instant): Unit
        def duration(value: java.time.Duration): Unit
        def result(): Span[Byte]

        /** Materialize the output as a String. Default delegates to `result()` + UTF-8 decode; codecs with char-native or ASCII-fast paths
          * should override to skip intermediate copies.
          */
        def resultString: String =
            new String(result().toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)
    end Writer

end Codec
