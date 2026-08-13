package kyo.internal

import kyo.*

/** Wire values exchanged while a browser drag session is active. */
private[kyo] object DragProtocol:

    // --- Event payloads ---

    /** Browser drag state shared by start, target, and drop events. */
    final private[kyo] case class EventData(
        sessionId: String,
        items: Chunk[Drag.Item],
        operation: Drag.Operation,
        sourceKey: Maybe[String],
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

    /** Directory entry metadata returned by the browser. */
    private[kyo] enum EntryData derives CanEqual, Schema:
        case File(meta: Drag.FileMeta)
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
        case FileChunk(requestId: String, bytes: Chunk[Byte], done: Boolean)
        case FileEntries(requestId: String, entries: Chunk[EntryData], done: Boolean)
        case FileFailure(requestId: String, failure: FileFailureData)
    end ClientMessage

end DragProtocol
