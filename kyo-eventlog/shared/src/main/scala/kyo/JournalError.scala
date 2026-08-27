package kyo

/** Recoverable failures for [[Journal]] operations, used with `Abort[JournalError]`.
  *
  * `JournalError` is the sealed umbrella base of the journal's flat KyoException hierarchy. Three sealed per-operation traits name each
  * operation's precise failure set on the [[Journal.Backend]] contract: [[JournalAppendFailure]], [[JournalReadFailure]],
  * [[JournalStreamInfoFailure]]. Each leaf mixes in every operation trait it can occur in. [[Journal.run]] leaves each op's `Abort[<trait>]`
  * on the residual; a caller catches them all at once with `Abort.run[JournalError]`, into which every per-op row widens by subtyping.
  *
  * `JournalEmptyAppendError` and `JournalConflictError` are append-only; `JournalCorruptedError` and `JournalStorageError` are
  * durable-backend cases that cross-cut all three operations; `JournalInvalidIdentifierError` carries no operation trait (it is the
  * `Result` error of identifier construction, never on an `Abort` op row).
  *
  * @see
  *   [[kyo.Journal]] for the operations that abort with these errors
  * @see
  *   [[kyo.ExpectedOffset]] for the append concurrency model behind [[JournalConflictError]]
  */
sealed abstract class JournalError(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** Failure set of [[Journal.Backend.append]] (and the [[Journal.append]] operation under [[Journal.run]]). */
sealed trait JournalAppendFailure extends JournalError

/** Failure set of [[Journal.Backend.read]]. */
sealed trait JournalReadFailure extends JournalError

/** Failure set of [[Journal.Backend.streamInfo]]. */
sealed trait JournalStreamInfoFailure extends JournalError

/** An append was attempted with an empty event chunk. */
case class JournalEmptyAppendError()(using Frame)
    extends JournalError("Cannot append an empty batch; an append requires at least one event.")
    with JournalAppendFailure derives CanEqual

/** The expected offset did not match the live stream state; `actual` is the state observed at append time. */
case class JournalConflictError(streamId: Event.StreamId, expected: ExpectedOffset, actual: StreamInfo)(using Frame)
    extends JournalError(s"Append to stream '${streamId.value}' expected $expected but observed $actual.")
    with JournalAppendFailure derives CanEqual

/** An Event.StreamId, Event.Id, Event.Type, Event.StreamOffset, Event.StreamVersion, or Event.Metadata.Key value failed validation. */
case class JournalInvalidIdentifierError(kind: String, value: String)(using Frame)
    extends JournalError(s"Invalid $kind: '$value'.") derives CanEqual

/** A durable backend detected unrecoverable corruption in stored events and refuses to repair it silently. */
case class JournalCorruptedError(streamId: Maybe[Event.StreamId], detail: String)(using Frame)
    extends JournalError(s"Corrupted journal data${streamId.map(s => s" for stream '${s.value}'").getOrElse("")}: $detail.")
    with JournalAppendFailure with JournalReadFailure with JournalStreamInfoFailure derives CanEqual

/** An I/O failure from a durable backend's underlying storage. */
case class JournalStorageError(detail: String, cause: Maybe[Throwable])(using Frame)
    extends JournalError(s"Journal storage failure: $detail.", cause.getOrElse(""))
    with JournalAppendFailure with JournalReadFailure with JournalStreamInfoFailure derives CanEqual
