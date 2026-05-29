# steering.md — kyo-jsonrpc Rule 8 cleanup campaign

## NEVER STOP

Once `flow-validate` exits 0, drive every phase through commit and
immediately launch the next. Stop only when:
1. Plan exhausted (all phases committed + strip-dev commit green).
2. Genuinely blocked after 3 retries AND concrete repro recorded here.
3. User explicitly says stop.

Not valid stop reasons: phase committed, verify passed, ambiguity,
self-doubt, "good stopping point".

## Scope

Fix Rule 8 (Organization) violations in `kyo-jsonrpc/shared/src/`.
Detected gaps as of campaign start:

- 8a (`package kyo` hygiene): 7 files flagged for class-B judgment
  (HandlerCtx, IdStrategy, JsonRpcCodec, JsonRpcEndpoint, JsonRpcMethod,
  JsonRpcRequest, UnknownMethodPolicy). Most are likely user-facing
  despite `private[kyo]` smart-constructor markers; class-B verdict
  needed per file.
- 8b (one type + companion per file): 1 hit. `JsonRpcRequest.scala`
  declares both `JsonRpcRequest` and `JsonRpcResponse` top-level.
- 8c (test prefix-match): 5 orphan tests + 8 missing tests.
  - Orphan tests: MaxInFlightTest, Test, ScenarioBidiTest,
    ScenarioHttpStyleTest, ScenarioWsStyleTest. The 3 Scenario* tests
    move to `kyo/scenario/` subdir per convention. `Test.scala` is a
    shared base (exception). `MaxInFlightTest` needs a matching source
    OR rename.
  - Missing tests: JsonRpcError, MessageGate, IdStrategy, JsonRpcRequest,
    JsonRpcEnvelope, JsonRpcId, HandlerCtx, ExtrasEncoder.

## Conventions

- `// flow-allow:` rationales for any exception, traced to a
  VALIDATED_EXCEPTION verdict.
- All annotated sources MUST have matching focused tests (Rule 8c HARD).
- No scope cuts; no deferrals; no "tested transitively" excuses.

## Plan v1 → v2 directive (after 06-validation.md FAIL)

flow-validate FAILED v1 of 05-plan at Phase 2: JsonRpcResponse.scala
shipped without its matching JsonRpcResponseTest.scala in the same
phase. Per Rule 8c HARD: source ships with its focused test in the
SAME phase commit. Stub-in-phase + flesh-out-later is the
deferred-coverage anti-pattern; banned.

Remediation: regenerate the plan with JsonRpcResponseTest.scala
fully created in Phase 2 (5 cases per design). Phase 4 then ships
8 source/test pairs (the 8 missing-test sources), not 9. Update
the markdown table, the YAML files_produced entries, and the
per-phase test totals.

## Design v2 + Plan v3 directives

Two issues discovered after plan v2 validate-PASS:

1. **Incomplete 8a audit**: only 7 files surveyed (those flagged by
   the script's private[kyo] heuristic). The other 7 files in
   package kyo (CancellationPolicy, ExtrasEncoder, JsonRpcEnvelope,
   JsonRpcError, JsonRpcId, MessageGate, ProgressPolicy) were NOT
   verdicted for genuine user-facing intent. Every kyo/*.scala
   file must carry an explicit PUBLIC / SPLIT / INTERNAL verdict in
   02-design.md, sourced from class-B judgment after reading use
   sites.

2. **Plan v2 doesn't satisfy code-in-plan contract**: flow-plan.md
   was updated to require every Files-to-produce entry, Files-to-
   modify entry, and Tests entry to carry its ACTUAL code as a
   fenced scala block (not signatures or one-line scenarios).
   Plan v3 must transcribe the real code for all 4 phases.

Design v2 first (verdict the 7 unflagged files; relocate any
genuine internals to kyo/internal/). Then plan v3 with full
code blocks throughout.
