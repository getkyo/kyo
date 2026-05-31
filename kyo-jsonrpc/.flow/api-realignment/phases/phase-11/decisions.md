# Phase 11 — Strip Protocol-Specific Names from Generic Modules

## Changes per file

### kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcCodec.scala
- 1 rename: `val Cdp` → `val Lenient`
- 1 scaladoc rewrite: `[[JsonRpcCodec.Cdp]]: Chrome DevTools Protocol dialect without the "jsonrpc" version` → `[[JsonRpcCodec.Lenient]]: omits the "jsonrpc" version field on encode; accepts messages without it on decode`

### kyo-jsonrpc/shared/src/main/scala/kyo/internal/codec/JsonRpcCodecImpl.scala
- 1 rename: `private val cdpReservedKeys` → `private val reservedKeys`
- 2 reference renames: `cdpReservedKeys` → `reservedKeys` (lines 167, 194)
- 1 rename: `val Cdp: JsonRpcCodec = new JsonRpcCodec:` → `val Lenient: JsonRpcCodec = new JsonRpcCodec:`
- 1 rename: `end Cdp` → `end Lenient`

### kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala
- 2 scaladoc rewrites (JsonRpcRequest line 31, JsonRpcNotification line 117): "e.g. CDP `sessionId`" → "e.g. protocol-extension fields like `sessionId` or `_meta`"

### kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecTest.scala
- 7 test name renames: all "Cdp ..." → "Lenient ..."
- 7 `JsonRpcCodec.Cdp` → `JsonRpcCodec.Lenient` call site renames
- 1 variable rename: `rCdp` → `rLenient`

### kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerTest.scala
- 3 `JsonRpcCodec.Cdp` → `JsonRpcCodec.Lenient` call site renames
- 3 `cdpConfig` → `lenientConfig` variable renames

### kyo-jsonrpc/shared/src/test/scala/kyo/scenario/WsStyleTest.scala
- 2 test name renames: "CDP-shape ..." → "Lenient ..."
- 2 `JsonRpcCodec.Cdp` → `JsonRpcCodec.Lenient` call site renames
- 4 `cdpConfig` → `lenientConfig` variable renames

### kyo-jsonrpc/README.md
- Line 342: rewrote `JsonRpcCodec.Cdp` description; removed "Chrome DevTools Protocol dialect" mention
- Line 440: `JsonRpcCodec.Cdp` → `JsonRpcCodec.Lenient` in code example

### kyo-browser (9 caller sites updated, internal names untouched)
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`: 3 sites
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendLifecycleTest.scala`: 1 site
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala`: 1 site
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala`: 1 site
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala`: 1 site
- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala`: 2 sites
- All kyo-browser internal names (`CdpBackend`, `CdpTypes`, `CdpEvent`, `kyo.internal.cdp.*`, etc.) left untouched.

## Final sweep result

```
rg -ni "lsp|mcp|cdp|chrome devtools|language server|model context" \
   kyo-jsonrpc/shared/src kyo-jsonrpc/jvm/src kyo-jsonrpc/js/src \
   kyo-jsonrpc/native/src kyo-jsonrpc-http/src kyo-jsonrpc/README.md
```
Result: **ZERO HITS**

```
rg -n "JsonRpcCodec\.Cdp\b" kyo-browser/ --type scala
```
Result: **ZERO HITS**

## Compile results

- `kyo-jsonrpc/Test/compile`: [success]
- `kyo-jsonrpc-http/Test/compile`: [success]
- `kyo-browser/Test/compile`: [success]
