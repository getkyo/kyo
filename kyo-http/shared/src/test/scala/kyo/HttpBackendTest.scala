package kyo

import kyo.*
import scala.language.implicitConversions

class HttpBackendTest extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Json, CanEqual

    val stubClient = new HttpBackend.Client:
        type Connection = Unit

        def connectWith[A](
            url: HttpUrl,
            connectTimeout: Maybe[Duration]
        )(
            f: Connection => A < (Async & Abort[HttpException])
        )(using Frame): A < (Async & Abort[HttpException]) = f(())

        def sendWith[In, Out, A](
            conn: Connection,
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In],
            onRelease: Maybe[Result.Error[Any]] => Unit < Sync
        )(
            f: HttpResponse[Out] => A < (Async & Abort[HttpException])
        )(using Frame): A < (Async & Abort[HttpException]) =
            Abort.fail(HttpJsonDecodeException("stub", "TEST", "/test"))

        def isAlive(conn: Connection)(using Frame): Boolean < Sync                    = Sync.defer(true)
        def closeNow(conn: Connection)(using Frame): Unit < Sync                      = Kyo.unit
        def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async = ()
        def close(gracePeriod: Duration)(using Frame): Unit < Async                   = ()

    val stubServer: HttpBackend.Server = new HttpBackend.Server:
        def bind(
            handlers: Seq[HttpHandler[?, ?, ?]],
            config: HttpServerConfig
        )(using Frame): HttpBackend.Binding < (Async & Scope) =
            new HttpBackend.Binding:
                val address: HttpServerAddress = config.unixSocket match
                    case Present(path) => HttpServerAddress.Unix(path)
                    case Absent        => HttpServerAddress.Tcp(config.host, config.port)
                def close(gracePeriod: Duration)(using Frame): Unit < Async = ()
                def await(using Frame): Unit < Async                        = ()

    "Client" - {

        "send returns response" in {
            val route                                                             = HttpRoute.getRaw("users").response(_.bodyText)
            val request                                                           = HttpRequest.getRaw(HttpUrl.fromUri("/users"))
            val result                                                            = stubClient.sendWith((), route, request)(identity)
            val _: HttpResponse["body" ~ String] < (Async & Abort[HttpException]) = result
            succeed
        }

        "send with path captures" in {
            val route = HttpRoute.getRaw("users" / Capture[Int]("id"))
                .response(_.bodyJson[User])
            val request: HttpRequest["id" ~ Int] =
                HttpRequest.getRaw(HttpUrl.fromUri("/users/1"))
                    .addField("id", 42)
            val result                                                          = stubClient.sendWith((), route, request)(identity)
            val _: HttpResponse["body" ~ User] < (Async & Abort[HttpException]) = result
            succeed
        }

        "send accepts route with any error parameter" in {
            val filter = new HttpFilter.Passthrough[String]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[String | E2 | HttpResponse.Halt]) =
                    next(request)
            val route   = HttpRoute.getRaw("users").filter(filter).response(_.bodyText)
            val request = HttpRequest.getRaw(HttpUrl.fromUri("/users"))
            // Backend accepts any error parameter — error resolution is HttpServer's responsibility
            val result                                                            = stubClient.sendWith((), route, request)(identity)
            val _: HttpResponse["body" ~ String] < (Async & Abort[HttpException]) = result
            succeed
        }

        "send rejects request missing required fields" in {
            typeCheckFailure("""
                val route = HttpRoute.getRaw("users" / Capture[Int]("id"))
                    .response(_.bodyText)
                val request: HttpRequest[Any] = ???
                stubClient.sendWith((), route, request)(identity)
            """)(
                "Required"
            )
        }
    }

    "Server" - {

        "bind accepts handlers and returns binding" in {
            val route   = HttpRoute.getRaw("users").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok.addField("body", "hello"))
            val result  = stubServer.bind(Seq(handler), HttpServerConfig.default.port(8080).host("0.0.0.0"))
            val _: HttpBackend.Binding < (Async & Scope) = result
            succeed
        }

        "bind accepts handlers with pending errors" in {
            val filter = new HttpFilter.Passthrough[String]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[String | E2 | HttpResponse.Halt]) =
                    next(request)
            val route                                        = HttpRoute.getRaw("users").filter(filter).response(_.bodyText)
            val handler                                      = route.handler(_ => HttpResponse.ok.addField("body", "hello"))
            val _: HttpHandler[Any, "body" ~ String, String] = handler
            val _ = stubServer.bind(Seq(handler), HttpServerConfig.default.port(8080).host("0.0.0.0"))
            succeed
        }

        "bind accepts multiple handlers with different In/Out" in {
            val route1   = HttpRoute.getRaw("users").response(_.bodyText)
            val handler1 = route1.handler(_ => HttpResponse.ok.addField("body", "users"))

            val route2   = HttpRoute.postRaw("users").request(_.bodyJson[User]).response(_.bodyJson[User])
            val handler2 = route2.handler(req => HttpResponse.ok.addField("body", req.fields.body))

            val result = stubServer.bind(Seq(handler1, handler2), HttpServerConfig.default.port(8080).host("0.0.0.0"))
            val _: HttpBackend.Binding < (Async & Scope) = result
            succeed
        }
    }

    "Binding" - {

        "close with default grace period" in {
            var captured: Duration = Duration.Zero
            val binding = new HttpBackend.Binding:
                val address: HttpServerAddress = HttpServerAddress.Tcp("localhost", 9090)
                def close(gracePeriod: Duration)(using Frame): Unit < Async =
                    captured = gracePeriod
                def await(using Frame): Unit < Async = ()
            val _: Unit < Async = binding.close
            assert(binding.address == HttpServerAddress.Tcp("localhost", 9090))
            assert(captured == 30.seconds)
        }

        "closeNow uses zero duration" in {
            var captured: Duration = 30.seconds
            val binding = new HttpBackend.Binding:
                val address: HttpServerAddress = HttpServerAddress.Tcp("localhost", 9090)
                def close(gracePeriod: Duration)(using Frame): Unit < Async =
                    captured = gracePeriod
                def await(using Frame): Unit < Async = ()
            val _: Unit < Async = binding.closeNow
            assert(captured == Duration.Zero)
        }
    }

end HttpBackendTest
