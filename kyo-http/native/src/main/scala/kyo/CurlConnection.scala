package kyo

import java.util.concurrent.atomic.AtomicLong
import kyo.internal.BufferedTransferState
import kyo.internal.CurlBindings
import kyo.internal.CurlEventLoop
import kyo.internal.StreamingHeaders
import kyo.internal.StreamingTransferState
import kyo.internal.TransferState
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.runtime.fromRawPtr
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** A Backend.Connection wrapping curl easy handles. */
final private[kyo] class CurlConnection(
    host: String,
    port: Int,
    ssl: Boolean,
    eventLoop: CurlEventLoop,
    maxResponseSizeBytes: Int,
    connectTimeout: Maybe[Duration]
) extends Backend.Connection:
    import AllowUnsafe.embrace.danger
    import CurlBindings.*

    private val alive = AtomicBoolean.Unsafe.init(true)

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        Zone {
            val handle = curl_easy_init()
            if handle == null then
                Abort.fail(HttpError.ConnectionFailed(host, port, new RuntimeException("curl_easy_init failed")))
            else
                val transferId = CurlConnection.nextTransferId.getAndIncrement()
                val promise    = Promise.Unsafe.init[HttpResponse[HttpBody.Bytes], Abort[HttpError]]()
                val state      = new BufferedTransferState(promise, handle, host, port)
                configureHandle(handle, request, transferId, state)
                eventLoop.enqueue(transferId, state)
                promise.safe.get
            end if
        }
    end send

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        Zone {
            val handle = curl_easy_init()
            if handle == null then
                Abort.fail(HttpError.ConnectionFailed(host, port, new RuntimeException("curl_easy_init failed")))
            else
                val transferId    = CurlConnection.nextTransferId.getAndIncrement()
                val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()
                val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
                val state         = new StreamingTransferState(headerPromise, byteChannel, handle, host, port)
                configureHandle(handle, request, transferId, state)
                eventLoop.enqueue(transferId, state)

                headerPromise.safe.get.map { sh =>
                    val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                        Abort.run[Closed](byteChannel.safe.stream().emit).unit
                    }
                    HttpResponse.initStreaming(sh.status, sh.headers, bodyStream)
                }
            end if
        }
    end stream

    def isAlive(using AllowUnsafe): Boolean = alive.get()

    def close(using Frame): Unit < Async = Sync.Unsafe.defer { alive.set(false) }

    private[kyo] def closeAbruptly()(using AllowUnsafe): Unit = alive.set(false)

    private def configureHandle(handle: CURL, request: HttpRequest[?], transferId: Long, state: TransferState)(using Zone): Unit =
        // URL
        val url = HttpClient.buildUrl(host, port, ssl, request.url)
        discard(curl_easy_setopt(handle, CURLOPT_URL, toCString(url)))

        // Method
        discard(curl_easy_setopt(handle, CURLOPT_CUSTOMREQUEST, toCString(request.method.name)))
        if request.method == HttpRequest.Method.HEAD then
            discard(curl_easy_setopt(handle, CURLOPT_NOBODY, 1L))

        // Disable signals (required for multi-threaded use)
        discard(curl_easy_setopt(handle, CURLOPT_NOSIGNAL, 1L))

        // Don't follow redirects (shared code handles this)
        discard(curl_easy_setopt(handle, CURLOPT_FOLLOWLOCATION, 0L))

        // Connect timeout
        connectTimeout.foreach { timeout =>
            discard(curl_easy_setopt(handle, CURLOPT_CONNECTTIMEOUT_MS, timeout.toMillis))
        }

        // Headers
        var headerList: Ptr[Byte] = null
        request.headers.foreach { (k, v) =>
            headerList = curl_slist_append(headerList, toCString(s"$k: $v"))
        }
        request.contentType.foreach { ct =>
            headerList = curl_slist_append(headerList, toCString(s"Content-Type: $ct"))
        }
        // Set Host header if not already present
        val hasHost = request.headers.contains("Host")
        if !hasHost then
            val hostHeader =
                if port == HttpRequest.DefaultHttpPort || port == HttpRequest.DefaultHttpsPort then host
                else s"$host:$port"
            headerList = curl_slist_append(headerList, toCString(s"Host: $hostHeader"))
        end if
        if headerList != null then
            discard(curl_easy_setopt(handle, CURLOPT_HTTPHEADER, headerList))
            state.setHeaderList(headerList)
        end if

        // Body (for buffered requests)
        request.body.use(
            b =>
                if !b.isEmpty then
                    val bodyData = b.data
                    discard(curl_easy_setopt(handle, CURLOPT_POSTFIELDSIZE_LARGE, bodyData.length.toLong))
                    val bodyPtr = stackalloc[Byte](bodyData.length)
                    var i       = 0
                    while i < bodyData.length do
                        bodyPtr(i) = bodyData(i)
                        i += 1
                    end while
                    discard(curl_easy_setopt(handle, CURLOPT_COPYPOSTFIELDS, bodyPtr))
                end if
            ,
            _ => () // Streaming request body not supported via curl
        )

        // Write and header callbacks with transferId as userdata
        val idPtr = fromRawPtr[Byte](Intrinsics.castLongToRawPtr(transferId))
        discard(curl_easy_setopt(handle, CURLOPT_WRITEFUNCTION, CurlEventLoop.writeCallback))
        discard(curl_easy_setopt(handle, CURLOPT_WRITEDATA, idPtr))
        discard(curl_easy_setopt(handle, CURLOPT_HEADERFUNCTION, CurlEventLoop.headerCallback))
        discard(curl_easy_setopt(handle, CURLOPT_HEADERDATA, idPtr))
    end configureHandle

end CurlConnection

private[kyo] object CurlConnection:
    val nextTransferId: AtomicLong = new AtomicLong(1)
end CurlConnection
