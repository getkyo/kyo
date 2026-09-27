package kyo.internal.telegram

import kyo.*

class WireCodecTest extends kyo.test.Test[Any]:

    private def value(json: String)(using Frame): Structure.Value =
        Json.decode[Structure.Value](json) match
            case Result.Success(v) => v
            case other             => throw new IllegalStateException(s"not JSON: $other")

    private def update(json: String)(using Frame): WireCodec.Decoded[TelegramUpdate] = WireCodec.decodeUpdate(value(json))

    /** A message update's content, or `Absent` for any other update. */
    private def contentOf(u: TelegramUpdate): Maybe[TelegramMessage.Content] =
        u match
            case TelegramUpdate.Message(_, m) => Present(m.content)
            case _                            => Absent

    private val at   = Instant.of(1700000000L.seconds, Duration.Zero)
    private val ann  = TelegramUser(TelegramId.UserId(5L), false, "Ann", username = Present("ann"), languageCode = Present("en"))
    private val dm   = TelegramChat(TelegramId.ChatId(5L), TelegramChat.Type.Private, firstName = Present("Ann"))
    private val team = TelegramChat(TelegramId.ChatId(-1001L), TelegramChat.Type.Supergroup, title = Present("Team"), isForum = true)

    private val annJson  = """{"id":5,"is_bot":false,"first_name":"Ann","username":"ann","language_code":"en"}"""
    private val dmJson   = """{"id":5,"type":"private","first_name":"Ann"}"""
    private val teamJson = """{"id":-1001,"type":"supergroup","title":"Team","is_forum":true}"""

    // --- Updates ---

    "a message update decodes every field the model reads" in {
        val json =
            s"""{"update_id":900,"message":{"message_id":7,"date":1700000000,"chat":$teamJson,"from":$annJson,"message_thread_id":3,""" +
                s""""edit_date":1700000060,"reply_to_message":{"message_id":6,"date":1700000000,"chat":$teamJson,"text":"earlier"},""" +
                s""""text":"/start now","entities":[{"type":"bot_command","offset":0,"length":6}]}}"""
        assert(update(json) == Result.succeed(TelegramUpdate.Message(
            TelegramId.UpdateId(900L),
            TelegramMessage(
                id = TelegramId.MessageId(7),
                chat = team,
                date = at,
                content = TelegramMessage.Content.Text("/start now", Chunk(TelegramEntity(TelegramEntity.Kind.BotCommand, 0, 6))),
                from = Present(ann),
                thread = Present(TelegramId.MessageThreadId(3)),
                editDate = Present(Instant.of(1700000060L.seconds, Duration.Zero)),
                replyTo = Present(TelegramMessage(
                    TelegramId.MessageId(6),
                    team,
                    at,
                    TelegramMessage.Content.Text("earlier", Chunk.empty)
                ))
            )
        )))
    }

    "a reply that does not decode makes its update unknown, with the whole update" in {
        val json = s"""{"update_id":901,"message":{"message_id":7,"date":1700000000,"chat":$teamJson,""" +
            s""""reply_to_message":{"message_id":6,"chat":$teamJson,"text":"no date"},"text":"x"}}"""
        assert(update(json) == Result.succeed(TelegramUpdate.Unknown(TelegramId.UpdateId(901L), "message", WireCodec.raw(value(json)))))
    }

    "edited messages, channel posts and edited channel posts decode to their kinds" in {
        val msg      = s"""{"message_id":1,"date":1700000000,"chat":$dmJson,"text":"x"}"""
        val expected = TelegramMessage(TelegramId.MessageId(1), dm, at, TelegramMessage.Content.Text("x", Chunk.empty))
        val kinds    = Chunk("edited_message", "channel_post", "edited_channel_post").map(k => update(s"""{"update_id":1,"$k":$msg}"""))
        assert(kinds == Chunk(
            Result.succeed(TelegramUpdate.EditedMessage(TelegramId.UpdateId(1L), expected)),
            Result.succeed(TelegramUpdate.ChannelPost(TelegramId.UpdateId(1L), expected)),
            Result.succeed(TelegramUpdate.EditedChannelPost(TelegramId.UpdateId(1L), expected))
        ))
    }

    "each media kind decodes with its caption and size, a negative size as unknown, and a location with its coordinates" in {
        def content(fields: String) =
            update(s"""{"update_id":1,"message":{"message_id":1,"date":1700000000,"chat":$dmJson,$fields}}""").map(contentOf)
        val caption = Present(TelegramMessage.Caption("cap", Chunk(TelegramEntity(TelegramEntity.Kind.Bold, 0, 3))))
        val capJson = """"caption":"cap","caption_entities":[{"type":"bold","offset":0,"length":3}]"""
        val fid     = TelegramId.FileId("F")
        val uid     = TelegramId.FileUniqueId("U")
        assert(Chunk(
            content(s""""photo":[{"file_id":"F","file_unique_id":"U","width":90,"height":60,"file_size":1000}],$capJson"""),
            content(
                s""""document":{"file_id":"F","file_unique_id":"U","file_name":"a.pdf","mime_type":"application/pdf","file_size":4},$capJson"""
            ),
            content(s""""audio":{"file_id":"F","file_unique_id":"U","duration":61,"performer":"P","title":"T","file_size":-1},$capJson"""),
            content(
                s""""video":{"file_id":"F","file_unique_id":"U","width":640,"height":480,"duration":5,"file_size":5000000000},$capJson"""
            ),
            content(s""""voice":{"file_id":"F","file_unique_id":"U","duration":2,"mime_type":"audio/ogg"},$capJson"""),
            content(""""location":{"latitude":51.5,"longitude":-0.25}""")
        ) == Chunk(
            Result.succeed(Present(TelegramMessage.Content.Photo(
                Chunk(TelegramMedia.PhotoSize(fid, uid, 90, 60, Present(1000.bytes))),
                caption
            ))),
            Result.succeed(Present(TelegramMessage.Content.Document(
                TelegramMedia.Document(fid, uid, Present("a.pdf"), Present("application/pdf"), Present(4.bytes)),
                caption
            ))),
            Result.succeed(Present(TelegramMessage.Content.Audio(
                TelegramMedia.Audio(fid, uid, 61.seconds, Present("P"), Present("T")),
                caption
            ))),
            Result.succeed(Present(TelegramMessage.Content.Video(
                TelegramMedia.Video(fid, uid, 640, 480, 5.seconds, fileSize = Present(5000000000L.bytes)),
                caption
            ))),
            Result.succeed(Present(TelegramMessage.Content.Voice(TelegramMedia.Voice(fid, uid, 2.seconds, Present("audio/ogg")), caption))),
            Result.succeed(Present(TelegramMessage.Content.Location(TelegramMedia.Location(51.5, -0.25))))
        ))
    }

    "a message with content the module does not model keeps the whole message" in {
        val msg    = s"""{"message_id":1,"date":1700000000,"chat":$dmJson,"sticker":{"file_id":"S"}}"""
        val result = update(s"""{"update_id":1,"message":$msg}""")
        assert(result == Result.succeed(TelegramUpdate.Message(
            TelegramId.UpdateId(1L),
            TelegramMessage(TelegramId.MessageId(1), dm, at, TelegramMessage.Content.Unknown(WireCodec.raw(value(msg))))
        )))
    }

    "every entity type decodes to its kind, and one the module does not know keeps its name" in {
        val types = Chunk(
            "mention",
            "hashtag",
            "cashtag",
            "bot_command",
            "url",
            "email",
            "phone_number",
            "bold",
            "italic",
            "underline",
            "strikethrough",
            "spoiler",
            "blockquote",
            "expandable_blockquote",
            "code",
            "date_time"
        )
        val simple = types.map(t => s"""{"type":"$t","offset":0,"length":1}""")
        val rich   = Chunk(
            """{"type":"pre","offset":0,"length":1,"language":"scala"}""",
            """{"type":"text_link","offset":0,"length":1,"url":"https://getkyo.io"}""",
            s"""{"type":"text_mention","offset":0,"length":1,"user":$annJson}""",
            """{"type":"custom_emoji","offset":0,"length":1,"custom_emoji_id":"E1"}"""
        )
        val json = s"""{"update_id":1,"message":{"message_id":1,"date":1700000000,"chat":$dmJson,"text":"x","entities":[${(simple ++
                rich).mkString(",")}]}}"""
        val kinds: WireCodec.Decoded[Maybe[TelegramMessage.Content]] = update(json).map(contentOf)
        import TelegramEntity.Kind
        val expected = Chunk[Kind](
            Kind.Mention,
            Kind.Hashtag,
            Kind.Cashtag,
            Kind.BotCommand,
            Kind.Url,
            Kind.Email,
            Kind.PhoneNumber,
            Kind.Bold,
            Kind.Italic,
            Kind.Underline,
            Kind.Strikethrough,
            Kind.Spoiler,
            Kind.Blockquote,
            Kind.ExpandableBlockquote,
            Kind.Code,
            Kind.Other("date_time"),
            Kind.Pre(Present("scala")),
            Kind.TextLink(TelegramUrl("https://getkyo.io")),
            Kind.TextMention(ann),
            Kind.CustomEmoji("E1")
        )
        assert(kinds == Result.succeed(Present(TelegramMessage.Content.Text("x", expected.map(TelegramEntity(_, 0, 1))))))
    }

    "an entity or a reaction lacking the field its type requires, or a text link whose URL is not a link, is kept as Other with its type" in {
        val entities = Chunk("text_link", "text_mention", "custom_emoji").map(t => s"""{"type":"$t","offset":0,"length":1}""") :+
            """{"type":"text_link","offset":0,"length":1,"url":"javascript:alert(1)"}"""
        val message = s"""{"update_id":1,"message":{"message_id":1,"date":1700000000,"chat":$dmJson,"text":"x","entities":[${entities
                .mkString(",")}]}}"""
        val reaction = s"""{"update_id":2,"message_reaction":{"chat":$dmJson,"message_id":1,"date":1700000000,""" +
            """"old_reaction":[{"type":"emoji"}],"new_reaction":[{"type":"custom_emoji"}]}}"""
        val content = update(message).map(contentOf)
        import TelegramEntity.Kind
        assert(content == Result.succeed(Present(TelegramMessage.Content.Text(
            "x",
            Chunk(Kind.Other("text_link"), Kind.Other("text_mention"), Kind.Other("custom_emoji"), Kind.Other("text_link"))
                .map(TelegramEntity(_, 0, 1))
        ))))
        assert(update(reaction) == Result.succeed(TelegramUpdate.MessageReaction(
            TelegramId.UpdateId(2L),
            TelegramReactionUpdate(
                dm,
                TelegramId.MessageId(1),
                at,
                Chunk(TelegramReaction.Other("emoji")),
                Chunk(TelegramReaction.Other("custom_emoji"))
            )
        )))
    }

    "a callback query decodes with its message, or with only where it was when the message is inaccessible" in {
        val accessible =
            s"""{"update_id":2,"callback_query":{"id":"q1","from":$annJson,"chat_instance":"ci","data":"yes",""" +
                s""""message":{"message_id":9,"date":1700000000,"chat":$dmJson,"text":"pick"}}}"""
        val inaccessible =
            s"""{"update_id":3,"callback_query":{"id":"q2","from":$annJson,"chat_instance":"ci","message":{"message_id":9,"date":0,"chat":$dmJson}}}"""
        val inline = s"""{"update_id":4,"callback_query":{"id":"q3","from":$annJson,"chat_instance":"ci","inline_message_id":"IM"}}"""
        assert(Chunk(update(accessible), update(inaccessible), update(inline)) == Chunk(
            Result.succeed(TelegramUpdate.CallbackQuery(
                TelegramId.UpdateId(2L),
                TelegramCallbackQuery(
                    TelegramId.CallbackQueryId("q1"),
                    ann,
                    "ci",
                    Present(TelegramCallbackQuery.Source.Accessible(
                        TelegramMessage(TelegramId.MessageId(9), dm, at, TelegramMessage.Content.Text("pick", Chunk.empty))
                    )),
                    data = Present("yes")
                )
            )),
            Result.succeed(TelegramUpdate.CallbackQuery(
                TelegramId.UpdateId(3L),
                TelegramCallbackQuery(
                    TelegramId.CallbackQueryId("q2"),
                    ann,
                    "ci",
                    Present(TelegramCallbackQuery.Source.Inaccessible(dm, TelegramId.MessageId(9)))
                )
            )),
            Result.succeed(TelegramUpdate.CallbackQuery(
                TelegramId.UpdateId(4L),
                TelegramCallbackQuery(TelegramId.CallbackQueryId("q3"), ann, "ci", inlineMessageId = Present("IM"))
            ))
        ))
    }

    "membership changes decode for the bot and for other members, with every status" in {
        def member(kind: String, old: String, now: String) =
            update(
                s"""{"update_id":5,"$kind":{"chat":$dmJson,"from":$annJson,"date":1700000000,""" +
                    s""""old_chat_member":{"status":"$old","user":$annJson},"new_chat_member":{"status":"$now","user":$annJson}}}"""
            )
        import TelegramChatMemberUpdate.Status
        def expected(old: Status, now: Status) = TelegramChatMemberUpdate(dm, ann, at, ann, old, now)
        val id                                 = TelegramId.UpdateId(5L)
        assert(Chunk(
            member("my_chat_member", "member", "kicked"),
            member("chat_member", "creator", "administrator"),
            member("chat_member", "restricted", "left"),
            member("chat_member", "left", "guest")
        ) == Chunk(
            Result.succeed(TelegramUpdate.MyChatMember(id, expected(Status.Member, Status.Kicked))),
            Result.succeed(TelegramUpdate.ChatMember(id, expected(Status.Creator, Status.Administrator))),
            Result.succeed(TelegramUpdate.ChatMember(id, expected(Status.Restricted, Status.Left))),
            Result.succeed(TelegramUpdate.ChatMember(id, expected(Status.Left, Status.Other("guest"))))
        ))
    }

    "a reaction update decodes every reaction type" in {
        val json =
            s"""{"update_id":6,"message_reaction":{"chat":$dmJson,"message_id":9,"date":1700000000,"user":$annJson,""" +
                """"old_reaction":[{"type":"emoji","emoji":"👍"}],""" +
                """"new_reaction":[{"type":"custom_emoji","custom_emoji_id":"E"},{"type":"paid"},{"type":"star"}]}}"""
        assert(update(json) == Result.succeed(TelegramUpdate.MessageReaction(
            TelegramId.UpdateId(6L),
            TelegramReactionUpdate(
                dm,
                TelegramId.MessageId(9),
                at,
                Chunk(TelegramReaction.Emoji("👍")),
                Chunk(TelegramReaction.CustomEmoji("E"), TelegramReaction.Paid, TelegramReaction.Other("star")),
                user = Present(ann)
            )
        )))
    }

    "an update of a kind the module does not model keeps the update, its id and its kind's name" in {
        val json =
            """{"update_id":7,"inline_query":{"id":"iq","from":{"id":5,"is_bot":false,"first_name":"Ann"},"query":"q","offset":""}}"""
        assert(update(json) == Result.succeed(TelegramUpdate.Unknown(TelegramId.UpdateId(7L), "inline_query", WireCodec.raw(value(json)))))
    }

    "a known kind lacking a field it requires keeps the update as unknown" in {
        val json = s"""{"update_id":8,"message":{"message_id":1,"chat":$dmJson,"text":"no date"}}"""
        assert(update(json) == Result.succeed(TelegramUpdate.Unknown(TelegramId.UpdateId(8L), "message", WireCodec.raw(value(json)))))
    }

    "an update without an id does not decode" in {
        assert(update("""{"message":{}}""") == Result.fail(MissingFieldException(Seq.empty, "update_id")))
    }

    "an update with no field naming its kind is refused" in {
        assert(update("""{"update_id":9}""") == Result.fail(WireCodec.Rejected(Chunk.empty)))
    }

    "the raw payload of an unknown case renders only its length" in {
        val raw = WireCodec.raw(value("""{"text":"a private message"}"""))
        assert(raw.value == """{"text":"a private message"}""")
        assert(raw.toString == "TelegramRawJson(28 characters)")
    }

    // --- Answers ---

    "a file, a webhook info and a user decode from their results" in {
        assert(WireCodec.file(value("""{"file_id":"F","file_unique_id":"U"}""")) ==
            Result.succeed(TelegramFile(TelegramId.FileId("F"), TelegramId.FileUniqueId("U"))))
        assert(WireCodec.webhookInfo(value(
            """{"url":"https://bot.example.com","has_custom_certificate":true,"pending_update_count":0,"ip_address":"1.2.3.4",""" +
                """"last_error_date":1700000000,"last_error_message":"Connection refused","max_connections":40,"allowed_updates":["message"]}"""
        )) == Result.succeed(TelegramWebhookInfo(
            Present(HttpUrl(Present("https"), "bot.example.com", 443, "/", Absent)),
            true,
            0,
            Present("1.2.3.4"),
            Present(at),
            Present("Connection refused"),
            Present(40),
            Chunk(TelegramUpdate.Type.Message)
        )))
        assert(WireCodec.userOf(value(annJson)) == Result.succeed(ann))
    }

    // --- Encoding ---

    "every entity kind encodes as Telegram names it" in {
        import TelegramEntity.Kind
        val kinds = Chunk[Kind](
            Kind.Mention,
            Kind.Hashtag,
            Kind.Cashtag,
            Kind.BotCommand,
            Kind.Url,
            Kind.Email,
            Kind.PhoneNumber,
            Kind.Bold,
            Kind.Italic,
            Kind.Underline,
            Kind.Strikethrough,
            Kind.Spoiler,
            Kind.Blockquote,
            Kind.ExpandableBlockquote,
            Kind.Code,
            Kind.Pre(Absent),
            Kind.Pre(Present("scala")),
            Kind.TextLink(TelegramUrl("https://getkyo.io")),
            Kind.TextMention(ann),
            Kind.CustomEmoji("E"),
            Kind.Other("date_time")
        )
        assert(kinds.map(k => Json.encode(WireCodec.entityValue(TelegramEntity(k, 1, 2)))) == Chunk(
            "mention",
            "hashtag",
            "cashtag",
            "bot_command",
            "url",
            "email",
            "phone_number",
            "bold",
            "italic",
            "underline",
            "strikethrough",
            "spoiler",
            "blockquote",
            "expandable_blockquote",
            "code",
            "pre"
        ).map(t => s"""{"type":"$t","offset":1,"length":2}""") ++ Chunk(
            """{"type":"pre","offset":1,"length":2,"language":"scala"}""",
            """{"type":"text_link","offset":1,"length":2,"url":"https://getkyo.io"}""",
            """{"type":"text_mention","offset":1,"length":2,"user":{"id":5,"is_bot":false,"first_name":"Ann","username":"ann"}}""",
            """{"type":"custom_emoji","offset":1,"length":2,"custom_emoji_id":"E"}""",
            """{"type":"date_time","offset":1,"length":2}"""
        ))
    }

    "every keyboard encodes as Telegram names it" in {
        import TelegramKeyboard.*
        val keyboards = Chunk[TelegramKeyboard](
            TelegramKeyboard.inline(
                Seq(InlineButton.callback("A", "a"), InlineButton.Url("B", TelegramUrl("https://b"))),
                Seq(InlineButton.callback("C", "c"))
            ),
            Reply(
                Chunk(Chunk(ReplyButton.Text("Hi"), ReplyButton.RequestContact("Phone"), ReplyButton.RequestLocation("Where"))),
                resize = false,
                oneTime = true,
                persistent = true,
                placeholder = Present("say")
            ),
            Remove,
            ForceReply(Present("reply"))
        )
        assert(keyboards.map(k => Json.encode(WireCodec.keyboard(k))) == Chunk(
            """{"inline_keyboard":[[{"text":"A","callback_data":"a"},{"text":"B","url":"https://b"}],[{"text":"C","callback_data":"c"}]]}""",
            """{"keyboard":[[{"text":"Hi"},{"text":"Phone","request_contact":true},{"text":"Where","request_location":true}]],""" +
                """"resize_keyboard":false,"one_time_keyboard":true,"is_persistent":true,"input_field_placeholder":"say"}""",
            """{"remove_keyboard":true}""",
            """{"force_reply":true,"input_field_placeholder":"reply"}"""
        ))
    }

    "every command scope encodes as Telegram names it" in {
        import TelegramCommand.Scope
        val chat   = TelegramChat.Target.Username("team")
        val scopes = Chunk[Scope](
            Scope.Default,
            Scope.AllPrivateChats,
            Scope.AllGroupChats,
            Scope.AllChatAdministrators,
            Scope.Chat(chat),
            Scope.ChatAdministrators(chat),
            Scope.ChatMember(chat, TelegramId.UserId(5L))
        )
        assert(scopes.map(s => Json.encode(WireCodec.commandScope(s))) == Chunk(
            """{"type":"default"}""",
            """{"type":"all_private_chats"}""",
            """{"type":"all_group_chats"}""",
            """{"type":"all_chat_administrators"}""",
            """{"type":"chat","chat_id":"@team"}""",
            """{"type":"chat_administrators","chat_id":"@team"}""",
            """{"type":"chat_member","chat_id":"@team","user_id":5}"""
        ))
    }

    "reactions and allowed updates encode by their wire names" in {
        assert(Chunk[TelegramReaction.Sendable](TelegramReaction.Emoji("👍"), TelegramReaction.CustomEmoji("E"), TelegramReaction.Paid).map(
            r =>
                Json.encode(WireCodec.record(WireCodec.reactionValue(r)))
        ) == Chunk("""{"type":"emoji","emoji":"👍"}""", """{"type":"custom_emoji","custom_emoji_id":"E"}""", """{"type":"paid"}"""))
        val types = Chunk(
            TelegramUpdate.Type.Message,
            TelegramUpdate.Type.EditedMessage,
            TelegramUpdate.Type.ChannelPost,
            TelegramUpdate.Type.EditedChannelPost,
            TelegramUpdate.Type.CallbackQuery,
            TelegramUpdate.Type.MyChatMember,
            TelegramUpdate.Type.ChatMember,
            TelegramUpdate.Type.MessageReaction,
            TelegramUpdate.Type.Other("poll")
        )
        assert(
            Json.encode(WireCodec.allowedUpdates(types)) ==
                """["message","edited_message","channel_post","edited_channel_post","callback_query","my_chat_member","chat_member","message_reaction","poll"]"""
        )
        assert(types.map(t => WireCodec.updateType(WireCodec.updateTypeName(t))) == types)
    }

    "every chat action encodes by its wire name" in {
        assert(Chunk.from(TelegramChatAction.values).map(WireCodec.chatActionName) == Chunk(
            "typing",
            "upload_photo",
            "record_video",
            "upload_video",
            "record_voice",
            "upload_voice",
            "upload_document",
            "choose_sticker",
            "find_location",
            "record_video_note",
            "upload_video_note"
        ))
    }

end WireCodecTest
