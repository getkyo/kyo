package kyo.internal.mcp

import kyo.*
import kyo.McpRouteCarrier

/** Frozen snapshot of user-registered routes built once at `McpServer.init` time.
  *
  * Partitions routes by `McpRoute.Kind` for engine dispatch and for the built-in
  * list endpoints. No mutation is possible after construction (INV-018).
  *
  * Auto-derives `McpCapabilities.Server` when `McpConfig.declaredCapabilities` is
  * `Absent`, using registered route counts and `config.autoNotifyListChanged` (INV-019).
  */
final private[kyo] class McpCatalog(val routes: Seq[McpRoute[?, ?, ?]]):

    // Partition at construction time; allocation is bounded to init, not per-request.
    val toolRoutes: Seq[McpRoute[?, ?, ?]] =
        routes.filter(_.kind == McpRoute.Kind.Tool)

    val resourceRoutes: Seq[McpRoute[?, ?, ?]] =
        routes.filter(_.kind == McpRoute.Kind.Resource)

    val resourceTemplateRoutes: Seq[McpRoute[?, ?, ?]] =
        routes.filter(_.kind == McpRoute.Kind.ResourceTemplate)

    val promptRoutes: Seq[McpRoute[?, ?, ?]] =
        routes.filter(_.kind == McpRoute.Kind.Prompt)

    // Completion routes use Kind.Custom per Decision 13.
    val completionRoutes: Seq[McpRoute[?, ?, ?]] =
        routes.filter(_.kind == McpRoute.Kind.Custom)

    /** Extracts `ToolMeta` from a tool route carrier. */
    def toolMetaOf(r: McpRoute[?, ?, ?]): McpRoute.ToolMeta =
        r match
            case c: McpRouteCarrier.Tool[?, ?] => c.toolMeta
            case _                             => throw new IllegalStateException(s"expected Tool carrier for route '${r.name}'")

    /** Extracts `ResourceMeta` from a resource route carrier. */
    def resourceMetaOf(r: McpRoute[?, ?, ?]): McpRoute.ResourceMeta =
        r match
            case c: McpRouteCarrier.Resource[?] => c.resourceMeta
            case _                              => throw new IllegalStateException(s"expected Resource carrier for route '${r.name}'")

    /** Extracts `ResourceTemplateMeta` from a resource template route carrier. */
    def resourceTemplateMetaOf(r: McpRoute[?, ?, ?]): McpRoute.ResourceTemplateMeta =
        r match
            case c: McpRouteCarrier.ResourceTemplate[?] => c.resourceTemplateMeta
            case _ => throw new IllegalStateException(s"expected ResourceTemplate carrier for route '${r.name}'")

    /** Extracts `PromptMeta` from a prompt route carrier. */
    def promptMetaOf(r: McpRoute[?, ?, ?]): McpRoute.PromptMeta =
        r match
            case c: McpRouteCarrier.Prompt[?] => c.promptMeta
            case _                            => throw new IllegalStateException(s"expected Prompt carrier for route '${r.name}'")

    /** Auto-derives `McpCapabilities.Server` from registered routes and `config`.
      *
      * When `config.declaredCapabilities` is `Present(c)`, returns `c` verbatim (INV-019).
      * Otherwise derives from registered route kinds and `config.autoNotifyListChanged`.
      */
    def autoDeriveServerCapabilities(config: McpConfig): McpCapabilities.Server =
        config.declaredCapabilities match
            case Present(c) => c
            case Absent =>
                McpCapabilities.Server(
                    tools =
                        if toolRoutes.nonEmpty then
                            Present(McpCapabilities.ToolsCapability(listChanged = config.autoNotifyListChanged))
                        else Absent,
                    resources =
                        if resourceRoutes.nonEmpty || resourceTemplateRoutes.nonEmpty then
                            Present(
                                McpCapabilities.ResourcesCapability(
                                    subscribe = false,
                                    listChanged = config.autoNotifyListChanged
                                )
                            )
                        else Absent,
                    prompts =
                        if promptRoutes.nonEmpty then
                            Present(McpCapabilities.PromptsCapability(listChanged = config.autoNotifyListChanged))
                        else Absent,
                    completions =
                        if completionRoutes.nonEmpty then
                            Present(McpCapabilities.CompletionsCapability())
                        else Absent,
                    logging = Absent,
                    experimental = Map.empty
                )

end McpCatalog
