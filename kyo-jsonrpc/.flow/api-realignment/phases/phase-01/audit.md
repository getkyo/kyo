# Phase 01 audit ; foundation renames

Commit: 8bec7d8821320d59ce866a42e47b3b9cf8256bfe
Plan anchor: design/realignment-plan.md Phase A
Steering: control/steering.md
Pulse: phases/phase-01/pulse-1.md (CONTINUE)
Scope: 38 files, +658 / -665 (rename-balanced)

## Verdict

| Severity | Count |
|---|---|
| BLOCKER | 0 |
| WARN    | 0 |
| NOTE    | 3 |

PASS. The commit is a faithful, pure-rename foundation phase. No semantic, type-parameter, effect-row, or Schema-derivation changes. All plan-mandated scope items are present byte-correctly. Internal `JsonRpcEndpointImpl` preserved per plan intent. Convention sweep clean on production sources.

## Checklist results

| # | Check | Status | Evidence |
|---|---|---|---|
| 1 | `JsonRpcEndpoint` -> `JsonRpcHandler` byte-correct (file rename + content) | PASS | `JsonRpcHandler.scala` is `new file` mode in diff; previously `JsonRpcEndpoint.scala` removed; 442 LoC; scaladoc retitled to "live JSON-RPC 2.0 handler" |
| 2 | `JsonRpcMethod` -> `JsonRpcRoute` complete (no stale refs anywhere) | PASS | `grep -rE "JsonRpcMethod" --include="*.scala"` returns 0 hits outside `.flow/` design docs |
| 3 | `JsonRpcMethod.Context` -> `JsonRpcRoute.Context` complete | PASS | `JsonRpcRoute.scala:80` defines `Context`; `JsonRpcRoute.scala:36,40,69,75,98,108` reference `JsonRpcRoute.Context`; engine + tests + kyo-browser callers all updated |
| 4 | 2nd-overload `apply` (no-Context form) deleted; no stale calls | PASS | Pre-rename file had `def apply[...](handler: In => Out < S)` at line 92 ; post-rename `JsonRpcRoute.scala` has exactly 1 `def apply` (the canonical `(In, Context) => Out < S` form); one test exercising the dropped form was rewritten against the 2-arg form (see `JsonRpcRouteTest.scala` "handler returns Out and result is encoded as Structure.Value") |
| 5 | `JsonRpcTestBase` -> `JsonRpcTest` complete | PASS | `JsonRpcTest.scala:9` declares `abstract class JsonRpcTest`; 20 test classes updated to `extends JsonRpcTest`; 0 surviving `JsonRpcTestBase` refs |
| 6 | NO semantic changes (no type-param / effect-row / Schema / error-hierarchy changes) | PASS | Diff of `JsonRpcRoute.scala` apply signature shows only `Context` qualifier change (`JsonRpcMethod.Context` -> `JsonRpcRoute.Context`); engine impl diff shows only identifier renames in field types and parameters; no new traits, no new type parameters introduced |
| 7 | Internal `JsonRpcEndpointImpl` class name preserved | PASS | `internal/engine/JsonRpcEndpointImpl.scala` retains class name + filename; contents updated to reference `JsonRpcHandler.{ExtrasEncoder, Config, UnknownMethodPolicy, ProgressPolicy}` and `JsonRpcRoute` correctly |
| 8 | No FP-discipline regressions on production scala | PASS | Added-lines sweep on non-`.flow/` files: 0 em-dashes, 0 `asInstanceOf`, 0 new `var`, 0 `Thread.sleep`, 0 `synchronized`, 0 `protected`, 0 `@uncheckedVariance`, 0 `Option[`/`Either[` introductions |
| 9 | Convention sweep on changed files | PASS | All 9 sweeps clean on production sources (em-dash, asInstanceOf, var, Option, Either, Thread.sleep, synchronized, protected, @uncheckedVariance) |

### Held-out check: kyo-http template mirror

```
$ grep -rE "class HttpServer|class HttpRoute|class HttpHandler" kyo-http/shared/src/main/scala/
kyo-http/shared/src/main/scala/kyo/HttpHandler.scala:sealed abstract class HttpHandler[In, Out, +E](val route: HttpRoute[In, Out, E]):
kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:case class HttpRoute[In, Out, +E](
```

