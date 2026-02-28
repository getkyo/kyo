package kyo.internal

import kyo.*
import kyo.discard
import kyo.internal.IncomingMessage
import kyo.internal.NodeHttp
import kyo.internal.NodeHttpServerJs
import kyo.internal.ServerResponse
import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import scala.scalajs.js
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array

/** JS server backend using Node.js `http` module.
  *
  * Dispatches incoming requests through HttpRouter with support for both buffered and streaming request/response bodies. Backpressure is
  * handled via Node.js readable stream pause()/resume() for incoming bodies and the write() return value + drain event for outgoing
  * streaming responses.
  */
final class NodeServerBackend extends HttpBackend.Server:

    def bind(
        handlers: Seq[HttpHandler[?, ?, ?]],
        config: HttpServer.Config
    )(using Frame): HttpBackend.Binding < Async =
        Sync.Unsafe.defer {
            val router = HttpRouter(handlers, config.cors)
            val p      = Promise.Unsafe.init[HttpBackend.Binding, Any]()

            val options = js.Dynamic.literal(
                keepAlive = config.keepAlive
            )

            val requestListener = (req: IncomingMessage, res: ServerResponse) =>
                import AllowUnsafe.embrace.danger
                guardResponse(res)(handleRequest(req, res, router, config.maxContentLength))

            val nodeServer = NodeHttp.createServer(
                options.asInstanceOf[js.Object],
                requestListener
            )

            discard(nodeServer.on(
                "error",
                { (err: js.Dynamic) =>
                    import AllowUnsafe.embrace.danger
                    val msg   = err.message.asInstanceOf[String]
                    val cause = new Exception(msg)
                    discard(p.complete(Result.Panic(HttpError.BindError(config.host, config.port, cause))))
                }
            ))

            discard(nodeServer.listen(
                config.port,
                config.host,
                config.backlog,
                { () =>
                    import AllowUnsafe.embrace.danger
                    val addr        = nodeServer.address()
                    val actualPort  = addr.port.asInstanceOf[Int]
                    val actualHost  = config.host
                    val closedLatch = Promise.Unsafe.init[Unit, Any]()

                    val binding = new HttpBackend.Binding:
                        def port: Int    = actualPort
                        def host: String = actualHost
                        def close(gracePeriod: Duration)(using Frame): Unit < Async =
                            Sync.Unsafe.defer {
                                discard(nodeServer.close { () =>
                                    import AllowUnsafe.embrace.danger
                                    discard(closedLatch.complete(Result.succeed(())))
                                })
                                if gracePeriod == Duration.Zero then
                                    nodeServer.closeAllConnections()
                                else
                                    nodeServer.closeIdleConnections()
                                    discard(launchFiber {
                                        Async.sleep(gracePeriod).andThen {
                                            Sync.Unsafe.defer(nodeServer.closeAllConnections())
                                        }
                                    })
                                end if
                            }.andThen(closedLatch.safe.get)
                        def await(using Frame): Unit < Async =
                            closedLatch.safe.get
                    discard(p.complete(Result.succeed(binding)))
                }
            ))

            p.safe.get
        }
    end bind

    private def handleRequest(
        req: IncomingMessage,
        res: ServerResponse,
        router: HttpRouter,
        maxContentLength: Int
    )(using AllowUnsafe, Frame): Unit =
        val method  = HttpMethod.unsafe(req.method)
        val uri     = req.url
        val pathEnd = uri.indexOf('?')
        val path    = if pathEnd >= 0 then uri.substring(0, pathEnd) else uri
        val headers = extractHeaders(req.headers)

        router.find(method, path) match
            case Result.Failure(HttpRouter.FindError.NotFound) =>
                discard(req.resume())
                discard(req.on("end", { () => sendErrorResponse(res, HttpStatus.NotFound, HttpHeaders.empty) }))

            case Result.Failure(HttpRouter.FindError.Options(headers)) =>
                discard(req.resume())
                discard(req.on("end", { () => writeBufferedResponse(res, HttpStatus.NoContent, headers, Span.empty[Byte]) }))

            case Result.Failure(HttpRouter.FindError.MethodNotAllowed(allowed)) =>
                // RFC 9110: HEAD is implicitly supported when GET is, OPTIONS is always supported
                val allMethods =
                    allowed
                        ++ (if allowed.contains(HttpMethod.GET) then Set(HttpMethod.HEAD) else Set.empty)
                        + HttpMethod.OPTIONS
                val allowValue = allMethods.iterator.map(_.name).mkString(", ")
                discard(req.resume())
                discard(req.on(
                    "end",
                    { () =>
                        sendErrorResponse(res, HttpStatus.MethodNotAllowed, HttpHeaders.empty.add("Allow", allowValue))
                    }
                ))

            case Result.Success(routeMatch) =>
                if routeMatch.isStreamingRequest then
                    handleStreaming(req, res, router, routeMatch, uri, path, pathEnd, headers, method)
                else
                    handleBuffered(req, res, router, routeMatch, uri, path, pathEnd, headers, maxContentLength, method)

            case Result.Panic(_) =>
                sendErrorResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty)
        end match
    end handleRequest

    private def handleBuffered(
        req: IncomingMessage,
        res: ServerResponse,
        router: HttpRouter,
        routeMatch: HttpRouter.RouteMatch,
        uri: String,
        path: String,
        pathEnd: Int,
        headers: HttpHeaders,
        maxContentLength: Int,
        method: HttpMethod
    )(using AllowUnsafe, Frame): Unit =
        val chunks   = js.Array[Uint8Array]()
        var bodySize = 0
        var rejected = false

        discard(req.onData(
            "data",
            { (chunk: Uint8Array) =>
                if !rejected then
                    bodySize += chunk.length
                    if bodySize > maxContentLength then
                        rejected = true
                        discard(req.resume())
                    else
                        discard(chunks.push(chunk))
                    end if
                end if
            }
        ))

        discard(req.on(
            "end",
            { () =>
                guardResponse(res) {
                    if rejected then
                        sendErrorResponse(res, HttpStatus.PayloadTooLarge, HttpHeaders.empty)
                    else
                        val bodyBytes = concatChunks(chunks, bodySize)
                        val queryFn   = makeQueryParam(uri, pathEnd)
                        val endpoint  = routeMatch.endpoint
                        val route     = endpoint.route

                        RouteUtil.decodeBufferedRequest(
                            route,
                            routeMatch.pathCaptures,
                            queryFn,
                            headers,
                            bodyBytes,
                            path,
                            Present(method)
                        ) match
                            case Result.Success(request) =>
                                invokeHandler(res, endpoint, route, request, method == HttpMethod.HEAD)
                            case Result.Failure(_: HttpError.UnsupportedMediaTypeError) =>
                                sendErrorResponse(res, HttpStatus.UnsupportedMediaType, HttpHeaders.empty)
                            case Result.Failure(_) =>
                                sendErrorResponse(res, HttpStatus.BadRequest, HttpHeaders.empty)
                            case Result.Panic(_) =>
                                sendErrorResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty)
                        end match
                    end if
                }
            }
        ))
    end handleBuffered

    private def handleStreaming(
        req: IncomingMessage,
        res: ServerResponse,
        router: HttpRouter,
        routeMatch: HttpRouter.RouteMatch,
        uri: String,
        path: String,
        pathEnd: Int,
        headers: HttpHeaders,
        method: HttpMethod
    )(using AllowUnsafe, Frame): Unit =
        val byteChannel = Channel.Unsafe.init[Maybe[Span[Byte]]](32)

        val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
            Abort.run[Closed] {
                Loop.foreach {
                    byteChannel.safe.takeWith {
                        case Present(bytes) =>
                            Emit.valueWith(Chunk(bytes))(Loop.continue)
                        case Absent =>
                            Loop.done(())
                    }
                }
            }.unit
        }

        discard(req.onData(
            "data",
            { (chunk: Uint8Array) =>
                val bytes = uint8ArrayToBytes(chunk)
                val value = Present(Span.fromUnsafe(bytes))
                byteChannel.offer(value) match
                    case Result.Success(true)  => // offered successfully
                    case Result.Success(false) =>
                        // Channel full — apply backpressure
                        discard(req.pause())
                        val fiber = byteChannel.putFiber(value)
                        fiber.onComplete { _ =>
                            discard(req.resume())
                        }
                    case _ => // channel closed, drop
                end match
            }
        ))

        var reqEnded = false

        discard(req.on(
            "end",
            { () =>
                reqEnded = true
                discard(byteChannel.offer(Absent))
            }
        ))

        // Close channel on request error so handler doesn't hang
        discard(req.on("error", { () => discard(byteChannel.close()) }))

        // Close channel on client disconnect (but not on normal completion)
        discard(req.on(
            "close",
            { () =>
                if !reqEnded then discard(byteChannel.close())
            }
        ))

        val queryFn  = makeQueryParam(uri, pathEnd)
        val endpoint = routeMatch.endpoint
        val route    = endpoint.route

        RouteUtil.decodeStreamingRequest(
            route,
            routeMatch.pathCaptures,
            queryFn,
            headers,
            bodyStream,
            path,
            Present(method)
        ) match
            case Result.Success(request) =>
                invokeHandler(res, endpoint, route, request, method == HttpMethod.HEAD)
            case Result.Failure(_: HttpError.UnsupportedMediaTypeError) =>
                discard(byteChannel.close())
                sendErrorResponse(res, HttpStatus.UnsupportedMediaType, HttpHeaders.empty)
            case Result.Failure(_) =>
                discard(byteChannel.close())
                sendErrorResponse(res, HttpStatus.BadRequest, HttpHeaders.empty)
            case Result.Panic(_) =>
                discard(byteChannel.close())
                sendErrorResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty)
        end match
    end handleStreaming

    private def invokeHandler(
        res: ServerResponse,
        handler: HttpHandler[?, ?, ?],
        route: HttpRoute[?, ?, ?],
        request: HttpRequest[?],
        isHead: Boolean
    )(using AllowUnsafe, Frame): Unit =
        val h   = handler.asInstanceOf[HttpHandler[Any, Any, Any]]
        val req = request.asInstanceOf[HttpRequest[Any]]
        val rt  = route.asInstanceOf[HttpRoute[Any, Any, Any]]

        try
            val fiber = launchFiber {
                Abort.run[Any](h(req)).map {
                    case Result.Success(response) =>
                        RouteUtil.encodeResponse(rt, response)(
                            onEmpty = (status, headers) =>
                                writeBufferedResponse(res, status, headers, Span.empty[Byte]),
                            onBuffered = (status, headers, contentType, body) =>
                                val h = if headers.get("Content-Type").nonEmpty then headers else headers.add("Content-Type", contentType)
                                if isHead then
                                    val hh = h.add("Content-Length", body.size.toString)
                                    writeBufferedResponse(res, status, hh, Span.empty[Byte])
                                else writeBufferedResponse(res, status, h, body)
                                end if
                            ,
                            onStreaming = (status, headers, contentType, stream) =>
                                val h = if headers.get("Content-Type").nonEmpty then headers else headers.add("Content-Type", contentType)
                                if isHead then writeBufferedResponse(res, status, h, Span.empty[Byte])
                                else writeStreamingResponse(res, status, h, stream)
                        )
                    case Result.Failure(halt: HttpResponse.Halt) =>
                        RouteUtil.encodeHalt(halt)((status, headers, body) =>
                            writeBufferedResponse(res, status, headers, body)
                        )
                    case Result.Failure(error) =>
                        RouteUtil.encodeError(rt, error) match
                            case Present((status, headers, body)) =>
                                writeBufferedResponse(res, status, headers, body)
                            case Absent =>
                                sendErrorResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty)
                    case Result.Panic(_) =>
                        sendErrorResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty)
                }
            }

            // Interrupt handler fiber on client disconnect
            discard(res.on(
                "close",
                { () =>
                    import AllowUnsafe.embrace.danger
                    discard(fiber.unsafe.interrupt(Result.Panic(new Exception("Client disconnected"))))
                }
            ))

            fiber.unsafe.onComplete {
                case Result.Failure(e) =>
                    val handlerError = HttpError.HandlerError(e)
                    val body         = Span.fromUnsafe(handlerError.getMessage.getBytes("UTF-8"))
                    writeBufferedResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty, body)
                case Result.Panic(e) =>
                    val handlerError = HttpError.HandlerError(e)
                    val body         = Span.fromUnsafe(handlerError.getMessage.getBytes("UTF-8"))
                    writeBufferedResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty, body)
                case _ => ()
            }
        catch
            case e: Throwable =>
                val handlerError = HttpError.HandlerError(e)
                val body         = Span.fromUnsafe(handlerError.getMessage.getBytes("UTF-8"))
                writeBufferedResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty, body)
        end try
    end invokeHandler

    private def writeBufferedResponse(
        res: ServerResponse,
        status: HttpStatus,
        headers: HttpHeaders,
        body: Span[Byte]
    )(using AllowUnsafe): Unit =
        val withDate  = if headers.get("Date").nonEmpty then headers else headers.add("Date", formatHttpDate())
        val withCl    = if withDate.get("Content-Length").nonEmpty then withDate else withDate.add("Content-Length", body.size.toString)
        val jsHeaders = buildJsHeaders(withCl)
        discard(res.writeHead(status.code, jsHeaders))
        if body.isEmpty then
            res.endEmpty()
        else
            res.end(bytesToUint8Array(body.toArrayUnsafe))
        end if
    end writeBufferedResponse

    private def writeStreamingResponse(
        res: ServerResponse,
        status: HttpStatus,
        headers: HttpHeaders,
        stream: Stream[Span[Byte], Async]
    )(using AllowUnsafe, Frame): Unit =
        val withDate  = if headers.get("Date").nonEmpty then headers else headers.add("Date", formatHttpDate())
        val jsHeaders = buildJsHeaders(withDate)
        jsHeaders("Transfer-Encoding") = "chunked"
        discard(res.writeHead(status.code, jsHeaders))

        val writeFiber = launchFiber {
            Abort.run[Throwable](Abort.catching[Throwable] {
                stream.foreach { bytes =>
                    val chunk    = bytesToUint8Array(bytes.toArrayUnsafe)
                    val canWrite = res.write(chunk)
                    if !canWrite then
                        // Backpressure: wait for drain event
                        val drainP = Promise.Unsafe.init[Unit, Any]()
                        discard(res.once(
                            "drain",
                            { () =>
                                import AllowUnsafe.embrace.danger
                                discard(drainP.complete(Result.succeed(())))
                            }
                        ))
                        drainP.safe.get
                    else ()
                    end if
                }
            }).map { _ =>
                res.endEmpty()
            }
        }

        // Interrupt write fiber when client disconnects
        discard(res.on(
            "close",
            { () =>
                import AllowUnsafe.embrace.danger
                discard(writeFiber.unsafe.interrupt(Result.Panic(new Exception("Client disconnected"))))
            }
        ))
    end writeStreamingResponse

    private def sendErrorResponse(
        res: ServerResponse,
        status: HttpStatus,
        extraHeaders: HttpHeaders
    )(using AllowUnsafe): Unit =
        val bodyBytes = RouteUtil.encodeErrorBody(status)
        val withCt    = extraHeaders.add("Content-Type", "application/json")
        val withDate  = if withCt.get("Date").nonEmpty then withCt else withCt.add("Date", formatHttpDate())
        writeBufferedResponse(res, status, withDate, bodyBytes)
    end sendErrorResponse

    private inline def guardResponse(res: ServerResponse)(inline body: Unit)(using AllowUnsafe): Unit =
        try body
        catch
            case e: Throwable =>
                try writeBufferedResponse(res, HttpStatus.InternalServerError, HttpHeaders.empty, Span.empty[Byte])
                catch case _: Throwable => ()

    private def makeQueryParam(uri: String, pathEnd: Int): Maybe[HttpUrl] =
        if pathEnd < 0 then Absent
        else Present(HttpUrl.fromUri(uri))

    private def launchFiber[A](v: => A < Async)(using AllowUnsafe, Frame): Fiber[A, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(v))

    private def extractHeaders(jsHeaders: js.Dictionary[js.Any]): HttpHeaders =
        val builder = ChunkBuilder.init[String]
        jsHeaders.foreach { (key, value) =>
            value match
                case arr: js.Array[?] =>
                    arr.foreach { v =>
                        discard(builder += key)
                        discard(builder += v.toString)
                    }
                case _ =>
                    discard(builder += key)
                    discard(builder += value.toString)
        }
        HttpHeaders.fromChunk(builder.result())
    end extractHeaders

    private def buildJsHeaders(headers: HttpHeaders): js.Dictionary[js.Any] =
        val dict = js.Dictionary.empty[js.Any]
        headers.foreach { (k, v) =>
            dict.get(k) match
                case Some(existing: js.Array[?]) =>
                    discard(existing.asInstanceOf[js.Array[js.Any]].push(v))
                case Some(existing) =>
                    dict(k) = js.Array(existing, v)
                case None =>
                    dict(k) = v
        }
        dict
    end buildJsHeaders

    private def concatChunks(chunks: js.Array[Uint8Array], totalSize: Int): Span[Byte] =
        if totalSize == 0 then Span.empty[Byte]
        else
            val result = new Array[Byte](totalSize)
            var pos    = 0
            chunks.foreach { chunk =>
                val bytes = uint8ArrayToBytes(chunk)
                java.lang.System.arraycopy(bytes, 0, result, pos, bytes.length)
                pos += bytes.length
            }
            Span.fromUnsafe(result)
    end concatChunks

    private def uint8ArrayToBytes(uint8: Uint8Array): Array[Byte] =
        val int8 = new Int8Array(uint8.buffer, uint8.byteOffset, uint8.length)
        int8.toArray

    private def bytesToUint8Array(bytes: Array[Byte]): Uint8Array =
        val int8 = js.typedarray.byteArray2Int8Array(bytes)
        new Uint8Array(int8.buffer, int8.byteOffset, int8.length)

    private def formatHttpDate(): String =
        new js.Date().toUTCString()

end NodeServerBackend
