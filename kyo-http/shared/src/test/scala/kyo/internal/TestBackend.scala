package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kyo.*

/** In-memory Backend for cross-platform tests. Routes client requests directly through stored server handlers without TCP. */
object TestBackend extends Backend:

    private case class ServerEntry(handler: Backend.ServerHandler, maxContentLength: Int)

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
                        new TestConnection(entry.handler, entry.maxContentLength)

            def close(gracePeriod: Duration)(using Frame): Unit < Async = ()
        end new
    end connectionFactory

    def server(
        serverPort: Int,
        serverHost: String,
        maxContentLength: Int,
        backlog: Int,
        keepAlive: Boolean,
        tcpFastOpen: Boolean,
        flushConsolidationLimit: Int,
        handler: Backend.ServerHandler
    )(using Frame): Backend.Server < Async =
        val assignedPort = if serverPort == 0 then nextPort.getAndIncrement() else serverPort
        servers.put(assignedPort, ServerEntry(handler, maxContentLength))
        new Backend.Server:
            def port: Int    = assignedPort
            def host: String = serverHost
            def close(gracePeriod: Duration)(using Frame): Unit < Async =
                servers.remove(assignedPort)
                ()
            def await(using Frame): Unit < Async =
                Async.sleep(Duration.Infinity)
        end new
    end server

end TestBackend

private class TestConnection(handler: Backend.ServerHandler, maxContentLength: Int) extends Backend.Connection:

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        if request.body.span.size > maxContentLength then
            HttpResponse(HttpResponse.Status.PayloadTooLarge)
        else
            val path = extractPath(request.url)
            handler.reject(request.method, path) match
                case Present(response) => response
                case Absent =>
                    Abort.run[Nothing](handler.handle(request)).map {
                        case Result.Success(resp) => ensureBytes(resp)
                        case Result.Panic(e) => HttpResponse.serverError(if e.getMessage != null then e.getMessage else e.getClass.getName)
                        case Result.Failure(_) => HttpResponse.serverError("unexpected failure")
                    }
            end match
    end send

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        val path = extractPath(request.url)
        handler.reject(request.method, path) match
            case Present(response) =>
                // Match Netty behavior: error statuses abort with StatusError for streaming
                if response.status.isError then
                    Abort.fail(HttpError.StatusError(response.status, response.body.span.toArrayUnsafe.map(_.toChar).mkString))
                else
                    response.withBody(HttpBody.stream(Stream.init(Chunk(response.body.span))))
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
                                s.stream.run.map { chunks =>
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
                Abort.run[Nothing](result).map {
                    case Result.Success(resp) =>
                        // Match Netty behavior: error statuses abort with StatusError for streaming
                        if resp.status.isError then
                            Abort.fail(HttpError.StatusError(resp.status, ""))
                        else
                            resp
                    case Result.Panic(e) =>
                        val msg = if e.getMessage != null then e.getMessage else e.getClass.getName
                        ensureStreamed(HttpResponse.serverError(msg))
                    case Result.Failure(_) =>
                        ensureStreamed(HttpResponse.serverError("unexpected failure"))
                }
        end match
    end stream

    def isAlive: Boolean = true

    def close(using Frame): Unit < Async = ()

    private[kyo] def closeAbruptly(): Unit = ()

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

    private def ensureBytes(response: HttpResponse[?])(using Frame): HttpResponse[HttpBody.Bytes] < Async =
        response.body.use(
            b => response.withBody(b),
            s =>
                s.stream.run.map { chunks =>
                    val totalSize = chunks.foldLeft(0)((acc, span) => acc + span.size)
                    val arr       = new Array[Byte](totalSize)
                    var pos       = 0
                    chunks.foreach { span =>
                        val bytes = span.toArrayUnsafe
                        java.lang.System.arraycopy(bytes, 0, arr, pos, bytes.length)
                        pos += bytes.length
                    }
                    response.withBody(HttpBody(arr))
                }
        )

    private def ensureStreamed(response: HttpResponse[?])(using Frame): HttpResponse[HttpBody.Streamed] =
        response.body.use(
            b => response.withBody(HttpBody.stream(Stream.init(Chunk(b.span)))),
            s => response.withBody(s)
        )

end TestConnection
