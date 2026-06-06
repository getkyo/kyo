package kyo

import kyo.*
import scala.language.implicitConversions

class HttpHandlerTest extends BaseHttpTest:

    import HttpPath.*

    case class User(id: Int, name: String) derives Schema, CanEqual

    "route.handler" - {

        "creates handler from route" in {
            val route = HttpRoute.getRaw("users")
                .response(_.bodyText)
            val handler = route.handler { request =>
                HttpResponse.ok.addField("body", "hello")
            }
            val _: HttpHandler[Any, "body" ~ String, Nothing] = handler
            succeed("compile-time type check: handler has the expected HttpHandler type")
        }

        "preserves In type from path captures and request fields" in {
            val route = HttpRoute.getRaw("users" / Capture[Int]("id"))
                .request(_.query[String]("include"))
                .response(_.bodyJson[User])
            val handler = route.handler { request =>
                val _: Int    = request.fields.id
                val _: String = request.fields.include
                HttpResponse.ok.addField("body", User(1, "alice"))
            }
            val _: HttpHandler["id" ~ Int & "include" ~ String, "body" ~ User, Nothing] = handler
            succeed("compile-time type check: handler In/Out types are correctly inferred")
        }

        "preserves Out type with multiple response fields" in {
            val route = HttpRoute.getRaw("users")
                .response(_.bodyJson[User].header[String]("etag"))
            val handler = route.handler { _ =>
                HttpResponse.ok
                    .addField("body", User(1, "alice"))
                    .addField("etag", "abc")
            }
            val _: HttpHandler[Any, "body" ~ User & "etag" ~ String, Nothing] = handler
            succeed("compile-time type check: Out type includes both body and etag fields")
        }

        "handler can use Abort[HttpResponse.Halt]" in {
            val route = HttpRoute.getRaw("users")
                .response(_.bodyText)
            val handler = route.handler { _ =>
                Abort.fail(HttpResponse.Halt(HttpResponse.unauthorized))
            }
            val _: HttpHandler[Any, "body" ~ String, Nothing] = handler
            succeed("compile-time type check: handler accepting Abort[HttpResponse.Halt] is valid")
        }

        "error accumulation on route" in {
            val route = HttpRoute.getRaw("users")
                .response(_.bodyText)
                .error[String](HttpStatus.BadRequest)
                .error[Int](HttpStatus.NotFound)
            val handler = route.handler { _ =>
                HttpResponse.ok.addField("body", "hello")
            }
            val _: HttpHandler[Any, "body" ~ String, String | Int] = handler
            succeed("compile-time type check: error union type is correctly accumulated")
        }

        "filter E accumulates with route E" in {
            val filter = new HttpFilter.Passthrough[String]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[String | E2 | HttpResponse.Halt]) =
                    next(request)

            val route = HttpRoute.getRaw("users")
                .filter(filter)
                .response(_.bodyText)

            val handler = route.handler { _ =>
                HttpResponse.ok.addField("body", "hello")
            }
            val _: HttpHandler[Any, "body" ~ String, String] = handler
            succeed("compile-time type check: filter E accumulates into handler E type")
        }

        "filter-added request fields are accessible in handler" in {
            val filter = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "alice"))

            val route = HttpRoute.getRaw("users")
                .filter(filter)
                .response(_.bodyText)

            val handler = route.handler { request =>
                val user: String = request.fields.user
                HttpResponse.ok.addField("body", user)
            }
            val _: HttpHandler["user" ~ String, "body" ~ String, Nothing] = handler
            succeed("compile-time type check: filter-added request fields are in In type")
        }

        "filter-added fields survive request builder chaining" in {
            val filter = new HttpFilter.Request[Any, "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.addField("user", "alice"))

            val route = HttpRoute.getRaw("users")
                .filter(filter)
                .request(_.query[Int]("page"))
                .response(_.bodyText)

            val handler = route.handler { request =>
                val _: String = request.fields.user
                val _: Int    = request.fields.page
                HttpResponse.ok.addField("body", "ok")
            }
            val _: HttpHandler["user" ~ String & "page" ~ Int, "body" ~ String, Nothing] = handler
            succeed("compile-time type check: filter fields survive request builder chaining")
        }
    }

    "HttpHandler.health" - {

        "default path" in {
            val _: HttpHandler[Any, "body" ~ String, Nothing] = HttpHandler.health()
            succeed("compile-time type check: health() returns the correct handler type")
        }

        "custom path" in {
            val _: HttpHandler[Any, "body" ~ String, Nothing] = HttpHandler.health("healthz")
            succeed("compile-time type check: health() with custom path returns the correct type")
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

        "head" in {
            val handler = HttpHandler.headRaw("users")(_ => HttpResponse.ok)
            assert(handler.route.method == HttpMethod.HEAD)
        }

        "options" in {
            val handler = HttpHandler.optionsRaw("users")(_ => HttpResponse.ok)
            assert(handler.route.method == HttpMethod.OPTIONS)
        }

        "shortcut with errors tracks E type" in {
            val handler = HttpHandler.getRaw[String]("users") { _ =>
                Abort.fail("bad request")
            }
            val _: HttpHandler[Any, Any, String] = handler
            succeed("compile-time type check: error type parameter flows into handler E")
        }
    }

    "sealed" - {

        "cannot extend HttpHandler directly" in {
            typeCheckFailure("""
                new HttpHandler[Any, Any, Nothing](HttpRoute.getRaw("test")):
                    def apply(request: HttpRequest[Any])(using Frame): HttpResponse[Any] < (Async & Abort[Nothing | HttpResponse.Halt]) =
                        HttpResponse.ok
            """)(
                "Cannot extend"
            )
        }
    }

    "route accessor" - {

        "handler's route retains error information" in {
            val route = HttpRoute.getRaw("users")
                .response(_.bodyText)
                .error[String](HttpStatus.BadRequest)

            val handler = route.handler { _ =>
                HttpResponse.ok.addField("body", "hello")
            }

            val _: HttpRoute[Any, "body" ~ String, ?] = handler.route
            succeed("compile-time type check: handler.route retains the route's error type")
        }
    }

end HttpHandlerTest
