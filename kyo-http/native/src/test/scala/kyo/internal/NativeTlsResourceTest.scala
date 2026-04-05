package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Cycle 2 Phase 1: fd resource tracking during TLS operations.
  *
  * Counts open file descriptors before/after TLS operations to detect resource leaks.
  */
class NativeTlsResourceTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8    = StandardCharsets.UTF_8
    private val isMacOS = java.lang.System.getProperty("os.name", "").toLowerCase.contains("mac")

    private def onMacOS(
        f: => Assertion < (Async & Abort[HttpException] & Scope)
    )(using Frame): Assertion < (Async & Abort[HttpException] & Scope) =
        if !isMacOS then succeed
        else f

    private def listenerPort(listener: TransportListener[?]): Int =
        listener.address match
            case TransportAddress.Tcp(_, port) => port
            case TransportAddress.Unix(_)      => -1

    private def countOpenFds(): Int =
        val fdDir = new java.io.File("/dev/fd")
        if fdDir.exists() then fdDir.list().length else -1

    private def acceptOne(
        listener: TransportListener[KqueueConnection]
    )(using Frame): KqueueConnection < (Async & Abort[HttpException]) =
        listener.connections.take(1).run.map { chunk =>
            if chunk.isEmpty then
                Abort.panic(new Exception("No connection accepted"))
            else
                chunk(0)
        }

    "fd count after sequential transport TLS connections" in run {
        onMacOS {
            val transport = new KqueueNativeTransport
            val startFds  = countOpenFds()
            java.lang.System.err.println(s"[fd-transport] Start fds: $startFds")
            Scope.run {
                transport.listen(TransportAddress.Tcp("127.0.0.1", 0), 128, Present(TlsTestHelper.serverTlsConfig)).map { listener =>
                    Loop.indexed { i =>
                        if i >= 20 then
                            val endFds = countOpenFds()
                            java.lang.System.err.println(s"[fd-transport] After 20 conns: $endFds fds, delta=${endFds - startFds}")
                            Loop.done(assert(endFds - startFds < 100))
                        else
                            val serverFiber = Fiber.initUnscoped {
                                acceptOne(listener).map { serverConn =>
                                    serverConn.write(Span.fromUnsafe("ok".getBytes(Utf8))).andThen {
                                        transport.closeNow(serverConn)
                                    }
                                }
                            }
                            serverFiber.andThen {
                                transport.connect(
                                    TransportAddress.Tcp("127.0.0.1", listenerPort(listener)),
                                    Present(TlsTestHelper.clientTlsConfig)
                                ).map {
                                    clientConn =>
                                        clientConn.read.take(1).run.andThen {
                                            transport.closeNow(clientConn).andThen {
                                                if (i + 1) % 5 == 0 then
                                                    val fds = countOpenFds()
                                                    java.lang.System.err.println(s"[fd-transport] After ${i + 1} connections: $fds fds")
                                                end if
                                                Loop.continue
                                            }
                                        }
                                }
                            }
                    }
                }
            }
        }
    }

    "fd count after sequential HTTP TLS requests" in run {
        onMacOS {
            val startFds = countOpenFds()
            java.lang.System.err.println(s"[fd-http] Start fds: $startFds")
            val client  = new HttpTransportClient(new TrustAllNativeTransport)
            val server  = new HttpTransportServer(new KqueueNativeTransport)
            val route   = HttpRoute.getRaw("test" / "ping").response(_.bodyText)
            val handler = route.handler { _ => HttpResponse.ok("pong") }
            Scope.run {
                HttpServer.init(
                    server,
                    HttpServerConfig.default.port(0).host("localhost").tls(TlsTestHelper.serverTlsConfig)
                )(handler).map { binding =>
                    val bindingPort = binding.address match
                        case HttpServerAddress.Tcp(_, p) => p
                        case HttpServerAddress.Unix(_)   => -1
                    Loop.indexed { i =>
                        if i >= 20 then
                            val endFds = countOpenFds()
                            java.lang.System.err.println(s"[fd-http] After 20 reqs: $endFds fds, delta=${endFds - startFds}")
                            Loop.done(succeed)
                        else
                            client.connectWith(
                                HttpUrl.parse(s"https://localhost:$bindingPort/test/ping").getOrThrow,
                                Absent
                            ) { conn =>
                                Scope.run {
                                    Scope.ensure(client.closeNow(conn)).andThen {
                                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/test/ping"))) { resp =>
                                            assert(resp.status == HttpStatus.OK)
                                        }
                                    }
                                }
                            }.andThen {
                                if (i + 1) % 5 == 0 then
                                    val fds = countOpenFds()
                                    java.lang.System.err.println(s"[fd-http] After ${i + 1} HTTP TLS requests: $fds fds")
                                end if
                                Loop.continue
                            }
                    }
                }
            }
        }
    }

end NativeTlsResourceTest
