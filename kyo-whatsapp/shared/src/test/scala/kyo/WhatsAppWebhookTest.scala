package kyo

import kyo.internal.whatsapp.Hmac

class WhatsAppWebhookTest extends BaseWhatsAppTest:

    val verifyToken: String = "MYTOKEN"

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

    def computeSignature(secret: String, body: Array[Byte]): String =
        s"sha256=${Hmac.hexLower(Hmac.hmacSha256(secret.getBytes("UTF-8"), body))}"

    "verificationHandler echoes hub.challenge on a token match".notNative in {
        HttpClient.init().map { client =>
            val handler = WhatsAppWebhook.verificationHandler(verifyToken)
            HttpServer.init(0, "localhost")(handler).map { server =>
                HttpClient.let(client) {
                    HttpClient.getBinary(
                        s"http://localhost:${server.port}/?hub.mode=subscribe&hub.verify_token=$verifyToken&hub.challenge=1158201444"
                    ).map { resp =>
                        val body = new String(resp.toArrayUnsafe, "UTF-8")
                        assert(body == "1158201444")
                    }
                }
            }
        }
    }

    "verificationHandler returns 403 on a token mismatch".notNative in {
        HttpClient.init().map { client =>
            val handler = WhatsAppWebhook.verificationHandler(verifyToken)
            HttpServer.init(0, "localhost")(handler).map { server =>
                HttpClient.let(client) {
                    Abort.run[HttpException](
                        HttpClient.getBinary(
                            s"http://localhost:${server.port}/?hub.mode=subscribe&hub.verify_token=WRONG&hub.challenge=1158201444"
                        )
                    ).map { result =>
                        result match
                            case Result.Failure(e: HttpStatusException) =>
                                assert(e.status.code == 403)
                            case other =>
                                assert(false, s"expected 403 HttpStatusException, got: $other")
                    }
                }
            }
        }
    }

    "handler verifies the byte-exact body and 200s, callback fires".notNative in {
        Channel.init[WhatsAppNotification](16).map { captured =>
            val appSecret = "appsecret"
            val bodyBytes = textWebhookBody.getBytes("UTF-8")
            val sig       = computeSignature(appSecret, bodyBytes)

            val webhookHandler = WhatsAppWebhook.handler(appSecret) { n =>
                Abort.run[Closed](captured.put(n)).unit
            }

            HttpClient.init().map { client =>
                HttpServer.init(0, "localhost")(webhookHandler).map { server =>
                    HttpClient.let(client) {
                        val url   = HttpUrl(Present("http"), "localhost", server.port, "/", Absent)
                        val route = HttpRoute.postRaw("/").request(_.bodyBinary).response(_.bodyBinary)
                        val req = HttpRequest.postRaw(url)
                            .addHeader("X-Hub-Signature-256", sig)
                            .addHeader("Content-Type", "application/json")
                            .addField("body", Span.from(bodyBytes))
                        HttpClient.use(_.sendWith(route, req) { resp =>
                            assert(resp.status.code == 200)
                            Abort.run[Closed](captured.take).map {
                                case Result.Success(notification) =>
                                    notification match
                                        case msg: WhatsAppNotification.InboundMessage =>
                                            assert(msg.content == WhatsAppNotification.Content.Text("Does it come in another color?"))
                                        case other =>
                                            assert(false, s"expected InboundMessage, got: $other")
                                case other =>
                                    assert(false, s"expected Success from channel take, got: $other")
                            }
                        })
                    }
                }
            }
        }
    }

    "a bad signature returns 403 and skips the callback; a broken change returns 200 while standalone decode fails".notNative in {
        Channel.init[WhatsAppNotification](16).map { captured =>
            val appSecret = "appsecret"
            val bodyBytes = textWebhookBody.getBytes("UTF-8")
            val wrongSig  = "sha256=" + "0" * 64

            val webhookHandler = WhatsAppWebhook.handler(appSecret) { n =>
                Abort.run[Closed](captured.put(n)).unit
            }

            val brokenBody = """{"object":"whatsapp_business_account","entry":""".getBytes("UTF-8")
            val brokenSig  = computeSignature(appSecret, brokenBody)

            HttpClient.init().map { client =>
                HttpServer.init(0, "localhost")(webhookHandler).map { server =>
                    HttpClient.let(client) {
                        val route = HttpRoute.postRaw("/").request(_.bodyBinary).response(_.bodyBinary)

                        // (a) bad signature -> 403, f not invoked
                        val badSigReq = HttpRequest.postRaw(HttpUrl(Present("http"), "localhost", server.port, "/", Absent))
                            .addHeader("X-Hub-Signature-256", wrongSig)
                            .addHeader("Content-Type", "application/json")
                            .addField("body", Span.from(bodyBytes))

                        HttpClient.use(_.sendWith(route, badSigReq) { resp =>
                            assert(resp.status.code == 403)
                        }).map { _ =>
                            // confirm the channel is empty (f was NOT invoked)
                            Abort.run[Closed](captured.poll).map {
                                case Result.Success(maybeN) =>
                                    assert(maybeN == Absent, s"f should not have been invoked, but got: $maybeN")
                                case _ => ()
                            }
                        }.map { _ =>
                            // (b) broken body with correct sig -> 200, logged-and-skipped
                            val brokenReq = HttpRequest.postRaw(HttpUrl(Present("http"), "localhost", server.port, "/", Absent))
                                .addHeader("X-Hub-Signature-256", brokenSig)
                                .addHeader("Content-Type", "application/json")
                                .addField("body", Span.from(brokenBody))

                            HttpClient.use(_.sendWith(route, brokenReq) { resp =>
                                assert(resp.status.code == 200)
                            }).map { _ =>
                                // standalone decode of the same broken body fails with DecodeError
                                Abort.run[WhatsAppError.DecodeError](
                                    WhatsAppWebhook.decode(Span.from(brokenBody))
                                ).map {
                                    case Result.Failure(_: WhatsAppError.DecodeError) => assert(true)
                                    case other                                        => assert(false, s"expected DecodeError, got: $other")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end WhatsAppWebhookTest
