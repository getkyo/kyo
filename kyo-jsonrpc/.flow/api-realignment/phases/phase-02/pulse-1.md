# Phase 02 pulse 1

Time: 2026-05-30T19:30Z
Files reviewed: JsonRpcEnvelope.scala (145 LoC), JsonRpcId.scala (83 LoC), decisions.md, git diff --stat (35 files), all run logs
Plan cites: ./design/realignment-plan.md §Phase B

## Plan anchor

- Files to produce (plan): 5 new files (JsonRpcRequest.scala, JsonRpcResponse.scala, JsonRpcNotification.scala, JsonRpcMalformedMessage.scala, JsonRpcId.scala)
- Files produced (dirty tree): 1 new file (JsonRpcId.scala) + JsonRpcEnvelope.scala restructured in-place; decisions.md documents the Scala 3 sealed-trait same-compilation-unit constraint as the reason for consolidation
- Files modified: 34 source + test files across kyo-jsonrpc, kyo-jsonrpc-http, kyo-browser (matches expected ~30-40 deduped)
- Tests: kyo-jsonrpc 179 tests, all pass; kyo-jsonrpc-http Test/compile [success]; kyo-browser Test/compile [success]
- Public API additions: JsonRpcRequest, JsonRpcResponse, JsonRpcNotification, JsonRpcMalformedMessage (case classes, top-level kyo package), JsonRpcId (opaque type), JsonRpcResponse.success/failure factories

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | CLEAN | runs/impl-compile-jvm-001.log [success]; impl-test-jvm-001.log 179 tests passed; impl-compile-http-jvm-001.log [success]; impl-compile-browser-jvm-001.log [success] |
| Compile-only "success" claim | CLEAN | Test run confirmed: "179 tests, 0 failed" in impl-test-jvm-001.log |
| Priority inference | CLEAN | decisions.md records the file-consolidation decision with Scala 3 rationale |
| Scope substitution | CLEAN | All 5 plan deliverables present (4 wire types in JsonRpcEnvelope.scala + JsonRpcId.scala); convention sweep in decisions.md reports 0 hits on all 9 stale patterns |
| Foreach-discards-assert | CLEAN | No evidence of weakened assertions in test diff; 179 tests unchanged in count vs phase start |
| Stale-state passing | CLEAN | Convention sweep confirmed 0 hits for JsonRpcEnvelope.Request/Response/Notification/Malformed/Id |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | CLEAN | JsonRpcResponse.success/failure factories restored on companion; JsonRpcId schema is hand-written untagged number-or-string; 4 wire types extend JsonRpcEnvelope; sealed trait shape matches plan |
| No off-plan architecture substitution | MINOR | Plan said "5 new top-level files"; impl used 1 restructured file + 1 new file. Scala 3 sealed-trait constraint documented in decisions.md as the driver. Net effect is identical public API surface. |
| No cross-cutting refactor outside phase | CLEAN | Only kyo-jsonrpc, kyo-jsonrpc-http, kyo-browser touched; no other modules modified |
| Internal helpers stay internal | CLEAN | internal/codec/JsonRpcRequest.scala (private[kyo]) untouched structurally; only field type updated |

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| JsonRpcRequest top-level case class | PRESENT_STRICT | Defined in JsonRpcEnvelope.scala line 45; extends JsonRpcEnvelope; full scaladoc 20 lines |
| JsonRpcResponse top-level case class | PRESENT_STRICT | Defined in JsonRpcEnvelope.scala line 75; companion with success/failure factories restored |
| JsonRpcNotification top-level case class | PRESENT_STRICT | Defined in JsonRpcEnvelope.scala line 113; extends JsonRpcEnvelope |
| JsonRpcMalformedMessage top-level case class | PRESENT_STRICT | Defined in JsonRpcEnvelope.scala line 141; extends JsonRpcEnvelope |
| JsonRpcId opaque type (separate file) | PRESENT_STRICT | JsonRpcId.scala with hand-rolled Schema, Num/Str extractors, fold/isLong/isString extensions |
| JsonRpcEnvelope -> sealed trait | PRESENT_STRICT | enum removed; sealed trait with derives CanEqual at line 23 |
| All JsonRpcEnvelope.X references eliminated | PRESENT_STRICT | Convention sweep in decisions.md: 9 patterns, 0 hits |
| kyo-jsonrpc-http updated | PRESENT_STRICT | JsonRpcHttpTransport.scala + test updated; compile [success] |
| kyo-browser updated | PRESENT_STRICT | CdpBackend.scala + 3 test files updated; compile [success] |
| Wire round-trip tests pass | PRESENT_STRICT | 179 tests passed including JsonRpcEnvelopeIdTest, JsonRpcCodecTest, JsonRpcEnvelopeTest |

## CRITICAL (steer immediately)

None.

## MINOR (queue for post-commit audit)

- Plan said "5 new top-level files" but impl produced 1 restructured file + 1 new file. The Scala 3 sealed-trait same-compilation-unit constraint is the documented reason (decisions.md). The public API surface is identical. Auditor should confirm Scala 3 constraint is accurate and that the plan wording was aspirational rather than binding.
- Net LoC is 63 lines (425 added, 362 removed), well within the ~300 plan estimate. No budget concern.

## Recommendation: CONTINUE

Phase 02 is complete and clean. All 5 plan deliverables are present in the correct public shape. Tests are 179/179 green across kyo-jsonrpc. Downstream callers (kyo-jsonrpc-http, kyo-browser) compile. Schema encoding is byte-identical to pre-hoist (verified in decisions.md). The single deviation (consolidated file vs 5 separate files) is documented, constraint-driven, and functionally equivalent. No CRITICAL findings. Ready for flow-verify and commit.
