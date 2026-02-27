package demo

import kyo.*

/** Uptime monitor with SSE dashboard.
  *
  * Periodically pings real websites and streams health check results as SSE events. Demonstrates Async.parallel for concurrent health
  * checks, SSE streaming, and periodic polling with Stream.
  */
object UptimeMonitor extends KyoApp:

    case class HealthCheck(url: String, status: Int, healthy: Boolean, latencyMs: Long) derives Schema
    case class CheckRound(timestamp: String, checks: List[HealthCheck]) derives Schema

    val targets = Seq(
        "https://www.google.com",
        "https://github.com",
        "https://www.scala-lang.org",
        "https://docs.scala-lang.org",
        "https://httpbin.org/status/200"
    )

    def checkOne(url: String): HealthCheck < Async =
        Clock.stopwatch.map { sw =>
            HttpClient.withConfig(_.timeout(10.seconds).followRedirects(true)) {
                Abort.run[HttpError](HttpClient.getText(url)).map { result =>
                    sw.elapsed.map { dur =>
                        result match
                            case kyo.Result.Success(_) =>
                                HealthCheck(url, 200, true, dur.toMillis)
                            case _ =>
                                HealthCheck(url, 0, false, dur.toMillis)
                    }
                }
            }
        }

    def checkAll: CheckRound < Async =
        for
            checks <- Async.foreach(targets, targets.size)(checkOne).map(_.toList)
            now    <- Clock.now
        yield CheckRound(now.toString, checks)

    val statusStream = HttpHandler.getSseJson[CheckRound]("status") { _ =>
        Stream.init(1 to 10000, chunkSize = 1).mapChunk { _ =>
            Async.delay(30.seconds) {
                checkAll.map(round => Chunk(HttpEvent(data = round)))
            }
        }
    }

    val checkRoute = HttpHandler.getJson[CheckRound]("check") { _ =>
        checkAll
    }

    val health = HttpHandler.health()

    run {
        HttpServer.init(
            HttpServer.Config().port(3006).openApi("/openapi.json", "Uptime Monitor")
        )(statusStream, checkRoute, health).map { server =>
            for
                _ <- Console.printLine(s"UptimeMonitor running on http://localhost:${server.port}")
                _ <- Console.printLine("  curl http://localhost:3006/check")
                _ <- Console.printLine("  curl -N http://localhost:3006/status")
                _ <- server.await
            yield ()
        }
    }
end UptimeMonitor