Observed kyo-http: `HttpServer` (opaque runtime), `HttpRoute` (declarative), `HttpHandler` (per-route handler class taking a route).

kyo-jsonrpc Phase 01 result: `JsonRpcHandler` (runtime, future opaque type), `JsonRpcRoute` (declarative). The naming intentionally deviates from kyo-http's `HttpServer` -> uses `JsonRpcHandler` because JSON-RPC is symmetric/bidirectional (both peers serve and call), so "server" is misleading; "handler" carries the bidirectional intent. This rationale is documented in `design/realignment-plan.md:23` ("the runtime") and the audit's Phase A scope. Rationale STANDS.

Note that kyo-http's own `HttpHandler` is a per-route construct (route plus type-binding), while kyo-jsonrpc's `JsonRpcHandler` is the runtime peer. The lexical collision exists at the name level but is unambiguous in context because the two modules' top-level surfaces do not overlap and each is imported from its own package family. Future Phase 05 will convert `JsonRpcHandler` to an opaque type matching kyo-http's `HttpServer` opaque-type idiom.

## NOTES (informational, not blockers)

### NOTE-1 ; `JsonRpcEndpointImpl` identifier remaining in 4 production scala files

`grep -rE "JsonRpcEndpoint" --include="*.scala"` outside `.flow/` returns 6 hits, all referencing the deliberately-preserved internal class `JsonRpcEndpointImpl`:
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/JsonRpcEndpointImpl.scala` (the class itself)
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcHandler.scala:28,110,439` (3 mentions: field type, comment, init call)
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRoute.scala:57` (1 comment)
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerTest.scala:546` (1 comment)
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:612` (1 scaladoc cross-reference)

This matches the commit message's explicit declaration: "Internal class JsonRpcEndpointImpl kept under that name (it is internal-only and not part of the public surface)." Plan §A does not mandate the internal rename. Acceptable.

### NOTE-2 ; Historical docs retain old names

`kyo-jsonrpc/PHASE-2-PREP.md`, `kyo-jsonrpc/IMPLEMENTATION.md`, `kyo-jsonrpc/IMPLEMENTATION-VALIDATION.md`, and the prior `kyo-jsonrpc/.flow/api-cleanup/` and `.flow/protocol-coverage/` design folders contain old names (`JsonRpcMethod`, `JsonRpcEndpoint`). These are historical design records of completed campaigns, not source. Pulse-1 already flagged this as acceptable.

### NOTE-3 ; Phase-A scope of `JsonRpcEnvelope.Id` left as-is

`JsonRpcRoute.Context` still references `JsonRpcEnvelope.Id` (not yet hoisted to top-level `JsonRpcId`). This is the explicit scope boundary between Phase A (foundation renames) and Phase B (wire-message hoist) per `realignment-plan.md:54` and Phase B scope §95. Not a Phase 01 defect.

## Phase 01 axes vs plan

| Plan-mandated leaf | Status |
|---|---|
| Rename `JsonRpcEndpoint` -> `JsonRpcHandler` everywhere | PRESENT (byte-correct) |
| Rename `JsonRpcMethod` -> `JsonRpcRoute` everywhere | PRESENT (byte-correct) |
| Rename `JsonRpcMethod.Context` -> `JsonRpcRoute.Context` | PRESENT (byte-correct) |
| Rename `JsonRpcTestBase` -> `JsonRpcTest` (verify no collision with sibling modules' Test class) | PRESENT (`JsonRpcTest` chosen specifically to avoid collision with `kyo-http/shared/src/test/scala/kyo/Test.scala:9`) |
| Drop / rename the 2nd-overload `JsonRpcRoute.apply` (no-Context form) per audit A6 | PRESENT (dropped; 1 test rewritten) |

5/5 leaves PRESENT_STRICT.

## Cross-platform / verification posture

The commit message reports compile-green on all three modules (kyo-jsonrpc, kyo-jsonrpc-http, kyo-browser) and 102 tests / 0 failures on focused `*JsonRpcHandler* *JsonRpcRoute*` testOnly. Plan §A only requires `sbt 'kyo-jsonrpc/Test/compile' 'kyo-jsonrpc/test'` and `sbt 'kyo-browser/Test/compile'`; this commit met both. Cross-platform JS/native verification is reserved for Phase 08 per steering.

## Recommendation

PROCEED to Phase B (wire-message hoist). No remediation required.
