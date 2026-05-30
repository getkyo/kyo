# Phase 05 decisions

## Opaque-type final shape

`JsonRpcHandler` is now `opaque type JsonRpcHandler = JsonRpcHandler.Unsafe`, verbatim after `HttpServer.scala:37`.

`JsonRpcHandler.Unsafe` is a `sealed abstract class` (well, `abstract class` - not `sealed` since
`JsonRpcEndpointImpl` is in a sibling package). `JsonRpcEndpointImpl` extends it and provides all
concrete implementations.

The `final def safe: JsonRpcHandler = this` pattern from `HttpServer.Unsafe:162` is present on
`JsonRpcHandler.Unsafe`, and the companion's `extension (self: JsonRpcHandler)` block provides all
public API methods.

## Extension-method placement

All former instance methods on `final class JsonRpcHandler` moved to
`object JsonRpcHandler { extension (self: JsonRpcHandler) { ... } }`:

| Former instance method | Extension method | Delegates to |
|---|---|---|
| `call[In, Out]` | `call[In, Out]` | `self.callUnsafe[In, Out]` |
| `notify[In]` | `notify[In]` | `self.notifyUnsafe[In]` |
| `sendUnmatched[In]` | `sendUnmatched[In]` | `self.sendUnmatchedUnsafe[In]` |
| `callWithProgress[In, Out]` | `callWithProgress[In, Out]` | `self.callWithProgressUnsafe[In, Out]` |
| `callPartialResults[In, T]` | `callPartialResults[In, T]` | `self.callPartialResultsUnsafe[In, T]` |
| `subscribeProgress` | `subscribeProgress` | `self.subscribeProgressUnsafe` |
| `unsubscribeProgress` | `unsubscribeProgress` | `self.unsubscribeProgressUnsafe` |
| `cancel` | `cancel` | `self.cancelUnsafe` |
| `awaitDrain` | `awaitDrain` | `self.awaitDrainUnsafe` |
| `close` (no-arg) | `close` (no-arg) | `self.closeUnsafe(Duration.Zero)` |
| `close(gracePeriod)` | `close(gracePeriod)` | `self.closeUnsafe(gracePeriod)` |
| `closeNow` | `closeNow` | `self.closeUnsafe(Duration.Zero)` |

Added: `.unsafe` extension method returns the underlying `Unsafe` instance (mirrors `HttpServer.unsafe`
at `HttpServer.scala:66`).

## close overload ambiguity

The scaladoc warnings about Scala 3 overload-resolution ambiguity (previously on lines 78-95) were
deleted entirely. Opaque-type extension methods resolve cleanly in Scala 3 without ambiguity.

## `JsonRpcRoute.dispatch` migration

`JsonRpcRoute.dispatch` was changed from `def` to `private[kyo] def`. It is no longer part of the
public API surface. Tests in `package kyo` continue to call it directly.

`JsonRpcHandler.Unsafe.dispatch` is added as a new abstract method implemented by
`JsonRpcEndpointImpl.dispatch`. Signature:

```scala
def dispatch(
    name: String,
    params: Structure.Value,
    ctx: JsonRpcRoute.Context
)(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError])]
```

The implementation looks up `name` in the handler's own `methodMap` (the same map built at
`initEngine` time), returning `Present(route.handle(...))` or `Absent`. This gives engine-level
callers access to dispatch without going through `JsonRpcRoute.dispatch`.

## `JsonRpcHandler.init` migration

The old `JsonRpcEndpointImpl.init` (which wrapped `initEngine` in `Scope.acquireRelease`) was removed.
`JsonRpcHandler.init` now calls `JsonRpcEndpointImpl.initEngine` directly (exposed as `private[kyo]`)
and wraps it in `Scope.acquireRelease`. `JsonRpcHandler.initUnscoped` is added as a new public method
returning `JsonRpcHandler < Async` (mirrors `HttpServer.initUnscoped` at `HttpServer.scala:95`).

## `.unsafe` access in tests

Tests that previously accessed `handler.impl.callerRegistry` / `.inFlight` / `.config` now use:

```scala
handler.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl].callerRegistry
```

The cast is acceptable because `JsonRpcEndpointImpl` is the only concrete `Unsafe` implementation,
and the test files are in `package kyo` where `private[kyo]` access is valid.

## Deviations from the plan

1. **`closeFiber` not introduced.** The plan cited `HttpServer.closeFiber` returning
   `Fiber.Unsafe[Unit, Any]`. For `JsonRpcHandler`, the underlying `closeImpl` already returns
   `Unit < Async` (a properly suspended effect), so wrapping it in a `Fiber.Unsafe` then
   immediately calling `.safe.get` in every extension method would be needless allocation.
   Instead, `closeUnsafe(gracePeriod)(using Frame): Unit < Async` is the abstract method on
   `Unsafe`, and extension methods call it directly. Functionally equivalent; simpler.

2. **`Sync.Unsafe.defer` not used in extension methods.** The plan suggested bridging via
   `Sync.Unsafe.defer`. The `Unsafe` methods already return proper Kyo effects (`Unit < Async`
   etc.) so no defer bridge is needed; extension methods delegate directly.

3. **`abstract class` not `sealed abstract class`.** `JsonRpcHandler.Unsafe` is `abstract class`
   (not `sealed`) because `JsonRpcEndpointImpl` lives in `kyo.internal.engine`, a sub-package,
   and Scala 3 sealed hierarchies require all implementations in the same file or package.
   This matches `HttpServer.Unsafe` at `HttpServer.scala:145` which is also `abstract class`.
