package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Protocol-agnostic HTTP client. Integrates with ConnectionPool via HttpBackend.Client.
  *
  * The onReleaseUnsafe callback is critical for connection pooling. Sync.ensure has an error-aware overload that natively passes the error:
  * Absent on success → pool returns connection to idle set, Present(error) on failure → pool discards connection.
  */
class HttpTransportClient(private[kyo] val transport: Transport, protocol: Protocol) extends HttpBackend.Client:

    type Connection = transport.Connection

    def connectWith[A](host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(
        f: Connection => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        val base = transport.connect(host, port, ssl).map(f)
        connectTimeout match
            case Present(t) =>
                Abort.run[Timeout](Async.timeout(t)(base)).map {
                    case Result.Failure(_: Timeout) =>
                        Abort.fail(HttpTimeoutException(t, "CONNECT", s"$host:$port"))
                    case Result.Success(a) => a
                    case Result.Panic(e)   => throw e
                }
            case Absent => base
        end match
    end connectWith

    def sendWith[In, Out, A](
        connection: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onReleaseUnsafe: Maybe[Result.Error[Any]] => Unit
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Sync.ensure(onReleaseUnsafe) {
            transport.stream(connection).map { stream =>
                writeRequest(stream, route, request).andThen {
                    if RouteUtil.isStreamingResponse(route) then
                        readStreamingResponse(stream, route, request).map(f)
                    else
                        readBufferedResponse(stream, route, request).map(f)
                }
            }
        }

    def isAlive(connection: Connection)(using AllowUnsafe): Boolean =
        transport.isAlive(connection)

    def closeNowUnsafe(connection: Connection)(using AllowUnsafe): Unit =
        transport.closeNowUnsafe(connection)

    def close(connection: Connection, gracePeriod: Duration)(using Frame): Unit < Async =
        transport.close(connection, gracePeriod)

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(())

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
                    route.method,
                    path,
                    headers.add("Content-Length", "0")
                ),
            onBuffered = (path, headers, body) =>
                protocol.writeRequestHead(
                    stream,
                    route.method,
                    path,
                    headers.add("Content-Length", body.size.toString)
                ).andThen {
                    protocol.writeBody(stream, body)
                },
            onStreaming = (path, headers, bodyStream) =>
                protocol.writeRequestHead(stream, route.method, path, headers).andThen {
                    protocol.writeStreamingBody(stream, bodyStream)
                }
        )

    /** Read buffered response, decode via RouteUtil. */
    private def readBufferedResponse[Out](
        stream: TransportStream,
        route: HttpRoute[?, Out, ?],
        request: HttpRequest[?]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        protocol.readResponse(stream, 65536).map { (status, headers, body) =>
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
        protocol.readResponse(stream, 65536).map { (status, headers, body) =>
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
