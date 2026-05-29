# 04 Invariants ledger: Port kyo-browser CDP client to kyo-jsonrpc

Feature: kyo-browser jsonrpc-port
Task type: migration
Cites design: ./02-design.md §10 (Cross-cutting invariants), §11 (per-phase scope/acceptance), §9 (semantic risks A/B), §6 (API surface), §7 (Package surface verdicts), §8 (Per-sessionId routing), §3-§5 (architecture).
Cites resolutions: ./03a-open-resolutions.md, ./03b-user-escalations.md (Q-002 RATIFIED option b).

Phase index (from design §11):
- Phase 01: scaffold new `CdpBackend` runtime class behind feature parity; add kyo-jsonrpc / kyo-jsonrpc-http dep.
- Phase 02: cut over production call sites; delete `CdpClient.scala`.
- Phase 03: delete `CdpWire` wire-envelope helpers; rename / delete wire-layer tests (net -32 test cases).
- Phase 04: conditional defensive backports to kyo-jsonrpc-http (probability low; may contribute zero commits).
- Phase 05: cross-platform sweep; final cleanup; test-suite parity verification.

All cross-phase contracts in the design §10 are reified below as INV-NNN blocks with explicit `produced_by` / `consumed_by` / `smoke_test_path`. Categories required by the supervisor's prompt are covered:
- API stability (INV-001, INV-002, INV-003)
- Test-count stability and platform parity (INV-004, INV-005, INV-006)
- Stability-layer byte-equivalence (INV-007)
- Rule 8c (source + matching focused test in same phase commit) (INV-008)
- Rule 8a/8b (file basename matches sole top-level type; `package kyo` public-only; `kyo.internal` for impl detail) (INV-009)
- No backwards-compat / no shims (INV-010)
- No manual JSON (INV-011)
- No `var` for shared state under `package kyo` / `package kyo.internal` (INV-012)
- Side effects in `Sync.defer`; `Sync.Unsafe.*` only with `// Unsafe:` justification (INV-013)
- No em-dashes / no LLM-tells (INV-014)
- Per-sessionId routing via `ExtrasEncoder` + `JsonRpcCodec.Cdp` (INV-015)
- WS-connect failure surfacing via `Browser.getVersion` probe (Q-002 ratification) (INV-016)
- Typed error recovery at the `CdpBackend.send` boundary (INV-017)
- Negative-id sentinel disjoint from `IdStrategy.SequentialInt` positive space (INV-018)
- No `Fiber.block` (INV-019)
- Phase commits are green-build on JVM, JS, Native (INV-020)
- Per-platform test runs SEQUENTIAL during validation (INV-021)
- No Co-Authored-By in commit trailers (INV-022)
- No `git push` from the campaign agent (INV-023)
- `flow-allow:` rationale annotations carried for every audit-flag exception (INV-024)

Verification gates name exact grep / sbt invocations or a phase-boundary smoke test under `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala`. Where the design lists an `Acceptance` grep verbatim (design §11), the same grep is reused.

---

INV-001: `kyo.Browser` public method / val / enum / case-class signatures are byte-identical pre-port vs post-port.
  produced_by: Phase 02
  consumed_by: Phase 03, Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-001
  verification_gate: `git show pre-port-tag:kyo-browser/shared/src/main/scala/kyo/Browser.scala | grep -nE 'class|object|def|val|enum|case class'` byte-equal to the same grep on HEAD (design §6 "Proof technique", design §11 Phase 02 Acceptance line 944-948).
  rationale: design §6 (API delta proof public surface is byte-identical), design §10 INV-002.

INV-002: `kyo.BrowserException` ADT (`BrowserReadException`, `BrowserProtocolErrorException`, `BrowserConnectionLostException`, `BrowserDecodingException`, `BrowserSetupException`) keeps every exception case class shape byte-identical.
  produced_by: Phase 02
  consumed_by: Phase 03, Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-002
  verification_gate: `grep -nE 'class|object|def|val|enum|case class' kyo-browser/shared/src/main/scala/kyo/BrowserException.scala` byte-equal to pre-port HEAD; INV-002 smoke also instantiates each ADT variant via reflection-free constructor signature to detect arity drift.
  rationale: design §6 (BrowserException.scala — No changes), design §10 INV-002.

