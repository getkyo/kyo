package kyo.http2.internal

import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.AllowUnsafe
import kyo.Async
import kyo.Chunk
import kyo.Duration
import kyo.Emit
import kyo.Frame
import kyo.Loop
import kyo.Maybe
import kyo.Maybe.Present
import kyo.Result
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Sync
import kyo.discard
import kyo.http2.*
import org.scalajs.dom
import org.scalajs.dom.Headers as FetchHeaders
import org.scalajs.dom.RequestInit
import org.scalajs.dom.Response
import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array

/** JS client backend using the Fetch API.
  *
  * Each Connection is a lightweight handle (host/port/ssl). Fetch is stateless — every sendWith() issues an independent fetch(). The
  * connection pool will never reuse connections (isAlive = false) since Fetch handles its own pooling.
  */
final class FetchClientBackend extends HttpBackend.Client:

    type Connection = FetchClientBackend.FetchConnection

    def connectWith[A, S](
        host: String,
        port: Int,
        ssl: Boolean,
        connectTimeout: Maybe[Duration]
    )(
        f: Connection => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        f(new FetchClientBackend.FetchConnection(host, port, ssl))

    def sendWith[In, Out, A, S](
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(
        f: HttpResponse[Out] => A < S
    )(using Frame): A < (S & Async & Abort[HttpError]) =
        conn.send(route, request).map(f)

    def isAlive(conn: Connection)(using AllowUnsafe): Boolean = false

    def closeNowUnsafe(conn: Connection)(using AllowUnsafe): Unit = ()

    def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async = ()

    def close(gracePeriod: Duration)(using Frame): Unit < Async = ()

end FetchClientBackend

object FetchClientBackend:

    final class FetchConnection(
        val host: String,
        val port: Int,
        val ssl: Boolean
    ):
        // Pre-computed URL prefix to avoid per-request string interpolation
        private val urlPrefix: String =
            val scheme      = if ssl then "https" else "http"
            val defaultPort = if ssl then 443 else 80
            if port == defaultPort then s"$scheme://$host"
            else s"$scheme://$host:$port"
        end urlPrefix

        // MacrotaskExecutor avoids microtask starvation and aligns with kyo-scheduler's JS usage
        private given ExecutionContext = MacrotaskExecutor

        // Mutable fields set by encodeRequest, read immediately after in the same Sync.defer block.
        // Safe: connection pool ensures sequential access (one request at a time per connection).
        // RouteUtil.encodeRequest is inline CPS returning Unit — vars are the intended pattern (same as Netty).
        private var encodedUrl: String                                          = ""
        private var encodedHeaders: HttpHeaders                                 = HttpHeaders.empty
        private var encodedBody: Maybe[Uint8Array]                              = Absent
        private var encodedStreamBody: Maybe[Stream[Span[Byte], Async & Scope]] = Absent

        def send[In, Out](
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In]
        )(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
            if RouteUtil.isStreamingResponse(route) then
                sendStreaming(route, request)
            else
                sendBuffered(route, request)

        private def sendBuffered[In, Out](
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In]
        )(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
            Sync.defer {
                encodeRequest(route, request)
                val url        = encodedUrl
                val headers    = encodedHeaders
                val body       = encodedBody
                val streamBody = encodedStreamBody
                resetEncoded()

                if streamBody.isDefined then
                    // Streaming request body with buffered response — materialize first
                    Scope.run {
                        streamBody.get.run.map { chunks =>
                            doFetchBuffered(route, request.method, url, headers, Maybe(bytesToUint8Array(concatSpans(chunks))))
                        }
                    }
                else
                    doFetchBuffered(route, request.method, url, headers, body)
                end if
            }

        private def doFetchBuffered[In, Out](
            route: HttpRoute[In, Out, ?],
            method: HttpMethod,
            url: String,
            kyoHeaders: HttpHeaders,
            body: Maybe[Uint8Array]
        )(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
            // AbortController provides a signal that cancels the in-flight fetch request.
            // Sync.ensure registers controller.abort() to run when the fiber is interrupted,
            // which causes the fetch promise to reject with AbortError.
            // This is needed because Async.fromFuture does NOT cancel the underlying request.
            val controller = new dom.AbortController()
            val init       = buildInit(method, kyoHeaders, body)
            init.signal = controller.signal

            Sync.ensure(controller.abort()) {
                handleErrors {
                    Async.fromFuture(dom.fetch(url, init).toFuture).map { response =>
                        Async.fromFuture(response.arrayBuffer().toFuture).map { arrayBuffer =>
                            val int8    = new Int8Array(arrayBuffer)
                            val bytes   = Span.fromUnsafe(int8.toArray)
                            val status  = HttpStatus(response.status)
                            val headers = extractHeaders(response.headers)
                            RouteUtil.decodeBufferedResponse(route, status, headers, bytes) match
                                case Result.Success(r) => r
                                case Result.Failure(e) => Abort.fail(classifyError(e))
                                case Result.Panic(e)   => Abort.panic(e)
                            end match
                        }
                    }
                }
            }
        end doFetchBuffered

        private def sendStreaming[In, Out](
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In]
        )(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
            Sync.defer {
                encodeRequest(route, request)
                val url        = encodedUrl
                val headers    = encodedHeaders
                val body       = encodedBody
                val streamBody = encodedStreamBody
                resetEncoded()

                if streamBody.isDefined then
                    // Materialize streaming request body — Fetch API requires body before calling fetch()
                    Scope.run {
                        streamBody.get.run.map { chunks =>
                            doFetchStreaming(route, request.method, url, headers, Maybe(bytesToUint8Array(concatSpans(chunks))))
                        }
                    }
                else
                    doFetchStreaming(route, request.method, url, headers, body)
                end if
            }

        private def doFetchStreaming[In, Out](
            route: HttpRoute[In, Out, ?],
            method: HttpMethod,
            url: String,
            kyoHeaders: HttpHeaders,
            body: Maybe[Uint8Array]
        )(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
            val controller = new dom.AbortController()
            val init       = buildInit(method, kyoHeaders, body)
            init.signal = controller.signal

            handleErrors {
                Async.fromFuture(dom.fetch(url, init).toFuture).map { response =>
                    val status  = HttpStatus(response.status)
                    val headers = extractHeaders(response.headers)
                    val reader  = response.body.getReader()

                    val bodyStream: Stream[Span[Byte], Async & Scope] = Stream[Span[Byte], Async & Scope] {
                        // Abort the fetch when the stream's Scope closes (consumer done or fiber interrupted).
                        // Without this, interrupted fibers would leak the in-flight HTTP connection.
                        Scope.ensure(controller.abort()).andThen(Loop.foreach {
                            Abort.recover[Throwable](
                                // Network error mid-stream — propagate as Panic so the stream consumer sees the failure
                                e => throw classifyError(e),
                                e => throw classifyError(e)
                            ) {
                                Async.fromFuture(reader.read().toFuture).map { chunk =>
                                    if chunk.done then
                                        Loop.done[Unit, Unit](())
                                    else
                                        val uint8 = chunk.value
                                        val bytes = Span.fromUnsafe(new Int8Array(uint8.buffer, uint8.byteOffset, uint8.length).toArray)
                                        Emit.valueWith(Chunk(bytes))(Loop.continue[Unit])
                                }
                            }
                        })
                    }

                    RouteUtil.decodeStreamingResponse(route, status, headers, bodyStream) match
                        case Result.Success(r) => r
                        case Result.Failure(e) => Abort.fail(classifyError(e))
                        case Result.Panic(e)   => Abort.panic(e)
                    end match
                }
            }
        end doFetchStreaming

        /** Catches any Throwable (from Future rejection, network errors, etc.) and converts to typed HttpError via classifyError. */
        private def handleErrors[A, S](v: => A < (S & Abort[HttpError]))(using Frame): A < (S & Abort[HttpError]) =
            Abort.recoverError[Throwable](e => Abort.fail(classifyError(e.failureOrPanic.asInstanceOf[Throwable])))(
                v.asInstanceOf[A < (S & Abort[HttpError | Throwable])]
            ).asInstanceOf[A < (S & Abort[HttpError])]

        private def encodeRequest[In, Out](
            route: HttpRoute[In, Out, ?],
            request: HttpRequest[In]
        )(using Frame): Unit =
            RouteUtil.encodeRequest(route, request)(
                onEmpty = (url, headers) =>
                    encodedUrl = urlPrefix + url
                    encodedHeaders = headers
                    encodedBody = Absent
                    encodedStreamBody =
                        Absent
                ,
                onBuffered = (url, headers, contentType, bytes) =>
                    encodedUrl = urlPrefix + url
                    encodedHeaders = headers.add("Content-Type", contentType)
                    encodedBody = Maybe(bytesToUint8Array(bytes.toArrayUnsafe))
                    encodedStreamBody =
                        Absent
                ,
                onStreaming = (url, headers, contentType, stream) =>
                    encodedUrl = urlPrefix + url
                    encodedHeaders = headers.add("Content-Type", contentType)
                    encodedBody = Absent
                    encodedStreamBody = Maybe(stream)
            )
        end encodeRequest

        private def resetEncoded(): Unit =
            encodedUrl = ""
            encodedHeaders = HttpHeaders.empty
            encodedBody = Absent
            encodedStreamBody = Absent
        end resetEncoded

        private def buildInit(method: HttpMethod, kyoHeaders: HttpHeaders, body: Maybe[Uint8Array]): RequestInit =
            val init = new RequestInit {}
            init.method = method.name.asInstanceOf[dom.HttpMethod]
            init.redirect = dom.RequestRedirect.manual
            init.headers = buildFetchHeaders(kyoHeaders)
            if body.isDefined then
                init.body = body.get.asInstanceOf[dom.BodyInit]
            init
        end buildInit

        private def classifyError(e: Throwable)(using Frame): HttpError =
            val jsError = e match
                case ex: scala.scalajs.js.JavaScriptException => ex.exception.asInstanceOf[js.Dynamic]
                case _                                        => e.asInstanceOf[js.Dynamic]
            val cause = jsError.cause
            if !js.isUndefined(cause) && !js.isUndefined(cause.code) then
                val codeStr = cause.code.asInstanceOf[String]
                if codeStr == "ECONNREFUSED" || codeStr == "ECONNRESET" then
                    HttpError.ConnectionError(s"Connection to $host:$port failed: $codeStr", e)
                else
                    HttpError.ConnectionError(s"Connection error to $host:$port", e)
                end if
            else
                HttpError.ConnectionError(s"Request to $host:$port failed", e)
            end if
        end classifyError

        private def buildFetchHeaders(kyoHeaders: HttpHeaders): FetchHeaders =
            val headers = new FetchHeaders()
            kyoHeaders.foreach { (k, v) =>
                headers.append(k, v)
            }
            headers
        end buildFetchHeaders

        private def extractHeaders(fetchHeaders: FetchHeaders): HttpHeaders =
            val builder  = kyo.ChunkBuilder.init[String]
            val iterator = fetchHeaders.jsIterator
            @tailrec def loop(next: js.Iterator.Entry[js.Array[String]]): Unit =
                if !next.done then
                    val pair = next.value
                    discard(builder += pair(0))
                    discard(builder += pair(1))
                    loop(iterator.next())
            loop(iterator.next())
            HttpHeaders.fromChunk(builder.result())
        end extractHeaders

        private def concatSpans(chunks: Chunk[Span[Byte]]): Array[Byte] =
            val totalSize = chunks.foldLeft(0)(_ + _.size)
            val result    = new Array[Byte](totalSize)
            var pos       = 0
            chunks.foreach { span =>
                val arr = span.toArrayUnsafe
                java.lang.System.arraycopy(arr, 0, result, pos, arr.length)
                pos += arr.length
            }
            result
        end concatSpans

        private def bytesToUint8Array(bytes: Array[Byte]): Uint8Array =
            val int8 = js.typedarray.byteArray2Int8Array(bytes)
            new Uint8Array(int8.buffer, int8.byteOffset, int8.length)

    end FetchConnection

end FetchClientBackend
