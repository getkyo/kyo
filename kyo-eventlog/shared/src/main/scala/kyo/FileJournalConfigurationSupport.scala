package kyo

/** Private wiring for [[FileJournal]]'s `Binary.configuration` / `Jsonl.configuration`
  * factories. Kept in a separate file (rather than inside `FileJournal.scala`) because
  * `FileJournal.scala` is a materialized, byte-locked source: extension methods on
  * `FileJournal.type` are the mechanism that lets this file add `private[kyo]` members
  * callable unqualified (`FileJournal.binaryConfiguration(...)`, etc.) from that locked
  * file without editing it.
  *
  * Each factory resolves its own literal `profileName` directly: with no `P` type
  * parameter, each factory already knows which profile it builds. It also derives
  * `Configuration#metadataMediaType` / `payloadMediaType` from the supplied codecs'
  * `Codec.mediaType`. There is no `segmentedConfiguration` / custom-family
  * construction path: only the two built-in profiles, binary and jsonl, exist.
  */
extension (self: FileJournal.type)

    /** Built-in binary configuration over the shared segmented-append engine (`.seg`
      * framing, CRC32, commit terminators).
      */
    private[kyo] def binaryConfiguration[A](
        journalId: JournalId,
        codecs: EventLog.Codecs[A],
        options: FileJournal.Options
    )(using frame: Frame): FileJournal.Configuration[A] =
        val payloadMediaType = codecs.value match
            case EventLogCodecs.ValueCodec.SchemaValue(_, binary, _) => binary.mediaType
            case _: EventLogCodecs.ValueCodec.BytesValue.type        => "application/octet-stream"
        FileJournal.Configuration[A](
            journalId = journalId,
            codecs = codecs,
            options = options,
            profileName = "binary",
            metadataMediaType = codecs.metadata.codec.mediaType,
            payloadMediaType = payloadMediaType
        )
    end binaryConfiguration

    /** Built-in JSONL configuration over the shared segmented-append engine (LF-delimited
      * `.jsonl` lines, no file header, JSON payload envelope for both schema-derived and
      * raw byte payloads).
      */
    private[kyo] def jsonlConfiguration[A](
        journalId: JournalId,
        codecs: EventLog.Codecs[A],
        options: FileJournal.Options
    )(using frame: Frame): FileJournal.Configuration[A] =
        val payloadMediaType = codecs.value match
            case EventLogCodecs.ValueCodec.SchemaValue(_, _, json) => json.mediaType
            case _: EventLogCodecs.ValueCodec.BytesValue.type      => "application/json"
        FileJournal.Configuration[A](
            journalId = journalId,
            codecs = codecs,
            options = options,
            profileName = "jsonl",
            metadataMediaType = codecs.metadata.codec.mediaType,
            payloadMediaType = payloadMediaType
        )
    end jsonlConfiguration
end extension
