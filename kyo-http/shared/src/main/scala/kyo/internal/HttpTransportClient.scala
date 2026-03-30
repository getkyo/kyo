package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Protocol-agnostic HTTP client. Pure — zero AllowUnsafe.
  *
  * Implements HttpBackend.Client. The onRelease callback uses suspended effects for connection pooling: Absent on success → pool returns
  * connection, Present(error) → pool discards.
  */
class HttpTransportClient(private[kyo] val transport: Transport, protocol: Protocol) extends HttpBackend.Client:

    type Connection = transport.Connection

    def connectWith[A](host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(
        f: Connection => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        val base = transport.connect(host, port, ssl).map(f)
        connectTimeout match
            case Present(t) =>
                Abort.recover[Timeout](_ =>
                    Abort.fail(HttpTimeoutException(t, "CONNECT", s"$host:$port"))
                )(Async.timeout(t)(base))
            case Absent => base
        end match
    end connectWith

    def sendWith[In, Out, A](
        connection: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onRelease: Maybe[Result.Error[Any]] => Unit < Sync
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Sync.ensure(onRelease) {
            transport.stream(connection).map { stream =>
                writeRequest(stream, route, request).andThen {
                    if RouteUtil.isStreamingResponse(route) then
                        readStreamingResponse(stream, route, request).map(f)
                    else
                        readBufferedResponse(stream, route, request).map(f)
                }
            }
        }

    def isAlive(connection: Connection)(using Frame): Boolean < Sync =
        transport.isAlive(connection)

    def closeNow(connection: Connection)(using Frame): Unit < Sync =
        transport.closeNow(connection)

    def close(connection: Connection, gracePeriod: Duration)(using Frame): Unit < Async =
        transport.close(connection, gracePeriod)

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Kyo.unit

    /** Encode request via RouteUtil callbacks, write to stream. */
    private def writeRequest[In, Out](
        stream: TransportStream,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): Unit < Async =
        RouteUtil.encodeRequest(route, request)(
            onEmpty = (path, headers) =>
                protocol.writeRequestHead(
                    stream,
                    request.method,
                    path,
                    headers.add("Content-Length", "0")
                ),
            onBuffered = (path, headers, body) =>
                protocol.writeRequestHead(
                    stream,
                    request.method,
                    path,
                    headers.add("Content-Length", body.size.toString)
                ).andThen {
                    protocol.writeBody(stream, body)
                },
            onStreaming = (path, headers, bodyStream) =>
                protocol.writeRequestHead(stream, request.method, path, headers).andThen {
                    protocol.writeStreamingBody(stream, bodyStream)
                }
        )

    /** Read buffered response, decode via RouteUtil. */
    private def readBufferedResponse[Out](
        stream: TransportStream,
        route: HttpRoute[?, Out, ?],
        request: HttpRequest[?]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        protocol.readResponse(stream, Int.MaxValue, request.method).map { (status, headers, body) =>
            val bodyBytes = body match
                case HttpBody.Empty          => Span.empty[Byte]
                case HttpBody.Buffered(data) => data
                case HttpBody.Streamed(_)    => Span.empty[Byte]
            Abort.get(RouteUtil.decodeBufferedResponse(
                route,
                status,
                headers,
                bodyBytes,
                route.method.name,
                request.url.toString
            ))
        }

    /** Read streaming response, decode via RouteUtil. */
    private def readStreamingResponse[Out](
        stream: TransportStream,
        route: HttpRoute[?, Out, ?],
        request: HttpRequest[?]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        protocol.readResponse(stream, Int.MaxValue, request.method).map { (status, headers, body) =>
            val bodyStream = body match
                case HttpBody.Streamed(chunks) => chunks
                case HttpBody.Buffered(data)   => Stream.init(Seq(data))
                case HttpBody.Empty            => Stream.empty[Span[Byte]]
            Abort.get(RouteUtil.decodeStreamingResponse(
                route,
                status,
                headers,
                bodyStream,
                route.method.name,
                request.url.toString
            ))
        }

end HttpTransportClient
