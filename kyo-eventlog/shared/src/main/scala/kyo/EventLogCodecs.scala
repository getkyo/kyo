package kyo

/** Raised when a schema-derived codec configuration cannot be honored: a value or metadata
  * codec choice that is wire-incompatible with the derived `Schema[A]` shape. Surfaced by
  * [[EventLogCodecs.schema]] before any [[EventLogCodecs.Codecs]] value is constructed, so an
  * incompatible pairing is a construction-time failure, never a per-event decode surprise.
  * This is the codec CONFIGURATION row; a decode-time failure is a JournalReadFailure
  * (undecodable bytes fold into JournalCorruptedError), never a codec-specific read row.
  */
final case class EventCodecConfigurationError(reason: String)(using Frame)
    extends KyoException(s"Incompatible codec configuration: $reason") derives CanEqual

/** Codec authority for a typed EventLog: the single owner of value and metadata byte
  * encoding. The value and metadata codecs are data-only descriptors; the byte transform
  * lives in the private[kyo] interpreter ([[EventLogCodecs.encodeValue]] and
  * [[EventLogCodecs.decodeValue]]). A [[Codecs]] is built only by [[EventLogCodecs.schema]]
  * (schema-derived values) or [[EventLogCodecs.bytes]] (raw `Span[Byte]` payloads); both
  * factories build the private[kyo] apply. Codec choices are constructed here before downstream
  * event-log and file-journal values use the descriptors.
  */
object EventLogCodecs:

    /** Value codec descriptor for domain type `A`. Data-only: [[ValueCodec.SchemaValue]]
      * carries the `Schema[A]` and the binary and JSONL `Codec` choices;
      * [[ValueCodec.BytesValue]] is the identity descriptor for raw `Span[Byte]` payloads.
      * The encode and decode behavior lives in the interpreter ([[encodeValue]] and
      * [[decodeValue]]), never on the descriptor.
      */
    sealed trait ValueCodec[A] derives CanEqual
    object ValueCodec:
        /** Schema-derived value descriptor: `binary` frames `.seg` payloads and `json` embeds
          * the payload for the JSONL lane. Ion Binary is the default binary framing.
          */
        final case class SchemaValue[A](schema: Schema[A], binary: Codec, json: Codec) extends ValueCodec[A]

        /** Identity descriptor for raw `Span[Byte]` payloads. */
        case object BytesValue extends ValueCodec[Span[Byte]]
    end ValueCodec

    /** Metadata codec descriptor: a thin wrapper over the kyo-schema `Codec` that frames an
      * [[kyo.Event.Metadata]] map. Data-only; the metadata byte framing is applied by the file
      * segment codec over this `codec`.
      */
    final case class MetadataCodec(codec: Codec) derives CanEqual

    /** The coherent value+metadata codec pair. One authority, type-represented so a codec
      * clash between a log and a file backend is a compile error, not a runtime discovery.
      * The apply is private[kyo]: [[schema]] is the construction path.
      */
    final case class Codecs[A] private[kyo] (value: ValueCodec[A], metadata: MetadataCodec) derives CanEqual

    /** Schema-derived codecs. `binary` and `json` are the value-codec choices (Ion Binary by
      * default, JSON for JSONL backends); `metadata` defaults to Ion Binary. Validates codec
      * compatibility before returning.
      *
      * @see [[Codec]] for the kyo-schema byte-codec integration
      */
    def schema[A](
        binary: Codec = IonBinary(),
        json: Codec = Json(),
        metadata: Codec = IonBinary()
    )(using Schema[A], Frame): Codecs[A] < Abort[EventCodecConfigurationError] =
        // Validate before building descriptors so an incompatible codec pairing fails at construction.
        Abort.get(
            EventCodecCompat.validate[A](binary, json, metadata)
                .map(_ => Codecs(ValueCodec.SchemaValue(summon[Schema[A]], binary, json), MetadataCodec(metadata)))
        )

    /** Identity codecs for raw `Span[Byte]` payloads: the value is stored verbatim
      * ([[ValueCodec.BytesValue]] is the identity descriptor, so encode and decode are
      * passthrough), and `metadata` frames the [[kyo.Event.Metadata]] map, defaulting to Ion
      * Binary. No schema is derived and there is no value-codec compatibility to validate;
      * the `Abort[EventCodecConfigurationError]` row matches [[schema]] so both factories
      * share one construction surface.
      *
      * @see [[Codec]] for the kyo-schema byte-codec integration
      */
    def bytes(metadata: Codec = IonBinary())(using Frame): Codecs[Span[Byte]] < Abort[EventCodecConfigurationError] =
        Codecs(ValueCodec.BytesValue, MetadataCodec(metadata))

    /** Encodes a value to its stored byte form. Carries `Sync`: a schema writer allocates a
      * fresh mutable builder. Selecting the binary or JSONL lane is the file segment codec's
      * concern; this transform frames through the descriptor's binary `Codec`.
      */
    private[kyo] def encodeValue[A](codec: ValueCodec[A], value: A)(using Frame): Span[Byte] < Sync =
        codec match
            case ValueCodec.SchemaValue(schema, binary, _) =>
                Sync.defer {
                    val writer = binary.newWriter()
                    schema.writeTo(value, writer)
                    writer.result()
                }
            case _: ValueCodec.BytesValue.type =>
                // BytesValue extends ValueCodec[Span[Byte]]; the value is already stored form.
                Sync.defer(value)

    /** Decodes a stored byte form back to a value. Carries no `Sync`, so read paths can keep
      * their failure-only effect rows. Undecodable bytes fold into JournalCorruptedError on
      * Abort[JournalReadFailure]; the read boundary never widens to a codec-specific error row.
      */
    private[kyo] def decodeValue[A](codec: ValueCodec[A], bytes: Span[Byte])(using Frame): A < Abort[JournalReadFailure] =
        codec match
            case ValueCodec.SchemaValue(schema, binary, _) =>
                Abort.get(
                    Result.catching[DecodeException](schema.readFrom(binary.newReader(bytes)))
                        .mapFailure(error => JournalCorruptedError(Absent, error.getMessage))
                )
            case _: ValueCodec.BytesValue.type =>
                bytes

    // EventCodecCompat.validate is the private[kyo] construction-time compatibility check:
    // a Codec.Writer capability reconciliation against the Schema[A] top-level
    // representation, over the kyo-schema Codec surface, leaving Schema derivation unchanged.
