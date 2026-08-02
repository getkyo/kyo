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

    // Every scenario shares the process-lifetime per-backend transport + pooled client, so run leaves sequentially AND
    // globally sequentially: a parallel scenario, or another suite sharing the transport, would otherwise interfere with
    // the churn/cancellation load on the shared driver/pool.
    override def config = super.config.sequential.globallySequential(true)

    private val ping =
        HttpRoute.getRaw("ping").response(_.bodyText).handler(_ => HttpResponse.ok("pong"))

    /** A handler that never responds in time: it sleeps far longer than any client timeout, so a request to it is only
      * ever resolved by the client's cancellation (or the connection closing).
      */
    private val slow =
        HttpRoute.getRaw("slow").response(_.bodyText).handler(_ => Async.sleep(30.seconds).andThen(HttpResponse.ok("late")))

    /** A handler that fails (panics) so the server dispatch has to contain the failure without wedging the connection
      * handling for other requests.
      */
    private val boom =
        HttpRoute.getRaw("boom").response(_.bodyText).handler(_ => Sync.defer(throw new RuntimeException("handler boom")))

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

    // ---- dedicated, tightened reproduction of the reported wedge (reproduce-before-fix: RED until the driver is fixed) ---

    /** Reliable reproduction of the reported NioIoDriver "... is closed" wedge, at the HTTP level, with FIBERS.
      *
      * The wedge is a cross-carrier race (a cancelled request's caller-carrier `closeHandle` vs the poll carrier's
      * `dispatchReadyKeys` on the same fd) that only `NioIoDriver` is exposed to (its dispatch catch is
      * `CancelledKeyException`-only; `PollerIoDriver`'s dispatch is total). It reproduces on nio but NOT kqueue/epoll,
      * so this targets nio directly. A single 3s pass is racy (0 to tens of hits), so this runs an aggressive load
      * (tight 10ms server restarts + a 30ms request timeout that fires on the hung requests) and stops as soon as the
      * driver wedges (typically well under a second). This is a reproduce-before-fix guard: it is RED by design while the
      * bug is unfixed, and it goes GREEN once the driver contains per-connection dispatch errors like `PollerIoDriver`.
      */
    "reproduce (nio): pooled client wedges under request cancellation + server churn" in {
        val durationMs = sys.props.get("kyo.reproDurationMs").map(_.toLong).getOrElse(20000L)
        TestBackends.all.find(e => e.name == "nio" && e.isAvailable) match
            case None => cancel("nio backend not available on this host")
            case Some(entry) =>
                val transport = entry.transport
                val client    = clientFor(entry)
                HttpClient.let(client) {
                    val current = new java.util.concurrent.atomic.AtomicReference[HttpServer]()
                    val stop    = new java.util.concurrent.atomic.AtomicBoolean(false)
                    val bug     = new java.util.concurrent.atomic.AtomicInteger(0)
                    for
                        server0 <- startServer(transport, ping)
                        _ = current.set(server0)
                        churn <- Fiber.initUnscoped {
                            Abort.run[Any] {
                                Loop.foreach {
                                    if stop.get() then Loop.done(())
                                    else
                                        Async.sleep(10.millis)
                                            .andThen(startServer(transport, ping).map(s => current.getAndSet(s).closeNow))
                                            .andThen(Loop.continue)
                                }
                            }.unit
                        }
                        deadline = java.lang.System.currentTimeMillis() + durationMs
                        _ <- Async.foreach(0 until 16, 16) { _ =>
                            Loop.foreach {
                                if java.lang.System.currentTimeMillis() >= deadline || bug.get() > 0 then Loop.done(())
                                else
                                    val url = s"http://localhost:${current.get().port}/ping"
                                    Abort.run[Any] {
                                        Async.timeout(30.millis) {
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
                        _ <- churn.get
                        _ <- current.get().closeNow
                    yield assert(bug.get() == 0, s"driver-closed wedge REPRODUCED on nio: ${bug.get()} hits")
                    end for
                }
        end match
    }

    // ---- HTTP request cancellation / pool integrity / handler failure (fibers) -------------------------------------

    "in-flight request cancellation stays contained; the shared client survives" - eachBackend { (transport, _) =>
        // Many concurrent requests to a never-responding endpoint, each cancelled by a short firing timeout. The
        // cancellations must not break the shared pooled client: a healthy request afterward still round-trips.
        for
            server <- startServer(transport, ping, slow)
            base = s"http://localhost:${server.port}"
            _ <- Async.foreach(0 until 32, 16) { _ =>
                Abort.run[Any](Async.timeout(30.millis)(HttpClient.getText(s"$base/slow"))).unit
            }
            body <- HttpClient.getText(s"$base/ping")
            _    <- server.closeNow
        yield assert(body == "pong")
    }

    "a cancelled request does not poison a reused pooled connection" - eachBackend { (transport, _) =>
        // Interleave a cancelled slow request with a healthy ping on the SAME pooled client. If a cancelled request left
        // undrained bytes on a pooled connection, the next reuse would read them as the ping's status line and fail.
        for
            server <- startServer(transport, ping, slow)
            base = s"http://localhost:${server.port}"
            _ <- Loop(0) { i =>
                if i >= 20 then Loop.done(())
                else
                    Abort.run[Any](Async.timeout(30.millis)(HttpClient.getText(s"$base/slow")))
                        .andThen(HttpClient.getText(s"$base/ping"))
                        .map(b => assert(b == "pong", s"pooled reuse after a cancelled request returned '$b', not 'pong'"))
                        .andThen(Loop.continue(i + 1))
            }
            _ <- server.closeNow
        yield succeed
    }

    "handler failure stays isolated; a healthy endpoint still serves" - eachBackend { (transport, _) =>
        // A failing handler must not wedge the connection handling for other requests: after hammering the failing
        // endpoint, the healthy endpoint on the same server (and shared client) still round-trips.
        for
            server <- startServer(transport, ping, boom)
            base = s"http://localhost:${server.port}"
            _ <- Async.foreach(0 until 20, 10) { _ =>
                Abort.run[Any](HttpClient.getText(s"$base/boom")).unit
            }
            body <- HttpClient.getText(s"$base/ping")
            _    <- server.closeNow
        yield assert(body == "pong")
    }

end HttpServerResilienceTest
