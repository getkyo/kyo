package kyo

import kyo.schema.catchAll
import kyo.schema.discriminator
import kyo.schema.omit
import kyo.schema.rename
import kyo.schema.untagged

/** The body of a webhook POST, as Meta writes it: `object` names the subscription, and each `entry` holds one account's `changes`.
  *
  * `WhatsAppWebhook.decode` reads this and turns it into the [[kyo.WhatsAppNotification]]s a handler receives; a bot that wants the
  * payload as Meta sent it decodes this type itself. The `Schema` reads every item it can and keeps every other as its JSON: a change of a
  * field the module does not model is a [[kyo.WhatsAppWebhookPayload.Change.Other]], a change that does not decode an
  * [[kyo.WhatsAppWebhookPayload.ChangeEntry.Undecodable]], and a message or status that does not decode the `Undecodable` entry of its
  * list. Only a body that is not this envelope fails to decode.
  */
final case class WhatsAppWebhookPayload(`object`: String, entry: Chunk[WhatsAppWebhookPayload.Entry]) derives CanEqual, Schema

object WhatsAppWebhookPayload:

    /** One account's changes: Meta's `entry` object, its `id` the WhatsApp Business Account the changes concern. */
    final case class Entry(id: WhatsAppId.WabaId, changes: Chunk[ChangeEntry]) derives CanEqual, Schema

    /** One item of an entry's `changes`: a change, or the JSON of one that does not decode. */
    @untagged
    sealed trait ChangeEntry derives CanEqual
    object ChangeEntry:
        @catchAll() final case class Undecodable(raw: WhatsAppRawJson) extends ChangeEntry derives CanEqual
        given Schema[ChangeEntry] = Schema.derived[ChangeEntry]

    /** A change, tagged by the webhook `field` it belongs to. `Other` holds a field the module does not model, with the change as Meta
      * sent it.
      */
    @discriminator("field")
    sealed trait Change extends ChangeEntry derives CanEqual
    object Change:
        @rename("messages")
        final case class Messages(value: MessagesValue) extends Change derives CanEqual

        @catchAll() final case class Other(field: String, payload: WhatsAppRawJson) extends Change derives CanEqual

        given Schema[Change] = Schema.derived[Change]
    end Change

    /** The `value` of a `messages` change: the business number it is addressed to, the senders' profiles, its messages and statuses,
      * and the errors Meta reports about the change itself.
      */
    final case class MessagesValue(
        @rename("messaging_product") messagingProduct: Maybe[String] = Absent,
        metadata: WhatsAppNotification.Metadata,
        @omit(omit.WhenEmpty) contacts: Chunk[Contact] = Chunk.empty,
        @omit(omit.WhenEmpty) messages: Chunk[WhatsAppInboundMessage.Entry] = Chunk.empty,
        @omit(omit.WhenEmpty) statuses: Chunk[WhatsAppStatus.Entry] = Chunk.empty,
        @omit(omit.WhenEmpty) errors: Chunk[WhatsAppStatus.DeliveryIssue] = Chunk.empty
    ) derives CanEqual, Schema

    /** A sender's profile: Meta's `contacts` item, its `wa_id` the sender of the messages beside it. */
    final case class Contact(profile: Maybe[Contact.Profile] = Absent, @rename("wa_id") waId: WhatsAppId.WaId) derives CanEqual, Schema

    object Contact:
        /** Meta's `profile` object: the name the sender set. */
        final case class Profile(name: String) derives CanEqual, Schema

end WhatsAppWebhookPayload
