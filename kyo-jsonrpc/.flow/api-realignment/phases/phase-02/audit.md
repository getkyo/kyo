# Phase 02 audit

Time: 2026-05-30T19:45Z
HEAD: 24b78e70f
Phase commit: 24b78e70f
Plan cites: ./design/realignment-plan.md §Phase B (Wire-message hoist)
Design cites: ./design/realignment-plan.md (combined design+plan document for this campaign)

## Test count

| Leaf | Status | Notes |
|---|---|---|
| 1: JsonRpcRequest top-level case class | PRESENT_STRICT | JsonRpcEnvelope.scala:45; extends JsonRpcEnvelope; 20-line scaladoc with @see |
| 2: JsonRpcResponse top-level case class | PRESENT_STRICT | JsonRpcEnvelope.scala:75; extends JsonRpcEnvelope; success/failure factories on companion at lines 82-92 |
| 3: JsonRpcNotification top-level case class | PRESENT_STRICT | JsonRpcEnvelope.scala:113; extends JsonRpcEnvelope; 14-line scaladoc |
| 4: JsonRpcMalformedMessage top-level case class | PRESENT_STRICT | JsonRpcEnvelope.scala:141; extends JsonRpcEnvelope; 21-line scaladoc |
| 5: JsonRpcEnvelope sealed trait (no enum) | PRESENT_STRICT | JsonRpcEnvelope.scala:23 `sealed trait JsonRpcEnvelope derives CanEqual`; enum form removed |
| 6: JsonRpcId opaque type in separate file | PRESENT_STRICT | JsonRpcId.scala:24 `opaque type JsonRpcId = String \| Long`; Num/Str extractors, fold/isLong/isString/toLongOption/toStringOption extensions |
| 7: JsonRpcId hand-written Schema byte-identical | PRESENT_STRICT | JsonRpcId.scala:67-79; writeFn long/string branch, readFn tries long() first then string(); TypeMismatchException for null preserved (compared verbatim against decisions.md old form) |
| 8: JsonRpcResponse.success/failure restored | PRESENT_STRICT | JsonRpcEnvelope.scala:84-90; signatures `(JsonRpcId, Structure.Value)` / `(JsonRpcId, JsonRpcError)` (using Frame) |
| 9: kyo-jsonrpc pattern matches updated | PRESENT_STRICT | rg `case (JsonRpcRequest\|JsonRpcResponse\|JsonRpcNotification\|JsonRpcMalformedMessage)\(` -> 39 hits across 3 modules; 0 hits on legacy `case JsonRpcEnvelope.X` form |
| 10: kyo-jsonrpc-http pattern matches updated | PRESENT_STRICT | JsonRpcHttpTransport.scala lines 13/22/35 reference `JsonRpcEnvelope` only as the parent type for Channel/Stream parameters (correct); 0 hits on legacy form |
| 11: kyo-browser pattern matches updated | PRESENT_STRICT | CdpBackend.scala + 3 test files updated per decisions.md; 0 hits on legacy form in convention sweep |
| 12: Convention sweep 9/9 clean | PRESENT_STRICT | All 9 strict-form patterns return 0 hits across kyo-jsonrpc, kyo-jsonrpc-http, kyo-browser (re-verified at audit time) |

## CONTRIBUTING.md violations

None observed.

- Scaladoc length: 14-21 lines per case class, within the 8-35 line guideline.
- Frame ordering: companion factories `(id: JsonRpcId, result: Structure.Value)(using Frame)` — Frame last on non-inline, correct.
- No `@uncheckedVariance`, no `protected`. Internal helpers stay in `kyo.internal.*` packages.
- Kyo types used: `Maybe[Structure.Value]`, not `Option`. `Chunk` retained where present in surrounding code.

## Unsafe markers

None added in this phase. Pre-existing `AllowUnsafe.embrace.danger` usages in test files are unchanged.

## Cross-platform consistency

- Platforms checked at phase commit: JVM (per decisions.md). JS/Native compile gates deferred to Phase H per plan §"Verification approach".
- Per-platform deltas: none — Phase B is pure source restructure on the `shared/` tree; the single JVM-specific test file (`jvm/JsonRpcTransportUnixTest.scala`) was bulk-updated as part of the sed pass.

## Naming convention compliance

