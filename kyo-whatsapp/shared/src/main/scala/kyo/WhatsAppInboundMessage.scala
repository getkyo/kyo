package kyo

import kyo.internal.whatsapp.Transformers
import kyo.schema.catchAll
import kyo.schema.discriminator
import kyo.schema.rename
import kyo.schema.transform
import kyo.schema.untagged

/** A message a person sent the business, as Meta's webhook delivers it: one item of a change's `messages`.
  *
  * Its `Schema` is Meta's message object, tagged by `type`. Every typed case carries the sender (`from`), the message id, the time it
  * was sent (`timestamp`, Meta's epoch-seconds string), the message it replies to (`context`), and its content under the key its type
  * names (`text`, `image`, `location`, ...), a record whose `Schema` is Meta's object for it; methods read that record's fields. Those
  * four are on every typed case through [[kyo.WhatsAppInboundMessage.Common]].
  *
  * `Unknown` holds a type the module does not enumerate, with the message as Meta sent it, and writes it back unchanged. A message of a
  * known type that does not decode, such as one missing its content or with a timestamp that is not epoch seconds, is not a case of this
  * type: the change's `messages` holds it as an [[kyo.WhatsAppInboundMessage.Entry.Undecodable]], so the messages beside it still
  * decode.
  */
@discriminator("type")
sealed trait WhatsAppInboundMessage extends WhatsAppInboundMessage.Entry derives CanEqual