INV-003: `kyo.BrowserTab` / `kyo.BrowserSnapshot` public method signatures stay byte-identical; only the private[kyo] field rename `client: CdpClient` -> `backend: CdpBackend` is permitted (private to kyo, not user-visible).
  produced_by: Phase 02
  consumed_by: Phase 03, Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-003
  verification_gate: `grep -nE '^[[:space:]]*(def|val|enum|case class|object|class)\b' kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala kyo-browser/shared/src/main/scala/kyo/internal/BrowserSnapshot.scala` filtered to public symbols (no `private[`) byte-equal to pre-port HEAD.
  rationale: design §6 ("This is a refactor: zero new public API"), §7 (BrowserTab.scala: KEEP-with-rename ; only one field rename).

INV-004: Cross-platform test-count parity. After Phase 03 completes, shared test cases = 1276 (= 1308 pre-port - 32 net deletion, per Q-005 / design §11 Phase 03 Acceptance line 1010-1024); platform-specific tests = 4 per platform. All 1276 + 4 cases green on JVM, JS, and Native.
  produced_by: Phase 03
  consumed_by: Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-004
  verification_gate: Phase 03 commit message MUST enumerate every deleted test case against an engine-side replacement (design §11 Phase 03 Acceptance line 1022-1024, design §12 "Per-test test-count drift" mitigation line 1204-1209). `flow-verify` at Phase 03 boundary validates the test-count accounting against the prose enumeration. Concretely: `sbt 'kyo-browserJVM/Test/test'` reports 1276 shared + 4 jvm passing; `sbt 'kyo-browserJS/Test/test'` reports 1276 + 4 js passing; `sbt 'kyo-browserNative/Test/test'` reports 1276 + 4 native passing.
  rationale: design §11 Phase 03 (Q-005 resolution), design §10 INV-009.

INV-005: Every phase that declares `platforms: [jvm, js, native]` runs the full configured test suite on JVM, JS, and Native sequentially before the phase commits. Phases 01, 02, 03, 04 (if executed), 05 all carry this declaration.
  produced_by: Phase 01
  consumed_by: Phase 02, Phase 03, Phase 04, Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-005
  verification_gate: Phase commit message includes the literal triple `JVM: <count> green`, `JS: <count> green`, `Native: <count> green` lines, each preceded by the corresponding `sbt kyo-browser{JVM,JS,Native}/Test` invocation log. Smoke test grep: `git log -1 --format=%B | grep -E '^JVM:.*green$' && git log -1 --format=%B | grep -E '^JS:.*green$' && git log -1 --format=%B | grep -E '^Native:.*green$'`.
  rationale: design §11 (every phase block declares `platforms: [jvm, js, native]`), feedback_all_platforms_all_tests.

INV-006: No test file is demoted out of `kyo-browser/shared/src/test/scala/kyo/` into a platform-specific source root (`jvm/`, `js/`, or `native/`) during the campaign. Tests stay shared.
  produced_by: Phase 03
  consumed_by: Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-006
  verification_gate: `git diff --stat pre-port-tag..HEAD -- kyo-browser/jvm/src/test/ kyo-browser/js/src/test/ kyo-browser/native/src/test/` reports zero added/modified test files beyond the existing 4 platform-specific cases.
  rationale: design §10 INV-003, feedback_all_platforms_all_tests.

