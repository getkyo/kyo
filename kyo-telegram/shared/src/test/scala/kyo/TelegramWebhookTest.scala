package kyo

import kyo.internal.charset.Utf8

class TelegramWebhookTest extends kyo.test.Test[Any]:

    private val secret = Telegram.SecretToken.init("s3cret-Token_1").getOrThrow

    private val update = TelegramTest.update(7, "hi")

    private def bytes(s: String): Span[Byte] = Utf8.encode(s)

    /** Serves `handler` inside a `Telegram.run` region on `config`, with a callback that records each update it gets
      * and then runs `after`, and hands the test a function that POSTs a body with an optional secret header,
      * answering the status. The server, its client and the leaf run under `Clock.withTimeControl`, so no
      * deadline the default configs arm fires on the real clock.
      */
    private def serving[A](
        path: String = "",
        config: TelegramConfig = TelegramConfig.init(Telegram.Token.init("123456:TEST-token_local").getOrThrow).getOrThrow,
        after: Telegram.Update => Unit < (Async & Abort[String] & Env[Telegram]) = _ => Kyo.unit
    )(
        test: (
            AtomicRef[Chunk[Telegram.Update]],
            (String, String, Maybe[String]) => HttpStatus < (Async & Abort[HttpException])
        ) => A < (
            Async & Abort[Any] & Scope & Env[Telegram]
        )
    )(using Frame): A < (Async & Abort[Any] & Scope) =
        Clock.withTimeControl { _ =>
            Telegram.run(config) {
                for
                    received <- AtomicRef.init(Chunk.empty[Telegram.Update])
                    handler  <- Telegram.Webhook.handler[String](TelegramWebhookConfig.init(secret, path).getOrThrow)(u =>
                        received.updateAndGet(_ :+ u).andThen(after(u))
                    )
                    server <- HttpServer.init(0, "127.0.0.1")(handler)
                    post = (at: String, body: String, header: Maybe[String]) =>
                        HttpClient.postBinaryResponse(
                            s"http://127.0.0.1:${server.port}/$at",
                            bytes(body),
                            headers = header.fold(Seq.empty[(String, String)])(h => Seq(Telegram.Webhook.SecretHeader -> h)),
                            failOnError = false
                        ).map(_.status)
                    result <- test(received, post)
                yield result
            }
        }

    "an update with the right secret reaches the callback and is answered 200" in {
        serving() { (received, post) =>
            for
                status <- post("", update, Present(secret.value))
                got    <- received.get
            yield assert((status, got.map(_.id)) == (HttpStatus.OK, Chunk(Telegram.UpdateId(7L))))
        }
    }

    "a request without the secret header is answered 403 and not decoded" in {
        serving() { (received, post) =>
            for
                status <- post("", update, Absent)
                got    <- received.get
            yield assert((status, got) == (HttpStatus.Forbidden, Chunk.empty))
        }
    }

    "a request with a wrong secret is answered 403, including one that is a prefix of the secret" in {
        serving() { (received, post) =>
            for
                wrong  <- post("", update, Present("s3cret-Token_2"))
                prefix <- post("", update, Present("s3cret"))
                longer <- post("", update, Present(secret.value + "x"))
                got    <- received.get
            yield assert((wrong, prefix, longer, got) == (HttpStatus.Forbidden, HttpStatus.Forbidden, HttpStatus.Forbidden, Chunk.empty))
        }
    }

    "a body that is not an update is answered 200, so Telegram does not redeliver it, and the callback does not run" in {
        serving() { (received, post) =>
            for
                notJson <- post("", "not json", Present(secret.value))
                noId    <- post("", """{"message":{}}""", Present(secret.value))
                got     <- received.get
            yield assert((notJson, noId, got) == (HttpStatus.OK, HttpStatus.OK, Chunk.empty))
        }
    }

    "an update with only update_id reaches the callback as Unknown with no type" in {
        serving() { (received, post) =>
            for
                status <- post("", """{"update_id":8}""", Present(secret.value))
                got    <- received.get
            yield assert((
                status,
                got.map(u =>
                    (
                        u.id,
                        u match
                            case Telegram.Update.Unknown(_, kind, _) => kind
                            case _                                   => Present("known")
                    )
                )
            ) == (HttpStatus.OK, Chunk((Telegram.UpdateId(8L), Absent))))
        }
    }

    "a callback that fails is answered 500, so Telegram redelivers the update" in {
        serving(after = _ => Abort.fail("callback failed")) { (received, post) =>
            for
                status <- post("", update, Present(secret.value))
                got    <- received.get
            yield assert((status, got.map(_.id)) == (HttpStatus.InternalServerError, Chunk(Telegram.UpdateId(7L))))
        }
    }

    "a callback that panics is answered 500" in {
        serving(after = _ => Abort.panic(new IllegalStateException("callback panicked"))) { (_, post) =>
            post("", update, Present(secret.value)).map(status => assert(status == HttpStatus.InternalServerError))
        }
    }

    "a handler mounted at a path serves that path only" in {
        serving(path = "telegram/hook") { (received, post) =>
            for
                atPath <- post("telegram/hook", update, Present(secret.value))
                atRoot <- post("", update, Present(secret.value))
                got    <- received.get
            yield assert((atPath, atRoot, got.size) == (HttpStatus.OK, HttpStatus.NotFound, 1))
        }
    }

    "the callback calls the verbs directly, on the region's client" in {
        TelegramTest.withLocal { local =>
            val sent = """{"message_id":1,"date":1700000000,"chat":{"id":5,"type":"private","first_name":"Ann"},"text":"pong"}"""
            local.reply("sendMessage", TelegramTest.ok(sent)).andThen {
                val reply = Telegram.send(Telegram.Chat.Target(Telegram.ChatId(5L)), Telegram.Content.text("pong"))
                serving(config = local.config, after = _ => Abort.run[TelegramSendFailure](reply).unit) {
                    (_, post) =>
                        post("", update, Present(secret.value)).map { status =>
                            local.bodies("sendMessage").map(bodies =>
                                assert((status, bodies) == (HttpStatus.OK, Chunk("""{"chat_id":5,"text":"pong"}""")))
                            )
                        }
                }
            }
        }
    }

    "every delivery's callback runs on the client the handler was built with" in {
        AtomicRef.init(Chunk.empty[Telegram]).map { clients =>
            serving(after = _ => Env.get[Telegram].map(t => clients.updateAndGet(_ :+ t).unit)) { (_, post) =>
                for
                    region <- Env.get[Telegram]
                    first  <- post("", update, Present(secret.value))
                    second <- post("", update, Present(secret.value))
                    seen   <- clients.get
                yield assert((first, second, seen.size, seen.forall(_ eq region)) == (HttpStatus.OK, HttpStatus.OK, 2, true))
            }
        }
    }

    "verify accepts the secret itself" in {
        assert(Telegram.Webhook.verify(TelegramWebhookConfig.init(secret).getOrThrow, Present(secret.value)) == Result.unit)
    }

    "decode reads an update's id and kind" in {
        Abort.run[TelegramWebhookDecodeFailure](Telegram.Webhook.decode(bytes(update))).map { result =>
            assert(result.map {
                case Telegram.Update.Message(id, m) => (id, m.text)
                case other                          => (other.id, Absent)
            } == Result.succeed((Telegram.UpdateId(7L), Present("hi"))))
        }
    }

end TelegramWebhookTest
