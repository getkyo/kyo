package kyo

class WhatsAppCustomTest extends BaseWhatsAppTest:

    import WhatsAppCustomTest.*

    val token   = "CUSTOM_TOKEN"
    val phoneId = WhatsAppId.PhoneNumberId("999000111")

    def makeConfig(port: Int)(using Frame): WhatsAppConfig =
        WhatsAppConfig(WhatsAppToken(token), phoneId, baseUrl = HttpUrl.parse(s"http://localhost:$port").getOrThrow)

    def decodeFailure(failure: WhatsAppDecodeException.Failure, path: Chunk[String] = Chunk.empty)(using
        Frame
    ): Result[WhatsAppException, Nothing] =
        Result.fail(WhatsAppDecodeException("custom", WhatsAppDecodeException.Part.Response, failure, path, Absent))

    /** Serves `body` with `status` on every path, and hands `test` the port and each request's method, path, query, auth and body. */
    def withServer[A, S](body: String, status: HttpStatus = HttpStatus(200))(
        test: (Int, Channel[Captured]) => A < S
    )(using Frame): A < (S & Async & Scope & Abort[HttpBindException]) =
        Channel.init[Captured](4).map { captured =>
            def record(req: HttpRequest[?], sent: String) =
                captured.put(Captured(
                    req.method.name,
                    req.path,
                    req.url.rawQuery.getOrElse(""),
                    req.headers.get("Authorization").getOrElse(""),
                    sent
                )).andThen(HttpResponse(status).addField("body", body))
            val rest     = HttpPath.Capture.Rest("rest")
            val handlers = Seq(
                HttpRoute.getRaw(rest).response(_.bodyText).handler(req => record(req, "")),
                HttpRoute.deleteRaw(rest).response(_.bodyText).handler(req => record(req, "")),
                HttpRoute.postRaw(rest).request(_.bodyText).response(_.bodyText).handler(req => record(req, req.fields.body)),
                HttpRoute.putRaw(rest).request(_.bodyText).response(_.bodyText).handler(req => record(req, req.fields.body)),
                HttpRoute.patchRaw(rest).request(_.bodyText).response(_.bodyText).handler(req => record(req, req.fields.body))
            )
            HttpServer.init(0, "localhost")(handlers*).map(s => test(s.port, captured))
        }

    "custom GET calls {baseUrl}/{apiVersion}/{path} with the bearer and no body, and decodes the answer" in {
        withServer("""{"value":"templates"}""") { (port, captured) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsApp.custom[Unit, SomeDto](HttpMethod.GET, WhatsAppPath("123/message_templates")).map { result =>
                    captured.take.map { c =>
                        assert(c == Captured("GET", "/v25.0/123/message_templates", "", s"Bearer $token", ""))
                        assert(result == SomeDto("templates"))
                    }
                }
            }
        }
    }

    "custom POST sends the body as JSON" in {
        withServer("""{"result":"ok"}""") { (port, captured) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsApp.custom[InDto, OutDto](HttpMethod.POST, WhatsAppPath("some/endpoint"), body = Present(InDto("hello"))).map {
                    result =>
                        captured.take.map { c =>
                            assert(c == Captured("POST", "/v25.0/some/endpoint", "", s"Bearer $token", """{"name":"hello"}"""))
                            assert(result == OutDto("ok"))
                        }
                }
            }
        }
    }

    "custom POST without a body sends the JSON encoding of ()" in {
        withServer("""{"result":"empty-ok"}""") { (port, captured) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsApp.custom[Unit, OutDto](HttpMethod.POST, WhatsAppPath("no/body")).map { result =>
                    captured.take.map { c =>
                        assert(c.body == Json.encode(()))
                        assert(result == OutDto("empty-ok"))
                    }
                }
            }
        }
    }

    "custom DELETE, PUT and PATCH issue their method" in {
        withServer("""{"result":"r"}""") { (port, captured) =>
            WhatsApp.let(makeConfig(port)) {
                for
                    _ <- WhatsApp.custom[Unit, OutDto](HttpMethod.DELETE, WhatsAppPath("r/1"))
                    d <- captured.take
                    _ <- WhatsApp.custom[InDto, OutDto](HttpMethod.PUT, WhatsAppPath("r/1"), body = Present(InDto("n")))
                    p <- captured.take
                    _ <- WhatsApp.custom[InDto, OutDto](HttpMethod.PATCH, WhatsAppPath("r/1"), body = Present(InDto("m")))
                    q <- captured.take
                yield
                    assert(d == Captured("DELETE", "/v25.0/r/1", "", s"Bearer $token", ""))
                    assert(p == Captured("PUT", "/v25.0/r/1", "", s"Bearer $token", """{"name":"n"}"""))
                    assert(q == Captured("PATCH", "/v25.0/r/1", "", s"Bearer $token", """{"name":"m"}"""))
            }
        }
    }

    /** Runs `call` against a raw listener that reads one request head, answers 204 and closes, and returns what it read. kyo-http's
      * router has no route for TRACE or CONNECT, so the request line is read off the socket.
      */
    def requestHeadOf(call: Int => Any < (Async & Abort[WhatsAppException]))(using
        Frame
    ): String < (Async & Scope & Abort[kyo.net.NetException]) =
        // Unsafe: the listener and its accepted connection are kyo-net's unsafe tier, bridged here for a test peer.
        import AllowUnsafe.embrace.danger
        def readHead(conn: kyo.net.Connection, read: String): String < (Async & Abort[Closed]) =
            if read.contains("\r\n\r\n") then read
            else conn.inbound.safe.take.map(bytes => readHead(conn, read + new String(bytes.toArray, "UTF-8")))
        val accepted  = Promise.Unsafe.init[kyo.net.Connection, Any]()
        val listening = kyo.net.NetPlatform.transport.listen("localhost", 0, 16)(conn => accepted.completeDiscard(Result.succeed(conn)))
        listening.safe.get.map { listener =>
            Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen {
                val peer = accepted.safe.get.map { conn =>
                    Abort.run[Closed](readHead(conn, "").map { head =>
                        conn.outbound.safe.put(Span.from("HTTP/1.1 204 No Content\r\n\r\n".getBytes("UTF-8"))).andThen(head)
                    }).map(head => Sync.Unsafe.defer(conn.close()).andThen(head.getOrElse("")))
                }
                Fiber.init(peer).map(fiber => Abort.run[WhatsAppException](call(listener.port)).andThen(fiber.get))
            }
        }
    end requestHeadOf

    "custom sends GET, DELETE, HEAD, OPTIONS, TRACE and CONNECT as themselves, with no body" in {
        val methods = Chunk(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE, HttpMethod.CONNECT)
        Kyo.foreach(methods) { method =>
            requestHeadOf(port => WhatsApp.let(makeConfig(port))(WhatsApp.custom[InDto, OutDto](method, WhatsAppPath("r/1"))))
        }.map { heads =>
            def shape(head: String) =
                val (lines, rest) = head.split("\r\n\r\n", 2) match
                    case Array(h, r) => (h.split("\r\n").toList, r)
                    case other       => (other.head.split("\r\n").toList, "")
                val fields = lines.drop(1).map(_.toLowerCase)
                val body   = fields.exists(f =>
                    f.startsWith("content-type:") || f.startsWith("transfer-encoding:") ||
                        (f.startsWith("content-length:") && f.drop("content-length:".length).trim != "0")
                )
                (lines.head, body, rest)
            end shape
            assert(heads.map(shape) == methods.map(m => (s"${m.name} /v25.0/r/1 HTTP/1.1", false, "")), heads.toString)
        }
    }

    "custom percent-encodes every query name and value outside the unreserved set" in {
        withServer("""{"value":"v"}""") { (port, captured) =>
            WhatsApp.let(makeConfig(port)) {
                WhatsApp.custom[Unit, SomeDto](
                    HttpMethod.GET,
                    WhatsAppPath("123/message_templates"),
                    query = Seq("fields" -> "name,status", "a&b" -> "c=d e", "q" -> "é#?/~-._")
                ).andThen(captured.take).map { c =>
                    assert(c.path == "/v25.0/123/message_templates")
                    assert(c.query == "fields=name%2Cstatus&a%26b=c%3Dd%20e&q=%C3%A9%23%3F%2F~-._")
                }
            }
        }
    }

    "custom maps a Graph error that means the same on every endpoint to its leaf" in {
        withServer("""{"error":{"code":190,"type":"OAuthException","message":"Token expired","fbtrace_id":"fb4"}}""", HttpStatus(401)) {
            (port, _) =>
                Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsApp.custom[Unit, SomeDto](
                    HttpMethod.GET,
                    WhatsAppPath("x")
                )))
                    .map { result =>
                        assert(result ==
                            Result.fail(WhatsAppTokenExpiredException("custom", Absent, "Token expired", Absent, Present("fb4"))))
                    }
        }
    }

    "custom answers an endpoint-specific Graph code as WhatsAppOtherApiException" in {
        withServer("""{"error":{"code":131047,"message":"Re-engagement message","fbtrace_id":"fb5"}}""", HttpStatus(400)) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsApp.custom[Unit, SomeDto](HttpMethod.GET, WhatsAppPath("x"))))
                .map { result =>
                    assert(result ==
                        Result.fail(WhatsAppOtherApiException("custom", 131047, Absent, "Re-engagement message", Absent, Present("fb5"))))
                }
        }
    }

    "custom reports an answer it cannot decode as a decode failure, not transport" in {
        withServer("""{"other":"x"}""") { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsApp.custom[Unit, SomeDto](
                HttpMethod.GET,
                WhatsAppPath("shape")
            )))
                .map(result => assert(result == decodeFailure(WhatsAppDecodeException.Failure.MissingField)))
        }
    }

    "custom reports a sealed-trait answer with an unknown variant as UnknownVariant" in {
        withServer("""{"Triangle":{}}""") { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsApp.custom[Unit, Shape](
                HttpMethod.GET,
                WhatsAppPath("shape")
            )))
                .map(result => assert(result == decodeFailure(WhatsAppDecodeException.Failure.UnknownVariant)))
        }
    }

    "custom reports an answer nested past kyo-schema's depth limit as LimitExceeded" in {
        withServer("""{"next":""" * 512 + "{}" + "}" * 512) { (port, _) =>
            Abort.run[WhatsAppException](WhatsApp.let(makeConfig(port))(WhatsApp.custom[Unit, Node](HttpMethod.GET, WhatsAppPath("deep"))))
                .map(result => assert(result == decodeFailure(WhatsAppDecodeException.Failure.LimitExceeded)))
        }
    }

end WhatsAppCustomTest

object WhatsAppCustomTest:
    final case class Captured(method: String, path: String, query: String, auth: String, body: String) derives CanEqual
    final case class SomeDto(value: String) derives Schema, CanEqual
    final case class InDto(name: String) derives Schema, CanEqual
    final case class OutDto(result: String) derives Schema, CanEqual
    sealed trait Shape derives Schema, CanEqual
    final case class Circle(radius: Int) extends Shape derives CanEqual
    final case class Node(next: Maybe[Node] = Absent) derives Schema, CanEqual
end WhatsAppCustomTest