INV-007: Stability-layer files are byte-identical post-port. The files are: `BrowserNetworkTracker.scala`, `MutationSettlement.scala`, `NavigationWatcher.scala`, `StabilitySampler.scala`, `Actionability.scala`, `SharedChrome.scala`, `BrowserLauncher.scala` (+ `BrowserLauncherPlatform.scala`), `Resolver.scala`, `Selector.scala`, `JsStringUtil.scala`, `IFrame.scala`, `Image.scala`, `Key.scala`, `KeyModifiers.scala`, `ChromeDownloader.scala`, `CookieWire.scala`, `ProbesJs.scala`, `internal/cdp/Accessibility.scala`. Each file's SHA256 pre-port equals its SHA256 post-port.
  produced_by: Phase 02
  consumed_by: Phase 03, Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-007
  verification_gate: `for f in BrowserNetworkTracker MutationSettlement NavigationWatcher StabilitySampler Actionability SharedChrome BrowserLauncher Resolver Selector JsStringUtil IFrame Image Key KeyModifiers ChromeDownloader CookieWire ProbesJs; do diff <(git show pre-port-tag:kyo-browser/shared/src/main/scala/kyo/internal/$f.scala) <(cat kyo-browser/shared/src/main/scala/kyo/internal/$f.scala) || exit 1; done; diff <(git show pre-port-tag:kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala) <(cat kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala)`. Phase 05's package audit reruns this gate as a one-line invocation.
  rationale: design §5 (test-stability layer is invisible to the port; only call `BrowserEval.evalJs`), §7 ("Non-CDP internal files (the test-stability layer) are NOT touched by the port; their verdicts are KEEP-AS-IS"), §10 (preservation proof per stability primitive).

