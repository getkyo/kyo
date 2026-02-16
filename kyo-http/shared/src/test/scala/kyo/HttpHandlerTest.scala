package kyo

import HttpPath./
import HttpRequest.Method
import HttpResponse.Status

class HttpHandlerTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class CreateUser(name: String) derives Schema, CanEqual
    case class ApiError(message: String) derives Schema, CanEqual

    val simpleHandler = HttpHandler.get("/test") { _ => HttpResponse.ok("hello") }

    "method convenience factories" - {
        "get" in run {
            val handler = HttpHandler.get("/m") { _ => HttpResponse.ok("get") }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/m")
            yield assertBodyText(response, "get")
            end for
        }

        "post" in run {
            val handler = HttpHandler.post("/m") { req => HttpResponse.ok("post") }
            for
                port     <- startTestServer(handler)
                response <- testPost(port, "/m", "body")
            yield assertBodyText(response, "post")
            end for
        }

        "put" in run {
            val handler = HttpHandler.put("/m") { req => HttpResponse.ok("put") }
            for
                port     <- startTestServer(handler)
                response <- testPut(port, "/m", "body")
            yield assertBodyText(response, "put")
            end for
        }

        "delete" in run {
            val handler = HttpHandler.delete("/m") { _ => HttpResponse.ok("delete") }
            for
                port     <- startTestServer(handler)
                response <- testDelete(port, "/m")
            yield assertBodyText(response, "delete")
            end for
        }

        "patch" in run {
            val handler = HttpHandler.patch("/m") { req => HttpResponse.ok("patch") }
            for
                port <- startTestServer(handler)
                response <- HttpClient.send(
                    HttpRequest.patch(s"http://localhost:$port/m", "body")
                )
            yield assertBodyText(response, "patch")
            end for
        }

        "head" in run {
            val handler = HttpHandler.head("/m") { _ => HttpResponse.ok }
            for
                port <- startTestServer(handler)
                response <- HttpClient.send(
                    HttpRequest.head(s"http://localhost:$port/m")
                )
            yield assertStatus(response, Status.OK)
            end for
        }

        "options" in run {
            val handler = HttpHandler.options("/m") { _ => HttpResponse.ok("options") }
            for
                port <- startTestServer(handler)
                response <- HttpClient.send(
                    HttpRequest.options(s"http://localhost:$port/m")
                )
            yield assertBodyText(response, "options")
            end for
        }
    }

    "health" - {
        "returns healthy with default path" in run {
            val handler = HttpHandler.health()
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/health")
            yield assertBodyText(response, "healthy")
            end for
        }

        "custom path" in run {
            val handler = HttpHandler.health("/ping")
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/ping")
            yield assertBodyText(response, "healthy")
            end for
        }
    }

    "const" - {
        "with status" in run {
            val handler = HttpHandler.const(Method.GET, "/gone", Status.Gone)
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/gone")
            yield assertStatus(response, Status.Gone)
            end for
        }

        "with response" in run {
            val resp    = HttpResponse.ok("fixed")
            val handler = HttpHandler.const(Method.GET, "/fixed", resp)
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/fixed")
            yield assertBodyText(response, "fixed")
            end for
        }
    }

    "path captures" - {
        "int capture" in run {
            val handler = HttpHandler.get("/items" / HttpPath.int("id")) { in =>
                HttpResponse.ok(s"item:${in.id}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/items/42")
            yield assertBodyText(response, "item:42")
            end for
        }

        "string capture" in run {
            val handler = HttpHandler.get("/greet" / HttpPath.string("name")) { in =>
                HttpResponse.ok(s"hello:${in.name}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/greet/world")
            yield assertBodyText(response, "hello:world")
            end for
        }

        "multiple captures" in run {
            val handler = HttpHandler.get("/users" / HttpPath.string("name") / HttpPath.int("id")) { in =>
                HttpResponse.ok(s"${in.name}:${in.id}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/users/alice/7")
            yield assertBodyText(response, "alice:7")
            end for
        }

        "long capture" in run {
            val handler = HttpHandler.get("/big" / HttpPath.long("n")) { in =>
                HttpResponse.ok(s"n:${in.n}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/big/9999999999")
            yield assertBodyText(response, "n:9999999999")
            end for
        }

        "boolean capture" in run {
            val handler = HttpHandler.get("/flag" / HttpPath.boolean("b")) { in =>
                HttpResponse.ok(s"flag:${in.b}")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/flag/true")
            yield assertBodyText(response, "flag:true")
            end for
        }
    }

    "route-based handler" - {
        "JSON input/output" in run {
            val route = HttpRoute.post("/users").requestBody[CreateUser].responseBody[User]
            val handler = HttpHandler.init(route) { in =>
                User(1, in.body.name)
            }
            for
                port     <- startTestServer(handler)
                response <- testPost(port, "/users", CreateUser("alice"))
            yield
                val body = response.bodyText
                assert(body.contains("\"id\""))
                assert(body.contains("\"alice\""))
            end for
        }

        "output-only route" in run {
            val route   = HttpRoute.get("/users").responseBody[List[User]]
            val handler = HttpHandler.init(route)(_ => List(User(1, "a"), User(2, "b")))
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/users")
            yield
                assert(response.bodyText.contains("\"a\""))
                assert(response.bodyText.contains("\"b\""))
            end for
        }

        "no output schema returns 200 OK" in run {
            val route   = HttpRoute.post("/action")
            val handler = HttpHandler.init(route)(_ => ())
            for
                port     <- startTestServer(handler)
                response <- testPost(port, "/action", "")
            yield assertStatus(response, Status.OK)
            end for
        }
    }

    "error handling" - {
        "handler exception returns 500" in run {
            val handler = HttpHandler.get("/boom") { _ =>
                throw new RuntimeException("explosion")
            }
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/boom")
            yield assertStatus(response, Status.InternalServerError)
            end for
        }

        "route handler Abort maps to error response" in run {
            val route = HttpRoute.post("/validate")
                .requestBody[CreateUser]
                .responseBody[User]
                .error[ApiError](Status.BadRequest)
            val handler = HttpHandler.init(route) { _ =>
                Abort.fail(ApiError("invalid"))
            }
            for
                port     <- startTestServer(handler)
                response <- testPost(port, "/validate", CreateUser("x"))
            yield
                assertStatus(response, Status.BadRequest)
                assert(response.bodyText.contains("invalid"))
            end for
        }

        "unmatched Abort error returns 500" in run {
            val route = HttpRoute.post("/fail").requestBody[CreateUser].responseBody[User]
            val handler = HttpHandler.init(route) { _ =>
                Abort.fail(ApiError("oops"))
            }
            for
                port     <- startTestServer(handler)
                response <- testPost(port, "/fail", CreateUser("x"))
            yield assertStatus(response, Status.InternalServerError)
            end for
        }
    }

    "query parameters" - {
        "required query param" in run {
            val route   = HttpRoute.get("/search").query[String]("q").responseBody[String]
            val handler = HttpHandler.init(route)(in => s"found:${in.q}")
            for
                port <- startTestServer(handler)
                response <- HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/search?q=test")
                )
            yield assertBodyContains(response, "found:test")
            end for
        }

        "missing required query param returns 400" in run {
            val route   = HttpRoute.get("/search").query[String]("q").responseBody[String]
            val handler = HttpHandler.init(route)(in => s"found:${in.q}")
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/search")
            yield assertStatus(response, Status.BadRequest)
            end for
        }

        "query param with default" in run {
            val route   = HttpRoute.get("/page").query[Int]("n", 1).responseBody[String]
            val handler = HttpHandler.init(route)(in => s"page:${in.n}")
            for
                port     <- startTestServer(handler)
                response <- testGet(port, "/page")
            yield assertBodyContains(response, "page:1")
            end for
        }
    }

    "multiple handlers" - {
        "dispatches to correct handler by path" in run {
            val h1 = HttpHandler.get("/a") { _ => HttpResponse.ok("handler-a") }
            val h2 = HttpHandler.get("/b") { _ => HttpResponse.ok("handler-b") }
            for
                port <- startTestServer(h1, h2)
                ra   <- testGet(port, "/a")
                rb   <- testGet(port, "/b")
            yield
                assertBodyText(ra, "handler-a")
                assertBodyText(rb, "handler-b")
            end for
        }

        "dispatches to correct handler by method" in run {
            val h1 = HttpHandler.get("/x") { _ => HttpResponse.ok("got") }
            val h2 = HttpHandler.post("/x") { _ => HttpResponse.ok("posted") }
            for
                port <- startTestServer(h1, h2)
                rg   <- testGet(port, "/x")
                rp   <- testPost(port, "/x", "")
            yield
                assertBodyText(rg, "got")
                assertBodyText(rp, "posted")
            end for
        }

        "unmatched path returns 404" in run {
            for
                port     <- startTestServer(simpleHandler)
                response <- testGet(port, "/nonexistent")
            yield assertStatus(response, Status.NotFound)
        }
    }

end HttpHandlerTest
