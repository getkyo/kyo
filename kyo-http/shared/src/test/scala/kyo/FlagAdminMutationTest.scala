package kyo

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import scala.language.implicitConversions

// Separate test flags for mutation tests to avoid interference with other tests
object flagAdminMutationTestFlags:
    object staticFlag  extends StaticFlag[Int](99)
    object dynamicFlag extends DynamicFlag[Boolean](false)
    object dynamicInt  extends DynamicFlag[Int](0)
    object dynamicStr  extends DynamicFlag[String]("original")
    object reloadFlag  extends DynamicFlag[String]("default-val")
end flagAdminMutationTestFlags

class FlagAdminMutationTest extends Test:

    import HttpPath.*

    val client = kyo.internal.HttpPlatformBackend.client

    // Ensure test flags are loaded
    locally {
        val _ = flagAdminMutationTestFlags.staticFlag
        val _ = flagAdminMutationTestFlags.dynamicFlag
        val _ = flagAdminMutationTestFlags.dynamicInt
        val _ = flagAdminMutationTestFlags.dynamicStr
        val _ = flagAdminMutationTestFlags.reloadFlag
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

    // For PUT requests with text body, use a route that has body text input
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

    "PUT updates DynamicFlag expression" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.dynamicStr", "newvalue").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val info = Json.decode[FlagAdmin.FlagInfo](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(info.expression == Some("newvalue"))
            }
        }
    }

    "PUT response includes new expression" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.dynamicStr", "updated-expr").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val info = Json.decode[FlagAdmin.FlagInfo](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(info.name == "kyo.flagAdminMutationTestFlags.dynamicStr")
                assert(info.expression == Some("updated-expr"))
            }
        }
    }

    "PUT on static flag returns 409" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.staticFlag", "123").map { resp =>
                assert(resp.status == HttpStatus.Conflict)
                val err = Json.decode[FlagAdmin.ErrorResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(err.error.contains("static"))
                assert(err.error.contains("cannot be updated"))
            }
        }
    }

    "PUT on unknown flag returns 404" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/nonexistent.flag", "value").map { resp =>
                assert(resp.status == HttpStatus.NotFound)
                val err = Json.decode[FlagAdmin.ErrorResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(err.error.contains("not found"))
            }
        }
    }

    "PUT with invalid expression returns 400" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.dynamicInt", "notanumber").map { resp =>
                assert(resp.status == HttpStatus.BadRequest)
                val err = Json.decode[FlagAdmin.ErrorResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(err.error.nonEmpty)
            }
        }
    }

    "PUT safe rollback — after 400 error, old expression preserved" in run {
        val handlers = FlagAdmin.routes("flags")
        // First set a known good value
        flagAdminMutationTestFlags.dynamicInt.update("42")
        withServer(handlers*) { port =>
            for
                // Try an invalid update
                badResp <- sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.dynamicInt", "notanumber")
                // Verify old expression is preserved
                getResp <- sendRaw(port, HttpMethod.GET, "/flags/kyo.flagAdminMutationTestFlags.dynamicInt")
            yield
                assert(badResp.status == HttpStatus.BadRequest)
                val info = Json.decode[FlagAdmin.FlagInfo](getResp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(info.expression == Some("42"))
        }
    }

    "PUT with empty body clears expression, flag uses default" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.dynamicStr", "").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val info = Json.decode[FlagAdmin.FlagInfo](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(info.expression == Some(""))
            }
        }
    }

    "POST reload on dynamic flag" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPost(port, "/flags/kyo.flagAdminMutationTestFlags.reloadFlag/reload").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val reload =
                    Json.decode[FlagAdmin.ReloadResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(reload.name == "kyo.flagAdminMutationTestFlags.reloadFlag")
            }
        }
    }

    "POST reload returns reloaded status" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPost(port, "/flags/kyo.flagAdminMutationTestFlags.reloadFlag/reload").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val reload =
                    Json.decode[FlagAdmin.ReloadResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                // Source is Default, so reloaded should be false with reason
                assert(!reload.reloaded)
                assert(reload.reason.isDefined)
            }
        }
    }

    "POST reload on static flag returns 409" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPost(port, "/flags/kyo.flagAdminMutationTestFlags.staticFlag/reload").map { resp =>
                assert(resp.status == HttpStatus.Conflict)
                val err = Json.decode[FlagAdmin.ErrorResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(err.error.contains("static"))
                assert(err.error.contains("cannot be reloaded"))
            }
        }
    }

    "POST reload on Default-source flag returns no_source reason" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPost(port, "/flags/kyo.flagAdminMutationTestFlags.reloadFlag/reload").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val reload =
                    Json.decode[FlagAdmin.ReloadResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(!reload.reloaded)
                assert(reload.reason.exists(_.contains("Default")))
            }
        }
    }

    "POST reload on unknown flag returns 404" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPost(port, "/flags/nonexistent.flag/reload").map { resp =>
                assert(resp.status == HttpStatus.NotFound)
            }
        }
    }

    "PUT then GET consistency" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            for
                putResp <- sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.dynamicStr", "consistency-check")
                getResp <- sendRaw(port, HttpMethod.GET, "/flags/kyo.flagAdminMutationTestFlags.dynamicStr")
            yield
                assert(putResp.status == HttpStatus.OK)
                val putInfo = Json.decode[FlagAdmin.FlagInfo](putResp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                val getInfo = Json.decode[FlagAdmin.FlagInfo](getResp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(putInfo.expression == getInfo.expression)
                assert(getInfo.expression == Some("consistency-check"))
        }
    }

    "PUT with JSON body detected returns 400 with helpful message" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.dynamicStr", """{"expression":"true"}""").map { resp =>
                assert(resp.status == HttpStatus.BadRequest)
                val err = Json.decode[FlagAdmin.ErrorResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(err.error.contains("plain text"))
                assert(err.error.contains("not JSON"))
            }
        }
    }

    "PUT with JSON array body detected returns 400" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendPut(port, "/flags/kyo.flagAdminMutationTestFlags.dynamicStr", """["a","b"]""").map { resp =>
                assert(resp.status == HttpStatus.BadRequest)
                val err = Json.decode[FlagAdmin.ErrorResponse](resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(err.error.contains("plain text"))
            }
        }
    }

end FlagAdminMutationTest