INV-008: Rule 8c — source and matching focused test land in the SAME phase commit. For each phase: every source file added or rewritten in `kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala` (and any helper in the same set) has a matching test file added or rewritten in the same commit.
  produced_by: Phase 01, Phase 02, Phase 03
  consumed_by: Phase 05 (final audit)
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-008
  verification_gate: for each commit C from Phase 01..03: `git show --name-only --format= C` listed sources `Cdp*.scala` under `shared/src/main/scala/kyo/internal/` are each matched by a same-commit `Cdp*Test.scala` or `Cdp*Spec.scala` under `shared/src/test/scala/kyo/internal/` (or the design's enumerated equivalent: `CdpBackendSmokeTest`, `CdpBackendTest`, `CdpBackendLifecycleTest`, `CdpParamsRoundTripTest`, `CdpEvalDecoderTest`, `CdpTypesTest`, `CdpTypesSchemaFailureTest`, `BrowserWireDecodeFailureTest`).
  rationale: design §11 Phase 01 (new `CdpBackend` runtime class exercised by NEW test file `CdpBackendSmokeTest.scala` in same phase), feedback_test_placement, Rule 8c.

INV-009: Rule 8a/8b — file basename matches the sole top-level type at `package kyo` / `package kyo.internal`; `package kyo` is public-only (no implementation detail); `package kyo.internal` carries the impl. `CdpBackend.scala` declares `private[kyo] final class CdpBackend` as its sole top-level type post-Phase-02; Phase 01's temporary co-existence of `CdpBackend` and the old `CdpClient` is in `CdpClient.scala` and `CdpBackend.scala` respectively (one top-level type per file).
  produced_by: Phase 01, Phase 02
  consumed_by: Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-009
  verification_gate: `for f in kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala; do head -1 "$f" | grep -q '^package kyo\.internal$' || { echo "FAIL: $f not package kyo.internal"; exit 1; }; done`. Plus: in HEAD post-Phase-02, `grep -lE '^(final class|object|trait|sealed trait|enum) ' kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` matches the basename rule (sole top-level type `CdpBackend`).
  rationale: design §7 Package surface verdicts (internal/Cdp*.scala enumeration), feedback_kyo_package.

INV-010: No backwards-compat artifacts. The Phase 01 temporary co-existence of `CdpClient` and `CdpBackend` is permitted within Phase 01 only; Phase 02 deletes `CdpClient.scala` (627 LoC) and removes the `CdpClient.init` companion. No shim, parallel API, deprecation alias, or overload-for-migration survives past Phase 02. The `CdpSender` trait is deleted in Phase 02. The `decodeOrFail` helper is deleted in Phase 02.
  produced_by: Phase 02
  consumed_by: Phase 03, Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-010
  verification_gate: post-Phase-02 `grep -lE 'CdpClient|CdpEnvelope|CdpWireMessage|CdpReply|CdpEventParams|FallbackIdEnvelope|CdpSender|decodeOrFail' kyo-browser/` returns ZERO matches (design §11 Phase 02 Acceptance line 949-953, Phase 03 Acceptance line 1025-1027, Phase 05 dead-code grep line 1052-1055). Plus: no source file under `kyo-browser/shared/src/main/scala/kyo/` carries the strings `@deprecated`, `// shim`, `// migration`, `legacy` introduced by this campaign.
  rationale: design §11 Phase 01 / 02 (`feedback_no_backcompat: no shims, no parallel surfaces`), feedback_no_backcompat.

INV-011: No manual JSON. All CDP wire payloads use `derives Schema` + `Json.encode` / `Json.decode`; no string concatenation, regex parsing, or hand-rolled JSON assembly is introduced or retained in `kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala`. The CDP codec dispatch routes per-sessionId through `ExtrasEncoder` + `JsonRpcCodec.Cdp`, not a hand-rolled dispatcher.
  produced_by: Phase 01, Phase 02
  consumed_by: Phase 03, Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-011
  verification_gate: post-port `grep -rnE 'Json\.parseString|String\.format.*"\{|\\"jsonrpc\\"|Pattern\.compile.*\\{' kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala` returns ZERO matches; `grep -rE 'derives (Schema|Json)' kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala` shows only `derives Schema` (no `derives Json`). Per INV-015, dispatch is via `JsonRpcMethod.notification` registrations consuming `ctx.extras` for sessionId, not a hand-rolled `decodeCdpMessage`.
  rationale: design §10 INV-007 (no `derives Json`; CDP wire payloads use `derives Schema` + `Json.encode/decode`), feedback_no_manual_json.

INV-012: No `var` for shared state under `package kyo` / `package kyo.internal` introduced by this campaign. Mutable cross-fiber state lives in `AtomicRef` / `AtomicInt` / `AtomicBoolean`. The CdpBackend fields `dialogHandlers`, `dialogQueue`, `frameEventDispatchers`, `downloadEventDispatchers`, `dialogRecorders`, `lastEvaluateParams`, `dialogIdCounter`, `sessionId` are either Atomic primitives or value-typed.
  produced_by: Phase 01, Phase 02
  consumed_by: Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-012
  verification_gate: `git diff pre-port-tag..HEAD -- kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala | grep -nE '^\+[[:space:]]*(private )?var [a-zA-Z]'` returns ZERO added `var` declarations for shared state (a same-line annotated `// flow-allow: <rationale>` is the documented exception; per INV-024 these must carry rationale).
  rationale: design §10 INV-014, feedback_atomic_not_var.

INV-013: Side effects sit inside `Sync.defer`; `Sync.Unsafe.*` only with a `// Unsafe:` justification comment. The Phase 01 wiring (notification handler construction, dispatcher-table init) uses `Sync.defer` for the `JsonRpcMethod.notification` builders (design §9 wiring pseudocode lines 455-488). No new `AllowUnsafe` or `Frame.internal` site introduced; no new `Sync.Unsafe.*` call without a `// Unsafe:` line.
  produced_by: Phase 01, Phase 02
  consumed_by: Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-013
  verification_gate: `git diff pre-port-tag..HEAD -- kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala | grep -nE '^\+.*(AllowUnsafe|Frame\.internal|Sync\.Unsafe\.)'` ; each match must be on a same-or-preceding-line `// Unsafe: <rationale>` comment, otherwise the gate FAILs.
  rationale: design §9 wiring pseudocode (uses `Sync.defer(JsonRpcMethod.notification[...](...))`), feedback_no_unsafe, design §10 INV-005.

INV-014: No em-dashes and no LLM-tells anywhere in the diff. The campaign's added lines never contain `—` (U+2014) or `–` (U+2013).
  produced_by: Phase 01, Phase 02, Phase 03, Phase 04 (if executed), Phase 05
  consumed_by: flow-validate's LLM-tells gate at each phase boundary; flow-strip-dev at campaign end.
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-014
  verification_gate: `git diff pre-port-tag..HEAD | grep -nP '^\+.*[\x{2014}\x{2013}]'` returns ZERO matches (every phase commit).
  rationale: design §10 INV-006, feedback_no_em_dashes.

INV-015: Per-sessionId routing flows through `ExtrasEncoder` + `JsonRpcCodec.Cdp`, NOT a hand-rolled dispatcher. Outbound: session-scoped `CdpBackend.send` attaches `ExtrasEncoder.const(Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value))))` and the CDP codec inlines it at the wire's top level. Inbound: each `JsonRpcMethod.notification[In]` handler reads `ctx.extras` for `"sessionId"` and demultiplexes via the kyo-browser-owned dispatcher tables (`frameEventDispatchers` / `downloadEventDispatchers` / `dialogHandlers`). The old `CdpClient.decodeCdpMessage` hand-rolled router is deleted in Phase 02.
  produced_by: Phase 01, Phase 02
  consumed_by: Phase 03 (lifecycle test rewrite assumes this routing path), Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-015
  verification_gate: post-Phase-02 `grep -nE 'def decodeCdpMessage|fallbackDecode' kyo-browser/shared/src/main/scala/kyo/` returns ZERO matches; AND `grep -nE 'ExtrasEncoder\.const|ctx\.extras' kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` returns at least one outbound `ExtrasEncoder.const` site and one inbound `ctx.extras` read site (the `readSessionIdFromExtras` helper in design §8 lines 634-641). Plus: a `JsonRpcPortInvariantsSpec.scala::INV-015` smoke test round-trips a `sessionId`-stamped call through `JsonRpcTransport.inMemory` and asserts the inbound notification handler observes the sessionId in `ctx.extras`.
  rationale: design §3 (architecture overview), §8 (Per-sessionId routing strategy), §5 wiring (5 notification handlers consume `ctx.extras`).

INV-016: WS-connect failure surfacing goes through a `Browser.getVersion` probe in `CdpBackend.initUnscoped`. The probe call recovers `Closed` to `BrowserSetupException`. `JsonRpcHttpTransport.webSocket` is NOT modified.
  produced_by: Phase 01
  consumed_by: Phase 02 (production cutover assumes this init contract), Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-016
  verification_gate: `grep -nE '"Browser\.getVersion"' kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` returns at least one match inside `initUnscoped`; AND `git diff pre-port-tag..HEAD -- kyo-jsonrpc-http/shared/src/main/scala/kyo/JsonRpcHttpTransport.scala` returns an empty diff (unless Phase 04 fires and the patch is documented; Phase 04 is conditional, so the default expectation is empty). Smoke: spin up a `JsonRpcTransport.inMemory` that fails the probe call (returns `Closed`) and assert `CdpBackend.initUnscoped` surfaces `BrowserSetupException` typed-error.
  rationale: design §9.A (WS-connect failure surfacing, RI-002 / Q-002), 03b-user-escalations.md §1 (Q-002 RATIFIED option b per `feedback_no_backcompat`).

INV-017: Typed `Abort` recovery for `Closed`, `JsonRpcError`, and `Timeout` at the `CdpBackend.send` boundary; no panics escape into kyo-browser callers. Mapping: `Closed -> BrowserConnectionLostException`; `JsonRpcError -> BrowserProtocolErrorException`; `Timeout -> BrowserConnectionLostException`. Same three branches `CdpClient.submit` performs at `CdpClient.scala:93-104`.
  produced_by: Phase 01, Phase 02
  consumed_by: Phase 03 (`BrowserWireDecodeFailureTest` rewrite assumes this routing), Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-017
  verification_gate: `grep -nE 'Abort\.recover\[(Closed|JsonRpcError|Timeout)\]' kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` returns three or more matches; smoke test drives each error type through `JsonRpcTransport.inMemory` and asserts the typed `Browser*Exception` surfaces.
  rationale: design §6 (`CdpBackend.send` signature: `Async & Abort[BrowserReadException]`), §9 wiring (`CdpBackend.send` body Abort.recover stack lines 562-572), Q-006 resolution (design §15), design §10 INV-013.

INV-018: Negative-id sentinel for fire-and-forget dialog responses stays disjoint from `IdStrategy.SequentialInt`'s positive Int allocator. The dialog drainer uses `JsonRpcId.Num(negCounterValue)` via `endpoint.sendUnmatched`; the engine writes the caller-supplied id to the wire as-is. `dialogIdCounter` starts at `Int.MinValue` and is incremented per dialog response.
  produced_by: Phase 01
  consumed_by: Phase 03 (`CdpBackendLifecycleTest` cases probing the dialog drainer fire-and-forget invariant), Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-018
  verification_gate: `grep -nE 'AtomicInt\.init\(Int\.MinValue\)' kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` returns at least one match; smoke test exercises `dialogIdCounter.getAndIncrement` from `Int.MinValue` and asserts (a) the produced id is negative and (b) `IdStrategy.SequentialInt`'s next allocation is positive (disjointness).
  rationale: design §10 last bullet ("Negative-id sentinel for fire-and-forget dialog responses"), §12 ("`SequentialInt` id overflow vs negative dialog ids"), Q-007 resolution.

INV-019: No `Fiber.block` introduced anywhere in the campaign diff; awaits go through `safe.get` / `onComplete`.
  produced_by: Phase 01, Phase 02
  consumed_by: Phase 05
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-019
  verification_gate: `git diff pre-port-tag..HEAD -- kyo-browser/ | grep -nE '^\+.*Fiber\.block'` returns ZERO matches.
  rationale: design §10 INV-015, feedback_no_fiber_block.

INV-020: Each phase commit is green-build on JVM, JS, and Native (compile + targeted tests). Phase 05 additionally runs the full cross-platform suite.
  produced_by: Phase 01, Phase 02, Phase 03, Phase 04 (if executed), Phase 05
  consumed_by: the next phase in sequence (Phase N's compile assumes Phase N-1 left HEAD green); Phase 05 final audit.
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-020
  verification_gate: per-phase Acceptance gates in design §11: `sbt kyo-browser/Test/compile` green; `sbt 'kyo-browser/Test/testOnly *<PhaseFocusedTest>*'` green; on Phase 05 boundary the full suite green. `flow-verify` at each phase runs `sbt kyo-browserJVM/Test/compile kyo-browserJS/Test/compile kyo-browserNative/Test/compile` and asserts zero non-zero exits.
  rationale: design §10 INV-009 ("phases must each be a green-build commit"), feedback_commit_between_phases, feedback_targeted_tests_only (per-phase scope), feedback_clean_stable_fast.

INV-021: Per-platform test runs SEQUENTIAL during validation steps; JVM, JS, Native one at a time (never parallel, due to Chrome contention).
  produced_by: Phase 05 (the cross-platform sweep formally codifies this)
  consumed_by: every prior phase's local validation; Phase 05 final audit.
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-021
  verification_gate: Phase 05 commit message records the sequential invocation order: `sbt kyo-browserJVM/Test` then `sbt kyo-browserJS/Test` then `sbt kyo-browserNative/Test`, each as a separate sbt invocation in the log (design §11 Phase 05 Scope line 1060-1063). Grep: `git log -1 --format=%B | grep -E 'kyo-browserJVM/Test' && git log -1 --format=%B | grep -E 'kyo-browserJS/Test' && git log -1 --format=%B | grep -E 'kyo-browserNative/Test'` (three matches).
  rationale: design §11 Phase 05 scope, feedback_sequential_test_runs.

INV-022: No `Co-Authored-By` trailer in any commit message produced by this campaign.
  produced_by: Phase 01, Phase 02, Phase 03, Phase 04 (if executed), Phase 05
  consumed_by: (none yet; documentation contract for the audit trail)
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-022
  verification_gate: `git log pre-port-tag..HEAD --format=%B | grep -iE '^[[:space:]]*Co-Authored-By:'` returns ZERO matches across every campaign commit.
  rationale: design §10 INV-011, feedback_no_coauthor.

INV-023: No `git push` is executed by the campaign agent at any phase boundary.
  produced_by: Phase 01, Phase 02, Phase 03, Phase 04 (if executed), Phase 05
  consumed_by: (none yet; operational contract)
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-023
  verification_gate: the supervisor's bash command audit log carries ZERO `git push` invocations across the campaign. (Verification is procedural; the smoke test is a no-op stub that documents the contract — the audit comes from outside-process logs.)
  rationale: design §10 INV-012, feedback_no_push.

INV-024: Every audit-flag exception in the campaign diff (any `AllowUnsafe`, `var` for shared state, manual JSON, or other lint-class exception) carries a `// flow-allow: <rationale>` comment on a same-or-preceding line. `flow-strip-dev` converts these comments at end-of-campaign per the standard end-of-campaign cleanup.
  produced_by: Phase 01, Phase 02, Phase 03, Phase 04 (if executed), Phase 05
  consumed_by: `flow-strip-dev` at campaign end (after Phase 05 commits).
  smoke_test_path: kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala::INV-024
  verification_gate: any line added by the campaign that is flagged by `INV-012` (`var`), `INV-013` (`AllowUnsafe` / `Sync.Unsafe.*`), or `INV-011` (manual JSON) must carry a `// flow-allow:` comment within 1 line above (or trailing on the same line); `flow-strip-dev` rewrites each `// flow-allow: <rationale>` to `// <rationale>` at the end. Gate: `awk` over the diff scans for flagged tokens and confirms a same-or-preceding-line `// flow-allow:` annotation per occurrence.
  rationale: design §10 INV-004, feedback_no_unsafe, feedback_no_manual_json, feedback_atomic_not_var, FLOW-DESIGN.md flow-strip-dev contract.

---

## Phase-by-phase consumed_invariants / produced_invariants summary (for flow-plan's phase blocks)

This summary is the input flow-plan consumes when populating each `## Phase N` block in `05-plan.md` (the `consumed_invariants: [...]` / `produced_invariants: [...]` fields).

- Phase 01 (scaffold CdpBackend behind feature parity):
  - consumed_invariants: (none; phase produces the foundational invariants)
  - produced_invariants: [INV-005, INV-008, INV-009, INV-011, INV-012, INV-013, INV-014, INV-015, INV-016, INV-017, INV-018, INV-019, INV-020, INV-022, INV-023, INV-024]

- Phase 02 (cut over production sites; delete CdpClient):
  - consumed_invariants: [INV-015, INV-016, INV-017]
  - produced_invariants: [INV-001, INV-002, INV-003, INV-007, INV-008, INV-009, INV-010, INV-011, INV-012, INV-013, INV-014, INV-019, INV-020, INV-022, INV-023, INV-024]

- Phase 03 (delete wire-envelope helpers; rename / delete wire-layer tests):
  - consumed_invariants: [INV-001, INV-002, INV-003, INV-007, INV-010, INV-015, INV-017, INV-018]
  - produced_invariants: [INV-004, INV-006, INV-008, INV-014, INV-020, INV-022, INV-023, INV-024]

- Phase 04 (defensive backports to kyo-jsonrpc-http; conditional):
  - consumed_invariants: [INV-016]
  - produced_invariants: [INV-014, INV-020, INV-022, INV-023, INV-024] (only if executed)
  - Note: if Phase 04 contributes zero commits, this phase's invariants list is `invariants: none` per the flow-invariants enumeration convention.

- Phase 05 (cross-platform sweep, final cleanup, test-suite parity verification):
  - consumed_invariants: [INV-001, INV-002, INV-003, INV-004, INV-005, INV-006, INV-007, INV-008, INV-009, INV-010, INV-011, INV-012, INV-013, INV-015, INV-016, INV-017, INV-018, INV-019, INV-020, INV-022, INV-023, INV-024]
  - produced_invariants: [INV-021]

---

## Notes on smoke-test file

The smoke tests for INV-001..INV-024 are co-located in a single new test file: `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala`. It is introduced in Phase 01 (carrying the INVs Phase 01 produces) and extended in each subsequent phase as new INVs come online. Per Rule 8c, each phase's source change for an invariant ships with its smoke-test addition in the same commit. INVs that verify by grep / SHA / commit-log audit (INV-001, INV-002, INV-003, INV-005, INV-006, INV-007, INV-010, INV-011, INV-014, INV-019, INV-020, INV-021, INV-022, INV-023) have a one-liner smoke test that re-runs the same grep / diff via `scala.sys.process.Process` and `Sync.Unsafe.evalOrThrow`-crashes on non-zero exit (per the flow-invariants companion-test convention, carrying a `// flow-allow: smoke-test invariant gate` rationale). INVs that exercise runtime behavior (INV-004 via test count, INV-008 via commit listing, INV-009 via package header, INV-012 / INV-013 via diff scan, INV-015 / INV-016 / INV-017 / INV-018 via `JsonRpcTransport.inMemory` round-trips, INV-024 via diff-flag-with-annotation scan) carry a runtime test body. INV-023 is a no-op stub documenting the contract (the verification is in the outside-process audit log).
