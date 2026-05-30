# Phase 01 decisions

## Files renamed (git mv, history preserved)

### Main source renames

| Old path | New path |
|---|---|
| `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala` | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcHandler.scala` |
| `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcMethod.scala` | `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRoute.scala` |

### Test file renames

| Old path | New path |
|---|---|
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTestBase.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointCancellationPolicyTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerCancellationPolicyTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointExtrasEncoderTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerExtrasEncoderTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointIdStrategyTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerIdStrategyTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointMessageGateTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerMessageGateTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointProgressPolicyTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerProgressPolicyTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEndpointUnknownMethodPolicyTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcHandlerUnknownMethodPolicyTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcMethodTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcRouteTest.scala` |
| `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcMethodContextTest.scala` | `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcRouteContextTest.scala` |

## Reference-update counts per module

### kyo-jsonrpc
- Main source files updated: 6 (`JsonRpcHandler.scala`, `JsonRpcRoute.scala`, `JsonRpcCodec.scala`, `JsonRpcError.scala`, `JsonRpcTransport.scala`, `JsonRpcEnvelope.scala`)
- Internal engine files updated: 4 (`JsonRpcEndpointImpl.scala`, `CancellationEngine.scala`, `IdStrategyEngine.scala`, `ProgressEngine.scala`)
- Test files updated: 21 (10 renames + 11 content-only updates)
- `JsonRpcEndpoint` reference updates: ~60 (companion member accesses like `.Config`, `.ExtrasEncoder`, `.IdStrategy`, etc.)
- `JsonRpcMethod` reference updates: ~40 (type usages, companion member accesses like `.Context`, `.Kind`, `.notification`, `.dispatch`)
- `extends JsonRpcTestBase` -> `extends JsonRpcTest`: 22 sites

### kyo-jsonrpc-http
- Files updated: 1 (`JsonRpcHttpTransport.scala`) - 0 direct references (no `JsonRpcEndpoint`/`JsonRpcMethod` usage in this module's source)

### kyo-browser
- Main source files updated: 1 (`CdpBackend.scala`)
- Test files updated: 5 (`CdpBackendTest.scala`, `CdpBackendLifecycleTest.scala`, `CdpClientDecoderTest.scala`, `CdpBackendSmokeTest.scala`, `JsonRpcPortInvariantsSpec.scala`)
- Reference updates: ~30 (`JsonRpcEndpoint` as bare type, in scaladoc, in function signatures; `JsonRpcMethod` type references in test helpers)

## 2nd-overload `apply` removal

**Removed**: `JsonRpcMethod.apply[In: Schema, Out: Schema, S](name: String)(handler: In => Out < S)` - the no-Context form at old `JsonRpcMethod.scala:92-97`.

**Rationale**: kyo-http avoids overload ambiguity by giving convenience factories distinct names. Audit A6 confirmed Scala 3 eta-expansion makes the two `apply` overloads (differing only in handler arity) resolve ambiguously with method references.

**Caller updates**: 1 test updated. The test "no-ctx overload produces identical encoded output as ctx overload ignoring ctx" at `JsonRpcRouteTest.scala` was renamed to "two routes with same logic produce identical encoded output" and updated to use the 2-arg `(n, _) => handler(n)` form. All other callers in production code already used the 2-arg `(params, ctx) =>` form.

## TestBase rename

`JsonRpcTestBase` existed at `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTestBase.scala:9`.

Renamed to `JsonRpcTest` per kyo project convention (test base classes are named `<Module>Test`). Per `audit.md` Axis 19: kyo-http uses `Test` at `kyo-http/shared/src/test/scala/kyo/Test.scala:9`.

**Extends sites updated**: 22 sites across kyo-jsonrpc test files (all shared + the JVM-only `JsonRpcTransportUnixTest`).

Note from Axis 19 audit: the rename from `Test` to `JsonRpcTestBase` had been done in commit `c332edf4c` ("Rule 8c Phase 3: orphan-test moves + Test->JsonRpcTestBase rename") to avoid collision across modules in a multi-module sbt context. However, since all test files are in `package kyo` and the base class is local to the `kyo-jsonrpc` test classpath, there is no collision risk. The rename back is safe.

## kyo-http precedents cited

- `JsonRpcEndpoint` -> `JsonRpcHandler`: kyo-http has `HttpServer` (server, unidirectional) and `HttpHandler` (handler, bidirectional). JSON-RPC is bidirectional, so `JsonRpcHandler` is the accurate analogue. Precedent: `kyo-http/shared/src/main/scala/kyo/HttpHandler.scala`.
- `JsonRpcMethod` -> `JsonRpcRoute`: kyo-http's declarative route entry is `HttpRoute[In, Out, +E]`. Precedent: `kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:40`.
- `JsonRpcMethod.Context` -> `JsonRpcRoute.Context`: carried by the rename above. No kyo-http direct analogue (kyo-http doesn't have a per-request context struct; the handler receives an `HttpRequest`). The rename is mechanical.
- Test base class: `abstract class Test` at `kyo-http/shared/src/test/scala/kyo/Test.scala:9`.

## Convention-sweep results (9 sweeps)

All sweeps on changed files (`.scala` files only):

| Sweep | Finding | Verdict |
|---|---|---|
| 1. Em-dashes | 0 hits | PASS |
| 2. AllowUnsafe outside Unsafe blocks | 1 comment hit (`// Unsafe: Promise.Unsafe.init...`) in engine file; not a violation | PASS |
| 3. Option vs Maybe | `Some`/`None` in `JsonRpcRoute.dispatch` are interop with `Map.get` (returns `scala.Option`); already annotated with `// scala.Option arm` comments | PASS |
| 4. Semicolons as statement terminators | 0 hits (2 hits are inside string literals and scaladoc) | PASS |
| 5. asInstanceOf | 0 hits | PASS |
| 6. Default params | Legitimate: `reason: Maybe[String] = Absent`, `config: Config = Config.default`, `extras: ExtrasEncoder = ExtrasEncoder.empty` | PASS |
| 7. Frame.internal | 0 hits | PASS |
| 8. java.util.concurrent in public API | 0 hits (only in internal engine) | PASS |
| 9. LLM-tells | 0 hits | PASS |

## Deviations from the plan

None. All 5 items (Endpoint rename, Method rename, Context rename, 2nd-overload removal, TestBase rename) were executed as specified.

One minor detail: the plan said "update every caller to use the 2-arg form `(params, _ctx) => body`". All pre-existing production callers already used the 2-arg form. Only the explicit test of the single-arg overload needed updating.

## Compilation results

All three modules compiled with `[success]`:
- `kyo-jsonrpc/Test/compile`: `[success]` (36 s)
- `kyo-jsonrpc-http/Test/compile`: `[success]` (5 s)
- `kyo-browser/Test/compile`: `[success]` (55 s)

Focused test run `kyo-jsonrpc/testOnly *JsonRpcHandler* *JsonRpcRoute*`: 102 tests, 0 failures.
