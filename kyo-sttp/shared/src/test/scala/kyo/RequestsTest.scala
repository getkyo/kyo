package kyo

import scala.util.*
import sttp.client3.*

class RequestsTest extends Test:

    class TestBackend extends Requests.Backend:
        var calls = 0
        def send[A: Flat](r: Request[A, Any]) =
            calls += 1
            Response.ok(Right("mocked")).asInstanceOf[Response[A]]
    end TestBackend

    "apply" in run {
        val backend = new TestBackend
        Requests.let(backend) {
            for
                r <- Requests(_.get(uri"https://httpbin.org/get"))
            yield
                assert(r == "mocked")
                assert(backend.calls == 1)
        }
    }
    "request" in run {
        val backend = new TestBackend
        Requests.let(backend) {
            for
                r <- Requests.request(
                    Requests.basicRequest.get(uri"https://httpbin.org/get")
                )
            yield
                assert(r == "mocked")
                assert(backend.calls == 1)
        }
    }
    "with fiber" in run {
        val backend = new TestBackend
        Requests.let(backend) {
            Async.run {
                for
                    r <- Requests(_.get(uri"https://httpbin.org/get"))
                yield
                    assert(r == "mocked")
                    assert(backend.calls == 1)
            }.map(_.get)
        }
    }
    "with meter" in run {
        var calls = 0
        val meter = new Meter:
            def capacity                               = ???
            def availablePermits(using Frame)          = ???
            def pendingWaiters(using Frame)            = ???
            def closed(using Frame)                    = ???
            def tryRun[A, S](v: => A < S)(using Frame) = ???
            def run[A, S](v: => A < S)(using Frame) =
                calls += 1
                v
            def close(using Frame) = ???
        val backend = (new TestBackend).withMeter(meter)
        Requests.let(backend) {
            Async.run {
                for
                    r <- Requests(_.get(uri"https://httpbin.org/get"))
                yield
                    assert(r == "mocked")
                    assert(calls == 1)
            }.map(_.get)
        }
    }
end RequestsTest
