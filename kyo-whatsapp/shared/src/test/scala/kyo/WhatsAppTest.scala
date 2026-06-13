package kyo

import kyo.WhatsAppId.*
import kyo.internal.whatsapp.Codec as WhatsAppCodec

class WhatsAppTest extends BaseWhatsAppTest:

    val token   = "TEST_TOKEN"
    val phoneId = WhatsAppId.PhoneNumberId("106540352242922")
    val to      = WhatsAppId.WaId("16505551234")

    val sendOkBody =
        """{"messaging_product":"whatsapp","contacts":[{"wa_id":"W"}],"messages":[{"id":"wamid.X","message_status":"accepted"}]}"""
    val successBody = """{"success":true}"""

    def rateLimitErrorBody(code: Int = 130429): String =
        s"""{"error":{"code":$code,"type":"OAuthException","message":"Rate limit hit","fbtrace_id":"fb123"}}"""

    def windowClosedBody: String =
        """{"error":{"code":131047,"type":"OAuthException","message":"Window closed","fbtrace_id":"fb1"}}"""

    def templateErrorBody: String =
        """{"error":{"code":132001,"type":"OAuthException","message":"Template does not exist","fbtrace_id":"fb2"}}"""

    def invalidParamBody(code: Int = 100): String =
        s"""{"error":{"code":$code,"type":"OAuthException","message":"Invalid param","fbtrace_id":"fb3"}}"""

    def authErrorBody(code: Int = 190): String =
        s"""{"error":{"code":$code,"type":"OAuthException","message":"Token expired","fbtrace_id":"fb4"}}"""

    val expectedPath = s"/v25.0/${phoneId.value}/messages"

    def makeConfig(port: Int): WhatsAppConfig =
        WhatsAppConfig(token, phoneId, baseUrl = s"http://localhost:$port")

    def withSendServer[A, S](responseBody: String, statusOk: Boolean = true)(
        test: (Int, Channel[Tuple3[String, String, String]]) => A < S
    )(using Frame): A < (S & Async & Scope) =
        Channel.init[Tuple3[String, String, String]](1).map { captured =>
            val route = HttpRoute.postRaw("v25.0" / phoneId.value / "messages")
                .request(_.bodyBinary)
                .response(_.bodyText)
            val handler = route.handler { req =>
                val bodyStr = new String(req.fields.body.toArrayUnsafe, "UTF-8")
                val auth    = req.headers.get("Authorization").getOrElse("")
                val path    = req.path
                captured.put((path, auth, bodyStr)).map { _ =>
                    if statusOk then HttpResponse.ok(responseBody)
                    else HttpResponse.badRequest(responseBody)
                }
            }
            HttpClient.init().map { httpClient =>
                HttpServer.init(0, "localhost")(handler).map { s =>
                    HttpClient.let(httpClient) {
                        test(s.port, captured)
                    }
                }
            }
        }

    "let binds Config visible to use within the scope" in {
        val cfg = makeConfig(0)
        WhatsApp.let(cfg)(
            WhatsApp.use(c => c.phoneNumberId.value)
        ).map { result =>
            assert(result == phoneId.value)
        }
    }

    "send POSTs to the versioned messages endpoint with bearer".notNative in {
        withSendServer(sendOkBody) { (port, captured) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                WhatsApp.send(to, WhatsAppMessage.Text("hi")).map { _ =>
                    captured.take.map { case (path, auth, _) =>
                        assert(path == expectedPath)
                        assert(auth == s"Bearer $token")
                    }
                }
            }
        }
    }

    "send decodes E response into SendResult".notNative in {
        withSendServer(sendOkBody) { (port, captured) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                WhatsApp.send(to, WhatsAppMessage.Text("hi")).map { result =>
                    captured.take.map { _ =>
                        assert(result.messageId == WhatsAppId.MessageId("wamid.X"))
                        assert(result.contactWaId == Present(WhatsAppId.WaId("W")))
                        assert(result.status == Present(WhatsAppSendResult.Status.Accepted))
                    }
                }
            }
        }
    }

    "send body equals the encoded WhatsAppMessage envelope".notNative in {
        withSendServer(sendOkBody) { (port, captured) =>
            val cfg     = makeConfig(port)
            val img     = WhatsAppMessage.Image(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("M")))
            val encoded = new String(WhatsAppCodec.encodeSend(to, img, Absent).toArrayUnsafe, "UTF-8")
            WhatsApp.let(cfg) {
                WhatsApp.send(to, img).map { _ =>
                    captured.take.map { case (_, _, body) =>
                        assert(body == encoded)
                    }
                }
            }
        }
    }

    "send with replyTo includes the context object".notNative in {
        withSendServer(sendOkBody) { (port, captured) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                WhatsApp.send(to, WhatsAppMessage.Text("re"), Present(WhatsAppId.MessageId("MSG_ID"))).map { _ =>
                    captured.take.map { case (_, _, body) =>
                        assert(body.contains(""""context":{"message_id":"MSG_ID"}"""))
                    }
                }
            }
        }
    }

    "a 4xx Graph error maps to the typed RateLimited leaf".notNative in {
        withSendServer(rateLimitErrorBody(130429), statusOk = false) { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    WhatsApp.send(to, WhatsAppMessage.Text("hi"))
                }
            ).map { result =>
                result match
                    case Result.Failure(WhatsAppError.RateLimited(130429, _, WhatsAppError.RateLimited.Throughput)) =>
                        assert(true)
                    case other =>
                        assert(false, s"expected RateLimited(130429), got: $other")
            }
        }
    }

    "a 131047 error maps to WindowClosed".notNative in {
        withSendServer(windowClosedBody, statusOk = false) { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    WhatsApp.send(to, WhatsAppMessage.Text("hi"))
                }
            ).map { result =>
                result match
                    case Result.Failure(WhatsAppError.WindowClosed()) => assert(true)
                    case other                                        => assert(false, s"expected WindowClosed, got: $other")
            }
        }
    }

    "sendTemplate POSTs the template envelope and decodes SendResult".notNative in {
        withSendServer(sendOkBody) { (port, captured) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                WhatsApp.sendTemplate(to, WhatsAppTemplate("hello_world", "en_US")).map { result =>
                    captured.take.map { case (path, _, body) =>
                        assert(path == expectedPath)
                        assert(body.contains(""""type":"template""""))
                        assert(body.contains(""""template":"""))
                        assert(result.messageId == WhatsAppId.MessageId("wamid.X"))
                    }
                }
            }
        }
    }

    "sendTemplate surfaces TemplateError on 132001".notNative in {
        withSendServer(templateErrorBody, statusOk = false) { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    WhatsApp.sendTemplate(to, WhatsAppTemplate("hello_world", "en_US"))
                }
            ).map { result =>
                result match
                    case Result.Failure(WhatsAppError.TemplateError.DoesNotExist()) => assert(true)
                    case other => assert(false, s"expected TemplateError.DoesNotExist, got: $other")
            }
        }
    }

    "markRead POSTs the status:read body and consumes {success:true}".notNative in {
        withSendServer(successBody) { (port, captured) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                WhatsApp.markRead(WhatsAppId.MessageId("wamid.IN")).map { result =>
                    captured.take.map { case (path, _, body) =>
                        assert(path == expectedPath)
                        assert(body.contains(""""status":"read""""))
                        assert(body.contains(""""message_id":"wamid.IN""""))
                        assert(!body.contains("typing_indicator"))
                        assert(result == ())
                    }
                }
            }
        }
    }

    "markReadWithTyping adds the typing_indicator object".notNative in {
        withSendServer(successBody) { (port, captured) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                WhatsApp.markReadWithTyping(WhatsAppId.MessageId("wamid.IN")).map { result =>
                    captured.take.map { case (_, _, body) =>
                        assert(body.contains(""""typing_indicator":"""))
                        assert(body.contains(""""type":"text""""))
                        assert(result == ())
                    }
                }
            }
        }
    }

    "markRead maps a Graph error to InvalidParameter".notNative in {
        withSendServer(invalidParamBody(100), statusOk = false) { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    WhatsApp.markRead(WhatsAppId.MessageId("wamid.IN"))
                }
            ).map { result =>
                result match
                    case Result.Failure(WhatsAppError.InvalidParameter(100, _)) => assert(true)
                    case other => assert(false, s"expected InvalidParameter(100), got: $other")
            }
        }
    }

    "a connection failure surfaces WhatsAppError.Transport".notNative in {
        HttpClient.init().map { httpClient =>
            Scope.run(
                HttpServer.init(0, "localhost")().map { deadServer =>
                    deadServer.port
                }
            ).map { deadPort =>
                HttpClient.let(httpClient) {
                    val cfg = WhatsAppConfig(token, phoneId, baseUrl = s"http://localhost:$deadPort")
                    Abort.run[WhatsAppError](
                        WhatsApp.let(cfg) {
                            WhatsApp.send(to, WhatsAppMessage.Text("hi"))
                        }
                    ).map { result =>
                        result match
                            case Result.Failure(_: WhatsAppError.Transport) => assert(true)
                            case other                                      => assert(false, s"expected Transport failure, got: $other")
                    }
                }
            }
        }
    }

    "the effect row is exactly Async & Abort[WhatsAppError]" in {
        typeCheck("""
            import kyo.*
            import kyo.WhatsAppId.*
            val to2: WhatsAppId.WaId = WhatsAppId.WaId("16505551234")
            val _: WhatsAppSendResult < (Async & Abort[WhatsAppError]) =
                WhatsApp.send(to2, WhatsAppMessage.Text("hi"))
        """)
    }

    "send of empty Contacts posts contacts:[] and surfaces InvalidParameter".notNative in {
        withSendServer(invalidParamBody(131009), statusOk = false) { (port, captured) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    WhatsApp.send(to, WhatsAppMessage.Contacts(Chunk.empty))
                }
            ).map { result =>
                captured.take.map { case (_, _, body) =>
                    assert(body.contains(""""contacts":[]"""))
                    result match
                        case Result.Failure(WhatsAppError.InvalidParameter(131009, _)) => assert(true)
                        case other => assert(false, s"expected InvalidParameter(131009), got: $other")
                }
            }
        }
    }

    "send of a reaction-remove posts emoji:\"\" and succeeds".notNative in {
        withSendServer(sendOkBody) { (port, captured) =>
            val cfg = makeConfig(port)
            WhatsApp.let(cfg) {
                WhatsApp.send(to, WhatsAppMessage.Reaction(WhatsAppId.MessageId("wamid.T"), "")).map { result =>
                    captured.take.map { case (_, _, body) =>
                        assert(body.contains(""""message_id":"wamid.T""""))
                        assert(body.contains(""""emoji":"""""))
                        assert(result.messageId == WhatsAppId.MessageId("wamid.X"))
                    }
                }
            }
        }
    }

    "a structurally-broken 200 response surfaces DecodeError".notNative in {
        withSendServer("""{"messaging_product":""") { (port, _) =>
            val cfg = makeConfig(port)
            Abort.run[WhatsAppError](
                WhatsApp.let(cfg) {
                    WhatsApp.send(to, WhatsAppMessage.Text("hi"))
                }
            ).map { result =>
                result match
                    case Result.Failure(_: WhatsAppError.DecodeError) => assert(true)
                    case other                                        => assert(false, s"expected DecodeError, got: $other")
            }
        }
    }

    "send posts Content-Type application/json header".notNative in {
        withSendServer(sendOkBody) { (port, _) =>
            Channel.init[String](1).map { ctCapture =>
                val route = HttpRoute.postRaw("v25.0" / phoneId.value / "messages")
                    .request(_.bodyBinary)
                    .response(_.bodyText)
                val handler = route.handler { req =>
                    val ct = req.headers.get("Content-Type").getOrElse("")
                    ctCapture.put(ct).map(_ => HttpResponse.ok(sendOkBody))
                }
                HttpClient.init().map { httpClient =>
                    HttpServer.init(0, "localhost")(handler).map { s =>
                        HttpClient.let(httpClient) {
                            val cfg = makeConfig(s.port)
                            WhatsApp.let(cfg) {
                                WhatsApp.send(to, WhatsAppMessage.Text("hi")).map { _ =>
                                    ctCapture.take.map { ct =>
                                        assert(ct == "application/json")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end WhatsAppTest
