package kyoTest

import kyo._

import sttp.client3._
import sttp.model.StatusCode
import scala.util._

class requestsLiveTest extends KyoTest {

  "requests" - {
    "live" - {
      "success" in run {
        for {
          port <- startTestServer("/ping", Success("pong"))
          r    <- Requests(_.get(uri"http://localhost:$port/ping"))
        } yield {
          assert(r == "pong")
        }
      }
      "failure" in run {
        for {
          port <- startTestServer("/ping", Failure(new Exception))
          r    <- Tries.run(Requests(_.get(uri"http://localhost:$port/ping")))
        } yield {
          assert(r.isFailure)
        }
      }
    }
  }

  private def startTestServer(
      endpointPath: String,
      response: Try[String],
      port: Int = 8000
  ): Int < (IOs with Resources) =
    IOs {

      import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
      import java.io.OutputStream
      import java.net.InetSocketAddress
      import scala.util.{Try, Success, Failure}

      val server = HttpServer.create(new InetSocketAddress(port), 0)
      server.createContext(
          endpointPath,
          new HttpHandler {
            def handle(exchange: HttpExchange): Unit = {
              response match {
                case Success(responseString) =>
                  exchange.sendResponseHeaders(200, responseString.getBytes.length)
                  val os: OutputStream = exchange.getResponseBody
                  os.write(responseString.getBytes)
                  os.close()

                case Failure(ex) =>
                  val errorMessage = "Internal server error"
                  exchange.sendResponseHeaders(500, errorMessage.getBytes.length)
                  val os: OutputStream = exchange.getResponseBody
                  os.write(errorMessage.getBytes)
                  os.close()
              }
            }
          }
      )
      server.setExecutor(null)
      server.start()
      Resources.ensure(server.stop(0))
        .andThen(IOs(server.getAddress.getPort()))
    }
}
