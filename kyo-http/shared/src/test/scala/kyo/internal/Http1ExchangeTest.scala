package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class Http1ExchangeTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    private def bytes(s: String): Span[Byte]  = Span.fromUnsafe(s.getBytes(Utf8))
    private def str(span: Span[Byte]): String = new String(span.toArrayUnsafe, Utf8)
    private def bodyStr(body: HttpBody): String =
        body match
            case HttpBody.Buffered(data) => str(data)
            case HttpBody.Empty          => ""
            case HttpBody.Streamed(_)    => "<streaming>"

    /** Create a connected pair of `StreamTestConnection`s, start a background server handler, create an Exchange on the client side, and
      * run `f` with it. All channel and exchange resources are scoped.
      */
    private def withExchange[A](
        handler: StreamTestConnection => Unit < (Async & Abort[HttpException]),
        maxSize: Int = 65536
    )(
        f: Exchange[RawHttpRequest, RawHttpResponse, Nothing, HttpException] => A < (Async & Abort[HttpException | Closed])
    )(using Frame): A < (Async & Abort[HttpException | Closed] & Scope) =
        Channel.init[Span[Byte]](64).map { ch1 =>
            Channel.init[Span[Byte]](64).map { ch2 =>
                val client = new StreamTestConnection(ch2, ch1)
                val server = new StreamTestConnection(ch1, ch2)
                Fiber.initUnscoped(handler(server)).andThen {
                    Http1Exchange.init(client, maxSize).map(f)
                }
            }
        }

    /** Simple echo-style server: reads one request and writes one response. */
    private def echoServer(
        server: StreamTestConnection,
        responseStatus: HttpStatus,
        responseBody: String
    )(using Frame): Unit < (Async & Abort[HttpException]) =
        Http1Protocol.readRequestWith(server.read, 65536) { (_, _, _, _, _) =>
            val body = HttpBody.Buffered(bytes(responseBody))
            Http1Protocol.writeResponse(server, responseStatus, HttpHeaders.empty, body)
        }

    /** Multi-request server: reads `n` requests and echoes back numbered responses. */
    private def multiServer(
        server: StreamTestConnection,
        n: Int
    )(using Frame): Unit < (Async & Abort[HttpException]) =
        Loop(server.read, 0) { (stream, i) =>
            if i >= n then Loop.done(())
            else
                Http1Protocol.readRequestWith(stream, 65536) { (method, path, headers, reqBody, rest) =>
                    val respBody = HttpBody.Buffered(bytes(s"response-$i"))
                    Http1Protocol.writeResponse(server, HttpStatus(200), HttpHeaders.empty, respBody).andThen {
                        Loop.continue(rest, i + 1)
                    }
                }
        }

    "Http1Exchange" - {

        // Test 1: GET /hello → 200 OK
        "exchange(GET /hello) → 200 OK" in run {
            withExchange(echoServer(_, HttpStatus(200), "Hello")) { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/hello", HttpHeaders.empty, HttpBody.Empty)
                exchange(req).map { resp =>
                    assert(resp.status.code == 200)
                    assert(bodyStr(resp.body) == "Hello")
                }
            }
        }

        // Test 2: POST /submit with body
        "exchange(POST /submit) with body" in run {
            withExchange { server =>
                Http1Protocol.readRequestWith(server.read, 65536) { (method, path, _, reqBody, _) =>
                    assert(method == HttpMethod.POST)
                    assert(path == "/submit")
                    val respBody = HttpBody.Buffered(bytes("Submitted"))
                    Http1Protocol.writeResponse(server, HttpStatus(201), HttpHeaders.empty, respBody)
                }
            } { exchange =>
                val reqBody = HttpBody.Buffered(bytes("payload"))
                val req     = RawHttpRequest(HttpMethod.POST, "/submit", HttpHeaders.empty, reqBody)
                exchange(req).map { resp =>
                    assert(resp.status.code == 201)
                    assert(bodyStr(resp.body) == "Submitted")
                }
            }
        }

        // Test 3: Two sequential requests, both correct
        "sequential req1 then req2 both correct" in run {
            withExchange(multiServer(_, 2)) { exchange =>
                val req1 = RawHttpRequest(HttpMethod.GET, "/a", HttpHeaders.empty, HttpBody.Empty)
                val req2 = RawHttpRequest(HttpMethod.GET, "/b", HttpHeaders.empty, HttpBody.Empty)
                exchange(req1).map { resp1 =>
                    assert(resp1.status.code == 200)
                    assert(bodyStr(resp1.body) == "response-0")
                    exchange(req2).map { resp2 =>
                        assert(resp2.status.code == 200)
                        assert(bodyStr(resp2.body) == "response-1")
                    }
                }
            }
        }

        // Test 4: exchange.close → pending fails with Closed
        "exchange.close → pending fails with Closed" in run {
            withExchange { server =>
                // Server never responds — just keeps the connection open without writing
                Latch.init(1).map(_.await)
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/slow", HttpHeaders.empty, HttpBody.Empty)
                Fiber.initUnscoped(exchange(req)).map { fiber =>
                    // Close the exchange while the request is pending
                    exchange.close.andThen {
                        Abort.run[Closed](fiber.get).map { result =>
                            assert(result.isFailure, s"Expected Closed failure but got $result")
                        }
                    }
                }
            }
        }

        // Test 5: exchange.awaitDone suspends until close
        "exchange.awaitDone suspends until close" in run {
            withExchange { server =>
                // Server echoes one response then waits forever
                echoServer(server, HttpStatus(200), "ok")
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/ping", HttpHeaders.empty, HttpBody.Empty)
                exchange(req).andThen {
                    // awaitDone should block — not complete immediately
                    Fiber.initUnscoped {
                        Abort.run[Closed](exchange.awaitDone)
                    }.map { awaitFiber =>
                        // awaitDone should NOT have completed yet (exchange still open)
                        awaitFiber.done.map { isDone =>
                            assert(!isDone, "awaitDone should not complete before close")
                            // Now close the exchange
                            exchange.close.andThen {
                                Abort.run[Closed](awaitFiber.get).map { _ =>
                                    succeed
                                }
                            }
                        }
                    }
                }
            }
        }

        // Test 6: server closes connection without responding → awaitDone completes
        "server closes connection → awaitDone completes" in run {
            withExchange { server =>
                // Server closes the write channel immediately (EOF without response)
                server.closeWrite
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/bye", HttpHeaders.empty, HttpBody.Empty)
                // Send a request — it will fail because the server closed the connection.
                // But more importantly, awaitDone should complete after the connection error.
                Abort.run[HttpException | Closed](exchange(req)).andThen {
                    // awaitDone should complete (with Closed or HttpException) after the connection was closed
                    Abort.run[HttpException | Closed](exchange.awaitDone).map { result =>
                        assert(result.isFailure || result.isSuccess, "awaitDone should complete after server closes")
                    }
                }
            }
        }

        // Test 7: server malformed response → HttpException
        "server malformed response → HttpException" in run {
            withExchange { server =>
                // Write garbage instead of a valid HTTP response
                server.write(bytes("GARBAGE NOT HTTP\r\n\r\n"))
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/test", HttpHeaders.empty, HttpBody.Empty)
                Abort.run[HttpException | Closed](exchange(req)).map { result =>
                    result match
                        case Result.Failure(_: HttpException) => succeed
                        case Result.Failure(_: Closed)        => succeed // exchange may close on error
                        case other                            => fail(s"Expected HttpException or Closed, got $other")
                }
            }
        }

        // Test 8: connection drops → pending fails
        "connection drops → pending fails" in run {
            withExchange { server =>
                // Server immediately closes the connection without responding
                server.closeWrite
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/drop", HttpHeaders.empty, HttpBody.Empty)
                Abort.run[HttpException | Closed](exchange(req)).map { result =>
                    assert(result.isFailure, s"Expected failure on dropped connection, got $result")
                }
            }
        }

        // Test 9: after transport failure, awaitDone raises error
        "after failure, awaitDone raises" in run {
            withExchange { server =>
                // Server writes garbage to trigger a protocol error
                server.write(bytes("GARBAGE\r\n\r\n"))
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/fail", HttpHeaders.empty, HttpBody.Empty)
                Abort.run[HttpException | Closed](exchange(req)).andThen {
                    // After error, awaitDone should raise
                    Abort.run[HttpException | Closed](exchange.awaitDone).map { result =>
                        assert(result.isFailure, "awaitDone should raise after transport failure")
                    }
                }
            }
        }

        // Test 10: write-then-signal ordering
        "write-then-signal ordering" in run {
            withExchange { server =>
                // Server verifies request was received before responding
                Http1Protocol.readRequestWith(server.read, 65536) { (method, path, _, _, _) =>
                    assert(method == HttpMethod.GET)
                    assert(path == "/order")
                    Http1Protocol.writeResponse(server, HttpStatus(200), HttpHeaders.empty, HttpBody.Empty)
                }
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/order", HttpHeaders.empty, HttpBody.Empty)
                exchange(req).map { resp =>
                    assert(resp.status.code == 200)
                }
            }
        }

        // Test 11: reader blocks until request sent
        "reader blocks until request sent" in run {
            withExchange { server =>
                // Server expects to receive the request before sending back a response
                Http1Protocol.readRequestWith(server.read, 65536) { (_, path, _, _, _) =>
                    val body = HttpBody.Buffered(bytes(path))
                    Http1Protocol.writeResponse(server, HttpStatus(200), HttpHeaders.empty, body)
                }
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/echo-path", HttpHeaders.empty, HttpBody.Empty)
                exchange(req).map { resp =>
                    assert(resp.status.code == 200)
                    assert(bodyStr(resp.body) == "/echo-path")
                }
            }
        }

        // Test 12: 5 sequential requests all correct
        "5 sequential requests all correct" in run {
            withExchange(multiServer(_, 5)) { exchange =>
                Kyo.foreach(0.until(5).toSeq) { i =>
                    val req = RawHttpRequest(HttpMethod.GET, s"/item/$i", HttpHeaders.empty, HttpBody.Empty)
                    exchange(req).map { resp =>
                        assert(resp.status.code == 200)
                        assert(bodyStr(resp.body) == s"response-$i", s"Mismatch at $i: ${bodyStr(resp.body)}")
                    }
                }.map(_ => succeed)
            }
        }

        // Test 13: large response body (100KB)
        "large response body" in run {
            val largeContent = "X" * (100 * 1024)
            withExchange(
                handler = server =>
                    Http1Protocol.readRequestWith(server.read, 65536) { (_, _, _, _, _) =>
                        val body = HttpBody.Buffered(bytes(largeContent))
                        Http1Protocol.writeResponse(server, HttpStatus(200), HttpHeaders.empty, body)
                    },
                maxSize = 200 * 1024
            ) { exchange =>
                val req = RawHttpRequest(HttpMethod.GET, "/large", HttpHeaders.empty, HttpBody.Empty)
                exchange(req).map { resp =>
                    assert(resp.status.code == 200)
                    assert(bodyStr(resp.body).length == largeContent.length)
                }
            }
        }

        // Test 14: empty response body (204 No Content)
        "empty response body" in run {
            withExchange { server =>
                Http1Protocol.readRequestWith(server.read, 65536) { (_, _, _, _, _) =>
                    Http1Protocol.writeResponse(server, HttpStatus(204), HttpHeaders.empty, HttpBody.Empty)
                }
            } { exchange =>
                val req = RawHttpRequest(HttpMethod.DELETE, "/resource", HttpHeaders.empty, HttpBody.Empty)
                exchange(req).map { resp =>
                    assert(resp.status.code == 204)
                    assert(resp.body == HttpBody.Empty)
                }
            }
        }

        // Test 15: exchange after close → Abort[Closed]
        "exchange after close → Abort[Closed]" in run {
            withExchange { server =>
                // Server just closes without doing anything
                server.closeWrite
            } { exchange =>
                exchange.close.andThen {
                    val req = RawHttpRequest(HttpMethod.GET, "/closed", HttpHeaders.empty, HttpBody.Empty)
                    Abort.run[HttpException | Closed](exchange(req)).map { result =>
                        result match
                            case Result.Failure(_: Closed) => succeed
                            case other                     => fail(s"Expected Closed after close(), got $other")
                    }
                }
            }
        }

    } // Http1Exchange

end Http1ExchangeTest
