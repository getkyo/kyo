package kyo

import DiscordTest.*
import DiscordWebhookTest.*
import java.nio.charset.StandardCharsets.UTF_8

class DiscordWebhookTest extends kyo.test.Test[Any]:

    import Discord.*
    import DiscordWebhookMalformedHeaderException.Problem
    import DiscordWebhookMissingHeaderException.Header

    private def verify(signature: Maybe[String], timestamp: Maybe[String], body: String): Result[DiscordWebhookVerifyFailure, Unit] =
        Webhook.verify(webhook, signature, timestamp, bytes(body))

    // --- verify, in the order of the checks ---

    "a request signed with the application's key verifies" in {
        assert(verify(Present(commandSignature), Present(timestamp), commandBody) == Result.unit)
        assert(verify(Present(pingSignature.toUpperCase), Present(timestamp), pingBody) == Result.unit)
    }

    "a missing signature header is refused before the timestamp is read" in {
        assert(verify(Absent, Absent, commandBody) == Result.fail(DiscordWebhookMissingHeaderException(Header.Signature)))
        assert(verify(Present(commandSignature), Absent, commandBody) ==
            Result.fail(DiscordWebhookMissingHeaderException(Header.Timestamp)))
    }

    "a timestamp that is not 1 to 20 decimal digits is refused before the signature's shape is checked" in {
        Chunk("", "17000x0000", "-1700000000", " 1700000000", "1" * 21, "１７").foreach { bad =>
            assert(
                verify(Present("zz"), Present(bad), commandBody) ==
                    Result.fail(DiscordWebhookMalformedHeaderException(Header.Timestamp, Problem.NotSeconds)),
                s"timestamp: '$bad'"
            )
        }
        assert(verify(Present(commandSignature), Present("1" * 20), commandBody) == Result.fail(DiscordWebhookSignatureMismatchException()))
    }

    "a signature that is not 128 hex characters is refused before any verification" in {
        assert(verify(Present(commandSignature.drop(2)), Present(timestamp), commandBody) ==
            Result.fail(DiscordWebhookMalformedHeaderException(Header.Signature, Problem.Length(126))))
        assert(verify(Present(""), Present(timestamp), commandBody) ==
            Result.fail(DiscordWebhookMalformedHeaderException(Header.Signature, Problem.Length(0))))
        assert(verify(Present(commandSignature.take(10) + "g" + commandSignature.drop(11)), Present(timestamp), commandBody) ==
            Result.fail(DiscordWebhookMalformedHeaderException(Header.Signature, Problem.Hex(Hex.Failure.IllegalCharacter(10)))))
    }

    "a signature over another body or another timestamp does not verify" in {
        val mismatch = Result.fail(DiscordWebhookSignatureMismatchException())
        assert(verify(Present(commandSignature), Present(timestamp), commandBody + " ") == mismatch)
        assert(verify(Present(commandSignature), Present("1700000001"), commandBody) == mismatch)
        assert(verify(Present(pingSignature), Present(timestamp), commandBody) == mismatch)
        assert(verify(Present("0" * 128), Present(timestamp), commandBody) == mismatch)
    }

    // --- decode ---

    "a PING decodes as the endpoint's own delivery, and a command as its event" in {
        Abort.run[DiscordWebhookDecodeFailure](Webhook.decode(bytes(pingBody))).map { ping =>
            Abort.run[DiscordWebhookDecodeFailure](Webhook.decode(bytes(commandBody))).map { command =>
                assert(ping == Result.succeed(Webhook.Delivery.Ping))
                val named = command.map {
                    case Webhook.Delivery.Event(Event.Command(interaction, data)) => Present((interaction.ref.id, data.name))
                    case _                                                        => Absent
                }
                assert(named == Result.succeed(Present((InteractionId(1200000000000000001L), "roll"))))
            }
        }
    }

    "a body that is not an interaction fails with the decode leaf, located" in {
        Abort.run[DiscordWebhookDecodeFailure](Webhook.decode(bytes(undecodableBody))).map { result =>
            val located = result.failure.collect { case e: DiscordWebhookDecodeException => (e.part, e.failure) }
            assert(located == Present((DiscordDecodeException.Part.Interaction, DiscordDecodeException.Failure.MissingField)))
        }
    }

    // --- handler ---

    "a PING is answered 200 with type 1 and never reaches the handler" in {
        withLocal { local =>
            AtomicInt.init.map { calls =>
                withEndpoint(local.config)([A] => (event: Event[A]) => calls.incrementAndGet.andThen(answer[A](event))) { url =>
                    post(url, pingBody, Present(pingSignature), Present(timestamp)).map { reply =>
                        calls.get.map(n => assert((reply, n) == ((200, Present("application/json"), """{"type":1}"""), 0)))
                    }
                }
            }
        }
    }

    "a command's answer is the 200 reply, as JSON, and the handler's client is the caller's" in {
        withLocal { local =>
            local.reply("GET /channels/111", ok(channelJson)).andThen {
                withEndpoint(local.config)([A] =>
                    (event: Event[A]) =>
                        event match
                            case e: Event.Command =>
                                Abort.run[DiscordChannelFailure](Discord.channel(ChannelId(111L))).map { fetched =>
                                    InteractionResponse.Message(
                                        Message.Create.init(content = Present(s"${e.data.name} ${fetched.isSuccess}")).getOrThrow
                                    )
                                }
                            case other => answer[A](other)
                ) { url =>
                    post(url, commandBody, Present(commandSignature), Present(timestamp)).map { reply =>
                        local.seen.map { seen =>
                            assert(reply ==
                                ((200, Present("application/json"), """{"type":4,"data":{"content":"roll true","tts":false}}""")))
                            assert(seen.map(s => (s.method, s.path, s.authorization)) ==
                                Chunk(("GET", "/channels/111", Present(s"Bot $tokenSecret"))))
                        }
                    }
                }
            }
        }
    }

    "an answer with files is posted to the callback as multipart, and the reply is 202 with no body" in {
        val file = File.init("a.txt", Span.from("A".getBytes(UTF_8))).getOrThrow
        withLocal { local =>
            val callback = "/interactions/1200000000000000001/aW50ZXJhY3Rpb24.dG9rZW4/callback"
            local.reply(s"POST $callback", noContent).andThen {
                withEndpoint(local.config)([A] =>
                    (event: Event[A]) =>
                        event match
                            case _: Event.Command =>
                                InteractionResponse.Message(Message.Create.init(content = Present("see"), files = Chunk(file)).getOrThrow)
                            case other => answer[A](other)
                ) { url =>
                    post(url, commandBody, Present(commandSignature), Present(timestamp)).map { reply =>
                        local.seen.map { seen =>
                            assert((reply._1, reply._3) == (202, ""), s"got: $reply")
                            assert(seen.map(s => (s.method, s.path, s.contentType)) ==
                                Chunk(("POST", callback, Present("multipart/form-data; boundary=kyo-discord-0"))))
                            assert(seen.exists(_.body.contains(
                                """{"type":4,"data":{"content":"see","tts":false,"attachments":[{"id":0,"filename":"a.txt"}]}}"""
                            )))
                        }
                    }
                }
            }
        }
    }

    "an answer with files whose callback Discord refuses is answered 500" in {
        val file = File.init("a.txt", Span.from("A".getBytes(UTF_8))).getOrThrow
        withLocal { local =>
            val callback = "/interactions/1200000000000000001/aW50ZXJhY3Rpb24.dG9rZW4/callback"
            local.reply(s"POST $callback", apiError(404, 10062, "Unknown interaction")).andThen {
                withEndpoint(local.config)([A] =>
                    (event: Event[A]) =>
                        event match
                            case _: Event.Command =>
                                InteractionResponse.Message(Message.Create.init(content = Present("see"), files = Chunk(file)).getOrThrow)
                            case other => answer[A](other)
                ) { url =>
                    post(url, commandBody, Present(commandSignature), Present(timestamp)).map { reply =>
                        local.seen.map(seen => assert((reply._1, seen.map(_.path)) == (500, Chunk(callback))))
                    }
                }
            }
        }
    }

    "a handler taken out of its client's region holds a closed client, so a callback it posts fails and is answered 500" in {
        val file = File.init("a.txt", Span.from("A".getBytes(UTF_8))).getOrThrow
        withLocal { local =>
            val callback = "/interactions/1200000000000000001/aW50ZXJhY3Rpb24.dG9rZW4/callback"
            local.reply(s"POST $callback", noContent).andThen {
                Discord.run(local.config)(Webhook.handler(webhook)([A] =>
                    (event: Event[A]) =>
                        event match
                            case _: Event.Command =>
                                InteractionResponse.Message(Message.Create.init(content = Present("see"), files = Chunk(file)).getOrThrow)
                            case other => answer[A](other)
                )).map { escaped =>
                    HttpServer.init(HttpServerConfig.default.port(0).host("127.0.0.1"))(escaped).map { server =>
                        post(s"http://127.0.0.1:${server.port}/interactions", commandBody, Present(commandSignature), Present(timestamp))
                            .map { reply =>
                                local.seen.map(seen => assert((reply._1, seen) == (500, Chunk.empty)))
                            }
                    }
                }
            }
        }
    }

    "each request that fails verification is answered 401 and never decoded or handled" in {
        withLocal { local =>
            AtomicInt.init.map { calls =>
                withEndpoint(local.config)([A] => (event: Event[A]) => calls.incrementAndGet.andThen(answer[A](event))) { url =>
                    Kyo.foreach(Chunk(
                        (Absent, Present(timestamp), undecodableBody),
                        (Present(undecodableSignature), Absent, undecodableBody),
                        (Present(undecodableSignature), Present("x"), undecodableBody),
                        (Present(undecodableSignature.drop(1)), Present(timestamp), undecodableBody),
                        (Present(pingSignature), Present(timestamp), undecodableBody),
                        (Present(commandSignature), Present(timestamp), commandBody + " ")
                    ))((signature, at, body) => post(url, body, signature, at).map(_._1)).map { statuses =>
                        calls.get.map(n => assert((statuses, n) == (Chunk.fill(6)(401), 0)))
                    }
                }
            }
        }
    }

    "a verified body that is not an interaction is answered 400 and never handled" in {
        withLocal { local =>
            AtomicInt.init.map { calls =>
                withEndpoint(local.config)([A] => (event: Event[A]) => calls.incrementAndGet.andThen(answer[A](event))) { url =>
                    post(url, undecodableBody, Present(undecodableSignature), Present(timestamp)).map { reply =>
                        calls.get.map(n => assert((reply._1, n) == (400, 0)))
                    }
                }
            }
        }
    }

    "a body past the server's maxContentLength is refused with 413 before the route runs" in {
        withLocal { local =>
            AtomicInt.init.map { calls =>
                withEndpoint(local.config)([A] => (event: Event[A]) => calls.incrementAndGet.andThen(answer[A](event))) { url =>
                    post(url, " " * 65537, Present(commandSignature), Present(timestamp)).map { reply =>
                        calls.get.map(n => assert((reply._1, n) == (413, 0)))
                    }
                }
            }
        }
    }

    "an interaction the handler declines is answered 500 at once, for every kind, and nothing is posted" in {
        withLocal { local =>
            withEndpoint(local.config)([A] => (event: Event[A]) => Event.unhandled(event)) { url =>
                Kyo.foreach(Chunk(
                    (commandBody, commandSignature),
                    (componentBody, componentSignature),
                    (autocompleteBody, autocompleteSignature),
                    (modalBody, modalSignature),
                    (unknownBody, unknownSignature)
                ))((body, signature) => post(url, body, Present(signature), Present(timestamp)).map(_._1)).map { statuses =>
                    local.seen.map(seen => assert((statuses, seen) == (Chunk.fill(5)(500), Chunk.empty)))
                }
            }
        }
    }

    "a handler's typed failure and its panic are answered 500" in {
        withLocal { local =>
            withEndpoint[String](local.config)([A] =>
                (event: Event[A]) =>
                    event match
                        case e: Event.Command if e.data.name == "roll" => Abort.fail("refused")
                        case other                                     => answer[A](other)
            ) { failing =>
                withEndpoint(local.config)([A] =>
                    (event: Event[A]) =>
                        event match
                            case _: Event.Command => Abort.panic(new IllegalStateException("handler bug"))
                            case other            => answer[A](other)
                ) { panicking =>
                    post(failing, commandBody, Present(commandSignature), Present(timestamp)).map { failed =>
                        post(panicking, commandBody, Present(commandSignature), Present(timestamp)).map { panicked =>
                            assert((failed._1, panicked._1) == (500, 500))
                        }
                    }
                }
            }
        }
    }

    "a handler past interactionDeadline is interrupted and answered 500" in {
        withLocal { local =>
            Clock.withTimeControl { control =>
                Latch.init(1).map { started =>
                    Latch.init(1).map { interrupted =>
                        withEndpoint(local.config)([A] =>
                            (event: Event[A]) =>
                                event match
                                    case _: Event.Command => Sync.ensure(interrupted.release)(started.release.andThen(Async.never))
                                    case other            => answer[A](other)
                        ) { url =>
                            Fiber.initUnscoped(post(url, commandBody, Present(commandSignature), Present(timestamp))).map { request =>
                                for
                                    _     <- started.await
                                    _     <- control.advance(local.config.interactionDeadline)
                                    _     <- interrupted.await
                                    reply <- request.get
                                yield assert(reply._1 == 500)
                            }
                        }
                    }
                }
            }
        }
    }

