package kyo

/** Public model for file-backed journals: the two built-in profiles (Binary and JSONL)
  * sharing one internal segmented-append engine. `Binary`/`Jsonl` are plain factory
  * namespaces; a genuinely custom backend implements the already-public [[Journal.Backend]]
  * tier directly, not a composable profile assembly.
  */
object FileJournal:

    /** Durability policy for acknowledged appends. */
    enum Fsync derives CanEqual:
        case Always
        case Disabled
    end Fsync

    /** Engine options after codec and layout selectors migrate elsewhere. No format,
      * no codec slot.
      */
    final case class Options(
        fsync: Fsync = Fsync.Always,
        segmentSize: FileSize = 64L.mib
    ) derives CanEqual
    object Options:
        val default: Options = Options()

    /** Coherence carrier for typed file backend construction: one [[EventLog.Codecs]],
      * one [[Options]]. No profile type parameter: the value
      * type `A` is the only load-bearing type parameter. `profileName`, `metadataMediaType`,
      * and `payloadMediaType` are derived once at construction by [[Binary.configuration]] /
      * [[Jsonl.configuration]] and are never independently settable.
      */
    final case class Configuration[A] private[kyo] (
        journalId: JournalId,
        codecs: EventLog.Codecs[A],
        options: Options,
        profileName: String,
        metadataMediaType: String,
        payloadMediaType: String
    ) derives CanEqual

    /** Typed file-backed SWMR reader. No profile type parameter. */
    trait Reader[A, S] extends Journal.Reader[S]

    /** Typed file backend while preserving the raw backend lane. No profile type parameter. */
    trait Backend[A, S] extends Journal.Backend[S] with Reader[A, S]

    object Binary:
        /** Built-in binary configuration over the shared segmented-append engine. Resolves
          * `profileName = "binary"` directly: this factory IS the binary profile, so no
          * typeclass is summoned for a profile identity.
          */
        def configuration[A](
            journalId: JournalId,
            codecs: EventLog.Codecs[A],
            options: Options = Options.default
        )(using Frame): Configuration[A] < Abort[ConfigurationError] =
            // Resolves the literal profileName "binary" and the codecs' value/metadata
            // Codec.mediaType into payloadMediaType/metadataMediaType, and validates
            // root marker/layout coherence against the shared engine.
            Abort.get(FileJournal.binaryConfiguration(journalId, codecs, options))
    end Binary

    object Jsonl:
        /** Built-in JSONL configuration; `.jsonl` segments, no file header, codec-driven
          * payload. Resolves `profileName = "jsonl"` directly.
          */
        def configuration[A](
            journalId: JournalId,
            codecs: EventLog.Codecs[A],
            options: Options = Options.default
        )(using Frame): Configuration[A] < Abort[ConfigurationError] =
            Abort.get(FileJournal.jsonlConfiguration(journalId, codecs, options))
    end Jsonl

    /** Configuration validation failure (root marker, layout version, media-type
      * derivation).
      */
    final case class ConfigurationError(reason: String)(using Frame) extends KyoException

    // binaryConfiguration / jsonlConfiguration are private[kyo] extension methods on
    // FileJournal.type (kyo-eventlog/shared/src/main/scala/kyo/
    // FileJournalConfigurationSupport.scala) that adapt the shipped FileJournalCore into
    // the Configuration model. Each resolves its own literal profileName ("binary"/"jsonl")
    // directly; derives metadataMediaType from codecs.metadata.codec.mediaType; derives
    // payloadMediaType per the profile's payload rule: codecs.value's binary Codec's
    // mediaType for Binary (a fixed "application/octet-stream" for a BytesValue payload),
    // codecs.value's json Codec's mediaType for Jsonl's SchemaValue payload (a fixed
    // "application/json" for a BytesValue payload). There is no segmentedConfiguration /
    // custom-family construction path: FileJournal.SegmentedFamily, SegmentedComponents, and
    // Components[P] are removed entirely as dead apparatus (verified dead: twelve
    // behaviorless marker traits, no-op built-in witnesses, the engine never read
    // configuration.components).
end FileJournal
