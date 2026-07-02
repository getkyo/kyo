package kyo

/** Structural metadata attached to a journaled event.
  *
  * Metadata is a map from validated dotted-path keys to structural values, stored beside the raw payload. It exists for infrastructure
  * concerns (correlation identifiers, tracing, tags) that consumers may need without decoding the payload. The value tree mirrors the
  * shape of kyo-schema's `Structure.Value` without depending on it; a bridge layer with access to both types provides lossless conversion.
  *
  * @see
  *   [[kyo.EventEnvelope]] and [[kyo.RecordedEvent]] which carry this metadata
  * @see
  *   [[kyo.MetadataKey]] for the key validation rules
  */
final case class EventMetadata(values: Map[MetadataKey, MetadataValue]) derives CanEqual

object EventMetadata:
    /** Metadata with no entries. */
    val empty: EventMetadata = EventMetadata(Map.empty)
end EventMetadata

/** Validated dotted-path metadata key, such as `trace.correlation_id`.
  *
  * Keys are non-empty and contain no empty segments: `""`, `.foo`, `foo.`, and `foo..bar` are all rejected. The constructor returns a
  * `Result`; there is no unchecked public construction.
  */
opaque type MetadataKey = String

object MetadataKey:
    /** Creates a validated metadata key, failing on an empty key or any empty dot-separated segment. */
    def apply(value: String): Result[JournalError.InvalidIdentifier, MetadataKey] =
        if value.isEmpty || value.startsWith(".") || value.endsWith(".") || value.contains("..") then
            Result.fail(JournalError.InvalidIdentifier("MetadataKey", value))
        else Result.succeed(value)

    extension (self: MetadataKey)
        /** The underlying dotted-path string. */
        def value: String = self

        /** The dot-separated segments of the key. */
        def segments: Chunk[String] = Chunk.from(self.split("\\.").toIndexedSeq)
    end extension

    given CanEqual[MetadataKey, MetadataKey] = CanEqual.derived
end MetadataKey

/** Structural metadata value: records, variant cases, sequences, map entries, and scalars.
  *
  * The cases mirror kyo-schema's `Structure.Value` one-to-one; a bridge layer that can see both types provides lossless conversion in both directions. Structural equality via `derives CanEqual` is the only interpretation kyo-core applies.
  */
enum MetadataValue derives CanEqual:
    case Record(fields: Chunk[(String, MetadataValue)])
    case VariantCase(name: String, value: MetadataValue)
    case Sequence(elements: Chunk[MetadataValue])
    case MapEntries(entries: Chunk[(MetadataValue, MetadataValue)])
    case Str(value: String)
    case Bool(value: Boolean)
    case Integer(value: Long)
    case Decimal(value: Double)
    case BigNum(value: BigDecimal)
    case Null
end MetadataValue
