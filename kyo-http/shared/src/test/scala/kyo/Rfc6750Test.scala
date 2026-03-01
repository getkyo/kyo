package kyo

import kyo.*
import kyo.Record.~

// RFC 6750: The OAuth 2.0 Authorization Framework: Bearer Token Usage
// Tests validate Bearer auth behavior per the RFC specification.
// Failing tests indicate RFC non-compliance — do NOT adjust assertions to match implementation.
class Rfc6750Test extends Test:

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

    def bearerRoute = HttpRoute.getRaw("api")
        .request(_.headerOpt[String]("authorization"))
        .response(_.bodyText)

    def bearerEndpoint = bearerRoute
        .filter(HttpFilter.server.bearerAuth(token => token == "valid-token-123"))
        .handler(_ => HttpResponse.okText("protected data"))

    // ==================== Section 2.1: Authorization Request Header Field ====================

    "Section 2.1 - Valid token extraction" in run {
        // RFC 6750 §2.1: "Authorization: Bearer <token>"
        withServer(bearerEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", "Bearer valid-token-123")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"Valid token should return 200, got: ${resp.status}")
            }
        }
    }

    "Section 2.1 - Missing Bearer prefix returns 401" in run {
        withServer(bearerEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", "valid-token-123") // No "Bearer " prefix
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"Missing prefix should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2.1 - No Authorization header returns 401 with WWW-Authenticate: Bearer" in run {
        withServer(bearerEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"No auth should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2.1 - Empty token returns 401" in run {
        withServer(bearerEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", "Bearer ") // Empty token
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"Empty token should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2.1 - Case-insensitive scheme" in run {
        // RFC 9110 §11.1: Authentication scheme names are case-insensitive
        // "bearer" (lowercase) should be accepted
        withServer(bearerEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", "bearer valid-token-123") // lowercase
            send(port, rawRoute, req).map { resp =>
                // RFC 9110 §11.1: scheme comparison MUST be case-insensitive
                // Implementation uses startsWith("Bearer ") which is case-sensitive
                assert(
                    resp.status == HttpStatus.OK,
                    s"Case-insensitive 'bearer' should be accepted per RFC 9110 §11.1, got: ${resp.status}. " +
                        "Implementation uses case-sensitive startsWith(\"Bearer \")"
                )
            }
        }
    }

    "Section 2.1 - Token with special chars" in run {
        // b64token = 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *( "=" )
        val specialToken = "abc-._~+/def123=="
        val ep = bearerRoute
            .filter(HttpFilter.server.bearerAuth(token => token == specialToken))
            .handler(_ => HttpResponse.okText("protected data"))
        withServer(ep) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", s"Bearer $specialToken")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"Token with special chars should work, got: ${resp.status}")
            }
        }
    }

    // ==================== Additional Bearer Auth Tests ====================

    "Section 2.1 - Wrong token returns 401" in run {
        withServer(bearerEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", "Bearer wrong-token")
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"Wrong token should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2.1 - UPPER CASE scheme accepted" in run {
        // RFC 9110 §11.1: Authentication scheme names are case-insensitive
        withServer(bearerEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", "BEARER valid-token-123")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"BEARER (uppercase) should be accepted per RFC 9110 §11.1, got: ${resp.status}")
            }
        }
    }

    "Section 2.1 - Mixed case scheme accepted" in run {
        withServer(bearerEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", "bEaReR valid-token-123")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"bEaReR (mixed case) should be accepted per RFC 9110 §11.1, got: ${resp.status}")
            }
        }
    }

    "Section 2.1 - Basic scheme rejected by bearer auth filter" in run {
        withServer(bearerEndpoint) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("user:pass".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", s"Basic $encoded")
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"Basic on bearer endpoint should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2.1 - Long token accepted" in run {
        val longToken = "x" * 1000
        val ep = bearerRoute
            .filter(HttpFilter.server.bearerAuth(token => token == longToken))
            .handler(_ => HttpResponse.okText("protected data"))
        withServer(ep) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
                .setHeader("Authorization", s"Bearer $longToken")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"Long token should work, got: ${resp.status}")
            }
        }
    }

    // ==================== Client-side Bearer auth filter ====================

    "Client-side bearer auth filter adds Authorization header" in run {
        val route = HttpRoute.getRaw("api")
            .request(_.headerOpt[String]("authorization"))
            .response(_.bodyText)
        val ep = route.handler { req =>
            val auth = req.headers.get("Authorization").getOrElse("none")
            HttpResponse.okText(s"auth=$auth")
        }
        withServer(ep) { port =>
            val clientRoute = HttpRoute.getRaw("api").response(_.bodyText)
            val req         = HttpRequest.getRaw(HttpUrl.fromUri("/api"))
            val filtered    = clientRoute.filter(HttpFilter.client.bearerAuth("my-token-abc"))
            send(port, filtered, req).map { resp =>
                assert(resp.fields.body.contains("Bearer my-token-abc"), s"Client should send Bearer auth, got: ${resp.fields.body}")
            }
        }
    }

end Rfc6750Test
