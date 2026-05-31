package kyo.internal.mcp

import kyo.*

/** Eight built-in `JsonRpcRoute` instances that aggregate user-registered routes from `McpCatalog`.
  *
  * Pagination uses a cursor-as-decimal-offset scheme over the frozen catalog snapshot.
  * The cursor string is the decimal representation of the start index (e.g. "100", "200").
  * Page size is 100 items per page. Because the catalog is immutable, offset-based cursors
  * are stable for the lifetime of the server instance.
  *
  * Decision 3: cursor-as-decimal-offset pagination.
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
    final private case class ToolCallParams(name: String, arguments: Structure.Value = Structure.Value.Record(Chunk.empty))
        derives Schema
    final private case class ToolCallResponse(
        content: Chunk[McpContent],
        isError: Boolean = false,
        // flow-allow: Structure carve-out per §11a / INV-021
        structuredContent: Maybe[Structure.Value] = Absent
    ) derives Schema

    // Wire shapes for resources/read dispatcher.
    final private case class ResourceReadParams(uri: String) derives Schema
    final private case class ResourceReadResponse(contents: Chunk[McpResourceContents]) derives Schema

    // Wire shapes for prompts/get dispatcher.
    final private case class PromptGetParams(name: String, arguments: Map[String, String] = Map.empty) derives Schema

    final private case class CompleteParams(ref: McpRoute.CompletionRef, argument: McpRoute.CompletionArg) derives Schema
    final private case class CompleteResult(completion: McpRoute.CompletionResult) derives Schema

    // Wire shape for logging/setLevel.
    final private case class SetLogLevelParams(level: String) derives Schema
    final private case class SetLogLevelResult() derives Schema

    def toolsList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, ToolsListResult]("tools/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.toolRoutes.map(r => catalog.toolMetaOf(r)))
            val (page, next) = paginate(allMetas, params.cursor)
            ToolsListResult(page, next)
        }

    def toolsCall(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ToolCallParams, McpRoute.ToolCallResult]("tools/call") { (params, jrCtx) =>
            val matched = catalog.toolRoutes.collectFirst {
                case c: McpRouteCarrier.Tool[?, ?] if c.name == params.name   => c.asInstanceOf[McpRouteCarrier.Tool[Any, McpContent]]
                case c: McpRouteCarrier.ToolMulti[?] if c.name == params.name => null
            }
            val matchedMulti = catalog.toolRoutes.collectFirst {
                case c: McpRouteCarrier.ToolMulti[?] if c.name == params.name => c.asInstanceOf[McpRouteCarrier.ToolMulti[Any]]
            }
            val registeredNames = Chunk.from(catalog.toolRoutes.map(_.name))
            (matched, matchedMulti) match
                case (_, Some(carrier)) =>
                    // AllowUnsafe: synchronous read of forward server reference (Decision 2).
                    val server = carrier.serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                        throw new IllegalStateException(s"McpServer not initialized for toolMulti '${params.name}'")
                    )
                    val ctx  = new McpRoute.Context(jrCtx, server.safe)
                    val args = Structure.decode[Any](params.arguments)(using carrier.inSchema.asInstanceOf[Schema[Any]], summon[Frame])
                    args match
                        case Result.Success(in) => carrier.handler.asInstanceOf[(
                                Any,
                                McpRoute.Context
                            ) => McpRoute.ToolCallResult < (Async & Abort[McpError | JsonRpcResponse.Halt])](in, ctx)
                        case Result.Failure(e) => Abort.fail(McpInvalidArgumentError("tools/call", "arguments", e.getMessage))
                        case Result.Panic(t)   => Abort.panic(t)
                    end match
                case (Some(carrier), _) =>
                    // AllowUnsafe: synchronous read of forward server reference (Decision 2).
                    val server = carrier.serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                        throw new IllegalStateException(s"McpServer not initialized for tool '${params.name}'")
                    )
                    val ctx  = new McpRoute.Context(jrCtx, server.safe)
                    val args = Structure.decode[Any](params.arguments)(using carrier.inSchema.asInstanceOf[Schema[Any]], summon[Frame])
                    args match
                        case Result.Success(in) =>
                            carrier.handler.asInstanceOf[(
                                Any,
                                McpRoute.Context
                            ) => McpContent < (Async & Abort[McpError | JsonRpcResponse.Halt])](in, ctx)
                                .map(out => McpRoute.ToolCallResult(Chunk(out), isError = false, structuredContent = Absent))
                        case Result.Failure(e) => Abort.fail(McpInvalidArgumentError("tools/call", "arguments", e.getMessage))
                        case Result.Panic(t)   => Abort.panic(t)
                    end match
                case (None, None) =>
                    Abort.fail(McpUnknownToolError(params.name, registeredNames))
            end match
        }

    def resourcesList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, ResourcesListResult]("resources/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.resourceRoutes.map(r => catalog.resourceMetaOf(r)))
            val (page, next) = paginate(allMetas, params.cursor)
            ResourcesListResult(page, next)
        }

    def resourcesRead(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ResourceReadParams, ResourceReadResponse]("resources/read") { (params, jrCtx) =>
            val parsedUri = McpResourceUri.parse(params.uri)
            parsedUri match
                case Absent =>
                    Abort.fail(McpInvalidArgumentError("resources/read", "uri", s"invalid URI: ${params.uri}"))
                case Present(uri) =>
                    val matched = catalog.resourceRoutes.collectFirst {
                        case c: McpRouteCarrier.Resource[?] if c.resourceMeta.uri == uri => c
                    }
                    matched match
                        case Some(carrier) =>
                            // AllowUnsafe: synchronous read of forward server reference (Decision 2).
                            val server = carrier.serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                                throw new IllegalStateException(s"McpServer not initialized for resource '${params.uri}'")
                            )
                            val ctx = new McpRoute.Context(jrCtx, server.safe)
                            carrier.handler(uri, ctx).map(contents => ResourceReadResponse(contents))
                        case None =>
                            val registeredUris = Chunk.from(catalog.resourceRoutes.map(r => catalog.resourceMetaOf(r).uri))
                            Abort.fail(McpUnknownResourceError(uri, registeredUris))
                    end match
            end match
        }

    def resourceTemplatesList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, ResourceTemplatesListResult]("resources/templates/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.resourceTemplateRoutes.map(r => catalog.resourceTemplateMetaOf(r)))
            val (page, next) = paginate(allMetas, params.cursor)
            ResourceTemplatesListResult(page, next)
        }

    def promptsList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, PromptsListResult]("prompts/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.promptRoutes.map(r => catalog.promptMetaOf(r)))
            val (page, next) = paginate(allMetas, params.cursor)
            PromptsListResult(page, next)
        }

    def promptsGet(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[PromptGetParams, McpRoute.PromptGetResult]("prompts/get") { (params, jrCtx) =>
            val matched = catalog.promptRoutes.collectFirst {
                case c: McpRouteCarrier.Prompt[?] if c.name == params.name => c
            }
            matched match
                case Some(carrier) =>
                    // AllowUnsafe: synchronous read of forward server reference (Decision 2).
                    val server = carrier.serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                        throw new IllegalStateException(s"McpServer not initialized for prompt '${params.name}'")
                    )
                    val ctx = new McpRoute.Context(jrCtx, server.safe)
                    carrier.handler(params.arguments, ctx)
                case None =>
                    val registeredNames = Chunk.from(catalog.promptRoutes.map(_.name))
                    Abort.fail(McpUnknownPromptError(params.name, registeredNames))
            end match
        }

    def loggingSetLevel(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[SetLogLevelParams, SetLogLevelResult]("logging/setLevel") { (_, _) =>
            // Accept the setLevel request; log level enforcement is application-level.
            SetLogLevelResult()
        }

    def completionComplete(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[CompleteParams, CompleteResult]("completion/complete") { (params, jrCtx) =>
            // Look up a registered completion route matching params.ref; fall back to empty (non-fatal per spec).
            val matched = catalog.completionRoutes.collectFirst {
                case c: McpRouteCarrier.Completion if c.ref == params.ref => c
            }
            matched match
                case Some(carrier) =>
                    // AllowUnsafe: synchronous read of forward server reference (Decision 2).
                    val server = carrier.serverRef.unsafe.get()(using AllowUnsafe.embrace.danger).getOrElse(
                        throw new IllegalStateException(s"McpServer not initialized for completion route '${carrier.name}'")
                    )
                    val ctx = new McpRoute.Context(jrCtx, server.safe)
                    carrier.handler(params.ref, params.argument, ctx).map(r => CompleteResult(r))
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