end DiscordWebhookTest

object DiscordWebhookTest:

    import Discord.*

    // RFC 8032 section 7.1, TEST 1. The signatures below were made with its secret key over the timestamp's bytes followed by the body.
    val publicKeyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

    def webhook(using Frame): DiscordWebhookConfig =
        DiscordWebhookConfig.init(PublicKey.init(publicKeyHex).getOrThrow, "interactions").getOrThrow

    val timestamp = "1700000000"

    val pingBody =
        """{"id":"1200000000000000001","application_id":"1100000000000000000","type":1,"token":"aW50ZXJhY3Rpb24.dG9rZW4","version":1}"""
    val pingSignature =
        "a30a7708f850ec2abd0963385fb662904ca064d8a12c2cdcf51395b0c8ac36a9f453e424c42d479d8e070a694510623cdb2bce8341de4d493c909d9d7a371e04"

    val commandBody =
        """{"type":2,"id":"1200000000000000001","application_id":"1100000000000000000","token":"aW50ZXJhY3Rpb24.dG9rZW4","version":1,""" +
            """"guild_id":"3","channel_id":"2","member":{"user":{"id":"80351110224678912","username":"nelly","discriminator":"0",""" +
            """"global_name":null,"avatar":null},"roles":[],"permissions":"2147483647"},"app_permissions":"442368","locale":"en-US",""" +
            """"data":{"id":"1","name":"roll","type":1}}"""
    val commandSignature =
        "14f1f04f02e03c7b1f33a66fcba114d6f06616243d65949d052a7f465530ec1e1cb4bda79478e971cb21192e40a625fdf7d0cb9c65ffa6f0bd9f4a740dc5180c"

    private val shared =
        """"id":"1200000000000000001","application_id":"1100000000000000000","token":"aW50ZXJhY3Rpb24.dG9rZW4","version":1,"channel_id":"2""""

    val componentBody      = s"""{"type":3,$shared,"data":{"custom_id":"pick","component_type":2}}"""
    val componentSignature =
        "6cf558e2469b11c61f539e04880f3c9f3a636a167f6db8ef76f546f73509099510d2b4799d192cb7db4b7f640d6286694c943918812d8ccc9d46ab6e3e3fae07"

    val autocompleteBody =
        s"""{"type":4,$shared,"data":{"id":"1","name":"roll","type":1,"options":[{"name":"sides","type":4,"value":"1","focused":true}]}}"""
    val autocompleteSignature =
        "b098667d87b6fd89ef462dd0296a45675cec064fd178d412523fd5c0b701db193f748c552323cf976332e13ee488b1340df343d6771d9d82ed03dbee75429b0d"

    val modalBody      = s"""{"type":5,$shared,"data":{"custom_id":"form","components":[]}}"""
    val modalSignature =
        "c122c100f2f3c38e44a00742cd9c0ad722a6da3b69faa0178ffb7a65c0e3a4a3ecfaf2633e0035a845e71ae321d7de7fd7a2394cf43ea95383356c6d8891780b"

    val unknownBody      = s"""{"type":9,$shared}"""
    val unknownSignature =
        "5069e19293046d7e4f15dd1c22effd4cb4e9487b3db1b8c0d40135af2a1b7aa928ce0654b2d0f45416bd185c1db84db1705ae2ddbde6f2709fcd1eb18f9e720f"

    val undecodableBody      = """{"type":2,"id":"1200000000000000001"}"""
    val undecodableSignature =
        "b56496df33aadad9ad775b47329cb8c061800c5b2658e44cb7ea2c28a9a37fdd503559ec40a5cb5ac1d83cd38194e6f889a089508840e553de82537c6097e50c"

    val channelJson = """{"id":"111","type":0,"guild_id":"5","name":"general"}"""

    def bytes(text: String): Span[Byte] = Span.from(text.getBytes(UTF_8))

    /** An answer of the type each interaction kind admits, for the cases a test does not script. */
    def answer[A](event: Event[A]): A =
        event match
            case _: Event.Command            => InteractionResponse.DeferredMessage()
            case _: Event.Component          => InteractionResponse.DeferredUpdate
            case _: Event.Autocomplete       => InteractionResponse.Autocomplete.init(Chunk.empty).getOrThrow
            case _: Event.ModalSubmit        => InteractionResponse.DeferredMessage()
            case _: Event.UnknownInteraction => InteractionResponse.DeferredMessage()
            case _: (Event.Ready | Event.Resumed.type | Event.MessageCreated | Event.MessageUpdated | Event.MessageDeleted |
                    Event.ReactionAdded | Event.ReactionRemoved | Event.ThreadCreated | Event.ThreadUpdated | Event.ThreadDeleted |
                    Event.ChannelCreated | Event.ChannelUpdated | Event.ChannelDeleted | Event.GuildCreated | Event.GuildDeleted |
                    Event.MemberJoined | Event.MemberLeft | Event.TypingStarted | Event.Unknown) => ()

    /** The handler for [[webhook]] on a client of `config`, served on a local server inside the client's region; `test` gets the
      * endpoint's URL.
      */
    def withEndpoint[E](config: DiscordConfig)(f: [A] => Event[A] => A < (Async & Abort[E] & Env[Discord]))[B](
        test: String => B < (Async & Abort[Any] & Scope)
    )(using Frame): B < (Async & Abort[Any] & Scope) =
        Discord.run(config)(Webhook.handler(webhook)(f).map { handler =>
            HttpServer.init(HttpServerConfig.default.port(0).host("127.0.0.1"))(handler).map { server =>
                test(s"http://127.0.0.1:${server.port}/interactions")
            }
        })

    /** POSTs `body` with the signature headers given, answering the status, the Content-Type and the body as text. */
    def post(url: String, body: String, signature: Maybe[String], timestamp: Maybe[String])(using
        Frame
    ): (Int, Maybe[String], String) < (Async & Abort[Any]) =
        val headers = signature.fold(Seq.empty)(s => Seq("X-Signature-Ed25519" -> s)) ++
            timestamp.fold(Seq.empty)(t => Seq("X-Signature-Timestamp" -> t))
        HttpClient.postBinaryResponse(url, bytes(body), headers, failOnError = false).map { response =>
            (response.status.code, response.headers.get("Content-Type"), new String(response.fields.body.toArray, UTF_8))
        }
    end post

end DiscordWebhookTest
