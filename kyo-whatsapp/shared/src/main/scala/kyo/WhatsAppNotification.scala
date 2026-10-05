package kyo

import kyo.schema.rename

/** One notification a webhook POST carries, produced by `WhatsApp.Webhook.decode` and `WhatsApp.Webhook.handler`. Meta takes no answer
  * beyond the 200 the webhook sends, so the handler's callback returns `Unit` for every notification.
  *
  * A POST's `entry[].changes[]` decode to one notification per message and per status, so one change can yield several:
  *   - `Message` carries the [[kyo.WhatsAppInboundMessage]] as Meta sent it, the business number it was sent to, and the sender's
  *     profile when Meta sends one;
  *   - `Status` carries the [[kyo.WhatsAppStatus]] as Meta sent it and the business number it concerns;
  *   - `Unknown` carries what the module does not model or cannot decode, by its change's field name (`type`) and its JSON as Meta sent
  *     it, with the metadata when it has one: a change of a field the module does not model (its `value`), a `messages` change with
  *     neither messages nor statuses (its `value`), a change that does not decode (its `value`), and a message or status that does not
  *     decode (the item). No notification carries a value standing for a missing one, and the items beside an `Unknown` are still
  *     delivered.
  *
  * A message of a type the module does not enumerate is a `Message` holding [[kyo.WhatsAppInboundMessage.Unknown]], and a status the
  * module does not enumerate a `Status` whose kind is `Other`, so an unknown type never stops a running webhook.
  *
  * A notification is the module's composition of Meta's objects, not one of them, so it has no `Schema`; the payload it is read from is
  * [[kyo.WhatsAppWebhookPayload]].
  */
sealed trait WhatsAppNotification derives CanEqual

object WhatsAppNotification:

    // Bounded rather than on `WhatsAppNotification` alone, so the refusal also answers a summon of one case, such as `Message`.
    inline given [N <: WhatsAppNotification]: Schema[N] =
        compiletime.error("WhatsAppNotification has no Schema: it composes Meta's objects; WhatsAppWebhookPayload is the payload's")

    /** The business phone number a change is addressed to: Meta's `metadata` object. */
    final case class Metadata(
        @rename("display_phone_number") displayPhoneNumber: String,
        @rename("phone_number_id") phoneNumberId: WhatsAppId.PhoneNumberId
    ) derives CanEqual, Schema

    /** A message a person sent: the business number it was sent to, the sender's profile from the change's `contacts`, and the message. */
    final case class Message(
        metadata: Metadata,
        contact: Maybe[WhatsAppWebhookPayload.Contact],
        message: WhatsAppInboundMessage
    ) extends WhatsAppNotification derives CanEqual

    /** A delivery status of a message the business sent, and the business number it concerns. */
    final case class Status(metadata: Metadata, status: WhatsAppStatus) extends WhatsAppNotification derives CanEqual

    /** A change, message or status the module does not model or cannot decode: `type` is its change's field name and `payload` its
      * JSON as Meta sent it.
      */
    final case class Unknown(`type`: String, payload: WhatsAppRawJson, metadata: Maybe[Metadata] = Absent) extends WhatsAppNotification
        derives CanEqual

end WhatsAppNotification
