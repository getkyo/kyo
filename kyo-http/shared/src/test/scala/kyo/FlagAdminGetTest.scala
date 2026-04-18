package kyo

import kyo.*
import scala.language.implicitConversions

// Test flags — defined at package level as objects nested in objects (valid for Flag name derivation)
// Names will be: kyo.flagAdminTestFlags.staticInt, kyo.flagAdminTestFlags.dynamicBool, etc.
object flagAdminTestFlags:
    object staticInt    extends StaticFlag[Int](42)
    object staticStr    extends StaticFlag[String]("hello")
    object dynamicBool  extends DynamicFlag[Boolean](false)
    object dynamicInt   extends DynamicFlag[Int](0)
    object dynamicStr   extends DynamicFlag[String]("default")
    object appDbPool    extends StaticFlag[Int](10)
    object appDbTimeout extends StaticFlag[Int](30)
    object appCacheSize extends StaticFlag[Int](100)
end flagAdminTestFlags

class FlagAdminGetTest extends Test:

    import HttpPath.*

    val client = kyo.internal.HttpPlatformBackend.client

    // Ensure test flags are loaded
    locally {
        val _ = flagAdminTestFlags.staticInt
        val _ = flagAdminTestFlags.staticStr
        val _ = flagAdminTestFlags.dynamicBool
        val _ = flagAdminTestFlags.dynamicInt
        val _ = flagAdminTestFlags.dynamicStr
        val _ = flagAdminTestFlags.appDbPool
        val _ = flagAdminTestFlags.appDbTimeout
        val _ = flagAdminTestFlags.appCacheSize
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

    val jsonFlagInfo  = Json[FlagAdmin.FlagInfo]
    val jsonFlagInfos = Json[List[FlagAdmin.FlagInfo]]
    val jsonError     = Json[FlagAdmin.ErrorResponse]

    "GET /flags returns all flags" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val body  = resp.fields.body
                val infos = jsonFlagInfos.decode(body)
                assert(infos.isSuccess)
                val list = infos.getOrElse(throw new AssertionError("JSON decode failed"))
                // Should contain our test flags
                assert(list.exists(_.name == "kyo.flagAdminTestFlags.staticInt"))
                assert(list.exists(_.name == "kyo.flagAdminTestFlags.dynamicBool"))
            }
        }
    }

    "GET /flags/:name returns specific flag info" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags/kyo.flagAdminTestFlags.staticInt").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val info = jsonFlagInfo.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(info.name == "kyo.flagAdminTestFlags.staticInt")
            }
        }
    }

    "GET /flags/:name returns 404 for unknown flag" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags/nonexistent.flag.name").map { resp =>
                assert(resp.status == HttpStatus.NotFound)
                val err = jsonError.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(err.error.contains("not found"))
            }
        }
    }

    "static FlagInfo includes correct name, type, value, default, source" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags/kyo.flagAdminTestFlags.staticInt").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val info = jsonFlagInfo.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(info.name == "kyo.flagAdminTestFlags.staticInt")
                assert(info.`type` == "static")
                assert(info.value == Some("42"))
                assert(info.default == "42")
                assert(info.source == "Default")
            }
        }
    }

    "dynamic FlagInfo includes expression, evaluations, history" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags/kyo.flagAdminTestFlags.dynamicBool").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val info = jsonFlagInfo.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(info.name == "kyo.flagAdminTestFlags.dynamicBool")
                assert(info.`type` == "dynamic")
                assert(info.expression.isDefined)
                assert(info.evaluations.isDefined)
                assert(info.history.isDefined)
            }
        }
    }

    "static flag info has Absent for expression, evaluations, history" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags/kyo.flagAdminTestFlags.staticStr").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val info = jsonFlagInfo.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(info.`type` == "static")
                assert(info.expression == None)
                assert(info.evaluations == None)
                assert(info.history == None)
            }
        }
    }

    "filter parameter works with glob matching" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags?filter=kyo.flagAdminTestFlags.app*").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val list = jsonFlagInfos.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                // Should match appDbPool, appDbTimeout, appCacheSize
                assert(list.forall(_.name.startsWith("kyo.flagAdminTestFlags.app")))
                assert(list.size >= 3)
            }
        }
    }

    "filter parameter with no match returns empty array" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags?filter=nonexistent.*").map { resp =>
                assert(resp.status == HttpStatus.OK)
                val list = jsonFlagInfos.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(list.isEmpty)
            }
        }
    }

    "FlagInfo round-trips correctly through Json" in run {
        val info = FlagAdmin.FlagInfo(
            name = "test.flag",
            `type` = "static",
            value = Some("42"),
            expression = None,
            default = "0",
            source = "Default",
            evaluations = None,
            history = None
        )
        val encoded = jsonFlagInfo.encode(info)
        val decoded = jsonFlagInfo.decode(encoded).getOrElse(throw new AssertionError("JSON decode failed"))
        assert(decoded.name == info.name)
        assert(decoded.`type` == info.`type`)
        assert(decoded.value == info.value)
        assert(decoded.default == info.default)
        assert(decoded.source == info.source)
        succeed
    }

    "ErrorResponse on not-found is valid JSON with error field" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            sendRaw(port, HttpMethod.GET, "/flags/unknown.flag").map { resp =>
                assert(resp.status == HttpStatus.NotFound)
                val err = jsonError.decode(resp.fields.body).getOrElse(throw new AssertionError("JSON decode failed"))
                assert(err.error.nonEmpty)
                assert(err.error.contains("unknown.flag"))
            }
        }
    }

    "Content-Type is application/json for all responses" in run {
        val handlers = FlagAdmin.routes("flags")
        withServer(handlers*) { port =>
            for
                listResp <- sendRaw(port, HttpMethod.GET, "/flags")
                getResp  <- sendRaw(port, HttpMethod.GET, "/flags/kyo.flagAdminTestFlags.staticInt")
                notFound <- sendRaw(port, HttpMethod.GET, "/flags/no.such.flag")
            yield
                def checkJson(resp: HttpResponse["body" ~ String]) =
                    val ct = resp.headers.get("Content-Type")
                    assert(ct.isDefined, "Content-Type header must be present")
                    assert(ct.get.contains("application/json"), s"Expected application/json, got ${ct.get}")
                end checkJson
                checkJson(listResp)
                checkJson(getResp)
                checkJson(notFound)
        }
    }

    "glob matching edge cases" - {

        "empty glob matches empty string" in run {
            assert(FlagAdmin.matchGlob("", ""))
            assert(!FlagAdmin.matchGlob("", "anything"))
            succeed
        }

        "star glob matches any single segment" in run {
            assert(FlagAdmin.matchGlob("*", "anything"))
            assert(!FlagAdmin.matchGlob("*", "has.dot"))
            succeed
        }

        "double star glob matches everything" in run {
            assert(FlagAdmin.matchGlob("**", ""))
            assert(FlagAdmin.matchGlob("**", "anything"))
            assert(FlagAdmin.matchGlob("**", "any.thing.deep"))
            succeed
        }
    }

    "glob matching" - {
        "star matches within segment" in run {
            assert(FlagAdmin.matchGlob("kyo.flagAdminTestFlags.*", "kyo.flagAdminTestFlags.staticInt"))
            assert(!FlagAdmin.matchGlob("kyo.flagAdminTestFlags.*", "kyo.flagAdminTestFlags.nested.deep"))
            succeed
        }

        "double star matches across segments" in run {
            assert(FlagAdmin.matchGlob("kyo.**", "kyo.flagAdminTestFlags.staticInt"))
            assert(FlagAdmin.matchGlob("kyo.**", "kyo.deep.nested.flag"))
            succeed
        }

        "glob with regex metacharacters does not crash or misinterpret" in run {
            // Parentheses, plus, brackets — all should be treated as literal characters
            assert(!FlagAdmin.matchGlob("kyo.(test)+", "kyo.flagAdminTestFlags.staticInt"))
            assert(FlagAdmin.matchGlob("kyo.(test)+", "kyo.(test)+"))
            // Square brackets
            assert(!FlagAdmin.matchGlob("kyo.[test]", "kyo.t"))
            assert(FlagAdmin.matchGlob("kyo.[test]", "kyo.[test]"))
            // Question mark
            assert(!FlagAdmin.matchGlob("kyo.tes?", "kyo.test"))
            assert(FlagAdmin.matchGlob("kyo.tes?", "kyo.tes?"))
            // Pipe
            assert(!FlagAdmin.matchGlob("kyo.a|b", "kyo.a"))
            assert(FlagAdmin.matchGlob("kyo.a|b", "kyo.a|b"))
            // Caret and dollar
            assert(FlagAdmin.matchGlob("kyo.$test^", "kyo.$test^"))
            // Star still works with metacharacters nearby
            assert(FlagAdmin.matchGlob("kyo.(test)*", "kyo.(test)Foo"))
            assert(!FlagAdmin.matchGlob("kyo.(test)*", "kyo.(test).deep"))
            succeed
        }
    }

end FlagAdminGetTest
