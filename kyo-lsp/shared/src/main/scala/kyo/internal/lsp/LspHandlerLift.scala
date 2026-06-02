package kyo.internal.lsp

import kyo.*

/** Lifts each `LspHandler` carrier to a `JsonRpcRoute` that wraps the user closure in
  * `Lsp.local.let(Present(ctx))` so the body can reach the per-request context through the
  * `Lsp.*` accessors.
  *
  * The `serverRef` / `clientRef` forward references are populated after `JsonRpcHandler.initUnscoped`
  * completes; the wrapped closure reads them lazily on every invocation.
  *
  * Error mappings are folded onto the produced `JsonRpcRoute` via `.error[E2]` for each
  * registered `LspHandler.ErrorMapping`.
  *
  * Token extraction reads `workDoneToken` and `partialResultToken` from the encoded
  * `Structure.Value` representation of the params, then threads them into `Lsp.RequestContext`.
  */
private[kyo] object LspHandlerLift:

    import LspHandler.*

    // =========================================================================
    // Public entry points
    // =========================================================================

    def liftServer(
        handler: LspHandler[?, ?, ?],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        val base = buildServerRoute(handler, serverRef, registry, encodingRef)
        applyErrors(base, handler.errorMappings)
    end liftServer

    def liftClient(
        handler: LspHandler[?, ?, ?],
        clientRef: AtomicRef[Maybe[LspClient.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        val base = buildClientRoute(handler, clientRef, registry, encodingRef)
        applyErrors(base, handler.errorMappings)
    end liftClient

    // =========================================================================
    // Error mapping fold
    // =========================================================================

    private def applyErrors(base: JsonRpcRoute[?, ?, ?], mappings: Chunk[ErrorMapping[?]]): JsonRpcRoute[?, ?, ?] =
        mappings.foldLeft(base) { (acc, m) => applyOne(acc, m) }

    private def applyOne[E](base: JsonRpcRoute[?, ?, ?], m: ErrorMapping[E]): JsonRpcRoute[?, ?, ?] =
        given Schema[E]      = m.schema
        given ConcreteTag[E] = m.tag
        base.asInstanceOf[JsonRpcRoute[Any, Any, Nothing]].error[E](m.code, m.message)
    end applyOne

    // =========================================================================
    // Token extraction from Structure.Value params
    // =========================================================================

    private def extractToken(sv: Structure.Value, field: String): Maybe[ProgressToken] =
        sv match
            case Structure.Value.Record(fields) =>
                fields.iterator.collectFirst {
                    case (k, v) if k == field =>
                        v match
                            case Structure.Value.Integer(n) => Present(ProgressToken.IntToken(n.toInt))
                            case Structure.Value.Str(s)     => Present(ProgressToken.StringToken(s))
                            case _                          => Absent
                }.getOrElse(Absent)
            case _ => Absent

    // =========================================================================
    // Server route builders
    // =========================================================================

    private def buildServerRoute(
        handler: LspHandler[?, ?, ?],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        handler match
            case h: RequestHandler[?, ?, ?] =>
                liftServerRequest(h, serverRef, registry, encodingRef)
            case h: NotificationHandler[?, ?] =>
                liftServerNotification(h, serverRef, registry, encodingRef)
            case h: CustomHandler[?, ?, ?] =>
                liftServerCustom(h, serverRef, registry, encodingRef)
        end match
    end buildServerRoute

    private def liftServerRequest[In, Out, E](
        h: RequestHandler[In, Out, E],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In]  = h.inSchema
        given Schema[Out] = h.outSchema
        JsonRpcRoute.request[In, Out](h.name) { (in, jrCtx) =>
            // AllowUnsafe: synchronous read of forward server ref and encoding ref.
            val mSrv = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            val enc  = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            mSrv match
                case Present(srv) =>
                    val paramsValue = Structure.encode[In](in)
                    val wdt         = extractToken(paramsValue, "workDoneToken")
                    val prt         = extractToken(paramsValue, "partialResultToken")
                    val ctx = Lsp.RequestContext(
                        jsonRpc = jrCtx,
                        peer = Lsp.Peer.Server(srv.safe),
                        workDoneToken = wdt,
                        partialResultToken = prt,
                        documents = registry,
                        positionEncoding = enc
                    )
                    Lsp.local.let(Present(ctx))(h.handlerFn(in))
                case Absent =>
                    throw new IllegalStateException(s"LspServer not initialised for '${h.name}'")
            end match
        }
    end liftServerRequest

    private def liftServerNotification[In, E](
        h: NotificationHandler[In, E],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In] = h.inSchema
        JsonRpcRoute.notification[In](h.name) { (in, jrCtx) =>
            val mSrv = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            val enc  = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            mSrv match
                case Present(srv) =>
                    val paramsValue = Structure.encode[In](in)
                    val wdt         = extractToken(paramsValue, "workDoneToken")
                    val prt         = extractToken(paramsValue, "partialResultToken")
                    val ctx = Lsp.RequestContext(
                        jsonRpc = jrCtx,
                        peer = Lsp.Peer.Server(srv.safe),
                        workDoneToken = wdt,
                        partialResultToken = prt,
                        documents = registry,
                        positionEncoding = enc
                    )
                    Lsp.local.let(Present(ctx))(h.handlerFn(in))
                case Absent =>
                    throw new IllegalStateException(s"LspServer not initialised for '${h.name}'")
            end match
        }
    end liftServerNotification

    private def liftServerCustom[In, Out, E](
        h: CustomHandler[In, Out, E],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In]  = h.inSchema
        given Schema[Out] = h.outSchema
        JsonRpcRoute.request[In, Out](h.name) { (in, jrCtx) =>
            val mSrv = serverRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            val enc  = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            mSrv match
                case Present(srv) =>
                    val paramsValue = Structure.encode[In](in)
                    val wdt         = extractToken(paramsValue, "workDoneToken")
                    val prt         = extractToken(paramsValue, "partialResultToken")
                    val ctx = Lsp.RequestContext(
                        jsonRpc = jrCtx,
                        peer = Lsp.Peer.Server(srv.safe),
                        workDoneToken = wdt,
                        partialResultToken = prt,
                        documents = registry,
                        positionEncoding = enc
                    )
                    Lsp.local.let(Present(ctx))(h.handlerFn(in))
                case Absent =>
                    throw new IllegalStateException(s"LspServer not initialised for '${h.name}'")
            end match
        }
    end liftServerCustom

    // =========================================================================
    // Client route builders
    // =========================================================================

    private def buildClientRoute(
        handler: LspHandler[?, ?, ?],
        clientRef: AtomicRef[Maybe[LspClient.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        handler match
            case h: RequestHandler[?, ?, ?] =>
                liftClientRequest(h, clientRef, registry, encodingRef)
            case h: NotificationHandler[?, ?] =>
                liftClientNotification(h, clientRef, registry, encodingRef)
            case h: CustomHandler[?, ?, ?] =>
                liftClientCustom(h, clientRef, registry, encodingRef)
        end match
    end buildClientRoute

    private def liftClientRequest[In, Out, E](
        h: RequestHandler[In, Out, E],
        clientRef: AtomicRef[Maybe[LspClient.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In]  = h.inSchema
        given Schema[Out] = h.outSchema
        JsonRpcRoute.request[In, Out](h.name) { (in, jrCtx) =>
            val mCl = clientRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            val enc = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            mCl match
                case Present(cl) =>
                    val paramsValue = Structure.encode[In](in)
                    val wdt         = extractToken(paramsValue, "workDoneToken")
                    val prt         = extractToken(paramsValue, "partialResultToken")
                    val ctx = Lsp.RequestContext(
                        jsonRpc = jrCtx,
                        peer = Lsp.Peer.Client(cl.safe),
                        workDoneToken = wdt,
                        partialResultToken = prt,
                        documents = registry,
                        positionEncoding = enc
                    )
                    Lsp.local.let(Present(ctx))(h.handlerFn(in))
                case Absent =>
                    throw new IllegalStateException(s"LspClient not initialised for '${h.name}'")
            end match
        }
    end liftClientRequest

    private def liftClientNotification[In, E](
        h: NotificationHandler[In, E],
        clientRef: AtomicRef[Maybe[LspClient.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In] = h.inSchema
        JsonRpcRoute.notification[In](h.name) { (in, jrCtx) =>
            val mCl = clientRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            val enc = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            mCl match
                case Present(cl) =>
                    val paramsValue = Structure.encode[In](in)
                    val wdt         = extractToken(paramsValue, "workDoneToken")
                    val prt         = extractToken(paramsValue, "partialResultToken")
                    val ctx = Lsp.RequestContext(
                        jsonRpc = jrCtx,
                        peer = Lsp.Peer.Client(cl.safe),
                        workDoneToken = wdt,
                        partialResultToken = prt,
                        documents = registry,
                        positionEncoding = enc
                    )
                    Lsp.local.let(Present(ctx))(h.handlerFn(in))
                case Absent =>
                    throw new IllegalStateException(s"LspClient not initialised for '${h.name}'")
            end match
        }
    end liftClientNotification

    private def liftClientCustom[In, Out, E](
        h: CustomHandler[In, Out, E],
        clientRef: AtomicRef[Maybe[LspClient.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In]  = h.inSchema
        given Schema[Out] = h.outSchema
        JsonRpcRoute.request[In, Out](h.name) { (in, jrCtx) =>
            val mCl = clientRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            val enc = encodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)
            mCl match
                case Present(cl) =>
                    val paramsValue = Structure.encode[In](in)
                    val wdt         = extractToken(paramsValue, "workDoneToken")
                    val prt         = extractToken(paramsValue, "partialResultToken")
                    val ctx = Lsp.RequestContext(
                        jsonRpc = jrCtx,
                        peer = Lsp.Peer.Client(cl.safe),
                        workDoneToken = wdt,
                        partialResultToken = prt,
                        documents = registry,
                        positionEncoding = enc
                    )
                    Lsp.local.let(Present(ctx))(h.handlerFn(in))
                case Absent =>
                    throw new IllegalStateException(s"LspClient not initialised for '${h.name}'")
            end match
        }
    end liftClientCustom

end LspHandlerLift