end EventLogCodecs

/** Construction-time compatibility check for [[EventLogCodecs.schema]]: reconciles a `Codec`'s
  * writer capability against a `Schema[A]`'s top-level structural shape. A `Structure.Type.Product`
  * (the object-shaped case every built-in codec accepts) is always compatible; any other top-level
  * shape (a primitive, a sum, a collection) requires the codec's writer to declare
  * `canWriteTopLevelNonObject`.
  */
private[kyo] object EventCodecCompat:

    private def checkCapability(
        label: String,
        codec: Codec,
        structure: Structure.Type
    )(using Frame): Result[EventCodecConfigurationError, Unit] =
        structure match
            case _: Structure.Type.Product => Result.unit
            case _ =>
                if codec.newWriter().capabilities.canWriteTopLevelNonObject then Result.unit
                else
                    Result.fail(EventCodecConfigurationError(
                        s"$label codec cannot represent the non-object top-level shape of ${structure.name}"
                    ))

    /** Validates `binary` and `json` against `Schema[A]`'s top-level structure. `metadata` frames
      * [[kyo.Event.Metadata]], whose structure is always `Product`-shaped, so it is unconditionally
      * compatible with every codec and needs no capability check.
      */
    private[kyo] def validate[A](binary: Codec, json: Codec, metadata: Codec)(using
        schema: Schema[A],
        frame: Frame
    ): Result[EventCodecConfigurationError, Unit] =
        for
            _ <- checkCapability("binary value", binary, schema.structure)
            _ <- checkCapability("json value", json, schema.structure)
        yield ()
end EventCodecCompat
