package kyo

/** Pairs an [[McpRoute]] descriptor with an implementation closure.
  *
  * `McpHandler[In, Out, +E]` is the value the engine consumes: the route's metadata is still
  * reachable via `.route`, and `.error[E2](code, message)` adds a typed mapping in the same
  * shape as `JsonRpcRoute.error`. The engine lifts each `McpHandler` to a `JsonRpcRoute` at
  * `McpServer.init` time, wrapping the handler closure in a `Mcp.local.let` so route bodies can
  * reach the per-request context through the `Mcp.*` accessors.
  *
  * Mirrors `HttpHandler` in `kyo-http`: route + closure = handler.
  *
  * @tparam In  the request parameter type
  * @tparam Out the response result type
  * @tparam E   the union of user-registered domain error types
  */
sealed trait McpHandler[In, Out, +E]:
    def route: McpRoute[In]
    def name: String        = route.name
    def kind: McpRoute.Kind = route.kind

    /** Per-handler error mappings registered via `.error[E2]`. The engine threads them onto the
      * lifted `JsonRpcRoute` at init time.
      */
    private[kyo] def errorMappings: Chunk[McpHandler.ErrorMapping[?]]

    /** Adds a typed-error mapping. When the handler aborts with a value of type `E2`, the engine
      * emits a JSON-RPC error with the supplied `code` and `message`. Mirrors
      * `JsonRpcRoute.error[E2]`.
      */
    def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpHandler[In, Out, E | E2]
end McpHandler

