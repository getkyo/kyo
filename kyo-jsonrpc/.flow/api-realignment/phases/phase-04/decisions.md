# Phase 04 decisions

## JsonRpcRoute type-parameter shape

Final: `sealed trait JsonRpcRoute[In, Out, +E]` (sealed trait, not case class).

Rationale: JSON-RPC routes have two distinct kinds (Request and Notification) with different handler signatures (returning `Out` vs. `Unit`). A single case class cannot cleanly accommodate both kinds. The sealed trait with two private concrete classes (`RequestRoute`, `NotificationRoute`) mirrors the existing precedent while adding the `[In, Out, +E]` type parameters.

The `E` type parameter accumulates user-registered error types via `.error[E2]`. It starts as `Nothing` at construction and grows with each `.error` call via union: `JsonRpcRoute[In, Out, Nothing]` becomes `JsonRpcRoute[In, Out, NotFound | Forbidden]` after two `.error` calls.

Deviation from plan: the plan showed a potential case-class shape based on kyo-http. The sealed-trait shape is retained because kyo-http's `HttpRoute` is a case class because it separates route definition from handler attachment (`.handler(f)` is a separate step). In kyo-jsonrpc, the handler is passed directly to the factory, making a single case class insufficient for two handler signatures.

## Halt placement

`JsonRpcResponse.Halt` is a case class in the `JsonRpcResponse` companion object at:
`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala` (lines 98-111).

```scala
case class Halt(response: JsonRpcResponse)
def halt(response: JsonRpcResponse)(using Frame): Nothing < Abort[Halt]
```

Mirrors `HttpResponse.Halt` at `kyo-http/shared/src/main/scala/kyo/HttpResponse.scala:169`.

## .error[E2] signature

```scala
// on sealed trait JsonRpcRoute[In, Out, +E]:
def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Out, E | E2]
```

Not `inline` (the plan said `inline` but Scala 3 does not allow deferred inline methods on sealed traits). The `using` params (Schema, ConcreteTag) are passed explicitly at the call site so inline is not needed for their resolution.

Mirrors `HttpRoute.error[E2]` at `kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:82` minus the `inline` keyword.

## Handler effect row

Fixed to `(In, Context) => Out < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])`.

Domain errors `E` are NOT in the handler effect row for Phase 04. They are stored in `errorMappings` for future Phase 05 engine wiring. Scala 3's `Abort.run[E | ...]` requires `ConcreteTag[E]` at compile time, which is not available when `E` is a generic type parameter on the private route classes without threading it through all constructors. Keeping the handler row fixed avoids this constraint, matching the pattern where the engine absorbs `E` errors at dispatch time using the stored `ErrorMapping.matches` (which uses `ConcreteTag[E].accepts`).

## ErrorMapping

New type: `JsonRpcRoute.ErrorMapping[E](code: Int, message: String)(using tag: ConcreteTag[E])` with `matches(e: Any): Boolean = tag.accepts(e)`.

## dispatch signature

Changed from `dispatch[S](...)(using ..., (Async & Abort[JsonRpcError]) <:< S)` to `dispatch(...)` with no type parameter. Routes are now `JsonRpcRoute[?, ?, ?]` at the existential boundary. This removes the subtype-evidence constraint that no longer makes sense.

## JsonRpcHandler.init

Changed `methods: Seq[JsonRpcRoute[Async & Abort[JsonRpcError]]]` to `methods: Seq[JsonRpcRoute[?, ?, ?]]`.

## Caller-update counts

- `kyo-jsonrpc/shared/src/test/scala/kyo/`: 8 test files updated
  - `JsonRpcRouteTest.scala`: complete rewrite (factory calls, dispatch calls, 2 new tests)
  - `JsonRpcHandlerTest.scala`: ~25 route constructor calls
  - `JsonRpcHandlerCancellationPolicyTest.scala`: ~14 route constructor calls
  - `JsonRpcHandlerProgressPolicyTest.scala`: ~10 route constructor calls
  - `JsonRpcHandlerUnknownMethodPolicyTest.scala`: ~3 route constructor calls
  - `scenario/BidiTest.scala`, `scenario/HttpStyleTest.scala`, `scenario/MaxInFlightTest.scala`, `scenario/WsStyleTest.scala`: misc route calls

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`: 5 notification builder methods (return type + factory call)
- `kyo-browser/shared/src/test/scala/kyo/internal/`: 6 test files (route constructor calls + Seq type parameters)
- `kyo-jsonrpc-http/`: no route usage found; 0 changes.

Total: ~170 change sites across 14 files (bulk-replaced via perl one-liners).

## Tests added

Two new test cases in `JsonRpcRouteTest.scala`:
1. `JsonRpcResponse.Halt aborts with the wrapped response`: verifies Halt in a route handler is caught and surfaces as `JsonRpcHandlerPanicError`.
2. `.error accumulates error mappings on the route`: verifies `.error[E2]` appends an `ErrorMapping` with correct code/message and the `matches` predicate works.

## Deviations from plan

1. `error` is non-inline (see rationale above; inline on sealed trait deferred methods is a Scala 3 compile error).
2. Handler effect row excludes `E` (see rationale above; `Abort.run[E | ...]` requires `ConcreteTag[E]` which cannot be provided without threading it through all constructors). This is deferred to Phase 05 where the engine can use `errorMappings` at runtime.
3. `dispatch` lost the `[S]` type parameter (plan deferred it to Phase 05, but actually the old constraint is simply removed since the existential boundary is now `Seq[JsonRpcRoute[?, ?, ?]]`).

## Verification

- `kyo-jsonrpc/Test/compile`: [success]
- `kyo-jsonrpc-http/Test/compile`: [success]
- `kyo-browser/Test/compile`: [success]
- `kyo-jsonrpc/test`: 186/186 passed (2 new tests: Halt + .error)
