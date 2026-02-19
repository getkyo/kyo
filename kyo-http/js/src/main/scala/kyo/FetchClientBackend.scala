package kyo

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

/** JS client backend using the Fetch API for HTTP transport.
  *
  * Each "connection" is virtual — Fetch is stateless, so every send() issues an independent fetch(). The pool will never reuse connections
  * (isAlive = false), which is fine because Fetch handles its own connection pooling at the browser/runtime level.
  */
object FetchClientBackend extends Backend.Client:

    def connectionFactory(maxResponseSizeBytes: Int, daemon: Boolean)(using AllowUnsafe): Backend.ConnectionFactory =
        new Backend.ConnectionFactory:

            def connect(host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(using
                Frame
            ): Backend.Connection < (Async & Abort[HttpError]) =
                new FetchConnection(host, port, ssl, maxResponseSizeBytes)

            def close(gracePeriod: Duration)(using Frame): Unit < Async = ()
        end new
    end connectionFactory

end FetchClientBackend

final private[kyo] class FetchConnection(
    host: String,
    port: Int,
    ssl: Boolean,
    maxResponseSizeBytes: Int
) extends Backend.Connection:

    // MacrotaskExecutor avoids microtask starvation and aligns with kyo-scheduler's JS usage
    private given ExecutionContext = MacrotaskExecutor

    // Fetch is stateless — no persistent connection
    def isAlive(using AllowUnsafe): Boolean = false

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        val (url, init) = buildFetchRequest(request)
        val controller  = new dom.AbortController()
        init.signal = controller.signal
        // Abort the fetch when the fiber is interrupted (e.g. client timeout or disconnect)
        Sync.ensure(controller.abort()) {
            val future: Future[HttpResponse[HttpBody.Bytes]] =
                dom.fetch(url, init).toFuture.flatMap { response =>
                    response.arrayBuffer().toFuture.map { arrayBuffer =>
                        val bodyBytes = new Int8Array(arrayBuffer).toArray
                        if maxResponseSizeBytes > 0 && bodyBytes.length > maxResponseSizeBytes then
                            throw new RuntimeException(
                                s"Response size ${bodyBytes.length} exceeds limit $maxResponseSizeBytes"
                            )
                        end if
                        val status  = HttpStatus(response.status)
                        val headers = extractHeaders(response.headers)
                        HttpResponse.initBytes(status, headers, Span.fromUnsafe(bodyBytes))
                    }
                }
            Abort.recover[Throwable](
                e => Abort.fail(classifyError(e)),
                e => Abort.fail(classifyError(e))
            )(Async.fromFuture(future))
        }
    end send

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Abort[HttpError]) =
        val (url, init) = buildFetchRequest(request)
        val controller  = new dom.AbortController()
        init.signal = controller.signal
        // Materialize streaming request body — Fetch API requires body before calling fetch()
        val prepareBody: Unit < Async = request.body.use(
            _ => (), // buffered body already set by buildFetchRequest
            streamed =>
                streamed.stream.run.map { chunks =>
                    val allBytes = chunks.foldLeft(Array.empty[Byte])(_ ++ _.toArrayUnsafe)
                    if allBytes.nonEmpty then
                        init.body = js.typedarray.byteArray2Int8Array(allBytes)
                }
        )
        // Abort the fetch when the Scope closes (stream consumer done or fiber interrupted).
        // Unlike send(), we use Scope.ensure instead of Sync.ensure because the body stream
        // is consumed lazily after this method returns.
        Scope.ensure(controller.abort()).andThen {
            prepareBody.andThen {
                val future: Future[Response] = dom.fetch(url, init).toFuture
                Abort.recover[Throwable](
                    e => Abort.fail(classifyError(e)),
                    e => Abort.fail(classifyError(e))
                ) {
                    Async.fromFuture(future).map { response =>
                        val status  = HttpStatus(response.status)
                        val headers = extractHeaders(response.headers)
                        val reader  = response.body.getReader()

                        val bodyStream: Stream[Span[Byte], Async & Scope] = Stream[Span[Byte], Async & Scope] {
                            Loop(()) { _ =>
                                val readFuture = reader.read().toFuture
                                Abort.recover[Throwable](
                                    _ => Loop.done[Unit, Unit](()),
                                    _ => Loop.done[Unit, Unit](())
                                ) {
                                    Async.fromFuture(readFuture).map { chunk =>
                                        if chunk.done then Loop.done(())
                                        else
                                            val uint8 = chunk.value
                                            val bytes = new Int8Array(uint8.buffer, uint8.byteOffset, uint8.length).toArray
                                            Emit.valueWith(Chunk(Span.fromUnsafe(bytes)))(Loop.continue(()))
                                    }
                                }
                            }
                        }

                        HttpResponse.initStreaming(status, headers, bodyStream)
                    }
                }
            }
        }
    end stream

    def close(using Frame): Unit < Async = ()

    private[kyo] def closeAbruptly()(using AllowUnsafe): Unit = ()

    private def classifyError(e: Throwable)(using Frame): HttpError =
        // Scala.js wraps JS errors in JavaScriptException — unwrap to access the original error
        val jsError = e match
            case ex: scala.scalajs.js.JavaScriptException => ex.exception.asInstanceOf[js.Dynamic]
            case _                                        => e.asInstanceOf[js.Dynamic]
        // Node.js fetch wraps connection errors as TypeError with a cause containing an error code
        val cause = jsError.cause
        if !js.isUndefined(cause) && !js.isUndefined(cause.code) then
            val codeStr = cause.code.asInstanceOf[String]
            if codeStr == "ECONNREFUSED" || codeStr == "ECONNRESET" then
                HttpError.ConnectionFailed(host, port, e)
            else
                HttpError.fromThrowable(e, host, port)
            end if
        else
            HttpError.fromThrowable(e, host, port)
        end if
    end classifyError

    private def buildFetchRequest(request: HttpRequest[?]): (String, RequestInit) =
        val url = HttpClient.buildUrl(host, port, ssl, request.url)

        val init = new RequestInit {}
        init.redirect = dom.RequestRedirect.manual
        val headers = new FetchHeaders()

        request.headers.foreach { (k, v) =>
            headers.append(k, v)
        }
        request.contentType.foreach(ct => headers.set("Content-Type", ct))

        init.method = request.method.name.asInstanceOf[dom.HttpMethod]
        init.headers = headers

        request.body.use(
            b => if !b.isEmpty then init.body = js.typedarray.byteArray2Int8Array(b.data),
            _ => ()
        )

        (url, init)
    end buildFetchRequest

    private def extractHeaders(fetchHeaders: FetchHeaders): HttpHeaders =
        var headers  = HttpHeaders.empty
        val iterator = fetchHeaders.jsIterator
        @tailrec def loop(next: js.Iterator.Entry[js.Array[String]]): Unit =
            if !next.done then
                val pair = next.value
                headers = headers.add(pair(0), pair(1))
                loop(iterator.next())
        loop(iterator.next())
        headers
    end extractHeaders

end FetchConnection
