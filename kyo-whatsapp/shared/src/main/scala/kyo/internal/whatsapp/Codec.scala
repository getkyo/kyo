package kyo.internal.whatsapp

import java.io.IOException
import kyo.*

/** Total pure mapping functions between the public ADT and the wire DTO layer. `encodeSend`
  * and `encodeTemplate` build a `Wire.SendEnvelope` with exactly the one populated body
  * field its message type selects, then serialize it to bytes (Absent fields are omitted,
  * yielding the nested `type`-keyed sibling shape). `decodeSendResult` / `decodeMediaInfo`
  * parse a response DTO into the public value. `mapError` is total over the documented Cloud API
  * error-code table, with `Cloud(...)` preserving the full envelope for any unlisted code and
  * `Transport` wrapping a non-Graph HttpException. Functions that build a `WhatsAppError` take
  * `(using Frame)` because every error leaf carries a `Frame`; encode/decode helpers take it for
  * their `Json`/error needs. No effect row: these are pure functions.
  */
private[kyo] object Codec:

    def encodeSend(to: WhatsAppId.WaId, msg: WhatsAppMessage, replyTo: Maybe[WhatsAppId.MessageId])(using Frame): Span[Byte] =
        val ctx = replyTo.map(id => Wire.ContextBody(id.value))
        val base = Wire.SendEnvelope(
            messaging_product = "whatsapp",
            recipient_type = Present("individual"),
            to = to.value,
            `type` = typeName(msg),
            context = ctx
        )
        Json.encodeBytes(fill(base, msg))
    end encodeSend

    def encodeTemplate(to: WhatsAppId.WaId, t: WhatsAppTemplate, replyTo: Maybe[WhatsAppId.MessageId])(using Frame): Span[Byte] =
        val ctx = replyTo.map(id => Wire.ContextBody(id.value))
        val env = Wire.SendEnvelope(
            messaging_product = "whatsapp",
            recipient_type = Present("individual"),
            to = to.value,
            `type` = "template",
            template = Present(templateBody(t)),
            context = ctx
        )
        Json.encodeBytes(env)
    end encodeTemplate

    def encodeMarkRead(messageId: WhatsAppId.MessageId, typing: Boolean)(using Frame): Span[Byte] =
        val env = Wire.StatusReadEnvelope(
            messaging_product = "whatsapp",
            status = "read",
            message_id = messageId.value,
            typing_indicator = if typing then Present(Wire.TypingDto("text")) else Absent
        )
        Json.encodeBytes(env)
    end encodeMarkRead

    def decodeSendResult(body: Span[Byte])(using Frame): Result[WhatsAppError.DecodeError, WhatsAppSendResult] =
        Json.decodeBytes[Wire.SendResponse](body) match
            case Result.Success(r) =>
                r.messages.headMaybe match
                    case Present(m) =>
                        Result.succeed(WhatsAppSendResult(
                            messageId = WhatsAppId.MessageId(m.id),
                            contactWaId = r.contacts.flatMap(_.headMaybe).flatMap(_.wa_id).map(WhatsAppId.WaId(_)),
                            status = m.message_status.map(sendStatus)
                        ))
                    case Absent => Result.fail(WhatsAppError.DecodeError("send response has no messages[]"))
            case Result.Failure(e)   => Result.fail(WhatsAppError.DecodeError(e.getMessage))
            case panic: Result.Panic => panic

    def decodeSuccess(body: Span[Byte])(using Frame): Result[WhatsAppError.DecodeError, Unit] =
        Json.decodeBytes[Wire.SuccessResponse](body) match
            case Result.Success(r) =>
                if r.success then Result.unit
                else Result.fail(WhatsAppError.DecodeError("success field is false"))
            case Result.Failure(e)   => Result.fail(WhatsAppError.DecodeError(e.getMessage))
            case panic: Result.Panic => panic

    def decodeMediaId(body: Span[Byte])(using Frame): Result[WhatsAppError.DecodeError, WhatsAppId.MediaId] =
        Json.decodeBytes[Wire.MediaIdResponse](body) match
            case Result.Success(r)   => Result.succeed(WhatsAppId.MediaId(r.id))
            case Result.Failure(e)   => Result.fail(WhatsAppError.DecodeError(e.getMessage))
            case panic: Result.Panic => panic

    def decodeMediaInfo(body: Span[Byte])(using Frame): Result[WhatsAppError.DecodeError, WhatsAppMedia.MediaInfo] =
        Json.decodeBytes[Wire.MediaInfoResponse](body) match
            case Result.Success(r) =>
                Maybe.fromOption(r.file_size.value.toLongOption) match
                    case Present(size) =>
                        Result.succeed(WhatsAppMedia.MediaInfo(WhatsAppId.MediaId(r.id), r.url, r.mime_type, r.sha256, size))
                    case Absent => Result.fail(WhatsAppError.DecodeError(s"file_size not numeric: ${r.file_size.value}"))
            case Result.Failure(e)   => Result.fail(WhatsAppError.DecodeError(e.getMessage))
            case panic: Result.Panic => panic

    def mapError(e: HttpException)(using Frame): WhatsAppError =
        e match
            case s: HttpStatusException =>
                s.body.flatMap(b => Json.decode[Wire.ErrorEnvelope](b).toMaybe)
                    .map(env => mapCode(env.error))
                    .getOrElse(WhatsAppError.Transport(e))
            case other => WhatsAppError.Transport(other)

    /** Maps a Panic throwable from an HTTP call to a typed result. An `IOException`
      * or subtype indicates a transport-level failure (connection refused, connection
      * closed, network reset) and maps to `WhatsAppError.Transport`. Any other throwable
      * is a genuine defect and re-panics so it is not silently swallowed.
      */
    def mapTransportPanic(ex: Throwable)(using Frame): Result[WhatsAppError, Nothing] =
        ex match
            case io: IOException => Result.fail(WhatsAppError.Transport(io))
            case other           => Result.panic(other)

    def mapCode(err: Wire.ErrorDto)(using Frame): WhatsAppError =
        import WhatsAppError.*
        val details = err.error_data.flatMap(_.details)
        err.code match
            case 190                            => AuthError.TokenExpired()
            case 0 | 10 | 200 | 131005          => AuthError.AccessDenied()
            case 130429                         => RateLimited(err.code, err.message, RateLimited.Throughput)
            case 80007                          => RateLimited(err.code, err.message, RateLimited.Waba)
            case 4                              => RateLimited(err.code, err.message, RateLimited.PhoneNumber)
            case 131026                         => RecipientError.Undeliverable()
            case 131021                         => RecipientError.SenderEqualsRecipient()
            case 131047                         => WindowClosed()
            case 132000                         => TemplateError.ParamCountMismatch()
            case 132001                         => TemplateError.DoesNotExist()
            case 132005                         => TemplateError.TextTooLong()
            case 132007                         => TemplateError.ContentPolicy()
            case 132012                         => TemplateError.ParamFormatMismatch()
            case 132015                         => TemplateError.Paused()
            case 131052                         => MediaError.DownloadFailed()
            case 131053                         => MediaError.UploadFailed()
            case 100 | 131008 | 131009 | 135000 => InvalidParameter(err.code, err.message)
            case 131000 | 131016                => ServiceUnavailable(err.code, err.message)
            case _ =>
                Cloud(err.code, err.error_subcode, err.`type`, err.message, details, err.fbtrace_id)
        end match
    end mapCode

    // ----- private encode helpers -----

    private def typeName(msg: WhatsAppMessage): String = msg match
        case _: WhatsAppMessage.Text          => "text"
        case _: WhatsAppMessage.Image         => "image"
        case _: WhatsAppMessage.Video         => "video"
        case _: WhatsAppMessage.Document      => "document"
        case _: WhatsAppMessage.Audio         => "audio"
        case _: WhatsAppMessage.Sticker       => "sticker"
        case _: WhatsAppMessage.Location      => "location"
        case _: WhatsAppMessage.Contacts      => "contacts"
        case _: WhatsAppMessage.Reaction      => "reaction"
        case _: WhatsAppMessage.OfInteractive => "interactive"

    private def fill(env: Wire.SendEnvelope, msg: WhatsAppMessage): Wire.SendEnvelope = msg match
        case WhatsAppMessage.Text(body, preview) =>
            env.copy(text = Present(Wire.TextBody(body, if preview then Present(true) else Absent)))
        case WhatsAppMessage.Image(src, caption)           => env.copy(image = Present(mediaBody(src, caption, Absent)))
        case WhatsAppMessage.Video(src, caption)           => env.copy(video = Present(mediaBody(src, caption, Absent)))
        case WhatsAppMessage.Document(src, caption, fname) => env.copy(document = Present(mediaBody(src, caption, fname)))
        case WhatsAppMessage.Audio(src)                    => env.copy(audio = Present(mediaBody(src, Absent, Absent)))
        case WhatsAppMessage.Sticker(src)                  => env.copy(sticker = Present(mediaBody(src, Absent, Absent)))
        case WhatsAppMessage.Location(lat, lon, name, addr) =>
            env.copy(location = Present(Wire.LocationBody(lat, lon, name, addr)))
        case WhatsAppMessage.Contacts(cs)         => env.copy(contacts = Present(cs.map(contactDto)))
        case WhatsAppMessage.Reaction(mid, emoji) => env.copy(reaction = Present(Wire.ReactionBody(mid.value, emoji)))
        case WhatsAppMessage.OfInteractive(inter) => env.copy(interactive = Present(interactiveBody(inter)))

    private def mediaBody(src: WhatsAppMedia.Source, caption: Maybe[String], filename: Maybe[String]): Wire.MediaBody =
        src match
            case WhatsAppMedia.Source.ById(id)    => Wire.MediaBody(id = Present(id.value), caption = caption, filename = filename)
            case WhatsAppMedia.Source.ByLink(url) => Wire.MediaBody(link = Present(url), caption = caption, filename = filename)

    private def sendStatus(s: String): WhatsAppSendResult.Status = s match
        case "accepted"                    => WhatsAppSendResult.Status.Accepted
        case "held_for_quality_assessment" => WhatsAppSendResult.Status.HeldForQualityAssessment
        case "paused"                      => WhatsAppSendResult.Status.Paused
        case other                         => WhatsAppSendResult.Status.Other(other)

    private def contactDto(c: WhatsAppContact): Wire.ContactDto =
        def presentIfNonEmpty[A](ch: Chunk[A]): Maybe[Chunk[A]] = if ch.isEmpty then Absent else Present(ch)
        Wire.ContactDto(
            name = Wire.ContactNameDto(c.name.formattedName, c.name.first, c.name.last, c.name.middle, c.name.prefix, c.name.suffix),
            phones = presentIfNonEmpty(c.phones.map(p => Wire.ContactPhoneDto(p.phone, p.kind, p.waId.map(_.value)))),
            emails = presentIfNonEmpty(c.emails.map(e => Wire.ContactEmailDto(e.email, e.kind))),
            addresses = presentIfNonEmpty(
                c.addresses.map(a => Wire.ContactAddressDto(a.street, a.city, a.state, a.zip, a.country, a.countryCode, a.kind))
            ),
            org = c.org.map(o => Wire.ContactOrgDto(o.company, o.department, o.title)),
            urls = presentIfNonEmpty(c.urls.map(u => Wire.ContactUrlDto(u.url, u.kind))),
            birthday = c.birthday
        )
    end contactDto

    private def header(h: WhatsAppInteractive.Header): Wire.HeaderDto = h match
        case WhatsAppInteractive.Header.Text(text) =>
            Wire.HeaderDto(`type` = "text", text = Present(text))
        case WhatsAppInteractive.Header.Media(src, WhatsAppInteractive.Header.MediaKind.Image) =>
            Wire.HeaderDto(`type` = "image", image = Present(mediaBody(src, Absent, Absent)))
        case WhatsAppInteractive.Header.Media(src, WhatsAppInteractive.Header.MediaKind.Video) =>
            Wire.HeaderDto(`type` = "video", video = Present(mediaBody(src, Absent, Absent)))
        case WhatsAppInteractive.Header.Document(src, filename) =>
            Wire.HeaderDto(`type` = "document", document = Present(mediaBody(src, Absent, filename)))

    private def interactiveBody(i: WhatsAppInteractive): Wire.InteractiveBody = i match
        case lm: WhatsAppInteractive.ListMenu =>
            val action = Wire.ActionDto(
                button = Present(lm.button),
                sections = Present(lm.sections.map(s =>
                    Wire.SectionDto(s.title, rows = Present(s.rows.map(r => Wire.RowDto(r.id, r.title, r.description))))
                ))
            )
            Wire.InteractiveBody("list", lm.header.map(header), lm.body.map(Wire.TextOnly(_)), lm.footer.map(Wire.TextOnly(_)), action)
        case b: WhatsAppInteractive.Buttons =>
            val action = Wire.ActionDto(buttons = Present(b.buttons.map(rb => Wire.ButtonDto("reply", Wire.ReplyDto(rb.id, rb.title)))))
            Wire.InteractiveBody("button", b.header.map(header), b.body.map(Wire.TextOnly(_)), b.footer.map(Wire.TextOnly(_)), action)
        case c: WhatsAppInteractive.CtaUrl =>
            val params = Wire.ActionParamsDto(display_text = Present(c.displayText), url = Present(c.url))
            val action = Wire.ActionDto(name = Present("cta_url"), parameters = Present(params))
            Wire.InteractiveBody("cta_url", c.header.map(header), c.body.map(Wire.TextOnly(_)), c.footer.map(Wire.TextOnly(_)), action)
        case f: WhatsAppInteractive.Flow =>
            val (flowId, flowName) = f.ref match
                case WhatsAppInteractive.Flow.Ref.ById(id)     => (Present(id), Absent)
                case WhatsAppInteractive.Flow.Ref.ByName(name) => (Absent, Present(name))
            val (flowAction, payload) = f.action match
                case WhatsAppInteractive.Flow.Action.Navigate(screen, data) => ("navigate", Present(Wire.FlowPayloadDto(screen, data)))
                case WhatsAppInteractive.Flow.Action.DataExchange           => ("data_exchange", Absent)
            val mode = f.mode match
                case WhatsAppInteractive.Flow.Mode.Draft     => "draft"
                case WhatsAppInteractive.Flow.Mode.Published => "published"
            val params = Wire.ActionParamsDto(
                flow_message_version = Present("3"),
                flow_token = Present(f.token),
                flow_id = flowId,
                flow_name = flowName,
                flow_cta = Present(f.cta),
                flow_action = Present(flowAction),
                flow_action_payload = payload,
                mode = Present(mode)
            )
            val action = Wire.ActionDto(name = Present("flow"), parameters = Present(params))
            Wire.InteractiveBody("flow", f.header.map(header), f.body.map(Wire.TextOnly(_)), f.footer.map(Wire.TextOnly(_)), action)
        case p: WhatsAppInteractive.Product =>
            val action = Wire.ActionDto(catalog_id = Present(p.catalogId), product_retailer_id = Present(p.productRetailerId))
            Wire.InteractiveBody("product", Absent, p.body.map(Wire.TextOnly(_)), p.footer.map(Wire.TextOnly(_)), action)
        case pl: WhatsAppInteractive.ProductList =>
            val action = Wire.ActionDto(
                catalog_id = Present(pl.catalogId),
                sections = Present(pl.sections.map(s =>
                    Wire.SectionDto(s.title, product_items = Present(s.productRetailerIds.map(Wire.ProductItemDto(_))))
                ))
            )
            Wire.InteractiveBody(
                "product_list",
                Present(Wire.HeaderDto(`type` = "text", text = Present(pl.headerText))),
                pl.body.map(Wire.TextOnly(_)),
                pl.footer.map(Wire.TextOnly(_)),
                action
            )

    private def parameterDto(p: WhatsAppTemplate.Parameter): Wire.ParameterDto = p match
        case WhatsAppTemplate.Parameter.Text(text) =>
            Wire.ParameterDto(`type` = "text", text = Present(text))
        case WhatsAppTemplate.Parameter.Currency(fallback, code, amount1000) =>
            Wire.ParameterDto(`type` = "currency", currency = Present(Wire.CurrencyDto(fallback, code, amount1000)))
        case WhatsAppTemplate.Parameter.DateTime(fallback) =>
            Wire.ParameterDto(`type` = "date_time", date_time = Present(Wire.DateTimeDto(fallback)))
        case WhatsAppTemplate.Parameter.Image(src) =>
            Wire.ParameterDto(`type` = "image", image = Present(mediaBody(src, Absent, Absent)))
        case WhatsAppTemplate.Parameter.Document(src, filename) =>
            Wire.ParameterDto(`type` = "document", document = Present(mediaBody(src, Absent, filename)))
        case WhatsAppTemplate.Parameter.Video(src) =>
            Wire.ParameterDto(`type` = "video", video = Present(mediaBody(src, Absent, Absent)))
        case WhatsAppTemplate.Parameter.Payload(payload) =>
            Wire.ParameterDto(`type` = "payload", payload = Present(payload))

    private def componentDto(c: WhatsAppTemplate.Component): Wire.ComponentDto = c match
        case WhatsAppTemplate.Component.Header(params) =>
            Wire.ComponentDto(`type` = "header", parameters = params.map(parameterDto))
        case WhatsAppTemplate.Component.Body(params) =>
            Wire.ComponentDto(`type` = "body", parameters = params.map(parameterDto))
        case WhatsAppTemplate.Component.Button(subType, index, params) =>
            val sub = subType match
                case WhatsAppTemplate.ButtonSubType.QuickReply => "quick_reply"
                case WhatsAppTemplate.ButtonSubType.Url        => "url"
                case WhatsAppTemplate.ButtonSubType.CopyCode   => "copy_code"
                case WhatsAppTemplate.ButtonSubType.Flow       => "flow"
            Wire.ComponentDto(`type` = "button", sub_type = Present(sub), index = Present(index), parameters = params.map(parameterDto))

    private def templateBody(t: WhatsAppTemplate): Wire.TemplateBody =
        Wire.TemplateBody(
            name = t.name,
            language = Wire.LanguageDto(code = t.language),
            components = if t.components.isEmpty then Absent else Present(t.components.map(componentDto))
        )

    def decodeNotifications(body: Span[Byte])(using Frame): Result[WhatsAppError.DecodeError, Chunk[WhatsAppNotification]] =
        Json.decodeBytes[Wire.InboundEnvelope](body) match
            case Result.Success(env) =>
                Result.succeed(
                    env.entry.flatMap { entry =>
                        entry.changes.flatMap(change => decodeChange(change))
                    }
                )
            case Result.Failure(e)   => Result.fail(WhatsAppError.DecodeError(e.getMessage))
            case panic: Result.Panic => panic

    private def decodeChange(change: Wire.InboundChange)(using Frame): Chunk[WhatsAppNotification] =
        val meta = change.value.metadata match
            case Present(m) => WhatsAppNotification.Metadata(m.display_phone_number, WhatsAppId.PhoneNumberId(m.phone_number_id))
            case Absent     => WhatsAppNotification.Metadata("", WhatsAppId.PhoneNumberId(""))
        val contacts = change.value.contacts.getOrElse(Chunk.empty)
        val msgs     = change.value.messages.getOrElse(Chunk.empty).map(dto => decodeInboundMessage(meta, contacts, dto))
        val statuses = change.value.statuses.getOrElse(Chunk.empty).map(dto => decodeStatus(meta, dto))
        val all      = msgs ++ statuses
        if all.isEmpty then Chunk(WhatsAppNotification.Unsupported(meta, change.field, rawOf(change))) else all
    end decodeChange

    private def decodeInboundMessage(
        meta: WhatsAppNotification.Metadata,
        contacts: Chunk[Wire.InboundContactDto],
        dto: Wire.InboundMessageDto
    )(using Frame): WhatsAppNotification =
        val content = decodeContent(dto)
        val senderProfileName = Maybe.fromOption(contacts.find(c => c.wa_id.contains(dto.from)))
            .flatMap(c => c.profile.flatMap(_.name))
        WhatsAppNotification.InboundMessage(
            metadata = meta,
            from = WhatsAppId.WaId(dto.from),
            id = WhatsAppId.MessageId(dto.id),
            timestamp = Maybe.fromOption(dto.timestamp.toLongOption).getOrElse(0L),
            content = content,
            context = dto.context.map(c => WhatsAppNotification.Context(WhatsAppId.WaId(c.from), WhatsAppId.MessageId(c.id))),
            senderProfileName = senderProfileName
        )
    end decodeInboundMessage

    private def decodeContent(dto: Wire.InboundMessageDto)(using Frame): WhatsAppNotification.Content =
        dto.`type` match
            case "text" =>
                dto.text match
                    case Present(t) => WhatsAppNotification.Content.Text(t.body)
                    case Absent     => WhatsAppNotification.Content.Unknown("text", rawOfMessage(dto))
            case "image"    => mediaContent(WhatsAppMedia.MediaType.Other("image"), dto.image, dto)
            case "audio"    => mediaContent(WhatsAppMedia.MediaType.Other("audio"), dto.audio, dto)
            case "video"    => mediaContent(WhatsAppMedia.MediaType.Other("video"), dto.video, dto)
            case "document" => mediaContent(WhatsAppMedia.MediaType.Other("document"), dto.document, dto)
            case "sticker"  => mediaContent(WhatsAppMedia.MediaType.Other("sticker"), dto.sticker, dto)
            case "location" =>
                dto.location match
                    case Present(l) => WhatsAppNotification.Content.Location(l.latitude, l.longitude, l.name, l.address)
                    case Absent     => WhatsAppNotification.Content.Unknown("location", rawOfMessage(dto))
            case "contacts" =>
                dto.contacts match
                    case Present(cs) => WhatsAppNotification.Content.Contacts(cs.map(fromContactDto))
                    case Absent      => WhatsAppNotification.Content.Unknown("contacts", rawOfMessage(dto))
            case "reaction" =>
                dto.reaction match
                    case Present(r) => WhatsAppNotification.Content.Reaction(WhatsAppId.MessageId(r.message_id), r.emoji)
                    case Absent     => WhatsAppNotification.Content.Unknown("reaction", rawOfMessage(dto))
            case "button" =>
                dto.button match
                    case Present(b) => WhatsAppNotification.Content.Button(b.payload, b.text)
                    case Absent     => WhatsAppNotification.Content.Unknown("button", rawOfMessage(dto))
            case "interactive" =>
                dto.interactive match
                    case Present(i) =>
                        (i.`type`, i.button_reply, i.list_reply) match
                            case ("button_reply", Present(r), _) => WhatsAppNotification.Content.ButtonReply(r.id, r.title)
                            case ("list_reply", _, Present(r))   => WhatsAppNotification.Content.ListReply(r.id, r.title, r.description)
                            case _                               => WhatsAppNotification.Content.Unknown("interactive", rawOfMessage(dto))
                    case Absent => WhatsAppNotification.Content.Unknown("interactive", rawOfMessage(dto))
            case "order" =>
                dto.order match
                    case Present(o) => WhatsAppNotification.Content.Order(o.catalog_id, o.product_items.map(_.product_retailer_id))
                    case Absent     => WhatsAppNotification.Content.Unknown("order", rawOfMessage(dto))
            case "system" =>
                dto.system match
                    case Present(s) => WhatsAppNotification.Content.System(s.body)
                    case Absent     => WhatsAppNotification.Content.Unknown("system", rawOfMessage(dto))
            case other =>
                WhatsAppNotification.Content.Unknown(other, rawOfMessage(dto))

    private def mediaContent(kind: WhatsAppMedia.MediaType, body: Maybe[Wire.InboundMediaDto], dto: Wire.InboundMessageDto)(using
        Frame
    ): WhatsAppNotification.Content =
        body match
            case Present(m) =>
                WhatsAppNotification.Content.Media(kind, WhatsAppId.MediaId(m.id), m.mime_type, m.sha256, m.caption, m.filename, m.voice)
            case Absent => WhatsAppNotification.Content.Unknown(dto.`type`, rawOfMessage(dto))

    private def decodeStatus(meta: WhatsAppNotification.Metadata, dto: Wire.InboundStatusDto)(using Frame): WhatsAppNotification =
        val status = dto.status match
            case "sent"      => WhatsAppNotification.Status.Sent
            case "delivered" => WhatsAppNotification.Status.Delivered
            case "read"      => WhatsAppNotification.Status.Read
            case "failed"    => WhatsAppNotification.Status.Failed
            case "deleted"   => WhatsAppNotification.Status.Deleted
            case other       => WhatsAppNotification.Status.Other(other)
        WhatsAppNotification.StatusUpdate(
            metadata = meta,
            id = WhatsAppId.MessageId(dto.id),
            status = status,
            timestamp = Maybe.fromOption(dto.timestamp.toLongOption).getOrElse(0L),
            recipientId = WhatsAppId.WaId(dto.recipient_id),
            conversation = dto.conversation.map(c => WhatsAppNotification.Conversation(c.id, c.expiration_timestamp, c.origin.`type`)),
            pricing = dto.pricing.map(p => WhatsAppNotification.Pricing(p.billable, p.pricing_model, p.category, p.`type`)),
            errors = dto.errors.map(e =>
                WhatsAppError.Cloud(e.code, e.error_subcode, e.`type`, e.message, e.error_data.flatMap(_.details), e.fbtrace_id)
            )
        )
    end decodeStatus

    private def fromContactDto(dto: Wire.ContactDto): WhatsAppContact =
        WhatsAppContact(
            name = WhatsAppContact.Name(
                dto.name.formatted_name,
                dto.name.first_name,
                dto.name.last_name,
                dto.name.middle_name,
                dto.name.prefix,
                dto.name.suffix
            ),
            phones = dto.phones.getOrElse(Chunk.empty).map(p => WhatsAppContact.Phone(p.phone, p.`type`, p.wa_id.map(WhatsAppId.WaId(_)))),
            emails = dto.emails.getOrElse(Chunk.empty).map(e => WhatsAppContact.Email(e.email, e.`type`)),
            addresses = dto.addresses.getOrElse(Chunk.empty).map(a =>
                WhatsAppContact.Address(a.street, a.city, a.state, a.zip, a.country, a.country_code, a.`type`)
            ),
            org = dto.org.map(o => WhatsAppContact.Org(o.company, o.department, o.title)),
            urls = dto.urls.getOrElse(Chunk.empty).map(u => WhatsAppContact.Url(u.url, u.`type`)),
            birthday = dto.birthday
        )

    private def rawOf(change: Wire.InboundChange)(using Frame): String =
        Json.encode(change.value)

    private def rawOfMessage(dto: Wire.InboundMessageDto)(using Frame): String =
        Json.encode(dto)

end Codec
