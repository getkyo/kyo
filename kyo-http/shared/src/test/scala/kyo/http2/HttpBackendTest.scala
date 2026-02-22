package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Duration
import kyo.Frame
import kyo.Record.~
import kyo.Test
import kyo.seconds
import scala.language.implicitConversions

class HttpBackendTest extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Schema, CanEqual

    // Stub client — body is never evaluated since tests only check return types
    val stubClient: HttpBackend.Client = new HttpBackend.Client:
        def send[In, Out, S](
            route: HttpRoute[In, Out, S],
            request: HttpRequest[In]
        )(using Frame): HttpResponse[Out] < (S & Async & Abort[HttpError]) =
            Abort.fail(new HttpError.ParseError("stub"))

    // Stub server that captures handlers
    val stubServer: HttpBackend.Server = new HttpBackend.Server:
        def bind[S](
            handlers: Seq[HttpHandler[?, ?, S]],
            port: Int,
            host: String
        )(using Frame): HttpBackend.Binding < (S & Async) =
            new HttpBackend.Binding:
                val port: Int                                               = 0
                val host: String                                            = "localhost"
                def close(gracePeriod: Duration)(using Frame): Unit < Async = ()
                def await(using Frame): Unit < Async                        = ()

    "Client" - {

        "send returns response" in {
            val route                                                         = HttpRoute.get("users").response(_.bodyText)
            val request                                                       = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/users"))
            val result                                                        = stubClient.send(route, request)
            val _: HttpResponse["body" ~ String] < (Async & Abort[HttpError]) = result
            succeed
        }

        "send with path captures" in {
            val route = HttpRoute.get("users" / Capture[Int]("id"))
                .response(_.bodyJson[User])
            val request: HttpRequest["id" ~ Int] =
                HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/users/1"))
                    .addField("id", 42)
            val result                                                      = stubClient.send(route, request)
            val _: HttpResponse["body" ~ User] < (Async & Abort[HttpError]) = result
            succeed
        }

        "send with filter effects propagated" in {
            val filter = new HttpFilter.Passthrough[Abort[String]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & S2) =
                    next(request)
            val route = HttpRoute.get("users")
                .filter(filter)
                .response(_.bodyText)
            val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/users"))
            val result  = stubClient.send(route, request)
            val _: HttpResponse["body" ~ String] < (Abort[String] & Async & Abort[HttpError]) = result
            succeed
        }

        "send rejects request missing required fields" in {
            typeCheckFailure("""
                val route = HttpRoute.get("users" / Capture[Int]("id"))
                    .response(_.bodyText)
                val request: HttpRequest[Any] = ???
                stubClient.send(route, request)
            """)(
                "Required"
            )
        }
    }

    "Server" - {

        "bind accepts handlers and returns binding" in {
            val route                          = HttpRoute.get("users").response(_.bodyText)
            val handler                        = route.handle(_ => HttpResponse.ok.addField("body", "hello"))
            val result                         = stubServer.bind(Seq(handler), 8080, "0.0.0.0")
            val _: HttpBackend.Binding < Async = result
            succeed
        }

        "bind propagates handler effects" in {
            val filter = new HttpFilter.Passthrough[Abort[String]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[String] & S2) =
                    next(request)
            val route                                            = HttpRoute.get("users").filter(filter).response(_.bodyText)
            val handler                                          = route.handle(_ => HttpResponse.ok.addField("body", "hello"))
            val result                                           = stubServer.bind(Seq(handler), 8080, "0.0.0.0")
            val _: HttpBackend.Binding < (Abort[String] & Async) = result
            succeed
        }

        "bind accepts multiple handlers with different In/Out" in {
            val route1   = HttpRoute.get("users").response(_.bodyText)
            val handler1 = route1.handle(_ => HttpResponse.ok.addField("body", "users"))

            val route2   = HttpRoute.post("users").request(_.bodyJson[User]).response(_.bodyJson[User])
            val handler2 = route2.handle(req => HttpResponse.ok.addField("body", req.fields.body))

            val result                         = stubServer.bind(Seq(handler1, handler2), 8080, "0.0.0.0")
            val _: HttpBackend.Binding < Async = result
            succeed
        }
    }

    "Binding" - {

        "close with default grace period" in {
            var captured: Duration = Duration.Zero
            val binding = new HttpBackend.Binding:
                val port: Int    = 9090
                val host: String = "localhost"
                def close(gracePeriod: Duration)(using Frame): Unit < Async =
                    captured = gracePeriod
                def await(using Frame): Unit < Async = ()
            val _: Unit < Async = binding.close
            assert(binding.port == 9090)
            assert(binding.host == "localhost")
            assert(captured == 30.seconds)
        }

        "closeNow uses zero duration" in {
            var captured: Duration = 30.seconds
            val binding = new HttpBackend.Binding:
                val port: Int    = 9090
                val host: String = "localhost"
                def close(gracePeriod: Duration)(using Frame): Unit < Async =
                    captured = gracePeriod
                def await(using Frame): Unit < Async = ()
            val _: Unit < Async = binding.closeNow
            assert(captured == Duration.Zero)
        }
    }

end HttpBackendTest
