package kyo.internal.mcp

import kyo.*

/** Eight built-in `JsonRpcRoute` instances that aggregate user-registered handlers from `McpCatalog`.
  *
  * Pagination uses a cursor-as-decimal-offset scheme over the frozen catalog snapshot.
  * The cursor string is the decimal representation of the start index (e.g. "100", "200").
  * Page size is 100 items per page. Because the catalog is immutable, offset-based cursors
  * are stable for the lifetime of the server instance.
  *
  * Decision 3: cursor-as-decimal-offset pagination.
  *
  * Dispatchers wrap each invocation of a user handler closure in `Mcp.local.let(Present(ctx))`
  * so the handler can reach the per-request context via `Mcp.*` accessors.
  *
  * Built-in routes provided:
  *   - `tools/list`               list registered tools with pagination
  *   - `tools/call`               dispatch to a named tool handler
  *   - `resources/list`           list registered resources
  *   - `resources/read`           dispatch to a named resource handler
  *   - `resources/templates/list` list registered resource templates
  *   - `prompts/list`             list registered prompts
  *   - `prompts/get`              dispatch to a named prompt handler
  *   - `completion/complete`      dispatch to a named completion handler
  */
private[kyo] object McpBuiltInRoutes:

    private val PageSize = 100

    // Wire shapes for list request/response payloads.
    final private case class ListParams(cursor: Maybe[String] = Absent) derives Schema
    final private case class ToolsListResult(tools: Chunk[McpRoute.ToolMeta], nextCursor: Maybe[String] = Absent) derives Schema
    final private case class ResourcesListResult(resources: Chunk[McpRoute.ResourceMeta], nextCursor: Maybe[String] = Absent) derives Schema
    final private case class ResourceTemplatesListResult(
        resourceTemplates: Chunk[McpRoute.ResourceTemplateMeta],
        nextCursor: Maybe[String] = Absent
    ) derives Schema
    final private case class PromptsListResult(prompts: Chunk[McpRoute.PromptMeta], nextCursor: Maybe[String] = Absent) derives Schema

    // Wire shapes for tools/call dispatcher.
    // flow-allow: Structure carve-out per §11a / INV-021
    final private case class ToolCallParams(
        name: String,
        arguments: Structure.Value = Structure.Value.Record(Chunk.empty),
        // flow-allow: Structure carve-out per §11a / INV-021
        meta: Maybe[Structure.Value] = Absent
    ) derives Schema
    final private case class ToolCallResponse(
        content: Chunk[McpContent],
        isError: Boolean = false,
        // flow-allow: Structure carve-out per §11a / INV-021
        structuredContent: Maybe[Structure.Value] = Absent
    ) derives Schema

    // Wire shapes for resources/read dispatcher.
    final private case class ResourceReadParams(uri: String) derives Schema
    final private case class ResourceReadResponse(contents: Chunk[McpRoute.ResourceContents]) derives Schema

    // Wire shapes for prompts/get dispatcher.
    final private case class PromptGetParams(name: String, arguments: Map[String, String] = Map.empty) derives Schema

    final private case class CompleteParams(
        ref: McpRoute.CompletionRef,
        argument: McpRoute.CompletionArg,
        context: Maybe[McpRoute.CompletionArg.Context] = Absent
    ) derives Schema
    final private case class CompleteResult(completion: McpRoute.CompletionResult) derives Schema

    // Wire shape for logging/setLevel.
    final private case class SetLogLevelParams(level: McpServer.LogLevel) derives Schema
    final private case class SetLogLevelResult() derives Schema

    // Wire shape for resources/subscribe and resources/unsubscribe.
    final private case class ResourceSubscribeParams(uri: String) derives Schema

    // Reads the live server from the forward reference for binding into Mcp.local.
    private def withCtx[A, S](
        jrCtx: JsonRpcRoute.Context,
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]],
        method: String
    )(body: => A < S)(using Frame): A < S =
        // AllowUnsafe: synchronous read of forward server reference (Decision 2 carryover).
        serverRef.unsafe.get()(using AllowUnsafe.embrace.danger) match
            case Present(srv) =>
                Mcp.local.let(Present(Mcp.RequestContext(jrCtx, srv.safe)))(body)
            case Absent =>
                throw new IllegalStateException(s"McpServer not initialised for '$method'")
    end withCtx

    def toolsList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, ToolsListResult]("tools/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.toolHandlers.map(h => catalog.toolMetaOf(h)))
            val (page, next) = paginate(allMetas, params.cursor)
            ToolsListResult(page, next)
        }

    def toolsCall(catalog: McpCatalog, serverRef: AtomicRef[Maybe[McpServer.Unsafe]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ToolCallParams, McpRoute.ToolCallResult]("tools/call") { (params, jrCtx) =>
            val matchedTool = catalog.toolHandlers.collectFirst {
                case c: McpHandler.ToolHandler[?, ?, ?] if c.name == params.name =>
                    c.asInstanceOf[McpHandler.ToolHandler[Any, McpContent, McpException]]
            }
            val matchedMulti = catalog.toolHandlers.collectFirst {
                case c: McpHandler.ToolMultiHandler[?, ?] if c.name == params.name =>
                    c.asInstanceOf[McpHandler.ToolMultiHandler[Any, McpException]]
            }
            val registeredNames = Chunk.from(catalog.toolHandlers.map(_.name))
            (matchedTool, matchedMulti) match
                case (_, Some(carrier)) =>
                    val args =
                        Structure.decode[Any](params.arguments)(using carrier.toolRoute.inSchema.asInstanceOf[Schema[Any]], summon[Frame])
                    args match
                        case Result.Success(in) =>
                            withCtx(jrCtx, serverRef, "tools/call")(carrier.toolHandler(in))
                        case Result.Failure(e) => Abort.fail(McpInvalidArgumentException("tools/call", "arguments", e.getMessage))
                        case Result.Panic(t)   => Abort.panic(t)
                    end match
                case (Some(carrier), _) =>
                    val args =
                        Structure.decode[Any](params.arguments)(using carrier.toolRoute.inSchema.asInstanceOf[Schema[Any]], summon[Frame])
                    args match
                        case Result.Success(in) =>
                            withCtx(jrCtx, serverRef, "tools/call") {
                                carrier.toolHandler(in)
                                    .map(out => McpRoute.ToolCallResult(Chunk(out), isError = false, structuredContent = Absent))
                            }
                        case Result.Failure(e) => Abort.fail(McpInvalidArgumentException("tools/call", "arguments", e.getMessage))
                        case Result.Panic(t)   => Abort.panic(t)
                    end match
                case (None, None) =>
                    Abort.fail(McpUnknownToolException(params.name, registeredNames))
            end match
        }

    def resourcesList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, ResourcesListResult]("resources/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.resourceHandlers.map(h => catalog.resourceMetaOf(h)))
            val (page, next) = paginate(allMetas, params.cursor)
            ResourcesListResult(page, next)
        }

    def resourcesRead(catalog: McpCatalog, serverRef: AtomicRef[Maybe[McpServer.Unsafe]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ResourceReadParams, ResourceReadResponse]("resources/read") { (params, jrCtx) =>
            val parsedUri = McpResourceUri.parse(params.uri)
            parsedUri match
                case Absent =>
                    Abort.fail(McpInvalidArgumentException("resources/read", "uri", s"invalid URI: ${params.uri}"))
                case Present(uri) =>
                    val matched = catalog.resourceHandlers.collectFirst {
                        case c: McpHandler.ResourceHandler[?] if c.resourceMeta.uri == uri => c
                    }
                    matched match
                        case Some(carrier) =>
                            withCtx(jrCtx, serverRef, "resources/read") {
                                carrier.resourceHandler(uri).map(contents => ResourceReadResponse(contents))
                            }
                        case None =>
                            val registeredUris = Chunk.from(catalog.resourceHandlers.map(h => catalog.resourceMetaOf(h).uri))
                            Abort.fail(McpUnknownResourceException(uri, registeredUris))
                    end match
            end match
        }

    def resourceTemplatesList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, ResourceTemplatesListResult]("resources/templates/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.resourceTemplateHandlers.map(h => catalog.resourceTemplateMetaOf(h)))
            val (page, next) = paginate(allMetas, params.cursor)
            ResourceTemplatesListResult(page, next)
        }

    def promptsList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, PromptsListResult]("prompts/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.promptHandlers.map(h => catalog.promptMetaOf(h)))
            val (page, next) = paginate(allMetas, params.cursor)
            PromptsListResult(page, next)
        }

    def promptsGet(catalog: McpCatalog, serverRef: AtomicRef[Maybe[McpServer.Unsafe]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[PromptGetParams, McpRoute.PromptGetResult]("prompts/get") { (params, jrCtx) =>
            val matched = catalog.promptHandlers.collectFirst {
                case c: McpHandler.PromptHandler[?] if c.name == params.name => c
            }
            matched match
                case Some(carrier) =>
                    withCtx(jrCtx, serverRef, "prompts/get")(carrier.promptHandler(params.arguments))
                case None =>
                    val registeredNames = Chunk.from(catalog.promptHandlers.map(_.name))
                    Abort.fail(McpUnknownPromptException(params.name, registeredNames))
            end match
        }

    def loggingSetLevel(logLevelRef: AtomicRef[McpServer.LogLevel])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[SetLogLevelParams, SetLogLevelResult]("logging/setLevel") { (params, _) =>
            logLevelRef.set(params.level).andThen(SetLogLevelResult())
        }

    def resourcesSubscribe(subs: AtomicRef[Set[McpResourceUri]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ResourceSubscribeParams, SetLogLevelResult]("resources/subscribe") { (p, _) =>
            McpResourceUri.parse(p.uri) match
                case Absent =>
                    Abort.fail(McpInvalidArgumentException("resources/subscribe", "uri", s"invalid URI: ${p.uri}"))
                case Present(uri) =>
                    subs.getAndUpdate(_ + uri).andThen(SetLogLevelResult())
        }

    def resourcesUnsubscribe(subs: AtomicRef[Set[McpResourceUri]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ResourceSubscribeParams, SetLogLevelResult]("resources/unsubscribe") { (p, _) =>
            McpResourceUri.parse(p.uri) match
                case Absent =>
                    Abort.fail(McpInvalidArgumentException("resources/unsubscribe", "uri", s"invalid URI: ${p.uri}"))
                case Present(uri) =>
                    subs.getAndUpdate(_ - uri).andThen(SetLogLevelResult())
        }

    def completionComplete(catalog: McpCatalog, serverRef: AtomicRef[Maybe[McpServer.Unsafe]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[CompleteParams, CompleteResult]("completion/complete") { (params, jrCtx) =>
            // Look up a registered completion handler matching params.ref; fall back to empty (non-fatal per spec).
            val matched = catalog.completionHandlers.collectFirst {
                case c: McpHandler.CompletionHandler[?] if c.ref == params.ref => c
            }
            matched match
                case Some(carrier) =>
                    withCtx(jrCtx, serverRef, "completion/complete") {
                        carrier.completionHandler(params.ref, params.argument, params.context).map(r => CompleteResult(r))
                    }
                case None =>
                    // No handler registered for this ref; return empty completion.
                    CompleteResult(McpRoute.CompletionResult(Chunk.empty, Absent, Absent))
            end match
        }

    // Cursor-as-decimal-offset pagination: returns (page, nextCursor).
    private def paginate[A](items: Chunk[A], cursor: Maybe[String]): (Chunk[A], Maybe[String]) =
        val offset  = cursor.fold(0)(s => s.toIntOption.getOrElse(0))
        val page    = items.drop(offset).take(PageSize)
        val nextOff = offset + PageSize
        val next    = if nextOff < items.size then Present(nextOff.toString) else Absent
        (page, next)
    end paginate

end McpBuiltInRoutes
