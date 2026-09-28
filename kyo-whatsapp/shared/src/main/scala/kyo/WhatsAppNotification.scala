package kyo

/** One notification a webhook POST carries, produced by `WhatsAppWebhook.decode` and `WhatsAppWebhook.handler`. Meta takes no answer
  * beyond the 200 the webhook sends, so the handler's callback returns `Unit` for every notification.
  *
  * A POST's `entry[].changes[]` decode to one notification per message and per status, so one change can yield several:
  *   - `InboundMessage` carries the sender, message id, timestamp, decoded `Content`, optional reply context, and the sender's profile
  *     name when Meta sends it;
  *   - `StatusUpdate` carries the message id, delivery status, timestamp, recipient id, optional conversation and pricing, and the
  *     delivery issues of a failed status;
  *   - `Unknown` carries a change the module does not model, by its field name (`type`) and its value as Meta sent it, with its metadata
  *     when it has one. A change of a modelled field that lacks its metadata, and a message or status that does not decode or whose
  *     timestamp does not parse, is `Unknown` too, holding its own JSON, so no notification carries a value standing for a missing one
  *     and the items beside it are still delivered.
  *
  * An unrecognized message type decodes to `Content.Unknown` and an unrecognized status to `Status.Other`, so an unknown type never
  * stops a running webhook.
  *
  * No inbound type has a `Schema`: kyo-whatsapp decodes Meta's payloads through its own wire types.
  */
sealed trait WhatsAppNotification derives CanEqual

object WhatsAppNotification:

    // Bounded rather than on `WhatsAppNotification` alone, so the refusal also answers a summon of one case, such as `InboundMessage`.
    inline given [N <: WhatsAppNotification]: Schema[N] =
        compiletime.error("WhatsAppNotification has no Schema: kyo-whatsapp decodes Meta's payloads itself")

    /** The business phone number a change is addressed to. */
    final case class Metadata(displayPhoneNumber: String, phoneNumberId: WhatsAppId.PhoneNumberId) derives CanEqual

    object Metadata:
        inline given Schema[Metadata] =
            compiletime.error("WhatsAppNotification.Metadata has no Schema: kyo-whatsapp decodes Meta's payloads itself")

    /** The message an inbound message replies to. */
    final case class Context(from: WhatsAppId.WaId, id: WhatsAppId.MessageId) derives CanEqual

    object Context:
        inline given Schema[Context] =
            compiletime.error("WhatsAppNotification.Context has no Schema: kyo-whatsapp decodes Meta's payloads itself")

    /** The conversation a status is billed in, and when it expires. */
    final case class Conversation(id: String, expiration: Maybe[Instant] = Absent, originType: String) derives CanEqual

    object Conversation:
        inline given Schema[Conversation] =
            compiletime.error("WhatsAppNotification.Conversation has no Schema: kyo-whatsapp decodes Meta's payloads itself")

    /** How a status is billed. */
    final case class Pricing(billable: Boolean, pricingModel: String, category: String, kind: Maybe[String] = Absent) derives CanEqual

    object Pricing:
        inline given Schema[Pricing] =
            compiletime.error("WhatsAppNotification.Pricing has no Schema: kyo-whatsapp decodes Meta's payloads itself")

    /** Why a message was not delivered: one entry of a failed status's `errors`. `title` is Meta's short description, `details` its
      * explanation, `href` a link to Meta's error-code documentation. It is data inside the notification, not an exception.
      */
    final case class DeliveryIssue(code: Int, title: Maybe[String] = Absent, details: Maybe[String] = Absent, href: Maybe[String] = Absent)
        derives CanEqual

    object DeliveryIssue:
        inline given Schema[DeliveryIssue] =
            compiletime.error("WhatsAppNotification.DeliveryIssue has no Schema: kyo-whatsapp decodes Meta's payloads itself")

    /** An inbound message's payload, by message type. `Unknown` is a type the module does not enumerate, or one lacking a field its type
      * requires, with the message as Meta sent it.
      */
    sealed trait Content derives CanEqual
    object Content:
        final case class Text(body: String) extends Content derives CanEqual

        /** Media a person sent: `kind` is the message type, `mimeType` the file's MIME type, `id` the media id `WhatsAppMedia` resolves. */
        final case class Media(
            kind: Media.Kind,
            id: WhatsAppId.MediaId,
            mimeType: String,
            sha256: String,
            caption: Maybe[String] = Absent,
            filename: Maybe[String] = Absent,
            voice: Maybe[Boolean] = Absent
        ) extends Content derives CanEqual

        object Media:
            /** The message type a media message arrived as. */
            enum Kind derives CanEqual:
                case Image, Video, Audio, Document, Sticker
        end Media

        final case class Location(latitude: Double, longitude: Double, name: Maybe[String] = Absent, address: Maybe[String] = Absent)
            extends Content derives CanEqual
        final case class Contacts(contacts: Chunk[WhatsAppContact])                                extends Content derives CanEqual
        final case class Reaction(messageId: WhatsAppId.MessageId, emoji: String)                  extends Content derives CanEqual
        final case class Button(payload: String, text: String)                                     extends Content derives CanEqual
        final case class ListReply(id: String, title: String, description: Maybe[String] = Absent) extends Content derives CanEqual
        final case class ButtonReply(id: String, title: String)                                    extends Content derives CanEqual
        final case class Order(catalogId: String, items: Chunk[String])                            extends Content derives CanEqual
        final case class System(body: String)                                                      extends Content derives CanEqual
        final case class Unknown(`type`: String, payload: WhatsAppRawJson)                         extends Content derives CanEqual

        inline given [C <: Content]: Schema[C] =
            compiletime.error("WhatsAppNotification.Content has no Schema: kyo-whatsapp decodes Meta's payloads itself")
    end Content

    /** A status's delivery state; `Other(value)` absorbs a state the module does not enumerate. */
    sealed trait Status derives CanEqual
    object Status:
        case object Sent                      extends Status
        case object Delivered                 extends Status
        case object Read                      extends Status
        case object Failed                    extends Status
        case object Deleted                   extends Status
        final case class Other(value: String) extends Status derives CanEqual

        inline given [S <: Status]: Schema[S] =
            compiletime.error("WhatsAppNotification.Status has no Schema: kyo-whatsapp decodes Meta's payloads itself")
    end Status

    final case class InboundMessage(
        metadata: Metadata,
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        timestamp: Instant,
        content: Content,
        context: Maybe[Context] = Absent,
        senderProfileName: Maybe[String] = Absent
    ) extends WhatsAppNotification derives CanEqual

    final case class StatusUpdate(
        metadata: Metadata,
        id: WhatsAppId.MessageId,
        status: Status,
        timestamp: Instant,
        recipientId: WhatsAppId.WaId,
        conversation: Maybe[Conversation] = Absent,
        pricing: Maybe[Pricing] = Absent,
        errors: Chunk[DeliveryIssue] = Chunk.empty
    ) extends WhatsAppNotification derives CanEqual

    /** A change, message or status the module does not model or cannot decode: `type` is its change's field name and `payload`
      * its JSON as Meta sent it.
      */
    final case class Unknown(`type`: String, payload: WhatsAppRawJson, metadata: Maybe[Metadata] = Absent) extends WhatsAppNotification
        derives CanEqual

end WhatsAppNotification
