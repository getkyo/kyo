# Phase 2 Decisions Log

## Decision 1: JsonRpcResponse PUBLIC marker rationale

The top-of-file marker is:
```
// flow-allow: PUBLIC response wire-shape with success/failure smart constructors and Schema derivation
```

Rationale: `JsonRpcResponse` is consumed by users who pattern-match on decoded envelopes and who call `JsonRpcResponse.success`/`.failure` to build responses in test-double transports. It lives in `kyo.*` (not `kyo.internal.*`) and is referenced directly in `JsonRpcCodecTest.scala`. The PUBLIC designation is correct per design §8b.

## Decision 2: Imports in JsonRpcResponse.scala

The plan's code block includes explicit imports (`kyo.Frame`, `kyo.Maybe`, `kyo.Maybe.Absent`, `kyo.Maybe.Present`, `kyo.Schema`, `kyo.Structure`). These were carried verbatim from the plan. Although `kyo.*` would suffice (this is the `kyo` package), the plan specified explicit imports and they were preserved as written.

## Decision 3: JsonRpcCodecTest.scala - two cases removed

The plan identifies two cases absorbed into `JsonRpcResponseTest.scala`:
1. `"JsonRpcResponse success and failure factories enforce xor"` (lines 185-192 pre-Phase-2)
2. `"Schema JsonRpcResponse compiles and round-trips via Json"` (lines 208-214 pre-Phase-2)

Both were removed. The intervening case `"Cdp omits jsonrpc field and Strict2_0 always includes it"` was NOT removed (it tests codec behavior, not `JsonRpcResponse` construction). The removal was surgical per plan instructions.

## Decision 4: No plan code adaptation needed

The plan code blocks were used exactly as written. No adaptation was required. The `JsonRpcRequest` case class in `kyo/internal/JsonRpcRequest.scala` dropped the `package kyo` declaration (it was implied by the `package kyo.internal` directive). The `Frame` import in `JsonRpcRequest.scala` was not included since the internal case class uses no `Frame`-typed parameters, matching the plan's code block exactly.

## Smells noted but deferred

1. `JsonRpcCodecTest.scala` still imports `kyo.Result` and `kyo.Structure.Value.*` - these remain valid for the retained test cases. No change needed.
2. The `kyo.internal.JsonRpcRequest` case class has `derives Schema, CanEqual` but there are currently zero `Schema[JsonRpcRequest]` summons in the codebase. The derivation is cheap and keeps the type serializable for future internal use. Deferred observation only.
3. The removed `"Schema JsonRpcResponse compiles and round-trips via Json"` case used `Json.encode`/`Json.decode` while the new `JsonRpcResponseTest.scala` uses `Structure.encode`/`Structure.decode`. The plan specifies `Structure.*` for the Phase 2 unit tests; the `Json` variant is covered by the existing codec tests that remain. No action needed.
