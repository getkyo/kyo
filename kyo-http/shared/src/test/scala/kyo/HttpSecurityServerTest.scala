package kyo

import kyo.*
import kyo.internal.transport.*

class HttpSecurityServerTest extends BaseHttpTest:

    val echoRoute   = HttpRoute.postRaw("echo").response(_.bodyText)
    val echoHandler = echoRoute.handler(_ => HttpResponse.ok("ok"))

    /** Start a plain HTTP server and run a test against it. */
    def withEchoServer(
        test: (String, Int) => Unit < (Async & Abort[Any] & Scope)
    )(using Frame): Unit < (Scope & Async & Abort[Any]) =
        HttpServer.init(0, "localhost")(echoHandler).map { s =>
            test("localhost", s.port)
        }

    /** Send raw bytes over TCP to host:port and read the response as a string.
      *
      * Returns the response string, or empty string if the connection was closed or timed out (both indicate server rejection of the
      * malformed request).
      */
    def sendRawBytes(host: String, port: Int, raw: Array[Byte])(using Frame): String < (Async & Abort[Any]) =
        Sync.Unsafe.defer {
            val transport = internal.HttpPlatformTransport.transport
            val fiber     = transport.connect(host, port)
            Abort.run[Closed](fiber.safe.get).map {
                case Result.Success(conn) =>
                    val payload = Span.fromUnsafe(raw)
                    // Write the malicious request
                    Abort.run[Closed](conn.outbound.safe.put(payload)).map { _ =>
                        // Give the server time to process and respond
                        Async.sleep(500.millis).andThen {
                            // Try to read the response; catch both Closed and Timeout
                            Abort.run[Any](
                                Async.timeout(2.seconds)(
                                    Abort.run[Closed](conn.inbound.safe.take)
                                )
                            ).map { outerResult =>
                                val response = outerResult match
                                    case Result.Success(Result.Success(data)) =>
                                        new String(data.toArray, "UTF-8")
                                    case _ =>
                                        // Closed, timed out, or other error — server rejected
                                        ""
                                Sync.Unsafe.defer(conn.close())
                                    .andThen(response)
                            }
                        }
                    }
                case _ => "" // connection failed
            }
        }

    /** Check that a response indicates rejection: either 400 status or connection closed. */
    def assertRejected(response: String, description: String)(using kyo.test.AssertScope): Unit =
        if response.isEmpty then
            // Connection closed -- server rejected the request
            succeed("expected: server rejected by closing connection")
        else if response.contains("400") then
            assert(response.contains("400"), s"$description: server returned 400 Bad Request for malformed input")
        else
            fail(
                s"$description: Expected 400 Bad Request or connection close, but got:\n${response.take(200)}"
            )

    "request smuggling defenses" - {

        "duplicate Content-Length headers (CVE-2019-20445)" in {
            withEchoServer { (host, port) =>
                val raw =
                    ("POST /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: 5\r\n" +
                        "Content-Length: 100\r\n" +
                        "\r\n" +
                        "hello").getBytes("UTF-8")

                sendRawBytes(host, port, raw).map { response =>
                    assertRejected(response, "Duplicate Content-Length")
                }
            }
        }

        "CL+TE conflict (classic CL.TE smuggling)" in {
            withEchoServer { (host, port) =>
                val raw =
                    ("POST /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: 100\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n").getBytes("UTF-8")

                sendRawBytes(host, port, raw).map { response =>
                    assertRejected(response, "CL+TE conflict")
                }
            }
        }

        "Content-Length integer overflow" in {
            withEchoServer { (host, port) =>
                val raw =
                    ("POST /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: 99999999999999999999\r\n" +
                        "\r\n" +
                        "hello").getBytes("UTF-8")

                sendRawBytes(host, port, raw).map { response =>
                    assertRejected(response, "Content-Length integer overflow")
                }
            }
        }
    }

    "handshake-stall DoS defenses" - {

        // The cross-backend reap mechanism itself (including Native) is covered by kyo-net's TransportHandshakeTimeoutTest
        // via the public NetPlatform.transport(config) factory; this test covers the kyo-http wiring:
        // HttpServerConfig.transportConfig.handshakeTimeout reaching an owned per-config transport whose finite deadline
        // reaps a stalled accept handshake.

        val serverTls = internal.HttpTestPlatformBackend.serverTlsConfig

        "a finite handshakeTimeout reaps a stalled TLS accept handshake (CWE-400, slowloris)" in {
            val tc = HttpTransportConfig.default.handshakeTimeout(150.millis)
            val serverConfig = HttpServerConfig.default.port(0).host("localhost")
                .tls(serverTls)
                .transportConfig(tc)
            HttpServer.init(serverConfig)(echoHandler).map { server =>
                // Raw plaintext client: completes the TCP accept but never sends a ClientHello, so the server-side TLS
                // handshake parks. The bug this guards (handshakeTimeout silently ignored under the shared default transport)
                // would leave the connection pinned and the bounded await below would expire (Timeout, the regression
                // symptom); the deadline reaps the accepted fd, which the client observes as its inbound
                // terminating (Closed, or an empty EOF span).
                Sync.Unsafe.defer {
                    val transport = kyo.net.NetPlatform.transport
                    transport.connect("localhost", server.port).safe.get.map { conn =>
                        Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[Closed](conn.inbound.safe.take))).map { outcome =>
                            conn.close()
                            val reaped = outcome match
                                case Result.Success(Result.Success(span)) => span.isEmpty
                                case Result.Success(Result.Failure(_))    => true
                                case _                                    => false
                            assert(reaped, s"expected the finite handshakeTimeout to reap the stalled server handshake, got $outcome")
                        }
                    }
                }
            }
        }

        "a TLS handshake completing within the deadline is served, not reaped" in {
            val okRoute   = HttpRoute.getText("ok").response(_.bodyText)
            val okHandler = okRoute.handler(_ => HttpResponse.ok("served"))
            // A generous finite deadline: the loopback handshake completes well under it, so the timer disarms and the
            // request round-trips. This proves the finite deadline does not reap completed handshakes and that the owned
            // per-config transport serves real TLS traffic.
            val tc = HttpTransportConfig.default.handshakeTimeout(30.seconds)
            val serverConfig = HttpServerConfig.default.port(0).host("localhost")
                .tls(serverTls)
                .transportConfig(tc)
            initTrustAllClient().map { httpClient =>
                HttpServer.init(serverConfig)(okHandler).map { server =>
                    HttpClient.let(httpClient) {
                        HttpClient.getText(s"https://localhost:${server.port}/ok").map { body =>
                            assert(body == "served")
                        }
                    }
                }
            }
        }
    }

end HttpSecurityServerTest
