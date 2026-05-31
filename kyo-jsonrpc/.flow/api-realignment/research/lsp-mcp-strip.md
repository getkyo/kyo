# LSP / MCP Touchpoint Strip Audit

Goal: verify that `kyo-jsonrpc` can become a generic JSON-RPC 2.0 module with **zero** LSP / MCP knowledge in types, scaladoc, or engine runtime, and that every behavior the current `.lsp` / `.mcp` presets encode can be rebuilt by callers using only the generic extension points the policy types already (or will) expose.

Every claim about "current state" cites the file and line in this worktree (`/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur`).

---

## 1. Per-touchpoint analysis

### A. `JsonRpcCancellationPolicy` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCancellationPolicy.scala`)

**Behaviors LSP/MCP need from the cancellation policy**

1. **Cancel method name** ; LSP uses `"$/cancelRequest"`, MCP uses `"notifications/cancelled"`.
   Captured today: `cancelMethod: String` (line 19). Generic.
2. **Cancel params encoding** ; LSP wire shape `{"id": <id>}`, MCP wire shape `{"requestId": <id>, "reason": <maybe string>}`.
   Captured today: `encodeParams: (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync` (lines 20, 28). Generic; the `reason` arg is already in the signature even though LSP ignores it.
3. **Cancel params decoding** ; map an inbound notification's params back to a `JsonRpcId`.
   Captured today: `decodeParams: Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync` (lines 21, 29). Generic.
4. **Does the cancelled request still reply?** ; LSP says yes (server still emits a `MethodNotFound`-shaped error reply), MCP says no (the cancellation is fire-and-forget; handler is interrupted, no wire reply).
   Captured today: `expectReplyForCancelledRequest: Boolean` (line 22). Generic.
5. **Synthetic abort error** ; the error value the local caller's promise is completed with when its outbound request is cancelled by us (timeout-driven or user-driven). LSP preset uses code `-32800 "Request cancelled"`; MCP also uses `-32800` (see `lsp.cancelledError = Present(JsonRpcCustomError(-32800, "Request cancelled"))` at line 71; `mcp.cancelledError = Absent` at line 80, with the fallback being `-32800 "Request cancelled"` ; see `CancellationEngine.handleTimeout` lines 106-107 of `internal/engine/CancellationEngine.scala`).
   Captured today: `cancelledError: Maybe[JsonRpcError]` (line 23). Generic.
6. **Protected methods** ; methods that the policy must refuse to cancel, e.g. MCP protects `"initialize"` (line 81). Cancel calls against a protected method become a no-op with a `Log.warn` (`JsonRpcEndpointImpl.scala:610-613`).
   Captured today: `protectedMethods: Set[String]` (line 24). Generic.

**Current extension-point shape** ; the six fields above are sufficient: method name, encoder, decoder, expect-reply flag, cancelled-error, protected-method set. The lambdas (`LspCancelParams`, `McpCancelParams`, `lspEncoder`, `mcpEncoder`, `lspDecoder`, `mcpDecoder` at lines 31-64) are **closed-source helpers** used only by the two presets ; they are not exposed and disappear when the presets disappear.

**Generic refactor target** ; delete `LspCancelParams`, `McpCancelParams`, `lspEncoder`, `mcpEncoder`, `lspDecoder`, `mcpDecoder`, `lsp` (lines 66-73), `mcp` (lines 75-82). Keep the case class and its six fields. Rewrite the scaladoc to describe what each field does instead of pointing at LSP/MCP presets.

**Engine consumption** ; the engine reads only the six fields:
- `policy.cancelMethod` at `JsonRpcEndpointImpl.scala:881`
- `policy.decodeParams` via `CancellationEngine.extractCancelId` at `CancellationEngine.scala:17`
- `policy.encodeParams` via `CancellationEngine.buildAndEnqueueOutboundCancel` at `CancellationEngine.scala:87`
- `policy.expectReplyForCancelledRequest` at `JsonRpcEndpointImpl.scala:51` and `617`, `1099`
- `policy.cancelledError` at `JsonRpcEndpointImpl.scala:616` and `CancellationEngine.scala:106`
- `policy.protectedMethods` at `JsonRpcEndpointImpl.scala:610`

