package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.client.*
import kyo.internal.http1.Http1ClientConnection
import kyo.net.Connection.Status
import kyo.net.TestChannelTransport
import kyo.net.internal.transport.Connection as TransportConnection
import kyo.scheduler.IOPromise

/** How the public client reports a response it cannot read: a peer that closes before or during the response, a head it refuses, a
  * body cut short, and bytes a peer sends that belong to no request. Most peers are raw kyo-net listeners, so each byte on the wire is
  * the test's choice; where the split of those bytes into reads matters, the peer is an in-memory connection, which delivers each offer
  * as one read.
  */
class HttpClientBackendResponseFailureTest extends kyo.BaseHttpTest:

    import AllowUnsafe.embrace.danger

    private def bytes(s: String): Span[Byte] = Span.fromUnsafe(s.getBytes(StandardCharsets.US_ASCII))

    /** Runs `f` once the peer has received the next span of the request. */
    private def onRequest(conn: kyo.net.Connection)(f: => Unit): Unit =
        conn.inbound.takeFiber().asInstanceOf[IOPromise[Closed, Span[Byte]]].onComplete {
            case Result.Success(_) => f
            case _                 => ()
        }

    /** Writes `s` and closes. The transport flushes queued bytes before it releases the socket. */
    private def replyAndClose(conn: kyo.net.Connection, s: String): Unit =
        discard(conn.outbound.offer(bytes(s)))
        conn.close()

    private def withPeer[A](handler: kyo.net.Connection => Unit)(test: Int => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        Sync.Unsafe.defer(kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16)(handler)).map { fiber =>
            fiber.safe.use { listener =>
                Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen(test(listener.port))
            }
        }

    /** The length of the first complete request in `buf`: its head and its `Content-Length` or chunked body. Absent while it is
      * incomplete.
      */
    private def requestLength(buf: Array[Byte]): Maybe[Int] =
        val text    = new String(buf, StandardCharsets.ISO_8859_1)
        val headEnd = text.indexOf("\r\n\r\n")
        if headEnd < 0 then Absent
        else
            val head   = text.substring(0, headEnd).toLowerCase(java.util.Locale.ROOT)
            val bodyAt = headEnd + 4
            val lines  = Chunk.from(head.split("\r\n"))
            val length = Maybe.fromOption(lines.collectFirst {
                case line if line.startsWith("content-length:") => line.drop("content-length:".length).trim
            }).filter(v => v.nonEmpty && v.forall(_.isDigit)).map(_.toInt)
            val chunked = lines.exists(line => line.startsWith("transfer-encoding:") && line.contains("chunked"))
            if chunked then
                val end = text.indexOf("\r\n0\r\n\r\n", bodyAt - 2)
                if end < 0 then Absent else Present(end + 7)
            else
                val total = bodyAt + length.getOrElse(0)
                if buf.length >= total then Present(total) else Absent
            end if
        end if
    end requestLength

    /** A peer that answers every request on every connection with `respond(n)`, where `n` counts requests across connections from 1, and
      * closes the connection instead when `respond(n)` is `Absent`, or after answering when `closeAfter(n)`.
      *
      * A request counts once its head and body have both arrived, however the transport splits them into reads: the client writes a
      * request's head and body separately, and a driver may deliver them as two reads. The close is triggered by the request's arrival,
      * so a connection the client took from its pool closes exactly as it is reused: the deterministic form of an idle connection closed
      * by its server while the client hands it to a request.
      */
    private def withCountingPeer[A](respond: Int => Maybe[String], closeAfter: Int => Boolean = _ => false)(
        test: (Int, AtomicInt.Unsafe, AtomicInt.Unsafe) => A < (Async & Abort[Any] & Scope)
    )(using Frame): A < (Async & Abort[Any] & Scope) =
        val requests = AtomicInt.Unsafe.init(0)
        val accepts  = AtomicInt.Unsafe.init(0)
        withPeer { conn =>
            discard(accepts.incrementAndGet())
            def answer(buf: Array[Byte]): Unit =
                requestLength(buf) match
                    case Absent          => serve(buf)
                    case Present(length) =>
                        val n = requests.incrementAndGet()
                        respond(n) match
                            case Present(response) if closeAfter(n) => replyAndClose(conn, response)
                            case Present(response)                  =>
                                discard(conn.outbound.offer(bytes(response)))
                                answer(buf.drop(length))
                            case Absent => conn.close()
                        end match
            def serve(pending: Array[Byte]): Unit =
                conn.inbound.takeFiber().asInstanceOf[IOPromise[Closed, Span[Byte]]].onComplete {
                    case Result.Success(span) => answer(pending ++ span.toArrayUnsafe)
                    case _                    => ()
                }
            serve(Array.empty)
        }(port => test(port, requests, accepts))
    end withCountingPeer

    private def getText(port: Int, path: String = "/x")(using Frame): Result[HttpException, String] < Async =
        Abort.run[HttpException](HttpClient.getText(s"http://127.0.0.1:$port$path"))

    /** Status code and body text of a GET or HEAD on a fresh route, so body-less responses can be observed. */
    private def exchange(port: Int, method: HttpMethod)(using Frame): Result[HttpException, String] < Async =
        val route =
            if method == HttpMethod.HEAD then HttpRoute.headRaw("x").response(_.bodyText)
            else HttpRoute.getRaw("x").response(_.bodyText)
        val request = HttpRequest(method, HttpUrl.parse(s"http://127.0.0.1:$port/x").getOrThrow, HttpHeaders.empty, Record.empty)
        Abort.run[HttpException](HttpClient.use(_.sendWith(route, request)(res => s"${res.status.code} '${res.fields.body}'")))
    end exchange

    /** Consumes a streamed body, returning the bytes that arrived and how the stream ended. */
    private def streamed(port: Int)(using Frame): (String, Result[HttpException, Unit]) < Async =
        AtomicRef.init("").map { received =>
            Abort.run[HttpException](
                HttpClient.getStreamBytes(s"http://127.0.0.1:$port/s").foreach(span =>
                    received.updateAndGet(_ + new String(span.toArrayUnsafe, StandardCharsets.US_ASCII))
                )
            ).map(result => received.get.map(text => (text, result)))
        }

    /** Asserts whole-value equality and, on a mismatch, reports the observed value on one line. */
    private def assertValue[A](actual: A, expected: A)(using CanEqual[A, A], Frame, kyo.test.AssertScope): Unit =
        assert(actual == expected, s"observed: ${actual.toString.replaceAll("\\s+", " ").take(4000)}")

    private def protocolError(detail: String)(using Frame): Result[HttpException, Nothing] = Result.fail(HttpProtocolException(detail))
    private def closed(using Frame): Result[HttpException, Nothing]                        =
        Result.fail(HttpConnectionClosedException(HttpConnectionClosedException.Phase.BeforeHead))
    private def closedInBody(using Frame): Result[HttpException, Nothing] =
        Result.fail(HttpConnectionClosedException(HttpConnectionClosedException.Phase.BodyTruncated))
    private def closedTls(using Frame): Result[HttpException, Nothing] =
        Result.fail(HttpConnectionClosedException(HttpConnectionClosedException.Phase.TlsTruncated))

    "a connection that closes before the response head" - {

        "a peer that closes on accept fails the request with HttpConnectionClosedException" in {
            withPeer(conn => conn.close())(port => getText(port)).map(result => assertValue(result, closed))
        }

        "a peer that reads the request and closes without replying fails it with HttpConnectionClosedException" in {
            withPeer(conn => onRequest(conn)(conn.close()))(port => getText(port)).map(result => assertValue(result, closed))
        }

        // A close is a connectivity failure, not a decode failure: a caller that retries on HttpConnectionException must see it as one.
        "a close before the head is an HttpConnectionException" in {
            withPeer(conn => onRequest(conn)(conn.close()))(port => getText(port)).map { result =>
                assert(result.failure.exists(_.isInstanceOf[HttpConnectionException]), s"observed: $result")
            }
        }

        "a peer that closes after part of the head fails it with HttpConnectionClosedException" in {
            withPeer(conn => onRequest(conn)(replyAndClose(conn, "HTTP/1.1 200 OK\r\nContent-Le")))(port => getText(port)).map(result =>
                assertValue(result, closed)
            )
        }

        "connectRaw against a peer that closes before replying fails with HttpConnectionClosedException" in {
            withPeer(conn => onRequest(conn)(conn.close())) { port =>
                Scope.run(Abort.run[HttpException](HttpClient.connectRaw(s"http://127.0.0.1:$port/raw", HttpMethod.GET).unit))
            }.map(result => assertValue(result, closed))
        }

        "the WebSocket client against a peer that closes on accept fails with HttpConnectionClosedException" in {
            withPeer(conn => conn.close()) { port =>
                Abort.run[HttpException](HttpClient.webSocket(s"ws://127.0.0.1:$port/ws")(_ => Kyo.unit))
            }.map(result => assertValue(result, closed))
        }

        "a retry schedule does not resend a request whose connection closed before the head" in {
            withCountingPeer(_ => Absent) { (port, requests, accepts) =>
                HttpClient.withConfig(_.retry(Schedule.repeat(2)).retryOn(_ => true)) {
                    getText(port).map(result => (result, accepts.get()))
                }
            }.map(outcome => assertValue(outcome, (closed, 1)))
        }
    }

    "a pooled connection that closes as it is reused (RFC 9110 section 9.2.2)" - {

        val ok = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"

        /** Two requests on one client, the second on the connection the first returned to the pool, with the peer's request and accept
          * counts.
          */
        def twoOnOneClient(port: Int, requests: AtomicInt.Unsafe, accepts: AtomicInt.Unsafe)(
            send: Int => Result[HttpException, String] < Async
        )(using Frame): (Result[HttpException, String], Result[HttpException, String], Int, Int) < (Async & Scope) =
            HttpClient.init().map { client =>
                HttpClient.let(client) {
                    send(port).map(first => send(port).map(second => (first, second, requests.get(), accepts.get())))
                }
            }

        def post(port: Int)(using Frame): Result[HttpException, String] < Async =
            Abort.run[HttpException](HttpClient.postText(s"http://127.0.0.1:$port/x", "a"))

        "a GET that meets the close before any response byte is sent once more on a fresh connection" in {
            withCountingPeer(n => if n == 2 then Absent else Present(ok)) { (port, requests, accepts) =>
                twoOnOneClient(port, requests, accepts)(getText(_))
            }.map(outcome => assertValue(outcome, (Result.succeed("ok"), Result.succeed("ok"), 3, 2)))
        }

        "the retry is sent once: a fresh connection that also closes fails the GET" in {
            withCountingPeer(n => if n == 1 then Present(ok) else Absent) { (port, requests, accepts) =>
                twoOnOneClient(port, requests, accepts)(getText(_))
            }.map(outcome => assertValue(outcome, (Result.succeed("ok"), closed, 3, 2)))
        }

        "a POST that meets the close fails, and reached the peer once" in {
            withCountingPeer(n => if n == 2 then Absent else Present(ok)) { (port, requests, accepts) =>
                twoOnOneClient(port, requests, accepts)(post)
            }.map(outcome => assertValue(outcome, (Result.succeed("ok"), closed, 2, 1)))
        }

        // A response byte means the peer read the request and answered it, so the connection was not stale and a retry would send the
        // request to a peer that already acted on it.
        "a GET whose reused connection closes after part of the head fails, and reached the peer once" in {
            withCountingPeer(n => Present(if n == 2 then "HTTP/1.1 200 OK\r\nContent-Le" else ok), _ == 2) { (port, requests, accepts) =>
                twoOnOneClient(port, requests, accepts)(getText(_))
            }.map(outcome => assertValue(outcome, (Result.succeed("ok"), closed, 2, 1)))
        }

        "a GET on a first-use connection that closes before any response byte is not retried" in {
            withCountingPeer(_ => Absent) { (port, requests, accepts) =>
                getText(port).map(result => (result, requests.get(), accepts.get()))
            }.map(outcome => assertValue(outcome, (closed, 1, 1)))
        }
    }

    "a response head the client refuses" - {

        "a head larger than maxHeaderSize fails with HttpProtocolException" in {
            val head = "HTTP/1.1 200 OK\r\nX-Big: " + ("x" * 70000) + "\r\nContent-Length: 0\r\n\r\n"
            withPeer(conn => onRequest(conn)(discard(conn.outbound.offer(bytes(head)))))(port => getText(port)).map(result =>
                assertValue(result, protocolError("the response head exceeds 65536 bytes"))
            )
        }

        "each status line is accepted or refused by RFC 9112 section 4 and RFC 9110 section 2.5" in {
            val cases: Chunk[(String, Result[HttpException, String])] = Chunk(
                "HTTP/1.1 200 OK"  -> Result.succeed("ok"),
                "HTTP/1.1 200"     -> Result.succeed("ok"),
                "HTTP/1.1 200 "    -> Result.succeed("ok"),
                "HTTP/1.0 200 OK"  -> Result.succeed("ok"),
                "HTTP/1.2 200 OK"  -> Result.succeed("ok"),
                "HTTP/2.0 200 OK"  -> protocolError("the response status line does not begin with HTTP/1.x"),
                "http/1.1 200 OK"  -> protocolError("the response status line does not begin with HTTP/1.x"),
                "FOO 200 OK"       -> protocolError("the response status line does not begin with HTTP/1.x"),
                "XTTP/1.1 200 OK"  -> protocolError("the response status line does not begin with HTTP/1.x"),
                "HTTP/11 200 OK"   -> protocolError("the response status line does not begin with HTTP/1.x"),
                "HTTP/1 200 OK"    -> protocolError("the response status line does not begin with HTTP/1.x"),
                " 200 OK"          -> protocolError("the response status line does not begin with HTTP/1.x"),
                "HTTP/1.1\t200 OK" -> protocolError("the response status line does not begin with HTTP/1.x"),
                "GARBAGE"          -> protocolError("the response status line does not begin with HTTP/1.x"),
                "HTTP/1.1 20 OK"   -> protocolError("the response status line has no three-digit status code"),
                "HTTP/1.1  200 OK" -> protocolError("the response status line has no three-digit status code"),
                "HTTP/1.1 2x0 OK"  -> protocolError("the response status line has no three-digit status code"),
                "HTTP/1.1 000 OK"  -> protocolError("the response status code 0 is outside 100 to 599"),
                "HTTP/1.1 099 OK"  -> protocolError("the response status code 99 is outside 100 to 599"),
                "HTTP/1.1 600 OK"  -> protocolError("the response status code 600 is outside 100 to 599")
            )
            Kyo.foreach(cases) { (line, _) =>
                withPeer(conn => onRequest(conn)(discard(conn.outbound.offer(bytes(s"$line\r\nContent-Length: 2\r\n\r\nok")))))(port =>
                    getText(port).map(result => line -> result)
                )
            }.map(results => assertValue(results, cases))
        }

        "each header field fault fails with HttpProtocolException naming the fault, never the field" in {
            val cases: Chunk[(String, Result[HttpException, String])] = Chunk(
                "X-A: b\nX-B: c\r\n"                         -> protocolError("a response header line holds a bare CR or LF"),
                "X-A: b\rX-B: c\r\n"                         -> protocolError("a response header line holds a bare CR or LF"),
                "X-A: one\r\n two\r\n"                       -> protocolError("a response header line is folded"),
                "X Foo: bar\r\n"                             -> protocolError("a response header name is not a token"),
                "X-A: b\u0000c\r\n"                          -> protocolError("a response header value holds a NUL"),
                "Content-Length: 1a2b\r\n"                   -> protocolError("the response Content-Length is not a valid length"),
                "Content-Length: 5\r\nContent-Length: 2\r\n" -> protocolError("the response has conflicting Content-Length values")
            )
            Kyo.foreach(cases) { (fields, _) =>
                val response = s"HTTP/1.1 200 OK\r\n${fields}Connection: close\r\n\r\nok"
                withPeer(conn => onRequest(conn)(replyAndClose(conn, response)))(port => getText(port).map(result => fields -> result))
            }.map(results => assertValue(results, cases))
        }
    }

    "the head limit counts the head, not the read that carries it" - {

        "a small head and a body larger than maxHeaderSize in one read are accepted" in {
            val body = "y" * 150000
            withPeer(conn =>
                onRequest(conn)(discard(conn.outbound.offer(bytes(s"HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"))))
            ) { port =>
                HttpClient.init(transportConfig = HttpTransportConfig.default.readChunkSize(256.kib)).map { client =>
                    HttpClient.let(client)(getText(port).map(_.map(_.length)))
                }
            }.map(result => assertValue(result, Result.succeed(150000)))
        }

        "with the default config, a pooled connection that carried a large body accepts the next response" in {
            val sizes = Chunk(4 * 1024 * 1024, 500000, 500000, 500000)
            withCountingPeer { n =>
                Maybe.fromOption(sizes.lift(n - 1)).map { size =>
                    val body = "b" * size
                    s"HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
                }
            } { (port, _, _) =>
                HttpClient.init().map { client =>
                    HttpClient.let(client)(Kyo.foreach(sizes)(_ => getText(port).map(_.map(_.length))))
                }
            }.map(results => assertValue(results, sizes.map(Result.succeed(_))))
        }
    }

    "a streamed body cut short" - {

        "a 304 declaring a Content-Length has no body: the streamed body is empty and complete without waiting for it" in {
            withPeer { conn =>
                onRequest(conn)(discard(conn.outbound.offer(bytes("HTTP/1.1 304 Not Modified\r\nContent-Length: 10\r\n\r\n"))))
            } { port =>
                val route   = HttpRoute.getRaw("s").response(_.bodyStream)
                val request = HttpRequest.getRaw(HttpUrl.parse(s"http://127.0.0.1:$port/s").getOrThrow)
                Abort.run[HttpException](HttpClient.use(_.sendWith(route, request) { res =>
                    res.fields.body.run.map(chunks => (res.status.code, chunks.foldLeft(0)(_ + _.size)))
                })).map(outcome => assertValue(outcome, Result.succeed((304, 0))))
            }
        }

        "a Content-Length body the peer cuts short fails after the bytes that arrived" in {
            withPeer(conn => onRequest(conn)(replyAndClose(conn, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nhello")))(port =>
                streamed(port)
            ).map(outcome => assertValue(outcome, ("hello", closedInBody)))
        }

        "a chunked body with no terminal chunk fails after the bytes that arrived" in {
            withPeer(conn => onRequest(conn)(replyAndClose(conn, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n")))(
                port => streamed(port)
            ).map(outcome => assertValue(outcome, ("hello", closedInBody)))
        }

        "a chunked body with broken framing fails with HttpMalformedBodyException after the bytes that arrived" in {
            withPeer(conn => onRequest(conn)(replyAndClose(conn, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhelloX")))(
                port => streamed(port)
            ).map(outcome => assertValue(outcome, ("hello", Result.fail(HttpMalformedBodyException("missing CRLF after chunk data")))))
        }

        // The peer never closes, so the close that ends the stream is the client's own: the body stopped where the client cut it.
        "a close-framed body the client closes its own connection under fails after the bytes that arrived" in {
            withPeer(conn => onRequest(conn)(discard(conn.outbound.offer(bytes("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nhello"))))) {
                port =>
                    HttpClient.init().map { client =>
                        AtomicRef.init("").map { received =>
                            HttpClient.let(client) {
                                Abort.run[HttpException](
                                    HttpClient.getStreamBytes(s"http://127.0.0.1:$port/s").foreach { span =>
                                        received.updateAndGet(_ + new String(span.toArrayUnsafe, StandardCharsets.US_ASCII))
                                            .andThen(client.closeNow)
                                    }
                                )
                            }.map(result => received.get.map(text => (text, result)))
                        }
                    }
            }.map(outcome => assertValue(outcome, ("hello", closedInBody)))
        }

        "a close-framed body is read to the close, across reads" in {
            // The peer writes the rest only once the consumer holds the first bytes, so the two parts arrive in different reads.
            val firstConsumed = Promise.Unsafe.init[Int, Any]()
            withPeer { conn =>
                onRequest(conn) {
                    discard(conn.outbound.offer(bytes("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nhello")))
                    firstConsumed.asInstanceOf[IOPromise[Nothing, Int]].onComplete(_ => replyAndClose(conn, "world"))
                }
            } { port =>
                AtomicRef.init("").map { received =>
                    Abort.run[HttpException](
                        HttpClient.getStreamBytes(s"http://127.0.0.1:$port/s").foreach { span =>
                            received.updateAndGet(_ + new String(span.toArrayUnsafe, StandardCharsets.US_ASCII))
                                .andThen(Sync.Unsafe.defer(firstConsumed.completeDiscard(Result.succeed(1))))
                        }
                    ).map(result => received.get.map(text => (text, result)))
                }
            }.map(outcome => assertValue(outcome, ("helloworld", Result.unit)))
        }

    }

    "a streamed response ends where its head says (RFC 9112 section 6.3)" - {

        val good          = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ngood"
        val goodThenClose = "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 4\r\n\r\ngood"

        // The client is the test's own so the pool it observes is empty at the start; `accepts` counts the connections it opened.
        def streamedThenText(responses: String*)(using Frame) =
            withCountingPeer(n => Present(if n <= responses.size then responses(n - 1) else good)) { (port, requests, accepts) =>
                HttpClient.init().map { client =>
                    HttpClient.let(client) {
                        streamed(port).map(r1 => getText(port).map(r2 => (r1, r2, requests.get(), accepts.get())))
                    }
                }
            }

        "a Content-Length: 0 response is empty at once and its connection serves the next request" in {
            streamedThenText("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n").map(outcome =>
                assertValue(outcome, (("", Result.unit), Result.succeed("good"), 2, 1))
            )
        }

        // A redirect on a streaming route is followed without the caller ever seeing its body, so the client has to consume that body
        // itself: a connection whose reuse decision is never made is neither released nor closed. The connection is held while the
        // redirect is followed, so the target answers on a second one, which it closes; the request after the chain is then served by
        // the first connection only if the redirect released it.
        // The body is chunked and longer than what the client's streaming decoder reads ahead of a consumer, so a client that streams
        // it never reaches its end on its own.
        "a redirect with a body releases its connection once followed" in {
            val redirect = "HTTP/1.1 301 Moved Permanently\r\nLocation: /s\r\nTransfer-Encoding: chunked\r\n\r\n" + "1\r\nm\r\n" * 8 +
                "0\r\n\r\n"
            streamedThenText(redirect, goodThenClose).map(outcome =>
                assertValue(outcome, (("good", Result.unit), Result.succeed("good"), 3, 2))
            )
        }

        "a redirect with Content-Length: 0 releases its connection once followed" in {
            streamedThenText("HTTP/1.1 301 Moved Permanently\r\nLocation: /s\r\nContent-Length: 0\r\n\r\n", goodThenClose).map(outcome =>
                assertValue(outcome, (("good", Result.unit), Result.succeed("good"), 3, 2))
            )
        }
    }

    "bytes that belong to no request are never read as a response (RFC 9112 section 6.3)" - {

        val unsolicited = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nevil"
        val good        = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ngood"

        // The peer writes the first response and the unsolicited bytes in one offer, and these cases rely on the client receiving that
        // write in one read, so the extra bytes are in the parser's buffer when the first response completes. Loopback delivers a write
        // this small in one read on every transport, but nothing guarantees it; extra bytes that arrive in a later read are covered at
        // unit level, where the reads are the test's choice.
        def secondRequestAfter(first: String, method: HttpMethod)(using Frame) =
            withCountingPeer(n => Present(if n == 1 then first + unsolicited else good)) { (port, requests, accepts) =>
                HttpClient.init().map { client =>
                    HttpClient.let(client) {
                        exchange(port, method).map { r1 =>
                            exchange(port, HttpMethod.GET).map(r2 => (r1, r2, requests.get(), accepts.get()))
                        }
                    }
                }
            }

        "after a Content-Length body" in {
            secondRequestAfter("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello", HttpMethod.GET).map(outcome =>
                assertValue(outcome, (Result.succeed("200 'hello'"), Result.succeed("200 'good'"), 2, 2))
            )
        }

        "after a Content-Length: 0 response" in {
            secondRequestAfter("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", HttpMethod.GET).map(outcome =>
                assertValue(outcome, (Result.succeed("200 ''"), Result.succeed("200 'good'"), 2, 2))
            )
        }

        "after a 204" in {
            secondRequestAfter("HTTP/1.1 204 No Content\r\n\r\n", HttpMethod.GET).map(outcome =>
                assertValue(outcome, (Result.succeed("204 ''"), Result.succeed("200 'good'"), 2, 2))
            )
        }

        "after a 304" in {
            secondRequestAfter("HTTP/1.1 304 Not Modified\r\n\r\n", HttpMethod.GET).map(outcome =>
                assertValue(outcome, (Result.succeed("304 ''"), Result.succeed("200 'good'"), 2, 2))
            )
        }

        "after a response to HEAD that declares a Content-Length" in {
            secondRequestAfter("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n", HttpMethod.HEAD).map(outcome =>
                assertValue(outcome, (Result.succeed("200 ''"), Result.succeed("200 'good'"), 2, 2))
            )
        }

        // The controls: a body-less response that declares a Content-Length, followed by nothing, keeps its connection.
        def secondRequestAfterExactly(first: String, method: HttpMethod)(using Frame) =
            withCountingPeer(n => Present(if n == 1 then first else good)) { (port, requests, accepts) =>
                HttpClient.init().map { client =>
                    HttpClient.let(client) {
                        exchange(port, method).map { r1 =>
                            exchange(port, HttpMethod.GET).map(r2 => (r1, r2, requests.get(), accepts.get()))
                        }
                    }
                }
            }

        "a body-less 304 declaring a Content-Length is followed by the next request on the same connection" in {
            secondRequestAfterExactly("HTTP/1.1 304 Not Modified\r\nContent-Length: 5\r\n\r\n", HttpMethod.GET).map(outcome =>
                assertValue(outcome, (Result.succeed("304 ''"), Result.succeed("200 'good'"), 2, 1))
            )
        }

        "a body-less response to HEAD declaring a Content-Length is followed by the next request on the same connection" in {
            secondRequestAfterExactly("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n", HttpMethod.HEAD).map(outcome =>
                assertValue(outcome, (Result.succeed("200 ''"), Result.succeed("200 'good'"), 2, 1))
            )
        }

        "a complete response followed by nothing keeps its connection" in {
            withCountingPeer(_ => Present(good)) { (port, requests, accepts) =>
                HttpClient.init().map { client =>
                    HttpClient.let(client) {
                        getText(port).map(r1 => getText(port).map(r2 => (r1, r2, requests.get(), accepts.get())))
                    }
                }
            }.map(outcome => assertValue(outcome, (Result.succeed("good"), Result.succeed("good"), 2, 1)))
        }

        // The cases below choose the reads: the first exchange runs on an in-memory connection, the peer's offers are the client's
        // reads, and a second request is then sent through the pool. Returned: the first body, the second body, and how many
        // connections the client opened.
        val plainRoute  = HttpRoute.getRaw("plain").response(_.bodyText)
        val streamRoute = HttpRoute.getRaw("stream").response(_.bodyStream)
        val lengthHead  = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n"
        val chunkedHead = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        val chunkedBody = "a\r\nfirst-body\r\n0\r\n\r\n"
        val second      = Seq("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n", "second-body")

        def inMemory(streaming: Boolean, reads: Seq[String], whileCheckedOut: String, whilePooled: String)(using
            Frame
        ): (String, String, Int) < (Async & Abort[Any] & Scope) =
            val (client1, server1)                                           = TransportConnection.inMemoryPair()
            val (client2, server2)                                           = TransportConnection.inMemoryPair()
            val transport                                                    = new TestChannelTransport(Seq(client1, client2))
            val backend                                                      = HttpClientBackend.init(transport, 2, 60.seconds)
            val config                                                       = HttpClientConfig(timeout = Duration.Infinity)
            def offer(server: kyo.net.Connection, read: String): Unit < Sync =
                Sync.Unsafe.defer(if read.nonEmpty then discard(server.outbound.offer(bytes(read))))
            def serve(server: kyo.net.Connection, reads: Seq[String]): Unit < (Async & Abort[Closed]) =
                server.inbound.safe.take.andThen(Kyo.foreachDiscard(reads)(offer(server, _)))
            val first: String < (Async & Abort[HttpException]) =
                if streaming then
                    backend.sendWithConfig(streamRoute, HttpRequest.getRaw(HttpUrl.fromUri("/stream")), config)(res =>
                        res.fields.body.run.map(spans => spans.map(s => new String(s.toArrayUnsafe, StandardCharsets.US_ASCII)).mkString)
                            .map(body => offer(server1, whileCheckedOut).andThen(body))
                    )
                else
                    backend.sendWithConfig(plainRoute, HttpRequest.getRaw(HttpUrl.fromUri("/plain")), config)(res =>
                        offer(server1, whileCheckedOut).andThen(res.fields.body)
                    )
            Fiber.init(first).map { firstFiber =>
                serve(server1, reads).andThen(firstFiber.get).map { firstBody =>
                    offer(server1, whilePooled).andThen {
                        Fiber.init(serve(server1, second)).andThen(Fiber.init(serve(server2, second))).andThen {
                            backend.sendWithConfig(plainRoute, HttpRequest.getRaw(HttpUrl.fromUri("/plain")), config)(_.fields.body)
                                .map(secondBody => (firstBody, secondBody, transport.connectCount))
                        }
                    }
                }
            }
        end inMemory

        "extra bytes in their own read, before the connection is returned to the pool, make the pool discard it" in {
            inMemory(streaming = false, Seq(lengthHead, "first-body"), whileCheckedOut = unsolicited, whilePooled = "").map(outcome =>
                assertValue(outcome, ("first-body", "second-body", 2))
            )
        }

        "extra bytes in their own read, while the connection sits in the pool, make the pool discard it" in {
            inMemory(streaming = false, Seq(lengthHead, "first-body"), whileCheckedOut = "", whilePooled = unsolicited).map(outcome =>
                assertValue(outcome, ("first-body", "second-body", 2))
            )
        }

        "extra bytes in the read that ends a Content-Length body are dropped with the connection" in {
            inMemory(streaming = false, Seq(lengthHead, "first-body" + unsolicited), whileCheckedOut = "", whilePooled = "").map(outcome =>
                assertValue(outcome, ("first-body", "second-body", 2))
            )
        }

        "extra bytes in the read that ends a streamed Content-Length body are dropped with the connection" in {
            inMemory(streaming = true, Seq(lengthHead, "first-body" + unsolicited), whileCheckedOut = "", whilePooled = "").map(outcome =>
                assertValue(outcome, ("first-body", "second-body", 2))
            )
        }

        "extra bytes after the terminal chunk are dropped with the connection" in {
            inMemory(streaming = false, Seq(chunkedHead, chunkedBody + unsolicited), whileCheckedOut = "", whilePooled = "").map(outcome =>
                assertValue(outcome, ("first-body", "second-body", 2))
            )
        }

        "extra bytes after the terminal chunk of a streamed body are dropped with the connection" in {
            inMemory(streaming = true, Seq(chunkedHead, chunkedBody + unsolicited), whileCheckedOut = "", whilePooled = "").map(outcome =>
                assertValue(outcome, ("first-body", "second-body", 2))
            )
        }

        "a connection with nothing after its response is reused" in {
            inMemory(streaming = false, Seq(lengthHead, "first-body"), whileCheckedOut = "", whilePooled = "").map(outcome =>
                assertValue(outcome, ("first-body", "second-body", 1))
            )
        }
    }

    "a close-framed body over TLS is complete only after a close_notify (RFC 9112 section 9.8)" - {

        val closeFramed = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nhello"

        /** Serves one close-framed response on an in-memory connection that reports `reason` as its close reason, over a transport that
          * does or does not report close reasons and a connection the client opened for TLS or not, and returns how the buffered read and
          * the streamed read ended: the buffered result, the streamed bytes that arrived, and the streamed result.
          */
        def closeFramedOutcome(tls: Boolean, reason: kyo.net.Connection.Status, reportsReason: Boolean = true)(using
            Frame
        ): (Result[HttpException, String], String, Result[HttpException, Unit]) < (Async & Abort[Any] & Scope) =
            val backend = HttpClientBackend.init(new TestChannelTransport(Seq.empty, tlsCloseReason = reportsReason), 2, 60.seconds)
            def once(streaming: Boolean): (String, Result[HttpException, Unit]) < (Async & Abort[Any] & Scope) =
                val (client, server) = TransportConnection.inMemoryPair()
                val transport        = new kyo.net.TlsCloseConnection(client, reason)
                val http1            = Http1ClientConnection.init(transport.inbound, transport.outbound)
                val conn             = new HttpConnection(transport, http1, "test", if tls then 443 else 80, tls, "test")
                // The peer's outbound is the client's inbound: closing it once drained is the peer's close after the body.
                val serve =
                    server.inbound.safe.take.andThen(Sync.Unsafe.defer(discard(server.outbound.offer(bytes(closeFramed)))))
                        .andThen(server.outbound.safe.closeAwaitEmpty)
                AtomicRef.init("").map { received =>
                    Fiber.init(serve).andThen {
                        val exchange =
                            if streaming then
                                val route = HttpRoute.getRaw("s").response(_.bodyStream)
                                val req   = HttpRequest.getRaw(HttpUrl.fromUri("/s"))
                                backend.sendStreaming(conn, route, req, 1 << 20, Absent, Absent).safe.get.map(
                                    _.fields.body.foreach(span =>
                                        received.updateAndGet(_ + new String(span.toArrayUnsafe, StandardCharsets.US_ASCII)).unit
                                    )
                                )
                            else
                                val route = HttpRoute.getRaw("b").response(_.bodyText)
                                val req   = HttpRequest.getRaw(HttpUrl.fromUri("/b"))
                                backend.sendBuffered(conn, route, req, 1 << 20, Absent).safe.get.map(res => received.set(res.fields.body))
                        Abort.run[HttpException](exchange).map(result => received.get.map(text => (text, result)))
                    }
                }
            end once
            once(streaming = false).map { (bufferedText, buffered) =>
                once(streaming = true).map { (streamedText, streamed) =>
                    (buffered.map(_ => bufferedText), streamedText, streamed)
                }
            }
        end closeFramedOutcome

        "a TLS close without close_notify fails the body, buffered and streamed, after the streamed bytes that arrived" in {
            closeFramedOutcome(tls = true, reason = Status.Truncated).map(outcome => assertValue(outcome, (closedTls, "hello", closedTls)))
        }

        // A reset or a fatal record ends the connection with no closure alert either, and the transport reports it as neither close.
        "a TLS end the transport reports as neither close fails the body, buffered and streamed" in {
            closeFramedOutcome(tls = true, reason = Status.Active).map(outcome => assertValue(outcome, (closedTls, "hello", closedTls)))
        }

        "a TLS close after close_notify ends the body" in {
            closeFramedOutcome(tls = true, reason = Status.CleanClose).map(outcome =>
                assertValue(outcome, (Result.succeed("hello"), "hello", Result.unit))
            )
        }

        // The Node transport delegates TLS to Node and observes no close reason, so the close is all it has to end the body on.
        "on a transport that reports no close reason, a TLS close ends the body" in {
            closeFramedOutcome(tls = true, reason = Status.Active, reportsReason = false).map(outcome =>
                assertValue(outcome, (Result.succeed("hello"), "hello", Result.unit))
            )
        }

        "a plaintext close ends the body whatever close reason the transport reports" in {
            Kyo.foreach(Chunk(Status.Truncated, Status.Active)) { reason =>
                closeFramedOutcome(tls = false, reason = reason)
            }.map(outcomes => assertValue(outcomes, Chunk.fill(2)((Result.succeed("hello"), "hello", Result.unit))))
        }
    }

    "interim responses (RFC 9110 section 15.2)" - {

        "a 103 before the final response in the same read gives the caller the final response" in {
            val response =
                "HTTP/1.1 103 Early Hints\r\nLink: </s.css>; rel=preload\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
            withPeer(conn => onRequest(conn)(discard(conn.outbound.offer(bytes(response)))))(port => getText(port)).map(result =>
                assertValue(result, Result.succeed("hello"))
            )
        }

        "a 100 Continue before the final response gives the caller the final response" in {
            val response = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"
            withPeer(conn => onRequest(conn)(discard(conn.outbound.offer(bytes(response)))))(port => getText(port)).map(result =>
                assertValue(result, Result.succeed("hello"))
            )
        }
    }

end HttpClientBackendResponseFailureTest
