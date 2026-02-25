package kyo.internal

import java.io.ByteArrayOutputStream
import kyo.*
import kyo.HttpError as Http2Error
import kyo.HttpHeaders as Http2Headers
import kyo.internal.CurlBindings
import scala.scalanative.unsafe.*

/** Transfer state for a single curl easy handle in the http2 backend.
  *
  * Stores raw response data (status code, header lines, body bytes) and completes promises when the transfer finishes. The backend converts
  * raw data to typed HttpResponse via RouteUtil after completion.
  */
sealed private[kyo] trait CurlTransferState:
    def easyHandle: CurlBindings.CURL
    def headerList: Ptr[Byte]
    def setHeaderList(p: Ptr[Byte]): Unit
    def responseHeaders: StringBuilder
    def statusCode: Int
    def setStatusCode(code: Int): Unit
    var readState: CurlReadState = null
end CurlTransferState

/** State for streaming request body via curl read callback. */
final private[kyo] class CurlReadState(val channel: Channel.Unsafe[Maybe[Span[Byte]]]):
    var currentChunk: Array[Byte]   = null
    var offset: Int                 = 0
    @volatile var isPaused: Boolean = false
end CurlReadState

/** Raw response data from a completed buffered transfer. */
private[kyo] case class CurlBufferedResult(
    statusCode: Int,
    headers: Http2Headers,
    body: Span[Byte]
)

/** Raw response data from a completed streaming transfer's headers. */
private[kyo] case class CurlStreamingHeaders(
    statusCode: Int,
    headers: Http2Headers
)

final private[kyo] class CurlBufferedTransferState(
    val promise: Promise.Unsafe[CurlBufferedResult, Abort[Http2Error]],
    val easyHandle: CurlBindings.CURL,
    val host: String,
    val port: Int
) extends CurlTransferState:
    val responseBody: ByteArrayOutputStream = new ByteArrayOutputStream()
    val responseHeaders: StringBuilder      = new StringBuilder()
    private var _statusCode: Int            = 0
    private var _headerList: Ptr[Byte]      = null

    def statusCode: Int                   = _statusCode
    def setStatusCode(code: Int): Unit    = _statusCode = code
    def headerList: Ptr[Byte]             = _headerList
    def setHeaderList(p: Ptr[Byte]): Unit = _headerList = p
end CurlBufferedTransferState

final private[kyo] class CurlStreamingTransferState(
    val headerPromise: Promise.Unsafe[CurlStreamingHeaders, Abort[Http2Error]],
    val bodyChannel: Channel.Unsafe[Maybe[Span[Byte]]],
    val easyHandle: CurlBindings.CURL,
    val host: String,
    val port: Int
) extends CurlTransferState:
    val responseHeaders: StringBuilder = new StringBuilder()
    private var _statusCode: Int       = 0
    private var _headerList: Ptr[Byte] = null
    var headersCompleted: Boolean      = false
    @volatile var isPaused: Boolean    = false

    def statusCode: Int                   = _statusCode
    def setStatusCode(code: Int): Unit    = _statusCode = code
    def headerList: Ptr[Byte]             = _headerList
    def setHeaderList(p: Ptr[Byte]): Unit = _headerList = p
end CurlStreamingTransferState
