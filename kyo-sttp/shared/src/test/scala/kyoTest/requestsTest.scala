package kyoTest

import kyo.concurrent.fibers._
import kyo._
import kyo.ios._
import kyo.requests._
import kyo.tries._
import kyoTest.KyoTest
import sttp.client3._
import sttp.model.StatusCode
import kyo.resources.Resources
import scala.util._

class requestsTest extends KyoTest {

  class TestBackend extends Backend {
    var calls = 0
    def send[T](r: Request[T, Any]) = {
      calls += 1
      Response.ok(Right("mocked")).asInstanceOf[Response[T]]
    }
  }

  "apply" in run {
    val backend = new TestBackend
    Requests.run(backend) {
      for {
        r <- Requests[String](_.get(uri"https://httpbin.org/get"))
      } yield {
        assert(r == "mocked")
        assert(backend.calls == 1)
      }
    }
  }
  "request" in run {
    val backend = new TestBackend
    Requests.run(backend) {
      for {
        r <- Requests.request[String](Requests.basicRequest.get(uri"https://httpbin.org/get"))
      } yield {
        assert(r == "mocked")
        assert(backend.calls == 1)
      }
    }
  }
  "race" in run {
    val backend = new TestBackend
    Requests.run(backend) {
      val call = Requests.request[String](Requests.basicRequest.get(uri"https://httpbin.org/get"))
      for {
        r <- Requests.race(call, call)
      } yield {
        assert(r == "mocked")
        assert(backend.calls == 2)
      }
    }
  }

}
