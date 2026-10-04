package kyo

import DiscordTest.*
import java.nio.charset.StandardCharsets.UTF_8

class DiscordTest extends kyo.test.Test[Any]:

    import Discord.*

    private val channelId = ChannelId(111L)
    private val messageId = MessageId(300L)
    private val guildId   = GuildId(5L)
    private val userId    = UserId(42L)

    private val messageJson =
        """{"id":"300","channel_id":"111","author":{"id":"7","username":"kyo"},"content":"hi","timestamp":"2026-10-02T10:00:00.000000+00:00"}"""
    private val channelJson = """{"id":"111","type":0,"guild_id":"5","name":"general"}"""
    private val threadJson  = """{"id":"112","type":11,"guild_id":"5","name":"plans","parent_id":"111"}"""
    private val dmJson      = """{"id":"113","type":1}"""
    private val memberJson  = """{"user":{"id":"42","username":"ann"},"nick":"A","roles":["9"]}"""
    private val commandJson =
        """{"id":"800","application_id":"900","version":"1","type":1,"name":"roll","description":"Roll dice","default_member_permissions":null,"nsfw":false}"""
    private val gatewayJson =
        """{"url":"wss://gateway.discord.gg","shards":2,"session_start_limit":{"total":1000,"remaining":999,"reset_after":14400000,"max_concurrency":1}}"""

    private def decoded[A: Schema](json: String)(using Frame): A = Json.decode[A](json).getOrThrow

    private val create = Message.Create.init(content = Present("hi")).getOrThrow
    private val edited = Message.Edit.init(content = Present(Patch.Set("edited"))).getOrThrow
    private val fire   = Reaction.unicode("🔥").getOrThrow

    // --- The request path ---

    "a call carries the bot token and Discord's user agent to its route under the base URL's path" in {
        withLocal { local =>
            local.reply("GET /channels/111", ok(channelJson)).andThen {
                local.api(Discord.channel(channelId)).map { channel =>
                    local.seen.map { seen =>
                        assert(channel == decoded[Channel](channelJson))
                        assert(seen ==
                            Chunk(Seen("GET", "/channels/111", Absent, Present(s"Bot $tokenSecret"), Present(userAgent), Absent, "")))
                    }
                }
            }
        }
    }

    "run(config) closes its client's HTTP client when its region ends" in {
        withLocal { local =>
            Discord.run(local.config)(Env.get[Discord]).map { discord =>
                // Unsafe: whether a pool is closed has no safe accessor; this reads the flag once, after the region ended.
                Sync.Unsafe.defer(discord.http.isPoolClosed).map(closed => assert(closed))
            }
        }
    }

    "run(client) provides the client without closing it, so a second region on the same client calls too" in {
        withLocal { local =>
            local.reply("GET /channels/111", ok(channelJson), ok(channelJson)).andThen {
                Discord.client(local.config).map { discord =>
                    for
                        first  <- Discord.run(discord)(Discord.channel(channelId))
                        second <- Discord.run(discord)(Discord.channel(channelId))
                        // Unsafe: whether a pool is closed has no safe accessor; this reads the flag once, after both regions ended.
                        closed <- Sync.Unsafe.defer(discord.http.isPoolClosed)
                        seen   <- local.seen
                    yield
                        assert(first == decoded[Channel](channelJson) && second == first)
                        assert(!closed)
                        assert(seen.size == 2)
                }
            }
        }
    }

    // --- The verbs ---

    "send posts the message's JSON and answers the message Discord created" in {
        withLocal { local =>
            local.reply("POST /channels/111/messages", ok(messageJson)).andThen {
                local.api(Discord.send(channelId, create)).map { message =>
                    local.seen.map { seen =>
                        assert(message == decoded[Message](messageJson))
                        assert(seen.map(s => (s.method, s.path, s.contentType, s.body)) ==
                            Chunk(("POST", "/channels/111/messages", Present("application/json"), """{"content":"hi","tts":false}""")))
                    }
                }
            }
        }
    }

    "send with files posts multipart form data: payload_json naming each attachment, then files[n]" in {
        val file = File.init("a.txt", Span.from("AAA".getBytes(UTF_8)), description = Present("alt")).getOrThrow
        withLocal { local =>
            local.reply("POST /channels/111/messages", ok(messageJson)).andThen {
                local.api(Discord.send(channelId, Message.Create.init(content = Present("see"), files = Chunk(file)).getOrThrow)).andThen {
                    local.seen.map { seen =>
                        assert(seen.map(_.contentType) == Chunk(Present("multipart/form-data; boundary=kyo-discord-0")))
                        assert(seen.map(_.body) == Chunk(
                            "--kyo-discord-0\r\n" +
                                "Content-Disposition: form-data; name=\"payload_json\"\r\n" +
                                "Content-Type: application/json\r\n\r\n" +
                                """{"content":"see","tts":false,"attachments":[{"id":0,"filename":"a.txt","description":"alt"}]}""" +
                                "\r\n" +
                                "--kyo-discord-0\r\n" +
                                "Content-Disposition: form-data; name=\"files[0]\"; filename=\"a.txt\"\r\n\r\n" +
                                "AAA\r\n" +
                                "--kyo-discord-0--\r\n"
                        ))
                    }
                }
            }
        }
    }

    "edit, delete, react, unreact and typing reach their routes with their bodies" in {
        withLocal { local =>
            local.reply("PATCH /channels/111/messages/300", ok(messageJson))
                .andThen(local.reply("DELETE /channels/111/messages/300", noContent))
                .andThen(local.reply("PUT /channels/111/messages/300/reactions/%F0%9F%94%A5/@me", noContent))
                .andThen(local.reply("DELETE /channels/111/messages/300/reactions/%F0%9F%94%A5/@me", noContent))
                .andThen(local.reply("POST /channels/111/typing", noContent))
                .andThen {
                    local.api {
                        Discord.edit(channelId, messageId, edited).map { message =>
                            Discord.delete(channelId, messageId)
                                .andThen(Discord.react(channelId, messageId, fire))
                                .andThen(Discord.unreact(channelId, messageId, fire))
                                .andThen(Discord.typing(channelId))
                                .andThen(message)
                        }
                    }.map { message =>
                        local.seen.map { seen =>
                            assert(message == decoded[Message](messageJson))
                            assert(seen.map(s => (s.method, s.path, s.body)) == Chunk(
                                ("PATCH", "/channels/111/messages/300", """{"content":"edited"}"""),
                                ("DELETE", "/channels/111/messages/300", ""),
                                ("PUT", "/channels/111/messages/300/reactions/%F0%9F%94%A5/@me", ""),
                                ("DELETE", "/channels/111/messages/300/reactions/%F0%9F%94%A5/@me", ""),
                                ("POST", "/channels/111/typing", "")
                            ))
                        }
                    }
                }
        }
    }

    "a custom emoji's reaction is name:id in the path" in {
        withLocal { local =>
            local.reply("PUT /channels/111/messages/300/reactions/party%3A77/@me", noContent).andThen {
                local.api(Discord.react(channelId, messageId, Reaction.custom(EmojiId(77L), "party").getOrThrow)).andThen {
                    local.seen.map(seen => assert(seen.map(_.path) == Chunk("/channels/111/messages/300/reactions/party%3A77/@me")))
                }
            }
        }
    }

    "startThread from a message and without one, and openDm, answer the channel Discord created" in {
        val thread = Thread.Start.init("plans").getOrThrow
        withLocal { local =>
            local.reply("POST /channels/111/messages/300/threads", ok(threadJson))
                .andThen(local.reply("POST /channels/111/threads", ok(threadJson)))
                .andThen(local.reply("POST /users/@me/channels", ok(dmJson)))
                .andThen {
                    local.api {
                        for
                            fromMessage <- Discord.startThread(channelId, messageId, thread)
                            standalone  <- Discord.startThread(channelId, thread)
                            dm          <- Discord.openDm(userId)
                        yield (fromMessage, standalone, dm)
                    }.map { answers =>
                        local.seen.map { seen =>
                            assert(answers == (decoded[Channel](threadJson), decoded[Channel](threadJson), decoded[Channel](dmJson)))
                            assert(seen.map(s => (s.path, s.body)) == Chunk(
                                ("/channels/111/messages/300/threads", """{"name":"plans"}"""),
                                ("/channels/111/threads", """{"name":"plans"}"""),
                                ("/users/@me/channels", """{"recipient_id":"42"}""")
                            ))
                        }
                    }
                }
        }
    }

    "setCommands resolves the application once and overwrites the global and a guild's commands" in {
        val roll = Command.Create.init("roll", "Roll dice").getOrThrow
        withLocal { local =>
            local.reply("GET /applications/@me", ok("""{"id":"900","name":"kyo"}"""))
                .andThen(local.reply("PUT /applications/900/commands", ok(s"[$commandJson]")))
                .andThen(local.reply("PUT /applications/900/guilds/5/commands", ok(s"[$commandJson]")))
                .andThen {
                    local.api(Discord.setCommands(Chunk(roll)).map(global =>
                        Discord.setCommands(guildId, Chunk(roll)).map(global -> _)
                    ))
                        .map { (global, guild) =>
                            local.seen.map { seen =>
                                assert((global, guild) == (Chunk(decoded[Command](commandJson)), Chunk(decoded[Command](commandJson))))
                                assert(seen.map(s => (s.method, s.path, s.body)) == Chunk(
                                    ("GET", "/applications/@me", ""),
                                    ("PUT", "/applications/900/commands", s"[${Json.encode(roll)}]"),
                                    ("PUT", "/applications/900/guilds/5/commands", s"[${Json.encode(roll)}]")
                                ))
                            }
                        }
                }
        }
    }

    "followUp, editResponse and deleteResponse address the interaction's webhook, with no bot rate-limit token involved" in {
        withLocal { local =>
            val hook = s"/webhooks/900/$interactionSecret"
            local.reply(s"POST $hook", ok(messageJson))
                .andThen(local.reply(s"PATCH $hook/messages/@original", ok(messageJson)))
                .andThen(local.reply(s"DELETE $hook/messages/@original", noContent))
                .andThen {
                    local.api {
                        for
                            followed <- Discord.followUp(interaction, create)
                            response <- Discord.editResponse(interaction, edited)
                            _        <- Discord.deleteResponse(interaction)
                        yield (followed, response)
                    }.map { answers =>
                        local.seen.map { seen =>
                            assert(answers == (decoded[Message](messageJson), decoded[Message](messageJson)))
                            assert(seen.map(s => (s.method, s.path, s.body)) == Chunk(
                                ("POST", hook, """{"content":"hi","tts":false}"""),
                                ("PATCH", s"$hook/messages/@original", """{"content":"edited"}"""),
                                ("DELETE", s"$hook/messages/@original", "")
                            ))
                        }
                    }
                }
        }
    }

    "channel, member, message and gateway read their resources" in {
        withLocal { local =>
            local.reply("GET /channels/111", ok(channelJson))
                .andThen(local.reply("GET /guilds/5/members/42", ok(memberJson)))
                .andThen(local.reply("GET /channels/111/messages/300", ok(messageJson)))
                .andThen(local.reply("GET /gateway/bot", ok(gatewayJson)))
                .andThen {
                    local.api {
                        for
                            channel <- Discord.channel(channelId)
                            member  <- Discord.member(guildId, userId)
                            message <- Discord.message(channelId, messageId)
                            info    <- Discord.gateway
                        yield (channel, member, message, info)
                    }.map { answers =>
                        assert(answers == (
                            decoded[Channel](channelJson),
                            decoded[Member](memberJson),
                            decoded[Message](messageJson),
                            decoded[GatewayInfo](gatewayJson)
                        ))
                    }
                }
        }
    }

    "messages reads a page from its anchor with its limit" in {
        val pages = Chunk(
            Message.Page.init(),
            Message.Page.init(Message.Page.Anchor.Around(messageId), 10),
            Message.Page.init(Message.Page.Anchor.Before(messageId), 1),
            Message.Page.init(Message.Page.Anchor.After(messageId), 100)
        ).map(_.getOrThrow)
        withLocal { local =>
            local.reply("GET /channels/111/messages", ok(s"[$messageJson]")).andThen {
                local.api(Kyo.foreach(pages)(Discord.messages(channelId, _))).map { answers =>
                    local.seen.map { seen =>
                        assert(answers == Chunk.fill(4)(Chunk(decoded[Message](messageJson))))
                        assert(seen.map(_.query) == Chunk(
                            Present("limit=50"),
                            Present("around=300&limit=10"),
                            Present("before=300&limit=1"),
                            Present("after=300&limit=100")
                        ))
                    }
                }
            }
        }
    }

    "custom sends its method, path, encoded query and body, and decodes the answer" in {
        val path = Path.init("guilds/5/roles").getOrThrow
        withLocal { local =>
            local.reply("POST /guilds/5/roles", ok("""{"id":"9","name":"mods"}""")).andThen {
                local.api(Discord.custom[Role, Role](HttpMethod.POST, path, Seq("reason" -> "a b&c=d"), Present(Role("0", "mods"))))
                    .map { role =>
                        local.seen.map { seen =>
                            assert(role == Role("9", "mods"))
                            assert(seen.map(s => (s.method, s.path, s.contentType, s.body)) ==
                                Chunk(("POST", "/guilds/5/roles", Present("application/json"), """{"id":"0","name":"mods"}""")))
                            assert(seen.map(s => HttpUrl(Present("http"), "h", 80, "/", s.query).query("reason")) ==
                                Chunk(Present("a b&c=d")))
                        }
                    }
            }
        }
    }

    "custom without a body sends none, and Unit ignores the answer's body" in {
        withLocal { local =>
            local.reply("DELETE /guilds/5/roles/9", noContent).andThen {
                local.api(Discord.custom[Unit, Unit](HttpMethod.DELETE, Path.init("guilds/5/roles/9").getOrThrow)).andThen {
                    local.seen.map(seen => assert(seen.map(s => (s.method, s.contentType, s.body)) == Chunk(("DELETE", Absent, ""))))
                }
            }
        }
    }

    // --- Discord's error answers ---

    "a code with a leaf of its own on the verb is that leaf" in {
        withLocal { local =>
            Kyo.foreach(Chunk(
                (50013, "POST /channels/111/messages"),
                (50001, "POST /channels/111/messages"),
                (10003, "POST /channels/111/messages"),
                (50007, "POST /channels/111/messages"),
                (200000, "POST /channels/111/messages"),
                (30015, "POST /channels/111/messages"),
                (50083, "POST /channels/111/messages"),
                (40005, "POST /channels/111/messages")
            )) { (code, route) =>
                local.reply(route, apiError(400, code, s"code $code")).andThen(local.api(Abort.run[DiscordSendFailure](Discord.send(
                    channelId,
                    create
                ))))
            }.map { results =>
                val method = "POST /channels/{channel.id}/messages"
                assert(results.map(_.failure) == Chunk(
                    Present(DiscordMissingPermissionsException(method, "code 50013")),
                    Present(DiscordMissingAccessException(method, "code 50001")),
                    Present(DiscordUnknownChannelException(method, "code 10003")),
                    Present(DiscordCannotMessageUserException(method, "code 50007")),
                    Present(DiscordBlockedByModerationException(method, Code(200000), "code 200000")),
                    Present(DiscordMaximumReachedException(method, Code(30015), "code 30015")),
                    Present(DiscordThreadClosedException(method, Code(50083), "code 50083")),
                    Present(DiscordPayloadTooLargeException(method, Code(40005), "code 40005"))
                ))
            }
        }
    }

    "each verb's own leaves reach it through its route" in {
        val thread = Thread.Start.init("plans").getOrThrow
        val roll   = Command.Create.init("roll", "Roll dice").getOrThrow
        withLocal { local =>
            local.reply("GET /applications/@me", ok("""{"id":"900"}""")).andThen {
                for
                    edit <- local.reply("PATCH /channels/111/messages/300", apiError(404, 10008, "m"))
                        .andThen(local.api(Abort.run[DiscordEditFailure](Discord.edit(channelId, messageId, edited))))
                    delete <- local.reply("DELETE /channels/111/messages/300", apiError(404, 10008, "m"))
                        .andThen(local.api(Abort.run[DiscordDeleteFailure](Discord.delete(channelId, messageId))))
                    react <- local.reply("PUT /channels/111/messages/300/reactions/%F0%9F%94%A5/@me", apiError(400, 10014, "e"))
                        .andThen(local.api(Abort.run[DiscordReactFailure](Discord.react(channelId, messageId, fire))))
                    maxReactions <- local.reply("PUT /channels/111/messages/300/reactions/%F0%9F%94%A5/@me", apiError(400, 30010, "r"))
                        .andThen(local.api(Abort.run[DiscordReactFailure](Discord.react(channelId, messageId, fire))))
                    unreact <- local.reply("DELETE /channels/111/messages/300/reactions/%F0%9F%94%A5/@me", apiError(400, 10014, "e"))
                        .andThen(local.api(Abort.run[DiscordUnreactFailure](Discord.unreact(channelId, messageId, fire))))
                    typing <- local.reply("POST /channels/111/typing", apiError(403, 50013, "p"))
                        .andThen(local.api(Abort.run[DiscordTypingFailure](Discord.typing(channelId))))
                    exists <- local.reply("POST /channels/111/messages/300/threads", apiError(400, 160004, "t"))
                        .andThen(local.api(Abort.run[DiscordStartThreadFailure](Discord.startThread(channelId, messageId, thread))))
                    active <- local.reply("POST /channels/111/threads", apiError(400, 160006, "a"))
                        .andThen(local.api(Abort.run[DiscordStartThreadFailure](Discord.startThread(channelId, thread))))
                    dm <- local.reply("POST /users/@me/channels", apiError(400, 10013, "u"))
                        .andThen(local.api(Abort.run[DiscordOpenDmFailure](Discord.openDm(userId))))
                    commands <- local.reply("PUT /applications/900/guilds/5/commands", apiError(404, 10004, "g"))
                        .andThen(local.api(Abort.run[DiscordSetCommandsFailure](Discord.setCommands(guildId, Chunk(roll)))))
                    channel <- local.reply("GET /channels/111", apiError(404, 10003, "c"))
                        .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
                    member <- local.reply("GET /guilds/5/members/42", apiError(404, 10007, "m"))
                        .andThen(local.api(Abort.run[DiscordMemberFailure](Discord.member(guildId, userId))))
                    message <- local.reply("GET /channels/111/messages/300", apiError(404, 10008, "m"))
                        .andThen(local.api(Abort.run[DiscordMessageFailure](Discord.message(channelId, messageId))))
                    messages <- local.reply("GET /channels/111/messages", apiError(403, 50001, "a"))
                        .andThen(local.api(Abort.run[DiscordMessagesFailure](Discord.messages(channelId, Message.Page.init().getOrThrow))))
                yield assert(Chunk(
                    edit.failure,
                    delete.failure,
                    react.failure,
                    maxReactions.failure,
                    unreact.failure,
                    typing.failure,
                    exists.failure,
                    active.failure,
                    dm.failure,
                    commands.failure,
                    channel.failure,
                    member.failure,
                    message.failure,
                    messages.failure
                ) == Chunk(
                    Present(DiscordUnknownMessageException("PATCH /channels/{channel.id}/messages/{message.id}", "m")),
                    Present(DiscordUnknownMessageException("DELETE /channels/{channel.id}/messages/{message.id}", "m")),
                    Present(DiscordUnknownEmojiException("PUT /channels/{channel.id}/messages/{message.id}/reactions/{emoji}/@me", "e")),
                    Present(DiscordMaximumReachedException(
                        "PUT /channels/{channel.id}/messages/{message.id}/reactions/{emoji}/@me",
                        Code(30010),
                        "r"
                    )),
                    Present(DiscordUnknownEmojiException("DELETE /channels/{channel.id}/messages/{message.id}/reactions/{emoji}/@me", "e")),
                    Present(DiscordMissingPermissionsException("POST /channels/{channel.id}/typing", "p")),
                    Present(DiscordThreadAlreadyExistsException("POST /channels/{channel.id}/messages/{message.id}/threads", "t")),
                    Present(DiscordMaximumReachedException("POST /channels/{channel.id}/threads", Code(160006), "a")),
                    Present(DiscordUnknownUserException("POST /users/@me/channels", "u")),
                    Present(DiscordUnknownGuildException("PUT /applications/{application.id}/guilds/{guild.id}/commands", "g")),
                    Present(DiscordUnknownChannelException("GET /channels/{channel.id}", "c")),
                    Present(DiscordUnknownMemberException("GET /guilds/{guild.id}/members/{user.id}", "m")),
                    Present(DiscordUnknownMessageException("GET /channels/{channel.id}/messages/{message.id}", "m")),
                    Present(DiscordMissingAccessException("GET /channels/{channel.id}/messages", "a"))
                ))
            }
        }
    }

    "a code whose leaf is not on the verb's row, and any code on custom, is the catch-all with the status" in {
        withLocal { local =>
            for
                send <- local.reply("POST /channels/111/messages", apiError(404, 10008, "m"))
                    .andThen(local.api(Abort.run[DiscordSendFailure](Discord.send(channelId, create))))
                expired <- local.reply("POST /channels/111/messages", apiError(404, 10015, "w"))
                    .andThen(local.api(Abort.run[DiscordSendFailure](Discord.send(channelId, create))))
                custom <- local.reply("GET /guilds/5", apiError(403, 50013, "p"))
                    .andThen(local.api(Abort.run[DiscordCustomFailure](Discord.custom[Unit, Unit](
                        HttpMethod.GET,
                        Path.init("guilds/5").getOrThrow
                    ))))
            yield assert(Chunk(send.failure, expired.failure, custom.failure) == Chunk(
                Present(DiscordOtherApiException("POST /channels/{channel.id}/messages", HttpStatus(404), Code(10008), "m")),
                Present(DiscordOtherApiException("POST /channels/{channel.id}/messages", HttpStatus(404), Code(10015), "w")),
                Present(DiscordOtherApiException("GET custom", HttpStatus(403), Code(50013), "p"))
            ))
        }
    }

    "an expired interaction token is its leaf on the interaction routes" in {
        withLocal { local =>
            val hook = s"/webhooks/900/$interactionSecret"
            for
                followUp <- local.reply(s"POST $hook", apiError(404, 10015, "w"))
                    .andThen(local.api(Abort.run[DiscordFollowUpFailure](Discord.followUp(interaction, create))))
                edit <- local.reply(s"PATCH $hook/messages/@original", apiError(401, 50027, "t"))
                    .andThen(local.api(Abort.run[DiscordEditResponseFailure](Discord.editResponse(interaction, edited))))
                delete <- local.reply(s"DELETE $hook/messages/@original", apiError(404, 10015, "w"))
                    .andThen(local.api(Abort.run[DiscordDeleteResponseFailure](Discord.deleteResponse(interaction))))
            yield assert(Chunk(followUp.failure, edit.failure, delete.failure) == Chunk(
                Present(DiscordInteractionExpiredException("POST /webhooks/{application.id}/{interaction.token}", Code(10015), "w")),
                Present(DiscordInteractionExpiredException(
                    "PATCH /webhooks/{application.id}/{interaction.token}/messages/@original",
                    Code(50027),
                    "t"
                )),
                Present(DiscordInteractionExpiredException(
                    "DELETE /webhooks/{application.id}/{interaction.token}/messages/@original",
                    Code(10015),
                    "w"
                ))
            ))
            end for
        }
    }

    "a 401 with Discord's body is the unauthorized leaf, whatever its code" in {
        withLocal { local =>
            Kyo.foreach(Chunk(0, 40001, 50014)) { code =>
                local.reply("GET /channels/111", apiError(401, code, "401: Unauthorized"))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
            }.map { results =>
                assert(results.map(_.failure) == Chunk(0, 40001, 50014).map(code =>
                    Present(DiscordUnauthorizedException("GET /channels/{channel.id}", Code(code), "401: Unauthorized"))
                ))
            }
        }
    }

    "50035 carries each refused field from the errors tree" in {
        val body =
            """{"code":50035,"message":"Invalid Form Body","errors":{"content":{"_errors":[{"code":"BASE_TYPE_MAX_LENGTH","message":"Must be 2000 or fewer in length."}]}}}"""
        withLocal { local =>
            local.reply("POST /channels/111/messages", Reply(400, body)).andThen {
                local.api(Abort.run[DiscordSendFailure](Discord.send(channelId, create))).map { result =>
                    assert(result.failure == Present(DiscordInvalidFormBodyException(
                        "POST /channels/{channel.id}/messages",
                        "Invalid Form Body",
                        Chunk(DiscordInvalidFormBodyException.FieldError(
                            Chunk("content"),
                            "BASE_TYPE_MAX_LENGTH",
                            "Must be 2000 or fewer in length."
                        ))
                    )))
                }
            }
        }
    }

    "an answer that is not Discord's error body is an unexpected status; one that is but does not decode, the error part's decode" in {
        withLocal { local =>
            for
                html <- local.reply("GET /channels/111", Reply(500, "<html>oops</html>"))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
                other <- local.reply("GET /channels/111", Reply(503, """{"error":"unavailable"}"""))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
                broken <- local.reply("GET /channels/111", Reply(400, """{"code":"x","message":"m"}"""))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
            yield assert(Chunk(html.failure, other.failure, broken.failure) == Chunk(
                Present(DiscordUnexpectedStatusException("GET /channels/{channel.id}", HttpStatus(500))),
                Present(DiscordUnexpectedStatusException("GET /channels/{channel.id}", HttpStatus(503))),
                Present(DiscordDecodeException(
                    "GET /channels/{channel.id}",
                    DiscordDecodeException.Part.Error,
                    DiscordDecodeException.Failure.TypeMismatch,
                    Chunk("code"),
                    Absent
                ))
            ))
        }
    }

    "a 2xx answer that does not decode is the response part's decode, at its path" in {
        withLocal { local =>
            for
                missing <- local.reply("GET /channels/111", ok("""{"id":"111"}"""))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
                notJson <- local.reply("GET /channels/111", ok("nope"))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
            yield
                val missingType: Maybe[DiscordException] = missing.failure
                val parse                                = notJson.failure.collect { case e: DiscordDecodeException => (e.part, e.failure) }
                assert(missingType == Present(DiscordDecodeException(
                    "GET /channels/{channel.id}",
                    DiscordDecodeException.Part.Response,
                    DiscordDecodeException.Failure.MissingField,
                    Chunk("type"),
                    Absent
                )))
                assert(parse == Present((DiscordDecodeException.Part.Response, DiscordDecodeException.Failure.Parse)))
            end for
        }
    }

    // --- Rate limits ---

    "a 429 is the leaf its scope names, with retry_after, the bucket and the code" in {
        def limited(scope: Maybe[String], global: Boolean, extra: Seq[(String, String)] = Seq.empty): Reply =
            Reply(
                429,
                s"""{"message":"You are being rate limited.","retry_after":1.5,"global":$global,"code":20028}""",
                Seq("X-RateLimit-Bucket" -> "abcd") ++ scope.fold(Seq.empty)(s => Seq("X-RateLimit-Scope" -> s)) ++ extra
            )
        withLocal { local =>
            Kyo.foreach(Chunk(
                limited(Present("user"), false),
                limited(Present("shared"), false),
                limited(Present("global"), false),
                limited(Absent, true),
                limited(Absent, false, Seq("X-RateLimit-Global" -> "true")),
                Reply(429, """{"message":"You are being rate limited.","retry_after":0.25,"global":false}""")
            )) { reply =>
                local.reply("GET /channels/111", reply).andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
            }.map { results =>
                val method = "GET /channels/{channel.id}"
                assert(results.map(_.failure) == Chunk(
                    Present(DiscordRouteRateLimitException(method, Present(1500.millis), Present(Code(20028)), Present("abcd"))),
                    Present(DiscordSharedRateLimitException(method, Present(1500.millis), Present(Code(20028)), Present("abcd"))),
                    Present(DiscordGlobalRateLimitException(method, Present(1500.millis), Present(Code(20028)))),
                    Present(DiscordGlobalRateLimitException(method, Present(1500.millis), Present(Code(20028)))),
                    Present(DiscordGlobalRateLimitException(method, Present(1500.millis), Present(Code(20028)))),
                    Present(DiscordRouteRateLimitException(method, Present(250.millis), Absent, Absent))
                ))
            }
        }
    }

    "a 429 with no Discord body is Cloudflare's block, with Retry-After when sent" in {
        withLocal { local =>
            for
                withHeader <- local.reply("GET /channels/111", Reply(429, "<html>banned</html>", Seq("Retry-After" -> "600")))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
                without <- local.reply("GET /channels/111", Reply(429, ""))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
            yield assert((withHeader.failure, without.failure) == (
                Present(DiscordBlockedException("GET /channels/{channel.id}", Present(600.seconds))),
                Present(DiscordBlockedException("GET /channels/{channel.id}", Absent))
            ))
        }
    }

    // --- Retry ---

    "with retry absent nothing is retried, a 429 and a 502 included" in {
        withLocal { local =>
            for
                limited <- local.reply("GET /channels/111", rateLimited(0.0), ok(channelJson))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
                unavailable <- local.reply("GET /channels/111", Reply(502, "bad gateway"), ok(channelJson))
                    .andThen(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
                seen <- local.seen
            yield assert((limited.isFailure, unavailable.isFailure, seen.size) == (true, true, 2))
        }
    }

    "with retry set, a 429 naming a wait and a 502 are retried until an answer" in {
        withLocal { local =>
            val retrying = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(3)))
            for
                limited <- local.reply("GET /channels/111", rateLimited(0.0), rateLimited(0.0), ok(channelJson))
                    .andThen(Discord.run(retrying)(Discord.channel(channelId)))
                unavailable <- local.reply("GET /channels/112", Reply(502, """{"message":"502: Bad Gateway","code":0}"""), ok(channelJson))
                    .andThen(Discord.run(retrying)(Discord.channel(ChannelId(112L))))
                seen <- local.seen
            yield assert((limited, unavailable, seen.map(_.path)) == (
                decoded[Channel](channelJson),
                decoded[Channel](channelJson),
                Chunk("/channels/111", "/channels/111", "/channels/111", "/channels/112", "/channels/112")
            ))
            end for
        }
    }

    "with retry set, another 5xx, a 429 naming no wait or a wait above retryMaxDelay, and a 400 are not retried" in {
        withLocal { local =>
            val retrying           = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(3)), retryMaxDelay = 60.seconds)
            def once(reply: Reply) =
                local.reply("GET /channels/111", reply, ok(channelJson))
                    .andThen(Discord.run(retrying)(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
            for
                server  <- once(Reply(500, "boom"))
                busy    <- once(Reply(503, "busy"))
                noWait  <- once(Reply(429, """{"message":"m","global":false}"""))
                tooLong <- once(rateLimited(61.0))
                refused <- once(apiError(400, 50035, "Invalid Form Body"))
                seen    <- local.seen
            yield
                assert(Chunk(server, busy, noWait, tooLong, refused).map(_.isFailure) == Chunk.fill(5)(true))
                assert(tooLong.failure == Present(DiscordRouteRateLimitException(
                    "GET /channels/{channel.id}",
                    Present(61.seconds),
                    Absent,
                    Absent
                )))
                assert(seen.size == 5)
            end for
        }
    }

    "when the schedule is done the last failure surfaces unchanged" in {
        withLocal { local =>
            val retrying = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(2)))
            local.reply("GET /channels/111", Reply(502, "a"), Reply(502, "b"), Reply(502, "c"), ok(channelJson)).andThen {
                Discord.run(retrying)(Abort.run[DiscordChannelFailure](Discord.channel(channelId))).map { result =>
                    local.seen.map { seen =>
                        assert((result.failure, seen.size) ==
                            (Present(DiscordUnexpectedStatusException("GET /channels/{channel.id}", HttpStatus(502))), 3))
                    }
                }
            }
        }
    }

    "the interaction callback is never retried" in {
        withLocal { local =>
            val retrying = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(3)))
            local.reply(s"POST /interactions/901/$interactionSecret/callback", rateLimited(0.0), noContent).andThen {
                Discord.run(retrying)(Abort.run[DiscordException](
                    kyo.internal.discord.Rest.callback(interaction, InteractionResponse.DeferredMessage())
                )).map { result =>
                    local.seen.map { seen =>
                        assert(result.isFailure)
                        assert(seen.map(s => (s.path, s.body)) ==
                            Chunk((s"/interactions/901/$interactionSecret/callback", """{"type":5}""")))
                    }
                }
            }
        }
    }

    // --- Transport, and no token in any failure ---

    "a request with no answer within requestTimeout fails as the transport's timeout, to the server's host and port" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Fiber.initUnscoped(local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))).map { fiber =>
                    local.awaitHeld(1).andThen(control.advance(local.config.requestTimeout)).andThen(fiber.get).map { result =>
                        assert(result.failure == Present(DiscordTransportException(
                            "GET /channels/{channel.id}",
                            DiscordTransportException.Kind.Timeout,
                            "127.0.0.1",
                            local.base.port,
                            Present(local.config.requestTimeout)
                        )()))
                    }
                }
            }
        }
    }

    "a request past the 100 connections the pool holds to one host fails at once as the transport's exhausted pool" in {
        withLocal { local =>
            // Time stands still here, so the global limit must admit all 101 calls without a refill.
            Discord.run(local.config.copy(globalRateLimit = 101)) {
                Env.use[Discord] { discord =>
                    Kyo.foreach(Chunk.range(0, 100))(_ =>
                        Fiber.initUnscoped(Env.run(discord)(Abort.run[DiscordChannelFailure](Discord.channel(channelId))))
                    ).map { held =>
                        local.awaitHeld(100)
                            .andThen(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))
                            .map(result => Kyo.foreachDiscard(held)(_.interrupt.unit).andThen(result))
                    }
                }
            }.map { result =>
                assert(result.failure == Present(DiscordTransportException(
                    "GET /channels/{channel.id}",
                    DiscordTransportException.Kind.PoolExhausted(100),
                    "127.0.0.1",
                    local.base.port,
                    Absent
                )()))
            }
        }
    }

    "a TLS handshake that gets no answer is bounded by requestTimeout, which kyo-http applies past the TCP connect" in {
        withCountingPeer("") { (port, _) =>
            val silent = configAt(port).copy(baseUrl = HttpUrl(Present("https"), "127.0.0.1", port, "/api/v10", Absent))
            Clock.withTimeControl { control =>
                Fiber.initUnscoped(Discord.run(silent)(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))).map { fiber =>
                    // Two sleepers: the client's global limiter refills on a timer of its own, so one alone may not be the request's.
                    control.awaitPendingSleepers(2).andThen(control.advance(silent.requestTimeout)).andThen(fiber.get).map {
                        result =>
                            assert(
                                result.failure.collect { case e: DiscordTransportException => (e.kind, e.timeout) } ==
                                    Present((DiscordTransportException.Kind.Timeout, Present(silent.requestTimeout))),
                                s"got: $result"
                            )
                    }
                }
            }
        }
    }

    "a send with files is bounded by transferTimeout, not requestTimeout" in {
        val file = File.init("a.txt", Span.from("A".getBytes(UTF_8))).getOrThrow
        withLocal { local =>
            Clock.withTimeControl { control =>
                Fiber.initUnscoped(local.api(Abort.run[DiscordSendFailure](
                    Discord.send(channelId, Message.Create.init(files = Chunk(file)).getOrThrow)
                ))).map { fiber =>
                    local.awaitHeld(1)
                        .andThen(control.advance(local.config.requestTimeout))
                        .andThen(fiber.done)
                        .map { doneAtRequestTimeout =>
                            // The defaults: 120 seconds of transferTimeout, of which requestTimeout's 10 have passed.
                            control.advance(110.seconds).andThen(fiber.get).map { result =>
                                assert(!doneAtRequestTimeout)
                                assert(result.failure.collect { case e: DiscordTransportException => e.timeout } ==
                                    Present(Present(local.config.transferTimeout)))
                            }
                        }
                }
            }
        }
    }

    "a body over maxResponseLength fails as the transport's oversized payload" in {
        val body = s"""{"id":"111","type":0,"name":"${"x" * 64}"}"""
        withLocal { local =>
            local.reply("GET /channels/111", ok(body)).andThen {
                Discord.run(local.config.copy(maxResponseLength = 16.bytes))(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))
                    .map { result =>
                        assert(result.failure == Present(DiscordTransportException(
                            "GET /channels/{channel.id}",
                            DiscordTransportException.Kind.PayloadTooLarge(body.length.bytes, 16.bytes),
                            "127.0.0.1",
                            local.base.port,
                            Absent
                        )()))
                    }
            }
        }
    }

    "a status outside 100 to 599 fails as the transport's protocol kind" in {
        withCountingPeer("HTTP/1.1 700 Beyond\r\nContent-Length: 0\r\n\r\n") { (port, _) =>
            Discord.run(configAt(port))(Abort.run[DiscordChannelFailure](Discord.channel(channelId))).map { result =>
                assert(
                    result.failure.collect { case e: DiscordTransportException => e.kind } ==
                        Present(DiscordTransportException.Kind.Protocol),
                    s"got: $result"
                )
            }
        }
    }

    "a host that does not resolve fails as the transport's DNS kind, naming the host" in {
        // RFC 6761 reserves `.invalid`: no resolver answers it.
        val unresolved = HttpUrl(Present("http"), "discord-test.invalid", 80, "/api/v10", Absent)
        val config     = configAt(80).copy(baseUrl = unresolved)
        Discord.run(config)(Abort.run[DiscordChannelFailure](Discord.channel(channelId))).map { result =>
            assert(result.failure.collect { case e: DiscordTransportException => (e.kind, e.host, e.port) } ==
                Present((DiscordTransportException.Kind.Dns, "discord-test.invalid", 80)))
        }
    }

    "on the interaction routes, every transport failure and every echoed path keeps no part of either token" in {
        val hook     = s"/webhooks/900/$interactionSecret"
        val echoBody = s"""{"code":50001,"message":"no access to $hook with Bot $tokenSecret"}"""
        def calls(using Frame): Chunk[Result[DiscordException, Any] < (Async & Env[Discord])] = Chunk(
            Abort.run[DiscordFollowUpFailure](Discord.followUp(interaction, create)),
            Abort.run[DiscordEditResponseFailure](Discord.editResponse(interaction, edited)),
            Abort.run[DiscordDeleteResponseFailure](Discord.deleteResponse(interaction)),
            Abort.run[DiscordException](kyo.internal.discord.Rest.callback(interaction, InteractionResponse.DeferredMessage()))
        )
        def clean(results: Chunk[Result[DiscordException, Any]]): Unit =
            results.foreach { result =>
                assert(result.isFailure, s"expected a failure, got $result")
                result.failure.foreach(e =>
                    val text = rendered(e)
                    assert(!text.contains(interactionSecret) && !text.contains(tokenSecret), s"a token leaked: $text")
                )
            }
        // A client per call: the peers answer once per connection, and a client would send its next call on the pooled one.
        def each(port: Int) = Kyo.foreach(Chunk.range(0, 4))(i => Discord.run(configAt(port))(calls(i)))
        for
            echoed <-
                withCountingPeer(response(403, echoBody, Seq("Location" -> s"http://127.0.0.1/api/v10$hook")))((port, _) => each(port))
            redirected <- withCountingPeer(response(301, "", Seq("Location" -> s"http://127.0.0.1/api/v10$hook")))((port, _) => each(port))
            chunked    <- withCountingPeer(badChunked)((port, _) => each(port))
            statusLine <- withCountingPeer("HTTP/1.1 abc Nope\r\nContent-Length: 0\r\n\r\n")((port, _) => each(port))
            closing    <- Kyo.foreach(Chunk.range(0, 4))(i => withClosingAfterHead(port => Discord.run(configAt(port))(calls(i))))
            refused    <- closedPort.map(each)
        yield
            Chunk(echoed, redirected, chunked, statusLine, closing, refused).foreach(clean)
            assert(echoed.map(_.failure.collect { case e: DiscordApiException => e.description }) ==
                Chunk.fill(4)(Present("no access to /webhooks/900/<redacted> with Bot <redacted>")))
            assert(redirected.map(_.failure.collect { case e: DiscordUnexpectedStatusException => e.status }) ==
                Chunk.fill(4)(Present(HttpStatus(301))))
            assert(Chunk(chunked, statusLine, closing, refused).map(_.map(_.failure.collect {
                case e: DiscordTransportException => e.kind
            })) == Chunk(
                Chunk.fill(4)(Present(DiscordTransportException.Kind.Protocol)),
                Chunk.fill(4)(Present(DiscordTransportException.Kind.Protocol)),
                Chunk.fill(4)(Present(DiscordTransportException.Kind.ConnectionClosed)),
                Chunk.fill(4)(Present(DiscordTransportException.Kind.Connect))
            ))
        end for
    }

    // --- The caller's configuration reaches no request ---

    "a caller's client filter, installed by withConfig or on a client bound with let, sees no request of the module" in {
        withLocal { local =>
            AtomicRef.init(Chunk.empty[String]).map { filtered =>
                val recording = recorder(filtered)
                local.reply("GET /channels/111", ok(channelJson)).andThen {
                    HttpClient.withConfig(_.filter(recording))(local.api(Discord.channel(channelId))).andThen {
                        HttpClient.init().map { callerClient =>
                            HttpClient.let(callerClient)(HttpClient.withConfig(_.filter(recording).tls(HttpTlsConfig(trustAll = true)))(
                                local.api(Discord.channel(channelId))
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
        withCountingPeer(response(200, channelJson)) { (port, accepted) =>
            val url = HttpUrl(Present("http"), "127.0.0.1", port, "/", Absent)
            HttpClient.init().map { callerClient =>
                HttpClient.let(callerClient)(HttpClient.withConfig(_.tls(HttpTlsConfig(trustAll = true)))(
                    HttpClient.getText(url.copy(path = "/caller"))
                )).andThen(Discord.run(configAt(port))(Discord.channel(channelId)))
                    .andThen(accepted.get.map(n => assert(n == 2)))
            }
        }
    }

    "tls comes from the config: the default refuses a self-signed server, the config's trust reaches it, and the caller's does not" in {
        withLocalTls { local =>
            local.reply("GET /channels/111", ok(channelJson)).andThen {
                val trusting = local.config.copy(tls = kyo.internal.TlsTestHelper.clientTlsConfig)
                for
                    refused <- local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))
                    ambient <- HttpClient.withConfig(_.tls(kyo.internal.TlsTestHelper.clientTlsConfig))(
                        local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))
                    )
                    trusted <- Discord.run(trusting)(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))
                yield
                    val refusal = Present(DiscordTransportException.Kind.Tls)
                    val kinds   = Chunk(refused, ambient).map(_.failure.collect { case e: DiscordTransportException => e.kind })
                    assert(kinds == Chunk(refusal, refusal), s"got: $refused and $ambient")
                    assert(trusted == Result.succeed(decoded[Channel](channelJson)), s"got: $trusted")
                end for
            }
        }
    }

    "transport comes from the config: a head over the default limit fails, the config's larger limit reads it, and the caller's does not" in {
        val padded = Reply(200, channelJson, Seq("X-Pad" -> "a" * (70 * 1024)))
        val wider  = HttpTransportConfig.default.maxHeaderSize(256 * 1024)
        withLocal { local =>
            local.reply("GET /channels/111", padded).andThen {
                for
                    refused <- local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))
                    ambient <- HttpClient.withConfig(_.transportConfig(wider))(
                        local.api(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))
                    )
                    read <- Discord.run(local.config.copy(transport = wider))(Abort.run[DiscordChannelFailure](Discord.channel(channelId)))
                yield
                    val refusal = Present(DiscordTransportException(
                        "GET /channels/{channel.id}",
                        DiscordTransportException.Kind.Protocol,
                        "127.0.0.1",
                        local.base.port,
                        Absent
                    )())
                    assert((refused.failure, ambient.failure) == (refusal, refusal), s"got: $refused and $ambient")
                    assert(read == Result.succeed(decoded[Channel](channelJson)), s"got: $read")
                end for
            }
        }
    }

