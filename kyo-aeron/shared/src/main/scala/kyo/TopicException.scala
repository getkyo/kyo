package kyo

import kyo.*

/** Base class for all kyo-aeron `Topic` errors, organized into three sealed subcategories by failure mode.
  *
  * The three subcategories map to distinct failure modes:
  *   - [[kyo.TopicBackpressureException]] transient flow-control / not-yet-connected conditions that the retry
  *     schedule absorbs; they surface terminally only after the schedule exhausts.
  *   - [[kyo.TopicPublishException]] terminal failures on the offer path of an established publication
  *     (publication or client closed, max position exceeded, message too large).
  *   - [[kyo.TopicTransportException]] terminal lifecycle / transport failures reachable while adding a
  *     publication or subscription (registration rejected, bounded-add timeout, fatal conductor error).
  *
  * `Topic.publish` fails with `Abort[TopicBackpressureException | TopicPublishException | TopicTransportException]`;
  * `Topic.stream` fails with `Abort[TopicBackpressureException | TopicTransportException]` (the publish-only offer
  * failures are unreachable on the subscribe path). Match on the subcategory to distinguish a retryable
  * transient stall from a terminal transport error, or on a concrete leaf for the specific condition.
  *
  * @see [[kyo.Topic]] the operations that abort with `TopicException`
  * @see [[kyo.TopicBackpressureException]] transient, retry-absorbed failures
  * @see [[kyo.TopicPublishException]] terminal offer-path failures
  * @see [[kyo.TopicTransportException]] terminal add / transport failures
  */
sealed abstract class TopicException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object TopicException:
    // Shared message-formatting helper, mirroring HttpException.stripQuery / showRequest.
    // Renders the (uri, streamId) destination uniformly across leaf messages so the format
    // lives in exactly one place.
    private[kyo] def showDestination(aeronUri: String, streamId: Int): String =
        s"$aeronUri (stream id $streamId)"
end TopicException

// --- Backpressure (transient, retry-absorbed) ---

/** Transient flow-control or not-yet-connected conditions. Absorbed by the retry schedule; surfaces
  * terminally only after the schedule exhausts.
  *
  * @see [[kyo.TopicBackpressureExhaustedException]] the retry schedule ran out while the condition persisted
  */
sealed abstract class TopicBackpressureException(message: String, cause: String | Throwable = "")(using Frame)
    extends TopicException(message, cause)

/** The retry schedule exhausted while Aeron kept signalling back-pressure (publish), no fragments
  * (stream), not-connected, or an admin action (e.g. log rotation). All of these are transient; a
  * longer or more patient `retrySchedule` may let the operation succeed.
  */
case class TopicBackpressureExhaustedException(aeronUri: String, streamId: Int)(using Frame)
    extends TopicBackpressureException(
        s"""Retries exhausted for ${TopicException.showDestination(aeronUri, streamId)}.
           |
           |  Aeron kept signalling a transient condition (back-pressure, not-connected,
           |  no data, or an admin action) for the full retry schedule.
           |
           |  Pass a longer or more patient retrySchedule, or verify a peer is connected.""".stripMargin
    )

// --- Publish (terminal offer-path failures) ---

/** Terminal failures on the offer path of an established publication. None is retryable; the publish
  * aborts.
  *
  * @see [[kyo.TopicPublicationClosedException]] the publication or client was closed
  * @see [[kyo.TopicMaxPositionExceededException]] the publication reached its position limit
  * @see [[kyo.TopicMessageTooLargeException]] a single message exceeds the publication's max message length
  */
sealed abstract class TopicPublishException(message: String, cause: String | Throwable = "")(using Frame)
    extends TopicException(message, cause)

/** The publication or its owning client was closed (offer sentinel -4, or the client was already
  * closed when the publication was added). Terminal: re-run under a live `Topic.run` scope.
  */
case class TopicPublicationClosedException(aeronUri: String, streamId: Int)(using Frame)
    extends TopicPublishException(
        s"""Publication to ${TopicException.showDestination(aeronUri, streamId)} is closed.
           |
           |  The publication or its Aeron client was closed (concurrently, or before the
           |  publication could be added).
           |
           |  Ensure the publish runs within an active Topic.run scope and is not raced by teardown.""".stripMargin
    )

/** The publication reached the maximum position its term-buffer layout allows (offer sentinel -5).
  * Terminal: a new publication (a fresh URI/session) is required to continue.
  */
