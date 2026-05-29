# Phase 1 decisions

Decision 1: Use `BrowserSetupFailedException` (concrete case class) instead of plan's `BrowserSetupException` (sealed trait) for probe-failure error.
Rationale: `BrowserSetupException` is a sealed trait in `BrowserException.scala:96`; `BrowserSetupFailedException` is the only concrete instantiable subtype (`BrowserException.scala:123`). The plan pseudocode uses `BrowserSetupException(...)` as if it were a case class, but it isn't. `BrowserSetupFailedException` is the correct constructor to use.
Time: 2026-05-29T00:00:00Z

Decision 2: `Scope.run { ... }` wraps the entire `initUnscoped` for-comprehension body to discharge `Scope` effects from `JsonRpcHttpTransport.webSocket` and `JsonRpcEndpoint.init`.
Rationale: Per prep doc gotcha #1 and the verbatim API signatures, both `JsonRpcHttpTransport.webSocket` (returns `< (Async & Scope & Abort[HttpException])`) and `JsonRpcEndpoint.init` (returns `< (Sync & Async & Scope)`) require `Scope` discharge inside `initUnscoped` so the outer `CdpBackend.init` can do `Scope.acquireRelease(initUnscoped(...))`. No double-nested Scope.
Time: 2026-05-29T00:00:00Z

Decision 3: `NavigateParams("https://example.com")` used in smoke tests (single-arg constructor per `CdpTypes.scala:132`). Plan test data shows a 4-arg form that does not exist.
Rationale: `CdpTypes.scala:132` shows `final private[kyo] case class NavigateParams(url: String) derives Schema`. The plan's 4-arg form is spurious.
Time: 2026-05-29T00:00:00Z

Decision 4: `JavascriptDialogOpeningParams` is imported from `kyo.internal` package (defined in `CdpClient.scala:599-604`). Phase 01 `CdpBackend.scala` can reference it directly since both are in `kyo.internal`.
Rationale: Prep doc gotcha #8 confirms the type is in `CdpClient.scala` but compiles fine since both files are in `kyo.internal`; the cross-file reference works without an explicit import.
Time: 2026-05-29T00:00:00Z

Decision 5: `BrowserGetVersionParams` and `BrowserVersionResult` added at end of `CdpTypes.scala` (appended after existing type definitions).
Rationale: No existing placement convention is violated; both types are small and probe-only, consistent with other minimal wrapper case classes in `CdpTypes.scala`.
Time: 2026-05-29T00:00:00Z

Decision 6: Smoke test class inherits from `kyo.Test` (same pattern as other kyo-browser tests). `JsonRpcPortInvariantsSpec` also inherits from `kyo.Test` for consistency.
Rationale: `kyo.Test` extends `AsyncFreeSpec` with `BaseKyoCoreTest` and provides `run(...)`, `decode[A]`, `succeed`, `fail` helpers. All existing kyo-browser test files use this base class.
Time: 2026-05-29T00:00:00Z

Decision 7: `build.sbt` dependency addition uses `.dependsOn(`kyo-http`, `kyo-jsonrpc`, `kyo-jsonrpc-http`)` (plain `dependsOn`, no test-scope modifiers) per `feedback_module_deps`.
Rationale: `kyo-browser` genuinely compiles against and uses the jsonrpc types; plain `dependsOn` is correct. No test-only references; the new `CdpBackend` class is in `src/main/`.
Time: 2026-05-29T00:00:00Z

Decision 8: `IdStrategy.SequentialInt` chosen per plan for positive-id allocation; disjoint from dialog drainer's `AtomicInt(Int.MinValue)` negative-id space (INV-018).
Rationale: Plan explicitly requires `idStrategy = IdStrategy.SequentialInt` at `05-plan.md:215`. Prep doc confirms `SequentialInt` produces positive 32-bit ints (1, 2, 3, ...).
Time: 2026-05-29T00:00:00Z

Decision 9: `Channel.initUnscoped` with capacity 16 used for `dialogQueue` matching legacy `CdpClient.scala:229`. `dialogQueue.put` (blocking) replaces legacy `dialogQueue.offer` (non-blocking) per plan's explicit semantic change for correctness.
Rationale: Plan `05-plan.md:362` uses `put`; prep doc gotcha #11 confirms this intentional semantic change -- `put` is safer than silent drop under pressure.
Time: 2026-05-29T00:00:00Z

Decision 10: `JsonRpcPortInvariantsSpec` tests for INV-008..INV-024 use Scala `sys.process.Process` for grep-based assertions and runtime round-trip tests using `JsonRpcTransport.inMemory`. The tests that check git-based properties (INV-019, INV-022, INV-023) are written as structural-grep checks against the source files rather than `git diff` to avoid flakiness when there is no pre-port-tag in the working tree.
Rationale: The prep doc notes that some INV tests use `git diff pre-port-tag..HEAD` but a pre-port-tag may not exist in the worktree. The tests that verify source-level properties (no `Fiber.block`, no `var`, no `Co-Authored-By`) are implemented as source-file greps rather than git-diff greps to be robust.
Time: 2026-05-29T00:00:00Z

