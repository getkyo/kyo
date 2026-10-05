package kyo

import kyo.schema.discriminator
import kyo.schema.omit
import kyo.schema.rename
import kyo.schema.tagOnly

/** A template message value for `WhatsApp.sendTemplate`. Templates are the only message class the Cloud API allows outside the 24-hour
  * conversation window, so this type is the primary vehicle for proactive business-initiated conversations.
  *
  * A `WhatsAppTemplate` carries the template name, its language, and an optional sequence of `Component` values that fill the template's
  * variable slots at send time. Its `Schema` is Meta's template object; each component and parameter holds Meta's object for its type,
  * and a companion `apply` builds one from plain values.
  *
  * Component types:
  *   - `Component.Header` fills header-slot parameters (text, currency, date_time, or media)
  *   - `Component.Body` fills body-slot parameters with the same parameter vocabulary
  *   - `Component.Button` fills a button slot, identified by sub-type and zero-based index
  *
  * Parameter types:
  *   - `Parameter.Text` for plain text substitution
  *   - `Parameter.Currency` for a formatted currency amount with fallback
  *   - `Parameter.DateTime` for a formatted date/time with fallback
  *   - `Parameter.Image`, `Parameter.Video`, `Parameter.Document` for media parameters
  *   - `Parameter.Payload` for button payload strings (quick-reply and flow buttons)
  *
  * `ButtonSubType` enumerates the four Cloud API button kinds: `QuickReply`, `Url`, `CopyCode`, and `Flow`.
  */
final case class WhatsAppTemplate(
    name: String,
    language: WhatsAppTemplate.Language,
    @omit(omit.WhenEmpty) components: Chunk[WhatsAppTemplate.Component]
) derives CanEqual, Schema

object WhatsAppTemplate:

    /** The template named `name` in the language whose code is `language`, such as `en_US`. */
    def apply(name: String, language: String, components: Chunk[Component] = Chunk.empty): WhatsAppTemplate =
        WhatsAppTemplate(name, Language(language), components)

    /** Meta's language object: the code of the template's translation. */
    final case class Language(code: String) derives CanEqual, Schema

    @tagOnly()
    sealed trait ButtonSubType derives CanEqual
    object ButtonSubType:
        @rename("quick_reply") case object QuickReply extends ButtonSubType
        @rename("url") case object Url                extends ButtonSubType
        @rename("copy_code") case object CopyCode     extends ButtonSubType
        @rename("flow") case object Flow              extends ButtonSubType
        given Schema[ButtonSubType] = Schema.derived[ButtonSubType]
    end ButtonSubType

    @discriminator("type")
    sealed trait Parameter derives CanEqual
    object Parameter:
        /** A text value; `name` is the template's named parameter it fills (Meta's `parameter_name`), `Absent` for a positional one. */
        @rename("text")
        final case class Text(text: String, @rename("parameter_name") name: Maybe[String] = Absent) extends Parameter derives CanEqual

        @rename("currency")
        final case class Currency(currency: Currency.Body) extends Parameter derives CanEqual:
            def fallback: String = currency.fallback
            def code: String     = currency.code
            def amount1000: Long = currency.amount1000
        end Currency

        object Currency:
            def apply(fallback: String, code: String, amount1000: Long): Currency = Currency(Body(fallback, code, amount1000))

            final case class Body(
                @rename("fallback_value") fallback: String,
                code: String,
                @rename("amount_1000") amount1000: Long
            ) derives CanEqual, Schema
        end Currency

        @rename("date_time")
        final case class DateTime(@rename("date_time") dateTime: DateTime.Body) extends Parameter derives CanEqual:
            def fallback: String = dateTime.fallback

        object DateTime:
            def apply(fallback: String): DateTime = DateTime(Body(fallback))

            final case class Body(@rename("fallback_value") fallback: String) derives CanEqual, Schema
        end DateTime

        @rename("image")
        final case class Image(image: WhatsAppMedia.Link) extends Parameter derives CanEqual:
            def source: WhatsAppMedia.Source = image.source

        object Image:
            def apply(source: WhatsAppMedia.Source): Image = Image(WhatsAppMedia.Link(source))

        @rename("document")
        final case class Document(document: WhatsAppMedia.Document) extends Parameter derives CanEqual:
            def source: WhatsAppMedia.Source = document.source
            def filename: Maybe[String]      = document.filename
        end Document

        object Document:
            def apply(source: WhatsAppMedia.Source, filename: Maybe[String] = Absent): Document =
                Document(WhatsAppMedia.Document(source, Absent, filename))

        @rename("video")
        final case class Video(video: WhatsAppMedia.Link) extends Parameter derives CanEqual:
            def source: WhatsAppMedia.Source = video.source

        object Video:
            def apply(source: WhatsAppMedia.Source): Video = Video(WhatsAppMedia.Link(source))

        @rename("payload")
        final case class Payload(payload: String) extends Parameter derives CanEqual

        given Schema[Parameter] = Schema.derived[Parameter]
    end Parameter

    @discriminator("type")
    sealed trait Component derives CanEqual
    object Component:
        @rename("header")
        final case class Header(parameters: Chunk[Parameter]) extends Component derives CanEqual

        @rename("body")
        final case class Body(parameters: Chunk[Parameter]) extends Component derives CanEqual

        @rename("button")
        final case class Button(@rename("sub_type") subType: ButtonSubType, index: Int, parameters: Chunk[Parameter]) extends Component
            derives CanEqual

        given Schema[Component] = Schema.derived[Component]
    end Component

end WhatsAppTemplate
