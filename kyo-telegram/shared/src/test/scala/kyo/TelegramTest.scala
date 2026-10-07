package kyo

import TelegramTest.*
import kyo.internal.charset.Utf8

class TelegramTest extends kyo.test.Test[Any]:

    private val chat = Telegram.Chat.Target(Telegram.ChatId(-100200300L))

    private val sentMessage =
        """{"message_id":42,"date":1700000000,"chat":{"id":-100200300,"type":"supergroup","title":"Team"},"text":"hi"}"""

    private val sentModel = Telegram.Message(
        id = Telegram.MessageId(42),
        chat = Telegram.Chat(Telegram.ChatId(-100200300L), Telegram.Chat.Type.Supergroup, title = Present("Team")),
        date = Instant.of(1700000000L.seconds, Duration.Zero),
        content = Telegram.Message.Content.Text("hi", Chunk.empty)
    )

    private val me = """{"id":7,"is_bot":true,"first_name":"Kyo"}"""

    // --- The request path ---

    "every call is a POST of a JSON body to /bot{token}/{method}" in {
        withLocal { local =>
            local.reply("getMe", ok("""{"id":7,"is_bot":true,"first_name":"Kyo","username":"kyo_bot"}""")).andThen {
                local.api(Telegram.me).map { me =>
                    local.seen.map { seen =>
                        assert(me == Telegram.User(Telegram.UserId(7L), true, "Kyo", username = Present("kyo_bot")))
                        assert(seen == Chunk(Seen(s"bot${local.token.value}/getMe", Present("application/json"), "{}")))
                    }
                }
            }
        }
    }

    "a base URL with a path puts the Bot API's paths under it" in {
        withLocal { local =>
            local.reply("getMe", ok(me)).andThen {
                Telegram.run(local.config.copy(baseUrl = local.base.copy(path = "/proxy/telegram")))(Telegram.me).andThen {
                    local.seen.map(seen => assert(seen.map(_.path) == Chunk(s"proxy/telegram/bot${local.token.value}/getMe")))
                }
            }
        }
    }

    "an answer that starts with a byte order mark decodes, for a JSON call" in {
        withCountingPeer(okResponseWithBom(me)) { (port, _) =>
            Telegram.run(configAt(port))(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                assert(result == Result.succeed(Telegram.User(Telegram.UserId(7L), true, "Kyo")), s"got: $result")
            }
        }
    }

    "an answer that starts with a byte order mark decodes, for a multipart upload" in {
        withCountingPeer(okResponseWithBom(sentMessage)) { (port, _) =>
            Telegram.run(configAt(port))(Abort.run[TelegramSendFailure](Telegram.send(chat, upload))).map { result =>
                assert(result == Result.succeed(sentModel), s"got: $result")
            }
        }
    }

    // --- The client ---

    "run closes its client's HTTP client when its region ends" in {
        withLocal { local =>
            Telegram.run(local.config)(Env.get[Telegram]).map { telegram =>
                // Unsafe: whether a pool is closed has no safe accessor; this reads the flag once, after the region ended.
                import AllowUnsafe.embrace.danger
                assert(telegram.http.isPoolClosed)
            }
        }
    }

    "init's client is open in its Scope and closed when the Scope ends" in {
        withLocal { local =>
            // Unsafe: whether a pool is closed has no safe accessor; each read is of the flag alone.
            import AllowUnsafe.embrace.danger
            Scope.run(Telegram.init(local.config).map(telegram => (telegram.http.isPoolClosed, telegram))).map { (closedInside, telegram) =>
                assert((closedInside, telegram.http.isPoolClosed) == (false, true))
            }
        }
    }

    "run(client) provides that client and leaves it open, and close closes it, twice without failing" in {
        withLocal { local =>
            // Unsafe: whether a pool is closed has no safe accessor; each read is of the flag alone.
            import AllowUnsafe.embrace.danger
            local.reply("getMe", ok(me)).andThen {
                Telegram.initUnscoped(local.config).map { telegram =>
                    for
                        provided <- Telegram.run(telegram)(Telegram.me.andThen(Env.get[Telegram]))
                        openAfter = telegram.http.isPoolClosed
                        _ <- Telegram.close(telegram)
                        _ <- Telegram.close(telegram)
                    yield assert((provided eq telegram, openAfter, telegram.http.isPoolClosed) == (true, false, true))
                }
            }
        }
    }

    "a caller's client filter, installed by withConfig or on a client bound with let, sees no request of the module" in {
        withLocal { local =>
            AtomicRef.init(Chunk.empty[String]).map { filtered =>
                val recording = recorder(filtered)
                local.reply("getMe", ok(me)).andThen {
                    HttpClient.withConfig(_.filter(recording))(local.api(Telegram.me)).andThen {
                        HttpClient.init().map { callerClient =>
                            HttpClient.let(callerClient)(HttpClient.withConfig(_.filter(recording).tls(HttpTlsConfig(trustAll = true)))(
                                local.api(Telegram.me)
                            ))
                        }
                    }.andThen {
                        filtered.get.map(paths => local.seen.map(seen => assert((paths, seen.size) == (Chunk.empty[String], 2))))
                    }
                }
            }
        }
    }

    "a connection the caller opened to the same server is not reused for a request of the module" in {
        withCountingPeer(okResponse(me)) { (port, accepted) =>
            val url = HttpUrl(Present("http"), "127.0.0.1", port, "/", Absent)
            HttpClient.init().map { callerClient =>
                HttpClient.let(callerClient)(HttpClient.withConfig(_.tls(HttpTlsConfig(trustAll = true)))(
                    HttpClient.getText(url.copy(path = "/caller"))
                )).andThen {
                    Telegram.run(TelegramConfig.init(
                        Telegram.Token.init("123456:TEST-token_local").getOrThrow,
                        baseUrl = url
                    ).getOrThrow)(Telegram.me)
                }.andThen(accepted.get.map(n => assert(n == 2)))
            }
        }
    }

    // --- Messages ---

    "send text posts chat_id and text and answers the message Telegram created" in {
        withLocal { local =>
            local.reply("sendMessage", ok(sentMessage)).andThen {
                local.api(Telegram.send(chat, Telegram.Content.text("hi"))).map { message =>
                    local.bodies("sendMessage").map { bodies =>
                        assert(message == sentModel)
                        assert(bodies == Chunk("""{"chat_id":-100200300,"text":"hi"}"""))
                    }
                }
            }
        }
    }

    "send to a username targets @username" in {
        withLocal { local =>
            local.reply("sendMessage", ok(sentMessage)).andThen {
                local.api(Telegram.send(Telegram.Chat.Target.Username("kyo_channel"), Telegram.Content.text("hi"))).andThen {
                    local.bodies("sendMessage").map(bodies => assert(bodies == Chunk("""{"chat_id":"@kyo_channel","text":"hi"}""")))
                }
            }
        }
    }

    "send with entities, no link preview and every option encodes each as Telegram names it" in {
        val text =
            Telegram.Text.Plain(
                "see kyo",
                Chunk(Telegram.Entity(Telegram.Entity.Kind.TextLink(Telegram.Url.init("https://getkyo.io").getOrThrow), 4, 3))
            )
        val options = Telegram.SendOptions(
            thread = Present(Telegram.MessageThreadId(9)),
            replyTo = Present(Telegram.MessageId(5)),
            keyboard = Present(Telegram.Keyboard.inline(Seq(Telegram.Keyboard.InlineButton.callback("Yes", "y").getOrThrow))),
            silent = true,
            protect = true
        )
        withLocal { local =>
            local.reply("sendMessage", ok(sentMessage)).andThen {
                local.api(Telegram.send(chat, Telegram.Content.Text(text, linkPreview = false), options)).andThen {
                    local.bodies("sendMessage").map { bodies =>
                        assert(bodies == Chunk(
                            """{"chat_id":-100200300,"text":"see kyo","entities":[{"type":"text_link","url":"https://getkyo.io","offset":4,"length":3}],""" +
                                """"link_preview_options":{"is_disabled":true},"message_thread_id":9,"reply_parameters":{"message_id":5},""" +
                                """"reply_markup":{"inline_keyboard":[[{"text":"Yes","callback_data":"y"}]]},"disable_notification":true,"protect_content":true}"""
                        ))
                    }
                }
            }
        }
    }

    "setCommands without a scope or a language sends neither, leaving Telegram's defaults" in {
        val menu = Telegram.Command.Menu.init(
            Telegram.Command.init("start", "Start the bot").getOrThrow,
            Telegram.Command.init("help", "Show help").getOrThrow
        ).getOrThrow
        withLocal { local =>
            local.reply("setMyCommands", ok("true")).andThen {
                local.api(Telegram.setCommands(menu)).andThen {
                    local.bodies("setMyCommands").map { bodies =>
                        assert(bodies == Chunk(
                            """{"commands":[{"command":"start","description":"Start the bot"},{"command":"help","description":"Show help"}]}"""
                        ))
                    }
                }
            }
        }
    }

    "send MarkdownV2 and HTML render the markup escaped and name the parse mode" in {
        val markup = Telegram.Markup.of(Telegram.Markup.Bold(Telegram.Markup.Text("1+1=2!")), Telegram.Markup.Text(" <ok>"))
        withLocal { local =>
            local.reply("sendMessage", ok(sentMessage)).andThen {
                local.api {
                    Telegram.send(chat, Telegram.Content.Text(Telegram.Text.MarkdownV2(markup)))
                        .andThen(Telegram.send(chat, Telegram.Content.Text(Telegram.Text.Html(markup))))
                }.andThen {
                    local.bodies("sendMessage").map { bodies =>
                        assert(bodies == Chunk(
                            """{"chat_id":-100200300,"text":"*1\\+1\\=2\\!* <ok\\>","parse_mode":"MarkdownV2"}""",
                            """{"chat_id":-100200300,"text":"<b>1+1=2!</b> &lt;ok&gt;","parse_mode":"HTML"}"""
                        ))
                    }
                }
            }
        }
    }

    "send media by id and by URL posts the file field and the caption" in {
        withLocal { local =>
            local.reply("sendPhoto", ok(sentMessage)).andThen(local.reply("sendDocument", ok(sentMessage))).andThen {
                local.api {
                    Telegram.send(
                        chat,
                        Telegram.Content.Photo(Telegram.InputFile.Id(Telegram.FileId("AgAD")), Present(Telegram.Text("cap")))
                    )
                        .andThen(Telegram.send(
                            chat,
                            Telegram.Content.Document(Telegram.InputFile.Url(HttpUrl.parse("https://example.com/a.pdf").getOrThrow))
                        ))
                }.andThen {
                    local.bodies("sendPhoto").map { photos =>
                        local.bodies("sendDocument").map { documents =>
                            assert(photos == Chunk("""{"chat_id":-100200300,"photo":"AgAD","caption":"cap"}"""))
                            assert(documents == Chunk("""{"chat_id":-100200300,"document":"https://example.com/a.pdf"}"""))
                        }
                    }
                }
            }
        }
    }

    "send an upload posts multipart form data with the parameters and the file" in {
        val bytes = Utf8.encode("PDFBYTES")
        withLocal { local =>
            local.reply("sendDocument", ok(sentMessage)).andThen {
                Random.withSeed(7)(local.api {
                    Telegram.send(
                        chat,
                        Telegram.Content.Document(
                            Telegram.InputFile.Upload("r.pdf", bytes, Present("application/pdf")),
                            Present(Telegram.Text("q"))
                        )
                    )
                }).andThen(Random.withSeed(7)(Random.nextStringAlphanumeric(32))).map { drawn =>
                    local.seen.map { seen =>
                        val boundary = s"kyo-telegram-$drawn"
                        assert(seen.map(_.contentType) == Chunk(Present(s"multipart/form-data; boundary=$boundary")))
                        assert(seen.map(_.body) == Chunk(
                            s"--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n-100200300\r\n" +
                                s"--$boundary\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\nq\r\n" +
                                s"--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"r.pdf\"\r\nContent-Type: application/pdf\r\n\r\nPDFBYTES\r\n" +
                                s"--$boundary--\r\n"
                        ))
                    }
                }
            }
        }
    }

    "send a location posts its coordinates" in {
        withLocal { local =>
            local.reply("sendLocation", ok(sentMessage)).andThen {
                local.api(Telegram.send(chat, Telegram.Content.Location(51.5, -0.25))).andThen {
                    local.bodies("sendLocation").map(b =>
                        assert(b == Chunk("""{"chat_id":-100200300,"latitude":51.5,"longitude":-0.25}"""))
                    )
                }
            }
        }
    }

    "edit maps text, caption and keyboard to their methods, and an absent keyboard sends none" in {
        val keyboard =
            Telegram.Keyboard.inline(Seq(Telegram.Keyboard.InlineButton.Url("Docs", Telegram.Url.init("https://getkyo.io").getOrThrow)))
        val message = Telegram.MessageId(42)
        withLocal { local =>
            Kyo.foreachDiscard(Chunk("editMessageText", "editMessageCaption", "editMessageReplyMarkup"))(m =>
                local.reply(m, ok(sentMessage))
            )
                .andThen {
                    local.api {
                        Telegram.edit(chat, message, Telegram.Edit.Text(Telegram.Text("new"), Present(keyboard)))
                            .andThen(Telegram.edit(chat, message, Telegram.Edit.Caption(Present(Telegram.Text("c")))))
                            .andThen(Telegram.edit(chat, message, Telegram.Edit.Keyboard(Absent)))
                    }
                }.andThen {
                    local.seen.map { seen =>
                        assert(seen.map(s => s.method -> s.body) == Chunk(
                            "editMessageText" ->
                                """{"chat_id":-100200300,"message_id":42,"text":"new","reply_markup":{"inline_keyboard":[[{"text":"Docs","url":"https://getkyo.io"}]]}}""",
                            "editMessageCaption"     -> """{"chat_id":-100200300,"message_id":42,"caption":"c"}""",
                            "editMessageReplyMarkup" -> """{"chat_id":-100200300,"message_id":42}"""
                        ))
                    }
                }
        }
    }

    "the methods that return True post their parameters and accept true" in {
        withLocal { local =>
            val methods = Chunk(
                "deleteMessage",
                "answerCallbackQuery",
                "sendChatAction",
                "setMessageReaction",
                "setMyCommands",
                "setWebhook",
                "deleteWebhook"
            )
            Kyo.foreachDiscard(methods)(m => local.reply(m, ok("true"))).andThen {
                local.api {
                    Telegram.delete(chat, Telegram.MessageId(42))
                        .andThen(Telegram.answerCallback(
                            Telegram.CallbackQueryId("q1"),
                            Telegram.CallbackAnswer.init(
                                Present("done"),
                                showAlert = true,
                                url = Present(Telegram.Url.init("tg://user?id=8").getOrThrow),
                                cacheTime = Present(5.seconds)
                            ).getOrThrow
                        ))
                        .andThen(Telegram.sendChatAction(chat, Telegram.ChatAction.Typing, Present(Telegram.MessageThreadId(3))))
                        .andThen(Telegram.setReaction(
                            chat,
                            Telegram.MessageId(42),
                            Seq(Telegram.Reaction.Emoji("👍"), Telegram.Reaction.CustomEmoji("e1")),
                            big = true
                        ))
                        .andThen(Telegram.setCommands(
                            Telegram.Command.Menu.init(Telegram.Command.init("start", "Start the bot").getOrThrow).getOrThrow,
                            Present(Telegram.Command.Scope.ChatMember(chat, Telegram.UserId(8L))),
                            Present("en")
                        ))
                        .andThen(Telegram.setWebhook(
                            HttpUrl(Present("https"), "bot.example.com", 443, "/tg", Absent),
                            TelegramTest.webhook("s3cret"),
                            Telegram.WebhookOptions.init(
                                Present(Chunk(Telegram.Update.Type.Message)),
                                Present(10),
                                dropPendingUpdates = true
                            ).getOrThrow
                        ))
                        .andThen(Telegram.deleteWebhook(dropPendingUpdates = true))
                }.andThen {
                    local.seen.map { seen =>
                        assert(seen.map(s => s.method -> s.body) == Chunk(
                            "deleteMessage"       -> """{"chat_id":-100200300,"message_id":42}""",
                            "answerCallbackQuery" ->
                                """{"callback_query_id":"q1","text":"done","show_alert":true,"url":"tg://user?id=8","cache_time":5}""",
                            "sendChatAction"     -> """{"chat_id":-100200300,"action":"typing","message_thread_id":3}""",
                            "setMessageReaction" ->
                                """{"chat_id":-100200300,"message_id":42,"reaction":[{"type":"emoji","emoji":"👍"},{"type":"custom_emoji","custom_emoji_id":"e1"}],"is_big":true}""",
                            "setMyCommands" ->
                                """{"commands":[{"command":"start","description":"Start the bot"}],"scope":{"type":"chat_member","chat_id":-100200300,"user_id":8},"language_code":"en"}""",
                            "setWebhook" ->
                                """{"url":"https://bot.example.com/tg","secret_token":"s3cret","allowed_updates":["message"],"max_connections":10,"drop_pending_updates":true}""",
                            "deleteWebhook" -> """{"drop_pending_updates":true}"""
                        ))
                    }
                }
            }
        }
    }

    "download refuses a file path that could move the request, sending nothing and copying no part of it" in {
        val paths = Chunk(
            "http://evil.example/x",
            "/etc/passwd",
            "documents/../bot",
            "..",
            "documents//file",
            "documents/file?x=1",
            "documents/file#top",
            "documents/my file.pdf",
            "documents/café.pdf",
            ""
        )
        withLocal { local =>
            local.api {
                Kyo.foreach(paths) { path =>
                    Abort.run[TelegramDownloadFailure](
                        Telegram.download(Telegram.File(Telegram.FileId("F"), Telegram.FileUniqueId("U"), path = Present(path)))
                    )
                }
            }.map { results =>
                local.seen.map { seen =>
                    assert(results.map(r => (r.isSuccess, r.failure)) ==
                        Chunk.fill(paths.size)((false, Present(TelegramRefusedUrlException("download")))))
                    assert(seen == Chunk.empty)
                }
            }
        }
    }

    "setWebhook refuses a URL that is not absolute http or https, sending nothing" in {
        val urls = Chunk(
            HttpUrl.fromUri("/hook"),
            HttpUrl.fromUri("hooks/x"),
            HttpUrl(Present("ftp"), "bot.example.com", 21, "/hook", Absent),
            HttpUrl(Present("wss"), "bot.example.com", 443, "/hook", Absent),
            HttpUrl(Present("https"), "", 443, "/hook", Absent),
            HttpUrl(Present("http"), "localhost", 80, "/hook", Absent, Present("/tmp/bot.sock"))
        )
        val webhook = TelegramTest.webhook("s3cret")
        withLocal { local =>
            local.api(Kyo.foreach(urls)(u => Abort.run[TelegramSetWebhookFailure](Telegram.setWebhook(u, webhook)))).map { results =>
                local.seen.map { seen =>
                    assert(results == Chunk.fill(urls.size)(Result.fail(TelegramRefusedUrlException("setWebhook"))))
                    assert(seen == Chunk.empty)
                }
            }
        }
    }

    "file answers the file and download fetches /file/bot{token}/{path}" in {
        withLocal { local =>
            local.reply(
                "getFile",
                ok("""{"file_id":"F1","file_unique_id":"U1","file_size":8,"file_path":"documents/file_1.pdf"}""")
            ).andThen {
                local.api(Telegram.file(Telegram.FileId("F1")).map(file => Telegram.download(file).map(file -> _))).map {
                    (file, bytes) =>
                        local.seen.map { seen =>
                            assert(file == Telegram.File(
                                Telegram.FileId("F1"),
                                Telegram.FileUniqueId("U1"),
                                Present(8.bytes),
                                Present("documents/file_1.pdf")
                            ))
                            assert(Utf8.decode(bytes) == "file:documents/file_1.pdf")
                            assert(seen.map(_.path) ==
                                Chunk(s"bot${local.token.value}/getFile", s"file/bot${local.token.value}/documents/file_1.pdf"))
                        }
                }
            }
        }
    }

    "webhookInfo reads an empty url as no webhook, and a set one as its URL" in {
        withLocal { local =>
            local.reply(
                "getWebhookInfo",
                ok("""{"url":"","has_custom_certificate":false,"pending_update_count":3,"allowed_updates":["message","poll"]}"""),
                ok(
                    """{"url":"https://bot.example.com/tg","has_custom_certificate":false,"pending_update_count":0,"last_error_date":1700000000,"last_error_message":"Connection refused"}"""
                )
            ).andThen {
                local.api(Telegram.webhookInfo.map(first => Telegram.webhookInfo.map(first -> _))).map { (none, set) =>
                    assert(none == Telegram.WebhookInfo(
                        Absent,
                        false,
                        3,
                        allowedUpdates = Chunk(Telegram.Update.Type.Message, Telegram.Update.Type.Other("poll"))
                    ))
                    assert(set == Telegram.WebhookInfo(
                        Present(HttpUrl(Present("https"), "bot.example.com", 443, "/tg", Absent)),
                        false,
                        0,
                        lastDeliveryFailureDate = Present(Instant.of(1700000000L.seconds, Duration.Zero)),
                        lastDeliveryFailureMessage = Present("Connection refused")
                    ))
                }
            }
        }
    }

    "webhookInfo reads any url that parses, since nothing is sent to it, and fails at url on one that does not" in {
        withLocal { local =>
            val info = (url: String) => ok(s"""{"url":"$url","has_custom_certificate":false,"pending_update_count":0}""")
            local.reply("getWebhookInfo", info("http://10.0.0.1:8443/hooks/x"), info("bad://x y")).andThen {
                local.api(Kyo.fill(2)(Abort.run[TelegramWebhookInfoFailure](Telegram.webhookInfo.map(_.url)))).map { results =>
                    assert(
                        results == Chunk(
                            Result.succeed(Present(HttpUrl(Present("http"), "10.0.0.1", 8443, "/hooks/x", Absent))),
                            Result.fail(TelegramDecodeException(
                                "getWebhookInfo",
                                TelegramDecodeException.Part.Result,
                                TelegramDecodeException.Failure.ConstructorRejected,
                                Chunk("url"),
                                Absent
                            ))
                        ),
                        s"got: $results"
                    )
                }
            }
        }
    }

    "custom posts the encoded parameters and decodes the result" in {
        withLocal { local =>
            local.reply("getChatMemberCount", ok("12")).andThen {
                local.api(Telegram.custom[ChatParam, Int](Telegram.Method.init("getChatMemberCount").getOrThrow, ChatParam(-5L))).map {
                    count =>
                        local.bodies("getChatMemberCount").map { bodies =>
                            assert(count == 12)
                            assert(bodies == Chunk("""{"chat_id":-5}"""))
                        }
                }
            }
        }
    }

    // --- Failure answers ---

    "a 429 carries Telegram's retry_after, and the header when the body has none" in {
        withLocal { local =>
            local.reply(
                "getMe",
                Reply(
                    HttpStatus(429),
                    """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 7","parameters":{"retry_after":7}}"""
                ),
                Reply(HttpStatus(429), "slow down", Seq("Retry-After" -> "3")),
                Reply(HttpStatus(429), "slow down")
            ).andThen {
                local.api(Kyo.fill(3)(Abort.run[TelegramMeFailure](Telegram.me))).map { results =>
                    assert(results == Chunk(
                        Result.fail(TelegramRateLimitException("getMe", Present(7.seconds))),
                        Result.fail(TelegramRateLimitException("getMe", Present(3.seconds))),
                        Result.fail(TelegramRateLimitException("getMe", Absent))
                    ))
                }
            }
        }
    }

    "with config.retry, a flood-control answer is sent again and the second attempt's answer is the verb's" in {
        withLocal { local =>
            local.reply("getMe", flood(0), ok(me)).andThen {
                val config = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(3)))
                Telegram.run(config)(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                    local.bodies("getMe").map { bodies =>
                        assert(result.isSuccess)
                        assert(bodies.size == 2)
                    }
                }
            }
        }
    }

    "with config.retry, an answer without retry_after is not sent again" in {
        withLocal { local =>
            local.reply(
                "getMe",
                Reply(HttpStatus(429), "slow down", Seq("Retry-After" -> "0")),
                Reply(HttpStatus.BadRequest, """{"ok":false,"error_code":400,"description":"Bad Request: x"}"""),
                Reply(HttpStatus(500), """{"ok":false,"error_code":500,"description":"Internal Server Error"}"""),
                ok(me)
            ).andThen {
                val config = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(3)))
                Telegram.run(config)(Kyo.fill(3)(Abort.run[TelegramMeFailure](Telegram.me))).map { results =>
                    local.bodies("getMe").map { bodies =>
                        assert(results == Chunk(
                            Result.fail(TelegramRateLimitException("getMe", Present(Duration.Zero))),
                            Result.fail(TelegramOtherApiException("getMe", 400, "Bad Request: x")),
                            Result.fail(TelegramOtherApiException("getMe", 500, "Internal Server Error"))
                        ))
                        assert(bodies.size == 3)
                    }
                }
            }
        }
    }

    "without config.retry, a flood-control answer is the verb's failure and nothing is sent again" in {
        withLocal { local =>
            local.reply("getMe", flood(0), ok(me)).andThen {
                local.api(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                    local.bodies("getMe").map { bodies =>
                        assert(result == Result.fail(TelegramRateLimitException("getMe", Present(Duration.Zero))))
                        assert(bodies.size == 1)
                    }
                }
            }
        }
    }

    "with config.retry, the attempts stop when the schedule does, and the last answer is the failure" in {
        withLocal { local =>
            local.reply("getMe", flood(0)).andThen {
                val config = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(2)))
                Telegram.run(config)(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                    local.bodies("getMe").map { bodies =>
                        assert(result == Result.fail(TelegramRateLimitException("getMe", Present(Duration.Zero))))
                        assert(bodies.size == 3)
                    }
                }
            }
        }
    }

    "with config.retry, the second attempt is sent only once retry_after has passed" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Gate.init.map { second =>
                    local.reply("getMe", flood(5), ok(me).gated(second)).andThen {
                        val config = local.config.copy(
                            retry = Present(Schedule.fixed(Duration.Zero).take(3)),
                            requestTimeout = 1.hour,
                            connectTimeout = 1.hour
                        )
                        for
                            fiber <- Fiber.initUnscoped(Telegram.run(config)(Abort.run[TelegramMeFailure](Telegram.me)))
                            // The request and connect deadlines share the clock, so only a fence on the flood wait's own duration
                            // knows the wait is armed before time moves.
                            _      <- control.awaitPendingSleeper(5.seconds)
                            parked <- Clock.now
                            _      <- control.advance(5.seconds.minusOrZero(1.milli))
                            early  <- second.arrived.pending
                            _      <- control.advance(1.milli)
                            _      <- second.arrived.await
                            sent   <- Clock.now
                            _      <- second.open.release
                            result <- fiber.get
                            bodies <- local.bodies("getMe")
                        yield
                            assert(early == 1, "the second attempt was sent before retry_after passed")
                            assert(sent == parked + 5.seconds, s"sent at $sent, parked at $parked")
                            assert(result.isSuccess)
                            assert(bodies.size == 2)
                        end for
                    }
                }
            }
        }
    }

    "with config.retry, a retry_after over retryMaxDelay is not waited: the call fails with it at once" in {
        withLocal { local =>
            local.reply("getMe", flood(61), ok(me)).andThen {
                val config = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(3)))
                Telegram.run(config)(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                    local.bodies("getMe").map { bodies =>
                        assert(result == Result.fail(TelegramRateLimitException("getMe", Present(61.seconds))))
                        assert(bodies.size == 1)
                    }
                }
            }
        }
    }

    "an ok:false answer without error_code or description is the envelope's decode failure at that field on a 2xx, and an unexpected status otherwise" in {
        withLocal { local =>
            local.reply(
                "getMe",
                Reply(HttpStatus.OK, """{"ok":false,"description":"x"}"""),
                Reply(HttpStatus.OK, """{"ok":false,"error_code":400}"""),
                Reply(HttpStatus.BadRequest, """{"ok":false,"description":"x"}""")
            ).andThen {
                local.api(Kyo.fill(3)(Abort.run[TelegramMeFailure](Telegram.me))).map { results =>
                    def at(field: String) = Result.fail(TelegramDecodeException(
                        "getMe",
                        TelegramDecodeException.Part.Envelope,
                        TelegramDecodeException.Failure.ConstructorRejected,
                        Chunk(field),
                        Absent
                    ))
                    assert(results == Chunk(
                        at("error_code"),
                        at("description"),
                        Result.fail(TelegramUnexpectedStatusException("getMe", HttpStatus.BadRequest))
                    ))
                }
            }
        }
    }

    "a description that echoes the request path holds the token redacted" in {
        withLocal { local =>
            val echoed = s"Not Found: /bot${local.token.value}/getMe"
            local.reply("getMe", Reply(HttpStatus(404), s"""{"ok":false,"error_code":404,"description":"$echoed"}""")).andThen {
                local.api(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                    assert(result == Result.fail(TelegramOtherApiException("getMe", 404, "Not Found: /bot<redacted>/getMe")))
                }
            }
        }
    }

    "Retry-After accepts ASCII digits with optional white space and nothing else" in {
        import kyo.internal.telegram.BotApi.parseRetryAfter
        assert(
            Chunk(" 12\t", "0", "999999999", "1000000000", "", "1.5", "-1", "١", "Wed, 21 Oct 2015 07:28:00 GMT").map(parseRetryAfter) ==
                Chunk(
                    Present(12.seconds),
                    Present(Duration.Zero),
                    Present(999999999.seconds),
                    Absent,
                    Absent,
                    Absent,
                    Absent,
                    Absent,
                    Absent
                )
        )
    }

    // --- The transport ---

    "a request with no answer within requestTimeout fails as the transport's timeout, to the server's host and port" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Fiber.initUnscoped(local.api(Abort.run[TelegramMeFailure](Telegram.me))).map { fiber =>
                    local.pollStarted.await.andThen(control.advance(local.config.requestTimeout)).andThen(fiber.get).map {
                        result =>
                            assert(result == Result.fail(TelegramTransportException(
                                "getMe",
                                TelegramTransportException.Kind.Timeout,
                                "127.0.0.1",
                                local.base.port,
                                Present(local.config.requestTimeout)
                            )()))
                    }
                }
            }
        }
    }

    "a body over maxResponseLength fails as the transport's oversized payload" in {
        withLocal { local =>
            val body = s"""{"ok":true,"result":"${"x" * 64}"}"""
            local.reply("getMe", Reply(HttpStatus.OK, body)).andThen {
                Telegram.run(local.config.copy(maxResponseLength = 16.bytes))(Abort.run[TelegramMeFailure](Telegram.me)).map {
                    result =>
                        assert(result == Result.fail(TelegramTransportException(
                            "getMe",
                            TelegramTransportException.Kind.PayloadTooLarge(body.length.bytes, 16.bytes),
                            "127.0.0.1",
                            local.base.port,
                            Absent
                        )()))
                }
            }
        }
    }

    "a redirect is not followed: 301, 302, 307 and 308 are unexpected statuses, on a call and on a download" in {
        val statuses = Chunk(HttpStatus(301), HttpStatus(302), HttpStatus(307), HttpStatus(308))
        withLocal { local =>
            val location = Seq("Location" -> local.base.copy(path = s"/redirected/bot${local.token.value}/getMe").full)
            local.reply("getMe", statuses.map(s => Reply(s, s"moved: bot${local.token.value}/getMe", location))*)
                .andThen(local.reply("download", statuses.map(s => Reply(s, "moved", location))*))
                .andThen {
                    local.api {
                        Kyo.foreach(statuses)(_ => Abort.run[TelegramMeFailure](Telegram.me)).map { calls =>
                            Kyo.foreach(statuses)(_ =>
                                Abort.run[TelegramDownloadFailure](Telegram.download(
                                    Telegram.File(Telegram.FileId("F"), Telegram.FileUniqueId("U"), path = Present("a/b"))
                                ))
                            ).map(calls -> _)
                        }
                    }
                }.map { (calls, downloads) =>
                    local.seen.map { seen =>
                        assert(calls == statuses.map(s => Result.fail(TelegramUnexpectedStatusException("getMe", s))))
                        assert(downloads.map(_.failure) == statuses.map(s => Present(TelegramUnexpectedStatusException("download", s))))
                        assert(seen.filter(_.path.startsWith("redirected")) == Chunk.empty)
                    }
                }
        }
    }

    "a response that closes after its head fails as a transport failure holding no part of the token" in {
        withClosingAfterHead(port => Telegram.run(configAt(port))(Abort.run[TelegramMeFailure](Telegram.me)).map(port -> _)).map {
            (port, result) =>
                assert(
                    result == Result.fail(TelegramTransportException(
                        "getMe",
                        TelegramTransportException.Kind.ConnectionClosed,
                        "127.0.0.1",
                        port,
                        Absent
                    )()),
                    s"got: $result"
                )
                assert(!result.failure.map(rendered).exists(_.contains(tokenSecret)))
        }
    }

    "an answer echoing the request's secrets, the token percent-encoded and the webhook secret raw, keeps neither" in {
        // Built from parts: a failure's message quotes the source lines around its frame.
        val hookSecret = Seq("echoed", "hook", "Secret").mkString("_")
        val encoded    = s"123456%3A$tokenSecret"
        val lower      = s"123456%3a$tokenSecret"
        val echo       = s"""{"ok":false,"error_code":400,"description":"Bad Request: got url=$encoded $lower secret_token=$hookSecret"}"""
        val answer     = s"HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${echo.length}\r\n\r\n$echo"
        withCountingPeer(answer) { (port, _) =>
            val url = HttpUrl.parse("https://bot.example.com/hook").getOrThrow
            Telegram.run(configAt(port))(
                Abort.run[TelegramSetWebhookFailure](Telegram.setWebhook(url, TelegramTest.webhook(hookSecret)))
            ).map { result =>
                val description = result.failure match
                    case Present(e: TelegramOtherApiException) => e.description
                    case other                                 => s"not an API answer: $other"
                assert(
                    description == "Bad Request: got url=<redacted> <redacted> secret_token=<redacted>",
                    s"got: $description"
                )
                assert(!result.failure.map(rendered).exists(r => r.contains(tokenSecret) || r.contains(hookSecret)))
            }
        }
    }

    "a chunked body with a malformed size line fails as the transport's protocol kind holding no part of the token" in {
        withCountingPeer(badChunked) { (port, _) =>
            Telegram.run(configAt(port))(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                assert(
                    result == Result.fail(TelegramTransportException(
                        "getMe",
                        TelegramTransportException.Kind.Protocol,
                        "127.0.0.1",
                        port,
                        Absent
                    )()),
                    s"got: $result"
                )
                assert(!result.failure.map(rendered).exists(_.contains(tokenSecret)))
            }
        }
    }

    "receive retries a poll whose chunked body is malformed, as any transport failure" in {
        withCountingPeer(badChunked) { (port, accepted) =>
            val config = configAt(port).copy(retrySchedule = Schedule.fixed(Duration.Zero).take(1))
            Abort.run[TelegramReceiveFailure](Telegram.run(config)(Telegram.receive(onMessage(_ => Kyo.unit)))).map { result =>
                accepted.get.map { connections =>
                    assert(
                        (result, connections) == (
                            Result.fail(TelegramTransportException(
                                "getUpdates",
                                TelegramTransportException.Kind.Protocol,
                                "127.0.0.1",
                                port,
                                Absent
                            )()),
                            2
                        ),
                        s"got: $result after $connections connections"
                    )
                }
            }
        }
    }

    "a status code outside 100 to 599 fails as the transport's protocol kind holding no part of the token" in {
        withCountingPeer("HTTP/1.1 999 Nope\r\nContent-Length: 0\r\n\r\n") { (port, _) =>
            Telegram.run(configAt(port))(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                assert(
                    result == Result.fail(TelegramTransportException(
                        "getMe",
                        TelegramTransportException.Kind.Protocol,
                        "127.0.0.1",
                        port,
                        Absent
                    )()),
                    s"got: $result"
                )
                assert(!result.failure.map(rendered).exists(_.contains(tokenSecret)))
            }
        }
    }

    "a malformed status line fails as a transport failure holding no part of the token" in {
        withCountingPeer("HTTP/1.1 abc Nope\r\nContent-Length: 0\r\n\r\n") { (port, _) =>
            Telegram.run(configAt(port))(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                assert(
                    result == Result.fail(TelegramTransportException(
                        "getMe",
                        TelegramTransportException.Kind.Protocol,
                        "127.0.0.1",
                        port,
                        Absent
                    )()),
                    s"got: $result"
                )
                assert(!result.failure.map(rendered).exists(_.contains(tokenSecret)))
            }
        }
    }

    // --- TLS, transport and transfer bounds from the config ---

    "tls comes from the config: the default refuses a self-signed server, the config's trust reaches it, and the caller's does not" in {
        withLocalTls { local =>
            local.reply("getMe", ok(me)).andThen {
                val trusting = local.config.copy(tls = kyo.internal.TlsTestHelper.clientTlsConfig)
                for
                    refused <- Telegram.run(local.config)(Abort.run[TelegramMeFailure](Telegram.me))
                    ambient <- HttpClient.withConfig(_.tls(kyo.internal.TlsTestHelper.clientTlsConfig))(
                        Telegram.run(local.config)(Abort.run[TelegramMeFailure](Telegram.me))
                    )
                    trusted <- Telegram.run(trusting)(Abort.run[TelegramMeFailure](Telegram.me))
                yield
                    val refusal = Result.fail(TelegramTransportException(
                        "getMe",
                        TelegramTransportException.Kind.Tls,
                        "127.0.0.1",
                        local.base.port,
                        Absent
                    )())
                    assert((refused, ambient) == (refusal, refusal), s"got: $refused and $ambient")
                    assert(trusted == Result.succeed(Telegram.User(Telegram.UserId(7L), true, "Kyo")), s"got: $trusted")
                end for
            }
        }
    }

    "transport comes from the config: a response head over the default limit fails, the config's larger limit reads it, and the caller's does not" in {
        val padded = Reply(HttpStatus.OK, s"""{"ok":true,"result":$me}""", Seq("X-Pad" -> "a" * (70 * 1024)))
        val wider  = HttpTransportConfig.default.maxHeaderSize(256.kib)
        withLocal { local =>
            local.reply("getMe", padded).andThen {
                for
                    refused <- Telegram.run(local.config)(Abort.run[TelegramMeFailure](Telegram.me))
                    ambient <- HttpClient.withConfig(_.transportConfig(wider))(
                        Telegram.run(local.config)(Abort.run[TelegramMeFailure](Telegram.me))
                    )
                    read <- Telegram.run(local.config.copy(transport = wider))(Abort.run[TelegramMeFailure](Telegram.me))
                yield
                    val refusal = Result.fail(TelegramTransportException(
                        "getMe",
                        TelegramTransportException.Kind.Protocol,
                        "127.0.0.1",
                        local.base.port,
                        Absent
                    )())
                    assert((refused, ambient) == (refusal, refusal), s"got: $refused and $ambient")
                    assert(read == Result.succeed(Telegram.User(Telegram.UserId(7L), true, "Kyo")), s"got: $read")
                end for
            }
        }
    }

    "receive's poll client takes transport from the config" in {
        val wider = HttpTransportConfig.default.maxHeaderSize(256.kib)
        withLocal { local =>
            local.reply(
                "getUpdates",
                Reply(HttpStatus.OK, s"""{"ok":true,"result":[${update(10, "a")}]}""", Seq("X-Pad" -> "a" * (70 * 1024))),
                Reply(HttpStatus(401), """{"ok":false,"error_code":401,"description":"Unauthorized"}""")
            ).andThen {
                AtomicInt.init.map { handled =>
                    Abort.run[TelegramReceiveFailure](Telegram.run(local.config.copy(transport = wider, retrySchedule = Schedule.done))(
                        Telegram.receive(onMessage(_ => handled.incrementAndGet.unit))
                    )).map { result =>
                        handled.get.map { count =>
                            assert((count, result) == (1, Result.fail(TelegramUnauthorizedException("getUpdates", "Unauthorized"))))
                        }
                    }
                }
            }
        }
    }

    private val upload = Telegram.Content.Document(Telegram.InputFile.Upload("r.pdf", Utf8.encode("PDF"), Absent))

    private val stored = Telegram.File(Telegram.FileId("F"), Telegram.FileUniqueId("U"), path = Present("documents/r.pdf"))

    "an upload runs past requestTimeout and answers, since transferTimeout bounds it" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Gate.init.map { gate =>
                    local.reply("sendDocument", ok(sentMessage).gated(gate)).andThen {
                        Fiber.initUnscoped(local.api(Abort.run[TelegramSendFailure](Telegram.send(chat, upload)))).map { fiber =>
                            gate.arrived.await
                                .andThen(control.advance(local.config.requestTimeout))
                                .andThen(gate.open.release)
                                .andThen(fiber.get)
                                .map(result => assert(result.map(_.id) == Result.succeed(Telegram.MessageId(42)), s"got: $result"))
                        }
                    }
                }
            }
        }
    }

    "an upload with no answer within transferTimeout fails as the transport's timeout, naming transferTimeout" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Gate.init.map { gate =>
                    local.reply("sendDocument", ok(sentMessage).gated(gate)).andThen {
                        Fiber.initUnscoped(local.api(Abort.run[TelegramSendFailure](Telegram.send(chat, upload)))).map { fiber =>
                            gate.arrived.await.andThen(control.advance(local.config.transferTimeout)).andThen(fiber.get).map {
                                result =>
                                    assert(result == Result.fail(TelegramTransportException(
                                        "sendDocument",
                                        TelegramTransportException.Kind.Timeout,
                                        "127.0.0.1",
                                        local.base.port,
                                        Present(local.config.transferTimeout)
                                    )()))
                            }
                        }
                    }
                }
            }
        }
    }

    "an API call under the same config keeps requestTimeout" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Fiber.initUnscoped(local.api(Abort.run[TelegramSendFailure](Telegram.send(chat, Telegram.Content.text("x"))))).map {
                    fiber =>
                        local.pollStarted.await.andThen(control.advance(local.config.requestTimeout)).andThen(fiber.get).map {
                            result =>
                                assert(result == Result.fail(TelegramTransportException(
                                    "sendMessage",
                                    TelegramTransportException.Kind.Timeout,
                                    "127.0.0.1",
                                    local.base.port,
                                    Present(local.config.requestTimeout)
                                )()))
                        }
                }
            }
        }
    }

    "a download runs past requestTimeout and answers, since transferTimeout bounds it" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Gate.init.map { gate =>
                    local.reply("download", Reply(HttpStatus.OK, "BYTES").gated(gate)).andThen {
                        Fiber.initUnscoped(local.api(Abort.run[TelegramDownloadFailure](Telegram.download(stored)))).map { fiber =>
                            gate.arrived.await
                                .andThen(control.advance(local.config.requestTimeout))
                                .andThen(gate.open.release)
                                .andThen(fiber.get)
                                .map(result =>
                                    assert(
                                        result.map(Utf8.decode) == Result.succeed("BYTES"),
                                        s"got: $result"
                                    )
                                )
                        }
                    }
                }
            }
        }
    }

    "a download with no answer within transferTimeout fails as the transport's timeout, naming transferTimeout" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Gate.init.map { gate =>
                    local.reply("download", Reply(HttpStatus.OK, "BYTES").gated(gate)).andThen {
                        Fiber.initUnscoped(local.api(Abort.run[TelegramDownloadFailure](Telegram.download(stored)))).map { fiber =>
                            gate.arrived.await.andThen(control.advance(local.config.transferTimeout)).andThen(fiber.get).map {
                                result =>
                                    assert(result.map(_.size) == Result.fail(TelegramTransportException(
                                        "download",
                                        TelegramTransportException.Kind.Timeout,
                                        "127.0.0.1",
                                        local.base.port,
                                        Present(local.config.transferTimeout)
                                    )()))
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Long polling ---

    "receive handles each update in order and confirms it by the next offset, only after the handler returned" in {
        withLocal { local =>
            local.reply(
                "getUpdates",
                ok(s"[${update(10, "a")},${update(11, "b")}]"),
                ok(s"[${update(12, "c")}]"),
                Reply(HttpStatus(401), """{"ok":false,"error_code":401,"description":"Unauthorized"}""")
            ).andThen {
                AtomicRef.init(Chunk.empty[String]).map { handled =>
                    Abort.run[TelegramReceiveFailure](Telegram.run(local.config.copy(retrySchedule = Schedule.done))(
                        Telegram.receive(onMessage(m => handled.updateAndGet(_ :+ m.text.getOrElse("?")).unit))
                    )).map { result =>
                        handled.get.map { texts =>
                            local.bodies("getUpdates").map { bodies =>
                                assert(
                                    result == Result.fail(TelegramUnauthorizedException("getUpdates", "Unauthorized")),
                                    s"got: $result"
                                )
                                assert(texts == Chunk("a", "b", "c"))
                                assert(bodies == Chunk(
                                    """{"timeout":30,"limit":100}""",
                                    """{"offset":12,"timeout":30,"limit":100}""",
                                    """{"offset":13,"timeout":30,"limit":100}"""
                                ))
                            }
                        }
                    }
                }
            }
        }
    }

    "receive hands an update with only update_id to the handler as Unknown with no type, and polls past it" in {
        withLocal { local =>
            local.reply(
                "getUpdates",
                ok("""[{"update_id":20}]"""),
                Reply(HttpStatus(401), """{"ok":false,"error_code":401,"description":"Unauthorized"}""")
            ).andThen {
                AtomicRef.init(Chunk.empty[Telegram.Update]).map { handled =>
                    Abort.run[TelegramReceiveFailure](Telegram.run(local.config.copy(retrySchedule = Schedule.done))(
                        Telegram.receive(update => handled.updateAndGet(_ :+ update).unit)
                    )).andThen {
                        handled.get.map { updates =>
                            local.bodies("getUpdates").map { bodies =>
                                assert(updates == Chunk(Telegram.Update.Unknown(
                                    Telegram.UpdateId(20L),
                                    Absent,
                                    TelegramTest.Fixtures.raw("""{"update_id":20}""")
                                )))
                                assert(bodies == Chunk("""{"timeout":30,"limit":100}""", """{"offset":21,"timeout":30,"limit":100}"""))
                            }
                        }
                    }
                }
            }
        }
    }

    "a method documented to return true that answers another result is a decode failure of its result" in {
        withLocal { local =>
            local.reply("deleteMessage", ok("false")).andThen {
                Telegram.run(local.config)(Abort.run[TelegramDeleteFailure](Telegram.delete(chat, Telegram.MessageId(1)))).map {
                    result =>
                        assert(result == Result.fail(TelegramDecodeException(
                            "deleteMessage",
                            TelegramDecodeException.Part.Result,
                            TelegramDecodeException.Failure.TypeMismatch,
                            Chunk.empty,
                            Absent
                        )))
                }
            }
        }
    }

    "migrate_to_chat_id on an answer other than 400 is the leaf of that answer's code, not TelegramMigratedException" in {
        withLocal { local =>
            local.reply(
                "sendMessage",
                Reply(
                    HttpStatus(403),
                    """{"ok":false,"error_code":403,"description":"Forbidden: d","parameters":{"migrate_to_chat_id":-1001}}"""
                )
            ).andThen {
                Telegram.run(local.config)(Abort.run[TelegramSendFailure](Telegram.send(chat, Telegram.Content.text("x")))).map {
                    result => assert(result == Result.fail(TelegramForbiddenException("sendMessage", "Forbidden: d")), s"got: $result")
                }
            }
        }
    }

    "a handler's typed failure ends receive with it, and the update is not confirmed" in {
        withLocal { local =>
            local.reply("getUpdates", ok(s"[${update(10, "a")},${update(11, "boom")}]"), ok("[]")).andThen {
                Abort.run[TelegramReceiveFailure | String](Telegram.run(local.config)(
                    Telegram.receive(onMessage(m => if m.text == Present("boom") then Abort.fail("handler refused") else Kyo.unit))
                )).map { result =>
                    local.bodies("getUpdates").map { bodies =>
                        assert(result == Result.fail("handler refused"), s"got: $result")
                        assert(bodies == Chunk("""{"timeout":30,"limit":100}"""))
                    }
                }
            }
        }
    }

    "a handler's panic ends receive with it, and the update is not confirmed" in {
        val boom = new IllegalStateException("handler bug")
        withLocal { local =>
            local.reply("getUpdates", ok(s"[${update(10, "a")}]"), ok("[]")).andThen {
                Abort.run[TelegramReceiveFailure](Telegram.run(local.config)(Telegram.receive(onMessage(_ => Abort.panic(boom))))).map {
                    result =>
                        local.bodies("getUpdates").map { bodies =>
                            assert(result == Result.panic(boom), s"got: $result")
                            assert(bodies == Chunk("""{"timeout":30,"limit":100}"""))
                        }
                }
            }
        }
    }

    "receive polls again after a server error and a rate limit, and ends on a conflict" in {
        withLocal { local =>
            local.reply(
                "getUpdates",
                Reply(HttpStatus(502), "<html>bad gateway</html>"),
                Reply(
                    HttpStatus(429),
                    """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 0","parameters":{"retry_after":0}}"""
                ),
                Reply(HttpStatus(409), """{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}""")
            ).andThen {
                // Zero delay: advancing the fixture's clock past a retry delay would also advance kyo-http's request deadlines, whose
                // sleepers this leaf does not count, so it asserts what is retried and PollerTest asserts the wait against `retry` alone.
                val config = local.config.copy(retrySchedule = Schedule.fixed(Duration.Zero))
                Abort.run[TelegramReceiveFailure](Telegram.run(config)(Telegram.receive(onMessage(_ => Kyo.unit)))).map { result =>
                    local.bodies("getUpdates").map { bodies =>
                        assert(result ==
                            Result.fail(TelegramConflictException("getUpdates", "Conflict: terminated by other getUpdates request")))
                        assert(bodies.size == 3)
                    }
                }
            }
        }
    }

    "receive ends with the failure when the schedule allows no more attempts" in {
        withLocal { local =>
            local.reply("getUpdates", Reply(HttpStatus(503), "unavailable")).andThen {
                Abort.run[TelegramReceiveFailure](Telegram.run(local.config.copy(retrySchedule = Schedule.done))(
                    Telegram.receive(onMessage(_ => Kyo.unit))
                )).map {
                    result =>
                        assert(result == Result.fail(TelegramUnexpectedStatusException("getUpdates", HttpStatus(503))), s"got: $result")
                }
            }
        }
    }

    "the handler calls the verbs directly, on the client receive polls with" in {
        withLocal { local =>
            local.reply(
                "getUpdates",
                ok(s"[${update(10, "a")}]"),
                Reply(HttpStatus(401), """{"ok":false,"error_code":401,"description":"Unauthorized"}""")
            )
                .andThen(local.reply("sendMessage", ok(sentMessage)))
                .andThen {
                    Abort.run[TelegramReceiveFailure | TelegramSendFailure](Telegram.run(local.config.copy(retrySchedule = Schedule.done))(
                        Telegram.receive(onMessage(_ => Telegram.send(chat, Telegram.Content.text("pong")).unit))
                    )).andThen {
                        local.bodies("sendMessage").map(b => assert(b == Chunk("""{"chat_id":-100200300,"text":"pong"}""")))
                    }
                }
        }
    }

    "a handler carries effects of its own through receive" in {
        withLocal { local =>
            local.reply(
                "getUpdates",
                ok(s"[${update(10, "a")},${update(11, "b")}]"),
                Reply(HttpStatus(401), """{"ok":false,"error_code":401,"description":"Unauthorized"}""")
            )
                .andThen {
                    Var.runTuple(0)(Abort.run[TelegramReceiveFailure](Telegram.run(local.config.copy(retrySchedule = Schedule.done))(
                        Telegram.receive(onMessage(_ => Var.update[Int](_ + 1).unit))
                    ))).map { (count, result) =>
                        assert((count, result) == (2, Result.fail(TelegramUnauthorizedException("getUpdates", "Unauthorized"))))
                    }
                }
        }
    }

    "interrupting receive closes its poll's connection" in {
        withLocal { local =>
            for
                fiber <- Fiber.initUnscoped(Abort.run[TelegramReceiveFailure](Telegram.run(local.config)(Telegram.receive(onMessage(_ =>
                    Kyo.unit
                )))))
                _ <- local.pollStarted.await
                _ <- fiber.interrupt
                _ <- local.pollClosed.await
            yield succeed("the local server saw the held poll end")
        }
    }

    // --- Allowed updates ---

    "receive passes allowed_updates by their wire names" in {
        withLocal { local =>
            local.reply("getUpdates", Reply(HttpStatus(401), """{"ok":false,"error_code":401,"description":"Unauthorized"}""")).andThen {
                val config = local.config.copy(
                    allowedUpdates =
                        Present(Chunk(
                            Telegram.Update.Type.Message,
                            Telegram.Update.Type.MessageReaction,
                            Telegram.Update.Type.Other("poll")
                        ))
                )
                Abort.run[TelegramReceiveFailure](Telegram.run(config)(Telegram.receive(onMessage(_ => Kyo.unit)))).andThen {
                    local.bodies("getUpdates").map { bodies =>
                        assert(bodies == Chunk("""{"timeout":30,"limit":100,"allowed_updates":["message","message_reaction","poll"]}"""))
                    }
                }
            }
        }
    }

end TelegramTest

object TelegramTest:

    /** The Bot API 10.3 payloads the round-trip leaves decode and re-encode, with the objects they share. */
    object Fixtures:
        import Telegram.*

        val aliceJson =
            """{"id":111111111,"is_bot":false,"first_name":"Alice","last_name":"Liddell","username":"alice","language_code":"en"}"""
        val alice = User(
            UserId(111111111L),
            isBot = false,
            firstName = "Alice",
            lastName = Present("Liddell"),
            username = Present("alice"),
            languageCode = Present("en")
        )

        val botJson = """{"id":7000000001,"is_bot":true,"first_name":"Kyo Bot","username":"kyo_bot"}"""
        val bot     = User(UserId(7000000001L), isBot = true, firstName = "Kyo Bot", username = Present("kyo_bot"))

        val privateChatJson = """{"id":111111111,"type":"private","first_name":"Alice","last_name":"Liddell","username":"alice"}"""
        val privateChat     = Chat(
            ChatId(111111111L),
            Chat.Type.Private,
            username = Present("alice"),
            firstName = Present("Alice"),
            lastName = Present("Liddell")
        )

        val forumJson = """{"id":-1001234567890,"type":"supergroup","title":"Kyo Dev","username":"kyodev","is_forum":true}"""
        val forum     =
            Chat(ChatId(-1001234567890L), Chat.Type.Supergroup, title = Present("Kyo Dev"), username = Present("kyodev"), isForum = true)

        val channelJson = """{"id":-1009876543210,"type":"channel","title":"Kyo News"}"""
        val channel     = Chat(ChatId(-1009876543210L), Chat.Type.Channel, title = Present("Kyo News"))

        val groupJson = """{"id":-123456789,"type":"group","title":"Old Group"}"""
        val group     = Chat(ChatId(-123456789L), Chat.Type.Group, title = Present("Old Group"))

        /** The private chat with Alice as a message carries it when only her first name is set. */
        val aliceChatJson = """{"id":111111111,"type":"private","first_name":"Alice"}"""
        val aliceChat     = Chat(ChatId(111111111L), Chat.Type.Private, firstName = Present("Alice"))

        val aliceUserJson = """{"id":111111111,"is_bot":false,"first_name":"Alice"}"""
        val aliceUser     = User(UserId(111111111L), isBot = false, firstName = "Alice")

        def at(seconds: Long): Instant = Instant.of(seconds.seconds, Duration.Zero)

        def bytes(n: Long): Maybe[ByteSize] = Present(ByteSize.fromBytes(n))

        def raw(json: String)(using Frame): RawJson = RawJson(Json.decode[Structure.Value](json).getOrThrow)

        /** `json` as a tree with every object's keys sorted, so two encodings compare regardless of key order. */
        def wire(json: String)(using Frame): Structure.Value = kyo.internal.telegram.WireFieldTest.wire(json)

        def wire[A: Schema](value: A)(using Frame): Structure.Value = kyo.internal.telegram.WireFieldTest.wire(value)
    end Fixtures

    final case class ChatParam(chat_id: Long) derives Schema

    // Built from parts: a failure's message quotes the source lines around its frame, which would otherwise show the literal.
    val tokenSecret: String = Seq("TEST", "secret", "part").mkString("-")

    /** A config whose token holds `tokenSecret`, on a peer at `port`. */
    def configAt(port: Int)(using Frame): TelegramConfig =
        TelegramConfig.init(
            Telegram.Token.init(s"123456:$tokenSecret").getOrThrow,
            baseUrl = HttpUrl(Present("http"), "127.0.0.1", port, "/", Absent)
        ).getOrThrow

    /** A webhook config at the root path whose secret is `secret`. */
    def webhook(secret: String)(using Frame): TelegramWebhookConfig =
        Telegram.SecretToken.init(secret).flatMap(TelegramWebhookConfig.init(_)).getOrThrow

    /** A handler that runs `f` on each message and answers every other update with nothing. */
    def onMessage[E, S](f: Telegram.Message => Unit < (Async & Abort[E] & Env[Telegram] & S))
        : Telegram.Update => Unit < (Async & Abort[E] & Env[Telegram] & S) =
        case Telegram.Update.Message(_, message) => f(message)
        case _                                   => Kyo.unit
    end onMessage

    /** One request the local Bot API received: the path after `/`, the content type and the body. */
    final case class Seen(path: String, contentType: Maybe[String], body: String) derives CanEqual:
        def method: String = path.substring(path.lastIndexOf('/') + 1)

    /** A reply sent only once `gated` is given a gate and the leaf opens it; `gate.arrived` opens when the request is in. */
    final case class Reply(status: HttpStatus, body: String, headers: Seq[(String, String)] = Seq.empty, gate: Maybe[Gate] = Absent):
        def gated(g: Gate): Reply = copy(gate = Present(g))

    /** The two sides of a gated reply: the server releases `arrived` on the request, then waits for `open`. */
    final class Gate(val arrived: Latch, val open: Latch)

    object Gate:
        def init(using Frame): Gate < Sync = Latch.init(1).map(arrived => Latch.init(1).map(open => Gate(arrived, open)))

    def ok(result: String): Reply = Reply(HttpStatus.OK, s"""{"ok":true,"result":$result}""")

    /** Telegram's flood-control answer, asking for `seconds` before the request is repeated. */
    def flood(seconds: Long): Reply =
        Reply(
            HttpStatus(429),
            s"""{"ok":false,"error_code":429,"description":"Too Many Requests: retry after $seconds","parameters":{"retry_after":$seconds}}"""
        )

    def update(id: Long, text: String): String =
        s"""{"update_id":$id,"message":{"message_id":$id,"date":1700000000,"chat":{"id":5,"type":"private","first_name":"Ann"},"from":{"id":5,"is_bot":false,"first_name":"Ann"},"text":"$text"}}"""

    /** A leaf's message, its rendering, every field, and its cause's rendering, message and fields, as one string. */
    def rendered(e: TelegramException): String =
        def fields(a: Any): String =
            a match
                case p: Product => p.productIterator.mkString("|")
                case other      => String.valueOf(other)
        val cause = Maybe(e.getCause()).fold("")(c => c.toString + "|" + c.getMessage + "|" + fields(c))
        e.getMessage + "|" + e.toString + "|" + fields(e) + "|" + cause
    end rendered

    /** The local Bot API's recorded requests and queued replies, shared by its routes. */
    final class Backend(
        replies: AtomicRef[Map[String, Chunk[Reply]]],
        requests: AtomicRef[Chunk[Seen]],
        val pollStarted: Latch,
        val pollClosed: Latch
    ):
        def reply(method: String, rs: Reply*)(using Frame): Unit < Sync = replies.updateAndGet(_.updated(method, Chunk.from(rs))).unit

        def seen(using Frame): Chunk[Seen] < Sync = requests.get

        private[TelegramTest] def next(method: String)(using Frame): Maybe[Reply] < Sync =
            replies.getAndUpdate(m => m.get(method).fold(m)(q => if q.size > 1 then m.updated(method, q.drop(1)) else m))
                .map(m => Maybe.fromOption(m.get(method)).flatMap(_.headMaybe))

        private[TelegramTest] def record(s: Seen)(using Frame): Unit < Sync = requests.updateAndGet(_ :+ s).unit
    end Backend

    /** A local Bot API: each method answers the next reply queued for it, the last one repeating; a
      * method with nothing queued holds the request open until the client goes away. `/file/bot*`
      * answers the next reply queued for `download`, or `file:{path}`.
      *
      * The server and every leaf on it run under `Clock.withTimeControl`, so the connect, request and poll deadlines the
      * default config arms never fire unless the leaf advances time; a leaf that does opens its own `withTimeControl`,
      * which reuses this control.
      */
    final class Local(val base: HttpUrl, val token: Telegram.Token, backend: Backend):
        def config(using Frame): TelegramConfig = TelegramConfig.init(token, baseUrl = base).getOrThrow

        /** Runs `v` with a client on this local Bot API. */
        def api[A, S](v: A < (S & Env[Telegram]))(using Frame): A < (S & Async) = Telegram.run(config)(v)

        def reply(method: String, rs: Reply*)(using Frame): Unit < Sync = backend.reply(method, rs*)

        def seen(using Frame): Chunk[Seen] < Sync = backend.seen

        def bodies(method: String)(using Frame): Chunk[String] < Sync = backend.seen.map(_.filter(_.method == method).map(_.body))

        def pollStarted: Latch = backend.pollStarted
        def pollClosed: Latch  = backend.pollClosed
    end Local

    def withLocal[A](test: Local => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        serveLocal(HttpServerConfig.default.port(0).host("127.0.0.1"), "http")(test)

    /** [[withLocal]] served over TLS with a self-signed certificate, which the default `tls` does not trust. */
    def withLocalTls[A](test: Local => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        serveLocal(HttpServerConfig.default.port(0).host("127.0.0.1").tls(kyo.internal.TlsTestHelper.serverTlsConfig), "https")(test)

    private def serveLocal[A](serverConfig: HttpServerConfig, scheme: String)(test: Local => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        val token             = Telegram.Token.init("123456:TEST-token_local").getOrThrow
        def respond(r: Reply) =
            r.gate.fold(Kyo.unit)(g => g.arrived.release.andThen(g.open.await)).andThen(
                r.headers.foldLeft(HttpResponse(r.status).addField("body", r.body))((resp, h) => resp.addHeader(h._1, h._2))
            )
        Clock.withTimeControl { _ =>
            for
                replies  <- AtomicRef.init(Map.empty[String, Chunk[Reply]])
                requests <- AtomicRef.init(Chunk.empty[Seen])
                started  <- Latch.init(1)
                closed   <- Latch.init(1)
                backend = Backend(replies, requests, started, closed)
                api     = HttpRoute.postRaw(HttpPath.Capture.Rest("path")).request(_.bodyBinary).response(_.bodyText).handler { req =>
                    val seen = Seen(req.fields.path, req.headers.get("Content-Type"), Utf8.decode(req.fields.body))
                    backend.record(seen).andThen(backend.next(seen.method)).map {
                        case Present(r) => respond(r)
                        case Absent     =>
                            Scope.run(Scope.ensure(closed.release).andThen(started.release).andThen(Async.never))
                    }
                }
                files = HttpRoute.getRaw("file" / HttpPath.Capture.Rest("path")).response(_.bodyText).handler { req =>
                    val path = req.fields.path
                    backend.record(Seen("file/" + path, Absent, "")).andThen(backend.next("download")).map {
                        case Present(r) => respond(r)
                        case Absent     => HttpResponse.ok.addField("body", "file:" + path.substring(path.indexOf('/') + 1))
                    }
                }
                redirected = HttpRoute.getRaw("redirected" / HttpPath.Capture.Rest("path")).response(_.bodyText).handler { req =>
                    backend.record(Seen("redirected/" + req.fields.path, Absent, "")).andThen(HttpResponse.ok.addField("body", "followed"))
                }
                redirectedPost = HttpRoute.postRaw("redirected" / HttpPath.Capture.Rest("path")).response(_.bodyText).handler { req =>
                    backend.record(Seen("redirected/" + req.fields.path, Absent, "")).andThen(HttpResponse.ok.addField("body", "followed"))
                }
                server <- HttpServer.init(serverConfig)(redirected, redirectedPost, api, files)
                result <- test(Local(HttpUrl(Present(scheme), "127.0.0.1", server.port, "/", Absent), token, backend))
            yield result
            end for
        }
    end serveLocal

    /** A client filter that records the path of every request it sees. */
    def recorder(paths: AtomicRef[Chunk[String]]): HttpFilter.Passthrough[Nothing] =
        new HttpFilter.Passthrough[Nothing]:
            def apply[In, Out, E2, S](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt]) =
                paths.updateAndGet(_ :+ request.path).andThen(next(request))

    def okResponse(result: String): String = jsonResponse(s"""{"ok":true,"result":$result}""")

    /** [[okResponse]] with its body led by a UTF-8 byte order mark, EF BB BF. */
    def okResponseWithBom(result: String): String = jsonResponse(s"""﻿{"ok":true,"result":$result}""")

    private def jsonResponse(body: String): String =
        s"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${Utf8.encode(body).size}\r\n\r\n$body"

    /** A 200 whose chunked body's first size line is not hexadecimal. */
    val badChunked: String = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n{}\r\n0\r\n\r\n"

    /** A peer that answers every connection with `response`, queued on accept, and counts the connections it accepted. The leaf
      * runs under `Clock.withTimeControl`, as on [[withLocal]].
      */
    def withCountingPeer[A](response: String)(test: (Int, AtomicInt) => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        val bytes = Utf8.encode(response)
        Clock.withTimeControl { _ =>
            AtomicInt.init.map { accepted =>
                Sync.Unsafe.defer {
                    kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16) { conn =>
                        // Unsafe: the accept callback runs outside the effect system; it counts the connection and queues the answer,
                        // which HTTP/1.1 lets a server send before it has read the request.
                        discard(accepted.unsafe.incrementAndGet())
                        discard(conn.outbound.offer(bytes))
                    }
                }.map { fiber =>
                    // Unsafe: the listener is kyo-net's raw tier; it is closed when the test's Scope ends.
                    fiber.safe.use(listener => Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen(test(listener.port, accepted)))
                }
            }
        }
    end withCountingPeer

    /** A peer that, once `request` is running against it, reads the request, answers a head promising 100 bytes with 6 of them,
      * and closes. kyo-net writes every queued span before it closes the socket, so the client reads the whole head first. The
      * request runs under `Clock.withTimeControl`, as on [[withLocal]].
      */
    def withClosingAfterHead[A](request: Int => A < Async)(using Frame): A < (Async & Abort[Any] & Scope) =
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"ok\":"
        Clock.withTimeControl { _ =>
            Channel.init[kyo.net.Connection](1).map { accepted =>
                Sync.Unsafe.defer {
                    kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16) { conn =>
                        // Unsafe: the accept callback runs outside the effect system; it hands the connection to the test.
                        discard(accepted.unsafe.offer(conn))
                    }
                }.map { fiber =>
                    fiber.safe.use { listener =>
                        // Unsafe: the raw listener and connection have no safe close; each is closed once the test is done with it.
                        Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen {
                            Fiber.initUnscoped(request(listener.port)).map { client =>
                                accepted.take.map { conn =>
                                    Abort.run[Closed](conn.inbound.safe.take)
                                        .andThen(Abort.run[Closed](conn.outbound.safe.put(Utf8.encode(head))))
                                        .andThen(Sync.Unsafe.defer(conn.close()))
                                        .andThen(client.get)
                                }
                            }
                        }
                    }
                }
            }
        }
    end withClosingAfterHead

end TelegramTest