object WhatsAppInboundMessage:

    /** One item of a change's `messages`: a message, or the JSON of one that does not decode. */
    @untagged
    sealed trait Entry derives CanEqual
    object Entry:
        @catchAll() final case class Undecodable(raw: WhatsAppRawJson) extends Entry derives CanEqual
        given Schema[Entry] = Schema.derived[Entry]

    /** What every typed message carries beside its content. */
    sealed trait Common:
        def from: WhatsAppId.WaId
        def id: WhatsAppId.MessageId
        def timestamp: Instant
        def context: Maybe[Context]
    end Common

    /** The message an inbound message replies to: Meta's `context` object. */
    final case class Context(from: WhatsAppId.WaId, id: WhatsAppId.MessageId) derives CanEqual, Schema

    @rename("text")
    final case class Text(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        text: Text.Body
    ) extends WhatsAppInboundMessage, Common derives CanEqual:
        def body: String = text.body
    end Text

    object Text:
        final case class Body(body: String) derives CanEqual, Schema

    /** The media object of an image, video, audio, document or sticker message: `id` is the media id `WhatsAppMedia` resolves,
      * `mimeType` the file's MIME type; `voice` marks an audio message recorded as a voice note.
      */
    final case class Media(
        id: WhatsAppId.MediaId,
        @rename("mime_type") mimeType: String,
        sha256: String,
        caption: Maybe[String] = Absent,
        filename: Maybe[String] = Absent,
        voice: Maybe[Boolean] = Absent
    ) derives CanEqual, Schema

    @rename("image")
    final case class Image(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        image: Media
    ) extends WhatsAppInboundMessage, Common derives CanEqual

    @rename("video")
    final case class Video(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        video: Media
    ) extends WhatsAppInboundMessage, Common derives CanEqual

    @rename("audio")
    final case class Audio(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        audio: Media
    ) extends WhatsAppInboundMessage, Common derives CanEqual

    @rename("document")
    final case class Document(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        document: Media
    ) extends WhatsAppInboundMessage, Common derives CanEqual

    @rename("sticker")
    final case class Sticker(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        sticker: Media
    ) extends WhatsAppInboundMessage, Common derives CanEqual

    @rename("location")
    final case class Location(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        location: Location.Body
    ) extends WhatsAppInboundMessage, Common derives CanEqual:
        def latitude: Double       = location.latitude
        def longitude: Double      = location.longitude
        def name: Maybe[String]    = location.name
        def address: Maybe[String] = location.address
    end Location

    object Location:
        final case class Body(latitude: Double, longitude: Double, name: Maybe[String] = Absent, address: Maybe[String] = Absent)
            derives CanEqual, Schema

    @rename("contacts")
    final case class Contacts(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        contacts: Chunk[WhatsAppContact]
    ) extends WhatsAppInboundMessage, Common derives CanEqual

    @rename("reaction")
    final case class Reaction(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        reaction: Reaction.Body
    ) extends WhatsAppInboundMessage, Common derives CanEqual:
        def messageId: WhatsAppId.MessageId = reaction.messageId
        def emoji: String                   = reaction.emoji
    end Reaction

    object Reaction:
        final case class Body(@rename("message_id") messageId: WhatsAppId.MessageId, emoji: String) derives CanEqual, Schema

    /** A tap on a template's quick-reply button. */
    @rename("button")
    final case class Button(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        button: Button.Body
    ) extends WhatsAppInboundMessage, Common derives CanEqual:
        def payload: String = button.payload
        def text: String    = button.text
    end Button

    object Button:
        final case class Body(payload: String, text: String) derives CanEqual, Schema

    /** A reply to an interactive message: a tapped reply button or a chosen list row. */
    @rename("interactive")
    final case class Interactive(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        interactive: Interactive.Reply
    ) extends WhatsAppInboundMessage, Common derives CanEqual

    object Interactive:
        /** Meta's interactive reply object, tagged by `type`; `Other` holds a reply type the module does not enumerate. */
        @discriminator("type")
        sealed trait Reply derives CanEqual
        object Reply:
            @rename("button_reply")
            final case class ButtonReply(@rename("button_reply") reply: ButtonReply.Body) extends Reply derives CanEqual:
                def id: String    = reply.id
                def title: String = reply.title
            end ButtonReply

            object ButtonReply:
                final case class Body(id: String, title: String) derives CanEqual, Schema

            @rename("list_reply")
            final case class ListReply(@rename("list_reply") reply: ListReply.Body) extends Reply derives CanEqual:
                def id: String                 = reply.id
                def title: String              = reply.title
                def description: Maybe[String] = reply.description
            end ListReply

            object ListReply:
                final case class Body(id: String, title: String, description: Maybe[String] = Absent) derives CanEqual, Schema

            @catchAll() final case class Other(`type`: String, payload: WhatsAppRawJson) extends Reply derives CanEqual

            given Schema[Reply] = Schema.derived[Reply]
        end Reply
    end Interactive

    /** An order placed from a catalog message: Meta's `order` object, its `text` the note the person added. */
    @rename("order")
    final case class Order(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        order: Order.Body
    ) extends WhatsAppInboundMessage, Common derives CanEqual:
        def catalogId: String = order.catalogId
    end Order

    object Order:
        final case class Body(
            @rename("catalog_id") catalogId: String,
            text: Maybe[String] = Absent,
            @rename("product_items") productItems: Chunk[Item] = Chunk.empty
        ) derives CanEqual, Schema

        /** One ordered product, by its `product_retailer_id`. */
        final case class Item(@rename("product_retailer_id") productRetailerId: String) derives CanEqual, Schema
    end Order

    /** A system message, such as a customer changing their number: Meta's `system` object. */
    @rename("system")
    final case class System(
        from: WhatsAppId.WaId,
        id: WhatsAppId.MessageId,
        @transform(Transformers.EpochSeconds) timestamp: Instant,
        context: Maybe[Context] = Absent,
        system: System.Body
    ) extends WhatsAppInboundMessage, Common derives CanEqual:
        def body: String = system.body
    end System

    object System:
        final case class Body(body: String) derives CanEqual, Schema

    /** A message of a type the module does not enumerate: its `type` and the message as Meta sent it. */
    @catchAll() final case class Unknown(`type`: String, payload: WhatsAppRawJson) extends WhatsAppInboundMessage derives CanEqual

    given Schema[WhatsAppInboundMessage] = Schema.derived[WhatsAppInboundMessage]

end WhatsAppInboundMessage
