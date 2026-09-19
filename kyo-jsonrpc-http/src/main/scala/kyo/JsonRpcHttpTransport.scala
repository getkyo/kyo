package kyo

/** A [[JsonRpcTransport]] backed by a kyo-http WebSocket connection.
  *
  * Lifts `HttpWebSocket` text frames to and from JSON-RPC envelopes: outbound envelopes are encoded to
  * JSON text and sent as WebSocket text frames; inbound text frames are parsed back to envelopes (a
  * parse or decode failure becomes a [[JsonRpcMalformedMessage]] rather than tearing down the stream).
  * The contract is text-only, and the two factories differ in what they do with a binary frame: the
  * `ws` overload surfaces it as a [[JsonRpcMalformedMessage]] so a server hands its peer a
  * protocol-level failure, while the `url` overload drops it with a warning so a client is not
  * derailed by a server that mixes frame types.
  *
  * The `url` factory connects before it returns: a failed upgrade aborts `HttpException`, and the
  * established connection then runs in a background fiber, closed via the transport's `close()` and a
  * `Scope.ensure` cleanup. When the connection ends, both directions close, so a later `send` aborts
  * `Closed` instead of buffering into a channel nothing drains.
  *
  * Prefer the `JsonRpcTransport.webSocket(url)` extension for the common case; this object's
  * [[webSocket]] is the full factory with header and codec overrides.
  */
