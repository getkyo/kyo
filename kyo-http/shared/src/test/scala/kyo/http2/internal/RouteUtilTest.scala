package kyo.http2.internal

import kyo.Absent
import kyo.Maybe
import kyo.Maybe.Present
import kyo.Record2
import kyo.Result
import kyo.Span
import kyo.http2.HttpCodec
import kyo.http2.HttpHeaders
import kyo.http2.HttpMethod
import kyo.http2.HttpPath
import kyo.http2.HttpRequest
import kyo.http2.HttpResponse
import kyo.http2.HttpRoute
import kyo.http2.HttpStatus
import kyo.http2.HttpUrl
import kyo.http2.Schema

class RouteUtilTest extends kyo.Test:

    override protected def useTestClient: Boolean = false

    import HttpPath./

    given CanEqual[Any, Any] = CanEqual.derived

    case class User(name: String, age: Int) derives Schema, CanEqual

    // ==================== Route inspection ====================

    "isStreamingRequest" - {
        "false for no body" in {
            val route = HttpRoute.get("users")
            assert(!RouteUtil.isStreamingRequest(route))
        }
        "false for json body" in {
            val route = HttpRoute.post("users").request(_.bodyJson[User])
            assert(!RouteUtil.isStreamingRequest(route))
        }
        "false for text body" in {
            val route = HttpRoute.post("echo").request(_.bodyText)
            assert(!RouteUtil.isStreamingRequest(route))
        }
        "true for byte stream body" in {
            val route = HttpRoute.post("upload").request(_.bodyStream)
            assert(RouteUtil.isStreamingRequest(route))
        }
        "true for ndjson body" in {
            val route = HttpRoute.post("events").request(_.bodyNdjson[User])
            assert(RouteUtil.isStreamingRequest(route))
        }
    }

    "isStreamingResponse" - {
        "false for no body" in {
            val route = HttpRoute.get("health")
            assert(!RouteUtil.isStreamingResponse(route))
        }
        "false for json body" in {
            val route = HttpRoute.get("users").response(_.bodyJson[User])
            assert(!RouteUtil.isStreamingResponse(route))
        }
        "true for byte stream body" in {
            val route = HttpRoute.get("download").response(_.bodyStream)
            assert(RouteUtil.isStreamingResponse(route))
        }
        "true for sse body" in {
            val route = HttpRoute.get("events").response(_.bodySse[User])
            assert(RouteUtil.isStreamingResponse(route))
        }
    }

    // ==================== encodeRequest ====================

    "encodeRequest" - {
        "empty body" in {
            val route   = HttpRoute.get("users")
            val request = HttpRequest(HttpMethod.GET, HttpUrl.parse("http://localhost/users").getOrThrow, HttpHeaders.empty, Record2.empty)

            var result: (String, HttpHeaders) = null
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (url, headers) => result = (url, headers),
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(result._1 == "/users")
        }

        "json body" in {
            val route = HttpRoute.post("users").request(_.bodyJson[User])
            val request = HttpRequest(HttpMethod.POST, HttpUrl.parse("http://localhost/users").getOrThrow, HttpHeaders.empty, Record2.empty)
                .addField("body", User("Alice", 30))

            var contentType = ""
            var body        = Span.empty[Byte]
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (url, headers, ct, bytes) =>
                    contentType = ct
                    body = bytes
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
            assert(contentType == "application/json")
            val bodyStr = new String(body.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
            assert(bodyStr.contains("Alice"))
            assert(bodyStr.contains("30"))
        }

        "text body" in {
            val route = HttpRoute.post("echo").request(_.bodyText)
            val request = HttpRequest(HttpMethod.POST, HttpUrl.parse("http://localhost/echo").getOrThrow, HttpHeaders.empty, Record2.empty)
                .addField("body", "hello world")

            var contentType = ""
            var bodyStr     = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (url, headers, ct, bytes) =>
                    contentType = ct
                    bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
            assert(contentType == "text/plain; charset=utf-8")
            assert(bodyStr == "hello world")
        }

        "path captures" in {
            val route = HttpRoute.get("users" / HttpPath.Capture[Int]("userId") / "posts")
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users/42/posts").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("userId", 42)

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(url == "/users/42/posts")
        }

        "query params" in {
            val route = HttpRoute.get("users").request(_.query[Int]("page").query[String]("sort"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("page", 2).addField("sort", "name")

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(url.contains("page=2"))
            assert(url.contains("sort=name"))
        }

        "header params" in {
            val route = HttpRoute.get("data").request(_.header[String]("apiKey", wireName = "X-Api-Key"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("apiKey", "secret123")

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(headers.get("X-Api-Key") == Present("secret123"))
        }

        "cookie params" in {
            val route = HttpRoute.get("data").request(_.cookie[String]("session"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("session", "abc123")

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(headers.get("Cookie") == Present("session=abc123"))
        }

        "optional param present" in {
            val route = HttpRoute.get("users").request(_.queryOpt[Int]("page"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("page", Present(5): Maybe[Int])

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(url.contains("page=5"))
        }

        "optional param absent" in {
            val route = HttpRoute.get("users").request(_.queryOpt[Int]("page"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("page", Absent: Maybe[Int])

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(!url.contains("page"))
        }
    }

    // ==================== decodeBufferedResponse ====================

    "decodeBufferedResponse" - {
        "json body" in {
            val route = HttpRoute.get("users").response(_.bodyJson[User])
            val json  = """{"name":"Bob","age":25}"""
            val bytes = Span.fromUnsafe(json.getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, HttpHeaders.empty, bytes) match
                case Result.Success(response) =>
                    assert(response.status == HttpStatus.OK)
                    val user = response.fields.body
                    assert(user == User("Bob", 25))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "text body" in {
            val route = HttpRoute.get("echo").response(_.bodyText)
            val bytes = Span.fromUnsafe("hello".getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, HttpHeaders.empty, bytes) match
                case Result.Success(response) =>
                    assert(response.fields.body == "hello")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "with response headers" in {
            val route   = HttpRoute.get("data").response(_.header[String]("requestId", wireName = "X-Request-Id").bodyText)
            val headers = HttpHeaders.empty.add("X-Request-Id", "req-123")
            val bytes   = Span.fromUnsafe("ok".getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, headers, bytes) match
                case Result.Success(response) =>
                    assert(response.fields.body == "ok")
                    assert(response.fields.requestId == "req-123")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "missing required header fails" in {
            val route = HttpRoute.get("data").response(_.header[String]("requestId", wireName = "X-Request-Id").bodyText)
            val bytes = Span.fromUnsafe("ok".getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, HttpHeaders.empty, bytes) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Missing required param"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "no body" in {
            val route = HttpRoute.get("health")

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, HttpHeaders.empty, Span.empty[Byte]) match
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
            val route    = HttpRoute.post("users" / HttpPath.Capture[Int]("userId")).request(_.bodyJson[User])
            val captures = Map("userId" -> "42")
            val json     = """{"name":"Alice","age":30}"""
            val bytes    = Span.fromUnsafe(json.getBytes("UTF-8"))

            RouteUtil.decodeBufferedRequest(route, captures, _ => Absent, HttpHeaders.empty, bytes) match
                case Result.Success(request) =>
                    assert(request.fields.userId == 42)
                    assert(request.fields.body == User("Alice", 30))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "query params" in {
            val route = HttpRoute.get("users").request(_.query[Int]("page").query[String]("sort"))
            val queryFn = (name: String) =>
                name match
                    case "page" => Present("2")
                    case "sort" => Present("name")
                    case _      => Absent

            RouteUtil.decodeBufferedRequest(route, Map.empty, queryFn, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(request) =>
                    assert(request.fields.page == 2)
                    assert(request.fields.sort == "name")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional query param absent" in {
            val route = HttpRoute.get("users").request(_.queryOpt[Int]("page"))

            RouteUtil.decodeBufferedRequest(route, Map.empty, _ => Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(request) =>
                    assert(request.fields.page == Absent)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "default param value" in {
            val route = HttpRoute.get("users").request(_.query[Int]("page", default = Present(1)))

            RouteUtil.decodeBufferedRequest(route, Map.empty, _ => Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(request) =>
                    assert(request.fields.page == 1)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "missing required param fails" in {
            val route = HttpRoute.get("users").request(_.query[Int]("page"))

            RouteUtil.decodeBufferedRequest(route, Map.empty, _ => Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Missing required param"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "missing path capture fails" in {
            val route = HttpRoute.get("users" / HttpPath.Capture[Int]("userId"))

            RouteUtil.decodeBufferedRequest(route, Map.empty, _ => Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Missing path capture"))
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== encodeResponse ====================

    "encodeResponse" - {
        "json body" in {
            val route    = HttpRoute.get("users").response(_.bodyJson[User])
            val response = HttpResponse(HttpStatus.OK, HttpHeaders.empty, Record2.empty).addField("body", User("Bob", 25))

            var contentType = ""
            var bodyStr     = ""
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (status, headers, ct, bytes) =>
                    contentType = ct
                    bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
            assert(contentType == "application/json")
            assert(bodyStr.contains("Bob"))
        }

        "empty body" in {
            val route    = HttpRoute.get("health")
            val response = HttpResponse(HttpStatus.OK, HttpHeaders.empty, Record2.empty)

            var status: HttpStatus = null
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (s, _) => status = s,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(status == HttpStatus.OK)
        }

        "with response header params" in {
            val route = HttpRoute.get("data").response(_.header[String]("requestId", wireName = "X-Request-Id").bodyText)
            val response = HttpResponse(HttpStatus.OK, HttpHeaders.empty, Record2.empty)
                .addField("requestId", "req-456")
                .addField("body", "ok")

            var headers = HttpHeaders.empty
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, h, _, _) => headers = h,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
            assert(headers.get("X-Request-Id") == Present("req-456"))
        }
    }

    // ==================== matchError ====================

    "matchError" - {
        "matches error status" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].error[String](HttpStatus.BadRequest))
            val body = Span.fromUnsafe("\"invalid input\"".getBytes("UTF-8"))

            RouteUtil.matchError(route, HttpStatus.BadRequest, body) match
                case Present(err) => assert(err == "invalid input")
                case Absent       => fail("expected error match")
        }

        "no match for success status" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].error[String](HttpStatus.BadRequest))
            val body = Span.fromUnsafe("\"ok\"".getBytes("UTF-8"))

            assert(RouteUtil.matchError(route, HttpStatus.OK, body) == Absent)
        }

        "no match when no error mappings" in {
            val route = HttpRoute.get("users").response(_.bodyJson[User])
            val body  = Span.fromUnsafe("\"err\"".getBytes("UTF-8"))

            assert(RouteUtil.matchError(route, HttpStatus.BadRequest, body) == Absent)
        }
    }

    // ==================== Round-trip ====================

    "round-trip" - {
        "encode request then decode as server" in {
            val route = HttpRoute.post("users" / HttpPath.Capture[Int]("userId"))
                .request(_.query[String]("action").header[String]("auth", wireName = "Authorization").bodyJson[User])

            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/users/42").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            )
                .addField("userId", 42)
                .addField("action", "create")
                .addField("auth", "Bearer token123")
                .addField("body", User("Alice", 30))

            // Encode
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (url, headers, ct, bytes) =>
                    // Verify encoded URL contains query
                    assert(url.contains("action=create"))

                    // Verify Authorization header
                    assert(headers.get("Authorization") == Present("Bearer token123"))

                    // Now decode as if we're the server
                    val queryFn = (name: String) =>
                        if name == "action" then Present("create")
                        else Absent

                    RouteUtil.decodeBufferedRequest(
                        route,
                        Map("userId" -> "42"),
                        queryFn,
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
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
        }

        "encode response then decode as client" in {
            val route = HttpRoute.get("users")
                .response(_.header[String]("requestId", wireName = "X-Request-Id").bodyJson[User])

            val response = HttpResponse(HttpStatus.OK, HttpHeaders.empty, Record2.empty)
                .addField("requestId", "req-789")
                .addField("body", User("Bob", 25))

            // Encode as server
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (status, headers, ct, bytes) =>
                    // Decode as client
                    RouteUtil.decodeBufferedResponse(route, status, headers, bytes) match
                        case Result.Success(decoded) =>
                            assert(decoded.status == HttpStatus.OK)
                            assert(decoded.fields.dict("requestId") == "req-789")
                            assert(decoded.fields.dict("body") == User("Bob", 25))
                        case Result.Failure(err) => fail(s"decode failed: $err")
                        case p: Result.Panic     => throw p.exception
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
        }
    }

end RouteUtilTest
