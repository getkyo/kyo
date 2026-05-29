# Phase 3 Decisions Log

## DEC-P3-001: Scenario tests import `kyo.*` not individual symbols

Plan code blocks show `import kyo.*` for the scenario files in `package kyo.scenario`. The original source files used `package kyo` with explicit imports from the wildcard scope. Since the relocated files switch to `package kyo.scenario`, they need `import kyo.*` to pull in `JsonRpcEndpoint`, `JsonRpcMethod`, etc. This matches the plan's code blocks exactly and is the correct approach.

## DEC-P3-002: `JsonRpcTestBase` resolves cross-package for `kyo.scenario` tests

The four scenario test files are in `package kyo.scenario` and extend `JsonRpcTestBase` which is in `package kyo`. This compiles without an import because `kyo.JsonRpcTestBase` is picked up via `import kyo.*`. Verified clean at compile time.

## DEC-P3-003: Organization check still reports 7 violations post-Phase-3

The `--check test-match` mode reports 7 `8c-missing-test` violations (ExtrasEncoder, HandlerCtx, IdStrategy, JsonRpcEnvelope, JsonRpcError, JsonRpcId, MessageGate have no matching focused test files). These are Phase 4 deliverables, not Phase 3 scope. The 5 orphan-test files that existed before Phase 3 (Test.scala, ScenarioBidiTest, ScenarioHttpStyleTest, ScenarioWsStyleTest, MaxInFlightTest) are now all removed. The `scenario/` subdir is allowlisted by the script. No remediation needed in Phase 3.

## DEC-P3-004: `progressValues` val in BidiTest is unused

The `val progressValues = AtomicRef.Unsafe.init(...)` at line 174 of the original `ScenarioBidiTest.scala` is copied verbatim into `BidiTest.scala`. It is never used in the test body (progress is collected through `pending.progress.run`). The plan instructs "body verbatim from the pre-rename file"; this smell is pre-existing and not introduced by Phase 3. Noted here; do not fix (scope limit).
