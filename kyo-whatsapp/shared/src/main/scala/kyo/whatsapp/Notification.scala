package kyo.whatsapp

import kyo.*

/** Closed ADT for one decoded inbound webhook change, produced by `Webhook.decode` and
  * `Webhook.handler`. Each element in the `entry[].changes[]` array decodes to exactly one
  * `Notification` value. The degenerate-input policy ensures an unknown type never crashes a
  * running webhook: an unrecognized message type decodes to `Content.Unknown`, an unrecognized
  * status to `Status.Other`, and an unrecognized change field or shape to `Unsupported`.
  *
  * Variants:
  *   - `InboundMessage` carries the sender, message id, timestamp, decoded `Content`, optional
  *     reply context, and the sender's display-profile name when the API provides it
  *   - `StatusUpdate` carries the message id, delivery status, timestamp, recipient id,
  *     optional conversation and pricing metadata, and any delivery-level error codes
  *   - `Unsupported` carries the raw change field name and the JSON value for forward
  *     compatibility with Cloud API types not yet enumerated here
  *
  * The `Content` nested ADT enumerates text, media (image, video, audio, sticker, document),
  * location, contacts, reaction, button, list/button replies, order, and system messages.
  * The `Status` nested ADT enumerates sent, delivered, read, failed, deleted, and an open
  * `Other` case for future values.
  */
sealed trait Notification derives CanEqual:
    def metadata: Notification.Metadata

object Notification:

    final case class Metadata(displayPhoneNumber: String, phoneNumberId: Id.PhoneNumberId) derives Schema, CanEqual

    final case class Context(from: Id.WhatsAppId, id: Id.MessageId) derives Schema, CanEqual

    final case class Conversation(id: String, expiration: Maybe[Long] = Absent, originType: String) derives Schema, CanEqual

    final case class Pricing(billable: Boolean, pricingModel: String, category: String, kind: Maybe[String] = Absent)
        derives Schema, CanEqual

    /** Per-type inbound message payload. `Unknown(messageType, raw)` is the degenerate case
      * for a Meta message type this release does not enumerate.
      */
    sealed trait Content derives CanEqual
    object Content:
        final case class Text(body: String) extends Content derives CanEqual
        final case class Media(
            kind: kyo.whatsapp.Media.MediaType,
            id: Id.MediaId,
            mimeType: String,
            sha256: String,
            caption: Maybe[String] = Absent,
            filename: Maybe[String] = Absent,
            voice: Maybe[Boolean] = Absent
        ) extends Content derives CanEqual
        final case class Location(latitude: Double, longitude: Double, name: Maybe[String] = Absent, address: Maybe[String] = Absent)
            extends Content derives CanEqual
        final case class Contacts(contacts: Chunk[Contact])                                        extends Content derives CanEqual
        final case class Reaction(messageId: Id.MessageId, emoji: String)                          extends Content derives CanEqual
        final case class Button(payload: String, text: String)                                     extends Content derives CanEqual
        final case class ListReply(id: String, title: String, description: Maybe[String] = Absent) extends Content derives CanEqual
        final case class ButtonReply(id: String, title: String)                                    extends Content derives CanEqual
        final case class Order(catalogId: String, items: Chunk[String])                            extends Content derives CanEqual
        final case class System(body: String)                                                      extends Content derives CanEqual
        final case class Unknown(messageType: String, raw: String)                                 extends Content derives CanEqual
    end Content

    /** The statuses[].status enum; `Other(value)` absorbs an unenumerated status. */
    sealed trait Status derives CanEqual
    object Status:
        case object Sent                      extends Status
        case object Delivered                 extends Status
        case object Read                      extends Status
        case object Failed                    extends Status
        case object Deleted                   extends Status
        final case class Other(value: String) extends Status derives CanEqual
    end Status

    final case class InboundMessage(
        metadata: Metadata,
        from: Id.WhatsAppId,
        id: Id.MessageId,
        timestamp: Long,
        content: Content,
        context: Maybe[Context] = Absent,
        senderProfileName: Maybe[String] = Absent
    ) extends Notification derives CanEqual

    final case class StatusUpdate(
        metadata: Metadata,
        id: Id.MessageId,
        status: Status,
        timestamp: Long,
        recipientId: Id.WhatsAppId,
        conversation: Maybe[Conversation] = Absent,
        pricing: Maybe[Pricing] = Absent,
        errors: Chunk[WhatsAppError.Cloud] = Chunk.empty
    ) extends Notification derives CanEqual

    final case class Unsupported(metadata: Metadata, field: String, raw: String) extends Notification derives CanEqual

end Notification
