package kyo.internal

import kyo.*
import kyo.internal.server.*

class RouteUtilTest extends kyo.BaseHttpTest:

    import HttpPath./

    given CanEqual[Any, Any] = CanEqual.derived

    private val maxPartSize = readBufferCapacity(HttpServerConfig.default.maxMultipartPartSize)

    case class User(name: String, age: Int) derives Schema, CanEqual
    case class LoginForm(username: String, password: String) derives HttpFormCodec

    final private class FixedUUIDGenerator(value: UUID) extends UUIDGenerator:
        var calls = 0

        def v4(using Frame): UUID < Sync =
            Sync.defer {
                calls += 1
                value
            }

        def v7(using Frame): UUID < Sync =
            Sync.defer(value)
    end FixedUUIDGenerator

    // ==================== Route inspection ====================

    "isStreamingRequest" - {
        "false for no body" in {
            val route = HttpRoute.getRaw("users")
            assert(!RouteUtil.isStreamingRequest(route))
        }
        "false for json body" in {
            val route = HttpRoute.postRaw("users").request(_.bodyJson[User])
            assert(!RouteUtil.isStreamingRequest(route))
        }
        "false for text body" in {
            val route = HttpRoute.postRaw("echo").request(_.bodyText)
            assert(!RouteUtil.isStreamingRequest(route))
        }
        "true for byte stream body" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyStream)
            assert(RouteUtil.isStreamingRequest(route))
        }
        "true for ndjson body" in {
            val route = HttpRoute.postRaw("events").request(_.bodyNdjson[User])
            assert(RouteUtil.isStreamingRequest(route))
        }
    }

    "isStreamingResponse" - {
        "false for no body" in {
            val route = HttpRoute.getRaw("health")
            assert(!RouteUtil.isStreamingResponse(route))
        }
        "false for json body" in {
            val route = HttpRoute.getRaw("users").response(_.bodyJson[User])
            assert(!RouteUtil.isStreamingResponse(route))
        }
        "true for byte stream body" in {
            val route = HttpRoute.getRaw("download").response(_.bodyStream)
            assert(RouteUtil.isStreamingResponse(route))
        }
        "true for sse body" in {
            val route = HttpRoute.getRaw("events").response(_.bodySseJson[User])
            assert(RouteUtil.isStreamingResponse(route))
        }
    }

    // ==================== encodeRequest ====================

    "encodeRequest" - {
        "buffered multipart boundary is generated inside the scoped effect" in {
            val uuid      = UUID.parse("00112233-4455-4677-a899-aabbccddeeff").getOrThrow
            val generator = new FixedUUIDGenerator(uuid)
            val route     = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            val parts     = Seq(
                HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8")))
            )
            val request = HttpRequest.postRaw(HttpUrl.parse("http://localhost/upload").getOrThrow)
                .addField("body", parts)

            var callbackInvoked                                      = false
            var headers                                              = HttpHeaders.empty
            var body                                                 = Span.empty[Byte]
            val encoding: Unit < (Sync & Abort[HttpCookieException]) =
                RouteUtil.encodeRequest(route, request)(
                    onEmpty = (_, _) => fail("expected buffered"),
                    onBuffered = (_, actualHeaders, actualBody) =>
                        callbackInvoked = true
                        headers = actualHeaders
                        body =
                            actualBody
                    ,
                    onStreaming = (_, _, _) => fail("expected buffered")
                )

            assert(!callbackInvoked)
            assert(generator.calls == 0)

            UUID.let(generator)(encoding).map { _ =>
                val boundary = uuid.show
                assert(callbackInvoked)
                assert(generator.calls == 1)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=$boundary"))
                assert(new String(body.toArrayUnsafe, "UTF-8").contains(s"--$boundary\r\n"))
            }
        }

        "streaming multipart boundary is generated inside the scoped effect" in {
            val uuid      = UUID.parse("ffeeddcc-bbaa-4988-b766-554433221100").getOrThrow
            val generator = new FixedUUIDGenerator(uuid)
            val route     = HttpRoute.postRaw("upload").request(_.bodyMultipartStream)
            val parts: Stream[HttpRequest.Part, Async & Abort[HttpException]] = Stream.init(Seq(
                HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8")))
            ))
            val request = HttpRequest.postRaw(HttpUrl.parse("http://localhost/upload").getOrThrow)
                .addField("body", parts)

            var callbackInvoked                                        = false
            var headers                                                = HttpHeaders.empty
            var body: Stream[Span[Byte], Async & Abort[HttpException]] = Stream.empty
            val encoding: Unit < (Sync & Abort[HttpCookieException])   =
                RouteUtil.encodeRequest(route, request)(
                    onEmpty = (_, _) => fail("expected streaming"),
                    onBuffered = (_, _, _) => fail("expected streaming"),
                    onStreaming = (_, actualHeaders, actualBody) =>
                        callbackInvoked = true
                        headers = actualHeaders
                        body = actualBody
                )

            assert(!callbackInvoked)
            assert(generator.calls == 0)

            UUID.let(generator)(encoding).map { _ =>
                assert(callbackInvoked)
                assert(generator.calls == 1)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=${uuid.show}"))
                body.run.map { chunks =>
                    val encoded = chunks.toSeq.map(span => new String(span.toArrayUnsafe, "UTF-8")).mkString
                    assert(encoded.contains(s"--${uuid.show}\r\n"))
                    assert(encoded.endsWith(s"--${uuid.show}--\r\n"))
                }
            }
        }

        "buffered multipart uses the boundary supplied in the request Content-Type" in {
            val suppliedBoundary = "caller:request-boundary"
            val generated        =
                UUID.parse("00112233-4455-4677-a899-aabbccddeeff").getOrThrow
            val generator = new FixedUUIDGenerator(generated)
            val route     = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            val request   = HttpRequest
                .postRaw(HttpUrl.parse("http://localhost/upload").getOrThrow)
                .setHeader("Content-Type", s"multipart/form-data; boundary=\"$suppliedBoundary\"")
                .addField(
                    "body",
                    Seq(HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8"))))
                )

            var headers = HttpHeaders.empty
            var body    = Span.empty[Byte]
            UUID.let(generator) {
                RouteUtil.encodeRequest(route, request)(
                    onEmpty = (_, _) => fail("expected buffered"),
                    onBuffered = (_, actualHeaders, actualBody) =>
                        headers = actualHeaders
                        body = actualBody
                    ,
                    onStreaming = (_, _, _) => fail("expected buffered")
                )
            }.map { _ =>
                val encoded = new String(body.toArrayUnsafe, "UTF-8")
                assert(generator.calls == 0)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=\"$suppliedBoundary\""))
                assert(encoded.contains(s"--$suppliedBoundary\r\n"))
                assert(!encoded.contains(generated.show))
            }
        }

        "buffered multipart replaces an invalid quoted request boundary without mismatching the body" in {
            val uuid      = UUID.parse("00112233-4455-4677-a899-aabbccddeeff").getOrThrow
            val generator = new FixedUUIDGenerator(uuid)
            val route     = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            val request   = HttpRequest
                .postRaw(HttpUrl.parse("http://localhost/upload").getOrThrow)
                .setHeader("Content-Type", "multipart/form-data; boundary=\"abc;def\"")
                .addField(
                    "body",
                    Seq(HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8"))))
                )

            var headers = HttpHeaders.empty
            var body    = Span.empty[Byte]
            UUID.let(generator) {
                RouteUtil.encodeRequest(route, request)(
                    onEmpty = (_, _) => fail("expected buffered"),
                    onBuffered = (_, actualHeaders, actualBody) =>
                        headers = actualHeaders
                        body = actualBody
                    ,
                    onStreaming = (_, _, _) => fail("expected buffered")
                )
            }.map { _ =>
                val boundary = uuid.show
                val encoded  = new String(body.toArrayUnsafe, "UTF-8")
                assert(generator.calls == 1)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=$boundary"))
                assert(encoded.contains(s"--$boundary\r\n"))
                assert(!encoded.contains("--abc"))
            }
        }

        "buffered multipart replaces an unquoted request boundary containing a MIME tspecial" in {
            val uuid      = UUID.parse("00112233-4455-4677-a899-aabbccddeeff").getOrThrow
            val generator = new FixedUUIDGenerator(uuid)
            val route     = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            val request   = HttpRequest
                .postRaw(HttpUrl.parse("http://localhost/upload").getOrThrow)
                .setHeader("Content-Type", "multipart/form-data; boundary=abc:def")
                .addField(
                    "body",
                    Seq(HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8"))))
                )

            var headers = HttpHeaders.empty
            var body    = Span.empty[Byte]
            UUID.let(generator) {
                RouteUtil.encodeRequest(route, request)(
                    onEmpty = (_, _) => fail("expected buffered"),
                    onBuffered = (_, actualHeaders, actualBody) =>
                        headers = actualHeaders
                        body = actualBody
                    ,
                    onStreaming = (_, _, _) => fail("expected buffered")
                )
            }.map { _ =>
                val boundary = uuid.show
                val encoded  = new String(body.toArrayUnsafe, "UTF-8")
                assert(generator.calls == 1)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=$boundary"))
                assert(encoded.contains(s"--$boundary\r\n"))
                assert(!encoded.contains("--abc:def"))
            }
        }

        "empty body" in {
            val route   = HttpRoute.getRaw("users")
            val request = HttpRequest.getRaw(HttpUrl.parse("http://localhost/users").getOrThrow)

            var result: (String, HttpHeaders) = null
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (url, headers) => result = (url, headers),
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(result._1 == "/users")
        }

        "json body" in {
            val route   = HttpRoute.postRaw("users").request(_.bodyJson[User])
            val request = HttpRequest.postRaw(HttpUrl.parse("http://localhost/users").getOrThrow)
                .addField("body", User("Alice", 30))

            var headers = HttpHeaders.empty
            var body    = Span.empty[Byte]
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (url, hdrs, bytes) =>
                    headers = hdrs
                    body = bytes
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
            assert(headers.get("Content-Type").contains("application/json"))
            val bodyStr = new String(body.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
            assert(bodyStr.contains("Alice"))
            assert(bodyStr.contains("30"))
        }

        "text body" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText)
            val request = HttpRequest.postRaw(HttpUrl.parse("http://localhost/echo").getOrThrow)
                .addField("body", "hello world")

            var headers = HttpHeaders.empty
            var bodyStr = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (url, hdrs, bytes) =>
                    headers = hdrs
                    bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
            assert(headers.get("Content-Type").contains("text/plain; charset=utf-8"))
            assert(bodyStr == "hello world")
        }

        "path captures" in {
            val route   = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId") / "posts")
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users/42/posts").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("userId", 42)

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(url == "/users/42/posts")
        }

        "query params" in {
            val route   = HttpRoute.getRaw("users").request(_.query[Int]("page").query[String]("sort"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("page", 2).addField("sort", "name")

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(url.contains("page=2"))
            assert(url.contains("sort=name"))
        }

        "header params" in {
            val route   = HttpRoute.getRaw("data").request(_.header[String]("apiKey", wireName = "X-Api-Key"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("apiKey", "secret123")

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(headers.get("X-Api-Key") == Present("secret123"))
        }

        "cookie params" in {
            val route   = HttpRoute.getRaw("data").request(_.cookie[String]("session"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("session", "abc123")

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(headers.get("Cookie") == Present("session=abc123"))
        }

        "a cookie param carrying ';' fails with HttpCookieException instead of writing another cookie" in {
            val route   = HttpRoute.getRaw("data").request(_.cookie[String]("session"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("session", "abc; admin=1")

            Abort.run[HttpCookieException](RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected the cookie to be refused"),
                onBuffered = (_, _, _) => fail("expected the cookie to be refused"),
                onStreaming = (_, _, _) => fail("expected the cookie to be refused")
            )).map(result => assert(result.failure.map(_.part) == Present("the value of cookie 'session'")))
        }

        "optional param present" in {
            val route   = HttpRoute.getRaw("users").request(_.queryOpt[Int]("page"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("page", Present(5): Maybe[Int])

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(url.contains("page=5"))
        }

        "optional param absent" in {
            val route   = HttpRoute.getRaw("users").request(_.queryOpt[Int]("page"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("page", Absent: Maybe[Int])

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(!url.contains("page"))
        }
    }

    // ==================== decodeBufferedResponse ====================

    "decodeBufferedResponse" - {
        "json body" in {
            val route = HttpRoute.getRaw("users").response(_.bodyJson[User])
            val json  = """{"name":"Bob","age":25}"""
            val bytes = Span.fromUnsafe(json.getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                bytes,
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(response) =>
                    assert(response.status == HttpStatus.OK)
                    val user = response.fields.body
                    assert(user == User("Bob", 25))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "text body" in {
            val route = HttpRoute.getRaw("echo").response(_.bodyText)
            val bytes = Span.fromUnsafe("hello".getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                bytes,
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(response) =>
                    assert(response.fields.body == "hello")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "with response headers" in {
            val route   = HttpRoute.getRaw("data").response(_.header[String]("requestId", wireName = "X-Request-Id").bodyText)
            val headers = HttpHeaders.empty.add("X-Request-Id", "req-123")
            val bytes   = Span.fromUnsafe("ok".getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, headers, bytes, route.method.name, HttpUrl.fromUri("/test")) match
                case Result.Success(response) =>
                    assert(response.fields.body == "ok")
                    assert(response.fields.requestId == "req-123")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "missing required header fails" in {
            val route = HttpRoute.getRaw("data").response(_.header[String]("requestId", wireName = "X-Request-Id").bodyText)
            val bytes = Span.fromUnsafe("ok".getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                bytes,
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Missing required header"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "no body" in {
            val route = HttpRoute.getRaw("health")

            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                Span.empty[Byte],
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(response) =>
                    assert(response.status == HttpStatus.OK)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== decodeBufferedRequest ====================

    "decodeBufferedRequest" - {
        "json body with path captures" in {
            val route    = HttpRoute.postRaw("users" / HttpPath.Capture[Int]("userId")).request(_.bodyJson[User])
            val captures = Dict("userId" -> "42")
            val json     = """{"name":"Alice","age":30}"""
            val bytes    = Span.fromUnsafe(json.getBytes("UTF-8"))

            RouteUtil.decodeBufferedRequest(route, captures, Absent, HttpHeaders.empty, bytes) match
                case Result.Success(request) =>
                    assert(request.fields.userId == 42)
                    assert(request.fields.body == User("Alice", 30))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "query params" in {
            val route      = HttpRoute.getRaw("users").request(_.query[Int]("page").query[String]("sort"))
            val queryParam = Present(HttpUrl.fromUri("/?page=2&sort=name"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], queryParam, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(request) =>
                    assert(request.fields.page == 2)
                    assert(request.fields.sort == "name")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional query param absent" in {
            val route = HttpRoute.getRaw("users").request(_.queryOpt[Int]("page"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(request) =>
                    assert(request.fields.page == Absent)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "default param value" in {
            val route = HttpRoute.getRaw("users").request(_.query[Int]("page", default = Present(1)))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(request) =>
                    assert(request.fields.page == 1)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "missing required param fails" in {
            val route = HttpRoute.getRaw("users").request(_.query[Int]("page"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Missing required query"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "missing path capture fails" in {
            val route = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Missing required path"))
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== encodeResponse ====================

    "encodeResponse" - {
        "buffered multipart boundary is generated inside the scoped effect" in {
            val uuid      = UUID.parse("00112233-4455-4677-a899-aabbccddeeff").getOrThrow
            val generator = new FixedUUIDGenerator(uuid)
            type MultipartOutput = "body" ~ Seq[HttpRequest.Part]
            val base                                            = HttpRoute.getRaw("download")
            val route: HttpRoute[Any, MultipartOutput, Nothing] = HttpRoute(
                base.method,
                base.request,
                HttpRoute.ResponseDef[MultipartOutput](
                    fields = Chunk(HttpRoute.Field.Body("body", HttpRoute.ContentType.Multipart, ""))
                )
            )
            val response = HttpResponse.ok.addField(
                "body",
                Seq(HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8"))))
            )

            var callbackInvoked                                      = false
            var headers                                              = HttpHeaders.empty
            var body                                                 = Span.empty[Byte]
            val encoding: Unit < (Sync & Abort[HttpCookieException]) =
                RouteUtil.encodeResponse(route, response)(
                    onEmpty = (_, _) => fail("expected buffered"),
                    onBuffered = (_, actualHeaders, actualBody) =>
                        callbackInvoked = true
                        headers = actualHeaders
                        body =
                            actualBody
                    ,
                    onStreaming = (_, _, _) => fail("expected buffered")
                )

            assert(!callbackInvoked)
            assert(generator.calls == 0)

            UUID.let(generator)(encoding).map { _ =>
                val boundary = uuid.show
                val encoded  = new String(body.toArrayUnsafe, "UTF-8")
                assert(callbackInvoked)
                assert(generator.calls == 1)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=$boundary"))
                assert(encoded.contains(s"--$boundary\r\n"))
                assert(encoded.endsWith(s"--$boundary--\r\n"))
            }
        }

        "streaming multipart boundary is generated inside the scoped effect" in {
            val uuid      = UUID.parse("ffeeddcc-bbaa-4988-b766-554433221100").getOrThrow
            val generator = new FixedUUIDGenerator(uuid)
            type MultipartOutput = "body" ~ Stream[HttpRequest.Part, Async & Abort[HttpException]]
            val base                                            = HttpRoute.getRaw("download")
            val route: HttpRoute[Any, MultipartOutput, Nothing] = HttpRoute(
                base.method,
                base.request,
                HttpRoute.ResponseDef[MultipartOutput](
                    fields = Chunk(HttpRoute.Field.Body("body", HttpRoute.ContentType.MultipartStream, ""))
                )
            )
            val parts = Stream.init[HttpRequest.Part, Async & Abort[HttpException]](Seq(
                HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8")))
            ))
            val response = HttpResponse.ok.addField("body", parts)

            var callbackInvoked                                        = false
            var headers                                                = HttpHeaders.empty
            var body: Stream[Span[Byte], Async & Abort[HttpException]] = Stream.empty
            val encoding: Unit < (Sync & Abort[HttpCookieException])   =
                RouteUtil.encodeResponse(route, response)(
                    onEmpty = (_, _) => fail("expected streaming"),
                    onBuffered = (_, _, _) => fail("expected streaming"),
                    onStreaming = (_, actualHeaders, actualBody) =>
                        callbackInvoked = true
                        headers = actualHeaders
                        body = actualBody
                )

            assert(!callbackInvoked)
            assert(generator.calls == 0)

            UUID.let(generator)(encoding).map { _ =>
                val boundary = uuid.show
                assert(callbackInvoked)
                assert(generator.calls == 1)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=$boundary"))
                body.run.map { chunks =>
                    val encoded = chunks.toSeq.map(span => new String(span.toArrayUnsafe, "UTF-8")).mkString
                    assert(encoded.contains(s"--$boundary\r\n"))
                    assert(encoded.endsWith(s"--$boundary--\r\n"))
                }
            }
        }

        "buffered multipart uses the boundary supplied in the response Content-Type" in {
            val suppliedBoundary = "caller:response-boundary"
            val generated        =
                UUID.parse("00112233-4455-4677-a899-aabbccddeeff").getOrThrow
            val generator = new FixedUUIDGenerator(generated)
            type MultipartOutput = "body" ~ Seq[HttpRequest.Part]
            val base                                            = HttpRoute.getRaw("download")
            val route: HttpRoute[Any, MultipartOutput, Nothing] = HttpRoute(
                base.method,
                base.request,
                HttpRoute.ResponseDef[MultipartOutput](
                    fields = Chunk(HttpRoute.Field.Body("body", HttpRoute.ContentType.Multipart, ""))
                )
            )
            val response = HttpResponse.ok
                .setHeader("Content-Type", s"multipart/form-data; boundary=\"$suppliedBoundary\"")
                .addField(
                    "body",
                    Seq(HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8"))))
                )

            var headers = HttpHeaders.empty
            var body    = Span.empty[Byte]
            UUID.let(generator) {
                RouteUtil.encodeResponse(route, response)(
                    onEmpty = (_, _) => fail("expected buffered"),
                    onBuffered = (_, actualHeaders, actualBody) =>
                        headers = actualHeaders
                        body = actualBody
                    ,
                    onStreaming = (_, _, _) => fail("expected buffered")
                )
            }.map { _ =>
                val encoded = new String(body.toArrayUnsafe, "UTF-8")
                assert(generator.calls == 0)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=\"$suppliedBoundary\""))
                assert(encoded.contains(s"--$suppliedBoundary\r\n"))
                assert(!encoded.contains(generated.show))
            }
        }

        "buffered multipart replaces an invalid quoted response boundary without mismatching the body" in {
            val uuid      = UUID.parse("ffeeddcc-bbaa-4988-b766-554433221100").getOrThrow
            val generator = new FixedUUIDGenerator(uuid)
            type MultipartOutput = "body" ~ Seq[HttpRequest.Part]
            val base                                            = HttpRoute.getRaw("download")
            val route: HttpRoute[Any, MultipartOutput, Nothing] = HttpRoute(
                base.method,
                base.request,
                HttpRoute.ResponseDef[MultipartOutput](
                    fields = Chunk(HttpRoute.Field.Body("body", HttpRoute.ContentType.Multipart, ""))
                )
            )
            val response = HttpResponse.ok
                .setHeader("Content-Type", "multipart/form-data; boundary=\"abc;def\"")
                .addField(
                    "body",
                    Seq(HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8"))))
                )

            var headers = HttpHeaders.empty
            var body    = Span.empty[Byte]
            UUID.let(generator) {
                RouteUtil.encodeResponse(route, response)(
                    onEmpty = (_, _) => fail("expected buffered"),
                    onBuffered = (_, actualHeaders, actualBody) =>
                        headers = actualHeaders
                        body = actualBody
                    ,
                    onStreaming = (_, _, _) => fail("expected buffered")
                )
            }.map { _ =>
                val boundary = uuid.show
                val encoded  = new String(body.toArrayUnsafe, "UTF-8")
                assert(generator.calls == 1)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=$boundary"))
                assert(encoded.contains(s"--$boundary\r\n"))
                assert(!encoded.contains("--abc"))
            }
        }

        "buffered multipart replaces an unquoted response boundary containing whitespace" in {
            val uuid      = UUID.parse("ffeeddcc-bbaa-4988-b766-554433221100").getOrThrow
            val generator = new FixedUUIDGenerator(uuid)
            type MultipartOutput = "body" ~ Seq[HttpRequest.Part]
            val base                                            = HttpRoute.getRaw("download")
            val route: HttpRoute[Any, MultipartOutput, Nothing] = HttpRoute(
                base.method,
                base.request,
                HttpRoute.ResponseDef[MultipartOutput](
                    fields = Chunk(HttpRoute.Field.Body("body", HttpRoute.ContentType.Multipart, ""))
                )
            )
            val response = HttpResponse.ok
                .setHeader("Content-Type", "multipart/form-data; boundary=abc def")
                .addField(
                    "body",
                    Seq(HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8"))))
                )

            var headers = HttpHeaders.empty
            var body    = Span.empty[Byte]
            UUID.let(generator) {
                RouteUtil.encodeResponse(route, response)(
                    onEmpty = (_, _) => fail("expected buffered"),
                    onBuffered = (_, actualHeaders, actualBody) =>
                        headers = actualHeaders
                        body = actualBody
                    ,
                    onStreaming = (_, _, _) => fail("expected buffered")
                )
            }.map { _ =>
                val boundary = uuid.show
                val encoded  = new String(body.toArrayUnsafe, "UTF-8")
                assert(generator.calls == 1)
                assert(headers.get("Content-Type").contains(s"multipart/form-data; boundary=$boundary"))
                assert(encoded.contains(s"--$boundary\r\n"))
                assert(!encoded.contains("--abc def"))
            }
        }

        "json body" in {
            val route    = HttpRoute.getRaw("users").response(_.bodyJson[User])
            val response = HttpResponse.ok.addField("body", User("Bob", 25))

            var headers = HttpHeaders.empty
            var bodyStr = ""
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (status, hdrs, bytes) =>
                    headers = hdrs
                    bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
            assert(headers.get("Content-Type").contains("application/json"))
            assert(bodyStr.contains("Bob"))
        }

        "empty body" in {
            val route    = HttpRoute.getRaw("health")
            val response = HttpResponse.ok

            var status: HttpStatus = null
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (s, _) => status = s,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(status == HttpStatus.OK)
        }

        "with response header params" in {
            val route    = HttpRoute.getRaw("data").response(_.header[String]("requestId", wireName = "X-Request-Id").bodyText)
            val response = HttpResponse.ok
                .addField("requestId", "req-456")
                .addField("body", "ok")

            var headers = HttpHeaders.empty
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, h, _) => headers = h,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
            assert(headers.get("X-Request-Id") == Present("req-456"))
        }
    }

    // ==================== encodeRequest edge cases ====================

    "encodeRequest edge cases" - {
        "body + query + header combined" in {
            val route = HttpRoute.postRaw("items")
                .request(_.query[Int]("page").header[String]("auth", wireName = "Authorization").bodyJson[User])
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/items").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("page", 1).addField("auth", "Bearer tok").addField("body", User("X", 1))

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (url, headers, bytes) =>
                    assert(url.contains("page=1"))
                    assert(headers.get("Authorization") == Present("Bearer tok"))
                    assert(headers.get("Content-Type").contains("application/json"))
                    val bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                    assert(bodyStr.contains("X"))
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
        }

        "multiple cookies" in {
            val route = HttpRoute.getRaw("data")
                .request(_.cookie[String]("session").cookie[String]("theme"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("session", "abc").addField("theme", "dark")

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            val cookie = headers.get("Cookie")
            assert(cookie.isDefined)
            val cookieStr = cookie.getOrElse("")
            assert(cookieStr.contains("session=abc"))
            assert(cookieStr.contains("theme=dark"))
            assert(cookieStr.contains("; "))
        }

        "URL-encoded special characters in query" in {
            val route   = HttpRoute.getRaw("search").request(_.query[String]("q"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/search").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("q", "hello world&foo=bar")

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(!url.contains(" "))
            assert(url.contains("q="))
            val encoded = url.split("q=")(1)
            assert(encoded == "hello%20world%26foo%3Dbar")
        }

        "URL-encoded special characters in path capture" in {
            val route   = HttpRoute.getRaw("users" / HttpPath.Capture[String]("name"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users/x").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("name", "John Doe")

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(!url.contains(" "))
            assert(url.startsWith("/users/"))
        }

        "preserves existing request headers" in {
            val route           = HttpRoute.getRaw("data").request(_.header[String]("extra", wireName = "X-Extra"))
            val existingHeaders = HttpHeaders.empty.add("X-Existing", "keep-me")
            val request         = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                existingHeaders,
                Record.empty
            ).addField("extra", "new-val")

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(headers.get("X-Existing") == Present("keep-me"))
            assert(headers.get("X-Extra") == Present("new-val"))
        }

        "optional header absent" in {
            val route   = HttpRoute.getRaw("data").request(_.headerOpt[String]("auth", wireName = "Authorization"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("auth", Absent: Maybe[String])

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _) => fail("expected empty"),
                onStreaming = (_, _, _) => fail("expected empty")
            )
            assert(!headers.contains("Authorization"))
        }

        "binary body" in {
            val route   = HttpRoute.postRaw("upload").request(_.bodyBinary)
            val data    = Span.fromUnsafe(Array[Byte](1, 2, 3, 4))
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/upload").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("body", data)

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, headers, bytes) =>
                    assert(headers.get("Content-Type").contains("application/octet-stream"))
                    assert(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]].toSeq == Seq[Byte](1, 2, 3, 4))
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
        }

        "form body" in {
            val route   = HttpRoute.postRaw("login").request(_.bodyForm[LoginForm])
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/login").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("body", LoginForm("alice", "secret"))

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, headers, bytes) =>
                    assert(headers.get("Content-Type").contains("application/x-www-form-urlencoded"))
                    val bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                    assert(bodyStr.contains("username=alice"))
                    assert(bodyStr.contains("password=secret"))
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
        }

        "user-set Content-Type preserved for buffered request" in {
            val route   = HttpRoute.postRaw("api").request(_.bodyBinary)
            val data    = Span.fromUnsafe("{ k1 }".getBytes("UTF-8"))
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/api").getOrThrow,
                HttpHeaders.empty.add("Content-Type", "application/graphql"),
                Record.empty
            ).addField("body", data)

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, headers, _) =>
                    assert(headers.get("Content-Type").contains("application/graphql")),
                onStreaming = (_, _, _) => fail("expected buffered")
            )
        }

        "user-set Content-Type preserved for streaming request" in {
            val route   = HttpRoute.postRaw("api").request(_.bodyStream)
            val stream  = kyo.Stream.init[Span[Byte], kyo.Async & Abort[HttpException]](Seq(Span.fromUnsafe("data".getBytes("UTF-8"))))
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/api").getOrThrow,
                HttpHeaders.empty.add("Content-Type", "text/xml"),
                Record.empty
            ).addField("body", stream)

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected streaming"),
                onBuffered = (_, _, _) => fail("expected streaming"),
                onStreaming = (_, hdrs, _) => headers = hdrs
            )
            assert(headers.get("Content-Type").contains("text/xml"))
        }
    }

    // ==================== decodeBufferedRequest edge cases ====================

    "decodeBufferedRequest edge cases" - {
        "header params" in {
            val route   = HttpRoute.getRaw("data").request(_.header[String]("auth", wireName = "Authorization"))
            val headers = HttpHeaders.empty.add("Authorization", "Bearer xyz")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("auth") == "Bearer xyz")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "cookie params" in {
            val route   = HttpRoute.getRaw("data").request(_.cookie[String]("session"))
            val headers = HttpHeaders.empty.add("Cookie", "session=abc123; theme=dark")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("session") == "abc123")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional header present" in {
            val route   = HttpRoute.getRaw("data").request(_.headerOpt[String]("auth", wireName = "Authorization"))
            val headers = HttpHeaders.empty.add("Authorization", "Bearer xyz")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("auth") == Present("Bearer xyz"))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional header absent" in {
            val route = HttpRoute.getRaw("data").request(_.headerOpt[String]("auth", wireName = "Authorization"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("auth") == Absent)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "invalid int capture fails with parse error" in {
            val route = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId"))

            RouteUtil.decodeBufferedRequest(route, Dict("userId" -> "notanumber"), Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Failed to decode path"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "invalid int query param fails with parse error" in {
            val route = HttpRoute.getRaw("users").request(_.query[Int]("page"))

            RouteUtil.decodeBufferedRequest(
                route,
                Dict.empty[String, String],
                Present(HttpUrl.fromUri("/?page=abc")),
                HttpHeaders.empty,
                Span.empty[Byte]
            ) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Failed to decode query"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "default param ignored when value present" in {
            val route = HttpRoute.getRaw("users").request(_.query[Int]("page", default = Present(1)))

            RouteUtil.decodeBufferedRequest(
                route,
                Dict.empty[String, String],
                Present(HttpUrl.fromUri("/?page=5")),
                HttpHeaders.empty,
                Span.empty[Byte]
            ) match
                case Result.Success(req) =>
                    assert(req.fields.dict("page") == 5)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "invalid json body fails" in {
            val route = HttpRoute.postRaw("users").request(_.bodyJson[User])
            val bytes = Span.fromUnsafe("not valid json".getBytes("UTF-8"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, bytes) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("JSON decode failed"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "binary body" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyBinary)
            val data  = Span.fromUnsafe(Array[Byte](10, 20, 30))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, data) match
                case Result.Success(req) =>
                    val decoded = req.fields.dict("body").asInstanceOf[Span[Byte]]
                    assert(decoded.toArrayUnsafe.asInstanceOf[Array[Byte]].toSeq == Seq[Byte](10, 20, 30))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "form body" in {
            val route = HttpRoute.postRaw("login").request(_.bodyForm[LoginForm])
            val bytes = Span.fromUnsafe("username=alice&password=secret".getBytes("UTF-8"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, bytes) match
                case Result.Success(req) =>
                    assert(req.fields.dict("body") == LoginForm("alice", "secret"))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "empty body for no-body route" in {
            val route = HttpRoute.getRaw("health")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict.isEmpty)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        // Unit body short-circuit: RouteUtil.decodeBufferedBodyValue short-circuits
        // Unit-schema JSON handlers for empty body and JSON "null" body, replacing the
        // tolerance that the old Json[Unit] typeclass override provided before the Json migration.
        // bodyJson[Unit] cannot be expressed through the public route DSL (jsonSchema[Unit]
        // fails at compile time), so the route is constructed by embedding Schema.unitSchema
        // directly into ContentType.Json.
        "Unit JSON body - empty body short-circuits to success" in {
            val unitBodyField: HttpRoute.Field["body" ~ Unit] =
                HttpRoute.Field.Body("body", HttpRoute.ContentType.Json(Schema.unitSchema, Json.JsonSchema.Bool()), "")
            val route = HttpRoute["body" ~ Unit, Any, Nothing](
                HttpMethod.POST,
                HttpRoute.RequestDef["body" ~ Unit](HttpPath.Literal("unit-action"), Chunk(unitBodyField))
            )
            val headers = HttpHeaders.empty.add("Content-Type", "application/json")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("body") == ())
                case Result.Failure(err) => fail(s"expected success for empty body, got failure: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        // This test uses the public bodyJson[Unit] DSL (now that PrimitiveKind.Unit and
        // JsonSchema.Null are in place), verifying the short-circuit for "null" body.
        "Unit JSON body - null body short-circuits to success via public DSL" in {
            val route     = HttpRoute.postRaw("unit-action").request(_.bodyJson[Unit])
            val headers   = HttpHeaders.empty.add("Content-Type", "application/json")
            val nullBytes = Span.fromUnsafe("null".getBytes("UTF-8"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, nullBytes) match
                case Result.Success(req) =>
                    assert(req.fields.dict("body") == ())
                case Result.Failure(err) => fail(s"expected success for null body, got failure: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== decodeBufferedResponse edge cases ====================

    "decodeBufferedResponse edge cases" - {
        "invalid json body fails" in {
            val route = HttpRoute.getRaw("users").response(_.bodyJson[User])
            val bytes = Span.fromUnsafe("{invalid".getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                bytes,
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("JSON decode failed"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "binary body" in {
            val route = HttpRoute.getRaw("download").response(_.bodyBinary)
            val data  = Span.fromUnsafe(Array[Byte](5, 6, 7))

            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                data,
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(resp) =>
                    val decoded = resp.fields.dict("body").asInstanceOf[Span[Byte]]
                    assert(decoded.toArrayUnsafe.asInstanceOf[Array[Byte]].toSeq == Seq[Byte](5, 6, 7))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional response header present" in {
            val route   = HttpRoute.getRaw("data").response(_.headerOpt[String]("etag", wireName = "ETag"))
            val headers = HttpHeaders.empty.add("ETag", "abc")

            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus.OK,
                headers,
                Span.empty[Byte],
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(resp) =>
                    assert(resp.fields.dict("etag") == Present("abc"))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional response header absent" in {
            val route = HttpRoute.getRaw("data").response(_.headerOpt[String]("etag", wireName = "ETag"))

            RouteUtil.decodeBufferedResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                Span.empty[Byte],
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(resp) =>
                    assert(resp.fields.dict("etag") == Absent)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== encodeResponse edge cases ====================

    "encodeResponse edge cases" - {
        "status preserved with json body" in {
            val route    = HttpRoute.getRaw("users").response(_.bodyJson[User])
            val response = HttpResponse.created.addField("body", User("X", 1))

            var status: HttpStatus = HttpStatus.OK
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (s, _, _) => status = s,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
            assert(status == HttpStatus.Created)
        }

        "response cookie encoding" in {
            val route = HttpRoute.getRaw("login")
                .response(_.cookie[String]("session", wireName = "session"))
            val response = HttpResponse.ok
                .addField("session", HttpCookie("tok123").httpOnly(true))

            var headers = HttpHeaders.empty
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, h, _) => headers = h,
                onStreaming = (_, _, _) => fail("expected empty or buffered")
            )
            val setCookie = headers.get("Set-Cookie")
            assert(setCookie.isDefined)
            val sc = setCookie.getOrElse("")
            assert(sc.contains("session=tok123"))
            assert(sc.contains("HttpOnly"))
        }

        "a response cookie carrying ';' fails with HttpCookieException instead of writing an attribute" in {
            val route = HttpRoute.getRaw("login")
                .response(_.cookie[String]("session", wireName = "session"))
            val response = HttpResponse.ok.addField("session", HttpCookie("tok; Domain=evil.example"))

            Abort.run[HttpCookieException](RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected the cookie to be refused"),
                onBuffered = (_, _, _) => fail("expected the cookie to be refused"),
                onStreaming = (_, _, _) => fail("expected the cookie to be refused")
            )).map(result => assert(result.failure.map(_.part) == Present("the value of cookie 'session'")))
        }

        "text body" in {
            val route    = HttpRoute.getRaw("echo").response(_.bodyText)
            val response = HttpResponse.ok.addField("body", "hello")

            var bodyStr = ""
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, headers, bytes) =>
                    assert(headers.get("Content-Type").contains("text/plain; charset=utf-8"))
                    bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
            assert(bodyStr == "hello")
        }

        "user-set Content-Type preserved for buffered response" in {
            val route    = HttpRoute.getRaw("data").response(_.bodyBinary)
            val response = HttpResponse.ok.addField("body", Span.fromUnsafe("hello".getBytes("UTF-8")))
                .setHeader("Content-Type", "application/graphql-response+json")

            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, headers, _) =>
                    assert(headers.get("Content-Type").contains("application/graphql-response+json")),
                onStreaming = (_, _, _) => fail("expected buffered")
            )
        }

        "user-set Content-Type preserved for streaming response" in {
            val route    = HttpRoute.getRaw("events").response(_.bodyStream)
            val stream   = kyo.Stream.init[Span[Byte], kyo.Async & Abort[HttpException]](Seq(Span.fromUnsafe("data".getBytes("UTF-8"))))
            val response = HttpResponse.ok.addField("body", stream)
                .setHeader("Content-Type", "multipart/mixed; boundary=abc")

            var headers = HttpHeaders.empty
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected streaming"),
                onBuffered = (_, _, _) => fail("expected streaming"),
                onStreaming = (_, hdrs, _) => headers = hdrs
            )
            assert(headers.get("Content-Type").contains("multipart/mixed; boundary=abc"))
        }
    }

    // ==================== Round-trip ====================

    "round-trip" - {
        "encode request then decode as server" in {
            val route = HttpRoute.postRaw("users" / HttpPath.Capture[Int]("userId"))
                .request(_.query[String]("action").header[String]("auth", wireName = "Authorization").bodyJson[User])

            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/users/42").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            )
                .addField("userId", 42)
                .addField("action", "create")
                .addField("auth", "Bearer token123")
                .addField("body", User("Alice", 30))

            // Encode
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (url, headers, bytes) =>
                    // Verify encoded URL contains query
                    assert(url.contains("action=create"))

                    // Verify Authorization header
                    assert(headers.get("Authorization") == Present("Bearer token123"))

                    // Now decode as if we're the server
                    RouteUtil.decodeBufferedRequest(
                        route,
                        Dict("userId" -> "42"),
                        Present(HttpUrl.fromUri("/?action=create")),
                        headers,
                        bytes
                    ) match
                        case Result.Success(decoded) =>
                            assert(decoded.fields.dict("userId") == 42)
                            assert(decoded.fields.dict("action") == "create")
                            assert(decoded.fields.dict("auth") == "Bearer token123")
                            assert(decoded.fields.dict("body") == User("Alice", 30))
                        case Result.Failure(err) => fail(s"decode failed: $err")
                        case p: Result.Panic     => throw p.exception
                    end match
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
        }

        "encode response then decode as client" in {
            val route = HttpRoute.getRaw("users")
                .response(_.header[String]("requestId", wireName = "X-Request-Id").bodyJson[User])

            val response = HttpResponse.ok
                .addField("requestId", "req-789")
                .addField("body", User("Bob", 25))

            // Encode as server
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (status, headers, bytes) =>
                    // Decode as client
                    RouteUtil.decodeBufferedResponse(route, status, headers, bytes, route.method.name, HttpUrl.fromUri("/test")) match
                        case Result.Success(decoded) =>
                            assert(decoded.status == HttpStatus.OK)
                            assert(decoded.fields.dict("requestId") == "req-789")
                            assert(decoded.fields.dict("body") == User("Bob", 25))
                        case Result.Failure(err) => fail(s"decode failed: $err")
                        case p: Result.Panic     => throw p.exception
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
        }

        "form body round-trip" in {
            val route   = HttpRoute.postRaw("login").request(_.bodyForm[LoginForm])
            val form    = LoginForm("bob", "pass123")
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/login").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("body", form)

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, _, bytes) =>
                    RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, bytes) match
                        case Result.Success(decoded) =>
                            assert(decoded.fields.dict("body") == form)
                        case Result.Failure(err) => fail(s"decode failed: $err")
                        case p: Result.Panic     => throw p.exception
                ,
                onStreaming = (_, _, _) => fail("expected buffered")
            )
        }
    }

    // ==================== SSE encoding ====================

    "SSE encoding" - {
        "encodeResponse produces SSE frames" in {
            val route = HttpRoute.getRaw("events").response(_.bodySseJson[User])
            val events: kyo.Stream[HttpSseEvent[User], kyo.Async & Abort[HttpException]] = kyo.Stream.init(Seq(
                HttpSseEvent(User("Alice", 30)),
                HttpSseEvent(User("Bob", 25), event = Present("update")),
                HttpSseEvent(User("Carol", 35), id = Present("3"), retry = Present(5000.millis))
            ))
            val response = HttpResponse.ok
                .addField("body", events)

            var headers: HttpHeaders                                             = HttpHeaders.empty
            var stream: kyo.Stream[Span[Byte], kyo.Async & Abort[HttpException]] = null
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected streaming"),
                onBuffered = (_, _, _) => fail("expected streaming"),
                onStreaming = (_, hdrs, s) =>
                    headers = hdrs
                    stream = s
            )
            assert(headers.get("Content-Type").contains("text/event-stream"))
            stream.run.map { chunks =>
                val frames = chunks.toSeq.map(span => new String(span.toArrayUnsafe, "UTF-8"))
                // First event: just data
                assert(frames(0).contains("data:"))
                assert(frames(0).contains("Alice"))
                assert(frames(0).endsWith("\n\n"))
                // Second event: data + event name
                assert(frames(1).contains("event: update"))
                assert(frames(1).contains("Bob"))
                // Third event: data + id + retry
                assert(frames(2).contains("id: 3"))
                assert(frames(2).contains("retry: 5000"))
                assert(frames(2).contains("Carol"))
            }
        }
    }

    // ==================== SSE decoding ====================

    "SSE decoding" - {
        "decodeStreamingResponse parses SSE frames" in {
            val route     = HttpRoute.getRaw("events").response(_.bodySseJson[User])
            val frame1    = "data: {\"name\":\"Alice\",\"age\":30}\n\n"
            val frame2    = "event: update\ndata: {\"name\":\"Bob\",\"age\":25}\n\n"
            val frame3    = "id: 3\nretry: 5000\ndata: {\"name\":\"Carol\",\"age\":35}\n\n"
            val rawStream = kyo.Stream.init(Seq(
                Span.fromUnsafe(frame1.getBytes("UTF-8")),
                Span.fromUnsafe(frame2.getBytes("UTF-8")),
                Span.fromUnsafe(frame3.getBytes("UTF-8"))
            ))

            RouteUtil.decodeStreamingResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                rawStream,
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(response) =>
                    val eventStream = response.fields.body
                    eventStream.run.map { events =>
                        val evts = events.toSeq
                        assert(evts.size == 3)
                        assert(evts(0).data == User("Alice", 30))
                        assert(evts(0).event == Absent)
                        assert(evts(1).data == User("Bob", 25))
                        assert(evts(1).event == Present("update"))
                        assert(evts(2).data == User("Carol", 35))
                        assert(evts(2).id == Present("3"))
                        assert(evts(2).retry == Present(5000.millis))
                    }
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== Streamed body framing ====================

    // A streamed body is framed on bytes, whatever the span boundaries: a multi-byte character or a delimiter split across spans is
    // reassembled, binary content passes unchanged, and an element that does not decode fails the stream after the elements before it.
    "streamed body framing" - {

        def spans(pieces: String*): Stream[Span[Byte], Async & Abort[HttpException]] =
            Stream.init(pieces.map(p => Span.fromUnsafe(p.getBytes("UTF-8"))))

        /** Runs a decoded stream to its end or its failure: the elements emitted before either, and how it ended. */
        def collect[A: Tag](stream: Stream[A, Async & Abort[HttpException]])(using
            Frame
        ): (Chunk[A], Result[HttpException, Unit]) < Async =
            AtomicRef.init(Chunk.empty[A]).map { seen =>
                Abort.run[HttpException](stream.foreach(a => seen.updateAndGet(_.append(a)).unit)).map { result =>
                    seen.get.map(items => (items, result))
                }
            }

        def responseBody[A](
            route: HttpRoute[?, "body" ~ Stream[A, Async & Abort[HttpException]], ?],
            stream: Stream[Span[Byte], Async & Abort[HttpException]]
        )(using Frame, kyo.test.AssertScope): Stream[A, Async & Abort[HttpException]] =
            RouteUtil.decodeStreamingResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                stream,
                route.method.name,
                HttpUrl.fromUri("/s")
            ) match
                case Result.Success(response) => response.fields.body
                case Result.Failure(err)      => fail(s"decode failed: $err")
                case p: Result.Panic          => throw p.exception

        val ndjson  = HttpRoute.getRaw("lines").response(_.bodyNdjson[User])
        val sseJson = HttpRoute.getRaw("events").response(_.bodySseJson[User])
        val sseText = HttpRoute.getRaw("events").response(_.bodySseText)

        "NDJSON" - {
            "the elements before a line that does not decode are emitted, then the stream fails with HttpJsonDecodeException" in {
                val body = "{\"name\":\"Alice\",\"age\":30}\n{\"name\":\"Bob\",\"age\":25}\nnot json\n{\"name\":\"Carol\",\"age\":35}\n"
                collect(responseBody(ndjson, spans(body))).map { (users, ended) =>
                    assert(users == Chunk(User("Alice", 30), User("Bob", 25)), s"observed $users")
                    ended match
                        case Result.Failure(_: HttpJsonDecodeException) => succeed
                        case other                                      => fail(s"expected HttpJsonDecodeException, got $other")
                }
            }

            "a multi-byte character split across two spans is decoded whole" in {
                val line                                                     = "{\"name\":\"Zoë\",\"age\":30}\n".getBytes("UTF-8")
                val split                                                    = line.indexWhere(_ == 0xc3.toByte) + 1
                val stream: Stream[Span[Byte], Async & Abort[HttpException]] =
                    Stream.init(Seq(Span.fromUnsafe(line.take(split)), Span.fromUnsafe(line.drop(split))))
                collect(responseBody(ndjson, stream)).map { (users, ended) =>
                    assert(users == Chunk(User("Zoë", 30)), s"observed $users")
                    assert(ended == Result.unit, s"observed $ended")
                }
            }

            "a final line without a trailing newline is decoded" in {
                collect(responseBody(ndjson, spans("{\"name\":\"Alice\",\"age\":30}\n{\"name\":\"Bob\",\"age\"", ":25}"))).map {
                    (users, ended) =>
                        assert(users == Chunk(User("Alice", 30), User("Bob", 25)), s"observed $users")
                        assert(ended == Result.unit, s"observed $ended")
                }
            }

            "CRLF line endings and blank lines are accepted" in {
                collect(responseBody(ndjson, spans("{\"name\":\"Alice\",\"age\":30}\r\n\r\n\n{\"name\":\"Bob\",\"age\":25}\r\n"))).map {
                    (users, ended) =>
                        assert(users == Chunk(User("Alice", 30), User("Bob", 25)), s"observed $users")
                        assert(ended == Result.unit, s"observed $ended")
                }
            }
        }

        "SSE" - {
            "the events before one whose data does not decode are emitted, then the stream fails with HttpJsonDecodeException" in {
                val body = "data: {\"name\":\"Alice\",\"age\":30}\n\ndata: not json\n\ndata: {\"name\":\"Carol\",\"age\":35}\n\n"
                collect(responseBody(sseJson, spans(body))).map { (events, ended) =>
                    assert(events.map(_.data) == Chunk(User("Alice", 30)), s"observed $events")
                    ended match
                        case Result.Failure(_: HttpJsonDecodeException) => succeed
                        case other                                      => fail(s"expected HttpJsonDecodeException, got $other")
                }
            }

            "a multi-byte character split across two spans is decoded whole" in {
                val frame                                                    = "data: {\"name\":\"Zoë\",\"age\":30}\n\n".getBytes("UTF-8")
                val split                                                    = frame.indexWhere(_ == 0xc3.toByte) + 1
                val stream: Stream[Span[Byte], Async & Abort[HttpException]] =
                    Stream.init(Seq(Span.fromUnsafe(frame.take(split)), Span.fromUnsafe(frame.drop(split))))
                collect(responseBody(sseJson, stream)).map { (events, ended) =>
                    assert(events.map(_.data) == Chunk(User("Zoë", 30)), s"observed $events")
                    assert(ended == Result.unit, s"observed $ended")
                }
            }

            "CRLF and CR line endings frame events, and an event split across spans is assembled" in {
                collect(responseBody(sseText, spans("data: one\r\n\r\ndata: two\r\rdata: th", "ree\n\n"))).map { (events, ended) =>
                    assert(events.map(_.data) == Chunk("one", "two", "three"), s"observed $events")
                    assert(ended == Result.unit, s"observed $ended")
                }
            }

            "one space after the colon is stripped and the rest of the value is kept" in {
                collect(responseBody(sseText, spans("data:  two spaces\n\ndata:tight\n\nevent:  spaced\ndata: x\n\n"))).map {
                    (events, ended) =>
                        assert(events.map(_.data) == Chunk(" two spaces", "tight", "x"), s"observed $events")
                        assert(events(2).event == Present(" spaced"), s"observed ${events(2)}")
                        assert(ended == Result.unit, s"observed $ended")
                }
            }

            "data lines join with LF, a comment is ignored, a field-less event is not dispatched, and an unterminated event is discarded" in {
                collect(responseBody(sseText, spans(": ping\ndata: a\ndata: b\n\nevent: only\n\ndata: tail"))).map { (events, ended) =>
                    assert(events.map(_.data) == Chunk("a\nb"), s"observed $events")
                    assert(ended == Result.unit, s"observed $ended")
                }
            }

            "a line without a colon is a field with an empty value; retry with a non-digit value is ignored" in {
                collect(responseBody(sseText, spans("data\n\nretry: soon\nid: 7\ndata: x\n\nretry: 250\ndata: y\n\n"))).map {
                    (events, ended) =>
                        assert(events.map(_.data) == Chunk("", "x", "y"), s"observed $events")
                        assert(events(1).retry == Absent && events(1).id == Present("7"), s"observed ${events(1)}")
                        assert(events(2).retry == Present(250.millis), s"observed ${events(2)}")
                        assert(ended == Result.unit, s"observed $ended")
                }
            }

            // The last event id is stream state (the WHATWG event stream format): a dispatch clears the data and event type buffers only,
            // so the id set by one line names every later event until the next id line, which may set it to the empty string.
            "the last event id persists across events until the next id line" in {
                collect(responseBody(sseText, spans("id: 1\ndata: a\n\ndata: b\n\nid: 2\ndata: c\n\nid\ndata: d\n\n"))).map {
                    (events, ended) =>
                        assert(events.map(_.data) == Chunk("a", "b", "c", "d"), s"observed $events")
                        assert(
                            events.map(_.id) == Chunk(Present("1"), Present("1"), Present("2"), Present("")),
                            s"observed ${events.map(_.id)}"
                        )
                        assert(ended == Result.unit, s"observed $ended")
                }
            }

            "one byte order mark before the first line is skipped, split across spans as well; a second one is part of the field name" in {
                val bom  = Array[Byte](0xef.toByte, 0xbb.toByte, 0xbf.toByte)
                val text = "data: x\n\n".getBytes("UTF-8")
                def stream(pieces: Array[Byte]*): Stream[Span[Byte], Async & Abort[HttpException]] =
                    Stream.init(pieces.map(Span.fromUnsafe))
                collect(responseBody(sseText, stream(bom ++ text))).map { (whole, _) =>
                    assert(whole.map(_.data) == Chunk("x"), s"observed $whole")
                    collect(responseBody(sseText, stream(bom.take(2), bom.drop(2) ++ text))).map { (split, _) =>
                        assert(split.map(_.data) == Chunk("x"), s"observed $split")
                        collect(responseBody(sseText, stream(bom ++ bom ++ text))).map { (twice, ended) =>
                            assert(twice.isEmpty, s"observed $twice")
                            assert(ended == Result.unit, s"observed $ended")
                        }
                    }
                }
            }
        }

        "a line over the 16 MiB bound" - {
            val bound    = 16 * 1024 * 1024
            val oversize = Span.fromUnsafe(Array.fill[Byte](bound + 1)('x'.toByte))
            def oversized(first: String): Stream[Span[Byte], Async & Abort[HttpException]] =
                Stream.init(Seq(Span.fromUnsafe(first.getBytes("UTF-8")), oversize))
            val tooLarge = Result.fail(HttpPayloadTooLargeException((bound + 1).bytes, bound.bytes))

            "an NDJSON record over the bound fails the stream after the records before it" in {
                collect(responseBody(ndjson, oversized("{\"name\":\"Alice\",\"age\":30}\n"))).map { (users, ended) =>
                    assert(users == Chunk(User("Alice", 30)), s"observed $users")
                    assert(ended == tooLarge, s"observed $ended")
                }
            }

            "an SSE line over the bound fails the stream after the events before it" in {
                collect(responseBody(sseText, oversized("data: a\n\n"))).map { (events, ended) =>
                    assert(events.map(_.data) == Chunk("a"), s"observed $events")
                    assert(ended == tooLarge, s"observed $ended")
                }
            }

            val multipartRoute   = HttpRoute.postRaw("upload").request(_.bodyMultipartStream)
            val multipartHeaders = HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=b")
            val partBound        = 1024
            def multipartPartsOf(
                stream: Stream[Span[Byte], Async & Abort[HttpException]],
                maxPartSize: Int = readBufferCapacity(HttpServerConfig.default.maxMultipartPartSize)
            )(using Frame, kyo.test.AssertScope) =
                RouteUtil.decodeStreamingRequest(
                    multipartRoute,
                    Dict.empty[String, String],
                    Absent,
                    multipartHeaders,
                    stream,
                    maxPartSize
                ) match
                    case Result.Success(request) => collect(request.fields.body)
                    case other                   => fail(s"decode failed: $other")
            def multipartParts(first: String)(using Frame, kyo.test.AssertScope) = multipartPartsOf(oversized(first))

            "under the default part bound a part over 16 MiB is delivered whole" in {
                val head  = "--b\r\nContent-Disposition: form-data; name=\"g\"\r\n\r\n".getBytes("UTF-8")
                val close = "\r\n--b--\r\n".getBytes("UTF-8")
                multipartPartsOf(Stream.init(Seq(Span.fromUnsafe(head), oversize, Span.fromUnsafe(close)))).map { (parts, ended) =>
                    assert(parts.map(_.data.size) == Chunk(bound + 1) && ended == Result.unit, s"observed ${parts.map(_.data.size)} $ended")
                }
            }

            // The part bound is exact: the first bytes of a delimiter split across spans are held with the part until the rest arrives
            // and are not counted against it, and a part the body ends inside is bounded the same way.
            "a multipart part of exactly the part bound is delivered and one byte more fails, its delimiter split across spans or absent" in {
                val headersG                         = "Content-Disposition: form-data; name=\"g\"\r\n\r\n"
                val exact                            = partBound - headersG.length
                def body(dataSize: Int): Array[Byte] = ("--b\r\n" + headersG).getBytes("UTF-8") ++ Array.fill[Byte](dataSize)('x'.toByte)
                val close                            = "\r\n--b--\r\n".getBytes("UTF-8")
                def delimited(dataSize: Int): Stream[Span[Byte], Async & Abort[HttpException]] =
                    val bytes = body(dataSize) ++ close
                    val cut   = body(dataSize).length + 3
                    Stream.init(Seq(Span.fromUnsafe(bytes.take(cut)), Span.fromUnsafe(bytes.drop(cut))))
                end delimited
                def unterminated(dataSize: Int): Stream[Span[Byte], Async & Abort[HttpException]] =
                    Stream.init(Seq(Span.fromUnsafe(body(dataSize))))
                val oneMore = Result.fail(HttpPayloadTooLargeException((partBound + 1).bytes, partBound.bytes))
                multipartPartsOf(delimited(exact), partBound).map { (parts, ended) =>
                    assert(parts.map(_.data.size) == Chunk(exact) && ended == Result.unit, s"observed ${parts.map(_.data.size)} $ended")
                    multipartPartsOf(delimited(exact + 1), partBound).map { (parts, ended) =>
                        assert(parts.isEmpty && ended == oneMore, s"observed ${parts.map(_.data.size)} $ended")
                        multipartPartsOf(unterminated(exact), partBound).map { (parts, ended) =>
                            assert(
                                parts.map(_.data.size) == Chunk(exact) && ended == Result.unit,
                                s"observed ${parts.map(_.data.size)} $ended"
                            )
                            multipartPartsOf(unterminated(exact + 1), partBound).map { (parts, ended) =>
                                assert(parts.isEmpty && ended == oneMore, s"observed ${parts.map(_.data.size)} $ended")
                            }
                        }
                    }
                }
            }

            "a multipart boundary line over the bound fails the stream after the parts before it" in {
                multipartParts("--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nd\r\n--b").map { (parts, ended) =>
                    assert(parts.map(_.name) == Chunk("f"), s"observed $parts")
                    assert(ended == tooLarge, s"observed $ended")
                }
            }

            // A part is delivered whole, as one span, so it is held until its delimiter; its size counts its header lines, which are
            // part of the section.
            "a multipart part over the part bound fails the stream after the parts before it" in {
                val headersG = "Content-Disposition: form-data; name=\"g\"\r\n\r\n"
                val first    = "--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nd\r\n--b\r\n" + headersG
                val data     = Span.fromUnsafe(Array.fill[Byte](partBound)('x'.toByte))
                multipartPartsOf(Stream.init(Seq(Span.fromUnsafe(first.getBytes("UTF-8")), data)), partBound).map { (parts, ended) =>
                    assert(parts.map(_.name) == Chunk("f"), s"observed $parts")
                    assert(
                        ended == Result.fail(HttpPayloadTooLargeException((headersG.length + partBound).bytes, partBound.bytes)),
                        s"observed $ended"
                    )
                }
            }
        }

        "multipart" - {
            val route   = HttpRoute.postRaw("upload").request(_.bodyMultipartStream)
            val headers = HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=b")

            def parts(stream: Stream[Span[Byte], Async & Abort[HttpException]])(using
                Frame,
                kyo.test.AssertScope
            ): Chunk[HttpRequest.Part] < (Async & Abort[HttpException]) =
                RouteUtil.decodeStreamingRequest(route, Dict.empty[String, String], Absent, headers, stream, maxPartSize) match
                    case Result.Success(request) => request.fields.body.run
                    case Result.Failure(err)     => fail(s"decode failed: $err")
                    case p: Result.Panic         => throw p.exception

            "a final part the body ends inside keeps a CRLF at the end of its data, which no delimiter follows" in {
                val body = "--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nab\r\n".getBytes("UTF-8")
                parts(Stream.init(Seq(Span.fromUnsafe(body)))).map { ps =>
                    val observed = ps.map(p => (p.name, new String(p.data.toArrayUnsafe, "UTF-8")))
                    assert(observed == Chunk(("f", "ab\r\n")), s"observed $observed")
                }
            }

            "part data with every byte value survives framing across span boundaries" in {
                val data = Array.tabulate[Byte](256)(i => i.toByte)
                val head = "--b\r\nContent-Disposition: form-data; name=\"bin\"\r\n\r\n".getBytes("UTF-8")
                val tail = "\r\n--b--\r\n".getBytes("UTF-8")
                val body = head ++ data ++ tail
                val stream: Stream[Span[Byte], Async & Abort[HttpException]] =
                    Stream.init(body.grouped(37).map(Span.fromUnsafe).toSeq)
                parts(stream).map { ps =>
                    assert(ps.size == 1 && ps(0).name == "bin", s"observed $ps")
                    assert(ps(0).data.toArrayUnsafe.toSeq == data.toSeq, "the bytes must arrive unchanged")
                }
            }

            "a boundary string inside part data is not a delimiter unless it follows CRLF" in {
                val body =
                    "--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nx--b y\r\n--b\r\nContent-Disposition: form-data; name=\"g\"\r\n\r\nz\r\n--b--\r\n"
                parts(spans(body)).map { ps =>
                    assert(ps.map(_.name) == Chunk("f", "g"), s"observed $ps")
                    assert(new String(ps(0).data.toArrayUnsafe, "UTF-8") == "x--b y", s"observed ${ps(0)}")
                    assert(new String(ps(1).data.toArrayUnsafe, "UTF-8") == "z")
                }
            }

            "a preamble and an epilogue are ignored, and a delimiter split across spans is found" in {
                val body =
                    "preamble text\r\n--b\r\nContent-Disposition: form-data; name=\"p1\"\r\n\r\nd1\r\n--b\r\nContent-Disposition: form-data; name=\"p2\"\r\n\r\nd2\r\n--b--\r\nepilogue"
                val bytes                                                    = body.getBytes("UTF-8")
                val cut                                                      = body.indexOf("d1\r\n--b") + 4
                val stream: Stream[Span[Byte], Async & Abort[HttpException]] =
                    Stream.init(Seq(Span.fromUnsafe(bytes.take(cut)), Span.fromUnsafe(bytes.drop(cut))))
                parts(stream).map { ps =>
                    assert(ps.map(_.name) == Chunk("p1", "p2"), s"observed $ps")
                    assert(ps.map(p => new String(p.data.toArrayUnsafe, "UTF-8")) == Chunk("d1", "d2"), s"observed $ps")
                }
            }
        }
    }

    // ==================== Multipart buffered decoding ====================

    "multipart buffered decoding" - {
        "decodeBufferedRequest parses multipart body" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            val body  =
                "------TestBoundary123\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\nContent-Type: text/plain\r\n\r\nhello world\r\n------TestBoundary123\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue123\r\n------TestBoundary123--\r\n"
            val bytes   = Span.fromUnsafe(body.getBytes("UTF-8"))
            val headers = HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=----TestBoundary123")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, bytes) match
                case Result.Success(request) =>
                    val parts = request.fields.dict("body").asInstanceOf[Seq[HttpRequest.Part]]
                    assert(parts.size == 2)
                    assert(parts(0).name == "file")
                    assert(parts(0).filename == Present("test.txt"))
                    assert(parts(0).contentType == Present("text/plain"))
                    assert(new String(parts(0).data.toArrayUnsafe, "UTF-8") == "hello world")
                    assert(parts(1).name == "field")
                    assert(parts(1).filename == Absent)
                    assert(new String(parts(1).data.toArrayUnsafe, "UTF-8") == "value123")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "decodeBufferedRequest accepts a quoted case-insensitive boundary after another parameter" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            val body  =
                "--abc:def\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--abc:def--\r\n"
            val bytes   = Span.fromUnsafe(body.getBytes("UTF-8"))
            val headers =
                HttpHeaders.empty.add("Content-Type", "multipart/form-data; charset=utf-8; Boundary=\"abc:def\"")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, bytes) match
                case Result.Success(request) =>
                    val parts = request.fields.dict("body").asInstanceOf[Seq[HttpRequest.Part]]
                    assert(parts.size == 1)
                    assert(parts.head.name == "field")
                    assert(new String(parts.head.data.toArrayUnsafe, "UTF-8") == "value")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "decodeBufferedRequest rejects a quoted boundary containing a parameter separator" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            val body  =
                "--abc\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--abc--\r\n"
            val bytes   = Span.fromUnsafe(body.getBytes("UTF-8"))
            val headers =
                HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=\"abc;def\"")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, bytes) match
                case Result.Failure(_: HttpMissingBoundaryException) => succeed
                case other => fail(s"expected an invalid quoted boundary to fail with HttpMissingBoundaryException, got $other")
            end match
        }

        "decodeBufferedRequest rejects an unquoted boundary containing a MIME tspecial" in {
            val route   = HttpRoute.postRaw("upload").request(_.bodyMultipart)
            val body    = "--abc:def\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--abc:def--\r\n"
            val bytes   = Span.fromUnsafe(body.getBytes("UTF-8"))
            val headers = HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=abc:def")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, bytes) match
                case Result.Failure(_: HttpMissingBoundaryException) => succeed
                case other => fail(s"expected an unquoted MIME tspecial to fail with HttpMissingBoundaryException, got $other")
            end match
        }
    }

    "multipart streaming decoding" - {
        "decodeStreamingRequest accepts a quoted case-insensitive boundary after another parameter" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyMultipartStream)
            val body  =
                "--abc:def\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--abc:def--\r\n"
            val stream  = Stream.init[Span[Byte], Async](Seq(Span.fromUnsafe(body.getBytes("UTF-8"))))
            val headers =
                HttpHeaders.empty.add("Content-Type", "multipart/form-data; charset=utf-8; Boundary=\"abc:def\"")

            RouteUtil.decodeStreamingRequest(route, Dict.empty[String, String], Absent, headers, stream, maxPartSize) match
                case Result.Success(request) =>
                    request.fields.body.run.map { parts =>
                        assert(parts.size == 1)
                        assert(parts.head.name == "field")
                        assert(new String(parts.head.data.toArrayUnsafe, "UTF-8") == "value")
                    }
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "decodeStreamingRequest rejects a quoted boundary with invalid trailing whitespace" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyMultipartStream)
            val body  =
                "--abc\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--abc--\r\n"
            val stream  = Stream.init[Span[Byte], Async](Seq(Span.fromUnsafe(body.getBytes("UTF-8"))))
            val headers =
                HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=\"abc \"")

            RouteUtil.decodeStreamingRequest(route, Dict.empty[String, String], Absent, headers, stream, maxPartSize) match
                case Result.Failure(_: HttpMissingBoundaryException) => succeed
                case other => fail(s"expected an invalid quoted boundary to fail with HttpMissingBoundaryException, got $other")
            end match
        }

        "decodeStreamingRequest rejects an unquoted boundary containing whitespace" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyMultipartStream)
            val body  =
                "--abc def\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--abc def--\r\n"
            val stream  = Stream.init[Span[Byte], Async](Seq(Span.fromUnsafe(body.getBytes("UTF-8"))))
            val headers = HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=abc def")

            RouteUtil.decodeStreamingRequest(route, Dict.empty[String, String], Absent, headers, stream, maxPartSize) match
                case Result.Failure(_: HttpMissingBoundaryException) => succeed
                case other => fail(s"expected unquoted whitespace to fail with HttpMissingBoundaryException, got $other")
            end match
        }
    }

    // ==================== NDJSON line splitting ====================

    "NDJSON line splitting" - {
        "handles multiple lines in one chunk" in {
            val route     = HttpRoute.getRaw("events").response(_.bodyNdjson[User])
            val combined  = "{\"name\":\"Alice\",\"age\":30}\n{\"name\":\"Bob\",\"age\":25}\n"
            val rawStream = kyo.Stream.init(Seq(
                Span.fromUnsafe(combined.getBytes("UTF-8"))
            ))

            RouteUtil.decodeStreamingResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                rawStream,
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(response) =>
                    val userStream = response.fields.body
                    userStream.run.map { users =>
                        val us = users.toSeq
                        assert(us.size == 2)
                        assert(us(0) == User("Alice", 30))
                        assert(us(1) == User("Bob", 25))
                    }
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "handles line split across chunks" in {
            val route     = HttpRoute.getRaw("events").response(_.bodyNdjson[User])
            val part1     = "{\"name\":\"Ali"
            val part2     = "ce\",\"age\":30}\n"
            val rawStream = kyo.Stream.init(Seq(
                Span.fromUnsafe(part1.getBytes("UTF-8")),
                Span.fromUnsafe(part2.getBytes("UTF-8"))
            ))

            RouteUtil.decodeStreamingResponse(
                route,
                HttpStatus.OK,
                HttpHeaders.empty,
                rawStream,
                route.method.name,
                HttpUrl.fromUri("/test")
            ) match
                case Result.Success(response) =>
                    val userStream = response.fields.body
                    userStream.run.map { users =>
                        val us = users.toSeq
                        assert(us.size == 1)
                        assert(us(0) == User("Alice", 30))
                    }
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== Multipart streaming closing boundary ====================

    "multipart streaming encoding" - {
        "includes closing boundary" in {
            val route = HttpRoute.postRaw("upload").request(_.bodyMultipartStream)
            val parts: kyo.Stream[HttpRequest.Part, kyo.Async & Abort[HttpException]] = kyo.Stream.init(Seq(
                HttpRequest.Part("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8")))
            ))
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/upload").getOrThrow,
                HttpHeaders.empty,
                Record.empty
            ).addField("body", parts)

            var stream: kyo.Stream[Span[Byte], kyo.Async & Abort[HttpException]] = null
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected streaming"),
                onBuffered = (_, _, _) => fail("expected streaming"),
                onStreaming = (_, _, s) => stream = s
            ).map { _ =>
                stream.run.map { chunks =>
                    val all = chunks.toSeq.map(span => new String(span.toArrayUnsafe, "UTF-8")).mkString
                    // Must end with closing boundary
                    assert(all.contains("--"), "should contain boundary markers")
                    val lastBoundaryIdx = all.lastIndexOf("--")
                    assert(all.substring(lastBoundaryIdx - 2).contains("--\r\n"), "should end with closing boundary --boundary--")
                }
            }
        }
    }

    // ==================== buildRequest URL preservation ====================

    "server-side request URL preservation" - {
        "decodeBufferedRequest preserves path" in {
            val route = HttpRoute.getRaw("users")

            RouteUtil.decodeBufferedRequest(
                route,
                Dict.empty[String, String],
                Absent,
                HttpHeaders.empty,
                Span.empty[Byte],
                "/users"
            ) match
                case Result.Success(request) =>
                    assert(request.path != "", "request.path should not be empty")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

end RouteUtilTest
