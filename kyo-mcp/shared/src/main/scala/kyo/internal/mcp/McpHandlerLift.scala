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
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        val base: JsonRpcRoute[?, ?, ?] = handler match
            case h: McpHandler.ToolHandler[?, ?, ?]       => liftTool(h, serverRef)
            case h: McpHandler.ToolMultiHandler[?, ?]     => liftToolMulti(h, serverRef)
            case h: McpHandler.TypedPromptHandler[?, ?]   => liftTypedPrompt(h, serverRef)
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

    // Exposed for the client-side McpClientHandlerLift to reuse the same context binding.
    private[kyo] def bindClientCtx[A, S](
        jrCtx: JsonRpcRoute.Context,
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(body: => A < S)(using Frame): A < (S & Async) =
        withCtx(jrCtx, serverRef)(body)

    // Waits for the live server rather than reading a slot that may not be filled yet. The engine
    // completes the forward reference immediately after construction, but the dispatch loop is already
    // running by then, so a request that arrives in that window used to find the slot empty and was
    // answered with a -32603 "handler panicked" naming a handler that had not been called. Waiting makes
    // the window unobservable: the wait is over as soon as the server exists, which is at once.
    private def withCtx[A, S](
        jrCtx: JsonRpcRoute.Context,
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(body: => A < S)(using Frame): A < (S & Async) =
        serverRef.get.map(srv => Mcp.local.let(Present(Mcp.RequestContext(jrCtx, srv.safe)))(body))
    end withCtx

    private def liftTool[In, Out, E](
        h: McpHandler.ToolHandler[In, Out, E],
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[Out] = h.outSchema
        JsonRpcRoute.request[In, McpHandler.ToolOutcome](h.name) { (in, jrCtx) =>
            withCtx(jrCtx, serverRef) {
                h.toolHandler(in).map { out =>
                    McpHandler.ToolOutcome(
                        content = Chunk(McpContent.text(Json.encode[Out](out))),
                        isError = false,
                        structuredContent = Present(Structure.encode[Out](out))
                    )
                }
            }
        }(using h.inSchema, summon[Schema[McpHandler.ToolOutcome]])
    end liftTool

    private def liftToolMulti[In, E](
        h: McpHandler.ToolMultiHandler[In, E],
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[In, McpHandler.ToolOutcome](h.name) { (in, jrCtx) =>
            withCtx(jrCtx, serverRef)(h.toolHandler(in))
        }(using h.inSchema, summon[Schema[McpHandler.ToolOutcome]])

    private def liftResource[E](
        h: McpHandler.ResourceHandler[E],
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        val registeredUri  = h.resourceMeta.uri
        val registeredMime = h.resourceMeta.mimeType
        JsonRpcRoute.request[McpResourceUri, Chunk[McpHandler.ResourceContents]](registeredUri.asString) { (_, jrCtx) =>
            // Inbound URI always equals the registered URI (engine routes by URI match); the engine
            // stamps the registered URI and default mime onto each URI-less ResourceBody.
            withCtx(jrCtx, serverRef)(h.resourceHandler().map(_.map(stampBody(registeredUri, registeredMime, _))))
        }
    end liftResource

    private def liftResourceTemplate[E](
        h: McpHandler.ResourceTemplateHandler[E],
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        val template = h.resourceTemplateMeta.uriTemplate
        val regMime  = h.resourceTemplateMeta.mimeType
        JsonRpcRoute.request[McpResourceUri, Chunk[McpHandler.ResourceContents]](template.asString) {
            (uri, jrCtx) =>
                val bindings = template.extract(uri).getOrElse(Map.empty)
                val matched  = new McpHandler.ResourceMatch(uri, bindings)
                withCtx(jrCtx, serverRef)(h.resourceTemplateHandler(matched).map(_.map(stampBody(uri, regMime, _))))
        }
    end liftResourceTemplate

    // Stamps the engine-owned URI and the registered default mime onto a URI-less ResourceBody.
    // Exposed as private[mcp] so McpBuiltInRoutes (same package) can reuse the same stamp logic.
    private[mcp] def stampBody(
        uri: McpResourceUri,
        defaultMime: Maybe[McpMimeType],
        body: McpHandler.ResourceBody
    ): McpHandler.ResourceContents =
        val mime = body.mimeType.orElse(defaultMime)
        body match
            case McpHandler.ResourceBody.Text(_, text) => McpHandler.ResourceContents.Text(uri, mime, text)
            case McpHandler.ResourceBody.Blob(_, blob) => McpHandler.ResourceContents.Blob(uri, mime, blob)
    end stampBody

    private def liftTypedPrompt[In, E](
        h: McpHandler.TypedPromptHandler[In, E],
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        given Schema[In] = h.inSchema
        JsonRpcRoute.request[Map[String, String], McpHandler.PromptOutcome](h.name) { (args, jrCtx) =>
            withCtx(jrCtx, serverRef) {
                Structure.decode[In](McpPromptArguments.encodeArgs(args)) match
                    case Result.Success(in) => h.typedPromptHandler(in)
                    case Result.Failure(e)  => Abort.fail(McpInvalidArgumentException(h.name, "arguments", e.getMessage))
                    case Result.Panic(t)    => Abort.panic(t)
            }
        }
    end liftTypedPrompt

    private def liftPrompt[E](
        h: McpHandler.PromptHandler[E],
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[Map[String, String], McpHandler.PromptOutcome](h.name) { (args, jrCtx) =>
            withCtx(jrCtx, serverRef)(h.promptHandler(args))
        }

    private def liftCompletion[E](
        h: McpHandler.CompletionHandler[E],
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[(McpHandler.CompletionRef, McpHandler.CompletionArg), McpHandler.CompletionOutcome](h.name) {
            (pair, jrCtx) => withCtx(jrCtx, serverRef)(h.completionHandler(pair._2, Absent))
        }

    private def liftCustom[In, Out, E](
        h: McpHandler.CustomHandler[In, Out, E],
        serverRef: Fiber.Promise[McpServer.Unsafe, Any]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[In, Out](h.method) { (in, jrCtx) =>
            withCtx(jrCtx, serverRef)(h.customHandler(in))
        }(using h.inSchema, h.outSchema)

end McpHandlerLift
