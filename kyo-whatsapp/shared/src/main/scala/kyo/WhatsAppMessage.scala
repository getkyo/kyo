package kyo

/** Closed ADT of every non-template outbound message body that `WhatsApp.send` accepts.
  * One value selects the Cloud API `type` field and the corresponding sibling body object
  * in the JSON envelope; the internal codec handles the wire encoding.
  *
  * Variants:
  *   - `Text` carries a body string and an optional URL-preview flag
  *   - `Image`, `Video`, `Document`, `Audio`, `Sticker` carry a `WhatsAppMedia.Source`
  *     (id-XOR-link); `Image`, `Video`, `Document` also accept an optional caption;
  *     `Document` adds an optional filename
  *   - `Location` carries numeric latitude and longitude with optional name and address
  *   - `Contacts` wraps one or more `WhatsAppContact` values (the vCard-style contact share)
  *   - `Reaction` targets a prior message by `WhatsAppId.MessageId` with an emoji string
  *   - `OfInteractive` embeds the `WhatsAppInteractive` ADT for list, button, cta_url, flow,
  *     product, and product_list messages
  *
  * Template messages are a separate flow and use `WhatsApp.sendTemplate` with
  * `WhatsAppTemplate` rather than this type.
  */
sealed trait WhatsAppMessage derives CanEqual

object WhatsAppMessage:
    final case class Text(body: String, previewUrl: Boolean = false)                      extends WhatsAppMessage derives CanEqual
    final case class Image(source: WhatsAppMedia.Source, caption: Maybe[String] = Absent) extends WhatsAppMessage derives CanEqual
    final case class Video(source: WhatsAppMedia.Source, caption: Maybe[String] = Absent) extends WhatsAppMessage derives CanEqual
    final case class Document(source: WhatsAppMedia.Source, caption: Maybe[String] = Absent, filename: Maybe[String] = Absent)
        extends WhatsAppMessage derives CanEqual
    final case class Audio(source: WhatsAppMedia.Source)   extends WhatsAppMessage derives CanEqual
    final case class Sticker(source: WhatsAppMedia.Source) extends WhatsAppMessage derives CanEqual
    final case class Location(latitude: Double, longitude: Double, name: Maybe[String] = Absent, address: Maybe[String] = Absent)
        extends WhatsAppMessage derives CanEqual
    final case class Contacts(contacts: Chunk[WhatsAppContact])               extends WhatsAppMessage derives CanEqual
    final case class Reaction(messageId: WhatsAppId.MessageId, emoji: String) extends WhatsAppMessage derives CanEqual
    final case class OfInteractive(interactive: WhatsAppInteractive)          extends WhatsAppMessage derives CanEqual
end WhatsAppMessage
