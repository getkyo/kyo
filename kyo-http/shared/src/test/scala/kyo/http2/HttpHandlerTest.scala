package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Frame
import kyo.Record.~
import kyo.Tag
import kyo.Test
import scala.language.implicitConversions

class HttpHandlerTest extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Schema, CanEqual

    "route.handle" - {

        "creates handler from route" in {
            val route = HttpRoute.get("users")
                .response(_.bodyText)
            val handler = route.handle { request =>
                HttpResponse.ok.addField("body", "hello")
            }
            val _: HttpHandler[Any, "body" ~ String, Any] = handler
            succeed
        }

        "preserves In type from path captures and request fields" in {
            val route = HttpRoute.get("users" / Capture[Int]("id"))
                .request(_.query[String]("include"))
                .response(_.bodyJson[User])
            val handler = route.handle { request =>
                val _: Int    = request.fields.id
                val _: String = request.fields.include
                HttpResponse.ok.addField("body", User(1, "alice"))
            }
            val _: HttpHandler["id" ~ Int & "include" ~ String, "body" ~ User, Any] = handler
            succeed
        }

        "preserves Out type with multiple response fields" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].header[String]("etag"))
            val handler = route.handle { _ =>
                HttpResponse.ok
                    .addField("body", User(1, "alice"))
                    .addField("etag", "abc")
            }
            val _: HttpHandler[Any, "body" ~ User & "etag" ~ String, Any] = handler
            succeed
        }

        "combines route filter S with handler S2" in {
            val filter = new HttpFilter.Passthrough[Abort[String]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & S2) =
                    next(request)

            val route = HttpRoute.get("users")
                .filter(filter)
                .response(_.bodyText)

            val handler = route.handle { _ =>
                Async.sleep(kyo.Duration.Zero).andThen(
                    HttpResponse.ok.addField("body", "hello")
                )
            }
            val _: HttpHandler[Any, "body" ~ String, Abort[String] & Async] = handler
            succeed
        }

        "filter-added fields are accessible in handler" in {
            val filter = new HttpFilter.Request[Any, "user" ~ String, Any]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < S2 =
                    next(request.addField("user", "alice"))

            val route = HttpRoute.get("users")
                .filter(filter)
                .response(_.bodyText)

            val handler = route.handle { request =>
                val user: String = request.fields.user
                HttpResponse.ok.addField("body", user)
            }
            val _: HttpHandler["user" ~ String, "body" ~ String, Any] = handler
            succeed
        }

        "filter-added fields survive request builder chaining" in {
            val filter = new HttpFilter.Request[Any, "user" ~ String, Any]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In & "user" ~ String] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < S2 =
                    next(request.addField("user", "alice"))

            val route = HttpRoute.get("users")
                .filter(filter)
                .request(_.query[Int]("page"))
                .response(_.bodyText)

            val handler = route.handle { request =>
                val _: String = request.fields.user
                val _: Int    = request.fields.page
                HttpResponse.ok.addField("body", "ok")
            }
            val _: HttpHandler["user" ~ String & "page" ~ Int, "body" ~ String, Any] = handler
            succeed
        }
    }

    "handler.handle" - {

        "resolves effects producing HttpHandler with S2" in {
            val filter = new HttpFilter.Passthrough[Abort[String]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & S2) =
                    next(request)
            val route   = HttpRoute.get("users").filter(filter).response(_.bodyText)
            val handler = route.handle(_ => HttpResponse.ok.addField("body", "hello"))
            val resolved = handler.handle { response =>
                Abort.run[String](response).map {
                    case kyo.Result.Success(r) => r
                    case _                     => HttpResponse.ok.addField("body", "error")
                }
            }
            val _: HttpHandler[Any, "body" ~ String, Any] = resolved
            succeed
        }
    }

    "HttpHandler.const" - {

        "returns fixed response for effectless route" in {
            val route                                     = HttpRoute.get("health").response(_.bodyText)
            val response                                  = HttpResponse.ok.addField("body", "healthy")
            val handler                                   = HttpHandler.const(route, response)
            val _: HttpHandler[Any, "body" ~ String, Any] = handler
            succeed
        }

        "rejects routes with filter effects" in {
            val filter = new HttpFilter.Passthrough[Abort[String]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & S2) =
                    next(request)
            val route = HttpRoute.get("users")
                .filter(filter)
                .response(_.bodyText)
            val response = HttpResponse.ok.addField("body", "hello")
            typeCheckFailure("""HttpHandler.const(route, response)""")(
                "Required"
            )
        }
    }

    "HttpHandler.health" - {

        "default path" in {
            val _: HttpHandler[Any, "body" ~ String, Any] = HttpHandler.health()
            succeed
        }

        "custom path" in {
            val _: HttpHandler[Any, "body" ~ String, Any] = HttpHandler.health("healthz")
            succeed
        }
    }

    "sealed" - {

        "cannot extend HttpHandler directly" in {
            typeCheckFailure("""
                new HttpHandler[Any, Any, Any](HttpRoute.get("test")):
                    def apply(request: HttpRequest[Any])(using Frame): HttpResponse[Any] < Any =
                        HttpResponse.ok
            """)(
                "Cannot extend"
            )
        }
    }

    "route bound (? >: S)" - {

        "handler's route retains effect information" in {
            val filter = new HttpFilter.Passthrough[Abort[String]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & S2) =
                    next(request)

            val route = HttpRoute.get("users")
                .filter(filter)
                .response(_.bodyText)

            val handler = route.handle { _ =>
                HttpResponse.ok.addField("body", "hello")
            }

            val _: HttpRoute[Any, "body" ~ String, ? >: Abort[String]] = handler.route
            succeed
        }
    }

end HttpHandlerTest
