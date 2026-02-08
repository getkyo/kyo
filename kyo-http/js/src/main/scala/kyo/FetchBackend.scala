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
import scala.scalajs.js.typedarray.Uint8Array

/** JS backend using the Fetch API for HTTP transport.
  *
  * Each "connection" is virtual — Fetch is stateless, so every send() issues an independent fetch(). The pool will never reuse connections
  * (isAlive = false), which is fine because Fetch handles its own connection pooling at the browser/runtime level.
  *
  * Server functionality is not available on JS.
  */
object FetchBackend extends Backend:

    def connectionFactory(maxResponseSizeBytes: Int, daemon: Boolean)(using AllowUnsafe): Backend.ConnectionFactory =
        new Backend.ConnectionFactory:

            def connect(host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(using
                Frame
            ): Backend.Connection < (Async & Abort[HttpError]) =
                new FetchConnection(host, port, ssl)

            def close(gracePeriod: Duration)(using Frame): Unit < Async = ()
        end new
    end connectionFactory

    def server(
        port: Int,
        host: String,
        maxContentLength: Int,
        backlog: Int,
        keepAlive: Boolean,
        tcpFastOpen: Boolean,
        flushConsolidationLimit: Int,
        handler: Backend.ServerHandler
    )(using Frame): Backend.Server < Async =
        throw new UnsupportedOperationException("HTTP server is not supported on JavaScript platform")

end FetchBackend

final private[kyo] class FetchConnection(
    host: String,
    port: Int,
    ssl: Boolean
) extends Backend.Connection:

    // MacrotaskExecutor avoids microtask starvation and aligns with kyo-scheduler's JS usage
    private given ExecutionContext = MacrotaskExecutor

    // Fetch is stateless — no persistent connection
    def isAlive(using AllowUnsafe): Boolean = false

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        val (url, init) = buildFetchRequest(request)
        val future: Future[HttpResponse[HttpBody.Bytes]] =
            dom.fetch(url, init).toFuture.flatMap { response =>
                response.text().toFuture.map { bodyText =>
                    val status  = HttpResponse.Status(response.status)
                    val headers = extractHeaders(response.headers)
                    var resp    = HttpResponse(status, bodyText)
                    headers.foreach((k, v) => resp = resp.addHeader(k, v))
                    resp
                }
            }
        Abort.recover[Throwable](e =>
            Abort.fail(HttpError.fromThrowable(e, host, port))
        )(Async.fromFuture(future))
    end send

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        val (url, init)              = buildFetchRequest(request)
        val future: Future[Response] = dom.fetch(url, init).toFuture
        Abort.run[Throwable](Async.fromFuture(future)).map {
            case Result.Success(response) =>
                val status  = HttpResponse.Status(response.status)
                val headers = extractHeaders(response.headers)
                val reader  = response.body.getReader()

                val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                    Loop(()) { _ =>
                        val readFuture = reader.read().toFuture
                        Abort.run[Throwable](Async.fromFuture(readFuture)).map {
                            case Result.Success(chunk) =>
                                if chunk.done then Loop.done(())
                                else
                                    val uint8 = chunk.value
                                    val bytes = new Int8Array(uint8.buffer, uint8.byteOffset, uint8.length).toArray
                                    Emit.valueWith(Chunk(Span.fromUnsafe(bytes)))(Loop.continue(()))
                            case Result.Failure(_) => Loop.done(())
                            case Result.Panic(_)   => Loop.done(())
                        }
                    }
                }

                HttpResponse.initStreaming(status, headers, bodyStream)
            case Result.Failure(e) => Abort.fail(HttpError.fromThrowable(e, host, port))
            case Result.Panic(e)   => throw e
        }
    end stream

    def close(using Frame): Unit < Async = ()

    private[kyo] def closeAbruptly()(using AllowUnsafe): Unit = ()

    private def buildFetchRequest(request: HttpRequest[?]): (String, RequestInit) =
        val url = HttpClient.buildUrl(host, port, ssl, request.url)

        val init    = new RequestInit {}
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

    private def extractHeaders(fetchHeaders: FetchHeaders): Seq[(String, String)] =
        val result   = Seq.newBuilder[(String, String)]
        val iterator = fetchHeaders.jsIterator
        @tailrec def loop(next: js.Iterator.Entry[js.Array[String]]): Unit =
            if !next.done then
                val pair = next.value
                result += ((pair(0), pair(1)))
                loop(iterator.next())
        loop(iterator.next())
        result.result()
    end extractHeaders

end FetchConnection
