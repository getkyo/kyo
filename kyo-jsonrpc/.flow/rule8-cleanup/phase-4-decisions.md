# Phase 4 Decisions Log

## Files created: 7 new focused test files, 32 cases total

---

### 1. JsonRpcErrorTest.scala (5 cases)

**Design**: Exercises named constants, smart constructors, and Schema round-trip for `JsonRpcError`.

- "RFC code constants match the spec catalog": reads all 11 named `val`s and asserts exact RFC codes. Single test asserts all codes for conciseness without coupling test count to symbol count.
- "methodNotFound stamps the method name into message": verifies string interpolation in the smart constructor. Checks code, message, and data (Absent).
- "invalidRequest, invalidParams, internalError carry reason into data": three constructors in one test since they share the same "reason into data" shape; avoids duplication of boilerplate.
- "cancelled smart constructor reports RequestCancelled with reason": exercises both `Present(reason)` and `Absent` paths in a single test.
- "Schema[JsonRpcError] round-trips through Structure": uses `Structure.encode/decode` round-trip pattern consistent with `JsonRpcResponseTest`.

No mock strategy needed - all assertions operate on pure values.

---

### 2. MessageGateTest.scala (5 cases, plan started at 4 + 1 appended)

**Design**: Exercises the `Decision` ADT and the `MessageGate` trait via inline test-double implementations.

- "Decision values are CanEqual-distinguishable across Allow / Reject / Drop": pairwise `!=` assertions confirming `derives CanEqual` works across all three variants.
- "Reject decision carries the supplied JsonRpcError": pattern-match extraction to confirm field presence inside the `Reject` case.
- "a test-double gate returning Drop reports Drop for any envelope": anonymous inner class implementing `MessageGate`, effect monadic chain via `.map`.
- "a test-double gate returning Allow reports Allow for any envelope": same pattern with a Request envelope.
- "Reject and Drop are structurally distinct from Allow under pattern matching" (appended per plan reconciliation): exhaustive match on `Seq` to confirm pattern-match coverage - pinned as the 5th case to reach count target.

Test doubles: anonymous `new MessageGate` inline; no mocking framework needed since the trait has a single abstract method.

---

### 3. IdStrategyTest.scala (4 cases, plan started at 3 + 1 appended)

**Design**: Exercises `IdStrategyEngine.mkNextId` for all three `IdStrategy` enum cases.

- "SequentialLong allocates monotonically increasing JsonRpcId.Num starting at 1": calls `next()` three times in a for-comprehension, verifies 1, 2, 3.
- "SequentialInt allocates monotonically increasing JsonRpcId.Num starting at 1": two calls, verifies widening to `Long`.
- "Custom forwards verbatim to the supplied next function": uses `AtomicLong.Unsafe.init` at counter=99 to produce 100, 101. Unsafe init is justified by the same pattern as `IdStrategyEngine` itself; comment included.
- "Custom with constant-returning function returns the same id repeatedly" (appended): stateless `Str("static")` lambda confirms the engine does not memoize.

The `IdStrategyEngine` is `private[kyo]`; tests import it as `kyo.internal.IdStrategyEngine` which is accessible from the `kyo` test package.

---

### 4. JsonRpcEnvelopeTest.scala (5 cases)

**Design**: Exercises the four-case `JsonRpcEnvelope` enum for field shape and `CanEqual` distinguishability.

**Adaptation from plan**: The plan code blocks used untyped `val req = JsonRpcEnvelope.Request(...)` and then accessed `.extras`, `.id`, etc. directly. Scala 3 type inference widens the binding to `JsonRpcEnvelope` (the enum base) when the surrounding context has a non-trivial expected type, causing "not a member" errors. Fix: explicit type annotations (`val req: JsonRpcEnvelope.Request = ...`) on the field-access tests only. The CanEqual-distinguishable test keeps `val req: JsonRpcEnvelope = ...` type annotations since it only uses `!=`.

- "Request, Notification, Response, Malformed are CanEqual-distinguishable": pairwise `!=` on all 4 cases.
- "Request preserves the extras field on round-trip": explicit case type annotation to access `.extras`.
- "Notification preserves the extras field on round-trip": same pattern for `Notification`.
- "Response with Present id and Present result is constructible": explicit `JsonRpcEnvelope.Response` type to access `.id`, `.result`, `.error`.
- "Malformed retains both reason and raw payload": explicit `JsonRpcEnvelope.Malformed` type to access `.reason`, `.raw`.

---

### 5. JsonRpcIdTest.scala (5 cases)

**Design**: Exercises the `JsonRpcId` Schema (custom reader/writer) directly.

- "Num case round-trips through Structure": encode + decode symmetry for `Num`.
- "Str case round-trips through Structure": encode + decode symmetry for `Str`.
- "encoding Num produces a numeric Structure value": structural match on `Structure.Value.Integer`.
- "encoding Str produces a string Structure value": structural match on `Structure.Value.Str`.
- "decoding Structure.Value.Null fails": verifies the TypeMismatchException path produces `.isFailure`.

`Structure.Value.Integer` is confirmed to exist in the codebase (used in `JsonRpcCodecImpl`, `MaxInFlightTest`).

---

### 6. HandlerCtxTest.scala (4 cases, plan started at 3 + 1 appended)

**Design**: Exercises `HandlerCtx.forTest` constructor and `progress` dispatch. Uses `AtomicRef.Unsafe` for side-effect capture.

- "progress with a Present sink invokes the captured callback": `AtomicRef.Unsafe.init` stores `List[Structure.Value]`. Sink appends via `updateAndGet`. After `ctx.progress(...)`, `captured.get()` reads back the list. Note: plan code used `captured.update(...)` which does not exist on `AtomicRef.Unsafe`; adapted to `updateAndGet` with a trailing `()` to produce `Unit` for the sink lambda type.
- "progress with an Absent sink is a no-op": Absent sink path completes without exception.
- "extras and requestId are surfaced verbatim from forTest": reads back `requestId` and `extras` fields.
- "cancelled Promise is constructible and not yet completed at forTest exit" (appended): `ctx.cancelled.done` returns `false` on a fresh promise.

The `progressSink` type requires `Unit < (Async & Abort[Closed])`. The sink lambda uses `Sync.defer { updateAndGet(...); () }` to produce `Unit` within the effect type.

---

### 7. ExtrasEncoderTest.scala (4 cases)

**Design**: Exercises the opaque-type companion API (`empty`, `const`, `apply`, `.resolve`).

- "empty.resolve always yields Absent regardless of id": two id variants confirm constant behavior.
- "const(v).resolve always yields Present(v) regardless of id": two id variants confirm constant behavior.
- "apply(f).resolve forwards id to f": verifies `id.toString` is passed through.
- "apply(f) lifts a Sync-effectful body through .resolve": `AtomicLong.Unsafe.init` counter verifies the body runs on each `.resolve` call, not once at construction.

`ExtrasEncoder.apply` is the opaque companion constructor; the extension `.resolve` unwraps the opaque type and applies the stored function.

---

## Summary of plan adaptations

1. `JsonRpcEnvelopeTest`: explicit case-type annotations added to field-access tests because Scala 3 infers the base `JsonRpcEnvelope` type in the generic `run { }` context, blocking access to case-specific members.
2. `HandlerCtxTest`: `captured.update(...)` from the plan does not exist on `AtomicRef.Unsafe`; replaced with `updateAndGet(...)` and trailing `()` to produce `Unit`.
3. Both adaptations are minimal and faithful to the plan's intent; test semantics are preserved exactly.