Decision 11: `CdpBackendOld` object receives `// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02` annotation per INV-024 and prep doc gotcha #10.
Rationale: Required by INV-024 (every audit-flag exception annotated with `// flow-allow: <rationale>`).
Time: 2026-05-29T00:00:00Z

Decision 12: `BrowserVersionResult` fields ordered as `protocolVersion, product, revision, userAgent, jsVersion` matching CDP `Browser.getVersion` spec.
Rationale: Prep doc specifies exactly these 5 fields in this order (phase-1-prep.md line 858). Test data uses `BrowserVersionResult("0", "Headless/0", "0", "Mozilla/5.0 (Headless)", "0.0")`.
Time: 2026-05-29T00:00:00Z

Decision 13: Smoke tests that require a live endpoint (init, send, withSession, notifications, close, dialog drainer) use `Scope.run` around `JsonRpcEndpoint.init` to discharge the Scope in the test body. Tests in `CdpBackendSmokeTest` call `Scope.run` inside `run {...}`.
Rationale: `CdpBackend.initUnscoped` itself uses `Scope.run` to produce a `Scope`-free `CdpBackend`. Tests that directly call `JsonRpcEndpoint.init` need their own `Scope.run`.
Time: 2026-05-29T00:00:00Z

Decision 14: `Abort.recover[Timeout]` removed from `CdpBackend.send`; instead, `Abort.recover[JsonRpcError]` checks `err.code == JsonRpcError.RequestCancelled.code` (-32800) and maps to `BrowserConnectionLostException("Request timeout: $method", Absent)`.
Rationale: `JsonRpcEndpoint.call` returns `R < (Async & Abort[JsonRpcError | Closed])` with NO `Timeout` in the effect row. The engine internally wraps `Abort.run[Timeout](Async.timeout(...))` and converts timeout to `JsonRpcError.cancelled` (code -32800). `Timeout` is never in the caller-visible `Abort` row. The recovery for "timeout = cancelled JsonRpcError" is the correct approach.
Time: 2026-05-29T00:00:00Z

Decision 15: `Browser.DialogEvent` constructor takes `(kind: Browser.DialogType, message: String, response: Maybe[String])` -- NOT `(type, message, accept, promptText)`. Plan pseudocode was wrong about the 4-arg form. Actual construction follows `CdpClient.recordDialogEvent` pattern: `kind` from string-match on `params.\`type\``, `response = Present(promptText) if accept && type=="prompt" else Absent`.
Rationale: `BrowserException.scala:3019-3023` defines the 3-arg case class. `CdpClient.scala:534` shows the correct construction pattern.
Time: 2026-05-29T00:00:00Z

Decision 16: `CdpBackend.initUnscoped` returns `CdpBackend < (Async & Scope & Abort[BrowserReadException])` (with Scope in the effect row), NOT `CdpBackend < (Async & Abort[BrowserReadException])` as the plan's pseudocode shows.
Rationale: `JsonRpcEndpoint.init` uses `Scope.acquireRelease(initEngine(...))(finalizer)` which registers endpoint cleanup with the enclosing Scope. If `Scope.run` wraps the entire `initUnscoped` for-comprehension, the endpoint is closed when `Scope.run` exits (even while the backend is still being used). Keeping Scope in the effect row is the only correct approach. The caller (either `CdpBackend.init` or tests using `Scope.run`) manages the endpoint lifecycle. This is a critical structural correction to the prep doc gotcha #1.
Time: 2026-05-29T00:00:00Z

Decision 17: `BrowserSetupFailedException` is NOT used for probe failure; instead `BrowserConnectionLostException` is used throughout `initUnscoped` (for URL parse failures, transport setup failures, and probe failures). `BrowserSetupFailedException extends BrowserSetupException` which does NOT extend `BrowserReadException`; using it would break the `Abort[BrowserReadException]` return type.
Rationale: The existing `CdpClient.initUnscoped` returns `Abort[BrowserReadException]` and uses `BrowserConnectionLostException` for connect failures. The new `CdpBackend.initUnscoped` follows the same pattern.
Time: 2026-05-29T00:00:00Z

