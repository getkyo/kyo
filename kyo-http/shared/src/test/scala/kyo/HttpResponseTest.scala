package kyo

import HttpResponse.*

class HttpResponseTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class ErrorBody(message: String) derives Schema, CanEqual

    // Helper to extract body text for unit tests
    private def getBodyText(response: HttpResponse[HttpBody.Bytes]): String =
        response.bodyText

    // Helper to extract body bytes for unit tests
    private def getBodyBytes(response: HttpResponse[HttpBody.Bytes]): Span[Byte] =
        response.bodyBytes

    // Helper to extract typed body for unit tests
    private def getBodyAs[A: Schema](response: HttpResponse[HttpBody.Bytes]): A =
        Abort.run(response.bodyAs[A]).eval match
            case Result.Success(a) => a
            case other             => fail(s"Unexpected result: $other")

    "Status" - {

        "construction" - {
            "from Int" in {
                val status = Status(200)
                assert(status.code == 200)
            }

            "custom status code" in {
                val status = Status(599)
                assert(status.code == 599)
            }
        }

        "as Int subtype" - {
            "implicit conversion to Int" in {
                val status: Status = Status.OK
                val code: Int      = status
                assert(code == 200)
            }

            "arithmetic operations" in {
                val status: Status = Status.OK
                assert(status + 1 == 201)
            }

            "comparison with Int" in {
                val status: Status = Status.OK
                assert(status == 200)
            }
        }

        "category checks" - {
            "isInformational" in {
                assert(Status.Continue.isInformational)
                assert(Status(199).isInformational)
                assert(!Status.OK.isInformational)
            }

            "isSuccess" in {
                assert(Status.OK.isSuccess)
                assert(Status.Created.isSuccess)
                assert(Status(299).isSuccess)
                assert(!Status.BadRequest.isSuccess)
            }

            "isRedirect" in {
                assert(Status.MovedPermanently.isRedirect)
                assert(Status.Found.isRedirect)
                assert(!Status.OK.isRedirect)
            }

            "isClientError" in {
                assert(Status.BadRequest.isClientError)
                assert(Status.NotFound.isClientError)
                assert(!Status.InternalServerError.isClientError)
            }

            "isServerError" in {
                assert(Status.InternalServerError.isServerError)
                assert(Status.BadGateway.isServerError)
                assert(!Status.BadRequest.isServerError)
            }

            "isError" in {
                assert(Status.BadRequest.isError)
                assert(Status.InternalServerError.isError)
                assert(!Status.OK.isError)
            }
        }

        "boundary cases" - {
            "100 is informational" in {
                assert(Status(100).isInformational)
            }

            "199 is informational" in {
                assert(Status(199).isInformational)
                assert(!Status(199).isSuccess)
            }

            "200 is success" in {
                assert(Status(200).isSuccess)
                assert(!Status(200).isInformational)
            }

            "299 is success" in {
                assert(Status(299).isSuccess)
                assert(!Status(299).isRedirect)
            }

            "300 is redirect" in {
                assert(Status(300).isRedirect)
                assert(!Status(300).isSuccess)
            }

            "399 is redirect" in {
                assert(Status(399).isRedirect)
                assert(!Status(399).isClientError)
            }

            "400 is client error" in {
                assert(Status(400).isClientError)
                assert(!Status(400).isRedirect)
            }

            "499 is client error" in {
                assert(Status(499).isClientError)
                assert(!Status(499).isServerError)
            }

            "500 is server error" in {
                assert(Status(500).isServerError)
                assert(!Status(500).isClientError)
            }

            "599 is server error" in {
                assert(Status(599).isServerError)
            }
        }

        "edge cases" - {
            "status code 0 throws" in {
                assertThrows[IllegalArgumentException] {
                    Status(0)
                }
            }

            "status code 99 throws" in {
                assertThrows[IllegalArgumentException] {
                    Status(99)
                }
            }

            "negative status code throws" in {
                assertThrows[IllegalArgumentException] {
                    Status(-1)
                }
            }

            "status code 600 throws" in {
                assertThrows[IllegalArgumentException] {
                    Status(600)
                }
            }

            "very large status code throws" in {
                assertThrows[IllegalArgumentException] {
                    Status(999)
                }
            }

            "boundary 100 is valid" in {
                val status = Status(100)
                assert(status.code == 100)
            }

            "boundary 599 is valid" in {
                val status = Status(599)
                assert(status.code == 599)
            }
        }

        "predefined constants" - {
            "1xx informational" in {
                assert(Status.Continue == 100)
                assert(Status.SwitchingProtocols == 101)
                assert(Status.Processing == 102)
                assert(Status.EarlyHints == 103)
            }

            "2xx success" in {
                assert(Status.OK == 200)
                assert(Status.Created == 201)
                assert(Status.Accepted == 202)
                assert(Status.NoContent == 204)
            }

            "3xx redirection" in {
                assert(Status.MovedPermanently == 301)
                assert(Status.Found == 302)
                assert(Status.NotModified == 304)
                assert(Status.TemporaryRedirect == 307)
            }

            "4xx client error" in {
                assert(Status.BadRequest == 400)
                assert(Status.Unauthorized == 401)
                assert(Status.Forbidden == 403)
                assert(Status.NotFound == 404)
                assert(Status.ImATeapot == 418)
            }

            "5xx server error" in {
                assert(Status.InternalServerError == 500)
                assert(Status.NotImplemented == 501)
                assert(Status.BadGateway == 502)
                assert(Status.ServiceUnavailable == 503)
            }
        }
    }

    "HttpRequest.Cookie" - {
        "construction" in {
            val cookie = HttpRequest.Cookie("session", "abc123")
            assert(cookie.name == "session")
            assert(cookie.value == "abc123")
        }

        "toResponse" in {
            val request  = HttpRequest.Cookie("session", "abc123")
            val response = request.toResponse
            assert(response.name == "session")
            assert(response.value == "abc123")
            assert(response.maxAge == Absent)
            assert(response.secure == false)
        }
    }

    "HttpResponse.Cookie" - {

        "construction" - {
            "minimal" in {
                val cookie = HttpResponse.Cookie("session", "abc123")
                assert(cookie.name == "session")
                assert(cookie.value == "abc123")
                assert(cookie.maxAge == Absent)
                assert(cookie.domain == Absent)
                assert(cookie.path == Absent)
                assert(!cookie.secure)
                assert(!cookie.httpOnly)
                assert(cookie.sameSite == Absent)
            }

            "with all options" in {
                val cookie = HttpResponse.Cookie(
                    name = "session",
                    value = "abc123",
                    maxAge = Present(1.hour),
                    domain = Present("example.com"),
                    path = Present("/app"),
                    secure = true,
                    httpOnly = true,
                    sameSite = Present(HttpResponse.Cookie.SameSite.Strict)
                )
                assert(cookie.maxAge == Present(1.hour))
                assert(cookie.domain == Present("example.com"))
                assert(cookie.path == Present("/app"))
                assert(cookie.secure)
                assert(cookie.httpOnly)
                assert(cookie.sameSite == Present(HttpResponse.Cookie.SameSite.Strict))
            }
        }

        "builder methods" - {
            "maxAge" in {
                val cookie = HttpResponse.Cookie("a", "b").maxAge(30.minutes)
                assert(cookie.maxAge == Present(30.minutes))
            }

            "domain" in {
                val cookie = HttpResponse.Cookie("a", "b").domain("example.com")
                assert(cookie.domain == Present("example.com"))
            }

            "path" in {
                val cookie = HttpResponse.Cookie("a", "b").path("/api")
                assert(cookie.path == Present("/api"))
            }

            "secure" in {
                val cookie = HttpResponse.Cookie("a", "b").secure(true)
                assert(cookie.secure)
            }

            "httpOnly" in {
                val cookie = HttpResponse.Cookie("a", "b").httpOnly(true)
                assert(cookie.httpOnly)
            }

            "sameSite" in {
                val cookie = HttpResponse.Cookie("a", "b").sameSite(HttpResponse.Cookie.SameSite.Lax)
                assert(cookie.sameSite == Present(HttpResponse.Cookie.SameSite.Lax))
            }

            "chaining" in {
                val cookie = HttpResponse.Cookie("session", "xyz")
                    .maxAge(1.hour)
                    .domain("example.com")
                    .path("/")
                    .secure(true)
                    .httpOnly(true)
                    .sameSite(HttpResponse.Cookie.SameSite.Strict)
                assert(cookie.maxAge == Present(1.hour))
                assert(cookie.domain == Present("example.com"))
                assert(cookie.path == Present("/"))
                assert(cookie.secure)
                assert(cookie.httpOnly)
                assert(cookie.sameSite == Present(HttpResponse.Cookie.SameSite.Strict))
            }
        }

        "SameSite enum" in {
            assert(HttpResponse.Cookie.SameSite.Strict != HttpResponse.Cookie.SameSite.Lax)
            assert(HttpResponse.Cookie.SameSite.Lax != HttpResponse.Cookie.SameSite.None)
        }

        "edge cases" - {
            "empty cookie name throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpResponse.Cookie("", "value")
                }
            }

            "empty cookie value" in {
                val cookie = HttpResponse.Cookie("name", "")
                assert(cookie.value == "")
            }

            "cookie with special characters in value" in {
                val cookie = HttpResponse.Cookie("name", "value=with;special,chars")
                assert(cookie.value == "value=with;special,chars")
            }

            "cookie with unicode value" in {
                val cookie = HttpResponse.Cookie("name", "值")
                assert(cookie.value == "值")
            }

            "zero max age" in {
                val cookie = HttpResponse.Cookie("a", "b").maxAge(Duration.Zero)
                assert(cookie.maxAge == Present(Duration.Zero))
            }

            "empty domain" in {
                val cookie = HttpResponse.Cookie("a", "b").domain("")
                assert(cookie.domain == Present(""))
            }

            "empty path" in {
                val cookie = HttpResponse.Cookie("a", "b").path("")
                assert(cookie.path == Present(""))
            }
        }
    }

    "Response factory methods" - {

        "generic apply" - {
            "with status and empty body" in {
                val response = HttpResponse(Status.OK)
                assert(response.status == Status.OK)
                assert(getBodyText(response) == "")
            }

            "with status and string body" in {
                val response = HttpResponse(Status.OK, "hello")
                assert(response.status == Status.OK)
                assert(getBodyText(response) == "hello")
            }

            "with status and typed body" in {
                val response = HttpResponse(Status.OK, User(1, "Alice"))
                assert(response.status == Status.OK)
                assert(getBodyAs[User](response) == User(1, "Alice"))
            }
        }

        "2xx success" - {
            "ok without body" in {
                val response = HttpResponse.ok
                assert(response.status == Status.OK)
            }

            "ok with string body" in {
                val response = HttpResponse.ok("success")
                assert(response.status == Status.OK)
                assert(getBodyText(response) == "success")
            }

            "ok with typed body" in {
                val response = HttpResponse.ok(User(1, "Alice"))
                assert(response.status == Status.OK)
                assert(getBodyAs[User](response) == User(1, "Alice"))
            }

            "created with body" in {
                val response = HttpResponse.created(User(1, "Alice"))
                assert(response.status == Status.Created)
                assert(getBodyAs[User](response) == User(1, "Alice"))
            }

            "created with body and location" in {
                val response = HttpResponse.created(User(1, "Alice"), "/users/1")
                assert(response.status == Status.Created)
                assert(response.header("Location") == Present("/users/1"))
            }

            "accepted without body" in {
                val response = HttpResponse.accepted
                assert(response.status == Status.Accepted)
            }

            "accepted with body" in {
                val response = HttpResponse.accepted(User(1, "Alice"))
                assert(response.status == Status.Accepted)
            }

            "noContent" in {
                val response = HttpResponse.noContent
                assert(response.status == Status.NoContent)
                assert(getBodyText(response) == "")
            }
        }

        "3xx redirection" - {
            "redirect with url" in {
                val response = HttpResponse.redirect("/new-location")
                assert(response.status == Status.Found)
                assert(response.header("Location") == Present("/new-location"))
            }

            "redirect with url and status" in {
                val response = HttpResponse.redirect("/new-location", Status.TemporaryRedirect)
                assert(response.status == Status.TemporaryRedirect)
                assert(response.header("Location") == Present("/new-location"))
            }

            "movedPermanently" in {
                val response = HttpResponse.movedPermanently("/permanent")
                assert(response.status == Status.MovedPermanently)
                assert(response.header("Location") == Present("/permanent"))
            }

            "notModified" in {
                val response = HttpResponse.notModified
                assert(response.status == Status.NotModified)
            }

            "redirect with empty url throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpResponse.redirect("")
                }
            }
        }

        "4xx client error" - {
            "badRequest without body" in {
                val response = HttpResponse.badRequest
                assert(response.status == Status.BadRequest)
            }

            "badRequest with string body" in {
                val response = HttpResponse.badRequest("Invalid input")
                assert(response.status == Status.BadRequest)
                assert(getBodyText(response) == "Invalid input")
            }

            "badRequest with typed body" in {
                val response = HttpResponse.badRequest(ErrorBody("Invalid"))
                assert(response.status == Status.BadRequest)
                assert(getBodyAs[ErrorBody](response) == ErrorBody("Invalid"))
            }

            "unauthorized without body" in {
                val response = HttpResponse.unauthorized
                assert(response.status == Status.Unauthorized)
            }

            "unauthorized with body" in {
                val response = HttpResponse.unauthorized("Please login")
                assert(response.status == Status.Unauthorized)
                assert(getBodyText(response) == "Please login")
            }

            "forbidden without body" in {
                val response = HttpResponse.forbidden
                assert(response.status == Status.Forbidden)
            }

            "forbidden with body" in {
                val response = HttpResponse.forbidden("Access denied")
                assert(response.status == Status.Forbidden)
                assert(getBodyText(response) == "Access denied")
            }

            "notFound without body" in {
                val response = HttpResponse.notFound
                assert(response.status == Status.NotFound)
            }

            "notFound with body" in {
                val response = HttpResponse.notFound("Resource not found")
                assert(response.status == Status.NotFound)
                assert(getBodyText(response) == "Resource not found")
            }

            "conflict without body" in {
                val response = HttpResponse.conflict
                assert(response.status == Status.Conflict)
            }

            "conflict with body" in {
                val response = HttpResponse.conflict("Already exists")
                assert(response.status == Status.Conflict)
                assert(getBodyText(response) == "Already exists")
            }

            "unprocessableEntity without body" in {
                val response = HttpResponse.unprocessableEntity
                assert(response.status == Status.UnprocessableEntity)
            }

            "unprocessableEntity with body" in {
                val response = HttpResponse.unprocessableEntity(ErrorBody("Validation failed"))
                assert(response.status == Status.UnprocessableEntity)
            }

            "tooManyRequests without retryAfter" in {
                val response = HttpResponse.tooManyRequests
                assert(response.status == Status.TooManyRequests)
            }

            "tooManyRequests with retryAfter" in {
                val response = HttpResponse.tooManyRequests(60.seconds)
                assert(response.status == Status.TooManyRequests)
                assert(response.header("Retry-After") == Present("60"))
            }
        }

        "5xx server error" - {
            "serverError without body" in {
                val response = HttpResponse.serverError
                assert(response.status == Status.InternalServerError)
            }

            "serverError with string body" in {
                val response = HttpResponse.serverError("Something went wrong")
                assert(response.status == Status.InternalServerError)
                assert(getBodyText(response) == "Something went wrong")
            }

            "serverError with typed body" in {
                val response = HttpResponse.serverError(ErrorBody("Internal error"))
                assert(response.status == Status.InternalServerError)
            }

            "serviceUnavailable without retryAfter" in {
                val response = HttpResponse.serviceUnavailable
                assert(response.status == Status.ServiceUnavailable)
            }

            "serviceUnavailable with retryAfter" in {
                val response = HttpResponse.serviceUnavailable(30.seconds)
                assert(response.status == Status.ServiceUnavailable)
                assert(response.header("Retry-After") == Present("30"))
            }
        }

    }

    "Response abstract members" - {
        "status" in {
            val response = HttpResponse.ok
            assert(response.status == Status.OK)
        }

        "contentType" in {
            val response = HttpResponse.ok(User(1, "Alice"))
            assert(response.contentType == Present("application/json"))
        }

        "contentLength" in {
            val response = HttpResponse.ok("hello")
            assert(response.contentLength == 5L)
        }

        "header" in {
            val response = HttpResponse.ok.addHeader("X-Custom", "value")
            assert(response.header("X-Custom") == Present("value"))
            assert(response.header("X-Missing") == Absent)
        }

        "headers" in {
            val response = HttpResponse.ok
                .addHeader("X-One", "1")
                .addHeader("X-Two", "2")
            val headers = response.headers
            assert(headers.exists((k, v) => k == "X-One" && v == "1"))
            assert(headers.exists((k, v) => k == "X-Two" && v == "2"))
        }

        "cookie" in {
            val response = HttpResponse.ok.addCookie(HttpResponse.Cookie("session", "abc"))
            assert(response.cookie("session").map(_.value) == Present("abc"))
            assert(response.cookie("missing") == Absent)
        }

        "cookies" in {
            val response = HttpResponse.ok
                .addCookie(HttpResponse.Cookie("a", "1"))
                .addCookie(HttpResponse.Cookie("b", "2"))
            val cookies = response.cookies
            assert(cookies.exists(_.name == "a"))
            assert(cookies.exists(_.name == "b"))
        }

        "bodyText" in {
            val response = HttpResponse.ok("hello world")
            assert(getBodyText(response) == "hello world")
        }

        "bodyBytes" in {
            val response = HttpResponse.ok("hello")
            assert(getBodyBytes(response).toArray.sameElements("hello".getBytes))
        }

        "bodyAs" in {
            val response = HttpResponse.ok(User(1, "Alice"))
            assert(getBodyAs[User](response) == User(1, "Alice"))
        }

    }

    "Response extension methods" - {

        "headers" - {
            "withHeader single" in {
                val response = HttpResponse.ok.addHeader("X-Test", "value")
                assert(response.header("X-Test") == Present("value"))
            }

            "withHeader overwrites existing via setHeader" in {
                val response = HttpResponse.ok
                    .setHeader("X-Test", "old")
                    .setHeader("X-Test", "new")
                assert(response.header("X-Test") == Present("new"))
            }

            "withHeaders multiple" in {
                val response = HttpResponse.ok.addHeaders(
                    HttpHeaders.empty.add("X-One", "1").add("X-Two", "2").add("X-Three", "3")
                )
                assert(response.header("X-One") == Present("1"))
                assert(response.header("X-Two") == Present("2"))
                assert(response.header("X-Three") == Present("3"))
            }

            "withHeaders empty" in {
                val response = HttpResponse.ok.addHeaders(HttpHeaders.empty)
                assert(response.status == Status.OK)
            }
        }

        "cookies" - {
            "withCookie single" in {
                val response = HttpResponse.ok.addCookie(HttpResponse.Cookie("session", "abc"))
                assert(response.cookie("session").map(_.value) == Present("abc"))
            }

            "withCookie multiple calls" in {
                val response = HttpResponse.ok
                    .addCookie(HttpResponse.Cookie("a", "1"))
                    .addCookie(HttpResponse.Cookie("b", "2"))
                assert(response.cookie("a").map(_.value) == Present("1"))
                assert(response.cookie("b").map(_.value) == Present("2"))
            }

            "withCookies multiple" in {
                val response = HttpResponse.ok.addCookies(
                    HttpResponse.Cookie("a", "1"),
                    HttpResponse.Cookie("b", "2")
                )
                assert(response.cookies.size >= 2)
            }

            "withCookies empty" in {
                val response = HttpResponse.ok.addCookies()
                assert(response.status == Status.OK)
            }
        }

        "content disposition" - {
            "withContentDisposition attachment" in {
                val response = HttpResponse.ok.contentDisposition("report.pdf")
                assert(response.header("Content-Disposition") == Present("attachment; filename=\"report.pdf\""))
            }

            "withContentDisposition inline" in {
                val response = HttpResponse.ok.contentDisposition("image.png", isInline = true)
                assert(response.header("Content-Disposition") == Present("inline; filename=\"image.png\""))
            }

            "withContentDisposition special characters in filename" in {
                val response = HttpResponse.ok.contentDisposition("my file (1).pdf")
                assert(response.header("Content-Disposition").isDefined)
            }
        }

        "caching" - {
            "withETag" in {
                val response = HttpResponse.ok.etag("abc123")
                assert(response.header("ETag") == Present("\"abc123\""))
            }

            "withETag with quotes" in {
                val response = HttpResponse.ok.etag("\"already-quoted\"")
                assert(response.header("ETag") == Present("\"already-quoted\""))
            }

            "withLastModified" in {
                val instant  = Instant.fromJava(java.time.Instant.parse("2024-01-15T10:30:00Z"))
                val response = HttpResponse.ok.lastModified(instant)
                assert(response.header("Last-Modified").isDefined)
            }

            "withCacheControl" in {
                val response = HttpResponse.ok.cacheControl("max-age=3600, public")
                assert(response.header("Cache-Control") == Present("max-age=3600, public"))
            }

            "noCache" in {
                val response = HttpResponse.ok.noCache
                assert(response.header("Cache-Control") == Present("no-cache"))
            }

            "noStore" in {
                val response = HttpResponse.ok.noStore
                assert(response.header("Cache-Control") == Present("no-store"))
            }
        }

        "compression" - {
            "withContentEncoding gzip" in {
                val response = HttpResponse.ok.contentEncoding("gzip")
                assert(response.header("Content-Encoding") == Present("gzip"))
            }

            "withContentEncoding deflate" in {
                val response = HttpResponse.ok.contentEncoding("deflate")
                assert(response.header("Content-Encoding") == Present("deflate"))
            }

            "withContentEncoding br" in {
                val response = HttpResponse.ok.contentEncoding("br")
                assert(response.header("Content-Encoding") == Present("br"))
            }
        }

        "chaining" in {
            val response = HttpResponse.ok("body")
                .addHeader("X-Custom", "value")
                .addCookie(HttpResponse.Cookie("session", "abc"))
                .etag("etag123")
                .cacheControl("max-age=3600")
            assert(response.status == Status.OK)
            assert(response.header("X-Custom") == Present("value"))
            assert(response.cookie("session").isDefined)
            assert(response.header("ETag").isDefined)
            assert(response.header("Cache-Control").isDefined)
        }
    }

    "Header edge cases" - {
        "empty header name" in {
            val response = HttpResponse.ok.addHeader("", "value")
            assert(response.header("") == Present("value"))
        }

        "empty header value" in {
            val response = HttpResponse.ok.addHeader("X-Empty", "")
            assert(response.header("X-Empty") == Present(""))
        }

        "header with colon in value" in {
            val response = HttpResponse.ok.addHeader("X-Time", "12:30:00")
            assert(response.header("X-Time") == Present("12:30:00"))
        }

        "duplicate header names with addHeader (append)" in {
            val response = HttpResponse.ok
                .addHeader("X-Multi", "first")
                .addHeader("X-Multi", "second")
            // addHeader appends — both values present, last returned by header()
            assert(response.header("X-Multi") == Present("second"))
        }

        "setHeader replaces existing" in {
            val response = HttpResponse.ok
                .setHeader("X-Multi", "first")
                .setHeader("X-Multi", "second")
            assert(response.header("X-Multi") == Present("second"))
            var count = 0
            response.headers.foreach { (k, _) =>
                if k.equalsIgnoreCase("X-Multi") then count += 1
            }
            assert(count == 1, s"Expected exactly 1 X-Multi header but got $count")
        }

        "case sensitivity of header names" in {
            val response = HttpResponse.ok.addHeader("Content-Type", "text/plain")
            // HTTP headers are case-insensitive
            assert(response.header("content-type") == Present("text/plain"))
            assert(response.header("CONTENT-TYPE") == Present("text/plain"))
        }

        "very long header value" in {
            val longValue = "x" * 8000
            val response  = HttpResponse.ok.addHeader("X-Long", longValue)
            assert(response.header("X-Long") == Present(longValue))
        }

        "header with unicode" in {
            val response = HttpResponse.ok.addHeader("X-Unicode", "值")
            assert(response.header("X-Unicode") == Present("值"))
        }
    }

    "Multiple Set-Cookie headers" - {

        "addCookie preserves all cookies in model" in {
            val response = HttpResponse.ok
                .addCookie(HttpResponse.Cookie("a", "1"))
                .addCookie(HttpResponse.Cookie("b", "2"))
                .addCookie(HttpResponse.Cookie("c", "3"))
            assert(response.cookies.size == 3)
            assert(response.cookie("a").map(_.value) == Present("1"))
            assert(response.cookie("b").map(_.value) == Present("2"))
            assert(response.cookie("c").map(_.value) == Present("3"))
        }

        "addHeader appends same-name header (append semantics)" in {
            val response = HttpResponse.ok
                .addHeader("X-Trace", "first")
                .addHeader("X-Trace", "second")
            // response addHeader now has append semantics (consistent with HttpRequest)
            assert(response.header("X-Trace") == Present("second"))
            var count = 0
            response.headers.foreach { (k, _) =>
                if k.equalsIgnoreCase("X-Trace") then count += 1
            }
            assert(count == 2, s"Expected exactly 2 X-Trace headers but got $count")
        }
    }

    "Response Content-Type edge cases" - {

        "noContent response has no content type" in {
            val response = HttpResponse.noContent
            assert(response.contentType == Absent)
        }

        "ok with text body has text/plain content type" in {
            val response = HttpResponse.ok("hello")
            assert(response.contentType == Present("text/plain"))
        }

        "ok with typed body has application/json content type" in {
            val response = HttpResponse.ok(User(1, "Alice"))
            assert(response.contentType == Present("application/json"))
        }

        "redirect has no content type" in {
            val response = HttpResponse.redirect("/new")
            assert(response.contentType == Absent)
        }

        "empty ok response" in {
            val response = HttpResponse.ok
            assert(response.contentLength == 0L)
        }
    }

    "Cookie edge cases" - {

        "cookie with equals in value" in {
            val cookie = HttpResponse.Cookie("token", "abc=def=ghi")
            assert(cookie.value == "abc=def=ghi")
        }

        "cookie with long value" in {
            val longVal = "x" * 4096
            val cookie  = HttpResponse.Cookie("big", longVal)
            assert(cookie.value.length == 4096)
        }

        "overwriting cookie with same name" in {
            val response = HttpResponse.ok
                .addCookie(HttpResponse.Cookie("session", "old"))
                .addCookie(HttpResponse.Cookie("session", "new"))
            // Both cookies should be present (multiple Set-Cookie headers)
            val cookies = response.cookies
            assert(cookies.count(_.name == "session") == 2)
        }

        "delete cookie pattern (max-age=0)" in {
            val cookie = HttpResponse.Cookie("session", "").maxAge(Duration.Zero)
            assert(cookie.maxAge == Present(Duration.Zero))
        }
    }

    "Body edge cases" - {
        "empty body" in {
            val response = HttpResponse.ok("")
            assert(getBodyText(response) == "")
            assert(getBodyBytes(response).isEmpty)
        }

        "null bytes in body" in {
            val bytes    = Array[Byte](0, 1, 2, 0, 3)
            val response = HttpResponse(Status.OK, new String(bytes))
            assert(getBodyBytes(response).size == 5)
        }

        "very large body" in {
            val largeBody = "x" * (1024 * 1024) // 1MB
            val response  = HttpResponse.ok(largeBody)
            assert(getBodyText(response).length == 1024 * 1024)
        }

        "unicode body" in {
            val response = HttpResponse.ok("Hello 世界 🌍")
            assert(getBodyText(response) == "Hello 世界 🌍")
        }

        "binary body" in {
            // Use ASCII-compatible bytes (0-127) since HttpResponse uses UTF-8 encoding
            // Bytes outside ASCII range get multi-byte UTF-8 encoding
            val bytes    = Array[Byte](0, 1, 64, 127, 65)
            val response = HttpResponse(Status.OK, new String(bytes, "ISO-8859-1"))
            assert(getBodyBytes(response).size == 5)
        }
    }

end HttpResponseTest