end DiscordTest

object DiscordTest:

    /** A body `custom` sends and reads. */
    final case class Role(id: String, name: String) derives Schema, CanEqual

    // Built from parts: a failure's message quotes the source lines around its frame, which would otherwise show the literal.
    val tokenSecret: String       = Seq("MTA0OTI3NjU0MzIxMDk4NzY1", "rest", "TESTsecretPart").mkString(".")
    val interactionSecret: String = Seq("aW50ZXJhY3Rpb24", "tokenLEAK", "check").mkString("_")

    def interaction(using Frame): Discord.Interaction.Ref =
        Discord.Interaction.Ref(
            Discord.ApplicationId(900L),
            Discord.InteractionId(901L),
            Discord.InteractionToken.init(interactionSecret).getOrThrow
        )

    def userAgent: String = s"DiscordBot (https://github.com/getkyo/kyo, ${kyo.internal.discord.DiscordVersion.value})"

    /** A config whose token holds `tokenSecret`, on a peer at `port` under `/api/v10`. */
    def configAt(port: Int)(using Frame): DiscordConfig =
        DiscordConfig.init(
            Discord.Token.init(tokenSecret).getOrThrow,
            Discord.Intents.Guilds,
            baseUrl = HttpUrl(Present("http"), "127.0.0.1", port, "/api/v10", Absent)
        ).getOrThrow

    /** One request the local API received: its method, its path under `/api/v10`, the raw query, the headers the module sets and the
      * body as text.
      */
    final case class Seen(
        method: String,
        path: String,
        query: Maybe[String],
        authorization: Maybe[String],
        userAgent: Maybe[String],
        contentType: Maybe[String],
        body: String
    ) derives CanEqual

    final case class Reply(status: HttpStatus, body: String, headers: Seq[(String, String)])

    object Reply:
        inline def apply(inline status: Int, body: String, headers: Seq[(String, String)] = Seq.empty): Reply =
            new Reply(HttpStatus(status), body, headers)

    def ok(body: String): Reply = Reply(200, body)

    val noContent: Reply = Reply(204, "")

    inline def apiError(inline status: Int, code: Int, message: String): Reply =
        Reply(status, s"""{"code":$code,"message":"$message"}""")

    def rateLimited(retryAfter: Double): Reply =
        Reply(
            429,
            s"""{"message":"You are being rate limited.","retry_after":$retryAfter,"global":false}""",
            Seq("X-RateLimit-Scope" -> "user")
        )

    /** A leaf's message, its rendering, every field, and its cause's rendering, message and fields, as one string. */
    def rendered(e: DiscordException): String =
        def fields(a: Any): String =
            a match
                case p: Product => p.productIterator.mkString("|")
                case other      => String.valueOf(other)
        val cause = Maybe(e.getCause()).fold("")(c => c.toString + "|" + c.getMessage + "|" + fields(c))
        e.getMessage + "|" + e.toString + "|" + fields(e) + "|" + cause
    end rendered

    /** The local API's recorded requests and queued replies. */
    final class Backend(replies: AtomicRef[Map[String, Chunk[Reply]]], requests: AtomicRef[Chunk[Seen]], val held: Channel[Unit]):
        def reply(route: String, rs: Reply*)(using Frame): Unit < Sync = replies.updateAndGet(_.updated(route, Chunk.from(rs))).unit

        def seen(using Frame): Chunk[Seen] < Sync = requests.get

        private[DiscordTest] def next(route: String)(using Frame): Maybe[Reply] < Sync =
            replies.getAndUpdate(m => m.get(route).fold(m)(q => if q.size > 1 then m.updated(route, q.drop(1)) else m))
                .map(m => Maybe.fromOption(m.get(route)).flatMap(_.headMaybe))

        private[DiscordTest] def record(s: Seen)(using Frame): Unit < Sync = requests.updateAndGet(_ :+ s).unit
    end Backend

    /** A local Discord API under `/api/v10`: each `"<METHOD> <path>"` answers the next reply queued for it, the last one repeating; a
      * route with nothing queued holds the request open until the client goes away, and `awaitHeld(n)` waits for `n` such requests.
      *
      * The server and every leaf on it run under `Clock.withTimeControl`, so no deadline fires unless the leaf advances time.
      */
    final class Local(val base: HttpUrl, backend: Backend):
        def config(using Frame): DiscordConfig = configAt(base.port).copy(baseUrl = base)

        def api[A, S](v: A < (S & Env[Discord]))(using Frame): A < (S & Async) = Discord.run(config)(v)

        def reply(route: String, rs: Reply*)(using Frame): Unit < Sync = backend.reply(route, rs*)

        def seen(using Frame): Chunk[Seen] < Sync = backend.seen

        def awaitHeld(n: Int)(using Frame): Unit < (Async & Abort[Closed]) = Kyo.foreachDiscard(Chunk.range(0, n))(_ => backend.held.take)
    end Local

    def withLocal[A](test: Local => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        serveLocal(HttpServerConfig.default.port(0).host("127.0.0.1"), "http")(test)

    /** [[withLocal]] served over TLS with a self-signed certificate, which the default `tls` does not trust. */
    def withLocalTls[A](test: Local => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        serveLocal(HttpServerConfig.default.port(0).host("127.0.0.1").tls(kyo.internal.TlsTestHelper.serverTlsConfig), "https")(test)

    private def serveLocal[A](serverConfig: HttpServerConfig, scheme: String)(test: Local => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        def respond(r: Reply) =
            r.headers.foldLeft(HttpResponse(r.status).addField("body", r.body))((resp, h) => resp.addHeader(h._1, h._2))
        Clock.withTimeControl { _ =>
            for
                replies  <- AtomicRef.init(Map.empty[String, Chunk[Reply]])
                requests <- AtomicRef.init(Chunk.empty[Seen])
                held     <- Channel.init[Unit](256)
                backend = Backend(replies, requests, held)
                handle  = (method: HttpMethod, req: HttpRequest[?], body: String) =>
                    val path = req.url.path.stripPrefix("/api/v10")
                    val seen = Seen(
                        method.name,
                        path,
                        req.url.rawQuery,
                        req.headers.get("Authorization"),
                        req.headers.get("User-Agent"),
                        req.headers.get("Content-Type"),
                        body
                    )
                    backend.record(seen).andThen(backend.next(s"${method.name} $path")).map {
                        case Present(r) => respond(r)
                        case Absent     => held.put(()).andThen(Async.never)
                    }
                rest      = HttpPath.Capture.Rest("path")
                getRoute  = HttpRoute.getRaw(rest).response(_.bodyText).handler(req => handle(HttpMethod.GET, req, ""))
                delRoute  = HttpRoute.deleteRaw(rest).response(_.bodyText).handler(req => handle(HttpMethod.DELETE, req, ""))
                postRoute = HttpRoute.postRaw(rest).request(_.bodyBinary).response(_.bodyText).handler(req =>
                    handle(HttpMethod.POST, req, new String(req.fields.body.toArray, UTF_8))
                )
                putRoute = HttpRoute.putRaw(rest).request(_.bodyBinary).response(_.bodyText).handler(req =>
                    handle(HttpMethod.PUT, req, new String(req.fields.body.toArray, UTF_8))
                )
                patchRoute = HttpRoute.patchRaw(rest).request(_.bodyBinary).response(_.bodyText).handler(req =>
                    handle(HttpMethod.PATCH, req, new String(req.fields.body.toArray, UTF_8))
                )
                server <- HttpServer.init(serverConfig)(getRoute, delRoute, postRoute, putRoute, patchRoute)
                result <- test(Local(HttpUrl(Present(scheme), "127.0.0.1", server.port, "/api/v10", Absent), backend))
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

    /** A whole HTTP/1.1 response with `body` as JSON. */
    def response(status: Int, body: String, headers: Seq[(String, String)] = Seq.empty): String =
        val head = headers.map((k, v) => s"$k: $v\r\n").mkString
        s"HTTP/1.1 $status Status\r\nContent-Type: application/json\r\n${head}Content-Length: ${body.getBytes(UTF_8).length}\r\n\r\n$body"

    /** A 200 whose chunked body's first size line is not hexadecimal. */
    val badChunked: String = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n{}\r\n0\r\n\r\n"

    /** A port nothing listens on: a listener's, closed. */
    def closedPort(using Frame): Int < (Async & Abort[Any]) =
        Sync.Unsafe.defer(kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 1)(_ => ())).map { fiber =>
            // Unsafe: the listener is kyo-net's raw tier; it is closed at once, so only its port is kept.
            fiber.safe.use(listener => Sync.Unsafe.defer(listener.close()).andThen(listener.port))
        }

    /** A peer that answers every connection with `response`, queued on accept, and counts the connections it accepted. The leaf
      * runs under `Clock.withTimeControl`, as on [[withLocal]].
      *
      * Closing the listener does not close what it accepted, so each accepted connection is closed when the test's Scope ends;
      * otherwise its socket outlives the leaf and the run's leak check reports it.
      */
    def withCountingPeer[A](response: String)(test: (Int, AtomicInt) => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        val bytes = response.getBytes(UTF_8)
        Clock.withTimeControl { _ =>
            AtomicInt.init.map { accepted =>
                AtomicRef.init(Chunk.empty[kyo.net.Connection]).map { open =>
                    Sync.Unsafe.defer {
                        kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16) { conn =>
                            // Unsafe: the accept callback runs outside the effect system; it counts and keeps the connection and
                            // queues the answer, which HTTP/1.1 lets a server send before it has read the request.
                            discard(accepted.unsafe.incrementAndGet())
                            discard(open.unsafe.updateAndGet(_.append(conn)))
                            discard(conn.outbound.offer(Span.fromUnsafe(bytes)))
                        }
                    }.map { fiber =>
                        // Unsafe: the listener and the connections it accepted are kyo-net's raw tier; all are closed when the test's
                        // Scope ends, the listener first so nothing is accepted after the connections are closed.
                        fiber.safe.use { listener =>
                            Scope.ensure(Sync.Unsafe.defer {
                                listener.close()
                                open.unsafe.get().foreach(_.close())
                            }).andThen(test(listener.port, accepted))
                        }
                    }
                }
            }
        }
    end withCountingPeer

    /** A peer that, once `request` is running against it, reads the request, answers a head promising 100 bytes with 6 of them, and
      * closes. The request runs under `Clock.withTimeControl`, as on [[withLocal]].
      */
    def withClosingAfterHead[A](request: Int => A < Async)(using Frame): A < (Async & Abort[Any] & Scope) =
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"id\":"
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
                                        .andThen(Abort.run[Closed](conn.outbound.safe.put(Span.fromUnsafe(head.getBytes(UTF_8)))))
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

end DiscordTest
