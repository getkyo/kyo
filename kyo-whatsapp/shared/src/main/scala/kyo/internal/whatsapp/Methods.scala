package kyo.internal.whatsapp

import kyo.*

/** The request and answer objects of the Cloud API calls the module makes, each named for its call. A call's object is its own JSON,
  * not a second model of a public type: its fields are the call's arguments and results, typed with the public types.
  *
  * A field is named by its wire key rather than renamed, which keeps each record readable as the JSON it is.
  */
private[kyo] object Methods:

    /** The `context` of a send: the message the new one replies to. */
    final case class Context(message_id: WhatsAppId.MessageId) derives CanEqual, Schema

    /** `send`'s body: the call's arguments beside the message, whose `type` and object sit at the top level. */
    final case class Send(
        messaging_product: String,
        recipient_type: String,
        to: WhatsAppId.WaId,
        context: Maybe[Context],
        message: WhatsAppMessage
    ) derives CanEqual

    object Send:
        def apply(to: WhatsAppId.WaId, message: WhatsAppMessage, replyTo: Maybe[WhatsAppId.MessageId]): Send =
            Send("whatsapp", "individual", to, replyTo.map(Context(_)), message)

        given Schema[Send] = Schema[Send].flatten(_.message)
    end Send

    /** `sendTemplate`'s body: the call's arguments beside the template. */
    final case class SendTemplate(
        messaging_product: String,
        recipient_type: String,
        to: WhatsAppId.WaId,
        `type`: String,
        context: Maybe[Context],
        template: WhatsAppTemplate
    ) derives CanEqual, Schema

    object SendTemplate:
        def apply(to: WhatsAppId.WaId, template: WhatsAppTemplate, replyTo: Maybe[WhatsAppId.MessageId]): SendTemplate =
            SendTemplate("whatsapp", "individual", to, "template", replyTo.map(Context(_)), template)

    /** `markRead`'s body: a `read` status for `message_id`, with the typing indicator shown while the business replies. */
    final case class MarkRead(
        messaging_product: String,
        status: String,
        message_id: WhatsAppId.MessageId,
        typing_indicator: Maybe[MarkRead.TypingIndicator] = Absent
    ) derives Schema

    object MarkRead:
        def apply(messageId: WhatsAppId.MessageId, typing: Boolean): MarkRead =
            MarkRead("whatsapp", "read", messageId, if typing then Present(TypingIndicator("text")) else Absent)

        final case class TypingIndicator(`type`: String) derives Schema
    end MarkRead

    /** The answer of `markRead` and `delete`: the Graph API acknowledges both with `{"success": true}`. */
    final case class SuccessAnswer(success: Boolean) derives CanEqual, Schema

    /** `upload`'s answer: the id of the uploaded media. */
    final case class UploadAnswer(id: WhatsAppId.MediaId) derives CanEqual, Schema

    /** The body of a non-2xx answer: Meta's Graph error object, from which the module builds the failure leaf. */
    final case class GraphError(error: GraphError.Detail) derives CanEqual, Schema

    object GraphError:
        final case class Detail(
            message: String,
            `type`: Maybe[String] = Absent,
            code: Int,
            error_subcode: Maybe[Int] = Absent,
            error_data: Maybe[Data] = Absent,
            fbtrace_id: Maybe[String] = Absent,
            error_user_title: Maybe[String] = Absent,
            error_user_msg: Maybe[String] = Absent
        ) derives CanEqual, Schema

        final case class Data(messaging_product: Maybe[String] = Absent, details: Maybe[String] = Absent) derives CanEqual, Schema
    end GraphError

end Methods
