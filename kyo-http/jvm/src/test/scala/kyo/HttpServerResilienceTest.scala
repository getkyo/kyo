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

end HttpServerResilienceTest
