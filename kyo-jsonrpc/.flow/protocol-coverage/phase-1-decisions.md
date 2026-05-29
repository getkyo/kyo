# Phase 01 Decisions Log

## Decision 1: `decodeParams` field type uses `ParamsDecoder` context-function alias

**Plan specifies:** `decodeParams: Structure.Value => Maybe[JsonRpcId] < Sync`

**Adapted to:** `decodeParams: CancellationPolicy.ParamsDecoder` where `type ParamsDecoder = Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync`

**Reason:** `CancellationPolicy.scala` is `package kyo`, so `Frame` cannot be auto-derived at `val` sites. The `lspDecoder` and `mcpDecoder` vals need a `Frame` to call `Sync.defer` and `Structure.decode`. The `Frame ?=>` context function pattern (matching `ParamsEncoder`'s design) provides the frame via implicit application at each call site (`extractCancelId` has `using Frame` so the frame auto-applies). The `extractCancelIdForTest` in `CancellationEngine` exposes the private helper for test 10 to exercise without changing visibility of `extractCancelId`.

## Decision 2: `Malformed` handler in `decodeCallback` uses `abortSignal` not `responsePromise`

**Plan specifies:** `info.responsePromise.unsafe.completeDiscard(...)`

**Adapted to:** `info.abortSignal.unsafe.completeDiscard(Result.succeed(JsonRpcError.invalidRequest(...)))`

**Reason:** `CallerInfo` has no `responsePromise` field. The existing Response-error branch (line 1146) uses `info.abortSignal.unsafe.completeDiscard(Result.succeed(e))` to fail the caller. The `abortSignal` holds `JsonRpcError` errors via `Result.succeed(err)` semantics. The `Malformed` handler uses the same pattern for consistency.

## Decision 3: Non-object error field in Strict2_0 decoder emits `Malformed`

**Plan test 4** expects `{"jsonrpc":"2.0","id":42,"error":"stringy"}` to decode as `Malformed(Present(Num(42)), _, _)`.

**Original decoder** fell back to `JsonRpcError.InvalidRequest` on bad error fields, producing a `Response`.

**Added check:** `errorIsRecord` flag; when `hasError && !errorIsRecord`, the decoder emits `Malformed(idMaybe, "error field is not a Record", raw)` instead of attempting decode. This makes the decoder strict about error field shape.

## Decision 4: Pre-existing tests calling `a.close.andThen` updated to `a.closeNow.andThen`

The introduction of `close(gracePeriod: Duration)` as an overload caused Scala 3 overload resolution ambiguity when `a.close.andThen { block }` was parsed (the compiler tried to apply the block as the `gracePeriod` argument). Two pre-existing tests were updated to `a.closeNow.andThen` which unambiguously calls the no-grace-period variant. Semantics are identical.

## Decision 5: Test 2 uses `Abort.run[Timeout](Async.timeout(...)(Abort.run[JsonRpcError | Closed](...)))` for timeout handling

The plan's code block used `Abort.run[JsonRpcError | Closed](Async.timeout(...)(call))` which leaves `Abort[Timeout]` unhandled. Added `Abort.run[Timeout]` wrapper to discharge the timeout effect before `andThen` chaining.

## Decision 6: Test 7 wraps `tb.send` in `Abort.run[Closed]` inside `Fiber.initUnscoped`

`tb.send` returns `Unit < (Async & Abort[Closed])`. `Fiber.initUnscoped` handles `Abort[E]` via `Reducible`, but requires explicit declaration. Used `Abort.run[Closed](tb.send(...))` to produce `Result[Closed, Unit] < Async` for clean fiber initiation.
