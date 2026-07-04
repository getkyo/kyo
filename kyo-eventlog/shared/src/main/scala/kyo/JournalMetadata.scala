package kyo

/** Structural metadata attached to a journaled event.
  *
  * Metadata is a map from validated dotted-path keys to structural values, stored beside the raw payload. It exists for infrastructure
  * concerns (correlation identifiers, tracing, tags) that consumers may need without decoding the payload. Values are kyo-schema
  * [[kyo.Structure.Value]] trees, so metadata shares the schema layer's structural vocabulary directly.
  *
  * @see
  *   [[kyo.EventEnvelope]] and [[kyo.RecordedEvent]] which carry this metadata
  * @see
  *   [[kyo.MetadataKey]] for the key validation rules
  */
final case class EventMetadata(values: Map[MetadataKey, Structure.Value]) derives CanEqual

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
    def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, MetadataKey] =
        if value.isEmpty || value.startsWith(".") || value.endsWith(".") || value.contains("..") then
            Result.fail(JournalInvalidIdentifierError("MetadataKey", value))
        else Result.succeed(value)

    extension (self: MetadataKey)
        /** The underlying dotted-path string. */
        def value: String = self

        /** The dot-separated segments of the key. */
        def segments: Chunk[String] = Chunk.from(self.split("\\.").toIndexedSeq)
    end extension

    inline given CanEqual[MetadataKey, MetadataKey] = CanEqual.derived
end MetadataKey
