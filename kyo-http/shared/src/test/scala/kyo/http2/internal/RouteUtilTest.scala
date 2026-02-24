package kyo.http2.internal

import kyo.Absent
import kyo.Dict
import kyo.Maybe
import kyo.Maybe.Present
import kyo.Record2
import kyo.Result
import kyo.Span
import kyo.http2.HttpCodec
import kyo.http2.HttpCookie
import kyo.http2.HttpEvent
import kyo.http2.HttpFormCodec
import kyo.http2.HttpHeaders
import kyo.http2.HttpMethod
import kyo.http2.HttpPart
import kyo.http2.HttpPath
import kyo.http2.HttpRequest
import kyo.http2.HttpResponse
import kyo.http2.HttpRoute
import kyo.http2.HttpStatus
import kyo.http2.HttpUrl
import kyo.http2.Schema
import kyo.millis

class RouteUtilTest extends kyo.Test:

    override protected def useTestClient: Boolean = false

    import HttpPath./

    given CanEqual[Any, Any] = CanEqual.derived

    case class User(name: String, age: Int) derives Schema, CanEqual
    case class LoginForm(username: String, password: String) derives HttpFormCodec

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
            val route = HttpRoute.get("events").response(_.bodySseJson[User])
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
            val route      = HttpRoute.get("users").request(_.query[Int]("page").query[String]("sort"))
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
            val route = HttpRoute.get("users").request(_.queryOpt[Int]("page"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(request) =>
                    assert(request.fields.page == Absent)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "default param value" in {
            val route = HttpRoute.get("users").request(_.query[Int]("page", default = Present(1)))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(request) =>
                    assert(request.fields.page == 1)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "missing required param fails" in {
            val route = HttpRoute.get("users").request(_.query[Int]("page"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Missing required param"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "missing path capture fails" in {
            val route = HttpRoute.get("users" / HttpPath.Capture[Int]("userId"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
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

    // ==================== encodeRequest edge cases ====================

    "encodeRequest edge cases" - {
        "body + query + header combined" in {
            val route = HttpRoute.post("items")
                .request(_.query[Int]("page").header[String]("auth", wireName = "Authorization").bodyJson[User])
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/items").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("page", 1).addField("auth", "Bearer tok").addField("body", User("X", 1))

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (url, headers, ct, bytes) =>
                    assert(url.contains("page=1"))
                    assert(headers.get("Authorization") == Present("Bearer tok"))
                    assert(ct == "application/json")
                    val bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                    assert(bodyStr.contains("X"))
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
        }

        "multiple cookies" in {
            val route = HttpRoute.get("data")
                .request(_.cookie[String]("session").cookie[String]("theme"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("session", "abc").addField("theme", "dark")

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            val cookie = headers.get("Cookie")
            assert(cookie.isDefined)
            val cookieStr = cookie.getOrElse("")
            assert(cookieStr.contains("session=abc"))
            assert(cookieStr.contains("theme=dark"))
            assert(cookieStr.contains("; "))
        }

        "URL-encoded special characters in query" in {
            val route = HttpRoute.get("search").request(_.query[String]("q"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/search").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("q", "hello world&foo=bar")

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(!url.contains(" "))
            assert(url.contains("q="))
            // Decoded value should round-trip
            val encoded = url.split("q=")(1)
            assert(java.net.URLDecoder.decode(encoded, "UTF-8") == "hello world&foo=bar")
        }

        "URL-encoded special characters in path capture" in {
            val route = HttpRoute.get("users" / HttpPath.Capture[String]("name"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/users/x").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("name", "John Doe")

            var url = ""
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (u, _) => url = u,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(!url.contains(" "))
            assert(url.startsWith("/users/"))
        }

        "preserves existing request headers" in {
            val route           = HttpRoute.get("data").request(_.header[String]("extra", wireName = "X-Extra"))
            val existingHeaders = HttpHeaders.empty.add("X-Existing", "keep-me")
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                existingHeaders,
                Record2.empty
            ).addField("extra", "new-val")

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(headers.get("X-Existing") == Present("keep-me"))
            assert(headers.get("X-Extra") == Present("new-val"))
        }

        "optional header absent" in {
            val route = HttpRoute.get("data").request(_.headerOpt[String]("auth", wireName = "Authorization"))
            val request = HttpRequest(
                HttpMethod.GET,
                HttpUrl.parse("http://localhost/data").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("auth", Absent: Maybe[String])

            var headers = HttpHeaders.empty
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, _, _, _) => fail("expected empty"),
                onStreaming = (_, _, _, _) => fail("expected empty")
            )
            assert(!headers.contains("Authorization"))
        }

        "binary body" in {
            val route = HttpRoute.post("upload").request(_.bodyBinary)
            val data  = Span.fromUnsafe(Array[Byte](1, 2, 3, 4))
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/upload").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("body", data)

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, _, ct, bytes) =>
                    assert(ct == "application/octet-stream")
                    assert(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]].toSeq == Seq[Byte](1, 2, 3, 4))
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
        }

        "form body" in {
            val route = HttpRoute.post("login").request(_.bodyForm[LoginForm])
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/login").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("body", LoginForm("alice", "secret"))

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, _, ct, bytes) =>
                    assert(ct == "application/x-www-form-urlencoded")
                    val bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                    assert(bodyStr.contains("username=alice"))
                    assert(bodyStr.contains("password=secret"))
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
        }
    }

    // ==================== decodeBufferedRequest edge cases ====================

    "decodeBufferedRequest edge cases" - {
        "header params" in {
            val route   = HttpRoute.get("data").request(_.header[String]("auth", wireName = "Authorization"))
            val headers = HttpHeaders.empty.add("Authorization", "Bearer xyz")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("auth") == "Bearer xyz")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "cookie params" in {
            val route   = HttpRoute.get("data").request(_.cookie[String]("session"))
            val headers = HttpHeaders.empty.add("Cookie", "session=abc123; theme=dark")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("session") == "abc123")
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional header present" in {
            val route   = HttpRoute.get("data").request(_.headerOpt[String]("auth", wireName = "Authorization"))
            val headers = HttpHeaders.empty.add("Authorization", "Bearer xyz")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("auth") == Present("Bearer xyz"))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional header absent" in {
            val route = HttpRoute.get("data").request(_.headerOpt[String]("auth", wireName = "Authorization"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict("auth") == Absent)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "invalid int capture fails with parse error" in {
            val route = HttpRoute.get("users" / HttpPath.Capture[Int]("userId"))

            RouteUtil.decodeBufferedRequest(route, Dict("userId" -> "notanumber"), Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Failed to decode path capture"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "invalid int query param fails with parse error" in {
            val route = HttpRoute.get("users").request(_.query[Int]("page"))

            RouteUtil.decodeBufferedRequest(
                route,
                Dict.empty[String, String],
                Present(HttpUrl.fromUri("/?page=abc")),
                HttpHeaders.empty,
                Span.empty[Byte]
            ) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("Failed to decode param"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "default param ignored when value present" in {
            val route = HttpRoute.get("users").request(_.query[Int]("page", default = Present(1)))

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
            val route = HttpRoute.post("users").request(_.bodyJson[User])
            val bytes = Span.fromUnsafe("not valid json".getBytes("UTF-8"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, bytes) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("JSON decode failed"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "binary body" in {
            val route = HttpRoute.post("upload").request(_.bodyBinary)
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
            val route = HttpRoute.post("login").request(_.bodyForm[LoginForm])
            val bytes = Span.fromUnsafe("username=alice&password=secret".getBytes("UTF-8"))

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, bytes) match
                case Result.Success(req) =>
                    assert(req.fields.dict("body") == LoginForm("alice", "secret"))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "empty body for no-body route" in {
            val route = HttpRoute.get("health")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, Span.empty[Byte]) match
                case Result.Success(req) =>
                    assert(req.fields.dict.isEmpty)
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== decodeBufferedResponse edge cases ====================

    "decodeBufferedResponse edge cases" - {
        "invalid json body fails" in {
            val route = HttpRoute.get("users").response(_.bodyJson[User])
            val bytes = Span.fromUnsafe("{invalid".getBytes("UTF-8"))

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, HttpHeaders.empty, bytes) match
                case Result.Success(_)   => fail("expected failure")
                case Result.Failure(err) => assert(err.getMessage.contains("JSON decode failed"))
                case p: Result.Panic     => throw p.exception
            end match
        }

        "binary body" in {
            val route = HttpRoute.get("download").response(_.bodyBinary)
            val data  = Span.fromUnsafe(Array[Byte](5, 6, 7))

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, HttpHeaders.empty, data) match
                case Result.Success(resp) =>
                    val decoded = resp.fields.dict("body").asInstanceOf[Span[Byte]]
                    assert(decoded.toArrayUnsafe.asInstanceOf[Array[Byte]].toSeq == Seq[Byte](5, 6, 7))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional response header present" in {
            val route   = HttpRoute.get("data").response(_.headerOpt[String]("etag", wireName = "ETag"))
            val headers = HttpHeaders.empty.add("ETag", "abc")

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, headers, Span.empty[Byte]) match
                case Result.Success(resp) =>
                    assert(resp.fields.dict("etag") == Present("abc"))
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }

        "optional response header absent" in {
            val route = HttpRoute.get("data").response(_.headerOpt[String]("etag", wireName = "ETag"))

            RouteUtil.decodeBufferedResponse(route, HttpStatus.OK, HttpHeaders.empty, Span.empty[Byte]) match
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
            val route    = HttpRoute.get("users").response(_.bodyJson[User])
            val response = HttpResponse(HttpStatus.Created, HttpHeaders.empty, Record2.empty).addField("body", User("X", 1))

            var status: HttpStatus = HttpStatus.OK
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (s, _, _, _) => status = s,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
            assert(status == HttpStatus.Created)
        }

        "response cookie encoding" in {
            val route = HttpRoute.get("login")
                .response(_.cookie[String]("session", wireName = "session"))
            val response = HttpResponse(HttpStatus.OK, HttpHeaders.empty, Record2.empty)
                .addField("session", HttpCookie("tok123").httpOnly(true))

            var headers = HttpHeaders.empty
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, h) => headers = h,
                onBuffered = (_, h, _, _) => headers = h,
                onStreaming = (_, _, _, _) => fail("expected empty or buffered")
            )
            val setCookie = headers.get("Set-Cookie")
            assert(setCookie.isDefined)
            val sc = setCookie.getOrElse("")
            assert(sc.contains("session=tok123"))
            assert(sc.contains("HttpOnly"))
        }

        "text body" in {
            val route    = HttpRoute.get("echo").response(_.bodyText)
            val response = HttpResponse(HttpStatus.OK, HttpHeaders.empty, Record2.empty).addField("body", "hello")

            var bodyStr = ""
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, _, ct, bytes) =>
                    assert(ct == "text/plain; charset=utf-8")
                    bodyStr = new String(bytes.toArrayUnsafe.asInstanceOf[Array[Byte]], "UTF-8")
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
            assert(bodyStr == "hello")
        }
    }

    // ==================== matchError edge cases ====================

    "matchError edge cases" - {
        "multiple error mappings selects matching status" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].error[String](HttpStatus.BadRequest).error[Int](HttpStatus.NotFound))
            val body = Span.fromUnsafe("\"not found msg\"".getBytes("UTF-8"))

            // Should not match BadRequest
            assert(RouteUtil.matchError(route, HttpStatus.BadRequest, body) == Present("not found msg"))

            // NotFound with int body
            val intBody = Span.fromUnsafe("404".getBytes("UTF-8"))
            assert(RouteUtil.matchError(route, HttpStatus.NotFound, intBody) == Present(404))
        }

        "decode failure skips to next mapping" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].error[Int](HttpStatus.BadRequest).error[String](HttpStatus.BadRequest))
            // "not a number" can't decode as Int, should fall through to String
            val body = Span.fromUnsafe("\"fallback\"".getBytes("UTF-8"))

            RouteUtil.matchError(route, HttpStatus.BadRequest, body) match
                case Present(err) => assert(err == "fallback")
                case Absent       => fail("expected error match")
        }

        "empty body returns Absent when decode fails" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].error[User](HttpStatus.BadRequest))
            val body = Span.empty[Byte]

            // Empty string can't decode as User JSON, so should return Absent
            assert(RouteUtil.matchError(route, HttpStatus.BadRequest, body) == Absent)
        }

        "empty body fails to decode as JSON String (no fallback)" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].error[String](HttpStatus.BadRequest))
            val body = Span.empty[Byte]

            // Empty body is not valid JSON — decode should fail (fallback removed)
            val result = RouteUtil.matchError(route, HttpStatus.BadRequest, body)
            assert(result == Absent)
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

        "form body round-trip" in {
            val route = HttpRoute.post("login").request(_.bodyForm[LoginForm])
            val form  = LoginForm("bob", "pass123")
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/login").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("body", form)

            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected buffered"),
                onBuffered = (_, _, _, bytes) =>
                    RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, HttpHeaders.empty, bytes) match
                        case Result.Success(decoded) =>
                            assert(decoded.fields.dict("body") == form)
                        case Result.Failure(err) => fail(s"decode failed: $err")
                        case p: Result.Panic     => throw p.exception
                ,
                onStreaming = (_, _, _, _) => fail("expected buffered")
            )
        }
    }

    // ==================== SSE encoding ====================

    "SSE encoding" - {
        "encodeResponse produces SSE frames" in run {
            val route = HttpRoute.get("events").response(_.bodySseJson[User])
            val events: kyo.Stream[HttpEvent[User], kyo.Async & kyo.Scope] = kyo.Stream.init(Seq(
                HttpEvent(User("Alice", 30)),
                HttpEvent(User("Bob", 25), event = Present("update")),
                HttpEvent(User("Carol", 35), id = Present("3"), retry = Present(5000.millis))
            ))
            val response = HttpResponse(HttpStatus.OK, HttpHeaders.empty, Record2.empty)
                .addField("body", events)

            var contentType                                           = ""
            var stream: kyo.Stream[Span[Byte], kyo.Async & kyo.Scope] = null
            RouteUtil.encodeResponse(route, response)(
                onEmpty = (_, _) => fail("expected streaming"),
                onBuffered = (_, _, _, _) => fail("expected streaming"),
                onStreaming = (_, _, ct, s) =>
                    contentType = ct
                    stream = s
            )
            assert(contentType == "text/event-stream")
            kyo.Scope.run {
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
    }

    // ==================== SSE decoding ====================

    "SSE decoding" - {
        "decodeStreamingResponse parses SSE frames" in run {
            val route  = HttpRoute.get("events").response(_.bodySseJson[User])
            val frame1 = "data: {\"name\":\"Alice\",\"age\":30}\n\n"
            val frame2 = "event: update\ndata: {\"name\":\"Bob\",\"age\":25}\n\n"
            val frame3 = "id: 3\nretry: 5000\ndata: {\"name\":\"Carol\",\"age\":35}\n\n"
            val rawStream = kyo.Stream.init(Seq(
                Span.fromUnsafe(frame1.getBytes("UTF-8")),
                Span.fromUnsafe(frame2.getBytes("UTF-8")),
                Span.fromUnsafe(frame3.getBytes("UTF-8"))
            ))

            RouteUtil.decodeStreamingResponse(route, HttpStatus.OK, HttpHeaders.empty, rawStream) match
                case Result.Success(response) =>
                    val eventStream = response.fields.body
                    kyo.Scope.run {
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
                    }
                case Result.Failure(err) => fail(s"decode failed: $err")
                case p: Result.Panic     => throw p.exception
            end match
        }
    }

    // ==================== Multipart buffered decoding ====================

    "multipart buffered decoding" - {
        "decodeBufferedRequest parses multipart body" in {
            val route = HttpRoute.post("upload").request(_.bodyMultipart)
            val body =
                "------TestBoundary123\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\nContent-Type: text/plain\r\n\r\nhello world\r\n------TestBoundary123\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue123\r\n------TestBoundary123--\r\n"
            val bytes   = Span.fromUnsafe(body.getBytes("UTF-8"))
            val headers = HttpHeaders.empty.add("Content-Type", "multipart/form-data; boundary=----TestBoundary123")

            RouteUtil.decodeBufferedRequest(route, Dict.empty[String, String], Absent, headers, bytes) match
                case Result.Success(request) =>
                    val parts = request.fields.dict("body").asInstanceOf[Seq[HttpPart]]
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
    }

    // ==================== NDJSON line splitting ====================

    "NDJSON line splitting" - {
        "handles multiple lines in one chunk" in run {
            val route    = HttpRoute.get("events").response(_.bodyNdjson[User])
            val combined = "{\"name\":\"Alice\",\"age\":30}\n{\"name\":\"Bob\",\"age\":25}\n"
            val rawStream = kyo.Stream.init(Seq(
                Span.fromUnsafe(combined.getBytes("UTF-8"))
            ))

            RouteUtil.decodeStreamingResponse(route, HttpStatus.OK, HttpHeaders.empty, rawStream) match
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

        "handles line split across chunks" in run {
            val route = HttpRoute.get("events").response(_.bodyNdjson[User])
            val part1 = "{\"name\":\"Ali"
            val part2 = "ce\",\"age\":30}\n"
            val rawStream = kyo.Stream.init(Seq(
                Span.fromUnsafe(part1.getBytes("UTF-8")),
                Span.fromUnsafe(part2.getBytes("UTF-8"))
            ))

            RouteUtil.decodeStreamingResponse(route, HttpStatus.OK, HttpHeaders.empty, rawStream) match
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
        "includes closing boundary" in run {
            val route = HttpRoute.post("upload").request(_.bodyMultipartStream)
            val parts: kyo.Stream[HttpPart, kyo.Async] = kyo.Stream.init(Seq(
                HttpPart("field", Absent, Absent, Span.fromUnsafe("value".getBytes("UTF-8")))
            ))
            val request = HttpRequest(
                HttpMethod.POST,
                HttpUrl.parse("http://localhost/upload").getOrThrow,
                HttpHeaders.empty,
                Record2.empty
            ).addField("body", parts)

            var stream: kyo.Stream[Span[Byte], kyo.Async & kyo.Scope] = null
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (_, _) => fail("expected streaming"),
                onBuffered = (_, _, _, _) => fail("expected streaming"),
                onStreaming = (_, _, _, s) => stream = s
            )
            kyo.Scope.run {
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
            val route = HttpRoute.get("users")

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
