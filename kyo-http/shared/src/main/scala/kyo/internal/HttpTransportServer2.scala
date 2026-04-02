package kyo.internal

import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kyo.*

/** Stream-first HTTP server backend. Uses Transport2 + Http1Protocol2.
  *
  * One fiber per accepted connection. Keep-alive loop threads the remaining byte stream through each request-response cycle. WebSocket
  * upgrade supported via a buffered adapter from TransportStream2 to TransportStream.
  *
  * Scope management: bind() creates an internal Scope that owns the server socket and accept loop fiber. A Promise gates close/await.
  */
class HttpTransportServer2(private[kyo] val transport: Transport2)(using
    Tag[transport.Connection],
    Tag[Emit[Chunk[transport.Connection]]]
) extends HttpBackend.Server:

    private val Utf8 = StandardCharsets.UTF_8

    /** RFC 9110 §6.6.1: Date header in IMF-fixdate format. */
    private val httpDateFormat = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)

    private def withDate(headers: HttpHeaders): HttpHeaders =
        headers.add("Date", ZonedDateTime.now(ZoneOffset.UTC).format(httpDateFormat))

    /** Write response body with Date header added automatically. */
    private def writeResponse(conn: transport.Connection, status: HttpStatus, headers: HttpHeaders, body: HttpBody)(using
        Frame
    ): Unit < Async =
        Http1Protocol2.writeResponse(conn, status, withDate(headers), body)

    def bind(
        handlers: Seq[HttpHandler[?, ?, ?]],
        config: HttpServerConfig
    )(using Frame): HttpBackend.Binding < (Async & Scope) =
        val router = HttpRouter(handlers, config.cors)
        Promise.init[Unit, Any].map { done =>
            transport.listen(config.host, config.port, config.backlog, config.tls).map { listener =>
                Fiber.initUnscoped {
                    listener.connections.foreach { conn =>
                        Fiber.initUnscoped {
                            Sync.ensure(transport.closeNow(conn)) {
                                serveConnection(conn, router, config)
                            }
                        }.unit
                    }
                }.map { acceptFiber =>
                    new HttpBackend.Binding:
                        val port: Int    = listener.port
                        val host: String = listener.host
                        def close(gracePeriod: Duration)(using Frame): Unit < Async =
                            listener.close.andThen {
                                acceptFiber.interrupt.unit.andThen(done.completeDiscard(Result.succeed(())))
                            }
                        def await(using Frame): Unit < Async =
                            done.get.unit
                }
            }
        }
    end bind

    /** One connection, keep-alive loop. Threads the remaining stream through each request. */
    private def serveConnection(
        conn: transport.Connection,
        router: HttpRouter,
        config: HttpServerConfig
    )(using Frame): Unit < Async =
        Abort.run[HttpException] {
            Loop(conn.read) { stream =>
                Http1Protocol2.readRequestStreaming(stream, config.maxContentLength).map {
                    case ((method, rawPath, headers, body), remaining) =>
                        val path = rawPath.indexOf('?') match
                            case -1 => rawPath
                            case i  => rawPath.substring(0, i)
                        val routerResult = router.find(method, path)
                        routerResult match
                            case Result.Success(routeMatch) =>
                                routeMatch.endpoint match
                                    case wsHandler: WebSocketHttpHandler =>
                                        // Bridge remaining stream to TransportStream for WsCodec
                                        makeWsAdapter(conn, remaining).map { wsAdapter =>
                                            WsCodec.acceptUpgrade(wsAdapter, headers, wsHandler.wsConfig).andThen {
                                                serveWebSocket(wsAdapter, wsHandler, headers, path)
                                            }.andThen(Loop.done(()))
                                        }
                                    case _ =>
                                        // If the route is not streaming but we got a Streamed body (chunked transfer),
                                        // buffer it first so non-streaming routes receive the full body bytes.
                                        bufferBodyIfNeeded(body, routeMatch.isStreamingRequest, config.maxContentLength).map {
                                            bufferedBody =>
                                                serveHttpRequest(conn, routeMatch, method, rawPath, headers, bufferedBody, config).andThen {
                                                    if Http1Protocol2.isKeepAlive(headers) then Loop.continue(remaining)
                                                    else Loop.done(())
                                                }
                                        }
                            case Result.Failure(error) =>
                                writeRouterError(conn, error).andThen {
                                    error match
                                        case HttpRouter.FindError.Options(_) =>
                                            if Http1Protocol2.isKeepAlive(headers) then Loop.continue(remaining)
                                            else Loop.done(())
                                        case _ => Loop.done(())
                                }
                            case Result.Panic(_) =>
                                writeErrorResponse(conn, HttpStatus(500), HttpHeaders.empty).andThen(Loop.done(()))
                        end match
                }
            }
        }.map {
            case Result.Success(_) => Kyo.unit
            case Result.Failure(error) =>
                error match
                    case _: HttpConnectionClosedException =>
                        Kyo.unit
                    case _: HttpPayloadTooLargeException =>
                        Abort.run[Any](writeDecodeErrorResponse(conn, HttpStatus(413), error)).unit
                    case _ =>
                        Abort.run[Any](writeDecodeErrorResponse(conn, HttpStatus(400), error)).unit
                end match
            case Result.Panic(_) => Kyo.unit
        }
    end serveConnection

    /** Decode request → invoke handler → encode response → write. */
    private def serveHttpRequest(
        conn: transport.Connection,
        routeMatch: HttpRouter.RouteMatch,
        method: HttpMethod,
        path: String,
        headers: HttpHeaders,
        body: HttpBody,
        config: HttpServerConfig
    )(using Frame): Unit < (Async & Abort[HttpException]) =
        val endpoint = routeMatch.endpoint
        val isHead   = method == HttpMethod.HEAD
        val queryFn  = parseQueryParam(path)

        // Decode + invoke via HttpHandler.serve* — types resolved through endpoint, no casts
        val serveResult =
            if routeMatch.isStreamingRequest then
                val bodyStream = body match
                    case HttpBody.Streamed(chunks) => chunks
                    case HttpBody.Buffered(data)   => Stream.init(Seq(data))
                    case HttpBody.Empty            => Stream.empty[Span[Byte]]
                endpoint.serveStreaming(routeMatch.pathCaptures, queryFn, headers, bodyStream, path, method)
            else
                val bodyBytes = body match
                    case HttpBody.Buffered(data) => data
                    case HttpBody.Empty          => Span.empty[Byte]
                    case HttpBody.Streamed(_)    => Span.empty[Byte]
                endpoint.serveBuffered(routeMatch.pathCaptures, queryFn, headers, bodyBytes, path, method)

        serveResult match
            case Result.Failure(error) =>
                val status = error match
                    case _: HttpUnsupportedMediaTypeException => HttpStatus(415)
                    case _                                    => HttpStatus(400)
                writeDecodeErrorResponse(conn, status, error)
            case Result.Panic(e) =>
                writeErrorResponse(conn, HttpStatus(500), HttpHeaders.empty)
            case Result.Success(handlerComputation) =>
                Abort.run[Any](handlerComputation).map {
                    case Result.Success(response) =>
                        endpoint.encodeResponse(response)(
                            onEmpty = (status, hdrs) =>
                                writeResponse(conn, status, hdrs.add("Content-Length", "0"), HttpBody.Empty),
                            onBuffered = (status, hdrs, responseBody) =>
                                val withLen = hdrs.add("Content-Length", responseBody.size.toString)
                                // For HEAD: send headers with correct Content-Length but no body.
                                // Pass HttpBody.Empty so addBodyHeaders does not overwrite Content-Length with 0.
                                if isHead then writeResponse(conn, status, withLen, HttpBody.Empty)
                                else writeResponse(conn, status, withLen, HttpBody.Buffered(responseBody))
                            ,
                            onStreaming = (status, hdrs, responseStream) =>
                                if isHead then
                                    writeResponse(conn, status, hdrs.add("Transfer-Encoding", "chunked"), HttpBody.Empty)
                                else
                                    writeResponse(conn, status, hdrs.add("Transfer-Encoding", "chunked"), HttpBody.Streamed(responseStream))
                        )
                    case Result.Failure(error) =>
                        error match
                            case halt: HttpResponse.Halt =>
                                RouteUtil.encodeHalt(halt) { (status, hdrs, haltBody) =>
                                    val withLen = hdrs.add("Content-Length", haltBody.size.toString)
                                    writeResponse(conn, status, withLen, HttpBody.Buffered(if isHead then Span.empty else haltBody))
                                }
                            case _ =>
                                endpoint.encodeError(error) match
                                    case Present((status, hdrs, errorBody)) =>
                                        val withLen = hdrs.add("Content-Length", errorBody.size.toString)
                                        writeResponse(conn, status, withLen, HttpBody.Buffered(if isHead then Span.empty else errorBody))
                                    case Absent =>
                                        writeErrorResponse(conn, HttpStatus(500), HttpHeaders.empty)
                    case Result.Panic(_) =>
                        writeErrorResponse(conn, HttpStatus(500), HttpHeaders.empty)
                }
        end match
    end serveHttpRequest

    /** Buffer a streaming body into `HttpBody.Buffered` if the route is not streaming. For streaming routes, returns the body unchanged.
      * Non-streamed bodies are also returned unchanged.
      */
    private def bufferBodyIfNeeded(body: HttpBody, isStreamingRoute: Boolean, maxSize: Int)(using
        Frame
    ): HttpBody < (Async & Abort[HttpException]) =
        if isStreamingRoute then body
        else
            body match
                case HttpBody.Streamed(chunks) =>
                    chunks.run.map { allChunks =>
                        val spans    = allChunks.toSeq
                        val combined = Span.concat(spans*)
                        if combined.isEmpty then HttpBody.Empty
                        else HttpBody.Buffered(combined)
                    }
                case other => other

    private def writeErrorResponse(
        conn: transport.Connection,
        status: HttpStatus,
        extraHeaders: HttpHeaders
    )(using Frame): Unit < Async =
        val bodyBytes = RouteUtil.encodeErrorBody(status)
        val headers = extraHeaders.add("Content-Type", "application/json")
            .add("Content-Length", bodyBytes.size.toString)
        writeResponse(conn, status, headers, HttpBody.Buffered(bodyBytes))
    end writeErrorResponse

    /** Write an error response that includes the decode error message. */
    private def writeDecodeErrorResponse(
        conn: transport.Connection,
        status: HttpStatus,
        error: Any
    )(using Frame): Unit < Async =
        val message = error match
            case e: HttpException => e.getMessage
            case e: Throwable     => e.getMessage
            case other            => other.toString
        val bodyBytes = RouteUtil.encodeErrorBodyWithMessage(status, message)
        val headers = HttpHeaders.empty
            .add("Content-Type", "application/json")
            .add("Content-Length", bodyBytes.size.toString)
        writeResponse(conn, status, headers, HttpBody.Buffered(bodyBytes))
    end writeDecodeErrorResponse

    /** Create a TransportStream adapter bridging the remaining byte stream and connection write.
      *
      * Starts a background fiber that drains the remaining stream into a Channel, so WsCodec can read frames byte-by-byte. The channel is
      * unbounded to avoid blocking the feeder fiber.
      */
    private def makeWsAdapter(
        conn: transport.Connection,
        remaining: Stream[Span[Byte], Async]
    )(using Frame): TransportStream < Async =
        Channel.initUnscoped[Maybe[Span[Byte]]](4096).map { ch =>
            Fiber.initUnscoped {
                Abort.run[Any] {
                    remaining.foreach { span =>
                        ch.put(Present(span))
                    }
                }.andThen {
                    ch.put(Absent).unit
                }
            }.map { _ =>
                new TransportStream:
                    // Leftover bytes from a partially-consumed span
                    private var leftover: Span[Byte] = Span.empty[Byte]

                    def read(buf: Array[Byte])(using Frame): Int < Async =
                        if leftover.nonEmpty then
                            val take = math.min(leftover.size, buf.length)
                            discard(leftover.copyToArray(buf, 0, take))
                            leftover = leftover.slice(take, leftover.size)
                            take
                        else
                            Abort.run[Closed](ch.take).map {
                                case Result.Failure(_)      => -1 // channel closed = EOF
                                case Result.Panic(_)        => -1
                                case Result.Success(Absent) => -1 // EOF sentinel
                                case Result.Success(Present(span)) =>
                                    if span.isEmpty then read(buf)
                                    else
                                        val take = math.min(span.size, buf.length)
                                        discard(span.copyToArray(buf, 0, take))
                                        if take < span.size then
                                            leftover = span.slice(take, span.size)
                                        take
                            }

                    def write(data: Span[Byte])(using Frame): Unit < Async =
                        conn.write(data)
            }
        }
    end makeWsAdapter

    /** Three concurrent fibers: read loop, write loop, user handler. Server does NOT mask (RFC 6455 §5.1). */
    private def serveWebSocket(
        stream: TransportStream,
        wsHandler: WebSocketHttpHandler,
        headers: HttpHeaders,
        path: String
    )(using Frame): Unit < Async =
        Channel.initUnscoped[WebSocketFrame](wsHandler.wsConfig.bufferSize).map { inbound =>
            Channel.initUnscoped[WebSocketFrame](wsHandler.wsConfig.bufferSize).map { outbound =>
                AtomicRef.init[Maybe[(Int, String)]](Absent).map { closeReasonRef =>
                    val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                        closeReasonRef.set(Present((code, reason))).andThen {
                            WsCodec.writeClose(stream, code, reason, mask = false)
                        }
                    val ws = new WebSocket(inbound, outbound, closeReasonRef, closeFn)
                    val wsUrl = HttpUrl.parse(path) match
                        case Result.Success(u) => u
                        case _                 => HttpUrl.parse("/").getOrThrow
                    val request = HttpRequest(HttpMethod.GET, wsUrl, headers, Record.empty)

                    Fiber.initUnscoped {
                        Loop.foreach {
                            WsCodec.readFrame(stream).map { frame =>
                                inbound.put(frame).andThen(Loop.continue)
                            }
                        }
                    }.map { readFiber =>
                        Fiber.initUnscoped {
                            readFiber.getResult.map { _ =>
                                inbound.close.unit.andThen(outbound.close.unit)
                            }
                        }.map { monitorFiber =>
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    Loop.foreach {
                                        outbound.take.map { frame =>
                                            WsCodec.writeFrame(stream, frame, mask = false).andThen(Loop.continue)
                                        }
                                    }
                                }.andThen(outbound.close).unit
                            }.map { writeFiber =>
                                Sync.ensure(
                                    readFiber.interrupt.unit
                                        .andThen(writeFiber.interrupt.unit)
                                        .andThen(monitorFiber.interrupt.unit)
                                        .andThen(outbound.close.unit)
                                ) {
                                    Abort.run[Any](wsHandler.wsHandler(request, ws)).map { _ =>
                                        closeReasonRef.get.map {
                                            case Absent =>
                                                Abort.run[Any](WsCodec.writeClose(stream, 1000, "", mask = false)).unit
                                            case _ => Kyo.unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private def writeRouterError(
        conn: transport.Connection,
        error: HttpRouter.FindError
    )(using Frame): Unit < Async =
        error match
            case HttpRouter.FindError.NotFound =>
                writeErrorResponse(conn, HttpStatus(404), HttpHeaders.empty)
            case HttpRouter.FindError.MethodNotAllowed(allowed) =>
                // RFC 9110 §15.5.6: HEAD is implicitly allowed when GET is registered,
                // OPTIONS is always allowed
                val augmented =
                    val base     = allowed.toSet
                    val withHead = if base.contains(HttpMethod.GET) then base + HttpMethod.HEAD else base
                    withHead + HttpMethod.OPTIONS
                end augmented
                val allow = augmented.map(_.name).mkString(", ")
                writeErrorResponse(conn, HttpStatus(405), HttpHeaders.empty.add("Allow", allow))
            case HttpRouter.FindError.Options(headers) =>
                writeResponse(conn, HttpStatus(204), headers, HttpBody.Empty)

    private def parseQueryParam(path: String)(using Frame): Maybe[HttpUrl] =
        val qIdx = path.indexOf('?')
        if qIdx < 0 then Absent
        else
            HttpUrl.parse(path) match
                case Result.Success(url) => Present(url)
                case _                   => Absent
        end if
    end parseQueryParam

end HttpTransportServer2
