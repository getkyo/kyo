package kyo.internal

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
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

    /** Waits until the keep-alive idle timer for the next idle period is armed.
      *
      * `restartParserKeepAlive` arms the timer then restarts the parser, so a parser take on inbound is proof the
      * timer exists. Tests need this before advancing virtual time against the deadline: the already-collected
      * response was written while the handler ran and says nothing about the arm.
      */
    private def awaitIdleTimerArmed(inbound: Channel.Unsafe[Span[Byte]])(using Frame): Boolean < Async =
        pollUntil(inbound.pendingTakes().contains(1))

    private val defaultConfig = HttpServerConfig.default

    "UnsafeServerDispatch" - {

        "dispatch GET request returns 200" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = HttpRouter(Seq(handler), Absent)

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

        "dispatch returns 404 for unknown path" in {
            val handler = HttpHandler.getText("hello")(_ => "world")
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.getRaw("users" / HttpPath.Capture[String]("id")).response(_.bodyText)
            val handler = route.handler { req =>
                val userId = req.fields.id
                HttpResponse.ok(userId)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                val body = req.fields.body
                HttpResponse.ok(body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request = "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))
            // Pipelined follow-up as its own span, so the parser cannot have buffered it with the first request.
            // Consumed only if the parser restarts, which Connection: close forbids.
            val followUp = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(followUp.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, defaultConfig)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 200 OK"), s"Expected 200 OK, got: $response")
                // Connection: close registers no keep-alive hook, so once the response is out nothing runs again:
                // the follow-up span stays queued and unread, nothing more is written.
                assert(
                    inbound.size().contains(1),
                    s"the follow-up request must stay unread after Connection: close, queued=${inbound.size()}"
                )
                outbound.poll() match
                    case Result.Success(Present(span)) =>
                        fail(s"Expected no more data after Connection: close, but got: ${new String(span.toArray)}")
                    case _ =>
                        succeed
                end match
            }
        }

        "dispatch error in handler returns 500" in {
            val handler = HttpHandler.getRaw[Nothing]("fail") { _ =>
                throw new RuntimeException("handler exploded")
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val postRoute = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val postHandler = postRoute.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val getHandler = HttpHandler.getText("echo")(_ => "get-ok")
            val router     = HttpRouter(Seq(postHandler, getHandler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                val body = req.fields.body
                // Empty body should produce empty string
                HttpResponse.ok(s"len=${body.length}")
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(s"size=${req.fields.body.length}")
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val body = "continued body"
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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val route  = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val served = new AtomicBoolean(false)
            val handler = route.handler { req =>
                discard(served.set(true))
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

            val config   = defaultConfig.maxContentLength(10)
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            val request =
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 100\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "5\r\nhello\r\n0\r\n\r\n"
            discard(inbound.offer(Span.fromUnsafe(request.getBytes(StandardCharsets.US_ASCII))))

            UnsafeServerDispatch.serve(router, inbound, outbound, config)

            collectResponse(outbound).map { response =>
                assert(response.contains("HTTP/1.1 400 Bad Request"), s"Expected 400 for the CL+TE conflict, got: $response")
                assert(response.contains("Connection: close"), s"the 400 must announce the close (RFC 9112 section 9.6), got: $response")
                assert(!response.contains("413"), s"the request must be refused on framing, not on the Content-Length cap: $response")
                assert(!served.get(), "a request with two candidate framings must never reach the handler")
                assert(inbound.closed(), "the connection must be torn down after an unframeable request")
            }
        }

        "chunked body exceeding max returns 413" in {
            // A chunked body on a buffered route is dechunked bounded by maxContentLength; a body decoding to more
            // than the limit is answered 413, not buffered without limit (CWE-400, RFC 9112 section 6.1).
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

            val config   = defaultConfig.maxContentLength(10)
            val inbound  = Channel.Unsafe.init[Span[Byte]](16)
            val outbound = Channel.Unsafe.init[Span[Byte]](16)

            // Send a chunked request whose decoded body (15 bytes) exceeds maxContentLength (10).
            // Chunk format: hex-size\r\ndata\r\n ... 0\r\n\r\n
            val chunk1 = "a\r\n0123456789\r\n" // 10 bytes (at limit)
            val chunk2 = "5\r\nABCDE\r\n"      // 5 more bytes (over limit)
            val end    = "0\r\n\r\n"
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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val sb = new StringBuilder
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
            val router  = HttpRouter(Seq(handler), Absent)

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

        "HttpWebSocket rejects frames exceeding configured maxFrameSize" in {
            Latch.initWith(1) { handlerDone =>
                val received = new AtomicBoolean(false)
                val config   = HttpWebSocket.Config(maxFrameSize = 4)
                val handler = HttpHandler.webSocket("ws", config) { (_, ws) =>
                    Abort.run[Closed](ws.take()).map {
                        case Result.Success(_) =>
                            discard(received.set(true))
                        case _ => ()
                    }.andThen(handlerDone.release)
                }
                val router = HttpRouter(Seq(handler), Absent)

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
            val router    = HttpRouter(Seq(handler), Absent)

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
                val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

            // Set up 3 independent connections, each with separate channel pairs
            val n = 3
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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(req.fields.body)
            }
            val router = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
                            tc.advance(idleTimeout - 1.milli).andThen {
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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val router  = HttpRouter(Seq(handler), Absent)

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
            val route = HttpRoute.getRaw("stream").response(_.bodyText)
            val handler = route.handler { _ =>
                HttpResponse.ok("streamed data")
            }
            val router = HttpRouter(Seq(handler), Absent)

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

        "hoistedClosureSameDispatch" in {
            // Two distinct routes, dispatched in sequence on a keep-alive connection using the hoisted
            // restartParserFn closure. Verifies no stale capture: request B gets handler B's response,
            // not handler A's.
            val handlerA = HttpHandler.getText("pathA")(_ => "response-A")
            val handlerB = HttpHandler.getText("pathB")(_ => "response-B")
            val router   = HttpRouter(Seq(handlerA, handlerB), Absent)

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
                    val router = HttpRouter(Seq(handler), Absent)

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
                        val router = HttpRouter(Seq(handler), Absent)

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

end UnsafeServerDispatchTest
