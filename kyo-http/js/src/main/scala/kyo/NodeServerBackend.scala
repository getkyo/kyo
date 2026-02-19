package kyo

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
  * Dispatches incoming requests through `Backend.ServerHandler` with support for both buffered and streaming request/response bodies.
  * Backpressure is handled via Node.js readable stream `pause()`/`resume()` for incoming bodies and the `write()` return value + `drain`
  * event for outgoing streaming responses.
  */
object NodeServerBackend extends Backend.Server:

    def server(
        port: Int,
        host: String,
        maxContentLength: Int,
        backlog: Int,
        keepAlive: Boolean,
        handler: Backend.ServerHandler
    )(using Frame): Backend.Server.Binding < Async =
        Sync.Unsafe.defer {
            val p = Promise.Unsafe.init[Backend.Server.Binding, Any]()

            val options = js.Dynamic.literal(
                keepAlive = keepAlive
            )

            val requestListener = (req: IncomingMessage, res: ServerResponse) =>
                import AllowUnsafe.embrace.danger
                guardResponse(res)(handleRequest(req, res, handler, maxContentLength))

            val nodeServer = NodeHttp.createServer(
                options.asInstanceOf[js.Object],
                requestListener
            )

            discard(nodeServer.listen(
                port,
                host,
                backlog,
                { () =>
                    import AllowUnsafe.embrace.danger
                    val addr       = nodeServer.address()
                    val actualPort = addr.port.asInstanceOf[Int]
                    // Use the original host parameter — Node.js may resolve "localhost" to "::1"
                    val actualHost  = host
                    val closedLatch = Promise.Unsafe.init[Unit, Any]()

                    val binding = new Backend.Server.Binding:
                        def port: Int    = actualPort
                        def host: String = actualHost
                        def close(gracePeriod: Duration)(using Frame): Unit < Async =
                            Sync.Unsafe.defer {
                                // Stop accepting new connections first
                                discard(nodeServer.close { () =>
                                    import AllowUnsafe.embrace.danger
                                    discard(closedLatch.complete(Result.succeed(())))
                                })
                                // Then terminate existing connections
                                if gracePeriod == Duration.Zero then
                                    nodeServer.closeAllConnections()
                                else
                                    nodeServer.closeIdleConnections()
                                    // Force-close remaining connections after grace period
                                    discard(Backend.Unsafe.launchFiber {
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
    end server

    private def handleRequest(
        req: IncomingMessage,
        res: ServerResponse,
        handler: Backend.ServerHandler,
        maxContentLength: Int
    )(using AllowUnsafe, Frame): Unit =
        val method  = HttpRequest.Method(req.method)
        val uri     = req.url
        val pathEnd = uri.indexOf('?')
        val path    = if pathEnd >= 0 then uri.substring(0, pathEnd) else uri

        val headers = extractHeaders(req.headers)

        // Fast-path rejection (404, 405)
        handler.reject(method, path) match
            case Present(errorResponse) =>
                // Consume body and send error
                discard(req.resume())
                discard(req.on("end", { () => writeBufferedResponse(res, errorResponse) }))

            case Absent =>
                if handler.isStreaming(method, path) then
                    handleStreaming(req, res, handler, method, uri, headers)
                else
                    handleBuffered(req, res, handler, method, uri, headers, maxContentLength)
        end match
    end handleRequest

    private def handleBuffered(
        req: IncomingMessage,
        res: ServerResponse,
        handler: Backend.ServerHandler,
        method: HttpRequest.Method,
        uri: String,
        headers: HttpHeaders,
        maxContentLength: Int
    )(using AllowUnsafe, Frame): Unit =
        val chunks   = js.Array[Uint8Array]()
        var bodySize = 0
        var rejected = false

        val dataListener = (chunk: Uint8Array) =>
            if !rejected then
                bodySize += chunk.length
                if bodySize > maxContentLength then
                    rejected = true
                    discard(req.resume())
                else
                    discard(chunks.push(chunk))
                end if
            end if

        discard(req.onData("data", dataListener))

        discard(req.on(
            "end",
            { () =>
                guardResponse(res) {
                    if rejected then
                        writeBufferedResponse(res, HttpResponse(HttpStatus.PayloadTooLarge))
                    else
                        val bodyBytes = concatChunks(chunks, bodySize)
                        val request   = HttpRequest.fromRawHeaders(method, uri, headers, bodyBytes)

                        val fiber = Backend.Unsafe.launchFiber(handler.handle(request))
                        completeFiber(res, fiber)
                    end if
                }
            }
        ))
    end handleBuffered

    private def handleStreaming(
        req: IncomingMessage,
        res: ServerResponse,
        handler: Backend.ServerHandler,
        method: HttpRequest.Method,
        uri: String,
        headers: HttpHeaders
    )(using AllowUnsafe, Frame): Unit =
        val byteChannel = Channel.Unsafe.init[Maybe[Span[Byte]]](32)

        val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
            Abort.run[Closed] {
                Loop(()) { _ =>
                    Channel.take(byteChannel.safe).map {
                        case Present(bytes) =>
                            Emit.valueWith(Chunk(bytes))(Loop.continue(()))
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

        discard(req.on(
            "end",
            { () =>
                discard(byteChannel.offer(Absent))
            }
        ))

        val request = HttpRequest.fromRawStreaming(method, uri, headers, bodyStream)

        guardResponse(res) {
            val fiber = Backend.Unsafe.launchFiber(handler.handleStreaming(request))
            completeFiber(res, fiber)
        }
    end handleStreaming

    private def writeResponse(res: ServerResponse, response: HttpResponse[?])(using AllowUnsafe, Frame): Unit =
        response.body.use(
            _ => writeBufferedResponse(res, response),
            streamed => writeStreamingResponse(res, response, streamed)
        )

    private def writeBufferedResponse(res: ServerResponse, response: HttpResponse[?])(using AllowUnsafe): Unit =
        val bodyData  = response.body.use(_.data, _ => Array.empty[Byte])
        val jsHeaders = buildJsHeaders(response)

        discard(res.writeHead(response.status.code, jsHeaders))
        if bodyData.isEmpty then
            res.endEmpty()
        else
            res.end(bytesToUint8Array(bodyData))
        end if
    end writeBufferedResponse

    private def writeStreamingResponse(
        res: ServerResponse,
        response: HttpResponse[?],
        streamed: HttpBody.Streamed
    )(using AllowUnsafe, Frame): Unit =
        val jsHeaders = buildJsHeaders(response)
        // Set chunked encoding if no Content-Length is set
        if !response.resolvedHeaders.get("Content-Length").isDefined then
            jsHeaders("Transfer-Encoding") = "chunked"
        discard(res.writeHead(response.status.code, jsHeaders))

        discard(Backend.Unsafe.launchFiber {
            Abort.run[Throwable](Abort.catching[Throwable] {
                streamed.stream.foreach { bytes =>
                    val chunk    = bytesToUint8Array(bytes.toArrayUnsafe)
                    val canWrite = res.write(chunk)
                    if !canWrite then
                        // Backpressure: wait for drain event
                        val drainP = Promise.Unsafe.init[Unit, Any]()
                        discard(res.on(
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
        })
    end writeStreamingResponse

    private def completeFiber(res: ServerResponse, fiber: Fiber[Any, Any])(using AllowUnsafe, Frame): Unit =
        discard(res.on(
            "close",
            { () =>
                import AllowUnsafe.embrace.danger
                discard(fiber.unsafe.interrupt(Result.Panic(new Exception("Client disconnected"))))
            }
        ))
        fiber.unsafe.onComplete { result =>
            try
                result match
                    case Result.Success(r) =>
                        writeResponse(res, r.asInstanceOf[HttpResponse[?]])
                    case Result.Failure(e) =>
                        writeBufferedResponse(res, HttpResponse.serverError(e.toString))
                    case Result.Panic(e) =>
                        writeBufferedResponse(
                            res,
                            HttpResponse.serverError(
                                if e.getMessage != null then e.getMessage else "Internal Server Error"
                            )
                        )
            catch case _: Throwable => () // client already disconnected
        }
    end completeFiber

    private inline def guardResponse(res: ServerResponse)(inline body: Unit)(using AllowUnsafe): Unit =
        try body
        catch
            case e: Throwable =>
                try
                    writeBufferedResponse(
                        res,
                        HttpResponse.serverError(
                            if e.getMessage != null then e.getMessage else "Internal Server Error"
                        )
                    )
                catch case _: Throwable => ()

    private def extractHeaders(jsHeaders: js.Dictionary[js.Any]): HttpHeaders =
        var headers = HttpHeaders.empty
        jsHeaders.foreach { (key, value) =>
            value match
                case arr: js.Array[?] =>
                    arr.foreach { v =>
                        headers = headers.add(key, v.toString)
                    }
                case _ =>
                    headers = headers.add(key, value.toString)
        }
        headers
    end extractHeaders

    private def buildJsHeaders(response: HttpResponse[?]): js.Dictionary[js.Any] =
        val dict = js.Dictionary.empty[js.Any]
        response.resolvedHeaders.foreach { (k, v) =>
            // Accumulate multi-value headers as arrays
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

    private def concatChunks(chunks: js.Array[Uint8Array], totalSize: Int): Array[Byte] =
        if totalSize == 0 then Array.empty[Byte]
        else
            val result = new Array[Byte](totalSize)
            var pos    = 0
            chunks.foreach { chunk =>
                val bytes = uint8ArrayToBytes(chunk)
                java.lang.System.arraycopy(bytes, 0, result, pos, bytes.length)
                pos += bytes.length
            }
            result
    end concatChunks

    private def uint8ArrayToBytes(uint8: Uint8Array): Array[Byte] =
        val int8 = new Int8Array(uint8.buffer, uint8.byteOffset, uint8.length)
        int8.toArray

    private def bytesToUint8Array(bytes: Array[Byte]): Uint8Array =
        val int8 = js.typedarray.byteArray2Int8Array(bytes)
        new Uint8Array(int8.buffer, int8.byteOffset, int8.length)

end NodeServerBackend