case class TopicMaxPositionExceededException(aeronUri: String, streamId: Int)(using Frame)
    extends TopicPublishException(
        s"""Publication to ${TopicException.showDestination(aeronUri, streamId)} reached its maximum position.
           |
           |  The term-buffer position limit was hit; this publication can send no further messages.
           |
           |  Use a larger term-length in the Aeron URI, or establish a new publication.""".stripMargin
    )

/** A single logical message exceeds the publication's `maxMessageLength`
  * (`min(termLength >> 3, 16 MiB)`). Terminal: split the batch into smaller messages, or raise the
  * URI term-length.
  */
case class TopicMessageTooLargeException(messageSize: Int, maxMessageLength: Int)(using Frame)
    extends TopicPublishException(
        s"""Message of $messageSize bytes exceeds the Aeron maxMessageLength of $maxMessageLength bytes.
           |
           |  maxMessageLength is min(termLength >> 3, 16 MiB); the default UDP term gives 2 MiB and
           |  aeron:ipc?term-length=64k gives 8 KiB.
           |
           |  Increase the URI term-length, or split the batch into smaller chunks.""".stripMargin
    )

// --- Transport (terminal add / lifecycle failures) ---

/** Terminal lifecycle and transport failures reachable while adding a publication or subscription, or
  * from a fatal conductor condition. None is retryable.
  *
  * @see [[kyo.TopicRegistrationFailedException]] the driver rejected the registration (bad URI / policy)
  * @see [[kyo.TopicAddTimeoutException]] registration did not complete within the add timeout
  * @see [[kyo.TopicTransportFailedException]] a fatal conductor / transport error
  */
sealed abstract class TopicTransportException(message: String, cause: String | Throwable = "")(using Frame)
    extends TopicException(message, cause)

/** The Aeron driver rejected a publication or subscription registration (malformed URI, driver policy,
  * or a non-transient add failure). Terminal: the rejection surfaces directly rather than retrying as
  * back-pressure.
  *
  * `errorCode` is the Aeron driver's positive error code, identical across platforms for a given
  * rejection; `detail` is the driver's accompanying message (`"(no driver detail)"` when the driver
  * supplies none). Check the URI is well-formed and that the driver accepts it.
  */
case class TopicRegistrationFailedException private (aeronUri: String, streamId: Int, errorCode: Int, detail: String)(using Frame)
    extends TopicTransportException(
        s"""Aeron rejected registration for ${TopicException.showDestination(aeronUri, streamId)}.
           |
           |  Driver error $errorCode: $detail
           |
           |  Check the Aeron URI is well-formed and the driver accepts it.""".stripMargin
    )

object TopicRegistrationFailedException:
    /** Normalizes a missing driver detail to a stable placeholder so the message never renders an empty
      * `detail`.
      */
    def apply(aeronUri: String, streamId: Int, errorCode: Int, detail: String)(using Frame): TopicRegistrationFailedException =
        new TopicRegistrationFailedException(aeronUri, streamId, errorCode, if detail.isEmpty then "(no driver detail)" else detail)
end TopicRegistrationFailedException

/** Registration of a publication or subscription did not complete within the add timeout. Terminal:
  * the driver did not register it in time, so it may be unreachable or overloaded. Verify the driver
  * is running, or raise the add timeout.
  */
case class TopicAddTimeoutException(aeronUri: String, streamId: Int, timeout: Duration)(using Frame)
    extends TopicTransportException(
        s"""Registration for ${TopicException.showDestination(aeronUri, streamId)} timed out after ${timeout.show}.
           |
           |  The Aeron conductor did not complete the registration within the deadline. The media driver
           |  may be unreachable or overloaded.
           |
           |  Verify the driver is running, or raise the add timeout.""".stripMargin
    )

/** A fatal Aeron conductor or transport condition (driver timeout, client timeout, command-buffer-full,
  * or broadcast error). Terminal: the Aeron client is no longer usable; restart the `Topic.run` scope
  * to re-establish it.
  */
case class TopicTransportFailedException(detail: String)(using Frame)
    extends TopicTransportException(
        s"""Fatal Aeron transport error: $detail
           |
           |  The Aeron conductor reported an unrecoverable condition and the client is no longer usable.
           |
           |  Restart the Topic.run scope to re-establish the client.""".stripMargin
    )
