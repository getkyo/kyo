package kyo

import kyo.internal.whatsapp.Transformers
import kyo.schema.alias
import kyo.schema.catchAll
import kyo.schema.omit
import kyo.schema.rename
import kyo.schema.tagOnly
import kyo.schema.transform
import kyo.schema.untagged

/** A delivery status of a message the business sent, as Meta's webhook delivers it: one item of a change's `statuses`.
  *
  * Its `Schema` is Meta's status object: the message id, its `status` (a [[kyo.WhatsAppStatus.Kind]]), the time (`timestamp`, Meta's
  * epoch-seconds string), the recipient, the conversation it is billed in and how, and the delivery issues of a failed status.
  *
  * A status that does not decode is not a value of this type: the change's `statuses` holds it as an
  * [[kyo.WhatsAppStatus.Entry.Undecodable]], so the statuses beside it still decode.
  */
final case class WhatsAppStatus(
    id: WhatsAppId.MessageId,
    status: WhatsAppStatus.Kind,
    @transform(Transformers.EpochSeconds) timestamp: Instant,
    @rename("recipient_id") recipientId: WhatsAppId.WaId,
    conversation: Maybe[WhatsAppStatus.Conversation] = Absent,
    pricing: Maybe[WhatsAppStatus.Pricing] = Absent,
    @omit(omit.WhenEmpty) errors: Chunk[WhatsAppStatus.DeliveryIssue] = Chunk.empty
) extends WhatsAppStatus.Entry derives CanEqual, Schema

object WhatsAppStatus:

    /** One item of a change's `statuses`: a status, or the JSON of one that does not decode. */
    @untagged
    sealed trait Entry derives CanEqual
    object Entry:
        @catchAll() final case class Undecodable(raw: WhatsAppRawJson) extends Entry derives CanEqual
        given Schema[Entry] = Schema.derived[Entry]

    /** A status's delivery state; `Other(value)` absorbs a state the module does not enumerate. */
    @tagOnly()
    sealed trait Kind derives CanEqual
    object Kind:
        @rename("sent") case object Sent                  extends Kind
        @rename("delivered") case object Delivered        extends Kind
        @rename("read") case object Read                  extends Kind
        @rename("failed") case object Failed              extends Kind
        @rename("deleted") case object Deleted            extends Kind
        @catchAll() final case class Other(value: String) extends Kind derives CanEqual
        given Schema[Kind] = Schema.derived[Kind]
    end Kind

    /** The conversation a status is billed in: Meta's `conversation` object, its expiration read from `expiration_timestamp`. */
    final case class Conversation(
        id: String,
        @rename("expiration_timestamp") @transform(Transformers.OptionalEpochSeconds) expiration: Maybe[Instant] = Absent,
        origin: Conversation.Origin
    ) derives CanEqual, Schema

    object Conversation:
        /** Where the conversation started: Meta's `origin` object, whose `type` is the conversation's category. */
        final case class Origin(`type`: String) derives CanEqual, Schema

    /** How a status is billed: Meta's `pricing` object. */
    final case class Pricing(
        billable: Boolean,
        @rename("pricing_model") pricingModel: String,
        category: String,
        @rename("type") kind: Maybe[String] = Absent
    ) derives CanEqual, Schema

    /** Why a message was not delivered: one entry of a failed status's `errors`. `title` is Meta's short description, read from
      * `message` when Meta sends no `title` (the reference documents both, with equal text); `errorData` is Meta's `error_data`, and
      * `details` its explanation; `href` a link to Meta's error-code documentation. It is data inside the status, not an exception.
      */
    final case class DeliveryIssue(
        code: Int,
        @alias("message") title: Maybe[String] = Absent,
        @rename("error_data") errorData: Maybe[DeliveryIssue.ErrorData] = Absent,
        href: Maybe[String] = Absent
    ) derives CanEqual, Schema:
        def details: Maybe[String] = errorData.flatMap(_.details)
    end DeliveryIssue

    object DeliveryIssue:
        /** Meta's `error_data` object: the `details` explaining the issue. */
        final case class ErrorData(details: Maybe[String] = Absent) derives CanEqual, Schema

end WhatsAppStatus