None of those sites branch on the literal string `"$/cancelRequest"` or `"notifications/cancelled"`. **Branching is already field-driven.** Comments mention LSP / MCP (lines 618, 634, 1095, 1097, 1102, 1221); those are documentation-only.

**Verdict** ; A is strip-safe. No new fields needed. Required edits: delete the two preset vals + helper case classes/lambdas, rewrite scaladoc, remove the LSP/MCP-naming comments in `JsonRpcEndpointImpl.scala`.

---

### B. `JsonRpcProgressPolicy` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcProgressPolicy.scala`)

**Behaviors LSP/MCP need**

1. **Progress notification method name** ; `$/progress` (LSP) vs `notifications/progress` (MCP).
   Captured today: `progressMethod: String` (line 20). Generic.
2. **Inbound token extraction** ; pull the progress token out of the *progress notification* params on the receiving side. LSP: `params.token`. MCP: `params.progressToken`.
   Captured today: `extractInboundToken: Structure.Value => Maybe[Structure.Value] < Sync` (line 21, lines 49 / 59). Generic.
3. **Request-side token extraction** ; on the server, pull the *request's* progress token out of the original request params so the handler knows where to send updates. LSP: `params.workDoneToken`. MCP: `params._meta.progressToken`.
   Captured today: `extractRequestToken: Structure.Value => Maybe[Structure.Value] < Sync` (line 22, lines 50 / 60-61). Generic.
4. **Outbound token stamping** ; on the client, merge a freshly-allocated progress token into outbound request params. LSP injects `workDoneToken`; MCP injects `_meta.progressToken`.
   Captured today: `stampOutboundToken: (Structure.Value, Structure.Value) => Structure.Value < Sync` (line 23, lines 51 / 62-65). Generic.
5. **Progress notification params encoding** ; build the wire shape of the progress notification given (token, value). LSP: `{token, value}`. MCP: `{progressToken, ...flattened value fields...}`.
   Captured today: `encodeProgressParams: (Structure.Value, Structure.Value) => Structure.Value < Sync` (line 24, lines 52 / 67-68). Generic.
