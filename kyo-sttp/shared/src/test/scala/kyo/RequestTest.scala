package kyo

import scala.util.*
import sttp.client3.*

class RequestTest extends Test:

    class TestBackend extends Request.Backend:
        var calls = 0
        def send[T](r: Request[T, Any]) =
            calls += 1
            Response.ok(Right("mocked")).asInstanceOf[Response[T]]
    end TestBackend

    "apply" in run {
        val backend = new TestBackend
        Request.let(backend) {
            for
                r <- Request(_.get(uri"https://httpbin.org/get"))
            yield
                assert(r == "mocked")
                assert(backend.calls == 1)
        }
    }
    "request" in run {
        val backend = new TestBackend
        Request.let(backend) {
            for
                r <- Request.request(
                    Request.basicRequest.get(uri"https://httpbin.org/get")
                )
            yield
                assert(r == "mocked")
                assert(backend.calls == 1)
        }
    }
    "with fiber" in run {
        val backend = new TestBackend
        Request.let(backend) {
            Async.run {
                for
                    r <- Request(_.get(uri"https://httpbin.org/get"))
                yield
                    assert(r == "mocked")
                    assert(backend.calls == 1)
            }.map(_.get)
        }
    }
    "with meter" in run {
        var calls = 0
        val meter = new Meter:
            def available(using Frame)                 = ???
            def tryRun[T, S](v: => T < S)(using Frame) = ???
            def run[T, S](v: => T < S)(using Frame) =
                calls += 1
                v
            def close(using Frame) = ???
        val backend = (new TestBackend).withMeter(meter)
        Request.let(backend) {
            Async.run {
                for
                    r <- Request(_.get(uri"https://httpbin.org/get"))
                yield
                    assert(r == "mocked")
                    assert(calls == 1)
            }.map(_.get)
        }
    }
end RequestTest
