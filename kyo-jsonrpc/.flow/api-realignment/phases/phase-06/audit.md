# Phase 06 audit

Time: 2026-05-30T21:00Z
HEAD: 302cdb7314a24a9167ab026873de15ff5d582d9f
Phase commit: 302cdb7314a24a9167ab026873de15ff5d582d9f
Plan cites: ./design/realignment-plan.md §Phase F
Design cites: ./design/realignment-plan.md (Resolutions 5, 6, 7)

## Test count

| Leaf | Status | Notes |
|---|---|---|
| 1: Lifecycle variants (init, initWith, initUnscoped, initUnscopedWith) | PRESENT_STRICT | 8 overloads on JsonRpcHandler companion (`JsonRpcHandler.scala:496-572`); mirrors HttpServer.scala:71-150 modulo the port/host axis which has no JSON-RPC analogue (transport is injected as JsonRpcTransport). |
| 2: Config.lsp / Config.mcp presets | PRESENT_STRICT | `JsonRpcHandler.scala:462-481`; policies wired exactly as plan specifies. |
| 3: JsonRpcMessageGate hoist | PRESENT_STRICT | Top-level `JsonRpcMessageGate.scala`; `noop`, `server.requireInitialize`, `client` (placeholder) namespaces present. Mirrors HttpFilter.scala layout. |
| 4: Decision.Reject widening | PRESENT_STRICT | `JsonRpcMessageGate.scala:43` `Reject(response: JsonRpcResponse)`; all 9 construction sites migrated (3 test files + engine). Engine sends `response` directly via WriterMsg.SendEnvelope at `JsonRpcEndpointImpl.scala:1181-1192`. |
| 5: STEER-1 ErrorMapping wiring | PRESENT_STRICT | `JsonRpcRoute.scala:184-193` (RequestRoute) and `:234-240` (NotificationRoute); end-to-end test at `JsonRpcHandlerTest.scala:691-715` asserts handler's `Abort.fail(originalError)` (code -31999) arrives on wire with mapping's code -32099. The pulse's "WEAKENED" verdict on this item is no longer accurate; the end-to-end test was added before commit. |
| 6: STEER-2 Halt interception | PRESENT_STRICT | Engine onComplete intercepts `Result.Failure(halt: JsonRpcResponse.Halt)` at `JsonRpcEndpointImpl.scala:1062-1064` BEFORE the generic error case; end-to-end test at `JsonRpcHandlerTest.scala:718-736` asserts `Abort.fail(JsonRpcResponse.halt(resp))` sends `resp` directly (wire code -32777 matches). |

## CONTRIBUTING.md violations

None found in changed files.

## Unsafe markers

- `JsonRpcMessageGate.scala:71`: `AtomicBoolean.Unsafe.init` inside `requireInitialize` preset.
  Justification: `// Unsafe: AtomicBoolean for thread-safe initialized flag shared across handler fibers` — PRESENT.
- `JsonRpcEndpointImpl.scala:1085-1088`: `writerChannel.unsafe.offer` inside gate-Reject arm.
  Justification: `// Unsafe: offer to writerChannel inside gate decision handler` — PRESENT.
- `JsonRpcEndpointImpl.scala:1052-1053`: `fiber.unsafe.onComplete` for Halt interception.
  Justification: `// fiber onComplete attaches cleanup hook from outside the fiber; no safe equivalent in Fiber public API` — PRESENT.

All AllowUnsafe sites carry the required `// Unsafe:` rationale comment.

## Cross-platform consistency

- Platforms checked: JVM only at phase commit (per plan Phase F: "JVM tests green; add new tests for the lifecycle variants + the Config.lsp / .mcp presets"; full cross-platform is Phase H per plan §H).
- Per-platform deltas: none introduced. All changes are in `shared/src/main/scala/kyo/` and `shared/src/test/scala/kyo/` — no `jvm/`, `js/`, or `native/` source touched.
- Verification logs present in `runs/`: impl-compile-jvm-001.log, impl-compile-http-jvm-001.log, impl-compile-browser-jvm-001.log, impl-test-jvm-001.log. Last is `Tests: succeeded 189, failed 0`.

## Naming convention compliance

- All hoisted/renamed types follow the `JsonRpc*` prefix convention.
- `JsonRpcMessageGate.server.requireInitialize` parallels `HttpFilter.server.basicAuth` per plan §G.
- `JsonRpcMessageGate.beforeDispatch` (decisions.md typo: claimed `admit`, source has `beforeDispatch`). NOTE-level naming consistency item; the decisions log is the drift, not the source.

## Steering deviation

