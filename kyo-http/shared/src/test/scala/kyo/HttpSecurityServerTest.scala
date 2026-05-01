package kyo

import kyo.*
import kyo.internal.transport.*

class HttpSecurityServerTest extends Test:

    val echoRoute   = HttpRoute.postRaw("echo").response(_.bodyText)
    val echoHandler = echoRoute.handler(_ => HttpResponse.ok("ok"))

    /** Start a plain HTTP server and run a test against it. */
    def withEchoServer(
        test: (String, Int) => Assertion < (Async & Abort[Any] & Scope)
    )(using Frame): Assertion < (Scope & Async & Abort[Any]) =
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
    def assertRejected(response: String, description: String): Assertion =
        if response.isEmpty then
            // Connection closed — server rejected the request
            succeed
        else if response.contains("400") then
            succeed
        else
            fail(
                s"$description: Expected 400 Bad Request or connection close, but got:\n${response.take(200)}"
            )

    "request smuggling defenses" - {

        "duplicate Content-Length headers (CVE-2019-20445)" in run {
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

        "CL+TE conflict (classic CL.TE smuggling)" in run {
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

        "Content-Length integer overflow" in run {
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

end HttpSecurityServerTest
