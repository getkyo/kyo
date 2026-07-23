package kyo

/** Base class for all exceptions raised by the kyo-schema module.
  *
  * Subtypes are grouped by the operation that raised them: DecodeException for deserialization failures, ValidationException for constraint
  * violations, TransformException for schema manipulation errors, and NavigationException for path traversal failures.
  */
// --- Base ---
sealed abstract class SchemaException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

// --- Operation markers ---

/** Marker for exceptions raised during decoding (deserialization). */
sealed trait DecodeException extends SchemaException

/** Marker for exceptions raised when a validation constraint is violated. */
sealed trait ValidationException extends SchemaException

/** Marker for exceptions raised during schema transformation (drop, rename, map, etc.). */
sealed trait TransformException extends SchemaException

/** Marker for exceptions raised during structural path navigation. */
sealed trait NavigationException extends SchemaException

// --- Decode ---

/** Thrown when a required field is absent in the input during decoding. */
case class MissingFieldException(path: Seq[String], fieldName: String)(using Frame)
    extends SchemaException(s"Missing required field '$fieldName'" + SchemaException.pathSuffix(path) + ". Add this field to the input.")
    with DecodeException with NavigationException derives CanEqual

/** Thrown when the actual runtime type does not match the expected type during decoding or navigation. */
case class TypeMismatchException(path: Seq[String], expected: String, actual: String)(using Frame)
    extends SchemaException(s"Type mismatch" + SchemaException.pathSuffix(
        path
    ) + s": expected $expected but got $actual. Check the input value matches the expected type.")
    with DecodeException with NavigationException derives CanEqual

/** Thrown when a variant name is not defined in the sealed type.
  *
  * Raised at decode time when the discriminator value does not match any known variant, and at config time when a
  * `variantNames` or `variantAlias` call references an unknown Scala variant name.
  */
case class UnknownVariantException(path: Seq[String], variantName: String)(using Frame)
    extends SchemaException(
        s"Unknown variant '$variantName'" + SchemaException.pathSuffix(path) + (
            if path.isEmpty then ". Check that the Scala variant name is one of the defined case class or object variants."
            else ". Check that the discriminator value matches one of the defined case class or object variants."
        )
    )
    with DecodeException derives CanEqual

/** Thrown when an input field is not recognized during decoding.
  *
  * Raised by schemas configured to reject unknown input fields. The path identifies the decoded
  * object whose field set was checked, and `fieldName` is the unmatched wire field encountered in
  * the input.
  *
  * This is decode-only. It is not a `NavigationException`, since the failure is not caused by
  * user code navigating to a missing schema path. It reports malformed input against the configured
  * read policy.
  */
case class UnknownFieldException(path: Seq[String], fieldName: String)(using Frame)
    extends SchemaException(
        s"Unknown field '$fieldName'" + SchemaException.pathSuffix(path) +
            ". Remove this field from the input, or decode with a schema that does not configure denyUnknownFields."
    )
    with DecodeException derives CanEqual

/** Thrown when an untagged sum decode matches no variant.
  *
  * Carries the wire names of the variants attempted, in declaration order. Surfaces as a
  * `Result.Failure` on the decode path.
  */
case class NoVariantMatchException(path: Seq[String], variants: Chunk[String])(using Frame)
    extends SchemaException(
        s"No variant matched the untagged input" + SchemaException.pathSuffix(path) +
            s". Attempted ${variants.size} variants: ${variants.mkString(", ")}. Ensure the input matches one of the declared variants."
    )
    with DecodeException derives CanEqual

/** Thrown when an untagged union decode matches more than one member under the `Strict` ambiguity
  * policy. Carries the matched member wire names. Surfaces as a `Result.Failure` on decode.
  */
case class AmbiguousVariantMatchException(path: Seq[String], matched: Chunk[String])(using Frame)
    extends SchemaException(
        s"Multiple union members matched the untagged input" + SchemaException.pathSuffix(path) +
            s". Matched ${matched.size} members: ${matched.mkString(", ")}. Configure unionAmbiguity(FirstMatch) to resolve by declared order, or tag the union with a representation."
    )
    with DecodeException derives CanEqual

/** Thrown when an adjacently-tagged sum decode input is missing the configured tag key.
  *
  * Surfaces as a `Result.Failure` on the decode path.
  */
case class MissingTagKeyException(path: Seq[String], tagKey: String)(using Frame)
    extends SchemaException(
        s"Missing adjacent tag key '$tagKey'" + SchemaException.pathSuffix(path) +
            ". Add the tag key to the input or check the adjacent representation configuration."
    )
    with DecodeException with NavigationException derives CanEqual

/** Thrown when raw input cannot be parsed into the target type.
  *
  * @param format
  *   the codec that attempted to parse the input
  * @param input
  *   the raw input string (truncated at 50 chars in the message)
  * @param targetType
  *   the expected target type name
  * @param path
  *   the field path within the document where parsing failed
  * @param position
  *   byte or character offset within the input where parsing failed, or -1 if unknown
  */
case class ParseException(
    format: Codec,
    input: String,
    targetType: String,
    path: Seq[String] = Seq.empty,
    position: Int = -1
)(using Frame)
    extends SchemaException(
        s"Cannot parse '${if input.length > 50 then input.take(50) + "..." else input}'" +
            s" as $targetType" +
            (if position >= 0 then s" at position $position" else "") +
            SchemaException.pathSuffix(path)
    ) with DecodeException

