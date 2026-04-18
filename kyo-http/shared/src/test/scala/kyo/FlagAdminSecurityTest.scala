package kyo

import kyo.*
import scala.language.implicitConversions

// Separate test flags for security tests
object flagAdminSecurityTestFlags:
    object dynamicFlag extends DynamicFlag[String]("secure-default")
    object staticFlag  extends StaticFlag[Int](7)
end flagAdminSecurityTestFlags

class FlagAdminSecurityTest extends Test:

    import HttpPath.*

    val client = kyo.internal.HttpPlatformBackend.client

    // Ensure test flags are loaded
    locally {
        val _ = flagAdminSecurityTestFlags.dynamicFlag
        val _ = flagAdminSecurityTestFlags.staticFlag
    }

    def withServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: Int => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "localhost")(handlers*).map(server => test(server.port))

    def send[In, Out](
        port: Int,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        client.connectWith("localhost", port, ssl = false, Absent) { conn =>
            Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                client.sendWith(conn, route, request)(identity)
            }
        }

    val textRoute = HttpRoute.getRaw("raw").response(_.bodyText)

    def sendRaw(
        port: Int,
        method: HttpMethod,
        path: String
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        send(port, textRoute, HttpRequest(method, HttpUrl.fromUri(path)))

    val putTextRoute = HttpRoute.putRaw("raw")
        .request(_.bodyText)
        .response(_.bodyText)

    def sendPut(
        port: Int,
        path: String,
        body: String,
        headers: Seq[(String, String)] = Seq.empty
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        var req = HttpRequest(HttpMethod.PUT, HttpUrl.fromUri(path))
            .addField("body", body)
        headers.foreach { case (k, v) => req = req.setHeader(k, v) }
        send(port, putTextRoute, req)
    end sendPut

    val postTextRoute = HttpRoute.postRaw("raw")
        .response(_.bodyText)

    def sendPost(
        port: Int,
        path: String,
        headers: Seq[(String, String)] = Seq.empty
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        var req = HttpRequest(HttpMethod.POST, HttpUrl.fromUri(path))
        headers.foreach { case (k, v) => req = req.setHeader(k, v) }
        send(port, postTextRoute, req)
    end sendPost

    val jsonError = Json[FlagAdmin.ErrorResponse]

    val testToken = "test-secret-token-12345"

    def setToken(): Unit   = discard(java.lang.System.setProperty("kyo.flag.admin.token", testToken))
    def clearToken(): Unit = discard(java.lang.System.clearProperty("kyo.flag.admin.token"))

    "no token configured — PUT allowed without auth" in run {
        clearToken()
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminSecurityTestFlags.dynamicFlag", "no-auth-value").map { resp =>
                clearToken()
                assert(resp.status == HttpStatus.OK)
            }
        }
    }

    "token configured — GET allowed without auth" in run {
        setToken()
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags").map { resp =>
                clearToken()
                assert(resp.status == HttpStatus.OK)
            }
        }
    }

    "token configured — PUT without auth returns 401" in run {
        setToken()
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminSecurityTestFlags.dynamicFlag", "blocked").map { resp =>
                clearToken()
                assert(resp.status == HttpStatus.Unauthorized)
            }
        }
    }

    "token configured — PUT with correct token succeeds" in run {
        setToken()
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(
                port,
                "/flags/kyo.flagAdminSecurityTestFlags.dynamicFlag",
                "authed-value",
                Seq("Authorization" -> s"Bearer $testToken")
            ).map { resp =>
                clearToken()
                assert(resp.status == HttpStatus.OK)
            }
        }
    }

    "token configured — PUT with wrong token returns 401" in run {
        setToken()
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(
                port,
                "/flags/kyo.flagAdminSecurityTestFlags.dynamicFlag",
                "wrong-auth",
                Seq("Authorization" -> "Bearer wrong-token")
            ).map { resp =>
                clearToken()
                assert(resp.status == HttpStatus.Unauthorized)
            }
        }
    }

    "token configured — POST reload requires auth" in run {
        setToken()
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPost(port, "/flags/kyo.flagAdminSecurityTestFlags.dynamicFlag/reload").map { resp =>
                clearToken()
                assert(resp.status == HttpStatus.Unauthorized)
            }
        }
    }

    "read-only mode — GET works" in run {
        clearToken()
        val handlers = FlagAdmin.routes("flags", readOnly = true)
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags").map { resp =>
                assert(resp.status == HttpStatus.OK)
            }
        }
    }

    "read-only mode — PUT blocked with 403" in run {
        clearToken()
        val handlers = FlagAdmin.routes("flags", readOnly = true)
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminSecurityTestFlags.dynamicFlag", "blocked").map { resp =>
                val err = jsonError.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(resp.status == HttpStatus.Forbidden)
                assert(err.error.contains("read-only"))
            }
        }
    }

    "read-only mode — POST blocked with 403" in run {
        clearToken()
        val handlers = FlagAdmin.routes("flags", readOnly = true)
        withServer(handlers*) { port =>
            sendPost(port, "/flags/kyo.flagAdminSecurityTestFlags.dynamicFlag/reload").map { resp =>
                assert(resp.status == HttpStatus.Forbidden)
            }
        }
    }

end FlagAdminSecurityTest