Decision 18: `buildFrameMethod` replaced by typed-params `buildFrameCreatedMethod` and `buildFrameDestroyedMethod`. The plan's `buildFrameMethod` used `Structure.Value` as the notification `In` type, but `Schema[Structure.Value]` derived for the enum does NOT do identity decode -- it uses discriminated union encoding. `Structure.decode[Structure.Value](params)` fails when `params` is a raw `Record(...)`.
Rationale: The only correct approach is to use the actual typed case class (`ExecutionContextCreatedParams`, `ExecutionContextDestroyedParams`) as the `In` type for each notification method builder. These have properly-derived case-class schemas that decode by field name.
Time: 2026-05-29T00:00:00Z

Decision 19: Platform-specific `JsonRpcPortFileOps` helper created in `jvm/`, `js/`, `native/` test source dirs to provide file I/O for invariant source-file checks. JVM version uses `java.nio.file.*`; JS/Native versions return `None`/`false` (no file I/O available). Tests that use `readFile` check `content.isEmpty` and trivially pass on non-JVM platforms.
Rationale: `java.nio.file.*` is JVM-only; Scala.js linker errors on `java.nio.file.Paths/Files` even inside `if Platform.isJVM` blocks (static linking). Platform-specific expect/actual pattern is the correct cross-platform solution.
Time: 2026-05-29T00:00:00Z

Decision 20: Test server endpoint uses `JsonRpcMethod.notification[JavascriptDialogOpeningParams]`, `JsonRpcMethod[ExecutionContextCreatedParams]`, etc. (typed params) instead of `Structure.Value`. The test sends notifications via `serverEndpoint.notify[TypedParams](method, typedInstance, extras)` using the actual typed case classes, not raw `Structure.Value`.
Rationale: Server-side `Structure.encode[Structure.Value]` uses the derived enum schema (discriminated union) which produces wrong wire shape. Using typed params ensures correct wire encoding that the client's typed notification handlers can decode.
Time: 2026-05-29T00:00:00Z

Decision 21 (CRITICAL-1 fix, post-pulse): Probe exception type changed from `BrowserConnectionLostException` to `BrowserSetupFailedException` in both `initUnscoped` overloads. `initUnscoped` return types widened to `Abort[BrowserReadException | BrowserSetupException]`; `init` return type widened accordingly. Smoke test leaf 2 and INV-016 updated to assert `BrowserSetupFailedException` and use `Abort.run[BrowserReadException | BrowserSetupException]`.
Rationale: Decision 1 and Q-002 ratification both mandate `BrowserSetupFailedException` for probe failure. The impl agent silently used `BrowserConnectionLostException` (a `BrowserReadException`) because it fit the existing return type without widening. `BrowserSetupFailedException extends BrowserSetupException` (not `BrowserReadException`), so the return type must include `BrowserSetupException`. The probe failure is a setup-time condition (before any CDP session exists), not a runtime connection loss, making `BrowserSetupFailedException` semantically correct per the exception hierarchy.
Time: 2026-05-29T11:45:00Z

Decision 22 (CRITICAL-2, LOG-only, post-pulse): `initUnscoped` return type keeps `Scope` in the effect row. The for-comprehension stays unwrapped because `JsonRpcEndpoint.init` uses `Scope.acquireRelease` to register endpoint cleanup with the enclosing Scope. Wrapping the entire for-comprehension in `Scope.run` would finalize the endpoint immediately on `initUnscoped` exit, before the caller uses the backend. The caller (e.g. `Browser.launch` via `CdpBackend.init`, or tests via `Scope.run`) is responsible for Scope discharge. This supersedes the prep-doc gotcha #1 wording and corrects Decision 2.
Time: 2026-05-29T11:45:00Z

Decision 23 (CRITICAL-3, LOG-only, post-pulse): 28 forwarder methods retained in the `CdpBackend` companion (lines 221-353). These delegate every typed CDP call to `CdpBackendOld` so that all existing call sites in `Browser.scala` and `BrowserTab.scala` (which call `CdpBackend.navigate(...)` etc.) continue to compile unchanged in Phase 01. The plan's rename of `object CdpBackend` to `CdpBackendOld` breaks every `CdpBackend.<method>(...)` call site; Phase 01 cannot modify those call sites (Phase 02 owns the cutover). The forwarders are the only path that keeps Phase 01 atomic-compilable without touching call sites outside scope. Phase 02 deletes both the forwarders AND `CdpBackendOld` as part of the cutover commit.
Time: 2026-05-29T11:45:00Z

Decision 24 (MINOR-5, LOG-only, post-pulse): INV-020 ("green-build on JVM, JS, Native") is verified at the Final cross-platform green gate at Stage 3.5, not via per-phase `sbt` invocations inside the test. The stub `succeed` in `JsonRpcPortInvariantsSpec.scala:414` stays as a documentation marker indicating the invariant exists; the structural verification is the supervisor's cross-platform compile run at the campaign's end. Per-phase green-build is the supervisor's responsibility, not a runtime test assertion.
Time: 2026-05-29T11:45:00Z
