# Phase 04 audit

Commit: `f71402d0d`
Plan ref: `kyo-jsonrpc/.flow/api-realignment/design/realignment-plan.md` §Phase D + Resolutions 3, 4, 7
Held-out reference: `kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:82, 95`
Auditor: flow-phase-audit (opus)

## Verdict summary

- BLOCKER: 0
- WARN: 2
- NOTE: 3

Phase 04 is structurally on-plan. The handler-row deviation is acknowledged in `decisions.md`, is functionally equivalent given the `E=Nothing` start + accumulating union, and tracks to Phase 05 for completion. No correctness or wire-protocol risk introduced. Em-dashes are clean across all source files.

## Audit items

### 1. JsonRpcRoute type parameter shape ; OK

Plan: `sealed trait JsonRpcRoute[In, Out, +E]` (Phase D scope, Resolution 4).
Impl: `sealed trait JsonRpcRoute[In, Out, +E]` at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRoute.scala:42`.

Two private concrete impls (`RequestRoute[In, Out, +E]`, `NotificationRoute[In, +E]`) carry the route shape. Variance, type-parameter order, and naming all match the plan exactly. Sealed-trait shape vs. case class is a documented deliberate deviation (decisions.md §JsonRpcRoute type-parameter shape) because the two kinds need different handler signatures (Out vs. Unit). This rationale holds.

### 2. .error[E2] signature ; OK (with NOTE)

Plan (Resolution 4, mirrors `HttpRoute.scala:82`):
```scala
inline def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Out, E | E2]
```

Impl (`JsonRpcRoute.scala:53`):
```scala
def error[E2](using schema: Schema[E2], tag: ConcreteTag[E2])(code: Int, message: String): JsonRpcRoute[In, Out, E | E2]
```

Difference: not `inline`. Rationale in decisions.md §.error[E2] signature is correct: Scala 3 cannot have `inline` abstract members on sealed traits with deferred body; the `using` evidence parameters are resolved at the call site without `inline`. The two concrete impls (`RequestRoute.error` at line ~165, `NotificationRoute.error` at line ~209) provide concrete implementations and could in principle be `inline`, but symmetry with the trait declaration is preferable. **NOTE-1**: confirm in Phase 05 / pre-final-commit sweep whether the concrete impls should be marked `inline` for parity with `HttpRoute.error`; the user-facing surface is identical either way.

### 3. JsonRpcResponse.Halt placement ; OK

Plan (Resolution 3, mirrors `HttpResponse.scala:169-173`):
```scala
case class Halt(response: JsonRpcResponse)
def halt(response: JsonRpcResponse)(using Frame): Nothing < Abort[Halt]
```

Impl (`JsonRpcEnvelope.scala:99, 105`):
```scala
case class Halt(response: JsonRpcResponse)
def halt(response: JsonRpcResponse)(using Frame): Nothing < Abort[Halt] = Abort.fail(Halt(response))
```

Verbatim match. Companion-object location (`object JsonRpcResponse`) matches kyo-http's pattern. Scaladoc references the source line in kyo-http, which is the convention.

### 4. Handler effect row deviation ; WARN (acceptable for Phase 04, must close in Phase 05)

Plan (Resolution 7, mirrors `HttpRoute.scala:95`):
```scala
def handler[E2 >: E](f: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]))
```

Plan's stated row for kyo-jsonrpc:
```scala
def handler[E2 >: E](f: (JsonRpcRequest[In], Context) => JsonRpcResponse[Out] < (Async & Abort[E2 | JsonRpcResponse.Halt | JsonRpcError]))
```

Impl (`JsonRpcRoute.scala:118-120`):
```scala
def apply[In: Schema, Out: Schema](name: String)(
    handler: (In, JsonRpcRoute.Context) => Out < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])
)(using Frame): JsonRpcRoute[In, Out, Nothing]
```

The factory's handler row drops `E` and starts the route at `E = Nothing`. `E` accumulates via `.error[E2]` chaining, producing `JsonRpcRoute[In, Out, E2]`, but the handler closure was already captured at `Abort[JsonRpcError | JsonRpcResponse.Halt]` and is never re-typed.

**Held-out-check observation**: kyo-http does NOT put the handler closure in the constructor. `HttpRoute` is a case class with no handler field; `.handler[E2 >: E](f)` is a separate method that attaches a handler **after** `.error[E2]` chaining has set `E`. This lets the handler row reference the now-known `E`. kyo-jsonrpc's API shape passes the handler at construction, so this trick is not available; the only way to thread `E` into the handler closure would be a separate `.handler[E2 >: E]` attachment step, which would require redesigning the factory as a two-step `JsonRpcRoute.request(name).handler(f)` flow.

**Severity assessment**: ACCEPTABLE for Phase 04, REVISIT in Phase 05.

Justification for ACCEPTABLE:
- `E=Nothing` start with accumulation is functionally equivalent for the **happy path** (handler aborts with `JsonRpcError` or `Halt`, both encoded by the engine).
- The user-domain `E2` registered via `.error[E2]` is stored in `errorMappings` with a `ConcreteTag[E2]` predicate; the engine can intercept `Abort.fail(e: E2)` at dispatch time using `tag.accepts(e)`. This is the path described in decisions.md §Handler effect row.
- The 186/186 test suite (including the 2 new Halt + .error tests) passes.
- No production caller currently chains `.error[E2]` and aborts with `E2` in its handler ; the wiring landing in Phase 05 will exercise that path.

Justification for REVISIT in Phase 05:
- The user-facing ergonomic outcome is currently: `route.error[NotFound](...)` returns `JsonRpcRoute[In, Out, NotFound]`, but the handler closure cannot type-check `Abort.fail(NotFound())` because `NotFound` is not in the closure's effect row. The user must explicitly widen the closure's effect row to `Abort[JsonRpcError | JsonRpcResponse.Halt | NotFound]` and use `Abort.fail[NotFound](...)`. The route's `E` accumulator helps the engine dispatch but does **not** help the user write the handler.
- Phase 05's engine wiring **must** handle `Abort[E]` dispatch via the stored `ErrorMapping[E].matches` ; without this, the `.error[E2]` declaration is decorative.
- Phase 05 should evaluate whether a `.handler` attachment step (mirroring `HttpRoute.handler[E2 >: E]`) is worth introducing to recover full plan parity, OR document the deliberate deviation in a scaladoc block on `JsonRpcRoute` itself explaining that the handler row is fixed and `.error[E2]` is only the engine-side declaration.

**WARN-1**: Phase 05 entry-criterion must include: "(a) engine wiring uses `ErrorMapping[E].matches` for dispatch; (b) `JsonRpcRoute` scaladoc states the handler-row constraint or the design is revised to add a `.handler[E2 >: E]` attachment step". Without (a), `.error[E2]` is dead API. Without (b) or the redesign, the ergonomic gap is undocumented.

### 5. Halt-in-request-handler behavior ; WARN

Impl (`JsonRpcRoute.scala`, `RequestRoute.handle`):
```scala
case Result.Failure(_: JsonRpcResponse.Halt) =>
    Abort.fail(JsonRpcHandlerPanicError(name, new IllegalStateException(
        s"JsonRpcResponse.Halt used in request route '$name' without a gate id"
    )))
