package kyo.internal

import kyo.*

/** Scalar browser byte quantity with rejecting wire decoding. */
private[kyo] opaque type DragWireByteSize = ByteSize

/** Construction, access, and scalar schema for browser byte quantities. */
private[kyo] object DragWireByteSize:
    private val maximum = ByteSize.fromBytes(9_007_199_254_740_991L)

    /** Creates a wire byte quantity when the domain value is browser-safe. */
    def from(value: ByteSize): Result[String, DragWireByteSize] = fromRaw(value.toBytes)

    extension (self: DragWireByteSize)
        /** Returns the domain byte quantity. */
        def value: ByteSize = self

    private def fromRaw(value: Long): Result[String, DragWireByteSize] =
        if value < 0 then Result.fail(s"negative byte size: $value")
        else if value > maximum.toBytes then Result.fail(s"byte size exceeds browser safe integer maximum: $value")
        else Result.succeed(ByteSize.fromBytes(value))

    /** Scalar schema that rejects invalid browser byte quantities before constructing `ByteSize`. */
    given Schema[DragWireByteSize] = Schema.init[DragWireByteSize](
        writeFn = (value, writer) => writer.long(value.value.toBytes),
        readFn = reader =>
            kyo.internal.constructedOrThrow(
                fromRaw(reader.long()),
                "WireByteSize"
            )(using reader.frame),
        structure = Structure.Type.Primitive(
            Structure.PrimitiveKind.Long,
            Tag[DragWireByteSize].asInstanceOf[Tag[Any]]
        )
    )
end DragWireByteSize

/** Wire values exchanged while a browser drag session is active.
  *
  * An external drag must establish its session with `DragStart` before sending target movement or drop events. File chunks use canonical
  * RFC 4648 base64. Empty chunks are rejected because they make no streaming progress. Request identifier uniqueness and reuse require
  * session state and remain the responsibility of the Task 10 runtime.
  *
  * Browser file sizes reject negative and unsafe integer wire values before conversion to `ByteSize`. Server-side read ceilings remain
  * authoritative for every requested range and decoded chunk.
  */
