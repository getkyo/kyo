package kyoTest

import kyo.*

import sttp.client3.*
import sttp.model.StatusCode
import scala.util.*

class requestsTest extends KyoTest:

    class TestBackend extends Requests.Backend:
        var calls = 0
        def send[T](r: Request[T, Any]) =
            calls += 1
            Response.ok(Right("mocked")).asInstanceOf[Response[T]]
    end TestBackend

    "apply" in run {
        val backend = new TestBackend
        Requests.let(backend) {
            for
                r <- Requests[String](_.get(uri"https://httpbin.org/get"))
            yield
                assert(r == "mocked")
                assert(backend.calls == 1)
        }
    }
    "request" in run {
        val backend = new TestBackend
        Requests.let(backend) {
            for
                r <- Requests.request[String](
                    Requests.basicRequest.get(uri"https://httpbin.org/get")
                )
            yield
                assert(r == "mocked")
                assert(backend.calls == 1)
        }
    }
end requestsTest
