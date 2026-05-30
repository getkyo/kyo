# Phase 5 decisions

Decision 1: Single-line edit at `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEndpoint.scala:437`
Change: `JsonRpcEndpoint < (Sync & Async & Scope)` -> `JsonRpcEndpoint < (Async & Scope)`
Rationale: `Async` subsumes `Sync` in kyo's effect hierarchy, so the explicit `Sync &` is redundant. Plan Phase 5 (`design/05-plan.md` §Phase 5; `design/05-plan.yaml` id=5) specifies this as a one-line drop. Aligns with the rest of the `JsonRpcTransport` factory family (`JsonRpcTransport.fromWire`, `JsonRpcTransport.stdio`) which already return `< (Async & Scope)`, and with kyo-http's `HttpServer.init` / `HttpClient.init` (template basis). Two kyo-browser test annotations already pre-declare the target row (`JsonRpcPortInvariantsSpec.scala:49`, `CdpBackendTest.scala:46`), so no downstream type annotations need updating.
Time: 2026-05-30T14:07:00Z

Decision 2: No call-site changes required
Rationale: A pre-edit grep across `kyo-jsonrpc`, `kyo-jsonrpc-http`, and `kyo-browser` for explicit `JsonRpcEndpoint < (Sync & Async & Scope)` type annotations found only the single declaration site itself and two test-file annotations in kyo-browser that already use `JsonRpcEndpoint < (Async & Scope)`. All call sites infer the result type. Post-edit compile + full module test suite confirms no breakage.
Time: 2026-05-30T14:07:30Z

Decision 3: Verification log captured at `phases/phase-05/runs/impl-test-jvm-001.log`
Result: `[success] Total time: 6 s, completed May 30, 2026, 2:07:42 PM` (line 250); `Tests: succeeded 179, failed 0, canceled 0, ignored 0, pending 0`; `All tests passed.` Compile + test passed via `sbt 'kyo-jsonrpc/Test/compile' 'kyo-jsonrpc/test'`.
Time: 2026-05-30T14:07:42Z
