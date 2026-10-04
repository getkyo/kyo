package kyo.internal.whatsapp

import kyo.*

/** The module's JSON at its call sites. Requests are the `Methods` records encoded through their `Schema`; answers decode into the
  * public types, whose `Schema` is Meta's JSON. A body kyo-schema rejects is a `WhatsAppDecodeException` naming kyo-schema's leaf, path
  * and position without its text, and an acknowledgement of `{"success": false}` is one too, `ConstructorRejected` at `success`.
  * Functions that build a `WhatsAppException` take `(using Frame)` because every error leaf carries a `Frame`.
  */
private[kyo] object Codec:

    def encodeSend(to: WhatsAppId.WaId, msg: WhatsAppMessage, replyTo: Maybe[WhatsAppId.MessageId])(using Frame): Span[Byte] =
        Json.encodeBytes(Methods.Send(to, msg, replyTo))

    def encodeTemplate(to: WhatsAppId.WaId, t: WhatsAppTemplate, replyTo: Maybe[WhatsAppId.MessageId])(using Frame): Span[Byte] =
        Json.encodeBytes(Methods.SendTemplate(to, t, replyTo))

    def encodeMarkRead(messageId: WhatsAppId.MessageId, typing: Boolean)(using Frame): Span[Byte] =
        Json.encodeBytes(Methods.MarkRead(messageId, typing))

    /** The send's answer. An answer with no message is refused by `WhatsAppSendResult`'s constructor. */
    def decodeSendResult(method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, WhatsAppSendResult] =
        decodeJson[WhatsAppSendResult](method, body)

    /** An acknowledgement. `{"success": false}` is not one, and is a decode failure at `success`. */
    def decodeSuccess(method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, Unit] =
        decodeJson[Methods.SuccessAnswer](method, body).flatMap { r =>
            if r.success then Result.unit
            else
                Result.fail(WhatsAppDecodeException(
                    method,
                    WhatsAppDecodeException.Part.Response,
                    WhatsAppDecodeException.Failure.ConstructorRejected,
                    Chunk("success"),
                    Absent
                ))
        }

    def decodeMediaId(method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, WhatsAppId.MediaId] =
        decodeJson[Methods.UploadAnswer](method, body).map(_.id)

    def decodeMediaInfo(method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, WhatsAppMedia.MediaInfo] =
        decodeJson[WhatsAppMedia.MediaInfo](method, body)

    def decodeCustom[A: Schema](method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, A] =
        decodeJson[A](method, body)

    private def decodeJson[A: Schema](method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, A] =
        Json.decodeBytes[A](body).mapFailure(WhatsAppDecodeException.of(method, WhatsAppDecodeException.Part.Response, _))

    /** The method name a webhook body's decode failure carries. */
    inline val WebhookMethod = "webhook"

    /** A body that is not the webhook envelope is the decode failure; anything inside the envelope that does not decode is delivered
      * as `Unknown`.
      */
    def decodeNotifications(body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, Chunk[WhatsAppNotification]] =
        Json.decodeBytes[WhatsAppWebhookPayload](body)
            .mapFailure(WhatsAppDecodeException.of(WebhookMethod, WhatsAppDecodeException.Part.Notification, _))
            .map(_.entry.flatMap(_.changes).flatMap(notificationsOf))

    /** A change's notifications, one per message and per status, or one `Unknown` for a change the module does not model or read. */
    private def notificationsOf(entry: WhatsAppWebhookPayload.ChangeEntry)(using Frame): Chunk[WhatsAppNotification] =
        import WhatsAppWebhookPayload.*
        entry match
            case Change.Messages(value) if value.messages.isEmpty && value.statuses.isEmpty =>
                Chunk(WhatsAppNotification.Unknown(MessagesField, WhatsAppRawJson(Structure.encode(value)), Present(value.metadata)))
            case Change.Messages(value) =>
                value.messages.map(messageOf(value, _)) ++ value.statuses.map(statusOf(value, _))
            case Change.Other(field, payload) =>
                Chunk(WhatsAppNotification.Unknown(field, valueOf(payload), metadataOf(payload)))
            case ChangeEntry.Undecodable(raw) =>
                val field = keyOf(raw.json, "field") match
                    case Present(Structure.Value.Str(name)) => name
                    case _                                  => ""
                Chunk(WhatsAppNotification.Unknown(field, valueOf(raw), metadataOf(raw)))
        end match
    end notificationsOf

    private inline val MessagesField = "messages"

    private def messageOf(value: WhatsAppWebhookPayload.MessagesValue, entry: WhatsAppInboundMessage.Entry)(using
        Frame
    ): WhatsAppNotification =
        entry match
            case message: WhatsAppInboundMessage =>
                val sender = message match
                    case common: WhatsAppInboundMessage.Common      => Present(common.from.value)
                    case WhatsAppInboundMessage.Unknown(_, payload) =>
                        keyOf(payload.json, "from") match
                            case Present(Structure.Value.Str(from)) => Present(from)
                            case _                                  => Absent
                val contact = sender.flatMap(from => Maybe.fromOption(value.contacts.find(_.waId.value == from)))
                WhatsAppNotification.Message(value.metadata, contact, message)
            case WhatsAppInboundMessage.Entry.Undecodable(raw) =>
                WhatsAppNotification.Unknown(MessagesField, raw, Present(value.metadata))

    private def statusOf(value: WhatsAppWebhookPayload.MessagesValue, entry: WhatsAppStatus.Entry): WhatsAppNotification =
        entry match
            case status: WhatsAppStatus                => WhatsAppNotification.Status(value.metadata, status)
            case WhatsAppStatus.Entry.Undecodable(raw) => WhatsAppNotification.Unknown(MessagesField, raw, Present(value.metadata))

    /** The `value` of a change's JSON, or the change itself when it has none. */
    private def valueOf(change: WhatsAppRawJson)(using Frame): WhatsAppRawJson =
        keyOf(change.json, "value") match
            case Present(value) => WhatsAppRawJson(value)
            case Absent         => change

    /** The `metadata` of a change's value, when it has one that decodes. */
    private def metadataOf(change: WhatsAppRawJson)(using Frame): Maybe[WhatsAppNotification.Metadata] =
        keyOf(change.json, "value").flatMap(keyOf(_, "metadata")).flatMap { metadata =>
            Structure.decode[WhatsAppNotification.Metadata](metadata) match
                case Result.Success(decoded) => Present(decoded)
                case _                       => Absent
        }

    private def keyOf(json: Structure.Value, key: String): Maybe[Structure.Value] =
        json match
            case Structure.Value.Record(fields) => Maybe.fromOption(fields.collectFirst { case (`key`, value) => value })
            case _                              => Absent

end Codec
