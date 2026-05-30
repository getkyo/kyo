# Phase 2 audit

Time: 2026-05-30T16:19:06Z
HEAD: 3f66991cda280c7ee36f6aeb8bb6e711e4d3528b
Phase commit: 3f66991cda280c7ee36f6aeb8bb6e711e4d3528b
Plan cites: ./design/05-plan.md §Phase 2 (lines 49-78)
Design cites: ./design/02-design.md §4 row "JsonRpcEnvelope" + §11 "JsonRpcResponseTest.scala deletes; ~6 cases migrate"

## Test count

Plan target: "Migrate the ~6 affected test cases into `JsonRpcEnvelopeTest.scala`" (plan §"Scope"). Decisions doc clarifies 3 absorbed (factory pair + copy), 2 Schema round-trips dropped (`JsonRpcEnvelope.Response` is an enum case, not a standalone case class with its own Schema derivation; wire-level Schema round-trip is covered by `JsonRpcCodecImplTest`). Net: 3 of original 5 cases migrated as direct equivalents, 2 dropped with justification.

| Leaf | Status | Notes |
|---|---|---|
| `success factory enforces result-present and error-Absent` | PRESENT_STRICT | absorbed at `JsonRpcEnvelopeTest.scala:61-67` as `Response.success factory sets result-present error-Absent extras-Absent`; assertions stronger (adds `extras == Absent`); id assertion adapted from `Present(JsonRpcId.Num(1L))` to `JsonRpcId.Num(1L)` matching the tightened non-Maybe id contract. |
| `failure factory enforces error-present and result-Absent` | PRESENT_STRICT | absorbed at `JsonRpcEnvelopeTest.scala:69-75` as `Response.failure factory sets error-present result-Absent extras-Absent`; same shape, stronger assertions (adds `extras == Absent`). |
| `Schema[JsonRpcResponse] round-trips a success through Structure` | DROPPED (justified) | `JsonRpcEnvelope.Response` is an enum case; no standalone `Schema` derivation exists on it. Wire-level encode/decode is covered by `JsonRpcCodecImplTest` (decisions.md §Deviations 2). No semantic gap. |
| `Schema[JsonRpcResponse] round-trips a failure through Structure` | DROPPED (justified) | same justification. |
| `copy preserves equality across both fields` | PRESENT_STRICT | absorbed at `JsonRpcEnvelopeTest.scala:77-83` as `Response copy preserves equality across fields`; assertions identical (`base != mutated`, `mutated.error`, `mutated.id == base.id`). No weakening. |

Verdict on absorbed tests: zero weakening, two justified drops.

## CONTRIBUTING.md violations

- None in the phase diff. `Frame` is propagated on both new public factories (lines 46, 49). `Maybe` used, not `Option`. `Result` not relevant (no error channel introduced). `Chunk` not relevant. Action-verb naming (`success`, `failure`). No symbolic operators added. No `protected` introduced. No `@uncheckedVariance` introduced. No `asInstanceOf` introduced.

## Unsafe markers

- None added or modified in the phase diff. No `AllowUnsafe` use site introduced.

## Cross-platform consistency

- platforms checked: jvm (per plan `verification_command`).
- Per-platform deltas: none — `JsonRpcEnvelope.scala` lives under `shared/src/main/scala/kyo/`, single source, no platform-specific overlays. Phase 2 was scoped JVM-only by design; cross-platform validation deferred to Phase 3 (where the nesting moves land).
- NOTE: the test `JsonRpcEnvelopeTest` is under `shared/src/test/`; JS and Native test runs were not invoked by the phase verification command and are also not strictly required at this stage. The phase's targeted-verification strategy is plan-authorized.

## Naming convention compliance

- `JsonRpcEnvelope.Response.success(id, result)` and `.failure(id, error)`: action-verb factories on a nested companion. Matches `HttpServerConfig.Cors.default`-style precedent. PASS.
- Parameter names (`id`, `result`, `error`): match the enum case field names — no Hungarian, no abbreviation drift. PASS.

## Steering deviation

