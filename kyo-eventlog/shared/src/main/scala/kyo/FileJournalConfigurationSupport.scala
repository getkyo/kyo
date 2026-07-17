package kyo

/** Private wiring for [[FileJournal]]'s `Binary.configuration` / `Jsonl.configuration`
  * factories. Kept in a separate file (rather than inside `FileJournal.scala`) because
  * `FileJournal.scala` is a materialized, byte-locked source: extension methods on
  * `FileJournal.type` are the mechanism that lets this file add `private[kyo]` members
  * callable unqualified (`FileJournal.binaryConfiguration(...)`, etc.) from that locked
  * file without editing it.
  *
  * `P` is a phantom compile-time profile identity (D-019): no value of type `P` is ever
  * constructed or read here. Each factory resolves the caller's `ProfileName[P]` into
  * `Configuration#profileName` (D-021) and derives `Configuration#metadataMediaType` /
  * `payloadMediaType` from the supplied codecs' `Codec.mediaType` (D-023). There is no
  * `segmentedConfiguration` / custom-family construction path: `Profile` is closed to
  * exactly `Binary` and `Jsonl`.
  */
extension (self: FileJournal.type)

    /** Built-in binary configuration over the shared segmented-append engine (`.seg`
      * framing, CRC32, commit terminators).
      */
    private[kyo] def binaryConfiguration[A](
        journalId: JournalId,
        codecs: EventLog.Codecs[A],
        options: FileJournal.Options
    )(using
        profileName: FileJournal.ProfileName[FileJournal.Binary],
        frame: Frame
    ): Result[FileJournal.ConfigurationError, FileJournal.Configuration[A, FileJournal.Binary]] =
        val payloadMediaType = codecs.value match
            case EventLogCodecs.ValueCodec.SchemaValue(_, binary, _) => binary.mediaType
            case _: EventLogCodecs.ValueCodec.BytesValue.type        => "application/octet-stream"
        Result.succeed(
            FileJournal.Configuration[A, FileJournal.Binary](
                journalId = journalId,
                codecs = codecs,
                options = options,
                profileName = profileName.name,
                metadataMediaType = codecs.metadata.codec.mediaType,
                payloadMediaType = payloadMediaType
            )
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
    )(using
        profileName: FileJournal.ProfileName[FileJournal.Jsonl],
        frame: Frame
    ): Result[FileJournal.ConfigurationError, FileJournal.Configuration[A, FileJournal.Jsonl]] =
        val payloadMediaType = codecs.value match
            case EventLogCodecs.ValueCodec.SchemaValue(_, _, json) => json.mediaType
            case _: EventLogCodecs.ValueCodec.BytesValue.type      => "application/json"
        Result.succeed(
            FileJournal.Configuration[A, FileJournal.Jsonl](
                journalId = journalId,
                codecs = codecs,
                options = options,
                profileName = profileName.name,
                metadataMediaType = codecs.metadata.codec.mediaType,
                payloadMediaType = payloadMediaType
            )
        )
    end jsonlConfiguration
end extension
