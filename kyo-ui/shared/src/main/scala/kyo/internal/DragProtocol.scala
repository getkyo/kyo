package kyo.internal

import kyo.*

/** Scalar browser byte quantity with rejecting wire decoding. */
private[kyo] opaque type DragWireByteSize = ByteSize

/** Construction, access, and scalar schema for browser byte quantities. */
private[kyo] object DragWireByteSize:
    private val maxBrowserSafeSize = ByteSize.fromBytes(9_007_199_254_740_991L)

    /** Creates a wire byte quantity from an already-valid domain byte quantity. */
    def apply(value: ByteSize): DragWireByteSize = value

    extension (self: DragWireByteSize)
        /** Returns the domain byte quantity. */
        def value: ByteSize = self

    private def fromRaw(value: Long): Result[String, DragWireByteSize] =
        if value < 0 then Result.fail(s"negative byte size: $value")
        else if value > maxBrowserSafeSize.toBytes then Result.fail(s"byte size exceeds browser safe integer maximum: $value")
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

        /** Converts the validated wire manifest to domain drag items for handler dispatch. */
        def domainItems: Chunk[Drag.Item] = items.map(_.toDomain)
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

    /** Constructor and accessor namespace for browser-safe wire byte quantities. */
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

        /** Converts validated wire metadata to domain file metadata. */
        def toDomain: Drag.FileMeta = Drag.FileMeta(token, name, mediaType, size.value, lastModified)
    end FileMetaData

    /** Transfer item whose file byte quantities are validated while decoding. */
    private[kyo] enum ItemData derives CanEqual, Schema:
        case Text(representations: Map[String, String])
        case Uri(value: String)
        case File(meta: FileMetaData)
        case Directory(token: String, name: String)

        /** Converts a validated wire item to its domain representation. */
        def toDomain: Drag.Item = this match
            case Text(representations)  => Drag.Item.Text(representations)
            case Uri(value)             => Drag.Item.Uri(value)
            case File(meta)             => Drag.Item.File(meta.toDomain)
            case Directory(token, name) => Drag.Item.Directory(token, name)
    end ItemData

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
        maxDecodedChunkSize: ByteSize
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
            maxDecodedChunkSize = 1.mib
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
        case InvalidTimestamp
        case InvalidNumber(field: String)
    end ValidationFailure

    /** Validates an untrusted decoded browser message before runtime dispatch. */
    private[kyo] def validate(message: ClientMessage, limits: Limits): Result[ValidationFailure, ClientMessage] =
        val result = message match
            case ClientMessage.Event(value) => validateEvent(value, limits)
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

    private def validateEvent(event: UIEvent, limits: Limits): Result[ValidationFailure, Unit] =
        validateCount(event.path.size, "path", limits.maxPathDepth)
            .flatMap(_ => validateAll(event.path)(validateIdentifier(_, "path", limits)))
            .flatMap { _ =>
                event match
                    case event: UIEvent.Click         => validateMouse(event.mouse, limits)
                    case event: UIEvent.ClickSelf     => validateMouse(event.mouse, limits)
                    case event: UIEvent.Input         => validateText(event.value, "value", limits.maxTextLength, allowEmpty = true)
                    case event: UIEvent.Change        => validateText(event.value, "value", limits.maxTextLength, allowEmpty = true)
                    case _: UIEvent.ChangeChecked     => Result.unit
                    case event: UIEvent.ChangeNumeric => validateNumber(event.value, "value")
                    case event: UIEvent.Submit        => validateMouse(event.mouse, limits)
                    case event: UIEvent.KeyDown       => validateKeyboard(event.keyboard, limits)
                    case event: UIEvent.KeyUp         => validateKeyboard(event.keyboard, limits)
                    case event: UIEvent.Focus         => validateMouse(event.mouse, limits)
                    case event: UIEvent.Blur          => validateMouse(event.mouse, limits)
                    case event: UIEvent.Scroll =>
                        validateNumber(event.deltaX, "deltaX")
                            .flatMap(_ => validateNumber(event.deltaY, "deltaY"))
                            .flatMap(_ => validateOptionalIdentifier(event.targetId, "targetId", limits))
                    case event: UIEvent.Hover     => validateMouse(event.mouse, limits)
                    case event: UIEvent.Unhover   => validateMouse(event.mouse, limits)
                    case event: UIEvent.DragStart => validateStart(event.event, limits)
                    case event: UIEvent.DragEnd   => validateEnd(event.event, limits)
                    case event: UIEvent.DragEnter => validateTarget(event.event, limits)
                    case event: UIEvent.DragLeave => validateTarget(event.event, limits)
                    case event: UIEvent.DragOver  => validateTarget(event.event, limits)
                    case event: UIEvent.Drop      => validateTarget(event.event, limits)
                    case event: UIEvent.SortMove  => validateSortMove(event.sessionId, event.move, limits)
            }
    end validateEvent

    private def validateMouse(mouse: MouseEventData, limits: Limits): Result[ValidationFailure, Unit] =
        validateOptionalIdentifier(mouse.targetId, "targetId", limits)

    private def validateKeyboard(keyboard: KeyboardEventData, limits: Limits): Result[ValidationFailure, Unit] =
        validateIdentifier(keyboard.key, "key", limits)
            .flatMap(_ => validateOptionalIdentifier(keyboard.targetId, "targetId", limits))

    private def validateStart(data: StartData, limits: Limits): Result[ValidationFailure, Unit] =
        validateIdentifier(data.sessionId, "sessionId", limits)
            .flatMap(_ => validateCount(data.items.size, "items", limits.maxItemCount))
            .flatMap(_ => validateAll(data.items)(validateItem(_, limits)))
            .flatMap(_ => validateOptionalIdentifier(data.sourceKey, "sourceKey", limits))
            .flatMap(_ => validatePoint(data.point))

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

    private def validateItem(item: ItemData, limits: Limits): Result[ValidationFailure, Unit] =
        item match
            case ItemData.Text(representations) =>
                validateCount(representations.size, "representations", limits.maxTextRepresentationCount)
                    .flatMap(_ =>
                        validateAll(representations) { case (mediaType, text) =>
                            validateMediaType(mediaType, limits)
                                .flatMap(_ => validateText(text, "text", limits.maxTextLength, allowEmpty = true))
                        }
                    )
            case ItemData.Uri(value) =>
                validateText(value, "uri", limits.maxTextLength, allowEmpty = false)
            case ItemData.File(meta) => validateFileMeta(meta, limits)
            case ItemData.Directory(token, name) =>
                validateIdentifier(token, "token", limits)
                    .flatMap(_ => validateText(name, "name", limits.maxNameLength, allowEmpty = false))
    end validateItem

    private def validateEntry(entry: EntryData, limits: Limits): Result[ValidationFailure, Unit] =
        entry match
            case EntryData.File(meta) => validateFileMeta(meta, limits)
            case EntryData.Directory(token, name) =>
                validateIdentifier(token, "token", limits)
                    .flatMap(_ => validateText(name, "name", limits.maxNameLength, allowEmpty = false))

    private def validateFileMeta(meta: FileMetaData, limits: Limits): Result[ValidationFailure, Unit] =
        validateIdentifier(meta.token, "token", limits)
            .flatMap(_ => validateText(meta.name, "name", limits.maxNameLength, allowEmpty = false))
            .flatMap(_ => validateMediaType(meta.mediaType, limits))
            .flatMap { _ =>
                if meta.lastModified >= browserTimestampMin && meta.lastModified <= browserTimestampMax then Result.unit
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
        validateText(value, "mediaType", limits.maxMediaTypeLength, allowEmpty = false).flatMap { _ =>
            val normalized = value.trim
            val slash      = normalized.indexOf('/')
            if slash > 0 && slash == normalized.lastIndexOf('/') && slash < normalized.length - 1 &&
                isMediaToken(normalized.substring(0, slash)) && isMediaToken(normalized.substring(slash + 1))
            then Result.unit
            else Result.fail(ValidationFailure.InvalidMediaType(value))
            end if
        }

    private def isMediaToken(value: String): Boolean =
        value.nonEmpty && !value.contains('*') && value.forall { char =>
            (char >= 'a' && char <= 'z') ||
            (char >= 'A' && char <= 'Z') ||
            (char >= '0' && char <= '9') ||
            "!#$%&'*+-.^_`|~".contains(char)
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
