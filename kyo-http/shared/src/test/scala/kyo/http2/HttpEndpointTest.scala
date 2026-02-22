package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Frame
import kyo.Record2.~
import kyo.Tag
import kyo.Test
import scala.language.implicitConversions

class HttpEndpointTest extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Schema, CanEqual

    "route.endpoint" - {

        "creates handler from route" in {
            val route = HttpRoute.get("users")
                .response(_.bodyText)
            val handler = route.endpoint { request =>
                HttpResponse.ok.addField("body", "hello")
            }
            val _: HttpEndpoint[Any, "body" ~ String, Any] = handler
            succeed
        }

        "preserves In type from path captures and request fields" in {
            val route = HttpRoute.get("users" / Capture[Int]("id"))
                .request(_.query[String]("include"))
                .response(_.bodyJson[User])
            val handler = route.endpoint { request =>
                val _: Int    = request.fields.id
                val _: String = request.fields.include
                HttpResponse.ok.addField("body", User(1, "alice"))
            }
            val _: HttpEndpoint["id" ~ Int & "include" ~ String, "body" ~ User, Any] = handler
            succeed
        }

        "preserves Out type with multiple response fields" in {
            val route = HttpRoute.get("users")
                .response(_.bodyJson[User].header[String]("etag"))
            val handler = route.endpoint { _ =>
                HttpResponse.ok
                    .addField("body", User(1, "alice"))
                    .addField("etag", "abc")
            }
            val _: HttpEndpoint[Any, "body" ~ User & "etag" ~ String, Any] = handler
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

            val handler = route.endpoint { _ =>
                Async.sleep(kyo.Duration.Zero).andThen(
                    HttpResponse.ok.addField("body", "hello")
                )
            }
            val _: HttpEndpoint[Any, "body" ~ String, Abort[String] & Async] = handler
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

            val handler = route.endpoint { request =>
                val user: String = request.fields.user
                HttpResponse.ok.addField("body", user)
            }
            val _: HttpEndpoint["user" ~ String, "body" ~ String, Any] = handler
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

            val handler = route.endpoint { request =>
                val _: String = request.fields.user
                val _: Int    = request.fields.page
                HttpResponse.ok.addField("body", "ok")
            }
            val _: HttpEndpoint["user" ~ String & "page" ~ Int, "body" ~ String, Any] = handler
            succeed
        }
    }

    "endpoint.handle" - {

        "narrows effects" in {
            val filter = new HttpFilter.Passthrough[Abort[String] & Abort[Int]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & Abort[Int] & S2) =
                    next(request)
            val route    = HttpRoute.get("users").filter(filter).response(_.bodyText)
            val endpoint = route.endpoint(_ => HttpResponse.ok.addField("body", "hello"))
            val resolved = endpoint.handle[Abort[Int]] { response =>
                Abort.run[String](response).map {
                    case kyo.Result.Success(r) => r
                    case _                     => HttpResponse.ok.addField("body", "error")
                }
            }
            val _: HttpEndpoint[Any, "body" ~ String, Abort[Int]] = resolved
            succeed
        }

        "fully resolves effects to Any" in {
            val filter = new HttpFilter.Passthrough[Abort[String]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & S2) =
                    next(request)
            val route    = HttpRoute.get("users").filter(filter).response(_.bodyText)
            val endpoint = route.endpoint(_ => HttpResponse.ok.addField("body", "hello"))
            val resolved = endpoint.handle[Any] { response =>
                Abort.run[String](response).map {
                    case kyo.Result.Success(r) => r
                    case _                     => HttpResponse.ok.addField("body", "error")
                }
            }
            val _: HttpEndpoint[Any, "body" ~ String, Any] = resolved
            succeed
        }

        "handler with no effects handles to Any" in {
            val route                                      = HttpRoute.get("users").response(_.bodyText)
            val endpoint                                   = route.endpoint(_ => HttpResponse.ok.addField("body", "hello"))
            val resolved                                   = endpoint.handle(identity)
            val _: HttpEndpoint[Any, "body" ~ String, Any] = resolved
            succeed
        }
    }

    // TODO: revisit const API
    // "HttpEndpoint.const" - {
    //     "returns fixed response for effectless route" in {
    //         val route                                      = HttpRoute.get("health").response(_.bodyText)
    //         val response                                   = HttpResponse.ok.addField("body", "healthy")
    //         val handler                                    = HttpEndpoint.const(route, response)
    //         val _: HttpEndpoint[Any, "body" ~ String, Any] = handler
    //         succeed
    //     }
    //     "rejects routes with filter effects" in { ... }
    // }

    "HttpEndpoint.health" - {

        "default path" in {
            val _: HttpEndpoint[Any, "body" ~ String, Any] = HttpEndpoint.health()
            succeed
        }

        "custom path" in {
            val _: HttpEndpoint[Any, "body" ~ String, Any] = HttpEndpoint.health("healthz")
            succeed
        }
    }

    "sealed" - {

        "cannot extend HttpEndpoint directly" in {
            typeCheckFailure("""
                new HttpEndpoint[Any, Any, Any](HttpRoute.get("test")):
                    def apply(request: HttpRequest[Any])(using Frame): HttpResponse[Any] < Any =
                        HttpResponse.ok
            """)(
                "Cannot extend"
            )
        }
    }

    "route accessor" - {

        "endpoint's route retains effect information" in {
            val filter = new HttpFilter.Passthrough[Abort[String]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & S2) =
                    next(request)

            val route = HttpRoute.get("users")
                .filter(filter)
                .response(_.bodyText)

            val endpoint = route.endpoint { _ =>
                HttpResponse.ok.addField("body", "hello")
            }

            val _: HttpRoute[Any, "body" ~ String, ?] = endpoint.route
            succeed
        }
    }

end HttpEndpointTest
