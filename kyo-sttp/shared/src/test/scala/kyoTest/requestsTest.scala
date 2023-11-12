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

  "requests" - {
    "mocked" in run {
      val backend = new Backend {
        def send[T](r: Request[T, Any]) =
          Response.ok(Right("mocked")).asInstanceOf[Response[T]]
      }
      Requests.run(backend) {
        for {
          r <- Requests[String](_.get(uri"https://httpbin.org/get"))
        } yield {
          assert(r == "mocked")
        }
      }
    }
  }

}