/** Thrown when the input byte stream ends before a complete value can be decoded. */
case class TruncatedInputException(format: Codec, detail: String)(using Frame)
    extends SchemaException(s"Truncated input: $detail")
    with DecodeException

/** Thrown when input remains after a complete value has been decoded.
  *
  * The mirror of [[TruncatedInputException]]: that one reports input ending before a value is whole,
  * this one reports input continuing after it. Without it, a decoder that reads one value and stops
  * reports success on input it only partly consumed, so a document holding two values back to back
  * yields the first and drops the rest in silence, and a value with anything stuck to its end looks
  * exactly like a clean parse.
  */
case class TrailingInputException(format: Codec, detail: String)(using Frame)
    extends SchemaException(s"Unexpected trailing content: $detail")
    with DecodeException

/** Thrown when a configured safety limit is exceeded during decoding. */
case class LimitExceededException(limit: String, actual: Int, maximum: Int)(using Frame)
    extends SchemaException(s"$limit $actual exceeds maximum $maximum")
    with DecodeException derives CanEqual

/** Thrown when a numeric value is outside the valid range of the target type (e.g., Int overflow). */
case class RangeException(value: Long, targetType: String, min: Long, max: Long)(using Frame)
    extends SchemaException(s"Value $value out of range for $targetType ($min to $max)")
    with DecodeException derives CanEqual

// --- Validation ---

/** Thrown when a user-defined validation check fails on a field or root value. */
case class ValidationFailedException(path: Seq[String], message: String)(using Frame)
    extends SchemaException(s"Validation failed: $message" + SchemaException.pathSuffix(path))
    with ValidationException derives CanEqual

// --- Transform ---

/** Thrown when a schema transform operation (drop, rename, map) cannot complete. */
case class TransformFailedException(detail: String)(using Frame)
    extends SchemaException(s"Transform failed: $detail")
    with TransformException derives CanEqual

/** Thrown when two variants (or a variant and an alias) map to the same wire discriminator value. */
case class VariantNameCollisionException(wireName: String, variants: Chunk[String])(using Frame)
    extends SchemaException(
        s"Wire name '$wireName' is targeted by ${variants.size} variants: ${variants.mkString(", ")}. Give each variant a distinct wire name."
    )
    with TransformException derives CanEqual

/** Thrown when two fields (or a field and an alias) map to the same wire name. */
case class FieldNameCollisionException(wireName: String, fields: Chunk[String])(using Frame)
    extends SchemaException(
        s"Wire name '$wireName' is targeted by ${fields.size} fields: ${fields.mkString(", ")}. Give each field a distinct wire name."
    )
    with TransformException derives CanEqual

// --- Navigation ---

/** Thrown when a path segment does not exist in the target Structure.Value tree. */
case class PathNotFoundException(path: Seq[String], segment: String)(using Frame)
    extends SchemaException(
        s"Path segment '$segment' not found" + SchemaException.pathSuffix(path) + ". Check that the field name exists in the target type."
    )
    with NavigationException derives CanEqual

/** Thrown when a schema cannot serialize because it lacks a registered write/read function.
  *
  * Occurs when `writeTo` or `readFrom` is called on a focused or Record schema that was not derived with serialization support.
  */
case class SchemaNotSerializableException(detail: String)(using Frame)
    extends SchemaException(
        s"Schema cannot serialize: $detail. Ensure the type is a case class or sealed trait with serialization support, or provide a custom Schema with writeTo/readFrom."
    )
    with TransformException derives CanEqual

/** Thrown when a representation requiring a wire shape the active codec cannot express is used to
  * encode through that codec (a top-level array or bare top-level scalar on a non-self-describing
  * binary codec such as Protobuf). Raised before any bytes are written, never a silently-wrong
  * encoding.
  */
case class RepresentationUnsupportedException(codec: String, representation: String)(using Frame)
    extends SchemaException(
        s"Codec '$codec' cannot express the '$representation' sum representation. Use a self-describing codec (such as: Json, Yaml, Ion, MsgPack) or a different representation."
    )
    with TransformException derives CanEqual

/** Thrown when a representation chain (`representations` / `orElseRepresentation`) contains a
  * duplicate entry. Raised at the builder call site, before any encode, never silently normalized.
  */
case class DuplicateRepresentationException(chain: Chunk[Schema.UnionRepresentation])(using Frame)
    extends SchemaException(
        s"Representation chain contains a duplicate entry: ${chain.mkString(", ")}. Each chain entry must be distinct."
    )
    with TransformException derives CanEqual

/** Thrown when a sequence index is outside the bounds of the sequence during path navigation. */
case class SchemaIndexOutOfBoundsException(path: Seq[String], index: Int, size: Int)(using Frame)
    extends SchemaException(s"Index $index out of bounds (size $size)" + SchemaException.pathSuffix(path))
    with NavigationException derives CanEqual

object SchemaException:
    private[kyo] def pathSuffix(path: Seq[String]): String =
        if path.nonEmpty then s" at ${path.mkString(".")}" else ""