`git diff --name-only 3f66991cd~1 3f66991cd`:
- `kyo-jsonrpc/.flow/api-cleanup/control/progress.md` — campaign management.
- `kyo-jsonrpc/.flow/api-cleanup/phases/phase-01/audit.md` — Phase 1 audit artifact, written during Phase 2 SLOT-B.
- `kyo-jsonrpc/.flow/api-cleanup/phases/phase-02/baseline.txt` — phase artifact.
- `kyo-jsonrpc/.flow/api-cleanup/phases/phase-02/decisions.md` — phase artifact.
- `kyo-jsonrpc/.flow/api-cleanup/phases/phase-02/verify.md` — phase artifact.
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala` — plan-authorized edit.
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala` — plan-authorized delete.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcEnvelopeTest.scala` — plan-authorized edit.
- `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcResponseTest.scala` — plan-authorized delete.

Plan's `files_modified` listed two more source files: `internal/engine/JsonRpcEndpointImpl.scala` and `internal/codec/JsonRpcCodecImpl.scala`. Both were untouched at HEAD because Phase 1 had already migrated their `JsonRpcResponse.success`/`failure` callsites to direct `JsonRpcEnvelope.Response(...)` constructor calls (decisions.md §Caller survey; verify.md MISSING=2). Manual grep confirmed zero `JsonRpcResponse` references in both files at HEAD. The plan overspecified; no drift.

Match verdict: MATCH (with documented overspecification on the plan side; nothing to fix in HEAD).

## Anti-flakiness measures

- Tests use the module's `JsonRpcTestBase` base class (correct per CONTRIBUTING.md).
- Tests use `in run { ... }` blocks (correct kyo test idiom).
- Assertions are deterministic; no timing, no concurrency, no environment-coupled state.
- Implemented: per-field equality assertions against literal expected values (`JsonRpcId.Num(1L)`, `Structure.Value.Str("ok")`, `JsonRpcError.MethodNotFound`).
- Missing: none.

## Architecture substitution check

- Design intent (§4 row "JsonRpcEnvelope", §11): "KEEP+MERGE — absorbs `JsonRpcResponse`". Plan §"Canonical change" specified `object JsonRpcEnvelope; object Response; def success(id, result); def failure(id, err)` with two-arg signatures and the smart-constructor factories living on the nested `Response` companion.
- HEAD reality (`JsonRpcEnvelope.scala:44-52`): `object JsonRpcEnvelope; object Response; def success(id: JsonRpcId, result: Structure.Value)(using Frame); def failure(id: JsonRpcId, error: JsonRpcError)(using Frame)` — both factories return `JsonRpcEnvelope.Response` with `extras = Absent` and the appropriate `Present(...)` / `Absent` arrangement.
- Verdict: MATCH. The id type tightened from the old `Maybe[JsonRpcId]` (in the deleted `JsonRpcResponse`) to the non-Maybe `JsonRpcId`, consistent with JSON-RPC 2.0 §5 (responses MUST have an id) and with the existing enum case field type at `JsonRpcEnvelope.scala:36`. `extras` is hard-coded to `Absent` inside the factories (not exposed as a parameter), matching the plan's canonical signature. `(using Frame)` is propagated on both factories. No simpler-equivalent substitution; no stringly-typed dispatch introduced.

## Documentation drift

- Scaladoc / README additions in this phase: none in JsonRpcEnvelope.scala. The existing class-level scaladoc (lines 9-22) was untouched.
- Beyond plan intent: no. (No expansion attempted; see WARN below for an omission.)

## Findings (categorized)

### BLOCKER
None.

### WARN
1. `JsonRpcEnvelope.scala:9-22` scaladoc was not updated to mention the new `Response.success` / `Response.failure` smart-constructor factories. The plan added a companion object with two public factories, but the class-level docblock still only describes the four enum cases. A reader landing on the type sees no hint that constructors are exposed via the companion. **Fix:** add one bullet line (e.g., "Use `Response.success(id, result)` / `Response.failure(id, error)` to build a `Response` envelope from the common shapes.") or a short `@see` entry pointing to the companion. Non-blocking for downstream phases because Phase 3 will rewrite this scaladoc anyway when nesting `JsonRpcId` -> `JsonRpcEnvelope.Id` (per design §5). Route this WARN to Phase 3 prep so it's batched with the broader scaladoc rewrite.

2. `JsonRpcEnvelope.scala:18,21` scaladoc references `[[MessageGate]]` as a top-level type. This is correct at HEAD (Phase 3 has not landed) but will become stale after Phase 3 nests `MessageGate` under `JsonRpcEndpoint`. Already implicitly tracked by Phase 3's scope; flagging here so it isn't overlooked. Route to Phase 3 prep.

### NOTE
1. The two dropped Schema round-trip tests (decisions.md §Deviations 2) lose direct per-type Schema-derivation coverage. The decisions doc points to `JsonRpcCodecImplTest` as covering the equivalent wire-level behavior; this is plausible but not verified by the audit. If end-of-campaign coverage review surfaces a gap, a single `JsonRpcEnvelope.Response` encode/decode round-trip case can be added to `JsonRpcCodecImplTest` (or a new `JsonRpcEnvelopeCodecTest`). Queue for end-of-project cleanup.

2. Plan §"Files modified" overspecified two internal files (`JsonRpcEndpointImpl.scala`, `JsonRpcCodecImpl.scala`) that Phase 1 had already migrated. This is a plan-correctness NOTE, not an impl regression; future similar campaigns should re-grep callsite inventories after each phase to avoid stale `files_modified` entries. Queue for end-of-project plan-template improvement.

3. `flow-verify-plan-diff.sh` yq-parsing bug (verify.md final note) emitted a false EXIT:1 due to object-typed `files_modified` entries. Already known and worked around manually; queue for end-of-project tooling fix.

## Routing

- BLOCKER findings: none. SLOT-A launch of Phase 4 is NOT halted on this audit's account.
- WARN findings: TaskCreate `phase-03-scaladoc-update-jsonrpcenvelope` — fold WARN-1 and WARN-2 into Phase 3's `JsonRpcEnvelope.scala` edit (which already touches scaladoc when nesting `Id`).
- NOTE findings: TaskCreate `end-of-project-schema-coverage-check` (NOTE-1); TaskCreate `end-of-project-plan-template-stale-files` (NOTE-2); TaskCreate `end-of-project-verify-script-yq-fix` (NOTE-3).
