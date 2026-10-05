package kyo

import kyo.internal.whatsapp.Codec as WhatsAppCodec
import kyo.internal.whatsapp.Graph

class WhatsAppTest extends BaseWhatsAppTest:

    val token   = "TEST_TOKEN"
    val phoneId = WhatsAppId.PhoneNumberId("106540352242922")
    val to      = WhatsAppId.WaId("16505551234")

    val sendOkBody =
        """{"messaging_product":"whatsapp","contacts":[{"wa_id":"W"}],"messages":[{"id":"wamid.X","message_status":"accepted"}]}"""
    val sendOkResult = sendResultOf(
        Chunk(WhatsAppSendResult.Contact(waId = Present(WhatsAppId.WaId("W")))),
        Chunk(WhatsAppSendResult.Message(WhatsAppId.MessageId("wamid.X"), Present(WhatsAppSendResult.Status.Accepted)))
    )
    val successBody = """{"success":true}"""

    def rateLimitErrorBody(code: Int = 130429): String =
        s"""{"error":{"code":$code,"type":"OAuthException","message":"Rate limit hit","fbtrace_id":"fb123"}}"""

    def windowClosedBody: String =
        """{"error":{"code":131047,"type":"OAuthException","message":"Re-engagement message","error_data":{"details":"More than 24 hours have passed"},"fbtrace_id":"fb1"}}"""

    def templateErrorBody: String =
        """{"error":{"code":132001,"type":"OAuthException","message":"Template does not exist","fbtrace_id":"fb2"}}"""

    def invalidParamBody(code: Int = 100): String =
        s"""{"error":{"code":$code,"type":"OAuthException","message":"Invalid param","fbtrace_id":"fb3"}}"""

    val expectedPath = s"/v25.0/${phoneId.value}/messages"

    def localUrl(port: Int)(using Frame): HttpUrl = HttpUrl.parse(s"http://localhost:$port").getOrThrow

    def makeConfig(port: Int)(using Frame): WhatsAppConfig =
        configOf(token, phoneId, baseUrl = localUrl(port))

    def withSendServer[A, S](responseBody: String, statusOk: Boolean = true)(
        test: (Int, Channel[Tuple3[String, String, String]]) => A < S
    )(using Frame): A < (S & Async & Scope & Abort[HttpBindException | HttpRouteException]) =
        Channel.init[Tuple3[String, String, String]](1).map { captured =>
            val route = HttpRoute.postRaw("v25.0" / phoneId.value / "messages")
                .request(_.bodyBinary)
                .response(_.bodyText)
            val handler = route.handler { req =>
                val bodyStr = textOf(req.fields.body)
                val auth    = req.headers.get("Authorization").getOrElse("")
                val path    = req.path
                captured.put((path, auth, bodyStr)).map { _ =>
                    if statusOk then HttpResponse.ok(responseBody)
                    else HttpResponse.badRequest(responseBody)
                }
            }
            HttpServer.init(0, "localhost")(handler).map(s => test(s.port, captured))
        }

    /** A Graph server answering every send with `sendOkBody`, and the number of sends it received. */
    def withCountingServer[A, S](test: (Int, AtomicInt) => A < S)(using
        Frame
    ): A < (S & Async & Scope & Abort[HttpBindException | HttpRouteException]) =
        AtomicInt.init(0).map { hits =>
            val route = HttpRoute.postRaw("v25.0" / phoneId.value / "messages").response(_.bodyText)
                .handler(_ => hits.incrementAndGet.andThen(HttpResponse.ok(sendOkBody)))
            HttpServer.init(0, "localhost")(route).map(server => test(server.port, hits))
        }

    def hello(using Frame): WhatsAppSendResult < (Async & Abort[WhatsAppSendFailure] & Env[WhatsApp]) =
        WhatsApp.send(to, WhatsAppMessage.Text("hi"))

    /** Whether `result` is a send that failed at the transport to the local server on `port`, as a send on a closed client does. */
    def transportFailureOfSendTo(port: Int, result: Result[WhatsAppException, WhatsAppSendResult]): Boolean =
        result match
            case Result.Failure(t: WhatsAppTransportException) => t.method == "send" && t.host == "localhost" && t.port == port
            case _                                             => false

    "init builds a client that sends until its Scope ends, and is closed after it" in {
        withCountingServer { (port, hits) =>
            Scope.run(WhatsApp.init(makeConfig(port)).map(client =>
                Abort.run[WhatsAppException](WhatsApp.run(client)(hello)).map((client, _))
            ))
                .map { (client, inside) =>
                    Abort.run[WhatsAppException](WhatsApp.run(client)(hello)).map { after =>
                        hits.get.map { count =>
                            assert(inside.isSuccess, s"the send inside the Scope failed: $inside")
                            assert(transportFailureOfSendTo(port, after), s"a send after the Scope ended: $after")
                            assert(count == 1)
                        }
                    }
                }
        }
    }

    "initUnscoped builds a client that stays open until close, and close is idempotent" in {
        withCountingServer { (port, hits) =>
            WhatsApp.initUnscoped(makeConfig(port)).map { client =>
                for
                    first  <- Abort.run[WhatsAppException](WhatsApp.run(client)(hello))
                    second <- Abort.run[WhatsAppException](WhatsApp.run(client)(hello))
                    _      <- WhatsApp.close(client)
                    _      <- WhatsApp.close(client)
                    closed <- Abort.run[WhatsAppException](WhatsApp.run(client)(hello))
                    count  <- hits.get
                yield
                    assert(first.isSuccess && second.isSuccess, s"a send before close failed: $first, $second")
                    assert(transportFailureOfSendTo(port, closed), s"a send after close: $closed")
                    assert(count == 2)
            }
        }
    }

    "run(client) provides the client without closing it, so a second region on the same client sends too" in {
        withCountingServer { (port, hits) =>
            WhatsApp.init(makeConfig(port)).map { client =>
                for
                    first  <- Abort.run[WhatsAppException](WhatsApp.run(client)(hello))
                    second <- Abort.run[WhatsAppException](WhatsApp.run(client)(hello))
                    count  <- hits.get
                yield
                    assert(first.map(_ => ()) == Result.unit && second.map(_ => ()) == Result.unit)
                    assert(count == 2)
            }
        }
    }

    "the caller's kyo-http client and config do not reach a request: a closed caller client and a caller filter are not used" in {
        for
            seen <- AtomicInt.init(0)
            spy = new HttpFilter.Passthrough[Nothing]:
                def apply[In, Out, E2, S](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (S & Async & Abort[E2 | HttpResponse.Halt]) =
                    seen.incrementAndGet.andThen(next(request))
            route = HttpRoute.postRaw("v25.0" / phoneId.value / "messages").response(_.bodyText).handler(_ => HttpResponse.ok(sendOkBody))
            server  <- HttpServer.init(0, "localhost")(route)
            callers <- HttpClient.initUnscoped()
            _       <- callers.closeNow
            // The probe can fail: the same request through the caller's closed client does.
            direct <- Abort.run[HttpException](HttpClient.let(callers)(HttpClient.getText(s"http://localhost:${server.port}/")))
            base   <- Abort.get(HttpClientConfig.BaseUrl.init("http://127.0.0.1:1"))
            sent   <- HttpClient.let(callers) {
                HttpClient.withConfig(_.filter(spy).baseUrl(base).followRedirects(true)) {
                    Abort.run[WhatsAppException](WhatsApp.run(makeConfig(server.port))(WhatsApp.send(to, WhatsAppMessage.Text("hi"))))
                }
            }
            count <- seen.get
        yield
            assert(direct.isFailure || direct.isPanic, s"a request through the closed caller client did not fail: $direct")
            assert(sent == Result.succeed(sendOkResult), s"got: $sent")
            assert(count == 0, s"the caller's filter saw $count requests")
        end for
    }

    "a caller's connection opened under trustAll TLS is not reused: the module connects afresh and refuses the certificate" in {
        val trustAll = HttpTlsConfig(trustAll = true)
        val route = HttpRoute.postRaw("v25.0" / phoneId.value / "messages").response(_.bodyText).handler(_ => HttpResponse.ok(sendOkBody))
        for
            server <- HttpServer.init(
                HttpServerConfig.default.port(0).host("localhost").tls(internal.HttpTestPlatformBackend.serverTlsConfig)
            )(route)
            callers <- HttpClient.init(defaultTlsConfig = trustAll)
            config = configOf(token, phoneId, baseUrl = url(s"https://localhost:${server.port}"))
            direct <- HttpClient.let(callers) {
                HttpClient.withConfig(_.tls(trustAll))(HttpClient.postText(s"https://localhost:${server.port}$expectedPath", "{}"))
            }
            sent <- HttpClient.let(callers) {
                HttpClient.withConfig(_.tls(trustAll)) {
                    Abort.run[WhatsAppException](WhatsApp.run(config)(WhatsApp.send(to, WhatsAppMessage.Text("hi"))))
                }
            }
        yield
            assert(direct == sendOkBody, "the caller's own trustAll request reached the server")
            val failure = failureOf[WhatsAppTransportException](sent)
            assert(failure == WhatsAppTransportException("send", WhatsAppTransportException.Kind.Tls, "localhost", server.port, Absent)())
            assert(failure.cause.exists(_.isInstanceOf[kyo.net.NetTlsException]), s"cause: ${failure.cause}")
        end for
    }

    "send POSTs to the versioned messages endpoint with bearer" in {
        withSendServer(sendOkBody) { (port, captured) =>
            WhatsApp.run(makeConfig(port)) {
                WhatsApp.send(to, WhatsAppMessage.Text("hi")).map { _ =>
                    captured.take.map { case (path, auth, _) =>
                        assert(path == expectedPath)
                        assert(auth == s"Bearer $token")
                    }
                }
            }
        }
    }

    "a base url with a path keeps it before the version" in {
        Channel.init[String](1).map { paths =>
            val route = HttpRoute.postRaw("graph" / "v25.0" / phoneId.value / "messages").request(_.bodyBinary).response(_.bodyText)
                .handler(req => paths.put(req.path).andThen(HttpResponse.ok(sendOkBody)))
            HttpServer.init(0, "localhost")(route).map { s =>
                val config = configOf(token, phoneId, baseUrl = url(s"http://localhost:${s.port}/graph"))
                WhatsApp.run(config)(WhatsApp.send(to, WhatsAppMessage.Text("hi"))).map { result =>
                    paths.take.map { path =>
                        assert(path == s"/graph/v25.0/${phoneId.value}/messages")
                        assert(result == sendOkResult)
                    }
                }
            }
        }
    }

    "send decodes the response into a SendResult" in {
        withSendServer(sendOkBody) { (port, captured) =>
            WhatsApp.run(makeConfig(port)) {
                WhatsApp.send(to, WhatsAppMessage.Text("hi")).map { result =>
                    captured.take.map(_ => assert(result == sendOkResult))
                }
            }
        }
    }

    "send body equals the encoded WhatsAppMessage envelope" in {
        withSendServer(sendOkBody) { (port, captured) =>
            val img     = WhatsAppMessage.Image(WhatsAppMedia.Source.ById(WhatsAppId.MediaId("M")))
            val encoded = textOf(WhatsAppCodec.encodeSend(to, img, Absent))
            WhatsApp.run(makeConfig(port)) {
                WhatsApp.send(to, img).map { _ =>
                    captured.take.map { case (_, _, body) => assert(body == encoded) }
                }
            }
        }
    }

    "send with replyTo includes the context object" in {
        withSendServer(sendOkBody) { (port, captured) =>
            WhatsApp.run(makeConfig(port)) {
                WhatsApp.send(to, WhatsAppMessage.Text("re"), Present(WhatsAppId.MessageId("MSG_ID"))).map { _ =>
                    captured.take.map { case (_, _, body) =>
                        assert(body.contains(""""context":{"message_id":"MSG_ID"}"""))
                    }
                }
            }
        }
    }

    "a 4xx Graph error maps to the typed WhatsAppThroughputRateLimitException leaf" in {
        withSendServer(rateLimitErrorBody(130429), statusOk = false) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.run(makeConfig(port))(WhatsApp.send(to, WhatsAppMessage.Text("hi")))).map { result =>
                assert(result ==
                    Result.fail(WhatsAppThroughputRateLimitException("send", Absent, "Rate limit hit", Absent, Present("fb123"))))
            }
        }
    }

    "a 131047 error maps to WhatsAppWindowClosedException, keeping Meta's details" in {
        withSendServer(windowClosedBody, statusOk = false) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.run(makeConfig(port))(WhatsApp.send(to, WhatsAppMessage.Text("hi")))).map { result =>
                assert(result == Result.fail(WhatsAppWindowClosedException(
                    "send",
                    Absent,
                    "Re-engagement message",
                    Present("More than 24 hours have passed"),
                    Present("fb1")
                )))
            }
        }
    }

    "a Graph error that echoes the token has it redacted from the description and the details" in {
        val body =
            s"""{"error":{"code":190,"message":"token $token expired","error_data":{"details":"the token $token"},"fbtrace_id":"fb"}}"""
        withSendServer(body, statusOk = false) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.run(makeConfig(port))(WhatsApp.send(to, WhatsAppMessage.Text("hi")))).map { result =>
                assert(result == Result.fail(WhatsAppTokenExpiredException(
                    "send",
                    Absent,
                    "token <redacted> expired",
                    Present("the token <redacted>"),
                    Present("fb")
                )))
                val e = failureOf[WhatsAppException](result)
                BaseWhatsAppTest.renderings(e).foreach(text => assert(!text.contains(token), text))
            }
        }
    }

    def fullErrorBody(code: Int): String =
        s"""{"error":{"message":"(#$code) msg","type":"OAuthException","code":$code,"error_subcode":2494055,"error_data":{"messaging_product":"whatsapp","details":"details $code"},"fbtrace_id":"trace$code","error_user_title":"title","error_user_msg":"user msg"}}"""

    def sendAgainst(status: HttpStatus, body: String)(using Frame) =
        val handler = HttpRoute.postRaw("v25.0" / phoneId.value / "messages")
            .request(_.bodyBinary)
            .response(_.bodyText)
            .handler(_ => HttpResponse(status).addField("body", body))
        Scope.run {
            HttpServer.init(0, "localhost")(handler).map { s =>
                Abort.run[WhatsAppException](WhatsApp.run(makeConfig(s.port))(WhatsApp.send(to, WhatsAppMessage.Text("hi"))))
            }
        }
    end sendAgainst

    "every code a send can raise arrives from a real error response with Meta's fields; a template code arrives as the catch-all" in {
        val s                                                  = Present(2494055)
        def d                                                  = (code: Int) => s"(#$code) msg"
        def det                                                = (code: Int) => Present(s"details $code")
        def t                                                  = (code: Int) => Present(s"trace$code")
        def other(code: Int): WhatsAppException                = WhatsAppOtherApiException("send", code, s, d(code), det(code), t(code))
        val table: Chunk[(Int, HttpStatus, WhatsAppException)] = Chunk(
            (190, HttpStatus(401), WhatsAppTokenExpiredException("send", s, d(190), det(190), t(190))),
            (3, HttpStatus(403), WhatsAppAccessDeniedException("send", 3, s, d(3), det(3), t(3))),
            (250, HttpStatus(403), WhatsAppAccessDeniedException("send", 250, s, d(250), det(250), t(250))),
            (4, HttpStatus(400), WhatsAppAppRateLimitException("send", s, d(4), det(4), t(4))),
            (80007, HttpStatus(400), WhatsAppBusinessAccountRateLimitException("send", s, d(80007), det(80007), t(80007))),
            (130429, HttpStatus(400), WhatsAppThroughputRateLimitException("send", s, d(130429), det(130429), t(130429))),
            (131056, HttpStatus(400), WhatsAppRecipientPairRateLimitException("send", s, d(131056), det(131056), t(131056))),
            (131026, HttpStatus(400), WhatsAppUndeliverableException("send", s, d(131026), det(131026), t(131026))),
            (131021, HttpStatus(400), WhatsAppSenderIsRecipientException("send", s, d(131021), det(131021), t(131021))),
            (131047, HttpStatus(400), WhatsAppWindowClosedException("send", s, d(131047), det(131047), t(131047))),
            (131053, HttpStatus(400), WhatsAppMediaUploadException("send", s, d(131053), det(131053), t(131053))),
            (100, HttpStatus(400), WhatsAppInvalidParameterException("send", 100, s, d(100), det(100), t(100))),
            (131000, HttpStatus(500), WhatsAppServiceUnavailableException("send", 131000, s, d(131000), det(131000), t(131000))),
            (131016, HttpStatus(503), WhatsAppServiceUnavailableException("send", 131016, s, d(131016), det(131016), t(131016))),
            (132000, HttpStatus(400), other(132000)),
            (132001, HttpStatus(404), other(132001)),
            (132005, HttpStatus(400), other(132005)),
            (132007, HttpStatus(400), other(132007)),
            (132012, HttpStatus(400), other(132012)),
            (132015, HttpStatus(400), other(132015)),
            (131042, HttpStatus(400), other(131042))
        )
        Kyo.foreach(table) { case (code, status, leaf) =>
            sendAgainst(status, fullErrorBody(code)).map(result => (result, Result.fail(leaf)))
        }.map { pairs =>
            pairs.foreach { case (actual, expected) => assert(actual == expected) }
            succeed
        }
    }

    // Built apart from the call: a leaf's development-mode message renders the source lines around its frame.
    val proxyPage = Seq("Bad", "Gateway", "from", "proxy").mkString(" ")

    "a non-2xx response that is not a Graph error is WhatsAppUnexpectedStatusException, keeping no body text" in {
        Kyo.foreach(Chunk(proxyPage, "", "y" * 600))(body => sendAgainst(HttpStatus(502), body)).map { results =>
            assert(results.forall(_ == Result.fail(WhatsAppUnexpectedStatusException("send", HttpStatus(502)))), s"got: $results")
            results.flatMap(_.failure.toList).foreach(e => assert(!e.getMessage.contains(proxyPage)))
        }
    }

    "a 413 or 431 from the peer is WhatsAppUnexpectedStatusException with that status" in {
        val statuses = Chunk(HttpStatus(413), HttpStatus(431))
        Kyo.foreach(statuses)(status => sendAgainst(status, "")).map { results =>
            assert(results == statuses.map(s => Result.fail(WhatsAppUnexpectedStatusException("send", s))), s"got: $results")
        }
    }

    "an answer larger than maxResponseLength is WhatsAppTransportException of kind PayloadTooLarge, naming both sizes" in {
        val handler = HttpRoute.postRaw("v25.0" / phoneId.value / "messages")
            .response(_.bodyText)
            .handler(_ => HttpResponse.ok("x" * 4096))
        HttpServer.init(0, "localhost")(handler).map { server =>
            val config = configOf(token, phoneId, baseUrl = localUrl(server.port), maxResponseLength = 1024.bytes)
            Abort.run[WhatsAppException](WhatsApp.run(config)(WhatsApp.send(to, WhatsAppMessage.Text("hi")))).map { result =>
                assert(result == Result.fail(WhatsAppTransportException(
                    "send",
                    WhatsAppTransportException.Kind.PayloadTooLarge(4096.bytes, 1024.bytes),
                    "localhost",
                    server.port,
                    Absent
                )()))
            }
        }
    }

    "sendTemplate POSTs the template envelope and decodes SendResult" in {
        withSendServer(sendOkBody) { (port, captured) =>
            WhatsApp.run(makeConfig(port)) {
                WhatsApp.sendTemplate(to, WhatsAppTemplate("hello_world", "en_US")).map { result =>
                    captured.take.map { case (path, _, body) =>
                        assert(path == expectedPath)
                        assert(body.contains(""""type":"template""""))
                        assert(body.contains(""""template":"""))
                        assert(result == sendOkResult)
                    }
                }
            }
        }
    }

    "sendTemplate surfaces WhatsAppTemplateNotFoundException on 132001" in {
        withSendServer(templateErrorBody, statusOk = false) { (port, _) =>
            Abort.run[WhatsAppException](
                WhatsApp.run(makeConfig(port))(WhatsApp.sendTemplate(to, WhatsAppTemplate("hello_world", "en_US")))
            ).map { result =>
                assert(result == Result.fail(
                    WhatsAppTemplateNotFoundException("sendTemplate", Absent, "Template does not exist", Absent, Present("fb2"))
                ))
            }
        }
    }

    "markRead POSTs the status:read body and consumes {success:true}" in {
        withSendServer(successBody) { (port, captured) =>
            WhatsApp.run(makeConfig(port)) {
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

    "markReadWithTyping adds the typing_indicator object" in {
        withSendServer(successBody) { (port, captured) =>
            WhatsApp.run(makeConfig(port)) {
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

    "markRead maps a Graph error to WhatsAppInvalidParameterException" in {
        withSendServer(invalidParamBody(100), statusOk = false) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.run(makeConfig(port))(WhatsApp.markRead(WhatsAppId.MessageId("wamid.IN")))).map {
                result =>
                    assert(result ==
                        Result.fail(WhatsAppInvalidParameterException("markRead", 100, Absent, "Invalid param", Absent, Present("fb3"))))
            }
        }
    }

    // Port 1 is privileged and unused, so the connection is refused. A port freed by a closed test server is not: the suites run in
    // parallel, and another server can take the port or the closing socket can still accept, which turns a refusal into a closed
    // connection.
    val refusedPort = 1

    "a connection failure is WhatsAppTransportException of kind Connect, with kyo-net's cause" in {
        Abort.run[WhatsAppException](WhatsApp.run(makeConfig(refusedPort))(WhatsApp.send(to, WhatsAppMessage.Text("hi")))).map { result =>
            val t = failureOf[WhatsAppTransportException](result)
            assert(result == Result.fail(
                WhatsAppTransportException("send", WhatsAppTransportException.Kind.Connect, "localhost", refusedPort, Absent)(t.cause)
            ))
            assert(t.cause.nonEmpty, "a refused connection keeps kyo-net's failure as the cause")
            assert(t.getCause eq t.cause.getOrElse(null))
        }
    }

    "a panic passes through the transport unchanged, whatever its type or message" in {
        val defects = Chunk(
            new IllegalStateException("defect"),
            new java.io.IOException("disk full"),
            new java.io.EOFException("connection closed"),
            new java.io.IOException("connection closed"),
            new java.io.IOException("connection closed") with scala.util.control.NoStackTrace
        )
        Scope.run {
            WhatsApp.init(makeConfig(refusedPort)).map { client =>
                Kyo.foreach(defects) { defect =>
                    Abort.run[WhatsAppException](Graph.transport(client, "send", localUrl(refusedPort))(_ => Abort.panic(defect)))
                }
            }
        }.map { results =>
            assert(results.map(_.map(_ => ())) == defects.map(d => Result.panic(d)))
        }
    }

    /** Sends against a raw listener that reads the request, writes `reply` (possibly nothing), and closes the connection. */
    def sendAgainstClosingServer(reply: String)(using Frame) =
        // Unsafe: kyo-http's HttpServer always completes a response, so a raw transport listener is the only way to close the socket
        // before the response or while the declared body is still owed.
        Sync.Unsafe.defer {
            val accepted  = Promise.Unsafe.init[kyo.net.Connection, Any]()
            val listening = kyo.net.NetPlatform.transport.listen("localhost", 0, 16)(conn => accepted.completeDiscard(Result.succeed(conn)))
            val peer      = accepted.safe.get.map { conn =>
                Abort.run[Closed](
                    conn.inbound.safe.take.andThen(
                        if reply.isEmpty then Kyo.unit else conn.outbound.safe.put(utf8(reply))
                    )
                ).andThen(Sync.Unsafe.defer(conn.close()))
            }
            listening.safe.get.map { listener =>
                Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen(Fiber.init(peer)).andThen {
                    Abort.run[WhatsAppException](WhatsApp.run(makeConfig(listener.port))(WhatsApp.send(to, WhatsAppMessage.Text("hi"))))
                        .map(result => (listener.port, result))
                }
            }
        }
    end sendAgainstClosingServer

    "a connection the server closes mid-response is WhatsAppTransportException of kind ConnectionClosed" in {
        sendAgainstClosingServer(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"messaging_product\":"
        ).map { (port, result) =>
            assert(result == Result.fail(
                WhatsAppTransportException("send", WhatsAppTransportException.Kind.ConnectionClosed, "localhost", port, Absent)()
            ))
        }
    }

    "a chunked body with a bad size line is WhatsAppTransportException of kind Protocol" in {
        sendAgainstClosingServer(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n{}\r\n0\r\n\r\n"
        ).map { (port, result) =>
            assert(
                result == Result.fail(
                    WhatsAppTransportException("send", WhatsAppTransportException.Kind.Protocol, "localhost", port, Absent)()
                ),
                s"got: $result"
            )
        }
    }

    "a connection the server closes before any response is WhatsAppTransportException of kind NoResponseHead" in {
        sendAgainstClosingServer("").map { (port, result) =>
            assert(result == Result.fail(
                WhatsAppTransportException("send", WhatsAppTransportException.Kind.NoResponseHead, "localhost", port, Absent)()
            ))
        }
    }

    "a status code outside 100 to 599 is WhatsAppTransportException of kind Protocol" in {
        sendAgainstClosingServer("HTTP/1.1 999 Odd\r\nContent-Length: 0\r\n\r\n").map { (port, result) =>
            assert(result == Result.fail(
                WhatsAppTransportException("send", WhatsAppTransportException.Kind.Protocol, "localhost", port, Absent)()
            ))
        }
    }

    "a response head larger than kyo-http's header limit is WhatsAppTransportException of kind Protocol" in {
        sendAgainstClosingServer(
            s"HTTP/1.1 200 OK\r\nX-Pad: ${"a" * (HttpTransportConfig.default.maxHeaderSize.toBytes.toInt + 1)}\r\n\r\n"
        ).map {
            (port, result) =>
                assert(result == Result.fail(
                    WhatsAppTransportException("send", WhatsAppTransportException.Kind.Protocol, "localhost", port, Absent)()
                ))
        }
    }

    "send of empty Contacts posts contacts:[] and surfaces WhatsAppInvalidParameterException" in {
        withSendServer(invalidParamBody(131009), statusOk = false) { (port, captured) =>
            Abort.run[WhatsAppException](WhatsApp.run(makeConfig(port))(WhatsApp.send(to, WhatsAppMessage.Contacts(Chunk.empty)))).map {
                result =>
                    captured.take.map { case (_, _, body) =>
                        assert(body.contains(""""contacts":[]"""))
                        assert(result == Result.fail(
                            WhatsAppInvalidParameterException("send", 131009, Absent, "Invalid param", Absent, Present("fb3"))
                        ))
                    }
            }
        }
    }

    "send of a reaction-remove posts emoji:\"\" and succeeds" in {
        withSendServer(sendOkBody) { (port, captured) =>
            WhatsApp.run(makeConfig(port)) {
                WhatsApp.send(to, WhatsAppMessage.Reaction(WhatsAppId.MessageId("wamid.T"), "")).map { result =>
                    captured.take.map { case (_, _, body) =>
                        assert(body.contains(""""message_id":"wamid.T""""))
                        assert(body.contains(""""emoji":"""""))
                        assert(result == sendOkResult)
                    }
                }
            }
        }
    }

    "a structurally-broken 200 response surfaces WhatsAppDecodeException" in {
        sendAgainst(HttpStatus(200), """{"messaging_product":}""").map { result =>
            assert(result == Result.fail(WhatsAppDecodeException(
                "send",
                WhatsAppDecodeException.Part.Response,
                WhatsAppDecodeException.Failure.Parse,
                Chunk.empty,
                Present(21)
            )))
        }
    }

    def failureAt(method: String, failure: WhatsAppDecodeException.Failure, path: String*)(using Frame): WhatsAppDecodeException =
        WhatsAppDecodeException(method, WhatsAppDecodeException.Part.Response, failure, Chunk.from(path), Absent)

    "a 200 send response with an empty messages[] is refused by the answer's constructor" in {
        sendAgainst(HttpStatus(200), """{"messaging_product":"whatsapp","messages":[]}""").map { result =>
            assert(result == Result.fail(failureAt("send", WhatsAppDecodeException.Failure.ConstructorRejected)))
        }
    }

    "a 200 sendTemplate response with an empty messages[] is refused by the answer's constructor, naming sendTemplate" in {
        withSendServer("""{"messaging_product":"whatsapp","messages":[]}""") { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.run(makeConfig(port))(WhatsApp.sendTemplate(
                to,
                WhatsAppTemplate("hello_world", "en_US")
            )))
                .map(result =>
                    assert(result == Result.fail(failureAt("sendTemplate", WhatsAppDecodeException.Failure.ConstructorRejected)))
                )
        }
    }

    "a 200 send response whose message has no id is a MissingField decode failure at that message" in {
        sendAgainst(HttpStatus(200), """{"messaging_product":"whatsapp","messages":[{"message_status":"accepted"}]}""").map { result =>
            assert(result == Result.fail(failureAt("send", WhatsAppDecodeException.Failure.MissingField, "messages", "0")))
        }
    }

    "a markRead acknowledgement with success false is a decode failure at success" in {
        withSendServer("""{"success":false}""") { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.run(makeConfig(port))(WhatsApp.markRead(WhatsAppId.MessageId("wamid.IN"))))
                .map(result =>
                    assert(result == Result.fail(failureAt("markRead", WhatsAppDecodeException.Failure.ConstructorRejected, "success")))
                )
        }
    }

    "a markReadWithTyping acknowledgement with success false is a decode failure of markRead at success" in {
        withSendServer("""{"success":false}""") { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.run(makeConfig(port))(WhatsApp.markReadWithTyping(WhatsAppId.MessageId("wamid.IN"))))
                .map(result =>
                    assert(result == Result.fail(failureAt("markRead", WhatsAppDecodeException.Failure.ConstructorRejected, "success")))
                )
        }
    }

    "send posts Content-Type application/json header" in {
        Channel.init[String](1).map { ctCapture =>
            val route = HttpRoute.postRaw("v25.0" / phoneId.value / "messages")
                .request(_.bodyBinary)
                .response(_.bodyText)
                .handler(req => ctCapture.put(req.headers.get("Content-Type").getOrElse("")).map(_ => HttpResponse.ok(sendOkBody)))
            HttpServer.init(0, "localhost")(route).map { s =>
                WhatsApp.run(makeConfig(s.port)) {
                    WhatsApp.send(to, WhatsAppMessage.Text("hi")).map { _ =>
                        ctCapture.take.map(ct => assert(ct == "application/json"))
                    }
                }
            }
        }
    }

end WhatsAppTest
