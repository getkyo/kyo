package kyo.internal.mcp

import kyo.*

/** Lifts each [[McpHandler]] to a [[JsonRpcRoute]] that wraps the user closure in
  * `Mcp.local.let(Present(RequestContext(jrCtx, server)))` so the closure can reach the
  * per-request context through the `Mcp.*` accessors.
  *
  * The `serverRef` is a forward reference that the engine populates once `JsonRpcHandler.initUnscoped`
  * completes; the wrapped closure reads it lazily on every invocation.
  *
  * The lift also threads the handler's per-handler error mappings onto the produced
  * `JsonRpcRoute` by calling `.error[E2](code, message)` for each registered mapping.
  */
private[kyo] object McpHandlerLift:

    def lift(
        handler: McpHandler[?, ?, ?],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        val base: JsonRpcRoute[?, ?, ?] = handler match
            case h: McpHandler.ToolHandler[?, ?, ?]       => liftTool(h, serverRef)
            case h: McpHandler.ToolMultiHandler[?, ?]     => liftToolMulti(h, serverRef)
            case h: McpHandler.ResourceHandler[?]         => liftResource(h, serverRef)
            case h: McpHandler.ResourceTemplateHandler[?] => liftResourceTemplate(h, serverRef)
            case h: McpHandler.PromptHandler[?]           => liftPrompt(h, serverRef)
            case h: McpHandler.CompletionHandler[?]       => liftCompletion(h, serverRef)
            case h: McpHandler.CustomHandler[?, ?, ?]     => liftCustom(h, serverRef)
        applyErrors(base, handler.errorMappings)
    end lift

    // Apply each registered McpHandler.ErrorMapping by calling JsonRpcRoute.error[E2](code, message).
    private def applyErrors(base: JsonRpcRoute[?, ?, ?], mappings: Chunk[McpHandler.ErrorMapping[?]]): JsonRpcRoute[?, ?, ?] =
        mappings.foldLeft(base) { (acc, m) =>
            // m is McpHandler.ErrorMapping[E] for some E; the schema and tag are captured in givens.
            applyOne(acc, m)
        }

    private def applyOne[E](base: JsonRpcRoute[?, ?, ?], m: McpHandler.ErrorMapping[E]): JsonRpcRoute[?, ?, ?] =
        given Schema[E]      = m.schema
        given ConcreteTag[E] = m.tag
        base.asInstanceOf[JsonRpcRoute[Any, Any, Nothing]].error[E](m.code, m.message)
    end applyOne

    // Reads the live server from the forward reference. Returns Absent before init completes (impossible
    // during a real dispatch because the engine writes the ref before any handler runs).
    private def readServer(serverRef: AtomicRef[Maybe[McpServer.Unsafe]]): Maybe[McpServer.Unsafe] =
        // AllowUnsafe: synchronous read of forward server reference (Decision 2 carryover).
        serverRef.unsafe.get()(using AllowUnsafe.embrace.danger)

    private def withCtx[A, S](
        jrCtx: JsonRpcRoute.Context,
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(body: => A < S)(using Frame): A < S =
        readServer(serverRef) match
            case Present(srv) =>
                Mcp.local.let(Present(Mcp.RequestContext(jrCtx, srv.safe)))(body)
            case Absent =>
                // The engine populates serverRef synchronously after construction; this can only fire
                // if a dispatch races initialisation. Surface as a panic so it shows up loudly.
                throw new IllegalStateException(s"McpServer not initialised for route '${jrCtx.requestId}'")
    end withCtx

    private def liftTool[In, Out <: McpContent, E](
        h: McpHandler.ToolHandler[In, Out, E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[In, McpRoute.ToolCallResult](h.toolRoute.name) { (in, jrCtx) =>
            withCtx(jrCtx, serverRef) {
                h.toolHandler(in).map(out => McpRoute.ToolCallResult(Chunk(out), isError = false, structuredContent = Absent))
            }
        }(using h.toolRoute.inSchema, summon[Schema[McpRoute.ToolCallResult]])

    private def liftToolMulti[In, E](
        h: McpHandler.ToolMultiHandler[In, E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[In, McpRoute.ToolCallResult](h.toolRoute.name) { (in, jrCtx) =>
            withCtx(jrCtx, serverRef)(h.toolHandler(in))
        }(using h.toolRoute.inSchema, summon[Schema[McpRoute.ToolCallResult]])

    private def liftResource[E](
        h: McpHandler.ResourceHandler[E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[McpResourceUri, Chunk[McpRoute.ResourceContents]](h.resourceMeta.uri.asString) { (uri, jrCtx) =>
            withCtx(jrCtx, serverRef)(h.resourceHandler(uri))
        }

    private def liftResourceTemplate[E](
        h: McpHandler.ResourceTemplateHandler[E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[McpResourceUri, Chunk[McpRoute.ResourceContents]](h.resourceTemplateMeta.uriTemplate.asString) {
            (uri, jrCtx) =>
                withCtx(jrCtx, serverRef)(h.resourceTemplateHandler(uri))
        }

    private def liftPrompt[E](
        h: McpHandler.PromptHandler[E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[Map[String, String], McpRoute.PromptGetResult](h.promptRoute.name) { (args, jrCtx) =>
            withCtx(jrCtx, serverRef)(h.promptHandler(args))
        }

    private def liftCompletion[E](
        h: McpHandler.CompletionHandler[E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[(McpRoute.CompletionRef, McpRoute.CompletionArg), McpRoute.CompletionResult](h.completionRoute.name) {
            (pair, jrCtx) => withCtx(jrCtx, serverRef)(h.completionHandler(pair._1, pair._2, Absent))
        }

    private def liftCustom[In, Out, E](
        h: McpHandler.CustomHandler[In, Out, E],
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[In, Out](h.method) { (in, jrCtx) =>
            withCtx(jrCtx, serverRef)(h.customHandler(in))
        }(using h.customRoute.inSchema, h.outSchema)

end McpHandlerLift
