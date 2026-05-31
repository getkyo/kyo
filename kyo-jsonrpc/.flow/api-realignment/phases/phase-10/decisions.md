# Phase 10 decisions

## Files Modified

### Source (main) — deletions & rewrites

| File | Change |
|------|--------|
| `JsonRpcMessageGate.scala` | Deleted `def requireInitialize` (step 4). `requireHandshake` already existed from prior agent. |
| `JsonRpcFramer.scala` | Rewrote scaladoc L15: "used by LSP over stdio" → "length-prefixed binary framing pattern" (step 5). |
| `JsonRpcHandler.scala` | Deleted `Config.lsp` and `Config.mcp` preset vals (28 lines deleted) (step 6). |
| `JsonRpcRoute.scala` | Rewrote scaladoc L85-86: `$.progress (LSP) / notifications/progress (MCP)` → protocol-agnostic (step 7). |
| `internal/engine/JsonRpcEndpointImpl.scala` | 7 comment/error-string sites updated (step 8): L380, L461, L617-618, L633-634, L1094-1097, L1101, L1220 |

### Engine comment updates (JsonRpcEndpointImpl.scala)

| Line | Change |
|------|--------|
| 380 | `"...ProgressPolicy.lsp / .mcp"` → `"...Present(<your JsonRpcProgressPolicy>)"` |
| 461 | same as 380 |
| 617-618 | `// LSP: server will still reply` → `// Policy requires a reply for cancelled requests` |
| 633-634 | `// MCP/no-reply` → `// Policy expects no reply for cancelled requests` |
| 1094-1097 | `// Cancel won CAS; for LSP...For MCP` → `// Cancel won the CAS. If the policy demands a reply...` |
| 1101 | `// Unsafe: SendEnvelope bypasses suppress check (LSP always replies)` → `// ...because the policy demands a reply.` |
| 1220 | `// LSP cancel was issued; reply arrived` → `// A reply-demanding cancel was issued; the reply has now arrived` |

## Test Renames + Caller-Side Case Class Renames

### JsonRpcHandlerCancellationPolicyTest.scala
- Added inline `CancelByIdParams` / `CancelWithReasonParams` case classes (replacing deleted `LspCancelParams` / `McpCancelParams`)
- Added `cancellationWithReply` / `cancellationWithoutReply` vals
- Renamed `lspConfig` → `expectReplyConfig`, `mcpConfig` → `noReplyConfig`
- Test renames: "LSP inbound cancel" → "cancellation with expectReply", "MCP inbound cancel" → "cancellation without expectReply", etc.

### JsonRpcHandlerProgressPolicyTest.scala
- Added inline `progressWithWorkDoneToken` / `progressWithMetaToken` vals
- Renamed `lspConfig` → `workDoneTokenConfig`, `mcpConfig` → `metaTokenConfig`
- Renamed `LspProgressParams` → `ProgressNotifParams`
- Test renames: "LSP" → "workDoneToken policy", "MCP monotonicity" → "enforceMonotonic=true", "LSP non-monotonic" → "enforceMonotonic=false"

### JsonRpcHandlerUnknownMethodPolicyTest.scala
- Replaced `JsonRpcUnknownMethodPolicy.lsp` with `.minimal.copy(ignoreUnknownNotification = _.startsWith("$/"))`
- Renamed `lspConfig` → `dollarSlashConfig`
- Test renames: "lsp policy: ..." → "dollar-slash ignore policy: ..."
- Renamed "gate LSP initialize pattern" → "gate initialize pattern"

### BidiTest.scala
- Replaced `JsonRpcCancellationPolicy.lsp` / `.mcp` with inline constructed vals
- Renamed `lspConfig` → `expectReplyConfig`, `mcpConfig` → `noReplyConfig`
- Replaced `JsonRpcProgressPolicy.lsp` with inline val
- Test renames: "LSP bidi cancel" → "bidi cancel with expectReply", "MCP bidi cancel" → "bidi cancel without expectReply", "LSP progress round-trip" → "progress round-trip"

### HttpStyleTest.scala
- Renamed "LSP pre-init gate" → "pre-init gate"; renamed local val `lspInitGate` → `initGate`

### MaxInFlightTest.scala
- Replaced `JsonRpcCancellationPolicy.lsp` with inline val; renamed `CancelByIdParams2` → `CancelByIdParamsB`
- Replaced `JsonRpcProgressPolicy.lsp` with inline val
- Test rename: "requestTimeout fires...with LSP policy" → "...with expectReply policy"

### JsonRpcHandlerTest.scala
- Test rename: "Config() default plus LSP-shaped timeout" → "Config() default plus timeout"

## Final Sweep Result

```
rg -ni "lsp|mcp" kyo-jsonrpc/shared/src kyo-jsonrpc/jvm/src kyo-jsonrpc-http/src --type scala
```
**0 hits** (exit code 1, no matches).

## Compile Results

- `kyo-jsonrpc/Test/compile`: `[success]` (one pre-existing exhaustivity warning, unrelated)
- `kyo-jsonrpc-http/Test/compile`: `[success]`
- `kyo-browser/Test/compile`: `[success]`
