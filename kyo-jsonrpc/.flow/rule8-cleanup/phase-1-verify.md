# Phase 1 Verify Report

Plan: 05-plan.yaml Phase 1 (8a sub-symbol relocations and PUBLIC markers)
Dirty tree: kyo-jsonrpc/ (uncommitted)
Verdict: FAIL (scope creep: 3 files modified outside the Phase 1 plan authorization)

## Gate results

### Class-A catalog: reward-hacking

fail_count=3, override_count=0. All hits are inside campaign planning artifacts under `.flow/`, not in the source tree.

| line | location | rule |
|------|----------|------|
| 04-invariants.md:165 | "re-verified at each boundary; the consumer set is the next phase" | deferral-next-phase |
| 04-invariants.md:201 | "campaign acceptance signal, no later phase consumes them" | deferral-next-phase |
| 05-plan.md:543 | "Extends `Test` for now; Phase 3 renames the base class" | deferral-for-now |

These are legitimate cross-phase invariant-deferral language inside the plan and ledger, not in source. No source-tree reward-hacking.

### Class-A catalog: fp-discipline

fail_count=0, override_count=174. Every unsafe-* and Option-arm site has either a per-line `// Unsafe:` or `// flow-allow:` rationale. Zero unannotated source violations.

### Class-A catalog: llm-tells

fail_count=3, override_count=0. All hits are em-dashes in pre-existing campaign artifacts:

| line | location |
|------|----------|
| steering.md:1 | header line |
| audit/flow-allow-verdicts.md:23 | header line |
| audit/flow-allow-verdicts.md:54 | header line |

No em-dashes in source files modified by Phase 1.

### Class-A catalog: dev-tag

fail_count=0, override_count=0. Clean.

### Class-A catalog: open-question

fail_count=4, override_count=0. All hits in planning artifacts (02-design.md, 01-exploration.md, 05-plan.md, audit verdicts). None in source.

### Organization check

violations=14. Breakdown by class:

- 8a-package-leak: 0 (every kyo/ source has the PUBLIC `// flow-allow:` marker; expected per plan)
- 8b-name-mismatch: 1 on JsonRpcRequest.scala (top-level JsonRpcResponse alongside; expected, Phase 2 dissolves the file)
- 8c-orphan-test: 5 (MaxInFlightTest, Test, ScenarioBidiTest, ScenarioHttpStyleTest, ScenarioWsStyleTest); Phase 3 handles
- 8c-missing-test: 8 (JsonRpcError, MessageGate, IdStrategy, JsonRpcRequest, JsonRpcEnvelope, JsonRpcId, HandlerCtx, ExtrasEncoder); Phase 4 handles

Matches plan-stated end-of-Phase-1 state.

### Plan-diff

Raw script invocation produces noise because yq emits each map key (path/before/after/code) as a separate string for nested file entries. Filtered with `yq '.files_modified[].path'` and `'.files_produced[].source'`:

Expected produced (1): `kyo-jsonrpc/shared/src/main/scala/kyo/internal/IdStrategyEngine.scala` -> present
Expected modified (17): all 17 paths present in dirty tree.

UNAUTHORIZED modifications (3 files modified outside the Phase 1 plan):

- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala` (flow-allow annotations added to existing Unsafe sites)
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala` (flow-allow ConcurrentHashMap annotation, plus minor)
- `kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala` (single flow-allow comment annotation)

These changes are not authorized by Phase 1's `files_produced` / `files_modified` lists. They are convention-sweep work that the audit verdicts at `audit/flow-allow-verdicts.md` triage for later phases. The Phase 1 impl agent picked them up out of band.

### Compile (`sbt 'project kyo-jsonrpc; clean; Test/compile'`)

PASS. 22 main sources + 12 test sources compile cleanly in 30s. No errors, no warnings.

## Invariant smoke verdicts

- INV-001 (public API stability): PASS. PUBLIC types and their public members are unchanged. Markers are comments.
- INV-002 (no dangling): PASS. `grep "IdStrategy.mkNextId"` returns 0 hits across the module; the single caller in JsonRpcEndpointImpl was correctly rewritten to `IdStrategyEngine.mkNextId`.
- INV-003 (rationale coverage): PASS. All 13 PUBLIC files carry a one-line `// flow-allow: PUBLIC ...` marker (verified via Decision 5 in phase-1-decisions.md).
- INV-007 (PUBLIC marker coverage): PASS. 13 of 13 PUBLIC files marked; organization check confirms 0 8a violations on PUBLIC files.
- INV-009 (existing tests still green): UNVERIFIED in this gate; the verification_command (`kyo-jsonrpcJVM/test` cross-platform) was not run. Test/compile is green, which is the documented Phase 1 deliverable per the brief.

## Class-B catch-list (opus self-review)

### Rationale quality

- 13 of 13 PUBLIC markers cite the actual user-facing entry point. Examples:
  - IdStrategy: "referenced by JsonRpcEndpoint.Config.idStrategy field"
  - JsonRpcId: "referenced by JsonRpcEndpoint.cancel, Pending.id, ExtrasEncoder, HandlerCtx.requestId"
  - MessageGate: "implemented by users and consumed via JsonRpcEndpoint.Config.gate"
  No generic "intentional API" placeholders. PASS.

### Architectural substitution

- IdStrategyEngine relocation: plan's signature `def mkNextId(strategy: IdStrategy)(using Frame): () => JsonRpcId < Sync` matches the impl verbatim. Match arms use `IdStrategy.SequentialLong/SequentialInt/Custom` (qualified, since the engine sits in package kyo.internal). Plan and impl agree.
- IdStrategy companion fully removed (since mkNextId was its only member). Plan's AFTER block confirms this. PASS.
- JsonRpcCodec companion: `cdpReservedKeys` correctly relocated as `private val` inside `internal.JsonRpcCodecImpl`; nested anonymous class bodies retain visibility. PASS.
- JsonRpcEndpointImpl callsite: only the single `IdStrategy.mkNextId` -> `IdStrategyEngine.mkNextId` rewrite. PASS.

### Skipped modifications

None on the 16 plan-listed files (IdStrategyEngine + 15 modifications all applied).

### Drift

The three out-of-scope files (CancellationEngine.scala, ProgressEngine.scala, CancellationPolicyTest.scala) carry flow-allow annotations that are themselves harmless and rooted in the existing audit verdicts; the substantive concern is **scope discipline**: Phase 1 should not silently sweep convention work onto files not in `files_modified`. Either the plan needs amendment to authorize these annotations, or the annotations should be reverted before commit. This is the FAIL trigger.

## Verdict: FAIL

### Remediation

Pick one of two paths before commit:

1. Revert the three out-of-scope files to HEAD:
   ```
   git checkout HEAD -- \
     kyo-jsonrpc/shared/src/main/scala/kyo/internal/CancellationEngine.scala \
     kyo-jsonrpc/shared/src/main/scala/kyo/internal/ProgressEngine.scala \
     kyo-jsonrpc/shared/src/test/scala/kyo/CancellationPolicyTest.scala
   ```
   Then re-run verify.

2. Amend `05-plan.yaml` Phase 1 to add these three files to `files_modified` with the specific flow-allow rationales the impl applied, cite the audit verdicts as authorization, and rerun verify.

Path (1) is recommended: Phase 1 is scoped to 8a sub-symbol relocations and PUBLIC markers on the 13 public files plus the IdStrategy carve-out. Convention-sweep annotations on impl-only files belong to a dedicated phase, not absorbed into the current scope without an amendment trail.

All other gates pass. Once the three out-of-scope diffs are resolved, Phase 1 is ready for commit.
