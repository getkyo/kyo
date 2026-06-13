package kyo.whatsapp

import kyo.*

/** Closed ADT of the six interactive message sub-shapes supported by the WhatsApp Cloud API.
  * An `Interactive` value is embedded in a `Message.OfInteractive` and sent via
  * `WhatsApp.send`. Each variant carries the shared optional `body`, `header`, and `footer`
  * fields alongside its own action-specific data.
  *
  * Variants:
  *   - `ListMenu` presents a scrollable list with sections and rows, driven by a button label
  *   - `Buttons` presents up to three quick-reply buttons, each with an id and display title
  *   - `CtaUrl` presents a single call-to-action button that opens a URL
  *   - `Flow` launches a WhatsApp Flow by id or name, with navigate or data_exchange action
  *   - `Product` presents a single catalog product by retailer id
  *   - `ProductList` presents a catalog header with one or more product sections
  *
  * The `Header` nested ADT is shared across variants and supports text, image/video media,
  * or a document attachment. `Flow.Ref` is id-XOR-name so exactly one reference is set,
  * preventing an ambiguous lookup at the API level.
  */
sealed trait Interactive derives CanEqual:
    def body: Maybe[String]
    def header: Maybe[Interactive.Header]
    def footer: Maybe[String]
end Interactive

object Interactive:

    sealed trait Header derives CanEqual
    object Header:
        sealed trait MediaKind derives CanEqual
        object MediaKind:
            case object Image extends MediaKind
            case object Video extends MediaKind
        final case class Text(text: String)                                                            extends Header derives CanEqual
        final case class Media(source: kyo.whatsapp.Media.Source, kind: MediaKind)                     extends Header derives CanEqual
        final case class Document(source: kyo.whatsapp.Media.Source, filename: Maybe[String] = Absent) extends Header derives CanEqual
    end Header

    final case class Row(id: String, title: String, description: Maybe[String] = Absent) derives CanEqual
    final case class Section(title: String, rows: Chunk[Row]) derives CanEqual
    final case class ReplyButton(id: String, title: String) derives CanEqual
    final case class ProductSection(title: String, productRetailerIds: Chunk[String]) derives CanEqual

    final case class ListMenu(
        button: String,
        sections: Chunk[Section],
        body: Maybe[String] = Absent,
        header: Maybe[Header] = Absent,
        footer: Maybe[String] = Absent
    ) extends Interactive derives CanEqual

    final case class Buttons(
        buttons: Chunk[ReplyButton],
        body: Maybe[String] = Absent,
        header: Maybe[Header] = Absent,
        footer: Maybe[String] = Absent
    ) extends Interactive derives CanEqual

    final case class CtaUrl(
        displayText: String,
        url: String,
        body: Maybe[String] = Absent,
        header: Maybe[Header] = Absent,
        footer: Maybe[String] = Absent
    ) extends Interactive derives CanEqual

    object Flow:
        sealed trait Ref derives CanEqual
        object Ref:
            final case class ById(flowId: String)     extends Ref derives CanEqual
            final case class ByName(flowName: String) extends Ref derives CanEqual
        sealed trait Action derives CanEqual
        object Action:
            final case class Navigate(screen: String, data: Maybe[String] = Absent) extends Action derives CanEqual
            case object DataExchange                                                extends Action
        sealed trait Mode derives CanEqual
        object Mode:
            case object Draft     extends Mode
            case object Published extends Mode
    end Flow

    final case class Flow(
        token: String,
        ref: Flow.Ref,
        cta: String,
        action: Flow.Action = Flow.Action.Navigate("", Absent),
        mode: Flow.Mode = Flow.Mode.Published,
        body: Maybe[String] = Absent,
        header: Maybe[Header] = Absent,
        footer: Maybe[String] = Absent
    ) extends Interactive derives CanEqual

    final case class Product(
        catalogId: String,
        productRetailerId: String,
        body: Maybe[String] = Absent,
        footer: Maybe[String] = Absent
    ) extends Interactive derives CanEqual:
        def header: Maybe[Header] = Absent
    end Product

    final case class ProductList(
        catalogId: String,
        headerText: String,
        bodyText: String,
        sections: Chunk[ProductSection],
        footer: Maybe[String] = Absent
    ) extends Interactive derives CanEqual:
        def body: Maybe[String]   = Present(bodyText)
        def header: Maybe[Header] = Present(Header.Text(headerText))
    end ProductList

end Interactive
