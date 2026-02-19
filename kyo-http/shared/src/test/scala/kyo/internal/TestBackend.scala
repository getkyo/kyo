package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kyo.*

/** In-memory Backend for cross-platform tests. Routes client requests directly through stored server handlers without TCP. */
object TestBackend extends Backend.Client with Backend.Server:

    private case class ServerEntry(handler: Backend.ServerHandler, maxContentLength: Int, closed: AtomicBoolean.Unsafe)

    private val nextPort = new AtomicInteger(50000)
    private val servers  = new ConcurrentHashMap[Int, ServerEntry]()

    def connectionFactory(maxResponseSizeBytes: Int, daemon: Boolean)(using AllowUnsafe): Backend.ConnectionFactory =
        new Backend.ConnectionFactory:
            def connect(host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(using
                Frame
            ): Backend.Connection < (Async & Abort[HttpError]) =
                servers.get(port) match
                    case null =>
                        Abort.fail(HttpError.ConnectionFailed(host, port, new RuntimeException(s"No test server on port $port")))
                    case entry =>
                        new TestConnection(entry.handler, entry.maxContentLength, maxResponseSizeBytes, entry.closed)

            def close(gracePeriod: Duration)(using Frame): Unit < Async = ()
        end new
    end connectionFactory

    def server(
        serverPort: Int,
        serverHost: String,
        maxContentLength: Int,
        backlog: Int,
        keepAlive: Boolean,
        handler: Backend.ServerHandler
    )(using Frame): Backend.Server.Binding < Async =
        Sync.Unsafe.defer {
            val assignedPort = if serverPort == 0 then nextPort.getAndIncrement() else serverPort
            val closed       = AtomicBoolean.Unsafe.init(false)
            servers.put(assignedPort, ServerEntry(handler, maxContentLength, closed))
            new Backend.Server.Binding:
                def port: Int    = assignedPort
                def host: String = serverHost
                def close(gracePeriod: Duration)(using Frame): Unit < Async =
                    Sync.Unsafe.defer {
                        closed.set(true)
                        discard(servers.remove(assignedPort))
                    }
                def await(using Frame): Unit < Async =
                    Async.sleep(Duration.Infinity)
            end new
        }
    end server

end TestBackend

private class TestConnection(handler: Backend.ServerHandler, maxContentLength: Int, maxResponseSizeBytes: Int, closed: AtomicBoolean.Unsafe)
    extends Backend.Connection:

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        if request.body.span.size > maxContentLength then
            HttpResponse(HttpStatus.PayloadTooLarge)
        else
            val path = extractPath(request.url)
            handler.reject(request.method, path) match
                case Present(response) => response
                case Absent =>
                    handler.handle(request)
                        .handle(Abort.recover[Nothing](_ => HttpResponse.serverError(""), e => HttpResponse.serverError(errorMessage(e))))
                        .map(ensureBytes)
                        .map { response =>
                            // Per RFC 9110, HEAD responses must not include a body
                            if request.method == HttpRequest.Method.HEAD then
                                response.withBody(HttpBody.empty)
                            else response
                        }
                        .map { response =>
                            if response.body.data.length > maxResponseSizeBytes then
                                Abort.fail[HttpError](HttpError.InvalidResponse(
                                    s"Response body size ${response.body.data.length} exceeds limit $maxResponseSizeBytes"
                                ))
                            else response
                        }
            end match
    end send

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Abort[HttpError]) =
        val path = extractPath(request.url)
        handler.reject(request.method, path) match
            case Present(response) =>
                ensureStreamed(response)
            case Absent =>
                val result =
                    if handler.isStreaming(request.method, path) then
                        // For streaming request handlers, pass the request as-is
                        request.body.use(
                            b =>
                                // Wrap buffered body as a stream for the handler
                                val streamReq = HttpRequest.fromRawStreaming(
                                    request.method,
                                    request.url,
                                    request.headers,
                                    Stream.init(Chunk(b.span))
                                )
                                handler.handleStreaming(streamReq).map(ensureStreamed)
                            ,
                            _ =>
                                handler.handleStreaming(request.asInstanceOf[HttpRequest[HttpBody.Streamed]]).map(ensureStreamed)
                        )
                    else
                        // Non-streaming handler: materialize request body to bytes, then handle
                        request.body.use(
                            _ =>
                                handler.handle(request.asInstanceOf[HttpRequest[HttpBody.Bytes]]).map(ensureStreamed),
                            s =>
                                Scope.run(s.stream.run).map { chunks =>
                                    val totalSize = chunks.foldLeft(0)((acc, span) => acc + span.size)
                                    val arr       = new Array[Byte](totalSize)
                                    var pos       = 0
                                    chunks.foreach { span =>
                                        val bytes = span.toArrayUnsafe
                                        java.lang.System.arraycopy(bytes, 0, arr, pos, bytes.length)
                                        pos += bytes.length
                                    }
                                    val bytesReq = HttpRequest.fromRawHeaders(request.method, request.url, request.headers, arr)
                                    handler.handle(bytesReq).map(ensureStreamed)
                                }
                        )
                result
                    .handle(Abort.recover[Nothing](
                        _ => ensureStreamed(HttpResponse.serverError("")),
                        e => ensureStreamed(HttpResponse.serverError(errorMessage(e)))
                    ))
        end match
    end stream

    def isAlive(using AllowUnsafe): Boolean = !closed.get()

    def close(using Frame): Unit < Async = ()

    private[kyo] def closeAbruptly()(using AllowUnsafe): Unit = ()

    private def extractPath(url: String): String =
        // URL may be just a path like "/foo" or a full URL like "http://localhost:50000/foo"
        val pathStart = url.indexOf("://")
        if pathStart >= 0 then
            val afterScheme = url.indexOf('/', pathStart + 3)
            if afterScheme >= 0 then
                val qIdx = url.indexOf('?', afterScheme)
                if qIdx >= 0 then url.substring(afterScheme, qIdx) else url.substring(afterScheme)
            else "/"
            end if
        else
            val qIdx = url.indexOf('?')
            if qIdx >= 0 then url.substring(0, qIdx) else url
        end if
    end extractPath

    private def errorMessage(e: Throwable): String =
        if e.getMessage != null then e.getMessage else e.getClass.getName

    private def materialize[B <: HttpBody](response: HttpResponse[B]): HttpResponse[B] =
        response.materializeCookies

    private def ensureBytes(response: HttpResponse[?])(using Frame): HttpResponse[HttpBody.Bytes] < Async =
        response.body.use(
            b => materialize(response.withBody(b)),
            s =>
                Scope.run(s.stream.run).map { chunks =>
                    val totalSize = chunks.foldLeft(0)((acc, span) => acc + span.size)
                    val arr       = new Array[Byte](totalSize)
                    var pos       = 0
                    chunks.foreach { span =>
                        val bytes = span.toArrayUnsafe
                        java.lang.System.arraycopy(bytes, 0, arr, pos, bytes.length)
                        pos += bytes.length
                    }
                    materialize(response.withBody(HttpBody(arr)))
                }
        )

    private def ensureStreamed(response: HttpResponse[?])(using Frame): HttpResponse[HttpBody.Streamed] =
        response.body.use(
            b => materialize(response.withBody(HttpBody.stream(Stream.init(Chunk(b.span))))),
            s => materialize(response.withBody(s))
        )

end TestConnection
