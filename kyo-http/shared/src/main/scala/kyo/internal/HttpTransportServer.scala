package kyo.internal

import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kyo.*

/** Protocol-agnostic HTTP server. Works with Http1Protocol, Http2Protocol, etc.
  *
  * One fiber per accepted connection. Keep-alive loop for HTTP/1.1. WebSocket upgrade detection.
  *
  * Scope management: bind() creates an internal Scope that owns the server socket and accept loop fiber. A Promise gates close/await.
  */
class HttpTransportServer(transport: Transport, protocol: Protocol) extends HttpBackend.Server:

    private val Utf8 = StandardCharsets.UTF_8

    /** RFC 9110 §6.6.1: Date header in IMF-fixdate format. */
    private val httpDateFormat = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)

    private def withDate(headers: HttpHeaders): HttpHeaders =
        headers.add("Date", ZonedDateTime.now(ZoneOffset.UTC).format(httpDateFormat))

    /** Write response head with Date header added automatically. */
    private def writeHead(stream: TransportStream, status: HttpStatus, headers: HttpHeaders)(using Frame): Unit < Async =
        protocol.writeResponseHead(stream, status, withDate(headers))

    def bind(
        handlers: Seq[HttpHandler[?, ?, ?]],
        config: HttpServerConfig
    )(using Frame): HttpBackend.Binding < (Async & Scope) =
        val router  = HttpRouter(handlers, config.cors)
        val handler = (stream: TransportStream) => serveConnection(stream, router, config)
        Promise.init[Unit, Any].map { done =>
            val listenOp = config.tls match
                case Present(tlsConfig) => transport.listenTls(config.host, config.port, config.backlog, tlsConfig)(handler)
                case Absent             => transport.listen(config.host, config.port, config.backlog)(handler)
            listenOp.map { listener =>
                new HttpBackend.Binding:
                    val port: Int    = listener.port
                    val host: String = listener.host
                    def close(gracePeriod: Duration)(using Frame): Unit < Async =
                        done.completeDiscard(Result.succeed(()))
                    def await(using Frame): Unit < Async =
                        done.get.unit
            }
        }
    end bind

    /** One connection, keep-alive loop. */
    private def serveConnection(
        stream: TransportStream,
        router: HttpRouter,
        config: HttpServerConfig
    )(using Frame): Unit < Async =
        val buffered = protocol.buffered(stream)
        Abort.run[HttpException] {
            Loop.foreach {
                protocol.readRequest(buffered, config.maxContentLength).map { (method, rawPath, headers, body) =>
                    val path = rawPath.indexOf('?') match
                        case -1 => rawPath;
                        case i  => rawPath.substring(0, i)
                    router.find(method, path) match
                        case Result.Success(routeMatch) =>
                            routeMatch.endpoint match
                                case wsHandler: WebSocketHttpHandler =>
                                    WsCodec.acceptUpgrade(buffered, headers, wsHandler.wsConfig).andThen {
                                        serveWebSocket(buffered, wsHandler, headers, path)
                                    }.andThen(Loop.done(()))
                                case _ =>
                                    serveHttpRequest(stream, routeMatch, method, rawPath, headers, body, config).andThen {
                                        if protocol.isKeepAlive(headers) then Loop.continue
                                        else Loop.done(())
                                    }
                        case Result.Failure(error) =>
                            writeRouterError(stream, error).andThen {
                                error match
                                    case HttpRouter.FindError.Options(_) =>
                                        if protocol.isKeepAlive(headers) then Loop.continue
                                        else Loop.done(())
                                    case _ => Loop.done(())
                            }
                        case Result.Panic(ex) =>
                            writeErrorResponse(stream, HttpStatus(500), HttpHeaders.empty).andThen(Loop.done(()))
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
                        Abort.run[Any](writeDecodeErrorResponse(stream, HttpStatus(413), error)).unit
                    case _ =>
                        Abort.run[Any](writeDecodeErrorResponse(stream, HttpStatus(400), error)).unit
                end match
            case Result.Panic(_) => Kyo.unit
        }
    end serveConnection

    /** Decode request → invoke handler → encode response → write. */
    private def serveHttpRequest(
        stream: TransportStream,
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
                writeDecodeErrorResponse(stream, status, error)
            case Result.Panic(e) =>
                writeErrorResponse(stream, HttpStatus(500), HttpHeaders.empty)
            case Result.Success(handlerComputation) =>
                Abort.run[Any](handlerComputation).map {
                    case Result.Success(response) =>
                        endpoint.encodeResponse(response)(
                            onEmpty = (status, hdrs) =>
                                writeHead(stream, status, hdrs.add("Content-Length", "0")).andThen {
                                    protocol.writeBody(stream, Span.empty)
                                },
                            onBuffered = (status, hdrs, responseBody) =>
                                val finalBody = if isHead then Span.empty[Byte] else responseBody
                                writeHead(
                                    stream,
                                    status,
                                    hdrs.add("Content-Length", responseBody.size.toString)
                                ).andThen {
                                    protocol.writeBody(stream, finalBody)
                                }
                            ,
                            onStreaming = (status, hdrs, responseStream) =>
                                writeHead(stream, status, hdrs.add("Transfer-Encoding", "chunked")).andThen {
                                    if isHead then Kyo.unit
                                    else protocol.writeStreamingBody(stream, responseStream)
                                }
                        )
                    case Result.Failure(error) =>
                        error match
                            case halt: HttpResponse.Halt =>
                                RouteUtil.encodeHalt(halt) { (status, hdrs, haltBody) =>
                                    val withLen = hdrs.add("Content-Length", haltBody.size.toString)
                                    writeHead(stream, status, withLen).andThen {
                                        protocol.writeBody(stream, if isHead then Span.empty else haltBody)
                                    }
                                }
                            case _ =>
                                endpoint.encodeError(error) match
                                    case Present((status, hdrs, errorBody)) =>
                                        val withLen = hdrs.add("Content-Length", errorBody.size.toString)
                                        writeHead(stream, status, withLen).andThen {
                                            protocol.writeBody(stream, if isHead then Span.empty else errorBody)
                                        }
                                    case Absent =>
                                        writeErrorResponse(stream, HttpStatus(500), HttpHeaders.empty)
                    case Result.Panic(_) =>
                        writeErrorResponse(stream, HttpStatus(500), HttpHeaders.empty)
                }
        end match
    end serveHttpRequest

    private def writeErrorResponse(
        stream: TransportStream,
        status: HttpStatus,
        extraHeaders: HttpHeaders
    )(using Frame): Unit < Async =
        val bodyBytes = RouteUtil.encodeErrorBody(status)
        val headers = extraHeaders.add("Content-Type", "application/json")
            .add("Content-Length", bodyBytes.size.toString)
        writeHead(stream, status, headers).andThen {
            protocol.writeBody(stream, bodyBytes)
        }
    end writeErrorResponse

    /** Write an error response that includes the decode error message. */
    private def writeDecodeErrorResponse(
        stream: TransportStream,
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
        writeHead(stream, status, headers).andThen {
            protocol.writeBody(stream, bodyBytes)
        }
    end writeDecodeErrorResponse

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
                                inbound.close.unit
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
                                    Abort.run[Any](wsHandler.wsHandler(request, ws)).andThen {
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
        stream: TransportStream,
        error: HttpRouter.FindError
    )(using Frame): Unit < Async =
        error match
            case HttpRouter.FindError.NotFound =>
                writeErrorResponse(stream, HttpStatus(404), HttpHeaders.empty)
            case HttpRouter.FindError.MethodNotAllowed(allowed) =>
                // RFC 9110 §15.5.6: HEAD is implicitly allowed when GET is registered,
                // OPTIONS is always allowed
                val augmented =
                    val base     = allowed.toSet
                    val withHead = if base.contains(HttpMethod.GET) then base + HttpMethod.HEAD else base
                    withHead + HttpMethod.OPTIONS
                end augmented
                val allow = augmented.map(_.name).mkString(", ")
                writeErrorResponse(stream, HttpStatus(405), HttpHeaders.empty.add("Allow", allow))
            case HttpRouter.FindError.Options(headers) =>
                writeHead(stream, HttpStatus(204), headers).andThen {
                    protocol.writeBody(stream, Span.empty)
                }

    private def parseQueryParam(path: String)(using Frame): Maybe[HttpUrl] =
        val qIdx = path.indexOf('?')
        if qIdx < 0 then Absent
        else
            HttpUrl.parse(path) match
                case Result.Success(url) => Present(url)
                case _                   => Absent
        end if
    end parseQueryParam

end HttpTransportServer
