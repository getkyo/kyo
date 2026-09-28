package kyo

import kyo.internal.whatsapp.Hmac

class WhatsAppWebhookTest extends BaseWhatsAppTest:

    val verifyTokenText: String = "MYTOKEN"
    val appSecretText: String   = "appsecret"
    val phoneId                 = WhatsAppId.PhoneNumberId("106540352242922")

    val webhook: WhatsAppWebhookConfig = WhatsAppWebhookConfig(WhatsAppAppSecret(appSecretText), WhatsAppVerifyToken(verifyTokenText))

    /** A config whose Graph server is on `port`; port 1 is privileged and unused, so a call through it is refused. */
    def configAt(port: Int)(using Frame): WhatsAppConfig =
        WhatsAppConfig(WhatsAppToken("TOKEN"), phoneId, baseUrl = url(s"http://localhost:$port"))

    val textWebhookBody: String =
        """{
          |  "object": "whatsapp_business_account",
          |  "entry": [
          |    {
          |      "id": "102290129340398",
          |      "changes": [
          |        {
          |          "value": {
          |            "messaging_product": "whatsapp",
          |            "metadata": { "display_phone_number": "15550783881", "phone_number_id": "106540352242922" },
          |            "contacts": [ { "profile": { "name": "Sheena Nelson" }, "wa_id": "16505551234" } ],
          |            "messages": [
          |              {
          |                "from": "16505551234",
          |                "id": "wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA=",
          |                "timestamp": "1749416383",
          |                "type": "text",
          |                "text": { "body": "Does it come in another color?" }
          |              }
          |            ]
          |          },
          |          "field": "messages"
          |        }
          |      ]
          |    }
          |  ]
          |}""".stripMargin

    val textNotification: WhatsAppNotification =
        WhatsAppNotification.InboundMessage(
            metadata = WhatsAppNotification.Metadata("15550783881", phoneId),
            from = WhatsAppId.WaId("16505551234"),
            id = WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA="),
            timestamp = epoch(1749416383L),
            content = WhatsAppNotification.Content.Text("Does it come in another color?"),
            senderProfileName = Present("Sheena Nelson")
        )

    val twoMessagesBody: String =
        """{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{"messaging_product":"whatsapp",""" +
            """"metadata":{"display_phone_number":"15550783881","phone_number_id":"106540352242922"},"messages":[""" +
            """{"from":"16505551234","id":"wamid.A","timestamp":"1749416383","type":"text","text":{"body":"first"}},""" +
            """{"from":"16505551234","id":"wamid.B","timestamp":"1749416384","type":"text","text":{"body":"second"}}]}}]}]}"""

    def inbound(id: String, timestamp: Long, text: String): WhatsAppNotification =
        WhatsAppNotification.InboundMessage(
            metadata = WhatsAppNotification.Metadata("15550783881", phoneId),
            from = WhatsAppId.WaId("16505551234"),
            id = WhatsAppId.MessageId(id),
            timestamp = epoch(timestamp),
            content = WhatsAppNotification.Content.Text(text)
        )

    def computeSignature(secret: String, body: Array[Byte]): String =
        s"sha256=${Hmac.hexLower(Hmac.hmacSha256(secret.getBytes("UTF-8"), body))}"

    def signedRequest(port: Int, body: String, secret: String = appSecretText, path: String = "/")(using
        Frame
    ): HttpRequest["body" ~ Span[Byte]] =
        val bytes = body.getBytes("UTF-8")
        HttpRequest.postRaw(HttpUrl(Present("http"), "localhost", port, path, Absent))
            .addHeader("X-Hub-Signature-256", computeSignature(secret, bytes))
            .addHeader("Content-Type", "application/json")
            .addField("body", Span.from(bytes))
    end signedRequest

    /** POSTs `request` to a server running `handler` and answers the response status. */
    def postStatus[E](handler: HttpHandler["body" ~ Span[Byte], Any, E])(request: Int => HttpRequest["body" ~ Span[Byte]])(using
        Frame
    ): Result[HttpException, HttpStatus] < (Async & Scope & Abort[HttpBindException]) =
        HttpServer.init(0, "localhost")(handler).map { server =>
            val route = HttpRoute.postRaw("/").request(_.bodyBinary).response(_.bodyBinary)
            Abort.run[HttpException](HttpClient.use(_.sendWith(route, request(server.port))(_.status)))
        }

    "verificationHandler echoes hub.challenge on a token match" in {
        HttpServer.init(0, "localhost")(WhatsAppWebhook.verificationHandler(webhook)).map { server =>
            HttpClient.getText(
                s"http://localhost:${server.port}/?hub.mode=subscribe&hub.verify_token=$verifyTokenText&hub.challenge=1158201444"
            ).map(body => assert(body == "1158201444"))
        }
    }

    "verificationHandler returns 403 on a token mismatch and on a mode other than subscribe" in {
        HttpServer.init(0, "localhost")(WhatsAppWebhook.verificationHandler(webhook)).map { server =>
            def status(query: String) =
                Abort.run[HttpException](HttpClient.getText(s"http://localhost:${server.port}/?$query")).map {
                    case Result.Failure(e: HttpStatusException) => e.status
                    case other                                  => HttpStatus(0)
                }
            for
                wrong <- status("hub.mode=subscribe&hub.verify_token=WRONG&hub.challenge=1")
                mode  <- status(s"hub.mode=unsubscribe&hub.verify_token=$verifyTokenText&hub.challenge=1")
                none  <- status(s"hub.mode=subscribe&hub.verify_token=$verifyTokenText")
            yield assert(Seq(wrong, mode, none) == Seq.fill(3)(HttpStatus.Forbidden))
            end for
        }
    }

    "both handlers are mounted at the webhook config's path" in {
        val mounted = webhook.copy(path = "hooks/whatsapp")
        Channel.init[WhatsAppNotification](4).map { captured =>
            WhatsAppWebhook.handler(configAt(1), mounted)(n => Abort.run[Closed](captured.put(n)).unit).map { handler =>
                HttpServer.init(0, "localhost")(WhatsAppWebhook.verificationHandler(mounted), handler).map { server =>
                    for
                        challenge <- HttpClient.getText(
                            s"http://localhost:${server.port}/hooks/whatsapp?hub.mode=subscribe&hub.verify_token=$verifyTokenText&hub.challenge=7"
                        )
                        route = HttpRoute.postRaw("hooks" / "whatsapp").request(_.bodyBinary).response(_.bodyBinary)
                        status <- HttpClient.use(_.sendWith(
                            route,
                            signedRequest(server.port, textWebhookBody, path = "/hooks/whatsapp")
                        )(_.status))
                        taken <- Abort.run[Closed](captured.take)
                    yield
                        assert(challenge == "7")
                        assert(status == HttpStatus.OK)
                        assert(taken == Result.succeed(textNotification))
                }
            }
        }
    }

    "handler verifies the byte-exact body and 200s, callback fires" in {
        Channel.init[WhatsAppNotification](16).map { captured =>
            WhatsAppWebhook.handler(configAt(1), webhook)(n => Abort.run[Closed](captured.put(n)).unit).map { handler =>
                postStatus(handler)(signedRequest(_, textWebhookBody)).map { status =>
                    Abort.run[Closed](captured.take).map { taken =>
                        assert(status == Result.succeed(HttpStatus.OK))
                        assert(taken == Result.succeed(textNotification))
                    }
                }
            }
        }
    }

    "the callback calls the verbs on the client the handler built from the config" in {
        Channel.init[(String, String)](4).map { requests =>
            val graph = HttpRoute.postRaw("v25.0" / phoneId.value / "messages").request(_.bodyText).response(_.bodyText).handler { req =>
                requests.put((
                    req.headers.get("Authorization").getOrElse(""),
                    req.fields.body
                )).andThen(HttpResponse.ok("""{"success":true}"""))
            }
            HttpServer.init(0, "localhost")(graph).map { graphServer =>
                WhatsAppWebhook.handler(configAt(graphServer.port), webhook) {
                    case m: WhatsAppNotification.InboundMessage => WhatsApp.markRead(m.id)
                    case _                                      => Kyo.unit
                }.map { handler =>
                    postStatus(handler)(signedRequest(_, textWebhookBody)).map { status =>
                        requests.take.map { case (auth, body) =>
                            assert(status == Result.succeed(HttpStatus.OK))
                            assert(auth == "Bearer TOKEN")
                            assert(body.contains(""""message_id":"wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA=""""))
                        }
                    }
                }
            }
        }
    }

    "a bad signature returns 403 and skips the callback; a broken body returns 200 while standalone decode fails" in {
        Channel.init[WhatsAppNotification](16).map { captured =>
            val brokenBody = """{"object":"whatsapp_business_account","entry":"""
            WhatsAppWebhook.handler(configAt(1), webhook)(n => Abort.run[Closed](captured.put(n)).unit).map { handler =>
                for
                    forged    <- postStatus(handler)(signedRequest(_, textWebhookBody, secret = "wrong"))
                    broken    <- postStatus(handler)(signedRequest(_, brokenBody))
                    decoded   <- Abort.run(WhatsAppWebhook.decode(Span.from(brokenBody.getBytes("UTF-8"))))
                    delivered <- Abort.run[Closed](captured.poll)
                yield
                    assert(forged == Result.succeed(HttpStatus.Forbidden))
                    assert(broken == Result.succeed(HttpStatus.OK))
                    assert(decoded == Result.fail(WhatsAppDecodeException(
                        "webhook",
                        WhatsAppDecodeException.Part.Notification,
                        WhatsAppDecodeException.Failure.Parse,
                        Chunk.empty,
                        Present(46)
                    )))
                    assert(delivered == Result.succeed(Absent), s"the callback was invoked: $delivered")
            }
        }
    }

    "a callback that fails with its own error type gets a 500, after the notifications before it were delivered" in {
        Channel.init[WhatsAppNotification](16).map { captured =>
            WhatsAppWebhook.handler(configAt(1), webhook) {
                case m: WhatsAppNotification.InboundMessage if m.id.value == "wamid.B" =>
                    Abort.fail(WhatsAppWebhookTest.Rejected(m.id.value))
                case other => Abort.run[Closed](captured.put(other)).unit
            }.map { handler =>
                postStatus(handler)(signedRequest(_, twoMessagesBody)).map { status =>
                    assert(status == Result.succeed(HttpStatus.InternalServerError))
                    Abort.run[Closed](captured.take).map { first =>
                        assert(first == Result.succeed(inbound("wamid.A", 1749416383L, "first")))
                        Abort.run[Closed](captured.poll).map(rest => assert(rest == Result.succeed(Absent)))
                    }
                }.andThen {
                    // The same handler invoked directly surfaces the caller's error value, not a module leaf.
                    Abort.run[HttpResponse.Halt](Abort.run[WhatsAppWebhookTest.Rejected](handler(signedRequest(0, twoMessagesBody)))).map {
                        result => assert(result.map(_.failure) == Result.succeed(Present(WhatsAppWebhookTest.Rejected("wamid.B"))))
                    }
                }
            }
        }
    }

    "a callback that panics gets a 500" in {
        WhatsAppWebhook.handler(configAt(1), webhook)(_ => Abort.panic(new IllegalStateException("callback defect"))).map { handler =>
            postStatus(handler)(signedRequest(_, twoMessagesBody)).map { status =>
                assert(status == Result.succeed(HttpStatus.InternalServerError))
            }
        }
    }

    "a callback that lets a verb's failure through gets a 500 when the verb fails, and the handler surfaces that failure" in {
        WhatsAppWebhook.handler(configAt(1), webhook)(_ => WhatsApp.send(WhatsAppId.WaId("16505551234"), WhatsAppMessage.Text("echo")).unit)
            .map { handler =>
                postStatus(handler)(signedRequest(_, twoMessagesBody)).map { status =>
                    assert(status == Result.succeed(HttpStatus.InternalServerError))
                }.andThen {
                    Abort.run[HttpResponse.Halt](Abort.run[WhatsAppSendFailure](handler(signedRequest(0, twoMessagesBody)))).map { result =>
                        assert(result.map(_.failure) == Result.succeed(Present(
                            WhatsAppTransportException("send", WhatsAppTransportException.Kind.Connect, "localhost", 1, Absent)()
                        )))
                    }
                }
            }
    }

    "neither handler prints its secret" in {
        val secretText = Seq("SECRET", "APP", "e61c02").mkString("-")
        val tokenText  = Seq("SECRET", "VERIFY", "70ad35").mkString("-")
        val secrets    = WhatsAppWebhookConfig(WhatsAppAppSecret(secretText), WhatsAppVerifyToken(tokenText))
        WhatsAppWebhook.handler(configAt(1), secrets)(_ => Kyo.unit).map { handler =>
            val handshake = WhatsAppWebhook.verificationHandler(secrets)
            Chunk(secrets.toString, handler.toString, handler.route.toString, handshake.toString, handshake.route.toString).foreach {
                text =>
                    assert(!text.contains(secretText))
                    assert(!text.contains(tokenText))
            }
            succeed
        }
    }

    "the decode acknowledgement logs its own text and the decode failure, never the app secret or the body; a signature failure logs nothing" in {
        val secretText        = Seq("SECRET", "APP", "0d93b8").mkString("-")
        val plantedBodySecret = "PLANTED-BODY-SECRET"
        val undecodable       = s"""{"entry":$plantedBodySecret}"""
        val secrets           = webhook.copy(appSecret = WhatsAppAppSecret(secretText))
        // Unsafe: the recording sink is read and written from the Log.Unsafe callbacks, which run under AllowUnsafe.
        import AllowUnsafe.embrace.danger
        val sink = AtomicRef.Unsafe.init(Chunk.empty[String])
        WhatsAppWebhook.handler(configAt(1), secrets)(_ => Kyo.unit).map { handler =>
            Log.let(Log(BaseWhatsAppTest.RecordingLog(sink))) {
                Abort.run[HttpResponse.Halt](handler(signedRequest(0, twoMessagesBody, "wrong-secret"))).map { forged =>
                    assert(forged.failure.map(_.response.status) == Present(HttpStatus.Forbidden))
                    Log.flush.andThen(assert(sink.get() == Chunk.empty))
                }.andThen {
                    Abort.run[HttpResponse.Halt](handler(signedRequest(0, undecodable, secretText))).map(acked =>
                        Log.flush.andThen(acked)
                    ).map {
                        acked =>
                            assert(acked.map(_.status) == Result.succeed(HttpStatus.OK))
                            val lines = sink.get()
                            assert(lines == Chunk(
                                "WhatsApp webhook acknowledged a body it cannot decode: WhatsApp webhook notification did not decode: " +
                                    "the JSON does not parse, position 9."
                            ))
                            succeed
                    }
                }
            }
        }
    }

    /** The handshake's answer when `configured` is the verify token and `presented` is the echoed `hub.verify_token`. */
    def handshake(configured: String, presented: String)(using
        Frame,
        kyo.test.AssertScope
    ): String < (Async & Scope & Abort[HttpBindException]) =
        HttpServer.init(
            0,
            "localhost"
        )(WhatsAppWebhook.verificationHandler(webhook.copy(verifyToken = WhatsAppVerifyToken(configured)))).map {
            server =>
                Abort.run[HttpException](HttpClient.getText(
                    s"http://localhost:${server.port}/?hub.mode=subscribe&hub.verify_token=$presented&hub.challenge=42"
                )).map {
                    case Result.Success(body)                   => body
                    case Result.Failure(e: HttpStatusException) => e.status.code.toString
                    case other                                  => fail(s"expected a body or a status failure, got: $other")
                }
        }

    "a verify token of 300 characters answers the handshake, since Meta documents no bound" in {
        val long = "t" * 300
        handshake(long, long).map(answer => assert(answer == "42"))
    }

    "the verify token comparison answers equal, different, different lengths and empty" in {
        Kyo.foreach(Chunk(
            ("MYTOKEN", "MYTOKEN"),
            ("MYTOKEN", "MYTOKEX"),
            ("MYTOKEN", "MYTOKEN2"),
            ("MYTOKEN", "MYTOKE"),
            ("MYTOKEN", "")
        )) { case (configured, presented) =>
            handshake(configured, presented)
        }.map { answers =>
            assert(answers == Chunk("42", "403", "403", "403", "403"))
        }
    }

end WhatsAppWebhookTest

object WhatsAppWebhookTest:
    final case class Rejected(id: String) derives CanEqual
