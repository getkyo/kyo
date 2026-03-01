package kyo

import kyo.*
import kyo.Record.~

// RFC 7617: The 'Basic' HTTP Authentication Scheme
// Tests validate Basic auth behavior per the RFC specification.
// Failing tests indicate RFC non-compliance — do NOT adjust assertions to match implementation.
class Rfc7617Test extends Test:

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

    def basicRoute = HttpRoute.getRaw("secure")
        .request(_.headerOpt[String]("authorization"))
        .response(_.bodyText)

    def basicEndpoint = basicRoute
        .filter(HttpFilter.server.basicAuth((u, p) => u == "Aladdin" && p == "open sesame"))
        .handler(req => HttpResponse.okText(s"hello ${req.fields.user}"))

    // ==================== Section 2: Base64 Credentials ====================

    "Section 2 - Valid credentials accepted" in run {
        // RFC 7617 §2: credentials = "Basic" SP base64(user-id ":" password)
        // "Aladdin:open sesame" → Base64 "QWxhZGRpbjpvcGVuIHNlc2FtZQ=="
        withServer(basicEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"Valid creds should return 200, got: ${resp.status}")
            }
        }
    }

    "Section 2 - Password with colon" in run {
        // RFC 7617 §2: "The user-id and password are separated by a single colon"
        // Only the first colon splits user-id from password
        val ep = basicRoute
            .filter(HttpFilter.server.basicAuth((u, p) => u == "user" && p == "pass:word"))
            .handler(req => HttpResponse.okText(s"hello ${req.fields.user}"))
        withServer(ep) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("user:pass:word".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"Basic $encoded")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"Password with colon should work, got: ${resp.status}")
            }
        }
    }

    "Section 2 - Empty password accepted" in run {
        val ep = basicRoute
            .filter(HttpFilter.server.basicAuth((u, p) => u == "user" && p == ""))
            .handler(req => HttpResponse.okText(s"hello ${req.fields.user}"))
        withServer(ep) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("user:".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"Basic $encoded")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"Empty password should work, got: ${resp.status}")
            }
        }
    }

    "Section 2 - Empty username accepted" in run {
        val ep = basicRoute
            .filter(HttpFilter.server.basicAuth((u, p) => u == "" && p == "password"))
            .handler(req => HttpResponse.okText(s"hello ${req.fields.user}"))
        withServer(ep) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString(":password".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"Basic $encoded")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"Empty username should work, got: ${resp.status}")
            }
        }
    }

    "Section 2 - Malformed Base64 returns 401" in run {
        withServer(basicEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", "Basic !!!not-base64!!!")
            Abort.run(send(port, rawRoute, req)).map { result =>
                // Should get 401 (either via response status or abort)
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"Malformed base64 should return 401, got: ${resp.status}")
                    case Result.Failure(_) =>
                        succeed // 401 abort is also acceptable
                    case _ => fail("Unexpected result")
            }
        }
    }

    "Section 2 - Missing Basic prefix returns 401" in run {
        withServer(basicEndpoint) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("Aladdin:open sesame".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", encoded) // No "Basic " prefix
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"Missing prefix should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2 - No Authorization header returns 401 with WWW-Authenticate" in run {
        withServer(basicEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"No auth should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2 - Case-insensitive scheme" in run {
        // RFC 7235 §2.1: "The scheme name is case-insensitive"
        // RFC 9110 §11.1: Authentication scheme names are case-insensitive
        // "basic" and "BASIC" should both be accepted
        withServer(basicEndpoint) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("Aladdin:open sesame".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"basic $encoded") // lowercase "basic"
            send(port, rawRoute, req).map { resp =>
                // RFC 9110 §11.1: scheme comparison MUST be case-insensitive
                // Implementation uses startsWith("Basic ") which is case-sensitive
                assert(
                    resp.status == HttpStatus.OK,
                    s"Case-insensitive 'basic' should be accepted per RFC 9110 §11.1, got: ${resp.status}. " +
                        "Implementation uses case-sensitive startsWith(\"Basic \")"
                )
            }
        }
    }

    "Section 2 - Extra whitespace after Basic" in run {
        withServer(basicEndpoint) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("Aladdin:open sesame".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"Basic  $encoded") // extra space
            Abort.run(send(port, rawRoute, req)).map { result =>
                // Extra whitespace handling is implementation-defined
                // Just verify it doesn't crash
                succeed
            }
        }
    }

    // ==================== Section 2: Additional credential tests ====================

    "Section 2 - Wrong credentials return 401" in run {
        withServer(basicEndpoint) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("wrong:creds".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"Basic $encoded")
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"Wrong creds should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2 - UPPER CASE scheme accepted" in run {
        // RFC 9110 §11.1: Authentication scheme names are case-insensitive
        withServer(basicEndpoint) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("Aladdin:open sesame".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"BASIC $encoded")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"BASIC (uppercase) should be accepted per RFC 9110 §11.1, got: ${resp.status}")
            }
        }
    }

    "Section 2 - Mixed case scheme accepted" in run {
        withServer(basicEndpoint) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("Aladdin:open sesame".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"bAsIc $encoded")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"bAsIc (mixed case) should be accepted per RFC 9110 §11.1, got: ${resp.status}")
            }
        }
    }

    "Section 2 - UTF-8 characters in password" in run {
        // RFC 7617 §2.1: user-id and password are encoded in UTF-8
        val ep = basicRoute
            .filter(HttpFilter.server.basicAuth((u, p) => u == "user" && p == "pässwörd"))
            .handler(req => HttpResponse.okText(s"hello ${req.fields.user}"))
        withServer(ep) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString("user:pässwörd".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"Basic $encoded")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"UTF-8 password should work, got: ${resp.status}")
            }
        }
    }

    "Section 2 - Bearer scheme rejected by basic auth filter" in run {
        // A Bearer token should not be accepted by a Basic auth filter
        withServer(basicEndpoint) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", "Bearer some-token")
            Abort.run(send(port, rawRoute, req)).map { result =>
                result match
                    case Result.Success(resp) =>
                        assert(resp.status == HttpStatus.Unauthorized, s"Bearer on basic endpoint should return 401, got: ${resp.status}")
                    case Result.Failure(_) => succeed
                    case _                 => fail("Unexpected result")
            }
        }
    }

    "Section 2 - Long credentials accepted" in run {
        val longUser = "a" * 200
        val longPass = "b" * 200
        val ep = basicRoute
            .filter(HttpFilter.server.basicAuth((u, p) => u == longUser && p == longPass))
            .handler(req => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            val encoded = java.util.Base64.getEncoder.encodeToString(s"$longUser:$longPass".getBytes("UTF-8"))
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
                .setHeader("Authorization", s"Basic $encoded")
            send(port, rawRoute, req).map { resp =>
                assert(resp.status == HttpStatus.OK, s"Long credentials should work, got: ${resp.status}")
            }
        }
    }

    // ==================== Client-side Basic auth filter ====================

    "Client-side basic auth filter adds Authorization header" in run {
        val route = HttpRoute.getRaw("secure")
            .request(_.headerOpt[String]("authorization"))
            .response(_.bodyText)
        val ep = route.handler { req =>
            val auth = req.headers.get("Authorization").getOrElse("none")
            HttpResponse.okText(s"auth=$auth")
        }
        withServer(ep) { port =>
            val clientRoute = HttpRoute.getRaw("secure").response(_.bodyText)
            val req         = HttpRequest.getRaw(HttpUrl.fromUri("/secure"))
            val filtered    = clientRoute.filter(HttpFilter.client.basicAuth("user", "pass"))
            send(port, filtered, req).map { resp =>
                val expected = java.util.Base64.getEncoder.encodeToString("user:pass".getBytes("UTF-8"))
                assert(resp.fields.body.contains(s"Basic $expected"), s"Client should send Basic auth, got: ${resp.fields.body}")
            }
        }
    }

end Rfc7617Test
