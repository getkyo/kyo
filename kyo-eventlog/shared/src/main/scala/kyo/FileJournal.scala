package kyo

/** Public model for file-backed journals: the two built-in profiles (Binary and JSONL)
  * sharing one internal segmented-append engine. `Profile` is sealed to exactly these
  * two; a genuinely custom backend implements the already-public [[Journal.Backend]]
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

    /** Phantom compile-time profile identity. No value of type `Profile` is ever
      * constructed, stored, or matched on; every profile-specific runtime behavior lives
      * in the internal engine's `SegmentCodec` dispatch (keyed on the derived
      * `profileName`) plus the `Configuration#profileName` / `metadataMediaType` /
      * `payloadMediaType` fields derived once at construction. Stays sealed, closed to
      * exactly `Binary` and `Jsonl`: there is no custom-family extension point. A caller
      * needing a genuinely different backend implements [[Journal.Backend]] directly.
      */
    sealed trait Profile

    /** Closed built-in profile marker: intrinsic binary framing, Ion Binary default value
      * and metadata encoding, `.seg` files, CRC validation, commit terminators. No value
      * of this type is ever constructed.
      */
    sealed trait Binary extends Profile

    /** Closed built-in profile marker: JSON value storage for schema-backed payloads,
      * base64 JSON strings for byte payloads, LF-delimited `.jsonl` lines. No value of
      * this type is ever constructed.
      */
    sealed trait Jsonl extends Profile

    /** Stable name/label for a profile, keyed on the phantom type `P`. `Binary` and
      * `Jsonl` supply the built-in names below; `Profile` is closed, so these are the
      * only two instances that will ever exist. Resolved once, at [[Configuration]]
      * construction, into `Configuration#profileName`.
      */
    trait ProfileName[P <: Profile]:
        def name: String

    object ProfileName:
        given ProfileName[Binary] with
            def name: String = "binary"
        given ProfileName[Jsonl] with
            def name: String = "jsonl"
    end ProfileName

    /** Coherence carrier for typed file backend construction: one [[EventLog.Codecs]],
      * one [[Options]]. `P` is a phantom compile-time profile identity carrying no
      * runtime value. `profileName`, `metadataMediaType`, and `payloadMediaType` are
      * derived once at construction by [[Binary.configuration]] / [[Jsonl.configuration]]
      * and are never independently settable.
      */
    final case class Configuration[A, P <: Profile] private[kyo] (
        journalId: JournalId,
        codecs: EventLog.Codecs[A],
        options: Options,
        profileName: String,
        metadataMediaType: String,
        payloadMediaType: String
    ) derives CanEqual

    /** Typed file-backed SWMR reader. */
    trait Reader[A, P <: Profile, S] extends Journal.Reader[S]

    /** Typed file backend while preserving the raw backend lane. */
    trait Backend[A, P <: Profile, S] extends Journal.Backend[S] with Reader[A, P, S]

    object Binary:
        /** Built-in binary configuration over the shared segmented-append engine.
          * Requires `ProfileName[Binary]` (satisfied by the built-in given above) rather
          * than constructing a `Binary` value; resolves `profileName`,
          * `metadataMediaType`, and `payloadMediaType` at construction.
          */
        def configuration[A](
            journalId: JournalId,
            codecs: EventLog.Codecs[A],
            options: Options = Options.default
        )(using ProfileName[Binary], Frame): Configuration[A, Binary] < Abort[ConfigurationError] =
            // Resolves ProfileName[Binary].name into profileName and the codecs' value/
            // metadata Codec.mediaType into payloadMediaType/metadataMediaType (D-023),
            // and validates root marker/layout coherence against the shared engine.
            Abort.get(FileJournal.binaryConfiguration(journalId, codecs, options))
    end Binary

    object Jsonl:
        /** Built-in JSONL configuration; `.jsonl` segments, no file header, codec-driven
          * payload. Requires `ProfileName[Jsonl]` (satisfied by the built-in given
          * above); resolves `profileName`, `metadataMediaType`, and `payloadMediaType`
          * at construction.
          */
        def configuration[A](
            journalId: JournalId,
            codecs: EventLog.Codecs[A],
            options: Options = Options.default
        )(using ProfileName[Jsonl], Frame): Configuration[A, Jsonl] < Abort[ConfigurationError] =
            Abort.get(FileJournal.jsonlConfiguration(journalId, codecs, options))
    end Jsonl

    /** Configuration validation failure (root marker, layout version, media-type
      * derivation).
      */
    final case class ConfigurationError(reason: String)(using Frame) extends KyoException

    // binaryConfiguration / jsonlConfiguration are private[kyo] extension methods on
    // FileJournal.type (kyo-eventlog/shared/src/main/scala/kyo/
    // FileJournalConfigurationSupport.scala) that adapt the shipped FileJournalCore into
    // the Configuration model. Each summons the caller's ProfileName[P] and stores its
    // .name into profileName; derives metadataMediaType from
    // codecs.metadata.codec.mediaType; derives payloadMediaType per the profile's
    // payload rule (D-023): codecs.value's binary Codec's mediaType for Binary (a fixed
    // "application/octet-stream" for a BytesValue payload), codecs.value's json Codec's
    // mediaType for Jsonl's SchemaValue payload (a fixed "application/json" for a
    // BytesValue payload). There is no segmentedConfiguration / custom-family
    // construction path: FileJournal.SegmentedFamily, SegmentedComponents, and
    // Components[P] are removed entirely as dead apparatus (verified dead: twelve
    // behaviorless marker traits, no-op built-in witnesses, the engine never read
    // configuration.components).
end FileJournal
