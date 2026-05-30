# Phase 03 pulse 1

Time: 2026-05-30T00:00:00Z
Files reviewed: dirty tree (9 modified + 11 new leaf files + 2 new test/app files)
Plan cites: ./design/realignment-plan.md §Phase C (lines 103-346)

## Plan anchor

- Files to produce: 11 leaf `.scala` files + `JsonRpcApplicationError.scala` (abstract open) — ALL PRESENT
- Files to modify: `JsonRpcError.scala` (base + Schema), `JsonRpcCodecImpl.scala`, `JsonRpcEndpointImpl.scala`, `JsonRpcRoute.scala`, `CancellationEngine.scala`, `ProgressEngine.scala`, `CdpBackend.scala` — ALL PRESENT in dirty tree
- Tests: `JsonRpcErrorTest.scala` (109 lines, 8 test cases with concrete assertions) — PRESENT
- Public API additions: sealed base + 4 traits + 11 leaves + `JsonRpcApplicationError` abstract class + `JsonRpcError.fromWire` + hand-rolled `Schema[JsonRpcError]`

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | FLAG: no compile run observed in dirty tree; impl in progress, expected | phase still in flight |
| Compile-only "success" claim | CLEAN: no claim made; phase not committed | — |
| Priority inference | CLEAN: no off-scope deprioritization seen | — |
| Scope substitution | CLEAN: hierarchy matches plan exactly; no collapsing of leaves | all 11 leaf files confirmed |
| Foreach-discards-assert | CLEAN: `JsonRpcErrorTest.scala` uses concrete `assert(x == y)` throughout | lines 11-107 |
| Stale-state passing | CLEAN: `fromWire` and Schema operate on live leaf types | `JsonRpcError.scala:45-93` |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | STEER: `JsonRpcConfigurationError` message uses em-dash `—` instead of plan's ` ; ` separator | `JsonRpcConfigurationError.scala:16` vs plan line 224 |
| No off-plan architecture substitution | CLEAN: all 4 traits top-level (`JsonRpcParseFailure` etc.), not nested under `object JsonRpcError` | `JsonRpcError.scala:100-115` |
| No cross-cutting refactor outside phase | CLEAN: only error-hierarchy files and their direct callers are modified | git diff confirms |
| Internal helpers stay internal | CLEAN: no new `private[kyo]` leakage; aux enums correctly nested under consuming leaf companions | confirmed per-file |
| Aux enums top-level (rule violation check) | CLEAN: `Reason`, `Stage`, `Operation`, `ParamError`, `Problem` all nested under their leaf's companion | per-file grep confirms |
| `detail: String` on any leaf | FLAG (MINOR): `JsonRpcTransportError(detail: String, cause: Throwable)` — plan explicitly names this parameter `detail` in its spec text; acceptable per plan line 239 ("detail: brief description of what went wrong") | plan line 239 vs `JsonRpcTransportError.scala:14` |
| `Text` parameter on any leaf | CLEAN: all parameters are `String`, `Int`, `Throwable`, `Chunk[...]`, or `Maybe[Structure.Value]` | — |
| Nesting under `object JsonRpcError` | CLEAN: companion contains only `fromWire` + `Schema[JsonRpcError]` given | `JsonRpcError.scala:38-95` |

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| JsonRpcParseError | PRESENT_STRICT | Reason enum nested; 5 cases; `describe` method |
| JsonRpcInvalidRequestError | PRESENT_STRICT | code -32600, with JsonRpcParseFailure |
| JsonRpcMethodNotFoundError | PRESENT_STRICT | code -32601, available: Chunk[String] |
| JsonRpcInvalidParamsError | PRESENT_STRICT | ParamError + Problem nested; both enums present |
| JsonRpcConfigurationError | PRESENT (minor wire-message drift) | em-dash instead of ` ; ` in message; functionally correct |
| JsonRpcLifecycleError | PRESENT_STRICT | Stage enum nested; 5 cases (Init/Bind/Connect/Drain/Close) |
| JsonRpcTransportError | PRESENT_STRICT | detail + cause fields; plan uses `detail` as name |
| JsonRpcHandlerPanicError | PRESENT_STRICT | method + cause; code -32603 |
| JsonRpcInternalError | PRESENT_STRICT | Operation enum nested; DecodeResult/EncodeResponse/Other |
| JsonRpcImplementationError | PRESENT_STRICT | private constructor + factory with range check |
| JsonRpcCustomError | PRESENT_STRICT | extends JsonRpcApplicationError |
| JsonRpcApplicationError (abstract open) | PRESENT_STRICT | non-sealed, for user extension |
| 4 out-of-spec -32602 sites (EndpointImpl:107,203,518,534) | PRESENT_STRICT | all reclassified to `JsonRpcInternalError(Operation.DecodeResult, e)` |
| 5 internalError callsites migration | PRESENT_STRICT | JsonRpcRoute: HandlerPanicError; ProgressEngine: InternalError(Other); EndpointImpl: Configuration/Lifecycle/Transport leaves |
| CdpBackend.scala:65-71 string-prefix to typed leaf | PRESENT_STRICT | `case _: JsonRpcTransportError | _: JsonRpcLifecycleError =>` at line 65 |
| fromWire decoder | PRESENT_STRICT | maps all 7 standard codes; unknown codes -> JsonRpcCustomError |
| Hand-rolled Schema[JsonRpcError] | PRESENT_STRICT | writeFn encodes (code, message, data); readFn calls fromWire |
| JsonRpcErrorTest.scala | PRESENT_STRICT | 8 test cases, all use concrete assertions; wire round-trip tested |

## CRITICAL (steer immediately)

None.

## MINOR (queue for post-commit audit)

1. `JsonRpcConfigurationError.scala:16`: message string uses em-dash `—` where plan specifies ` ; ` (semicolon-space). Steering.md forbids em-dashes. Fix: `s"Configuration error: '$setting'; $reason"`.
2. `JsonRpcHandlerPanicError.scala:8` and `JsonRpcError.scala:9-12`: em-dashes in Scaladoc comments. Steering.md forbids LLM-tells in all output. Fix: rewrite sentences (e.g., "that the current flat `internalError` string discards" instead of "— information that the current flat `internalError` string loses").
3. `ProgressEngine.scala:13`: Scaladoc still references `JsonRpcError.internalError(...)` (old API). Should be updated to `JsonRpcInternalError(JsonRpcInternalError.Operation.Other, ...)`. Low risk since the actual code at line 23 is correct.
4. `detail: String` parameter name on `JsonRpcTransportError` is plan-specified (plan line 239), so this is not a rule violation. No action needed.

## Recommendation: CONTINUE

All 11 leaves present and correct. All 4 reclassified -32602 sites migrated to `InternalError(DecodeResult)`. CdpBackend typed-pattern migration complete. Schema encoding intact. Aux enums correctly nested. No structural drift. Three minor em-dash/stale-comment issues queue for post-commit audit but do not block the impl.
