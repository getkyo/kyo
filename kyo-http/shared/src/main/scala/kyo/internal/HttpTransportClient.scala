package kyo.internal

import kyo.*

/** Stream-first HTTP client backend. Uses Transport + Http1Exchange for request/response dispatch.
  *
  * Implements HttpBackend.Client. The Connection type wraps the transport connection together with its Exchange, so the connection pool
  * stores ready-to-use Exchanges — protocol version is invisible to callers.
  *
  * The onRelease callback uses suspended effects for connection pooling: Absent on success → pool returns connection, Present(error) → pool
  * discards.
  *
  * Streaming responses use a two-phase connection lifecycle:
  *   - Phase 1: outer ensure covers request write + header read. If interrupted here, onRelease fires.
  *   - Phase 2: ensure embedded in the stream body. When stream ends/abandoned/interrupted, onRelease fires.
  *   - phase2Started flag prevents outer ensure from releasing when f returns with an unconsumed stream.
  *   - fullyConsumed flag ensures partial consumption discards the connection (dirty chunked data).
  *   - onRelease is made idempotent so double-fire from both phases is safe.
  */
class HttpTransportClient(private[kyo] val transport: Transport) extends HttpBackend.Client:

    /** Wraps the transport connection with its Exchange for keep-alive request/response dispatch. */
    final class Conn(
        val tc: transport.Connection,
        val exchange: Exchange[RawHttpRequest, RawHttpResponse, Nothing, HttpException]
    )

    type Connection = Conn

    def connectWith[A](url: HttpUrl, connectTimeout: Maybe[Duration])(
        f: Connection => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        val address = url.unixSocket match
            case Present(socketPath) => HttpAddress.Unix(socketPath)
            case Absent              => HttpAddress.Tcp(url.host, url.port)
        val tls = if url.ssl then Present(TlsConfig.default) else Absent
        val base = transport.connectWith(address, tls) { tc =>
            Http1Exchange.initUnscoped(tc, Int.MaxValue).map { exchange =>
                f(new Conn(tc, exchange))
            }
        }
        val target = url.unixSocket match
            case Present(socketPath) => s"unix:$socketPath"
            case Absent              => s"${url.host}:${url.port}"
        connectTimeout match
            case Present(t) =>
                Abort.recover[Timeout](_ =>
                    Abort.fail(HttpTimeoutException(t, "CONNECT", target))
                )(Async.timeout(t)(base))
            case Absent => base
        end match
    end connectWith

    def sendWith[In, Out, A](
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onRelease: Maybe[Result.Error[Any]] => Unit < Async
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        if RouteUtil.isStreamingResponse(route) then
            sendStreamingWith(conn, route, request, onRelease)(f)
        else
            sendBufferedWith(conn, route, request, onRelease)(f)
    end sendWith

    def isAlive(conn: Connection)(using Frame): Boolean < Sync =
        transport.isAlive(conn.tc)

    def closeNow(conn: Connection)(using Frame): Unit < Async =
        conn.exchange.close.andThen(transport.closeNow(conn.tc))

    def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async =
        conn.exchange.close.andThen(transport.close(conn.tc, gracePeriod))

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Kyo.unit

    /** Simple single-phase lifecycle for buffered responses. */
    private def sendBufferedWith[In, Out, A](
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onRelease: Maybe[Result.Error[Any]] => Unit < Async
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Scope.run {
            Scope.ensure(onRelease).andThen {
                encodeToRaw(route, request).map { rawReq =>
                    Abort.recover[Closed](_ => Abort.fail(HttpConnectionClosedException())) {
                        conn.exchange(rawReq)
                    }.map { rawResp =>
                        decodeFromRawBuffered(route, rawResp, request).map(f)
                    }
                }
            }
        }

    /** Two-phase lifecycle for streaming responses.
      *
      * Phase 1 (outer ensure): protects request send + response header read. If interrupted here, onRelease fires.
      *
      * Phase 2 (inner ensure in stream body): protects stream consumption. When stream ends/abandoned/interrupted, onRelease fires. Partial
      * consumption discards the connection (dirty chunked data).
      */
    private def sendStreamingWith[In, Out, A](
        conn: Connection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onRelease: Maybe[Result.Error[Any]] => Unit < Async
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        AtomicRef.initWith(false) { phase2Started =>
            AtomicRef.initWith(false) { released =>
                // Idempotent release — safe to call from both phases
                val safeRelease: Maybe[Result.Error[Any]] => Unit < Async = error =>
                    released.getAndSet(true).map { wasReleased =>
                        if !wasReleased then onRelease(error)
                        else Kyo.unit
                    }
                // Phase 1: outer ensure — only fires if phase 2 hasn't started
                Scope.run {
                    Scope.ensure { error =>
                        phase2Started.get.map { started =>
                            if !started then safeRelease(error)
                            else Kyo.unit
                        }
                    }.andThen {
                        encodeToRaw(route, request).map { rawReq =>
                            Abort.recover[Closed](_ => Abort.fail(HttpConnectionClosedException())) {
                                conn.exchange(rawReq)
                            }.map { rawResp =>
                                decodeFromRawStreaming(route, rawResp, request, phase2Started, safeRelease).map(f)
                            }
                        }
                    }
                }
            }
        }

    /** Decode a streaming raw response and wrap the body stream with phase-2 lifecycle management. */
    private def decodeFromRawStreaming[Out](
        route: HttpRoute[?, Out, ?],
        rawResp: RawHttpResponse,
        request: HttpRequest[?],
        phase2Started: AtomicRef[Boolean],
        safeRelease: Maybe[Result.Error[Any]] => Unit < Async
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        val rawStream = rawResp.body match
            case HttpBody.Streamed(chunks) => chunks
            case HttpBody.Buffered(data)   => Stream.init(Seq(data))
            case HttpBody.Empty            => Stream.empty[Span[Byte]]
        // Mark phase 2 started — outer ensure will no longer release
        phase2Started.set(true).andThen {
            // Wrap the body stream with phase-2 ensure:
            // - Track whether the stream was fully consumed
            // - On stream end: release with success (fully consumed) or error (partial)
            AtomicRef.initWith(false) { fullyConsumed =>
                val wrappedStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                    Scope.run {
                        Scope.ensure { error =>
                            fullyConsumed.get.map { consumed =>
                                if consumed then safeRelease(error)
                                else safeRelease(Present(Result.Panic(new Exception("streaming response not fully consumed"))))
                            }
                        }.andThen {
                            rawStream.emit.andThen {
                                fullyConsumed.set(true)
                            }
                        }
                    }
                }
                Abort.get(RouteUtil.decodeStreamingResponse(
                    route,
                    rawResp.status,
                    rawResp.headers,
                    wrappedStream,
                    route.method.name,
                    request.url.toString
                ))
            }
        }
    end decodeFromRawStreaming

    /** Encode route + request into a RawHttpRequest. */
    private def encodeToRaw[In, Out](
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): RawHttpRequest < Async =
        RouteUtil.encodeRequest(route, request)(
            onEmpty = (path, headers) =>
                RawHttpRequest(
                    request.method,
                    path,
                    headers.add("Content-Length", "0"),
                    HttpBody.Empty
                ),
            onBuffered = (path, headers, body) =>
                RawHttpRequest(
                    request.method,
                    path,
                    headers.add("Content-Length", body.size.toString),
                    HttpBody.Buffered(body)
                ),
            onStreaming = (path, headers, bodyStream) =>
                RawHttpRequest(
                    request.method,
                    path,
                    headers.add("Transfer-Encoding", "chunked"),
                    HttpBody.Streamed(bodyStream)
                )
        )

    /** Decode a buffered raw response via RouteUtil. */
    private def decodeFromRawBuffered[Out](
        route: HttpRoute[?, Out, ?],
        rawResp: RawHttpResponse,
        request: HttpRequest[?]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        val bodyBytes = rawResp.body match
            case HttpBody.Empty          => Span.empty[Byte]
            case HttpBody.Buffered(data) => data
            case HttpBody.Streamed(_)    => Span.empty[Byte]
        Abort.get(RouteUtil.decodeBufferedResponse(
            route,
            rawResp.status,
            rawResp.headers,
            bodyBytes,
            route.method.name,
            request.url.toString
        ))
    end decodeFromRawBuffered

end HttpTransportClient
