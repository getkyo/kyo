package kyoTest

import kyo.*
import scala.util.*
import sttp.client3.*

class requestsLiveTest extends KyoTest:

    "requests" - {
        "live" - {
            "success" in run {
                Requests.run {
                    for
                        port <- startTestServer("/ping", Success("pong"))
                        r    <- Requests(_.get(uri"http://localhost:$port/ping"))
                    yield assert(r == "pong")
                }
            }
            "failure" in run {
                Requests.run {
                    for
                        port <- startTestServer("/ping", Failure(new Exception))
                        r    <- IOs.attempt(Requests(_.get(uri"http://localhost:$port/ping")))
                    yield assert(r.isFailure)
                }
            }
            "race" in run {
                val n = 1000
                for
                    port <- startTestServer("/ping", Success("pong"))
                    r    <- Fibers.race(Seq.fill(n)(Requests.run(Requests(_.get(uri"http://localhost:$port/ping")))))
                yield assert(r == "pong")
                end for
            }
        }
    }

    private def startTestServer(
        endpointPath: String,
        response: Try[String],
        port: Int = 8000
    ): Int < (IOs & Resources) =
        IOs {

            import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
            import java.io.OutputStream
            import java.net.InetSocketAddress
            import scala.util.{Success, Failure}

            val server = HttpServer.create(new InetSocketAddress(port), 0)
            server.createContext(
                endpointPath,
                new HttpHandler:
                    def handle(exchange: HttpExchange): Unit =
                        response match
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
            )
            server.setExecutor(null)
            server.start()
            Resources.ensure(server.stop(0))
                .andThen(IOs(server.getAddress.getPort()))
        }
end requestsLiveTest