private[kyo] object DragProtocol:

    // --- Event payloads ---

    /** Establishes a drag session and carries its transferable manifest exactly once. */
    final private[kyo] case class StartData(
        sessionId: String,
        items: Chunk[ItemData],
        operation: Drag.Operation,
        sourceKey: Maybe[String],
        point: Drag.Point,
        modifiers: UI.Modifiers
    ) derives CanEqual, Schema:

        /** Validates and converts the wire manifest to domain drag items for handler dispatch. */
        def domainItems(limits: Limits = Limits.default): Result[ValidationFailure, Chunk[Drag.Item]] =
            validateItemsAndDomain(items, limits)
    end StartData

    /** Identifies an established drag session and carries only its current target state. */
    final private[kyo] case class TargetData(
        sessionId: String,
        operation: Drag.Operation,
        targetKey: Maybe[String],
        point: Drag.Point,
        modifiers: UI.Modifiers,
        position: Maybe[Drag.Position]
    ) derives CanEqual, Schema

    /** Final browser state for a completed or cancelled drag session. */
    final private[kyo] case class EndData(
        sessionId: String,
        operation: Drag.Operation,
        cancelled: Boolean
    ) derives CanEqual, Schema

    // --- File transfer payloads ---

    /** Browser-safe byte quantity that rejects invalid raw wire integers during schema decoding. */
    private[kyo] type WireByteSize = DragWireByteSize

    /** Construction and access namespace for browser-safe wire byte quantities. */
    private[kyo] val WireByteSize: DragWireByteSize.type = DragWireByteSize

    import DragWireByteSize.*

    /** Browser file metadata whose byte size is validated while decoding. */
    final private[kyo] case class FileMetaData(
        token: String,
        name: String,
        mediaType: String,
        size: WireByteSize,
        lastModified: Instant
    ) derives CanEqual, Schema:

        /** Validates and converts wire metadata to domain file metadata. */
        def toDomain(limits: Limits = Limits.default): Result[ValidationFailure, Drag.FileMeta] =
            validateFileMetaAndDomain(this, limits)
    end FileMetaData

    /** Transfer item whose file byte quantities are validated while decoding. */
    private[kyo] enum ItemData derives CanEqual, Schema:
        case Text(representations: Map[String, String])
        case Uri(value: String)
        case File(meta: FileMetaData)
        case Directory(token: String, name: String)

        /** Validates and converts a wire item to its domain representation. */
        def toDomain(limits: Limits = Limits.default): Result[ValidationFailure, Drag.Item] =
            validateItemAndDomain(this, limits)
    end ItemData

    /** Protocol event whose wire values and drag domain payload have passed one authoritative conversion boundary. */
    sealed private[kyo] trait ValidatedEvent derives CanEqual:
        def wire: UIEvent

    /** Closed construction namespace for validated protocol events. */
    private[kyo] object ValidatedEvent:
        final case class Click private[DragProtocol] (wire: UIEvent.Click)                              extends ValidatedEvent
        final case class ClickSelf private[DragProtocol] (wire: UIEvent.ClickSelf)                      extends ValidatedEvent
        final case class Input private[DragProtocol] (wire: UIEvent.Input)                              extends ValidatedEvent
        final case class Change private[DragProtocol] (wire: UIEvent.Change)                            extends ValidatedEvent
        final case class ChangeChecked private[DragProtocol] (wire: UIEvent.ChangeChecked)              extends ValidatedEvent
        final case class ChangeNumeric private[DragProtocol] (wire: UIEvent.ChangeNumeric)              extends ValidatedEvent
        final case class Submit private[DragProtocol] (wire: UIEvent.Submit)                            extends ValidatedEvent
        final case class KeyDown private[DragProtocol] (wire: UIEvent.KeyDown)                          extends ValidatedEvent
        final case class KeyUp private[DragProtocol] (wire: UIEvent.KeyUp)                              extends ValidatedEvent
        final case class Focus private[DragProtocol] (wire: UIEvent.Focus)                              extends ValidatedEvent
        final case class Blur private[DragProtocol] (wire: UIEvent.Blur)                                extends ValidatedEvent
        final case class Scroll private[DragProtocol] (wire: UIEvent.Scroll)                            extends ValidatedEvent
        final case class Hover private[DragProtocol] (wire: UIEvent.Hover)                              extends ValidatedEvent
        final case class Unhover private[DragProtocol] (wire: UIEvent.Unhover)                          extends ValidatedEvent
        final case class Start private[DragProtocol] (wire: UIEvent.DragStart, items: Chunk[Drag.Item]) extends ValidatedEvent
        final case class End private[DragProtocol] (wire: UIEvent.DragEnd)                              extends ValidatedEvent
        final case class Enter private[DragProtocol] (wire: UIEvent.DragEnter)                          extends ValidatedEvent
        final case class Leave private[DragProtocol] (wire: UIEvent.DragLeave)                          extends ValidatedEvent
        final case class Over private[DragProtocol] (wire: UIEvent.DragOver)                            extends ValidatedEvent
        final case class Drop private[DragProtocol] (wire: UIEvent.Drop)                                extends ValidatedEvent
        final case class SortMove private[DragProtocol] (wire: UIEvent.SortMove)                        extends ValidatedEvent
    end ValidatedEvent

    /** Browser-bound drag source configuration with safe file byte quantities. */
    final private[kyo] case class SourceConfig(
        key: String,
        items: Chunk[ItemData],
        operations: Drag.AllowedOperations,
        label: Maybe[String],
        handle: Boolean,
        preview: Drag.Preview,
        activation: Drag.Activation
    ) derives CanEqual, Schema

    /** Decoded source configuration paired with its validated domain items. */
    final private[kyo] case class ValidatedSourceConfig private[DragProtocol] (
        wire: SourceConfig,
        items: Chunk[Drag.Item]
    ) derives CanEqual

    /** Browser-bound drop acceptance configuration with a safe optional file-size limit. */
    final private[kyo] case class AcceptConfig(
        mediaTypes: Set[String],
        operations: Drag.AllowedOperations,
        maxItems: Maybe[Int],
        maxFileSize: Maybe[WireByteSize],
        directories: Boolean
    ) derives CanEqual, Schema

    /** Browser-bound drop target configuration. */
    final private[kyo] case class TargetConfig(
        key: String,
        accepts: AcceptConfig,
        label: Maybe[String],
        orientation: Drag.Orientation,
        collision: Drag.Collision
    ) derives CanEqual, Schema

    /** Decoded target configuration paired with its validated domain acceptance rules. */
    final private[kyo] case class ValidatedTargetConfig private[DragProtocol] (
        wire: TargetConfig,
        accept: Drag.Accept
    ) derives CanEqual

    /** Directory entry metadata returned by the browser. */
    private[kyo] enum EntryData derives CanEqual, Schema:
        case File(meta: FileMetaData)
        case Directory(token: String, name: String)
    end EntryData

    /** Typed browser failure returned by a file or directory read. */
    private[kyo] enum FileFailureData derives CanEqual, Schema:
        case InvalidToken
        case PermissionDenied
        case NotFound
        case LimitExceeded(reason: String)
        case Io(reason: String)
    end FileFailureData

    // --- Client messages ---

    /** Browser message carrying either a UI event or a drop read response. */
    private[kyo] enum ClientMessage derives CanEqual, Schema:
        case Event(value: UIEvent)

        /** Nonempty canonical base64 data for one nonterminal file stream chunk. */
        case FileChunk(requestId: String, bytesBase64: String)

        /** Terminal success response for a file read. */
        case FileReadComplete(requestId: String)

        /** One directory request/page response. `nextCursor` is absent only when the underlying directory is exhausted. */
        case FileEntries(requestId: String, entries: Chunk[EntryData], nextCursor: Maybe[String])

        /** Terminal failure response for either a file or directory read. */
        case FileFailure(requestId: String, failure: FileFailureData)
    end ClientMessage

    // --- Validation ---

    /** Conservative bounds applied before dispatching an untrusted browser message. */
    final private[kyo] case class Limits(
        maxIdentifierLength: Int,
        maxPathDepth: Int,
        maxItemCount: Int,
        maxTextRepresentationCount: Int,
        maxDirectoryEntryCount: Int,
        maxTextLength: Int,
        maxNameLength: Int,
        maxMediaTypeLength: Int,
        maxReasonLength: Int,
        maxBase64Length: Int,
        maxDecodedChunkSize: ByteSize,
        maxAttributeLength: Int = 1_048_576
    ) derives CanEqual

    private[kyo] object Limits:
        /** Default inbound limits, including a one MiB decoded file chunk ceiling. */
        val default: Limits = Limits(
            maxIdentifierLength = 256,
            maxPathDepth = 256,
            maxItemCount = 128,
            maxTextRepresentationCount = 32,
            maxDirectoryEntryCount = 256,
            maxTextLength = 1_048_576,
            maxNameLength = 1024,
            maxMediaTypeLength = 255,
            maxReasonLength = 4096,
            maxBase64Length = 1_398_104,
            maxDecodedChunkSize = 1.mib,
            maxAttributeLength = 1_048_576
        )
    end Limits

    /** Reason an untrusted browser message cannot enter runtime dispatch. */
    private[kyo] enum ValidationFailure derives CanEqual:
        case Empty(field: String)
        case TooLong(field: String, maximum: Int, actual: Int)
        case TooMany(field: String, maximum: Int, actual: Int)
        case InvalidBase64
        case ChunkTooLarge(maximum: ByteSize, actual: ByteSize)
        case InvalidMediaType(value: String)
        case DuplicateMediaType(value: String)
        case InvalidTimestamp
        case InvalidNumber(field: String)
        case InvalidCount(field: String, actual: Int)
        case InvalidByteSize(field: String, actual: ByteSize)
        case ByteSizeTooLarge(field: String, maximum: ByteSize, actual: ByteSize)
    end ValidationFailure

    private val maxBrowserSafeSize = ByteSize.fromBytes(9_007_199_254_740_991L)

    /** Validates and converts a domain drag source into its browser-bound representation. */
    private[kyo] def sourceConfig(source: Drag.Source, limits: Limits)(using Frame): Result[ValidationFailure, SourceConfig] =
        sourceConfigAndJson(source, limits).map(_._1)

    private def sourceConfigAndJson(
        source: Drag.Source,
        limits: Limits
    )(using Frame): Result[ValidationFailure, (SourceConfig, String)] =
        validateIdentifier(source.key, "source.key", limits)
            .flatMap(_ => validateCount(source.items.size, "source.items", limits.maxItemCount))
            .flatMap(_ => validateAll(source.items)(validateDomainItem(_, limits)))
            .flatMap(_ => validateOptionalText(source.label, "source.label", limits.maxNameLength))
            .flatMap(_ => validatePreview(source.preview, limits))
            .flatMap(_ => toItemData(source.items))
            .map(items =>
                SourceConfig(
                    source.key,
                    items,
                    source.operations,
                    source.label,
                    source.handle,
                    source.preview,
                    source.activation
                )
            )
            .flatMap { config =>
                val encoded = Json.encode(config)
                validateEncodedAttribute(encoded, "dragSource", limits).map(_ => (config, encoded))
            }
    end sourceConfigAndJson

    /** Converts and encodes a source once, validating the exact compact browser attribute value. */
    private[kyo] def encodedSourceConfig(source: Drag.Source, limits: Limits)(using Frame): Result[ValidationFailure, String] =
        sourceConfigAndJson(source, limits).map(_._2)

    /** Revalidates a decoded browser source configuration before the DOM runtime uses it. */
    private[kyo] def validateSourceConfig(config: SourceConfig, limits: Limits)(using
        Frame
    ): Result[ValidationFailure, SourceConfig] =
        validateSourceConfigAndDomain(config, Json.encode(config), limits).map(_.wire)

    /** Validates the exact decoded source attribute and converts its items once. */
    private[kyo] def validateSourceConfigAndDomain(
        config: SourceConfig,
        encoded: String,
        limits: Limits
    ): Result[ValidationFailure, ValidatedSourceConfig] =
        validateIdentifier(config.key, "source.key", limits)
            .flatMap(_ => validateCount(config.items.size, "source.items", limits.maxItemCount))
            .flatMap(_ => validateItemsAndDomain(config.items, limits))
            .flatMap { items =>
                validateOptionalText(config.label, "source.label", limits.maxNameLength)
                    .flatMap(_ => validatePreview(config.preview, limits))
                    .flatMap(_ => validateEncodedAttribute(encoded, "dragSource", limits))
                    .map(_ => ValidatedSourceConfig(config, items))
            }
    end validateSourceConfigAndDomain

    /** Validates and converts domain target rules into their browser-bound representation. */
    private[kyo] def targetConfig(target: Drag.Target, limits: Limits)(using Frame): Result[ValidationFailure, TargetConfig] =
        targetConfigAndJson(target, limits).map(_._1)

    private def targetConfigAndJson(
        target: Drag.Target,
        limits: Limits
    )(using Frame): Result[ValidationFailure, (TargetConfig, String)] =
        val accepts = target.accepts
        validateIdentifier(target.key, "target.key", limits)
            .flatMap(_ => validateOptionalText(target.label, "target.label", limits.maxNameLength))
            .flatMap(_ => validateCount(accepts.mediaTypes.size, "target.mediaTypes", limits.maxTextRepresentationCount))
            .flatMap(_ => validateAll(accepts.mediaTypes)(value => validateAcceptedMediaType(value.render, limits)))
            .flatMap(_ =>
                accepts.maxItems match
                    case Present(value) if value < 0 => Result.fail(ValidationFailure.InvalidCount("target.maxItems", value))
                    case Present(value)              => validateCount(value, "target.maxItems", limits.maxItemCount)
                    case Absent                      => Result.unit
            )
            .flatMap(_ => wireByteSize(accepts.maxFileSize, "target.maxFileSize"))
            .map(maxFileSize =>
                TargetConfig(
                    target.key,
                    AcceptConfig(
                        accepts.mediaTypes.map(_.render),
                        accepts.operations,
                        accepts.maxItems,
                        maxFileSize,
                        accepts.directories
                    ),
                    target.label,
                    target.orientation,
                    target.collision
                )
            )
            .flatMap { config =>
                val encoded = Json.encode(config)
                validateEncodedAttribute(encoded, "dropTarget", limits).map(_ => (config, encoded))
            }
    end targetConfigAndJson

    /** Converts and encodes a target once, validating the exact compact browser attribute value. */
    private[kyo] def encodedTargetConfig(target: Drag.Target, limits: Limits)(using Frame): Result[ValidationFailure, String] =
        targetConfigAndJson(target, limits).map(_._2)

    /** Revalidates a decoded browser target configuration before the DOM runtime uses it. */
    private[kyo] def validateTargetConfig(config: TargetConfig, limits: Limits)(using
        Frame
    ): Result[ValidationFailure, TargetConfig] =
        validateTargetConfigAndDomain(config, Json.encode(config), limits).map(_.wire)

    /** Validates a decoded browser target and converts its media patterns in the same pass. */
    private[kyo] def validateTargetConfigAndDomain(config: TargetConfig, limits: Limits)(using
        Frame
    ): Result[ValidationFailure, ValidatedTargetConfig] =
        validateTargetConfigAndDomain(config, Json.encode(config), limits)

    /** Validates the exact decoded target attribute and converts its media patterns once. */
    private[kyo] def validateTargetConfigAndDomain(
        config: TargetConfig,
        encoded: String,
        limits: Limits
    ): Result[ValidationFailure, ValidatedTargetConfig] =
        val accepts = config.accepts
        validateIdentifier(config.key, "target.key", limits)
            .flatMap(_ => validateOptionalText(config.label, "target.label", limits.maxNameLength))
            .flatMap(_ => validateCount(accepts.mediaTypes.size, "target.mediaTypes", limits.maxTextRepresentationCount))
            .flatMap(_ => parseAcceptedMediaTypes(accepts.mediaTypes, limits))
            .flatMap { mediaTypes =>
                val itemCount = accepts.maxItems match
                    case Present(value) if value < 0 => Result.fail(ValidationFailure.InvalidCount("target.maxItems", value))
                    case Present(value)              => validateCount(value, "target.maxItems", limits.maxItemCount)
                    case Absent                      => Result.unit
                itemCount
                    .flatMap { _ =>
                        accepts.maxFileSize match
                            case Present(value) => validateWireByteSize(value.value, "target.maxFileSize")
                            case Absent         => Result.unit
                    }
                    .flatMap(_ => validateEncodedAttribute(encoded, "dropTarget", limits))
                    .map(_ =>
                        ValidatedTargetConfig(
                            config,
                            Drag.Accept(
                                mediaTypes,
                                accepts.operations,
                                accepts.maxItems,
                                accepts.maxFileSize.map(_.value),
                                accepts.directories
                            )
                        )
                    )
            }
    end validateTargetConfigAndDomain

    private def validateDomainItem(item: Drag.Item, limits: Limits): Result[ValidationFailure, Unit] =
        item match
            case Drag.Item.Text(representations) =>
                validateCount(representations.size, "representations", limits.maxTextRepresentationCount)
                    .flatMap(_ =>
                        validateAll(representations) { case (mediaType, text) =>
                            validateMediaType(mediaType.render, limits)
                                .flatMap(_ => validateText(text, "text", limits.maxTextLength, allowEmpty = true))
                        }
                    )
            case Drag.Item.Uri(value) =>
                validateText(value, "uri", limits.maxTextLength, allowEmpty = false)
            case Drag.Item.File(meta) =>
                validateIdentifier(meta.token, "token", limits)
                    .flatMap(_ => validateText(meta.name, "name", limits.maxNameLength, allowEmpty = false))
                    .flatMap(_ => validateMediaType(meta.mediaType.render, limits))
                    .flatMap(_ => validateWireByteSize(meta.size, "file.size"))
                    .flatMap(_ =>
                        if meta.lastModified >= browserTimestampMin && meta.lastModified <= browserTimestampMax then Result.unit
                        else Result.fail(ValidationFailure.InvalidTimestamp)
                    )
            case Drag.Item.Directory(token, name) =>
                validateIdentifier(token, "token", limits)
                    .flatMap(_ => validateText(name, "name", limits.maxNameLength, allowEmpty = false))
    end validateDomainItem

    private def toItemData(items: Chunk[Drag.Item]): Result[ValidationFailure, Chunk[ItemData]] =
        val builder  = ChunkBuilder.init[ItemData]
        val iterator = items.iterator
        var result   = Result.unit: Result[ValidationFailure, Unit]
        while iterator.hasNext && result.isSuccess do
            result = toItemData(iterator.next()).map(builder.addOne).unit
        result.map(_ => builder.result())
    end toItemData

    private def toItemData(item: Drag.Item): Result[ValidationFailure, ItemData] = item match
        case Drag.Item.Text(representations) =>
            Result.succeed(ItemData.Text(representations.iterator.map((mediaType, value) => mediaType.render -> value).toMap))
        case Drag.Item.Uri(value) => Result.succeed(ItemData.Uri(value))
        case Drag.Item.File(meta) =>
            validatedWireByteSize(meta.size, "file.size").map(size =>
                ItemData.File(FileMetaData(meta.token, meta.name, meta.mediaType.render, size, meta.lastModified))
            )
        case Drag.Item.Directory(token, name) => Result.succeed(ItemData.Directory(token, name))

    private def wireByteSize(
        value: Maybe[ByteSize],
        field: String
    ): Result[ValidationFailure, Maybe[WireByteSize]] =
        value match
            case Absent => Result.succeed(Absent)
            case Present(value) =>
                validatedWireByteSize(value, field).map(Present(_))

    private def validatedWireByteSize(value: ByteSize, field: String): Result[ValidationFailure, WireByteSize] =
        validateWireByteSize(value, field).flatMap { _ =>
            WireByteSize.from(value) match
                case Result.Success(value) => Result.succeed(value)
                case _                     => Result.fail(ValidationFailure.InvalidByteSize(field, value))
        }

    private def validateWireByteSize(value: ByteSize, field: String): Result[ValidationFailure, Unit] =
        if value < ByteSize.Zero then Result.fail(ValidationFailure.InvalidByteSize(field, value))
        else if value > maxBrowserSafeSize then Result.fail(ValidationFailure.ByteSizeTooLarge(field, maxBrowserSafeSize, value))
        else Result.unit

    private def validateOptionalText(
        value: Maybe[String],
        field: String,
        maximum: Int
    ): Result[ValidationFailure, Unit] =
        value match
            case Present(value) => validateText(value, field, maximum, allowEmpty = false)
            case Absent         => Result.unit

    private def validatePreview(preview: Drag.Preview, limits: Limits): Result[ValidationFailure, Unit] =
        preview match
            case Drag.Preview.Label(value) => validateText(value, "source.preview.label", limits.maxNameLength, allowEmpty = false)
            case _                         => Result.unit

    private def validateAcceptedMediaType(value: String, limits: Limits): Result[ValidationFailure, Unit] =
        validateText(value, "mediaType", limits.maxMediaTypeLength, allowEmpty = false).flatMap { _ =>
            if Drag.MediaTypePattern.parse(value).nonEmpty then Result.unit
            else Result.fail(ValidationFailure.InvalidMediaType(value))
        }

    private def parseAcceptedMediaTypes(
        values: Set[String],
        limits: Limits
    ): Result[ValidationFailure, Set[Drag.MediaTypePattern]] =
        val iterator = values.iterator
        var parsed   = Set.empty[Drag.MediaTypePattern]
        var result   = Result.unit: Result[ValidationFailure, Unit]
        while iterator.hasNext && result.isSuccess do
            val value = iterator.next()
            result = validateText(value, "mediaType", limits.maxMediaTypeLength, allowEmpty = false).flatMap { _ =>
                Drag.MediaTypePattern.parse(value) match
                    case Present(pattern) =>
                        if parsed.contains(pattern) then
                            Result.fail(ValidationFailure.DuplicateMediaType(pattern.render))
                        else
                            parsed += pattern
                            Result.unit
                    case Absent => Result.fail(ValidationFailure.InvalidMediaType(value))
            }
        end while
        result.map(_ => parsed)
    end parseAcceptedMediaTypes

    private def validateEncodedAttribute(
        value: String,
        field: String,
        limits: Limits
    ): Result[ValidationFailure, Unit] =
        validateText(value, field, limits.maxAttributeLength, allowEmpty = false)

    /** Validates an untrusted decoded browser message before runtime dispatch. */
    private[kyo] def validate(message: ClientMessage, limits: Limits): Result[ValidationFailure, ClientMessage] =
        val result = message match
            case ClientMessage.Event(value) => validateEventAndDomain(value, limits).unit
            case ClientMessage.FileChunk(requestId, bytesBase64) =>
                validateIdentifier(requestId, "requestId", limits).flatMap(_ => validateBase64(bytesBase64, limits))
            case ClientMessage.FileReadComplete(requestId) =>
                validateIdentifier(requestId, "requestId", limits)
            case ClientMessage.FileEntries(requestId, entries, nextCursor) =>
                validateIdentifier(requestId, "requestId", limits)
                    .flatMap(_ => validateCount(entries.size, "entries", limits.maxDirectoryEntryCount))
                    .flatMap(_ => validateAll(entries)(validateEntry(_, limits)))
                    .flatMap(_ => validateOptionalIdentifier(nextCursor, "nextCursor", limits))
            case ClientMessage.FileFailure(requestId, failure) =>
                validateIdentifier(requestId, "requestId", limits).flatMap(_ => validateFailure(failure, limits))
        result.map(_ => message)
    end validate

    private val browserTimestampMin = Instant.parse("0001-01-01T00:00:00Z").getOrThrow
    private val browserTimestampMax = Instant.parse("9999-12-31T23:59:59.999999999Z").getOrThrow

    /** Validates an untrusted event and converts drag-start items exactly once. */
    private[kyo] def validateEventAndDomain(event: UIEvent, limits: Limits): Result[ValidationFailure, ValidatedEvent] =
        validateCount(event.path.size, "path", limits.maxPathDepth)
            .flatMap(_ => validateAll(event.path)(validateIdentifier(_, "path", limits)))
            .flatMap { _ =>
                event match
                    case event: UIEvent.Click =>
                        validateMouse(event.mouse, limits).map(_ => ValidatedEvent.Click(event))
                    case event: UIEvent.ClickSelf =>
                        validateMouse(event.mouse, limits).map(_ => ValidatedEvent.ClickSelf(event))
                    case event: UIEvent.Input =>
                        validateText(event.value, "value", limits.maxTextLength, allowEmpty = true)
                            .map(_ => ValidatedEvent.Input(event))
                    case event: UIEvent.Change =>
                        validateText(event.value, "value", limits.maxTextLength, allowEmpty = true)
                            .map(_ => ValidatedEvent.Change(event))
                    case event: UIEvent.ChangeChecked => Result.succeed(ValidatedEvent.ChangeChecked(event))
                    case event: UIEvent.ChangeNumeric =>
                        validateNumber(event.value, "value").map(_ => ValidatedEvent.ChangeNumeric(event))
                    case event: UIEvent.Submit => validateMouse(event.mouse, limits).map(_ => ValidatedEvent.Submit(event))
                    case event: UIEvent.KeyDown =>
                        validateKeyboard(event.keyboard, limits).map(_ => ValidatedEvent.KeyDown(event))
                    case event: UIEvent.KeyUp =>
                        validateKeyboard(event.keyboard, limits).map(_ => ValidatedEvent.KeyUp(event))
                    case event: UIEvent.Focus => validateMouse(event.mouse, limits).map(_ => ValidatedEvent.Focus(event))
                    case event: UIEvent.Blur  => validateMouse(event.mouse, limits).map(_ => ValidatedEvent.Blur(event))
                    case event: UIEvent.Scroll =>
                        validateNumber(event.deltaX, "deltaX")
                            .flatMap(_ => validateNumber(event.deltaY, "deltaY"))
                            .flatMap(_ => validateOptionalIdentifier(event.targetId, "targetId", limits))
                            .map(_ => ValidatedEvent.Scroll(event))
                    case event: UIEvent.Hover   => validateMouse(event.mouse, limits).map(_ => ValidatedEvent.Hover(event))
                    case event: UIEvent.Unhover => validateMouse(event.mouse, limits).map(_ => ValidatedEvent.Unhover(event))
                    case event: UIEvent.DragStart =>
                        validateStartAndDomain(event.event, limits).map(ValidatedEvent.Start(event, _))
                    case event: UIEvent.DragEnd => validateEnd(event.event, limits).map(_ => ValidatedEvent.End(event))
                    case event: UIEvent.DragEnter =>
                        validateTarget(event.event, limits).map(_ => ValidatedEvent.Enter(event))
                    case event: UIEvent.DragLeave =>
                        validateTarget(event.event, limits).map(_ => ValidatedEvent.Leave(event))
                    case event: UIEvent.DragOver =>
                        validateTarget(event.event, limits).map(_ => ValidatedEvent.Over(event))
                    case event: UIEvent.Drop => validateTarget(event.event, limits).map(_ => ValidatedEvent.Drop(event))
                    case event: UIEvent.SortMove =>
                        validateSortMove(event.sessionId, event.move, limits).map(_ => ValidatedEvent.SortMove(event))
            }
    end validateEventAndDomain

    /** Validates a locally constructed start without reconverting its already validated domain items. */
    private[kyo] def validatedStart(
        event: UIEvent.DragStart,
        items: Chunk[Drag.Item],
        limits: Limits
    ): Result[ValidationFailure, ValidatedEvent.Start] =
        validateCount(event.path.size, "path", limits.maxPathDepth)
            .flatMap(_ => validateAll(event.path)(validateIdentifier(_, "path", limits)))
            .flatMap(_ => validateIdentifier(event.event.sessionId, "sessionId", limits))
            .flatMap(_ => validateCount(event.event.items.size, "items", limits.maxItemCount))
            .flatMap { _ =>
                if event.event.items.size == items.size then Result.unit
                else Result.fail(ValidationFailure.InvalidCount("domainItems", items.size))
            }
            .flatMap(_ => validateOptionalIdentifier(event.event.sourceKey, "sourceKey", limits))
            .flatMap(_ => validatePoint(event.event.point))
            .map(_ => ValidatedEvent.Start(event, items))
    end validatedStart

    private def validateMouse(mouse: MouseEventData, limits: Limits): Result[ValidationFailure, Unit] =
        validateOptionalIdentifier(mouse.targetId, "targetId", limits)

    private def validateKeyboard(keyboard: KeyboardEventData, limits: Limits): Result[ValidationFailure, Unit] =
        validateIdentifier(keyboard.key, "key", limits)
            .flatMap(_ => validateOptionalIdentifier(keyboard.targetId, "targetId", limits))

    private def validateStartAndDomain(data: StartData, limits: Limits): Result[ValidationFailure, Chunk[Drag.Item]] =
        validateIdentifier(data.sessionId, "sessionId", limits)
            .flatMap(_ => validateCount(data.items.size, "items", limits.maxItemCount))
            .flatMap(_ => validateItemsAndDomain(data.items, limits))
            .flatMap(items =>
                validateOptionalIdentifier(data.sourceKey, "sourceKey", limits)
                    .flatMap(_ => validatePoint(data.point))
                    .map(_ => items)
            )

    private def validateTarget(data: TargetData, limits: Limits): Result[ValidationFailure, Unit] =
        validateIdentifier(data.sessionId, "sessionId", limits)
            .flatMap(_ => validateOptionalIdentifier(data.targetKey, "targetKey", limits))
            .flatMap(_ => validatePoint(data.point))

    private def validateEnd(data: EndData, limits: Limits): Result[ValidationFailure, Unit] =
        validateIdentifier(data.sessionId, "sessionId", limits)

    private def validateSortMove(sessionId: String, move: Drag.Move, limits: Limits): Result[ValidationFailure, Unit] =
        validateIdentifier(sessionId, "sessionId", limits)
            .flatMap(_ => validateCount(move.keys.size, "keys", limits.maxItemCount))
            .flatMap(_ => validateAll(move.keys)(validateIdentifier(_, "key", limits)))
            .flatMap(_ => validateIdentifier(move.source.collection, "source", limits))
            .flatMap(_ => validateIdentifier(move.destination.collection, "destination", limits))
            .flatMap(_ => validateOptionalIdentifier(move.anchor, "anchor", limits))

    private def validateItemsAndDomain(
        items: Chunk[ItemData],
        limits: Limits
    ): Result[ValidationFailure, Chunk[Drag.Item]] =
        val builder  = ChunkBuilder.init[Drag.Item]
        val iterator = items.iterator
        var result   = Result.unit: Result[ValidationFailure, Unit]
        while iterator.hasNext && result.isSuccess do
            result = validateItemAndDomain(iterator.next(), limits).map(builder.addOne).unit
        result.map(_ => builder.result())
    end validateItemsAndDomain

    private def validateItemAndDomain(item: ItemData, limits: Limits): Result[ValidationFailure, Drag.Item] =
        item match
            case ItemData.Text(representations) =>
                validateCount(representations.size, "representations", limits.maxTextRepresentationCount)
                    .flatMap { _ =>
                        val builder  = Map.newBuilder[Drag.MediaType, String]
                        val seen     = scala.collection.mutable.Set.empty[Drag.MediaType]
                        val iterator = representations.iterator
                        var result   = Result.unit: Result[ValidationFailure, Unit]
                        while iterator.hasNext && result.isSuccess do
                            val (rawMediaType, text) = iterator.next()
                            result = parseMediaType(rawMediaType, limits)
                                .flatMap(mediaType =>
                                    if seen.contains(mediaType) then
                                        Result.fail(ValidationFailure.DuplicateMediaType(mediaType.render))
                                    else
                                        validateText(text, "text", limits.maxTextLength, allowEmpty = true)
                                            .map { _ =>
                                                seen += mediaType
                                                builder += mediaType -> text
                                            }
                                )
                                .unit
                        end while
                        result.map(_ => Drag.Item.Text(builder.result()))
                    }
            case ItemData.Uri(value) =>
                validateText(value, "uri", limits.maxTextLength, allowEmpty = false).map(_ => Drag.Item.Uri(value))
            case ItemData.File(meta) => validateFileMetaAndDomain(meta, limits).map(Drag.Item.File(_))
            case ItemData.Directory(token, name) =>
                validateIdentifier(token, "token", limits)
                    .flatMap(_ => validateText(name, "name", limits.maxNameLength, allowEmpty = false))
                    .map(_ => Drag.Item.Directory(token, name))
    end validateItemAndDomain

    private def validateEntry(entry: EntryData, limits: Limits): Result[ValidationFailure, Unit] =
        entry match
            case EntryData.File(meta) => validateFileMetaAndDomain(meta, limits).unit
            case EntryData.Directory(token, name) =>
                validateIdentifier(token, "token", limits)
                    .flatMap(_ => validateText(name, "name", limits.maxNameLength, allowEmpty = false))

    private def validateFileMetaAndDomain(meta: FileMetaData, limits: Limits): Result[ValidationFailure, Drag.FileMeta] =
        validateIdentifier(meta.token, "token", limits)
            .flatMap(_ => validateText(meta.name, "name", limits.maxNameLength, allowEmpty = false))
            .flatMap(_ => parseMediaType(meta.mediaType, limits))
            .flatMap { mediaType =>
                if meta.lastModified >= browserTimestampMin && meta.lastModified <= browserTimestampMax then
                    Result.succeed(Drag.FileMeta(meta.token, meta.name, mediaType, meta.size.value, meta.lastModified))
                else Result.fail(ValidationFailure.InvalidTimestamp)
            }

    private def validateFailure(failure: FileFailureData, limits: Limits): Result[ValidationFailure, Unit] =
        failure match
            case FileFailureData.LimitExceeded(reason) => validateText(reason, "reason", limits.maxReasonLength, allowEmpty = false)
            case FileFailureData.Io(reason)            => validateText(reason, "reason", limits.maxReasonLength, allowEmpty = false)
            case _                                     => Result.unit

    private def validateBase64(value: String, limits: Limits): Result[ValidationFailure, Unit] =
        validateText(value, "bytesBase64", limits.maxBase64Length, allowEmpty = false).flatMap { _ =>
            val length = value.length
            if length % 4 != 0 then Result.fail(ValidationFailure.InvalidBase64)
            else
                val padding =
                    if value.charAt(length - 1) != '=' then 0
                    else if value.charAt(length - 2) == '=' then 2
                    else 1
                val dataLength = length - padding
                var index      = 0
                var valid      = true
                while index < dataLength && valid do
                    valid = base64Value(value.charAt(index)) >= 0
                    index += 1
                while index < length && valid do
                    valid = value.charAt(index) == '='
                    index += 1
                if valid && padding == 2 then
                    valid = (base64Value(value.charAt(length - 3)) & 0x0f) == 0
                else if valid && padding == 1 then
                    valid = (base64Value(value.charAt(length - 2)) & 0x03) == 0
                end if

                if !valid then Result.fail(ValidationFailure.InvalidBase64)
                else
                    val decodedSize = ByteSize.fromBytes((length.toLong / 4L) * 3L - padding.toLong)
                    if decodedSize <= limits.maxDecodedChunkSize then Result.unit
                    else Result.fail(ValidationFailure.ChunkTooLarge(limits.maxDecodedChunkSize, decodedSize))
                end if
            end if
        }
    end validateBase64

    private def base64Value(char: Char): Int =
        if char >= 'A' && char <= 'Z' then char - 'A'
        else if char >= 'a' && char <= 'z' then char - 'a' + 26
        else if char >= '0' && char <= '9' then char - '0' + 52
        else if char == '+' then 62
        else if char == '/' then 63
        else -1

    private def validateMediaType(value: String, limits: Limits): Result[ValidationFailure, Unit] =
        parseMediaType(value, limits).unit

    private def parseMediaType(value: String, limits: Limits): Result[ValidationFailure, Drag.MediaType] =
        validateText(value, "mediaType", limits.maxMediaTypeLength, allowEmpty = false).flatMap { _ =>
            Drag.MediaType.parse(value).toResult(Result.fail(ValidationFailure.InvalidMediaType(value)))
        }

    private def validatePoint(point: Drag.Point): Result[ValidationFailure, Unit] =
        validateNumber(point.x, "point.x").flatMap(_ => validateNumber(point.y, "point.y"))

    private def validateNumber(value: Double, field: String): Result[ValidationFailure, Unit] =
        if java.lang.Double.isFinite(value) then Result.unit
        else Result.fail(ValidationFailure.InvalidNumber(field))

    private def validateOptionalIdentifier(
        value: Maybe[String],
        field: String,
        limits: Limits
    ): Result[ValidationFailure, Unit] =
        value match
            case Present(value) => validateIdentifier(value, field, limits)
            case Absent         => Result.unit

    private def validateIdentifier(value: String, field: String, limits: Limits): Result[ValidationFailure, Unit] =
        validateText(value, field, limits.maxIdentifierLength, allowEmpty = false)

    private def validateText(
        value: String,
        field: String,
        maximum: Int,
        allowEmpty: Boolean
    ): Result[ValidationFailure, Unit] =
        if !allowEmpty && value.isEmpty then Result.fail(ValidationFailure.Empty(field))
        else if value.length > maximum then Result.fail(ValidationFailure.TooLong(field, maximum, value.length))
        else Result.unit

    private def validateCount(actual: Int, field: String, maximum: Int): Result[ValidationFailure, Unit] =
        if actual > maximum then Result.fail(ValidationFailure.TooMany(field, maximum, actual))
        else Result.unit

    private def validateAll[A](values: IterableOnce[A])(
        validateValue: A => Result[ValidationFailure, Unit]
    ): Result[ValidationFailure, Unit] =
        val iterator = values.iterator
        var result   = Result.unit: Result[ValidationFailure, Unit]
        while iterator.hasNext && result.isSuccess do
            result = validateValue(iterator.next())
        result
    end validateAll

end DragProtocol