object McpHandler:

    /** Marker trait for handlers that register a logging hook. Used by
      * [[kyo.internal.mcp.McpCatalog]] to auto-derive the `logging` server capability.
      */
    sealed private[kyo] trait LoggingHook

    final private[kyo] class ErrorMapping[E](val code: Int, val message: String)(using val tag: ConcreteTag[E], val schema: Schema[E]):
        def matches(e: Any): Boolean = tag.accepts(e)
    end ErrorMapping

    // ------------- Constructors used by McpRoute's inline .handler methods -------------

    private[kyo] def makeTool[In, Out <: McpContent](
        route: McpRoute.ToolRoute[In],
        outSchema: Schema[Out],
        handler: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt])
    )(using frame: Frame): McpHandler[In, Out, McpException] =
        new ToolHandler[In, Out, McpException](route, route.toolMeta, outSchema, handler, Chunk.empty)
    end makeTool

    private[kyo] def makeToolMulti[In](
        route: McpRoute.ToolMultiRoute[In],
        handler: In => McpRoute.ToolCallResult < (Async & Abort[McpException | JsonRpcResponse.Halt])
    )(using Frame): McpHandler[In, McpRoute.ToolCallResult, McpException] =
        new ToolMultiHandler[In, McpException](route, route.toolMeta, handler, Chunk.empty)

    private[kyo] def makeResource(
        route: McpRoute.ResourceRoute,
        handler: McpResourceUri => Chunk[McpRoute.ResourceContents] < (Async & Abort[McpException | JsonRpcResponse.Halt])
    )(using Frame): McpHandler[McpResourceUri, Chunk[McpRoute.ResourceContents], McpException] =
        new ResourceHandler[McpException](route, handler, Chunk.empty)

    private[kyo] def makeResourceTemplate(
        route: McpRoute.ResourceTemplateRoute,
        handler: McpResourceUri => Chunk[McpRoute.ResourceContents] < (Async & Abort[McpException | JsonRpcResponse.Halt])
    )(using Frame): McpHandler[McpResourceUri, Chunk[McpRoute.ResourceContents], McpException] =
        new ResourceTemplateHandler[McpException](route, handler, Chunk.empty)

    private[kyo] def makePrompt(
        route: McpRoute.PromptRoute,
        handler: Map[String, String] => McpRoute.PromptGetResult < (Async & Abort[McpException | JsonRpcResponse.Halt])
    )(using Frame): McpHandler[Map[String, String], McpRoute.PromptGetResult, McpException] =
        new PromptHandler[McpException](route, handler, Chunk.empty)

    private[kyo] def makeCompletion(
        route: McpRoute.CompletionRoute,
        handler: (
            McpRoute.CompletionRef,
            McpRoute.CompletionArg,
            Maybe[McpRoute.CompletionArg.Context]
        ) => McpRoute.CompletionResult < (Async & Abort[McpException | JsonRpcResponse.Halt])
    )(using Frame): McpHandler[(McpRoute.CompletionRef, McpRoute.CompletionArg), McpRoute.CompletionResult, McpException] =
        new CompletionHandler[McpException](route, handler, Chunk.empty)

    private[kyo] def makeCustom[In, Out](
        route: McpRoute.CustomRoute[In],
        outSchema: Schema[Out],
        handler: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt])
    )(using Frame): McpHandler[In, Out, McpException] =
        new CustomHandler[In, Out, McpException](route, outSchema, handler, Chunk.empty)

    // ------------- Concrete handler types -------------

    final private[kyo] class ToolHandler[In, Out <: McpContent, +E] private[kyo] (
        val toolRoute: McpRoute.ToolRoute[In],
        val toolMeta: McpRoute.ToolMeta,
        val outSchema: Schema[Out],
        val toolHandler: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[In, Out, E]:
        def route: McpRoute[In] = toolRoute

        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpHandler[In, Out, E | E2] =
            new ToolHandler[In, Out, E | E2](
                toolRoute,
                toolMeta,
                outSchema,
                toolHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )
    end ToolHandler

    final private[kyo] class ToolMultiHandler[In, +E] private[kyo] (
        val toolRoute: McpRoute.ToolMultiRoute[In],
        val toolMeta: McpRoute.ToolMeta,
        val toolHandler: In => McpRoute.ToolCallResult < (Async & Abort[McpException | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[In, McpRoute.ToolCallResult, E]:
        def route: McpRoute[In] = toolRoute

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[In, McpRoute.ToolCallResult, E | E2] =
            new ToolMultiHandler[In, E | E2](toolRoute, toolMeta, toolHandler, errorMappings.append(new ErrorMapping[E2](code, message)))
    end ToolMultiHandler

    final private[kyo] class ResourceHandler[+E] private[kyo] (
        val resourceRoute: McpRoute.ResourceRoute,
        val resourceHandler: McpResourceUri => Chunk[McpRoute.ResourceContents] < (Async & Abort[McpException | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[McpResourceUri, Chunk[McpRoute.ResourceContents], E]:
        def resourceMeta: McpRoute.ResourceMeta = resourceRoute.resourceMeta
        def subscribable: Boolean               = resourceRoute.subscribable
        def route: McpRoute[McpResourceUri]     = resourceRoute

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[McpResourceUri, Chunk[McpRoute.ResourceContents], E | E2] =
            new ResourceHandler[E | E2](resourceRoute, resourceHandler, errorMappings.append(new ErrorMapping[E2](code, message)))
    end ResourceHandler

    final private[kyo] class ResourceTemplateHandler[+E] private[kyo] (
        val resourceTemplateRoute: McpRoute.ResourceTemplateRoute,
        val resourceTemplateHandler: McpResourceUri => Chunk[
            McpRoute.ResourceContents
        ] < (Async & Abort[McpException | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[McpResourceUri, Chunk[McpRoute.ResourceContents], E]:
        def resourceTemplateMeta: McpRoute.ResourceTemplateMeta = resourceTemplateRoute.resourceTemplateMeta
        def route: McpRoute[McpResourceUri]                     = resourceTemplateRoute

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[McpResourceUri, Chunk[McpRoute.ResourceContents], E | E2] =
            new ResourceTemplateHandler[E | E2](
                resourceTemplateRoute,
                resourceTemplateHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )
    end ResourceTemplateHandler

    final private[kyo] class PromptHandler[+E] private[kyo] (
        val promptRoute: McpRoute.PromptRoute,
        val promptHandler: Map[String, String] => McpRoute.PromptGetResult < (Async & Abort[McpException | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[Map[String, String], McpRoute.PromptGetResult, E]:
        def promptMeta: McpRoute.PromptMeta      = promptRoute.promptMeta
        def route: McpRoute[Map[String, String]] = promptRoute

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[Map[String, String], McpRoute.PromptGetResult, E | E2] =
            new PromptHandler[E | E2](promptRoute, promptHandler, errorMappings.append(new ErrorMapping[E2](code, message)))
    end PromptHandler

    final private[kyo] class CompletionHandler[+E] private[kyo] (
        val completionRoute: McpRoute.CompletionRoute,
        val completionHandler: (
            McpRoute.CompletionRef,
            McpRoute.CompletionArg,
            Maybe[McpRoute.CompletionArg.Context]
        ) => McpRoute.CompletionResult < (Async & Abort[McpException | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[(McpRoute.CompletionRef, McpRoute.CompletionArg), McpRoute.CompletionResult, E]:
        def ref: McpRoute.CompletionRef                                       = completionRoute.ref
        def route: McpRoute[(McpRoute.CompletionRef, McpRoute.CompletionArg)] = completionRoute

        def error[E2](using
            schema: Schema[E2],
            tag: ConcreteTag[E2]
        )(code: Int, message: String): McpHandler[(McpRoute.CompletionRef, McpRoute.CompletionArg), McpRoute.CompletionResult, E | E2] =
            new CompletionHandler[E | E2](completionRoute, completionHandler, errorMappings.append(new ErrorMapping[E2](code, message)))
    end CompletionHandler

    final private[kyo] class CustomHandler[In, Out, +E] private[kyo] (
        val customRoute: McpRoute.CustomRoute[In],
        val outSchema: Schema[Out],
        val customHandler: In => Out < (Async & Abort[McpException | JsonRpcResponse.Halt]),
        val errorMappings: Chunk[ErrorMapping[?]]
    ) extends McpHandler[In, Out, E]:
        def method: String      = customRoute.method
        def route: McpRoute[In] = customRoute

        def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): McpHandler[In, Out, E | E2] =
            new CustomHandler[In, Out, E | E2](
                customRoute,
                outSchema,
                customHandler,
                errorMappings.append(new ErrorMapping[E2](code, message))
            )
    end CustomHandler

end McpHandler
