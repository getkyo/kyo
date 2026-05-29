// flow-allow: PUBLIC kyo-http-backed WebSocket transport adapter lifting HttpWebSocket text frames to JsonRpcTransport
package kyo

object JsonRpcHttpTransport:

    def webSocket(
        url: HttpUrl,
        headers: HttpHeaders = HttpHeaders.empty,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException]) =
        for
            // flow-allow: initUnscoped because lifetime is managed by the transport close() and Scope.ensure.
            inbound  <- Channel.initUnscoped[JsonRpcEnvelope](64)
            outbound <- Channel.initUnscoped[HttpWebSocket.Payload](64)
            // flow-allow: Unsafe Promise used as a close gate; completed by transport.close() or Scope.ensure.
            doneRef <- Sync.defer(Fiber.Promise.Unsafe.init[Unit, Async]()(using AllowUnsafe.embrace.danger))
            _ <- Scope.ensure(
                Sync.defer {
                    doneRef.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                }.andThen(Abort.run[Closed](inbound.close).unit)
                    .andThen(Abort.run[Closed](outbound.close).unit)
            )
        yield
            val transport: JsonRpcTransport = new JsonRpcTransport:
                def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
                    Abort.run[JsonRpcError](codec.encode(env)).map {
                        case Result.Success(structure) =>
                            // flow-allow: RawJsonParser.encode converts Structure.Value to standard JSON-RPC wire text;
                            // Json.encode[Structure.Value] uses kyo-schema format, not standard JSON.
                            val jsonStr = internal.RawJsonParser.encode(structure)
                            outbound.put(HttpWebSocket.Payload.Text(jsonStr))
                        case Result.Failure(err) =>
                            Log.warn(s"kyo-jsonrpc-http: encode failed ${err.message}")
                        case Result.Panic(t) =>
                            Log.warn(s"kyo-jsonrpc-http: encode panic ${t.getMessage}")
                    }

                def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
                    inbound.streamUntilClosed()

                def close(using Frame): Unit < Async =
                    Sync.defer {
                        doneRef.completeUnitDiscard()(using AllowUnsafe.embrace.danger)
                    }.andThen(Abort.run[Closed](inbound.close).unit)
                        .andThen(Abort.run[Closed](outbound.close).unit)

            // Start the WS connection in a background fiber.
            Fiber.initUnscoped {
                Abort.run[HttpException](
                    HttpClient.webSocket(url, headers, HttpWebSocket.Config()) { ws =>
                        // Outbound bridge: outbound channel -> ws (background fiber).
                        Fiber.initUnscoped {
                            outbound.streamUntilClosed().foreach { frame =>
                                Abort.run[Closed](ws.put(frame)).unit
                            }
                        }.andThen {
                            // Inbound bridge: ws frames -> inbound channel (runs in WS block fiber).
                            // Running here (not in a sub-fiber) ensures ws.stream is consumed
                            // before kyo-http closes ws.inbound when the server sends close.
                            Async.race(
                                ws.stream.foreach {
                                    case HttpWebSocket.Payload.Text(text) =>
                                        // flow-allow: RawJsonParser.parse converts standard JSON-RPC wire text
                                        internal.RawJsonParser.parse(text) match
                                            case Result.Success(sv) =>
                                                Abort.run[JsonRpcError](codec.decode(sv)).map {
                                                    case Result.Success(env) =>
                                                        Abort.run[Closed](inbound.put(env)).unit
                                                    case Result.Failure(err) =>
                                                        Abort.run[Closed](
                                                            inbound.put(
                                                                JsonRpcEnvelope.Malformed(Absent, err.message, sv)
                                                            )
                                                        ).unit
                                                    case Result.Panic(t) =>
                                                        Log.warn(s"kyo-jsonrpc-http: codec panic ${t.getMessage}")
                                                }
                                            case Result.Failure(e) =>
                                                Abort.run[Closed](
                                                    inbound.put(
                                                        JsonRpcEnvelope.Malformed(
                                                            Absent,
                                                            s"json parse: ${e.getMessage}",
                                                            Structure.Value.Str(text)
                                                        )
                                                    )
                                                ).unit
                                            case Result.Panic(t) =>
                                                Log.warn(s"kyo-jsonrpc-http: json parse panic ${t.getMessage}")
                                    case HttpWebSocket.Payload.Binary(_) =>
                                        Log.warn("kyo-jsonrpc-http: dropping binary frame (text-only contract)")
                                },
                                doneRef.safe.get.unit
                            )
                        }
                    }
                ).andThen(Abort.run[Closed](inbound.close).unit)
            }.map(_ => transport)

end JsonRpcHttpTransport
