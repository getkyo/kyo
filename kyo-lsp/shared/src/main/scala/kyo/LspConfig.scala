package kyo

/** Configuration for an LSP server or client.
  *
  * `LspConfig.default` is the recommended starting point; override individual fields via the
  * fluent setter methods. Call `LspConfig.require(config)` before passing to `LspServer.init`
  * or `LspClient.init` to validate cross-field constraints.
  *
  * The `jsonRpc` field is populated by the engine at init time with LSP-specific policy adapters
  * for cancellation, progress, and unknown-method handling. `positionEncodings` configures the
  * server's accepted encoding set; the engine intersects with the client's advertised set at
  * handshake time and defaults to UTF-16 per LSP 3.17.
  *
  * @param serverInfo                   server identity advertised in the initialize response
  * @param positionEncodings            server-preferred position encoding list (default UTF-16)
  * @param documentSync                 sync kind offered to clients (default Incremental)
  * @param declaredServerCapabilities   explicit capability tree; Absent triggers auto-derivation
  * @param enforceCapabilities          reject unadvertised capability calls (default true)
  * @param onTypeFormattingTriggers     required when onTypeFormatting handler is registered
  * @param semanticTokensLegend         required when any semanticTokens factory is registered
  * @param executeCommandCommands       required when executeCommand handler is registered
  * @param completionTriggerCharacters  optional trigger characters for completion
  * @param signatureHelpTriggerCharacters optional trigger characters for signature help
  * @param jsonRpc                      underlying JSON-RPC handler configuration
  */
final case class LspConfig(
    serverInfo: LspInfo = LspInfo(name = "kyo-lsp"),
    positionEncodings: Chunk[LspHandler.PositionEncodingKind] = Chunk(LspHandler.PositionEncodingKind.UTF16),
    documentSync: LspHandler.TextDocumentSyncKind = LspHandler.TextDocumentSyncKind.Incremental,
    declaredServerCapabilities: Maybe[LspCapabilities.Server.Server] = Absent,
    enforceCapabilities: Boolean = true,
    onTypeFormattingTriggers: Chunk[String] = Chunk.empty,
    semanticTokensLegend: Maybe[LspHandler.SemanticTokensLegend] = Absent,
    executeCommandCommands: Chunk[String] = Chunk.empty,
    completionTriggerCharacters: Chunk[String] = Chunk.empty,
    signatureHelpTriggerCharacters: Chunk[String] = Chunk.empty,
    jsonRpc: JsonRpcHandler.Config = LspConfig.defaultJsonRpcConfig
) derives CanEqual:
    def withServerInfo(i: LspInfo): LspConfig                                       = copy(serverInfo = i)
    def withPositionEncodings(e: Chunk[LspHandler.PositionEncodingKind]): LspConfig = copy(positionEncodings = e)
    def withDocumentSync(k: LspHandler.TextDocumentSyncKind): LspConfig             = copy(documentSync = k)
    def withDeclaredServerCapabilities(c: LspCapabilities.Server.Server): LspConfig = copy(declaredServerCapabilities = Present(c))
    def withEnforceCapabilities(b: Boolean): LspConfig                              = copy(enforceCapabilities = b)
    def withOnTypeFormattingTriggers(ts: Chunk[String]): LspConfig                  = copy(onTypeFormattingTriggers = ts)
    def withSemanticTokensLegend(l: LspHandler.SemanticTokensLegend): LspConfig     = copy(semanticTokensLegend = Present(l))
    def withExecuteCommandCommands(cmds: Chunk[String]): LspConfig                  = copy(executeCommandCommands = cmds)
    def withCompletionTriggerCharacters(cs: Chunk[String]): LspConfig               = copy(completionTriggerCharacters = cs)
    def withSignatureHelpTriggerCharacters(cs: Chunk[String]): LspConfig            = copy(signatureHelpTriggerCharacters = cs)
    def withJsonRpc(c: JsonRpcHandler.Config): LspConfig                            = copy(jsonRpc = c)

    def experimentalServerCapabilities[X](x: X)(using Schema[X]): LspConfig = ??? // Phase 03

end LspConfig

object LspConfig:

    /** The LSP specification version implemented by this library. */
    val SpecVersion: String = "3.17"

    /** JSON-RPC config populated at init; stub in Phase 01, filled by Phase 03. */
    val defaultJsonRpcConfig: JsonRpcHandler.Config = JsonRpcHandler.Config.default // Phase 03 replaces

    /** Default configuration using auto-derived capabilities. */
    val default: LspConfig = LspConfig()

    /** Validates cross-field constraints. Stub in Phase 01; filled by Phase 03. */
    def require(c: LspConfig): Unit = () // Phase 03 fills real body

end LspConfig
