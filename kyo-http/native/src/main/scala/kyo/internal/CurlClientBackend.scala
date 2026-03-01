package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import kyo.<
import kyo.Abort
import kyo.AllowUnsafe
import kyo.Async
import kyo.AtomicBoolean
import kyo.Channel
import kyo.Chunk
import kyo.Closed
import kyo.Duration
import kyo.Emit
import kyo.Fiber
import kyo.Frame
import kyo.HttpBackend
import kyo.HttpError as Http2Error
import kyo.HttpHeaders as Http2Headers
import kyo.HttpMethod
import kyo.HttpRequest
import kyo.HttpResponse
import kyo.HttpRoute
import kyo.HttpStatus
import kyo.HttpUrl
import kyo.Loop
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Promise
import kyo.Result
import kyo.Span
import kyo.Stream
import kyo.Sync
import kyo.discard
import kyo.internal.CurlBindings
import kyo.internal.CurlBindings.*
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.runtime.fromRawPtr
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Native HTTP client backend using libcurl via curl_multi for async I/O.
  *
  * Each `sendWith` creates a fresh curl easy handle, configures it via RouteUtil.encodeRequest, and enqueues it on the shared event loop.
  * curl_multi handles DNS/TCP/TLS connection reuse internally.
  */
final class CurlClientBackend(daemon: Boolean) extends HttpBackend.Client:

    locally { discard(curl_global_init(CURL_GLOBAL_DEFAULT)) }

    type Connection = CurlClientBackend.Conn

    private val eventLoop = new CurlEventLoop2(daemon)

    def connectWith[A, S](
        host: String,
        port: Int,
        ssl: Boolean,
        connectTimeout: Maybe[Duration]
    )(
        f: Connection => A < S
    )(using Frame): A < (S & Async & Abort[Http2Error]) =
        // Connection is a lightweight token — curl_multi manages actual TCP connections
        f(CurlClientBackend.Conn(host, port, ssl, connectTimeout))

    def sendWith[In, Out, A, S](
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onReleaseUnsafe: Maybe[Result.Error[Any]] => Unit
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[Http2Error]) =
        if RouteUtil.isStreamingResponse(route) then
            sendStreaming(conn, route, request, onReleaseUnsafe)(f)
        else
            sendBuffered(conn, route, request, onReleaseUnsafe)(f)

    private def sendBuffered[In, Out, A, S](
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onReleaseUnsafe: Maybe[Result.Error[Any]] => Unit
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[Http2Error]) =
        Sync.ensure(onReleaseUnsafe) {
            Sync.Unsafe.defer {
                Zone {
                    val handle = curl_easy_init()
                    if handle == null then
                        Abort.fail(Http2Error.ConnectionError(
                            s"curl_easy_init failed for ${conn.host}:${conn.port}",
                            new RuntimeException("curl_easy_init returned null")
                        ))
                    else
                        val transferId = CurlClientBackend.nextTransferId.getAndIncrement()
                        val promise    = Promise.Unsafe.init[CurlBufferedResult, Abort[Http2Error]]()
                        val state      = new CurlBufferedTransferState(promise, handle, conn.host, conn.port)
                        configureHandle(handle, conn, route, request, transferId, state)
                        eventLoop.enqueue(transferId, state)
                        promise.safe.use { result =>
                            Abort.get(RouteUtil.decodeBufferedResponse(
                                route,
                                HttpStatus(result.statusCode),
                                result.headers,
                                result.body
                            )).map(f)
                        }
                    end if
                }
            }
        }

    private def sendStreaming[In, Out, A, S](
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onReleaseUnsafe: Maybe[Result.Error[Any]] => Unit
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[Http2Error]) =
        Sync.Unsafe.defer {
            val phase2Started       = AtomicBoolean.Unsafe.init(false)
            val streamFullyConsumed = AtomicBoolean.Unsafe.init(false)
            Sync.ensure { error =>
                Sync.Unsafe.defer {
                    if !phase2Started.get() then
                        onReleaseUnsafe(error)
                }
            } {
                Zone {
                    val handle = curl_easy_init()
                    if handle == null then
                        Abort.fail(Http2Error.ConnectionError(
                            s"curl_easy_init failed for ${conn.host}:${conn.port}",
                            new RuntimeException("curl_easy_init returned null")
                        ))
                    else
                        val transferId    = CurlClientBackend.nextTransferId.getAndIncrement()
                        val headerPromise = Promise.Unsafe.init[CurlStreamingHeaders, Abort[Http2Error]]()
                        val byteChannel   = Channel.Unsafe.init[Maybe[Span[Byte]]](32)
                        val state         = new CurlStreamingTransferState(headerPromise, byteChannel, handle, conn.host, conn.port)
                        configureHandle(handle, conn, route, request, transferId, state)
                        eventLoop.enqueue(transferId, state)

                        headerPromise.safe.use { sh =>
                            Sync.Unsafe.defer(phase2Started.set(true)).andThen {
                                val bodyStream = Stream[Span[Byte], Async] {
                                    Sync.ensure { error =>
                                        Sync.Unsafe.defer {
                                            if !streamFullyConsumed.get() then
                                                onReleaseUnsafe(Maybe(Result.Panic(new Exception("stream not fully consumed"))))
                                            else
                                                onReleaseUnsafe(error)
                                        }
                                    } {
                                        Loop.foreach {
                                            byteChannel.safe.takeWith {
                                                case Present(bytes) =>
                                                    Emit.valueWith(Chunk(bytes))(Loop.continue)
                                                case Absent =>
                                                    Sync.Unsafe.defer(streamFullyConsumed.set(true)).andThen(Loop.done(()))
                                            }
                                        }.handle(Abort.run[Closed]).unit
                                    }
                                }
                                Abort.get(RouteUtil.decodeStreamingResponse(
                                    route,
                                    HttpStatus(sh.statusCode),
                                    sh.headers,
                                    bodyStream
                                )).map(f)
                            }
                        }
                    end if
                }
            }
        }

    private def configureHandle[In, Out](
        handle: CURL,
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        transferId: Long,
        state: CurlTransferState
    )(using Zone, Frame, AllowUnsafe): Unit =
        RouteUtil.encodeRequest(route, request)(
            onEmpty = (url, headers) =>
                configureCommon(handle, conn, route, url, headers, request.headers, transferId, state, request.method),
            onBuffered = (url, headers, contentType, body) =>
                configureCommon(handle, conn, route, url, headers, request.headers, transferId, state, request.method)
                // Set content type
                var headerList = state.headerList
                headerList = curl_slist_append(headerList, toCString(s"Content-Type: $contentType"))
                state.setHeaderList(headerList)
                if headerList != null then
                    discard(curl_easy_setopt(handle, CURLOPT_HTTPHEADER, headerList))
                // Set body
                if !body.isEmpty then
                    val bodyArr = body.toArrayUnsafe
                    discard(curl_easy_setopt(handle, CURLOPT_POSTFIELDSIZE_LARGE, bodyArr.length.toLong))
                    val bodyPtr = stackalloc[Byte](bodyArr.length)
                    var i       = 0
                    while i < bodyArr.length do
                        bodyPtr(i) = bodyArr(i)
                        i += 1
                    end while
                    discard(curl_easy_setopt(handle, CURLOPT_COPYPOSTFIELDS, bodyPtr))
                end if
            ,
            onStreaming = (url, headers, contentType, stream) =>
                configureCommon(handle, conn, route, url, headers, request.headers, transferId, state, request.method)
                var headerList = state.headerList
                headerList = curl_slist_append(headerList, toCString(s"Content-Type: $contentType"))
                headerList = curl_slist_append(headerList, toCString("Expect:")) // suppress 100-continue
                state.setHeaderList(headerList)
                if headerList != null then
                    discard(curl_easy_setopt(handle, CURLOPT_HTTPHEADER, headerList))

                // Set up read callback for streaming body
                val readChannel = Channel.Unsafe.init[Maybe[Span[Byte]]](8)
                state.readState = new CurlReadState(readChannel)

                discard(curl_easy_setopt(handle, CURLOPT_UPLOAD, 1L))
                discard(curl_easy_setopt(handle, CURLOPT_READFUNCTION, CurlEventLoop2.readCallback))
                val idPtr = fromRawPtr[Byte](Intrinsics.castLongToRawPtr(transferId))
                discard(curl_easy_setopt(handle, CURLOPT_READDATA, idPtr))

                // Launch fiber to drain stream into channel
                discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped {
                    stream.foreach { span =>
                        readChannel.safe.put(Present(span)).andThen(Sync.defer(eventLoop.wakeUp()))
                    }.andThen {
                        readChannel.safe.put(Absent).andThen(Sync.defer(eventLoop.wakeUp()))
                    }.handle(Abort.run[Closed]).unit
                }))
        )
    end configureHandle

    private def configureCommon[In, Out](
        handle: CURL,
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        url: String,
        routeHeaders: Http2Headers,
        requestHeaders: Http2Headers,
        transferId: Long,
        state: CurlTransferState,
        method: HttpMethod
    )(using Zone): Unit =
        // Build full URL
        val scheme  = if conn.ssl then "https" else "http"
        val portStr = if (conn.ssl && conn.port == 443) || (!conn.ssl && conn.port == 80) then "" else s":${conn.port}"
        val fullUrl = s"$scheme://${conn.host}$portStr$url"
        discard(curl_easy_setopt(handle, CURLOPT_URL, toCString(fullUrl)))

        // Method
        discard(curl_easy_setopt(handle, CURLOPT_CUSTOMREQUEST, toCString(method.name)))
        if method == HttpMethod.HEAD then
            discard(curl_easy_setopt(handle, CURLOPT_NOBODY, 1L))

        // Disable signals (required for multi-threaded use)
        discard(curl_easy_setopt(handle, CURLOPT_NOSIGNAL, 1L))

        // Don't follow redirects (shared code handles this)
        discard(curl_easy_setopt(handle, CURLOPT_FOLLOWLOCATION, 0L))

        // Connect timeout
        conn.connectTimeout.foreach { timeout =>
            discard(curl_easy_setopt(handle, CURLOPT_CONNECTTIMEOUT_MS, timeout.toMillis))
        }

        // Headers — merge route-generated headers and request headers
        var headerList: Ptr[Byte] = null
        requestHeaders.foreach { (k, v) =>
            headerList = curl_slist_append(headerList, toCString(s"$k: $v"))
        }
        routeHeaders.foreach { (k, v) =>
            headerList = curl_slist_append(headerList, toCString(s"$k: $v"))
        }
        // Set Host header if not already present
        val hasHost = requestHeaders.contains("Host") || routeHeaders.contains("Host")
        if !hasHost then
            val hostHeader =
                if (conn.ssl && conn.port == 443) || (!conn.ssl && conn.port == 80) then conn.host
                else s"${conn.host}:${conn.port}"
            headerList = curl_slist_append(headerList, toCString(s"Host: $hostHeader"))
        end if
        if headerList != null then
            discard(curl_easy_setopt(handle, CURLOPT_HTTPHEADER, headerList))
            state.setHeaderList(headerList)
        end if

        // Write and header callbacks with transferId as userdata
        val idPtr = fromRawPtr[Byte](Intrinsics.castLongToRawPtr(transferId))
        discard(curl_easy_setopt(handle, CURLOPT_WRITEFUNCTION, CurlEventLoop2.writeCallback))
        discard(curl_easy_setopt(handle, CURLOPT_WRITEDATA, idPtr))
        discard(curl_easy_setopt(handle, CURLOPT_HEADERFUNCTION, CurlEventLoop2.headerCallback))
        discard(curl_easy_setopt(handle, CURLOPT_HEADERDATA, idPtr))
    end configureCommon

    def isAlive(conn: Connection)(using AllowUnsafe): Boolean = true

    def closeNowUnsafe(conn: Connection)(using AllowUnsafe): Unit = ()

    def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async = ()

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(eventLoop.shutdown())

end CurlClientBackend

object CurlClientBackend:
    private val nextTransferId: AtomicLong = new AtomicLong(1)

    /** Lightweight connection token. curl_multi manages actual TCP connections internally. */
    final case class Conn(
        host: String,
        port: Int,
        ssl: Boolean,
        connectTimeout: Maybe[Duration]
    )
end CurlClientBackend
