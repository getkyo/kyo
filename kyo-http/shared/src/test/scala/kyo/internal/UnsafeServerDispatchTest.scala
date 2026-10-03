package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.server.*
import kyo.internal.transport.*
import kyo.internal.util.*
import kyo.internal.websocket.*

class UnsafeServerDispatchTest extends kyo.BaseHttpTest:

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    def buildRouter(handlers: Seq[HttpHandler[?, ?, ?]], cors: Maybe[HttpServerConfig.Cors])(using Frame): HttpRouter =
        HttpRouter.init(handlers, cors).getOrThrow

    /** Helper: collect exactly one complete HTTP response from the outbound channel. Reads headers until CRLFCRLF, extracts Content-Length,
      * then reads exactly that many body bytes. Stops after one complete response, leaving subsequent responses in the channel.
      */
    private def collectResponse(outbound: Channel.Unsafe[Span[Byte]])(using Frame): String < (Async & Abort[Closed]) =
        val sb = new StringBuilder

        def readMore(): String < (Async & Abort[Closed]) =
            outbound.safe.take.map { span =>
                sb.append(new String(span.toArray, StandardCharsets.US_ASCII))
                val s = sb.toString
                // Check if we have complete headers
                val headerEnd = s.indexOf("\r\n\r\n")
                if headerEnd < 0 then
                    readMore() // need more data for headers
                else
                    // Parse Content-Length from headers
                    val headers       = s.substring(0, headerEnd)
                    val clMatch       = "Content-Length: (\\d+)".r.findFirstMatchIn(headers)
                    val contentLength = clMatch.map(_.group(1).toInt).getOrElse(0)
                    val bodyStart     = headerEnd + 4
                    val bodyReceived  = s.length - bodyStart
                    if bodyReceived >= contentLength then
                        s.substring(0, bodyStart + contentLength) // complete response
                    else
                        readMore() // need more body bytes
                    end if
                end if
            }

        readMore()
    end collectResponse

    /** Send a raw HTTP request string to the inbound channel. */
    private def sendRequest(inbound: Channel.Unsafe[Span[Byte]], request: String): Unit =
        discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

    /** A close hook for `serve` that closes the inbound channel, the close `serve` performs without a hook, and records it, so a leaf awaits
      * the close as an event instead of polling the channel for it.
      */
    final private class CloseProbe(inbound: Channel.Unsafe[Span[Byte]]):
        private val done = Promise.Unsafe.init[Unit, Any]()

        val hook: Maybe[() => Unit] = Present { () =>
            discard(inbound.close())
            done.completeUnitDiscard()
        }

        /** Completes once the server has closed the connection, with whether the inbound channel is closed. */
        def closed(using Frame): Boolean < Async = done.safe.get.andThen(inbound.closed())
    end CloseProbe

    /** Waits until the keep-alive idle timer for the next idle period is armed.
      *
      * `restartParserKeepAlive` arms the timer then restarts the parser, so a parser take on inbound is proof the
      * timer exists. Tests need this before advancing virtual time against the deadline: the already-collected
      * response was written while the handler ran and says nothing about the arm.
      */
    private def awaitIdleTimerArmed(inbound: Channel.Unsafe[Span[Byte]])(using Frame): Boolean < Async =
        pollUntil(inbound.pendingTakes().contains(1))

    // A leaf on the wall clock would otherwise leave a live 60 s idle timer armed past its end; a leaf about the timer sets its own.
    private val defaultConfig = HttpServerConfig.default.idleTimeout(Duration.Infinity)

    "UnsafeServerDispatch" - {

        "dispatch GET request returns 200" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains("world"), s"Expected body 'world', got: $response")
            }
        }

        // Nothing marks the connection for a decode error on a request with no body owed, so the 400 does not announce a close and the
        // next request on the connection is answered.
        "a decode error on a keep-alive request is answered 400 without Connection: close, and the connection serves the next request" in {
            val paged  = HttpRoute.getRaw("paged").request(_.query[Int]("page")).response(_.bodyText).handler(_ => HttpResponse.ok("paged"))
            val hello  = HttpHandler.getText("hello")(_ => "world")
            val router = buildRouter(Seq(paged, hello), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            sendRequest(inbound, "GET /paged?page=abc HTTP/1.1\r\nHost: h\r\n\r\n")
            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig.idleTimeout(Duration.Infinity))
            collectResponse(outbound).map { refused =>
                assert(refused.startsWith("HTTP/1.1 400 Bad Request"), s"observed: $refused")
                assert(!refused.toLowerCase.contains("connection: close"), s"a keep-alive connection stays open, observed: $refused")
                sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: h\r\n\r\n")
                collectResponse(outbound).map { next =>
                    assert(next.startsWith("HTTP/1.1 200 OK") && next.endsWith("world"), s"observed: $next")
                    assert(!inbound.closed())
                }
            }
        }

        // A graceful close discards no request the server already received: the one pipelined behind the request in flight is served,
        // and the connection ends once its parser would wait on the peer.
        "a graceful close serves a request already pipelined behind the one in flight, then closes" in {
            Latch.init(1).map { proceed =>
                val slow     = HttpHandler.getText("slow")(_ => proceed.await.andThen("slow done"))
                val hello    = HttpHandler.getText("hello")(_ => "world")
                val router   = buildRouter(Seq(slow, hello), Absent)
                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)
                val flag     = AtomicBoolean.Unsafe.init(false)
                val drain    = new UnsafeServerDispatch.Drain(flag)
                val probe    = CloseProbe(inbound)
                sendRequest(inbound, "GET /slow HTTP/1.1\r\nHost: h\r\n\r\nGET /hello HTTP/1.1\r\nHost: h\r\n\r\n")
                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook, drain = Present(drain))
                flag.set(true)
                drain.endIfIdle()
                proceed.release.andThen(probe.closed).map { closed =>
                    assert(closed, "the connection closes once nothing more is in hand")
                    outbound.safe.drain.map { spans =>
                        val written = spans.map(span => new String(span.toArray, StandardCharsets.US_ASCII)).mkString
                        assert(written.split("HTTP/1.1 200 OK").length == 3, s"both requests are answered, observed: $written")
                        assert(written.contains("slow done") && written.endsWith("world"), s"observed: $written")
                    }
                }
            }
        }

        "a 100 Continue the outbound channel cannot take at once is written once it has room, not dropped" in {
            val route    = HttpRoute.postRaw("upload").request(_.bodyText).response(_.bodyText)
            val handler  = route.handler(req => HttpResponse.ok("got " + req.fields.body))
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](1)
            discard(outbound.offer(Span.fromUnsafe("earlier".getBytes(StandardCharsets.US_ASCII))))
            sendRequest(inbound, "POST /upload HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 3\r\n\r\n")
            UnsafeServerDispatch.serve(buildRouter(Seq(handler), Absent), inbound, outbound, defaultConfig)
            pollUntil(inbound.pendingTakes().contains(1)).map { waiting =>
                assert(waiting, "the body reader waits for the body the client holds back")
                outbound.safe.take.map { earlier =>
                    assert(new String(earlier.toArray, StandardCharsets.US_ASCII) == "earlier")
                    pollUntil(outbound.size().contains(1)).map { interim =>
                        assert(interim, "the 100 Continue goes out once the channel has room")
                        outbound.safe.take.map { continue =>
                            assert(new String(continue.toArray, StandardCharsets.US_ASCII) == "HTTP/1.1 100 Continue\r\n\r\n")
                        }
                    }
                }
            }
        }

        "dispatch returns 404 for unknown path" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /missing HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 404 Not Found"), s"Expected 404, got: $response")
            }
        }

        "dispatch returns 405 for wrong method" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "POST /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 405 Method Not Allowed"), s"Expected 405, got: $response")
                assert(response.contains("Allow:"), s"Expected Allow header, got: $response")
            }
        }

        "dispatch with path captures" in {
            import HttpPath./
            val route   = HttpRoute.getRaw("users" / HttpPath.Capture[String]("id")).response(_.bodyText)
            val handler = route.handler { req =>
                val userId = req.fields.id
                HttpResponse.ok(userId)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /users/42 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains("42"), s"Expected body containing '42', got: $response")
            }
        }

        "dispatch POST with body" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                val body = req.fields.body
                HttpResponse.ok(body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val body    = "Hello World"
            val request = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains("Hello World"), s"Expected body 'Hello World', got: $response")
            }
        }

        "dispatch multiple requests (keep-alive)" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Send two pipelined requests (keep-alive is default in HTTP/1.1)
            val request1 = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
            val request2 = "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request1.getBytes(StandardCharsets.US_ASCII))))
            discard(inbound.offer(Span.fromUnsafe(request2.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response1 =>
                assert(response1.contains("HTTP/1.1 200 OK"), s"First response expected 200, got: $response1")
                assert(response1.contains("world"), s"First response expected 'world', got: $response1")
                collectResponse(outbound).map { response2 =>
                    assert(response2.contains("HTTP/1.1 200 OK"), s"Second response expected 200, got: $response2")
                    assert(response2.contains("world"), s"Second response expected 'world', got: $response2")
                }
            }
        }

        "dispatch Connection: close stops after response" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))
            // Pipelined follow-up as its own span, so the parser cannot have buffered it with the first request.
            // Consumed only if the parser restarts, which Connection: close forbids.
            val followUp = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(followUp.getBytes(StandardCharsets.US_ASCII))))

            val probe = CloseProbe(inbound)
            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                // Connection: close ends the connection with this response: the parser never restarts, so the follow-up span
                // goes unread and unanswered, and the connection is closed under it.
                probe.closed.map { closed =>
                    assert(closed, "the connection must be closed after the final response to a Connection: close request")
                    outbound.poll() match
                        case Result.Success(Present(span)) =>
                            fail(s"Expected no more data after Connection: close, but got: ${new String(span.toArray)}")
                        case _ =>
                            succeed
                    end match
                }
            }
        }

        "dispatch error in handler returns 500" in {
            val handler = HttpHandler.getRaw[Nothing]("fail") { _ =>
                throw new RuntimeException("handler exploded")
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /fail HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("500"), s"Expected 500 status, got: $response")
            }
        }

        "body fits in header chunk" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // All 10 body bytes arrive with headers in one chunk
            val body    = "0123456789"
            val request = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains(body), s"Expected body '$body', got: $response")
            }
        }

        "body split across two chunks" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Content-Length=1000, 200 bytes with headers, 800 in next chunk
            val bodyPart1 = "A" * 200
            val bodyPart2 = "B" * 800
            val fullBody  = bodyPart1 + bodyPart2
            val headers   = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${fullBody.length}\r\n\r\n"
            // First chunk: headers + first 200 bytes of body
            discard(inbound.offer(Span.fromUnsafe((headers + bodyPart1).getBytes(StandardCharsets.US_ASCII))))
            // Second chunk: remaining 800 bytes
            discard(inbound.offer(Span.fromUnsafe(bodyPart2.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(
                    response.contains(fullBody),
                    s"Expected full body of length ${fullBody.length}, got response of length ${response.length}"
                )
            }
        }

        "body split across many chunks" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](256)
            val outbound = Channel.Unsafe.init[Span[Byte]](256)

            // Content-Length=5000, body arrives in 50-byte increments
            val chunkSize = 50
            val fullBody  = "X" * 5000
            val headers   = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${fullBody.length}\r\n\r\n"

            // First chunk: just headers, no body
            discard(inbound.offer(Span.fromUnsafe(headers.getBytes(StandardCharsets.US_ASCII))))
            // Send body in 100 chunks of 50 bytes each
            var offset = 0
            while offset < fullBody.length do
                val end   = math.min(offset + chunkSize, fullBody.length)
                val chunk = fullBody.substring(offset, end)
                discard(inbound.offer(Span.fromUnsafe(chunk.getBytes(StandardCharsets.US_ASCII))))
                offset = end
            end while

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains(fullBody), s"Expected full body of length ${fullBody.length}")
            }
        }

        "body arrives after delay" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Headers arrive first with no body
            val body    = "delayed body data"
            val headers = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(headers.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            // The body reader takes on inbound when out of bytes, so a pending take signals readBody has parked.
            // Sending the body only then exercises the park-and-resume path, not hoping a fixed delay sufficed.
            pollUntil(inbound.pendingTakes().contains(1)).map { parked =>
                assert(parked, "readBody must park on inbound while the body is outstanding")
                discard(inbound.offer(Span.fromUnsafe(body.getBytes(StandardCharsets.US_ASCII))))
                collectResponse(outbound).map { response =>
                    assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                    assert(response.contains(body), s"Expected body '$body', got: $response")
                }
            }
        }

        "exact Content-Length match" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Total bytes read exactly equals Content-Length, no leftover
            val body    = "exact match body"
            val headers = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n"
            // Headers in first chunk, body in second chunk — exactly Content-Length bytes
            discard(inbound.offer(Span.fromUnsafe(headers.getBytes(StandardCharsets.US_ASCII))))
            discard(inbound.offer(Span.fromUnsafe(body.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains(body), s"Expected body '$body', got: $response")
            }
        }

        "body with leftover for next request" in {
            val postRoute   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val postHandler = postRoute.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val getHandler = HttpHandler.getText("echo")(_ => "get-ok")
            val router     = buildRouter(Seq(postHandler, getHandler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // First request: Content-Length=5 but 5 body bytes + full second request arrive together
            val body1    = "ABCDE"
            val request2 = "GET /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            val headers1 = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body1.length}\r\n\r\n"
            // Send headers + body + second request all in one chunk
            val combined = headers1 + body1 + request2
            discard(inbound.offer(Span.fromUnsafe(combined.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            // First response should echo the 5-byte body
            collectResponse(outbound).map { response1 =>
                assert(response1.contains("HTTP/1.1 200 OK"), s"First response expected 200, got: $response1")
                assert(response1.contains(body1), s"Expected body '$body1' in first response, got: $response1")
                // Second response should be processed from leftover bytes
                collectResponse(outbound).map { response2 =>
                    assert(response2.contains("HTTP/1.1 200 OK"), s"Second response expected 200, got: $response2")
                    assert(response2.contains("get-ok"), s"Second response should contain 'get-ok', got: $response2")
                }
            }
        }

        "zero Content-Length" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                val body = req.fields.body
                // Empty body should produce empty string
                HttpResponse.ok(s"len=${body.length}")
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains("len=0"), s"Expected empty body (len=0), got: $response")
            }
        }

        "very large body" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(s"size=${req.fields.body.length}")
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](1024)
            val outbound = Channel.Unsafe.init[Span[Byte]](1024)

            // 1MB body split into 4KB chunks — tests accumulation without stack overflow
            val totalSize = 1024 * 1024
            val chunkSize = 4096
            val headers   = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: $totalSize\r\n\r\n"

            discard(inbound.offer(Span.fromUnsafe(headers.getBytes(StandardCharsets.US_ASCII))))
            var sent = 0
            while sent < totalSize do
                val thisChunk = math.min(chunkSize, totalSize - sent)
                val data      = new Array[Byte](thisChunk)
                java.util.Arrays.fill(data, 'Z'.toByte)
                discard(inbound.offer(Span.fromUnsafe(data)))
                sent += thisChunk
            end while

            val largeConfig = defaultConfig.maxContentLength(totalSize + 1)
            UnsafeServerDispatch.serve(router, inbound, outbound, largeConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains(s"size=$totalSize"), s"Expected size=$totalSize, got: $response")
            }
        }

        "inbound channel closed mid-body" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Content-Length=100 but only 30 bytes arrive, then channel closes
            val partialBody = "X" * 30
            val headers     = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 100\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe((headers + partialBody).getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            // Drop the connection exactly when the body reader is parked on the 70 bytes that never arrive: the
            // pending take proves the reader got that far, so the drop lands on the intended path, not a delay's guess.
            pollUntil(inbound.pendingTakes().contains(1)).map { parked =>
                assert(parked, "readBody must park on inbound while the rest of the body is outstanding")
                discard(inbound.close())
                // readBody aborts Closed, so nothing may be written for this request. The poll returns as soon as
                // anything is written (violation surfaces at once), otherwise ends with an empty outbound.
                pollUntil(!outbound.empty().contains(true), maxPolls = 100).map { _ =>
                    // Drain whatever is in the outbound channel
                    val sb   = new StringBuilder
                    var done = false
                    while !done do
                        outbound.poll() match
                            case Result.Success(Present(span)) =>
                                sb.append(new String(span.toArray, StandardCharsets.US_ASCII))
                            case _ =>
                                done = true
                    end while
                    val response = sb.toString
                    // Should NOT contain a successful echo of truncated body;
                    // an empty response (connection dropped) or an error response are both acceptable
                    assert(
                        response.isEmpty || !response.contains("200 OK") || !response.contains(partialBody),
                        s"Should not have 200 OK with truncated body, got: $response"
                    )
                }
            }
        }

        "Date header present on 200 response" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains("Date: "), s"Expected Date header, got: $response")
            }
        }

        "Date header present on error responses" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /missing HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 404 Not Found"), s"Expected 404, got: $response")
                assert(response.contains("Date: "), s"Expected Date header on error response, got: $response")
            }
        }

        "Date header cached per second" in {
            val date1 = UnsafeServerDispatch.currentDate()
            val date2 = UnsafeServerDispatch.currentDate()
            // Two calls within the same second should return the exact same String reference
            assert(date1 eq date2, s"Expected cached (same reference) Date strings, got '$date1' and '$date2'")
        }

        "Date header format matches RFC 9110" in {
            val date = UnsafeServerDispatch.currentDate()
            // RFC 9110 date format: "Wed, 09 Jun 2021 10:18:14 GMT"
            // Pattern: 3-letter day, comma, space, 2-digit day, space, 3-letter month, space, 4-digit year, space, HH:MM:SS, space, GMT
            val rfc9110Pattern = """[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT""".r
            assert(rfc9110Pattern.findFirstIn(date).isDefined, s"Date '$date' does not match RFC 9110 format")
        }

        "Content-Length exceeds max returns 413" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Default maxContentLength is 65536, send Content-Length of 100000
            val request = "POST /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 100000\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 413 Payload Too Large"), s"Expected 413, got: $response")
            }
        }

        "Content-Length at limit accepted" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            // Use a small maxContentLength for the test
            val config   = defaultConfig.maxContentLength(10)
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val body    = "0123456789" // exactly 10 bytes
            val request = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, config)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK (body at limit), got: $response")
                assert(response.contains(body), s"Expected body '$body', got: $response")
            }
        }

        "Content-Length below limit accepted" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val config   = defaultConfig.maxContentLength(100)
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val body    = "small"
            val request = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, config)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK (body below limit), got: $response")
                assert(response.contains(body), s"Expected body '$body', got: $response")
            }
        }

        // A 413 declines an over-limit body it never reads. Reusing the connection would let those unconsumed body
        // bytes be parsed as the next request (the unconsumed-body smuggling class, Undertow CVE-2020-10719, RFC 9112
        // section 9.3), so the server answers Connection: close and tears the connection down rather than serve a
        // pipelined follow-up. request2's bytes must NOT be served.
        "413 response closes the connection instead of reusing it (RFC 9112 section 9.3)" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val config   = defaultConfig.maxContentLength(10)
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // First request: Content-Length exceeds limit (keep-alive is default in HTTP/1.1)
            val request1 = "POST /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n"
            // A pipelined GET that must NOT be served, because the connection is torn down after the 413.
            val request2 = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request1.getBytes(StandardCharsets.US_ASCII))))
            discard(inbound.offer(Span.fromUnsafe(request2.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, config)

            collectResponse(outbound).map { response1 =>
                assert(response1.contains("HTTP/1.1 413 Payload Too Large"), s"First response expected 413, got: $response1")
                assert(response1.contains("Connection: close"), s"413 must announce Connection: close, got: $response1")
                // Nothing more is written: the pipelined request2 was not served.
                val servedFollowUp = outbound.poll() match
                    case Result.Success(Present(_)) => true
                    case _                          => false
                assert(!servedFollowUp, "the pipelined request after a 413 must not be served")
            }
        }

        "Expect: 100-continue sends 100 before body read" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val body    = "continued body"
            val headers =
                s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\nExpect: 100-continue\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(headers.getBytes(StandardCharsets.US_ASCII))))
            // Body arrives after the headers
            discard(inbound.offer(Span.fromUnsafe(body.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            // First data from outbound should be the 100 Continue interim response
            outbound.safe.take.map { firstSpan =>
                val first = new String(firstSpan.toArray, StandardCharsets.US_ASCII)
                assert(first.contains("HTTP/1.1 100 Continue"), s"Expected 100 Continue, got: $first")
                // Then the final response
                collectResponse(outbound).map { response =>
                    assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                    assert(response.contains(body), s"Expected body '$body', got: $response")
                }
            }
        }

        "Expect: 100-continue with body too large sends 417" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val config   = defaultConfig.maxContentLength(10)
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request =
                "POST /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 100\r\nExpect: 100-continue\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, config)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 417 Expectation Failed"), s"Expected 417, got: $response")
                assert(!response.contains("100 Continue"), s"Should NOT have sent 100 Continue, got: $response")
            }
        }

        "no Expect header skips 100 response" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val body    = "no expect"
            val request = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(!response.contains("100 Continue"), s"Should NOT have 100 Continue without Expect header, got: $response")
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains(body), s"Expected body '$body', got: $response")
            }
        }

        "Content-Length together with Transfer-Encoding is refused, not framed" in {
            // Both Content-Length and Transfer-Encoding is the CL.TE request-smuggling shape (RFC 9112 section 6.1).
            // The parser refuses it rather than pick a framing, so the dispatch answers 400 Connection: close and
            // tears down: the body is never dechunked, routed, or handled, and the over-limit 413 is never reached.
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val served  = AtomicBoolean.Unsafe.init(false)
            val handler = route.handler { req =>
                discard(served.set(true))
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val idleTimeout = 200.millis
            val config      = defaultConfig.maxContentLength(10).idleTimeout(idleTimeout)
            val inbound     = Channel.Unsafe.init[Span[Byte]](16)
            val outbound    = Channel.Unsafe.init[Span[Byte]](16)

            val request =
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 100\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "5\r\nhello\r\n0\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            val probe = CloseProbe(inbound)
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)

                    collectResponse(outbound).map { response =>
                        assert(response.contains("HTTP/1.1 400 Bad Request"), s"Expected 400 for the CL+TE conflict, got: $response")
                        assert(
                            response.contains("Connection: close"),
                            s"the 400 must announce the close (RFC 9112 section 9.6), got: $response"
                        )
                        assert(
                            !response.contains("413"),
                            s"the request must be refused on framing, not on the Content-Length cap: $response"
                        )
                        assert(!served.get(), "a request with two candidate framings must never reach the handler")
                        // The body's framing is unknown, so whatever the peer still sends is read and discarded until it stops; then the
                        // connection is torn down.
                        pollUntil(inbound.pendingTakes().contains(1)).map { draining =>
                            assert(draining, "the rest of the request is drained before the connection ends")
                            tc.advance(idleTimeout).andThen {
                                probe.closed.map(closed => assert(closed, "the connection must be torn down after an unframeable request"))
                            }
                        }
                    }
                }
            }
        }

        "chunked body exceeding max returns 413" in {
            // A chunked body on a buffered route is dechunked bounded by maxContentLength; a body decoding to more
            // than the limit is answered 413, not buffered without limit (CWE-400, RFC 9112 section 6.1).
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val config   = defaultConfig.maxContentLength(10)
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Send a chunked request whose decoded body (15 bytes) exceeds maxContentLength (10).
            // Chunk format: hex-size\r\ndata\r\n ... 0\r\n\r\n
            val chunk1  = "a\r\n0123456789\r\n" // 10 bytes (at limit)
            val chunk2  = "5\r\nABCDE\r\n"      // 5 more bytes (over limit)
            val end     = "0\r\n\r\n"
            val request =
                s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n$chunk1$chunk2$end"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, config)

            collectResponse(outbound).map { response =>
                assert(
                    response.contains("HTTP/1.1 413 Payload Too Large"),
                    s"an over-limit chunked body on a buffered route must be 413'd, got: $response"
                )
            }
        }

        "request with Host header accepted" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /hello HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                assert(response.contains("world"), s"Expected body 'world', got: $response")
            }
        }

        "request without Host header returns 400" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Has Connection header but no Host header
            val request = "GET /hello HTTP/1.1\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 400 Bad Request"), s"Expected 400 Bad Request, got: $response")
            }
        }

        "request with empty Host header returns 400" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Host header present but empty value
            val request = "GET /hello HTTP/1.1\r\nHost: \r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 400 Bad Request"), s"Expected 400 Bad Request, got: $response")
            }
        }

        "multiple Host headers returns 400" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Two Host headers — RFC 9110 section 7.2 violation
            val request = "GET /hello HTTP/1.1\r\nHost: example.com\r\nHost: other.com\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 400 Bad Request"), s"Expected 400 Bad Request, got: $response")
            }
        }

        "Host header case-insensitive detection" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Use non-standard casing — parser should detect "host" case-insensitively
            val request = "GET /hello HTTP/1.1\r\nhost: example.com\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK (case-insensitive Host), got: $response")
                assert(response.contains("world"), s"Expected body 'world', got: $response")
            }
        }

        "400 response preserves keep-alive" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // First request: has a header but NOT Host (keep-alive is default in HTTP/1.1)
            val request1 = "GET /hello HTTP/1.1\r\nAccept: */*\r\n\r\n"
            // Second request: valid with Host and Connection: close
            val request2 = "GET /hello HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request1.getBytes(StandardCharsets.US_ASCII))))
            discard(inbound.offer(Span.fromUnsafe(request2.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response1 =>
                assert(response1.contains("HTTP/1.1 400 Bad Request"), s"First response expected 400, got: $response1")
                collectResponse(outbound).map { response2 =>
                    assert(response2.contains("HTTP/1.1 200 OK"), s"Second response expected 200, got: $response2")
                    assert(response2.contains("world"), s"Second response expected 'world', got: $response2")
                }
            }
        }

        // ==================== HttpWebSocket upgrade tests ====================

        /** Helper: build a minimal WS upgrade request for a given path. */
        def wsUpgradeRequest(path: String, key: String = "dGhlIHNhbXBsZSBub25jZQ=="): String =
            s"GET /$path HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"

        /** Helper: collect all bytes from outbound until we see the end of HTTP headers (\r\n\r\n). Handles Abort[Closed] internally --
          * throws if channel is closed before headers complete.
          */
        def collectWsUpgradeResponse(outbound: Channel.Unsafe[Span[Byte]])(using Frame): String < Async =
            val sb                                           = new StringBuilder
            def readMore(): String < (Async & Abort[Closed]) =
                outbound.safe.take.map { span =>
                    sb.append(new String(span.toArray, StandardCharsets.US_ASCII))
                    val s = sb.toString
                    if s.contains("\r\n\r\n") then s
                    else readMore()
                }
            Abort.run[Closed](readMore()).map {
                case Result.Success(s) => s
                case Result.Failure(e) => throw new RuntimeException(s"Channel closed before WS upgrade response complete: $e")
                case Result.Panic(t)   => throw t
            }
        end collectWsUpgradeResponse

        /** Helper: encode a WS text frame (unmasked, for simplicity -- server readFrame handles both). */
        def encodeClientTextFrame(text: String): Array[Byte] =
            val payload = text.getBytes(StandardCharsets.UTF_8)
            val maskKey = Array[Byte](0x12, 0x34, 0x56, 0x78)
            val masked  = new Array[Byte](payload.length)
            var i       = 0
            while i < payload.length do
                masked(i) = (payload(i) ^ maskKey(i % 4)).toByte
                i += 1
            end while
            // FIN=1, opcode=1 (text), MASK=1
            val b0 = (0x80 | 0x01).toByte
            val b1 = (0x80 | payload.length).toByte // masked + length (<126)
            Array[Byte](b0, b1) ++ maskKey ++ masked
        end encodeClientTextFrame

        /** Helper: encode a WS binary frame (masked). */
        def encodeClientBinaryFrame(data: Array[Byte]): Array[Byte] =
            val maskKey = Array[Byte](0xaa.toByte, 0xbb.toByte, 0xcc.toByte, 0xdd.toByte)
            val masked  = new Array[Byte](data.length)
            var i       = 0
            while i < data.length do
                masked(i) = (data(i) ^ maskKey(i % 4)).toByte
                i += 1
            end while
            // FIN=1, opcode=2 (binary), MASK=1
            val b0 = (0x80 | 0x02).toByte
            val b1 = (0x80 | data.length).toByte
            Array[Byte](b0, b1) ++ maskKey ++ masked
        end encodeClientBinaryFrame

        /** Helper: encode a WS ping frame (masked). */
        def encodeClientPingFrame(data: Array[Byte] = Array.empty): Array[Byte] =
            val maskKey = Array[Byte](0x11, 0x22, 0x33, 0x44)
            val masked  = new Array[Byte](data.length)
            var i       = 0
            while i < data.length do
                masked(i) = (data(i) ^ maskKey(i % 4)).toByte
                i += 1
            end while
            // FIN=1, opcode=9 (ping), MASK=1
            val b0 = (0x80 | 0x09).toByte
            val b1 = (0x80 | data.length).toByte
            Array[Byte](b0, b1) ++ maskKey ++ masked
        end encodeClientPingFrame

        /** Helper: encode a WS close frame (masked). */
        def encodeClientCloseFrame(code: Int = 1000, reason: String = ""): Array[Byte] =
            val reasonBytes = reason.getBytes(StandardCharsets.UTF_8)
            val payload     = new Array[Byte](2 + reasonBytes.length)
            payload(0) = ((code >> 8) & 0xff).toByte
            payload(1) = (code & 0xff).toByte
            java.lang.System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length)
            val maskKey = Array[Byte](0x55, 0x66, 0x77, 0x88.toByte)
            val masked  = new Array[Byte](payload.length)
            var i       = 0
            while i < payload.length do
                masked(i) = (payload(i) ^ maskKey(i % 4)).toByte
                i += 1
            end while
            // FIN=1, opcode=8 (close), MASK=1
            val b0 = (0x80 | 0x08).toByte
            val b1 = (0x80 | payload.length).toByte
            Array[Byte](b0, b1) ++ maskKey ++ masked
        end encodeClientCloseFrame

        /** Helper: decode a WS frame from server (unmasked). Returns (opcode, payload bytes). */
        def decodeServerFrame(data: Array[Byte]): (Int, Array[Byte]) =
            val opcode     = data(0) & 0x0f
            val payloadLen = data(1) & 0x7f
            val payload    = data.slice(2, 2 + payloadLen)
            (opcode, payload)
        end decodeServerFrame

        /** Helper: decode a WS text frame from server (unmasked). Returns the text payload. */
        def decodeServerTextFrame(data: Array[Byte]): String =
            val (_, payload) = decodeServerFrame(data)
            new String(payload, StandardCharsets.UTF_8)
        end decodeServerTextFrame

        /** Helper: read one complete WS frame from outbound channel. Accumulates bytes until a complete frame (header + payload) is
          * available. Server frames are unmasked. Handles Abort[Closed] internally -- throws if channel is closed.
          */
        def readWsFrame(outbound: Channel.Unsafe[Span[Byte]])(using Frame): Array[Byte] < Async =
            val buf = new java.io.ByteArrayOutputStream()

            def takeMore(): Array[Byte] < Async =
                Abort.run[Closed](outbound.safe.take).map {
                    case Result.Success(span) =>
                        buf.write(span.toArray)
                        checkComplete()
                    case Result.Failure(e) => throw new RuntimeException(s"Channel closed while reading WS frame: $e")
                    case Result.Panic(t)   => throw t
                }

            def checkComplete(): Array[Byte] < Async =
                val data = buf.toByteArray
                if data.length < 2 then takeMore()
                else
                    val payloadLen = data(1) & 0x7f
                    val headerLen  = 2 // server frames are never masked, payloads < 126 in tests
                    val totalLen   = headerLen + payloadLen
                    if data.length >= totalLen then data.take(totalLen)
                    else takeMore()
                end if
            end checkComplete

            takeMore()
        end readWsFrame

        /** Standard echo WS handler -- echoes every payload back. */
        def wsEcho(req: HttpRequest[Any], ws: HttpWebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
            ws.stream.foreach(ws.put).handle(Abort.run[Closed]).unit

        "HttpWebSocket upgrade succeeds" in {
            val handler = HttpHandler.webSocket("ws")(wsEcho)
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 101 Switching Protocols"), s"Expected 101, got: $response")
                assert(response.contains("Upgrade: websocket"), s"Expected Upgrade header, got: $response")
                assert(response.contains("Connection: Upgrade"), s"Expected Connection header, got: $response")
                // Clean up: close inbound to terminate WS fibers
                discard(inbound.close())
                ()
            }
        }

        "a WebSocket route hit without an upgrade, by a request whose body arrived with its head: 404, the body skipped, the connection kept alive" in {
            val handler  = HttpHandler.webSocket("ws")(wsEcho)
            val router   = buildRouter(Seq(handler), Absent)
            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)
            sendRequest(
                inbound,
                "GET /ws HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhelloGET /ws HTTP/1.1\r\nHost: localhost\r\n\r\n"
            )
            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)
            collectResponse(outbound).map { first =>
                assert(first.startsWith("HTTP/1.1 404 Not Found"), s"observed: $first")
                assert(
                    !first.toLowerCase.contains("connection: close"),
                    s"a body received with the head leaves nothing on the wire, observed: $first"
                )
                collectResponse(outbound).map { second =>
                    assert(second.startsWith("HTTP/1.1 404 Not Found"), s"the request behind the body must be answered, observed: $second")
                    assert(!inbound.closed(), "the connection stays open")
                }
            }
        }

        "a WebSocket route hit without an upgrade, by a request whose body is still arriving: 404 with Connection: close, drained, and closed" in {
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val handler  = HttpHandler.webSocket("ws")(wsEcho)
                    val router   = buildRouter(Seq(handler), Absent)
                    val inbound  = Channel.Unsafe.init[Span[Byte]](64)
                    val outbound = Channel.Unsafe.init[Span[Byte]](64)
                    val config   = defaultConfig.idleTimeout(200.millis).lingeringTimeout(500.millis)
                    sendRequest(inbound, "GET /ws HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\n\r\nhello")
                    val probe = CloseProbe(inbound)
                    UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                    collectResponse(outbound).map { response =>
                        assert(response.startsWith("HTTP/1.1 404 Not Found"), s"observed: $response")
                        assert(
                            response.toLowerCase.contains("connection: close"),
                            s"a body left on the wire must close the connection, observed: $response"
                        )
                        pollUntil(inbound.pendingTakes().contains(1)).map { draining =>
                            assert(draining, "the rest of the body is drained before the close")
                            tc.advance(200.millis).andThen {
                                probe.closed.map(closed => assert(closed, "a silent peer is closed at the idle timeout"))
                            }
                        }
                    }
                }
            }
        }

        "a WebSocket route hit without an upgrade, by a request without a body: 404 and the connection stays keep-alive" in {
            val handler  = HttpHandler.webSocket("ws")(wsEcho)
            val router   = buildRouter(Seq(handler), Absent)
            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)
            sendRequest(inbound, "GET /ws HTTP/1.1\r\nHost: localhost\r\n\r\n")
            sendRequest(inbound, "GET /ws HTTP/1.1\r\nHost: localhost\r\n\r\n")
            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)
            collectResponse(outbound).map { first =>
                assert(first.startsWith("HTTP/1.1 404 Not Found"), s"observed: $first")
                assert(
                    !first.toLowerCase.contains("connection: close"),
                    s"a request with no body leaves the connection keep-alive, observed: $first"
                )
                collectResponse(outbound).map { second =>
                    assert(second.startsWith("HTTP/1.1 404 Not Found"), s"the pipelined request must be answered, observed: $second")
                    assert(!inbound.closed(), "the connection stays open")
                }
            }
        }

        "an upgrade on a route that is not a WebSocket, by a request whose body arrived with its head: 404, the body skipped, the route served next" in {
            val handler  = HttpHandler.getText("hello")(_ => "world")
            val router   = buildRouter(Seq(handler), Absent)
            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)
            sendRequest(
                inbound,
                "GET /hello HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nContent-Length: 5\r\n\r\nhelloGET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
            )
            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)
            collectResponse(outbound).map { first =>
                assert(first.startsWith("HTTP/1.1 404 Not Found"), s"observed: $first")
                assert(
                    !first.toLowerCase.contains("connection: close"),
                    s"a body received with the head leaves nothing on the wire, observed: $first"
                )
                collectResponse(outbound).map { second =>
                    assert(second.startsWith("HTTP/1.1 200 OK") && second.endsWith("world"), s"observed: $second")
                    assert(!inbound.closed(), "the connection stays open")
                }
            }
        }

        "a WebSocket route hit without an upgrade, by an HTTP/1.0 request: 404 with Connection: close and the connection closed" in {
            val handler  = HttpHandler.webSocket("ws")(wsEcho)
            val router   = buildRouter(Seq(handler), Absent)
            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)
            sendRequest(inbound, "GET /ws HTTP/1.0\r\nHost: localhost\r\n\r\n")
            val probe = CloseProbe(inbound)
            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook)
            collectResponse(outbound).map { response =>
                assert(response.contains("404 Not Found"), s"observed: $response")
                assert(
                    response.toLowerCase.contains("connection: close"),
                    s"an HTTP/1.0 answer must announce the close, observed: $response"
                )
                probe.closed.map(closed => assert(closed, "the connection closes after the answer to an HTTP/1.0 request"))
            }
        }

        "HttpWebSocket rejects frames exceeding configured maxFrameSize" in {
            Latch.initWith(1) { handlerDone =>
                val received = AtomicBoolean.Unsafe.init(false)
                val config   = HttpWebSocket.Config(maxFrameSize = 4)
                val handler  = HttpHandler.webSocket("ws", config) { (_, ws) =>
                    Abort.run[Closed](ws.take()).map {
                        case Result.Success(_) =>
                            discard(received.set(true))
                        case _ => ()
                    }.andThen(handlerDone.release)
                }
                val router = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](64)
                val outbound = Channel.Unsafe.init[Span[Byte]](64)

                discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

                collectWsUpgradeResponse(outbound).map { response =>
                    assert(response.contains("HTTP/1.1 101 Switching Protocols"), s"Expected 101, got: $response")
                    discard(inbound.offer(Span.fromUnsafe(encodeClientTextFrame("hello"))))
                    // The oversized frame ends the read pump, closing the session's inbound and failing the handler's
                    // take. The handler returning is when the frame's fate is settled: delivered by then or never.
                    // The timeout is a deadlock ceiling, not a window the assertion depends on.
                    Async.timeout(30.seconds)(handlerDone.await).andThen {
                        assert(!received.get(), "Oversized frame should close before reaching the handler")
                        discard(inbound.close())
                        succeed
                    }
                }
            }
        }

        "HttpWebSocket upgrade with correct Sec-WebSocket-Accept" in {
            val clientKey = "dGhlIHNhbXBsZSBub25jZQ=="
            val handler   = HttpHandler.webSocket("ws")(wsEcho)
            val router    = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws", clientKey).getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                // The expected accept key is SHA1(clientKey + GUID) base64-encoded
                val expectedAccept = WebSocketCodec.computeAcceptKey(clientKey)
                assert(
                    response.contains(s"Sec-WebSocket-Accept: $expectedAccept"),
                    s"Expected Sec-WebSocket-Accept: $expectedAccept, got: $response"
                )
                discard(inbound.close())
                ()
            }
        }

        "parser stops after upgrade" in {
            Latch.initWith(1) { handlerDone =>
                val handler = HttpHandler.webSocket("ws")((req, ws) => wsEcho(req, ws).andThen(handlerDone.release))
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](64)
                val outbound = Channel.Unsafe.init[Span[Byte]](64)

                discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

                collectWsUpgradeResponse(outbound).map { response =>
                    assert(response.contains("101"), s"Expected 101, got: $response")
                    // After upgrade a second HTTP request must produce no HTTP response: the connection is now
                    // HttpWebSocket and the parser must not restart. Those bytes reach the WS codec, which rejects
                    // them as an unmasked client frame and ends the session, so the handler returning means done.
                    val secondRequest = "GET /ws HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    discard(inbound.offer(Span.fromUnsafe(secondRequest.getBytes(StandardCharsets.US_ASCII))))
                    Async.timeout(30.seconds)(handlerDone.await).andThen {
                        // Poll outbound: no HTTP response (WS frames only, if any)
                        var foundHttpResponse = false
                        var done              = false
                        while !done do
                            outbound.poll() match
                                case Result.Success(Present(span)) =>
                                    val str = new String(span.toArray, StandardCharsets.US_ASCII)
                                    if str.contains("HTTP/1.1") then foundHttpResponse = true
                                case _ =>
                                    done = true
                        end while
                        assert(!foundHttpResponse, "Parser should NOT produce HTTP responses after WS upgrade")
                        discard(inbound.close())
                        succeed
                    }
                }
            }
        }

        "WS echo test" in {
            val handler = HttpHandler.webSocket("ws")(wsEcho)
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                assert(response.contains("101"), s"Expected 101, got: $response")
                // Send a text frame
                discard(inbound.offer(Span.fromUnsafe(encodeClientTextFrame("hello"))))
                // Read the echoed frame
                readWsFrame(outbound).map { frameBytes =>
                    val text = decodeServerTextFrame(frameBytes)
                    assert(text == "hello", s"Expected 'hello', got: '$text'")
                    discard(inbound.close())
                    ()
                }
            }
        }

        "WS binary frame" in {
            val handler = HttpHandler.webSocket("ws")(wsEcho)
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                assert(response.contains("101"), s"Expected 101, got: $response")
                // Send a binary frame
                val data = Array[Byte](1, 2, 3, 4, 5)
                discard(inbound.offer(Span.fromUnsafe(encodeClientBinaryFrame(data))))
                // Read the echoed frame
                readWsFrame(outbound).map { frameBytes =>
                    // Server response: FIN=1, opcode=2 (binary), no mask
                    val opcode     = frameBytes(0) & 0x0f
                    val payloadLen = frameBytes(1) & 0x7f
                    assert(opcode == 2, s"Expected binary opcode (2), got: $opcode")
                    assert(payloadLen == 5, s"Expected payload length 5, got: $payloadLen")
                    val payload = frameBytes.slice(2, 2 + payloadLen)
                    assert(payload.sameElements(data), s"Binary payload mismatch")
                    discard(inbound.close())
                    ()
                }
            }
        }

        "WS ping/pong" in {
            val handler = HttpHandler.webSocket("ws")(wsEcho)
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                assert(response.contains("101"), s"Expected 101, got: $response")
                // Send a ping frame with payload "hi"
                val pingPayload = "hi".getBytes(StandardCharsets.UTF_8)
                discard(inbound.offer(Span.fromUnsafe(encodeClientPingFrame(pingPayload))))
                // Read the pong frame
                readWsFrame(outbound).map { frameBytes =>
                    val opcode     = frameBytes(0) & 0x0f
                    val payloadLen = frameBytes(1) & 0x7f
                    // Pong opcode is 0x0A
                    assert(opcode == 0x0a, s"Expected pong opcode (0x0a), got: $opcode")
                    assert(payloadLen == 2, s"Expected pong payload length 2, got: $payloadLen")
                    val pongPayload = new String(frameBytes, 2, payloadLen, StandardCharsets.UTF_8)
                    assert(pongPayload == "hi", s"Expected pong payload 'hi', got: '$pongPayload'")
                    discard(inbound.close())
                    ()
                }
            }
        }

        "WS close frame" in {
            // Handler that waits for close
            val handler = HttpHandler.webSocket("ws") { (_, ws) =>
                Abort.run[Closed](ws.take()).unit
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                assert(response.contains("101"), s"Expected 101, got: $response")
                // Send a close frame
                discard(inbound.offer(Span.fromUnsafe(encodeClientCloseFrame(1000, "bye"))))
                // The server reads the Close frame, registering the peer's reason and failing the read loop (and
                // ws.take()) with Closed. Cleanup mirrors the peer's code and reason back as its own Close frame
                // (RFC 6455 section 5.5.1). Reading that frame is the settled outcome; the timeout is a deadlock ceiling.
                Async.timeout(30.seconds)(readWsFrame(outbound)).map { frameBytes =>
                    val opcode     = frameBytes(0) & 0x0f
                    val payloadLen = frameBytes(1) & 0x7f
                    assert(opcode == 0x08, s"Expected a close opcode (0x08) in reply, got: $opcode")
                    assert(payloadLen == 5, s"Expected a 2-byte code plus the 3-byte reason, got payload length: $payloadLen")
                    val code   = ((frameBytes(2) & 0xff) << 8) | (frameBytes(3) & 0xff)
                    val reason = new String(frameBytes, 4, payloadLen - 2, StandardCharsets.UTF_8)
                    assert(code == 1000, s"Expected the peer's close code 1000 to be mirrored, got: $code")
                    assert(reason == "bye", s"Expected the peer's close reason 'bye' to be mirrored, got: '$reason'")
                    discard(inbound.close())
                    succeed
                }
            }
        }

        "WS upgrade on non-WS route returns 404" in {
            // Only a regular HTTP handler, no WS handler
            val handler = HttpHandler.getText("ws")(_ => "hello")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            // Send WS upgrade request to a non-WS route
            discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 404 Not Found"), s"Expected 404, got: $response")
            }
        }

        "parser buffer forwarded to WS" in {
            // This test verifies that leftover bytes after the HTTP upgrade headers
            // are correctly forwarded to the WS codec via takeRemainingBytes.
            val handler = HttpHandler.webSocket("ws")(wsEcho)
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            // Send the upgrade request AND a WS text frame in the same chunk.
            // The parser should parse the HTTP headers, and the leftover (the WS frame)
            // should be forwarded to the inbound channel for the WS codec.
            val upgradeBytes = wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII)
            val wsFrameBytes = encodeClientTextFrame("piggybacked")
            val combined     = new Array[Byte](upgradeBytes.length + wsFrameBytes.length)
            java.lang.System.arraycopy(upgradeBytes, 0, combined, 0, upgradeBytes.length)
            java.lang.System.arraycopy(wsFrameBytes, 0, combined, upgradeBytes.length, wsFrameBytes.length)
            discard(inbound.offer(Span.fromUnsafe(combined)))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                assert(response.contains("101"), s"Expected 101, got: $response")
                // The piggybacked WS frame should have been echoed back
                readWsFrame(outbound).map { echoed =>
                    val text = decodeServerTextFrame(echoed)
                    assert(text == "piggybacked", s"Expected 'piggybacked', got: '$text'")
                    discard(inbound.close())
                    ()
                }
            }
        }

        "WS connection cleanup tears down pumps" in {
            // Handler that returns immediately — pumps should be torn down
            val handler = HttpHandler.webSocket("ws") { (_, _) => Kyo.unit }
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            discard(inbound.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                assert(response.contains("101"), s"Expected 101, got: $response")
                // Handler returned immediately. serveWebSocket installs a 1000 close reason, drains the write pump so
                // the close frame reaches the wire, then lets Sync.ensure interrupt the read pump. Reading that frame,
                // the sequence's last observable step, is what says teardown ran.
                Async.timeout(30.seconds)(readWsFrame(outbound)).map { closeFrame =>
                    assert((closeFrame(0) & 0x0f) == 0x08, s"Expected the session close frame, got opcode: ${closeFrame(0) & 0x0f}")
                    discard(inbound.offer(Span.fromUnsafe(encodeClientTextFrame("after-cleanup"))))
                    // A surviving write pump would echo this frame back. The poll returns the moment one appears, so a
                    // pump that outlived the handler is caught as soon as it acts, not after a fixed wait.
                    def echoed =
                        outbound.poll() match
                            case Result.Success(Present(span)) =>
                                val data = span.toArray
                                // Text opcode = 1: seeing it means the echo pump still runs
                                data.length >= 2 && (data(0) & 0x0f) == 1
                            case _ => false
                    pollUntil(echoed, maxPolls = 100).map { gotEcho =>
                        assert(!gotEcho, "Write pump should have been torn down — no echo expected after handler completes")
                        discard(inbound.close())
                        succeed
                    }
                }
            }
        }

        "multiple WS connections concurrent" in {
            val handler = HttpHandler.webSocket("ws")(wsEcho)
            val router  = buildRouter(Seq(handler), Absent)

            // Set up 3 independent connections, each with separate channel pairs
            val n     = 3
            val pairs = (0 until n).map { _ =>
                val in  = Channel.Unsafe.init[Span[Byte]](64)
                val out = Channel.Unsafe.init[Span[Byte]](64)
                (in, out)
            }

            // Initiate WS upgrade on each connection
            pairs.foreach { case (in, _) =>
                discard(in.offer(Span.fromUnsafe(wsUpgradeRequest("ws").getBytes(StandardCharsets.US_ASCII))))
            }

            // Serve each connection
            pairs.foreach { case (in, out) =>
                UnsafeServerDispatch.serve(router, in, out, defaultConfig)
            }

            // Wait for all upgrades, then send a unique message on each and verify echo
            val verifications = pairs.zipWithIndex.map { case ((in, out), idx) =>
                collectWsUpgradeResponse(out).map { response =>
                    assert(response.contains("101"), s"Connection $idx: Expected 101, got: $response")
                    val msg = s"hello-$idx"
                    discard(in.offer(Span.fromUnsafe(encodeClientTextFrame(msg))))
                    readWsFrame(out).map { frameBytes =>
                        val text = decodeServerTextFrame(frameBytes)
                        assert(text == msg, s"Connection $idx: Expected '$msg', got: '$text'")
                        discard(in.close())
                    }
                }
            }

            // Chain all verifications sequentially
            verifications.foldLeft(Kyo.unit: Unit < (Async & Abort[Any])) { (acc, v) =>
                acc.andThen(v)
            }.unit
        }

        "WS upgrade with subprotocol" in {
            val config  = HttpWebSocket.Config(subprotocols = Seq("graphql-transport-ws", "chat"))
            val handler = HttpHandler.webSocket("ws", config)(wsEcho)
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            // Client offers two subprotocols; server supports "graphql-transport-ws" and "chat"
            val upgradeReq =
                "GET /ws HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: chat, superchat\r\n" +
                    "\r\n"
            discard(inbound.offer(Span.fromUnsafe(upgradeReq.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectWsUpgradeResponse(outbound).map { response =>
                assert(response.contains("101"), s"Expected 101, got: $response")
                // Server should have selected "chat" (first client-offered that server supports)
                assert(
                    response.contains("Sec-WebSocket-Protocol: chat"),
                    s"Expected Sec-WebSocket-Protocol: chat in response, got: $response"
                )
                discard(inbound.close())
                ()
            }
        }

        "concurrent keep-alive requests with bodies" in {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            // Two sequential keep-alive requests, both with bodies split across chunks
            val body1    = "A" * 500
            val body2    = "B" * 300
            val headers1 = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body1.length}\r\n\r\n"
            val headers2 = s"POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body2.length}\r\n\r\n"

            // First request: headers + 200 of 500 body bytes
            discard(inbound.offer(Span.fromUnsafe((headers1 + body1.take(200)).getBytes(StandardCharsets.US_ASCII))))
            // Remaining 300 body bytes of first request
            discard(inbound.offer(Span.fromUnsafe(body1.drop(200).getBytes(StandardCharsets.US_ASCII))))
            // Second request: headers + 100 of 300 body bytes
            discard(inbound.offer(Span.fromUnsafe((headers2 + body2.take(100)).getBytes(StandardCharsets.US_ASCII))))
            // Remaining 200 body bytes of second request
            discard(inbound.offer(Span.fromUnsafe(body2.drop(100).getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response1 =>
                assert(response1.contains("HTTP/1.1 200 OK"), s"First response expected 200, got: $response1")
                assert(response1.contains(body1), s"First response should contain body1 of ${body1.length} chars")
                collectResponse(outbound).map { response2 =>
                    assert(response2.contains("HTTP/1.1 200 OK"), s"Second response expected 200, got: $response2")
                    assert(response2.contains(body2), s"Second response should contain body2 of ${body2.length} chars")
                }
            }
        }
    }

    "IdleTimeout" - {

        "default idle timeout is 60 seconds" in {
            assert(HttpServerConfig.default.idleTimeout == 60.seconds)
        }

        "idle connection closed after timeout" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val idleTimeout = 200.millis
            val config      = defaultConfig.idleTimeout(idleTimeout)

            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    sendRequest(inbound, request)

                    UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)

                    collectResponse(outbound).map { response =>
                        assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200, got: $response")

                        awaitIdleTimerArmed(inbound).map { armed =>
                            assert(armed, "the keep-alive restart must arm the idle timer")
                            // Elapse the whole idle period: the timer fires and the connection is torn down.
                            tc.advance(idleTimeout).andThen {
                                pollUntil(inbound.closed()).map { closed =>
                                    assert(closed, "the idle timer must close the connection once the idle period elapses")
                                    inbound.offer(Span.fromUnsafe("test".getBytes)) match
                                        case Result.Failure(_: Closed) => succeed
                                        case other => fail(s"Expected the closed connection to refuse input, got: $other")
                                }
                            }
                        }
                    }
                }
            }
        }

        "active connection not closed" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val config = defaultConfig.idleTimeout(500.millis)

            // Send first keep-alive request
            val request1 = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
            val request2 = "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            sendRequest(inbound, request1)
            sendRequest(inbound, request2)

            // Virtual time never advances, so a stalled runner cannot fire the idle timer between the two pipelined
            // requests: the leaf asserts pipelining leaves no idle gap.
            Clock.withTimeControl { _ =>
                Clock.use { clock =>
                    UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)

                    // Both succeed: pipelining, no idle gap
                    collectResponse(outbound).map { response1 =>
                        assert(response1.contains("HTTP/1.1 200 OK"), s"First response expected 200, got: $response1")
                        collectResponse(outbound).map { response2 =>
                            assert(response2.contains("HTTP/1.1 200 OK"), s"Second response expected 200, got: $response2")
                        }
                    }
                }
            }
        }

        "timeout reset on each request" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val idleTimeout = 400.millis
            val config      = defaultConfig.idleTimeout(idleTimeout)
            val keepAlive   = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"

            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    sendRequest(inbound, keepAlive)

                    UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)

                    // Each round idles three quarters of the timeout then sends another request. A timer armed once at
                    // connection start would expire in the second round; only a per-request rearm keeps it alive.
                    def round(previous: String): String < (Async & Abort[Closed]) =
                        assert(previous.contains("HTTP/1.1 200 OK"), s"Expected 200, got: $previous")
                        awaitIdleTimerArmed(inbound).map { armed =>
                            assert(armed, "the keep-alive restart must arm the idle timer")
                            tc.advance(idleTimeout * 0.75).andThen {
                                sendRequest(inbound, keepAlive)
                                collectResponse(outbound)
                            }
                        }
                    end round

                    collectResponse(outbound).map(round).map(round).map(round).map { last =>
                        assert(last.contains("HTTP/1.1 200 OK"), s"Expected 200 after three idle rounds, got: $last")
                        // The timer that survived every round is still live: a full idle period with no request closes it.
                        awaitIdleTimerArmed(inbound).map { armed =>
                            assert(armed, "the keep-alive restart must arm the idle timer")
                            tc.advance(idleTimeout).andThen {
                                pollUntil(inbound.closed()).map { closed =>
                                    assert(closed, "a full idle period with no request must still close the connection")
                                }
                            }
                        }
                    }
                }
            }
        }

        "custom idle timeout respected" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val idleTimeout = 100.millis
            val config      = defaultConfig.idleTimeout(idleTimeout)

            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    sendRequest(inbound, request)

                    UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)

                    collectResponse(outbound).map { response =>
                        assert(response.contains("HTTP/1.1 200 OK"))

                        awaitIdleTimerArmed(inbound).map { armed =>
                            assert(armed, "the keep-alive restart must arm the idle timer")
                            // One millisecond short of the configured period, so the connection must still be open.
                            tc.advance(idleTimeout.minusOrZero(1.milli)).andThen {
                                assert(!inbound.closed(), s"connection closed before the configured $idleTimeout elapsed")
                                // The remaining millisecond reaches the deadline.
                                tc.advance(1.milli).andThen {
                                    pollUntil(inbound.closed()).map { closed =>
                                        assert(closed, s"connection still open after the configured $idleTimeout elapsed")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "idle timeout disabled with Duration.Infinity" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val config = defaultConfig.idleTimeout(Duration.Infinity)

            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    sendRequest(inbound, request)

                    UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)

                    collectResponse(outbound).map { response =>
                        assert(response.contains("HTTP/1.1 200 OK"))

                        // The parser take proves the keep-alive restart ran; with the timeout disabled it armed
                        // nothing, so no elapsed time can close the connection.
                        awaitIdleTimerArmed(inbound).map { restarted =>
                            assert(restarted, "the keep-alive restart must leave the parser waiting for the next request")
                            tc.advance(1.hour).andThen {
                                assert(!inbound.closed(), "a disabled idle timeout must never close the connection")
                                // Still serving: the connection is usable, not merely unclosed.
                                sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                                collectResponse(outbound).map { second =>
                                    assert(second.contains("HTTP/1.1 200 OK"), s"Expected 200 on the reused connection, got: $second")
                                }
                            }
                        }
                    }
                }
            }
        }

        "timeout fires between keep-alive requests" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val idleTimeout = 150.millis
            val config      = defaultConfig.idleTimeout(idleTimeout)

            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    sendRequest(inbound, request)

                    UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)

                    collectResponse(outbound).map { response1 =>
                        assert(response1.contains("HTTP/1.1 200 OK"))

                        awaitIdleTimerArmed(inbound).map { armed =>
                            assert(armed, "the keep-alive restart must arm the idle timer")
                            // The idle period elapses before the next request is written.
                            tc.advance(idleTimeout).andThen {
                                pollUntil(inbound.closed()).map { closed =>
                                    assert(closed, "the idle timer must close the connection once the idle period elapses")
                                    // A keep-alive follow-up sent after the expiry is refused rather than served.
                                    inbound.offer(Span.fromUnsafe(
                                        "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
                                    )) match
                                        case Result.Failure(_: Closed) => succeed
                                        case other                     => fail(s"Expected the follow-up request to be refused, got: $other")
                                    end match
                                }
                            }
                        }
                    }
                }
            }
        }

        "concurrent connections with different idle states" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = buildRouter(Seq(handler), Absent)

            // Connection 1: goes idle after its request
            val inbound1  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound1 = Channel.Unsafe.init[Span[Byte]](16)

            // Connection 2: also idle, on its own independently armed timer
            val inbound2  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound2 = Channel.Unsafe.init[Span[Byte]](16)

            val idleTimeout = 200.millis
            val config      = defaultConfig.idleTimeout(idleTimeout)

            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    sendRequest(inbound1, request)
                    sendRequest(inbound2, request)

                    UnsafeServerDispatch.serve(router, inbound1, outbound1, config, clock = clock)
                    UnsafeServerDispatch.serve(router, inbound2, outbound2, config, clock = clock)

                    collectResponse(outbound1).map { r1 =>
                        assert(r1.contains("HTTP/1.1 200 OK"))
                        collectResponse(outbound2).map { r2 =>
                            assert(r2.contains("HTTP/1.1 200 OK"))

                            awaitIdleTimerArmed(inbound1).map { armed1 =>
                                awaitIdleTimerArmed(inbound2).map { armed2 =>
                                    assert(armed1 && armed2, "both connections must arm their own idle timer")
                                    tc.advance(idleTimeout).andThen {
                                        pollUntil(inbound1.closed() && inbound2.closed()).map { closed =>
                                            assert(closed, "both idle connections must be closed by their own timer")
                                            assert(
                                                inbound1.offer(Span.fromUnsafe("test".getBytes)).isFailure,
                                                "connection 1 must refuse input after its idle expiry"
                                            )
                                            assert(
                                                inbound2.offer(Span.fromUnsafe("test".getBytes)).isFailure,
                                                "connection 2 must refuse input after its idle expiry"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "idle timeout with streaming response" in {
            // A streaming endpoint — data is sent as chunked transfer encoding
            val route   = HttpRoute.getRaw("stream").response(_.bodyText)
            val handler = route.handler { _ =>
                HttpResponse.ok("streamed data")
            }
            val router = buildRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val idleTimeout = 300.millis
            val config      = defaultConfig.idleTimeout(idleTimeout)

            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val request = "GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    sendRequest(inbound, request)

                    UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)

                    collectResponse(outbound).map { response =>
                        assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200, got: $response")

                        awaitIdleTimerArmed(inbound).map { armed =>
                            assert(armed, "the keep-alive restart must arm the idle timer after a streamed response")
                            tc.advance(idleTimeout).andThen {
                                pollUntil(inbound.closed()).map { closed =>
                                    assert(closed, "the idle timer must close the connection once the idle period elapses")
                                    inbound.offer(Span.fromUnsafe("test".getBytes)) match
                                        case Result.Failure(_: Closed) => succeed
                                        case other => fail(s"Expected the closed connection to refuse input, got: $other")
                                }
                            }
                        }
                    }
                }
            }
        }

        "the idle timer bounds the wait for the peer, not the handler" - {

            "a connection whose first request head never completes is closed after the idle timeout" in {
                val handler = HttpHandler.getText("hello")(_ => "world")
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                val idleTimeout = 200.millis
                val config      = defaultConfig.idleTimeout(idleTimeout)

                val probe = CloseProbe(inbound)
                Clock.withTimeControl { tc =>
                    Clock.use { clock =>
                        sendRequest(inbound, "GET /hel")
                        UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)

                        pollUntil(inbound.pendingTakes().contains(1)).map { waiting =>
                            assert(waiting, "the parser must be waiting for the rest of the head")
                            tc.advance(idleTimeout.minusOrZero(1.milli)).andThen {
                                assert(!inbound.closed(), "the connection must stay open until the idle timeout elapses")
                                tc.advance(1.milli).andThen {
                                    probe.closed.map { closed =>
                                        assert(closed, "a head that never completes must not hold the connection past the idle timeout")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "the final response to a Connection: close request announces the close and the connection is then closed" in {
                val handler = HttpHandler.getText("hello")(_ => "world")
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                val probe = CloseProbe(inbound)
                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook)

                collectResponse(outbound).map { response =>
                    assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200, got: $response")
                    assert(
                        response.toLowerCase.contains("connection: close"),
                        s"the final response must carry Connection: close (RFC 9112 section 9.6), got: $response"
                    )
                    probe.closed.map { closed =>
                        assert(closed, "the server must close the connection after the final response to a Connection: close request")
                    }
                }
            }

            "a keep-alive request leaves the connection open for the next request" in {
                val handler = HttpHandler.getText("hello")(_ => "world")
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
                val probe = CloseProbe(inbound)
                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook)

                collectResponse(outbound).map { first =>
                    assert(first.contains("HTTP/1.1 200 OK"), s"Expected 200, got: $first")
                    assert(!first.toLowerCase.contains("connection: close"), s"a keep-alive answer must not announce a close, got: $first")
                    assert(!inbound.closed(), "a keep-alive answer must leave the connection open")
                    sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    collectResponse(outbound).map { second =>
                        assert(second.contains("HTTP/1.1 200 OK"), s"Expected 200 on the second request, got: $second")
                        probe.closed.map(closed => assert(closed, "the connection closes after the Connection: close request"))
                    }
                }
            }

            "the response to an HTTP/1.0 request without keep-alive announces the close and the connection is then closed" in {
                val handler = HttpHandler.getText("hello")(_ => "world")
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                sendRequest(inbound, "GET /hello HTTP/1.0\r\nHost: localhost\r\n\r\n")
                val probe = CloseProbe(inbound)
                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook)

                collectResponse(outbound).map { response =>
                    assert(response.contains("200 OK") && response.endsWith("world"), s"observed: $response")
                    assert(
                        response.toLowerCase.contains("connection: close"),
                        s"an HTTP/1.0 answer must announce the close, got: $response"
                    )
                    probe.closed.map(closed => assert(closed, "the connection closes after the answer to an HTTP/1.0 request"))
                }
            }

            // RFC 9112 section 6.1: no Transfer-Encoding in a response to an HTTP/1.0 request; its streamed body is delimited by the close.
            /** The values of the `Connection` fields of a response head, lower-cased. */
            def connectionValues(head: String): Seq[String] =
                head.split("\r\n").toSeq.filter(_.toLowerCase.startsWith("connection:")).map(_.drop("connection:".length).trim.toLowerCase)

            def http10StreamedAnswer(request: String, handlerKeepAlive: Boolean = false)(using
                Frame,
                kyo.test.AssertScope
            ): Unit < (Async & Abort[Closed]) =
                val route   = HttpRoute.getRaw("events").response(_.bodyStream)
                val handler = route.handler { _ =>
                    val body: Stream[Span[Byte], Async & Abort[HttpException]] = Stream.init(Seq(
                        Span.fromUnsafe("first".getBytes(StandardCharsets.US_ASCII)),
                        Span.fromUnsafe("last".getBytes(StandardCharsets.US_ASCII))
                    ))
                    val response = HttpResponse.ok.addField("body", body)
                    if handlerKeepAlive then response.setHeader("Connection", "keep-alive") else response
                }
                val router   = buildRouter(Seq(handler), Absent)
                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)
                sendRequest(inbound, request)
                val probe = CloseProbe(inbound)
                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook)
                probe.closed.map { closed =>
                    assert(closed, "the connection closes after the close-delimited body")
                    outbound.safe.drain.map { spans =>
                        val response  = spans.map(span => new String(span.toArray, StandardCharsets.US_ASCII)).mkString
                        val headerEnd = response.indexOf("\r\n\r\n")
                        assert(headerEnd > 0, s"observed: $response")
                        val head = response.substring(0, headerEnd).toLowerCase
                        val body = response.substring(headerEnd + 4)
                        assert(head.contains("200 ok"), s"observed head: $head")
                        assert(connectionValues(head) == Seq("close"), s"the head announces the close, observed head: $head")
                        assert(!head.contains("transfer-encoding"), s"no Transfer-Encoding to an HTTP/1.0 request, observed head: $head")
                        assert(body == "firstlast", s"the body is the raw bytes, observed: $body")
                    }
                }
            end http10StreamedAnswer

            // The head says close whenever the server will close, whatever the handler set (RFC 9112 section 9.6).
            "a handler's Connection: keep-alive on a streamed answer to an HTTP/1.0 request is replaced by close" in {
                http10StreamedAnswer("GET /events HTTP/1.0\r\nHost: h\r\n\r\n", handlerKeepAlive = true)
            }

            "a handler's Connection: keep-alive is replaced by close when the request asked to close" in {
                val handler = HttpRoute.getRaw("hello").response(_.bodyText).handler(_ =>
                    HttpResponse.ok("world").setHeader("Connection", "keep-alive")
                )
                val router   = buildRouter(Seq(handler), Absent)
                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)
                sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")
                val probe = CloseProbe(inbound)
                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook)
                collectResponse(outbound).map { response =>
                    val head = response.substring(0, response.indexOf("\r\n\r\n"))
                    assert(response.endsWith("world"), s"observed: $response")
                    assert(connectionValues(head) == Seq("close"), s"the head announces the close, observed head: $head")
                    probe.closed.map(closed => assert(closed, "the connection closes after the answer"))
                }
            }

            "an HTTP/1.0 request to a streaming route is answered without Transfer-Encoding, the body raw and the connection closed after it" in {
                http10StreamedAnswer("GET /events HTTP/1.0\r\nHost: h\r\n\r\n")
            }

            "an HTTP/1.0 keep-alive request to a streaming route is answered the same way, the close announced" in {
                http10StreamedAnswer("GET /events HTTP/1.0\r\nHost: h\r\nConnection: keep-alive\r\n\r\n")
            }

            // The body arrives in two reads, so the idle timer is armed while it is owed and still armed when the handler runs; the
            // answer to an HTTP/1.0 request is framed by the close, so a close mid-body would deliver a cut body as a complete one.
            "a streamed answer to an HTTP/1.0 request that outlives the idle timeout is not cut by the idle timer" in {
                val idleTimeout                    = 200.millis
                val config                         = defaultConfig.idleTimeout(idleTimeout)
                def span(text: String): Span[Byte] = Span.fromUnsafe(text.getBytes(StandardCharsets.US_ASCII))
                def text(span: Span[Byte]): String = new String(span.toArray, StandardCharsets.US_ASCII)
                Latch.init(1).map { release =>
                    val route   = HttpRoute.postRaw("events").request(_.bodyText).response(_.bodyStream)
                    val handler = route.handler { _ =>
                        val body: Stream[Span[Byte], Async & Abort[HttpException]] =
                            Stream.init(Seq(span("first"))).concat(Stream(release.await.andThen(Emit.value(Chunk(span("last"))))))
                        HttpResponse.ok.addField("body", body)
                    }
                    val router   = buildRouter(Seq(handler), Absent)
                    val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                    val outbound = Channel.Unsafe.init[Span[Byte]](16)
                    val probe    = CloseProbe(inbound)
                    Clock.withTimeControl { tc =>
                        Clock.use { clock =>
                            sendRequest(inbound, "POST /events HTTP/1.0\r\nHost: h\r\nContent-Length: 6\r\n\r\nabc")
                            UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                            pollUntil(inbound.pendingTakes().contains(1)).map { owed =>
                                assert(owed, "the body reader waits for the rest of the body")
                                sendRequest(inbound, "def")
                                outbound.safe.take.map { head =>
                                    assert(text(head).startsWith("HTTP/1.1 200 OK"), s"observed head: ${text(head)}")
                                    outbound.safe.take.map { first =>
                                        assert(text(first) == "first", s"observed: ${text(first)}")
                                        tc.advance(idleTimeout).andThen(tc.advance(idleTimeout)).andThen {
                                            assert(
                                                !inbound.closed(),
                                                "the idle timer must not close a connection whose answer is still streaming"
                                            )
                                            release.release.andThen(outbound.safe.take).map { last =>
                                                assert(text(last) == "last", s"observed: ${text(last)}")
                                                probe.closed.map(closed => assert(closed, "the connection closes after the body"))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // RFC 9110 section 10.1.1: a 100-continue expectation in an HTTP/1.0 request is ignored.
            "an HTTP/1.0 request expecting 100-continue gets its final response only" in {
                val route   = HttpRoute.postRaw("upload").request(_.bodyText).response(_.bodyText)
                val handler = route.handler(req => HttpResponse.ok("got " + req.fields.body))
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                sendRequest(inbound, "POST /upload HTTP/1.0\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 3\r\n\r\nabc")
                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)
                collectResponse(outbound).map { response =>
                    assert(!response.contains("100 Continue"), s"no interim response to an HTTP/1.0 request, observed: $response")
                    assert(response.contains("200 OK") && response.endsWith("got abc"), s"observed: $response")
                }
            }

            // Each chunk's wait for the peer to take it is a wait on the peer, like the wait for a buffered answer to be read.
            "a peer that stops reading a streamed response is closed after the idle timeout" in {
                val route   = HttpRoute.getRaw("events").response(_.bodyStream)
                val handler = route.handler { _ =>
                    val chunk = Chunk(Span.fromUnsafe("x".getBytes(StandardCharsets.US_ASCII)))
                    val body: Stream[Span[Byte], Async & Abort[HttpException]] =
                        Stream[Span[Byte], Async & Abort[HttpException]](Loop.forever(Emit.value(chunk)))
                    HttpResponse.ok.addField("body", body)
                }
                val router = buildRouter(Seq(handler), Absent)

                val inbound     = Channel.Unsafe.init[Span[Byte]](16)
                val outbound    = Channel.Unsafe.init[Span[Byte]](4)
                val idleTimeout = 200.millis
                val config      = defaultConfig.idleTimeout(idleTimeout)

                val probe = CloseProbe(inbound)
                Clock.withTimeControl { tc =>
                    Clock.use { clock =>
                        sendRequest(inbound, "GET /events HTTP/1.1\r\nHost: h\r\n\r\n")
                        UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                        pollUntil(outbound.pendingPuts().exists(_ > 0)).map { parked =>
                            assert(parked, s"a chunk must be waiting for the peer to read, observed ${outbound.pendingPuts()} queued spans")
                            tc.advance(idleTimeout.minusOrZero(1.milli)).andThen {
                                assert(!inbound.closed(), "the connection stays open until the idle timeout elapses")
                                tc.advance(1.milli).andThen {
                                    probe.closed.map(closed =>
                                        assert(closed, "a peer that stops reading a streamed response must be closed")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            "a request body that stops arriving is closed after the idle timeout and its handler never runs" in {
                val ran     = AtomicBoolean.Unsafe.init(false)
                val route   = HttpRoute.postRaw("upload").request(_.bodyText).response(_.bodyText)
                val handler = route.handler { req =>
                    ran.set(true)
                    HttpResponse.ok("got " + req.fields.body)
                }
                val router = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                val idleTimeout = 200.millis
                val config      = defaultConfig.idleTimeout(idleTimeout)

                val probe = CloseProbe(inbound)
                Clock.withTimeControl { tc =>
                    Clock.use { clock =>
                        sendRequest(inbound, "POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\nabc")
                        UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)

                        pollUntil(inbound.pendingTakes().contains(1)).map { waiting =>
                            assert(waiting, "the body read must be waiting for the rest of the body")
                            tc.advance(idleTimeout.minusOrZero(1.milli)).andThen {
                                assert(!inbound.closed(), "the connection must stay open until the idle timeout elapses")
                                tc.advance(1.milli).andThen {
                                    probe.closed.map { closed =>
                                        assert(closed, "a body that stops arriving must not hold the connection past the idle timeout")
                                        assert(!ran.get(), "the handler must not run on a body that never completed")
                                        assert(
                                            outbound.closed() || outbound.size().contains(0),
                                            "no response is written for a body that never completed"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "a request body that keeps arriving is not closed and is answered once complete" in {
                val route   = HttpRoute.postRaw("upload").request(_.bodyText).response(_.bodyText)
                val handler = route.handler(req => HttpResponse.ok("got " + req.fields.body))
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                val idleTimeout = 200.millis
                val config      = defaultConfig.idleTimeout(idleTimeout)

                Clock.withTimeControl { tc =>
                    Clock.use { clock =>
                        sendRequest(inbound, "POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\nabc")
                        UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)

                        pollUntil(inbound.pendingTakes().contains(1)).map { waiting =>
                            assert(waiting, "the body read must be waiting for the rest of the body")
                            tc.advance(idleTimeout * 0.75).andThen {
                                sendRequest(inbound, "def")
                                // The reader has taken the new bytes once it waits on the channel again.
                                pollUntil(inbound.pendingTakes().contains(1)).map { waitingAgain =>
                                    assert(waitingAgain, "the body read must take the bytes and wait for more")
                                    tc.advance(idleTimeout * 0.75).andThen {
                                        assert(!inbound.closed(), "a body that keeps arriving must not be closed by the idle timeout")
                                        sendRequest(inbound, "ghij")
                                        collectResponse(outbound).map { response =>
                                            assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200, got: $response")
                                            assert(
                                                response.endsWith("got abcdefghij"),
                                                s"the handler must see the whole body, got: $response"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "a chunked body arriving one chunk per three quarters of a window to a fast handler is not closed and is answered once complete" in {
                val route   = HttpRoute.postRaw("upload").request(_.bodyStream).response(_.bodyText)
                val handler = route.handler { req =>
                    Abort.run[HttpException](req.fields.body.run).map {
                        case Result.Success(spans) => HttpResponse.ok(s"got ${spans.foldLeft(0)(_ + _.size)}")
                        case other                 => HttpResponse.ok(s"failed $other")
                    }
                }
                val router = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                val idleTimeout = 200.millis
                val config      = defaultConfig.idleTimeout(idleTimeout)

                Clock.withTimeControl { tc =>
                    Clock.use { clock =>
                        sendRequest(inbound, "POST /upload HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n")
                        UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)
                        pollUntil(inbound.pendingTakes().contains(1)).map { waiting =>
                            assert(waiting, "the decoder must be waiting for the next chunk")
                            tc.advance(idleTimeout * 0.75).andThen {
                                sendRequest(inbound, "1\r\nb\r\n")
                                pollUntil(inbound.size().contains(0) && inbound.pendingTakes().contains(1)).map { again =>
                                    assert(again, "the decoder must take the chunk and wait for the next")
                                    tc.advance(idleTimeout * 0.75).andThen {
                                        assert(
                                            !inbound.closed(),
                                            "a chunked body that keeps arriving must not be closed by the idle timeout"
                                        )
                                        sendRequest(inbound, "1\r\nc\r\n0\r\n\r\n")
                                        collectResponse(outbound).map { response =>
                                            assert(response.contains("200 OK") && response.endsWith("got 3"), s"observed: $response")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "a Connection: close request answered by a stream longer than the idle window is closed only when the stream ends" in {
                Latch.init(1).map { release =>
                    val route   = HttpRoute.getRaw("events").response(_.bodyStream)
                    val handler = route.handler { _ =>
                        val body: Stream[Span[Byte], Async & Abort[HttpException]] = Stream[Span[Byte], Async & Abort[HttpException]] {
                            Emit.value(Chunk(Span.fromUnsafe("first".getBytes(StandardCharsets.US_ASCII))))
                                .andThen(release.await)
                                .andThen(Emit.value(Chunk(Span.fromUnsafe("last".getBytes(StandardCharsets.US_ASCII)))))
                        }
                        HttpResponse.ok.addField("body", body)
                    }
                    val router = buildRouter(Seq(handler), Absent)

                    val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                    val outbound = Channel.Unsafe.init[Span[Byte]](16)

                    val idleTimeout = 200.millis
                    val config      = defaultConfig.idleTimeout(idleTimeout)

                    def takeUntil(seen: String, wanted: String): String < (Async & Abort[Closed]) =
                        if seen.contains(wanted) then seen
                        else outbound.safe.take.map(span => takeUntil(seen + new String(span.toArray, StandardCharsets.US_ASCII), wanted))

                    val probe = CloseProbe(inbound)
                    Clock.withTimeControl { tc =>
                        Clock.use { clock =>
                            sendRequest(inbound, "GET /events HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")
                            UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                            takeUntil("", "first").map { head =>
                                assert(head.contains("200 OK") && head.toLowerCase.contains("connection: close"), s"observed: $head")
                                tc.advance(idleTimeout).andThen(tc.advance(idleTimeout)).andThen {
                                    assert(!inbound.closed(), "a response stream still running is the handler's work, not the peer's wait")
                                    release.release.andThen {
                                        takeUntil("", "last").map { tail =>
                                            assert(tail.contains("last"), s"observed: $tail")
                                            probe.closed.map(closed => assert(closed, "the connection closes once the stream ends"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "a Connection: close request whose body the handler left unread is drained until the peer's EOF, bounded by the idle timeout" in {
                // Closing with the peer's unread bytes still arriving resets the connection, and a reset can discard the response
                // before the peer reads it: the server reads and discards the rest of the body first (RFC 9112 section 9.6).
                val route   = HttpRoute.postRaw("sink").request(_.bodyStream).response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok("sunk"))
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)

                val idleTimeout = 200.millis
                val config      = defaultConfig.idleTimeout(idleTimeout)
                val probe       = CloseProbe(inbound)

                Clock.withTimeControl { tc =>
                    Clock.use { clock =>
                        // 17 chunks with the head: the decoder delivers 16 into its channel and parks on the 17th, so it never registers a
                        // take on the connection, and the one take the barrier below counts is the drain's. A take the decoder registered
                        // before the handler's settle interrupted it stays counted until the next transfer polls it out.
                        sendRequest(
                            inbound,
                            "POST /sink HTTP/1.1\r\nHost: h\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n" + "1\r\na\r\n" * 17
                        )
                        UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)

                        collectResponse(outbound).map { response =>
                            assert(response.endsWith("sunk"), s"Expected the answer, got: $response")
                            (0 until 5).foreach(_ => sendRequest(inbound, "1\r\nb\r\n"))
                            // The drain has read and counted what arrived once it waits on the connection again.
                            pollUntil(inbound.size().contains(0) && inbound.pendingTakes().contains(1)).map { drained =>
                                assert(drained, "the bytes the peer keeps sending must be read and discarded")
                                assert(!inbound.closed(), "the connection stays open while the peer's body still arrives")
                                tc.advance(idleTimeout.minusOrZero(1.milli)).andThen {
                                    sendRequest(inbound, "1\r\nc\r\n")
                                    pollUntil(inbound.size().contains(0) && inbound.pendingTakes().contains(1)).map { drainedAgain =>
                                        assert(drainedAgain, "the drain continues while bytes arrive")
                                        tc.advance(idleTimeout.minusOrZero(1.milli)).andThen {
                                            assert(!inbound.closed(), "bytes read within the window keep the connection open")
                                            tc.advance(idleTimeout).andThen {
                                                probe.closed.map { closed =>
                                                    assert(closed, "a peer that stops sending is closed once the idle timeout elapses")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The drain reads and discards a body the server will not use; it is bounded in total by lingeringTimeout, independently of
            // the idle timer, so a peer that keeps trickling cannot hold a connection that has no purpose left.
            def drainOf(idleTimeout: Duration, lingeringTimeout: Duration)(
                afterAnswer: (Clock.TimeControl, Channel.Unsafe[Span[Byte]], CloseProbe) => Unit < (Async & Abort[Any])
            )(using kyo.test.AssertScope) =
                val route   = HttpRoute.postRaw("sink").request(_.bodyStream).response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok("sunk"))
                val router  = buildRouter(Seq(handler), Absent)

                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)
                val config   = defaultConfig.idleTimeout(idleTimeout).lingeringTimeout(lingeringTimeout)
                val probe    = CloseProbe(inbound)

                Clock.withTimeControl { tc =>
                    Clock.use { clock =>
                        // 17 chunks with the head: the decoder delivers 16 into its channel and waits on the 17th, so the only take
                        // pending on the connection after the answer is the drain's, and the drain takes only once its bound is armed.
                        sendRequest(
                            inbound,
                            "POST /sink HTTP/1.1\r\nHost: h\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n" + "1\r\na\r\n" * 17
                        )
                        UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                        collectResponse(outbound).map { response =>
                            assert(response.endsWith("sunk"), s"Expected the answer, got: $response")
                            pollUntil(inbound.pendingTakes().contains(1)).map { draining =>
                                assert(draining, "the drain must be waiting on the connection")
                                afterAnswer(tc, inbound, probe)
                            }
                        }
                    }
                }
            end drainOf

            "the drain is bounded in total: a peer that keeps trickling within the idle window is closed at lingeringTimeout" in
                drainOf(idleTimeout = 200.millis, lingeringTimeout = 500.millis) { (tc, inbound, probe) =>
                    def trickle(): Unit < (Async & Abort[Any]) =
                        tc.advance(150.millis).andThen {
                            sendRequest(inbound, "1\r\nb\r\n")
                            pollUntil(inbound.size().contains(0) && inbound.pendingTakes().contains(1)).map { drained =>
                                assert(drained, "the trickle is read and discarded")
                            }
                        }
                    trickle().andThen(trickle()).andThen(trickle()).andThen {
                        assert(!inbound.closed(), "within the lingering bound a trickling peer keeps the connection")
                        tc.advance(150.millis).andThen {
                            probe.closed.map { closed =>
                                assert(closed, "the lingering bound closes a peer that keeps sending, whatever the idle timer says")
                            }
                        }
                    }
                }

            "the drain is bounded in total even with the idle timeout disabled" in
                drainOf(idleTimeout = Duration.Infinity, lingeringTimeout = 500.millis) { (tc, inbound, probe) =>
                    tc.advance(499.millis).andThen {
                        assert(!inbound.closed(), "the connection stays open until the lingering bound elapses")
                        tc.advance(1.milli).andThen {
                            probe.closed.map { closed =>
                                assert(closed, "with no idle timer the lingering bound alone closes the drained connection")
                            }
                        }
                    }
                }

            // The peer's whole chunked body arrives in one read: with the head (the decoder gets it as its initial bytes and never takes
            // from the connection) or as the one read after the head. A handler slower than the window is the handler's wait, not the
            // peer's silence: the body is in hand.
            def wholeBodyInOneRead(bodyWithHead: Boolean)(using kyo.test.AssertScope) =
                Latch.init(1).map { gate =>
                    val route   = HttpRoute.postRaw("upload").request(_.bodyStream).response(_.bodyText)
                    val handler = route.handler { req =>
                        gate.await.andThen {
                            Abort.run[HttpException](req.fields.body.run).map {
                                case Result.Success(spans) => HttpResponse.ok(s"got ${spans.foldLeft(0)(_ + _.size)}")
                                case other                 => HttpResponse.ok(s"failed $other")
                            }
                        }
                    }
                    val router = buildRouter(Seq(handler), Absent)

                    val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                    val outbound = Channel.Unsafe.init[Span[Byte]](16)

                    val idleTimeout = 200.millis
                    val config      = defaultConfig.idleTimeout(idleTimeout)
                    val chunks      = 40
                    val head        = "POST /upload HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n"
                    val body        = ("1\r\na\r\n" * chunks) + "0\r\n\r\n"

                    Clock.withTimeControl { tc =>
                        Clock.use { clock =>
                            if bodyWithHead then sendRequest(inbound, head + body)
                            else
                                sendRequest(inbound, head)
                                sendRequest(inbound, body)
                            end if
                            UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)
                            // The decoder has filled its output and parked; nothing of the body is left on the connection.
                            pollUntil(inbound.size().contains(0) && inbound.pendingTakes().contains(0)).map { parked =>
                                assert(parked, s"the body must be in hand, size ${inbound.size()} takes ${inbound.pendingTakes()}")
                                tc.advance(idleTimeout).andThen {
                                    tc.advance(idleTimeout).andThen {
                                        assert(!inbound.closed(), "a body already in hand is the handler's wait, not the peer's: no close")
                                        gate.release.andThen {
                                            collectResponse(outbound).map { response =>
                                                assert(
                                                    response.endsWith(s"got $chunks"),
                                                    s"the handler must read the whole body, got: $response"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            "a chunked body that arrived whole with its head is not closed while a slow handler reads it" in wholeBodyInOneRead(true)

            "a chunked body that arrived whole in one read after its head is not closed while a slow handler reads it" in
                wholeBodyInOneRead(false)

            "a handler slow to read a body the peer has fully sent is not closed" in {
                // The chunked decoder runs ahead of the handler into a bounded channel; once that is full, the peer's remaining
                // chunks wait on the connection. Bytes waiting to be read are the peer's progress, not the peer's silence.
                Latch.init(1).map { gate =>
                    val route   = HttpRoute.postRaw("upload").request(_.bodyStream).response(_.bodyText)
                    val handler = route.handler { req =>
                        gate.await.andThen {
                            Abort.run[HttpException](req.fields.body.run).map {
                                case Result.Success(spans) => HttpResponse.ok(s"got ${spans.foldLeft(0)(_ + _.size)}")
                                case other                 => HttpResponse.ok(s"failed $other")
                            }
                        }
                    }
                    val router = buildRouter(Seq(handler), Absent)

                    val inbound  = Channel.Unsafe.init[Span[Byte]](64)
                    val outbound = Channel.Unsafe.init[Span[Byte]](64)

                    val idleTimeout = 200.millis
                    val config      = defaultConfig.idleTimeout(idleTimeout)
                    val chunks      = 40

                    Clock.withTimeControl { tc =>
                        Clock.use { clock =>
                            sendRequest(inbound, "POST /upload HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n")
                            UnsafeServerDispatch.serve(router, inbound, outbound, config, clock = clock)
                            (0 until chunks).foreach(_ => sendRequest(inbound, "1\r\na\r\n"))
                            sendRequest(inbound, "0\r\n\r\n")

                            // The decoder stops taking once its output is full and the handler has not started reading.
                            pollUntil(inbound.size().exists(n => n > 0 && n < chunks) && inbound.pendingTakes().contains(0)).map { parked =>
                                assert(parked, s"the peer's remaining chunks must be waiting on the connection, size ${inbound.size()}")
                                tc.advance(idleTimeout).andThen {
                                    tc.advance(idleTimeout).andThen {
                                        assert(!inbound.closed(), "bytes waiting on the connection are the peer's progress: no close")
                                        gate.release.andThen {
                                            collectResponse(outbound).map { response =>
                                                assert(
                                                    response.endsWith(s"got $chunks"),
                                                    s"the handler must read the whole body, got: $response"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "hoistedClosureSameDispatch" in {
            // Two distinct routes, dispatched in sequence on a keep-alive connection using the hoisted
            // restartParserFn closure. Verifies no stale capture: request B gets handler B's response,
            // not handler A's.
            val handlerA = HttpHandler.getText("pathA")(_ => "response-A")
            val handlerB = HttpHandler.getText("pathB")(_ => "response-B")
            val router   = buildRouter(Seq(handlerA, handlerB), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)

            // Both requests are keep-alive by default in HTTP/1.1; second closes the connection.
            val requestA = "GET /pathA HTTP/1.1\r\nHost: localhost\r\n\r\n"
            val requestB = "GET /pathB HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            sendRequest(inbound, requestA)
            sendRequest(inbound, requestB)

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { responseA =>
                assert(responseA.contains("HTTP/1.1 200 OK"), s"Request A expected 200, got: $responseA")
                assert(
                    responseA.contains("response-A"),
                    s"Request A expected body 'response-A' (no stale closure), got: $responseA"
                )
                collectResponse(outbound).map { responseB =>
                    assert(responseB.contains("HTTP/1.1 200 OK"), s"Request B expected 200, got: $responseB")
                    assert(
                        responseB.contains("response-B"),
                        s"Request B expected body 'response-B' (no stale closure), got: $responseB"
                    )
                    assert(
                        !responseB.contains("response-A"),
                        s"Request B must not contain response-A body (stale capture), got: $responseB"
                    )
                }
            }
        }

        "closedSingletonNormalOnly" in {
            // IdleTimerClosed is the shared singleton for the normal idle-timer cancel path.
            // It must have referential identity and carry an empty details (no error context).
            val singleton = UnsafeServerDispatch.IdleTimerClosed

            assert(
                singleton eq UnsafeServerDispatch.IdleTimerClosed,
                "IdleTimerClosed must be a singleton (referential equality on repeated access)"
            )

            assert(
                singleton.getMessage.contains("idle timer"),
                s"IdleTimerClosed must mention 'idle timer', got: ${singleton.getMessage}"
            )

            // A fresh Closed (error path) is distinct from the singleton.
            val errorClosed = new Closed("idle timer", Frame.internal, "connection error")(using Frame.internal)
            assert(
                !(singleton eq errorClosed),
                "Error-path Closed must be a distinct object from IdleTimerClosed singleton"
            )
            assert(
                errorClosed.getMessage.contains("connection error"),
                s"Error-path Closed must carry its details in getMessage, got: ${errorClosed.getMessage}"
            )
        }
    }

    "ConnectionClose" - {

        "handler parked on a foreign await is interrupted when the connection closes" in {
            Latch.initWith(1) { started =>
                Latch.initWith(1) { terminated =>
                    val handler = HttpHandler.getText("park") { _ =>
                        // Parks on a promise no one completes: touches neither inbound nor outbound.
                        Fiber.Promise.init[Unit, Any].map { never =>
                            Sync.ensure(terminated.release) {
                                started.release.andThen(never.get).andThen("unreachable")
                            }
                        }
                    }
                    val router = buildRouter(Seq(handler), Absent)

                    val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                    val outbound = Channel.Unsafe.init[Span[Byte]](16)
                    // The connection's close signal on this bare-channel path (kyo.net.Connection.onClosing in production).
                    val closing = Promise.Unsafe.init[Unit, Any]()
                    sendRequest(inbound, "GET /park HTTP/1.1\r\nHost: localhost\r\n\r\n")

                    UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, Present(closing))

                    started.await.andThen {
                        // Fire the connection-close signal, as closeFn does on a real connection.
                        closing.completeDiscard(Result.succeed(()))
                        Async.timeout(5.seconds)(terminated.await).andThen(succeed)
                    }
                }
            }
        }

        "negative guard: a healthy handler is not interrupted by the watcher" in {
            Latch.initWith(1) { started =>
                Latch.initWith(1) { terminated =>
                    Fiber.Promise.init[Unit, Any].map { never =>
                        val handler = HttpHandler.getText("park") { _ =>
                            Sync.ensure(terminated.release) {
                                started.release.andThen(never.get).andThen("completed-normally")
                            }
                        }
                        val router = buildRouter(Seq(handler), Absent)

                        val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                        val outbound = Channel.Unsafe.init[Span[Byte]](16)
                        // Watcher IS armed (Present) but the close signal never fires: proves the watcher does not
                        // spuriously interrupt a handler on a healthy, still-open connection.
                        val closing = Promise.Unsafe.init[Unit, Any]()
                        sendRequest(inbound, "GET /park HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")

                        UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, Present(closing))

                        started.await.andThen {
                            // The connection stays open (closing never completed): the handler completes
                            // normally, and the watcher must never have interrupted it.
                            never.complete(Result.succeed(())).andThen {
                                Async.timeout(5.seconds)(terminated.await).andThen {
                                    collectResponse(outbound).map { response =>
                                        assert(
                                            response.contains("HTTP/1.1 200 OK") && response.contains("completed-normally"),
                                            s"Expected 200 OK with a normal completion body from the un-interrupted handler, got: $response"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a request head over the limit (RFC 6585 section 5, RFC 9110 section 15.5.15)" - {

        val smallHead = HttpServerConfig.default.transportConfig(HttpTransportConfig.default.maxHeaderSize(HttpTransportConfig.Size(256)))
        val router    = buildRouter(Seq(HttpHandler.getText("hello")(_ => "world")), Absent)

        /** Everything the dispatch has written so far, without waiting. A refusal of the head is written inside the parser's callback, before
          * the offer that carried the last read returns, so nothing here suspends.
          */
        def written(outbound: Channel.Unsafe[Span[Byte]]): String =
            val sb            = new StringBuilder
            def drain(): Unit =
                outbound.poll() match
                    case Result.Success(Present(span)) =>
                        sb.append(new String(span.toArray, StandardCharsets.ISO_8859_1))
                        drain()
                    case _ => ()
            drain()
            sb.toString
        end written

        def serveOne(request: String): (String, Boolean) =
            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)
            sendRequest(inbound, request)
            UnsafeServerDispatch.serve(router, inbound, outbound, smallHead)
            (written(outbound), inbound.closed())
        end serveOne

        /** The whole answer written for a refused head, with its Date line removed: the status line, the three headers in order, and
          * the JSON body for the status.
          */
        def refusal(status: HttpStatus): String =
            val body = new String(RouteUtil.encodeErrorBody(status).toArray, StandardCharsets.ISO_8859_1)
            s"HTTP/1.1 ${status.code} ${Http1StreamContext.reasonPhrase(status)}\r\n" +
                s"Content-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        end refusal
        def withoutDate(answer: String): String = answer.replaceFirst("Date: [^\r]*\r\n", "")

        // A refused request is answered while its peer may still be sending the rest of it; a close at that instant is a reset that can
        // discard the answer. So the rest is read and discarded first, bounded by the idle timer for a peer that stops and by
        // lingeringTimeout for one that does not, and the connection closes after.
        def answeredThenDrained(request: String, expected: HttpStatus)(
            afterAnswer: (Clock.TimeControl, Channel.Unsafe[Span[Byte]], CloseProbe) => Unit < (Async & Abort[Any])
        )(using kyo.test.AssertScope) =
            val config = smallHead.idleTimeout(200.millis).lingeringTimeout(500.millis)
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val inbound  = Channel.Unsafe.init[Span[Byte]](64)
                    val outbound = Channel.Unsafe.init[Span[Byte]](64)
                    val probe    = CloseProbe(inbound)
                    sendRequest(inbound, request)
                    UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                    val answer = written(outbound)
                    assert(withoutDate(answer) == refusal(expected), s"observed: $answer")
                    assert(!inbound.closed(), "the answer must reach a peer still sending before the connection closes")
                    // The drain runs on its own fiber; its bound starts once it waits on the connection, so time moves only after that.
                    pollUntil(inbound.pendingTakes().contains(1)).map { draining =>
                        assert(draining, "the drain must be waiting on the connection")
                        afterAnswer(tc, inbound, probe)
                    }
                }
            }
        end answeredThenDrained

        def drainedUntilTheBound(tc: Clock.TimeControl, inbound: Channel.Unsafe[Span[Byte]], probe: CloseProbe)(using
            kyo.test.AssertScope
        ) =
            def trickle(): Unit < (Async & Abort[Any]) =
                tc.advance(150.millis).andThen {
                    sendRequest(inbound, "more of what the peer was sending")
                    pollUntil(inbound.size().contains(0) && inbound.pendingTakes().contains(1)).map { drained =>
                        assert(drained, "what the peer keeps sending is read and discarded")
                    }
                }
            trickle().andThen(trickle()).andThen(trickle()).andThen {
                assert(!inbound.closed(), "within the lingering bound a sending peer keeps the connection")
                tc.advance(150.millis).andThen {
                    probe.closed.map(closed => assert(closed, "the lingering bound closes a peer that keeps sending"))
                }
            }
        end drainedUntilTheBound

        def closedWhenSilent(tc: Clock.TimeControl, inbound: Channel.Unsafe[Span[Byte]], probe: CloseProbe)(using kyo.test.AssertScope) =
            tc.advance(199.millis).andThen {
                assert(!inbound.closed(), "the connection stays open until the idle timeout elapses")
                tc.advance(1.milli).andThen {
                    probe.closed.map(closed => assert(closed, "a peer that stops sending is closed at the idle timeout"))
                }
            }

        "an oversized head is answered 431 with Connection: close while the peer still sends, the rest is drained, and the bound closes" in
            answeredThenDrained(
                "GET /hello HTTP/1.1\r\nHost: localhost\r\nX-Big: " + "x" * 300 + "\r\n\r\n",
                HttpStatus.RequestHeaderFieldsTooLarge
            )(
                drainedUntilTheBound
            )

        "a request line alone longer than the limit is answered 414 with Connection: close, and a silent peer is closed at the idle timeout" in
            answeredThenDrained("GET /" + "a" * 300 + " HTTP/1.1\r\nHost: localhost\r\n\r\n", HttpStatus.URITooLong)(closedWhenSilent)

        "a Content-Length over the limit is answered 413 with Connection: close while the body still arrives, drained, and the bound closes" in
            answeredThenDrained(
                "POST /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100000\r\n\r\npart of the body",
                HttpStatus.PayloadTooLarge
            )(
                drainedUntilTheBound
            )

        "a request without a Host header but with a body is answered 400 with Connection: close, drained, and the bound closes" in
            answeredThenDrained("GET /hello HTTP/1.1\r\nContent-Length: 20\r\n\r\npart", HttpStatus.BadRequest)(drainedUntilTheBound)

        "a head that never ends is answered once the limit is passed, not when the peer stops sending" in {
            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)
            UnsafeServerDispatch.serve(router, inbound, outbound, smallHead)
            // 30 bytes of request line and Host, then 50-byte header lines with no blank line: the fifth line carries the head past 256.
            sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: h\r\n")
            val line = "X-" + "a" * 40 + ": bbbb\r\n"
            assert(line.length == 50)
            (1 to 4).foreach(_ => sendRequest(inbound, line))
            assert(written(outbound) == "", "no answer is owed while the head is within the limit")
            assert(!inbound.closed())
            sendRequest(inbound, line)
            val answer = written(outbound)
            assert(withoutDate(answer) == refusal(HttpStatus.RequestHeaderFieldsTooLarge), s"observed: $answer")
            assert(!inbound.closed(), "the answer is written before the connection ends; the peer is still sending its head")
        }

        "a head within the limit in a read larger than the limit is served" in {
            val inbound  = Channel.Unsafe.init[Span[Byte]](64)
            val outbound = Channel.Unsafe.init[Span[Byte]](64)
            val body     = "b" * 1000
            sendRequest(
                inbound,
                s"POST /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body"
            )
            UnsafeServerDispatch.serve(router, inbound, outbound, smallHead)
            collectResponse(outbound).map { response =>
                assert(response.startsWith("HTTP/1.1 405 Method Not Allowed"), s"observed: $response")
            }
        }
    }

    // A streamed chunked body is decoded by a fiber of its own while the handler runs. When the handler settles before the decoder reaches the
    // terminal chunk, or the decoder refuses the framing, the body's remaining bytes are still on the connection, and a keep-alive restart
    // would read them as the next request (RFC 9112 section 9.3, the unconsumed-body class). The handler, for its part, must not take a body
    // that ended early for a complete one (RFC 9112 section 8).
    "a streamed chunked request body that is not fully decoded (RFC 9112 sections 8 and 9.3)" - {

        val sinkRoute  = HttpRoute.postRaw("sink").request(_.bodyStream).response(_.bodyText)
        val sink       = sinkRoute.handler(_ => HttpResponse.ok("sunk"))
        val drainRoute = HttpRoute.postRaw("drain").request(_.bodyStream).response(_.bodyText)
        val drain      = drainRoute.handler(req => req.fields.body.run.map(spans => HttpResponse.ok(spans.map(_.size).sum.toString)))
        val hello      = HttpHandler.getText("hello")(_ => "world")
        def chunkedHead(path: String) = s"POST /$path HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n"

        def serveWith(config: HttpServerConfig, clock: Clock)(handlers: HttpHandler[?, ?, ?]*)
            : (Channel.Unsafe[Span[Byte]], Channel.Unsafe[Span[Byte]], CloseProbe) =
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)
            val probe    = CloseProbe(inbound)
            UnsafeServerDispatch.serve(
                buildRouter(handlers, Absent),
                inbound,
                outbound,
                config,
                closeConnection = probe.hook,
                clock = clock
            )
            (inbound, outbound, probe)
        end serveWith

        "a body decoded to its terminal chunk keeps the connection alive, and the bytes after it are the next request" in {
            Clock.withTimeControl { _ =>
                Clock.use { clock =>
                    val (inbound, outbound, _) = serveWith(defaultConfig, clock)(drain, hello)
                    sendRequest(inbound, chunkedHead("drain") + "5\r\nhello\r\n0\r\n\r\n" + "GET /hello HTTP/1.1\r\nHost: h\r\n\r\n")
                    collectResponse(outbound).map { first =>
                        assert(first.startsWith("HTTP/1.1 200 OK") && first.endsWith("\r\n\r\n5"), s"observed: $first")
                        collectResponse(outbound).map { second =>
                            assert(second.endsWith("world"), s"observed: $second")
                            assert(!inbound.closed())
                        }
                    }
                }
            }
        }

        // The body still owed is drained, not reparsed: the connection is never restarted, and it closes at the peer's EOF or once the
        // idle timeout passes with nothing more arriving.
        "a handler that answers without reading the body: the connection is closed after the answer, not restarted" in {
            val idleTimeout = 200.millis
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val (inbound, outbound, probe) = serveWith(defaultConfig.idleTimeout(idleTimeout), clock)(sink, hello)
                    sendRequest(inbound, chunkedHead("sink") + "5\r\nhello\r\n")
                    collectResponse(outbound).map { response =>
                        assert(response.startsWith("HTTP/1.1 200 OK"), s"observed: $response")
                        sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: h\r\n\r\n")
                        pollUntil(inbound.size().contains(0)).map { drained =>
                            assert(drained, "bytes after the answer are the body still owed, read and discarded")
                            // The drained bytes were progress inside the first window; the second window sees none.
                            tc.advance(idleTimeout).andThen(tc.advance(idleTimeout)).andThen {
                                probe.closed.map { closed =>
                                    assert(closed, "the connection must be closed once the handler settles with the body still undecoded")
                                    assert(
                                        outbound.size().contains(0) || outbound.closed(),
                                        "nothing after the body is parsed as a request"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // The handler settles before any of its body arrives, so the decode cannot have reached the terminal chunk: the body and the GET
        // pipelined behind it are the rest of a request the server will not use, read and discarded, and the GET is never answered.
        "a handler that answers before its body arrives: the body and a GET pipelined behind it are drained, and the connection is closed" in {
            val idleTimeout = 200.millis
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val (inbound, outbound, probe) = serveWith(defaultConfig.idleTimeout(idleTimeout), clock)(sink, hello)
                    sendRequest(inbound, chunkedHead("sink"))
                    collectResponse(outbound).map { first =>
                        assert(first.startsWith("HTTP/1.1 200 OK") && first.endsWith("sunk"), s"observed: $first")
                        sendRequest(inbound, "5\r\nhello\r\n0\r\n\r\n" + "GET /hello HTTP/1.1\r\nHost: h\r\n\r\n")
                        pollUntil(inbound.size().contains(0)).map { drained =>
                            assert(drained, "the bytes behind the answer are read and discarded")
                            tc.advance(idleTimeout).andThen(tc.advance(idleTimeout)).andThen {
                                probe.closed.map { closed =>
                                    assert(closed, "the connection must be closed once the peer stops sending")
                                    assert(outbound.size().contains(0) || outbound.closed(), "the GET behind the body is never answered")
                                }
                            }
                        }
                    }
                }
            }
        }

        // A refused body ends the connection, and the peer may still be sending it: the answer announces the close, the rest of what
        // arrives is read and discarded so the answer is not lost to a reset, and the idle timer closes a peer that goes silent.
        def refusedStreamedBody(body: String, status: String)(using kyo.test.AssertScope) =
            val idleTimeout = 200.millis
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val (inbound, outbound, probe) = serveWith(defaultConfig.idleTimeout(idleTimeout), clock)(drain, hello)
                    sendRequest(inbound, chunkedHead("drain") + body)
                    collectResponse(outbound).map { response =>
                        assert(response.startsWith(status), s"observed: $response")
                        assert(
                            response.toLowerCase.contains("connection: close"),
                            s"the answer must announce the close, observed: $response"
                        )
                        sendRequest(inbound, "more of the body the peer was still sending")
                        pollUntil(inbound.size().contains(0) && inbound.pendingTakes().contains(1)).map { drained =>
                            assert(drained, "what the peer keeps sending is read and discarded")
                            assert(!inbound.closed(), "the connection stays open while the peer's body still arrives")
                            tc.advance(idleTimeout).andThen(tc.advance(idleTimeout)).andThen {
                                probe.closed.map { closed =>
                                    assert(closed, "the connection must be closed once the peer's body stops arriving")
                                }
                            }
                        }
                    }
                }
            }
        end refusedStreamedBody

        "a malformed chunk is answered 400 with Connection: close, the rest of the body is drained, and the connection is closed" in
            refusedStreamedBody("5\r\nhello\r\nZZ\r\n", "HTTP/1.1 400 Bad Request")

        "a chunk size line over the control-plane limit is answered 413 with Connection: close, drained, and closed" in
            refusedStreamedBody("F" * (defaultConfig.maxContentLength + 1), "HTTP/1.1 413 Payload Too Large")

        /** A streaming route that records whether its handler started, finished, or was interrupted, and opens `firstRead` after the first
          * span of its body.
          */
        class Observed(firstRead: Latch):
            val started     = AtomicBoolean.Unsafe.init(false)
            val finished    = AtomicBoolean.Unsafe.init(false)
            val interrupted = AtomicBoolean.Unsafe.init(false)
            val route       = HttpRoute.postRaw("observe").request(_.bodyStream).response(_.bodyText)
            val handler     = route.handler { req =>
                Sync.Unsafe.defer(started.set(true)).andThen {
                    Sync.ensure(Sync.Unsafe.defer(if !finished.get() then interrupted.set(true))) {
                        req.fields.body.foreach(_ => firstRead.release).map { _ =>
                            Sync.Unsafe.defer(finished.set(true)).andThen(HttpResponse.ok("done"))
                        }
                    }
                }
            }
        end Observed

        // With the head, a partial chunk and the close in one segment, the connection is closing before the request is dispatched: the
        // handler never runs, no response is written for the request, and the connection is closed, so nothing pipelined behind the
        // request is answered either.
        "a connection already closing when the request is dispatched: the handler never runs, no response is written, the connection is closed" in {
            Latch.init(1).map { firstRead =>
                val observed = new Observed(firstRead)
                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)
                val closing  = Fiber.Promise.Unsafe.init[Unit, Any]()
                val probe    = CloseProbe(inbound)
                closing.completeDiscard(Result.succeed(()))
                UnsafeServerDispatch.serve(
                    buildRouter(Seq(observed.handler, hello), Absent),
                    inbound,
                    outbound,
                    defaultConfig,
                    onClosing = Present(closing),
                    closeConnection = probe.hook
                )
                sendRequest(inbound, chunkedHead("observe") + "5\r\nhel")
                sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: h\r\n\r\n")
                probe.closed.map { closed =>
                    assert(closed, "a request dispatched on a closing connection ends the connection")
                    assert(!observed.started.get(), "the handler must not run against a peer that is gone")
                    assert(outbound.size().getOrElse(-1) == 0, "no response is written on a closing connection")
                }
            }
        }

        // The handler has read part of the body when the peer closes: the close watcher interrupts it, no response is written, and the
        // dispatch closes the connection, since the body was not decoded to its end. Nothing follows the partial chunk on the wire: bytes
        // sent behind an unfinished chunk are that chunk's data to the decoder, and a peer that closes mid-body has nothing framed behind it.
        "a peer that closes while the handler is reading the body: the handler is interrupted, no response is written, the connection is closed" in {
            Latch.init(1).map { firstRead =>
                val observed = new Observed(firstRead)
                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val outbound = Channel.Unsafe.init[Span[Byte]](16)
                val closing  = Fiber.Promise.Unsafe.init[Unit, Any]()
                val probe    = CloseProbe(inbound)
                UnsafeServerDispatch.serve(
                    buildRouter(Seq(observed.handler, hello), Absent),
                    inbound,
                    outbound,
                    defaultConfig,
                    onClosing = Present(closing),
                    closeConnection = probe.hook
                )
                sendRequest(inbound, chunkedHead("observe") + "5\r\nhel")
                firstRead.await.andThen {
                    closing.completeDiscard(Result.succeed(()))
                    probe.closed.map { closed =>
                        assert(closed, "the connection must be closed once the handler is interrupted with the body undecoded")
                        assert(observed.interrupted.get(), "the handler must be interrupted")
                        assert(!observed.finished.get(), "the handler must not complete")
                        assert(outbound.size().getOrElse(-1) == 0, "no response is written for the interrupted request")
                    }
                }
            }
        }

        "the body stream of a peer that closes mid-body fails with HttpConnectionClosedException after the bytes that arrived" in {
            Fiber.Promise.init[(Int, String), Any].map { outcome =>
                AtomicInt.init.map { seen =>
                    val observeRoute = HttpRoute.postRaw("observe").request(_.bodyStream).response(_.bodyText)
                    val observe      = observeRoute.handler { req =>
                        Abort.run[HttpException](req.fields.body.foreach(span => seen.addAndGet(span.size).unit)).map { result =>
                            val label = result match
                                case Result.Success(_) => "complete"
                                case Result.Failure(e) => e.getClass.getSimpleName
                                case Result.Panic(e)   => s"panic ${e.getClass.getSimpleName}"
                            seen.get.map(n => outcome.complete(Result.succeed((n, label))).andThen(HttpResponse.ok("observed")))
                        }
                    }
                    Clock.withTimeControl { _ =>
                        Clock.use { clock =>
                            val (inbound, _, _) = serveWith(defaultConfig, clock)(observe)
                            sendRequest(inbound, chunkedHead("observe") + "5\r\nhel")
                            discard(inbound.close())
                            outcome.get.map { case (bytes, label) =>
                                assert(bytes == 3, s"the handler must keep the bytes that arrived, observed $bytes")
                                assert(label == "HttpConnectionClosedException", s"observed: $label")
                            }
                        }
                    }
                }
            }
        }
    }

    // A request the dispatch answers with no handler (a missing route, a wrong method) is answered inside the parser's callback, and the
    // parser is restarted from there. Each pipelined request must cost a bounded stack, and the answers must wait for the peer to read
    // them instead of queuing without bound behind a full outbound channel.
    "pipelined requests answered without a handler" - {

        val router  = buildRouter(Seq(HttpHandler.getText("hello")(_ => "world")), Absent)
        val missing = "GET /missing HTTP/1.1\r\nHost: h\r\n\r\n"

        /** Takes from `outbound` until `n` answers have gone by. Every answer's head is offered as one span, so a span that starts a status
          * line is one answer.
          */
        def countAnswers(outbound: Channel.Unsafe[Span[Byte]], n: Int)(using Frame): Int < Async =
            Abort.run[Closed] {
                Loop(0) { count =>
                    if count >= n then Loop.done(count)
                    else
                        outbound.safe.take.map { span =>
                            val head = span.size >= 12 && new String(span.toArrayUnsafe, 0, 12, StandardCharsets.US_ASCII) == "HTTP/1.1 404"
                            Loop.continue(if head then count + 1 else count)
                        }
                }
            }.map(_.getOrElse(-1))

        // A declared body that arrived with its head is received in full: nothing is left on the wire to strand or to drain, so the
        // answer keeps the connection alive, and a close needs no drain. A chunked body is not decoded on this path, so it is drained.
        "a keep-alive request to no route whose declared body arrived with its head is answered without a close, and the request behind the body is answered next" in {
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)
            sendRequest(
                inbound,
                "POST /missing HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhelloGET /hello HTTP/1.1\r\nHost: h\r\n\r\n"
            )
            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)
            collectResponse(outbound).map { first =>
                assert(first.startsWith("HTTP/1.1 404 Not Found"), s"observed: $first")
                assert(
                    !first.toLowerCase.contains("connection: close"),
                    s"a body received in full leaves nothing to strand, observed: $first"
                )
                collectResponse(outbound).map { second =>
                    assert(second.startsWith("HTTP/1.1 200 OK") && second.endsWith("world"), s"observed: $second")
                    assert(!inbound.closed(), "the connection stays open for the next request")
                }
            }
        }

        "a Connection: close request to no route whose declared body arrived with its head is closed at once: nothing is left to drain" in {
            Clock.withTimeControl { _ =>
                Clock.use { clock =>
                    val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                    val outbound = Channel.Unsafe.init[Span[Byte]](16)
                    sendRequest(inbound, "POST /missing HTTP/1.1\r\nHost: h\r\nConnection: close\r\nContent-Length: 5\r\n\r\nhello")
                    val probe = CloseProbe(inbound)
                    UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig, closeConnection = probe.hook, clock = clock)
                    collectResponse(outbound).map { answer =>
                        assert(answer.startsWith("HTTP/1.1 404 Not Found"), s"observed: $answer")
                        assert(answer.toLowerCase.contains("connection: close"), s"observed: $answer")
                        probe.closed.map(closed => assert(closed, "with the body in hand the close waits for nothing"))
                    }
                }
            }
        }

        "a keep-alive request to no route with a chunked body in hand is answered with a close and drained: the terminal chunk is not looked for" in {
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                    val outbound = Channel.Unsafe.init[Span[Byte]](16)
                    val config   = defaultConfig.idleTimeout(200.millis).lingeringTimeout(500.millis)
                    sendRequest(inbound, "POST /missing HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n")
                    val probe = CloseProbe(inbound)
                    UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                    collectResponse(outbound).map { answer =>
                        assert(answer.startsWith("HTTP/1.1 404 Not Found"), s"observed: $answer")
                        assert(answer.toLowerCase.contains("connection: close"), s"observed: $answer")
                        assert(!inbound.closed(), "the drain waits for the peer before the close")
                        pollUntil(inbound.pendingTakes().contains(1)).map { draining =>
                            assert(draining, "the drain must be waiting on the connection")
                            tc.advance(200.millis).andThen {
                                probe.closed.map(closed => assert(closed, "a silent peer is closed at the idle timeout"))
                            }
                        }
                    }
                }
            }
        }

        "50,000 pipelined requests to no route in one read are each answered" in {
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)
            val n        = 50000
            Fiber.initUnscoped(countAnswers(outbound, n)).map { counting =>
                sendRequest(inbound, missing * n)
                UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)
                counting.get.map(count => assert(count == n, s"observed $count answers"))
            }
        }

        "answers a peer does not read stop the parsing at the outbound channel's capacity, and it resumes as the peer reads" in {
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](4)
            sendRequest(inbound, missing * 50)
            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)
            // An answer is two spans, its head and its body. Two answers fill the channel, the third's spans wait as pending puts, and
            // the fourth request is not parsed until the peer takes.
            assert(outbound.size().getOrThrow == 4, s"observed ${outbound.size()} spans in the channel")
            assert(outbound.pendingPuts().getOrThrow == 2, s"observed ${outbound.pendingPuts()} spans queued behind the full channel")
            countAnswers(outbound, 50).map(count => assert(count == 50, s"observed $count answers"))
        }

        // Waiting for the peer to take an answer is a wait on the peer: the idle timer covers it, so a peer that never reads does not hold
        // the connection for ever behind its full outbound channel.
        "a peer that never reads the answers to its pipelined requests is closed after the idle timeout" in {
            val inbound     = Channel.Unsafe.init[Span[Byte]](16)
            val outbound    = Channel.Unsafe.init[Span[Byte]](4)
            val idleTimeout = 200.millis
            val config      = defaultConfig.idleTimeout(idleTimeout)
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    sendRequest(inbound, missing * 50)
                    val probe = CloseProbe(inbound)
                    UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                    pollUntil(outbound.pendingPuts().contains(2)).map { parked =>
                        assert(parked, s"the parser must be waiting for the peer to read, observed ${outbound.pendingPuts()} queued spans")
                        tc.advance(idleTimeout.minusOrZero(1.milli)).andThen {
                            assert(!inbound.closed(), "the connection stays open until the idle timeout elapses")
                            tc.advance(1.milli).andThen {
                                probe.closed.map(closed => assert(closed, "a peer that never reads its answers must be closed"))
                            }
                        }
                    }
                }
            }
        }

        "a peer that never reads the responses of its pipelined handler requests is closed after the idle timeout" in {
            val inbound     = Channel.Unsafe.init[Span[Byte]](16)
            val outbound    = Channel.Unsafe.init[Span[Byte]](4)
            val idleTimeout = 200.millis
            val config      = defaultConfig.idleTimeout(idleTimeout)
            Clock.withTimeControl { tc =>
                Clock.use { clock =>
                    sendRequest(inbound, "GET /hello HTTP/1.1\r\nHost: h\r\n\r\n" * 6)
                    val probe = CloseProbe(inbound)
                    UnsafeServerDispatch.serve(router, inbound, outbound, config, closeConnection = probe.hook, clock = clock)
                    pollUntil(outbound.pendingPuts().exists(_ > 0)).map { parked =>
                        assert(parked, s"a response must be waiting for the peer to read, observed ${outbound.pendingPuts()} queued spans")
                        tc.advance(idleTimeout.minusOrZero(1.milli)).andThen {
                            assert(!inbound.closed(), "the connection stays open until the idle timeout elapses")
                            tc.advance(1.milli).andThen {
                                probe.closed.map(closed => assert(closed, "a peer that never reads its responses must be closed"))
                            }
                        }
                    }
                }
            }
        }
    }

end UnsafeServerDispatchTest
