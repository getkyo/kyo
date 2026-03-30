package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Protocol-agnostic HTTP server. Works with Http1Protocol, Http2Protocol, etc.
  *
  * One fiber per accepted connection. Keep-alive loop for HTTP/1.1. WebSocket upgrade detection.
  *
  * Scope management: bind() creates an internal Scope that owns the server socket and accept loop fiber. A Promise gates close/await.
  */
class HttpTransportServer(transport: Transport, protocol: Protocol) extends HttpBackend.Server:

    private val Utf8 = StandardCharsets.UTF_8

    def bind(
        handlers: Seq[HttpHandler[?, ?, ?]],
        config: HttpServerConfig
    )(using Frame): HttpBackend.Binding < (Async & Scope) =
        val router = HttpRouter(handlers, config.cors)
        Promise.init[Unit, Any].map { done =>
            transport.listen(config.host, config.port, config.backlog) { stream =>
                serveConnection(stream, router, config)
            }.map { listener =>
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
        Abort.run[HttpException] {
            Loop.foreach {
                protocol.readRequest(stream, config.maxContentLength).map { (method, rawPath, headers, body) =>
                    val path = rawPath.indexOf('?') match
                        case -1 => rawPath;
                        case i  => rawPath.substring(0, i)
                    router.find(method, path) match
                        case Result.Success(routeMatch) =>
                            routeMatch.endpoint match
                                case wsHandler: WebSocketHttpHandler =>
                                    WsCodec.acceptUpgrade(stream, headers, wsHandler.wsConfig).andThen {
                                        serveWebSocket(stream, wsHandler, headers, path)
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
        }.unit

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
            case Result.Failure(_) =>
                writeErrorResponse(stream, HttpStatus(400), HttpHeaders.empty)
            case Result.Panic(e) =>
                writeErrorResponse(stream, HttpStatus(500), HttpHeaders.empty)
            case Result.Success(handlerComputation) =>
                Abort.run[Any](handlerComputation).map {
                    case Result.Success(response) =>
                        endpoint.encodeResponse(response)(
                            onEmpty = (status, hdrs) =>
                                protocol.writeResponseHead(stream, status, hdrs.add("Content-Length", "0")).andThen {
                                    protocol.writeBody(stream, Span.empty)
                                },
                            onBuffered = (status, hdrs, responseBody) =>
                                val finalBody = if isHead then Span.empty[Byte] else responseBody
                                protocol.writeResponseHead(
                                    stream,
                                    status,
                                    hdrs.add("Content-Length", responseBody.size.toString)
                                ).andThen {
                                    protocol.writeBody(stream, finalBody)
                                }
                            ,
                            onStreaming = (status, hdrs, responseStream) =>
                                protocol.writeResponseHead(stream, status, hdrs.add("Transfer-Encoding", "chunked")).andThen {
                                    if isHead then Kyo.unit
                                    else protocol.writeStreamingBody(stream, responseStream)
                                }
                        )
                    case Result.Failure(error) =>
                        error match
                            case halt: HttpResponse.Halt =>
                                RouteUtil.encodeHalt(halt) { (status, hdrs, haltBody) =>
                                    protocol.writeResponseHead(stream, status, hdrs).andThen {
                                        protocol.writeBody(stream, if isHead then Span.empty else haltBody)
                                    }
                                }
                            case _ =>
                                endpoint.encodeError(error) match
                                    case Present((status, hdrs, errorBody)) =>
                                        protocol.writeResponseHead(stream, status, hdrs).andThen {
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
        protocol.writeResponseHead(stream, status, headers).andThen {
            protocol.writeBody(stream, bodyBytes)
        }
    end writeErrorResponse

    /** Three concurrent fibers: read loop, write loop, user handler. Server does NOT mask (RFC 6455 §5.1). */
    private def serveWebSocket(
        stream: TransportStream,
        wsHandler: WebSocketHttpHandler,
        headers: HttpHeaders,
        path: String
    )(using Frame): Unit < Async =
        Scope.run {
            Channel.init[WebSocketFrame](wsHandler.wsConfig.bufferSize).map { inbound =>
                Channel.init[WebSocketFrame](wsHandler.wsConfig.bufferSize).map { outbound =>
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

                        Sync.ensure(inbound.close.unit.andThen(outbound.close.unit)) {
                            // Read loop: transport → inbound
                            Fiber.init {
                                Loop.foreach {
                                    WsCodec.readFrame(stream).map { frame =>
                                        inbound.put(frame).andThen(Loop.continue)
                                    }
                                }.handle(Abort.run[Closed]).unit
                            }.andThen {
                                // Write loop: outbound → transport (no mask for server)
                                Fiber.init {
                                    Loop.foreach {
                                        outbound.take.map { frame =>
                                            WsCodec.writeFrame(stream, frame, mask = false).andThen(Loop.continue)
                                        }
                                    }.handle(Abort.run[Closed]).unit
                                }.andThen {
                                    // User handler
                                    Abort.run[Any](wsHandler.wsHandler(request, ws)).unit
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
                val allow = allowed.map(_.name).mkString(", ")
                writeErrorResponse(stream, HttpStatus(405), HttpHeaders.empty.add("Allow", allow))
            case HttpRouter.FindError.Options(headers) =>
                protocol.writeResponseHead(stream, HttpStatus(204), headers).andThen {
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
