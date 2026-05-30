# Phase 1 audit

Time: 2026-05-30T00:00:00Z
HEAD: 68ba4e1137f79fd2120ca6a773ac7978bad92be5
Phase commit: 68ba4e1137f79fd2120ca6a773ac7978bad92be5
Plan cites: ./design/05-plan.md §Phase 1
Design cites: ./design/02-design.md §6 Internal subpackage layout

## Test count

| Leaf | Status | Notes |
|---|---|---|
| Phase 1 has `tests_added: null` (mechanical reorg + scaladoc + banner sweep) | N/A | verify.md correctly skipped test-count gate; 5 test files received import-path edits only, no new assertions |

## CONTRIBUTING.md violations

- None observed in the Phase-1 diff. All added scaladoc uses Kyo-typed verbiage (`Maybe`, `Result`, `Chunk` are not introduced here; existing pre-existing internal patterns preserved verbatim).

## Unsafe markers

- No new `AllowUnsafe` sites added by Phase 1. The pre-existing `unsafe-site` patterns in moved files (`CancellationEngine`, `ProgressEngine`, `JsonRpcEndpointImpl`, `RawJsonParser`, `FramerImpl`, `UdsWireTransport`) are byte-identical to the pre-move bodies and out of scope of this phase.

## Cross-platform consistency

- platforms checked: jvm (per `verification_strategy: targeted`)
- Per-platform deltas: none introduced. The only JVM change is the rename `kyo/internal/UdsWireTransport.scala` -> `kyo/internal/transport/UdsWireTransport.scala` plus the reference update in `JsonRpcTransportJvm.scala:30`. The cross-platform fold-in is deferred to Phase 6 as planned.

## Naming convention compliance

- No deviations introduced by Phase 1. The 18 `8d-module-prefix` and 16 `8a-package-public` organization-gate flags in `verify.md` are intentional pre-existing decisions (D5/D6) addressed by Phase 3 NEST.

## Steering deviation

- `git diff --name-only HEAD~1 HEAD` matches `files_modified` for Phase 1 in design/05-plan.yaml; the 13 file moves + 17 banner-stripped files + 5 test-import edits + 1 JVM ref-update are all present.
- Two minor deviations from plan text (both PRE-EXISTING-OR-AUTHORIZED per `decisions.md` §Deviations):
  - Plan paragraph said "every public file" for banner sweep; impl stripped 17 (including ones the plan paragraph did not enumerate but YAML files_modified did include). This is correct.
  - Plan verification command uses `kyo-jsonrpcJVM/Test/compile`; impl ran `kyo-jsonrpc/Test/compile` (the actual sbt project name). Plan-text typo, not an impl deviation.

## Anti-flakiness measures

- N/A — no test changes were behavioural.

## Architecture substitution check

- Design intent (§6): "Single-line `package kyo.internal.<sub>` declaration on every file (no `package kyo` then `package internal` split)."
- HEAD reality: every file under `kyo/internal/{codec,engine,framing,transport}/` begins with a single-line `package kyo.internal.<sub>` declaration (verified: 4 of 13 had the chained `package kyo \n package internal` form pre-move; all four — `CancellationEngine.scala`, `JsonRpcEndpointImpl.scala`, `ProgressEngine.scala`, `RateLimitEngine.scala` — were converted to single-line and gained an `import kyo.*` to compensate for the lost scope).
- Verdict: MATCH.

- Design intent (§6 table): codec=3, engine=5, framing=1, transport=3 (shared) + 1 (jvm). Total 13 files distributed across 4 subpackages.
- HEAD reality: directory listings confirm exactly this distribution.
- Verdict: MATCH.

## Documentation drift

- Scaladoc / README additions in this phase: 17 source-file scaladoc additions on top-level public types in `kyo-jsonrpc/shared/src/main/scala/kyo/`, plus 2 nested-type scaladocs on `JsonRpcEndpoint.Pending` and `JsonRpcEndpoint.Config` (matching the 19-count in `decisions.md` §Scaladoc additions).
- Length sanity-check: every added doc block is between 11 and 17 content lines, comfortably inside the 8-35 band from CONTRIBUTING.md.
- Beyond plan intent: NO. Plan §"Scaladoc adds" specified "every Tier-A public type lacking scaladoc"; impl extended to the Tier-B types that will be NEST-moved in Phase 3, which is consistent with the design intent (every public type gets a doc before nesting).

## Findings (categorized)

- BLOCKER: none.

- WARN:
  - `kyo-jsonrpc/jvm/src/main/scala/kyo/JsonRpcTransportJvm.scala:1` still contains a `// PUBLIC JVM-only UDS transport extension on the shared JsonRpcTransport companion` banner. The shared `flow-verify-organization.sh --check banner-comments --root kyo-jsonrpc` reports `violations=0`, so the script's scope did not include this file, but the design intent (§Phase 1 "Strip every `// PUBLIC ...` banner comment from public source files") implies all `kyo/` package public files, not only shared. Phase 6 deletes this file entirely as part of the UDS fold-in, so the banner will disappear in due course. Recommend: leave as-is; Phase 6 takes care of it. No fix-commit warranted unless Phase 6 slips.
  - `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:7-22` scaladoc says "The two lifecycle methods are" then enumerates three bullets (`send`, `incoming`, `close`). Phase 02 / Phase 03 edits this file heavily; recommend folding a one-word fix ("The lifecycle methods are") into the Phase-03 edits rather than a separate fix-commit.
  - `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcResponse.scala` received a scaladoc block (per `decisions.md` §Scaladoc additions). The same Phase-01 plan also schedules Phase 2 to delete this file. Effort lost on documenting a soon-to-be-deleted type. Not a correctness issue; cosmetic.

- NOTE:
  - The decisions log notes a `kyo-jsonrpcJVM/Test/compile` vs `kyo-jsonrpc/Test/compile` plan-text typo. The plan-as-contract document should be patched out-of-band for future campaigns referencing the same template; not a Phase-1 concern.
  - The 4 chained-package files (`CancellationEngine`, `JsonRpcEndpointImpl`, `ProgressEngine`, `RateLimitEngine`) gained an `import kyo.*` to compensate for losing implicit `kyo` scope. This is a cleaner form than the chained-package idiom but it is a noteworthy semantic delta beyond a pure rename — captured correctly in `decisions.md` §Package declaration changes.
  - `WireTransportAdapter.scala` (subpackage `transport`) gained `import kyo.internal.codec.RawJsonParser` because the two now live in different subpackages. Mechanical and correctly disclosed.

## Routing

- BLOCKER findings: none — no halt of SLOT-A launch of Phase 03 is needed.
- WARN findings: file the "two/three lifecycle methods" copy-edit as a Phase-03 prep task so it lands in the same commit that absorbs `WireTransport` and `Framer` into the companion. The JVM banner is auto-resolved by Phase 6.
- NOTE findings: TaskCreate at end-of-project: patch plan template to reference the real sbt project name (`kyo-jsonrpc/Test/compile`) for any future campaign using this scaffolding.
