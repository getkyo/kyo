package kyo

import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.internal.client.HttpClientBackend
import kyo.net.NetException
import kyo.net.TestBackends
import kyo.net.Transport

/** HTTP-level resilience of the shared `HttpClient` + `HttpServer` stack under request cancellation and server churn,
  * fanned over every I/O backend the same way `kyo.net.TransportResilienceTest` fans the transport suite.
  *
  * The reported RC5 bug is a process-wide wedge: once the shared transport driver is marked closed, every later call
  * fails with "... is closed". Each scenario builds a client + server over a specific backend's transport (via the
  * kyo-net `TestBackends` harness, reachable through the `test->test` dependency), drives a failure/cancellation load,
  * and asserts the shared client still round-trips afterward. Fibers only: no real OS threads, no `runAndBlock`.
  */
class HttpServerResilienceTest extends BaseHttpTest:

    import AllowUnsafe.embrace.danger

    private val ping =
        HttpRoute.getRaw("ping").response(_.bodyText).handler(_ => HttpResponse.ok("pong"))

    /** One client per backend, built over that backend's process-lifetime shared transport and never closed: closing an
      * `HttpClientBackend` closes its transport, and `TestBackends` transports are shared for the whole run (exactly as
      * every client and server in a process shares `NetPlatform.transport`). The pool persists across scenarios, which
      * is the very shape the reported bug is about.
      */
    private val clientByBackend = new ConcurrentHashMap[String, HttpClient]()
    private def clientFor(entry: TestBackends.Entry)(using Frame): HttpClient =
        // `opaque type HttpClient = HttpClientBackend`; the alias is transparent only inside HttpClient's own scope, and
        // there is no public per-transport HttpClient factory (init/initUnscoped use the shared NetPlatform.transport).
        // Bridge the backend built over this per-backend transport across the opaque boundary. Safe: same runtime class.
        clientByBackend.computeIfAbsent(
            entry.name,
            _ => HttpClientBackend.init(entry.transport, 100, 60.seconds).asInstanceOf[HttpClient]
        )

    /** Stand up a server over the given transport on a dynamic port. The server does NOT own the transport, so closing
      * it (per scenario) leaves the shared transport and the co-tenant client intact.
      */
    private def startServer(transport: Transport, handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): HttpServer < (Async & Abort[NetException]) =
        Sync.Unsafe.defer {
            val config = HttpServerConfig.default.port(0).host("localhost")
            HttpServer.Unsafe.init(transport, config, handlers).safe.get.map(_.safe)
        }

    /** HTTP-level `eachBackend`: registers one leaf per registered backend, binds a per-backend client over that
      * backend's transport as the ambient client, and passes the transport so a scenario can stand up (and churn)
      * servers over the same transport. Unavailable backends cancel visibly, mirroring `kyo.net.Test.eachBackend`.
      */
    private def eachBackend(
        scenario: (Transport, HttpClient) => (kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))
    )(using Frame): Unit =
        TestBackends.all.foreach { entry =>
            s"[${entry.name}]" in {
                if !entry.isAvailable then cancel(s"backend ${entry.name} not available on this host")
                else run(entry)(scenario)
            }
        }

    private def run(entry: TestBackends.Entry)(
        scenario: (Transport, HttpClient) => (kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))
    )(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[Any] & Scope) =
        val transport = entry.transport
        val client    = clientFor(entry)
        HttpClient.let(client)(scenario(transport, client))
    end run

    // ---- smoke: the per-backend HTTP wiring actually serves ---------------------------------------------------------

    "healthy GET round-trips on the shared client" - eachBackend { (transport, _) =>
        for
            server <- startServer(transport, ping)
            body   <- HttpClient.getText(s"http://localhost:${server.port}/ping")
            _      <- server.closeNow
        yield assert(body == "pong")
    }

    // ---- reported bug: pooled client + firing request timeout + server restart churn (fibers) -----------------------

    /** Walk the cause chain for the reported wedge signature: a driver reported as closed ("<Backend>IoDriver[...] is closed"). */
    private def isDriverClosed(e: Any): Boolean =
        def check(t: Throwable, depth: Int): Boolean =
            if (t eq null) || depth > 6 then false
            else
                val m = String.valueOf(t.getMessage)
                (m.contains("is closed") && m.contains("Driver")) || check(t.getCause, depth + 1)
        e match
            case t: Throwable => check(t, 0)
            case other        => val m = String.valueOf(other); m.contains("is closed") && m.contains("Driver")
    end isDriverClosed

    "reported bug: shared pooled client survives request cancellation under server churn" - eachBackend { (transport, _) =>
        // The reporter's shape at the HTTP level, with fibers: 16-way concurrent load through the process-shared POOLED
        // client, each invocation firing two concurrent GETs bounded by a short Async.timeout that EXPIRES on requests
        // hung by the churn, while the server is restarted every 25ms (listener replaced), RST-ing in-flight responses
        // and orphaning pooled connections. The wedge (if reproduced) is a "<Driver> is closed" that then fails every
        // later call. Asserts zero driver-closed hits during the load and that the shared client still round-trips after.
        val durationMs = sys.props.get("kyo.reproDurationMs").map(_.toLong).getOrElse(3000L)
        val current    = new java.util.concurrent.atomic.AtomicReference[HttpServer]()
        val stop       = new java.util.concurrent.atomic.AtomicBoolean(false)
        val bug        = new java.util.concurrent.atomic.AtomicInteger(0)
        for
            server0 <- startServer(transport, ping)
            _ = current.set(server0)
            churn <- Fiber.initUnscoped {
                Abort.run[Any] {
                    Loop.foreach {
                        if stop.get() then Loop.done(())
                        else
                            Async.sleep(25.millis)
                                .andThen(startServer(transport, ping).map(s => current.getAndSet(s).closeNow))
                                .andThen(Loop.continue)
                    }
                }.unit
            }
            deadline = java.lang.System.currentTimeMillis() + durationMs
            _ <- Async.foreach(0 until 16, 16) { _ =>
                Loop.foreach {
                    if java.lang.System.currentTimeMillis() >= deadline then Loop.done(())
                    else
                        val url = s"http://localhost:${current.get().port}/ping"
                        Abort.run[Any] {
                            Async.timeout(50.millis) {
                                Async.zip(
                                    HttpClient.getTextResponse(url, failOnError = false),
                                    HttpClient.getTextResponse(url, failOnError = false)
                                )
                            }
                        }.map {
                            case Result.Failure(ex) => if isDriverClosed(ex) then discard(bug.incrementAndGet())
                            case Result.Panic(ex)   => if isDriverClosed(ex) then discard(bug.incrementAndGet())
                            case _                  => ()
                        }.andThen(Loop.continue)
                }
            }
            _ = stop.set(true)
            _          <- churn.get
            _          <- current.get().closeNow
            liveServer <- startServer(transport, ping)
            liveResult <- Abort.run[Any](HttpClient.getText(s"http://localhost:${liveServer.port}/ping"))
            _          <- liveServer.closeNow
        yield
            assert(bug.get() == 0, s"driver-closed wedge REPRODUCED: ${bug.get()} hits during churn")
            assert(liveResult == Result.Success("pong"), s"shared pooled client wedged after churn: got $liveResult")
        end for
    }

end HttpServerResilienceTest
