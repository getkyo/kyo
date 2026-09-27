package kyo.internal.telegram

import kyo.*

/** Pure mappings between the public model and the Bot API's JSON: the decoders from [[Wire]] values to
  * the model, and the encoders of each method's parameters as a JSON object.
  */
private[kyo] object WireCodec:

    type Params = Chunk[(String, Structure.Value)]

    /** A value the module's own check refused, at `path`: a URL that does not parse, an update with no kind, a
      * result other than the `true` a method documents.
      */
    final case class Rejected(
        path: Chunk[String],
        failure: TelegramDecodeException.Failure = TelegramDecodeException.Failure.ConstructorRejected
    ) derives CanEqual

    /** A decode that fails with kyo-schema's failure, or with the module's own refusal. */
    type Decoded[A] = Result[DecodeException | Rejected, A]

    // --- Decoding ---

    /** An update of a known kind that decodes, or `Unknown` with its kind's field name. Fails only when
      * `update_id` is missing, since nothing could confirm the update, or when no field names a kind.
      */
    def decodeUpdate(value: Structure.Value)(using Frame): Decoded[TelegramUpdate] =
        Structure.decode[Wire.UpdateHead](value).flatMap { head =>
            import TelegramUpdate.*
            val id                                                              = TelegramId.UpdateId(head.update_id)
            val known: Maybe[(String, Result[DecodeException, TelegramUpdate])] =
                head.message.map(v => "message" -> message(v).map(Message(id, _)))
                    .orElse(head.edited_message.map(v => "edited_message" -> message(v).map(EditedMessage(id, _))))
                    .orElse(head.channel_post.map(v => "channel_post" -> message(v).map(ChannelPost(id, _))))
                    .orElse(head.edited_channel_post.map(v => "edited_channel_post" -> message(v).map(EditedChannelPost(id, _))))
                    .orElse(head.callback_query.map(v => "callback_query" -> callbackQuery(v).map(CallbackQuery(id, _))))
                    .orElse(head.my_chat_member.map(v => "my_chat_member" -> chatMemberUpdate(v).map(MyChatMember(id, _))))
                    .orElse(head.chat_member.map(v => "chat_member" -> chatMemberUpdate(v).map(ChatMember(id, _))))
                    .orElse(head.message_reaction.map(v => "message_reaction" -> reactionUpdate(v).map(MessageReaction(id, _))))
            known match
                case Present((name, decoded)) => Result.succeed(decoded.getOrElse(Unknown(id, name, raw(value))))
                case Absent                   =>
                    kindField(value) match
                        case Present(name) => Result.succeed(Unknown(id, name, raw(value)))
                        case Absent        => Result.fail(Rejected(Chunk.empty))
            end match
        }

    /** The field of an update that names its kind: the first one other than `update_id`. */
    private def kindField(value: Structure.Value): Maybe[String] =
        value match
            case Structure.Value.Record(fields) => fields.map(_._1).filter(_ != "update_id").headMaybe
            case Structure.Value.VariantCase(_, _) | Structure.Value.Sequence(_) | Structure.Value.MapEntries(_) |
                Structure.Value.Str(_) | Structure.Value.Bool(_) | Structure.Value.Integer(_) | Structure.Value.Decimal(_) |
                Structure.Value.BigNum(_) | Structure.Value.Bytes(_) | Structure.Value.Instant(_) | Structure.Value.Duration(_) |
                Structure.Value.Null => Absent

    def message(value: Structure.Value)(using Frame): Result[DecodeException, TelegramMessage] =
        Structure.decode[Wire.Message](value).flatMap { m =>
            val reply: Result[DecodeException, Maybe[TelegramMessage]] =
                m.reply_to_message match
                    case Present(r) => message(r).map(msg => Present(msg.copy(replyTo = Absent)))
                    case Absent     => Result.succeed(Absent)
            reply.map { replyTo =>
                TelegramMessage(
                    id = TelegramId.MessageId(m.message_id),
                    chat = chat(m.chat),
                    date = instant(m.date),
                    content = content(m, value),
                    from = m.from.map(user),
                    senderChat = m.sender_chat.map(chat),
                    thread = m.message_thread_id.map(TelegramId.MessageThreadId(_)),
                    editDate = m.edit_date.map(instant),
                    replyTo = replyTo
                )
            }
        }

    def callbackQuery(value: Structure.Value)(using Frame): Result[DecodeException, TelegramCallbackQuery] =
        Structure.decode[Wire.CallbackQuery](value).flatMap { q =>
            val source: Result[DecodeException, Maybe[TelegramCallbackQuery.Source]] =
                q.message match
                    case Absent     => Result.succeed(Absent)
                    case Present(v) =>
                        Structure.decode[Wire.MessageHead](v).flatMap { head =>
                            if head.date == 0 then
                                Result.succeed(Present(TelegramCallbackQuery.Source.Inaccessible(
                                    chat(head.chat),
                                    TelegramId.MessageId(head.message_id)
                                )))
                            else message(v).map(m => Present(TelegramCallbackQuery.Source.Accessible(m)))
                        }
            source.map { s =>
                TelegramCallbackQuery(
                    id = TelegramId.CallbackQueryId(q.id),
                    from = user(q.from),
                    chatInstance = q.chat_instance,
                    message = s,
                    inlineMessageId = q.inline_message_id,
                    data = q.data
                )
            }
        }

    def chatMemberUpdate(value: Structure.Value)(using Frame): Result[DecodeException, TelegramChatMemberUpdate] =
        Structure.decode[Wire.ChatMemberUpdated](value).map { u =>
            TelegramChatMemberUpdate(
                chat = chat(u.chat),
                from = user(u.from),
                date = instant(u.date),
                member = user(u.new_chat_member.user),
                oldStatus = status(u.old_chat_member.status),
                newStatus = status(u.new_chat_member.status)
            )
        }

    def reactionUpdate(value: Structure.Value)(using Frame): Result[DecodeException, TelegramReactionUpdate] =
        Structure.decode[Wire.MessageReactionUpdated](value).map { r =>
            TelegramReactionUpdate(
                chat = chat(r.chat),
                message = TelegramId.MessageId(r.message_id),
                date = instant(r.date),
                oldReaction = r.old_reaction.map(reaction),
                newReaction = r.new_reaction.map(reaction),
                user = r.user.map(user),
                actorChat = r.actor_chat.map(chat)
            )
        }

    def file(value: Structure.Value)(using Frame): Result[DecodeException, TelegramFile] =
        Structure.decode[Wire.File](value).map { f =>
            TelegramFile(TelegramId.FileId(f.file_id), TelegramId.FileUniqueId(f.file_unique_id), size(f.file_size), f.file_path)
        }

    /** Telegram answers `url` as an empty string when no webhook is set, and otherwise the URL `setWebhook`
      * registered; one that does not parse fails at `url`.
      */
    def webhookInfo(value: Structure.Value)(using Frame): Decoded[TelegramWebhookInfo] =
        Structure.decode[Wire.WebhookInfo](value).flatMap { w =>
            val url: Result[Rejected, Maybe[HttpUrl]] =
                if w.url.isEmpty then Result.succeed(Absent)
                else
                    HttpUrl.parse(w.url) match
                        case Result.Success(u) => Result.succeed(Present(u))
                        case Result.Failure(_) => Result.fail(Rejected(Chunk("url")))
                        case Result.Panic(ex)  => Result.panic(ex)
            url.map { u =>
                TelegramWebhookInfo(
                    url = u,
                    hasCustomCertificate = w.has_custom_certificate,
                    pendingUpdateCount = w.pending_update_count,
                    ipAddress = w.ip_address,
                    lastDeliveryFailureDate = w.last_error_date.map(instant),
                    lastDeliveryFailureMessage = w.last_error_message,
                    maxConnections = w.max_connections,
                    allowedUpdates = w.allowed_updates.map(updateType)
                )
            }
        }

    def userOf(value: Structure.Value)(using Frame): Result[DecodeException, TelegramUser] =
        Structure.decode[Wire.User](value).map(user)

    def raw(value: Structure.Value)(using Frame): TelegramRawJson = TelegramRawJson(Json.encode(value))

    private def user(u: Wire.User): TelegramUser =
        TelegramUser(TelegramId.UserId(u.id), u.is_bot, u.first_name, u.last_name, u.username, u.language_code)

    private def chat(c: Wire.Chat): TelegramChat =
        val kind = c.`type` match
            case "private"    => TelegramChat.Type.Private
            case "group"      => TelegramChat.Type.Group
            case "supergroup" => TelegramChat.Type.Supergroup
            case "channel"    => TelegramChat.Type.Channel
            case other        => TelegramChat.Type.Other(other)
        TelegramChat(TelegramId.ChatId(c.id), kind, c.title, c.username, c.first_name, c.last_name, c.is_forum.getOrElse(false))
    end chat

    private def entity(e: Wire.Entity): TelegramEntity =
        import TelegramEntity.Kind
        val kind = e.`type` match
            case "mention"               => Kind.Mention
            case "hashtag"               => Kind.Hashtag
            case "cashtag"               => Kind.Cashtag
            case "bot_command"           => Kind.BotCommand
            case "url"                   => Kind.Url
            case "email"                 => Kind.Email
            case "phone_number"          => Kind.PhoneNumber
            case "bold"                  => Kind.Bold
            case "italic"                => Kind.Italic
            case "underline"             => Kind.Underline
            case "strikethrough"         => Kind.Strikethrough
            case "spoiler"               => Kind.Spoiler
            case "blockquote"            => Kind.Blockquote
            case "expandable_blockquote" => Kind.ExpandableBlockquote
            case "code"                  => Kind.Code
            case "pre"                   => Kind.Pre(e.language)
            case "text_link"             => e.url.flatMap(TelegramUrl.parse).fold(Kind.Other("text_link"))(Kind.TextLink(_))
            case "text_mention"          => e.user.fold(Kind.Other("text_mention"))(u => Kind.TextMention(user(u)))
            case "custom_emoji"          => e.custom_emoji_id.fold(Kind.Other("custom_emoji"))(Kind.CustomEmoji(_))
            case other                   => Kind.Other(other)
        TelegramEntity(kind, e.offset, e.length)
    end entity

    private def content(m: Wire.Message, value: Structure.Value)(using Frame): TelegramMessage.Content =
        import TelegramMessage.Content
        val caption = m.caption.map(TelegramMessage.Caption(_, m.caption_entities.map(entity)))
        m.text.map(Content.Text(_, m.entities.map(entity)))
            .orElse(m.photo.map(p => Content.Photo(p.map(photoSize), caption)))
            .orElse(m.document.map(d => Content.Document(document(d), caption)))
            .orElse(m.audio.map(a => Content.Audio(audio(a), caption)))
            .orElse(m.video.map(v => Content.Video(video(v), caption)))
            .orElse(m.voice.map(v => Content.Voice(voice(v), caption)))
            .orElse(m.location.map(l => Content.Location(TelegramMedia.Location(l.latitude, l.longitude))))
            .getOrElse(Content.Unknown(raw(value)))
    end content

    private def photoSize(p: Wire.PhotoSize): TelegramMedia.PhotoSize =
        TelegramMedia.PhotoSize(
            TelegramId.FileId(p.file_id),
            TelegramId.FileUniqueId(p.file_unique_id),
            p.width,
            p.height,
            size(p.file_size)
        )

    private def document(d: Wire.Document): TelegramMedia.Document =
        TelegramMedia.Document(
            TelegramId.FileId(d.file_id),
            TelegramId.FileUniqueId(d.file_unique_id),
            d.file_name,
            d.mime_type,
            size(d.file_size)
        )

    private def audio(a: Wire.Audio): TelegramMedia.Audio =
        TelegramMedia.Audio(
            TelegramId.FileId(a.file_id),
            TelegramId.FileUniqueId(a.file_unique_id),
            a.duration.toLong.seconds,
            a.performer,
            a.title,
            a.file_name,
            a.mime_type,
            size(a.file_size)
        )

    private def video(v: Wire.Video): TelegramMedia.Video =
        TelegramMedia.Video(
            TelegramId.FileId(v.file_id),
            TelegramId.FileUniqueId(v.file_unique_id),
            v.width,
            v.height,
            v.duration.toLong.seconds,
            v.file_name,
            v.mime_type,
            size(v.file_size)
        )

    private def voice(v: Wire.Voice): TelegramMedia.Voice =
        TelegramMedia.Voice(
            TelegramId.FileId(v.file_id),
            TelegramId.FileUniqueId(v.file_unique_id),
            v.duration.toLong.seconds,
            v.mime_type,
            size(v.file_size)
        )

    private def status(s: String): TelegramChatMemberUpdate.Status =
        import TelegramChatMemberUpdate.Status
        s match
            case "creator"       => Status.Creator
            case "administrator" => Status.Administrator
            case "member"        => Status.Member
            case "restricted"    => Status.Restricted
            case "left"          => Status.Left
            case "kicked"        => Status.Kicked
            case other           => Status.Other(other)
        end match
    end status

    private def reaction(r: Wire.ReactionType): TelegramReaction =
        r.`type` match
            case "emoji"        => r.emoji.fold(TelegramReaction.Other("emoji"))(TelegramReaction.Emoji(_))
            case "custom_emoji" => r.custom_emoji_id.fold(TelegramReaction.Other("custom_emoji"))(TelegramReaction.CustomEmoji(_))
            case "paid"         => TelegramReaction.Paid
            case other          => TelegramReaction.Other(other)

    private def instant(seconds: Long): Instant = Instant.of(seconds.seconds, Duration.Zero)

    // --- Names ---

    def updateType(name: String): TelegramUpdate.Type =
        import TelegramUpdate.Type
        name match
            case "message"             => Type.Message
            case "edited_message"      => Type.EditedMessage
            case "channel_post"        => Type.ChannelPost
            case "edited_channel_post" => Type.EditedChannelPost
            case "callback_query"      => Type.CallbackQuery
            case "my_chat_member"      => Type.MyChatMember
            case "chat_member"         => Type.ChatMember
            case "message_reaction"    => Type.MessageReaction
            case other                 => Type.Other(other)
        end match
    end updateType

    def updateTypeName(t: TelegramUpdate.Type): String =
        import TelegramUpdate.Type
        t match
            case Type.Message           => "message"
            case Type.EditedMessage     => "edited_message"
            case Type.ChannelPost       => "channel_post"
            case Type.EditedChannelPost => "edited_channel_post"
            case Type.CallbackQuery     => "callback_query"
            case Type.MyChatMember      => "my_chat_member"
            case Type.ChatMember        => "chat_member"
            case Type.MessageReaction   => "message_reaction"
            case Type.Other(name)       => name
        end match
    end updateTypeName

    def chatActionName(a: TelegramChatAction): String =
        import TelegramChatAction.*
        a match
            case Typing          => "typing"
            case UploadPhoto     => "upload_photo"
            case RecordVideo     => "record_video"
            case UploadVideo     => "upload_video"
            case RecordVoice     => "record_voice"
            case UploadVoice     => "upload_voice"
            case UploadDocument  => "upload_document"
            case ChooseSticker   => "choose_sticker"
            case FindLocation    => "find_location"
            case RecordVideoNote => "record_video_note"
            case UploadVideoNote => "upload_video_note"
        end match
    end chatActionName

    // ByteSize clamps a negative count to zero, which would claim an empty file; a count that cannot be a size is not known.
    private def size(bytes: Maybe[Long]): Maybe[ByteSize] = bytes.filter(_ >= 0).map(ByteSize.fromBytes)

    // --- Encoding ---

    import Structure.Value

    def str(s: String): Value            = Value.Str(s)
    def bool(b: Boolean): Value          = Value.Bool(b)
    def long(n: Long): Value             = Value.Integer(n)
    def record(fields: Params): Value    = Value.Record(fields)
    def seq(values: Chunk[Value]): Value = Value.Sequence(values)

    def target(t: TelegramChat.Target): Value =
        t match
            case TelegramChat.Target.Id(chat)       => long(chat.value)
            case TelegramChat.Target.Username(name) => str("@" + name)

    /** The fields of a text: `field` itself, and its parse mode or its entities. */
    def text(field: String, entitiesField: String, t: TelegramText): Params =
        t match
            case TelegramText.Plain(text, entities) =>
                Chunk(field -> str(text)) ++
                    (if entities.isEmpty then Chunk.empty else Chunk(entitiesField -> seq(entities.map(entityValue))))
            case TelegramText.MarkdownV2(markup) => Chunk(field -> str(Markup.markdownV2(markup)), "parse_mode" -> str("MarkdownV2"))
            case TelegramText.Html(markup)       => Chunk(field -> str(Markup.html(markup)), "parse_mode" -> str("HTML"))

    def entityValue(e: TelegramEntity): Value =
        import TelegramEntity.Kind
        val typed: (String, Params) = e.kind match
            case Kind.Mention              => ("mention", Chunk.empty)
            case Kind.Hashtag              => ("hashtag", Chunk.empty)
            case Kind.Cashtag              => ("cashtag", Chunk.empty)
            case Kind.BotCommand           => ("bot_command", Chunk.empty)
            case Kind.Url                  => ("url", Chunk.empty)
            case Kind.Email                => ("email", Chunk.empty)
            case Kind.PhoneNumber          => ("phone_number", Chunk.empty)
            case Kind.Bold                 => ("bold", Chunk.empty)
            case Kind.Italic               => ("italic", Chunk.empty)
            case Kind.Underline            => ("underline", Chunk.empty)
            case Kind.Strikethrough        => ("strikethrough", Chunk.empty)
            case Kind.Spoiler              => ("spoiler", Chunk.empty)
            case Kind.Blockquote           => ("blockquote", Chunk.empty)
            case Kind.ExpandableBlockquote => ("expandable_blockquote", Chunk.empty)
            case Kind.Code                 => ("code", Chunk.empty)
            case Kind.Pre(language)        => ("pre", language.fold(Chunk.empty)(l => Chunk("language" -> str(l))))
            case Kind.TextLink(url)        => ("text_link", Chunk("url" -> str(url.value)))
            case Kind.TextMention(u)       => ("text_mention", Chunk("user" -> userValue(u)))
            case Kind.CustomEmoji(id)      => ("custom_emoji", Chunk("custom_emoji_id" -> str(id)))
            case Kind.Other(other)         => (other, Chunk.empty)
        val (name, extra) = typed
        record(Chunk("type" -> str(name), "offset" -> long(e.offset.toLong), "length" -> long(e.length.toLong)) ++ extra)
    end entityValue

    private def userValue(u: TelegramUser): Value =
        record(
            Chunk("id" -> long(u.id.value), "is_bot" -> bool(u.isBot), "first_name" -> str(u.firstName)) ++
                u.lastName.fold(Chunk.empty)(v => Chunk("last_name" -> str(v))) ++
                u.username.fold(Chunk.empty)(v => Chunk("username" -> str(v)))
        )

    def keyboard(k: TelegramKeyboard): Value =
        k match
            case TelegramKeyboard.Inline(rows) =>
                record(Chunk("inline_keyboard" -> seq(rows.map(row => seq(row.map(inlineButton))))))
            case TelegramKeyboard.Reply(rows, resize, oneTime, persistent, placeholder) =>
                record(
                    Chunk(
                        "keyboard"          -> seq(rows.map(row => seq(row.map(replyButton)))),
                        "resize_keyboard"   -> bool(resize),
                        "one_time_keyboard" -> bool(oneTime),
                        "is_persistent"     -> bool(persistent)
                    ) ++ placeholder.fold(Chunk.empty)(p => Chunk("input_field_placeholder" -> str(p)))
                )
            case TelegramKeyboard.Remove                  => record(Chunk("remove_keyboard" -> bool(true)))
            case TelegramKeyboard.ForceReply(placeholder) =>
                record(Chunk("force_reply" -> bool(true)) ++ placeholder.fold(Chunk.empty)(p => Chunk("input_field_placeholder" -> str(p))))

    private def inlineButton(b: TelegramKeyboard.InlineButton): Value =
        b match
            case TelegramKeyboard.InlineButton.Callback(text, data) =>
                record(Chunk("text" -> str(text), "callback_data" -> str(data.value)))
            case TelegramKeyboard.InlineButton.Url(text, url) => record(Chunk("text" -> str(text), "url" -> str(url.value)))

    private def replyButton(b: TelegramKeyboard.ReplyButton): Value =
        b match
            case TelegramKeyboard.ReplyButton.Text(text)            => record(Chunk("text" -> str(text)))
            case TelegramKeyboard.ReplyButton.RequestContact(text)  => record(Chunk("text" -> str(text), "request_contact" -> bool(true)))
            case TelegramKeyboard.ReplyButton.RequestLocation(text) => record(Chunk("text" -> str(text), "request_location" -> bool(true)))

    def sendOptions(o: TelegramSendOptions): Params =
        o.thread.fold(Chunk.empty)(t => Chunk("message_thread_id" -> long(t.value.toLong))) ++
            o.replyTo.fold(Chunk.empty)(m => Chunk("reply_parameters" -> record(Chunk("message_id" -> long(m.value.toLong))))) ++
            o.keyboard.fold(Chunk.empty)(k => Chunk("reply_markup" -> keyboard(k))) ++
            (if o.silent then Chunk("disable_notification" -> bool(true)) else Chunk.empty) ++
            (if o.protect then Chunk("protect_content" -> bool(true)) else Chunk.empty)

    def linkPreview(enabled: Boolean): Params =
        if enabled then Chunk.empty else Chunk("link_preview_options" -> record(Chunk("is_disabled" -> bool(true))))

    def allowedUpdates(types: Chunk[TelegramUpdate.Type]): Value = seq(types.map(t => str(updateTypeName(t))))

    def reactionValue(r: TelegramReaction.Sendable): Params =
        r match
            case TelegramReaction.Emoji(emoji)    => Chunk("type" -> str("emoji"), "emoji" -> str(emoji))
            case TelegramReaction.CustomEmoji(id) => Chunk("type" -> str("custom_emoji"), "custom_emoji_id" -> str(id))
            case TelegramReaction.Paid            => Chunk("type" -> str("paid"))

    /** The two fields that name one message: its chat and its id. */
    def messageRef(chat: TelegramChat.Target, message: TelegramId.MessageId): Params =
        Chunk("chat_id" -> target(chat), "message_id" -> long(message.value.toLong))

    def commandScope(s: TelegramCommand.Scope): Value =
        import TelegramCommand.Scope
        s match
            case Scope.Default                  => record(Chunk("type" -> str("default")))
            case Scope.AllPrivateChats          => record(Chunk("type" -> str("all_private_chats")))
            case Scope.AllGroupChats            => record(Chunk("type" -> str("all_group_chats")))
            case Scope.AllChatAdministrators    => record(Chunk("type" -> str("all_chat_administrators")))
            case Scope.Chat(chat)               => record(Chunk("type" -> str("chat"), "chat_id" -> target(chat)))
            case Scope.ChatAdministrators(chat) =>
                record(Chunk("type" -> str("chat_administrators"), "chat_id" -> target(chat)))
            case Scope.ChatMember(chat, user) =>
                record(Chunk("type" -> str("chat_member"), "chat_id" -> target(chat), "user_id" -> long(user.value)))
        end match
    end commandScope

end WireCodec
