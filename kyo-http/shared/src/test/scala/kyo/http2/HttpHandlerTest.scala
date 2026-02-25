package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Emit
import kyo.Frame
import kyo.Record2.~
import kyo.Scope
import kyo.Stream
import kyo.Tag
import kyo.Test
import scala.language.implicitConversions

class HttpHandlerTest extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Schema, CanEqual

    "route.handler" - {

        "creates handler from route" in {
            val route = HttpRoute.get("users")
                .response(_.bodyText)
            val handler = route.handler { request =>
                HttpResponse.ok.addField("body", "hello")
            }
            val _: HttpHandler[Any, "body" ~ String, Nothing] = handler
            succeed
        }

        "preserves In type from path captures and request fields" in {
            val route = HttpRoute.get("users" / Capture[Int]("id"))
                .request(_.query[String]("include"))
                .response(_.bodyJson[User])
            val handler = route.handler { request =>
                val _: Int    = request.fields.id
                val _: String = request.fields.include
                HttpResponse.ok.addField("body", User(1, "alice"))
            }
            val _: HttpHandler["id" ~ Int & "include" ~ String, "body" ~ User, Nothing] = handler
            succeed
        }

        "preserves Out type with multiple response fields" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].header[String]("etag"))
            val handler = route.handler { _ =>
                HttpResponse.ok
                    .addField("body", User(1, "alice"))
                    .addField("etag", "abc")
            }
            val _: HttpHandler[Any, "body" ~ User & "etag" ~ String, Nothing] = handler
            succeed
        }

        "handler can use Abort[HttpResponse.Halt]" in {
            val route = HttpRoute.get("users")
                .response(_.bodyText)
            val handler = route.handler { _ =>
                Abort.fail(HttpResponse.Halt(HttpResponse.unauthorized))
            }
            val _: HttpHandler[Any, "body" ~ String, Nothing] = handler
            succeed
        }

        "error accumulation on route" in {
            val route = HttpRoute.get("users")
                .response(_.bodyText)
                .error[String](HttpStatus.BadRequest)
                .error[Int](HttpStatus.NotFound)
            val handler = route.handler { _ =>
                HttpResponse.ok.addField("body", "hello")
            }
            val _: HttpHandler[Any, "body" ~ String, String | Int] = handler
            succeed
        }

        "filter E accumulates with route E" in {
            val filter = new HttpFilter.Passthrough[String]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[String | E2 | HttpResponse.Halt]) =
                    next(request)

            val route = HttpRoute.get("users")
                .filter(filter)
                .response(_.bodyText)

            val handler = route.handler { _ =>
                HttpResponse.ok.addField("body", "hello")
            }
            val _: HttpHandler[Any, "body" ~ String, String] = handler
            succeed
        }

        "filter-added request fields are accessible in handler" in {
            val filter = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "alice"))

            val route = HttpRoute.get("users")
                .filter(filter)
                .response(_.bodyText)

            val handler = route.handler { request =>
                val user: String = request.fields.user
                HttpResponse.ok.addField("body", user)
            }
            val _: HttpHandler["user" ~ String, "body" ~ String, Nothing] = handler
            succeed
        }

        "filter-added fields survive request builder chaining" in {
            val filter = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                ): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "alice"))

            val route = HttpRoute.get("users")
                .filter(filter)
                .request(_.query[Int]("page"))
                .response(_.bodyText)

            val handler = route.handler { request =>
                val _: String = request.fields.user
                val _: Int    = request.fields.page
                HttpResponse.ok.addField("body", "ok")
            }
            val _: HttpHandler["user" ~ String & "page" ~ Int, "body" ~ String, Nothing] = handler
            succeed
        }
    }

    "HttpHandler.health" - {

        "default path" in {
            val _: HttpHandler[Any, "body" ~ String, Nothing] = HttpHandler.health()
            succeed
        }

        "custom path" in {
            val _: HttpHandler[Any, "body" ~ String, Nothing] = HttpHandler.health("healthz")
            succeed
        }
    }

    "HttpHandler.const" - {

        "returns fixed status" in {
            val handler                           = HttpHandler.const(HttpMethod.GET, "status", HttpStatus.NoContent)
            val _: HttpHandler[Any, Any, Nothing] = handler
            assert(handler.route.method == HttpMethod.GET)
        }

        "returns fixed response" in {
            val handler                           = HttpHandler.const(HttpMethod.POST, "echo", HttpResponse.ok)
            val _: HttpHandler[Any, Any, Nothing] = handler
            assert(handler.route.method == HttpMethod.POST)
        }
    }

    "HttpHandler shortcut methods" - {

        "getRaw" in {
            val handler = HttpHandler.getRaw("users")(_ => HttpResponse.ok)
            assert(handler.route.method == HttpMethod.GET)
        }

        "postRaw" in {
            val handler = HttpHandler.postRaw("users")(_ => HttpResponse.ok)
            assert(handler.route.method == HttpMethod.POST)
        }

        "putRaw" in {
            val handler = HttpHandler.putRaw("users")(_ => HttpResponse.ok)
            assert(handler.route.method == HttpMethod.PUT)
        }

        "patchRaw" in {
            val handler = HttpHandler.patchRaw("users")(_ => HttpResponse.ok)
            assert(handler.route.method == HttpMethod.PATCH)
        }

        "deleteRaw" in {
            val handler = HttpHandler.deleteRaw("users")(_ => HttpResponse.ok)
            assert(handler.route.method == HttpMethod.DELETE)
        }

        "shortcut with errors tracks E type" in {
            val handler = HttpHandler.getRaw[String]("users") { _ =>
                Abort.fail("bad request")
            }
            val _: HttpHandler[Any, Any, String] = handler
            succeed
        }
    }

    "sealed" - {

        "cannot extend HttpHandler directly" in {
            typeCheckFailure("""
                new HttpHandler[Any, Any, Nothing](HttpRoute.get("test")):
                    def apply(request: HttpRequest[Any])(using Frame): HttpResponse[Any] < (Async & Abort[Nothing | HttpResponse.Halt]) =
                        HttpResponse.ok
            """)(
                "Cannot extend"
            )
        }
    }

    "route accessor" - {

        "handler's route retains error information" in {
            val route = HttpRoute.get("users")
                .response(_.bodyText)
                .error[String](HttpStatus.BadRequest)

            val handler = route.handler { _ =>
                HttpResponse.ok.addField("body", "hello")
            }

            val _: HttpRoute[Any, "body" ~ String, ?] = handler.route
            succeed
        }
    }

end HttpHandlerTest