- New top-level types use the `JsonRpc` prefix (JsonRpcRequest, JsonRpcResponse, JsonRpcNotification, JsonRpcMalformedMessage, JsonRpcId), matching the kyo-http `Http*` prefix convention cited in the plan.
- `Num` / `Str` extractor objects nested under `JsonRpcId` companion — acceptable per Scala convention for opaque-type tag projections; mirrors the prior `JsonRpcEnvelope.Id.Num` / `.Str` API surface so external callers' pattern shapes are preserved.

## Steering deviation

- `git diff --name-only HEAD~1 HEAD` matches the plan's "Phase B scope" exactly: 4 wire-message hoists, 1 opaque-type hoist, enum→sealed-trait conversion, downstream caller updates across kyo-jsonrpc + kyo-jsonrpc-http + kyo-browser.
- One documented deviation: the plan specified "5 new top-level files" but the impl produced 1 restructured file (`JsonRpcEnvelope.scala` holding the trait + 4 case classes) + 1 new file (`JsonRpcId.scala`). The Scala 3 sealed-trait same-compilation-unit constraint is the driver (decisions.md §"Files Created"). Public API surface is identical — the 4 case classes are top-level in package `kyo`, importable individually.

## Anti-flakiness measures

- Wire round-trip preservation: decisions.md §"Schema Preservation" compares the new `JsonRpcId` Schema byte-for-byte against the deleted `JsonRpcEnvelope.Id` Schema; identical encoding semantics (long-then-string read-fallback, identical TypeMismatchException on null).
- 179/179 test pass count documented in decisions.md and the impl-test-jvm-001 run log.
- Pulse 1 reward-hack matrix at `phases/phase-02/pulse-1.md` flagged no CRITICAL items.

## Architecture substitution check

- Design intent: hoist enum cases to top-level case classes extending a sealed parent trait, mirroring kyo-http's HttpRequest/HttpResponse separate-top-level layout. JsonRpcId becomes an opaque type with its hand-written Schema preserved verbatim.
- HEAD reality: `sealed trait JsonRpcEnvelope` + 4 top-level case classes (JsonRpcRequest, JsonRpcResponse, JsonRpcNotification, JsonRpcMalformedMessage) each extending the trait; `opaque type JsonRpcId = String | Long` with the hand-written Schema in a separate file.
- Verdict: MATCH. The 5-files-vs-2-files difference is a Scala 3 compilation-unit constraint, not an architectural substitution. The public surface in `kyo.*` is identical to what 5 separate files would expose.

## Documentation drift

- Scaladoc additions in this phase: 4 new case-class doc blocks (14-21 lines each), 1 new opaque-type doc block on JsonRpcId (~18 lines), 1 updated parent-trait doc block.
- Beyond plan intent: NO. The plan §Phase B explicitly required "each get their own file with focused scaladoc"; the impl supplies focused scaladoc per type at the same volume. No marketing prose, no examples beyond what the type's role requires.
- The `@see` cross-references between the 4 wire types and JsonRpcEnvelope correctly model exhaustive-match navigation; these were added because the type is a sealed-trait union and downstream consumers need the linkage.

## Findings (categorized)

- BLOCKER: none.
- WARN: none.
- NOTE 1: Plan wording "5 new top-level files" is aspirational and was constrained to 2 files by Scala 3's sealed-trait same-compilation-unit rule. Public API is unchanged. The plan text for future phases (notably Phase C's sealed-hierarchy error types) should anticipate the same constraint and pre-commit to "1 file per sealed-trait family" rather than "1 file per leaf type". Recommendation: end-of-project cleanup pass updates the plan's file-count language for Phase C.
- NOTE 2: The `Num`/`Str` extractor objects under `JsonRpcId` companion are intentional pattern-match affordances for callers migrating from `JsonRpcEnvelope.Id.Num` / `.Str`. They duplicate the role of `id.isLong` / `id.toLongOption` extensions. Consumers using both surfaces is fine; future readme/skill work may want to pick one form as the documented preferred style.

## Routing

- BLOCKER findings: none. SLOT-A launch of Phase 04 is unblocked.
- WARN findings: none.
- NOTE 1: TaskCreate for end-of-project cleanup — update plan wording for sealed-trait file-count expectation in Phase C.
- NOTE 2: TaskCreate for end-of-project cleanup — README/skill should declare preferred style between `JsonRpcId.Num(n)` pattern and `id.toLongOption` extension.
