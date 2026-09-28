package kyo.internal.whatsapp

import kyo.*

/** Total pure mapping functions between the public ADT and the wire DTO layer. `encodeSend`
  * and `encodeTemplate` build a `Wire.SendEnvelope` with exactly the one populated body
  * field its message type selects, then serialize it to bytes (Absent fields are omitted,
  * yielding the nested `type`-keyed sibling shape). The `decode*` functions parse a response DTO
  * into the public value; a body kyo-schema rejects is a `WhatsAppDecodeException` naming
  * kyo-schema's leaf, path and position without its text, and an ok answer of the wrong shape (an
  * empty `messages`, a `success` of false) is one too, `MissingField` or `ConstructorRejected` at
  * that field. Functions that build a `WhatsAppException` take `(using Frame)`
  * because every error leaf carries a `Frame`. No effect row: these are pure functions.
  */
private[kyo] object Codec:

    def encodeSend(to: WhatsAppId.WaId, msg: WhatsAppMessage, replyTo: Maybe[WhatsAppId.MessageId])(using Frame): Span[Byte] =
        val ctx  = replyTo.map(id => Wire.ContextBody(id.value))
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

    /** The send's answer. An empty `messages`, which leaves the send without an id, is a decode failure at `messages`. */
    def decodeSendResult(method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, WhatsAppSendResult] =
        decodeJson[Wire.SendResponse](method, body).flatMap { r =>
            r.messages.headMaybe match
                case Present(m) =>
                    Result.succeed(WhatsAppSendResult(
                        messageId = WhatsAppId.MessageId(m.id),
                        contactWaId = r.contacts.flatMap(_.headMaybe).flatMap(_.wa_id).map(WhatsAppId.WaId(_)),
                        status = m.message_status.map(sendStatus)
                    ))
                case Absent =>
                    Result.fail(rejected(method, WhatsAppDecodeException.Failure.MissingField, "messages"))
        }

    /** An acknowledgement. `{"success": false}` is not one, and is a decode failure at `success`. */
    def decodeSuccess(method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, Unit] =
        decodeJson[Wire.SuccessResponse](method, body).flatMap { r =>
            if r.success then Result.unit
            else Result.fail(rejected(method, WhatsAppDecodeException.Failure.ConstructorRejected, "success"))
        }

    private def rejected(method: String, failure: WhatsAppDecodeException.Failure, field: String)(using Frame): WhatsAppDecodeException =
        WhatsAppDecodeException(method, WhatsAppDecodeException.Part.Response, failure, Chunk(field), Absent)

    def decodeMediaId(method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, WhatsAppId.MediaId] =
        decodeJson[Wire.MediaIdResponse](method, body).map(r => WhatsAppId.MediaId(r.id))

    /** The media info, with its `url` parsed: a url that does not parse is refused by the decode, at the path `url`. */
    def decodeMediaInfo(method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, WhatsAppMedia.MediaInfo] =
        decodeJson[Wire.MediaInfoResponse](method, body).flatMap { r =>
            HttpUrl.parse(r.url) match
                case Result.Success(url) =>
                    Result.succeed(WhatsAppMedia.MediaInfo(WhatsAppId.MediaId(r.id), url, r.mime_type, r.sha256, r.file_size.value.bytes))
                case _ =>
                    Result.fail(rejected(method, WhatsAppDecodeException.Failure.ConstructorRejected, "url"))
        }

    def decodeCustom[A: Schema](method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, A] =
        decodeJson[A](method, body)

    private def decodeJson[A: Schema](method: String, body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, A] =
        Json.decodeBytes[A](body).mapFailure(WhatsAppDecodeException.of(method, WhatsAppDecodeException.Part.Response, _))

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
        case WhatsAppMessage.Image(src, caption)            => env.copy(image = Present(mediaBody(src, caption, Absent)))
        case WhatsAppMessage.Video(src, caption)            => env.copy(video = Present(mediaBody(src, caption, Absent)))
        case WhatsAppMessage.Document(src, caption, fname)  => env.copy(document = Present(mediaBody(src, caption, fname)))
        case WhatsAppMessage.Audio(src)                     => env.copy(audio = Present(mediaBody(src, Absent, Absent)))
        case WhatsAppMessage.Sticker(src)                   => env.copy(sticker = Present(mediaBody(src, Absent, Absent)))
        case WhatsAppMessage.Location(lat, lon, name, addr) =>
            env.copy(location = Present(Wire.LocationBody(lat, lon, name, addr)))
        case WhatsAppMessage.Contacts(cs)         => env.copy(contacts = Present(cs.map(contactDto)))
        case WhatsAppMessage.Reaction(mid, emoji) => env.copy(reaction = Present(Wire.ReactionBody(mid.value, emoji)))
        case WhatsAppMessage.OfInteractive(inter) => env.copy(interactive = Present(interactiveBody(inter)))

    private def mediaBody(src: WhatsAppMedia.Source, caption: Maybe[String], filename: Maybe[String]): Wire.MediaBody =
        src match
            case WhatsAppMedia.Source.ById(id)    => Wire.MediaBody(id = Present(id.value), caption = caption, filename = filename)
            case WhatsAppMedia.Source.ByLink(url) => Wire.MediaBody(link = Present(url.full), caption = caption, filename = filename)

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
            val params = Wire.ActionParamsDto(display_text = Present(c.displayText), url = Present(c.url.full))
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

    /** The method name a webhook body's decode failure carries. */
    inline val WebhookMethod = "webhook"

    /** A body that is not the webhook envelope is the decode failure; anything inside the envelope that does not decode is
      * delivered as `Unknown` by `decodeChange`.
      */
    def decodeNotifications(body: Span[Byte])(using Frame): Result[WhatsAppDecodeException, Chunk[WhatsAppNotification]] =
        Json.decodeBytes[Wire.InboundEnvelope](body)
            .mapFailure(WhatsAppDecodeException.of(WebhookMethod, WhatsAppDecodeException.Part.Notification, _))
            .flatMap(env => Result.collect(env.entry.flatMap(_.changes).map(decodeChange)).map(changes => Chunk.from(changes).flatten))
    end decodeNotifications

    /** A change's notifications, one per message and per status. A change whose metadata or contacts do not decode is one
      * `Unknown` holding the change, and a message or status that does not decode is an `Unknown` holding that item, so the
      * items beside it are still delivered. A panic while decoding stays a panic.
      */
    private def decodeChange(change: Wire.InboundChange)(using Frame): Result[Nothing, Chunk[WhatsAppNotification]] =
        def unknown(raw: Structure.Value, meta: Maybe[WhatsAppNotification.Metadata]): WhatsAppNotification =
            WhatsAppNotification.Unknown(change.field, rawOf(raw), meta)
        val parts =
            for
                header <- Structure.decode[Wire.InboundHeader](change.value)
                raws   <- Structure.decode[Wire.InboundRawItems](change.value)
            yield (header, raws)
        parts match
            case Result.Success((header, raws)) =>
                val meta = header.metadata.map(m =>
                    WhatsAppNotification.Metadata(m.display_phone_number, WhatsAppId.PhoneNumberId(m.phone_number_id))
                )
                val contacts = header.contacts.getOrElse(Chunk.empty)
                val messages = raws.messages.getOrElse(Chunk.empty)
                val statuses = raws.statuses.getOrElse(Chunk.empty)
                meta match
                    case Present(m) if messages.nonEmpty || statuses.nonEmpty =>
                        val items =
                            messages.map(raw =>
                                orUnknown(
                                    Structure.decode[Wire.InboundMessageDto](raw).map(decodeInboundMessage(
                                        m,
                                        change.field,
                                        contacts,
                                        _,
                                        raw
                                    )),
                                    unknown(raw, meta)
                                )
                            ) ++ statuses.map(raw =>
                                orUnknown(
                                    Structure.decode[Wire.InboundStatusDto](raw).map(decodeStatus(m, change.field, _, raw)),
                                    unknown(raw, meta)
                                )
                            )
                        Result.collect(items).map(Chunk.from(_))
                    case _ => Result.succeed(Chunk(unknown(change.value, meta)))
                end match
            case Result.Failure(_) => Result.succeed(Chunk(unknown(change.value, Absent)))
            case Result.Panic(e)   => Result.panic(e)
        end match
    end decodeChange

    private def orUnknown(
        decoded: Result[DecodeException, WhatsAppNotification],
        unknown: => WhatsAppNotification
    ): Result[Nothing, WhatsAppNotification] =
        decoded match
            case Result.Success(n) => Result.succeed(n)
            case Result.Failure(_) => Result.succeed(unknown)
            case Result.Panic(e)   => Result.panic(e)

    private def decodeInboundMessage(
        meta: WhatsAppNotification.Metadata,
        field: String,
        contacts: Chunk[Wire.InboundContactDto],
        dto: Wire.InboundMessageDto,
        raw: Structure.Value
    )(using Frame): WhatsAppNotification =
        epochSeconds(dto.timestamp) match
            case Absent             => WhatsAppNotification.Unknown(field, rawOf(raw), Present(meta))
            case Present(timestamp) =>
                WhatsAppNotification.InboundMessage(
                    metadata = meta,
                    from = WhatsAppId.WaId(dto.from),
                    id = WhatsAppId.MessageId(dto.id),
                    timestamp = timestamp,
                    content = decodeContent(dto, rawOf(raw)),
                    context = dto.context.map(c => WhatsAppNotification.Context(WhatsAppId.WaId(c.from), WhatsAppId.MessageId(c.id))),
                    senderProfileName = Maybe.fromOption(contacts.find(c => c.wa_id.contains(dto.from))).flatMap(_.profile.flatMap(_.name))
                )
    end decodeInboundMessage

    private def decodeContent(dto: Wire.InboundMessageDto, raw: => WhatsAppRawJson)(using Frame): WhatsAppNotification.Content =
        dto.`type` match
            case "text" =>
                dto.text match
                    case Present(t) => WhatsAppNotification.Content.Text(t.body)
                    case Absent     => WhatsAppNotification.Content.Unknown("text", raw)
            case "image"    => mediaContent(WhatsAppNotification.Content.Media.Kind.Image, dto.image, dto, raw)
            case "audio"    => mediaContent(WhatsAppNotification.Content.Media.Kind.Audio, dto.audio, dto, raw)
            case "video"    => mediaContent(WhatsAppNotification.Content.Media.Kind.Video, dto.video, dto, raw)
            case "document" => mediaContent(WhatsAppNotification.Content.Media.Kind.Document, dto.document, dto, raw)
            case "sticker"  => mediaContent(WhatsAppNotification.Content.Media.Kind.Sticker, dto.sticker, dto, raw)
            case "location" =>
                dto.location match
                    case Present(l) => WhatsAppNotification.Content.Location(l.latitude, l.longitude, l.name, l.address)
                    case Absent     => WhatsAppNotification.Content.Unknown("location", raw)
            case "contacts" =>
                dto.contacts match
                    case Present(cs) => WhatsAppNotification.Content.Contacts(cs.map(fromContactDto))
                    case Absent      => WhatsAppNotification.Content.Unknown("contacts", raw)
            case "reaction" =>
                dto.reaction match
                    case Present(r) => WhatsAppNotification.Content.Reaction(WhatsAppId.MessageId(r.message_id), r.emoji)
                    case Absent     => WhatsAppNotification.Content.Unknown("reaction", raw)
            case "button" =>
                dto.button match
                    case Present(b) => WhatsAppNotification.Content.Button(b.payload, b.text)
                    case Absent     => WhatsAppNotification.Content.Unknown("button", raw)
            case "interactive" =>
                dto.interactive match
                    case Present(i) =>
                        (i.`type`, i.button_reply, i.list_reply) match
                            case ("button_reply", Present(r), _) => WhatsAppNotification.Content.ButtonReply(r.id, r.title)
                            case ("list_reply", _, Present(r))   => WhatsAppNotification.Content.ListReply(r.id, r.title, r.description)
                            case _                               => WhatsAppNotification.Content.Unknown("interactive", raw)
                    case Absent => WhatsAppNotification.Content.Unknown("interactive", raw)
            case "order" =>
                dto.order match
                    case Present(o) => WhatsAppNotification.Content.Order(o.catalog_id, o.product_items.map(_.product_retailer_id))
                    case Absent     => WhatsAppNotification.Content.Unknown("order", raw)
            case "system" =>
                dto.system match
                    case Present(s) => WhatsAppNotification.Content.System(s.body)
                    case Absent     => WhatsAppNotification.Content.Unknown("system", raw)
            case other =>
                WhatsAppNotification.Content.Unknown(other, raw)

    private def mediaContent(
        kind: WhatsAppNotification.Content.Media.Kind,
        body: Maybe[Wire.InboundMediaDto],
        dto: Wire.InboundMessageDto,
        raw: => WhatsAppRawJson
    ): WhatsAppNotification.Content =
        body match
            case Present(m) =>
                WhatsAppNotification.Content.Media(kind, WhatsAppId.MediaId(m.id), m.mime_type, m.sha256, m.caption, m.filename, m.voice)
            case Absent => WhatsAppNotification.Content.Unknown(dto.`type`, raw)

    private def decodeStatus(meta: WhatsAppNotification.Metadata, field: String, dto: Wire.InboundStatusDto, raw: Structure.Value)(using
        Frame
    ): WhatsAppNotification =
        val status = dto.status match
            case "sent"      => WhatsAppNotification.Status.Sent
            case "delivered" => WhatsAppNotification.Status.Delivered
            case "read"      => WhatsAppNotification.Status.Read
            case "failed"    => WhatsAppNotification.Status.Failed
            case "deleted"   => WhatsAppNotification.Status.Deleted
            case other       => WhatsAppNotification.Status.Other(other)
        // An expiration Meta sent that does not fit an Instant makes the status Unknown, as an unparseable timestamp does.
        val expiration         = dto.conversation.flatMap(_.expiration_timestamp).map(epochSeconds)
        val expirationUnusable = expiration.exists(_.isEmpty)
        epochSeconds(dto.timestamp) match
            case Present(_) if expirationUnusable => WhatsAppNotification.Unknown(field, rawOf(raw), Present(meta))
            case Absent                           => WhatsAppNotification.Unknown(field, rawOf(raw), Present(meta))
            case Present(timestamp)               =>
                WhatsAppNotification.StatusUpdate(
                    metadata = meta,
                    id = WhatsAppId.MessageId(dto.id),
                    status = status,
                    timestamp = timestamp,
                    recipientId = WhatsAppId.WaId(dto.recipient_id),
                    conversation = dto.conversation.map(c =>
                        WhatsAppNotification.Conversation(c.id, expiration.flatten, c.origin.`type`)
                    ),
                    pricing = dto.pricing.map(p => WhatsAppNotification.Pricing(p.billable, p.pricing_model, p.category, p.`type`)),
                    errors = dto.errors.map(e =>
                        WhatsAppNotification.DeliveryIssue(e.code, e.title.orElse(e.message), e.error_data.flatMap(_.details), e.href)
                    )
                )
        end match
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

    private def rawOf(value: Structure.Value)(using Frame): WhatsAppRawJson =
        WhatsAppRawJson(Json.encode(value))

    /** Meta's epoch-seconds text as an `Instant`, `Absent` unless it is ASCII digits within the range an `Instant` holds. The text is
      * checked character by character because `toLongOption` also takes a sign and any Unicode digit.
      */
    private def epochSeconds(text: String): Maybe[Instant] =
        if text.isEmpty || !text.forall(c => c >= '0' && c <= '9') then Absent
        else
            Maybe.fromOption(text.toLongOption)
                .filter(_ <= java.time.Instant.MAX.getEpochSecond)
                .map(s => Instant.fromJava(java.time.Instant.ofEpochSecond(s)))

end Codec