object JsonRpcHttpTransport:

    /** Adapts an accepted `HttpWebSocket` as a [[JsonRpcTransport]].
      *
      * This is the server-side counterpart to [[webSocket]]: each JSON-RPC envelope is carried as
      * one WebSocket text frame. Binary frames surface as malformed JSON-RPC messages so the peer
      * gets a protocol-level failure instead of a silent drop.
      */
    def webSocket(
        ws: HttpWebSocket,
        codec: Schema[JsonRpcEnvelope]
    )(using Frame): JsonRpcTransport < Sync =
        Sync.defer {
            new JsonRpcTransport:
                def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
                    Abort.catching[JsonRpcError](Structure.encode[JsonRpcEnvelope](env)(using codec)).map { structure =>
                        ws.put(HttpWebSocket.Payload.Text(Json.encode(structure)))
                    }

                def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
                    ws.stream.map {
                        case HttpWebSocket.Payload.Text(text) =>
                            Json.decode[Structure.Value](text) match
                                case Result.Success(sv) =>
                                    Structure.decode[JsonRpcEnvelope](sv)(using codec)
                                        .getOrElse(JsonRpcMalformedMessage(Absent, "decode failed", sv))
                                case Result.Failure(e) =>
                                    JsonRpcMalformedMessage(Absent, s"json parse: ${e.getMessage}", Structure.Value.Str(text))
                                case Result.Panic(t) =>
                                    JsonRpcMalformedMessage(Absent, s"json parse panic: ${t.getMessage}", Structure.Value.Str(text))
                        case HttpWebSocket.Payload.Binary(data) =>
                            JsonRpcMalformedMessage(Absent, "binary WebSocket frame", Structure.Value.Str(s"${data.size} bytes"))
                    }

                def close(using Frame): Unit < Async =
                    ws.close()
            end new
        }
    end webSocket

    def webSocket(ws: HttpWebSocket)(using Frame): JsonRpcTransport < Sync =
        webSocket(ws, summon[Schema[JsonRpcEnvelope]])

    /** Opens a WebSocket to `url` and adapts it as a [[JsonRpcTransport]].
      *
      * @param url
      *   the WebSocket endpoint.
      * @param headers
      *   headers sent on the upgrade request; defaults to none.
      * @param codec
      *   the envelope schema used to encode and decode the JSON-RPC wire shape; defaults to the standard one.
      */
    def webSocket(
        url: HttpUrl,
        headers: HttpHeaders = HttpHeaders.empty,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException]) =
        for
            // initUnscoped because lifetime is managed by the transport close() and Scope.ensure.
            inbound  <- Channel.initUnscoped[JsonRpcEnvelope](64)
            outbound <- Channel.initUnscoped[HttpWebSocket.Payload](64)
            doneRef  <- Fiber.Promise.init[Unit, Async]
            // Carries the outcome of the upgrade from the connection fiber back to this factory, so a
            // connect failure reaches the caller as the Abort[HttpException] the effect row declares
            // instead of being swallowed by the fiber's own Abort.run.
            connected <- Fiber.Promise.init[Unit, Abort[HttpException] & Async]
            _         <- Scope.ensure(
                doneRef.completeUnitDiscard.andThen(Abort.run[Closed](inbound.close).unit)
                    .andThen(Abort.run[Closed](outbound.close).unit)
            )
        yield
            val transport: JsonRpcTransport = new JsonRpcTransport:
                def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
                    // Structure.encode is pure but throws a JsonRpcError for the unencodable cases (a Malformed
                    // message, or a lenient reserved-extras key); Abort.catching reifies that into the
                    // declared JsonRpcError row rather than reporting a frame that was never queued as sent.
                    Abort.catching[JsonRpcError](Structure.encode[JsonRpcEnvelope](env)(using codec)).map { structure =>
                        // Json.encode[Structure.Value] emits standard JSON-RPC wire text via the identity
                        // wire shape (Record to object, Str to string, Integer/Decimal to number), so the
                        // universal value tree serializes as plain JSON rather than a tagged kyo-schema encoding.
                        val jsonStr = Json.encode(structure)
                        outbound.put(HttpWebSocket.Payload.Text(jsonStr))
                    }

                def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
                    inbound.streamUntilClosed()

                def close(using Frame): Unit < Async =
                    doneRef.completeUnitDiscard
                        .andThen(Abort.run[Closed](inbound.close).unit)
                        .andThen(Abort.run[Closed](outbound.close).unit)

            // Start the WS connection in a background fiber.
            Fiber.initUnscoped {
                Abort.run[HttpException](
                    HttpClient.webSocket(url, headers, HttpWebSocket.Config()) { ws =>
                        // The block runs only once the upgrade succeeded, so this is the connect
                        // acknowledgement the factory below is waiting on.
                        connected.completeUnitDiscard.andThen {
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
                                            // Json.decode[Structure.Value] parses standard JSON-RPC wire text into the
                                            // universal value tree via the identity wire shape.
                                            Json.decode[Structure.Value](text) match
                                                case Result.Success(sv) =>
                                                    // Structure.decode is total for the envelope schema: a bad shape decodes to
                                                    // a Malformed envelope rather than a Result.Failure, so getOrElse keeps the
                                                    // seam total without falling back.
                                                    val env = Structure.decode[JsonRpcEnvelope](sv)(using codec)
                                                        .getOrElse(JsonRpcMalformedMessage(Absent, "decode failed", sv))
                                                    Abort.run[Closed](inbound.put(env)).unit
                                                case Result.Failure(e) =>
                                                    Abort.run[Closed](
                                                        inbound.put(
                                                            JsonRpcMalformedMessage(
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
                                    doneRef.get.unit
                                )
                            }
                        }
                    }
                ).map { result =>
                    // Two cases reach here. The upgrade failed, and `connected` is still pending, so this
                    // completion is what the factory raises. Or the session ended after connecting, and this
                    // completion is a no-op. Either way both channels close: closing only `inbound` would
                    // leave `send` accepting 64 more envelopes into a channel nothing drains, then suspending.
                    connected.completeDiscard(result.map(_ => ())).andThen(
                        Abort.run[Closed](inbound.close).unit
                            .andThen(Abort.run[Closed](outbound.close).unit)
                    )
                }
            }.andThen(connected.get).andThen(transport)

    extension (self: JsonRpcTransport.type)
        def webSocket(ws: HttpWebSocket)(using Frame): JsonRpcTransport < Sync =
            JsonRpcHttpTransport.webSocket(ws)
        def webSocket(ws: HttpWebSocket, codec: Schema[JsonRpcEnvelope])(using Frame): JsonRpcTransport < Sync =
            JsonRpcHttpTransport.webSocket(ws, codec)
        def webSocket(url: HttpUrl)(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException]) =
            JsonRpcHttpTransport.webSocket(url)
        def webSocket(url: HttpUrl, headers: HttpHeaders)(using Frame): JsonRpcTransport < (Async & Scope & Abort[HttpException]) =
            JsonRpcHttpTransport.webSocket(url, headers)
        def webSocket(url: HttpUrl, headers: HttpHeaders, codec: Schema[JsonRpcEnvelope])(using
            Frame
        ): JsonRpcTransport < (Async & Scope & Abort[HttpException]) =
            JsonRpcHttpTransport.webSocket(url, headers, codec)
        def webSocket(url: HttpUrl, codec: Schema[JsonRpcEnvelope])(using
            Frame
        ): JsonRpcTransport < (Async & Scope & Abort[HttpException]) =
            JsonRpcHttpTransport.webSocket(url, codec = codec)
    end extension

end JsonRpcHttpTransport