6. **Progress value extraction** ; given the inbound progress notification params, pull the user-facing value out. LSP: `params.value` (it's nested). MCP: `params` itself is the value (the policy returns `Present(params)` at line 69).
   Captured today: `extractProgressValue: Structure.Value => Maybe[Structure.Value] < Sync` (line 25). Generic.
7. **Monotonic enforcement** ; MCP requires `progress.progress` to be monotonically non-decreasing; LSP does not.
   Captured today: `enforceMonotonic: Boolean` (line 26). Generic; the monotonicity check is field-driven at `ProgressEngine.scala:67`.

**Engine consumption**
- `progressMethod` at `JsonRpcEndpointImpl.scala:890` and `ProgressEngine.scala:97`
- `extractInboundToken` at `JsonRpcEndpointImpl.scala:892`
- `extractRequestToken` at `ProgressEngine.scala:55`
- `stampOutboundToken` at `JsonRpcEndpointImpl.scala:413`
- `encodeProgressParams` at `ProgressEngine.scala:95`
- `enforceMonotonic` at `ProgressEngine.scala:67`
- `extractProgressValue` is **declared but unused** in the engine (no engine-side consumer found). It is a forward-looking field for stream consumers; harmless and can remain.

**Generic refactor target** ; delete the `lsp` (lines 47-55) and `mcp` (lines 57-71) preset vals plus the private `field` / `merge` helpers (lines 33-45). Drop the LSP/MCP names from the scaladoc.

The error-message string at `JsonRpcEndpointImpl.scala:380` and `:461` says "pass `Config.progress = Present(ProgressPolicy.lsp / .mcp)`" ; this must be rewritten to something like `"pass Config.progress = Present(<your ProgressPolicy>)"`.

**Verdict** ; B is strip-safe. No new fields needed.

---

### C. `JsonRpcUnknownMethodPolicy` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcUnknownMethodPolicy.scala`)

**Behavior LSP needs** ; LSP reserves the `$/` method-name prefix for "extra" methods that a peer may safely ignore. Concretely, when an unknown notification with method starting `$/` arrives, drop it silently instead of treating it as a protocol violation.

**Current extension-point shape** ; the policy has three fields:
- `onUnknownRequest: UnknownAction` (line 17) ; the action for unknown *requests* (replied, dropped, or reject-and-close).
- `onUnknownNotification: UnknownAction` (line 18) ; same for unknown *notifications*.
- `dollarPrefixOverride: Boolean` (line 19) ; LSP-specific: when true and method starts `$/`, override `onUnknownNotification` to `Drop`.

The engine consumes `dollarPrefixOverride` at `JsonRpcEndpointImpl.scala:963`:
```scala
val isDollarPrefix = method.startsWith("$/")
if config.unknownMethod.dollarPrefixOverride && isDollarPrefix then ...
```

**Problem** ; `dollarPrefixOverride: Boolean` plus a hard-coded `startsWith("$/")` test in the engine is an LSP-shaped extension point hiding in the generic API. It only works for one specific predicate. A generic API should expose a predicate.

**Generic refactor target** ; replace `dollarPrefixOverride: Boolean` with

```scala
ignoreUnknownNotification: String => Boolean  // default: _ => false
```

Then the engine line 963 becomes:

```scala
if config.unknownMethod.ignoreUnknownNotification(method) then
  Exchange.Message.Skip
else
  config.unknownMethod.onUnknownNotification match ...
```

A user that wants the LSP behavior writes `.copy(ignoreUnknownNotification = _.startsWith("$/"))`.

Also delete the `lsp` preset val (lines 35-39) and rewrite scaladoc; `minimal` and `strict` are fine generic presets and stay.

**Verdict** ; C requires **one new field** (`ignoreUnknownNotification: String => Boolean`) replacing one removed field (`dollarPrefixOverride: Boolean`). The change is field-for-field at the call site (one line edit at `:963`). Strip is **not safe** until that swap lands.

---

### D. `JsonRpcMessageGate.server.requireInitialize` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMessageGate.scala`)

**Behavior LSP needs** ; LSP servers must complete an `initialize` request before any other request is dispatched. The `requireInitialize` gate flips an internal `AtomicBoolean` on the first `initialize` request and rejects (with a caller-supplied `JsonRpcResponse`) any other request that arrives before that point.

**Current extension-point shape** ; `def requireInitialize(onUninitializedRequest: JsonRpcResponse): JsonRpcMessageGate` (line 68). The implementation hard-codes the method name `"initialize"` at line 76:

```scala
case JsonRpcRequest(_, "initialize", _, _) => ...
```

**Generic refactor target** ; either

1. **Parameterize the method name**, signature becomes `def requireHandshake(handshakeMethod: String, onUninitializedRequest: JsonRpcResponse): JsonRpcMessageGate`, with `"initialize"` no longer hardcoded; or
2. **Delete the helper entirely** and document on `JsonRpcMessageGate` how to write a handshake-gating gate (it is ~15 lines). The `Gate` trait already permits any user-side implementation.

Option 1 is the smaller change and preserves the convenience. Option 2 is cleaner since the only thing the helper does is wrap one `AtomicBoolean` plus a method-name compare.

**Recommendation** ; option 1. Rename `requireInitialize` to `requireHandshake` and accept the method name as a parameter. Update scaladoc to drop the "LSP" mention; example becomes "common for servers that require a handshake message before accepting work." The mirrors-comment at line 19 about `HttpFilter` already covers the design lineage.

**Verdict** ; D requires **one signature change** (add `handshakeMethod: String` parameter, rename the helper). The scaladoc reference to LSP at line 62 must also go. Strip is not safe until D is updated.

---

### E. `JsonRpcFramer.contentLength` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcFramer.scala`)

**Behavior** ; Content-Length framing is HTTP/1.1-shaped header framing. The implementation at lines 43-51 is generic ; it writes `Content-Length: N\r\n\r\n<N bytes>` and parses the same shape. Nothing in `FramerImpl.parseContentLength` is LSP-specific.

**Current scaladoc** ; line 15 says: "`Content-Length` header framing used by LSP over stdio."

**Generic refactor target** ; rewrite the scaladoc line to "HTTP-style `Content-Length` header framing. Suitable for stdio or socket transports that require explicit message lengths." Drop the LSP attribution.

**Verdict** ; E is a one-line scaladoc edit. Strip-safe.

---

### F. `JsonRpcHandler.Config.lsp` / `Config.mcp` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcHandler.scala`)

**Behavior** ; the two presets are convenience compositions:
- `Config.lsp` ; `Config.default.cancellation(JsonRpcCancellationPolicy.lsp).progress(JsonRpcProgressPolicy.lsp)` (lines 218-220).
- `Config.mcp` ; same but with `.mcp` cancellation+progress and an explicit `unknownMethod(JsonRpcUnknownMethodPolicy.minimal)` (lines 234-237).

Both presets disappear naturally when `JsonRpcCancellationPolicy.lsp` / `JsonRpcProgressPolicy.lsp` etc. go away. The composition is two `.copy` chained calls; users can write it themselves.

**Generic refactor target** ; delete `Config.lsp` (lines 213-220) and `Config.mcp` (lines 222-237). Keep `Config.default` and `Config.require`.

**Verdict** ; F is a pure deletion. Strip-safe once A/B are stripped.

---

### G. `JsonRpcRoute` scaladoc reference to LSP / MCP

**Behavior** ; scaladoc only. `JsonRpcRoute.scala:86` says `progress` reports "via `$.progress` (LSP) or `notifications/progress` (MCP), depending on the active `ProgressPolicy`."

**Generic refactor target** ; rewrite as "via the notification method configured on the active `JsonRpcProgressPolicy`."

**Verdict** ; G is a one-line scaladoc edit. Strip-safe.

---

### H. `JsonRpcEndpointImpl` engine branches

Six branches in the engine carry LSP/MCP names in comments or error messages. All six are **already field-driven**; the LSP/MCP names appear only in surrounding comments / error strings.

| # | File:Line | Current branch text | Field that drives it | Action |
|---|-----------|---------------------|----------------------|--------|
| 1 | `JsonRpcEndpointImpl.scala:380` | `"required for callWithProgress; pass Config.progress = Present(ProgressPolicy.lsp / .mcp)"` | error message only | rewrite string to drop LSP/MCP |
| 2 | `JsonRpcEndpointImpl.scala:461` | `"required for callPartialResults; pass Config.progress = Present(ProgressPolicy.lsp / .mcp)"` | error message only | rewrite string |
| 3 | `JsonRpcEndpointImpl.scala:617-618` | `if policy.expectReplyForCancelledRequest then // LSP: server will still reply ...` | `policy.expectReplyForCancelledRequest` already drives the branch | rewrite comment to "if the policy requires a reply for cancelled requests" |
| 4 | `JsonRpcEndpointImpl.scala:633-634` | `else // MCP/no-reply: complete abortSignal immediately, no reply expected` | same field, else arm | rewrite comment to "policy expects no reply for cancelled requests" |
| 5 | `JsonRpcEndpointImpl.scala:1095-1097` | `// Cancel won CAS; for LSP (expectReplyForCancelledRequest=true) // the handler still produces a reply, so we send it. // For MCP the handler was interrupted and should not reply.` | `config.cancellation.map(_.expectReplyForCancelledRequest).getOrElse(false)` (line 1098-1100) | rewrite comment without LSP/MCP names |
| 6 | `JsonRpcEndpointImpl.scala:1102` | `// Unsafe: SendEnvelope bypasses suppress check (LSP always replies)` | `mustReply` from same field | rewrite comment ; "policy demands a reply" |
| 7 | `JsonRpcEndpointImpl.scala:1221` | `// LSP cancel was issued; reply arrived but caller should see cancel error.` | `info.pendingCancelError.get()` ; this is set at line 622-623 only when `expectReplyForCancelledRequest` is true | rewrite comment ; "policy issued a cancel that expects a reply; caller still sees cancel error" |

**Verdict** ; H is comment-only edits at six sites once C and D are fixed. The runtime is already generic; the comments are merely shorthand.

Bonus: lines 962-965 also embed an LSP shape (the `startsWith("$/")` literal), driven by C's `dollarPrefixOverride`. That branch becomes generic once C is refactored to `ignoreUnknownNotification(method)`.

---

## 2. Extension-point inventory

### Present today and sufficient

`JsonRpcCancellationPolicy`:
- `cancelMethod: String`
- `encodeParams: (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync`
- `decodeParams: Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync`
- `expectReplyForCancelledRequest: Boolean`
- `cancelledError: Maybe[JsonRpcError]`
- `protectedMethods: Set[String]`

`JsonRpcProgressPolicy`:
- `progressMethod: String`
- `extractInboundToken: Structure.Value => Maybe[Structure.Value] < Sync`
- `extractRequestToken: Structure.Value => Maybe[Structure.Value] < Sync`
- `stampOutboundToken: (Structure.Value, Structure.Value) => Structure.Value < Sync`
- `encodeProgressParams: (Structure.Value, Structure.Value) => Structure.Value < Sync`
- `extractProgressValue: Structure.Value => Maybe[Structure.Value] < Sync`
- `enforceMonotonic: Boolean`

`JsonRpcUnknownMethodPolicy`:
- `onUnknownRequest: UnknownAction`
- `onUnknownNotification: UnknownAction`

`JsonRpcMessageGate`:
- The base trait `beforeDispatch(env: JsonRpcEnvelope)(using Frame): Decision < Sync` is sufficient ; any handshake gate is just a custom `JsonRpcMessageGate` instance.

### Missing or to-be-changed

1. **`JsonRpcUnknownMethodPolicy.ignoreUnknownNotification: String => Boolean`** ; replaces `dollarPrefixOverride: Boolean`. Default: `_ => false`. Drives the predicate at `JsonRpcEndpointImpl.scala:963`.

2. **`JsonRpcMessageGate.server.requireHandshake(handshakeMethod: String, onUninitializedRequest: JsonRpcResponse): JsonRpcMessageGate`** ; replaces `requireInitialize`. Generalizes the method-name match at `JsonRpcMessageGate.scala:76`.

That is the entire missing-extension-point list: **two field/signature swaps.**

---

## 3. Engine refactor target

For each LSP/MCP-named site in `JsonRpcEndpointImpl.scala`:

| Line | Current condition or comment | Generic replacement |
|------|------------------------------|---------------------|
| `380` | error string `"... ProgressPolicy.lsp / .mcp"` | `"required for callWithProgress; pass Config.progress = Present(<your JsonRpcProgressPolicy>)"` |
| `461` | error string `"... ProgressPolicy.lsp / .mcp"` | `"required for callPartialResults; pass Config.progress = Present(<your JsonRpcProgressPolicy>)"` |
| `617` | `if policy.expectReplyForCancelledRequest then // LSP: server will still reply ...` | keep code; rewrite comment to `// Policy requires a reply for cancelled requests: set pendingCancelError so the decode callback completes abortSignal when the reply arrives.` |
| `633` | `else // MCP/no-reply: complete abortSignal immediately, no reply expected` | keep code; rewrite comment to `// Policy expects no reply for cancelled requests: complete abortSignal immediately after enqueuing the cancel notification.` |
| `963` | `if config.unknownMethod.dollarPrefixOverride && isDollarPrefix` (where `isDollarPrefix = method.startsWith("$/")`) | `if config.unknownMethod.ignoreUnknownNotification(method)` (delete `isDollarPrefix` local val) |
| `1095-1097` | comment block citing LSP / MCP | rewrite as `// Cancel won the CAS. If the policy demands a reply for cancelled requests, send the response anyway; otherwise the handler was interrupted and produces no reply.` |
| `1102` | `// Unsafe: SendEnvelope bypasses suppress check (LSP always replies)` | rewrite as `// Unsafe: SendEnvelope bypasses suppress check because the policy demands a reply.` |
| `1221` | `// LSP cancel was issued; reply arrived but caller should see cancel error.` | rewrite as `// A reply-demanding cancel was issued; the reply has now arrived but the caller still sees the configured cancel error.` |

No control-flow changes. Only field-name and comment edits.

---

## 4. Test recreation

The current tests use the `.lsp` / `.mcp` presets directly. After the strip they need to build the equivalent policy values inline. The construction is one block of fields per test (or a `val lspCancellation = ...` at file top).

**Example 1: `JsonRpcHandlerCancellationPolicyTest.scala:11-12`** today:

```scala
private val lspConfig = JsonRpcHandler.Config(cancellation = Present(JsonRpcCancellationPolicy.lsp))
private val mcpConfig = JsonRpcHandler.Config(cancellation = Present(JsonRpcCancellationPolicy.mcp))
```

After the strip (using a shared test fixture, e.g. `JsonRpcTestPolicies.lspCancellation`):

```scala
private val lspConfig = JsonRpcHandler.Config(cancellation = Present(JsonRpcTestPolicies.lspCancellation))
private val mcpConfig = JsonRpcHandler.Config(cancellation = Present(JsonRpcTestPolicies.mcpCancellation))
```

`JsonRpcTestPolicies` is a new test-side object that holds the case classes (`LspCancelParams`, `McpCancelParams`) and the encoder/decoder lambdas that the production code currently hides. The construction is mechanical; see section 5.

**Example 2: `JsonRpcHandlerUnknownMethodPolicyTest.scala:27`** today:

```scala
val lspConfig = JsonRpcHandler.Config(unknownMethod = JsonRpcUnknownMethodPolicy.lsp)
```

After the strip:

```scala
val lspUnknownMethod = JsonRpcUnknownMethodPolicy.minimal
  .copy(ignoreUnknownNotification = _.startsWith("$/"))
val lspConfig = JsonRpcHandler.Config(unknownMethod = lspUnknownMethod)
```

(Assuming `copy` is exposed; the case class is `private[kyo]`-constructor today via the `private[kyo]` constructor at line 16, so an additional `withIgnoreUnknownNotification(p: String => Boolean): JsonRpcUnknownMethodPolicy` builder might be the public path. The test-side change is one extra line per test fixture.)

---

## 5. Recreate LSP cancellation using only the generic API

```scala
import kyo.*

object LspCancellation:

    final case class LspCancelParams(id: JsonRpcId) derives Schema, CanEqual

    private val encode: (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync =
        (id, _) => f ?=> Sync.defer(Structure.encode(LspCancelParams(id)))(using f)

    private val decode: Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync =
        sv => f ?=> Sync.defer {
            Structure.decode[LspCancelParams](sv)(using summon[Schema[LspCancelParams]], f) match
                case Result.Success(p) => Present(p.id)
                case _                 => Absent
        }(using f)

    val policy: JsonRpcCancellationPolicy = JsonRpcCancellationPolicy(
        cancelMethod                  = "$/cancelRequest",
        encodeParams                  = encode,
        decodeParams                  = decode,
        expectReplyForCancelledRequest = true,
        cancelledError                = Present(JsonRpcCustomError(-32800, "Request cancelled")(using Frame.internal)),
        protectedMethods              = Set.empty
    )
end LspCancellation
```

This is line-for-line equivalent to today's `JsonRpcCancellationPolicy.lsp` (production lines 31-38 + 47-54 + 66-73). Everything the user needs is in the public API: `JsonRpcCancellationPolicy` constructor, `JsonRpcId`, `Structure.encode/decode`, `Sync.defer`, `Maybe.Present/Absent`, `JsonRpcCustomError`. **No private symbol is consumed.**

---

## 6. Recreate MCP cancellation using only the generic API

```scala
import kyo.*

object McpCancellation:

    final case class McpCancelParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

    private val encode: (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync =
        (id, reason) => f ?=> Sync.defer(Structure.encode(McpCancelParams(id, reason)))(using f)

    private val decode: Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync =
        sv => f ?=> Sync.defer {
            Structure.decode[McpCancelParams](sv)(using summon[Schema[McpCancelParams]], f) match
                case Result.Success(p) => Present(p.requestId)
                case _                 => Absent
        }(using f)

    val policy: JsonRpcCancellationPolicy = JsonRpcCancellationPolicy(
        cancelMethod                  = "notifications/cancelled",
        encodeParams                  = encode,
        decodeParams                  = decode,
        expectReplyForCancelledRequest = false,
        cancelledError                = Absent,
        protectedMethods              = Set("initialize")
    )
end McpCancellation
```

Again, line-for-line equivalent to production lines 32-44 + 57-64 + 75-82. Public API only.

For progress, the LSP/MCP `JsonRpcProgressPolicy` values are reconstructible the same way; the policy types reference only `Structure.Value`, `Structure.Value.Record`, and `Chunk`, all of which are public.

---

## 7. Recommendation

**Two extension-point swaps are required before the strip lands.** Once they are in place the engine becomes fully generic and the `.lsp` / `.mcp` named symbols (plus their comments and scaladoc) can be deleted in one mechanical pass.

Required changes before strip:

1. **`JsonRpcUnknownMethodPolicy`** ; replace `dollarPrefixOverride: Boolean` with `ignoreUnknownNotification: String => Boolean` (default `_ => false`). Update engine call site `JsonRpcEndpointImpl.scala:963`.
2. **`JsonRpcMessageGate.server.requireInitialize`** ; rename to `requireHandshake` and add a `handshakeMethod: String` parameter (replaces the hard-coded `"initialize"` at `JsonRpcMessageGate.scala:76`).

Strip operations (mechanical, no behavior change):

- Delete `JsonRpcCancellationPolicy.lsp`, `.mcp`, plus the `LspCancelParams` / `McpCancelParams` case classes and the four encoder/decoder lambdas.
- Delete `JsonRpcProgressPolicy.lsp`, `.mcp`, plus the private `field` / `merge` helpers.
- Delete `JsonRpcUnknownMethodPolicy.lsp`.
- Delete `JsonRpcHandler.Config.lsp` and `Config.mcp`.
- Rewrite scaladoc in `JsonRpcCancellationPolicy.scala`, `JsonRpcProgressPolicy.scala`, `JsonRpcUnknownMethodPolicy.scala`, `JsonRpcMessageGate.scala`, `JsonRpcFramer.scala` (one line at L15), `JsonRpcRoute.scala` (L86), `JsonRpcHandler.scala` (drop the preset paragraphs).
- Rewrite the eight comment / error-string sites in `JsonRpcEndpointImpl.scala` listed in section 3.
- Rebuild affected tests against test-side fixtures that compose the same values via the generic API.

After those two swaps + the strip, callers can rebuild LSP and MCP cancellation, progress, unknown-method, and handshake-gate behavior with zero reliance on private symbols, as the snippets in sections 5 and 6 show.

**Verdict: not strip-safe today. Add the two extension points above first; then strip.**