```

This is consistent with the comment "Halt used in a request route handler is not meaningful without a gate id". The new test "JsonRpcResponse.Halt aborts with the wrapped response" asserts that `JsonRpcResponse.halt(...)` from inside a route handler produces `JsonRpcHandlerPanicError` (code -32603).

The plan's Resolution 3 quote: "Used with `Abort.fail(Halt(response))` or the convenience `JsonRpcResponse.halt(response)`. When a route or gate aborts with Halt, the framework skips remaining processing and sends the wrapped response directly." Plan-text mentions "route or gate". kyo-http's `HttpResponse.Halt` is documented as being for "filters and handlers"; both layers respond by emitting the wrapped response.

In Phase 04, request-route Halt resolves to a panic, NOT to emitting the wrapped response. The intent appears to be that the Halt machinery is for the gate layer (which carries the request id), and using it in a route handler is a misuse.

**WARN-2**: This is a deviation from the plan's stated semantics ("route or gate aborts with Halt, the framework ... sends the wrapped response directly"). Phase 04 elected the safer panic-on-misuse path because the request-handler scope lacks a gate id to coordinate the response. **The test name "JsonRpcResponse.Halt aborts with the wrapped response" is misleading**: the test actually asserts the Halt is converted to a panic error, NOT that the wrapped response is sent. Phase 05 should either:
- Rename the test to "JsonRpcResponse.Halt in a request handler surfaces as JsonRpcHandlerPanicError" and add a separate test at the gate layer that exercises the actual Halt-to-response path, OR
- Revise the route handler to extract the request id from `ctx.requestId` and emit the wrapped response directly when `ctx.requestId` is Present (matching the plan's stated semantics).

### 6. New tests are concrete-asserting ; OK

Test 1 ("JsonRpcResponse.Halt aborts with the wrapped response"):
- `assert(err.code == -32603)` ; concrete value.
- Falls through to `fail(s"...")` on unexpected branch ; concrete failure message.
- See WARN-2 about the misleading test name; the assertions themselves are concrete.

Test 2 (".error accumulates error mappings on the route"):
- `assert(withError.errorMappings.length == 1)` ; concrete length.
- `assert(withError.errorMappings(0).code == -32001)` ; concrete value.
- `assert(withError.errorMappings(0).message == "Bad request")` ; concrete value.
- `assert(withError.errorMappings(0).matches(AddReq(1, 2)))` ; positive predicate check.
- `assert(!withError.errorMappings(0).matches(AddResp(3)))` ; negative predicate check (good ; covers the false branch).
- `assert(result == Result.Success(Structure.encode(AddResp(5))))` ; concrete value comparison.

Both tests assert specific values, not `nonEmpty` or `isInstanceOf`. They cover the positive AND negative branches of the predicate, and trip the engine-side dispatch (decode + encode round-trip).

### 7. Em-dash check ; CLEAN

`for f in $(commit-files); do count em/en dashes on added lines; done` returns hits ONLY in `kyo-jsonrpc/.flow/api-realignment/phases/phase-03/audit.md` (a prior-phase audit document, unchanged source). Zero em-dashes or en-dashes introduced on added lines in:
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcRoute.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcHandler.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/engine/JsonRpcEndpointImpl.scala`
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`
- All 14 test files (kyo-jsonrpc + kyo-browser)
- `kyo-jsonrpc/.flow/api-realignment/phases/phase-04/decisions.md`
- `kyo-jsonrpc/.flow/api-realignment/phases/phase-04/pulse-1.md`

Pulse-1 separately flagged pre-existing em-dashes in `NavigationWatcher.scala` and `SharedChrome.scala`; those files are NOT changed in this commit (verified by `--name-only`), so they are out of scope for Phase 04 and tracked for a future sweep.

## Cross-cutting checks

### Architecture substitution ; CLEAN
No substitution detected. `[+S]` → `[In, Out, +E]` is the planned change. `case Halt(response)` is placed in `JsonRpcResponse` companion as planned. `ErrorMapping[E]` is new but documented in decisions.md.

### Documentation drift ; CLEAN
Scaladoc on `JsonRpcRoute` updated to reflect the new shape. References to `kyo-http/shared/src/main/scala/kyo/HttpRoute.scala:82` and `:169` are accurate file-line citations (verified by reading HttpRoute.scala:82 and HttpResponse.scala:169 in the held-out check). The `@tparam` block documents `In`, `Out`, `E` correctly. Outdated `@tparam S` is removed.

### Class-C judgment ; flagged in WARN-2
The Halt-in-request-handler decision (panic vs. extract-id-and-emit) is a class-C judgment call: the framework could go either way, and Phase 04 chose the safer panic path. The test name does not reflect what the test asserts. This needs Phase 05 closure.

### Decisions.md completeness ; OK (with NOTE)
Decisions.md documents:
- Type-parameter shape (sealed trait vs. case class) ; with rationale.
- Halt placement ; with kyo-http file-line citation.
- .error[E2] signature ; with rationale for `inline` removal.
- Handler effect row ; with rationale and Phase 05 forward path.
- ErrorMapping ; introduced.
- dispatch signature change ; documented.
- JsonRpcHandler.init signature change ; documented.

**NOTE-2**: Decisions.md §Tests added section names test 1 as "JsonRpcResponse.Halt aborts with the wrapped response: verifies Halt in a route handler is caught and surfaces as JsonRpcHandlerPanicError." The decisions-log description IS accurate. Only the test-case name string in the source file is misleading (see WARN-2). Pre-commit sweep should rename the test string, not the description.

**NOTE-3**: Decisions.md §Deviations from plan enumerates three deviations:
1. `error` is non-inline ; documented, NOTE-1 above tracks Phase 05 follow-up.
2. Handler effect row excludes `E` ; documented, WARN-1 above.
3. `dispatch` lost `[S]` ; documented and structurally correct given the new existential boundary.

All three deviations are flagged in the decisions log and have forward paths. No undocumented deviations detected.

## Phase 05 entry checklist

To close out the Phase 04 deviations and consolidate the audit findings, Phase 05 must:

1. Engine wiring uses `ErrorMapping[E].matches` for `Abort[E]` dispatch (closes WARN-1, part 1).
2. Either (a) revise `JsonRpcRoute` to introduce a `.handler[E2 >: E]` attachment step matching `HttpRoute.handler`, OR (b) add a scaladoc paragraph on `JsonRpcRoute` documenting the fixed handler row + the user-facing pattern of widening the closure's effect row when calling `.error[E2]` (closes WARN-1, part 2).
3. Rename the test "JsonRpcResponse.Halt aborts with the wrapped response" to accurately describe the panic-on-misuse semantics it asserts, AND add a separate test at the gate layer that exercises the actual short-circuit-to-response path (closes WARN-2). The decision to keep panic-on-misuse vs. extract-id-and-emit is itself a Phase 05 judgment call; document the choice in Phase 05 decisions.md.
4. Optionally mark concrete `.error` impls `inline` for symmetry with kyo-http (closes NOTE-1).

If Phase 05 does NOT touch the engine wiring (e.g. if Phase 05 scope is renamed or deferred), then item 1 must move to whichever phase introduces the engine-side dispatch path. Without it, `.error[E2]` is documented API surface with no behavioral effect.

## Conclusion

**Verdict: PASS with WARN.** Zero BLOCKERs. Two WARNs:
- WARN-1: handler-row deviation requires Phase 05 closure (engine wiring + scaladoc/redesign).
- WARN-2: Halt-in-request-handler test name is misleading; Phase 05 must rename and add a gate-layer Halt test.

Three NOTEs are queued for sweep:
- NOTE-1: concrete `.error` impls could be `inline` for parity with kyo-http.
- NOTE-2: test name (source string) does not match decisions.md description (which is accurate); fix the source string.
- NOTE-3: all deviations are documented in decisions.md with forward paths.

The phase is structurally green, the commit is correct, and the deviation severity is acceptable for Phase 04. Phase 05 must consume the entry checklist above.
