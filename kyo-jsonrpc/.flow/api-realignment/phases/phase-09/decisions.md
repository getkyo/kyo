# Phase 09 Decisions

## Extension points added

### 1. `JsonRpcUnknownMethodPolicy.ignoreUnknownNotification: String => Boolean`

**File**: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcUnknownMethodPolicy.scala`

**Signature** (new case class field, replaces `dollarPrefixOverride: Boolean`):
```scala
final case class JsonRpcUnknownMethodPolicy private[kyo] (
    onUnknownRequest: JsonRpcUnknownMethodPolicy.UnknownAction,
    onUnknownNotification: JsonRpcUnknownMethodPolicy.UnknownAction,
    ignoreUnknownNotification: String => Boolean
) derives CanEqual
```

Semantic: when `ignoreUnknownNotification(methodName)` returns `true`, the notification is silently discarded without consulting `onUnknownNotification`. Default in `minimal` and `strict` presets: `_ => false`.

**Engine wiring** (`JsonRpcEndpointImpl.scala` line ~963): replaced
```scala
val isDollarPrefix = method.startsWith("$/")
if config.unknownMethod.dollarPrefixOverride && isDollarPrefix then
```
with:
```scala
if config.unknownMethod.ignoreUnknownNotification(method) then
```
No new engine branches; existing Skip arm is unchanged.

### 2. `JsonRpcMessageGate.server.requireHandshake(handshakeMethod: String, onUninitializedRequest: JsonRpcResponse): JsonRpcMessageGate`

**File**: `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMessageGate.scala`

**Signature**:
```scala
def requireHandshake(
    handshakeMethod: String,
    onUninitializedRequest: JsonRpcResponse
): JsonRpcMessageGate
```

Semantic: until a request with method `handshakeMethod` is seen, all other requests are rejected with `Decision.Reject(onUninitializedRequest)`. Notifications are always allowed through. No new engine branches; uses the same `AtomicBoolean` pattern as the original `requireInitialize`.

## Existing preset wiring

- `JsonRpcUnknownMethodPolicy.lsp` now constructed with `ignoreUnknownNotification = _.startsWith("$/")`. Behavioral equivalence to the old `dollarPrefixOverride = true` is preserved.
- `JsonRpcUnknownMethodPolicy.minimal` and `.strict` constructed with `ignoreUnknownNotification = _ => false`. Same behavior as before.
- `JsonRpcMessageGate.server.requireInitialize(r)` is now a one-line wrapper: `requireHandshake("initialize", r)`. Identical runtime behavior.

## kyo-browser fix

`kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` line ~310: renamed field from `dollarPrefixOverride = false` to `ignoreUnknownNotification = _ => false`.

## New tests added

**`JsonRpcHandlerUnknownMethodPolicyTest.scala`** (+3 tests):
1. `ignoreUnknownNotification predicate: matching notifications are silently dropped` - asserts engine stays open and invocation count stays 0 after an `internal/heartbeat` notification with `ignoreUnknownNotification = _.startsWith("internal/")`.
2. `ignoreUnknownNotification predicate: non-matching notifications go through onUnknownNotification` - asserts non-matching `external/event` falls through to `Drop` (engine stays open; subsequent call gets `-32601`).
3. `ignoreUnknownNotification predicate: always-true predicate drops all unknown notifications silently` - uses `strict` base with `ignoreUnknownNotification = _ => true`, asserts engine does NOT close after a notification that would otherwise Reject.

**`JsonRpcHandlerMessageGateTest.scala`** (+3 tests):
1. `requireHandshake: handshake method is allowed before handshake completes` - `begin` request gets `Allow` before handshake.
2. `requireHandshake: non-handshake request before handshake is rejected with supplied response` - `doWork` request gets `Reject(rejection)` before handshake, asserts `resp == rejection`.
3. `requireHandshake: requests allowed after handshake method observed` - after `handshake` request is seen, subsequent `doWork` gets `Allow`.

## Deviations

None. All existing `.lsp`, `.mcp`, and `requireInitialize` callsites continue to compile and pass without modification.

## Test counts

Before: 189 tests. After: 195 tests (+6).
All 195 passed; 0 failed.
