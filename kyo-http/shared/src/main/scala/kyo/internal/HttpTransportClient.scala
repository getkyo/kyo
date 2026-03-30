package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Protocol-agnostic HTTP client. Pure — zero AllowUnsafe.
  *
  * Implements HttpBackend.Client. The onRelease callback uses suspended effects for connection pooling: Absent on success → pool returns
  * connection, Present(error) → pool discards.
  *
  * Streaming responses use a two-phase connection lifecycle:
  *   - Phase 1: outer ensure covers request write + header read. If interrupted here, onRelease fires.
  *   - Phase 2: ensure embedded in the stream body. When stream ends/abandoned/interrupted, onRelease fires.
  *   - phase2Started flag prevents outer ensure from releasing when f returns with an unconsumed stream.
  *   - fullyConsumed flag ensures partial consumption discards the connection (dirty chunked data).
  *   - onRelease is made idempotent so double-fire from both phases is safe.
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
        if RouteUtil.isStreamingResponse(route) then
            sendStreamingWith(connection, route, request, onRelease)(f)
        else
            sendBufferedWith(connection, route, request, onRelease)(f)
    end sendWith

    def isAlive(connection: Connection)(using Frame): Boolean < Sync =
        transport.isAlive(connection)

    def closeNow(connection: Connection)(using Frame): Unit < Sync =
        transport.closeNow(connection)

    def close(connection: Connection, gracePeriod: Duration)(using Frame): Unit < Async =
        transport.close(connection, gracePeriod)

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Kyo.unit

    /** Simple single-phase lifecycle for buffered responses. */
    private def sendBufferedWith[In, Out, A](
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
                    readBufferedResponse(stream, route, request).map(f)
                }
            }
        }

    /** Two-phase lifecycle for streaming responses.
      *
      * Phase 1 (outer ensure): protects request write + header read. If interrupted here, onRelease fires.
      *
      * Phase 2 (inner ensure in stream body): protects stream consumption. When stream ends/abandoned/interrupted, onRelease fires. Partial
      * consumption discards the connection (dirty chunked data).
      */
    private def sendStreamingWith[In, Out, A](
        connection: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onRelease: Maybe[Result.Error[Any]] => Unit < Sync
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        AtomicRef.init(false).map { phase2Started =>
            AtomicRef.init(false).map { released =>
                // Idempotent release — safe to call from both phases
                val safeRelease: Maybe[Result.Error[Any]] => Unit < Sync = error =>
                    released.getAndSet(true).map { wasReleased =>
                        if !wasReleased then onRelease(error)
                        else Kyo.unit
                    }
                // Phase 1: outer ensure — only fires if phase 2 hasn't started
                Sync.ensure { error =>
                    phase2Started.get.map { started =>
                        if !started then safeRelease(error)
                        else Kyo.unit
                    }
                } {
                    transport.stream(connection).map { stream =>
                        writeRequest(stream, route, request).andThen {
                            readStreamingResponseWrapped(stream, route, request, phase2Started, safeRelease).map(f)
                        }
                    }
                }
            }
        }

    /** Read streaming response and wrap the body stream with phase-2 lifecycle management. */
    private def readStreamingResponseWrapped[Out](
        stream: TransportStream,
        route: HttpRoute[?, Out, ?],
        request: HttpRequest[?],
        phase2Started: AtomicRef[Boolean],
        safeRelease: Maybe[Result.Error[Any]] => Unit < Sync
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        protocol.readResponse(stream, Int.MaxValue, request.method).map { (status, headers, body) =>
            val rawStream = body match
                case HttpBody.Streamed(chunks) => chunks
                case HttpBody.Buffered(data)   => Stream.init(Seq(data))
                case HttpBody.Empty            => Stream.empty[Span[Byte]]
            // Mark phase 2 started — outer ensure will no longer release
            phase2Started.set(true).andThen {
                // Wrap the body stream with phase-2 ensure:
                // - Track whether the stream was fully consumed
                // - On stream end: release with success (fully consumed) or error (partial)
                AtomicRef.init(false).map { fullyConsumed =>
                    val wrappedStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                        Sync.ensure { error =>
                            fullyConsumed.get.map { consumed =>
                                if consumed then safeRelease(error)
                                else safeRelease(Present(Result.Panic(new Exception("streaming response not fully consumed"))))
                            }
                        } {
                            rawStream.emit.andThen {
                                fullyConsumed.set(true)
                            }
                        }
                    }
                    Abort.get(RouteUtil.decodeStreamingResponse(
                        route,
                        status,
                        headers,
                        wrappedStream,
                        route.method.name,
                        request.url.toString
                    ))
                }
            }
        }

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
                protocol.writeRequestHead(stream, request.method, path, headers.add("Transfer-Encoding", "chunked")).andThen {
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

end HttpTransportClient
