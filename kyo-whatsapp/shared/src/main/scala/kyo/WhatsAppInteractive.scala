package kyo

import kyo.internal.whatsapp.Transformers
import kyo.schema.discriminator
import kyo.schema.omit
import kyo.schema.rename
import kyo.schema.tagOnly
import kyo.schema.transform
import kyo.schema.untagged

/** Closed ADT of the six interactive message sub-shapes supported by the WhatsApp Cloud API. A `WhatsAppInteractive` value is embedded
  * in a `WhatsAppMessage.OfInteractive` and sent via `WhatsApp.send`.
  *
  * Its `Schema` is Meta's interactive object: `type` names the case, beside its `header`, `body`, `footer` and `action` objects. Each
  * case holds those objects as Meta writes them, and a companion `apply` builds one from plain values.
  *
  * Variants:
  *   - `ListMenu` presents a scrollable list with sections and rows, driven by a button label
  *   - `Buttons` presents up to three quick-reply buttons, each with an id and display title
  *   - `CtaUrl` presents a single call-to-action button that opens a URL
  *   - `Flow` launches a WhatsApp Flow by id or name, navigating to a screen or letting the Flow's endpoint choose it
  *   - `Product` presents a single catalog product by retailer id
  *   - `ProductList` presents a catalog header with one or more product sections
  *
  * `Flow.Ref` is id-XOR-name so exactly one reference is set, preventing an ambiguous lookup at the API level.
  */
@discriminator("type")
sealed trait WhatsAppInteractive derives CanEqual

