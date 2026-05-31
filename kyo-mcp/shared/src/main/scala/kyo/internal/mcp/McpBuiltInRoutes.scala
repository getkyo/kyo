package kyo.internal.mcp

import kyo.*

/** Five built-in `JsonRpcRoute` instances that aggregate user-registered routes from `McpCatalog`.
  *
  * Pagination uses a cursor-as-decimal-offset scheme over the frozen catalog snapshot.
  * The cursor string is the decimal representation of the start index (e.g. "100", "200").
  * Page size is 100 items per page. Because the catalog is immutable, offset-based cursors
  * are stable for the lifetime of the server instance.
  *
  * Decision 3: cursor-as-decimal-offset pagination.
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

    final private case class CompleteParams(ref: McpRoute.CompletionRef, argument: McpRoute.CompletionArg) derives Schema
    final private case class CompleteResult(completion: McpRoute.CompletionResult) derives Schema

    def toolsList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, ToolsListResult]("tools/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.toolRoutes.map(r => catalog.toolMetaOf(r)))
            val (page, next) = paginate(allMetas, params.cursor)
            ToolsListResult(page, next)
        }

    def resourcesList(catalog: McpCatalog)(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[ListParams, ResourcesListResult]("resources/list") { (params, _) =>
            val allMetas     = Chunk.from(catalog.resourceRoutes.map(r => catalog.resourceMetaOf(r)))
            val (page, next) = paginate(allMetas, params.cursor)
            ResourcesListResult(page, next)
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
