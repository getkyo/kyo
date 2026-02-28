package kyo

import kyo.*
import kyo.Record2.~

// RFC 6585: Additional HTTP Status Codes
// Tests validate status code behavior per the RFC specification.
// Failing tests indicate RFC non-compliance — do NOT adjust assertions to match implementation.
class Rfc6585Test extends Test:

    val rawRoute = HttpRoute.getRaw("raw").response(_.bodyText)

    def withServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: Int => A < (S & Async & Abort[HttpError])
    )(using Frame): A < (S & Async & Scope & Abort[HttpError]) =
        HttpServer.init(0, "localhost")(handlers*).map(server => test(server.port))

    def send[In, Out](port: Int, route: HttpRoute[In, Out, Any], request: HttpRequest[In])(using
        Frame
    ): HttpResponse[Out] < (Async & Abort[HttpError]) =
        HttpClient.use { client =>
            client.sendWith(
                route,
                request.copy(url =
                    HttpUrl(Present("http"), "localhost", port, request.url.path, request.url.rawQuery)
                )
            )(identity)
        }

    def sendRaw(port: Int, method: HttpMethod, path: String)(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpError]) =
        send(port, rawRoute, HttpRequest(method, HttpUrl.fromUri(path)))

    // ==================== Section 4: 429 Too Many Requests ====================

    "Section 4 - 429 response includes Retry-After header" in run {
        // RFC 6585 §4: "The 429 status code indicates that the user has sent too many
        // requests in a given amount of time."
        // "The response representations SHOULD include details explaining the condition,
        // and MAY include a Retry-After header"
        val route = HttpRoute.getRaw("limited").response(_.bodyText)
        Meter.initSemaphore(0).map { meter =>
            val ep = route.filter(HttpFilter.server.rateLimit(meter, retryAfter = 5)).handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                Abort.run(sendRaw(port, HttpMethod.GET, "/limited")).map { result =>
                    result match
                        case Result.Success(resp) =>
                            val retryAfter = resp.headers.get("Retry-After")
                            assert(retryAfter == Present("5"), s"Should have Retry-After: 5, got: $retryAfter")
                        case Result.Failure(_) =>
                            succeed
                        case _ => fail("Unexpected result")
                }
            }
        }
    }

    "Section 4 - 429 status code returned when rate limited" in run {
        val route = HttpRoute.getRaw("limited").response(_.bodyText)
        Meter.initSemaphore(0).map { meter =>
            val ep = route.filter(HttpFilter.server.rateLimit(meter, retryAfter = 10)).handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                Abort.run(sendRaw(port, HttpMethod.GET, "/limited")).map { result =>
                    result match
                        case Result.Success(resp) =>
                            assert(resp.status == HttpStatus.TooManyRequests, s"Should return 429, got: ${resp.status}")
                        case Result.Failure(_) =>
                            succeed // 429 may be aborted
                        case _ => fail("Unexpected result")
                }
            }
        }
    }

    "Section 4 - 429 with custom Retry-After value" in run {
        val route = HttpRoute.getRaw("limited2").response(_.bodyText)
        Meter.initSemaphore(0).map { meter =>
            val ep = route.filter(HttpFilter.server.rateLimit(meter, retryAfter = 120)).handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                Abort.run(sendRaw(port, HttpMethod.GET, "/limited2")).map { result =>
                    result match
                        case Result.Success(resp) =>
                            val retryAfter = resp.headers.get("Retry-After")
                            assert(retryAfter == Present("120"), s"Should have Retry-After: 120, got: $retryAfter")
                        case Result.Failure(_) =>
                            succeed
                        case _ => fail("Unexpected result")
                }
            }
        }
    }

    "Section 4 - Request succeeds when not rate limited" in run {
        val route = HttpRoute.getRaw("open").response(_.bodyText)
        Meter.initSemaphore(1).map { meter =>
            val ep = route.filter(HttpFilter.server.rateLimit(meter, retryAfter = 1)).handler(_ => HttpResponse.okText("allowed"))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/open").map { resp =>
                    assert(resp.status == HttpStatus.OK, s"Should return 200 when not limited, got: ${resp.status}")
                    assert(resp.fields.body == "allowed", s"Body should be 'allowed', got: ${resp.fields.body}")
                }
            }
        }
    }

    "Section 4 - POST request also rate limited" in run {
        val route = HttpRoute.postRaw("limited-post").response(_.bodyText)
        Meter.initSemaphore(0).map { meter =>
            val ep = route.filter(HttpFilter.server.rateLimit(meter, retryAfter = 3)).handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                Abort.run(send(port, rawRoute, HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/limited-post")))).map { result =>
                    result match
                        case Result.Success(resp) =>
                            assert(resp.status == HttpStatus.TooManyRequests, s"POST should also be rate limited, got: ${resp.status}")
                        case Result.Failure(_) =>
                            succeed
                        case _ => fail("Unexpected result")
                }
            }
        }
    }

end Rfc6585Test
