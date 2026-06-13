package kyo

/** Closed ADT for one decoded inbound webhook change, produced by `WhatsAppWebhook.decode` and
  * `WhatsAppWebhook.handler`. Each element in the `entry[].changes[]` array decodes to exactly
  * one `WhatsAppNotification` value. The degenerate-input policy ensures an unknown type never
  * crashes a running webhook: an unrecognized message type decodes to `Content.Unknown`, an
  * unrecognized status to `Status.Other`, and an unrecognized change field or shape to
  * `Unsupported`.
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
sealed trait WhatsAppNotification derives CanEqual:
    def metadata: WhatsAppNotification.Metadata

object WhatsAppNotification:

    final case class Metadata(displayPhoneNumber: String, phoneNumberId: WhatsAppId.PhoneNumberId) derives Schema, CanEqual

    final case class Context(from: WhatsAppId.WaId, id: WhatsAppId.MessageId) derives Schema, CanEqual

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
            kind: WhatsAppMedia.MediaType,
            id: WhatsAppId.MediaId,
            mimeType: String,
            sha256: String,
            caption: Maybe[String] = Absent,
            filename: Maybe[String] = Absent,
            voice: Maybe[Boolean] = Absent
        ) extends Content derives CanEqual
        final case class Location(latitude: Double, longitude: Double, name: Maybe[String] = Absent, address: Maybe[String] = Absent)
            extends Content derives CanEqual
        final case class Contacts(contacts: Chunk[WhatsAppContact])                                extends Content derives CanEqual
        final case class Reaction(messageId: WhatsAppId.MessageId, emoji: String)                  extends Content derives CanEqual
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
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        timestamp: Long,
        content: Content,
        context: Maybe[Context] = Absent,
        senderProfileName: Maybe[String] = Absent
    ) extends WhatsAppNotification derives CanEqual

    final case class StatusUpdate(
        metadata: Metadata,
        id: WhatsAppId.MessageId,
        status: Status,
        timestamp: Long,
        recipientId: WhatsAppId.WaId,
        conversation: Maybe[Conversation] = Absent,
        pricing: Maybe[Pricing] = Absent,
        errors: Chunk[WhatsAppError.Cloud] = Chunk.empty
    ) extends WhatsAppNotification derives CanEqual

    final case class Unsupported(metadata: Metadata, field: String, raw: String) extends WhatsAppNotification derives CanEqual

end WhatsAppNotification
