# Steering ; kyo-jsonrpc API realignment

## Most-corrected patterns (read before any phase)

1. Present finalized decisions, not options.
2. The summary IS the approval artifact.
3. Complete and correct is the North Star.
4. NEVER STOP.
5. Resume silently from any interruption.
6. No pending items at completion.

## Idle-wait and status-report are STOPS

- No mid-campaign status / checkpoint reports.
- Dispatch-then-work, never dispatch-then-wait.

## Campaign-specific constraints — REFERENCE IS kyo-http

EVERY phase impl prompt MUST cite kyo-http file:line precedents for the pattern being applied. Drift from kyo-http is the failure mode this campaign exists to fix. Do not invent shapes; copy kyo-http's conventions verbatim.

- **Subject**: kyo-jsonrpc + kyo-jsonrpc-http (+ kyo-browser caller updates as needed)
- **Template**: kyo-http (verbatim — naming, opaque types, sealed hierarchies, extension methods, error model)
- **Module prefix**: JsonRpc
- **Locked design**: realignment-plan.md (in this design/ folder)
- **Locked decisions** (per `exception-hierarchy-design.md` + `exception-hierarchy-q3q4.md`):
  - 4 operation-category traits (Parse/Dispatch/Execution/Application) ; single-leaf inheritance default
  - 11 case-class leaves + 1 abstract open class for user-extensible application errors
  - 4 specialized execution leaves (Configuration / Lifecycle / Transport / HandlerPanic) + InternalError catchall
  - Aux enums nest under their single-consumer leaf
  - String not Text ; no `detail: String` free-form parameters ; raw structured data only

## Phase plan (8 phases, ~2150 LoC)

01. Foundation renames (Endpoint→Handler, Method→Route, drop 2nd-overload, test base rename)
02. Wire-message hoist (Request/Response/Notification/Malformed/Id top-level, Envelope bare sealed trait)
03. Error hierarchy (sealed JsonRpcError + 4 operation traits + 11 leaves + JsonRpcTransportException analogue if scoped)
04. Route restructure (drop free S, add [In, Out, +E], Halt, .error[E])
05. Entry-point opaque type (JsonRpcHandler = JsonRpcHandler.Unsafe + extension methods)
06. Lifecycle variants + filter hoist (initWith/initUnscoped/initUnscopedWith, MessageGate top-level, Decision.Reject widen)
07. Cleanup nested types (hoist remaining Phase-03 nested ; Pending +Out ; codec/filter presets)
08. Final cross-platform green gate

## STEER (phase 05, from phase 04 audit)

Two items rolled into Phase 05 scope from phase 04 audit:

1. **Wire engine to `ErrorMapping[E].matches`** so `.error[E2]` is functional, not dead API. The route's `errorMappings: Chunk[ErrorMapping[?]]` exists with `ConcreteTag[E].accepts` runtime dispatch hook. The engine's request-dispatch loop (currently in `kyo.internal.engine.JsonRpcEndpointImpl` ; now the `JsonRpcHandler.Unsafe` body after Phase 05) MUST consult `errorMappings` when a route's handler aborts: match the abort value against each mapping's tag, emit the corresponding JsonRpcError code if found, else emit `JsonRpcHandlerPanicError`.

2. **Fix Halt-in-request-handler routing**: today a handler's `Abort.fail(JsonRpcResponse.Halt(resp))` lands as `JsonRpcHandlerPanicError` because the engine's catch-all interprets any abort as a panic. The fix: the engine's dispatch loop catches `JsonRpcResponse.Halt(resp)` before the generic catch and emits `resp` directly as the response. Then add an integration test that constructs a route which `Abort.fail(JsonRpcResponse.halt(myResponse))`s and asserts the wrapped response is what reaches the wire.

Phase 05 already covers the opaque-type conversion and `dispatch` migration; folding in (1)+(2) keeps the engine-wiring work in one phase. If they prove too large to fit, defer to Phase 06.

## STEER (phase 03, pulse 1)

Em-dashes detected in 3 sites. Fix BEFORE reporting done:

1. `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcConfigurationError.scala:16` ; the message string uses an em-dash. Replace with `; ` (semicolon-space). Pattern: `s"Configuration error: '$setting'; $reason"`.
2. Scaladoc on `JsonRpcHandlerPanicError.scala:8` and `JsonRpcError.scala:9-12` ; rewrite the affected sentences without em-dashes.
3. `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/ProgressEngine.scala:13` ; scaladoc references the old `JsonRpcError.internalError(...)` API. The code at line 23 is already correct (uses `JsonRpcInternalError(Operation.Other, ...)`); only the comment needs to match.

Steering rule: "No em-dashes / LLM-tells in any output." Class-A regex `llm-tells` catalog will reject the commit if these aren't fixed.

## Repo-wide rules

- Never push to remote. Never create / touch PRs.
- No Co-Authored-By in commits.
- No em-dashes / LLM-tells in any output.
- Never destructive git against uncommitted work.
- Test infrastructure changes OK; behavior changes need approval.
- Sequential test runs across platforms.