- `git diff --name-only HEAD~1 HEAD` ; matches the plan's Phase F file set:
  - source: JsonRpcHandler.scala, JsonRpcRoute.scala, JsonRpcMessageGate.scala (new), JsonRpcEnvelope.scala, JsonRpcEndpointImpl.scala
  - tests: JsonRpcHandlerMessageGateTest.scala, JsonRpcHandlerTest.scala, JsonRpcHandlerUnknownMethodPolicyTest.scala, JsonRpcRouteTest.scala, scenario/HttpStyleTest.scala
- All in-scope per Phase F. No drive-by refactor.

## Anti-flakiness measures

- N/A for this phase ; new tests are deterministic in-memory transport echo with explicit await on `Abort.run` results. No timing-based assertions.

## Architecture substitution check

- Design intent: top-level `JsonRpcMessageGate` mirroring `HttpFilter` with `.server`/`.client` namespaces; gate `Decision.Reject` carries a full `JsonRpcResponse`; engine intercepts `JsonRpcResponse.Halt` before generic error case; route layer consults `errorMappings` on Abort.fail.
- HEAD reality: exact 1:1 implementation. Hoist matches HttpFilter.scala layout; Decision.Reject carries `JsonRpcResponse`; engine onComplete at `JsonRpcEndpointImpl.scala:1062-1064` matches the precedent shape; route handle at `JsonRpcRoute.scala:184-193` consults `errorMappings.iterator.find(_.matches(err))`.
- Verdict: MATCH.

## Documentation drift

- Scaladoc additions in this phase:
  - `JsonRpcMessageGate` trait (24 lines) + `Decision` enum (14 lines) + `server.requireInitialize` (17 lines).
  - `Config.lsp` and `Config.mcp` doc blocks (~10 lines each).
  - 6 new lifecycle overload docstrings (~3 lines each).
- All additions are focused on the new public API surface and are within the project's 8-35-lines-per-public-type budget.
- Beyond plan intent: no.

## Held-out precedent check

The plan cites three kyo-http anchors. Verified at the held-out source:

- HttpServer.scala:71-150 ; init/initWith/initUnscoped/initUnscopedWith with port/host overloads. CONFIRMED present (3 init variants + 3 initWith + 3 initUnscoped + 3 initUnscopedWith = 12 overloads). kyo-jsonrpc has 8 overloads (no port/host axis because transport is supplied as `JsonRpcTransport`). The axis-shape match is correct ; the missing port/host pair is structurally inapplicable to JSON-RPC.
- HttpFilter.scala:43 + .server (line 108) + .client ; layout precedent. CONFIRMED. JsonRpcMessageGate mirrors verbatim modulo the `[ReqUse, ReqAdd, ResUse, ResAdd, +E]` type parameter pack (HttpFilter's request/response composition is HTTP-specific; envelope-level gating has no analogue).
- HttpResponse.scala:169 `case class Halt(response: HttpResponse[Any])`. CONFIRMED. `JsonRpcResponse.Halt(response: JsonRpcResponse)` mirrors verbatim.

## 9-sweep convention check

| Sweep | Count |
|---|---|
| em-dash / en-dash | 0 |
| Thread.sleep | 0 |
| synchronized | 0 |
| @uncheckedVariance | 0 |
| protected modifier | 0 |
| scala.Option[ | 0 |
| scala.Either[ | 0 |
| scala.List[ | 0 |
| CountDownLatch | 0 |

9/9 PASS.

## Findings (categorized)

### BLOCKER

None.

### WARN

None.

### NOTE

1. **Decisions-log drift**: `decisions.md §3` describes `JsonRpcMessageGate.admit(env, route)` but the source uses `beforeDispatch(env: JsonRpcEnvelope)` (no `route` param). The decisions log was written from an earlier draft; the source is correct (matches `HttpFilter.beforeDispatch` precedent). Queue for end-of-project cleanup ; either update the decisions log or note the rename in a follow-up doc. Cosmetic.

2. **`JsonRpcMessageGate.client` is an empty placeholder**: documented as such with comment `Intentionally empty for now. Client-side gate use-cases are expected to be added in Phase G when outbound gating is introduced.` Plan §F mentions `JsonRpcMessageGate.{noop, server, client, andThen, ...}` in the abstract; client-side presets are correctly deferred to Phase G per the explicit Phase F scope. No follow-up needed beyond the existing Phase G work item.

3. **`Config.mcp` adds `.unknownMethod(UnknownMethodPolicy.minimal)` redundantly**: `minimal` is already the default value of `Config.unknownMethod`. Setting it explicitly is harmless documentation-as-code; no behavioural change. NOTE only.

## Routing

- BLOCKER findings: none ; SLOT-A launch of phase G is not held.
- WARN findings: none.
- NOTE findings (3): TaskCreate for end-of-project cleanup. None affect Phase G prep input.
