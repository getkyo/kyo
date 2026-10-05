package kyo

import kyo.internal.whatsapp.Transformers
import kyo.schema.discriminator
import kyo.schema.omit
import kyo.schema.rename
import kyo.schema.transform

/** Closed ADT of every non-template outbound message body that `WhatsApp.send` accepts.
  *
  * Its `Schema` is the Cloud API's message: the `type` key names the case, and the case's object sits under the key the type names
  * (`text`, `image`, `location`, ...). Each case holds that object, a record whose `Schema` is Meta's object for it; a companion `apply`
  * builds one from its fields, and methods read them.
  *
  * Variants:
  *   - `Text` carries a body string and an optional URL-preview flag
  *   - `Image`, `Video`, `Document`, `Audio`, `Sticker` carry a `WhatsAppMedia.Source` (id-XOR-link); `Image`, `Video`, `Document` also
  *     accept an optional caption; `Document` adds an optional filename
  *   - `Location` carries numeric latitude and longitude with optional name and address
  *   - `Contacts` wraps one or more `WhatsAppContact` values (the vCard-style contact share)
  *   - `Reaction` targets a prior message by `WhatsAppId.MessageId` with an emoji string
  *   - `OfInteractive` embeds the `WhatsAppInteractive` ADT for list, button, cta_url, flow, product, and product_list messages
  *
  * Template messages are a separate flow and use `WhatsApp.sendTemplate` with `WhatsAppTemplate` rather than this type.
  */
@discriminator("type")
sealed trait WhatsAppMessage derives CanEqual

object WhatsAppMessage:

    /** A text message: the Cloud API's `text` object, of which `body` and `previewUrl` read the text and the URL-preview flag. */
    @rename("text")
    final case class Text(text: Text.Body) extends WhatsAppMessage derives CanEqual:
        def body: String        = text.body
        def previewUrl: Boolean = text.previewUrl
    end Text

    object Text:
        def apply(body: String, previewUrl: Boolean = false): Text = Text(Body(body, previewUrl))

        final case class Body(body: String, @rename("preview_url") @omit(omit.WhenDefault) previewUrl: Boolean = false)
            derives CanEqual, Schema
    end Text

    @rename("image")
    final case class Image(image: WhatsAppMedia.Captioned) extends WhatsAppMessage derives CanEqual:
        def source: WhatsAppMedia.Source = image.source
        def caption: Maybe[String]       = image.caption
    end Image

    object Image:
        def apply(source: WhatsAppMedia.Source, caption: Maybe[String] = Absent): Image = Image(WhatsAppMedia.Captioned(source, caption))

    @rename("video")
    final case class Video(video: WhatsAppMedia.Captioned) extends WhatsAppMessage derives CanEqual:
        def source: WhatsAppMedia.Source = video.source
        def caption: Maybe[String]       = video.caption
    end Video

    object Video:
        def apply(source: WhatsAppMedia.Source, caption: Maybe[String] = Absent): Video = Video(WhatsAppMedia.Captioned(source, caption))

    @rename("document")
    final case class Document(document: WhatsAppMedia.Document) extends WhatsAppMessage derives CanEqual:
        def source: WhatsAppMedia.Source = document.source
        def caption: Maybe[String]       = document.caption
        def filename: Maybe[String]      = document.filename
    end Document

    object Document:
        def apply(source: WhatsAppMedia.Source, caption: Maybe[String] = Absent, filename: Maybe[String] = Absent): Document =
            Document(WhatsAppMedia.Document(source, caption, filename))

    @rename("audio")
    final case class Audio(audio: WhatsAppMedia.Audio) extends WhatsAppMessage derives CanEqual:
        def source: WhatsAppMedia.Source = audio.source
        def voice: Boolean               = audio.voice
    end Audio

    object Audio:
        def apply(source: WhatsAppMedia.Source, voice: Boolean = false): Audio = Audio(WhatsAppMedia.Audio(source, voice))

    @rename("sticker")
    final case class Sticker(sticker: WhatsAppMedia.Link) extends WhatsAppMessage derives CanEqual:
        def source: WhatsAppMedia.Source = sticker.source

    object Sticker:
        def apply(source: WhatsAppMedia.Source): Sticker = Sticker(WhatsAppMedia.Link(source))

    /** A location message: the Cloud API's `location` object. */
    @rename("location")
    final case class Location(location: Location.Body) extends WhatsAppMessage derives CanEqual:
        def latitude: Double       = location.latitude
        def longitude: Double      = location.longitude
        def name: Maybe[String]    = location.name
        def address: Maybe[String] = location.address
    end Location

    object Location:
        def apply(latitude: Double, longitude: Double, name: Maybe[String] = Absent, address: Maybe[String] = Absent): Location =
            Location(Body(latitude, longitude, name, address))

        /** Meta's location object. Meta's own send example writes the coordinates as strings, so a coordinate reads as a JSON number
          * or a numeric string, and is written as a number.
          */
        final case class Body(
            @transform(Transformers.Coordinate) latitude: Double,
            @transform(Transformers.Coordinate) longitude: Double,
            name: Maybe[String] = Absent,
            address: Maybe[String] = Absent
        ) derives CanEqual, Schema
    end Location

    @rename("contacts")
    final case class Contacts(contacts: Chunk[WhatsAppContact]) extends WhatsAppMessage derives CanEqual

    /** A reaction to a prior message: the Cloud API's `reaction` object. */
    @rename("reaction")
    final case class Reaction(reaction: Reaction.Body) extends WhatsAppMessage derives CanEqual:
        def messageId: WhatsAppId.MessageId = reaction.messageId
        def emoji: String                   = reaction.emoji
    end Reaction

    object Reaction:
        def apply(messageId: WhatsAppId.MessageId, emoji: String): Reaction = Reaction(Body(messageId, emoji))

        final case class Body(@rename("message_id") messageId: WhatsAppId.MessageId, emoji: String) derives CanEqual, Schema
    end Reaction

    @rename("interactive")
    final case class OfInteractive(interactive: WhatsAppInteractive) extends WhatsAppMessage derives CanEqual

    given Schema[WhatsAppMessage] = Schema.derived[WhatsAppMessage]
end WhatsAppMessage
