package kyo

/** A template message value for `WhatsApp.sendTemplate`. Templates are the only message
  * class the Cloud API allows outside the 24-hour conversation window, so this type is the
  * primary vehicle for proactive business-initiated conversations.
  *
  * A `WhatsAppTemplate` carries the template name, a BCP-47 language code, and an optional
  * sequence of `Component` values that fill the template's variable slots at send time.
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
  * `ButtonSubType` enumerates the four Cloud API button kinds: `QuickReply`, `Url`,
  * `CopyCode`, and `Flow`.
  */
final case class WhatsAppTemplate(name: String, language: String, components: Chunk[WhatsAppTemplate.Component] = Chunk.empty)
    derives CanEqual

object WhatsAppTemplate:

    sealed trait ButtonSubType derives CanEqual
    object ButtonSubType:
        case object QuickReply extends ButtonSubType
        case object Url        extends ButtonSubType
        case object CopyCode   extends ButtonSubType
        case object Flow       extends ButtonSubType
    end ButtonSubType

    sealed trait Parameter derives CanEqual
    object Parameter:
        final case class Text(text: String)                                                       extends Parameter derives CanEqual
        final case class Currency(fallback: String, code: String, amount1000: Long)               extends Parameter derives CanEqual
        final case class DateTime(fallback: String)                                               extends Parameter derives CanEqual
        final case class Image(source: WhatsAppMedia.Source)                                      extends Parameter derives CanEqual
        final case class Document(source: WhatsAppMedia.Source, filename: Maybe[String] = Absent) extends Parameter derives CanEqual
        final case class Video(source: WhatsAppMedia.Source)                                      extends Parameter derives CanEqual
        final case class Payload(payload: String)                                                 extends Parameter derives CanEqual
    end Parameter

    sealed trait Component derives CanEqual
    object Component:
        final case class Header(parameters: Chunk[Parameter])                                     extends Component derives CanEqual
        final case class Body(parameters: Chunk[Parameter])                                       extends Component derives CanEqual
        final case class Button(subType: ButtonSubType, index: Int, parameters: Chunk[Parameter]) extends Component derives CanEqual
    end Component

end WhatsAppTemplate
