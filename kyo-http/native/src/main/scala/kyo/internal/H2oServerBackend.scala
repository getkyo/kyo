package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.<
import kyo.Abort
import kyo.AllowUnsafe
import kyo.Async
import kyo.Chunk
import kyo.ChunkBuilder
import kyo.Duration
import kyo.Emit
import kyo.Fiber
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Sync
import kyo.discard
import kyo.internal.H2oBindings
import kyo.internal.H2oBindings.H2oGenerator
import kyo.internal.H2oBindings.H2oReq
import kyo.internal.H2oBindings.H2oServer
import kyo.internal.HttpRouter.*
import scala.compiletime.uninitialized
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Native HTTP server backend using libh2o with async event loop.
  *
  * h2o runs a single-threaded event loop. The handler callback fires on the event loop thread, extracts request data, and launches a Kyo
  * fiber. The fiber processes the request and enqueues the response. A pipe wakeup triggers the event loop to drain responses and call
  * h2o_send(). No threads block on Scala side.
  */
final class H2oServerBackend extends HttpBackend.Server:

    def bind(
        handlers: Seq[HttpHandler[?, ?, ?]],
        config: HttpServer.Config
    )(using Frame): HttpBackend.Binding < Async =
        Sync.defer {
            H2oServerBackend.startServer(HttpRouter(handlers), config)
        }

end H2oServerBackend

