package kyoTest

import kyo.concurrent.fibers._
import kyo.core._
import kyo.ios._
import kyo.requests._
import kyoTest.KyoTest
import sttp.client3._
import sttp.model.StatusCode

class requestsTest extends KyoTest {

  "requests" - {
    "default" in run {
      Requests.run {
        for {
          r <- Requests(_.get(uri"https://httpbin.org/get"))
        } yield {
          assert(r.code == StatusCode.Ok)
        }
      }
    }
    "mocked" in run {
      val backend = new Backend {
        def send[T](r: Request[T, Any]): Response[T] > (IOs | Fibers) =
          Response.ok(Left("mocked")).asInstanceOf[Response[T]]
      }
      Requests.run(backend) {
        for {
          r <- Requests(_.get(uri"https://httpbin.org/get"))
        } yield {
          assert(r.body == Left("mocked"))
        }
      }
    }
  }
}
