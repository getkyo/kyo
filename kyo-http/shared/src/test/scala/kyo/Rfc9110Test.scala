package kyo

import kyo.*
import kyo.Record2.~
import kyo.millis
import kyo.seconds
import scala.language.implicitConversions

/** RFC 9110 — HTTP Semantics compliance tests.
  *
  * Tests validate behaviors defined in RFC 9110 that are implemented in shared code (routing, response encoding, client redirect handling).
  * Wire-level details (framing, connection management) are backend-specific and not tested here.
  *
  * Failing tests indicate RFC non-compliance in the implementation. Do NOT adjust assertions to match wrong behavior.
  */
class Rfc9110Test extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Schema, CanEqual

    def withServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: Int => A < (S & Async & Abort[HttpError])
    )(using Frame): A < (S & Async & Scope & Abort[HttpError]) =
        HttpServer.init(0, "localhost")(handlers*).map(server => test(server.port))

    def send[In, Out](
        port: Int,
        route: HttpRoute[In, Out, Any],
        request: HttpRequest[In]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
        HttpClient.use { client =>
            client.sendWith(
                route,
                request.copy(url =
                    HttpUrl(Present("http"), "localhost", port, request.url.path, request.url.rawQuery)
                )
            )(identity)
        }

    def withClient[A, S](f: HttpClient => A < (S & Async & Abort[HttpError]))(using Frame): A < (S & Async & Abort[HttpError]) =
        HttpClient.use(f)

    // rawRoute: use to observe raw response status/headers without type-safe body decoding
    val rawRoute  = HttpRoute.getRaw("raw").response(_.bodyText)
    val noTimeout = HttpClient.Config(timeout = Maybe.empty)

    // ==================== Section 9.3.2: HEAD ====================

    "Section 9.3.2 - HEAD response MUST NOT contain a message body" in run {
        val route = HttpRoute.getRaw("data").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("hello world"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.headRaw(HttpUrl.fromUri("/data"))).map { resp =>
                assert(resp.status == HttpStatus.OK)
                // RFC 9110 §9.3.2: "The server MUST NOT send content in a response to a HEAD request."
                val bodyText = resp.fields.body
                assert(bodyText.isEmpty, s"HEAD response should have empty body but got: '$bodyText'")
            }
        }
    }

    "Section 9.3.2 - HEAD response Content-Type MUST match GET" in run {
        val route = HttpRoute.getRaw("typed").response(_.bodyJson[User])
        val ep    = route.handler(_ => HttpResponse.okJson(User(1, "Alice")))
        withServer(ep) { port =>
            for
                getResp  <- send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/typed")))
                headResp <- send(port, rawRoute, HttpRequest.headRaw(HttpUrl.fromUri("/typed")))
            yield
                val getCt  = getResp.headers.get("Content-Type")
                val headCt = headResp.headers.get("Content-Type")
                assert(getCt.isDefined, "GET response should have Content-Type")
                assert(headCt.isDefined, "HEAD response should have Content-Type")
                // RFC 9110 §9.3.2: HEAD response headers should be identical to GET
                assert(getCt == headCt, s"Content-Type mismatch: GET=$getCt, HEAD=$headCt")
        }
    }

    "Section 9.3.2 - HEAD response Content-Length MUST match GET body size" in run {
        val route = HttpRoute.getRaw("sized").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("twelve chars"))
        withServer(ep) { port =>
            for
                getResp  <- send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/sized")))
                headResp <- send(port, rawRoute, HttpRequest.headRaw(HttpUrl.fromUri("/sized")))
            yield
                val getCl  = getResp.headers.get("Content-Length")
                val headCl = headResp.headers.get("Content-Length")
                assert(getCl.isDefined, "GET response should have Content-Length")
                assert(headCl.isDefined, "HEAD response should have Content-Length")
                // RFC 9110 §9.3.2: The payload header fields should match what GET would return
                assert(getCl == headCl, s"Content-Length mismatch: GET=$getCl, HEAD=$headCl")
        }
    }

    // ==================== Section 9.3.7: OPTIONS ====================

    "Section 9.3.7 - OPTIONS MUST include Allow header" in run {
        val getRoute  = HttpRoute.getRaw("resource").response(_.bodyText)
        val postRoute = HttpRoute.postRaw("resource").request(_.bodyText).response(_.bodyText)
        val getEp     = getRoute.handler(_ => HttpResponse.okText("get"))
        val postEp    = postRoute.handler(req => HttpResponse.okText("post"))
        withServer(getEp, postEp) { port =>
            send(port, rawRoute, HttpRequest.optionsRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                assert(resp.status == HttpStatus.NoContent, s"OPTIONS should return 204, got ${resp.status}")
                val allow = resp.headers.get("Allow")
                // RFC 9110 §9.3.7: "A server generating a successful response to OPTIONS SHOULD send any
                // header that might also be sent in a response to other methods on the same resource"
                assert(allow.isDefined, "OPTIONS response MUST include Allow header (RFC 9110 §9.3.7)")
            }
        }
    }

    "Section 9.3.7 - OPTIONS Allow lists HEAD when GET registered" in run {
        val route = HttpRoute.getRaw("resource").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.optionsRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                val allow = resp.headers.get("Allow").getOrElse("")
                assert(allow.contains("GET"), s"Allow should contain GET: $allow")
                // RFC 9110 §9.3.2: "A server MUST generate the same header fields [for HEAD] that it
                // would have sent if the request method had been GET" — implies HEAD is available when GET is.
                assert(allow.contains("HEAD"), s"Allow should contain HEAD (implicit from GET per RFC 9110 §9.3.2): $allow")
                assert(allow.contains("OPTIONS"), s"Allow should contain OPTIONS: $allow")
            }
        }
    }

    // ==================== Section 9.3.4: POST ====================

    "Section 9.3.4 - POST success with 201 and Location header" in run {
        // RFC 9110 §9.3.3: "If one or more resources has been created [...] the origin server
        // SHOULD send a 201 (Created) response [...] and an identifier for the primary resource created"
        val route = HttpRoute.postRaw("users").request(_.bodyJson[User]).response(_.bodyJson[User])
        val ep = route.handler { req =>
            val user = req.fields.body
            HttpResponse(HttpStatus.Created)
                .setHeader("Location", s"/users/${user.id}")
                .addField("body", user)
        }
        withServer(ep) { port =>
            val req = HttpRequest.postRaw(HttpUrl.fromUri("/users"))
                .addField("body", User(42, "Alice"))
            send(port, route, req).map { resp =>
                assert(resp.status == HttpStatus.Created)
                val location = resp.headers.get("Location")
                assert(location.isDefined, "201 Created SHOULD include Location header")
                assert(location.get == "/users/42", s"Location header mismatch: ${location.get}")
            }
        }
    }

    // ==================== Section 9.3.5: DELETE ====================

    "Section 9.3.5 - DELETE returning 202 Accepted" in run {
        val route = HttpRoute.deleteRaw("resource").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.acceptedText("queued"))
        withServer(ep) { port =>
            send(port, route, HttpRequest.deleteRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                assert(resp.status == HttpStatus.Accepted)
                assert(resp.fields.body == "queued")
            }
        }
    }

    "Section 9.3.5 - DELETE returning 204 No Content" in run {
        // RFC 9110 §9.3.5: "If a DELETE method is successfully applied [...] a 204 (No Content) status code
        // if the action has been enacted and no further information is to be supplied."
        val route = HttpRoute.deleteRaw("resource").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.halt(HttpResponse.noContent))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.deleteRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                assert(resp.status == HttpStatus.NoContent)
            }
        }
    }

    "Section 9.3.5 - DELETE returning 200 OK with status body" in run {
        // RFC 9110 §9.3.5: "a 200 (OK) status code if the action has been enacted and the
        // response message includes a representation describing the status."
        val route = HttpRoute.deleteRaw("resource").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("deleted successfully"))
        withServer(ep) { port =>
            send(port, route, HttpRequest.deleteRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == "deleted successfully")
            }
        }
    }

    // ==================== Section 15.5.6: 405 Method Not Allowed ====================

    "Section 15.5.6 - 405 MUST include Allow header" in run {
        val getRoute = HttpRoute.getRaw("resource").response(_.bodyText)
        val ep       = getRoute.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.putRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                assert(resp.status == HttpStatus.MethodNotAllowed)
                // RFC 9110 §15.5.6: "The origin server MUST generate an Allow header field [...]
                // containing a list of the target resource's currently supported methods."
                val allow = resp.headers.get("Allow")
                assert(allow.isDefined, "405 response MUST include Allow header (RFC 9110 §15.5.6)")
            }
        }
    }

    "Section 15.5.6 - 405 Allow includes HEAD when GET registered" in run {
        val route = HttpRoute.getRaw("resource").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.deleteRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                assert(resp.status == HttpStatus.MethodNotAllowed)
                val allow = resp.headers.get("Allow").getOrElse("")
                assert(allow.contains("GET"), s"405 Allow should contain GET: $allow")
                // RFC 9110 §9.3.2 implies HEAD is always available when GET is registered
                assert(allow.contains("HEAD"), s"405 Allow should include HEAD (implicit from GET): $allow")
            }
        }
    }

    "Section 15.5.6 - 405 Allow includes OPTIONS" in run {
        val route = HttpRoute.getRaw("resource").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.deleteRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                assert(resp.status == HttpStatus.MethodNotAllowed)
                val allow = resp.headers.get("Allow").getOrElse("")
                // RFC 9110 §9.3.7: OPTIONS is always available for any resource
                assert(allow.contains("OPTIONS"), s"405 Allow should include OPTIONS: $allow")
            }
        }
    }

    // ==================== Section 15.3.5: 204 No Content ====================

    "Section 15.3.5 - 204 MUST NOT contain body" in run {
        val route = HttpRoute.getRaw("empty").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.halt(HttpResponse.noContent))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/empty"))).map { resp =>
                assert(resp.status == HttpStatus.NoContent)
                // RFC 9110 §15.3.5: "A server MUST NOT send content in a response to this status code."
                val body = resp.fields.body
                assert(body.isEmpty, s"204 response MUST NOT have body but got: '$body'")
            }
        }
    }

    "Section 15.3.5 - 204 MUST NOT contain Content-Type" in run {
        val route = HttpRoute.getRaw("empty").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.halt(HttpResponse.noContent))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/empty"))).map { resp =>
                assert(resp.status == HttpStatus.NoContent)
                // RFC 9110 §15.3.5: No content means no Content-Type either
                val ct = resp.headers.get("Content-Type")
                assert(ct.isEmpty, s"204 response MUST NOT have Content-Type but got: $ct")
            }
        }
    }

    // ==================== Section 15.4.5: 304 Not Modified ====================

    "Section 15.4.5 - 304 MUST NOT contain body" in run {
        val route = HttpRoute.getRaw("cached").response(_.bodyText)
        val ep = route.handler(_ =>
            HttpResponse.halt(HttpResponse.notModified.etag("\"abc123\"").cacheControl("max-age=3600"))
        )
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/cached"))).map { resp =>
                assert(resp.status == HttpStatus.NotModified)
                // RFC 9110 §15.4.5: "A server MUST NOT generate content in a 304 response."
                val body = resp.fields.body
                assert(body.isEmpty, s"304 response MUST NOT have body but got: '$body'")
            }
        }
    }

    "Section 15.4.5 - 304 preserves ETag and Cache-Control" in run {
        val route = HttpRoute.getRaw("cached").response(_.bodyText)
        val ep = route.handler(_ =>
            HttpResponse.halt(HttpResponse.notModified.etag("\"abc123\"").cacheControl("max-age=3600"))
        )
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/cached"))).map { resp =>
                assert(resp.status == HttpStatus.NotModified)
                // RFC 9110 §15.4.5: "A 304 response MUST include [...] ETag [...] Cache-Control [...]"
                val etag = resp.headers.get("ETag")
                assert(etag.isDefined, "304 MUST include ETag if set")
                assert(etag.get == "\"abc123\"", s"ETag value mismatch: ${etag.get}")
                val cc = resp.headers.get("Cache-Control")
                assert(cc.isDefined, "304 MUST include Cache-Control if set")
                assert(cc.get == "max-age=3600", s"Cache-Control value mismatch: ${cc.get}")
            }
        }
    }

    // ==================== Section 15.5.16: 415 Unsupported Media Type ====================

    "Section 15.5.16 - wrong Content-Type MUST return 415" in run {
        // RFC 9110 §15.5.16: "The 415 (Unsupported Media Type) status code indicates that the
        // origin server is refusing to service the request because the content is in a format
        // not supported by this method on the target resource."
        val route = HttpRoute.postRaw("json-endpoint")
            .request(_.bodyJson[User])
            .response(_.bodyText)
        val ep = route.handler(req => HttpResponse.okText(s"got: ${req.fields.body}"))
        // Send with text/plain Content-Type to a JSON endpoint
        val textRoute = HttpRoute.postRaw("json-endpoint")
            .request(_.bodyText)
            .response(_.bodyText)
        withServer(ep) { port =>
            send(
                port,
                textRoute,
                HttpRequest.postRaw(HttpUrl.fromUri("/json-endpoint"))
                    .addField("body", """{"id":1,"name":"alice"}""")
            ).map { resp =>
                // RFC says 415; implementation currently returns 400. This test validates RFC compliance.
                assert(
                    resp.status == HttpStatus.UnsupportedMediaType,
                    s"Wrong Content-Type should return 415 Unsupported Media Type per RFC 9110 §15.5.16, got: ${resp.status}"
                )
            }
        }
    }

    // ==================== Section 6.6.1: Date header ====================

    "Section 6.6.1 - Server MUST send Date header" in run {
        val route = HttpRoute.getRaw("date").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("hello"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/date"))).map { resp =>
                assert(resp.status == HttpStatus.OK)
                // RFC 9110 §6.6.1: "An origin server MUST send a Date header field [...]
                // in all other cases."
                val date = resp.headers.get("Date")
                assert(date.isDefined, "Server MUST send Date header in responses (RFC 9110 §6.6.1)")
            }
        }
    }

    // ==================== Section 8.3: Content-Type ====================

    "Section 8.3 - Content-Type text/plain for text body" in run {
        val route = HttpRoute.getRaw("text").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("hello"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/text"))).map { resp =>
                val ct = resp.headers.get("Content-Type").getOrElse("")
                assert(ct.contains("text/plain"), s"Text body should have text/plain Content-Type, got: $ct")
            }
        }
    }

    "Section 8.3 - Content-Type application/json for JSON body" in run {
        val route = HttpRoute.getRaw("json").response(_.bodyJson[User])
        val ep    = route.handler(_ => HttpResponse.okJson(User(1, "Alice")))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/json"))).map { resp =>
                val ct = resp.headers.get("Content-Type").getOrElse("")
                assert(ct.contains("application/json"), s"JSON body should have application/json Content-Type, got: $ct")
            }
        }
    }

    // ==================== Section 8.6: Content-Length ====================

    "Section 8.6 - Content-Length present for buffered responses" in run {
        val route = HttpRoute.getRaw("sized").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("twelve chars"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/sized"))).map { resp =>
                val cl = resp.headers.get("Content-Length")
                assert(cl.isDefined, "Buffered response should include Content-Length")
                assert(cl.get == "12", s"Content-Length should be 12 for 'twelve chars', got: ${cl.get}")
            }
        }
    }

    // ==================== Section 15.6.1: 500 Internal Server Error ====================

    "Section 15.6.1 - 500 error MUST NOT leak internal details" in run {
        val route = HttpRoute.getRaw("crash").response(_.bodyText)
        val ep    = route.handler(_ => throw new RuntimeException("secret internal error"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/crash"))).map { resp =>
                assert(resp.status == HttpStatus.InternalServerError)
                val body = resp.fields.body
                // Server SHOULD NOT reveal internal error details to clients
                assert(!body.contains("secret internal error"), s"Error response should not leak error message: $body")
                assert(!body.contains("at kyo."), s"Error response should not contain stack trace: $body")
            }
        }
    }

    // ==================== Section 15.4: Client redirect following ====================

    "Section 15.4.4 - 303 See Other MUST change method to GET" in run {
        // RFC 9110 §15.4.4: "A client that makes an automatic redirection request to the new URI
        // MUST send a request with a method of GET (or HEAD)."
        // NOTE: Implementation currently preserves original method for ALL redirects.
        // This test validates the RFC requirement — it may fail if not implemented.
        val postRoute = HttpRoute.postRaw("submit").request(_.bodyText).response(_.bodyText)
        val getRoute  = HttpRoute.getRaw("result").response(_.bodyText)
        val postEp = postRoute.handler(_ =>
            HttpResponse.halt(HttpResponse(HttpStatus.SeeOther).setHeader("Location", "/result"))
        )
        val getEp = getRoute.handler(_ => HttpResponse.okText("result page"))
        withServer(postEp, getEp) { port =>
            HttpClient.withConfig(noTimeout) {
                withClient { c =>
                    val textRoute = HttpRoute.postText("")
                    val req = HttpRequest(HttpMethod.POST, HttpUrl(Present("http"), "localhost", port, "/submit", Absent))
                        .addField("body", "data")
                    c.sendWith(textRoute, req) { resp =>
                        // After 303, client MUST use GET for the redirect target
                        assert(
                            resp.status == HttpStatus.OK,
                            s"303 redirect should succeed with 200, got: ${resp.status}. " +
                                "If 405, the client likely preserved POST method instead of changing to GET per RFC 9110 §15.4.4"
                        )
                    }
                }
            }
        }
    }

    "Section 15.4.8 - 307 MUST preserve method and body" in run {
        // RFC 9110 §15.4.8: "The user agent MUST NOT change the request method if it performs
        // an automatic redirection to that URI."
        val postRoute1 = HttpRoute.postRaw("old").request(_.bodyText).response(_.bodyText)
        val postRoute2 = HttpRoute.postRaw("new").request(_.bodyText).response(_.bodyText)
        val ep1 = postRoute1.handler(_ =>
            HttpResponse.halt(HttpResponse(HttpStatus.TemporaryRedirect).setHeader("Location", "/new"))
        )
        val ep2 = postRoute2.handler(req => HttpResponse.okText(s"received: ${req.fields.body}"))
        withServer(ep1, ep2) { port =>
            HttpClient.withConfig(noTimeout) {
                withClient { c =>
                    val textRoute = HttpRoute.postText("")
                    val req = HttpRequest(HttpMethod.POST, HttpUrl(Present("http"), "localhost", port, "/old", Absent))
                        .addField("body", "payload")
                    c.sendWith(textRoute, req)(_.fields.body).map { body =>
                        assert(body == "received: payload", s"307 should preserve POST method and body, got: $body")
                    }
                }
            }
        }
    }

    "Section 15.4.9 - 308 MUST preserve method and body" in run {
        // RFC 9110 §15.4.9: "This status code is similar to 301 (Moved Permanently), except that
        // it does not allow changing the request method from POST to GET."
        val postRoute1 = HttpRoute.postRaw("old").request(_.bodyText).response(_.bodyText)
        val postRoute2 = HttpRoute.postRaw("new").request(_.bodyText).response(_.bodyText)
        val ep1 = postRoute1.handler(_ =>
            HttpResponse.halt(HttpResponse(HttpStatus.PermanentRedirect).setHeader("Location", "/new"))
        )
        val ep2 = postRoute2.handler(req => HttpResponse.okText(s"received: ${req.fields.body}"))
        withServer(ep1, ep2) { port =>
            HttpClient.withConfig(noTimeout) {
                withClient { c =>
                    val textRoute = HttpRoute.postText("")
                    val req = HttpRequest(HttpMethod.POST, HttpUrl(Present("http"), "localhost", port, "/old", Absent))
                        .addField("body", "payload")
                    c.sendWith(textRoute, req)(_.fields.body).map { body =>
                        assert(body == "received: payload", s"308 should preserve POST method and body, got: $body")
                    }
                }
            }
        }
    }

    "Section 15.4 - Redirect loop hits maxRedirects" in run {
        val route = HttpRoute.getRaw("loop").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.halt(HttpResponse(HttpStatus.Found).setHeader("Location", "/loop")))
        withServer(ep) { port =>
            HttpClient.withConfig(noTimeout) {
                withClient { c =>
                    val req = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", port, "/loop", Absent))
                    Abort.run(c.sendWith(rawRoute, req)(identity)).map { result =>
                        assert(result.isFailure, "Redirect loop should fail with TooManyRedirects")
                    }
                }
            }
        }
    }

    "Section 15.4 - Relative Location header resolved against request" in run {
        // RFC 9110 §15.4: "the Location header field [...] MAY be a relative reference"
        val route1 = HttpRoute.getRaw("old").response(_.bodyText)
        val route2 = HttpRoute.getRaw("new").response(_.bodyText)
        val ep1 = route1.handler(_ =>
            HttpResponse.halt(HttpResponse(HttpStatus.Found).setHeader("Location", "/new"))
        )
        val ep2 = route2.handler(_ => HttpResponse.okText("arrived"))
        withServer(ep1, ep2) { port =>
            HttpClient.withConfig(noTimeout) {
                withClient { c =>
                    val req = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", port, "/old", Absent))
                    c.sendWith(rawRoute, req)(_.fields.body).map { body =>
                        assert(body == "arrived", s"Relative redirect should resolve against request, got: $body")
                    }
                }
            }
        }
    }

    // ==================== Section 15.3.1: 413 Content Too Large ====================

    "Section 15.3.1 - 413 response body is readable" in run {
        // RFC 9110 §15.5.14: "The 413 (Content Too Large) status code indicates that the server is
        // refusing to process a request because the request content is larger than the server is
        // willing or able to process."
        val route = HttpRoute.postRaw("upload").request(_.bodyText).response(_.bodyText)
        val ep    = route.handler(req => HttpResponse.okText("ok"))
        // Use small maxContentLength
        val config = HttpServer.Config(port = 0, host = "localhost", maxContentLength = 10)
        Scope.run {
            HttpServer.init(config)(ep).map { server =>
                val bigBody   = "x" * 100 // exceeds 10-byte limit
                val sendRoute = HttpRoute.postRaw("upload").request(_.bodyText).response(_.bodyText)
                val req       = HttpRequest.postRaw(HttpUrl.fromUri("/upload")).addField("body", bigBody)
                send(server.port, sendRoute, req).map { resp =>
                    assert(
                        resp.status == HttpStatus.PayloadTooLarge || resp.status.code == 413,
                        s"Oversized body should return 413, got: ${resp.status}"
                    )
                }
            }
        }
    }

    // ==================== Section 9.3.1: GET ====================

    "Section 9.3.1 - GET returns 200 with body" in run {
        // RFC 9110 §9.3.1: "The GET method requests transfer of a current selected representation
        // for the target resource."
        val route = HttpRoute.getRaw("hello").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("hello world"))
        withServer(ep) { port =>
            send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                assert(resp.status == HttpStatus.OK, s"GET should return 200, got: ${resp.status}")
                assert(resp.fields.body == "hello world")
            }
        }
    }

    "Section 9.3.1 - GET with query parameters" in run {
        val route = HttpRoute.getRaw("search")
            .request(_.query[String]("q"))
            .response(_.bodyText)
        val ep = route.handler(req => HttpResponse.okText(s"query=${req.fields.q}"))
        withServer(ep) { port =>
            send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/search?q=hello")).addField("q", "hello")).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == "query=hello", s"Query param should reach handler, got: ${resp.fields.body}")
            }
        }
    }

    // ==================== Section 9.3.2: HEAD (additional) ====================

    "Section 9.3.2 - HEAD on non-existent resource returns 404" in run {
        val route = HttpRoute.getRaw("exists").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.headRaw(HttpUrl.fromUri("/nonexistent"))).map { resp =>
                assert(resp.status == HttpStatus.NotFound, s"HEAD on missing path should 404, got: ${resp.status}")
            }
        }
    }

    // ==================== Section 9.3.4: POST (additional) ====================

    "Section 9.3.4 - POST with JSON body round-trip" in run {
        val route = HttpRoute.postRaw("echo")
            .request(_.bodyJson[User])
            .response(_.bodyJson[User])
        val ep = route.handler(req => HttpResponse.okJson(req.fields.body))
        withServer(ep) { port =>
            val req = HttpRequest.postRaw(HttpUrl.fromUri("/echo"))
                .addField("body", User(7, "Bob"))
            send(port, route, req).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == User(7, "Bob"), s"JSON round-trip failed: ${resp.fields.body}")
            }
        }
    }

    // ==================== Section 9.3.6: PUT ====================

    "Section 9.3.6 - PUT returning 200 OK with body" in run {
        // RFC 9110 §9.3.6: "If the target resource does have a current representation and that
        // representation is successfully modified [...] the origin server MUST send [...] a 200 (OK)"
        val route = HttpRoute.putRaw("item")
            .request(_.bodyJson[User])
            .response(_.bodyJson[User])
        val ep = route.handler(req => HttpResponse.okJson(req.fields.body))
        withServer(ep) { port =>
            val req = HttpRequest.putRaw(HttpUrl.fromUri("/item"))
                .addField("body", User(1, "Updated"))
            send(port, route, req).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == User(1, "Updated"))
            }
        }
    }

    "Section 9.3.6 - PUT returning 201 Created" in run {
        // RFC 9110 §9.3.6: "If the target resource does not have a current representation
        // and the PUT successfully creates one, the origin server MUST [...] send a 201 (Created)"
        val route = HttpRoute.putRaw("item")
            .request(_.bodyJson[User])
            .response(_.bodyJson[User])
        val ep = route.handler { req =>
            HttpResponse(HttpStatus.Created)
                .setHeader("Location", "/item")
                .addField("body", req.fields.body)
        }
        withServer(ep) { port =>
            val req = HttpRequest.putRaw(HttpUrl.fromUri("/item"))
                .addField("body", User(1, "New"))
            send(port, route, req).map { resp =>
                assert(resp.status == HttpStatus.Created)
            }
        }
    }

    // ==================== Section 9.3.7: OPTIONS (additional) ====================

    "Section 9.3.7 - OPTIONS Allow lists all registered methods" in run {
        val getRoute    = HttpRoute.getRaw("multi").response(_.bodyText)
        val postRoute   = HttpRoute.postRaw("multi").request(_.bodyText).response(_.bodyText)
        val putRoute    = HttpRoute.putRaw("multi").request(_.bodyText).response(_.bodyText)
        val deleteRoute = HttpRoute.deleteRaw("multi").response(_.bodyText)
        val getEp       = getRoute.handler(_ => HttpResponse.okText("get"))
        val postEp      = postRoute.handler(_ => HttpResponse.okText("post"))
        val putEp       = putRoute.handler(_ => HttpResponse.okText("put"))
        val deleteEp    = deleteRoute.handler(_ => HttpResponse.okText("delete"))
        withServer(getEp, postEp, putEp, deleteEp) { port =>
            send(port, rawRoute, HttpRequest.optionsRaw(HttpUrl.fromUri("/multi"))).map { resp =>
                val allow = resp.headers.get("Allow").getOrElse("")
                assert(allow.contains("GET"), s"Allow missing GET: $allow")
                assert(allow.contains("POST"), s"Allow missing POST: $allow")
                assert(allow.contains("PUT"), s"Allow missing PUT: $allow")
                assert(allow.contains("DELETE"), s"Allow missing DELETE: $allow")
                assert(allow.contains("HEAD"), s"Allow missing HEAD: $allow")
                assert(allow.contains("OPTIONS"), s"Allow missing OPTIONS: $allow")
            }
        }
    }

    "Section 9.3.7 - OPTIONS response has no body" in run {
        val route = HttpRoute.getRaw("res").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.optionsRaw(HttpUrl.fromUri("/res"))).map { resp =>
                assert(resp.status == HttpStatus.NoContent)
                val body = resp.fields.body
                assert(body.isEmpty, s"OPTIONS response should have no body, got: '$body'")
            }
        }
    }

    // ==================== Section 9.3.3: PATCH ====================

    "Section 9.3.3 - PATCH with body" in run {
        val route = HttpRoute.patchRaw("item")
            .request(_.bodyJson[User])
            .response(_.bodyJson[User])
        val ep = route.handler(req => HttpResponse.okJson(req.fields.body))
        withServer(ep) { port =>
            val req = HttpRequest.patchRaw(HttpUrl.fromUri("/item"))
                .addField("body", User(1, "Patched"))
            send(port, route, req).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == User(1, "Patched"))
            }
        }
    }

    // ==================== Section 15.4: Redirects (additional) ====================

    "Section 15.4.2 - 301 Moved Permanently redirect followed" in run {
        val route1 = HttpRoute.getRaw("old-path").response(_.bodyText)
        val route2 = HttpRoute.getRaw("new-path").response(_.bodyText)
        val ep1 = route1.handler(_ =>
            HttpResponse.halt(HttpResponse.movedPermanently("/new-path"))
        )
        val ep2 = route2.handler(_ => HttpResponse.okText("new location"))
        withServer(ep1, ep2) { port =>
            HttpClient.withConfig(noTimeout) {
                withClient { c =>
                    val req = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", port, "/old-path", Absent))
                    c.sendWith(rawRoute, req)(_.fields.body).map { body =>
                        assert(body == "new location", s"301 redirect should be followed, got: $body")
                    }
                }
            }
        }
    }

    "Section 15.4.3 - 302 Found redirect followed" in run {
        val route1 = HttpRoute.getRaw("temp").response(_.bodyText)
        val route2 = HttpRoute.getRaw("target").response(_.bodyText)
        val ep1 = route1.handler(_ =>
            HttpResponse.halt(HttpResponse.redirect("/target"))
        )
        val ep2 = route2.handler(_ => HttpResponse.okText("target reached"))
        withServer(ep1, ep2) { port =>
            HttpClient.withConfig(noTimeout) {
                withClient { c =>
                    val req = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", port, "/temp", Absent))
                    c.sendWith(rawRoute, req)(_.fields.body).map { body =>
                        assert(body == "target reached", s"302 redirect should be followed, got: $body")
                    }
                }
            }
        }
    }

    "Section 15.4 - Client followRedirects=false stops redirect" in run {
        val route1 = HttpRoute.getRaw("redir").response(_.bodyText)
        val route2 = HttpRoute.getRaw("dest").response(_.bodyText)
        val ep1    = route1.handler(_ => HttpResponse.halt(HttpResponse.redirect("/dest")))
        val ep2    = route2.handler(_ => HttpResponse.okText("dest"))
        withServer(ep1, ep2) { port =>
            HttpClient.withConfig(noTimeout.copy(followRedirects = false)) {
                withClient { c =>
                    val req = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", port, "/redir", Absent))
                    c.sendWith(rawRoute, req) { resp =>
                        assert(resp.status == HttpStatus.Found, s"Should not follow redirect, got: ${resp.status}")
                        val loc = resp.headers.get("Location")
                        assert(loc == Present("/dest"), s"Location should be /dest, got: $loc")
                    }
                }
            }
        }
    }

    "Section 15.4 - maxRedirects=0 prevents any redirect" in run {
        val route1 = HttpRoute.getRaw("r").response(_.bodyText)
        val route2 = HttpRoute.getRaw("dest").response(_.bodyText)
        val ep1    = route1.handler(_ => HttpResponse.halt(HttpResponse.redirect("/dest")))
        val ep2    = route2.handler(_ => HttpResponse.okText("dest"))
        withServer(ep1, ep2) { port =>
            HttpClient.withConfig(noTimeout.copy(maxRedirects = 0)) {
                withClient { c =>
                    val req = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", port, "/r", Absent))
                    Abort.run(c.sendWith(rawRoute, req)(identity)).map { result =>
                        assert(result.isFailure, "maxRedirects=0 should fail on first redirect")
                    }
                }
            }
        }
    }

    // ==================== Section 15.5.5: 404 Not Found ====================

    "Section 15.5.5 - 404 Not Found for unknown path" in run {
        val route = HttpRoute.getRaw("exists").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/nonexistent"))).map { resp =>
                assert(resp.status == HttpStatus.NotFound, s"Unknown path should return 404, got: ${resp.status}")
            }
        }
    }

    // ==================== Section 15.5.6: 405 (additional) ====================

    "Section 15.5.6 - 405 Allow lists all registered methods for path" in run {
        val getRoute  = HttpRoute.getRaw("resource2").response(_.bodyText)
        val postRoute = HttpRoute.postRaw("resource2").request(_.bodyText).response(_.bodyText)
        val getEp     = getRoute.handler(_ => HttpResponse.okText("get"))
        val postEp    = postRoute.handler(_ => HttpResponse.okText("post"))
        withServer(getEp, postEp) { port =>
            send(port, rawRoute, HttpRequest.deleteRaw(HttpUrl.fromUri("/resource2"))).map { resp =>
                assert(resp.status == HttpStatus.MethodNotAllowed)
                val allow = resp.headers.get("Allow").getOrElse("")
                assert(allow.contains("GET"), s"Allow should list GET: $allow")
                assert(allow.contains("POST"), s"Allow should list POST: $allow")
                assert(allow.contains("HEAD"), s"Allow should list HEAD: $allow")
                assert(allow.contains("OPTIONS"), s"Allow should list OPTIONS: $allow")
            }
        }
    }

    // ==================== Section 8.3: Content-Type (additional) ====================

    "Section 8.3 - Content-Type application/octet-stream for binary body" in run {
        val route = HttpRoute.getRaw("bin").response(_.bodyBinary)
        val ep    = route.handler(_ => HttpResponse.okBinary(Span.fromUnsafe(Array[Byte](1, 2, 3))))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/bin"))).map { resp =>
                val ct = resp.headers.get("Content-Type").getOrElse("")
                assert(ct.contains("application/octet-stream"), s"Binary body should have octet-stream Content-Type, got: $ct")
            }
        }
    }

    "Section 8.3 - Handler-set Content-Type preserved" in run {
        val route = HttpRoute.getRaw("custom-ct").response(_.bodyText)
        val ep = route.handler(_ =>
            HttpResponse.okText("<html>hi</html>").setHeader("Content-Type", "text/html")
        )
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/custom-ct"))).map { resp =>
                val ct = resp.headers.get("Content-Type").getOrElse("")
                assert(ct.contains("text/html"), s"Handler-set Content-Type should be preserved, got: $ct")
            }
        }
    }

    // ==================== Section 6.6.1: Date (additional) ====================

    "Section 6.6.1 - Date header present on error responses" in run {
        val route = HttpRoute.getRaw("exists2").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/no-such-path"))).map { resp =>
                assert(resp.status == HttpStatus.NotFound)
                val date = resp.headers.get("Date")
                assert(date.isDefined, "Error responses should also include Date header")
            }
        }
    }

    // ==================== Section 8.6: Content-Length (additional) ====================

    "Section 8.6 - Content-Length is 0 for empty body" in run {
        val route = HttpRoute.getRaw("empty-body").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText(""))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/empty-body"))).map { resp =>
                val cl = resp.headers.get("Content-Length")
                assert(cl.isDefined, "Empty body should still have Content-Length")
                assert(cl.get == "0", s"Content-Length should be 0 for empty body, got: ${cl.get}")
            }
        }
    }

    "Section 8.6 - Content-Length matches actual bytes for UTF-8" in run {
        // "café" = 5 chars but 6 bytes in UTF-8 (é = 2 bytes)
        val route = HttpRoute.getRaw("utf8").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("café"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/utf8"))).map { resp =>
                val cl = resp.headers.get("Content-Length")
                assert(cl.isDefined, "UTF-8 body should have Content-Length")
                val expected = "café".getBytes("UTF-8").length.toString
                assert(cl.get == expected, s"Content-Length should be $expected bytes for 'café', got: ${cl.get}")
            }
        }
    }

    // ==================== Section 15.6.1: 500 (additional) ====================

    "Section 15.6.1 - 500 response has valid Content-Type" in run {
        val route = HttpRoute.getRaw("err").response(_.bodyText)
        val ep    = route.handler(_ => throw new RuntimeException("boom"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/err"))).map { resp =>
                assert(resp.status == HttpStatus.InternalServerError)
                val ct = resp.headers.get("Content-Type").getOrElse("")
                assert(ct.nonEmpty, "500 response should have Content-Type")
            }
        }
    }

    // ==================== Section 15.5.16: 415 (additional) ====================

    "Section 15.5.16 - text Content-Type to JSON endpoint returns 415" in run {
        val route = HttpRoute.postRaw("json-ep2")
            .request(_.bodyJson[User])
            .response(_.bodyText)
        val ep        = route.handler(req => HttpResponse.okText("ok"))
        val textRoute = HttpRoute.postRaw("json-ep2").request(_.bodyText).response(_.bodyText)
        withServer(ep) { port =>
            send(
                port,
                textRoute,
                HttpRequest.postRaw(HttpUrl.fromUri("/json-ep2"))
                    .addField("body", "plaintext")
            ).map { resp =>
                assert(
                    resp.status == HttpStatus.UnsupportedMediaType,
                    s"text/plain to JSON endpoint should return 415, got: ${resp.status}"
                )
            }
        }
    }

    // ==================== Section 15.5.1: 400 Bad Request ====================

    "Section 15.5.1 - 400 for malformed path param" in run {
        val route = HttpRoute.getRaw("items" / Capture[Int]("id")).response(_.bodyText)
        val ep    = route.handler(req => HttpResponse.okText(s"id=${req.fields.id}"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/items/notanumber"))).map { resp =>
                assert(
                    resp.status == HttpStatus.BadRequest || resp.status == HttpStatus.NotFound,
                    s"Invalid int path param should return 400 or 404, got: ${resp.status}"
                )
            }
        }
    }

    // ==================== Section 15.2.1: 200 OK ====================

    "Section 15.2.1 - 200 OK response with JSON body" in run {
        val route = HttpRoute.getRaw("user").response(_.bodyJson[User])
        val ep    = route.handler(_ => HttpResponse.okJson(User(1, "Alice")))
        withServer(ep) { port =>
            send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/user"))).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == User(1, "Alice"))
            }
        }
    }

    // ==================== Section 15.2.2: 201 Created ====================

    "Section 15.2.2 - 201 Created with Location header" in run {
        val route = HttpRoute.postRaw("items")
            .request(_.bodyText)
            .response(_.bodyText)
        val ep = route.handler { _ =>
            HttpResponse(HttpStatus.Created)
                .setHeader("Location", "/items/99")
                .addField("body", "created")
        }
        withServer(ep) { port =>
            val req = HttpRequest.postRaw(HttpUrl.fromUri("/items")).addField("body", "new item")
            send(port, route, req).map { resp =>
                assert(resp.status == HttpStatus.Created)
                assert(resp.headers.get("Location") == Present("/items/99"))
            }
        }
    }

    // ==================== Section 15.2.3: 202 Accepted ====================

    "Section 15.2.3 - 202 Accepted for async processing" in run {
        val route = HttpRoute.postRaw("jobs")
            .request(_.bodyText)
            .response(_.bodyText)
        val ep = route.handler(_ => HttpResponse.acceptedText("queued for processing"))
        withServer(ep) { port =>
            val req = HttpRequest.postRaw(HttpUrl.fromUri("/jobs")).addField("body", "job data")
            send(port, route, req).map { resp =>
                assert(resp.status == HttpStatus.Accepted)
                assert(resp.fields.body == "queued for processing")
            }
        }
    }

    // ==================== Section 15.5.4: 403 Forbidden ====================

    "Section 15.5.4 - 403 Forbidden response" in run {
        val route = HttpRoute.getRaw("secret").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.halt(HttpResponse.forbidden))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/secret"))).map { resp =>
                assert(resp.status == HttpStatus.Forbidden, s"Should return 403, got: ${resp.status}")
            }
        }
    }

    // ==================== Section 15.5.10: 409 Conflict ====================

    "Section 15.5.10 - 409 Conflict response" in run {
        val route = HttpRoute.getRaw("item-conflict").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.halt(HttpResponse.conflict))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/item-conflict"))).map { resp =>
                assert(resp.status == HttpStatus.Conflict, s"Should return 409, got: ${resp.status}")
            }
        }
    }

    // ==================== Section 15.6.4: 503 Service Unavailable ====================

    "Section 15.6.4 - 503 Service Unavailable response" in run {
        val route = HttpRoute.getRaw("down").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.halt(HttpResponse.serviceUnavailable))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/down"))).map { resp =>
                assert(resp.status == HttpStatus.ServiceUnavailable, s"Should return 503, got: ${resp.status}")
            }
        }
    }

    // ==================== Section 15.3.4: 303 See Other (additional) ====================

    "Section 15.4.4 - 303 from DELETE changes to GET" in run {
        // RFC 9110 §15.4.4: applies to any method, not just POST
        val deleteRoute = HttpRoute.deleteRaw("item-del").response(_.bodyText)
        val getRoute    = HttpRoute.getRaw("item-status").response(_.bodyText)
        val deleteEp = deleteRoute.handler(_ =>
            HttpResponse.halt(HttpResponse(HttpStatus.SeeOther).setHeader("Location", "/item-status"))
        )
        val getEp = getRoute.handler(_ => HttpResponse.okText("item deleted"))
        withServer(deleteEp, getEp) { port =>
            HttpClient.withConfig(noTimeout) {
                withClient { c =>
                    val textRoute = HttpRoute.deleteRaw("").response(_.bodyText)
                    val req       = HttpRequest.deleteRaw(HttpUrl(Present("http"), "localhost", port, "/item-del", Absent))
                    c.sendWith(textRoute, req) { resp =>
                        assert(
                            resp.status == HttpStatus.OK,
                            s"303 from DELETE should follow with GET and succeed, got: ${resp.status}"
                        )
                    }
                }
            }
        }
    }

    // ==================== Section 15.4 - Redirect with absolute URL ====================

    "Section 15.4 - Redirect with absolute URL in Location" in run {
        val route1 = HttpRoute.getRaw("abs-redir").response(_.bodyText)
        val route2 = HttpRoute.getRaw("abs-dest").response(_.bodyText)
        val ep2    = route2.handler(_ => HttpResponse.okText("absolute destination"))
        withServer(route1.handler(_ => HttpResponse.halt(HttpResponse(HttpStatus.Found))), ep2) { port =>
            // We need the port to construct the absolute URL, so use a different approach
            val route1b = HttpRoute.getRaw("abs-redir2").response(_.bodyText)
            val ep1b = route1b.handler(_ =>
                HttpResponse.halt(HttpResponse(HttpStatus.Found).setHeader("Location", s"http://localhost:$port/abs-dest"))
            )
            withServer(ep1b, ep2) { port2 =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val req = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", port2, "/abs-redir2", Absent))
                        c.sendWith(rawRoute, req)(_.fields.body).map { body =>
                            assert(body == "absolute destination", s"Absolute URL redirect should work, got: $body")
                        }
                    }
                }
            }
        }
    }

    // ==================== Custom response headers ====================

    "Section 8.1 - Custom response headers preserved" in run {
        val route = HttpRoute.getRaw("custom-hdr").response(_.bodyText)
        val ep = route.handler(_ =>
            HttpResponse.okText("ok")
                .setHeader("X-Custom-Header", "custom-value")
                .setHeader("X-Request-Id", "abc-123")
        )
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/custom-hdr"))).map { resp =>
                assert(resp.headers.get("X-Custom-Header") == Present("custom-value"))
                assert(resp.headers.get("X-Request-Id") == Present("abc-123"))
            }
        }
    }

end Rfc9110Test
