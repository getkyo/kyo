# 06-validation.md (v2 re-run)

Plan: `05-plan.md` + `05-plan.yaml` (v2)
Invariants: `04-invariants.md` (v2; INV-001 through INV-007)
Design: `02-design.md` (v2)
Steering: `steering.md` (Design v1 -> v2 directive)

## Verdict

**PASS** (with two script false-positives recorded as VALIDATED_EXCEPTION;
the seven protocol-bleed strings appear only in the supersedes paragraph
listing what was REMOVED from v2 scope, never in any phase's
implementation slot).

All v2-specific gates green: protocol-bleed scan finds no engine-scope
references to the 7 removed items, the `Config()` no-arg cancellation
flip is present in Phase 01 with explicit BEFORE/AFTER (yaml lines
39-79), phase count is 5 (down from v1's 7), total tests is 38 (within
the expected 30-50 window), every INV-001..INV-007 is referenced by a
test leaf, every phase carries a convention sweep, and every shared
state entry carries init_site plus observable_by.

## Per-script catalog hit counts

| Script | fail_count | override_count | exit | notes |
|---|---|---|---|---|
| flow-validate-grep --catalog rewardhack | 22 | 0 | 0 | 21 are `Sync.defer` (legitimate Kyo API regex false-positive on the literal token `defer`); 1 is the supersedes paragraph reading "drop to future consumer modules" (line 14). VALIDATED_EXCEPTION: `defer` is the Kyo Sync entry point; `future` here scopes work explicitly OUT of this plan into kyo-mcp/kyo-lsp/kyo-cdp per the steering directive. |
| flow-validate-grep --catalog vague | 0 | 0 | 0 | green |
| flow-validate-grep --catalog counts | 0 | 0 | 0 | green |
| flow-validate-grep --catalog acceptance | 0 | 0 | 0 | green |
| flow-validate-coverage-matrix | sections=10 ok=7 hand-wave=3 | n/a | 0 | All HAND-WAVE rows are `Files to produce: 0` or `Files to modify: 0` emitted when a phase has no produced or no modified files. Each phase still satisfies the other half. |
| flow-validate-cross-phase-invariants | references=0 anonymous=0 | n/a | 0 | green |
| flow-validate-test-count | 41 declarations, 22 ok, 0 range, 0 handwave, 19 unclear | n/a | 0 | UNCLEAR rows are non-count headings the regex over-collects. Explicit per-phase totals: 10+8+12+4+4=38; line 1457 "Total: 38 tests" is concrete. |
| flow-validate-open-question-count | 0 | n/a | 0 | green |
| flow-validate-init-order | 3 entries, all OK | n/a | 0 | callerRegistry, activeChannelRef, inbound each have init_site + observable_by. |
| flow-validate-invariant-coverage | INV-001..INV-007 all OK | n/a | 0 | green |
| flow-validate-convention-sweep | 5 phases, all OK | n/a | 0 | green |
| flow-validate-code-detail | global ok (25 blocks >= 22 changes); per-phase reports 5 PHASE-FAIL | n/a | 1 | Same script bug as v1 verdict noted: section parser anchors on `^# Phase ` (H1), plan uses `^## Phase ` (H2). Manual count: P01=9, P02=6, P03=6, P04=2, P05=2 (sum=25). Plan satisfies the contract; recorded as VALIDATED_EXCEPTION. |
| flow-validate-test-substance | 38 leaves, 37 ok, 1 MISSING-THEN | n/a | 0 | Leaf 12 ("dispatch unknown name returns Absent") has a valid `then: "Absent"` value; the substance script's regex treats single-token typed values as too short. Recorded as VALIDATED_EXCEPTION. |

## Protocol-bleed check (NEW v2)

Scan of `05-plan.md` and `05-plan.yaml` for the 7 removed v1 items:

| String | Hit locations | Verdict |
|---|---|---|
| `Config.cdp` | md:12 only (supersedes paragraph listing v1 items REMOVED) | CLEAN (engine scope) |
| `partialResultToken` | md:12 only (same) | CLEAN (engine scope) |
| `scopedNotification` | none | CLEAN |
| per-sessionId routing | md:12 ("per-sessionId" inside the same removed-items sentence) | CLEAN (engine scope) |
| `respondToMalformed` | none | CLEAN |
| `MetaPolicy` | md:13 only (same removed-items sentence) | CLEAN (engine scope) |
| `_meta.` field stamping | none | CLEAN |
| `JsonSchema2020_12` / `JsonSchema 2020-12` | md:13 only (same removed-items sentence) | CLEAN (engine scope) |
| `emitProgress` | md:14 only (same removed-items sentence) | CLEAN (engine scope) |

