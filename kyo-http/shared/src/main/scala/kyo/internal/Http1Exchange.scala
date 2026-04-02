package kyo.internal

import kyo.*

/** Raw HTTP request for use at the transport/exchange layer.
  *
  * Unlike `HttpRequest[Fields]`, which is the route-level public type, this type holds the raw data that the HTTP/1.1 wire protocol needs:
  * method, path, headers, and body. It is used as the `Req` type parameter in `Exchange[RawHttpRequest, RawHttpResponse, ...]`.
  */
case class RawHttpRequest(
    method: HttpMethod,
    path: String,
    headers: HttpHeaders,
    body: HttpBody
)

/** Raw HTTP response for use at the transport/exchange layer.
  *
  * The counterpart to `RawHttpRequest`. Holds the status, headers, and body returned from the server, without any route-level field
  * decoding.
  */
case class RawHttpResponse(
    status: HttpStatus,
    headers: HttpHeaders,
    body: HttpBody
)

/** Exchange factory for HTTP/1.1 over a `TransportStream` connection.
  *
  * Creates an `Exchange[RawHttpRequest, RawHttpResponse, Nothing, HttpException]` that serialises request/response pairs over a single
  * keep-alive connection.
  *
  * HTTP/1.1 is strictly sequential on a single connection: request N must be fully written before the response to request N can be read.
  * The `inflight` Channel (capacity 1) enforces this invariant:
  *   - The send callback writes the request bytes then puts `(id, method)` into `inflight`.
  *   - The receive stream takes `(id, method)` from `inflight` before reading the response.
  *
  * This guarantees write-then-signal ordering: the reader will never attempt to consume a response before the corresponding request bytes
  * have been sent.
  *
  * `Event = Nothing` because HTTP/1.1 has no server-push messages.
  */
object Http1Exchange:

    sealed private trait Http1Wire
    private case class OutReq(id: Int, req: RawHttpRequest)   extends Http1Wire
    private case class InResp(id: Int, resp: RawHttpResponse) extends Http1Wire

    def init(conn: TransportStream, maxSize: Int)(using
        Frame
    )
        : Exchange[RawHttpRequest, RawHttpResponse, Nothing, HttpException] < (Sync & Scope) =
        Channel.init[(Int, HttpMethod)](1).map { inflight =>
            Exchange.init[RawHttpRequest, RawHttpResponse, Http1Wire, Nothing, HttpException](
                encode = (id, req) => OutReq(id, req),
                send = {
                    case OutReq(id, req) =>
                        Http1Protocol.writeRequest(conn, req.method, req.path, req.headers, req.body).andThen {
                            Abort.run[Closed](inflight.put((id, req.method))).unit
                        }
                    case _ => Kyo.unit
                },
                receive = Stream[Http1Wire, Async & Abort[HttpException]] {
                    Loop(conn.read) { stream =>
                        Abort.run[Closed](inflight.take).map {
                            case Result.Failure(_) => Loop.done(())
                            case Result.Success((id, method)) =>
                                Http1Protocol.readResponseStreaming(stream, maxSize, method).map {
                                    case ((status, headers, body), rest) =>
                                        val wire: Http1Wire = InResp(id, RawHttpResponse(status, headers, body))
                                        Emit.valueWith(Chunk(wire))(Loop.continue(rest))
                                }
                        }
                    }
                },
                decode = {
                    case InResp(id, resp) => Exchange.Message.Response(id, resp)
                    case _                => Exchange.Message.Skip
                }
            )
        }

    /** Creates an *unscoped* Exchange for HTTP/1.1 over the given connection.
      *
      * Unlike `init`, this does not register a Scope finalizer — the caller is responsible for calling `exchange.close` when done. This is
      * needed for connection pooling, where the Exchange must outlive individual request scopes.
      */
    def initUnscoped(conn: TransportStream, maxSize: Int)(using
        Frame
    )
        : Exchange[RawHttpRequest, RawHttpResponse, Nothing, HttpException] < Sync =
        Channel.initUnscoped[(Int, HttpMethod)](1).map { inflight =>
            Exchange.initUnscoped[RawHttpRequest, RawHttpResponse, Http1Wire, Nothing, HttpException](
                encode = (id, req) => OutReq(id, req),
                send = {
                    case OutReq(id, req) =>
                        Http1Protocol.writeRequest(conn, req.method, req.path, req.headers, req.body).andThen {
                            // If inflight channel is closed (exchange shutting down), silently ignore —
                            // the exchange will fail the pending request via its done promise.
                            Abort.run[Closed](inflight.put((id, req.method))).unit
                        }
                    case _ => Kyo.unit
                },
                receive = Stream[Http1Wire, Async & Abort[HttpException]] {
                    Loop(conn.read) { stream =>
                        // If inflight channel is closed, terminate the receive stream cleanly.
                        Abort.run[Closed](inflight.take).map {
                            case Result.Failure(_) => Loop.done(())
                            case Result.Success((id, method)) =>
                                Http1Protocol.readResponseStreaming(stream, maxSize, method).map {
                                    case ((status, headers, body), rest) =>
                                        val wire: Http1Wire = InResp(id, RawHttpResponse(status, headers, body))
                                        Emit.valueWith(Chunk(wire))(Loop.continue(rest))
                                }
                        }
                    }
                },
                decode = {
                    case InResp(id, resp) => Exchange.Message.Response(id, resp)
                    case _                => Exchange.Message.Skip
                }
            )
        }

end Http1Exchange
