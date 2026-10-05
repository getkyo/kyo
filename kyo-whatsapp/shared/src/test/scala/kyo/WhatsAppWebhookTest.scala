package kyo

class WhatsAppWebhookTest extends BaseWhatsAppTest:

    val verifyTokenText: String = "MYTOKEN"
    val appSecretText: String   = "appsecret"
    val phoneId                 = WhatsAppId.PhoneNumberId("106540352242922")

    val webhook: WhatsAppWebhookConfig = webhookConfigOf(appSecretText, verifyTokenText)

    /** A config whose Graph server is on `port`; port 1 is privileged and unused, so a call through it is refused. */
    def configAt(port: Int)(using Frame): WhatsAppConfig =
        configOf("TOKEN", phoneId, baseUrl = url(s"http://localhost:$port"))

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
        WhatsAppNotification.Message(
            WhatsAppNotification.Metadata("15550783881", phoneId),
            Present(WhatsAppWebhookPayload.Contact(
                Present(WhatsAppWebhookPayload.Contact.Profile("Sheena Nelson")),
                WhatsAppId.WaId("16505551234")
            )),
            WhatsAppInboundMessage.Text(
                WhatsAppId.WaId("16505551234"),
                WhatsAppId.MessageId("wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA="),
                epoch(1749416383L),
                Absent,
                WhatsAppInboundMessage.Text.Body("Does it come in another color?")
            )
        )

    val twoMessagesBody: String =
        """{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{"messaging_product":"whatsapp",""" +
            """"metadata":{"display_phone_number":"15550783881","phone_number_id":"106540352242922"},"messages":[""" +
            """{"from":"16505551234","id":"wamid.A","timestamp":"1749416383","type":"text","text":{"body":"first"}},""" +
            """{"from":"16505551234","id":"wamid.B","timestamp":"1749416384","type":"text","text":{"body":"second"}}]}}]}]}"""

    def inbound(id: String, timestamp: Long, text: String): WhatsAppNotification =
        WhatsAppNotification.Message(
            WhatsAppNotification.Metadata("15550783881", phoneId),
            Absent,
            WhatsAppInboundMessage.Text(
                WhatsAppId.WaId("16505551234"),
                WhatsAppId.MessageId(id),
                epoch(timestamp),
                Absent,
                WhatsAppInboundMessage.Text.Body(text)
            )
        )

    def signedRequest(port: Int, body: String, secret: String = appSecretText, path: String = "/")(using
        Frame
    ): HttpRequest["body" ~ Span[Byte]] =
        val bytes = utf8(body)
        HttpRequest.postRaw(HttpUrl(Present("http"), "localhost", port, path, Absent))
            .addHeader("X-Hub-Signature-256", signatureOf(secret, bytes))
            .addHeader("Content-Type", "application/json")
            .addField("body", bytes)
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
        HttpServer.init(0, "localhost")(WhatsApp.Webhook.verificationHandler(webhook)).map { server =>
            HttpClient.getText(
                s"http://localhost:${server.port}/?hub.mode=subscribe&hub.verify_token=$verifyTokenText&hub.challenge=1158201444"
            ).map(body => assert(body == "1158201444"))
        }
    }

    "verificationHandler returns 403 on a token mismatch and on a mode other than subscribe" in {
        HttpServer.init(0, "localhost")(WhatsApp.Webhook.verificationHandler(webhook)).map { server =>
            def status(query: String) =
                Abort.run[HttpException](HttpClient.getText(s"http://localhost:${server.port}/?$query")).map {
                    case Result.Failure(e: HttpStatusException) => Present(e.status)
                    case other                                  => Absent
                }
            for
                wrong <- status("hub.mode=subscribe&hub.verify_token=WRONG&hub.challenge=1")
                mode  <- status(s"hub.mode=unsubscribe&hub.verify_token=$verifyTokenText&hub.challenge=1")
                none  <- status(s"hub.mode=subscribe&hub.verify_token=$verifyTokenText")
            yield assert(Seq(wrong, mode, none) == Seq.fill(3)(Present(HttpStatus.Forbidden)))
            end for
        }
    }

    "both handlers are mounted at the webhook config's path" in {
        val mounted = webhookConfigOf(appSecretText, verifyTokenText, "hooks/whatsapp")
        Channel.init[WhatsAppNotification](4).map { captured =>
            WhatsApp.run(configAt(1))(WhatsApp.Webhook.handler(mounted)(n => Abort.run[Closed](captured.put(n)).unit).map { handler =>
                HttpServer.init(0, "localhost")(WhatsApp.Webhook.verificationHandler(mounted), handler).map { server =>
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
            })
        }
    }

    "handler verifies the byte-exact body and 200s, callback fires" in {
        Channel.init[WhatsAppNotification](16).map { captured =>
            WhatsApp.run(configAt(1))(WhatsApp.Webhook.handler(webhook)(n => Abort.run[Closed](captured.put(n)).unit).map { handler =>
                postStatus(handler)(signedRequest(_, textWebhookBody)).map { status =>
                    Abort.run[Closed](captured.take).map { taken =>
                        assert(status == Result.succeed(HttpStatus.OK))
                        assert(taken == Result.succeed(textNotification))
                    }
                }
            })
        }
    }

    "the callback calls the verbs on the caller's client" in {
        Channel.init[(String, String)](4).map { requests =>
            val graph = HttpRoute.postRaw("v25.0" / phoneId.value / "messages").request(_.bodyText).response(_.bodyText).handler { req =>
                requests.put((
                    req.headers.get("Authorization").getOrElse(""),
                    req.fields.body
                )).andThen(HttpResponse.ok("""{"success":true}"""))
            }
            HttpServer.init(0, "localhost")(graph).map { graphServer =>
                WhatsApp.run(configAt(graphServer.port))(WhatsApp.Webhook.handler(webhook) {
                    case WhatsAppNotification.Message(_, _, m: WhatsAppInboundMessage.Common) => WhatsApp.markRead(m.id)
                    case _                                                                    => Kyo.unit
                }.map { handler =>
                    postStatus(handler)(signedRequest(_, textWebhookBody)).map { status =>
                        requests.take.map { case (auth, body) =>
                            assert(status == Result.succeed(HttpStatus.OK))
                            assert(auth == "Bearer TOKEN")
                            assert(body.contains(""""message_id":"wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA=""""))
                        }
                    }
                })
            }
        }
    }

    "a bad signature returns 403 and skips the callback; a broken body returns 200 while standalone decode fails" in {
        Channel.init[WhatsAppNotification](16).map { captured =>
            val brokenBody = """{"object":"whatsapp_business_account","entry":}"""
            WhatsApp.run(configAt(1))(WhatsApp.Webhook.handler(webhook)(n => Abort.run[Closed](captured.put(n)).unit).map { handler =>
                for
                    forged    <- postStatus(handler)(signedRequest(_, textWebhookBody, secret = "wrong"))
                    broken    <- postStatus(handler)(signedRequest(_, brokenBody))
                    decoded   <- Abort.run(WhatsApp.Webhook.decode(utf8(brokenBody)))
                    delivered <- Abort.run[Closed](captured.poll)
                yield
                    assert(forged == Result.succeed(HttpStatus.Forbidden))
                    assert(broken == Result.succeed(HttpStatus.OK))
                    assert(decoded == Result.fail(WhatsAppDecodeException(
                        "webhook",
                        WhatsAppDecodeException.Part.Notification,
                        WhatsAppDecodeException.Failure.Parse,
                        Chunk("entry"),
                        Present(46)
                    )))
                    assert(delivered == Result.succeed(Absent), s"the callback was invoked: $delivered")
            })
        }
    }

    "a callback that fails with its own error type gets a 500, after the notifications before it were delivered" in {
        Channel.init[WhatsAppNotification](16).map { captured =>
            WhatsApp.run(configAt(1))(WhatsApp.Webhook.handler(webhook) {
                case WhatsAppNotification.Message(_, _, m: WhatsAppInboundMessage.Common) if m.id.value == "wamid.B" =>
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
            })
        }
    }

    "a callback that panics gets a 500" in {
        WhatsApp.run(configAt(1))(WhatsApp.Webhook.handler(webhook)(_ => Abort.panic(new IllegalStateException("callback defect"))).map {
            handler =>
                postStatus(handler)(signedRequest(_, twoMessagesBody)).map { status =>
                    assert(status == Result.succeed(HttpStatus.InternalServerError))
                }
        })
    }

    "a callback that lets a verb's failure through gets a 500 when the verb fails, and the handler surfaces that failure" in {
        WhatsApp.run(configAt(1))(WhatsApp.Webhook.handler(webhook)(_ =>
            WhatsApp.send(WhatsAppId.WaId("16505551234"), WhatsAppMessage.Text("echo")).unit
        ).map { handler =>
            postStatus(handler)(signedRequest(_, twoMessagesBody)).map { status =>
                assert(status == Result.succeed(HttpStatus.InternalServerError))
            }.andThen {
                Abort.run[HttpResponse.Halt](Abort.run[WhatsAppSendFailure](handler(signedRequest(0, twoMessagesBody)))).map { result =>
                    assert(result.map(_.failure) == Result.succeed(Present(
                        WhatsAppTransportException("send", WhatsAppTransportException.Kind.Connect, "localhost", 1, Absent)()
                    )))
                }
            }
        })
    }

    "a handler taken out of its client's region holds a closed client, so a callback's verb fails and Meta gets a 500" in {
        AtomicInt.init(0).map { hits =>
            val graph = HttpRoute.postRaw("v25.0" / phoneId.value / "messages").request(_.bodyText).response(_.bodyText).handler { _ =>
                hits.incrementAndGet.andThen(HttpResponse.ok("""{"success":true}"""))
            }
            HttpServer.init(0, "localhost")(graph).map { graphServer =>
                WhatsApp.run(configAt(graphServer.port))(WhatsApp.Webhook.handler(webhook) {
                    case WhatsAppNotification.Message(_, _, m: WhatsAppInboundMessage.Common) => WhatsApp.markRead(m.id)
                    case _                                                                    => Kyo.unit
                }).map { escaped =>
                    postStatus(escaped)(signedRequest(_, textWebhookBody)).map { status =>
                        hits.get.map { count =>
                            assert(status == Result.succeed(HttpStatus.InternalServerError))
                            assert(count == 0, s"the closed client reached the Graph server $count times")
                        }
                    }
                }
            }
        }
    }

    "neither handler prints its secret" in {
        val secretText = Seq("SECRET", "APP", "e61c02").mkString("-")
        val tokenText  = Seq("SECRET", "VERIFY", "70ad35").mkString("-")
        val secrets    = webhookConfigOf(secretText, tokenText)
        WhatsApp.run(configAt(1))(WhatsApp.Webhook.handler(secrets)(_ => Kyo.unit).map { handler =>
            val handshake = WhatsApp.Webhook.verificationHandler(secrets)
            Chunk(secrets.toString, handler.toString, handler.route.toString, handshake.toString, handshake.route.toString).foreach {
                text =>
                    assert(!text.contains(secretText))
                    assert(!text.contains(tokenText))
            }
            succeed
        })
    }

    "the decode acknowledgement logs its own text and the decode failure, never the app secret or the body; a signature failure logs nothing" in {
        val secretText        = Seq("SECRET", "APP", "0d93b8").mkString("-")
        val plantedBodySecret = "PLANTED-BODY-SECRET"
        val undecodable       = s"""{"entry":$plantedBodySecret}"""
        val secrets           = webhookConfigOf(secretText, verifyTokenText)
        AtomicRef.init(Chunk.empty[String]).map { sink =>
            WhatsApp.run(configAt(1))(WhatsApp.Webhook.handler(secrets)(_ => Kyo.unit).map { handler =>
                Log.let(Log(BaseWhatsAppTest.RecordingLog(sink.unsafe))) {
                    Abort.run[HttpResponse.Halt](handler(signedRequest(0, twoMessagesBody, "wrong-secret"))).map { forged =>
                        assert(forged.failure.map(_.response.status) == Present(HttpStatus.Forbidden))
                        Log.flush.andThen(sink.get).map(lines => assert(lines == Chunk.empty))
                    }.andThen {
                        Abort.run[HttpResponse.Halt](handler(signedRequest(0, undecodable, secretText))).map { acked =>
                            assert(acked.map(_.status) == Result.succeed(HttpStatus.OK))
                            Log.flush.andThen(sink.get).map { lines =>
                                assert(lines == Chunk(
                                    "WhatsApp webhook acknowledged a body it cannot decode: WhatsApp webhook notification did not decode: " +
                                        "the JSON does not parse at entry, position 9."
                                ))
                            }
                        }
                    }
                }
            })
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
        )(WhatsApp.Webhook.verificationHandler(webhookConfigOf(appSecretText, configured))).map {
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