private[kyo] object H2oServerBackend:
    import AllowUnsafe.embrace.danger

    private given Frame = Frame.internal

    // ── Global state (one server per process, matching h2o's g_server) ───

    @volatile private var server: H2oServer    = null
    @volatile private var router: HttpRouter   = null
    @volatile private var evloopThread: Thread = null

    private val responseQueue = new ConcurrentLinkedQueue[PendingResponse]()
    private val inFlight      = new AtomicInteger(0)
    private val nextStreamId  = new AtomicInteger(1)
    private val streams       = new ConcurrentHashMap[Int, StreamContext]()

    // ── Response types enqueued by fibers ────────────────────────────────

    sealed private trait PendingResponse

    final private class BufferedResponse(
        val req: H2oReq,
        val status: Int,
        val headers: HttpHeaders,
        val body: Span[Byte]
    ) extends PendingResponse

    final private class StreamStartResponse(
        val streamCtx: StreamContext,
        val status: Int,
        val headers: HttpHeaders
    ) extends PendingResponse

    final private class StreamChunkNotify(
        val streamCtx: StreamContext
    ) extends PendingResponse

    final private class ErrorResponse(
        val req: H2oReq,
        val status: Int,
        val extraHeaders: HttpHeaders
    ) extends PendingResponse

    // ── Streaming state machine ─────────────────────────────────────────

    private val READY_FOR_DATA      = 0
    private val WAITING_FOR_PROCEED = 1
    private val STOPPED             = 2

    final private class StreamContext(
        val req: H2oReq,
        val streamId: Int,
        var generator: H2oGenerator
    ):
        @volatile var state: Int = READY_FOR_DATA
        val chunkQueue           = new ConcurrentLinkedQueue[(Array[Byte], Boolean)]()
        @volatile var stopped    = false
        private val decremented  = new java.util.concurrent.atomic.AtomicBoolean(false)

        def decrementInFlight(): Unit =
            if decremented.compareAndSet(false, true) then
                discard(inFlight.decrementAndGet())

        def onProceed(): Unit =
            if stopped then return
            val chunk = chunkQueue.poll()
            if chunk != null then
                val (data, isFinal) = chunk
                sendChunkNative(data, isFinal)
                if isFinal then state = STOPPED
                else state = WAITING_FOR_PROCEED
            else
                state = READY_FOR_DATA
            end if
        end onProceed

        def tryDeliver(): Unit =
            if stopped then return
            if state == READY_FOR_DATA then
                val chunk = chunkQueue.poll()
                if chunk != null then
                    val (data, isFinal) = chunk
                    sendChunkNative(data, isFinal)
                    if isFinal then state = STOPPED
                    else state = WAITING_FOR_PROCEED
                end if
            end if
        end tryDeliver

        def enqueueChunk(data: Array[Byte], isFinal: Boolean): Unit =
            if !stopped then
                discard(chunkQueue.add((data, isFinal)))
                H2oBindings.wake(server)
        end enqueueChunk

        def onStop(): Unit =
            stopped = true
            state = STOPPED
        end onStop

        private def sendChunkNative(data: Array[Byte], isFinal: Boolean): Unit =
            Zone {
                if data.length > 0 then
                    val ptr = alloc[Byte](data.length)
                    var i   = 0
                    while i < data.length do
                        ptr(i) = data(i)
                        i += 1
                    H2oBindings.sendChunk(req, generator, ptr, data.length, if isFinal then 1 else 0)
                else
                    H2oBindings.sendChunk(req, generator, null, 0, if isFinal then 1 else 0)
            }
            if isFinal then
                discard(streams.remove(streamId))
                decrementInFlight()
        end sendChunkNative

    end StreamContext

    // ── CFuncPtr callbacks (must only reference static symbols) ──────────

    private val handlerCallback: CFuncPtr1[H2oReq, CInt] =
        CFuncPtr1.fromScalaFunction { (req: H2oReq) =>
            discard(inFlight.incrementAndGet())
            try handleRequest(req)
            catch
                case _: Throwable =>
                    sendImmediateError(req, 500, HttpHeaders.empty)
                    discard(inFlight.decrementAndGet())
            end try
            0
        }

    private val drainCallback: CFuncPtr0[Unit] =
        CFuncPtr0.fromScalaFunction { () =>
            var resp = responseQueue.poll()
            while resp != null do
                try
                    resp match
                        case br: BufferedResponse =>
                            sendBufferedNative(br.req, br.status, br.headers, br.body)
                        case sr: StreamStartResponse =>
                            startStreamingNative(sr.streamCtx, sr.status, sr.headers)
                            sr.streamCtx.tryDeliver()
                        case sc: StreamChunkNotify =>
                            sc.streamCtx.tryDeliver()
                        case er: ErrorResponse =>
                            sendImmediateError(er.req, er.status, er.extraHeaders)
                catch case _: Throwable => ()
                end try
                resp = responseQueue.poll()
            end while
        }

    private val proceedCallback: CFuncPtr1[CInt, Unit] =
        CFuncPtr1.fromScalaFunction { (streamId: CInt) =>
            val ctx = streams.get(streamId: Int)
            if ctx != null then ctx.onProceed()
        }

    private val stopCallback: CFuncPtr1[CInt, Unit] =
        CFuncPtr1.fromScalaFunction { (streamId: CInt) =>
            val ctx = streams.remove(streamId)
            if ctx != null then
                ctx.onStop()
                ctx.decrementInFlight()
        }

    // ── Server lifecycle ────────────────────────────────────────────────

    private def startServer(r: HttpRouter, config: HttpServer.Config): HttpBackend.Binding =
        router = r
        Zone {
            server = H2oBindings.start(
                toCString(config.host),
                config.port,
                config.maxContentLength,
                config.backlog
            )
        }
        if server == null then throw new RuntimeException("h2o server start failed")

        H2oBindings.setHandler(server, handlerCallback)
        H2oBindings.setDrain(server, drainCallback)
        H2oBindings.setProceed(server, proceedCallback)
        H2oBindings.setStop(server, stopCallback)

        evloopThread = new Thread(
            () => while H2oBindings.evloopRunOnce(server) == 0 do (),
            "kyo-h2o-evloop"
        )
        evloopThread.setDaemon(true)
        evloopThread.start()

        val boundPort = H2oBindings.port(server)

        new HttpBackend.Binding:
            def port: Int    = boundPort
            def host: String = config.host
            def close(gracePeriod: Duration)(using Frame): Unit < Async =
                Sync.defer {
                    H2oBindings.stop(server)
                    evloopThread.join(gracePeriod.toMillis.max(5000))
                    H2oBindings.destroy(server)
                    server = null
                    router = null
                    evloopThread = null
                }
            def await(using Frame): Unit < Async =
                Async.sleep(Duration.Infinity)
        end new
    end startServer

    // ── Request handling (called from handlerCallback on evloop thread) ──

    private def handleRequest(req: H2oReq): Unit =
        val methodPtr = H2oBindings.reqMethod(req)
        val methodLen = H2oBindings.reqMethodLen(req)
        val method    = HttpMethod.unsafe(new String(fromCStringLen(methodPtr, methodLen)))

        val pathPtr  = H2oBindings.reqPath(req)
        val pathLen  = H2oBindings.reqPathLen(req)
        val fullPath = new String(fromCStringLen(pathPtr, pathLen))
        val queryAt  = H2oBindings.reqQueryAt(req)
        val path     = if queryAt >= 0 && queryAt < fullPath.length then fullPath.substring(0, queryAt) else fullPath

        router.find(method, path) match
            case Result.Success(routeMatch) =>
                val headers = readHeaders(req)
                val body    = readBody(req)

                val endpoint = routeMatch.endpoint
                val route    = endpoint.route

                val queryFn: Maybe[HttpUrl] =
                    if queryAt < 0 then Absent
                    else Present(HttpUrl.fromUri(fullPath))

                val decoded =
                    if routeMatch.isStreamingRequest then
                        val bodyStream =
                            if body.isEmpty then Stream.empty[Span[Byte]]
                            else Stream(Emit.value(Chunk(body)))
                        RouteUtil.decodeStreamingRequest(
                            route,
                            routeMatch.pathCaptures,
                            queryFn,
                            headers,
                            bodyStream,
                            path
                        )
                    else
                        RouteUtil.decodeBufferedRequest(
                            route,
                            routeMatch.pathCaptures,
                            queryFn,
                            headers,
                            body,
                            path
                        )

                decoded match
                    case Result.Success(request) =>
                        launchHandlerFiber(req, endpoint, route, request)
                    case Result.Failure(_) =>
                        sendImmediateError(req, 400, HttpHeaders.empty)
                        discard(inFlight.decrementAndGet())
                    case Result.Panic(_) =>
                        sendImmediateError(req, 500, HttpHeaders.empty)
                        discard(inFlight.decrementAndGet())
                end match

            case Result.Failure(FindError.NotFound) =>
                sendImmediateError(req, 404, HttpHeaders.empty)
                discard(inFlight.decrementAndGet())

            case Result.Failure(FindError.MethodNotAllowed(allowed)) =>
                val allowValue = allowed.iterator.map(_.name).mkString(", ")
                sendImmediateError(req, 405, HttpHeaders.empty.add("Allow", allowValue))
                discard(inFlight.decrementAndGet())

            case Result.Panic(_) =>
                sendImmediateError(req, 500, HttpHeaders.empty)
                discard(inFlight.decrementAndGet())
        end match
    end handleRequest

    // ── Request parsing helpers ─────────────────────────────────────────

    private def readHeaders(req: H2oReq): HttpHeaders =
        val count   = H2oBindings.reqHeaderCount(req)
        val builder = ChunkBuilder.init[String]
        var i       = 0
        while i < count do
            val namePtr  = H2oBindings.reqHeaderName(req, i)
            val nameLen  = H2oBindings.reqHeaderNameLen(req, i)
            val valuePtr = H2oBindings.reqHeaderValue(req, i)
            val valueLen = H2oBindings.reqHeaderValueLen(req, i)
            discard(builder += new String(fromCStringLen(namePtr, nameLen)))
            discard(builder += new String(fromCStringLen(valuePtr, valueLen)))
            i += 1
        end while
        HttpHeaders.fromChunk(builder.result())
    end readHeaders

    private def readBody(req: H2oReq): Span[Byte] =
        val bodyLen = H2oBindings.reqBodyLen(req)
        if bodyLen <= 0 then Span.empty[Byte]
        else
            val bodyPtr = H2oBindings.reqBody(req)
            val bytes   = new Array[Byte](bodyLen)
            var i       = 0
            while i < bodyLen do
                bytes(i) = bodyPtr(i)
                i += 1
            Span.fromUnsafe(bytes)
        end if
    end readBody

    private def fromCStringLen(ptr: CString, len: Int): Array[Byte] =
        val bytes = new Array[Byte](len)
        var i     = 0
        while i < len do
            bytes(i) = ptr(i)
            i += 1
        bytes
    end fromCStringLen

    // ── Fiber launch ────────────────────────────────────────────────────

    private def launchHandlerFiber(
        req: H2oReq,
        handler: HttpHandler[?, ?, ?],
        route: HttpRoute[?, ?, ?],
        request: HttpRequest[?]
    ): Unit =
        val h  = handler.asInstanceOf[HttpHandler[Any, Any, Any]]
        val r  = request.asInstanceOf[HttpRequest[Any]]
        val rt = route.asInstanceOf[HttpRoute[Any, Any, Any]]

        val fiber = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped {
            Abort.run[Any](h(r)).map { outerResult =>
                outerResult match
                    case Result.Success(innerAny) =>
                        val innerResult = innerAny.asInstanceOf[Result[Any, Any]]
                        innerResult match
                            case Result.Success(rawResponse) =>
                                val resp = rawResponse.asInstanceOf[HttpResponse[Any]]
                                encodeAndEnqueue(req, rt, resp)
                            case Result.Failure(err) =>
                                err match
                                    case halt: HttpResponse.Halt =>
                                        enqueueBuffered(req, halt.response.status.code, halt.response.headers, Span.empty[Byte])
                                    case _ =>
                                        enqueueError(req, 500, HttpHeaders.empty)
                            case Result.Panic(_) =>
                                enqueueError(req, 500, HttpHeaders.empty)
                        end match
                    case Result.Failure(_) | Result.Panic(_) =>
                        enqueueError(req, 500, HttpHeaders.empty)
                end match
            }
        })

        fiber.unsafe.onComplete {
            case Result.Failure(_) | Result.Panic(_) =>
                enqueueError(req, 500, HttpHeaders.empty)
            case _ => ()
        }
    end launchHandlerFiber

    // ── Response encoding and enqueueing ────────────────────────────────

    private def encodeAndEnqueue(
        req: H2oReq,
        route: HttpRoute[Any, Any, Any],
        response: HttpResponse[Any]
    ): Unit =
        RouteUtil.encodeResponse(route, response)(
            onEmpty = (status, headers) =>
                enqueueBuffered(req, status.code, headers, Span.empty[Byte]),
            onBuffered = (status, headers, contentType, body) =>
                enqueueBuffered(req, status.code, headers.add("Content-Type", contentType), body),
            onStreaming = (status, headers, contentType, stream) =>
                val streamId      = nextStreamId.getAndIncrement()
                val ctx           = new StreamContext(req, streamId, null)
                val headersWithCt = headers.add("Content-Type", contentType)
                discard(streams.put(streamId, ctx))
                discard(responseQueue.add(new StreamStartResponse(ctx, status.code, headersWithCt)))
                H2oBindings.wake(server)

                discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped {
                    Scope.run {
                        Abort.run[Any] {
                            stream.foreach { span =>
                                Sync.defer {
                                    if !ctx.stopped then
                                        ctx.enqueueChunk(span.toArrayUnsafe, isFinal = false)
                                        discard(responseQueue.add(new StreamChunkNotify(ctx)))
                                        H2oBindings.wake(server)
                                }
                            }.andThen {
                                Sync.defer {
                                    ctx.enqueueChunk(Array.emptyByteArray, isFinal = true)
                                    discard(responseQueue.add(new StreamChunkNotify(ctx)))
                                    H2oBindings.wake(server)
                                }
                            }
                        }.map {
                            case Result.Failure(_) | Result.Panic(_) =>
                                if !ctx.stopped then
                                    ctx.enqueueChunk(Array.emptyByteArray, isFinal = true)
                                    discard(responseQueue.add(new StreamChunkNotify(ctx)))
                                    H2oBindings.wake(server)
                                end if
                            case _ => ()
                        }
                    }
                }))
        )
    end encodeAndEnqueue

    private def enqueueBuffered(req: H2oReq, status: Int, headers: HttpHeaders, body: Span[Byte]): Unit =
        discard(responseQueue.add(new BufferedResponse(req, status, headers, body)))
        H2oBindings.wake(server)
        discard(inFlight.decrementAndGet())
    end enqueueBuffered

    private def enqueueError(req: H2oReq, status: Int, headers: HttpHeaders): Unit =
        discard(responseQueue.add(new ErrorResponse(req, status, headers)))
        H2oBindings.wake(server)
        discard(inFlight.decrementAndGet())
    end enqueueError

    // ── Native response helpers (called on evloop thread) ───────────────

    private def sendImmediateError(req: H2oReq, status: Int, headers: HttpHeaders): Unit =
        Zone {
            val (names, nameLens, values, valueLens, headerCount) = encodeHeaders(headers)
            H2oBindings.sendError(req, status, names, nameLens, values, valueLens, headerCount)
        }

    private def sendBufferedNative(req: H2oReq, status: Int, headers: HttpHeaders, body: Span[Byte]): Unit =
        Zone {
            val (names, nameLens, values, valueLens, headerCount) = encodeHeaders(headers)

            val bodyBytes = if body.isEmpty then Array.emptyByteArray else body.toArrayUnsafe
            val bodyPtr =
                if bodyBytes.isEmpty then null
                else
                    val p = alloc[Byte](bodyBytes.length)
                    var j = 0
                    while j < bodyBytes.length do
                        p(j) = bodyBytes(j)
                        j += 1
                    p

            H2oBindings.sendBuffered(req, status, names, nameLens, values, valueLens, headerCount, bodyPtr, bodyBytes.length)
        }

    private def startStreamingNative(ctx: StreamContext, status: Int, headers: HttpHeaders): Unit =
        Zone {
            val (names, nameLens, values, valueLens, headerCount) = encodeHeaders(headers)
            ctx.generator = H2oBindings.startStreaming(
                server,
                ctx.req,
                status,
                names,
                nameLens,
                values,
                valueLens,
                headerCount,
                ctx.streamId
            )
        }

    private def encodeHeaders(headers: HttpHeaders)(using Zone): (Ptr[CString], Ptr[CInt], Ptr[CString], Ptr[CInt], Int) =
        var headerCount = 0
        headers.foreach { (_, _) => headerCount += 1 }
        val sz        = if headerCount > 0 then headerCount else 1
        val names     = alloc[CString](sz)
        val nameLens  = alloc[CInt](sz)
        val values    = alloc[CString](sz)
        val valueLens = alloc[CInt](sz)
        var i         = 0
        headers.foreach { (k, v) =>
            names(i) = toCString(k)
            nameLens(i) = k.length
            values(i) = toCString(v)
            valueLens(i) = v.length
            i += 1
        }
        (names, nameLens, values, valueLens, headerCount)
    end encodeHeaders

end H2oServerBackend
