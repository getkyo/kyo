package kyo

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.*

/** Reliability of the shared `HttpClient` default-client transport under server churn: the HTTP-level analogue of
  * `kyo.net.TransportResilienceTest`. Faithful to the reported reproduction (a kyo-http server in place of
  * WireMock, no external dependency), expressed entirely with kyo's `Async` primitives (fibers run on real scheduler
  * worker threads, so no raw `Thread`s or `runAndBlock`): 16-way concurrent load, each firing two concurrent GETs per
  * invocation through the process-shared default client, while a churn fiber restarts the server every 25 ms so
  * in-flight responses are RST while unrelated fibers keep using the shared transport.
  *
  * The reported failure is a process-wide wedge: once the shared driver is marked closed, every later call fails with
  * "... is closed". This asserts that never happens (zero driver-closed hits during the load) and that the shared
  * client still round-trips afterwards. Prints the backend actually selected.
  */
class HttpServerResilienceTest extends BaseHttpTest:

    import AllowUnsafe.embrace.danger

    private val pingHandler =
        HttpRoute.getRaw("ping").response(_.bodyText).handler(_ => HttpResponse.ok("pong"))

    /** The reported wedge signature: a rendered `Closed` naming a driver as closed ("<Backend>IoDriver[...] is closed"). */
    private def isDriverClosed(t: Throwable): Boolean =
        val m = String.valueOf(t.getMessage)
        m.contains("is closed") && m.contains("Driver")

    "reported bug: shared HttpClient survives server churn under concurrent load" in {
        val clients    = 16
        val durationMs = sys.props.get("kyo.reproDurationMs").map(_.toLong).getOrElse(3000L)
        val backend    = sys.props.getOrElse("kyo.net.backend", "auto")
        val current    = new AtomicReference[HttpServer]()
        val stop       = new AtomicBoolean(false)
        val bug        = new AtomicInteger(0)
        for
            server0 <- HttpServer.initUnscoped(0, "localhost")(pingHandler)
            _ = current.set(server0)
            // Churn fiber: every 25ms start a new server, swap it in, close the old one (RST-ing its in-flight responses).
            churn <- Fiber.initUnscoped {
                Abort.run[HttpBindException] {
                    Loop.foreach {
                        if stop.get() then Loop.done(())
                        else
                            Async.sleep(25.millis).andThen {
                                HttpServer.initUnscoped(0, "localhost")(pingHandler).map { s =>
                                    current.getAndSet(s).closeNow
                                }
                            }.andThen(Loop.continue)
                    }
                }.unit
            }
            deadline = java.lang.System.currentTimeMillis() + durationMs
            // 16-way concurrent load: each fiber loops for `durationMs`, two concurrent GETs per invocation on the current server.
            _ <- Async.foreach(0 until clients, clients) { _ =>
                Loop(0) { i =>
                    if java.lang.System.currentTimeMillis() >= deadline then Loop.done(())
                    else
                        val url = s"http://localhost:${current.get().port}/ping"
                        Abort.run[HttpException] {
                            Async.zip(
                                HttpClient.getTextResponse(url, failOnError = false),
                                HttpClient.getTextResponse(url, failOnError = false)
                            )
                        }.map {
                            case Result.Failure(ex) => if isDriverClosed(ex) then discard(bug.incrementAndGet())
                            case Result.Panic(ex)   => if isDriverClosed(ex) then discard(bug.incrementAndGet())
                            case _                  => ()
                        }.andThen(Loop.continue(i + 1))
                }
            }
            _ = stop.set(true)
            _          <- churn.get
            _          <- current.get().closeNow
            liveServer <- HttpServer.initUnscoped(0, "localhost")(pingHandler)
            liveResult <- Abort.run[HttpException](HttpClient.getText(s"http://localhost:${liveServer.port}/ping"))
            _          <- liveServer.closeNow
        yield
            val liveOk = liveResult == Result.Success("pong")
            println(s"[HttpServerResilienceTest] backend=$backend driverClosedHits=${bug.get()} liveness=$liveOk")
            assert(bug.get() == 0, s"[backend=$backend] driver-closed wedge REPRODUCED: ${bug.get()} hits during churn")
            assert(
                liveOk,
                s"[backend=$backend] shared client wedged after churn: post-churn GET did not return pong (got $liveResult)"
            )
        end for
    }

end HttpServerResilienceTest
