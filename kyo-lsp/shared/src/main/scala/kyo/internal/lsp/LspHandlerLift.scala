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
        val base = buildRoute(handler, serverRef, "LspServer", (s: LspServer.Unsafe) => Lsp.Peer.Server(s.safe), registry, encodingRef)
        applyErrors(base, handler.errorMappings)
    end liftServer

    def liftClient(
        handler: LspHandler[?, ?, ?],
        clientRef: AtomicRef[Maybe[LspClient.Unsafe]],
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        val base = buildRoute(handler, clientRef, "LspClient", (c: LspClient.Unsafe) => Lsp.Peer.Client(c.safe), registry, encodingRef)
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
    // Shared route builder
    // =========================================================================

    /** The per-invocation body shared by every lifted route: reads the forward-populated peer ref
      * and the negotiated encoding through the safe `Sync` API, binds the per-request `Lsp` context,
      * and dispatches the handler under `Lsp.local`. A still-empty peer ref is a framework invariant
      * violation (the engine populates it before any route runs), surfaced as a panic rather than a
      * typed error.
      */
    private def contextualBody[In, Out, E, P](
        name: String,
        handlerFn: In => Out < (Async & Abort[JsonRpcResponse.Halt | E]),
        peerRef: AtomicRef[Maybe[P]],
        peerKind: String,
        mkPeer: P => Lsp.Peer,
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Schema[In], Frame): (In, JsonRpcRoute.Context) => Out < (Async & Abort[JsonRpcResponse.Halt | E]) =
        (in, jrCtx) =>
            peerRef.get.map { mPeer =>
                encodingRef.get.map { enc =>
                    mPeer match
                        case Present(peer) =>
                            val paramsValue = Structure.encode[In](in)
                            val ctx = Lsp.RequestContext(
                                jsonRpc = jrCtx,
                                peer = mkPeer(peer),
                                workDoneToken = extractToken(paramsValue, "workDoneToken"),
                                partialResultToken = extractToken(paramsValue, "partialResultToken"),
                                documents = registry,
                                positionEncoding = enc
                            )
                            Lsp.local.let(Present(ctx))(handlerFn(in))
                        case Absent =>
                            Abort.panic(new IllegalStateException(s"$peerKind not initialised for '$name'"))
                }
            }
    end contextualBody

    /** Dispatches a handler to a `JsonRpcRoute` of the matching wire shape (request, notification, or
      * custom-as-request), wiring in the peer-specific context through [[contextualBody]].
      */
    private def buildRoute[P](
        handler: LspHandler[?, ?, ?],
        peerRef: AtomicRef[Maybe[P]],
        peerKind: String,
        mkPeer: P => Lsp.Peer,
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        handler match
            case h: RequestHandler[?, ?, ?]   => requestRoute(h, peerRef, peerKind, mkPeer, registry, encodingRef)
            case h: NotificationHandler[?, ?] => notificationRoute(h, peerRef, peerKind, mkPeer, registry, encodingRef)
            case h: CustomHandler[?, ?, ?]    => customRoute(h, peerRef, peerKind, mkPeer, registry, encodingRef)
        end match
    end buildRoute

    private def requestRoute[In, Out, E, P](
        h: RequestHandler[In, Out, E],
        peerRef: AtomicRef[Maybe[P]],
        peerKind: String,
        mkPeer: P => Lsp.Peer,
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In]  = h.inSchema
        given Schema[Out] = h.outSchema
        JsonRpcRoute.request[In, Out](h.name)(contextualBody(h.name, h.handlerFn, peerRef, peerKind, mkPeer, registry, encodingRef))
    end requestRoute

    private def notificationRoute[In, E, P](
        h: NotificationHandler[In, E],
        peerRef: AtomicRef[Maybe[P]],
        peerKind: String,
        mkPeer: P => Lsp.Peer,
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In] = h.inSchema
        JsonRpcRoute.notification[In](h.name)(contextualBody(h.name, h.handlerFn, peerRef, peerKind, mkPeer, registry, encodingRef))
    end notificationRoute

    private def customRoute[In, Out, E, P](
        h: CustomHandler[In, Out, E],
        peerRef: AtomicRef[Maybe[P]],
        peerKind: String,
        mkPeer: P => Lsp.Peer,
        registry: LspDocumentRegistryImpl,
        encodingRef: AtomicRef[LspHandler.PositionEncodingKind]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In]  = h.inSchema
        given Schema[Out] = h.outSchema
        JsonRpcRoute.request[In, Out](h.name)(contextualBody(h.name, h.handlerFn, peerRef, peerKind, mkPeer, registry, encodingRef))
    end customRoute

end LspHandlerLift
