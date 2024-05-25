package kyoTest

import kyo.*
import kyo.internal.Trace
import scala.util.*
import sttp.client3.*

class requestsTest extends KyoTest:

    class TestBackend extends Requests.Backend:
        var calls = 0
        def send[T](r: Request[T, Any]) =
            calls += 1
            Response.ok(Right("mocked")).asInstanceOf[Response[T]]
    end TestBackend

    "apply" in run {
        val backend = new TestBackend
        Requests.run(backend) {
            for
                r <- Requests[String](_.get(uri"https://httpbin.org/get"))
            yield
                assert(r == "mocked")
                assert(backend.calls == 1)
        }
    }
    "request" in run {
        val backend = new TestBackend
        Requests.run(backend) {
            for
                r <- Requests.request[String](
                    Requests.basicRequest.get(uri"https://httpbin.org/get")
                )
            yield
                assert(r == "mocked")
                assert(backend.calls == 1)
        }
    }
    "with fiber" in run {
        val backend = new TestBackend
        Requests.run(backend) {
            Fibers.init {
                for
                    r <- Requests[String](_.get(uri"https://httpbin.org/get"))
                yield
                    assert(r == "mocked")
                    assert(backend.calls == 1)
            }.map(_.get)
        }
    }
    "with meter" in run {
        var calls = 0
        val meter = new Meter:
            def available(using Trace)                 = ???
            def tryRun[T, S](v: => T < S)(using Trace) = ???
            def run[T, S](v: => T < S)(using Trace) =
                calls += 1
                v
        val backend = (new TestBackend).withMeter(meter)
        Requests.run(backend) {
            Fibers.init {
                for
                    r <- Requests[String](_.get(uri"https://httpbin.org/get"))
                yield
                    assert(r == "mocked")
                    assert(calls == 1)
            }.map(_.get)
        }
    }
end requestsTest
