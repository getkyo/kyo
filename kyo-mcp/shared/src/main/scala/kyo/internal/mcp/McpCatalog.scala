package kyo.internal.mcp

import kyo.*

/** Frozen snapshot of user-registered handlers built once at `McpServer.init` time.
  *
  * Partitions handlers by `McpHandler.Kind` for engine dispatch and for the built-in
  * list endpoints. No mutation is possible after construction.
  *
  * Auto-derives `McpCapabilities.Server` when `McpConfig.declaredCapabilities` is
  * `Absent`, using registered handler counts and `config.autoNotifyListChanged`.
  */
final private[kyo] class McpCatalog(val handlers: Seq[McpHandler[?, ?, ?]]):

    // Partition at construction time; allocation is bounded to init, not per-request.
    val toolHandlers: Seq[McpHandler[?, ?, ?]] =
        handlers.filter(_.kind == McpHandler.Kind.Tool)

    val resourceHandlers: Seq[McpHandler[?, ?, ?]] =
        handlers.filter(_.kind == McpHandler.Kind.Resource)

    val resourceTemplateHandlers: Seq[McpHandler[?, ?, ?]] =
        handlers.filter(_.kind == McpHandler.Kind.ResourceTemplate)

    val promptHandlers: Seq[McpHandler[?, ?, ?]] =
        handlers.filter(_.kind == McpHandler.Kind.Prompt)

    // Completion handlers are carried as Kind.Custom; collect them by type.
    val completionHandlers: Seq[McpHandler[?, ?, ?]] =
        handlers.collect { case h: McpHandler.CompletionHandler[?] => h }

    /** Extracts `ToolMeta` from a tool handler. */
    def toolMetaOf(h: McpHandler[?, ?, ?]): McpHandler.ToolMeta =
        h match
            case c: McpHandler.ToolHandler[?, ?, ?]   => c.toolMeta
            case c: McpHandler.ToolMultiHandler[?, ?] => c.toolMeta
            case _                                    => throw new IllegalStateException(s"expected Tool handler for route '${h.name}'")

    /** Extracts `ResourceMeta` from a resource handler. */
    def resourceMetaOf(h: McpHandler[?, ?, ?]): McpHandler.ResourceMeta =
        h match
            case c: McpHandler.ResourceHandler[?] => c.resourceMeta
            case _                                => throw new IllegalStateException(s"expected Resource handler for route '${h.name}'")

    /** Extracts `ResourceTemplateMeta` from a resource template handler. */
    def resourceTemplateMetaOf(h: McpHandler[?, ?, ?]): McpHandler.ResourceTemplateMeta =
        h match
            case c: McpHandler.ResourceTemplateHandler[?] => c.resourceTemplateMeta
            case _ => throw new IllegalStateException(s"expected ResourceTemplate handler for route '${h.name}'")

    /** Extracts `PromptMeta` from a prompt handler. */
    def promptMetaOf(h: McpHandler[?, ?, ?]): McpHandler.PromptMeta =
        h match
            case c: McpHandler.PromptHandler[?]         => c.promptMeta
            case c: McpHandler.TypedPromptHandler[?, ?] => c.promptMeta
            case _                                      => throw new IllegalStateException(s"expected Prompt handler for route '${h.name}'")

    /** Auto-derives `McpCapabilities.Server` from registered handlers and `config`.
      *
      * When `config.declaredCapabilities` is `Present(c)`, returns `c` verbatim.
      * Otherwise derives from registered handler kinds and `config.autoNotifyListChanged`.
      */
    def autoDeriveServerCapabilities(config: McpConfig): McpCapabilities.Server =
        config.declaredCapabilities match
            case Present(c) => c
            case Absent =>
                McpCapabilities.Server(
                    tools =
                        if toolHandlers.nonEmpty then
                            Present(McpCapabilities.ToolsCapability(listChanged = config.autoNotifyListChanged))
                        else Absent,
                    resources =
                        if resourceHandlers.nonEmpty || resourceTemplateHandlers.nonEmpty then
                            Present(
                                McpCapabilities.ResourcesCapability(
                                    subscribe = resourceHandlers.exists {
                                        case c: McpHandler.ResourceHandler[?] => c.subscribable
                                        case _                                => false
                                    },
                                    listChanged = config.autoNotifyListChanged
                                )
                            )
                        else Absent,
                    prompts =
                        if promptHandlers.nonEmpty then
                            Present(McpCapabilities.PromptsCapability(listChanged = config.autoNotifyListChanged))
                        else Absent,
                    completions =
                        if completionHandlers.nonEmpty then
                            Present(McpCapabilities.CompletionsCapability())
                        else Absent,
                    logging = if handlers.exists(_.isInstanceOf[McpHandler.LoggingHook]) then
                        Present(McpCapabilities.LoggingCapability())
                    else Absent,
                    experimental = Map.empty
                )

end McpCatalog
