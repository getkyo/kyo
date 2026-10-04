package kyo

import kyo.*

class HttpExceptionTest extends BaseHttpTest:

    "HttpStatusException" - {
        "includes body when provided" in {
            val ex = HttpStatusException(HttpStatus.BadRequest, "POST", "http://x/y", "bad input")
            assert(ex.body == Maybe("bad input"))
            assert(ex.getMessage.contains("bad input"))
        }
        "omits body when absent" in {
            val ex = HttpStatusException(HttpStatus.InternalServerError, "GET", "http://x/y")
            assert(ex.body == Maybe.empty)
            assert(!ex.getMessage.contains("Body:"))
        }
        "truncates body over 500 chars" in {
            val longBody = "x" * 600
            val ex       = HttpStatusException(HttpStatus.BadRequest, "GET", "http://x/y", longBody)
            assert(ex.body == Maybe(longBody))
            assert(ex.getMessage.contains("..."))
            assert(!ex.getMessage.contains("x" * 600))
        }
        "strips query from url" in {
            val ex = HttpStatusException(HttpStatus.BadRequest, "GET", "http://x/y?token=abc", "err")
            assert(ex.url == "http://x/y")
        }
    }

    "HttpFieldDecodeException never carries the value that failed to decode" - {
        // The value can hold a credential: a query token, an Authorization header, a session cookie. It is assembled at run time so its
        // text appears nowhere in this file: a KyoException renders the source around its frame into getMessage, and a literal here would
        // be found in every message.
        val secret = Seq("s3cr3t", "token", "value").mkString("-")

        def mentions(t: Throwable): Boolean =
            Iterator.iterate(t)(_.getCause).takeWhile(_ != null).exists(e => Option(e.getMessage).exists(_.contains(secret)))

        def withServer[A, S](handlers: HttpHandler[?, ?, ?]*)(test: Int => A < (S & Async & Abort[HttpException]))(using
            Frame
        ): A < (S & Async & Scope & Abort[HttpException]) =
            HttpServer.init(0, "127.0.0.1")(handlers*).map(server => test(server.port))

        def send[In, Out](port: Int, route: HttpRoute[In, Out, Any], request: HttpRequest[In])(using
            Frame
        ): HttpResponse[Out] < (Async & Abort[HttpException]) =
            HttpClient.use { client =>
                client.sendWith(
                    route,
                    request.copy(url = HttpUrl(Present("http"), "localhost", port, request.url.path, request.url.rawQuery))
                )(
                    identity
                )
            }

        "a query parameter: the server's 400 answer does not echo it" in {
            val endpoint = HttpRoute.getRaw("count").request(_.query[Int]("token")).response(_.bodyText).handler(_ => HttpResponse.ok("ok"))
            val raw      = HttpRoute.getRaw("count").response(_.bodyText)
            withServer(endpoint) { port =>
                Abort.run[HttpException](send(port, raw, HttpRequest.getRaw(HttpUrl.fromUri(s"/count?token=$secret")))).map {
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.BadRequest, s"status ${resp.status}")
                        assert(!resp.fields.body.contains(secret), s"the 400 body echoes the value: ${resp.fields.body}")
                    case Result.Failure(e: HttpStatusException) =>
                        assert(e.status == HttpStatus.BadRequest, s"status ${e.status}")
                        assert(!e.body.exists(_.contains(secret)) && !mentions(e), s"the 400 answer echoes the value: $e")
                    case other => fail(s"expected a 400 answer, got $other")
                }
            }
        }

        "a response header: the client's exception names the field and type but not the value" in {
            val endpoint = HttpRoute.getRaw("hdr").response(_.bodyText).handler(_ => HttpResponse.ok("ok").setHeader("X-Count", secret))
            val typed    = HttpRoute.getRaw("hdr").response(_.header[Int]("X-Count").bodyText)
            withServer(endpoint) { port =>
                Abort.run[HttpException](send(port, typed, HttpRequest.getRaw(HttpUrl.fromUri("/hdr")))).map {
                    case Result.Failure(e: HttpFieldDecodeException) =>
                        assert(!mentions(e), s"the exception or its cause holds the value: ${e.getMessage}")
                        assert(!e.productIterator.exists(_.toString.contains(secret)), s"a field holds the value: $e")
                        assert(e.fieldName == "X-Count" && e.typeName == Present("Int"), s"field ${e.fieldName}, type ${e.typeName}")
                        assert(e.errorClass == "java.lang.NumberFormatException", s"error class ${e.errorClass}")
                    case other => fail(s"expected HttpFieldDecodeException, got $other")
                }
            }
        }
    }

    // Built here, away from the leaves: KyoException renders the source around its frame into getMessage, so a leaf that constructed the
    // exception next to the sentences it checks would find every sentence in every message.
    private def closedIn(phase: HttpConnectionClosedException.Phase): HttpConnectionClosedException =
        HttpConnectionClosedException(phase)

    "HttpConnectionClosedException" - {
        import HttpConnectionClosedException.Phase

        "is a connectivity failure, whatever its phase" in {
            val before: HttpConnectionException = HttpConnectionClosedException(Phase.BeforeHead)
            val body: HttpConnectionException   = HttpConnectionClosedException(Phase.BodyTruncated)
            val tls: HttpConnectionException    = HttpConnectionClosedException(Phase.TlsTruncated)
            val asAny: HttpException            = before
            assert(Seq(before, body, tls).forall(_.isInstanceOf[HttpConnectionException]))
            assert(!asAny.isInstanceOf[HttpDecodeException])
        }

        "builds its message from the phase" in {
            val beforeHead = "The connection closed before the message head arrived."
            val body       = "The connection closed before the body its framing declared was complete."
            val tls        =
                "The connection ended without the peer's TLS close_notify, so the close-framed body is incomplete (RFC 9112 section 9.8)."
            assert(closedIn(Phase.BeforeHead).getMessage.contains(beforeHead))
            assert(!closedIn(Phase.BeforeHead).getMessage.contains(body) && !closedIn(Phase.BeforeHead).getMessage.contains(tls))
            assert(closedIn(Phase.BodyTruncated).getMessage.contains(body))
            assert(!closedIn(Phase.BodyTruncated).getMessage.contains(beforeHead) &&
                !closedIn(Phase.BodyTruncated).getMessage.contains(tls))
            assert(closedIn(Phase.TlsTruncated).getMessage.contains(tls))
            assert(!closedIn(Phase.TlsTruncated).getMessage.contains(beforeHead) && !closedIn(Phase.TlsTruncated).getMessage.contains(body))
        }

        "two closes in the same phase are equal, in different phases not" in {
            assert(HttpConnectionClosedException(Phase.BeforeHead) == HttpConnectionClosedException(Phase.BeforeHead))
            assert(HttpConnectionClosedException(Phase.BeforeHead) != HttpConnectionClosedException(Phase.BodyTruncated))
        }
    }

end HttpExceptionTest
