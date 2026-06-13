package kyo.whatsapp

import kyo.*

/** Closed ADT of every non-template outbound message body that `WhatsApp.send` accepts.
  * One value selects the Cloud API `type` field and the corresponding sibling body object
  * in the JSON envelope; the internal codec handles the wire encoding.
  *
  * Variants:
  *   - `Text` carries a body string and an optional URL-preview flag
  *   - `Image`, `Video`, `Document`, `Audio`, `Sticker` carry a `Media.Source` (id-XOR-link);
  *     `Image`, `Video`, `Document` also accept an optional caption; `Document` adds an
  *     optional filename
  *   - `Location` carries numeric latitude and longitude with optional name and address
  *   - `Contacts` wraps one or more `Contact` values (the vCard-style contact share)
  *   - `Reaction` targets a prior message by `Id.MessageId` with an emoji string
  *   - `OfInteractive` embeds the `Interactive` ADT for list, button, cta_url, flow,
  *     product, and product_list messages
  *
  * Template messages are a separate flow and use `WhatsApp.sendTemplate` with `Template`
  * rather than this type.
  */
sealed trait Message derives CanEqual

object Message:
    final case class Text(body: String, previewUrl: Boolean = false)              extends Message derives CanEqual
    final case class Image(source: Media.Source, caption: Maybe[String] = Absent) extends Message derives CanEqual
    final case class Video(source: Media.Source, caption: Maybe[String] = Absent) extends Message derives CanEqual
    final case class Document(source: Media.Source, caption: Maybe[String] = Absent, filename: Maybe[String] = Absent)
        extends Message derives CanEqual
    final case class Audio(source: Media.Source)   extends Message derives CanEqual
    final case class Sticker(source: Media.Source) extends Message derives CanEqual
    final case class Location(latitude: Double, longitude: Double, name: Maybe[String] = Absent, address: Maybe[String] = Absent)
        extends Message derives CanEqual
    final case class Contacts(contacts: Chunk[Contact])               extends Message derives CanEqual
    final case class Reaction(messageId: Id.MessageId, emoji: String) extends Message derives CanEqual
    final case class OfInteractive(interactive: Interactive)          extends Message derives CanEqual
end Message
