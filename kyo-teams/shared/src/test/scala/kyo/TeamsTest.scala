package kyo

import TeamsLocal.*

class TeamsTest extends kyo.test.Test[Any]:

    // A local HttpServer's closed connections are reaped on the selector's next pass, which the socket leak check sees as an open
    // descriptor.
    override def config = super.config.leakCheckSockets(false)

    private val hello = Teams.Message.Create.text("hello")

    private def tree(json: String): Structure.Value = TeamsJsonTree(json)

    "send posts the message to the conversation's activities under the service URL, with the bearer token, and answers the id" in {
        withLocal { local =>
            local.reply("send", resource("1:abc")).andThen {
                local.api(Teams.send(local.reference, hello)).map { id =>
                    local.seen("send").map { seen =>
                        assert(id == Teams.ActivityId.init("1:abc").getOrThrow)
                        assert(seen.map(s => (s.method, s.path, s.authorization, s.contentType)) == Chunk((
                            "POST",
                            s"/amer/v3/conversations/$conversationSegment/activities",
                            Present(s"Bearer $tokenText"),
                            Present("application/json")
                        )))
                        assert(seen.map(s => tree(s.body)) == Chunk(tree("""{"text":"hello","type":"message"}""")))
                    }
                }
            }
        }
    }

    "run(client) provides a client init built, and two runs on it share one cached token" in {
        withLocal { local =>
            local.reply("send", resource("1:a"), resource("1:b")).andThen {
                Teams.init(local.config).map { teams =>
                    for
                        first  <- Teams.run(teams)(Teams.send(local.reference, hello))
                        second <- Teams.run(teams)(Teams.send(local.reference, hello))
                        tokens <- local.seen("token")
                    yield
                        assert(Chunk(first, second).map(_.value) == Chunk("1:a", "1:b"))
                        assert(tokens.size == 1, s"two runs on one client fetched ${tokens.size} tokens")
                }
            }
        }
    }

    "init's client is closed when its Scope ends, so a verb on it fails as a transport failure" in {
        withLocal { local =>
            Scope.run(Teams.init(local.config)).map { teams =>
                Abort.run[TeamsSendFailure](Teams.run(teams)(Teams.send(local.reference, hello))).map { result =>
                    assert(result.failure.exists(_.isInstanceOf[TeamsTransportException]), s"got: $result")
                }
            }
        }
    }

    "close closes an initUnscoped client, twice without failing, and a verb on it then fails as a transport failure" in {
        withLocal { local =>
            Teams.initUnscoped(local.config).map { teams =>
                Teams.close(teams).andThen(Teams.close(teams)).andThen {
                    Abort.run[TeamsSendFailure](Teams.run(teams)(Teams.send(local.reference, hello))).map { result =>
                        assert(result.failure.exists(_.isInstanceOf[TeamsTransportException]), s"got: $result")
                    }
                }
            }
        }
    }

    "reply posts under the activity it answers, from a reference or from the Activity itself" in {
        withLocal { local =>
            val to = Teams.ActivityId.init("1:parent").getOrThrow
            local.reply("reply", resource("1:r1"), resource("1:r2")).andThen {
                val activity = Teams.Activity.Typing(Teams.Activity.Common(
                    id = to,
                    serviceUrl = local.serviceUrl,
                    channel = Teams.BotChannel.MsTeams,
                    sender = Teams.Account(Teams.UserId.init("29:user").getOrThrow),
                    conversation = Teams.ConversationAccount(conversation),
                    recipient = Teams.Account(Teams.UserId.init("28:bot").getOrThrow)
                ))
                local.api(Teams.reply(local.reference, to, hello).map(a => Teams.reply(activity, hello).map(b => (a, b)))).map { ids =>
                    local.seen("reply").map { seen =>
                        assert(ids == (Teams.ActivityId.init("1:r1").getOrThrow, Teams.ActivityId.init("1:r2").getOrThrow))
                        assert(seen.map(_.path) == Chunk.fill(2)(s"/amer/v3/conversations/$conversationSegment/activities/1%3Aparent"))
                    }
                }
            }
        }
    }

    "edit puts the new message, delete deletes, and typing posts a typing Activity" in {
        withLocal { local =>
            val activity = Teams.ActivityId.init("1:abc").getOrThrow
            local.reply("edit", resource("1:abc"))
                .andThen(local.reply("delete", json("")))
                .andThen(local.reply("typing", json("")))
                .andThen {
                    local.api(Teams.edit(local.reference, activity, hello).map(id =>
                        Teams.delete(local.reference, activity).andThen(Teams.typing(local.reference)).andThen(id)
                    )).map { id =>
                        local.seen.map { seen =>
                            val calls = seen.filter(_.label != "token")
                            assert(id == activity)
                            assert(calls.map(s => (s.method, s.path, s.label)) == Chunk(
                                ("PUT", s"/amer/v3/conversations/$conversationSegment/activities/1%3Aabc", "edit"),
                                ("DELETE", s"/amer/v3/conversations/$conversationSegment/activities/1%3Aabc", "delete"),
                                ("POST", s"/amer/v3/conversations/$conversationSegment/activities", "typing")
                            ))
                            assert(tree(calls(2).body) == tree("""{"type":"typing"}"""))
                        }
                    }
                }
        }
    }

    "createConversation posts the parameters and answers a reference to the new conversation" in {
        withLocal { local =>
            val tenant = Teams.TenantId.init("72f988bf-86f1-41af-91ab-2d7cd011db47").getOrThrow
            val bot    = Teams.Account(Teams.UserId.init("28:bot").getOrThrow)
            val create = Teams.Conversation.Create(
                bot = Present(bot),
                members = Chunk(Teams.Account(Teams.UserId.init("29:user").getOrThrow)),
                tenantId = Present(tenant),
                activity = Present(hello)
            )
            local.reply("create", json("""{"id":"a:new","activityId":"1:first"}""")).andThen {
                local.api(Teams.createConversation(local.serviceUrl, create)).map { reference =>
                    local.seen("create").map { seen =>
                        assert(reference == Teams.ConversationReference(
                            serviceUrl = local.serviceUrl,
                            conversation =
                                Teams.ConversationAccount(Teams.ConversationId.init("a:new").getOrThrow, tenantId = Present(tenant)),
                            bot = Present(bot),
                            activityId = Present(Teams.ActivityId.init("1:first").getOrThrow)
                        ))
                        assert(seen.map(s => (s.path, tree(s.body))) == Chunk(("/amer/v3/conversations", tree(Json.encode(create)))))
                    }
                }
            }
        }
    }

    "createConversation answers the service URL the Bot Connector names when it names one" in {
        withLocal { local =>
            val other = local.base.copy(path = "/emea/").full
            local.reply("create", json(s"""{"id":"a:new","serviceUrl":"$other"}""")).andThen {
                local.api(Teams.createConversation(local.serviceUrl, Teams.Conversation.Create())).map { reference =>
                    assert(reference.serviceUrl.value == other)
                }
            }
        }
    }

    "members reads a page with its size and continuation, and member reads one member" in {
        withLocal { local =>
            val page = Teams.Member.Page.init(100, Present("token+/=1")).getOrThrow
            local.reply("members", json("""{"members":[{"id":"29:a","name":"Ada"}],"continuationToken":"next"}"""))
                .andThen(local.reply("member", json("""{"id":"29:a","name":"Ada","role":"user"}""")))
                .andThen {
                    local.api(Teams.members(local.reference, page).map(paged =>
                        Teams.members(local.reference).andThen(Teams.member(local.reference, Teams.UserId.init("29:a").getOrThrow))
                            .map(paged -> _)
                    )).map { (paged, member) =>
                        local.seen.map { seen =>
                            val ada = Teams.Account(Teams.UserId.init("29:a").getOrThrow, Present("Ada"))
                            assert(paged == Teams.Member.Paged(Chunk(ada), Present("next")))
                            assert(member == ada.copy(role = Present(Teams.Account.Role.User)))
                            assert(seen.filter(_.label == "members").map(s =>
                                (
                                    s.path,
                                    s.query.map(q => HttpUrl.fromUri("/?" + q)).map(u =>
                                        (u.query("pageSize"), u.query("continuationToken"))
                                    )
                                )
                            ) == Chunk(
                                (
                                    s"/amer/v3/conversations/$conversationSegment/pagedmembers",
                                    Present((Present("100"), Present("token+/=1")))
                                ),
                                (s"/amer/v3/conversations/$conversationSegment/pagedmembers", Present((Present("200"), Absent)))
                            ))
                            assert(seen.filter(_.label == "member").map(_.path) ==
                                Chunk(s"/amer/v3/conversations/$conversationSegment/members/29%3Aa"))
                        }
                    }
                }
        }
    }

    "custom sends any method to segments under the service URL, encoding the body and decoding the answer" in {
        final case class Note(text: String) derives Schema, CanEqual
        withLocal { local =>
            val path = Teams.Path.init("v3", "conversations", conversation.value, "custom-route").getOrThrow
            local.reply("custom", json("""{"text":"back"}"""), json("")).andThen {
                local.api(
                    Teams.custom[Note, Note](
                        local.serviceUrl,
                        Teams.Method.Post,
                        path,
                        HttpQueryParams.init("q", "a b"),
                        Present(Note("out"))
                    )
                        .map(n => Teams.custom[Unit, Unit](local.serviceUrl, Teams.Method.Delete, path).andThen(n))
                ).map { note =>
                    local.seen("custom").map { seen =>
                        assert(note == Note("back"))
                        assert(seen.map(s => (s.method, s.path, s.body)) == Chunk(
                            ("POST", s"/amer/v3/conversations/$conversationSegment/custom-route", """{"text":"out"}"""),
                            ("DELETE", s"/amer/v3/conversations/$conversationSegment/custom-route", "")
                        ))
                        assert(seen.head.query.map(q => HttpUrl.fromUri("/?" + q).query("q")) == Present(Present("a b")))
                    }
                }
            }
        }
    }

    "a service URL whose origin serviceHosts does not list is refused before a token is requested or anything is sent" in {
        withLocal { local =>
            val elsewhere = Teams.ConversationReference(
                Teams.ServiceUrl.init("https://smba.trafficmanager.net/amer/").getOrThrow,
                Teams.ConversationAccount(conversation)
            )
            val otherPort =
                local.reference.copy(serviceUrl = Teams.ServiceUrl.init(local.base.copy(port = 1, path = "/amer/").full).getOrThrow)
            local.api(
                Abort.run[TeamsSendFailure](Teams.send(elsewhere, hello)).map(a =>
                    Abort.run[TeamsCustomFailure](Teams.custom[Unit, Unit](
                        otherPort.serviceUrl,
                        Teams.Method.Get,
                        Teams.Path.init("v3").getOrThrow
                    )).map(a -> _)
                )
            ).map { (send, custom) =>
                local.seen.map { seen =>
                    assert(send == Result.fail(TeamsRefusedUrlException(Teams.Routes.Send)))
                    assert(custom == Result.fail(TeamsRefusedUrlException("GET custom")))
                    assert(seen == Chunk.empty)
                }
            }
        }
    }

    private def sendFailing(local: Local, answer: Reply)(using Frame): Result[TeamsSendFailure, Teams.ActivityId] < (Async & Abort[Any]) =
        local.reply("send", answer).andThen(local.api(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))))

    "each ErrorResponse a send can receive is its own leaf, with the description and the operation id" in {
        val operation = Chunk("X-Correlating-OperationId" -> "op-1")
        val cases     = Chunk(
            (HttpStatus(400), "BadArgument")                -> TeamsBadArgumentException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(400), "Bad Argument")               -> TeamsBadArgumentException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(401), "BotNotRegistered")           -> TeamsBotNotRegisteredException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(403), "BotDisabledByAdmin")         -> TeamsBotDisabledByAdminException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(403), "BotNotInConversationRoster") -> TeamsBotNotInConversationException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(403), "ConversationBlockedByUser")  ->
                TeamsConversationBlockedByUserException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(403), "ForbiddenOperationException") -> TeamsNotInstalledException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(403), "InvalidBotApiHost")           -> TeamsInvalidBotApiHostException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(403), "NotEnoughPermissions")        -> TeamsNotEnoughPermissionsException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(404), "ConversationNotFound")        -> TeamsConversationNotFoundException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(412), "PreconditionFailed")          -> TeamsPreconditionFailedException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(413), "MessageSizeTooBig")           -> TeamsMessageTooLargeException(Teams.Routes.Send, "d", Present("op-1")),
            (HttpStatus(404), "BotNotRegistered")            ->
                TeamsOtherApiException(Teams.Routes.Send, HttpStatus(404), "BotNotRegistered", "d", Present("op-1")),
            (HttpStatus(500), "InternalServerError") ->
                TeamsOtherApiException(Teams.Routes.Send, HttpStatus(500), "InternalServerError", "d", Present("op-1"))
        )
        withLocal { local =>
            Kyo.foreach(cases) { case ((status, code), _) => sendFailing(local, errorWith(status, code, "d", operation)) }.map { results =>
                assert(results == cases.map((_, leaf) => Result.fail(leaf)))
            }
        }
    }

    "a leaf the operation cannot receive is the catch-all, keeping the status, code and description" in {
        withLocal { local =>
            val activity = Teams.ActivityId.init("1:abc").getOrThrow
            local.reply("delete", error(403, "ConversationBlockedByUser", "d"))
                .andThen(local.reply("reply", error(404, "ActivityNotFoundInConversation", "gone")))
                .andThen(local.reply("custom", error(400, "BadArgument", "e")))
                .andThen {
                    local.api(
                        Abort.run[TeamsDeleteFailure](Teams.delete(local.reference, activity)).map { delete =>
                            Abort.run[TeamsReplyFailure](Teams.reply(local.reference, activity, hello)).map { reply =>
                                Abort.run[TeamsCustomFailure](Teams.custom[Unit, Unit](
                                    local.serviceUrl,
                                    Teams.Method.Get,
                                    Teams.Path.init("v3", "conversations", conversation.value, "custom-route").getOrThrow
                                )).map(custom => (delete, reply, custom))
                            }
                        }
                    )
                }.map { (delete, reply, custom) =>
                    assert(delete == Result.fail(
                        TeamsOtherApiException(Teams.Routes.Delete, HttpStatus(403), "ConversationBlockedByUser", "d", Absent)
                    ))
                    assert(reply == Result.fail(TeamsActivityNotFoundException(Teams.Routes.Reply, "gone", Absent)))
                    assert(custom == Result.fail(TeamsOtherApiException("GET custom", HttpStatus(400), "BadArgument", "e", Absent)))
                }
        }
    }

    "a write to a user who blocked the bot is MessageWritesBlocked, read from the JSON inside its message" in {
        val inner = """{\"subCode\":\"MessageWritesBlocked\",\"message\":\"Thread is blocked\"}"""
        withLocal { local =>
            sendFailing(local, Reply(HttpStatus(403), s"""{"errorCode":209,"message":"$inner"}""")).map { result =>
                assert(result == Result.fail(TeamsMessageWritesBlockedException(Teams.Routes.Send, "Thread is blocked", Absent)))
            }
        }
    }

    "429 is the rate-limit leaf with its Retry-After and code; a status with no ErrorResponse is unexpected" in {
        withLocal { local =>
            for
                limited <- sendFailing(local, error(429, "TooManyRequests", "slow down", Chunk("Retry-After" -> "7")))
                bare    <- sendFailing(local, Reply(HttpStatus(429), "", Chunk("Retry-After" -> "Wed, 21 Oct 2015 07:28:00 GMT")))
                html    <- sendFailing(local, Reply(HttpStatus(502), "<html>Bad Gateway</html>"))
                denied  <- sendFailing(local, Reply(HttpStatus(403), """{"errorCode":209,"message":"not json"}"""))
            yield
                assert(limited ==
                    Result.fail(TeamsRateLimitException(Teams.Routes.Send, Present(7.seconds), Present("TooManyRequests"), Absent)))
                assert(bare == Result.fail(TeamsRateLimitException(Teams.Routes.Send, Absent, Absent, Absent)))
                assert(html == Result.fail(TeamsUnexpectedStatusException(Teams.Routes.Send, HttpStatus(502))))
                assert(denied == Result.fail(TeamsUnexpectedStatusException(Teams.Routes.Send, HttpStatus(403))))
            end for
        }
    }

    "a 2xx answer that does not decode is the decode leaf for the response, quoting nothing of it" in {
        // Built from parts: a failure's rendering quotes the source lines around its frame.
        val words = Chunk("private", "words").mkString("_")
        withLocal { local =>
            for
                missing   <- sendFailing(local, json(s"""{"text":"$words"}"""))
                broken    <- sendFailing(local, json("""{"id":}"""))
                truncated <- sendFailing(local, json("""{"id":"""))
            yield
                assert(
                    missing == Result.fail(TeamsDecodeException(
                        Teams.Routes.Send,
                        TeamsDecodeException.Part.Response,
                        TeamsDecodeException.Failure.MissingField,
                        Chunk("id"),
                        Absent
                    )),
                    s"got: ${missing.failure.map(fieldsOf)}"
                )
                assert(
                    broken == Result.fail(TeamsDecodeException(
                        Teams.Routes.Send,
                        TeamsDecodeException.Part.Response,
                        TeamsDecodeException.Failure.Parse,
                        Chunk("id"),
                        Present(6)
                    )),
                    s"got: ${broken.failure.map(fieldsOf)}"
                )
                assert(
                    truncated == Result.fail(TeamsDecodeException(
                        Teams.Routes.Send,
                        TeamsDecodeException.Part.Response,
                        TeamsDecodeException.Failure.TruncatedInput,
                        Chunk.empty,
                        Absent
                    )),
                    s"got: ${truncated.failure.map(fieldsOf)}"
                )
                assert(!missing.failure.exists(e => rendered(e).contains(words)))
            end for
        }
    }

    "an answer echoing the token or the client secret, raw or percent-encoded, keeps neither" in {
        withLocal { local =>
            val echoed = s"got Bearer $tokenText and ${kyo.internal.teams.ServiceUrls.encode(secretText)} and $secretText"
            sendFailing(local, error(400, "BadArgument", echoed)).map { result =>
                assert(result == Result.fail(TeamsBadArgumentException(
                    Teams.Routes.Send,
                    "got Bearer <redacted> and <redacted> and <redacted>",
                    Absent
                )))
                assert(!result.failure.exists(e => rendered(e).contains(tokenText) || rendered(e).contains(secretText)))
            }
        }
    }

    "an answer echoing a managed identity's header keeps none of it" in {
        // Built from parts: a failure's rendering quotes the source lines around its frame.
        val headerText = Chunk("local", "identity", "header").mkString("-")
        withLocal { local =>
            val header = Teams.IdentityHeader.init(headerText).getOrThrow
            val config = local.configWith(TeamsConfig.Credential.ManagedIdentity(local.base.copy(path = "/MSI/token"), header))
            local.reply("msi", json(s"""{"token_type":"Bearer","expires_on":"3600","access_token":"$tokenText"}"""))
                .andThen(local.reply("send", error(400, "BadArgument", s"got $headerText")))
                .andThen(Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))))
                .map { result =>
                    assert(result == Result.fail(TeamsBadArgumentException(Teams.Routes.Send, "got <redacted>", Absent)))
                    assert(!result.failure.exists(e => rendered(e).contains(headerText)))
                }
        }
    }

    /** Runs `v` on its own fiber and advances virtual time a second at a time until it is done. The request timeout is an hour, so
      * no request in flight can time out while time moves.
      */
    private def advancingUntilDone[A](control: Clock.TimeControl, v: => A < (Async & Abort[Any]))(using Frame): A < (Async & Abort[Any]) =
        Fiber.initUnscoped(v).map { fiber =>
            Loop.foreach {
                fiber.done.map(done => if done then Loop.done else control.advance(1.second).andThen(Loop.continue))
            }.andThen(fiber.get)
        }

    "with a retry schedule, a retried status is sent again and its success answered" in {
        Clock.withTimeControl { control =>
            withLocal { local =>
                val config = local.config.copy(
                    retry = Present(Schedule.fixed(1.second).take(3)),
                    requestTimeout = 1.hour
                )
                local.reply("send", error(503, "ServiceUnavailable", "d"), error(412, "PreconditionFailed", "d"), resource("1:a")).andThen {
                    advancingUntilDone(control, Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))).map {
                        result =>
                            local.seen("send").map { sends =>
                                assert(result == Result.succeed(Teams.ActivityId.init("1:a").getOrThrow))
                                assert(sends.size == 3)
                            }
                    }
                }
            }
        }
    }

    "an exhausted schedule fails with the last answer's leaf" in {
        Clock.withTimeControl { control =>
            withLocal { local =>
                val config = local.config.copy(
                    retry = Present(Schedule.fixed(1.second).take(2)),
                    requestTimeout = 1.hour
                )
                local.reply("send", error(503, "ServiceUnavailable", "d")).andThen {
                    advancingUntilDone(control, Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))).map {
                        result =>
                            local.seen("send").map { sends =>
                                assert(result == Result.fail(
                                    TeamsOtherApiException(Teams.Routes.Send, HttpStatus(503), "ServiceUnavailable", "d", Absent)
                                ))
                                assert(sends.size == 3)
                            }
                    }
                }
            }
        }
    }

    "a Retry-After above retryMaxDelay is not waited: the call fails at once with the rate-limit leaf carrying it" in {
        withLocal { local =>
            val config = local.config.copy(retry = Present(Schedule.fixed(1.second).take(3)))
            local.reply("send", error(429, "TooManyRequests", "d", Chunk("Retry-After" -> "61")), resource("1:a")).andThen {
                Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))).map { result =>
                    Clock.now.map { now =>
                        local.seen("send").map { sends =>
                            assert(result == Result.fail(
                                TeamsRateLimitException(Teams.Routes.Send, Present(61.seconds), Present("TooManyRequests"), Absent)
                            ))
                            assert((sends.size, now) == (1, Instant.Epoch))
                        }
                    }
                }
            }
        }
    }

    "without a retry schedule, or for a status Microsoft does not say to retry, nothing is sent again" in {
        withLocal { local =>
            val scheduled = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(3)))
            for
                _        <- local.reply("send", error(503, "ServiceUnavailable", "d"), resource("1:a"))
                plain    <- local.api(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))
                _        <- local.reply("send", error(500, "InternalServerError", "d"), resource("1:a"))
                internal <- Teams.run(scheduled)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))
                sends    <- local.seen("send")
            yield
                assert(plain == Result.fail(TeamsOtherApiException(Teams.Routes.Send, HttpStatus(503), "ServiceUnavailable", "d", Absent)))
                assert(internal ==
                    Result.fail(TeamsOtherApiException(Teams.Routes.Send, HttpStatus(500), "InternalServerError", "d", Absent)))
                assert(sends.size == 2)
            end for
        }
    }

    private def peerAt(local: Local, port: Int)(using Frame): (TeamsConfig, Teams.ConversationReference) =
        val peer = HttpUrl(Present("http"), "127.0.0.1", port, "/", Absent)
        (
            local.config.copy(serviceHosts = Chunk(peer)),
            Teams.ConversationReference(Teams.ServiceUrl.init(peer.full).getOrThrow, Teams.ConversationAccount(conversation))
        )
    end peerAt

    private def transport(kind: TeamsTransportException.Kind, port: Int, timeout: Maybe[Duration] = Absent)(using Frame) =
        Result.fail(TeamsTransportException(Teams.Routes.Send, kind, "127.0.0.1", port, timeout)())

    "a request with no answer within requestTimeout fails as the transport's timeout, to the server's host and port" in {
        Clock.withTimeControl { control =>
            withLocal { local =>
                local.reply("send").andThen {
                    Fiber.initUnscoped(local.api(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))).map { fiber =>
                        local.held.await.andThen(control.advance(local.config.requestTimeout)).andThen(fiber.get).map { result =>
                            assert(result == transport(TeamsTransportException.Kind.Timeout, local.base.port, Present(10.seconds)))
                        }
                    }
                }
            }
        }
    }

    "a body over maxResponseLength fails as the transport's oversized payload, on the call and on the token fetch" in {
        withLocal { local =>
            val body = s"""{"id":"${"x" * 256}"}"""
            val send = Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))
            local.reply("send", json(body)).andThen {
                for
                    call  <- Teams.run(local.config.copy(maxResponseLength = 128.bytes))(send)
                    token <- Teams.run(local.config.copy(maxResponseLength = 16.bytes))(send)
                yield
                    assert(call == transport(TeamsTransportException.Kind.PayloadTooLarge(body.length.bytes, 128.bytes), local.base.port))
                    assert(token == Result.fail(TeamsTransportException(
                        kyo.internal.teams.TokenCache.IdentityPlatformMethod,
                        TeamsTransportException.Kind.PayloadTooLarge(bearer(tokenText, 3600).body.length.bytes, 16.bytes),
                        "127.0.0.1",
                        local.base.port,
                        Absent
                    )()))
                end for
            }
        }
    }

    "a response that closes after its head fails as the transport's closed connection" in {
        withLocal { local =>
            withClosingAfterHead { port =>
                val (config, reference) = peerAt(local, port)
                Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(reference, hello))).map(port -> _)
            }.map((port, result) => assert(result == transport(TeamsTransportException.Kind.ConnectionClosed, port)))
        }
    }

    "a chunked body with a malformed size line fails as the transport's protocol kind" in {
        withLocal { local =>
            withCountingPeer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n{}\r\n0\r\n\r\n") { (port, _) =>
                val (config, reference) = peerAt(local, port)
                Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(reference, hello))).map { result =>
                    assert(result == transport(TeamsTransportException.Kind.Protocol, port))
                }
            }
        }
    }

    "a malformed status line fails as the transport's protocol kind" in {
        withLocal { local =>
            withCountingPeer("HTTP/1.1 abc Nope\r\nContent-Length: 0\r\n\r\n") { (port, _) =>
                val (config, reference) = peerAt(local, port)
                Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(reference, hello))).map { result =>
                    assert(result == transport(TeamsTransportException.Kind.Protocol, port))
                }
            }
        }
    }

    "a status outside 100 to 599 fails as the transport's protocol kind" in {
        withLocal { local =>
            withCountingPeer("HTTP/1.1 700 Beyond\r\nContent-Length: 0\r\n\r\n") { (port, _) =>
                val (config, reference) = peerAt(local, port)
                Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(reference, hello))).map { result =>
                    assert(result == transport(TeamsTransportException.Kind.Protocol, port))
                }
            }
        }
    }

    "tls comes from the config: the default refuses a self-signed server, the config's trust reaches it, and the caller's does not" in {
        withLocalTls { local =>
            local.reply("send", resource("1:a")).andThen {
                val trusting = local.config.copy(tls = HttpTlsConfig(trustAll = true))
                for
                    refused <- Teams.run(local.config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))
                    ambient <- HttpClient.withConfig(_.tls(HttpTlsConfig(trustAll = true)))(
                        Teams.run(local.config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))
                    )
                    trusted <- Teams.run(trusting)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))
                yield
                    val refusal = Result.fail(TeamsTransportException(
                        kyo.internal.teams.TokenCache.IdentityPlatformMethod,
                        TeamsTransportException.Kind.Tls,
                        "127.0.0.1",
                        local.base.port,
                        Absent
                    )())
                    assert((refused, ambient) == (refusal, refusal))
                    assert(trusted == Result.succeed(Teams.ActivityId.init("1:a").getOrThrow))
                end for
            }
        }
    }

    "transport comes from the config: a response head over the default limit fails, the config's larger limit reads it, and the caller's does not" in {
        val padded = Reply(HttpStatus.OK, """{"id":"1:a"}""", Chunk("X-Pad" -> "a" * (70 * 1024)))
        val wider  = HttpTransportConfig.default.maxHeaderSize(256.kib)
        withLocal { local =>
            local.reply("send", padded).andThen {
                for
                    refused <- local.api(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))
                    ambient <- HttpClient.withConfig(_.transportConfig(wider))(
                        local.api(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))
                    )
                    read <- Teams.run(local.config.copy(transport = wider))(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello)))
                yield
                    val refusal = transport(TeamsTransportException.Kind.Protocol, local.base.port)
                    assert((refused, ambient) == (refusal, refusal))
                    assert(read == Result.succeed(Teams.ActivityId.init("1:a").getOrThrow))
                end for
            }
        }
    }

    "run closes its client's HTTP client when its region ends" in {
        withLocal { local =>
            Teams.run(local.config)(Env.get[Teams]).map { teams =>
                // Unsafe: whether a pool is closed has no safe accessor; this reads the flag once, after the region ended.
                import AllowUnsafe.embrace.danger
                assert(teams.http.isPoolClosed)
            }
        }
    }

    "a caller's client filter, installed by withConfig or on a client bound with let, sees no request of the module" in {
        withLocal { local =>
            AtomicRef.init(Chunk.empty[String]).map { filtered =>
                val recording = new HttpFilter.Passthrough[Nothing]:
                    def apply[In, Out, E2, S](
                        request: HttpRequest[In],
                        next: HttpRequest[In] => HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt])
                    )(using Frame): HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt]) =
                        filtered.updateAndGet(_ :+ request.path).andThen(next(request))
                local.reply("send", resource("1:a")).andThen {
                    HttpClient.withConfig(_.filter(recording))(local.api(Teams.send(local.reference, hello))).andThen {
                        HttpClient.init().map { callerClient =>
                            HttpClient.let(callerClient)(HttpClient.withConfig(_.filter(recording).tls(HttpTlsConfig(trustAll = true)))(
                                local.api(Teams.send(local.reference, hello))
                            ))
                        }
                    }.andThen {
                        filtered.get.map(paths => local.seen.map(seen => assert((paths, seen.size) == (Chunk.empty[String], 4))))
                    }
                }
            }
        }
    }

    "a connection the caller opened to the same server is not reused for a request of the module" in {
        withLocal { local =>
            withCountingPeer(raw("200 OK", """{"id":"1:a"}""")) { (port, accepted) =>
                val peer   = HttpUrl(Present("http"), "127.0.0.1", port, "/", Absent)
                val config = TeamsConfig.init(appId, local.config.credential, loginUrl = local.base, serviceHosts = Chunk(peer)).getOrThrow
                val reference =
                    Teams.ConversationReference(Teams.ServiceUrl.init(peer.full).getOrThrow, Teams.ConversationAccount(conversation))
                HttpClient.init().map { callerClient =>
                    HttpClient.let(callerClient)(HttpClient.withConfig(_.tls(HttpTlsConfig(trustAll = true)))(
                        HttpClient.getText(peer.copy(path = "/caller"))
                    )).andThen(Teams.run(config)(Teams.send(reference, hello))).andThen(accepted.get.map(n => assert(n == 2)))
                }
            }
        }
    }

end TeamsTest
