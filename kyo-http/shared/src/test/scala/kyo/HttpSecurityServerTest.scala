package kyo

import kyo.*
import kyo.internal.transport.*

class HttpSecurityServerTest extends BaseHttpTest:

    val echoRoute   = HttpRoute.postRaw("echo").response(_.bodyText)
    val echoHandler = echoRoute.handler(_ => HttpResponse.ok("ok"))

    // A buffered route that reads and echoes the request body, used to observe what body the handler actually received.
    val bodyRoute   = HttpRoute.postRaw("body").request(_.bodyText).response(_.bodyText)
    val bodyHandler = bodyRoute.handler(req => HttpResponse.ok(s"[${req.fields.body}]"))

    // A GET route whose response body is a recognizable marker, used as the target a smuggled request would reach.
    val markerRoute   = HttpRoute.getRaw("marker").response(_.bodyText)
    val markerHandler = markerRoute.handler(_ => HttpResponse.ok("SMUGGLED-MARKER"))

    // A streaming-response route, used to observe whether a HEAD response carries a body.
    val streamRoute = HttpRoute.getRaw("stream").response(_.bodyStream)
    val streamHandler = streamRoute.handler(_ =>
        HttpResponse.ok.addField("body", Stream.init(Seq(Span.fromUnsafe("STREAM-BODY".getBytes("UTF-8")))))
    )

    // A route whose response carries no body, used to observe the empty-response (onEmpty) framing.
    val emptyRoute   = HttpRoute.getRaw("empty").response(_.header[String]("X-Empty"))
    val emptyHandler = emptyRoute.handler(_ => HttpResponse.ok.addField("X-Empty", "yes"))

    /** Start a plain HTTP server and run a test against it. */
    def withEchoServer(
        test: (String, Int) => Unit < (Async & Abort[Any] & Scope)
    )(using Frame): Unit < (Scope & Async & Abort[Any]) =
        HttpServer.init(0, "localhost")(echoHandler, bodyHandler, markerHandler, streamHandler, emptyHandler).map { s =>
            test("localhost", s.port)
        }

    /** Opens a connection, writes each of `writes` with a pause between them, then reads everything the server sends
      * back within the budget. Returns the concatenated response bytes as a string. The staged writes let a test place
      * bytes on the wire AFTER the server has already answered an earlier part, which is how a split-read desync is
      * exercised.
      */
    def sendStaged(host: String, port: Int, writes: Seq[String])(using Frame): String < (Async & Abort[Any]) =
        Sync.Unsafe.defer {
            kyo.net.NetPlatform.transport.connect(host, port).safe.get.map { conn =>
                def writeAll(rem: Seq[String]): Unit < (Async & Abort[Any]) =
                    rem match
                        case Seq() => Kyo.unit
                        case w +: tail =>
                            Abort.run[Closed](conn.outbound.safe.put(Span.fromUnsafe(w.getBytes("ISO-8859-1")))).andThen {
                                Async.sleep(300.millis).andThen(writeAll(tail))
                            }
                def drain(acc: String, n: Int): String < (Async & Abort[Any]) =
                    if n > 8 then acc
                    else
                        Abort.run[Any](Async.timeout(500.millis)(Abort.run[Closed](conn.inbound.safe.take))).map {
                            case Result.Success(Result.Success(d)) => drain(acc + new String(d.toArray, "ISO-8859-1"), n + 1)
                            case _                                 => acc
                        }
                writeAll(writes).andThen(drain("", 0)).map(all => Sync.Unsafe.defer(conn.close()).andThen(all))
            }
        }

    /** Send raw bytes over TCP to host:port and read the WHOLE response as a string, draining spans until the peer closes or a read
      * finds no more data within the idle budget. The status line, headers and body can arrive in separate reads, so a single take
      * would capture only the leading span.
      *
      * Returns the concatenated response, or the empty string when the server answered nothing (a close or timeout with no bytes). An
      * empty result is not by itself proof of rejection; `assertRejected` treats it as a failure, not a pass.
      */
    def sendRawBytes(host: String, port: Int, raw: Array[Byte])(using Frame): String < (Async & Abort[Any]) =
        Sync.Unsafe.defer {
            val transport = kyo.net.NetPlatform.transport
            val fiber     = transport.connect(host, port)
            Abort.run[Closed](fiber.safe.get).map {
                case Result.Success(conn) =>
                    val payload = Span.fromUnsafe(raw)
                    // Write the malicious request
                    Abort.run[Closed](conn.outbound.safe.put(payload)).map { _ =>
                        // Read the WHOLE response, not just the first span. The status line, headers and body can arrive in
                        // separate reads (they reliably split on a loaded CI runner, coalesce locally), so a single take would
                        // capture only the leading span and miss a body a later span carries. Drain spans until the peer closes
                        // or a read finds no more data within the idle budget; the first read's longer budget covers the server's
                        // processing latency, so no upfront fixed sleep is needed.
                        def drain(acc: String, n: Int): String < (Async & Abort[Any]) =
                            if n >= 64 then acc
                            else
                                // A generous first-read budget covers server latency; a shorter per-span idle budget bounds the tail
                                // wait once bytes are flowing (only a keep-alive response with no close pays it). ISO-8859-1 is
                                // byte-preserving, so concatenating per-span decodes can never corrupt a boundary the way a split
                                // multibyte UTF-8 sequence would (matches sendStaged).
                                val budget = if n == 0 then 2.seconds else 1.second
                                Abort.run[Any](Async.timeout(budget)(Abort.run[Closed](conn.inbound.safe.take))).map {
                                    case Result.Success(Result.Success(data)) =>
                                        drain(acc + new String(data.toArray, "ISO-8859-1"), n + 1)
                                    case _ =>
                                        // Peer close, idle timeout, or error: the response is complete. Empty acc means the
                                        // server answered nothing (a close or timeout with no bytes), which assertRejected
                                        // rejects as no proof of refusal.
                                        acc
                                }
                        drain("", 0).map { response =>
                            Sync.Unsafe.defer(conn.close()).andThen(response)
                        }
                    }
                case _ => "" // connection failed
            }
        }

    /** Checks that the server answered a malformed request with 400.
      *
      * An empty read is NOT accepted as a rejection. `sendRawBytes` returns "" for a closed connection, a timeout AND a
      * failed connect alike, so treating silence as success would mean these tests could not fail for any infrastructure
      * reason: a server that never started would pass every one of them. Requiring the status also pins the behavior that
      * makes silence unnecessary, since a peer that is refused is told so (RFC 9112 section 6.3) rather than left to infer
      * it from a disconnect after its own timeout expires.
      *
      * The check anchors on the FIRST status line (`startsWith`), not a substring `contains("400")`. `sendRawBytes` now drains
      * the whole response, so a `contains` would pass on a later 400 too: exactly the smuggling failure these leaves guard, where
      * a server that treats the malformed framing as bodyless answers 200 to the request and 400 to the reparsed leftover. Only a
      * refusal answered as the FIRST response proves the request itself was rejected. This also closes the pre-existing hole where
      * a `Content-Length: 400` (or any incidental "400") satisfied the substring.
      */
    def assertRejected(response: String, description: String)(using kyo.test.AssertScope): Unit =
        if response.isEmpty then
            fail(
                s"$description: expected a 400 Bad Request, but the connection produced nothing. " +
                    "An empty read here is a closed, timed-out or never-established connection, none of which prove the request was refused."
            )
        else
            assert(
                response.startsWith("HTTP/1.1 400"),
                s"$description: expected the first response to be 400 Bad Request, but got:\n${response.take(200)}"
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

    // Transfer-Encoding framing, RFC 9112 section 6.1 and 6.3. These cover the TE.TE smuggling family: the danger is not a
    // malformed header on its own but a DISAGREEMENT between an upstream intermediary and this server about whether a body is
    // chunked. Whichever way the disagreement falls, the bytes one party treats as a body the other treats as a new request.
    // Rejecting is always a safe resolution, so each leaf asserts rejection rather than a particular framing interpretation.
    "Transfer-Encoding smuggling defenses (GHSA-jrpm-956j-96jg)" - {

        // A chunked request body to a BUFFERED route must reach the handler decoded, not be silently discarded. The
        // server only decodes chunked for streaming-request routes; an ordinary buffered route reads the body by
        // Content-Length, which is -1 for a chunked request, so the handler receives an empty body. That is both data
        // loss and a framing gap: the chunk bytes are neither decoded nor consumed, and the maxContentLength check is
        // skipped for chunked requests, so on a split read the chunk tail can be reparsed as the next request. This
        // asserts the handler receives the body and therefore FAILS until buffered routes decode chunked (or reject it).
        //
        // Origin: the unconsumed-body-reinterpreted class, Go GO-2023-1495 / Node CVE-2022-32213.
        "a chunked body to a buffered route reaches the handler" in {
            withEchoServer { (host, port) =>
                val raw =
                    ("POST /body HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n").getBytes("UTF-8")
                sendRawBytes(host, port, raw).map { response =>
                    assert(
                        response.contains("[hello]"),
                        s"the handler must receive the decoded chunked body, but got:\n${response.take(200)}"
                    )
                }
            }
        }

        // RFC 9112 section 6.1: chunked must be the FINAL encoding. Here it is final, so the message IS chunked, but the gzip
        // coding is one this server does not implement (section 6.1: respond 501 for a transfer coding it does not understand).
        // A front end that decodes gzip and forwards would frame a body this server must not silently treat as absent.
        "Transfer-Encoding: gzip, chunked (unsupported coding before chunked)" in {
            withEchoServer { (host, port) =>
                val raw =
                    ("POST /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: gzip, chunked\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n").getBytes("UTF-8")
                sendRawBytes(host, port, raw).map { response =>
                    assertRejected(response, "Transfer-Encoding: gzip, chunked")
                }
            }
        }

        // RFC 9112 section 6.3: Transfer-Encoding present whose final coding is NOT chunked means the message length is
        // undeterminable, and a server must respond 400. Accepting it as a bodyless request leaves any body bytes in the
        // buffer to be read as the next request.
        "Transfer-Encoding: identity (no chunked, length undeterminable)" in {
            withEchoServer { (host, port) =>
                val raw =
                    ("POST /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: identity\r\n" +
                        "\r\n" +
                        "hello").getBytes("UTF-8")
                sendRawBytes(host, port, raw).map { response =>
                    assertRejected(response, "Transfer-Encoding: identity")
                }
            }
        }

        // "chunkedfoo" is not the token "chunked"; RFC 9110 section 5.6.2 tokens are delimited, not prefixes. A parser that
        // matches a prefix accepts this as chunked while a strict intermediary rejects it, which is the desync.
        "Transfer-Encoding: chunkedfoo (token must not match by prefix)" in {
            withEchoServer { (host, port) =>
                val raw =
                    ("POST /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunkedfoo\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n").getBytes("UTF-8")
                sendRawBytes(host, port, raw).map { response =>
                    assertRejected(response, "Transfer-Encoding: chunkedfoo")
                }
            }
        }

        // Two Transfer-Encoding header lines. Intermediaries differ on whether to combine them into a list or honor one, so a
        // server that silently picks one desyncs against a front end that picked the other.
        "duplicate Transfer-Encoding headers" in {
            withEchoServer { (host, port) =>
                val raw =
                    ("POST /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Transfer-Encoding: identity\r\n" +
                        "\r\n" +
                        "5\r\n" +
                        "hello\r\n" +
                        "0\r\n" +
                        "\r\n").getBytes("UTF-8")
                sendRawBytes(host, port, raw).map { response =>
                    assertRejected(response, "duplicate Transfer-Encoding")
                }
            }
        }

    }

    // Connection reclamation on a refused request. A server that answers and then holds the socket open is a slow
    // resource leak an unauthenticated peer controls: every refused request costs a file descriptor that nothing
    // reclaims, since the idle timer is either cancelled by then or was never armed. The peer needs no valid request
    // and no open connection of its own to spend one.
    "refused requests release the connection (RFC 9112 section 9.6)" - {

        /** Sends `raw`, then reports whether the server ended the connection within the deadline. */
        def reapedAfter(host: String, port: Int, raw: String)(using Frame): Boolean < (Async & Abort[Any]) =
            Sync.Unsafe.defer {
                kyo.net.NetPlatform.transport.connect(host, port).safe.get.map { conn =>
                    Abort.run[Closed](conn.outbound.safe.put(Span.fromUnsafe(raw.getBytes("UTF-8")))).map { _ =>
                        // Read until the peer closes (an empty span or a Closed failure) or the budget runs out. A
                        // response arriving first is expected and not the thing under test, so keep reading past it.
                        def drain(n: Int): Boolean < (Async & Abort[Any]) =
                            if n > 20 then false
                            else
                                Abort.run[Any](Async.timeout(10.seconds)(Abort.run[Closed](conn.inbound.safe.take))).map {
                                    case Result.Success(Result.Success(span)) => if span.isEmpty then true else drain(n + 1)
                                    case Result.Success(Result.Failure(_))    => true
                                    case _                                    => false
                                }
                        drain(0).map { reaped =>
                            Sync.Unsafe.defer(conn.close()).andThen(reaped)
                        }
                    }
                }
            }

        // A LONG idle timeout, deliberately: with a short one every leaf below would pass on the timer expiring rather
        // than on the connection being closed, which is a different mechanism and not the one under test. At 30 seconds
        // against a 10 second read budget, only an explicit close can produce EOF.
        def assertReaped(raw: String, description: String)(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[Any] & Scope) =
            val cfg = HttpServerConfig.default.port(0).host("localhost").idleTimeout(30.seconds)
            HttpServer.init(cfg)(echoHandler).map { server =>
                reapedAfter("localhost", server.port, raw).map { reaped =>
                    assert(reaped, s"$description: the server answered but never released the connection")
                }
            }
        end assertReaped

        // A request with no headers at all is the case that leaked: keep-alive is carried by a header, so a bare
        // request line is not keep-alive, and the branch that handled that answered without closing or rearming
        // anything. This and the leaf below are the two that actually exercise the close.
        "a request with a missing Host header" in {
            assertReaped("GET /echo HTTP/1.1\r\n\r\n", "missing Host")
        }

        // The parser-level refusal, which reaches a different branch than the Host checks.
        "a request the parser refused" in {
            assertReaped("POST /echo HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: identity\r\n\r\nhello", "unframable request")
        }

        "a request whose Host header is missing and that asked to close" in {
            assertReaped("GET /echo HTTP/1.1\r\nConnection: close\r\n\r\n", "missing Host with Connection: close")
        }

        // An invalid Host on a KEEP-ALIVE request is deliberately NOT closed, and this pins that rather than leaving it
        // to look like an oversight. The message was framed correctly and only its content was wrong, so the next
        // request's boundary is known and the connection stays usable; RFC 9110 section 7.2 asks for the 400, not for a
        // teardown. The connection is then reclaimed by the ordinary idle timer like any other idle connection.
        //
        // Earlier versions of these leaves used a one second idle timeout, which made this case indistinguishable from
        // the closing ones: all four went to EOF and three of them were passing on the timer.
        "an invalid Host on a keep-alive request is answered without closing" in {
            val cfg = HttpServerConfig.default.port(0).host("localhost").idleTimeout(30.seconds)
            HttpServer.init(cfg)(echoHandler).map { server =>
                reapedAfter("localhost", server.port, "GET /echo HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n").map { reaped =>
                    assert(!reaped, "a keep-alive request with a bad Host must be answered, not disconnected")
                }
            }
        }
    }

    // Connection integrity across a request boundary. Both leaves here concern what a recipient reads AFTER a
    // response, on a connection it will keep using.
    "connection integrity defenses" - {

        // A request that is answered but whose declared body is not consumed must not leave that body to be parsed as
        // the next request. Here a POST to a nonexistent path carries a Content-Length body that is itself a valid
        // GET /marker request. The server answers 404, restarts the parser for keep-alive, and then reads the body
        // bytes, which arrive in a SEPARATE segment after the 404, as a fresh request, serving /marker. A front end
        // that allowed the POST but would have blocked GET /marker is bypassed. RFC 9112 section 9.3 requires a
        // recipient that does not consume the body to close the connection instead of reusing it. This asserts the
        // marker is never served and therefore FAILS until the rejected request's body is drained or the connection
        // closed.
        //
        // Origin: the unconsumed-body-reinterpreted smuggling class (Undertow CVE-2020-10719, RFC 9112 section 9.3).
        "a rejected request's unconsumed body is not parsed as the next request" in {
            withEchoServer { (host, port) =>
                val smuggled = "GET /marker HTTP/1.1\r\nHost: localhost\r\n\r\n"
                val headers  = s"POST /missing HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${smuggled.length}\r\n\r\n"
                // Two staged writes: headers first (the server answers 404), then the body in a later segment.
                sendStaged(host, port, Seq(headers, smuggled)).map { response =>
                    assert(
                        !response.contains("SMUGGLED-MARKER"),
                        s"the unconsumed body was reparsed and /marker was served:\n${response.take(300)}"
                    )
                }
            }
        }

        // The same unconsumed-body class on the 413 path: an over-limit Content-Length is answered 413 WITHOUT
        // reading the body, so reusing the connection would reparse that body. The server must close instead.
        //
        // Origin: the unconsumed-body-reinterpreted smuggling class on a rejected-by-size request (RFC 9112 section 9.3).
        "a 413-rejected request's unconsumed body is not parsed as the next request" in {
            val cfg = HttpServerConfig.default.port(0).host("localhost").maxContentLength(10)
            HttpServer.init(cfg)(echoHandler, bodyHandler, markerHandler, streamHandler).map { server =>
                val smuggled = "GET /marker HTTP/1.1\r\nHost: localhost\r\n\r\n"
                // Content-Length is the smuggled request length, well over the 10-byte limit, so the POST is 413'd.
                val headers = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${smuggled.length}\r\n\r\n"
                sendStaged("localhost", server.port, Seq(headers, smuggled)).map { response =>
                    assert(response.contains("413"), s"expected a 413, got:\n${response.take(300)}")
                    assert(
                        !response.contains("SMUGGLED-MARKER"),
                        s"the unconsumed body after a 413 was reparsed and /marker was served:\n${response.take(300)}"
                    )
                }
            }
        }

        // The same class on the invalid-Host path: multiple Host headers are answered 400 WITHOUT reading the
        // declared body, so reusing the connection would reparse that body. The server must close instead.
        //
        // Origin: the unconsumed-body-reinterpreted smuggling class on a rejected-by-Host request (RFC 9112 section 9.3).
        "an invalid-Host request's unconsumed body is not parsed as the next request" in {
            withEchoServer { (host, port) =>
                val smuggled = "GET /marker HTTP/1.1\r\nHost: localhost\r\n\r\n"
                val headers  = s"POST /echo HTTP/1.1\r\nHost: a\r\nHost: b\r\nContent-Length: ${smuggled.length}\r\n\r\n"
                sendStaged(host, port, Seq(headers, smuggled)).map { response =>
                    assert(response.contains("400"), s"expected a 400, got:\n${response.take(300)}")
                    assert(
                        !response.contains("SMUGGLED-MARKER"),
                        s"the unconsumed body after a 400 was reparsed and /marker was served:\n${response.take(300)}"
                    )
                }
            }
        }

        // No per-request state may cross a keep-alive connection boundary. Jetty CVE-2026-10051 (trailers not reset),
        // CVE-2020-27218 / CVE-2024-13009 (body buffer not recycled), and CVE-2015-2080 (a prior request's parse
        // buffer echoed) are all the same failure: a field left set from request A is read by request B on the same
        // connection, so B is served with A's body, trailers, or bytes. Two pipelined POSTs with distinct bodies must
        // each be answered with their OWN body.
        "does not leak a request body into the next pipelined request (CVE-2026-10051, CVE-2020-27218)" in {
            withEchoServer { (host, port) =>
                val a = "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nAAA"
                val b = "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nBBB"
                // Both requests in one write, back to back on the same connection.
                sendStaged(host, port, Seq(a + b)).map { response =>
                    assert(response.contains("[AAA]"), s"the first body must be served, got:\n${response.take(300)}")
                    assert(response.contains("[BBB]"), s"the second body must be served, got:\n${response.take(300)}")
                    assert(
                        !response.contains("[AAABBB]") && !response.contains("[AAABBB") && !response.contains("[BBBAAA"),
                        s"a body leaked across the pipelined boundary:\n${response.take(300)}"
                    )
                }
            }
        }

        // RFC 9112 section 9.4 / RFC 9110 section 9.3.2: a response to a HEAD request must not carry a message body.
        // The streaming-response encoder writes Transfer-Encoding: chunked and the chunk data regardless of method,
        // and the router maps HEAD to the GET handler, so a HEAD to a streaming route emits a body. A client that
        // correctly treats HEAD as bodyless then reads those body bytes as the next response on a pooled connection.
        // This asserts the HEAD response carries no body and therefore FAILS until the streaming encoder honors HEAD.
        //
        // Origin: the HEAD-with-body response-desync class (RFC 9112 section 9.4).
        "a HEAD to a streaming route carries no body" in {
            withEchoServer { (host, port) =>
                sendStaged(host, port, Seq("HEAD /stream HTTP/1.1\r\nHost: localhost\r\n\r\n")).map { response =>
                    assert(
                        !response.contains("STREAM-BODY"),
                        s"a HEAD response must carry no body, but the stream body was written:\n${response.take(300)}"
                    )
                }
            }
        }

        // A HEAD to a BUFFERED route is Content-Length framed and carries no body. The encoder terminated it with the
        // chunked last-chunk marker (0\r\n\r\n), which belongs only to a chunked response; those 5 bytes then read as
        // the head of the next response on a pooled connection. The HEAD head plus its Content-Length fully frames the
        // response, so no chunk terminator may appear. This asserts the pipelined GET after the HEAD is cleanly served.
        //
        // Origin: the HEAD-with-body response-desync class (RFC 9112 section 6.3 / 9.4).
        "a HEAD to a buffered route writes no chunk terminator and leaves the connection framed" in {
            withEchoServer { (host, port) =>
                val head = "HEAD /marker HTTP/1.1\r\nHost: localhost\r\n\r\n"
                val get  = "GET /marker HTTP/1.1\r\nHost: localhost\r\n\r\n"
                sendStaged(host, port, Seq(head + get)).map { response =>
                    // A spurious chunk terminator appears as a lone "0\r\n\r\n" after the head's blank line. (A plain
                    // "0\r\n\r\n" substring also occurs inside "Content-Length: 0\r\n\r\n", so match the blank line too.)
                    assert(
                        !response.contains("\r\n\r\n0\r\n\r\n"),
                        s"a HEAD response wrote a spurious chunk terminator:\n${response.take(300)}"
                    )
                    assert(
                        response.contains("SMUGGLED-MARKER"),
                        s"the pipelined GET after a HEAD must be served:\n${response.take(300)}"
                    )
                }
            }
        }

        // An empty (no-body) response is Content-Length: 0 framed. The encoder terminated it with the chunked
        // last-chunk marker (0\r\n\r\n), which desyncs the pipelined follow-up exactly as the HEAD case does. The
        // Content-Length: 0 head fully frames the response, so no chunk terminator may appear.
        //
        // Origin: the empty-response chunk-terminator desync class (RFC 9112 section 6.3).
        "an empty response writes no chunk terminator and leaves the connection framed" in {
            withEchoServer { (host, port) =>
                val empty = "GET /empty HTTP/1.1\r\nHost: localhost\r\n\r\n"
                val get   = "GET /marker HTTP/1.1\r\nHost: localhost\r\n\r\n"
                sendStaged(host, port, Seq(empty + get)).map { response =>
                    // A spurious chunk terminator appears as a lone "0\r\n\r\n" after the head's blank line. (A plain
                    // "0\r\n\r\n" substring also occurs inside "Content-Length: 0\r\n\r\n", so match the blank line too.)
                    assert(
                        !response.contains("\r\n\r\n0\r\n\r\n"),
                        s"an empty response wrote a spurious chunk terminator:\n${response.take(300)}"
                    )
                    assert(
                        response.contains("SMUGGLED-MARKER"),
                        s"the pipelined GET after an empty response must be served:\n${response.take(300)}"
                    )
                }
            }
        }
    }

    "handshake-stall DoS defenses" - {

        // The cross-backend reap mechanism itself (including Native) is covered by kyo-net's TransportHandshakeTimeoutTest
        // via the public NetPlatform.transport singleton; this test covers the kyo-http wiring:
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
                // handshake parks. The bug this guards (handshakeTimeout not honored by the server's transport) would leave
                // the connection pinned and the bounded await below would expire (Timeout, the regression symptom); the
                // deadline reaps the accepted fd, which the client observes as its inbound terminating (Closed, or an empty
                // EOF span).
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