object WhatsAppInteractive:

    /** Meta's `{"text": ...}` object of an interactive message's body and footer. */
    final case class TextObject(text: String) derives CanEqual, Schema

    /** An interactive message's header: text, or an image, video or document. */
    @discriminator("type")
    sealed trait Header derives CanEqual
    object Header:
        @rename("text")
        final case class Text(text: String) extends Header derives CanEqual

        @rename("image")
        final case class Image(image: WhatsAppMedia.Link) extends Header derives CanEqual
        object Image:
            def apply(source: WhatsAppMedia.Source): Image = Image(WhatsAppMedia.Link(source))

        @rename("video")
        final case class Video(video: WhatsAppMedia.Link) extends Header derives CanEqual
        object Video:
            def apply(source: WhatsAppMedia.Source): Video = Video(WhatsAppMedia.Link(source))

        @rename("document")
        final case class Document(document: WhatsAppMedia.Document) extends Header derives CanEqual
        object Document:
            def apply(source: WhatsAppMedia.Source, filename: Maybe[String] = Absent): Document =
                Document(WhatsAppMedia.Document(source, Absent, filename))

        given Schema[Header] = Schema.derived[Header]
    end Header

    final case class Row(id: String, title: String, description: Maybe[String] = Absent) derives CanEqual, Schema
    final case class Section(title: String, rows: Chunk[Row]) derives CanEqual, Schema

    @rename("list")
    final case class ListMenu(header: Maybe[Header], body: Maybe[TextObject], footer: Maybe[TextObject], action: ListMenu.Action)
        extends WhatsAppInteractive derives CanEqual

    object ListMenu:
        final case class Action(button: String, sections: Chunk[Section]) derives CanEqual, Schema

        def apply(
            button: String,
            sections: Chunk[Section],
            body: Maybe[String] = Absent,
            header: Maybe[Header] = Absent,
            footer: Maybe[String] = Absent
        ): ListMenu =
            ListMenu(header, body.map(TextObject(_)), footer.map(TextObject(_)), Action(button, sections))
    end ListMenu

    /** A quick-reply button: Meta's `{"type": "reply", "reply": {"id", "title"}}`. */
    @discriminator("type")
    sealed trait ReplyButton derives CanEqual
    object ReplyButton:
        @rename("reply")
        final case class Reply(reply: Reply.Body) extends ReplyButton derives CanEqual:
            def id: String    = reply.id
            def title: String = reply.title
        end Reply

        object Reply:
            final case class Body(id: String, title: String) derives CanEqual, Schema

        def apply(id: String, title: String): ReplyButton = Reply(Reply.Body(id, title))

        given Schema[ReplyButton] = Schema.derived[ReplyButton]
    end ReplyButton

    @rename("button")
    final case class Buttons(header: Maybe[Header], body: Maybe[TextObject], footer: Maybe[TextObject], action: Buttons.Action)
        extends WhatsAppInteractive derives CanEqual

    object Buttons:
        final case class Action(buttons: Chunk[ReplyButton]) derives CanEqual, Schema

        def apply(
            buttons: Chunk[ReplyButton],
            body: Maybe[String] = Absent,
            header: Maybe[Header] = Absent,
            footer: Maybe[String] = Absent
        ): Buttons =
            Buttons(header, body.map(TextObject(_)), footer.map(TextObject(_)), Action(buttons))
    end Buttons

    @rename("cta_url")
    final case class CtaUrl(header: Maybe[Header], body: Maybe[TextObject], footer: Maybe[TextObject], action: CtaUrl.Action)
        extends WhatsAppInteractive derives CanEqual

    object CtaUrl:
        /** Meta's action object of a cta_url message; `name` is always `cta_url`. */
        final case class Action(name: String, parameters: Parameters) derives CanEqual, Schema

        final case class Parameters(@rename("display_text") displayText: String, @transform(Transformers.Url) url: HttpUrl)
            derives CanEqual, Schema

        def apply(
            displayText: String,
            url: HttpUrl,
            body: Maybe[String] = Absent,
            header: Maybe[Header] = Absent,
            footer: Maybe[String] = Absent
        ): CtaUrl =
            CtaUrl(header, body.map(TextObject(_)), footer.map(TextObject(_)), Action("cta_url", Parameters(displayText, url)))
    end CtaUrl

    /** A Flow launch: Meta's action object, whose `parameters` say which Flow, its token and call-to-action, and how it starts. */
    @rename("flow")
    final case class Flow(header: Maybe[Header], body: Maybe[TextObject], footer: Maybe[TextObject], action: Flow.Action)
        extends WhatsAppInteractive derives CanEqual

    object Flow:

        /** The Flow, by its id or its name. On the wire it is the `flow_id` or the `flow_name` key of the parameters. */
        @untagged
        sealed trait Ref derives CanEqual
        object Ref:
            final case class ById(@rename("flow_id") flowId: String)       extends Ref derives CanEqual
            final case class ByName(@rename("flow_name") flowName: String) extends Ref derives CanEqual
            given Schema[Ref] = Schema.derived[Ref]
        end Ref

        @tagOnly()
        sealed trait Mode derives CanEqual
        object Mode:
            @rename("draft") case object Draft         extends Mode
            @rename("published") case object Published extends Mode
            given Schema[Mode] = Schema.derived[Mode]
        end Mode

        /** How the Flow starts: `Navigate` names the first screen, `DataExchange` lets the Flow's endpoint choose it. */
        sealed trait Start derives CanEqual
        object Start:
            final case class Navigate(screen: String, data: Maybe[Structure.Value] = Absent) extends Start derives CanEqual
            case object DataExchange                                                         extends Start
        end Start

        /** Meta's action object of a flow message; `name` is always `flow`. */
        final case class Action(name: String, parameters: Parameters) derives CanEqual, Schema

        /** Meta's parameters object, tagged by `flow_action`, with the Flow's reference among its keys. */
        @discriminator("flow_action")
        sealed trait Parameters derives CanEqual:
            def version: String
            def token: String
            def ref: Ref
            def cta: String
            def mode: Mode
        end Parameters

        object Parameters:
            @rename("navigate")
            final case class Navigate(
                @rename("flow_message_version") version: String,
                @rename("flow_token") token: String,
                ref: Ref,
                @rename("flow_cta") cta: String,
                @omit(omit.WhenDefault) mode: Mode = Mode.Published,
                @rename("flow_action_payload") payload: Navigate.Payload
            ) extends Parameters derives CanEqual

            object Navigate:
                final case class Payload(screen: String, data: Maybe[Structure.Value] = Absent) derives CanEqual, Schema
                given Schema[Navigate] = Schema[Navigate].flatten(_.ref)
            end Navigate

            @rename("data_exchange")
            final case class DataExchange(
                @rename("flow_message_version") version: String,
                @rename("flow_token") token: String,
                ref: Ref,
                @rename("flow_cta") cta: String,
                @omit(omit.WhenDefault) mode: Mode = Mode.Published
            ) extends Parameters derives CanEqual

            object DataExchange:
                given Schema[DataExchange] = Schema[DataExchange].flatten(_.ref)

            given Schema[Parameters] = Schema.derived[Parameters]
        end Parameters

        /** The Flows message version this module writes. */
        inline val MessageVersion = "3"

        def apply(
            token: String,
            ref: Ref,
            cta: String,
            start: Start,
            mode: Mode = Mode.Published,
            body: Maybe[String] = Absent,
            header: Maybe[Header] = Absent,
            footer: Maybe[String] = Absent
        ): Flow =
            val parameters = start match
                case Start.Navigate(screen, data) =>
                    Parameters.Navigate(MessageVersion, token, ref, cta, mode, Parameters.Navigate.Payload(screen, data))
                case Start.DataExchange => Parameters.DataExchange(MessageVersion, token, ref, cta, mode)
            Flow(header, body.map(TextObject(_)), footer.map(TextObject(_)), Action("flow", parameters))
        end apply
    end Flow

    @rename("product")
    final case class Product(body: Maybe[TextObject], footer: Maybe[TextObject], action: Product.Action) extends WhatsAppInteractive
        derives CanEqual

    object Product:
        final case class Action(@rename("catalog_id") catalogId: String, @rename("product_retailer_id") productRetailerId: String)
            derives CanEqual, Schema

        def apply(catalogId: String, productRetailerId: String, body: Maybe[String] = Absent, footer: Maybe[String] = Absent): Product =
            Product(body.map(TextObject(_)), footer.map(TextObject(_)), Action(catalogId, productRetailerId))
    end Product

    final case class ProductItem(@rename("product_retailer_id") productRetailerId: String) derives CanEqual, Schema

    final case class ProductSection(title: String, @rename("product_items") productItems: Chunk[ProductItem]) derives CanEqual, Schema

    object ProductSection:
        /** A section of the products whose retailer ids are `productRetailerIds`. */
        def of(title: String, productRetailerIds: Chunk[String]): ProductSection =
            ProductSection(title, productRetailerIds.map(ProductItem(_)))
    end ProductSection

    @rename("product_list")
    final case class ProductList(header: Header, body: TextObject, footer: Maybe[TextObject], action: ProductList.Action)
        extends WhatsAppInteractive derives CanEqual

    object ProductList:
        final case class Action(@rename("catalog_id") catalogId: String, sections: Chunk[ProductSection]) derives CanEqual, Schema

        def apply(
            catalogId: String,
            headerText: String,
            bodyText: String,
            sections: Chunk[ProductSection],
            footer: Maybe[String] = Absent
        ): ProductList =
            ProductList(Header.Text(headerText), TextObject(bodyText), footer.map(TextObject(_)), Action(catalogId, sections))
    end ProductList

    given Schema[WhatsAppInteractive] = Schema.derived[WhatsAppInteractive]

end WhatsAppInteractive
