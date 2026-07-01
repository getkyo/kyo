package kyo

/** Configuration for an LSP server or client.
  *
  * `LspConfig.default` is the recommended starting point; override individual fields via the
  * fluent setter methods. Call `LspConfig.require(config)` before passing to `LspServer.init`
  * or `LspClient.init` to validate cross-field constraints.
  *
  * The `jsonRpc` field is pre-populated by `LspConfig.defaultJsonRpcConfig`. The engine
  * installs LSP-specific policy adapters (cancellation, progress, unknown-method) at init time
  * by extending the config. `positionEncodings` configures the server's accepted encoding set;
  * the engine intersects with the client's advertised set at handshake time and defaults to
  * UTF-16 per LSP 3.17.
  *
  * @param serverInfo                     server identity advertised in the initialize response
  * @param positionEncodings              server-preferred position encoding list (default UTF-16)
  * @param documentSync                   sync kind offered to clients (default Incremental)
  * @param declaredServerCapabilities     explicit capability tree; Absent triggers auto-derivation
  * @param enforceCapabilities            reject unadvertised capability calls (default true)
  * @param onTypeFormattingTriggers       required when onTypeFormatting handler is registered
  * @param semanticTokensLegend           required when any semanticTokens factory is registered
  * @param executeCommandCommands         required when executeCommand handler is registered
  * @param completionTriggerCharacters    optional trigger characters for completion
  * @param signatureHelpTriggerCharacters optional trigger characters for signature help
  * @param jsonRpc                        underlying JSON-RPC handler configuration
  */
final case class LspConfig(
    serverInfo: LspInfo = LspInfo(name = "kyo-lsp"),
    positionEncodings: Chunk[LspHandler.PositionEncodingKind] = Chunk(LspHandler.PositionEncodingKind.UTF16),
    documentSync: LspHandler.TextDocumentSyncKind = LspHandler.TextDocumentSyncKind.Incremental,
    declaredServerCapabilities: Maybe[LspCapabilities.Server] = Absent,
    enforceCapabilities: Boolean = true,
    onTypeFormattingTriggers: Chunk[String] = Chunk.empty,
    semanticTokensLegend: Maybe[LspHandler.SemanticTokensLegend] = Absent,
    executeCommandCommands: Chunk[String] = Chunk.empty,
    completionTriggerCharacters: Chunk[String] = Chunk.empty,
    signatureHelpTriggerCharacters: Chunk[String] = Chunk.empty,
    jsonRpc: JsonRpcHandler.Config = LspConfig.defaultJsonRpcConfig,
    private[kyo] _rawExperimental: Maybe[String] = Absent
) derives CanEqual:
    def withServerInfo(i: LspInfo): LspConfig                                       = copy(serverInfo = i)
    def withPositionEncodings(e: Chunk[LspHandler.PositionEncodingKind]): LspConfig = copy(positionEncodings = e)
    def withDocumentSync(k: LspHandler.TextDocumentSyncKind): LspConfig             = copy(documentSync = k)
    def withDeclaredServerCapabilities(c: LspCapabilities.Server): LspConfig        = copy(declaredServerCapabilities = Present(c))
    def withEnforceCapabilities(b: Boolean): LspConfig                              = copy(enforceCapabilities = b)
    def withOnTypeFormattingTriggers(ts: Chunk[String]): LspConfig                  = copy(onTypeFormattingTriggers = ts)
    def withSemanticTokensLegend(l: LspHandler.SemanticTokensLegend): LspConfig     = copy(semanticTokensLegend = Present(l))
    def withExecuteCommandCommands(cmds: Chunk[String]): LspConfig                  = copy(executeCommandCommands = cmds)
    def withCompletionTriggerCharacters(cs: Chunk[String]): LspConfig               = copy(completionTriggerCharacters = cs)
    def withSignatureHelpTriggerCharacters(cs: Chunk[String]): LspConfig            = copy(signatureHelpTriggerCharacters = cs)
    def withJsonRpc(c: JsonRpcHandler.Config): LspConfig                            = copy(jsonRpc = c)

    /** Sets the experimental server capabilities slot using a typed value encoded to JSON.
      *
      * The value is encoded via `Json.encode[X]` into a wire-level JSON string stored in
      * `_rawExperimental`. The engine reads it back via `Lsp.serverExperimentalCapabilities[X]`
      * which decodes with `Json.decode[X]`.
      */
    def experimentalServerCapabilities[X](x: X)(using Schema[X], Frame): LspConfig =
        copy(_rawExperimental = Present(Json.encode(x)))

end LspConfig

object LspConfig:

    /** The LSP specification version implemented by this library. */
    val SpecVersion: String = "3.17"

    /** JSON-RPC handler config used by `default`.
      *
      * The three LSP-specific policy slots (cancellation, progress, unknownMethod) are
      * installed by the engine after this default is constructed. The gate slot is also set
      * by the engine after computing or reading the capability tree. This value provides a
      * safe baseline; overriding `jsonRpc` directly is an escape hatch.
      */
    val defaultJsonRpcConfig: JsonRpcHandler.Config = JsonRpcHandler.Config.default

    /** Default configuration using auto-derived capabilities and sensible LSP defaults.
      *
      * - positionEncodings: UTF-16 only (matches LSP 3.17 default)
      * - documentSync: Incremental
      * - enforceCapabilities: true
      * - All other optional fields: Absent / empty
      */
    val default: LspConfig = LspConfig()

    /** Validates structural constraints on `config`.
      *
      * Checks:
      *   - `positionEncodings` is non-empty; the engine must have at least one encoding to
      *     negotiate at handshake time.
      *   - Delegates to `JsonRpcHandler.Config.require` for the `jsonRpc` slot.
      *
      * Cross-field semantic validations (e.g. onTypeFormattingTriggers required when the
      * corresponding handler is registered) are performed by the engine catalog at init time,
      * not here.
      */
    def require(c: LspConfig)(using Frame): Unit =
        if c.positionEncodings.isEmpty then
            throw LspConfigurationError("positionEncodings", "must be non-empty")
        end if
        JsonRpcHandler.Config.require(c.jsonRpc)
    end require

end LspConfig
