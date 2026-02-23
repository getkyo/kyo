package kyo.http2

import java.util.concurrent.atomic.AtomicInteger
import kyo.<
import kyo.Abort
import kyo.AllowUnsafe
import kyo.Async
import kyo.Duration
import kyo.Frame
import kyo.Maybe
import kyo.Present
import kyo.Record2.~
import kyo.Result
import kyo.Schedule
import kyo.Scope
import kyo.Test
import kyo.millis
import kyo.seconds
import scala.language.implicitConversions

class HttpClientTest extends Test:

    val route = HttpRoute.get("test").response(_.bodyText)

    class StubBackend(respond: AtomicInteger => HttpResponse[?] = _ => HttpResponse.ok.addField("body", "hello"))
        extends HttpBackend.Client:
        type Connection = Unit
        val callCount            = new AtomicInteger(0)
        var capturedHost: String = ""
        var capturedPort: Int    = 0
        var capturedSsl: Boolean = false

        def connectWith[A, S](
            host: String,
            port: Int,
            ssl: Boolean,
            connectTimeout: Maybe[Duration]
        )(f: Connection => A < S)(using Frame): A < (S & Async & Abort[HttpError]) =
            capturedHost = host
            capturedPort = port
            capturedSsl = ssl
            f(())
        end connectWith

        def sendWith[In, Out, A, S](
            conn: Connection,
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In],
            timeout: Maybe[Duration]
        )(f: HttpResponse[Out] => A < S)(using Frame): A < (S & Async & Abort[HttpError]) =
            callCount.incrementAndGet()
            f(respond(callCount).asInstanceOf[HttpResponse[Out]])
        end sendWith

        def isAlive(conn: Connection)(using AllowUnsafe): Boolean                     = true
        def closeNowUnsafe(conn: Connection)(using AllowUnsafe): Unit                 = ()
        def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async = ()
        def close(gracePeriod: Duration)(using Frame): Unit < Async                   = ()
    end StubBackend

    def withClient[A](
        backend: HttpBackend.Client = new StubBackend
    )(f: HttpClient => A < (Async & Abort[HttpError]))(using Frame): A < (Async & Abort[Any]) =
        Scope.run {
            HttpClient.init(backend).map(f)
        }

    val noTimeout = HttpClient.Config(timeout = Maybe.empty)

    "sendWith" - {

        "returns response via continuation" in run {
            HttpClient.withConfig(noTimeout) {
                withClient() { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                        assert(res.status == HttpStatus.OK)
                    }
                }
            }
        }

        "passes response fields to continuation" in run {
            HttpClient.withConfig(noTimeout) {
                withClient() { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                        assert(res.fields.body == "hello")
                    }
                }
            }
        }
    }

    "baseUrl" - {

        "resolves relative URL against baseUrl" in run {
            val backend = new StubBackend
            val config = HttpClient.Config(
                baseUrl = Present(HttpUrl(Present("https"), "api.example.com", 443, "/", Maybe.empty)),
                timeout = Maybe.empty
            )
            HttpClient.withConfig(config) {
                withClient(backend) { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { _ =>
                        assert(backend.capturedHost == "api.example.com")
                        assert(backend.capturedPort == 443)
                    }
                }
            }
        }

        "does not override absolute URL" in run {
            val backend = new StubBackend
            val config = HttpClient.Config(
                baseUrl = Present(HttpUrl(Present("https"), "base.example.com", 443, "/", Maybe.empty)),
                timeout = Maybe.empty
            )
            val absoluteUrl = HttpUrl(Present("http"), "other.example.com", 8080, "/test", Maybe.empty)
            HttpClient.withConfig(config) {
                withClient(backend) { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, absoluteUrl)) { _ =>
                        assert(backend.capturedHost == "other.example.com")
                    }
                }
            }
        }
    }

    "withConfig" - {

        "transforms current config" in run {
            HttpClient.withConfig(noTimeout) {
                HttpClient.withConfig(_.copy(followRedirects = false)) {
                    withClient() { client =>
                        client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                            assert(res.status == HttpStatus.OK)
                        }
                    }
                }
            }
        }
    }

    "redirects" - {

        "follows redirects" in run {
            val backend = new StubBackend(count =>
                if count.get() == 1 then HttpResponse.redirect("http://localhost/test2").addField("body", "redirect")
                else HttpResponse.ok.addField("body", "final")
            )
            HttpClient.withConfig(noTimeout) {
                withClient(backend) { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                        assert(res.status == HttpStatus.OK)
                        assert(res.fields.body == "final")
                        assert(backend.callCount.get() == 2)
                    }
                }
            }
        }

        "fails on too many redirects" in run {
            val backend = new StubBackend(_ =>
                HttpResponse.redirect("http://localhost/loop").addField("body", "loop")
            )
            HttpClient.withConfig(noTimeout.copy(maxRedirects = 3)) {
                withClient(backend) { client =>
                    Abort.run[HttpError](
                        client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test")))(identity)
                    ).map {
                        case Result.Failure(err: HttpError.TooManyRedirects) =>
                            assert(err.count == 3)
                        case other =>
                            fail(s"Expected TooManyRedirects, got $other")
                    }
                }
            }
        }

        "disabled when followRedirects is false" in run {
            val backend = new StubBackend(_ =>
                HttpResponse.redirect("http://localhost/other").addField("body", "redirect")
            )
            HttpClient.withConfig(noTimeout.copy(followRedirects = false)) {
                withClient(backend) { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                        assert(res.status.isRedirect)
                    }
                }
            }
        }
    }

    "retry" - {

        "retries on server error" in run {
            val backend = new StubBackend(count =>
                if count.get() < 3 then HttpResponse.serverError.addField("body", "error")
                else HttpResponse.ok.addField("body", "success")
            )
            HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                withClient(backend) { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                        assert(res.status == HttpStatus.OK)
                        assert(backend.callCount.get() == 3)
                    }
                }
            }
        }

        "returns last response when retries exhausted" in run {
            val backend = new StubBackend(_ =>
                HttpResponse.serverError.addField("body", "error")
            )
            HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(2)))) {
                withClient(backend) { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                        assert(res.status.isServerError)
                    }
                }
            }
        }

        "does not retry on client error" in run {
            val backend = new StubBackend(_ =>
                HttpResponse.notFound.addField("body", "nope")
            )
            HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                withClient(backend) { client =>
                    client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                        assert(res.status == HttpStatus.NotFound)
                        assert(backend.callCount.get() == 1)
                    }
                }
            }
        }
    }

    "connection pool" - {

        "exhausted pool fails" in run {
            HttpClient.withConfig(noTimeout) {
                Scope.run {
                    HttpClient.init(new StubBackend, maxConnectionsPerHost = 1).map { client =>
                        client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { _ =>
                            Abort.run[HttpError](
                                client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test")))(identity)
                            ).map {
                                case Result.Failure(_: HttpError.ConnectionPoolExhausted) => succeed
                                case other => fail(s"Expected ConnectionPoolExhausted, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "close" - {

        "close with default grace period" in run {
            HttpClient.withConfig(noTimeout) {
                Scope.run {
                    HttpClient.init(new StubBackend).map { client =>
                        client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))) { res =>
                            assert(res.status == HttpStatus.OK)
                        }
                    }
                }
            }
        }
    }

end HttpClientTest