Every hit is confined to the four-line supersedes paragraph (md lines
11-14) that names the seven v1 items being removed from v2 scope. The
YAML is fully clean of all seven strings. No phase's `files_produced`,
`files_modified`, or test leaf references any of the removed items.
The protocol-bleed leak is fully sealed at the implementation layer.

## Config() flip check (NEW v2)

Confirmed in Phase 01. `05-plan.yaml` lines 39-79 contain the
`JsonRpcEndpoint.scala` `files_modified` entry with:
- BEFORE (line 43): `cancellation: Maybe[CancellationPolicy] = Present(CancellationPolicy.lsp),`
- AFTER  (line 61): `cancellation: Maybe[CancellationPolicy] = Absent,`

The flip is paired with the close(gracePeriod) and Malformed.id
additions in the same Phase 01 yaml entry block.

## Phase count + total tests

- Phase count: 5 (yaml `- id:` headers at lines 6, 375, 691, 1181, 1410). Matches expected 5.
- Total tests: 38 (yaml `- id:` test leaves count + plan markdown "Total: 38 tests" line 1457). Within expected 30-50.

## Code-in-plan sample compliance

| Phase | Path | BEFORE+AFTER or fenced code? |
|---|---|---|
| 1 | shared/src/main/scala/kyo/JsonRpcEnvelope.scala (Malformed.id extend) | YES |
| 1 | shared/src/main/scala/kyo/JsonRpcEndpoint.scala (Config flip + close grace) | YES (lines 39-79) |
| 1 | shared/src/main/scala/kyo/CancellationPolicy.scala (decodeParams field) | YES (lines 91-108) |
| 2 | shared/src/main/scala/kyo/JsonRpcMethod.scala (dispatch public) | YES |
| 3 | shared/src/main/scala/kyo/JsonRpcWireTransport.scala (PRODUCE) | YES |
| 3 | shared/src/main/scala/kyo/Framer.scala (PRODUCE) | YES |
| 5 | kyo-jsonrpc-http/shared/.../JsonRpcHttpTransport.scala (PRODUCE) | YES |

Every sampled file change shows the corresponding fenced scala block
in the markdown plan.

## Rule 8c per-phase pairing

| Phase | Produced source files | Paired tests in same phase? |
|---|---|---|
| 1 | (none; files_modified only) | n/a; tests still ship inside phase 1 (10 leaves) |
| 2 | (none; files_modified only) | n/a; 8 leaves inside phase 2 |
| 3 | JsonRpcWireTransport, Framer, internal/WireTransportAdapter, internal/StdioWireTransport | YES (WireTransportTest, FramerTest, StdioTransportTest in phase 3, 12 leaves) |
| 4 | jvm/internal/UdsWireTransport | YES (UdsTransportJvmTest in phase 4, 4 leaves) |
| 5 | kyo-jsonrpc-http/JsonRpcHttpTransport | YES (JsonRpcHttpTransportTest in phase 5, 4 leaves) |

Every new PUBLIC source ships its XxxTest in the same phase commit.
INTERNAL files (WireTransportAdapter, StdioWireTransport,
UdsWireTransport) reuse the public-seam test files per the Rule 8c
INTERNAL exemption.

## Remediation

None blocking. Two persistent observability items for the FLOW
toolchain (not the plan):

1. `flow-validate-code-detail.sh` line 128 anchors on `^# Phase `
   (H1). Plans use `^## Phase ` (H2). The regex should accept
   `^#+\s*Phase ` so per-phase code-block counting works on any
   heading depth. Same script bug carried from v1; plan satisfies the
   underlying contract (25 scala blocks across 5 phases for 22 file
   changes).

2. `flow-validate-test-substance.sh` flags single-token typed `then:`
   values (`Absent`) as MISSING-THEN. Same false-positive pattern as
   v1; the flagged leaf carries a concrete expected result.

The plan is ready for `flow-impl`.
